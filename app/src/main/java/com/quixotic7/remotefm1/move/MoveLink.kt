// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Connect my Ableton Move: a Move running Move Remote (github.com/Quixotic7/abletonmoveremote) as the FM-1's controller.
// The C++ (native/rfmcore/MoveLink.cpp, ported from RemoteFM1IOS ad33e95) runs RemoteFM1's rfm_move core on the
// engine's core thread and Move Remote's client on its own network thread (connecting again while wanted); this class
// is the app's side, as iOS's MoveController and the web player's MoveLink (RemoteFM1 18c6a82 web/site/js/move-link.js):
//   - Connect / Disconnect, the address (move.local asked of the network itself: MoveMdns), connecting by itself at
//     the next start (moveAuto), the status for the page
//   - the core's requests answered on a poll thread: its settings into the preferences, its 32 projects as files
//     (MoveProjects: save, load, delete, cut, copy, paste, colour), Sample's takes as WAV files (MoveTakes, Android's)
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

    @Volatile private var handle = 0L
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
            // ("address:port" as MoveLink.cpp splits it; an IPv6 address goes alone, on the default port)
            MoveNative.start(h, if (a.port != MoveAddress.PORT && !host.contains(':')) "$host:${a.port}" else host)
        }, "RFM Move resolve").apply { isDaemon = true; start() }
    }

    fun auto() { if (settings.auto && !want) connect() }

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
            val h = MoveNative.create(settings.image)
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

    /** the native status onto the page; returns the state */
    private fun status(st: MoveNative.Status, was: Int): Int {
        val on = st.state == MoveNative.CONNECTED
        val via = VIA.getOrElse(st.via) { "" }
        val name = st.name.ifEmpty { "The Move" }
        if (st.state != was) {
            if (on) toast("$name is the FM-1's controller now.", true)
            else if (was == MoveNative.CONNECTED && st.state == MoveNative.RETRYING) toast("$name: ${st.text}")
            log?.invoke("move: ${st.state} ${st.text}")
        }
        val t = when (st.state) {
            MoveNative.CONNECTED -> Tone.ON
            MoveNative.CONNECTING, MoveNative.RETRYING -> Tone.BUSY
            MoveNative.ERROR -> Tone.BAD
            else -> Tone.OFF
        }
        val mode = MODES.getOrElse(st.mode) { "" }
        val d = when {
            on && !st.ticking -> "${st.text}. No firmware is running: the Move waits for one."
            on -> "${st.text}: $mode mode. Track 1..4 pick the mode."
            st.state == MoveNative.OFF && !want -> "Not connected."
            st.state == MoveNative.OFF -> st.text.ifEmpty { "Not connected." }.let { if (it == "Off") "Not connected." else it }
            else -> st.text
        }
        val i = if (!on) Info() else Info(st.name, via, st.battery, if (st.following) "firmware" else "INT", st.bpm10,
            st.playing, st.project, mode, st.ticking)
        main {
            if (st.state == MoveNative.OFF && want && was == MoveNative.CONNECTED) want = false   // (BYE: it left)
            if (want || st.state != MoveNative.OFF) { tone = t; detail = d }
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
            MoveProjects.packFile(pick, if (slot == null) MoveProjects.KIND_SET else MoveProjects.KIND_ONE)
    }

    /** Import a file: a single project into [slot] (or the first free one: -1), a set into its own slots; checked by
     *  the core first, nothing stored unless all are good. Returns the slots written; throws saying why not */
    fun importFile(bytes: ByteArray, slot: Int = -1): List<Int> {
        val f = MoveProjects.unpackFile(bytes) { MoveNative.projectCheck(it) }
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
        val VIA = listOf("Wi-Fi", "USB", "this computer")       // MR_VIA_*
        val MODES = listOf("FM-1", "MIDI", "Control", "Seq")    // rfm_move_mode_name
        const val LEAVE = "Shift + touch the volume knob + click the jog wheel on the Move to leave Move Remote."
    }
}
