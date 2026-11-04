// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/FlashHistoryTests.swift (FlashHistory's rules; the
// player's safety copies through DataSafety: before a version change, a delete, a reset, a restore; the wiped memory;
// the load question; the built-in memory adopted). The FlashFile cases are ../FlashFileTest.kt (A2). iOS's cases with
// a real core (CorePlayer, the AU) are DataSafety's functions here, on files.
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class FlashHistoryTest {
    private fun bytes(v: Int, n: Int = 64) = ByteArray(n) { v.toByte() }

    @Test fun rollingCopiesAreDedupedAndLimited() {
        val clock = TestClock(1_800_000_000.0)
        val h = FlashHistory(tempDir()) { clock.t }
        assertNotNull(h.record(bytes(1), "fizz", "1", FlashHistory.Reason.Rolling))
        clock.t += 600
        assertNull("the same as the newest", h.record(bytes(1), "fizz", "1", FlashHistory.Reason.Rolling))
        clock.t = 1_800_000_060.0
        assertNull("too soon after the last", h.record(bytes(2), "fizz", "1", FlashHistory.Reason.Rolling))
        clock.t += 300
        assertNotNull(h.record(bytes(2), "fizz", "1", FlashHistory.Reason.Rolling))
        assertEquals(2, h.list("fizz").size)
        // a risky step's copy equal to the newest: that copy relabelled, not kept twice
        val e = h.record(bytes(2), "fizz", "1", FlashHistory.Reason.BeforeReset)
        assertEquals("before reset", e?.reason)
        assertEquals(2, h.list("fizz").size)
        assertEquals(FlashHistory.Kind.EVENT, h.list("fizz").first().kind)
        // 15 more distinct saves 5 min apart, the same day: the last 10 rolling, the event kept
        h.rollingInterval = 0.0
        for (i in 0 until 15) {
            clock.t += 300
            h.record(bytes(10 + i), "fizz", "1", FlashHistory.Reason.Rolling)
        }
        val l = h.list("fizz")
        assertEquals(10, l.count { it.kind == FlashHistory.Kind.ROLLING })
        assertEquals(1, l.count { it.kind == FlashHistory.Kind.EVENT })
        assertEquals("newest first", FlashFile.sha256(bytes(24)), l.first().sha256)
        assertNotNull(h.data(l[0]))
        val files = h.dir("fizz").list()!!.toList()
        assertEquals("pruned copies' files removed", 11, files.count { it.endsWith(".bin") })
        assertTrue(h.list("other").isEmpty())
    }

    @Test fun oneADayForAWeek() {
        val utc = ZoneId.of("UTC")
        val start = LocalDateTime.of(2026, 10, 1, 9, 0).toEpochSecond(ZoneOffset.UTC).toDouble()
        val clock = TestClock(start)
        val h = FlashHistory(tempDir()) { clock.t }
        h.zone = utc
        h.rollingInterval = 0.0
        var n = 0
        repeat(12) {                                            // 12 days, 3 saves a day (9:00, 12:00, 15:00)
            repeat(3) {
                n++
                h.record(bytes(n), "loom", "1", FlashHistory.Reason.Rolling)
                clock.t += 3 * 3600
            }
            clock.t += 15 * 3600
        }
        val l = h.list("loom")
        val rolling = l.filter { it.kind == FlashHistory.Kind.ROLLING }
        val daily = l.filter { it.kind == FlashHistory.Kind.DAILY }
        assertEquals(10, rolling.size)
        // the last 10 cover the last 4 days (10 = 3 + 3 + 3 + 1); days 7, 6, 5 back keep their newest as daily
        assertEquals(3, daily.size)
        fun at(t: Double) = java.time.Instant.ofEpochSecond(t.toLong()).atZone(utc)
        assertEquals("one a day for the last 7 days", 7, l.map { at(it.date).toLocalDate() }.toSet().size)
        for (d in daily) {
            assertEquals("the day's newest", 15, at(d.date).hour)
            assertEquals("daily", d.reason)
        }
    }

    @Test fun eventCopiesArePrunedAfterTheLimit() {
        val clock = TestClock(DataDates.now())
        val h = FlashHistory(tempDir()) { clock.t }
        h.keepEvents = 3
        for (i in 0 until 5) {
            clock.t += 1
            h.record(bytes(i), "x", "1", FlashHistory.Reason.BeforeDelete("X $i"))
        }
        assertEquals(listOf("before deleting X 4", "before deleting X 3", "before deleting X 2"), h.list("x").map { it.reason })
        assertNull("nothing to copy", h.record(ByteArray(0), "x", "1", FlashHistory.Reason.BeforeReset))
    }

    @Test fun reasonsReadAsOnIOS() {
        assertEquals("before update 1.6.1→1.6.2", FlashHistory.Reason.BeforeUpdate("1.6.1", "1.6.2").text)
        assertEquals("before update ?→1.6.2", FlashHistory.Reason.BeforeUpdate("", "1.6.2").text)
        assertEquals("before delete", FlashHistory.Reason.BeforeDelete("").text)
        assertEquals("before load state", FlashHistory.Reason.BeforeLoadState.text)
        assertEquals("before manager write", FlashHistory.Reason.BeforeManagerWrite.text)
        assertEquals("first safety copy", FlashHistory.Reason.FirstCopy.text)
    }

    @Test fun anIOSStepOneIndexIsSplitWhenRead() {
        val root = tempDir()
        val dir = File(root, "loom").apply { mkdirs() }
        val d = bytes(9, 128)
        val s = FlashHistory.Snapshot("1B6C4A29-5F1D-4C55-9A6B-0F6E1F0C2A11", FlashHistory.Kind.EVENT, "before reset",
            "loom", "1.6.1", 1_800_000_000.0, d.size, FlashFile.sha256(d))
        File(dir, "${s.id}.bin").writeBytes(d)
        File(dir, "index.json").writeText(Json.write(listOf(s.toJson())))
        val h = FlashHistory(root)
        assertEquals(listOf(s), h.list("loom"))
        assertArrayEquals(d, h.data(s))
        assertFalse(File(dir, "index.json").exists())
    }

    // ---- the player's side (DataSafety on files; iOS: CorePlayer with a real core)

    private class Player(dir: File, var version: String = "0.26.1", val flashSize: Int = 0) {
        val cores = File(dir, "cores")
        val history = FlashHistory(File(dir, "FlashHistory"))
        val library = StateLibrary(File(dir, "States"), codec = TestCodec.codec)
        val firmwares = HashMap<String, DataSafety.Firmware>()
        val safety = DataSafety(history, library, { File(cores, "$it.bin") }, { firmwares[it] })
        init { firmwares["choralroot"] = DataSafety.Firmware("choralroot", "choralroot", "ChoralRoot", "native", version, "", flashSize) }
        fun file(k: String = "choralroot") = File(cores, "$k.bin")
        /** what CoreFlash does on a save: the write, then the hook */
        fun save(d: ByteArray, k: String = "choralroot") {
            FlashFile.write(d, file(k), firmwares[k]?.firmwareID ?: k, firmwares[k]?.version ?: "")
            safety.written(k, d)
        }
    }

    private fun image(mark: Int, n: Int = 4096) = ByteArray(n) { ((it * 13 + 1) and 0xFF).toByte() }.also { it[n - 1] = mark.toByte() }

    @Test fun copiesBeforeVersionChangeResetAndRestore() {
        val p = Player(tempDir())
        val a = image(0xA1)
        // a flash written by an older ChoralRoot
        FlashFile.write(a, p.file(), "choralroot", "0.0.1")
        p.safety.checkVersion("choralroot")                      // (the boot)
        var l = p.safety.safetyCopies("choralroot")
        assertEquals("before update 0.0.1→0.26.1", l.first().reason)
        assertEquals(FlashFile.sha256(a), l.first().sha256)
        assertEquals("0.0.1", l.first().version)
        assertEquals("the sidecar names this version now", "0.26.1", FlashFile.meta(p.file())!!.version)
        p.safety.checkVersion("choralroot")                      // (the next boot: nothing more)
        assertEquals(1, p.safety.safetyCopies("choralroot").size)

        // before a delete
        assertEquals(FlashHistory.Kind.EVENT, p.safety.safetyCopy("choralroot", FlashHistory.Reason.BeforeDelete("ChoralRoot"))?.kind)

        // reset: a copy of the current memory, then its files gone (the player boots it fresh)
        val current = image(0xA2)
        p.save(current)
        val snap = p.safety.reset("choralroot")
        assertEquals(FlashFile.sha256(current), snap?.sha256)
        assertEquals("before reset", p.safety.safetyCopies("choralroot").first().reason)
        assertFalse(p.file().exists())
        assertFalse(FlashFile.prevFile(p.file()).exists())
        assertTrue(p.safety.resetMessage("choralroot").contains("clean memory"))

        // restore the copy from before the update: a copy of the current memory first, then written
        p.save(image(0xA3))                                      // (the fresh memory, saved)
        l = p.safety.safetyCopies("choralroot")
        val old = l.first { it.reason.startsWith("before update") }
        val d = p.safety.prepareRestore("choralroot", old)
        p.safety.writeFlash("choralroot", d)
        assertArrayEquals(a, FlashFile.read(p.file(), a.size).data)
        assertEquals("before restore", p.safety.safetyCopies("choralroot").first().reason)
        assertTrue(p.safety.restoreMessage(old).contains("(before update 0.0.1→0.26.1)"))
        // a copy of another firmware isn't restored here
        try {
            p.safety.prepareRestore("choralroot", old.copy(firmwareID = "felucca"))
            fail("another firmware's copy restored")
        } catch (e: DataSafety.Refused) {
            assertEquals("That safety copy can't be read.", e.message)
        }
    }

    @Test fun aFileFromBeforeTheSidecarsGetsAFirstCopy() {
        val p = Player(tempDir())
        p.file().parentFile!!.mkdirs()
        p.file().writeBytes(image(1))
        p.safety.checkVersion("choralroot")
        assertEquals("first safety copy", p.safety.safetyCopies("choralroot").single().reason)
        assertNull("no wipe watch without a known version", p.safety.wipedCopy)
    }

    @Test fun rollingCopiesComeFromTheSaves() {
        val p = Player(tempDir())
        p.history.rollingInterval = 0.0
        p.save(image(1))
        p.save(image(1))
        p.save(image(2))
        val l = p.safety.safetyCopies("choralroot")
        assertEquals(2, l.size)
        assertTrue(l.all { it.kind == FlashHistory.Kind.ROLLING && it.version == "0.26.1" })
    }

    /** after a version change, a first save that's empty offers the copy from before; Restore puts it back */
    @Test fun wipedMemoryAfterAnUpdateIsOffered() {
        val p = Player(tempDir())
        val a = image(0xB2)
        assertFalse(FlashFile.looksErased(a))
        FlashFile.write(a, p.file(), "choralroot", "0.0.1")
        p.safety.checkVersion("choralroot")
        assertNull(p.safety.wipedCopy)
        p.save(ByteArray(a.size) { 0xFF.toByte() })               // (the new version's first save: erased)
        val c = p.safety.wipedCopy ?: return fail("an erased first save after the update is offered back")
        assertEquals(FlashFile.sha256(a), c.sha256)
        p.save(ByteArray(a.size) { 0xFF.toByte() })               // (only the first is looked at)
        val r = p.safety.answerWiped(restore = true)!!
        assertNull(p.safety.wipedCopy)
        p.safety.writeFlash("choralroot", p.safety.prepareRestore("choralroot", r))
        assertArrayEquals(a, FlashFile.read(p.file(), a.size).data)
        assertTrue(FlashFile.looksErased(ByteArray(100)))
        assertNull(p.safety.answerWiped(restore = false))
    }

    @Test fun aMemoryKeptAfterTheUpdateIsNotOffered() {
        val p = Player(tempDir())
        FlashFile.write(image(1), p.file(), "choralroot", "0.0.1")
        p.safety.checkVersion("choralroot")
        repeat(6) { p.save(image(10 + it)) }
        p.save(ByteArray(4096) { 0xFF.toByte() })                 // (the 7th: no longer watched)
        assertNull(p.safety.wipedCopy)
    }

    /** (iOS 3828820) a state from a newer version: the Load question warns; an older version's loads plainly */
    @Test fun loadQuestionWarnsAboutANewerVersion() {
        var s = SavedState("x", "Jam 3", "", "fizz", "FIZZ", "native", "0.11", "", DataDates.now(), 1, "", origin = "app")
        assertEquals("Load \"Jam 3\" (made with 0.11) into 0.12? The current memory is kept as a safety copy first.",
            s.loadQuestion("0.12"))
        assertEquals("Load", s.loadAction("0.12"))
        assertTrue(!s.newer("0.12"))
        assertTrue(s.newer("0.10"))
        assertTrue(s.loadQuestion("0.10").contains("made with version 0.11, newer than this 0.10"))
        assertEquals("Load anyway", s.loadAction("0.10"))
        s = s.copy(version = "0.12")
        assertEquals("Load \"Jam 3\"? The current memory is kept as a safety copy first.", s.loadQuestion("0.12"))
        assertEquals("Load", s.loadAction("0.12"))
    }

    /** a removed built-in core's memory (<id>.bin) becomes its .rfm1's at that one's first boot, with a safety copy */
    @Test fun importedFirmwareAdoptsTheRemovedBuiltInMemory() {
        val p = Player(tempDir())
        val old = ByteArray(4096) { ((it * 13 + 1) and 0xFF).toByte() }
        FlashFile.write(old, p.file("oldsynth"), "oldsynth", "1.4.5")
        val info = DataSafety.Firmware("rfm1-oldsynth", "oldsynth", "Old Synth", "wasm", "1.5.1")
        p.firmwares[info.key] = info
        p.safety.adoptBuiltInFlash(info, hasBuiltIn = false)
        val url = p.file("rfm1-oldsynth")
        assertArrayEquals("the built-in one's memory is the .rfm1's now", old, FlashFile.read(url, 0).data)
        assertEquals("(so the boot sees a version change and watches it)", "1.4.5", FlashFile.meta(url)!!.version)
        assertEquals("a safety copy first", FlashFile.sha256(old), p.history.list("oldsynth").first().sha256)
        // its own memory now: never replaced again
        FlashFile.write(byteArrayOf(1, 2, 3, 4), url, "oldsynth", "1.5.1")
        p.safety.adoptBuiltInFlash(info, hasBuiltIn = false)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), FlashFile.read(url, 0).data)
    }
}
