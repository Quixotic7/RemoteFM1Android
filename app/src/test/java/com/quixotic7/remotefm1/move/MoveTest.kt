// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Ableton Move's Kotlin side on the JVM: the address as typed (move-link.js normalizeUrl's cases), the projects'
// file (Android's pre-0.3.0 writer against the golden files; the shared rfm1cs_file_* is MoveCoreTest's), withColor,
// and their store (one .rfmmove a slot, iOS's names), the settings in the preferences, the mDNS question and its answer, and Sample's WAV.
package com.quixotic7.remotefm1.move

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MoveAddressTest {
    @Test fun defaultsAndHosts() {
        assertEquals(MoveAddress("move.local", 7720), MoveAddress.parse(""))
        assertEquals(MoveAddress("move.local", 7720), MoveAddress.parse(null))
        assertEquals(MoveAddress("move.local", 7720), MoveAddress.parse("  move.local "))
        assertEquals(MoveAddress("172.16.254.1", 7720), MoveAddress.parse("172.16.254.1"))
        assertEquals(MoveAddress("192.168.1.20", 7000), MoveAddress.parse("192.168.1.20:7000"))
        assertEquals(MoveAddress("move.local", 7720), MoveAddress.parse("ws://move.local:7720/ws"))
        assertEquals(MoveAddress("move.local", 7720), MoveAddress.parse("http://move.local/"))
        assertEquals(MoveAddress("fe80::1", 7720), MoveAddress.parse("fe80::1"))
        assertEquals(MoveAddress("fe80::1", 8000), MoveAddress.parse("[fe80::1]:8000"))
    }

    @Test fun refused() {
        assertNull(MoveAddress.parse("move.local:abc"))
        assertNull(MoveAddress.parse("move.local:0"))
        assertNull(MoveAddress.parse("move.local:70000"))
        assertNull(MoveAddress.parse("my move"))
        assertNull(MoveAddress.parse("[fe80::1"))
    }

    @Test fun kinds() {
        assertTrue(MoveAddress.parse("move.local")!!.isMdns)
        assertTrue(MoveAddress.parse("Move.Local.")!!.isMdns)
        assertFalse(MoveAddress.parse("10.0.2.2")!!.isMdns)
        assertTrue(MoveAddress.parse("10.0.2.2")!!.isLiteral)
        assertTrue(MoveAddress.parse("172.16.254.1")!!.isUsb)
        assertFalse(MoveAddress.isIpv4("256.1.1.1"))
        assertEquals("move.local", MoveAddress.parse("move.local:7720")!!.text)
        assertEquals("10.0.2.2:17720", MoveAddress.parse("10.0.2.2:17720")!!.text)
        assertEquals(MoveAddress.parse("10.0.2.2:17720"), MoveAddress.parse(MoveAddress.parse("10.0.2.2:17720")!!.text))
    }
}

/** Android's own RMVP writer and reader before rfm1cs-abletonmove 0.3.0 (MoveProjects.packFile / unpackFile at
 *  a0d0fe6, as they were): the reference the shared rfm1cs_file_* must match byte for byte (the golden files in
 *  androidTest/assets/rmvp, from rfm1cs-abletonmove tests/rmvp, are what it wrote), and the store's codec on the JVM */
class KotlinRmvp(private val check: (ByteArray) -> Int = { 0 }) : MoveFileCodec {
    override fun pack(ps: List<MoveProject>, kind: Int): ByteArray {
        val n = ps.fold(HEAD) { a, p -> a + ENTRY + p.bytes.size }
        val out = ByteArray(n)
        MAGIC.copyInto(out)
        out[4] = FILE_VERSION.toByte(); out[5] = kind.toByte()
        le16(out, 6, ps.size)
        var o = HEAD
        for (p in ps) {
            out[o] = p.slot.toByte(); out[o + 1] = 0
            le32(out, o + 2, if (p.saved > 0) (p.saved / 1000).toInt() else 0)
            le32(out, o + 6, p.bytes.size)
            p.bytes.copyInto(out, o + ENTRY)
            o += ENTRY + p.bytes.size
        }
        return out
    }

    override fun unpack(bytes: ByteArray?): MoveProjects.Unpacked {
        fun bad(why: String): Nothing = throw IllegalArgumentException("Not a Move project file: $why.")
        if (bytes == null) bad("nothing to read")
        if (bytes.size > MAX_FILE) bad("it is ${bytes.size} bytes, more than a full set ($MAX_FILE)")
        if (bytes.size < HEAD || (0 until 4).any { bytes[it] != MAGIC[it] }) bad("it does not start with RMVP")
        if (bytes[4].toInt() != FILE_VERSION) bad("version ${bytes[4]}; this app reads version $FILE_VERSION")
        val kind = bytes[5].toInt()
        val count = u16(bytes, 6)
        if (kind != KIND_ONE && kind != KIND_SET) bad("kind $kind")
        if (count < 1 || count > SLOTS || (kind == KIND_ONE && count != 1)) bad("$count projects")
        val entries = ArrayList<MoveProject>()
        val seen = HashSet<Int>()
        var o = HEAD
        for (i in 0 until count) {
            if (o + ENTRY > bytes.size) bad("project ${i + 1} is cut off")
            val slot = bytes[o].toInt() and 255
            val t = u32(bytes, o + 2)
            val len = u32(bytes, o + 6)
            if (!MoveProjects.validSlot(slot) || slot in seen) bad("slot ${slot + 1} ${if (slot in seen) "twice" else "out of range"}")
            if (len < 1 || len > BLOB_MAX) bad("project ${i + 1} is $len bytes")
            if (o + ENTRY + len > bytes.size) bad("project ${i + 1} is cut off")
            val blob = bytes.copyOfRange(o + ENTRY, o + ENTRY + len.toInt())
            val r = try { check(blob) } catch (_: Exception) { -1 }
            if (r != 0) bad("project ${i + 1} (slot ${slot + 1}) is damaged")
            seen += slot
            entries += MoveProject(slot, blob, if (t > 0) t * 1000 else System.currentTimeMillis())
            o += ENTRY + len.toInt()
        }
        if (o != bytes.size) bad("${bytes.size - o} bytes after the last project")
        return MoveProjects.Unpacked(kind, entries)
    }

    companion object {
        const val SLOTS = 32
        const val BLOB_MAX = 39072                  // (the stale copy: RFM_MOVE_PROJECT_MAX was 39200, 40136 since 0.5.0)
        val MAGIC = byteArrayOf(0x52, 0x4d, 0x56, 0x50)
        const val FILE_VERSION = 1
        const val KIND_ONE = 1
        const val KIND_SET = 2
        const val HEAD = 8
        const val ENTRY = 10
        const val MAX_FILE = HEAD + SLOTS * (ENTRY + BLOB_MAX)
        private fun le16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        private fun le32(b: ByteArray, o: Int, v: Int) { for (k in 0 until 4) b[o + k] = (v ushr (8 * k)).toByte() }
        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
        private fun u32(b: ByteArray, o: Int): Long =
            (0 until 4).fold(0L) { a, k -> a or ((b[o + k].toLong() and 255) shl (8 * k)) }
    }
}

class MoveProjectsTest {
    // a stand-in blob: "RMSQ", 20 bytes of body, its FNV-1a (the core's check is the emulator test's)
    private fun blob(seed: Int, n: Int = 40): ByteArray = MoveProjects.withColor(ByteArray(n) { (it * 7 + seed).toByte() }.also {
        it[0] = 'R'.code.toByte(); it[1] = 'M'.code.toByte(); it[2] = 'S'.code.toByte(); it[3] = 'Q'.code.toByte()
    }, 0)
    private val ok: (ByteArray) -> Int = { 0 }
    private val codec = KotlinRmvp(ok)

    /** the golden files (rfm1cs-abletonmove tests/rmvp, copied to androidTest/assets/rmvp for MoveCoreTest) */
    private fun golden(name: String): ByteArray =
        listOf(File("src/androidTest/assets/rmvp/$name"), File("app/src/androidTest/assets/rmvp/$name")).first { it.isFile }.readBytes()

    /** Android's pre-0.3.0 writer made exactly the golden files that MoveCoreTest compares rfm1cs_file_pack with */
    @Test fun theOldWriterMadeTheGoldenFiles() {
        val a = golden("blob_a.bin")
        val b = golden("blob_b.bin")
        assertArrayEquals(golden("android.rfmmove"), codec.pack(listOf(MoveProject(3, a, 1_794_909_600_000L)), MoveProjects.KIND_ONE))
        assertArrayEquals(golden("android.rfmmoveset"), codec.pack(listOf(MoveProject(0, a, 1_794_909_600_000L),
            MoveProject(5, b, 1_794_990_615_000L)), MoveProjects.KIND_SET))
        // (the submodule's copy, when it is checked out, is the same)
        val vendor = listOf(File("../vendor/rfm1cs-abletonmove/tests/rmvp"), File("vendor/rfm1cs-abletonmove/tests/rmvp")).firstOrNull { it.isDirectory }
        if (vendor != null) for (n in listOf("android.rfmmove", "android.rfmmoveset", "blob_a.bin", "blob_b.bin"))
            assertArrayEquals(n, File(vendor, n).readBytes(), golden(n))
    }

    /** the old reader refused a blob of 39073..39200 bytes (its BLOB_MAX was 39072): rfm1cs_file_unpack reads it */
    @Test fun theOldReaderRefusedTheLargestBlobs() {
        val f = codec.pack(listOf(MoveProject(0, ByteArray(39200), 0)), MoveProjects.KIND_ONE)
        try { codec.unpack(f); fail("accepted") } catch (e: IllegalArgumentException) {
            assertEquals("Not a Move project file: project 1 is 39200 bytes.", e.message)
        }
    }

    @Test fun colourAndItsCheck() {
        val b = blob(5)
        val c = MoveProjects.withColor(b, 7)
        assertEquals(7, c[18].toInt())
        // FNV-1a over all but the last 4 bytes, little-endian at the end
        var h = 0x811C9DC5.toInt()
        for (i in 0 until c.size - 4) h = (h xor (c[i].toInt() and 255)) * 16777619
        assertEquals(h, (c[c.size - 4].toInt() and 255) or ((c[c.size - 3].toInt() and 255) shl 8) or
            ((c[c.size - 2].toInt() and 255) shl 16) or ((c[c.size - 1].toInt() and 255) shl 24))
        assertArrayEquals(b, MoveProjects.withColor(c, b[18].toInt()))
    }

    @Test fun storeOneFileASlot() {
        val dir = Files.createTempDirectory("move").toFile()
        val s = MoveProjects(dir, codec)
        assertTrue(s.list().isEmpty())
        s.put(0, blob(1), 1_600_000_000_000L)
        s.put(31, blob(2))
        assertEquals(listOf(0, 31), s.list().map { it.slot })
        assertEquals((1 shl 0) or (1 shl 31), s.dirBits())
        assertTrue(File(dir, "move-project-01.rfmmove").isFile)
        assertTrue(File(dir, "move-project-32.rfmmove").isFile)
        // each file is the web's one-project file, so it imports anywhere as it is
        val u = codec.unpack(File(dir, "move-project-01.rfmmove").readBytes())
        assertEquals(0, u.entries[0].slot)
        assertEquals(1_600_000_000_000L, s.get(0)!!.saved)
        s.put(0, blob(3))
        assertArrayEquals(blob(3), s.get(0)!!.bytes)
        s.remove(0)
        assertNull(s.get(0))
        File(dir, "move-project-05.rfmmove").writeBytes(byteArrayOf(1, 2, 3))   // (not one: skipped)
        assertEquals(listOf(31), s.list().map { it.slot })
        assertEquals(0, MoveProjects.firstFree(s.dirBits()))
        assertEquals(-1, MoveProjects.firstFree(-1))
        assertEquals("move-project-07.rfmmove", MoveProjects.fileName(6))
        assertEquals("Project 7", MoveProjects.projectName(6))
    }
}

class MoveSettingsTest {
    private class MapPrefs : MovePrefs {
        val m = HashMap<String, Any?>()
        override fun string(key: String) = m[key] as String?
        override fun setString(key: String, v: String?) { m[key] = v }
        override fun bool(key: String) = m[key] as Boolean? ?: false
        override fun setBool(key: String, v: Boolean) { m[key] = v }
        override fun int(key: String, def: Int) = m[key] as Int? ?: def
        override fun setInt(key: String, v: Int) { m[key] = v }
    }

    @Test fun roundTrips() {
        val p = MapPrefs()
        val s = MoveSettings(p)
        assertNull(s.image)
        assertEquals(MoveAddress.DEFAULT, s.address)
        assertFalse(s.auto)
        assertEquals(0, s.project)
        val img = byteArrayOf(0x52, 0x4d, 0x56, 3, 80, 0, 9)
        assertTrue(s.keepImage(img))
        assertFalse(s.keepImage(img.copyOf()))
        assertArrayEquals(img, MoveSettings(p).image)
        assertEquals("Uk1WA1AACQ==", p.m["move"])            // (base64, as the web's prefs.move)
        s.address = MoveAddress("10.0.2.2", 17720)
        assertEquals(MoveAddress("10.0.2.2", 17720), MoveSettings(p).address)
        s.auto = true
        assertTrue(MoveSettings(p).auto)
        s.project = 12
        assertEquals(12, MoveSettings(p).project)
        s.project = 40                                       // (out of range: kept as it was)
        assertEquals(12, s.project)
        p.m["move"] = "not base64!"
        assertNull(s.image)
        p.m["moveProject"] = 99
        assertEquals(0, s.project)
    }
}

class MoveMdnsTest {
    @Test fun question() {
        val q = MoveMdns.query("move.local", 0x1234)
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            4, 'm'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte(), 'e'.code.toByte(),
            5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 0, 0, 1, 0, 1), q)
    }

    private fun answer(name: String, ip: IntArray, compressed: Boolean): ByteArray {
        val q = MoveMdns.query(name, 7)
        val o = java.io.ByteArrayOutputStream()
        o.write(q, 0, 12)
        o.write(q, 12, q.size - 12)                          // the question echoed
        if (compressed) { o.write(0xC0); o.write(12) } else o.write(q, 12, q.size - 12 - 4)
        o.write(byteArrayOf(0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4))
        ip.forEach { o.write(it) }
        val b = o.toByteArray()
        b[2] = 0x84.toByte()                                 // response, authoritative
        b[7] = 1                                             // one answer
        return b
    }

    @Test fun answers() {
        val a = answer("move.local", intArrayOf(192, 168, 1, 42), compressed = true)
        assertEquals("192.168.1.42", MoveMdns.answer(a, a.size, "move.local"))
        val b = answer("Move.local", intArrayOf(10, 0, 0, 5), compressed = false)
        assertEquals("10.0.0.5", MoveMdns.answer(b, b.size, "move.local."))
        assertNull(MoveMdns.answer(a, a.size, "other.local"))
        assertNull(MoveMdns.answer(a, 20, "move.local"))                           // (cut short)
        val q = MoveMdns.query("move.local", 1)
        assertNull(MoveMdns.answer(q, q.size, "move.local"))                       // (a question, not an answer)
    }
}

class MoveTakesTest {
    @Test fun wavNamedWithTempoAndBars() {
        val dir = Files.createTempDirectory("takes").toFile()
        val t = MoveTakes(dir)
        t.start(48000, now = 0)
        val pcm = ShortArray(2 * 100) { (it * 100).toShort() }
        t.write(pcm, 100)
        t.write(pcm, 50)
        assertEquals(150L, t.frames)
        val f = t.finish(2, 1205)
        assertNotNull(f)
        assertTrue(f!!.name, f.name.endsWith("-120.5bpm-2bars.wav"))
        val b = f.readBytes()
        assertEquals(44 + 150 * 4, b.size)
        assertEquals("RIFF", String(b, 0, 4))
        assertEquals(48000, (b[24].toInt() and 255) or ((b[25].toInt() and 255) shl 8) or ((b[26].toInt() and 255) shl 16))
        assertEquals(600, (b[40].toInt() and 255) or ((b[41].toInt() and 255) shl 8))
        t.start(44100)
        assertNull(t.finish(1, 1200))                        // (nothing recorded: no file)
        assertEquals(1, t.list().size)
        t.start(44100, now = 1000)
        t.write(pcm, 1)
        assertTrue(t.finish(1, 1200)!!.name.endsWith("-120bpm-1bar.wav"))
    }
}

/** (0.5.11) the app's volume <-> the Move's slot 0 level, the web page's scales (move-link.js pageLevel, main.js level) */
class MoveLevelTest {
    @Test fun volumeToLevel() {
        assertEquals(0, MoveLink.levelOf(0f))
        assertEquals(50, MoveLink.levelOf(0.5f))
        assertEquals(80, MoveLink.levelOf(0.8f))
        assertEquals("full is 100", 100, MoveLink.levelOf(1f))
        assertEquals("kept to 0..127", 127, MoveLink.levelOf(3f))
        assertEquals(0, MoveLink.levelOf(-1f))
    }

    @Test fun levelToVolume() {
        assertEquals(0f, MoveLink.volumeOf(0), 0f)
        assertEquals(0.85f, MoveLink.volumeOf(85), 1e-6f)
        assertEquals(1f, MoveLink.volumeOf(100), 0f)
        assertEquals("above 100: full", 1f, MoveLink.volumeOf(127), 0f)
        for (l in 0..100) assertEquals("level $l there and back", l, MoveLink.levelOf(MoveLink.volumeOf(l)))
    }
}
