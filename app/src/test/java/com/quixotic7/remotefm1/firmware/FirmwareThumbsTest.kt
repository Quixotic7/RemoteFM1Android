// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/FirmwareThumbsTests.swift (testSaveLoadAndBlank,
// testCaptureTiming, testInfoLookup): the thumbnails (save, an all-black frame refused, the capture's timing, shipped
// ones preferred) and the Firmware card's info (the catalog's details by id, the source link's order, the core's own
// fallback). The PNG writer is a stand-in here (Bitmap is Android's; FirmwareTest writes a real one).
package com.quixotic7.remotefm1.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FirmwareThumbsTest {
    private fun thumbs(shipped: Set<String> = emptySet()) = FirmwareThumbs(Files.createTempDirectory("rfm-th").toFile(),
        shipped = { if (it in shipped) byteArrayOf(1) else null },
        writePng = { rgba, w, h, f -> f.writeBytes(rgba.copyOf(w * h * 4)); true })

    private fun frame(lit: Boolean) = ByteArray(240 * 240 * 4).also { b ->
        if (lit) for (i in 0 until b.size / 2 step 4) { b[i] = 200.toByte(); b[i + 1] = 120 }
    }

    @Test fun saveAndBlank() {
        val t = thumbs()
        assertNull(t.png("made-up-fw"))
        assertFalse(t.save(frame(false), "made-up-fw"))
        assertFalse(t.exists("made-up-fw"))
        assertTrue(t.save(frame(true), "made-up-fw"))
        assertTrue(t.exists("made-up-fw"))
        assertEquals(1, t.generation.value)
        assertEquals(240 * 240 * 4, t.png("made-up-fw")!!.size)
        assertEquals("rfm1_ab_c.png", t.file("rfm1:ab/c").name)
        assertTrue(FirmwareThumbs.isBlank(frame(false)))
        assertFalse(FirmwareThumbs.isBlank(frame(true)))
        assertTrue(t.dir.list()!!.none { it.startsWith(".") })        // (no temporary file left)
    }

    @Test fun captureTiming() {
        val t = thumbs(shipped = setOf("felucca"))
        val c = ThumbCapture(t)
        assertFalse(c.frame(frame(true), "other-fw", true, 100.0))
        assertFalse(c.frame(frame(true), "other-fw", true, 102.0))
        assertFalse(c.frame(frame(false), "other-fw", true, 103.5))  // black: retried
        assertTrue(c.frame(frame(true), "other-fw", true, 104.0))
        assertFalse(c.frame(frame(true), "other-fw", true, 105.0))   // once
        // another firmware, all black past the window: given up
        assertFalse(c.frame(frame(false), "buzz", true, 200.0))
        assertFalse(c.frame(frame(false), "buzz", true, 205.0))
        assertFalse(c.frame(frame(true), "buzz", true, 214.0))
        assertFalse(t.exists("buzz"))
        // existing: not taken again after a reboot
        assertFalse(c.frame(frame(true), "other-fw", false, 300.0))
        assertFalse(c.frame(frame(true), "other-fw", true, 301.0))
        assertFalse(c.frame(frame(true), "other-fw", true, 305.0))
        // a shipped one: never captured
        assertFalse(c.frame(frame(true), "felucca", true, 400.0))
        assertFalse(c.frame(frame(true), "felucca", true, 404.0))
        assertFalse(t.file("felucca").exists())
    }

    @Test fun infoLookup() {
        var core = CoreInfo("felucca", name = "Felucca", version = "1.6.1", sourceURL = "https://example.org/felucca",
            credits = "Felucca by someone.")
        val d = AvailableFirmware("felucca", version = "1.6.1", author = "A. Author", summary = "An FM synth",
            about = "A long description.", repo = "owner/felucca", sourceURL = "https://github.com/owner/felucca/tree/v1.6.1")
        var i = FirmwareInfo.lookup(core, mapOf("felucca" to d))
        assertEquals("An FM synth", i.tag)
        assertEquals("A long description.", i.about)
        assertEquals("A. Author", i.author)
        assertEquals("https://github.com/owner/felucca/tree/v1.6.1", i.source)
        i = FirmwareInfo.lookup(core, mapOf("felucca" to d.copy(version = "1.7.0")))      // another version: the repo
        assertEquals("https://github.com/owner/felucca", i.source)
        // not in the catalog: the core's own (an imported file's by its firmware id)
        val imp = CoreInfo("rfm1:abc", coreID = "felucca", name = "Felucca", kind = "wasm")
        assertEquals("An FM synth", FirmwareInfo.lookup(imp, mapOf("felucca" to d)).tag)
        i = FirmwareInfo.lookup(core, emptyMap())
        assertEquals("Felucca by someone.", i.about)
        assertEquals("https://example.org/felucca", i.source)
        core = core.copy(sourceURL = "javascript:alert(1)")
        assertNull(FirmwareInfo.lookup(core, emptyMap()).source)
    }

    @Test fun firmwareNames() {
        assertTrue(FirmwareCenter.isFirmwareName("felucca-1.6.1.rfm1"))
        assertTrue(FirmwareCenter.isFirmwareName("FELUCCA.RFM1"))
        assertTrue(FirmwareCenter.isFirmwareName("download"))
        assertFalse(FirmwareCenter.isFirmwareName("song.rfm1state"))
        assertFalse(FirmwareCenter.isFirmwareName("bank.syx"))
    }

}
