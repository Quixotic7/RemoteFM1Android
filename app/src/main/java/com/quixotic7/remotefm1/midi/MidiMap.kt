// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/MIDI/MidiMap.swift
// MIDI learn's map (M8): a controller's knobs, pots and buttons on the panel's knobs, MASTER and buttons. The website's
// js/midi-map.js, with its ids and stored form, so a map reads the same in both (and in the iOS app):
//   sources   "cc:<channel>:<number>" or "note:<channel>:<number>", channel 1..16 (notes: buttons only)
//   targets   SELECT ALGORITHM PRESETS KN1..KN4 (encoders), MASTER, and the 14 buttons by their printed names
//   modes     a CC on an encoder: abs, rel64, rel2c, relsign (rfme_map_entry_t's RFME_MAP_*)
//   stored    {"midiMap": [{src, target, mode?}], "knobSpeed": 0.5 | 1 | 2}
// Only the list lives here; the engine runs it (RFMEngine.h rfme_set_midi_map, on the core thread).
package com.quixotic7.remotefm1.midi

import com.quixotic7.remotefm1.ui.faceplate.PanelKnob
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel
import org.json.JSONArray
import org.json.JSONObject

/** how a CC turns an encoder */
enum class MidiMode(val raw: String, val label: String, val engine: Int) {
    ABS("abs", "absolute", 0), REL64("rel64", "relative 64±", 1), REL2C("rel2c", "relative 2's comp.", 2),
    RELSIGN("relsign", "relative sign bit", 3);

    /** the sheet's short form (sheet.js MODE_SHORT) */
    val short: String
        get() = when (this) {
            ABS -> "absolute"
            REL64 -> "rel. 64 ± n"
            REL2C -> "rel. 2's comp."
            RELSIGN -> "rel. sign bit"
        }

    companion object {
        fun of(raw: String?): MidiMode? = entries.firstOrNull { it.raw == raw }

        /** the mode, from the values a CC sent while it moved (learn). A pot sweeping (four or more values, each
         *  different, one way) is absolute; values hugging 64 are binary offset; values only in 1..15 or 113..127
         *  two's complement */
        fun guess(v: List<Int>): MidiMode {
            if (v.isEmpty()) return ABS
            fun sign(x: Int) = if (x > 0) 1 else if (x < 0) -1 else 0
            var sweep = v.size >= 4
            var i = 2
            while (i < v.size && sweep) { sweep = sign(v[i] - v[i - 1]) == sign(v[1] - v[0]); i++ }
            i = 1
            while (i < v.size && sweep) { sweep = v[i] != v[i - 1]; i++ }
            if (sweep) return ABS
            if (v.any { it != 64 } && v.all { kotlin.math.abs(it - 64) <= 7 }) return REL64
            if (v.all { it in 1..15 || it in 113..127 }) return REL2C
            return ABS
        }
    }
}

/** a control a source can drive */
data class MidiTarget(val id: String, val encoder: PanelKnob? = null, val master: Boolean = false, val button: PanelLabel? = null) {
    val isButton: Boolean get() = button != null
    val isEncoder: Boolean get() = encoder != null

    /** rfme_map_entry_t.target */
    val engine: Int
        get() = encoder?.ordinal ?: if (master) T_MASTER else T_BUTTON + button!!.ordinal

    companion object {
        const val T_MASTER = 7
        const val T_BUTTON = 8

        /** panel.js PANEL_LABELS (the printed names; plain "OCT-", not the legend's minus sign) */
        val BUTTON_NAMES = listOf("FX", "SEL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ", "PLAY", "REC",
                                  "OCT-", "OCT+")

        /** midi-map.js TARGETS: the 7 encoders, MASTER, the 14 buttons */
        val all: List<MidiTarget> = listOf("SELECT" to PanelKnob.SELECT, "ALGORITHM" to PanelKnob.ALGORITHM,
                                           "PRESETS" to PanelKnob.PRESETS, "KN1" to PanelKnob.K1, "KN2" to PanelKnob.K2,
                                           "KN3" to PanelKnob.K3, "KN4" to PanelKnob.K4).map { MidiTarget(it.first, encoder = it.second) } +
            MidiTarget("MASTER", master = true) + PanelLabel.entries.map { MidiTarget(BUTTON_NAMES[it.ordinal], button = it) }

        private val byId = all.associateBy { it.id }

        fun named(id: String?): MidiTarget? = id?.let { byId[it] }
        fun knob(k: PanelKnob): MidiTarget = if (k == PanelKnob.MASTER) byId["MASTER"]!! else all.first { it.encoder == k }
        fun button(l: PanelLabel): MidiTarget = byId[BUTTON_NAMES[l.ordinal]]!!
    }
}

/** a channel message, as learn and the map read it (midi-map.js parseMsg) */
data class MidiMessage(val kind: Kind, val channel: Int, val number: Int, val value: Int, val on: Boolean = false) {
    enum class Kind(val raw: String) { CC("cc"), NOTE("note") }

    /** srcKey: "cc:1:21" */
    val source: String get() = "${kind.raw}:$channel:$number"

    companion object {
        /** a CC or a note (3 bytes); null otherwise */
        fun of(d: ByteArray): MidiMessage? {
            if (d.size < 3) return null
            val b0 = d[0].toInt() and 0xFF
            val s = b0 and 0xF0
            val ch = (b0 and 15) + 1
            val n = d[1].toInt() and 127
            val v = d[2].toInt() and 127
            return when (s) {
                0xB0 -> MidiMessage(Kind.CC, ch, n, v)
                0x90, 0x80 -> MidiMessage(Kind.NOTE, ch, n, v, s == 0x90 && v > 0)
                else -> null
            }
        }
    }
}

/** the source ids */
object MidiSource {
    /** srcLabel: "CC 21 ch 1"; long: "CC 21, channel 1" (no break before the channel) */
    fun label(key: String, long: Boolean = false): String {
        val p = key.split(":")
        val type = p.getOrElse(0) { "" }
        val ch = p.getOrElse(1) { "" }
        val num = p.getOrElse(2) { "" }
        return "${if (type == "cc") "CC" else "note"} $num${if (long) ", channel " else " ch "}$ch"
    }

    /** validSrc: cc or note, channel 1..16, number 0..127, written plainly */
    fun isValid(key: String): Boolean = parse(key) != null

    data class Parsed(val note: Boolean, val channel: Int, val number: Int)

    fun parse(key: String): Parsed? {
        val p = key.split(":")
        if (p.size != 3 || (p[0] != "cc" && p[0] != "note") || !plain(p[1]) || !plain(p[2])) return null
        val ch = p[1].toIntOrNull() ?: return null
        val n = p[2].toIntOrNull() ?: return null
        if (ch !in 1..16 || n !in 0..127) return null
        return Parsed(p[0] == "note", ch, n)
    }

    private fun plain(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' } && (s.length == 1 || s[0] != '0')
}

/** one binding: a source on a target; the mode only for an encoder's CC */
data class MidiMapping(val src: String, val target: String, val mode: MidiMode? = null) {
    /** one line of the list: "KN1 ← CC 21 ch 1 · absolute" */
    val description: String get() = "$target ← ${MidiSource.label(src)}${mode?.let { " · " + it.label } ?: ""}"
}

/** the learned map: the bindings in order (the newest last) and the knob speed */
class MidiMap(list: List<MidiMapping> = emptyList(), speed: Double = 1.0) {
    var mappings: List<MidiMapping> = emptyList()
        private set
    var speed: Double = speedOf(speed)
        private set

    init {
        load(list.map { Triple(it.src, it.target, it.mode?.raw) })
    }

    // the stored list, checked (anything odd is left out; a source twice: the later binding in the first's place)
    private fun load(list: List<Triple<String?, String?, String?>>) {
        val out = ArrayList<MidiMapping>()
        for ((src, target, mode) in list) {
            if (src == null || target == null || !MidiSource.isValid(src)) continue
            val t = MidiTarget.named(target) ?: continue
            if (src.startsWith("note:") && !t.isButton) continue
            val def = modeFor(t, src)
            val mp = MidiMapping(src, target, if (def == null) null else MidiMode.of(mode) ?: def)
            val i = out.indexOfFirst { it.src == src }
            if (i >= 0) out[i] = mp else out.add(mp)
        }
        mappings = out
    }

    fun copy(): MidiMap = MidiMap().also { it.mappings = mappings; it.speed = speed }

    fun has(src: String) = mappings.any { it.src == src }
    fun get(src: String) = mappings.firstOrNull { it.src == src }
    val boundTargets: Set<String> get() = mappings.map { it.target }.toSet()
    fun sourcesOf(target: String) = mappings.filter { it.target == target }

    /** a source on a target (a source already bound moves; the list keeps the newest last); null: not allowed */
    fun bind(src: String, target: String, mode: MidiMode? = null): MidiMapping? {
        if (!MidiSource.isValid(src)) return null
        val t = MidiTarget.named(target) ?: return null
        if (src.startsWith("note:") && !t.isButton) return null
        val def = modeFor(t, src)
        val mp = MidiMapping(src, target, if (def == null) null else mode ?: def)
        mappings = mappings.filter { it.src != src } + mp
        return mp
    }

    /** an encoder's CC only */
    fun setMode(src: String, mode: MidiMode) {
        val i = mappings.indexOfFirst { it.src == src }
        if (i < 0 || mappings[i].mode == null) return
        mappings = mappings.toMutableList().also { it[i] = it[i].copy(mode = mode) }
    }

    fun remove(src: String): Boolean {
        val n = mappings.size
        mappings = mappings.filter { it.src != src }
        return mappings.size != n
    }

    /** a target's bindings removed; how many */
    fun forget(target: String): Int {
        val n = mappings.size
        mappings = mappings.filter { it.target != target }
        return n - mappings.size
    }

    fun clear() { mappings = emptyList() }
    fun setSpeed(v: Double) { speed = speedOf(v) }

    /** the engine's form: 5 ints per entry (src, channel 0..15, number, target, mode) for RfmEngine.setMidiMap */
    val entries: IntArray
        get() {
            val out = ArrayList<Int>()
            for (mp in mappings) {
                val s = MidiSource.parse(mp.src) ?: continue
                val t = MidiTarget.named(mp.target) ?: continue
                out += listOf(if (s.note) SRC_NOTE else SRC_CC, s.channel - 1, s.number, t.engine, mp.mode?.engine ?: 0)
            }
            return out.toIntArray()
        }

    // the stored form (the website's prefs.midiMap and prefs.knobSpeed)
    val json: String
        get() {
            val list = JSONArray()
            for (mp in mappings) {
                val d = JSONObject().put("src", mp.src).put("target", mp.target)
                mp.mode?.let { d.put("mode", it.raw) }
                list.put(d)
            }
            return JSONObject().put("knobSpeed", speed).put("midiMap", list).toString()
        }

    override fun equals(other: Any?): Boolean = other is MidiMap && other.mappings == mappings && other.speed == speed
    override fun hashCode(): Int = mappings.hashCode() * 31 + speed.hashCode()
    override fun toString(): String = "MidiMap($mappings, $speed)"

    companion object {
        const val SRC_CC = 0
        const val SRC_NOTE = 1
        val SPEEDS = listOf(0.5, 1.0, 2.0)
        fun speedOf(v: Double) = if (v in SPEEDS) v else 1.0

        private fun modeFor(t: MidiTarget, src: String): MidiMode? = if (t.isEncoder && src.startsWith("cc:")) MidiMode.ABS else null

        /** why a message can't drive a target ("" when it can) */
        fun canBind(targetId: String, m: MidiMessage?): String {
            val t = MidiTarget.named(targetId)
            if (t == null || m == null) return "no such control"
            if (m.kind == MidiMessage.Kind.NOTE && !t.isButton) return "${t.id} takes a CC: move a knob or a pot"
            return ""
        }

        /** the stored form read (anything unreadable: an empty map) */
        fun fromJson(text: String?): MidiMap {
            val o = runCatching { JSONObject(text ?: "") }.getOrNull() ?: return MidiMap()
            val a = o.optJSONArray("midiMap") ?: JSONArray()
            val list = (0 until a.length()).map { i ->
                val d = a.opt(i) as? JSONObject
                Triple(d?.opt("src") as? String, d?.opt("target") as? String, d?.opt("mode") as? String)
            }
            val sp = (o.opt("knobSpeed") as? Number)?.toDouble() ?: 1.0
            return MidiMap().also { it.speed = speedOf(sp); it.load(list) }
        }
    }
}
