// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/ManagerTests.swift (the decoder, the manager list)
// The web managers' pieces without a WebView: USB-MIDI packets put back into whole messages, the site's managers and
// ports, and which pages may talk to the player. (A SysEx through the firmware and back, and the page itself: the
// emulator's ManagerTest.)
package com.quixotic7.remotefm1.managers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagerListTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun decoderPutsMessagesBackTogether() {
        val d = UsbMidiDecoder()
        assertNull(d.feed(0x4 or (0xF0 shl 8) or (0x7D shl 16) or (0x01 shl 24)))
        assertNull(d.feed(0x4 or (0x02 shl 8) or (0x03 shl 16) or (0x04 shl 24)))
        assertArrayEquals(b(0xF0, 0x7D, 0x01, 0x02, 0x03, 0x04, 0x05, 0xF7), d.feed(0x6 or (0x05 shl 8) or (0xF7 shl 16)))
        assertNull("an end with nothing begun", d.feed(0x5 or (0xF7 shl 8)))
        assertArrayEquals(b(0x90, 0x3C, 0x64), d.feed(0x9 or (0x90 shl 8) or (0x3C shl 16) or (0x64 shl 24)))
        assertArrayEquals(b(0xC0, 0x05), d.feed(0xC or (0xC0 shl 8) or (0x05 shl 16)))
        assertArrayEquals(b(0xF8), d.feed(0xF or (0xF8 shl 8)))
    }

    @Test fun managerList() {
        assertEquals("https://remotefm1.com/managers/choralroot/", ManagerList.url("choralroot"))
        assertEquals("https://remotefm1.com/managers/choralroot/", ManagerList.url("purplemonkey"))
        assertEquals("https://remotefm1.com/managers/ghoulbox/", ManagerList.url("ghoulbox"))
        assertNull(ManagerList.url("fizz"))
        assertEquals("Melodee FM-1", ManagerList.port("melodee", "Melodee"))
        assertEquals("Nonesuch", ManagerList.port("nonesuch", "Nonesuch"))
        assertEquals(6, ManagerList.managers.size)
    }

    @Test fun onlyTheSiteTalksToThePlayer() {
        assertTrue(ManagerList.trusted("https://remotefm1.com/managers/choralroot/"))
        assertFalse(ManagerList.trusted("http://remotefm1.com/managers/choralroot/"))
        assertFalse(ManagerList.trusted("https://remotefm1.com.example.org/"))
        assertFalse(ManagerList.trusted("https://evil.example/remotefm1.com"))
        assertFalse(ManagerList.trusted(null))
    }

    @Test fun messageLengths() {
        assertEquals(listOf(3, 3, 2, 2, 3, 2, 3, 2, 1), listOf(0x90, 0xB3, 0xC0, 0xD5, 0xE0, 0xF1, 0xF2, 0xF3, 0xF8).map { UsbMidiDecoder.messageLength(it) })
    }
}
