// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/OMX27/OmxLink.swift
// Connect my OMX-27: the OMX-27 in REMOTE mode as the FM-1's controller (OmxWire the wire, OmxViews the views,
// OmxScreen its screen). The website's js/omx-link.js, ported as is. The ports whose names say OMX; the MODE command
// puts it in REMOTE mode, and while it is there its keys, encoder and pots drive the panel (an OmxTarget) and the app
// lights its 27 keys and draws its 128 x 32 screen. Connected once, it connects by itself at the next start (auto())
// and when it is plugged in. Disconnect clears its LEDs and screen; the OMX can't be told to leave REMOTE mode: AUX +
// encoder hold on it does. While it is in REMOTE mode the app's MIDI input skips its port (owns()). The main thread.
package com.quixotic7.remotefm1.omx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.quixotic7.remotefm1.ui.faceplate.PanelKnob
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel

/** the panel the OMX-27 plays (the app's PanelFeed, through PanelOmxTarget) */
interface OmxTarget {
    /** the FM-1's key 0..26 */
    fun omxKey(k: Int, down: Boolean)
    fun omxButton(l: PanelLabel, down: Boolean)
    fun omxTurn(k: PanelKnob, detents: Int)
    /** the encoder now turns this knob (the UI may show it) */
    fun omxSelect(k: PanelKnob)
    /** MASTER, 0..1023 */
    fun omxMaster(v: Int)
    /** K5 away from MASTER (pot, at: 0..1023; first: once per wait): MASTER waits for it */
    fun omxPickup(pot: Int, at: Int, first: Boolean)
    /** the Keyboard view: one channel message to the firmware's MIDI in */
    fun omxFirmwareMidi(bytes: ByteArray)
    /** MASTER now, 0..1023 */
    val omxMasterNow: Int
    /** the FM-1 key k's LED, 0..255 */
    fun omxKeyLevel(k: Int): Int
    /** everything the OMX holds let go */
    fun omxReleaseAll()
}

/** where the view prefs and the auto flag live (SharedPreferences in the app; a map in the tests) */
interface OmxPrefs {
    fun string(key: String): String?
    fun setString(key: String, v: String)
    fun bool(key: String): Boolean
    fun setBool(key: String, v: Boolean)
}

class OmxLink(
    var target: OmxTarget? = null,
    private val transport: OmxTransport,
    private val clock: OmxClock,
    private val prefs: OmxPrefs,
    /** the screen packed for the OMX (OmxScreen.render; the JVM tests give their own) */
    private val render: (OmxViews.Screen) -> ByteArray,
) {
    enum class Tone { OFF, BUSY, ON, BAD }

    // the status, for the UI
    var tone by mutableStateOf(Tone.OFF)
        private set
    var detail by mutableStateOf("Not connected.")
        private set
    /** Connect was asked for (and not Disconnect since) */
    var want by mutableStateOf(false)
        private set
    /** in REMOTE mode as the app sees it (52 03 + 52 02 at once: still) */
    var active by mutableStateOf(false)
        private set
    /** the OMX's port name ("" none) */
    val port: String get() = pair?.name ?: ""
    /** the button's title: Connect, Disconnect, Try again */
    val buttonTitle: String get() = if (want && tone != Tone.BAD) "Disconnect" else if (tone == Tone.BAD) "Try again" else "Connect my OMX-27"

    /** a short message to show for a moment (ok: a success) */
    var onToast: ((String, Boolean) -> Unit)? = null
    /** REMOTE mode began or ended (the MIDI input picks its sources again) */
    var onActive: ((Boolean) -> Unit)? = null

    val views = OmxViews(OmxViewPrefs.fromJson(prefs.string(PREFS_KEY)))
    private var started = false
    var pair: OmxPair? = null
        private set
    var session: OmxSession? = null
        private set
    private var timer: OmxTimer? = null
    private var answerTimer: OmxTimer? = null
    private var offTimer: OmxTimer? = null
    private var ownTurn = false
    private var shown = ""
    private val pots = LinkedHashMap<Int, Pair<Int, Int>>()     // pot -> its latest message, not taken yet

    /** what the link does, for the log (AppMidi: Android's log) */
    var log: ((String) -> Unit)? = null

    private fun set(t: Tone, d: String) {
        if (d != detail) log?.invoke("$t: $d")
        if (tone != t) tone = t
        if (detail != d) detail = d
    }

    private var autoFlag: Boolean
        get() = prefs.bool(AUTO_KEY)
        set(v) = prefs.setBool(AUTO_KEY, v)

    /** a port the app's MIDI input leaves alone (REMOTE mode: its events are ours) */
    fun owns(id: Int, name: String? = null): Boolean {
        val p = pair ?: return false
        if (!active) return false
        return id == p.input.id || (name != null && name == p.input.name)
    }

    /** a device the app's MIDI input leaves alone (by its MidiDeviceInfo id or its name) */
    fun ownsDevice(device: Int, name: String?): Boolean {
        val p = pair ?: return false
        if (!active) return false
        return device == p.input.device || (name != null && name == p.input.name)
    }

    /** the button: Connect (or Try again), or Disconnect */
    fun toggle() { if (want && tone != Tone.BAD) disconnect() else connect() }

    fun connect() {
        want = true
        if (!autoFlag) autoFlag = true
        val p = pair
        val s = session
        if (p != null && s != null) {
            s.enter()
            set(Tone.BUSY, "Asking ${p.name} for remote mode...")
            expectAnswer()
            return
        }
        if (!started) {
            val why = transport.start { scan() }
            if (why != null) {
                want = false
                set(Tone.BAD, why)
                return
            }
            started = true
        }
        scan()
    }

    /** at the app's start: connects by itself when it was connected last time */
    fun auto() { if (autoFlag && !want) connect() }

    fun disconnect() {
        want = false
        autoFlag = false
        val was = active
        session?.clear()
        drop()
        set(Tone.OFF, if (was) "Disconnected. The OMX-27 stays in remote mode: ${LEAVE.take(1).lowercase() + LEAVE.drop(1)}"
                      else "Not connected.")
    }

    /** the app going away: the OMX's LEDs and screen cleared (it stays in REMOTE mode) */
    fun willClose() { session?.let { if (it.active) it.clear() } }

    /** the ports now: the OMX's pair attached, or let go when it went */
    fun scan() {
        if (!want || !started) return
        val (ins, outs) = transport.ports()
        val p = omxPair(ins, outs)
        val cur = pair
        log?.invoke("scan: ${ins.size} in, ${outs.size} out, pair ${p?.name}, attached ${cur?.name}")
        if (cur != null && (p == null || p.input.id != cur.input.id || p.output.id != cur.output.id)) {
            val name = cur.name
            drop()
            set(Tone.BUSY, "$name unplugged: it connects again when it is back.")
            if (p == null) return
        }
        if (pair == null && p != null) attach(p)
        else if (pair == null) set(Tone.BUSY, "Plug in your OMX-27: it connects when it appears.")
    }

    private fun attach(p: OmxPair) {
        pair = p
        transport.open(p.input, p.output) { d -> message(d) }
        val s = OmxSession(send = { b, d -> transport.send(b, d) }, now = { clock.now })
        session = s
        shown = ""
        s.enter()
        set(Tone.BUSY, "Asking ${p.name} for remote mode...")
        expectAnswer()
        timer?.cancel()
        timer = clock.every(TICK_MS) { tick() }
    }

    private fun expectAnswer() {
        answerTimer?.cancel()
        answerTimer = clock.after(ANSWER_MS) {
            val s = session ?: return@after
            val p = pair ?: return@after
            if (s.active || !want) return@after
            val why = transport.problem
            set(Tone.BAD, if (why != null) "${p.name}: $why Connect asks again."
                          else "${p.name} did not answer: it needs the OMX-27 firmware with REMOTE mode. Connect asks again.")
        }
    }

    private fun drop() {
        timer?.cancel(); timer = null
        answerTimer?.cancel(); answerTimer = null
        offTimer?.cancel(); offTimer = null
        pots.clear()
        val was = active
        active = false
        release()
        transport.close()
        pair = null
        session = null
        if (!want && started) { transport.stop(); started = false }
        if (was) changed()
    }

    private fun release() {
        apply(views.releaseAll())
        target?.omxReleaseAll()
    }

    private fun changed() { onActive?.invoke(active) }

    // ---- the OMX's messages

    fun message(d: ByteArray) {
        val s = session ?: return
        val m = s.feed(d) ?: return
        val now = clock.now
        if (m is OmxWire.Message.Status) {
            if (m.s == OmxWire.STATUS_ON) {
                answerTimer?.cancel(); answerTimer = null
                shown = ""
                // (MODE while already in REMOTE mode: the OMX answers 52 03 and 52 02 within a few ms: still connected)
                offTimer?.cancel(); offTimer = null
                val name = pair?.name ?: "The OMX-27"
                set(Tone.ON, "$name in remote mode: ${views.view.title} view. $LEAVE")
                if (!active) {
                    active = true
                    onToast?.invoke("$name in remote mode: its keys, knobs and screen play the FM-1.", true)
                    changed()
                }
                refresh()
            } else if (m.s == OmxWire.STATUS_OFF) {
                offTimer?.cancel()
                offTimer = clock.after(OFF_MS) {
                    offTimer = null
                    val ss = session
                    if (ss == null || ss.active) return@after
                    val name = pair?.name ?: "The OMX-27"
                    active = false
                    release()
                    set(Tone.BUSY, "$name left remote mode: pick REMOTE on it again, or Connect.")
                    onToast?.invoke("$name left remote mode.", false)
                    changed()
                }
            }
            return
        }
        if (!s.active) return
        when (m) {
            is OmxWire.Message.Pot -> {
                // (pots stream every 2-3 ms while they move: the latest of each, taken on the next tick)
                pots[m.pot] = m.v to m.hires
                return
            }
            is OmxWire.Message.Key -> apply(views.key(m.key, m.ev, now))
            is OmxWire.Message.Enc -> apply(views.enc(m.delta))
            else -> {}
        }
        refresh()
    }

    private fun takePots(): Boolean {
        if (pots.isEmpty()) return false
        val now = clock.now
        for ((p, v) in pots.entries.toList()) apply(views.pot(p, v.first, v.second, now, target?.omxMasterNow ?: 768))
        pots.clear()
        return true
    }

    private fun apply(acts: List<OmxAction>) {
        for (a in acts) {
            when (a) {
                is OmxAction.Key -> target?.omxKey(a.k, a.down)
                is OmxAction.Button -> target?.omxButton(a.l, a.down)
                is OmxAction.Turn -> {
                    ownTurn = true
                    target?.omxTurn(a.knob, a.steps)
                    ownTurn = false
                }
                is OmxAction.Select -> target?.omxSelect(a.knob)
                is OmxAction.Midi -> target?.omxFirmwareMidi(ByteArray(a.bytes.size) { a.bytes[it].toByte() })
                is OmxAction.Master -> target?.omxMaster(a.v)
                is OmxAction.Pickup -> target?.omxPickup(a.pot, a.at, a.first)
                is OmxAction.View -> set(tone, "${pair?.name ?: "The OMX-27"} in remote mode: ${a.view.title} view. $LEAVE")
                is OmxAction.Theme -> {}
            }
            if (a is OmxAction.Theme || a is OmxAction.Select || a is OmxAction.Button || a is OmxAction.View) keep()
        }
    }

    private fun keep() {
        val v = views.saved()
        if (v == OmxViewPrefs.fromJson(prefs.string(PREFS_KEY))) return
        prefs.setString(PREFS_KEY, v.toJson())
    }

    /** a knob turned on the panel (not by the OMX): the encoder turns that one next */
    fun knobUsed(k: PanelKnob) {
        if (ownTurn) return
        views.knobUsed(k)
    }

    // ---- what it shows

    /** the LEDs and the screen again now (the FM-1's LEDs changed; the tick does it within 10 ms anyway) */
    fun refresh() {
        val s = session ?: return
        if (!s.active) return
        s.setLeds(views.leds { k -> target?.omxKeyLevel(k) ?: 0 })
        draw()
        s.tick()
    }

    private fun tick() {
        val s = session ?: return
        if (!s.active) return
        if (takePots()) return refresh()
        // (the FM-1's key LEDs, mirrored: every tick; the session sends only what changed)
        s.setLeds(views.leds { k -> target?.omxKeyLevel(k) ?: 0 })
        draw()
        s.tick()
    }

    private fun draw() {
        val s = session ?: return
        val sc = views.screen(clock.now)
        val key = "${sc.title}|${sc.line}|${sc.view.ordinal}"
        if (key == shown) return
        shown = key
        s.setFrame(render(sc))
    }

    companion object {
        const val OFF_MS = 50.0      // 52 03 then 52 02 within this: still in REMOTE mode
        const val ANSWER_MS = 2500.0
        const val TICK_MS = 10.0
        const val LEAVE = "To leave remote mode on the OMX-27, hold AUX and the encoder."
        const val PREFS_KEY = "rfm1.omx"
        const val AUTO_KEY = "rfm1.omxAuto"
    }
}
