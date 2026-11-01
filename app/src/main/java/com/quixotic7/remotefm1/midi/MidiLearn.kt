// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/MIDI/MidiLearn.swift
// MIDI learn (M8): js/midi-map.js Learn. A control picked on the panel, then the next CC or note binds to it; while
// the control keeps moving, the mode is guessed again from its values. The engine diverts the messages (rfme_learn,
// armed only while learn is on and a control is picked: CCs and note-ons from a controller then play nothing), and
// poll() takes them from its learn queue.
package com.quixotic7.remotefm1.midi

/** the engine's learn queue (RfmEngine.learn / learnTake) */
interface LearnEngine {
    fun learn(armed: Boolean)
    fun learnTake(msg: ByteArray): Boolean
}

class MidiLearn(val store: MidiMapStore, host: LearnEngine?) {
    sealed class Result {
        /** fresh: just bound (else the mode guess changed as it kept moving) */
        data class Bound(val mapping: MidiMapping, val fresh: Boolean) : Result()
        /** a note on a knob: why */
        data class Rejected(val why: String) : Result()
        /** consumed, nothing new */
        data object None : Result()
    }

    var host: LearnEngine? = host
        private set
    var active = false
        private set
    var selected: String? = null
        private set
    private var curSrc: String? = null
    private var curTarget: String? = null
    private val curValues = ArrayList<Int>()
    private var armed = false

    fun attach(h: LearnEngine?) {
        disarm()
        host = h
        arm()
    }

    fun start() {
        active = true
        selected = null
        curSrc = null
        arm()
    }

    fun stop() {
        active = false
        selected = null
        curSrc = null
        arm()
    }

    /** a target id (MidiTarget.id) to bind to, or null */
    fun select(target: String?) {
        selected = target?.takeIf { MidiTarget.named(it) != null }
        curSrc = null
        arm()
    }

    /** the picked control's bindings removed; how many */
    fun forgetSelected(): Int = selected?.let { store.forget(it) } ?: 0

    /** what learn makes of one message; null: it leaves it alone (nothing picked, or a note-off of a source not bound) */
    fun take(d: ByteArray): Result? {
        val sel = selected
        if (!active || sel == null) return null
        val m = MidiMessage.of(d) ?: return null
        val key = m.source
        if (m.kind == MidiMessage.Kind.NOTE && !m.on) return if (store.map.has(key)) Result.None else null
        val why = MidiMap.canBind(sel, m)
        if (why.isNotEmpty()) return Result.Rejected(why)
        if (curSrc == key && curTarget == sel) {
            if (curValues.size < 24) curValues.add(m.value)
            val mode = store.map.get(key)?.mode ?: return Result.None
            val g = MidiMode.guess(curValues)
            if (g == mode) return Result.None
            store.setMode(key, g)
            return store.map.get(key)?.let { Result.Bound(it, false) } ?: Result.None
        }
        val isEnc = MidiTarget.named(sel)?.isEncoder == true
        val mode = if (isEnc && m.kind == MidiMessage.Kind.CC) MidiMode.guess(listOf(m.value)) else null
        val mp = store.bind(key, sel, mode) ?: return Result.None
        curSrc = key
        curTarget = sel
        curValues.clear()
        curValues.add(m.value)
        return Result.Bound(mp, true)
    }

    private val msg = ByteArray(3)

    /** the engine's learn queue taken: what came of each message (consumed ones that changed nothing left out) */
    fun poll(): List<Result> {
        val h = host ?: return emptyList()
        var out: ArrayList<Result>? = null
        while (h.learnTake(msg)) {
            val r = take(msg.copyOf())
            if (r != null && r != Result.None) (out ?: ArrayList<Result>().also { out = it }).add(r)
        }
        return out ?: emptyList()
    }

    // the engine diverts messages only while a control is picked
    private fun arm() {
        val want = active && selected != null && host != null
        if (want == armed) return
        armed = want
        host?.learn(want)
    }

    private fun disarm() {
        if (armed) host?.learn(false)
        armed = false
    }
}
