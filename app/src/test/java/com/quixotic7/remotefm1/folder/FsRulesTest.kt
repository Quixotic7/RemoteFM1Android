// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Fs.kt against RemoteFM1 60a1b8b web/engines/rfm1/fs-test.mjs's checks that need no wasm: the path rules (section
// 3's 20 refused accesses), the manifest's folders, LIST's sort and paging, STAT's record.
package com.quixotic7.remotefm1.folder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FsRulesTest {
    private val all = mapOf("audio" to "r", "code" to "r", "data" to "rw")

    /** fs-test's request check, as the shared C++ makes it: the path, then the folders */
    private fun check(op: Int, p: ByteArray, folders: Map<String, String> = all): Int {
        val m = Fs.folderPath(p, "fscart") ?: return Fs.EACCES
        return Fs.access(op, m, folders)
    }
    private fun check(op: Int, p: String, folders: Map<String, String> = all) = check(op, p.toByteArray(), folders)

    @Test fun pathsMapOntoTheFolder() {
        assertEquals(Fs.Mapped("audio", false, "audio/kick.wav"), Fs.folderPath("/audio/kick.wav".toByteArray(), "fscart"))
        assertEquals(Fs.Mapped("audio", true, "audio"), Fs.folderPath("/audio".toByteArray(), "fscart"))
        assertEquals("code/fscart/droplets/a.lua", Fs.folderPath("/code/droplets/a.lua".toByteArray(), "fscart")?.path)
        assertEquals(Fs.Mapped("data", true, "data/fscart"), Fs.folderPath("/data".toByteArray(), "fscart"))
        assertEquals("audio/é.wav", Fs.folderPath("/audio/é.wav".toByteArray(), "fscart")?.path)
    }

    @Test fun twentyAccessesOutsideSectionThreeAreRefused() {
        val acc = listOf<Pair<Int, Any>>(Fs.WRITE to "/audio/x.wav", Fs.WRITE to "/code/x.lua", Fs.MKDIR to "/audio/new",
            Fs.DELETE to "/code/droplets/a.lua", Fs.DELETE to "/data", Fs.MKDIR to "/data",
            Fs.READ to "/audio/../code/other/secret.lua", Fs.READ to "/code/../other/secret.lua", Fs.READ to "/code/./droplets/a.lua",
            Fs.READ to "//audio/kick.wav", Fs.READ to "/audio//kick.wav", Fs.LIST to "/audio/", Fs.READ to "audio/kick.wav",
            Fs.READ to "/etc/passwd", Fs.READ to "/", Fs.READ to "/Audio/kick.wav", Fs.READ to "/audio/kick.wav\u0000",
            Fs.READ to "/audio\\kick.wav", Fs.READ to byteArrayOf(0x2f, 0x61, 0x75, 0x64, 0x69, 0x6f, 0x2f, 0xff.toByte()),
            Fs.READ to "/audio/" + "x".repeat(1100))
        assertEquals(20, acc.size)
        val bad = acc.filter { (op, p) -> (if (p is ByteArray) check(op, p) else check(op, p as String)) != Fs.EACCES }
        assertTrue("not refused: ${bad.map { it.second.toString().take(40) }}", bad.isEmpty())
        // a name over 255 bytes, under the path limit
        assertEquals(Fs.EACCES, check(Fs.READ, "/audio/" + "y".repeat(256)))
        assertEquals(0, check(Fs.READ, "/audio/" + "y".repeat(255)))
    }

    @Test fun theManifestsFoldersDecide() {
        assertEquals(0, check(Fs.READ, "/audio/kick.wav"))
        assertEquals(0, check(Fs.WRITE, "/data/save.txt"))
        assertEquals(0, check(Fs.MKDIR, "/data/sub"))
        val audioOnly = mapOf("audio" to "r")
        assertEquals(Fs.EACCES, check(Fs.READ, "/code/droplets/a.lua", audioOnly))
        assertEquals(Fs.EACCES, check(Fs.LIST, "/data", audioOnly))
        assertEquals(0, check(Fs.READ, "/audio/kick.wav", audioOnly))
        val dataR = mapOf("audio" to "r", "data" to "r")
        assertEquals(Fs.EACCES, check(Fs.WRITE, "/data/x", dataR))
        assertEquals(0, check(Fs.LIST, "/data", dataR))
    }

    @Test fun folderPathsAreCheckedAgain() {
        for (ok in listOf("audio", "audio/kick.wav", "audio/sub/x.wav", "code/ripple", "code/ripple/droplets/a.lua", "data/ripple"))
            assertEquals(ok, 0, Fs.checkFolderPath(ok, write = false))
        for (ok in listOf("data/ripple/save.txt", "data/ripple/sub/f.bin")) assertEquals(ok, 0, Fs.checkFolderPath(ok, write = true))
        for (bad in listOf("", "/audio", "audio/", "audio//x", "audio/../code/x", "audio/./x", "..", "code", "data", "etc/passwd",
                           "Audio/x", "audio/x\u0000", "audio\\x", "code/../data/x"))
            assertEquals("read $bad", Fs.EACCES, Fs.checkFolderPath(bad, write = false))
        for (bad in listOf("audio/x.wav", "code/ripple/x.lua", "data/ripple", "data", "data/ripple/../other/x"))
            assertEquals("write $bad", Fs.EACCES, Fs.checkFolderPath(bad, write = true))
        assertEquals("audio", Fs.areaOf("audio/sub/x.wav"))
        assertEquals("code/ripple", Fs.areaOf("code/ripple/droplets/a.lua"))
        assertEquals("data/ripple", Fs.areaOf("data/ripple"))
    }

    private val audio = listOf("kick.wav" to false, "sub" to true, ".hidden" to false, "B.wav" to false, "a.wav" to false,
                               "Z" to false, "é.wav" to false)

    @Test fun listSortsByNameBytesWithoutHiddenFiles() {
        val s = Fs.sortEntries(audio + listOf("" to false, "x".repeat(256) to false))
        assertEquals("B.wav Z a.wav kick.wav /sub é.wav", s.joinToString(" ") { (if (it.dir) "/" else "") + it.text })
        // packed {u8 dir, u8 len, name} and read back
        val b = Fs.packAll(s)
        assertEquals(s.sumOf { 2 + it.name.size }, b.size)
        assertEquals(1.toByte(), b[2 + 5 + 2 + 1 + 2 + 5 + 2 + 8])       // (sub: after B.wav, Z, a.wav, kick.wav)
        assertEquals(s.map { it.text to it.dir }, Fs.unpack(b).map { it.text to it.dir })
    }

    @Test fun listPagesNeverSplitAnEntry() {
        val s = Fs.sortEntries(audio)
        val pages = ArrayList<String>()
        var off = 0
        while (true) {
            val (b, n) = Fs.packPage(s, off, 12)
            if (n <= 0) { pages.add("$n"); break }
            pages.add(Fs.unpack(b).joinToString(",") { (if (it.dir) "/" else "") + it.text })
            off += n
        }
        assertEquals("B.wav,Z | a.wav | kick.wav | /sub | é.wav | 0", pages.joinToString(" | "))
        assertEquals(Fs.EIO, Fs.packPage(s, 0, 4).second)                  // (no room for the first name)
        assertEquals(0, Fs.packPage(emptyList(), 0, 4).second)
    }

    @Test fun statRecordIsLittleEndian() {
        val r = ByteBuffer.wrap(Fs.statRecord(3L * 1048576 + 17, 1_761_000_000L, false)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3L * 1048576 + 17, r.getLong(0))
        assertEquals(1_761_000_000, r.getInt(8))
        assertEquals(0, r.getInt(12))
        assertEquals(1, ByteBuffer.wrap(Fs.statRecord(0, 0, true)).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
        assertArrayEquals(ByteArray(16), Fs.statRecord(-5, -1, false))
    }

    @Test fun utf8IsStrict() {
        assertEquals("/audio/é", Fs.utf8("/audio/é".toByteArray()))
        assertNull(Fs.utf8(byteArrayOf(0x2f, 0xff.toByte())))
        assertNull(Fs.utf8(byteArrayOf(0xc3.toByte())))
    }

    @Test fun errorCodesAreTheSpecs() {
        assertEquals(listOf(-1, -2, -3, -4, -5, -6, -7, -8), listOf(Fs.PENDING, Fs.ENOENT, Fs.EACCES, Fs.EIO, Fs.ENOSPC, Fs.EBUSY,
                                                                    Fs.ECANCELED, Fs.ENOFOLDER))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), listOf(Fs.STAT, Fs.LIST, Fs.READ, Fs.WRITE, Fs.DELETE, Fs.MKDIR))
        assertEquals(Fs.ENOSPC, Fs.ioCode(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(Fs.ENOENT, Fs.ioCode(java.io.FileNotFoundException("x")))
        assertEquals(Fs.EIO, Fs.ioCode(java.io.IOException("odd")))
    }
}
