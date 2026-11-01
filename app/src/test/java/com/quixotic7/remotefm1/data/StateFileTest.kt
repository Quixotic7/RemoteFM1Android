// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/StateFileTests.swift (the .rfm1state format: round trip,
// bad files refused in plain words; import with duplicates skipped; the mirror; Back up everything and Restore). The
// mirror's folder is a FileTree here (on the phone: the picked folder, SafTree); the backup is one .zip.
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class StateFileTest {
    private val codec = TestCodec.codec

    private fun flash(v: Int, n: Int = 4096) = ByteArray(n) { (((it * 7) and 0xFF) xor v).toByte() }

    private fun save(lib: StateLibrary, name: String, v: Int, id: String = "fizz", fw: String = "Fizz") =
        lib.save(name, firmwareID = id, firmwareName = fw, kind = "native", version = "1.0", flash = flash(v), master = 512,
            transpose = -1, keyTouch = true, origin = "app")

    private fun lib() = StateLibrary(tempDir(), codec = codec)

    private fun decodeFails(d: ByteArray): StateFile.FileError =
        try { StateFile.decode(d, codec); fail("decoded"); throw IllegalStateException() } catch (e: StateFile.FileError) { e }

    // ---- the format

    @Test fun roundTrip() {
        val lib = lib()
        val s = save(lib, "Pads", 1)
        val d = StateFile.encode(s, flash(1), codec)
        assertArrayEquals("RFM1STAT".toByteArray(), d.copyOfRange(0, 8))
        assertTrue("compressed", d.size < 4096)
        val back = StateFile.decode(d, codec)
        assertEquals(lib.entry(s), back.state)
        assertArrayEquals(flash(1), back.flash)
        assertEquals(-1, back.state.transpose)
        assertEquals(512, back.state.master)
    }

    @Test fun anUncompressedFlashReadsToo() {
        val none = object : FlashCodec {
            override fun compress(raw: ByteArray): ByteArray? = null
            override fun decompress(z: ByteArray, size: Int): ByteArray? = null
        }
        val s = save(lib(), "Pads", 1)
        val d = StateFile.encode(s, flash(1), none)
        assertTrue(String(d, Charsets.UTF_8).contains("\"flashEncoding\" : \"none\""))
        assertArrayEquals(flash(1), StateFile.decode(d, codec).flash)
    }

    @Test fun badFilesRefused() {
        val s = save(lib(), "Pads", 1)
        val d = StateFile.encode(s, flash(1), codec)
        var e = decodeFails("hello world, not a state".toByteArray())
        assertEquals(StateFile.FileError.NotState, e)
        assertEquals("This isn't a RemoteFM1 state file.", e.message)
        e = decodeFails(d.copyOf(d.size - 10))
        assertEquals(StateFile.FileError.Truncated(d.size - 10, d.size), e)
        assertTrue(e.message!!.contains("cut short"))
        assertTrue(decodeFails(d.copyOf(20)) is StateFile.FileError.Truncated)
        val bad = d.copyOf()
        bad[bad.size - 3] = (bad[bad.size - 3].toInt() xor 0xFF).toByte()
        assertEquals(StateFile.FileError.Damaged, decodeFails(bad))
        val m = d.copyOf()
        m[14] = '#'.code.toByte()                                   // (inside the manifest's JSON)
        assertEquals(StateFile.FileError.BadManifest, decodeFails(m))
        // a newer format: said so
        val newer = String(d, 12, (d[8].toInt() and 0xFF) or ((d[9].toInt() and 0xFF) shl 8), Charsets.UTF_8)
            .replace("\"format\" : 1", "\"format\" : 7")
        val nd = StateFile.encode(s, flash(1), codec).let { orig ->
            val json = newer.toByteArray()
            val body = orig.copyOfRange(12 + json.size, orig.size)
            "RFM1STAT".toByteArray() + byteArrayOf(json.size.toByte(), (json.size shr 8).toByte(), 0, 0) + json + body
        }
        e = decodeFails(nd)
        assertEquals(StateFile.FileError.Newer(7), e)
        assertEquals("This state file was made by a newer RemoteFM1 (format 7): update the app to open it.", e.message)
        // a manifest length past 1 MiB
        val big = d.copyOf()
        big[11] = 1
        assertEquals(StateFile.FileError.BadManifest, decodeFails(big))
    }

    @Test fun safeNames() {
        assertEquals("Lead-1", StateFile.safeName("Lead/1"))
        assertEquals("a-b", StateFile.safeName(" a:b "))
        assertEquals("hidden", StateFile.safeName("..hidden"))
        assertEquals("State", StateFile.safeName("  "))
        assertEquals(120, StateFile.safeName("x".repeat(300)).length)
    }

    // ---- import

    @Test fun importSkipsDuplicates() {
        val a = lib()
        val b = lib()
        val s = save(a, "Pads", 1)
        val file = StateFile.encode(s, flash(1), codec)
        val i = b.importBytes(file)
        assertFalse(i.duplicate)
        assertEquals("its id kept", s.id, i.state.id)
        assertArrayEquals(flash(1), b.flash(i.state))
        assertTrue(b.importBytes(file).duplicate)
        assertEquals(1, b.list("fizz").size)
        // the same bytes under another name: still a duplicate; another firmware: not
        var other = s.copy(id = UUID.randomUUID().toString(), name = "Copy")
        assertTrue(b.importState(StateFile.Decoded(other, flash(1))).duplicate)
        other = other.copy(firmwareID = "buzz")
        assertFalse(b.importState(StateFile.Decoded(other, flash(1))).duplicate)
        // a taken id gets a new one
        val t = save(b, "Taken", 9)
        val c = b.importState(StateFile.Decoded(s.copy(id = t.id), flash(2)))
        assertNotEquals(t.id, c.state.id)
        assertEquals(4, b.allStates().size)
        // an id that would leave its folder: a new one
        val x = b.importState(StateFile.Decoded(s.copy(id = "../x"), flash(3)))
        assertNotEquals("../x", x.state.id)
    }

    // ---- the mirror

    @Test fun mirrorSync() {
        val clock = TestClock(1_800_000_000.0)
        val lib = StateLibrary(tempDir(), { clock.t += 1; clock.t }, codec)     // (a before b: the same name sorts by date)
        val top = tempDir()
        val docs = File(top, "States")
        val mirror = StateMirror(FileTree(top), lib)
        val a = save(lib, "Pads", 1)
        val b = save(lib, "Pads", 2)
        val c = save(lib, "Lead/1", 3, id = "amb", fw = "Ambient")
        var r = mirror.sync()
        assertEquals(3, r.written)
        assertTrue(File(docs, "README.txt").exists())
        val pads = File(docs, "Fizz/Pads.rfm1state")
        val pads2 = File(docs, "Fizz/Pads 2.rfm1state")
        assertEquals(a.id, StateFile.read(pads, codec).state.id)
        assertEquals(b.id, StateFile.read(pads2, codec).state.id)
        assertArrayEquals(flash(3), StateFile.read(File(docs, "Ambient/Lead-1.rfm1state"), codec).flash)
        r = mirror.sync()
        assertEquals("nothing to do", StateMirror.Report(), r)

        // a rename moves the file; a delete removes it; the folder left empty goes
        lib.rename(b, "Strings")
        lib.delete(c)
        r = mirror.sync()
        assertEquals(1, r.moved)
        assertEquals(1, r.removed)
        assertEquals("Strings", StateFile.read(File(docs, "Fizz/Strings.rfm1state"), codec).state.name)
        assertFalse(pads2.exists())
        assertFalse(File(docs, "Ambient").exists())

        // a file removed there comes back (the library wins)
        pads.delete()
        assertEquals(1, mirror.sync().written)
        assertTrue(pads.exists())

        // a file the user drops in: imported; a duplicate dropped: left, skipped
        val other = lib()
        val n = save(other, "From a friend", 7, id = "amb", fw = "Ambient")
        File(docs, "Inbox").mkdirs()
        File(docs, "Inbox/friend.rfm1state").writeBytes(StateFile.encode(n, flash(7), codec))
        File(docs, "dup.rfm1state").writeBytes(StateFile.encode(a, flash(1), codec))
        File(docs, "junk.rfm1state").writeBytes("junk".toByteArray())
        r = mirror.sync()
        assertEquals(1, r.imported)
        assertEquals(0, r.duplicates)                           // (dup.rfm1state has a's id: a's own file, elsewhere)
        assertEquals(1, r.failed.size)
        assertTrue(r.failed[0], r.failed[0].contains("isn't a RemoteFM1 state"))
        assertEquals(listOf("From a friend"), lib.list("amb").map { it.name })
        assertTrue(File(docs, "Ambient/From a friend.rfm1state").exists())
        assertFalse("moved in place", File(docs, "Inbox/friend.rfm1state").exists())
        // a note change rewrites the file in place
        lib.rename(a, "Pads", note = "new note")
        r = mirror.sync()
        assertEquals(0, r.written)
        assertEquals("new note", StateFile.read(pads, codec).state.note)
    }

    // ---- backups

    @Test fun backupAndRestore() {
        val lib = lib()
        val hist = FlashHistory(tempDir()).apply { rollingInterval = 0.0 }
        save(lib, "Pads", 1)
        save(lib, "Bass", 2)
        save(lib, "Lead", 3, id = "amb", fw = "Ambient")
        for (v in 10..14) hist.record(flash(v), "fizz", "1.0", FlashHistory.Reason.Other("copy $v"))
        val cur = listOf(Backup.Current("fizz", "Fizz", "native", "1.0", flash = flash(20)),
            Backup.Current("fizz", "Fizz", "wasm", "1.0", flash = flash(20)),
            Backup.Current("amb", "Ambient", "native", "2.0", flash = flash(21)))
        val date = 1_800_000_000.0
        val out = ByteArrayOutputStream()
        val m = Backup.writeZip(out, lib, hist, cur, "test", date)
        val zip = out.toByteArray()
        assertTrue(Backup.folderName(date).startsWith("RemoteFM1 backup "))
        assertEquals(3, m.states.size)
        assertEquals("the same flash once", listOf("Fizz current", "Ambient current"), m.current.map { it.name })
        assertEquals("the newest 3", 3, m.safetyCopies.size)
        assertTrue(Backup.check(ByteArrayInputStream(zip), m, codec))
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) names.add((z.nextEntry ?: break).name) }
        val top = Backup.folderName(date)
        assertTrue(names.all { it.startsWith("$top/") })
        assertTrue("$names", "$top/States/Fizz/Pads.rfm1state" in names)
        assertTrue("$top/Current/Fizz current.rfm1state" in names)
        assertTrue("$top/manifest.json" in names)
        assertTrue("$top/README.txt" in names)
        assertEquals(3, names.count { it.startsWith("$top/Safety copies/Fizz/") })
        // the zip's name in a folder: a second one in the same minute gets " 2"
        val t = FileTree(tempDir())
        val p1 = Backup.zipPath(t, date)
        t.write(p1, zip)
        assertEquals("Backups/$top 2.zip", Backup.zipPath(t, date))

        // into an empty library: states and current, not the safety copies; again: all skipped
        val fresh = lib()
        var r = Backup.restoreZip(ByteArrayInputStream(zip), fresh)
        assertEquals(5, r.imported.size)
        assertEquals(emptyList<String>(), r.failed)
        assertEquals(setOf("Pads", "Bass", "Fizz current"), fresh.list("fizz").map { it.name }.toSet())
        r = Backup.restoreZip(ByteArrayInputStream(zip), fresh)
        assertEquals(0, r.imported.size)
        assertEquals(5, r.duplicates)
        assertEquals("No new states (5 already in the library).", r.text)
        // into the library it came from: only the current flashes are new
        assertEquals(2, Backup.restoreZip(ByteArrayInputStream(zip), lib).imported.size)

        // the same as a folder (unzipped), with a broken file in it: said, the others still imported
        val dir = tempDir()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                File(dir, e.name).apply { parentFile!!.mkdirs() }.writeBytes(z.readBytes())
            }
        }
        File(dir, "$top/States/Fizz/broken.rfm1state").writeBytes("RFM1STAT".toByteArray())
        r = Backup.restoreTree(FileTree(dir), top, lib())
        assertEquals(5, r.imported.size)
        assertEquals(1, r.failed.size)
        assertTrue(r.failed[0], r.failed[0].startsWith("broken.rfm1state: "))
        assertTrue(r.text, r.text.startsWith("Imported 5 states. 1 file couldn't be read: broken.rfm1state: "))
        // a folder that isn't a backup: every .rfm1state in it (the safety copies too)
        r = Backup.restoreTree(FileTree(File(dir, "$top/Safety copies")), "", lib())
        assertEquals(3, r.imported.size)
        // single files
        val one = File(dir, "$top/States/Fizz/Pads.rfm1state")
        r = Backup.restoreFiles(listOf(one.name to one.readBytes()), lib())
        assertEquals("Imported 1 state.", r.text)
    }

    @Test fun theManifestReadsBack() {
        val m = Backup.Manifest(date = 1_800_000_000.5, app = "0.1")
        m.states.add(Backup.Item("States/Fizz/Pads.rfm1state", "Pads", "fizz", "1.0", 1.5, "ab"))
        assertEquals(m, Backup.Manifest.fromJson(Json.obj(Json.write(m.toJson()))))
        assertTrue(Json.write(m.toJson()).contains("\"current\" : [\n\n  ]"))
    }

    @Test fun restoreWordsNameTheMissingFirmware() {
        val r = Backup.RestoreReport()
        r.imported.add(SavedState("a", "A", "", "loom", "LOOM-8", "native", "1", "", 0.0, 1, "", origin = "app"))
        assertEquals("Imported 1 state. Not installed: LOOM-8 (its states wait in the library).",
            DataFolder.restored(r, setOf("fizz")))
        assertEquals("Imported 1 state.", DataFolder.restored(r, setOf("loom")))
    }

    @Test fun lzfseRoundTripsWhenBuilt() {
        org.junit.Assume.assumeTrue("no host lzfse (cc)", LzfseHost.loaded)
        for (d in listOf(flash(1, 65536), ByteArray(262144) { 0xFF.toByte() }, java.util.Random(5).let { r -> ByteArray(65536).also { r.nextBytes(it) } })) {
            val z = Lzfse.compress(d)!!
            assertArrayEquals(d, Lzfse.decompress(z, d.size))
            assertEquals("a wrong size is refused", null, Lzfse.decompress(z, d.size - 1))
            assertEquals(null, Lzfse.decompress(z, d.size + 1))
        }
        assertEquals(null, Lzfse.decompress(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 100))
        assertEquals(FlashFile.sha256(flash(1)), FlashFile.sha256(Lzfse.decompress(Lzfse.compress(flash(1))!!, 4096)!!))
    }
}
