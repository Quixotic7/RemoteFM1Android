// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/StateLibraryTests.swift (CRUD, two libraries on one
// folder, the change notice, two histories writing at once; the app's load and save of a state through DataSafety).
// Not ported: the App Group migration and the AU (Android has neither).
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StateLibraryTest {
    private fun bytes(v: Int, n: Int = 4096) = ByteArray(n) { v.toByte() }

    private inline fun <reified T : Throwable> assertThrows(f: () -> Unit) {
        try { f(); fail("no ${T::class.simpleName}") } catch (e: Throwable) { if (e !is T) throw e }
    }

    private fun concurrently(n: Int, f: (Int) -> Unit) {
        val ex = Executors.newFixedThreadPool(8)
        (0 until n).map { i -> ex.submit { f(i) } }.forEach { it.get() }
        ex.shutdown()
        ex.awaitTermination(10, TimeUnit.SECONDS)
    }

    @Test fun saveListLoadRenameDelete() {
        val clock = TestClock(1_800_000_000.0)
        val lib = StateLibrary(tempDir(), { clock.t }, TestCodec.codec)
        val a = lib.save("  Pads  ", firmwareID = "fizz", firmwareName = "Fizz", kind = "native", version = "1.0",
            flash = bytes(1), master = 700, transpose = 1, keyTouch = false, origin = "app")
        assertEquals("trimmed", "Pads", a.name)
        clock.t += 60
        val b = lib.save("", firmwareID = "fizz", firmwareName = "Fizz", kind = "native", version = "1.1", flash = bytes(2),
            origin = "au")
        assertTrue("the default name: ${b.name}", b.name.startsWith("Fizz "))
        assertEquals("newest first", listOf(b.id, a.id), lib.list("fizz").map { it.id })
        assertTrue(lib.list("other").isEmpty())
        assertArrayEquals(bytes(1), lib.flash(a))
        val z = File(lib.dir("fizz"), "${a.id}.flash").readBytes()
        assertTrue("compressed", z.size < 4096)
        assertEquals(700, a.master)
        assertEquals(1, a.keptTranspose)
        assertEquals(false, a.keyTouch)
        assertEquals(2, a.copy(transpose = 5).keptTranspose)
        assertEquals("made with 1.0", a.versionNote("1.1"))
        assertEquals("", a.versionNote("1.0"))

        val r = lib.rename(a, "Pads, bridge", note = "for Friday")
        assertEquals("Pads, bridge", lib.list("fizz").first { it.id == a.id }.name)
        assertEquals("for Friday", r.note)
        lib.delete(b)
        assertEquals(listOf(a.id), lib.list("fizz").map { it.id })
        assertThrows<StateLibrary.LibraryError> { lib.flash(b) }
        assertThrows<StateLibrary.LibraryError> { lib.rename(b, "x") }
        assertThrows<StateLibrary.LibraryError> {
            lib.save("e", firmwareID = "fizz", firmwareName = "", kind = "native", version = "", flash = ByteArray(0), origin = "app")
        }
        // a damaged flash file: refused, said so
        File(lib.dir("fizz"), "${a.id}.flash").writeBytes(byteArrayOf(1, 2, 3))
        try { lib.flash(a); fail() } catch (e: StateLibrary.LibraryError) { assertEquals("That state's memory can't be read.", e.message) }
    }

    @Test fun anEntryWithoutBytesIsNotListedAndOldBytesWithoutAnEntryGo() {
        val clock = TestClock(DataDates.now())
        val lib = StateLibrary(tempDir(), { clock.t }, TestCodec.codec)
        val a = lib.save("A", firmwareID = "fizz", firmwareName = "Fizz", kind = "native", version = "1", flash = bytes(1), origin = "app")
        val b = lib.save("B", firmwareID = "fizz", firmwareName = "Fizz", kind = "native", version = "1", flash = bytes(2), origin = "app")
        File(lib.dir("fizz"), "${a.id}.flash").delete()             // (a delete half done)
        File(lib.dir("fizz"), "${b.id}.json").delete()              // (a save cut short)
        assertTrue(lib.list("fizz").isEmpty())
        assertTrue(File(lib.dir("fizz"), "${b.id}.flash").exists())
        clock.t += 3601
        lib.list("fizz")
        assertFalse("an hour on: removed", File(lib.dir("fizz"), "${b.id}.flash").exists())
    }

    @Test fun twoLibrariesOnOneFolderNeverLoseAChange() {
        val root = tempDir()
        val l1 = StateLibrary(root, codec = TestCodec.codec)
        val l2 = StateLibrary(root, codec = TestCodec.codec)
        val n = 40
        concurrently(n) { i ->
            val l = if (i % 2 == 0) l1 else l2
            l.save("s$i", firmwareID = "loom", firmwareName = "Loom", kind = "native", version = "1", flash = bytes(i, 2048), origin = "app")
        }
        val all = l1.list("loom")
        assertEquals(n, all.size)
        assertEquals(all.map { it.id }.toSet(), l2.list("loom").map { it.id }.toSet())
        // renames by one, deletes by the other, at once: each lands
        concurrently(n) { i -> if (i % 2 == 0) l1.rename(all[i], "renamed $i") else l2.delete(all[i]) }
        val left = l2.list("loom")
        assertEquals(n / 2, left.size)
        assertTrue(left.all { it.name.startsWith("renamed") })
        for (s in left) assertNotNull(l2.flash(s))
        val files = l1.dir("loom").list()!!.toList()
        assertFalse("$files", files.any { it.endsWith(".tmp") })
    }

    @Test fun changesAreNotified() {
        val lib = StateLibrary(tempDir(), codec = TestCodec.codec)
        var seen = 0
        val l = { seen++; Unit }
        DataChanges.add(l)
        try {
            lib.save("x", firmwareID = "fizz", firmwareName = "Fizz", kind = "native", version = "1", flash = bytes(3), origin = "app")
            assertTrue(seen >= 1)
        } finally {
            DataChanges.remove(l)
        }
    }

    @Test fun historyConcurrentWritersKeepEveryCopy() {
        val root = tempDir()
        val h1 = FlashHistory(root).apply { keepEvents = 100 }
        val h2 = FlashHistory(root).apply { keepEvents = 100 }
        concurrently(40) { i -> (if (i % 2 == 0) h1 else h2).record(bytes(i, 256), "fizz", "1", FlashHistory.Reason.BeforeReset) }
        val l = h1.list("fizz")
        assertEquals(40, l.size)
        assertEquals(l.map { it.id }.toSet(), h2.list("fizz").map { it.id }.toSet())
        for (s in l) assertNotNull(h2.data(s))
        assertFalse(File(h1.dir("fizz"), "index.json").exists())
    }

    /** the entry's JSON as iOS's JSONEncoder writes it (sorted keys, nil left out), and read back */
    @Test fun entryJsonIsIOSs() {
        val s = SavedState("ID-1", "Pads / 2", "é \"q\"", "fizz", "Fizz", "native", "1.0", "", 1793000000.5, 4096, "ab",
            master = 700, origin = "app")
        val j = Json.write(s.toJson())
        assertEquals("""
            {
              "date" : 1793000000.5,
              "firmwareID" : "fizz",
              "firmwareName" : "Fizz",
              "flashSHA256" : "ab",
              "flashSize" : 4096,
              "id" : "ID-1",
              "kind" : "native",
              "master" : 700,
              "name" : "Pads \/ 2",
              "note" : "é \"q\"",
              "origin" : "app",
              "sha256" : "",
              "version" : "1.0"
            }""".trimIndent(), j)
        assertEquals(s, SavedState.fromJson(Json.obj(j)))
        assertNull(SavedState.fromJson(Json.obj(j)).transpose)
    }

    // ---- the app (iOS: CorePlayer's saveState / loadState with a real core)

    @Test fun saveAndLoadAStateThroughThePlayersHooks() {
        val dir = tempDir()
        val cores = File(dir, "cores")
        val history = FlashHistory(File(dir, "FlashHistory"))
        val lib = StateLibrary(File(dir, "States"), codec = TestCodec.codec)
        val fw = mutableMapOf("choralroot" to DataSafety.Firmware("choralroot", "choralroot", "ChoralRoot", "native", "0.26.1", "", 4096))
        val safety = DataSafety(history, lib, { File(cores, "$it.bin") }, { fw[it] })
        try { safety.saveState("choralroot", "x"); fail() } catch (e: DataSafety.Refused) {
            assertEquals("ChoralRoot has no memory saved yet: play it first.", e.message)
        }
        val mine = ByteArray(4096) { (it % 251).toByte() }
        FlashFile.write(mine, File(cores, "choralroot.bin"), "choralroot", "0.26.1")
        val s = safety.saveState("choralroot", "From the app", master = 512, transpose = -1, keyTouch = true)
        assertEquals("app", s.origin)
        assertEquals("Saved \"From the app\".", safety.savedMessage(s))
        assertEquals(listOf(s.id), safety.states("choralroot").map { it.id })

        // the memory moves on; the state loads back: a safety copy of the memory first
        val later = ByteArray(4096) { (it % 13).toByte() }
        FlashFile.write(later, File(cores, "choralroot.bin"), "choralroot", "0.26.1")
        val l = safety.prepareLoad("choralroot", s)
        safety.writeFlash("choralroot", l.flash)
        assertArrayEquals(mine, FlashFile.read(File(cores, "choralroot.bin"), 4096).data)
        assertEquals(512, l.master)
        assertEquals(-1, l.transpose)
        assertEquals(true, l.keyTouch)
        assertEquals("Loaded \"From the app\". The memory before it is in Safety copies.", l.message)
        val copy = safety.safetyCopies("choralroot").first()
        assertEquals("before load state", copy.reason)
        assertEquals(FlashFile.sha256(later), copy.sha256)

        // another version's state loads, said so; another firmware's and a wrong size don't
        fw["choralroot"] = fw["choralroot"]!!.copy(version = "0.27")
        assertEquals("Loaded \"From the app\" (made with 0.26.1). The memory before it is in Safety copies.",
            safety.prepareLoad("choralroot", s).message)
        try { safety.prepareLoad("choralroot", s.copy(firmwareID = "felucca")); fail() } catch (e: DataSafety.Refused) {
            assertEquals("That state is for another firmware.", e.message)
        }
        fw["choralroot"] = fw["choralroot"]!!.copy(flashSize = 8192)
        try { safety.prepareLoad("choralroot", s); fail() } catch (e: DataSafety.Refused) {
            assertEquals("That state's memory is 4096 bytes; ChoralRoot 0.27's is 8192.", e.message)
        }
        // Back up everything's current memories
        val cur = safety.currentFlashes(listOf("choralroot", "felucca"))
        assertEquals(1, cur.size)
        assertArrayEquals(mine, cur[0].flash)
    }
}
