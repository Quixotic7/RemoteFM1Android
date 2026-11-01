// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/TouchTests.swift (testSettingIsKeptAndDefaultsOn) and
// MidiEngineTests.swift (testAppMidiSettingsAreKept: the dictionary's round trip)
// The app's MIDI settings: TOUCH on by default and kept, the keys' channel, transpose and the rest in iOS's dictionary
// (an older dictionary's notesPlayKeys read as the channel).
package com.quixotic7.remotefm1.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiSettingsTest {
    @Test fun touchIsKeptAndDefaultsOn() {
        assertTrue(MidiSettings().keyTouch)
        assertTrue(MidiSettings.fromJson("{}").keyTouch)
        assertTrue(MidiSettings.fromJson(null).keyTouch)
        assertEquals(3, MidiSettings().keyTouchFlags)
        val s = MidiSettings().copy(keyTouch = false)
        assertFalse(MidiSettings.fromJson(s.toJson()).keyTouch)
        assertEquals(0, MidiSettings.fromJson(s.toJson()).keyTouchFlags)
    }

    @Test fun settingsAreKept() {
        assertEquals(MidiSettings(), MidiSettings.fromJson(null))
        val s = MidiSettings(transpose = -1, keyNotesToFirmware = true)
        val back = MidiSettings.fromJson(s.toJson())
        assertEquals(-1, back.transpose)
        assertTrue(back.keyNotesToFirmware)
        assertEquals(KeyChannel.Any, back.keyChannel)
        assertEquals(KeyChannel.Channel(5), MidiSettings.fromJson(MidiSettings(keyChannel = KeyChannel.Channel(5)).toJson()).keyChannel)
        assertEquals(KeyChannel.Off, MidiSettings.fromJson("""{"notesPlayKeys":false}""").keyChannel)
        assertEquals("clamped to -2..2", 2, MidiSettings.fromJson("""{"transpose":7}""").transpose)
        assertFalse(MidiSettings.fromJson("""{"useLearnedMap":false}""").useLearnedMap)
        // the keys' channel as the engine takes it, and as the rows say it
        assertEquals(listOf(0, 3, -1), listOf(KeyChannel.Any, KeyChannel.Channel(3), KeyChannel.Off).map { it.engineValue })
        assertEquals(listOf("Any", "Ch 3", "Off"), listOf(KeyChannel.Any, KeyChannel.Channel(3), KeyChannel.Off).map { it.label })
        assertEquals("Channel 16", KeyChannel.of(16).longLabel)
        assertEquals(18, KeyChannel.groups.flatten().size)
    }
}
