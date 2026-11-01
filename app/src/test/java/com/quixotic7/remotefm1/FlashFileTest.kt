// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// FlashFile on the JVM (iOS: RFMKit/Tests/RFMKitTests/FlashHistoryTests.swift's FlashFile cases), in a temp folder.
package com.quixotic7.remotefm1

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FlashFileTest {
    private lateinit var dir: File
    private lateinit var f: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("rfm-flash").toFile()
        f = File(dir, "cores/choralroot.bin")
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun image(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }

    @Test fun writeThenRead() {
        val a = image(4096, 1)
        FlashFile.write(a, f, "choralroot", "0.26.1", now = 1793452800.25)
        val r = FlashFile.read(f, 4096)
        assertArrayEquals(a, r.data)
        assertFalse(r.fromPrev)
        assertEquals("", r.note)
        assertFalse(FlashFile.prevFile(f).exists())                 // (nothing before it)
        assertFalse(File(f.parentFile, ".choralroot.bin.tmp").exists())
        val m = FlashFile.meta(f)!!
        assertEquals(FlashFile.Meta("choralroot", "0.26.1", 4096, FlashFile.sha256(a), 1793452800.25), m)
        assertEquals("choralroot.json", FlashFile.metaFile(f).name)
    }

    @Test fun secondWriteKeepsThePrevious() {
        val a = image(4096, 1)
        val b = image(4096, 2)
        FlashFile.write(a, f, "choralroot", "0.26.1")
        FlashFile.write(b, f, "choralroot", "0.26.1")
        assertArrayEquals(b, f.readBytes())
        assertArrayEquals(a, FlashFile.prevFile(f).readBytes())
        assertEquals(FlashFile.sha256(b), FlashFile.meta(f)!!.sha256)
    }

    @Test fun missingFileIsAFreshFlash() {
        val r = FlashFile.read(f, 4096)
        assertNull(r.data)
        assertEquals("", r.note)
    }

    @Test fun damagedFileFallsBackToPrev() {
        val a = image(4096, 1)
        FlashFile.write(a, f, "choralroot", "0.26.1")
        FlashFile.write(image(4096, 2), f, "choralroot", "0.26.1")
        f.writeBytes(ByteArray(100))                                // (cut short)
        val r = FlashFile.read(f, 4096)
        assertArrayEquals(a, r.data)
        assertTrue(r.fromPrev)
        assertEquals("The saved memory was damaged (100 bytes, not 4096): started from the copy saved before it.", r.note)
    }

    @Test fun missingFileWithPrevUsesPrev() {
        val a = image(64, 1)
        FlashFile.write(a, f)
        FlashFile.write(image(64, 2), f)
        f.delete()
        val r = FlashFile.read(f, 64)
        assertArrayEquals(a, r.data)
        assertEquals("The saved memory was missing: started from the copy saved before it.", r.note)
    }

    @Test fun nothingUsableIsAFreshMemoryWithANote() {
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(10))
        val r = FlashFile.read(f, 4096)
        assertNull(r.data)
        assertEquals("The saved memory was damaged (10 bytes, not 4096) and no earlier copy was usable: a fresh memory.", r.note)
        // a wrong-size .prev is not used either
        FlashFile.prevFile(f).writeBytes(ByteArray(20))
        assertNull(FlashFile.read(f, 4096).data)
        // any size is taken when none is expected
        assertEquals(10, FlashFile.read(f, 0).data!!.size)
    }

    @Test fun emptyFileIsDamaged() {
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(0))
        assertTrue(FlashFile.read(f, 0).note.startsWith("The saved memory was damaged (0 bytes"))
    }

    @Test fun writeWithoutMetaLeavesTheSidecar() {
        FlashFile.write(image(32, 1), f, "choralroot", "0.26.0")
        FlashFile.write(image(32, 2), f)
        assertEquals("0.26.0", FlashFile.meta(f)!!.version)
    }

    @Test fun removeTakesAllThree() {
        FlashFile.write(image(32, 1), f, "x", "1")
        FlashFile.write(image(32, 2), f, "x", "1")
        FlashFile.remove(f)
        assertFalse(f.exists() || FlashFile.prevFile(f).exists() || FlashFile.metaFile(f).exists())
    }

    @Test fun sidecarIsWhatSwiftWrites() {
        // JSONEncoder (.prettyPrinted, .sortedKeys, .secondsSince1970), as RemoteFM1IOS writes <id>.json
        val m = FlashFile.Meta("fizz", "0.12.0-beta.12", 1048576, "ab".repeat(32), 1793452800.0)
        assertEquals("""
            {
              "date" : 1793452800,
              "firmwareID" : "fizz",
              "sha256" : "${"ab".repeat(32)}",
              "size" : 1048576,
              "version" : "0.12.0-beta.12"
            }
        """.trimIndent(), FlashFile.encode(m))
        assertEquals(m, FlashFile.decode(FlashFile.encode(m)))
        // what an iPhone wrote (a fractional date, an escaped slash, the keys in any order, compact)
        val ios = """{"version":"1.6.1","size":2097152,"sha256":"00ff","firmwareID":"a\/b","date":1793452800.123456}"""
        assertEquals(FlashFile.Meta("a/b", "1.6.1", 2097152, "00ff", 1793452800.123456), FlashFile.decode(ios))
        assertEquals("1793452800.123456", FlashFile.encode(FlashFile.decode(ios)).lines()[1].substringAfter(": ").trimEnd(','))
    }

    @Test fun damagedSidecarIsNone() {
        FlashFile.write(image(32, 1), f, "x", "1")
        FlashFile.metaFile(f).writeText("{\"firmwareID\": ")
        assertNull(FlashFile.meta(f))
    }

    @Test fun sha256Known() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FlashFile.sha256(ByteArray(0)))
    }

    @Test fun looksErased() {
        assertTrue(FlashFile.looksErased(ByteArray(1000) { 0xFF.toByte() }))
        assertTrue(FlashFile.looksErased(ByteArray(1000).also { it[3] = 7 }))
        assertFalse(FlashFile.looksErased(image(1000, 1)))
    }
}
