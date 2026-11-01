// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/OMX27/OmxViews.swift
// The OMX-27 as the FM-1's controller (REMOTE mode, OmxWire.kt): its views, what each key does in them, its LEDs
// and its screen's text. The website's js/omx27-views.js, ported as is (the owner's design:
// RemoteFM1 design/RemoteFM1---OMX-27-Remote.json).
//   keys      0 AUX, 1..10 the black keys, 11..26 the white keys (B3..C6: the design's keyMap)
//   K1        the view: Base, Seq, Keyboard, Quick Keys (four zones, a little hysteresis)
//   K2        the colour theme (six zones)
//   K3 / K4   Keyboard: the MIDI channel / the octave; Seq: K4 makes AUX the 11th black key or the button quick key
//   K5        MASTER, with pickup (MasterPot, below: the website's midi-map.js)
//   encoder   turns the selected knob (the last picked with a knob quick key, or the last turned); SELECT at first
// Base: 1 2 OCT- OCT+, 11 12 Range 1 / 2 (key 15 plays the FM-1's first F or its second), 13 the knob quick keys and 14
// the button quick keys (while held; a double click latches them, a click then closes them), the rest plays the FM-1's
// keys; AUX presses the selected button. Seq: the FM-1's keys 1:1. Keyboard: notes to the firmware's MIDI in. Quick
// Keys: every key a shortcut. Input -> actions (OmxLink applies them).
package com.quixotic7.remotefm1.omx

import com.quixotic7.remotefm1.ui.faceplate.LedLevel
import com.quixotic7.remotefm1.ui.faceplate.PanelKnob
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

/** what the OMX's input asks of the panel */
sealed class OmxAction {
    data class Key(val k: Int, val down: Boolean) : OmxAction()                 // the FM-1's key 0..26
    data class Button(val l: PanelLabel, val down: Boolean) : OmxAction()
    data class Turn(val knob: PanelKnob, val steps: Int) : OmxAction()
    data class Select(val knob: PanelKnob) : OmxAction()
    data class Midi(val bytes: List<Int>) : OmxAction()                         // to the firmware's MIDI in
    data class Master(val v: Int) : OmxAction()                                 // 0..1023
    data class Pickup(val pot: Int, val at: Int, val first: Boolean) : OmxAction()   // K5 away from MASTER (both 0..1023)
    data class View(val view: OmxView) : OmxAction()
    data class Theme(val i: Int) : OmxAction()
}

enum class OmxView(val title: String) { BASE("Base"), SEQ("Seq"), KEYS("Keyboard"), QUICK("Quick Keys") }

/** the views' choices kept between runs (the website's prefs.omx; iOS's OmxViewPrefs JSON; bad values are ignored) */
data class OmxViewPrefs(val theme: Int? = null, val knob: Int? = null, val button: Int? = null, val auxBlack: Boolean? = null,
                        val ch: Int? = null, val oct: Int? = null) {
    fun toJson(): String = JSONObject().apply {
        theme?.let { put("theme", it) }; knob?.let { put("knob", it) }; button?.let { put("button", it) }
        auxBlack?.let { put("auxBlack", it) }; ch?.let { put("ch", it) }; oct?.let { put("oct", it) }
    }.toString()

    companion object {
        fun fromJson(s: String?): OmxViewPrefs? {
            val o = runCatching { JSONObject(s ?: return null) }.getOrNull() ?: return null
            fun int(k: String) = (o.opt(k) as? Number)?.toInt()
            return OmxViewPrefs(int("theme"), int("knob"), int("button"), o.opt("auxBlack") as? Boolean, int("ch"), int("oct"))
        }
    }
}

// the website's panel.js, the parts the views need
object OmxPanel {
    const val N_KEY = 27
    /** panel.js PANEL_LABELS (PanelLabel order; ASCII, as the OMX's screen shows them) */
    val labels = listOf("FX", "SEL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ", "PLAY", "REC", "OCT-", "OCT+")
    fun label(l: PanelLabel) = labels[l.ordinal]
    fun keyBlack(k: Int) = (0x54A shr ((k + 5) % 12)) and 1 == 1
    private val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    /** the FM-1's key k (0 = F3) */
    fun noteName(k: Int): String { val m = 53 + k; return noteNames[m % 12] + (m / 12 - 1) }
}

/** Math.round */
fun jsRound(x: Double): Int = floor(x + 0.5).toInt()

/** MASTER's pickup for one pot (midi-map.js pickedUp) */
class PickupState {
    var last: Double? = null
    var picked = false
}

/** v its value; at MASTER's position in the pot's units; tol how near is there. True once picked up: near MASTER, or it
 *  crossed MASTER since its last value */
fun pickedUp(s: PickupState, v: Double, at: Double, tol: Double): Boolean {
    val last = s.last
    s.last = v
    if (!s.picked) s.picked = abs(v - at) <= tol || (last != null && (last - at) * (v - at) <= 0)
    return s.picked
}

/** one pot on MASTER with that pickup (the OMX-27's K5; midi-map.js MasterPot): v 0..max, cur MASTER now (0..1023) ->
 *  Master or Pickup. MASTER moved by anything else: it waits again */
class MasterPot {
    private val s = PickupState()
    private var set: Int? = null
    private var hinted = false

    fun feed(v: Int, max: Int, cur: Int): List<OmxAction> {
        val want = jsRound(v.toDouble() * 1023 / max)
        if (cur != set) { set = cur; s.picked = false; hinted = false }
        if (!pickedUp(s, v.toDouble(), cur.toDouble() * max / 1023, 2.0 * max / 127)) {
            val first = !hinted
            hinted = true
            return listOf(OmxAction.Pickup(want, cur, first))
        }
        set = want
        return listOf(OmxAction.Master(want))
    }
}

class OmxViews(saved: OmxViewPrefs? = null) {
    data class ThemeDef(val name: String, val key: List<Int>, val dim: Double?)

    enum class QK { KNOB, BTN }

    /** what a key does now */
    sealed class Role {
        data class Fm1(val k: Int) : Role()                 // the FM-1's key
        data class Note(val n: Int, val ch: Int) : Role()   // a note to the firmware
        data class Oct(val l: PanelLabel) : Role()          // OCT- / OCT+ (not selected)
        data class Btn(val l: PanelLabel) : Role()          // a button (selected when pressed)
        data class Knob(val knob: PanelKnob) : Role()       // selects the encoder's knob
        data class Range(val i: Int) : Role()
        data class Qk(val which: QK) : Role()               // a quick-key toggle
        data class Aux(val l: PanelLabel) : Role()          // the selected button
    }

    /** the screen's text (line: a moment's note, else the view's context) */
    data class Screen(val title: String, val line: String, val view: OmxView)

    var view = OmxView.BASE
        private set
    var theme: Int
    var knob: PanelKnob
        private set
    var button: PanelLabel
        private set
    var auxBlack: Boolean
        private set
    var ch: Int
        private set
    var oct: Int
        private set
    var range = 0

    private data class Held(val k: Int, val role: Role?)
    private val held = ArrayList<Held>()                    // OMX key -> its role when it went down (in that order)
    private class Click { var latched = false; var last = -1e9 }
    private val qkKnob = Click()
    private val qkBtn = Click()
    private val qkHeld = ArrayList<QK>()                     // in the order held
    private val fm1 = IntArray(OmxPanel.N_KEY)               // how many OMX keys hold each FM-1 key
    private val btns = IntArray(OmxPanel.labels.size)
    val master = MasterPot()
    private var noteText: String? = null                     // the theme's name, MASTER's %
    private var noteUntil = 0.0

    init {
        val s = saved ?: OmxViewPrefs()
        theme = s.theme?.takeIf { it in THEMES.indices } ?: 0
        knob = s.knob?.let { PanelKnob.entries.getOrNull(it) }?.takeIf { knobName(it) != null } ?: PanelKnob.SELECT
        button = s.button?.let { PanelLabel.entries.getOrNull(it) } ?: PanelLabel.PLAY
        auxBlack = s.auxBlack == true
        ch = s.ch?.takeIf { it in 1..16 } ?: 1
        oct = s.oct?.takeIf { abs(it) <= 3 } ?: 0
    }

    fun saved() = OmxViewPrefs(theme, knob.ordinal, button.ordinal, auxBlack, ch, oct)

    val heldCount: Int get() = held.size
    fun isHeld(k: Int) = held.any { it.k == k }
    val knobLatched: Boolean get() = qkKnob.latched
    val buttonLatched: Boolean get() = qkBtn.latched

    /** the quick keys showing in Base */
    fun overlay(): QK? {
        if (view != OmxView.BASE) return null
        qkHeld.lastOrNull()?.let { return it }
        return if (qkKnob.latched) QK.KNOB else if (qkBtn.latched) QK.BTN else null
    }

    /** the FM-1 key an OMX key plays in Base (-1: past the FM-1's keys) */
    fun baseKey(k: Int): Int {
        val n = OMX_NOTES[k] ?: return -1
        val f = n - 65 + 12 * range
        return if (f >= 0 && f < OmxPanel.N_KEY) f else -1
    }

    /** what key k does now (null nothing) */
    fun roleOf(k: Int): Role? {
        fun fm1(f: Int): Role? = if (f >= 0 && f < OmxPanel.N_KEY) Role.Fm1(f) else null
        val aux = Role.Aux(button)
        when (view) {
            OmxView.SEQ -> {
                if (k == 0) return if (auxBlack) fm1(FM1_SHARPS[10]) else aux
                return fm1(if (k <= 10) FM1_SHARPS[k - 1] else FM1_WHITES[k - 11])
            }
            OmxView.KEYS -> {
                if (k == 0) return aux
                val n = OMX_NOTES[k]!! + 12 * oct
                return if (n in 0..127) Role.Note(n, ch) else null
            }
            OmxView.QUICK -> {
                if (k == 0) return aux
                if (k == 1 || k == 2) return Role.Btn(if (k == 1) PanelLabel.OCT_DOWN else PanelLabel.OCT_UP)
                if (k in 3..9) return Role.Knob(KNOBS[k - 3].first)
                if (k in 11..16) return Role.Btn(BTN6[k - 11])
                if (k in 19..24) return Role.Btn(BTN6B[k - 19])
                return null
            }
            OmxView.BASE -> {}
        }
        val ov = overlay()
        if (k == 0) return aux
        if (k == 1 || k == 2) {
            val b = if (k == 1) PanelLabel.OCT_DOWN else PanelLabel.OCT_UP
            return if (ov == QK.BTN) Role.Btn(b) else Role.Oct(b)
        }
        if (k == 11 || k == 12) return Role.Range(k - 11)
        if (k == 13 || k == 14) return Role.Qk(if (k == 13) QK.KNOB else QK.BTN)
        if (k <= 10) {
            if (ov == QK.KNOB) return if (k <= 9) Role.Knob(KNOBS[k - 3].first) else null
            if (ov == QK.BTN) return if (k >= 5) Role.Btn(BTN6[k - 5]) else null
        } else if (ov == QK.BTN) return if (k >= 21) Role.Btn(BTN6B[k - 21]) else null
        return fm1(baseKey(k))
    }

    // ---- input

    /** a key event (OmxWire.EV_*); now: milliseconds */
    fun key(k: Int, ev: Int, now: Double = 0.0): List<OmxAction> {
        if (k < 0 || k >= 27) return emptyList()
        if (ev == OmxWire.EV_DOWN) {
            if (isHeld(k)) return emptyList()
            val r = roleOf(k)
            held.add(Held(k, r))
            return press(r, true)
        }
        if (ev == OmxWire.EV_UP) {
            val i = held.indexOfFirst { it.k == k }
            if (i < 0) return emptyList()
            val r = held[i].role
            held.removeAt(i)
            return press(r, false)
        }
        if (ev == OmxWire.EV_QUICK && view == OmxView.BASE && (k == 13 || k == 14)) click(if (k == 13) QK.KNOB else QK.BTN, now)
        return emptyList()
    }

    /** a quick click on a quick-key toggle: a second one soon after latches it; when latched, one closes it */
    fun click(which: QK, now: Double) {
        val q = if (which == QK.KNOB) qkKnob else qkBtn
        if (q.latched) {
            q.latched = false; q.last = -1e9
        } else if (now - q.last <= DOUBLE_MS) {
            q.latched = true; q.last = -1e9
            if (which == QK.KNOB) qkBtn.latched = false else qkKnob.latched = false
        } else {
            q.last = now
        }
    }

    private fun press(r: Role?, down: Boolean): List<OmxAction> {
        r ?: return emptyList()
        return when (r) {
            is Role.Fm1 -> count(fm1, r.k, down) { OmxAction.Key(r.k, it) }
            is Role.Note -> listOf(OmxAction.Midi(listOf((if (down) 0x90 else 0x80) or (r.ch - 1), r.n, if (down) VELOCITY else 0)))
            is Role.Oct -> count(btns, r.l.ordinal, down) { OmxAction.Button(r.l, it) }
            is Role.Aux -> count(btns, r.l.ordinal, down) { OmxAction.Button(r.l, it) }
            is Role.Btn -> {
                if (down) button = r.l
                count(btns, r.l.ordinal, down) { OmxAction.Button(r.l, it) }
            }
            is Role.Knob -> {
                if (!down) return emptyList()
                knob = r.knob
                listOf(OmxAction.Select(r.knob))
            }
            is Role.Range -> { if (down) range = r.i; emptyList() }
            is Role.Qk -> {
                if (down) qkHeld.add(r.which) else qkHeld.lastIndexOf(r.which).takeIf { it >= 0 }?.let { qkHeld.removeAt(it) }
                emptyList()
            }
        }
    }

    /** a key or button held by several OMX keys: down with the first, up with the last */
    private fun count(arr: IntArray, i: Int, down: Boolean, act: (Boolean) -> OmxAction): List<OmxAction> {
        val was = arr[i]
        arr[i] = if (down) was + 1 else maxOf(0, was - 1)
        return if ((was == 0) != (arr[i] == 0)) listOf(act(down)) else emptyList()
    }

    fun enc(delta: Int): List<OmxAction> = if (delta != 0) listOf(OmxAction.Turn(knob, delta)) else emptyList()

    /** a knob turned elsewhere (the panel, a MIDI controller, the FM-1): the encoder turns it next */
    fun knobUsed(role: PanelKnob) { if (knobName(role) != null) knob = role }

    /** a pot: v 0..127, hires 0..16383, now milliseconds; master MASTER now (0..1023) */
    fun pot(p: Int, v: Int, hires: Int = 0, now: Double = 0.0, master: Int = 768): List<OmxAction> {
        if (p == 0) {
            val z = zoneOf(v, 4, view.ordinal)
            if (z == view.ordinal) return emptyList()
            view = OmxView.entries[z]
            return listOf(OmxAction.View(view))
        }
        if (p == 1) {
            val z = zoneOf(v, THEMES.size, theme)
            note("THEME " + THEMES[z].name.uppercase(), now)
            if (z == theme) return emptyList()
            theme = z
            return listOf(OmxAction.Theme(z))
        }
        if (p == 2 && view == OmxView.KEYS) ch = zoneOf(v, 16, ch - 1) + 1
        if (p == 3 && view == OmxView.KEYS) oct = zoneOf(v, 7, oct + 3) - 3
        if (p == 3 && view == OmxView.SEQ) auxBlack = zoneOf(v, 2, if (auxBlack) 1 else 0) == 1
        if (p == 4) {
            val acts = this.master.feed(hires, 16383, master)
            fun pct(x: Int) = "${jsRound(x.toDouble() / 1023 * 100)}%"
            when (val a = acts[0]) {
                is OmxAction.Master -> note("MASTER " + pct(a.v), now)
                is OmxAction.Pickup -> note("K5 ${pct(a.pot)} TO ${pct(a.at)}", now)
                else -> {}
            }
            return acts
        }
        return emptyList()
    }

    private fun note(t: String, now: Double) { noteText = t; noteUntil = now + NOTE_MS }

    /** everything the OMX holds, let go (it left REMOTE mode, went away, or was disconnected) */
    fun releaseAll(): List<OmxAction> {
        val out = ArrayList<OmxAction>()
        for (h in held) out += press(h.role, false)
        held.clear()
        qkHeld.clear()
        fm1.fill(0)
        btns.fill(0)
        return out
    }

    /** a Base-view playing key past the end of the FM-1's keyboard (Range 2's top): lit dark red, so it reads as not there */
    private fun deadKey(k: Int, ov: QK?) = view == OmxView.BASE && ov != QK.BTN && ((k in 3..10) || k >= 15) && baseKey(k) < 0

    // ---- what the OMX shows

    /** the 27 LEDs (7-bit); keyLevel(k): the FM-1's LED of key k (0..255) */
    fun leds(keyLevel: (Int) -> Int = { 0 }): List<OmxRGB> {
        val th = THEMES[theme]
        val dim = th.dim ?: DIM
        val ov = overlay()
        return (0 until 27).map { k ->
            val pressed = isHeld(k)
            fun sc(hue: List<Int>, on: Boolean) = c7(hue, if (on) 1.0 else dim)
            when (val r = roleOf(k)) {
                null -> if (deadKey(k, ov)) c7(HUE_AUX, DEAD * dim / DIM) else OmxRGB.OFF
                is Role.Fm1 -> c7(th.key, LedLevel.alpha(keyLevel(r.k)).toDouble())
                is Role.Note -> if (pressed) c7(th.key, 1.0) else OmxRGB.OFF
                is Role.Aux -> sc(HUE_AUX, pressed)
                is Role.Oct -> sc(hueBtn(r.l), pressed)
                is Role.Btn -> sc(hueBtn(r.l), pressed || button == r.l)
                is Role.Knob -> sc(if (r.knob == PanelKnob.SELECT || r.knob == PanelKnob.PRESETS || r.knob == PanelKnob.ALGORITHM)
                                       HUE_KNOB else HUE_KN, knob == r.knob)
                is Role.Range -> sc(if (r.i != 0) HUE_R2 else HUE_R1, range == r.i)
                is Role.Qk -> sc(if (r.which == QK.KNOB) HUE_KQK else HUE_BQK, pressed || ov == r.which)
            }
        }
    }

    /** now: milliseconds */
    fun screen(now: Double = 0.0): Screen {
        val ov = overlay()
        val kn = knobName(knob) ?: ""
        val aux = "AUX " + OmxPanel.label(button)
        val title = when (view) {
            OmxView.SEQ -> "SEQ"
            OmxView.KEYS -> "KEYBOARD"
            OmxView.QUICK -> "QUICK KEYS"
            OmxView.BASE -> if (ov == QK.KNOB) "KNOBS" else if (ov == QK.BTN) "BUTTONS" else "FM-1"
        }
        val nt = noteText
        val line = if (nt != null && now < noteUntil) nt else when (view) {
            OmxView.SEQ -> "$kn  ${if (auxBlack) "AUX " + OmxPanel.noteName(FM1_SHARPS[10]) else aux}"
            OmxView.KEYS -> "CH $ch  OCT ${if (oct > 0) "+" else ""}$oct  $kn"
            OmxView.QUICK -> "$kn  $aux"
            OmxView.BASE -> "R${range + 1}  $kn  $aux"
        }
        return Screen(title, line, view)
    }

    /** a short name for each key now (the tests' pictures of the OMX) */
    fun labels(): List<String> = (0 until 27).map { k ->
        when (val r = roleOf(k)) {
            null -> ""
            is Role.Fm1 -> OmxPanel.noteName(r.k)
            is Role.Note -> OmxPanel.noteName(r.n - 53)
            is Role.Aux -> "AUX"
            is Role.Oct -> OmxPanel.label(r.l)
            is Role.Btn -> OmxPanel.label(r.l)
            is Role.Knob -> knobName(r.knob) ?: ""
            is Role.Range -> "R${r.i + 1}"
            is Role.Qk -> if (r.which == QK.KNOB) "KNOBS" else "BTNS"
        }
    }

    companion object {
        /** the OMX's keys' notes (the design's keyMap; AUX none) */
        val OMX_NOTES: List<Int?> = listOf(null, 61, 63, 66, 68, 70, 73, 75, 78, 80, 82, 59, 60, 62, 64, 65, 67, 69, 71, 72,
                                           74, 76, 77, 79, 81, 83, 84)
        /** the FM-1's 27 keys (F3..G5): its 16 whites and 11 sharps, in order */
        val FM1_WHITES = (0 until OmxPanel.N_KEY).filter { !OmxPanel.keyBlack(it) }
        val FM1_SHARPS = (0 until OmxPanel.N_KEY).filter { OmxPanel.keyBlack(it) }
        val KNOBS = listOf(PanelKnob.SELECT to "SEL", PanelKnob.PRESETS to "PRESETS", PanelKnob.ALGORITHM to "ALGO",
                           PanelKnob.K1 to "KN1", PanelKnob.K2 to "KN2", PanelKnob.K3 to "KN3", PanelKnob.K4 to "KN4")
        fun knobName(k: PanelKnob): String? = KNOBS.firstOrNull { it.first == k }?.second
        val BTN6 = listOf(PanelLabel.FX, PanelLabel.SEL, PanelLabel.ENV, PanelLabel.LFO, PanelLabel.EDIT, PanelLabel.GLO)
        val BTN6B = listOf(PanelLabel.HOME, PanelLabel.SAVE, PanelLabel.ARP, PanelLabel.SEQ, PanelLabel.PLAY, PanelLabel.REC)

        // colours at full brightness (the design's hues); a shortcut shows at DIM and full while pressed or selected
        val HUE_AUX = listOf(255, 0, 0)
        val HUE_R1 = listOf(128, 255, 0)
        val HUE_R2 = listOf(255, 128, 0)
        val HUE_KQK = listOf(255, 0, 128)
        val HUE_BQK = listOf(255, 128, 128)
        val HUE_KNOB = listOf(0, 0, 255)
        val HUE_KN = listOf(255, 0, 0)
        private val SIX = listOf(listOf(255, 128, 0), listOf(255, 255, 0), listOf(255, 0, 255), listOf(255, 0, 128),
                                 listOf(0, 255, 128), listOf(255, 0, 0))
        fun hueBtn(b: PanelLabel): List<Int> {
            if (b == PanelLabel.OCT_DOWN) return listOf(0, 255, 255)
            if (b == PanelLabel.OCT_UP) return listOf(0, 127, 255)
            val i = BTN6.indexOf(b)
            return if (i >= 0) SIX[i] else SIX[BTN6B.indexOf(b)]
        }
        const val DIM = 0.5
        const val DEAD = 0.16     // the dark red of a key past the FM-1's keyboard

        /** K2: the hue of the keys that mirror the FM-1's (and Night: dimmer shortcuts, for a dark room) */
        val THEMES = listOf(ThemeDef("White", listOf(255, 255, 255), null), ThemeDef("Ember", listOf(255, 90, 0), null),
                            ThemeDef("Ice", listOf(0, 190, 255), null), ThemeDef("Violet", listOf(150, 40, 255), null),
                            ThemeDef("Lime", listOf(110, 255, 0), null), ThemeDef("Night", listOf(40, 60, 255), 0.25))
        const val DOUBLE_MS = 400.0
        const val NOTE_MS = 1500.0
        const val VELOCITY = 100

        /** a pot (0..127) in n zones; prev the zone now: it changes only past the zone's edge by a margin */
        fun zoneOf(v: Int, n: Int, prev: Int?): Int {
            val w = 128.0 / n
            val z = floor(v / w).toInt().coerceIn(0, n - 1)
            if (prev == null || prev < 0 || prev >= n || z == prev) return z
            val m = minOf(3.0, w / 4)
            return if (v < prev * w - m || v >= (prev + 1) * w + m) z else prev
        }

        fun c7(c: List<Int>, f: Double): OmxRGB {
            val v = c.map { jsRound(it * f / 2).coerceIn(0, 127) }
            return OmxRGB(v[0], v[1], v[2])
        }
    }
}
