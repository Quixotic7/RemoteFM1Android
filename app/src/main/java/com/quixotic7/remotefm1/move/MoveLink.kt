// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Connect my Ableton Move: a Move running Move Remote (github.com/Quixotic7/abletonmoveremote) as the FM-1's controller.
// The C++ (native/rfmcore/MoveLink.cpp) runs rfm1cs-abletonmove's session and its rfm_move core on the engine's core
// thread and the socket on its own thread (the session connects again while wanted: 1, 2, 4 s); this class
// is the app's side, as iOS's MoveController and the web player's MoveLink (RemoteFM1 18c6a82 web/site/js/move-link.js):
//   - Connect / Disconnect, the address (move.local asked of the network itself: MoveMdns), connecting by itself at
//     the next start (moveAuto), the status for the page
//   - the core's requests answered on a poll thread: its settings into the preferences, its 32 projects as files
//     (MoveProjects: save, load, delete, cut, copy, paste, colour), Sample's takes as WAV files (MoveTakes, Android's)
//   - (rfm1cs-abletonmove 0.5.10) the Move's firmware browser on one firmware and no FX (the core made with
//     rfm_move_fw_caps 0): the app's catalog as its list (MoveCatalog, through [firmware]: sent again when the
//     catalog, the install states or the thumbnails change, built on its own thread), what plays now as slot 0, a jog
//     press's load (the app's own pick), unload, a download through Get firmware's install (its progress on the Move),
//     the library blob kept in files/move/library.bin (MoveLibrary) and given back at start
//   - (0.5.11, as the web page) Fantasy CFWs listed with virt (their badge, step 16's filter); the firmware's live
//     labels and button names to the core on a load (the native side: MoveLink.cpp coreTick), its readouts forgotten
//     on a load or unload; the app's volume as slot 0's level (pageLevel, also once at start) and the Move's level as
//     the app's volume ([MoveFirmwareHost.level]), never played back
// The core is made at the first Connect and stays on the engine for the app's life (the sequencer and the projects
// with it); a disconnect lets go of everything the Move held and saves the project.
package com.quixotic7.remotefm1.move

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

class MoveLink(
    /** puts the core on the engine's core thread (RfmEngine.moveAttach) */
    private val attach: (Long) -> Unit,
    private val settings: MoveSettings,
    val projects: MoveProjects,
    val takes: MoveTakes,
    /** the Move's library blob (files/move/library.bin) */
    private val library: MoveLibrary,
    /** move.local and other .local names: an IPv4 address from the network (MoveMdns under a multicast lock), null */
    private val mdns: (String) -> String?,
    /** runs on the main thread (the state below is Compose state) */
    private val main: (() -> Unit) -> Unit,
) {
    enum class Tone { OFF, BUSY, ON, BAD }

    var tone by mutableStateOf(Tone.OFF)
        private set
    var detail by mutableStateOf("Not connected.")
        private set
    var want by mutableStateOf(false)
        private set
    var live by mutableStateOf(false)
        private set
    var address by mutableStateOf(settings.address)
        private set
    /** the sequencer and the Move, while live */
    var info by mutableStateOf(Info())
        private set
    var projectList by mutableStateOf<List<MoveProject>>(emptyList())
        private set
    var takeList by mutableStateOf<List<File>>(emptyList())
        private set

    data class Info(val name: String = "", val via: String = "", val battery: Int = -1, val clock: String = "",
                    val bpm10: Int = 0, val playing: Boolean = false, val project: Int = -1, val mode: String = "",
                    val ticking: Boolean = true)

    var onToast: ((String, Boolean) -> Unit)? = null
    var log: ((String) -> Unit)? = null
    val buttonTitle: String get() = if (want && tone != Tone.BAD) "Disconnect" else if (tone == Tone.BAD) "Try again" else "Connect my Move"

    /** the app's firmware for the Move's browser (AppModel sets it); null: an empty list */
    @Volatile var firmware: MoveFirmwareHost? = null

    @Volatile private var handle = 0L
    // the firmware browser: the list the core holds, what slot 0 was said to hold, the catalog's build in flight
    @Volatile private var fwShown: List<MoveFw> = emptyList()
    @Volatile private var fwSlot: String? = null
    @Volatile private var fwSlotSent = false
    @Volatile private var fwStamp: Any? = null
    @Volatile private var fwLoading = false         // (a load from the Move under way: slot 0 is its answer's)
    @Volatile private var levelSent = false         // (the app's volume given as the level once)
    private val fwBuilding = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var gen = 0                    // (a Connect started after another: the older resolve is dropped)
    private val lock = Object()
    private var projBuf: ByteArray? = null          // projects mode's cut / copy (the app's buffer)

    /** the native core (0 before the first Connect) */
    val nativeHandle: Long get() = handle

    private fun toast(t: String, ok: Boolean = false) = main { onToast?.invoke(t, ok) }

    // ---------------------------------------------------------------- connecting
    fun toggle(typed: String? = null) { if (want && tone != Tone.BAD) disconnect() else connect(typed) }

    /** from the button (the address field's text), or by itself at start where it was connected before */
    fun connect(typed: String? = null) {
        if (typed != null) {
            val a = MoveAddress.parse(typed)
            if (a == null) {
                tone = Tone.BAD
                detail = "\"$typed\" is not an address: move.local, an IP address, or ${MoveAddress.USB_ADDR}."
                return
            }
            settings.address = a
            address = a
        }
        want = true
        settings.auto = true
        ensureCore()
        tone = Tone.BUSY
        detail = "Connecting to ${address.text}..."
        val a = address
        val g = ++gen
        val h = handle
        Thread({
            var host = a.host
            if (a.isMdns) (try { mdns(a.host) } catch (_: Exception) { null })?.let { host = it }
            if (g != gen) return@Thread
            // (rfm1cs_tcp_connect splits "host:port" / "[v6]:port"; the texts name the address as typed)
            MoveNative.start(h, MoveAddress(host, a.port).text, a.text)
        }, "RFM Move resolve").apply { isDaemon = true; start() }
    }

    fun auto() { if (settings.auto && !want) connect() }

    /** (tests) the core made and attached, as by a Connect, without the network: its handle */
    internal fun testCore(): Long { ensureCore(); return handle }

    fun disconnect() {
        want = false
        gen++
        settings.auto = false
        if (handle != 0L) MoveNative.stop(handle)
        tone = Tone.OFF
        detail = "Not connected."
    }

    /** the core and its projects (once): the settings from the preferences, the stored slots, the last project */
    private fun ensureCore() {
        synchronized(lock) {
            if (handle != 0L) return
            val h = MoveNative.create(settings.image, library.read())
            handle = h
            attach(h)
            val list = projects.list()
            for (p in list) MoveNative.projectInfo(h, p.slot, p.bytes)
            val last = settings.project
            MoveNative.projectsInit(h, MoveProjects.dirBits(list), last, list.firstOrNull { it.slot == last }?.bytes)
            projectList = list
            Thread({ pollLoop(h) }, "RFM Move poll").apply { isDaemon = true; start() }
        }
        refreshTakes()
    }

    // ---------------------------------------------------------------- the poll thread: requests, Sample, the status
    private fun pollLoop(h: Long) {
        val out = IntArray(4)
        val data = ByteArray(MoveNative.projectMax)
        val pcm = ShortArray(2 * 8192)
        var lastStatus = 0L
        var was = -1
        var lastSlot = -2
        while (true) {
            if (takes.recording) drainAudio(h, pcm)
            while (true) {
                val n = MoveNative.takeRequest(h, out, data)
                if (n < 0) break
                try { request(h, out[0], out[1], out[2], out[3], data.copyOf(n), pcm) } catch (e: Exception) {
                    log?.invoke("move: request ${out[0]} failed: $e")
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastStatus >= 200) fwSync(h)
            if (now - lastStatus >= 200) {
                lastStatus = now
                val st = MoveNative.status(h)
                was = status(st, was)
                if (st.state == MoveNative.CONNECTED && st.project != lastSlot && MoveProjects.validSlot(st.project)) {
                    lastSlot = st.project
                    settings.project = st.project
                }
            }
            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return }
        }
    }

    // ---------------------------------------------------------------- the firmware browser
    /** the list sent again when the app's catalog changed (built on its own thread: the thumbnails), and slot 0 when
     *  what plays changed (a pick in the app, a load from the Move, an unload) */
    private fun fwSync(h: Long) {
        val host = firmware ?: return
        if (!levelSent) {                               // (once at start: the app's volume is the slot's level)
            levelSent = true
            runCatching { host.volume() }.getOrNull()?.let { MoveNative.fwSetLevel(h, 0, levelOf(it)) }
        }
        val stamp = runCatching { host.stamp() }.getOrNull()
        if (stamp != fwStamp && fwBuilding.compareAndSet(false, true)) {
            Thread({
                try {
                    val l = host.catalog()
                    fwShown = l
                    MoveNative.fwList(h, l)
                    fwStamp = stamp
                    fwSlotSent = false              // (slot 0 said again with the new list)
                    log?.invoke("move: catalog of ${l.size} firmware (${l.count { it.installed != null }} here)")
                } catch (e: Exception) {
                    fwStamp = stamp
                    log?.invoke("move: catalog failed: $e")
                } finally {
                    fwBuilding.set(false)
                }
            }, "RFM Move catalog").apply { isDaemon = true; start() }
        }
        if (fwLoading) return
        val r = runCatching { host.running() }.getOrNull()
        if (!fwSlotSent || r != fwSlot) {
            fwSlot = r
            fwSlotSent = true
            MoveNative.fwSlot(h, r)
        }
    }

    private fun fwEntry(id: String): MoveFw? = fwShown.firstOrNull { it.id == id }

    /** the Move's FW_* requests: load (a jog press), unload, download, the level, the library */
    private fun fwRequest(h: Long, kind: Int, value: Int, data: ByteArray) {
        val id = String(data, Charsets.US_ASCII).trimEnd('\u0000')
        val host = firmware
        when (kind) {
            MoveNative.REQ_FW_LOAD -> {
                val key = fwEntry(id)?.key
                if (host == null || key == null) { MoveNative.fwProgress(h, id, MoveNative.FW_FAILED); return }
                log?.invoke("move: load $id ($key)")
                MoveNative.fwProgress(h, id, 0)
                fwLoading = true
                host.load(key) { err ->
                    fwLoading = false
                    if (err == null) {
                        MoveNative.fwProgress(h, id, 100)
                        fwSlot = id
                        fwSlotSent = true
                        MoveNative.fwSlot(h, id)
                    } else {
                        log?.invoke("move: load $id failed: $err")
                        MoveNative.fwProgress(h, id, MoveNative.FW_FAILED)
                        toast(err)
                    }
                }
            }
            MoveNative.REQ_FW_UNLOAD -> {
                if (host == null) { MoveNative.fwSlot(h, null); return }
                host.unload {
                    fwSlot = null
                    fwSlotSent = true
                    MoveNative.fwSlot(h, null)
                }
            }
            MoveNative.REQ_FW_DOWNLOAD -> {
                val e = fwEntry(id)?.download
                if (host == null || e == null) { MoveNative.fwProgress(h, id, MoveNative.FW_FAILED); return }
                log?.invoke("move: download $id ${e.version}")
                var last = -1
                host.download(e, { p -> if (p != last) { last = p; MoveNative.fwProgress(h, id, p.coerceIn(0, 99)) } }) { v, busy ->
                    if (v != null) {
                        MoveNative.fwProgress(h, id, 100)
                        MoveNative.fwInstalled(h, id, v)
                    } else {
                        MoveNative.fwProgress(h, id, if (busy) MoveNative.FW_BUSY else MoveNative.FW_FAILED)
                    }
                }
            }
            MoveNative.REQ_FW_LIBRARY -> if (!library.write(data)) log?.invoke("move: the library (${data.size} bytes) not kept")
            MoveNative.REQ_FW_LEVEL -> {                 // (0.5.11) the app's volume follows (the web's level())
                settings.level = value
                host?.level(value)
            }
        }
    }

    /** (0.5.11) the app's volume (0..1) as slot 0's level (100 = full: the scale [MoveFirmwareHost.level] uses), as
     *  the Move's own edit (the library keeps it); no level comes back from it (the web page's pageLevel) */
    fun pageLevel(volume: Float) {
        val h = handle
        if (h == 0L || firmware == null) return
        MoveNative.fwSetLevel(h, 0, levelOf(volume))
    }

    /** the native status onto the page; returns the state */
    private fun status(st: MoveNative.Status, was: Int): Int {
        val on = st.state == MoveNative.CONNECTED
        val via = VIA.getOrElse(st.via) { "" }
        val name = st.name.ifEmpty { "The Move" }
        if (st.state != was) {
            if (on) toast("$name is the FM-1's controller now.", true)
            else if (was == MoveNative.CONNECTED && st.state == MoveNative.RETRYING && st.toast.isNotEmpty()) toast(st.toast)
            log?.invoke("move: ${st.state} ${st.text}")
        }
        // the line and its tone are rfm1cs_status_text's (the web player's and iOS's words)
        val t = when (st.tone) {
            MoveNative.TONE_ON -> Tone.ON
            MoveNative.TONE_BUSY -> Tone.BUSY
            MoveNative.TONE_BAD -> Tone.BAD
            else -> Tone.OFF
        }
        val mode = MODES.getOrElse(st.mode) { "" }
        val d = when {
            on && !st.ticking -> "${st.text} No firmware is running: the Move waits for one."
            st.state == MoveNative.OFF && !want -> "Not connected."
            else -> st.text.ifEmpty { "Not connected." }
        }
        val i = if (!on) Info() else Info(st.name, via, st.battery, if (st.following) "firmware" else "INT", st.bpm10,
            st.playing, st.project, mode, st.ticking)
        main {
            val left = st.state == MoveNative.OFF && want && was == MoveNative.CONNECTED   // (BYE: it left; its words)
            if (left) want = false
            if (want || left || st.state != MoveNative.OFF) { tone = t; detail = d }
            live = on
            if (i != info) info = i
        }
        return st.state
    }

    /** Sample's audio into the take; [until]: the take's frames (its end: what comes past it is dropped) */
    private fun drainAudio(h: Long, pcm: ShortArray, until: Long = -1) {
        val stop = System.currentTimeMillis() + 2000
        while (true) {
            val n = MoveNative.takeAudio(h, pcm)
            val keep = if (until < 0) n else minOf(n.toLong(), maxOf(0L, until - takes.frames)).toInt()
            if (keep > 0) takes.write(pcm, keep)
            if (until < 0) { if (n == 0) return else continue }
            if (takes.frames >= until) { while (MoveNative.takeAudio(h, pcm) > 0) {}; return }
            if (n == 0) { if (System.currentTimeMillis() > stop) return; Thread.sleep(2) }
        }
    }

    private fun request(h: Long, kind: Int, slot: Int, value: Int, total: Int, data: ByteArray, pcm: ShortArray) {
        when (kind) {
            MoveNative.REQ_SETTINGS -> settings.keepImage(data)
            MoveNative.REQ_PROJECT_SAVE -> { projects.put(slot, data); afterChange(h) }
            MoveNative.REQ_PROJECT_LOAD -> MoveNative.projectAnswer(h, slot, projects.get(slot)?.bytes)
            MoveNative.REQ_PROJECT_DELETE -> { projects.remove(slot); afterChange(h) }
            MoveNative.REQ_PROJECT_CUT, MoveNative.REQ_PROJECT_COPY -> {
                projects.get(slot)?.let { projBuf = it.bytes }
                if (kind == MoveNative.REQ_PROJECT_CUT) { projects.remove(slot); afterChange(h) }
            }
            MoveNative.REQ_PROJECT_PASTE -> projBuf?.let { b ->
                projects.put(slot, b)
                MoveNative.projectInfo(h, slot, b)
                afterChange(h)
            }
            MoveNative.REQ_PROJECT_COLOR -> projects.get(slot)?.let { p ->
                val b = MoveProjects.withColor(p.bytes, value)
                projects.put(slot, b)
                MoveNative.projectInfo(h, slot, b)
                refreshProjects()
            }
            MoveNative.REQ_FW_LOAD, MoveNative.REQ_FW_UNLOAD, MoveNative.REQ_FW_DOWNLOAD, MoveNative.REQ_FW_LIBRARY,
            MoveNative.REQ_FW_LEVEL -> fwRequest(h, kind, value, data)
            MoveNative.REQ_SAMPLE_START -> takes.start(value)
            MoveNative.REQ_SAMPLE_STOP -> {
                drainAudio(h, pcm, total.toLong())
                val f = takes.finish(slot, value)
                refreshTakes()
                if (f != null) toast("Sample: ${f.name} (Settings › Ableton Move).", true)
            }
        }
    }

    private fun afterChange(h: Long) {
        MoveNative.projectDir(h, projects.dirBits())
        refreshProjects()
    }

    fun refreshProjects() { val l = projects.list(); main { projectList = l } }
    fun refreshTakes() { val l = takes.list(); main { takeList = l } }

    // ---------------------------------------------------------------- the page's project buttons
    /** Backup: one project's file (slot) or every project's (null); its name and bytes, null when there is none */
    fun backup(slot: Int?): Pair<String, ByteArray>? {
        val all = projects.list()
        val pick = if (slot == null) all else all.filter { it.slot == slot }
        if (pick.isEmpty()) return null
        return (if (slot == null) MoveProjects.SET_NAME else MoveProjects.fileName(slot)) to
            projects.codec.pack(pick, if (slot == null) MoveProjects.KIND_SET else MoveProjects.KIND_ONE)
    }

    /** Import a file: a single project into [slot] (or the first free one: -1), a set into its own slots; checked by
     *  the core first, nothing stored unless all are good. Returns the slots written; throws saying why not */
    fun importFile(bytes: ByteArray, slot: Int = -1): List<Int> {
        val f = projects.codec.unpack(bytes)
        var todo = f.entries
        if (f.kind == MoveProjects.KIND_ONE) {
            val to = if (MoveProjects.validSlot(slot)) slot else MoveProjects.firstFree(projects.dirBits())
            require(to >= 0) { "All 32 project slots are used: pick one to replace, or delete one first." }
            todo = listOf(MoveProject(to, f.entries[0].bytes, System.currentTimeMillis()))
        }
        for (e in todo) projects.put(e.slot, e.bytes)
        val h = handle
        if (h != 0L) {
            for (e in todo) MoveNative.projectInfo(h, e.slot, e.bytes)
            val cur = MoveNative.status(h).project
            todo.firstOrNull { it.slot == cur }?.let { MoveNative.projectAnswer(h, cur, it.bytes) }
            afterChange(h)
        } else refreshProjects()
        return todo.map { it.slot }
    }

    fun deleteProject(slot: Int) {
        projects.remove(slot)
        val h = handle
        if (h != 0L) {
            if (MoveNative.status(h).project == slot) MoveNative.projectAnswer(h, slot, null)
            afterChange(h)
        } else refreshProjects()
    }

    fun deleteTake(f: File) { f.delete(); refreshTakes() }

    /** the app going to the background or away: the open project saved */
    fun flush() { if (handle != 0L) MoveNative.projectFlush(handle) }

    companion object {
        const val POLL_MS = 15L
        /** the web page's scales: volume 0..1 -> level round(v x 100) (0..127), level -> volume min(1, l / 100) */
        fun levelOf(volume: Float): Int = Math.round(volume * 100f).coerceIn(0, 127)
        fun volumeOf(level: Int): Float = (level.coerceAtLeast(0) / 100f).coerceAtMost(1f)
        val VIA = listOf("Wi-Fi", "USB", "this computer")       // MR_VIA_*
        val MODES = listOf("FM-1", "MIDI", "Control", "Seq")    // rfm_move_mode_name
        const val LEAVE = "Shift + touch the volume knob + click the jog wheel on the Move to leave Move Remote."
    }
}
