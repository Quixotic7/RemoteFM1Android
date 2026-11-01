// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported in part from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/ImportTests.swift
// (testSyncMirrorsValidFilesAndListsInvalidOnes, testImportCopiesOnceAndKeepsNames, testCoreRefResolves) and
// FirmwareStoreTests' file states: the library (files/Firmware/<sha256>.rfm1 and index.json in iOS's fields), an
// import, a refused file not kept, a delete, a sync after a cut-off import; a saved choice resolved; the selection's
// replacement on a delete; a built-in core's memory adopted by its .rfm1.
package com.quixotic7.remotefm1.firmware

import com.quixotic7.remotefm1.FlashFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class FirmwareLibraryTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("rfm1-lib").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun library() = FirmwareLibrary(File(root, "Firmware"), FakeRfm1)

    @Test fun importStoresByHashWithAnIndex() {
        val lib = library()
        lib.sync()
        val a = lib.import(FakeRfm1.amb)
        assertNull(a.error)
        assertEquals("amb-1.3.rfm1", a.file)
        assertEquals("AMB-1", a.name)
        assertEquals("1.3", a.version)
        assertEquals("MPL-2.0", a.license)
        assertEquals(214_629L, a.fileSize)
        assertEquals("rfm1:${FakeRfm1.AMB_SHA}", a.key)
        assertEquals("Added AMB-1 1.3", lib.message.value)
        // the folder: the file by hash and the index, nothing else
        assertEquals(listOf("${FakeRfm1.AMB_SHA}.rfm1", "index.json"), lib.dir.list()!!.sorted())
        val index = MiniJson.parse(lib.indexFile.readText()).obj()!!
        assertEquals(1L, index.int("format"))
        val first = index["firmware"].arr()!![0].obj()!!
        assertEquals(FakeRfm1.AMB_SHA, first.str("sha256"))
        assertEquals("amb", first.str("id"))
        assertEquals("1.3", first.str("version"))
        assertEquals("MPL-2.0", first.str("license"))
        assertEquals(214_629L, first.int("file_size"))
        assertEquals("amb-1.3.rfm1", first.str("file"))
        assertNotNull(Instant.parse(first.str("added")))           // (ISO 8601 to the second, as iOS's)
        assertFalse(first.str("added")!!.contains('.'))
        // the catalog
        val cores = lib.cores()
        assertEquals(listOf(a.key), cores.map { it.id })
        assertEquals("wasm", cores[0].kind)
        assertEquals("amb", cores[0].firmwareID)
        assertEquals(lib.path(FakeRfm1.AMB_SHA).path, cores[0].path)
        assertEquals("AMB-1 1.3 (imported)", cores[0].title)
        // its details (the manifest's authors and source, the licence sections)
        val d = lib.details(FakeRfm1.AMB_SHA)
        assertEquals(listOf("Charles Vestal"), d.authors)
        assertTrue(d.sourceURL.startsWith("https://github.com/charlesvestal/fm1-amb@"))
        assertEquals(5, d.licenses.size)
        assertTrue(d.licenses[0].startsWith("MPL-2.0"))
        // a new library over the same folder reads the index without checking the file again
        val n = FakeRfm1.checks
        val again = FirmwareLibrary(lib.dir, FakeRfm1)
        assertEquals(listOf(a), again.sync())
        assertEquals(n, FakeRfm1.checks)
    }

    @Test fun importCopiesOnceAndKeepsNames() {
        val lib = library()
        val a = lib.import(FakeRfm1.amb)
        val again = lib.import(FakeRfm1.amb)
        assertEquals(a.key, again.key)
        assertEquals("amb-1.3.rfm1 is already here", lib.message.value)
        assertEquals(1, lib.files.value.size)
        // a refused file: its reason, nothing kept
        val bad = lib.import("junk".toByteArray().inputStream(), "amb-1.3.rfm1")
        assertEquals("amb-1.3.rfm1", bad.file)
        assertTrue(bad.error!!, bad.error!!.startsWith("not a WebAssembly module"))
        assertEquals("amb-1.3.rfm1: ${bad.error}", lib.message.value)
        assertEquals(1, lib.files.value.size)
        assertEquals(2, lib.dir.list()!!.size)
        // another valid file under the same name: "amb-1.3 2.rfm1"; a name without .rfm1 gets it
        val other = FakeRfm1.amb.readBytes() + byteArrayOf(0x00, 0x06, 0x01, 'x'.code.toByte()) + "test".toByteArray()
        assertEquals("amb-1.3 2.rfm1", lib.import(other.inputStream(), "amb-1.3.rfm1").file)
        val third = FakeRfm1.amb.readBytes() + byteArrayOf(0x00, 0x06, 0x01, 'y'.code.toByte()) + "test".toByteArray()
        assertEquals("download.rfm1", lib.import(third.inputStream(), "content/download").file)
        assertEquals(3, lib.cores().size)
    }

    @Test fun deleteAndSync() {
        val lib = library()
        val a = lib.import(FakeRfm1.amb)
        lib.delete(a)
        assertEquals("Removed AMB-1 1.3", lib.message.value)
        assertTrue(lib.files.value.isEmpty())
        assertEquals(listOf("index.json"), lib.dir.list()!!.toList())
        assertEquals(0, FirmwareIndex.decode(lib.indexFile.readText()).size)
        // an import cut off: its copy removed; one stored but not indexed: checked and added; a file gone: dropped
        lib.import(FakeRfm1.amb)
        File(lib.dir, ".import-1234.rfm1").writeText("half")
        lib.indexFile.writeText(FirmwareIndex.encode(emptyList()))
        val other = FakeRfm1.amb.readBytes() + byteArrayOf(0x00, 0x06, 0x01, 'x'.code.toByte()) + "test".toByteArray()
        val ghost = ImportedFirmware("f".repeat(64), "felucca", "Felucca", "1.6.1", "GPL-3.0-only", 1, file = "felucca.rfm1")
        lib.indexFile.writeText(FirmwareIndex.encode(listOf(ghost)))
        lib.path(FakeRfm1.sha256(other)).writeBytes(other)
        File(lib.dir, "${"0".repeat(64)}.rfm1").writeText("not wasm, misnamed")
        val l = lib.sync()
        assertEquals(setOf(FakeRfm1.AMB_SHA, FakeRfm1.sha256(other)), l.map { it.sha256 }.toSet())
        assertEquals(setOf("amb-1.3.rfm1", "amb-1.3 2.rfm1"), l.map { it.file }.toSet())
        assertFalse(File(lib.dir, ".import-1234.rfm1").exists())
        assertFalse(File(lib.dir, "${"0".repeat(64)}.rfm1").exists())
        assertEquals(l, FirmwareIndex.decode(lib.indexFile.readText()))
    }

    /** an index iOS wrote (its mirror's index.json) reads */
    @Test fun readsAnIOSIndex() {
        val ios = """{
  "firmware" : [
    {
      "added" : "2026-10-29T08:12:44Z",
      "file" : "amb-1.3.rfm1",
      "file_size" : 214629,
      "id" : "amb",
      "license" : "MPL-2.0",
      "name" : "AMB-1",
      "sha256" : "${FakeRfm1.AMB_SHA}",
      "version" : "1.3"
    }
  ],
  "format" : 1
}"""
        val l = FirmwareIndex.decode(ios)
        assertEquals(1, l.size)
        assertEquals(Instant.parse("2026-10-29T08:12:44Z"), l[0].added)
        assertEquals("amb", l[0].coreID)
        assertEquals(FirmwareIndex.decode(FirmwareIndex.encode(l)), l)
    }

    // ---- the catalog and saved choices

    private fun c(key: String, id: String, v: String, wasm: Boolean) = CoreInfo(
        key, coreID = id, name = id.replaceFirstChar { it.uppercase() }, version = v,
        kind = if (wasm) "wasm" else "native", sha256 = if (wasm) key.removePrefix("rfm1:") else "")

    private val cat = listOf(c("choralroot", "choralroot", "0.26.1", false), c("felucca", "felucca", "1.6.1", false),
        c("rfm1:aaa", "felucca", "1.6.2", true), c("rfm1:bbb", "felucca", "1.6.10", true), c("rfm1:ccc", "amb", "1.3", true))

    @Test fun coreRefResolves() {
        // by SHA-256
        assertEquals(CoreRef.Resolved("rfm1:aaa", "", true), CoreRef("wasm", "felucca", "1.6.2", "aaa").resolve(cat))
        // the same id and kind: the same version if there is one, else the newest, with a note
        assertEquals(CoreRef.Resolved("rfm1:bbb", "Felucca 1.6.10 instead of 1.6.1", true), CoreRef("wasm", "felucca", "1.6.1", "old").resolve(cat))
        assertEquals("rfm1:aaa", CoreRef("wasm", "felucca", "1.6.2", "zzz").resolve(cat)?.key)
        assertEquals(CoreRef.Resolved("rfm1:ccc", "Amb 1.3 instead of 1.2", true), CoreRef("wasm", "amb", "1.2", "zzz").resolve(cat))
        // an imported one that's gone: the built-in core of that id
        val noWasm = cat.filter { !it.imported }
        assertEquals(CoreRef.Resolved("felucca", "Felucca 1.6.2 (imported) is not here: the built-in Felucca 1.6.1 instead", true),
            CoreRef("wasm", "felucca", "1.6.2", "aaa").resolve(noWasm))
        // a built-in one this build lacks (Melodee): an imported one of that id
        assertEquals(CoreRef.Resolved("rfm1:ccc", "amb is not in this build: Amb 1.3 (imported) instead", true),
            CoreRef(id = "amb", version = "1.3").resolve(cat))
        // nothing of that id: the first core, the flash not for it
        assertEquals(CoreRef.Resolved("choralroot", "nosuch 1 (imported) is not in this build: Choralroot instead", false),
            CoreRef("wasm", "nosuch", "1", "q").resolve(cat))
        assertEquals("nosuch is not in this build: Choralroot instead", CoreRef(id = "nosuch").resolve(cat)?.note)
        assertNull(CoreRef(id = "x").resolve(emptyList()))
        // the saved form
        val r = CoreRef("wasm", "felucca", "1.6.2", "aaa")
        assertEquals(r, CoreRef.fromJson(r.toJson()))
        assertEquals(CoreRef("native", "felucca"), CoreRef.fromJson("""{"id":"felucca"}"""))
        assertNull(CoreRef.fromJson(null))
        assertNull(CoreRef.fromJson("{}"))
        assertEquals(CoreRef(cat[2]), r)
    }

    @Test fun pickAndReplacement() {
        assertEquals("choralroot", CorePicker.pick(null, cat))
        assertEquals("rfm1:ccc", CorePicker.pick("rfm1:ccc", cat))
        assertEquals("choralroot", CorePicker.pick("gone", cat))
        assertEquals("rfm1:x", CorePicker.pick(null, listOf(c("rfm1:x", "amb", "1", true))))
        // the selection deleted: the same firmware's newest other file, else its built-in core, else the first
        assertEquals("rfm1:bbb", CorePicker.replacement("rfm1:aaa", "rfm1:aaa", cat))
        assertNull(CorePicker.replacement("rfm1:aaa", "choralroot", cat))
        assertEquals("felucca", CorePicker.replacement("rfm1:bbb", "rfm1:bbb", cat.filter { it.id != "rfm1:aaa" }))
        assertEquals("choralroot", CorePicker.replacement("rfm1:ccc", "rfm1:ccc", cat))
        // the built-in ones in ORDER, then the imported ones
        val l = CorePicker.catalog(listOf(c("loom", "loom", "1", false), c("felucca", "felucca", "1", false),
            c("zzz", "zzz", "1", false)), listOf(cat[4]))
        assertEquals(listOf("felucca", "loom", "zzz", "rfm1:ccc"), l.map { it.id })
        assertEquals("rfm1-felucca.bin", CorePicker.flashFileName(cat[2]))
        assertEquals("felucca.bin", CorePicker.flashFileName(cat[1]))
    }

    /** Melodee, built in until 2026-10-31, now an .rfm1: its memory (melodee.bin) becomes rfm1-melodee.bin, once */
    @Test fun builtInFlashAdopted() {
        val flash = File(root, "cores").also { it.mkdirs() }
        val mel = CoreInfo("rfm1:m", coreID = "melodee", name = "Melodee", version = "1.5.1", kind = "wasm", sha256 = "m")
        val mem = ByteArray(4096) { (it * 31).toByte() }
        FlashFile.write(mem, File(flash, "melodee.bin"), firmwareID = "melodee", version = "1.5.0")
        var copy: List<String>? = null
        assertTrue(FirmwareCenter.adoptBuiltInFlash(mel, flash, emptyList(), { d, id, v, from, to ->
            assertEquals(mem.size, d.size)
            copy = listOf(id, v, from, to)
        }))
        assertEquals(listOf("melodee", "1.5.0", "built in 1.5.0", "1.5.1"), copy)
        val got = FlashFile.read(File(flash, "rfm1-melodee.bin"), 4096)
        assertTrue(got.data!!.contentEquals(mem))
        assertEquals("melodee", FlashFile.meta(File(flash, "rfm1-melodee.bin"))?.firmwareID)
        assertTrue(File(flash, "melodee.bin").exists())                 // (kept)
        // once: the .rfm1's own memory is never overwritten
        assertFalse(FirmwareCenter.adoptBuiltInFlash(mel, flash, emptyList()))
        // a build that still has the built-in core: each keeps its own
        File(flash, "rfm1-melodee.bin").delete()
        assertFalse(FirmwareCenter.adoptBuiltInFlash(mel, flash, listOf(CoreInfo("melodee", version = "1.5.0"))))
        // a built-in core: nothing to adopt
        assertFalse(FirmwareCenter.adoptBuiltInFlash(CoreInfo("felucca"), flash, emptyList()))
    }
}
