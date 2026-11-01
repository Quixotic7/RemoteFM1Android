// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/PanelFeed.swift
// The on-screen panel's link to a core (the app's engine):
//   in    touches go in through rfme_event, the same queue and between hook as the FM-1's events: note keys 0..26,
//         buttons by label through the panel's btn_id map, encoder detents by knob role, MASTER absolute
//   out   the core's LCD (one Bitmap, updated in place), its LEDs, the knobs' turns and MASTER, read from the engine's
//         lock-free snapshot (rfme_panel, rfme_preview) by poll(), which the faceplate calls every frame
// What changes is split into small state counters, so an LED changing redraws the keybed or the buttons, not the whole
// faceplate. Learn mode (A6) and the OMX-27 (A6) hook in later: [onTick], the source of a touch.
package com.quixotic7.remotefm1.ui.faceplate

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.quixotic7.remotefm1.RfmEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** something that owns an engine the faceplate can play and read (the app's RfmEngine; a fake in tests) */
interface PanelEngine {
    fun event(type: Int, index: Int, value: Int): Boolean
    fun matrixButton(label: Int): Int
    fun matrixEncoder(role: Int): Int
    /** rfme_panel into [out] (56 ints: see RfmEngine.panel); its seq */
    fun panel(out: IntArray): Int
    /** the LCD into [buf] (RGBA, 240 x 240) when it changed since [have]; its number */
    fun preview(have: Int, buf: ByteBuffer): Int
    val buttonNamesGen: Int
    fun buttonNames(): ButtonNames
}

/** the app's engine as the faceplate sees it */
class EnginePanel(private val e: RfmEngine) : PanelEngine {
    override fun event(type: Int, index: Int, value: Int) = e.event(type, index, value)
    override fun matrixButton(label: Int) = e.matrixButtonId(label)
    override fun matrixEncoder(role: Int) = e.matrixEncoderId(role)
    override fun panel(out: IntArray) = e.panel(out)
    override fun preview(have: Int, buf: ByteBuffer) = e.previewDirect(have, buf)
    override val buttonNamesGen get() = e.buttonNamesGen
    override fun buttonNames(): ButtonNames = PanelFeed.parseNames(e.buttonNamesText())
}

/** LED brightness (fm1core.h FM1P_LED_*) as the faceplate draws it */
object LedLevel {
    const val LIT_MIN = 176

    /** the share of the lit colour over the off colour: FM1VST's ledAlpha (FAINT .2, DIM .38, MID .62, LIT 1) */
    fun alpha(v: Int): Float {
        val xs = floatArrayOf(0f, 12f, 40f, 96f, 255f)
        val ys = floatArrayOf(0f, 0.2f, 0.38f, 0.62f, 1f)
        val x = v.toFloat()
        for (i in 1 until 5) if (x <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / (xs[i] - xs[i - 1])
        return 1f
    }

    fun lit(v: Int) = v >= LIT_MIN
}

/** the LEDs and what is held (arrays read while drawing; [version] moves when any changed) */
class PanelLights {
    val keys = IntArray(27)
    val buttons = IntArray(14)                  // by label (PanelLabel)
    var playGreen = 0
    var heldKeys = 0                            // the core's (the unit's, the screen's, host notes)
    var heldLabels = 0
    var touchedKeys = 0                         // held on this screen now (drawn at once)
    var touchedLabels = 0
    var booted = false
    var version by mutableIntStateOf(0)
        internal set

    fun keyDown(k: Int) = ((heldKeys or touchedKeys) and (1 shl k)) != 0
    fun labelDown(l: Int) = ((heldLabels or touchedLabels) and (1 shl l)) != 0
    internal fun changed() { version++ }
}

/** the knobs: clockwise detents per role since power-on (pointer angles), MASTER */
class KnobFeed {
    val turns = IntArray(7)
    var master = 724                            // (a core's power-on value when the host gives none)
    var masterKnown = false
    var version by mutableIntStateOf(0)
        internal set
    internal fun changed() { version++ }
}

class PanelFeed(host: PanelEngine?) {
    var host: PanelEngine? = host
        private set

    /** the core's screen: one bitmap updated in place; [frames] moves with each new frame */
    val bitmap: Bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()
    var frames by mutableIntStateOf(0)
        private set
    val lights = PanelLights()
    val knobs = KnobFeed()
    /** the running firmware's button names, read again when the engine's core or setting changes */
    var names by mutableStateOf(ButtonNames.STANDARD)
        private set
    /** (A6) MIDI learn: while on, a touch picks a control instead of playing it */
    var learnActive by mutableStateOf(false)
    /** (A6) a control picked in learn mode (its MIDI target id) */
    var onLearnPick: ((String) -> Unit)? = null
    /** every poll (A6: MIDI learn takes its messages here) */
    var onTick: (() -> Unit)? = null
    /** each new screen frame (A4's thumbnails): the RGBA buffer, valid during the call */
    var onFrame: ((ByteBuffer) -> Unit)? = null
    /** a knob turned (or MASTER moved) by a touch on this screen */
    var onKnobTouched: ((PanelKnob) -> Unit)? = null

    private val rgba: ByteBuffer = ByteBuffer.allocateDirect(240 * 240 * 4).order(ByteOrder.nativeOrder())
    private val panelBuf = IntArray(56)
    private var panelSeq = 0
    private var previewSeq = 0
    private var namesGen = -1
    private val keyTouches = IntArray(27)
    private var screenLabels = 0
    private val localUntil = LongArray(8)

    fun attach(h: PanelEngine?) {
        host = h
        panelSeq = 0
        previewSeq = 0
        namesGen = -1
    }

    // ---- in (the same path as the FM-1's events)

    private fun event(type: Int, index: Int, value: Int) {
        if (index < 0) return
        host?.event(type, index, value)
    }

    /** a finger down on note key [k] (several fingers on one key: one press) */
    fun keyDown(k: Int) {
        if (k !in 0 until 27) return
        val was = keyTouches[k] > 0
        keyTouches[k]++
        if (was) return
        lights.touchedKeys = lights.touchedKeys or (1 shl k)
        lights.changed()
        event(RfmEngine.EV_KEY, k, 1)
    }

    fun keyUp(k: Int) {
        if (k !in 0 until 27 || keyTouches[k] == 0) return
        keyTouches[k]--
        if (keyTouches[k] > 0) return
        lights.touchedKeys = lights.touchedKeys and (1 shl k).inv()
        lights.changed()
        event(RfmEngine.EV_KEY, k, 0)
    }

    fun holdsKey(k: Int) = k in 0 until 27 && keyTouches[k] > 0
    fun holds(l: PanelLabel) = (screenLabels and (1 shl l.ordinal)) != 0

    /** a button by its label, through the panel's btn_id (the firmware's own map) */
    fun button(l: PanelLabel, down: Boolean) {
        val bit = 1 shl l.ordinal
        if (((screenLabels and bit) != 0) == down) return
        screenLabels = if (down) screenLabels or bit else screenLabels and bit.inv()
        lights.touchedLabels = if (down) lights.touchedLabels or bit else lights.touchedLabels and bit.inv()
        lights.changed()
        val id = host?.matrixButton(l.ordinal) ?: -1
        event(RfmEngine.EV_BUTTON, id, if (down) 1 else 0)
    }

    /** detents of an encoder (+ clockwise), by its knob role */
    fun turn(k: PanelKnob, detents: Int) {
        if (k == PanelKnob.MASTER || detents == 0) return
        val id = host?.matrixEncoder(k.ordinal) ?: -1
        var left = detents
        while (left != 0) {                                     // (int16 per event)
            val d = left.coerceIn(-1000, 1000)
            event(RfmEngine.EV_ENC, id, d)
            left -= d
        }
        knobs.turns[k.ordinal] += detents                       // (drawn at once; the snapshots confirm it)
        knobs.changed()
        localUntil[k.ordinal] = SystemClock.uptimeMillis() + LOCAL_HOLD_MS
        onKnobTouched?.invoke(k)
    }

    /** the MASTER pot, absolute 0..1023 */
    fun master(v: Int) {
        val c = v.coerceIn(0, 1023)
        knobs.master = c
        knobs.masterKnown = true
        knobs.changed()
        localUntil[PanelKnob.MASTER.ordinal] = SystemClock.uptimeMillis() + LOCAL_HOLD_MS
        event(EV_MASTER, 0, c)
        onKnobTouched?.invoke(PanelKnob.MASTER)
    }

    /** everything this screen holds let go (the faceplate went away) */
    fun releaseAll() {
        for (k in 0 until 27) if (keyTouches[k] > 0) { keyTouches[k] = 1; keyUp(k) }
        for (l in PanelLabel.entries) if (holds(l)) button(l, false)
    }

    // ---- out

    /** reads the engine's newest panel state and screen (cheap when nothing changed) */
    fun poll() {
        val h = host ?: return
        pollPanel(h)
        val g = h.buttonNamesGen
        if (g != namesGen) {
            namesGen = g
            val n = h.buttonNames()
            if (n != names) names = n
        }
        onTick?.invoke()
        rgba.rewind()
        val s = h.preview(previewSeq, rgba)
        if (s != previewSeq && s != 0) {
            previewSeq = s
            rgba.rewind()
            bitmap.copyPixelsFromBuffer(rgba)
            frames++
            rgba.rewind()
            onFrame?.invoke(rgba)
        }
    }

    private fun pollPanel(h: PanelEngine) {
        val seq = h.panel(panelBuf)
        if (seq != panelSeq) {
            panelSeq = seq
            apply(panelBuf)
        }
    }

    /** a panel snapshot (RfmEngine.panel's 56 ints) applied (tests call it directly) */
    fun apply(p: IntArray) {
        val booted = p[I_BOOTED] != 0
        var changed = false
        if (booted != lights.booted) { lights.booted = booted; changed = true }
        for (i in 0 until 27) {
            val v = if (booted) p[I_KEY_LED + i] else 0
            if (lights.keys[i] != v) { lights.keys[i] = v; changed = true }
        }
        for (i in 0 until 14) {
            val v = if (booted) p[I_BTN_LED + i] else 0
            if (lights.buttons[i] != v) { lights.buttons[i] = v; changed = true }
        }
        if (p[I_PLAY_GREEN] != lights.playGreen) { lights.playGreen = p[I_PLAY_GREEN]; changed = true }
        if (p[I_KEYS] != lights.heldKeys) { lights.heldKeys = p[I_KEYS]; changed = true }
        if (p[I_LABELS] != lights.heldLabels) { lights.heldLabels = p[I_LABELS]; changed = true }
        if (changed) lights.changed()
        if (!booted) return
        val now = SystemClock.uptimeMillis()
        var kc = false
        for (r in 0 until 7) {
            if (localUntil[r] > now) continue                   // (a turn here not applied yet)
            if (knobs.turns[r] != p[I_ENC + r]) { knobs.turns[r] = p[I_ENC + r]; kc = true }
        }
        if (localUntil[PanelKnob.MASTER.ordinal] <= now && (p[I_MASTER] != knobs.master || !knobs.masterKnown)) {
            knobs.master = p[I_MASTER]
            knobs.masterKnown = true
            kc = true
        }
        if (kc) knobs.changed()
    }

    companion object {
        const val EV_MASTER = 4                     // RFMC_EV_MASTER
        const val LOCAL_HOLD_MS = 250L
        // RfmEngine.panel's layout
        const val I_SEQ = 0
        const val I_KEYS = 1
        const val I_LABELS = 2
        const val I_ENC = 3
        const val I_MASTER = 10
        const val I_BOOTED = 11
        const val I_PLAY_GREEN = 12
        const val I_MASTER_POT = 13
        const val I_PICKUP_SEQ = 14
        const val I_KEY_LED = 15
        const val I_BTN_LED = 42

        /** RfmEngine.buttonNamesText's lines into the names (ButtonNames' rules) */
        fun parseNames(text: String): ButtonNames {
            val l = text.split("\n")
            if (l.size < 17) return ButtonNames.STANDARD
            val loaded = l[1] == "1"
            val use = l[2] == "1"
            return ButtonNames(if (loaded) l.subList(3, 17) else null, use)
        }
    }
}
