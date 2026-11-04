// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Tests/RFMKitTests/UpdateNoticeTests.swift: a newer build, firmware updates,
// closed once per set; this repo's release/android.json reads.
package com.quixotic7.remotefm1.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateNoticeTest {
    @Test fun notice() {
        val latest = LatestBuild(20, "0.2.0", "https://github.com/Quixotic7/RemoteFM1Android/releases/latest")
        assertNull("nothing new", UpdateNotice.make(20, latest, emptyList()))
        assertNull("newer than the file (a local build)", UpdateNotice.make(21, latest, emptyList()))
        assertNull("offline", UpdateNotice.make(19, null, emptyList()))
        val n = UpdateNotice.make(19, latest, listOf("JIANT" to "10.26", "Rainbow Mode" to "1.1.5-rb", "AMB-1" to "1.4",
            "Felucca" to "1.8.1"))!!
        assertEquals(20, n.newBuild)
        assertEquals("https://github.com/Quixotic7/RemoteFM1Android/releases/latest", n.url)
        assertEquals("Build 20 (0.2.0) is on GitHub; you have build 19. Please update before sending feedback." +
            "\n\nUpdates for AMB-1, Felucca, JIANT and 1 more in Get firmware.", n.message(19))
        assertEquals("Updates for DUELL and Ripple in Get firmware.",
            UpdateNotice.make(20, latest, listOf("Ripple" to "0.5", "DUELL" to "1.1"))?.message(20))
        val fw = UpdateNotice.make(20, latest, listOf("Ripple" to "0.5"))!!
        assertNull(fw.newBuild)
        assertEquals("Firmware updates", fw.title)
        assertEquals("An update for Ripple in Get firmware.", fw.message(20))
        // closed: not again for the same set; again when something newer comes
        var stored: String? = null
        assertFalse(n.dismissed(stored))
        stored = n.signature
        assertTrue(n.dismissed(stored))
        assertTrue("the same set in another order", UpdateNotice.make(19, latest, listOf("JIANT" to "10.26",
            "Rainbow Mode" to "1.1.5-rb", "Felucca" to "1.8.1", "AMB-1" to "1.4"))!!.dismissed(stored))
        assertFalse(UpdateNotice.make(19, LatestBuild(21), emptyList())!!.dismissed(stored))
        // this repo's file
        val f = listOf(File("../release/android.json"), File("release/android.json")).first { it.isFile }
        val l = LatestBuild.parse(f.readBytes())
        assertNotNull(l)
        assertEquals("https://github.com/Quixotic7/RemoteFM1Android/releases/latest", l?.url)
        assertNull(LatestBuild.parse("<html>".toByteArray()))
    }
}
