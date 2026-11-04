// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Tests/RFMKitTests/ManagerTests.swift (the decoder, the editor list, the
// editor page, an old cache entry)
// The web editors' pieces without a WebView: USB-MIDI packets put back into whole messages, the authors' editors and
// ports, the screen reader, and which pages may talk to the player. (A SysEx through the firmware and back, and the
// page itself: the emulator's ManagerTest.)
package com.quixotic7.remotefm1.managers

import com.quixotic7.remotefm1.firmware.AvailableFirmware
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals("https://quixotic7.github.io/ChoralRootFM1/webapp/installer/manager.html", ManagerList.url("choralroot"))
        assertEquals("https://quixotic7.github.io/ChoralRootFM1/webapp/installer/manager.html", ManagerList.url("purplemonkey"))
        assertEquals("https://hugelton.github.io/Felucca/webapp/editor/", ManagerList.url("felucca"))
        assertFalse("never the site's copies", ManagerList.authors.values.any { java.net.URI(it).host == "remotefm1.com" })
        assertEquals("https://quixotic7.github.io/FIZZ/manager/", ManagerList.url("fizz"))
        assertNull(ManagerList.url("nonesuch"))
        assertNotNull("the site lists it as \"mutable\"", ManagerList.url("mutabledreams"))
        assertEquals("Felucca's editor looks for Felucca", "Felucca", ManagerList.port("rainbow", "Rainbow Mode"))
        assertEquals("Melodee FM-1", ManagerList.port("melodee", "Melodee"))
        // (RemoteFM1FW 31b8751, checked against the firmwares' USB descriptors)
        assertEquals("10.26's; JIANT's editor matches FM-1", "FM-1", ManagerList.port("jiant", "JIANT"))
        assertEquals("Hortator", ManagerList.port("hortator", "Hortator"))
        assertNull("its firmware answers no editor (RemoteFM1FW 5bcbb65)", ManagerList.url("hortator"))
        assertTrue("CTL-1's catalog id", ManagerList.hasReader("ctl1"))
        assertTrue(ManagerList.hasReader("amb"))
        assertFalse("his, but no screen reader support (0.4)", ManagerList.hasReader("ripple"))
        assertEquals("Nonesuch", ManagerList.port("nonesuch", "Nonesuch"))
    }

    /** the page: the catalog's editor link (the author's, https only), else the author's page the app knows */
    @Test fun editorPage() {
        val sloop = AvailableFirmware("sloop", editor = "https://isod89.github.io/sloop-fm1/webapp/editor/")
        val bad = AvailableFirmware("old", editor = "http://example.com/editor")
        val felucca = AvailableFirmware("felucca", editor = "https://hugelton.github.io/Felucca/webapp/editor/")
        val d = mapOf("sloop" to sloop, "old" to bad, "felucca" to felucca)
        assertEquals(sloop.editor, ManagerList.editor("sloop", d))
        assertEquals("https://hugelton.github.io/Felucca/webapp/editor/", ManagerList.editor("felucca", d))
        assertNull("not https", ManagerList.editor("old", d))
        assertEquals("not in the catalog", ManagerList.authors["choralroot"], ManagerList.editor("choralroot", d))
        val moved = AvailableFirmware("melodee", editor = "https://keremimo.github.io/melodee-next/editor/")
        assertEquals("the catalog's link wins: the author may move it", moved.editor,
                     ManagerList.editor("melodee", mapOf("melodee" to moved)))
        assertNull(ManagerList.editor("nonesuch", d))
    }

    /** RemoteFM1FW's catalog `port` wins over the app's list (f02182b) */
    @Test fun catalogPortWins() {
        val j = AvailableFirmware("jiant", port = "JIANT FM")
        assertEquals("JIANT FM", ManagerList.port("jiant", "JIANT", mapOf("jiant" to j)))
        assertEquals("an empty port: the list", "FM-1", ManagerList.port("jiant", "JIANT", mapOf("jiant" to AvailableFirmware("jiant"))))
    }

    /** an older cache without `editor` still reads */
    @Test fun oldCacheEntryDecodes() {
        val e = AvailableFirmware.fromMap(mapOf("id" to "amb", "name" to "AMB-1", "version" to "1.3", "downloads" to emptyList<Any>()))!!
        assertEquals("AMB-1", e.name)
        assertEquals("", e.editor)
    }

    @Test fun onlyHttpsPagesTalkToThePlayer() {
        assertTrue(ManagerList.trusted("https://hugelton.github.io/Felucca/webapp/editor/"))
        assertTrue(ManagerList.trusted(ManagerList.READER))
        assertFalse(ManagerList.trusted("http://hugelton.github.io/Felucca/webapp/editor/"))
        assertFalse(ManagerList.trusted("file:///sdcard/x.html"))
        assertFalse(ManagerList.trusted("javascript:alert(1)"))
        assertFalse(ManagerList.trusted(null))
    }

    @Test fun messageLengths() {
        assertEquals(listOf(3, 3, 2, 2, 3, 2, 3, 2, 1), listOf(0x90, 0xB3, 0xC0, 0xD5, 0xE0, 0xF1, 0xF2, 0xF3, 0xF8).map { UsbMidiDecoder.messageLength(it) })
    }
}
