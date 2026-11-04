// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Cart/CartSender.swift (and CartTransport+App.swift's
// factory), updated to d629aa0 (dc771c8: RemoteFM1 in the app as a target; bd0711a + 41e8471: CartKeep, the one-way
// bank copy to the FM-1); changes: Kotlin, one StateFlow (CartState), suspend functions; a transport whose port the panel's link
// holds (Android: an input port is one client's at a time, CartTransport.sharesPanelPort) gets BANKS and NAME inside a
// pause too, and is told when an operation is over (done()) so it can close the port before the panel starts again.
// Sends DX7 cartridges to an FM-1 running RemoteFM1 0.3.0 or later, which keeps each as a user bank. The firmware
// collects a dump only as the instrument, with no host session, so the app's panel lets go of the FM-1 first (as for a
// firmware install), then ~400 ms, then each dump as one SysEx to the FM-1's port (never the updater's), 1 s between
// dumps (one still being written to the flash drops the next), then the panel starts again. Over Bluetooth MIDI the dump
// goes in 512-byte parts 100 ms apart (its input holds 256 packets); USB takes it whole. 0.3.0 sends no reply. 0.3.1
// (CartBanks.kt) lists its 16 slots (BANKS), takes a dump into a chosen slot (TARGET), deletes one (DELETE), names one
// (NAME) and confirms every dump (STORED, waited for instead of the 1 s); the list is read again before the panel
// starts. No reply to BANKS within 1 s: 0.3.0, the plain send as before.
package com.quixotic7.remotefm1.cart

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** an FM-1 MIDI destination */
data class CartPort(val id: String, val name: String, val bluetooth: Boolean)

/** where the bytes go (Android MIDI on a phone; a fake in the tests and with the fake link) */
interface CartTransport {
    /** the FM-1 candidates now */
    fun ports(): List<CartPort>

    /** a part of a SysEx (the whole dump, or one of its Bluetooth parts), in order */
    suspend fun send(bytes: ByteArray, port: CartPort)

    /** every whole SysEx message heard from the FM-1 (its replies), on any thread */
    fun listen(handler: (ByteArray) -> Unit) {}

    /** the panel's link holds the port while it runs: every request goes while the panel is paused */
    val sharesPanelPort: Boolean get() = false

    /** an operation is over: let go of the port (the panel starts again next) */
    fun done() {}
}

/** the waits (a fake one in the tests records them) */
fun interface CartClock {
    suspend fun sleep(ms: Int)
}

object SystemCartClock : CartClock {
    override suspend fun sleep(ms: Int) = delay(ms.toLong())
}

/** what the sender needs from the app's link (AppModel; a fake in the tests): as PanelLinkPausing, for a cartridge */
interface CartPanelPausing {
    /** ends the panel's session and closes its MIDI ports to the FM-1; returns whether it was running */
    suspend fun pauseForCart(): Boolean
    fun resumeAfterCart(restart: Boolean)
}

/** one picked file: its cartridges, or why it can't be sent */
data class CartFile(val name: String, val carts: List<DX7Cart>, val error: String?, val id: String = UUID.randomUUID().toString()) {
    val fixes: String?
        get() {
            val f = mutableListOf<String>()
            if (carts.any { it.checksumFixed }) f.add("checksum corrected")
            if (carts.any { it.originalChannel != 0 }) f.add("channel set to 1")
            return if (f.isEmpty()) null else f.joinToString(", ")
        }

    companion object {
        fun of(name: String, data: ByteArray): CartFile = try {
            CartFile(name, DX7Cart.parseFile(data), null)
        } catch (e: DX7Cart.ParseError) {
            CartFile(name, emptyList(), e.description)
        }

        fun unreadable(name: String, why: String) = CartFile(name, emptyList(), "The file couldn't be read: $why")
    }
}

/** replies heard on the FM-1's output (any thread) */
internal class CartInbox {
    private val bodies = mutableListOf<ByteArray>()

    @Synchronized
    fun heard(m: ByteArray) {
        val (cmd, b) = SAInfo.decode(m) ?: return
        if (cmd != SAInfo.REPLY || b.isEmpty()) return
        bodies.add(b)
    }

    @Synchronized
    fun drop(op: Int) {
        bodies.removeAll { (it[0].toInt() and 0xFF) == op }
    }

    @Synchronized
    fun take(op: Int): ByteArray? {
        val i = bodies.indexOfFirst { (it[0].toInt() and 0xFF) == op }
        return if (i < 0) null else bodies.removeAt(i)
    }
}

/** everything the cartridge page shows */
data class CartState(
    val files: List<CartFile> = emptyList(),
    val ports: List<CartPort> = emptyList(),
    val chosen: String? = null,
    val state: CartSender.State = CartSender.State.Idle,
    val firmware: CartSender.Firmware = CartSender.Firmware.unknown,
    val banks: BankList? = null,
    /** the slots the last send's dumps went to (STORED) */
    val storedSlots: List<Int> = emptyList(),
    /** the last bank copy's outcome */
    val lastSync: CartSender.SyncReport? = null,
) {
    val carts: List<DX7Cart> get() = files.flatMap { it.carts }
    val busy: Boolean get() = state is CartSender.State.Sending || state is CartSender.State.Working

    /** the port to send to: the only one, or the one picked (null: none, or several and none picked) */
    val target: CartPort? get() = if (ports.size == 1) ports[0] else ports.firstOrNull { it.id == chosen }

    /** the target is RemoteFM1 running in the app (CoreCartTransport), not an FM-1 */
    val inApp: Boolean get() = target?.id == CoreCartTransport.PORT.id

    /** the ports a bank copy works between: RemoteFM1 in the app and an FM-1 (the one chosen, else the first); null:
     *  not both */
    val syncPair: Pair<CartPort, CartPort>?
        get() {
            val a = ports.firstOrNull { it.id == CoreCartTransport.PORT.id } ?: return null
            val devices = ports.filter { it.id != a.id }
            val d = devices.firstOrNull { it.id == chosen } ?: devices.firstOrNull() ?: return null
            return a to d
        }

    val resultText: String?
        get() = when (val s = state) {
            is CartSender.State.Sent ->
                if (storedSlots.isNotEmpty()) {
                    if (s.count == 1) "Stored as USER${storedSlots[0] + 1}."
                    else "Stored as " + storedSlots.joinToString(", ") { "USER${it + 1}" } + "."
                } else {
                    if (s.count == 1) "Sent: the FM-1 should now show the new USER bank."
                    else "Sent ${s.count} cartridges: the FM-1 should now show the last one's USER bank."
                }
            is CartSender.State.Done -> s.text
            is CartSender.State.Failed -> s.text
            else -> null
        }
}

class CartSender(
    private val transport: CartTransport,
    private val panel: CartPanelPausing?,
    private val clock: CartClock = SystemCartClock,
    private val timing: Timing = Timing(),
    val simulated: Boolean,
) {
    data class Timing(
        val afterPause: Int = 400,          // the session's end, before the first byte
        val between: Int = 1000,            // between dumps (0.3.0: the flash write)
        val bluetoothPart: Int = 512,
        val bluetoothGap: Int = 100,
        val reply: Int = 1000,              // BANKS, NAME, TARGET; none at all: 0.3.0
        val delete: Int = 1500,             // DELETE's reply (the directory written; up to ~0.5 s behind a save)
        val stored: Int = 2000,             // STORED after a dump
        val poll: Int = 20,
        val namePoll: Int = 150,            // NAME: BANKS read again this often ...
        val nameSettle: Int = 3000,         // ... until the new name shows, at most this long
    )

    sealed class State {
        data object Idle : State()
        data class Sending(val done: Int, val total: Int) : State()
        /** a replace, a remove, a rename, the list */
        data class Working(val text: String) : State()
        data class Sent(val count: Int) : State()
        data class Done(val text: String) : State()
        data class Failed(val text: String) : State()
    }

    /** what the FM-1 answers to SA_INFO */
    enum class Firmware { unknown, v030, v031 }

    private val _state = MutableStateFlow(CartState())
    val state: StateFlow<CartState> = _state.asStateFlow()
    private val s get() = _state.value
    private val inbox = CartInbox()

    init {
        transport.listen { inbox.heard(it) }
    }

    val busy: Boolean get() = s.busy

    private fun set(st: State) = _state.update { it.copy(state = st) }

    fun add(file: CartFile) {
        if (busy) return
        _state.update {
            val settled = when (it.state) {
                is State.Sent, is State.Failed, is State.Done -> State.Idle
                else -> it.state
            }
            it.copy(files = it.files + file, state = settled)
        }
    }

    fun add(name: String, data: ByteArray) = add(CartFile.of(name, data))

    fun remove(id: String) {
        if (busy) return
        _state.update { it.copy(files = it.files.filter { f -> f.id != id }) }
    }

    fun clear() {
        if (busy) return
        _state.update { it.copy(files = emptyList(), state = State.Idle) }
    }

    // (another target: its banks)
    fun choose(portId: String?) = _state.update {
        if (it.chosen == portId) it else it.copy(chosen = portId, firmware = Firmware.unknown, banks = null)
    }

    fun rescan() {
        val ports = transport.ports()
        _state.update { st -> st.copy(ports = ports, chosen = st.chosen?.takeIf { c -> ports.any { it.id == c } }) }
    }

    private val noPort: String
        get() = if (s.ports.isEmpty()) "No FM-1 found. Connect it with USB (or Bluetooth MIDI) and try again."
        else "Choose which FM-1 to send to."

    // ---- requests

    /** a request, then its reply's body (null after the timeout) */
    private suspend fun ask(body: ByteArray, port: CartPort, timeout: Int): ByteArray? {
        val op = body[0].toInt() and 0xFF
        inbox.drop(op)
        try {
            transport.send(SAInfo.encode(SAInfo.REQUEST, body), port)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        }
        return waitFor(op, timeout)
    }

    private suspend fun waitFor(op: Int, timeout: Int): ByteArray? {
        var t = 0
        while (true) {
            inbox.take(op)?.let { return it }
            if (t >= timeout) return null
            clock.sleep(timing.poll)
            t += timing.poll
        }
    }

    /** BANKS: the list, and so which firmware answers (no reply: 0.3.0) */
    internal suspend fun list(port: CartPort): Boolean {
        val b = ask(byteArrayOf(SAInfo.Op.BANKS.code.toByte()), port, timing.reply)
        val l = b?.let { BankList.of(it) }
        if (l == null) {
            if (s.firmware != Firmware.v031) _state.update { it.copy(firmware = Firmware.v030, banks = null) }
            return false
        }
        _state.update { it.copy(firmware = Firmware.v031, banks = l) }
        return true
    }

    /** asks the FM-1 for its banks (the page's appearance, Refresh) */
    suspend fun refresh() {
        if (busy) return
        rescan()
        val port = s.target ?: return
        set(State.Working("Asking the FM-1 for its banks…"))
        if (transport.sharesPanelPort) paused(port, listAfter = false) { list(port); null } else list(port)
        set(State.Idle)
    }

    /** the panel let go of the FM-1 for [work], then started again; the list read again while still paused */
    private suspend fun paused(port: CartPort, listAfter: Boolean = true, work: suspend () -> String?): String? {
        if (port.id == CoreCartTransport.PORT.id) {             // (RemoteFM1 in the app: no link to let go of)
            val failure = work()
            if (listAfter && s.firmware == Firmware.v031) list(port)
            return failure
        }
        val restart = panel?.pauseForCart() ?: false
        try {
            clock.sleep(timing.afterPause)
            val failure = work()
            if (listAfter && s.firmware == Firmware.v031) list(port)
            return failure
        } finally {
            transport.done()
            panel?.resumeAfterCart(restart)
        }
    }

    /** sends every cartridge, in order, each to the next free slot (0.3.1: each confirmed by STORED) */
    suspend fun send() {
        if (busy) return
        rescan()
        val list = s.carts
        if (list.isEmpty()) { set(State.Failed("No cartridge to send.")); return }
        val port = s.target ?: run { set(State.Failed(noPort)); return }
        val shares = transport.sharesPanelPort
        if (s.firmware == Firmware.unknown && !shares) list(port)
        set(State.Sending(0, list.size))
        _state.update { it.copy(storedSlots = emptyList()) }
        val failure = paused(port) {
            if (s.firmware == Firmware.unknown) list(port)
            var e: String? = null
            for ((i, c) in list.withIndex()) {
                if (i > 0 && s.firmware != Firmware.v031) clock.sleep(timing.between)
                dump(c, port)?.let { e = "Cartridge ${i + 1}: $it" }
                if (e != null) break
                set(State.Sending(i + 1, list.size))
            }
            e
        }
        set(failure?.let { State.Failed(it) } ?: State.Sent(list.size))
    }

    /** one dump; on 0.3.1 the STORED that follows (null: stored, or 0.3.0, which says nothing) */
    private suspend fun dump(c: DX7Cart, port: CartPort): String? {
        inbox.drop(SAInfo.Op.STORED.code)
        try {
            write(c.bytes, port)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return "sending failed: ${e.message ?: e}"
        }
        if (s.firmware != Firmware.v031) return null
        val r = waitFor(SAInfo.Op.STORED.code, timing.stored)
        if (r == null || r.size < 3) return "the FM-1 didn't confirm it. Check its screen."
        SAInfo.words(r[2].toInt() and 0xFF)?.let { return it }
        _state.update { it.copy(storedSlots = it.storedSlots + (r[1].toInt() and 0xFF)) }
        CartKeep.keep(c)                                    // (a copy, for copying the bank elsewhere: CartKeep)
        return null
    }

    // ---- the bank copy (RemoteFM1 in the app to an FM-1)

    data class SyncReport(
        val copied: Map<String, List<String>> = emptyMap(), // target name -> the banks copied there
        val both: Int = 0,                                  // banks already on both
        val noCopy: List<String> = emptyList(),             // banks the app has no copy of (put there another way)
        val full: List<String> = emptyList(),               // banks not copied: the FM-1 has no free slot
        val failed: String? = null,
    )

    /**
     * every bank RemoteFM1 in the app has and the FM-1 doesn't copied to the FM-1 (from the app's kept copies,
     * CartKeep), with its name. One way (the owner: sounds are made in the app, not on the FM-1); the FM-1's own banks
     * are left alone
     */
    suspend fun sync() {
        if (busy) return
        rescan()
        val (a, d) = s.syncPair ?: run { set(State.Failed("Run RemoteFM1 in the app and connect an FM-1 to sync.")); return }
        val back = s.chosen
        set(State.Working("Reading both bank lists…"))
        val la = if (list(a)) s.banks else null
        if (la == null) { set(State.Failed("RemoteFM1 in the app didn't list its banks.")); return }
        var r = SyncReport()
        val failure = paused(d, listAfter = false) {
            val ld = if (list(d)) s.banks else null
            if (ld == null) return@paused "The FM-1 didn't list its banks: it needs RemoteFM1 0.3.1 or later."
            r = r.copy(both = la.banks.count { b -> ld.banks.any { it.crc == b.crc } })
            var dst: BankList = ld
            val copied = mutableListOf<String>()
            for (b in la.banks.filter { b -> dst.banks.none { it.crc == b.crc } }) {
                val c = CartKeep.cart(b.crc)
                if (c == null) { r = r.copy(noCopy = r.noCopy + b.name); continue }
                if (dst.banks.size >= dst.max) { r = r.copy(full = r.full + b.name); continue }
                set(State.Working("Copying ${b.name} to ${d.name}…"))
                _state.update { it.copy(storedSlots = emptyList()) }
                dump(c, d)?.let { r = r.copy(failed = "${b.name} to ${d.name}: $it"); return@paused null }
                val slot = s.storedSlots.firstOrNull()
                val body = slot?.let { nameBody(it, b.name) }
                if (body != null) {
                    ask(body, d, timing.reply)                  // (its name as on the other side)
                    clock.sleep(timing.nameSettle / 6)
                }
                copied += b.name
                if (list(d)) s.banks?.let { dst = it }
            }
            if (copied.isNotEmpty()) r = r.copy(copied = mapOf(d.name to copied))
            null
        }
        if (failure != null) r = r.copy(failed = failure)
        _state.update { it.copy(chosen = back, firmware = Firmware.unknown, lastSync = r) }
        s.target?.let { t -> if (t.id == CoreCartTransport.PORT.id || !transport.sharesPanelPort) list(t) }
        set(r.failed?.let { State.Failed(it) } ?: State.Done(syncText(r)))
    }

    /** replaces the bank in [slot] with the file's cartridge (it must hold exactly one): TARGET, the dump, STORED */
    suspend fun replace(slot: Int, file: CartFile) {
        if (busy) return
        file.error?.let { set(State.Failed(it)); return }
        val c = file.carts.singleOrNull() ?: run {
            set(State.Failed("${file.name} holds ${file.carts.size} cartridges. Choose a file with one to replace a bank."))
            return
        }
        rescan()
        val port = s.target ?: run { set(State.Failed(noPort)); return }
        if (s.firmware != Firmware.v031) { set(State.Failed("This FM-1's firmware can't replace a bank. Update RemoteFM1.")); return }
        set(State.Working("Replacing USER${slot + 1}…"))
        val failure = paused(port) {
            val r = ask(byteArrayOf(SAInfo.Op.TARGET.code.toByte(), slot.toByte()), port, timing.reply)
            if (r == null || r.size < 3) return@paused "The FM-1 didn't answer."
            SAInfo.words(r[2].toInt() and 0xFF)?.let { return@paused it }
            _state.update { it.copy(storedSlots = emptyList()) }
            dump(c, port)
        }
        set(failure?.let { State.Failed(it) } ?: State.Done("USER${slot + 1} replaced with ${file.name}."))
    }

    /** removes the bank in [slot] (DELETE: the reply once it is gone for good) */
    suspend fun delete(slot: Int) {
        if (busy) return
        rescan()
        val port = s.target ?: run { set(State.Failed(noPort)); return }
        set(State.Working("Removing USER${slot + 1}…"))
        val failure = paused(port) {
            val r = ask(byteArrayOf(SAInfo.Op.DELETE.code.toByte(), slot.toByte()), port, timing.delete)
            if (r == null || r.size < 3) "The FM-1 didn't answer." else SAInfo.words(r[2].toInt() and 0xFF)
        }
        set(failure?.let { State.Failed(it) } ?: State.Done("USER${slot + 1} removed."))
    }

    /**
     * names the bank in [slot] (NAME: in a session too, no pause, where the port allows it). The unit answers
     * `01 slot status` at once and writes the directory a moment later (a sector erase: 0.1..0.5 s, BANKS saying busy
     * meanwhile), so the list is read again until it is no longer busy and shows the new name (up to nameSettle);
     * every outcome in plain words with the status the unit gave.
     */
    suspend fun rename(slot: Int, name: String) {
        if (busy) return
        val body = nameBody(slot, name) ?: run { set(State.Failed("Type a name.")); return }
        val want = BankList.name(body, 2, body.size)
        rescan()
        val port = s.target ?: run { set(State.Failed(noPort)); return }
        set(State.Working("Renaming USER${slot + 1}…"))
        val result: State = if (transport.sharesPanelPort) {
            var out: State = State.Idle
            paused(port, listAfter = false) { out = renaming(port, slot, body, want); null }
            out
        } else renaming(port, slot, body, want)
        set(result)
    }

    private suspend fun renaming(port: CartPort, slot: Int, body: ByteArray, want: String): State {
        val r = ask(body, port, timing.reply)
        if (r == null || r.size < 3) return State.Failed("Rename: the FM-1 didn't answer NAME (no reply in ${timing.reply} ms).")
        val st = r[2].toInt() and 0xFF
        SAInfo.words(st)?.let { return State.Failed("Rename: $it (status $st)") }
        var t = 0
        var seen: String? = null
        while (true) {
            clock.sleep(timing.namePoll)
            t += timing.namePoll
            if (list(port)) {
                val l = s.banks
                if (l != null) {
                    seen = l.banks.firstOrNull { it.slot == slot }?.name
                    if (!l.busy && seen == want) return State.Done("USER${slot + 1} renamed $want.")
                }
            }
            if (t >= timing.nameSettle) break
        }
        return State.Failed("Rename: the FM-1 said yes (status 0) but still lists USER${slot + 1} as " +
            (seen?.let { "\"$it\"" } ?: "nothing") + " after ${timing.nameSettle} ms. Refresh in a moment.")
    }

    private suspend fun write(bytes: ByteArray, port: CartPort) {
        if (!port.bluetooth) return transport.send(bytes, port)
        var o = 0
        while (o < bytes.size) {
            if (o > 0) clock.sleep(timing.bluetoothGap)
            val e = minOf(o + timing.bluetoothPart, bytes.size)
            transport.send(bytes.copyOfRange(o, e), port)
            o = e
        }
    }

    companion object {
        fun syncText(r: SyncReport): String {
            val s = mutableListOf<String>()
            for ((to, names) in r.copied.toSortedMap()) {
                s += "Copied ${names.size} bank${if (names.size == 1) "" else "s"} to $to: ${names.joinToString(", ")}."
            }
            if (s.isEmpty() && r.noCopy.isEmpty() && r.full.isEmpty()) s += "Both have the same banks."
            if (r.both > 0) s += "${r.both} already on both."
            if (r.noCopy.isNotEmpty()) s += "Not copied, the app has no copy of: ${r.noCopy.joinToString(", ")}. Send their " +
                ".syx files once to RemoteFM1 in this app from this page and they can be copied."
            if (r.full.isNotEmpty()) s += "No free slot for: ${r.full.joinToString(", ")}."
            return s.joinToString(" ")
        }

        /** NAME's request body for [name] (1..10 printable ASCII; anything else a space, as the firmware does; null: blank) */
        fun nameBody(slot: Int, name: String): ByteArray? {
            val n = name.toByteArray(Charsets.UTF_8).map { val c = it.toInt() and 0xFF; if (c in 0x20..0x7E) c else 0x20 }.take(10)
            if (n.none { it != 0x20 }) return null
            return byteArrayOf(SAInfo.Op.NAME.code.toByte(), slot.toByte()) + ByteArray(n.size) { n[it].toByte() }
        }
    }
}

/** the fake FM-1 port (the fake link, demos, tests): keeps what it was sent; with a FakeFM1, which answers as 0.3.1,
 *  else silent as 0.3.0 */
class FakeCartTransport(
    private val list: List<CartPort> = listOf(CartPort("fake", "FM-1 (simulated)", false)),
    val fm1: FakeFM1? = null,
    override val sharesPanelPort: Boolean = false,
) : CartTransport {
    private val lock = Any()
    private val _sent = mutableListOf<Pair<String, ByteArray>>()
    @Volatile var onSend: ((ByteArray) -> Unit)? = null
    @Volatile private var heard: ((ByteArray) -> Unit)? = null
    @Volatile var doneCalls = 0
        private set

    override fun listen(handler: (ByteArray) -> Unit) {
        heard = handler
    }

    override fun ports(): List<CartPort> = list

    override suspend fun send(bytes: ByteArray, port: CartPort) {
        synchronized(lock) { _sent.add(port.id to bytes) }
        onSend?.invoke(bytes)
        val h = heard
        if (fm1 != null && h != null) for (m in fm1.receive(bytes)) h(m)
    }

    override fun done() {
        doneCalls++
    }

    val sent: List<Pair<String, ByteArray>> get() = synchronized(lock) { _sent.toList() }
}
