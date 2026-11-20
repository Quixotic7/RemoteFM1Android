// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Move's firmware list (MoveCatalog) against the app's own two lists, built by the app's own code from the test
// feeds: the Firmware page's cores (built in and an imported .rfm1) and Get firmware's list (FirmwareStore.list, which
// leaves out what isn't downloadable: the site catalog's card-only 386dx); the thumbnail's 512 bytes; the library file.
package com.quixotic7.remotefm1.move

import com.quixotic7.remotefm1.firmware.CoreInfo
import com.quixotic7.remotefm1.firmware.CorePicker
import com.quixotic7.remotefm1.firmware.FakeRfm1
import com.quixotic7.remotefm1.firmware.FakeTransport
import com.quixotic7.remotefm1.firmware.FirmwareFeed
import com.quixotic7.remotefm1.firmware.FirmwareLibrary
import com.quixotic7.remotefm1.firmware.FirmwareListing
import com.quixotic7.remotefm1.firmware.FirmwareStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MoveCatalogTest {
    @Test fun theMovesListIsTheAppsGatedList() {
        val root = Files.createTempDirectory("move-catalog").toFile()
        try {
            val lib = FirmwareLibrary(File(root, "Firmware"), FakeRfm1)
            lib.sync()
            val amb = lib.import(FakeRfm1.amb)
            assertNull(amb.error)
            val t = FakeTransport().apply {
                pages[FirmwareFeed.REMOTEFM1.url] = FakeRfm1.catalogJSON
                pages[FirmwareFeed.RELEASES.url] = FakeRfm1.releaseJSON
                pages[FirmwareFeed.FW_CATALOG.url] = FakeRfm1.fixture("fwcatalog-2026-11-01.json").readBytes()
            }
            val store = FirmwareStore(lib, transport = t, cacheFile = File(root, "cache/feeds.json"), tmpDir = File(root, "tmp"))
            store.refresh()
            val builtIn = listOf(CoreInfo("choralroot", name = "ChoralRoot", version = "0.26.1", coreID = "choralroot"),
                CoreInfo("felucca", name = "Felucca", version = "1.6.1", coreID = "felucca"))
            val cores = CorePicker.catalog(builtIn, lib.cores())
            val getFirmware = store.list(builtIn)
            val l = MoveCatalog.build(cores, FirmwareListing.addedDates(lib.files.value), getFirmware, { store.state(it) },
                store.details)

            // exactly the app's two lists: the cores on this device, then what Get firmware offers
            val app = (FirmwareListing.visible(cores, "").map { it.firmwareID } + getFirmware.map { it.id }).toSet()
            assertEquals(app, l.map { it.id }.toSet())
            assertEquals(l.size, l.map { it.id }.toSet().size)
            assertFalse("a card with nothing to download", l.any { it.id == "386dx" })
            assertTrue(store.details.containsKey("386dx"))
            // A-Z by name
            assertEquals(l.map { it.name.lowercase() }.sorted(), l.map { it.name.lowercase() })
            // installed: what is on this device, at its version; the rest to download (no key)
            val by = l.associateBy { it.id }
            assertEquals("1.3", by["amb"]!!.installed)
            assertEquals(amb.key, by["amb"]!!.key)
            assertEquals("0.26.1", by["choralroot"]!!.installed)
            assertEquals("choralroot", by["choralroot"]!!.key)
            for (id in getFirmware.map { it.id }.filter { it != "amb" }) {
                val e = by[id]!!
                assertNull(id, e.installed)
                assertNull(id, e.key)
                assertEquals(id, getFirmware.first { it.id == id }, e.download)
            }
            // the cards' categories (ChoralRoot: Chords, step 5; FIZZ: Groovebox), the text in ASCII
            assertEquals(4 to 2, by["choralroot"]!!.cats to by["choralroot"]!!.cat)
            assertEquals(2 to 1, by["fizz"]!!.cats to by["fizz"]!!.cat)
            assertTrue(l.all { e -> (e.name + (e.desc ?: "")).all { it.code in 32..126 } })
            assertTrue(by["amb"]!!.desc!!.isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun anUpdateAndAnUnknownCategory() {
        val c = CoreInfo("choralroot", name = "ChoralRoot", version = "0.26.1", coreID = "choralroot")
        val newer = com.quixotic7.remotefm1.firmware.AvailableFirmware("choralroot", name = "ChoralRoot", version = "0.27",
            category = "Chords")
        val l = MoveCatalog.build(listOf(c), emptyMap(), listOf(newer),
            { com.quixotic7.remotefm1.firmware.InstallState.NotInstalled }, emptyMap())
        assertEquals(1, l.size)
        assertEquals("0.27", l[0].version)
        assertEquals("0.26.1", l[0].installed)
        assertEquals(newer, l[0].download)
        assertEquals(16 to 4, MoveCatalog.cats("Sequencer"))
        assertEquals(16 to 4, MoveCatalog.cats(null))
        assertEquals("Arctic Scream - it's \"cold\"... ole", MoveCatalog.ascii("Arctic Scream — it’s “cold”… olé"))
    }

    @Test fun thumbnailsAre512Bytes() {
        // a 240 x 120 picture: its centre square (x 60..180), the left half white, the right black
        val w = 240
        val h = 120
        val px = IntArray(w * h) { i -> if (i % w < 120) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val t = MoveCatalog.thumb64(px, w, h)
        assertEquals(512, t.size)
        for (y in 0 until 64) {
            for (b in 0 until 4) assertEquals("row $y byte $b", 0xFF, t[y * 8 + b].toInt() and 0xFF)
            for (b in 4 until 8) assertEquals("row $y byte $b", 0, t[y * 8 + b].toInt())
        }
        assertArrayEquals(ByteArray(512), MoveCatalog.thumb64(IntArray(64 * 64) { 0xFF000000.toInt() }, 64, 64))
        assertArrayEquals(ByteArray(512) { -1 }, MoveCatalog.thumb64(IntArray(8 * 8) { -1 }, 8, 8))
        // a grey ramp dithered: about as many lit dots as its mean
        val ramp = IntArray(256 * 256) { i -> val v = i % 256; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val lit = MoveCatalog.thumb64(ramp, 256, 256).sumOf { Integer.bitCount(it.toInt() and 0xFF) }
        assertTrue("$lit of 4096", lit in 1800..2300)
    }

    @Test fun theLibraryFileRoundTrips() {
        val dir = Files.createTempDirectory("move-lib").toFile()
        try {
            val lib = MoveLibrary(File(dir, "move/library.bin"), 10244)
            assertNull(lib.read())
            val b = ByteArray(300) { it.toByte() }
            assertTrue(lib.write(b))
            assertArrayEquals(b, MoveLibrary(File(dir, "move/library.bin"), 10244).read())
            assertTrue(lib.write(byteArrayOf(1, 2, 3)))
            assertArrayEquals(byteArrayOf(1, 2, 3), lib.read())
            assertFalse(lib.write(ByteArray(10245)))
            assertFalse(lib.write(ByteArray(0)))
            assertArrayEquals(byteArrayOf(1, 2, 3), lib.read())
        } finally {
            dir.deleteRecursively()
        }
    }
}
