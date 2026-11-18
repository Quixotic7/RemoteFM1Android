// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Ableton Move's Kotlin side on the JVM: the address as typed (move-link.js normalizeUrl's cases), the projects'
// file (move-projects.js packFile / unpackFile / withColor, with the web's refusals) and their store (one .rfmmove a
// slot, iOS's names), the settings in the preferences, the mDNS question and its answer, and Sample's WAV.
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

class MoveProjectsTest {
    // a stand-in blob: "RMSQ", 20 bytes of body, its FNV-1a (the core's check is the emulator test's)
    private fun blob(seed: Int, n: Int = 40): ByteArray = MoveProjects.withColor(ByteArray(n) { (it * 7 + seed).toByte() }.also {
        it[0] = 'R'.code.toByte(); it[1] = 'M'.code.toByte(); it[2] = 'S'.code.toByte(); it[3] = 'Q'.code.toByte()
    }, 0)
    private val ok: (ByteArray) -> Int = { 0 }

    @Test fun packAndUnpack() {
        val a = MoveProject(3, blob(1), 1_700_000_000_000L)
        val b = MoveProject(10, blob(2, 60), 0)
        val one = MoveProjects.packFile(listOf(a))
        assertEquals(MoveProjects.KIND_ONE, one[5].toInt())
        assertEquals(MoveProjects.HEAD + MoveProjects.ENTRY + a.bytes.size, one.size)
        val u = MoveProjects.unpackFile(one, ok)
        assertEquals(1, u.entries.size)
        assertEquals(3, u.entries[0].slot)
        assertArrayEquals(a.bytes, u.entries[0].bytes)
        assertEquals(1_700_000_000_000L, u.entries[0].saved)
        val set = MoveProjects.unpackFile(MoveProjects.packFile(listOf(a, b)), ok)
        assertEquals(MoveProjects.KIND_SET, set.kind)
        assertEquals(listOf(3, 10), set.entries.map { it.slot })
        // the web's layout: "RMVP" 1 kind count16, slot flags saved32 len32
        assertArrayEquals(byteArrayOf(0x52, 0x4d, 0x56, 0x50, 1, 1, 1, 0, 3, 0), one.copyOf(10))
    }

    private fun refused(b: ByteArray?, says: String, check: (ByteArray) -> Int = ok) {
        try { MoveProjects.unpackFile(b, check); fail("accepted: $says") } catch (e: IllegalArgumentException) {
            assertTrue("${e.message} / $says", e.message!!.contains(says))
        }
    }

    @Test fun theWebsRefusals() {
        val good = MoveProjects.packFile(listOf(MoveProject(0, blob(1), 0)))
        refused(null, "nothing to read")
        refused(byteArrayOf(1, 2, 3), "does not start with RMVP")
        refused(good.copyOf().also { it[4] = 2 }, "version 2")
        refused(good.copyOf().also { it[5] = 3 }, "kind 3")
        refused(good.copyOf().also { it[6] = 2 }, "2 projects")
        refused(good.copyOf().also { it[8] = 40 }, "out of range")
        refused(good.copyOf(good.size - 1), "cut off")
        refused(good + byteArrayOf(0), "1 bytes after the last project")
        refused(good, "damaged") { -1 }
        val two = MoveProjects.packFile(listOf(MoveProject(4, blob(1), 0), MoveProject(4, blob(2), 0)))
        refused(two, "twice")
        refused(ByteArray(MoveProjects.MAX_FILE + 1).also { MoveProjects.MAGIC.copyInto(it) }, "more than a full set")
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
        val s = MoveProjects(dir, ok)
        assertTrue(s.list().isEmpty())
        s.put(0, blob(1), 1_600_000_000_000L)
        s.put(31, blob(2))
        assertEquals(listOf(0, 31), s.list().map { it.slot })
        assertEquals((1 shl 0) or (1 shl 31), s.dirBits())
        assertTrue(File(dir, "move-project-01.rfmmove").isFile)
        assertTrue(File(dir, "move-project-32.rfmmove").isFile)
        // each file is the web's one-project file, so it imports anywhere as it is
        val u = MoveProjects.unpackFile(File(dir, "move-project-01.rfmmove").readBytes(), ok)
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
