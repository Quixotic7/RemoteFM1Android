// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/LearnMode.swift and Toast.swift (ToastCenter)
// (A6) MIDI learn on the faceplate (js/main.js learnStart, learnView, learnPick, learnTook, learnForget), MASTER's
// pickup mark (main.js potAt) and the faceplate's toast:
//   LearnFeed      what the faceplate draws while learning: the learnable controls outlined, the bound ones marked,
//                  the picked one pulsing; the bar's text. A tap on a control picks it instead of playing it; the keys
//                  play nothing.
//   MidiLearnMode  runs it: MidiLearn (the engine's learn queue, polled on the faceplate's tick), the results as
//                  toasts, Forget, Done. The sheet's Learn starts it.
//   PickupFeed     a pot waiting to pick MASTER up: where it is, shown on MASTER for 4 s after it last moved.
//   ToastCenter    one short line at the bottom for a few seconds (js/main.js toast): learn's results, MASTER's
//                  pickup, the OMX-27. Tinted ok (green), bad (red) or neutral; a newer one replaces it.
package com.quixotic7.remotefm1.ui.faceplate

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.quixotic7.remotefm1.midi.LearnEngine
import com.quixotic7.remotefm1.midi.MidiLearn
import com.quixotic7.remotefm1.midi.MidiMapStore
import com.quixotic7.remotefm1.midi.MidiMapping
import com.quixotic7.remotefm1.midi.MidiSource
import com.quixotic7.remotefm1.midi.MidiTarget
import kotlin.math.roundToInt

/** MIDI learn's state on the faceplate (PanelFeed.learn) */
class LearnFeed {
    var active by mutableStateOf(false)
        internal set
    /** the picked control (a MidiTarget id), null: none */
    var selected by mutableStateOf<String?>(null)
        internal set
    /** the targets with a binding */
    var bound by mutableStateOf<Set<String>>(emptySet())
        internal set
    /** the bar's line */
    var text by mutableStateOf("")
        internal set
    /** Forget shown: the picked control has bindings */
    var canForget by mutableStateOf(false)
        internal set
    internal var mode: MidiLearnMode? = null

    /** a control tapped while learning (its MidiTarget id) */
    fun pick(target: String) { mode?.pick(target) }
    fun forget() { mode?.forget() }
    fun done() { mode?.stop() }
}

/** a learned MASTER pot (or the OMX-27's K5) away from MASTER: its position, 0..1023 (PanelFeed.pickup) */
class PickupFeed {
    var pot by mutableStateOf<Int?>(null)
        private set
    private var until = 0L

    internal fun show(v: Int, now: Long) {
        val c = v.coerceIn(0, 1023)
        if (pot != c) pot = c
        until = now + SHOW_FOR_MS
    }

    internal fun hide() { if (pot != null) pot = null }

    /** hidden 4 s after the last change */
    fun expire(now: Long) { if (pot != null && now >= until) pot = null }

    companion object {
        const val SHOW_FOR_MS = 4000L

        /** main.js mapAct's pickup toast: MASTER now (0..1023) as a percentage */
        fun text(master: Int): String =
            "MASTER: turn the pot to ${(master.coerceIn(0, 1023) / 1023.0 * 100).roundToInt()}% to pick it up"
    }
}

class ToastCenter {
    enum class Tone { NEUTRAL, OK, BAD }
    data class Toast(val id: Int, val text: String, val tone: Tone, val seconds: Double)

    var current by mutableStateOf<Toast?>(null)
        private set
    private var next = 0
    private var until = 0L

    /** shown for [seconds] (default: 4, bad 7, as the website) */
    fun show(text: String, tone: Tone = Tone.NEUTRAL, seconds: Double? = null) {
        val s = seconds ?: if (tone == Tone.BAD) 7.0 else 4.0
        next++
        current = Toast(next, text, tone, s)
        until = SystemClock.uptimeMillis() + (s * 1000).toLong()
    }

    /** hides it ([id]: only if it is still that one) */
    fun hide(id: Int? = null) {
        val c = current ?: return
        if (id == null || c.id == id) current = null
    }

    /** (the faceplate's tick) gone when its time is up */
    fun expire(now: Long) { if (current != null && now >= until) current = null }
}

class MidiLearnMode(val feed: PanelFeed, host: LearnEngine?, val store: MidiMapStore) {
    val learn = MidiLearn(store, host)
    private var seen = -1

    init {
        feed.learn.mode = this
    }

    val active: Boolean get() = learn.active

    fun attach(h: LearnEngine?) = learn.attach(h)

    /** learn mode on: the sheet closes and the Screen view gives way to the panel ([ui]: the faceplate's, if any) */
    fun start(ui: FaceplateUi? = null) {
        if (ui != null) {
            ui.sheetOpen = false
            ui.editorOpen = false
            if (ui.page == FaceplateUi.Page.SCREEN) ui.page = FaceplateUi.Page.FULL
        }
        feed.releaseAll()
        learn.start()
        feed.onTick = { tick() }
        refresh()
    }

    fun stop() {
        if (!learn.active) return
        learn.stop()
        feed.onTick = null
        refresh()
    }

    /** a control tapped (picked again: it stays picked) */
    fun pick(target: String) {
        if (!learn.active || learn.selected == target) return
        learn.select(target)
        refresh()
    }

    /** the picked control's bindings dropped (main.js learnForget) */
    fun forget() {
        val id = learn.selected ?: return
        val n = learn.forgetSelected()
        val t = feed.names.title(id)
        feed.toast.show(if (n == 0) "$t has no binding" else "$t: ${if (n == 1) "its binding" else "its $n bindings"} forgotten",
                        seconds = 2.5)
        refresh()
    }

    /** the faceplate's tick: the engine's learn queue taken, the results said */
    fun tick() {
        if (!learn.active) return
        for (r in learn.poll()) took(r)
        if (store.gen != seen) refresh()                    // (the sheet changed the map)
    }

    fun took(r: MidiLearn.Result) {
        when (r) {
            is MidiLearn.Result.Rejected -> feed.toast.show(r.why, ToastCenter.Tone.BAD, 2.5)
            is MidiLearn.Result.Bound -> feed.toast.show(boundText(r.mapping, feed.names), ToastCenter.Tone.OK, 3.0)
            MidiLearn.Result.None -> {}
        }
    }

    fun refresh() {
        val l = feed.learn
        seen = store.gen
        val on = learn.active
        if (l.active != on) l.active = on
        val sel = if (on) learn.selected else null
        if (l.selected != sel) l.selected = sel
        val bound = if (on) store.boundTargets else emptySet()
        if (l.bound != bound) l.bound = bound
        val srcs = sel?.let { store.sourcesOf(it) } ?: emptyList()
        val text = if (on) barText(sel, srcs, feed.names) else ""
        if (l.text != text) l.text = text
        if (l.canForget != srcs.isNotEmpty()) l.canForget = srcs.isNotEmpty()
    }

    companion object {
        /** main.js learnTook: "KN1 ← CC 21, channel 1 (absolute)" */
        fun boundText(b: MidiMapping, names: ButtonNames = ButtonNames.STANDARD): String =
            "${names.title(b.target)} ← ${MidiSource.label(b.src, long = true)}${b.mode?.let { " (${it.label})" } ?: ""}"

        /** main.js learnView's line */
        fun barText(selected: String?, sources: List<MidiMapping>, names: ButtonNames = ButtonNames.STANDARD): String {
            val id = selected ?: return "Tap a knob or button, then move a control on your MIDI device"
            val sel = names.title(id)
            if (sources.isNotEmpty()) return "$sel ← ${sources.joinToString(", ") { MidiSource.label(it.src) }}"
            val button = MidiTarget.named(id)?.isButton == true
            return "$sel: now ${if (button) "press a button or a key" else "turn a knob or a pot"} on your MIDI device"
        }
    }
}
