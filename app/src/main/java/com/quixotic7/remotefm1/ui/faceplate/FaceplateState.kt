// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/Faceplate.swift (FaceplateUI, PanelChip) and
// ScreenProtector.swift (ScreenProtector)
// The faceplate's own state (the view, the deck, the knob set, the keybeds' scroll, the sheet, the display alone),
// the status chip, and the screen protector's one setting.
package com.quixotic7.remotefm1.ui.faceplate

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class FaceplateUi {
    enum class Page(val raw: String) { FULL("full"), CONTROLS("controls"), SCREEN("screen");
        companion object { fun of(s: String?) = entries.firstOrNull { it.raw == s } } }
    enum class Deck(val raw: String) { BUTTONS("buttons"), KEYS("keys");
        companion object { fun of(s: String?) = entries.firstOrNull { it.raw == s } } }
    enum class KnobSet(val raw: String) { MAIN("main"), KN("kn");
        companion object { fun of(s: String?) = entries.firstOrNull { it.raw == s } } }

    var page by mutableStateOf(Page.FULL)
    var deck by mutableStateOf(Deck.BUTTONS)
    var knobSet by mutableStateOf(KnobSet.MAIN)
    var fullOffset by mutableFloatStateOf(-1f)     // the Full keybed's scroll (-1: its default)
    var deckOffset by mutableFloatStateOf(-1f)
    var stepNumbers by mutableStateOf(false)        // step numbers 1-16 on the naturals (Settings)
    var sheetOpen by mutableStateOf(false)
    var editorOpen by mutableStateOf(false)
    var displayAlone by mutableStateOf(false)       // the display alone, full size (a double tap on it)
    var firmwareName by mutableStateOf<String?>(null)   // the running firmware: the status row's word

    val word: String get() = word(firmwareName)

    /** the next or previous view (a sideways swipe: +1 / -1) */
    fun move(d: Int) {
        val i = page.ordinal + d
        if (i in Page.entries.indices) page = Page.entries[i]
    }

    companion object {
        /** the status row's word: the firmware's name in capitals, REMOTE FM1 without one */
        fun word(firmware: String?): String = firmware?.trim().orEmpty().let { if (it.isEmpty()) "REMOTE FM1" else it.uppercase() }
    }
}

/** the status chip: which way the FM-1 is played */
enum class PanelChip(val text: String) {
    EMULATOR("Emulator"), LINKED("FM-1 linked"), IN_USE("FM-1 in use"), SEARCHING("FM-1 searching")
}

/** whether the film is still on (default: on, as the FM-1 comes); remembered */
class ScreenProtector(private val prefs: SharedPreferences?) {
    var on by mutableStateOf(prefs?.getBoolean(KEY, true) ?: true)
        private set
    /** (screenshots) the film drawn this far folded at first */
    var startFold = 0f

    fun set(v: Boolean) {
        on = v
        prefs?.edit()?.putBoolean(KEY, v)?.apply()
    }

    companion object {
        const val KEY = "rfm1.screenProtector"
    }
}
