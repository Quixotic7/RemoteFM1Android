// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/ButtonNames.swift (and PanelFeed.swift's Label, Knob)
// The firmware's own button names (RemoteFM1 docs/BUTTON-LABELS.md): the running core's descriptor button_names
// (fm1core.h, FM1P_B_* order), read through the engine (rfme_button_names), and the rules for drawing them, as the
// website's panel.js buttonLabels: per button the firmware's name trimmed, else the printed one; all printed when the
// list is missing or not 14 long, or when the "Firmware's button names" setting is off; OCT-/OCT+ never renamed. The
// face shows the name upper-cased; a renamed button has its printed name drawn small beside the row.
package com.quixotic7.remotefm1.ui.faceplate

/** button labels (fm1core.h FM1P_B_*, RFME_B_*); the faceplate's order is [GRID] */
enum class PanelLabel(val legend: String) {
    FX("FX"), SEL("SEL"), ENV("ENV"), LFO("LFO"), EDIT("EDIT"), GLO("GLO"), HOME("HOME"), SAVE("SAVE"), ARP("ARP"),
    SEQ("SEQ"), PLAY("PLAY"), REC("REC"), OCT_DOWN("OCT−"), OCT_UP("OCT+");

    val spoken: String
        get() = when (this) {
            PLAY -> "Play / Stop"
            REC -> "Record"
            OCT_DOWN -> "Octave down"
            OCT_UP -> "Octave up"
            else -> legend
        }

    companion object {
        val GRID = listOf(FX, SEL, ENV, LFO, EDIT, GLO, HOME, SAVE, ARP, SEQ, PLAY, REC)
    }
}

/** knob roles (fm1core.h FM1P_E_*, RFME_E_*); MASTER is the pot */
enum class PanelKnob(val label: String) {
    SELECT("Select"), ALGORITHM("Algorithm"), PRESETS("Presets"), K1("KN1"), K2("KN2"), K3("KN3"), K4("KN4"), MASTER("Master");

    companion object {
        val MAIN = listOf(MASTER, SELECT, PRESETS, ALGORITHM)
        val KN = listOf(K1, K2, K3, K4)
    }
}

class ButtonNames(firmware: List<String?>?, use: Boolean = true) {
    /** what each button's face shows, by label (upper-cased) */
    val labels: List<String>
    /** the button shows a name of the firmware's that differs from the printed one */
    val renamed: List<Boolean>

    init {
        val fw: List<String?> = if (use && firmware != null && firmware.size == 14) firmware else List(14) { null }
        val l = ArrayList<String>(14)
        val r = ArrayList<Boolean>(14)
        for ((i, p) in PRINTED.withIndex()) {
            val n = (fw[i] ?: "").trim().uppercase()
            val mine = n.isNotEmpty() && i < PanelLabel.OCT_DOWN.ordinal && n != p
            l.add(if (mine) n else p)
            r.add(mine)
        }
        labels = l
        renamed = r
    }

    fun label(l: PanelLabel) = labels[l.ordinal]
    fun isRenamed(l: PanelLabel) = renamed[l.ordinal]
    val anyRenamed get() = renamed.contains(true)

    override fun equals(other: Any?) = other is ButtonNames && other.labels == labels && other.renamed == renamed
    override fun hashCode() = labels.hashCode() * 31 + renamed.hashCode()

    companion object {
        /** the printed faceplate, by label */
        val PRINTED = PanelLabel.entries.map { it.legend }
        val STANDARD = ButtonNames(null)
    }
}
