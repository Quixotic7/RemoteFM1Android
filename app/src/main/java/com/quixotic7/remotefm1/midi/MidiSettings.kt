// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/AUv3/Shared.swift (AUSettings, the app's part: CorePlayer's
// midiSettings). The app's MIDI settings: whose notes play the keys, transpose, key notes also to the firmware, the
// learned map on, and TOUCH (the FM-1's own velocity and pressure). Kept as iOS's dictionary (the same keys), in JSON.
package com.quixotic7.remotefm1.midi

import org.json.JSONObject

/** whose MIDI notes play the FM-1's keys: any channel, one, or none */
sealed class KeyChannel {
    data object Any : KeyChannel()
    data class Channel(val n: Int) : KeyChannel()
    data object Off : KeyChannel()

    /** short, for a row's value */
    val label: String get() = when (this) { Any -> "Any"; is Channel -> "Ch $n"; Off -> "Off" }

    /** in full, for the page's rows */
    val longLabel: String get() = when (this) { Any -> "Any"; is Channel -> "Channel $n"; Off -> "Off" }

    /** rfme_set_midi's key_channel: 0 any, 1..16, -1 off */
    val engineValue: Int get() = when (this) { Any -> 0; is Channel -> n; Off -> -1 }

    companion object {
        fun of(v: Int): KeyChannel = if (v == 0) Any else if (v in 1..16) Channel(v) else Off
        /** the page's groups: Any, Channel 1-16, Off */
        val groups: List<List<KeyChannel>> = listOf(listOf(Any), (1..16).map { Channel(it) }, listOf(Off))
    }
}

data class MidiSettings(
    val keyChannel: KeyChannel = KeyChannel.Any,   // MIDI notes on it play the FM-1's keys; the rest go to the firmware
    val transpose: Int = 0,                        // octaves, -2..2
    val keyNotesToFirmware: Boolean = false,       // the notes that played a key also go to the firmware's MIDI in
    val useLearnedMap: Boolean = true,             // MIDI learn's knobs and buttons play (off: kept, their messages flow on)
    val keyTouch: Boolean = true,                  // TOUCH (RemoteFM1 0.3.1+): the FM-1's keys' velocity and pressure
) {
    /** rfmc_set_key_touch's flags (RFMC_TOUCH_VEL | RFMC_TOUCH_PRESS) */
    val keyTouchFlags: Int get() = if (keyTouch) 3 else 0

    /** notes play keys at all (the old on/off) */
    val notesPlayKeys: Boolean get() = keyChannel != KeyChannel.Off

    /** iOS's dictionary (notesPlayKeys too, for an older build reading it) */
    fun toJson(): String = JSONObject()
        .put("keyChannel", keyChannel.engineValue).put("notesPlayKeys", notesPlayKeys).put("transpose", transpose)
        .put("keyNotesToFirmware", keyNotesToFirmware).put("useLearnedMap", useLearnedMap).put("keyTouch", keyTouch)
        .toString()

    companion object {
        const val PREFS_KEY = "rfm1.midiSettings"

        fun fromJson(s: String?): MidiSettings {
            val d = runCatching { JSONObject(s ?: "") }.getOrNull() ?: return MidiSettings()
            val ch = when {
                d.has("keyChannel") -> KeyChannel.of(d.optInt("keyChannel", 0))
                d.has("notesPlayKeys") -> if (d.optBoolean("notesPlayKeys", true)) KeyChannel.Any else KeyChannel.Off
                else -> KeyChannel.Any
            }
            return MidiSettings(ch, d.optInt("transpose", 0).coerceIn(-2, 2), d.optBoolean("keyNotesToFirmware", false),
                                d.optBoolean("useLearnedMap", true), d.optBoolean("keyTouch", true))
        }
    }
}
