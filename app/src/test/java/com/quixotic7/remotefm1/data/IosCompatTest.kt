// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
//
// iOS → Android: files the iOS app's own code wrote (RemoteFM1IOS edf2039 FlashHistory.swift, StateLibrary.swift,
// StateFile.swift built for the Mac with a tiny shim; Apple's Compression did the LZFSE) read here: a .rfm1state, a
// library folder (<uuid>.flash + .json), a safety copy, a backup folder and its zip (NSFileCoordinator's), and a raw
// LZFSE stream. Fixtures in src/test/resources/a5-ios. Needs the real LZFSE (the host build: LzfseHost).
// Android → iOS: [writesFilesForTheIOSCheck] leaves Android-made files in build/a5-android-out for the same iOS code
// to read (the A5 report has the result).
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class IosCompatTest {
    private lateinit var res: File

    @Before fun setUp() {
        assumeTrue("no host lzfse (cc)", LzfseHost.loaded)
        res = File(javaClass.getResource("/a5-ios/apple-input.bin")!!.toURI()).parentFile
    }

    /** the fixtures' flash pattern (the Swift tool's) */
    private fun pattern(n: Int, v: Int): ByteArray {
        val d = ByteArray(n) { 0xFF.toByte() }
        for (i in 0 until n / 3) d[i] = ((i * 7) xor v).toByte()
        return d
    }

    @Test fun appleLzfseDecodesAndOursIsTheSame() {
        val raw = File(res, "apple-input.bin").readBytes()
        val apple = File(res, "apple-lzfse.z").readBytes()
        assertArrayEquals(raw, Lzfse.decompress(apple, raw.size))
        assertArrayEquals("byte for byte what Compression writes", apple, Lzfse.compress(raw))
    }

    @Test fun anIOSStateFileOpens() {
        val d = StateFile.read(File(res, "Pads from iOS.rfm1state"))
        val s = d.state
        assertEquals("Pads from iOS", s.name)
        assertEquals("a note: ünïcödé \"q\" / slash", s.note)
        assertEquals("choralroot", s.firmwareID)
        assertEquals("ChoralRoot", s.firmwareName)
        assertEquals("0.26.1", s.version)
        assertEquals(1793000000.5, s.date, 0.0)
        assertEquals(700, s.master)
        assertEquals(-1, s.transpose)
        assertEquals(false, s.keyTouch)
        assertEquals("app", s.origin)
        assertArrayEquals(pattern(65536, 1), d.flash)
        // imported into an Android library and written again: the same state, readable
        val lib = StateLibrary(tempDir())
        val i = lib.importFile(File(res, "Pads from iOS.rfm1state"))
        assertEquals(s, i.state)
        assertArrayEquals(pattern(65536, 1), lib.flash(i.state))
    }

    @Test fun anIOSLibraryFolderLists() {
        val lib = StateLibrary(copy("States"))
        val c = lib.list("choralroot").single()
        assertEquals("Pads from iOS", c.name)
        assertArrayEquals(pattern(65536, 1), lib.flash(c))
        val f = lib.list("felucca").single()
        assertEquals("Bass", f.name)
        assertEquals("wasm", f.kind)
        assertEquals("au", f.origin)
        assertEquals("ab".repeat(32), f.sha256)
        assertEquals(null, f.master)
        assertArrayEquals(pattern(8192, 2), lib.flash(f))
        assertEquals(2, lib.allStates().size)
        // renamed here: still the iOS fields
        val r = lib.rename(c, "Renamed on Android")
        assertEquals(r, StateLibrary(lib.root).list("choralroot").single())
    }

    @Test fun anIOSSafetyCopyLists() {
        val h = FlashHistory(copy("FlashHistory"))
        val s = h.list("choralroot").single()
        assertEquals("before update 0.26.0→0.26.1", s.reason)
        assertEquals(FlashHistory.Kind.EVENT, s.kind)
        assertEquals("0.26.0", s.version)
        assertArrayEquals(pattern(65536, 3), h.data(s))
    }

    @Test fun anIOSBackupRestoresFromItsFolderAndItsZip() {
        val dir = File(res, "Backups").listFiles()!!.single()
        val m = Backup.Manifest.fromJson(Json.obj(File(dir, "manifest.json").readText()))
        assertEquals("ios-test", m.app)
        assertEquals(listOf(2, 1, 1), listOf(m.states.size, m.current.size, m.safetyCopies.size))
        var r = Backup.restoreTree(FileTree(dir.parentFile), dir.name, StateLibrary(tempDir()))
        assertEquals("Imported 3 states.", r.text)
        assertEquals(setOf("Pads from iOS", "Bass", "ChoralRoot current"), r.imported.map { it.name }.toSet())
        r = Backup.restoreZip(File(res, "backup.zip").inputStream(), StateLibrary(tempDir()))
        assertEquals("Imported 3 states.", r.text)
        assertTrue(Backup.check(File(res, "backup.zip").inputStream(), m))
    }

    /** Android → iOS: files for the iOS code to read (the Swift tool: dec, liblist, hist, restore, manifest) */
    @Test fun writesFilesForTheIOSCheck() {
        val out = File("build/a5-android-out")
        out.deleteRecursively()
        out.mkdirs()
        val t0 = 1_793_000_000.25
        val lib = StateLibrary(File(out, "States"), { t0 })
        val s = lib.save("Made on Android", note = "ü / \"x\"", firmwareID = "choralroot", firmwareName = "ChoralRoot",
            kind = "native", version = "0.26.1", flash = pattern(65536, 5), master = 300, transpose = 2, keyTouch = true,
            origin = "app")
        lib.save("Second", firmwareID = "fizz", firmwareName = "FIZZ", kind = "native", version = "0.12", flash = pattern(4096, 6),
            origin = "app")
        File(out, "Made on Android.rfm1state").writeBytes(StateFile.encode(s, pattern(65536, 5)))
        val h = FlashHistory(File(out, "FlashHistory")) { t0 }
        h.record(pattern(65536, 7), "choralroot", "0.26.0", FlashHistory.Reason.BeforeUpdate("0.26.0", "0.26.1"))
        val cur = listOf(Backup.Current("choralroot", "ChoralRoot", "native", "0.26.1", flash = pattern(65536, 8)))
        val m = FileOutputStream(File(out, "backup.zip")).use { Backup.writeZip(it, lib, h, cur, "android-test", t0) }
        assertTrue(Backup.check(File(out, "backup.zip").inputStream(), m))
        File(out, "expected.txt").writeText("state ${FlashFile.sha256(pattern(65536, 5))}\n")
    }

    private fun copy(name: String): File {
        val d = tempDir()
        File(res, name).copyRecursively(File(d, name))
        return File(d, name)
    }
}
