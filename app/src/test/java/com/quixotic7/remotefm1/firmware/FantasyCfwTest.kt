// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A Fantasy CFW's rules (FantasyCfw.kt) against RemoteFM1 60a1b8b web/engines/rfm1/fs-test.mjs's "inspect" checks
// (virtual, needs, rfm1_host_1 imports, folders, memory and file limits) on small modules made here; the import
// refusing an unknown need; the memory budget; catalog.json's `virtual`.
package com.quixotic7.remotefm1.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class FantasyCfwTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun leb(v0: Long): ByteArray {
        var v = v0
        val o = ByteArrayOutputStream()
        do { var c = (v and 0x7f).toInt(); v = v ushr 7; if (v != 0L) c = c or 0x80; o.write(c) } while (v != 0L)
        return o.toByteArray()
    }
    private fun name(s: String) = leb(s.toByteArray().size.toLong()) + s.toByteArray()
    private fun section(id: Int, body: ByteArray) = byteArrayOf(id.toByte()) + leb(body.size.toLong()) + body
    private fun custom(n: String, payload: ByteArray) = section(0, name(n) + payload)

    private val man = """"rfm1": 1, "abi": 6, "id": "fscart", "name": "FS Cart", "version": "1.0", "license": "MIT", "source_url": "https://example.com/fscart@0""""
    private val fantasy = """{$man, "virtual": true, "needs": ["fs1"], "folders": {"audio": "r", "code": "r", "data": "rw"}}"""
    private val plain = "{$man}"

    /** a module: types, the imports (rfm1_host_1 fields), one fixed memory of [pages], the manifest, [pad] bytes more */
    private fun module(manifest: String?, host: List<String> = listOf("fs_request", "fs_poll", "fs_changed"), pages: Long = 16,
                       pad: Int = 0, hostModule: String = "rfm1_host_1"): File {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0))
        // types: 0 (i32 i32 i32 i64 i32 i32)->i32, 1 (i32)->i32, 2 ()->i32
        o.write(section(1, byteArrayOf(3, 0x60, 6, 0x7f, 0x7f, 0x7f, 0x7e, 0x7f, 0x7f, 1, 0x7f, 0x60, 1, 0x7f, 1, 0x7f, 0x60, 0, 1, 0x7f)))
        val imp = ByteArrayOutputStream()
        val list = host.map { hostModule to it } + listOf("wasi_snapshot_preview1" to "proc_exit")
        imp.write(leb(list.size.toLong()))
        for ((m, f) in list) { imp.write(name(m)); imp.write(name(f)); imp.write(0); imp.write(when (f) { "fs_request" -> 0; "fs_changed" -> 2; else -> 1 }) }
        o.write(section(2, imp.toByteArray()))
        o.write(section(5, byteArrayOf(1, 1) + leb(pages) + leb(pages)))
        if (manifest != null) o.write(custom("rfm1", manifest.toByteArray()))
        if (pad > 0) o.write(custom("pad", ByteArray(pad)))
        return tmp.newFile().apply { writeBytes(o.toByteArray()) }
    }

    @Test fun whatAFantasyCfwMayBe() {
        val p = FantasyCfw.scan(module(plain, host = emptyList()))!!
        assertNull(FantasyCfw.refusal(p))
        assertFalse(FantasyCfw.info(p).virtual)
        val f = FantasyCfw.scan(module(fantasy))!!
        assertEquals(listOf("fs_request", "fs_poll", "fs_changed"), f.hostImports)
        assertEquals(16L * 65536, f.memoryBytes)
        assertNull(FantasyCfw.refusal(f))
        val i = FantasyCfw.info(f)
        assertTrue(i.virtual && i.usesFolder && i.needs == listOf("fs1") && i.folders["data"] == "rw")
        assertEquals("reads audio, code, data · writes data", i.access)

        fun refused(m: String?, re: String, host: List<String> = listOf("fs_request", "fs_poll", "fs_changed"), pages: Long = 16) {
            val why = FantasyCfw.refusal(FantasyCfw.scan(module(m, host, pages)))
            assertNotNull("refused: $re", why)
            assertTrue("$why ~ $re", Regex(re).containsMatchIn(why!!))
        }
        refused(plain, "imports rfm1_host_1 .*\"virtual\": true with \"fs1\"")
        refused("""{$man, "virtual": true, "needs": []}""", "imports rfm1_host_1")
        refused("""{$man, "virtual": true, "needs": ["fs1", "midi9"]}""", "needs a newer RemoteFM1 \\(it needs \"midi9\"\\)")
        refused("""{$man, "needs": ["fs1"]}""", "not \"virtual\"", host = emptyList())
        refused("""{$man, "virtual": true, "needs": ["fs1"], "folders": {"data": "w"}}""", "folders.*\"w\"")
        refused("""{$man, "virtual": "yes"}""", "\"virtual\" is not true or false", host = emptyList())
        refused("""{$man, "virtual": true, "needs": "fs1"}""", "\"needs\" is not a list", host = emptyList())
        refused("""{$man, "virtual": true, "needs": ["fs1"], "folders": ["data"]}""", "\"folders\" is not an object")
        refused(fantasy, "513 MiB: at most 512 MiB", pages = 8208)
        assertNull(FantasyCfw.refusal(FantasyCfw.scan(module(fantasy, pages = 8192))))
        // a folder this app doesn't know: never granted, not refused
        assertNull(FantasyCfw.refusal(FantasyCfw.scan(module("""{$man, "virtual": true, "needs": ["fs1"], "folders": {"net": "x", "audio": "r"}}"""))))
        assertEquals(mapOf("audio" to "r"), FantasyCfw.info(module("""{$man, "virtual": true, "needs": ["fs1"], "folders": {"net": "x", "audio": "r"}}""")).folders)
        // 40 MiB, not virtual: left to the native checks (refused there as today); virtual with no files: fine
        assertNull(FantasyCfw.refusal(FantasyCfw.scan(module(plain, host = emptyList(), pages = 640))))
        assertNull(FantasyCfw.refusal(FantasyCfw.scan(module("""{$man, "virtual": true}""", host = emptyList(), pages = 640))))
        // another host module: not ours (the native checks refuse it as today)
        assertTrue(FantasyCfw.scan(module(fantasy, hostModule = "rfm1_host_2"))!!.hostImports.isEmpty())
        // not a module, no manifest: the native checks say why
        assertNull(FantasyCfw.scan(tmp.newFile().apply { writeText("hello world") }))
        assertNull(FantasyCfw.refusal(FantasyCfw.scan(module(null))))
    }

    @Test fun aFantasyCfwsFileIsUnder16MiB() {
        val big = module(fantasy, pad = 16 shl 20)
        assertTrue(Regex("16 MiB").containsMatchIn(FantasyCfw.refusal(big) ?: ""))
        // the same size, not virtual: not refused here (64 MiB as today)
        assertNull(FantasyCfw.refusal(module(plain, host = emptyList(), pad = 16 shl 20)))
    }

    @Test fun ripplesManifest() {
        val f = File("/Volumes/Q7Media-2025/Projects/Github/ChoralRootFM1/FM1VST/.claude/worktrees/virtual-carts/build-rfm1/rfm1/ripple-0.4.rfm1")
        assumeTrue("ripple-0.4.rfm1 not on this machine", f.isFile)
        val s = FantasyCfw.scan(f)!!
        assertNull(FantasyCfw.refusal(s))
        val i = FantasyCfw.info(s)
        assertTrue(i.virtual && i.usesFolder)
        assertEquals(mapOf("code" to "r", "data" to "rw"), i.folders)
        assertEquals(16L shl 20, i.memoryBytes)
        assertEquals(setOf("fs_request", "fs_poll", "fs_changed"), s.hostImports.toSet())     // (ripple has no fs_cancel)
        assertEquals("reads code, data · writes data", i.access)
    }

    @Test fun anUnknownNeedIsRefusedOnImport() {
        val lib = FirmwareLibrary(tmp.newFolder("Firmware"), FakeRfm1)
        lib.sync()
        val f = lib.import(module("""{$man, "virtual": true, "needs": ["fs1", "midi9"]}"""))
        assertEquals("it needs a newer RemoteFM1 (it needs \"midi9\")", f.error)
        assertTrue(lib.files.value.isEmpty())
        assertTrue(lib.message.value.contains("needs a newer RemoteFM1"))
        // a Fantasy CFW the fake loader takes: kept, its fields in its details
        val ok = lib.import(module(fantasy))
        assertNull(ok.error)
        assertTrue(lib.details(ok.sha256).fantasy.virtual)
        assertFalse(lib.details(lib.import(FakeRfm1.amb).sha256).fantasy.virtual)
    }

    @Test fun theMemoryBudget() {
        val mb = 1L shl 20
        // free above the low-memory line less the reserve, at most max(large heap class, RAM / 4)
        assertEquals(2048 * mb - 256 * mb - 128 * mb, MemoryBudget.budget(2048 * mb, 256 * mb, 16384 * mb, 512))
        assertEquals(512 * mb, MemoryBudget.budget(8000 * mb, 256 * mb, 2048 * mb, 512))
        assertEquals(1024 * mb, MemoryBudget.budget(8000 * mb, 256 * mb, 4096 * mb, 256))
        assertEquals(0, MemoryBudget.budget(100 * mb, 256 * mb, 4096 * mb, 256))
        assertNull(MemoryBudget.refusal(512 * mb, 512 * mb))
        assertEquals("needs 512 MB of memory", MemoryBudget.refusal(512 * mb, 300 * mb))
        assertEquals("needs 33 MB of memory", MemoryBudget.refusal(32 * mb + 1, 0))
        assertNull(MemoryBudget.refusal(32 * mb, 0))                    // (every .rfm1 before Fantasy CFWs: as before)
    }

    @Test fun theCatalogsFlag() {
        val site = """{"firmwares": [
            {"id": "ripple", "name": "Ripple", "version": "0.4", "virtual": true, "rfm1": {"file": "fw/ripple-0.4.rfm1", "sha256": "AB", "size": 10}},
            {"id": "pond", "name": "Pond", "version": "1.0", "rfm1": {"file": "fw/pond-1.0.rfm1", "virtual": true}},
            {"id": "amb", "name": "AMB-1", "version": "1.3", "rfm1": {"file": "fw/amb-1.3.rfm1"}}]}"""
        val feed = FirmwareFeed(FirmwareFeed.Kind.SITE, "https://remotefm1.com/catalog.json", "remotefm1.com")
        val snap = FeedSnapshot.parse(site.toByteArray(), feed)
        val by = snap.firmware.associateBy { it.id }
        assertTrue(by.getValue("ripple").virtual)
        assertTrue(by.getValue("pond").virtual)
        assertFalse(by.getValue("amb").virtual)
        // kept in the cache, carried by the merge into another feed's entry of the same version
        assertTrue(FeedSnapshot.fromMap(snap.toMap())!!.firmware.first { it.id == "ripple" }.virtual)
        val gh = FeedSnapshot(FirmwareFeed(FirmwareFeed.Kind.GITHUB, "https://api.github.com/x", "GitHub"), snap.fetched,
            listOf(AvailableFirmware("ripple", version = "0.4", downloads = listOf(AvailableFirmware.Download("https://x/ripple-0.4.rfm1", "GitHub", "", 0, "ripple-0.4.rfm1")))))
        assertTrue(FeedSnapshot.merge(listOf(gh, FeedSnapshot(feed, snap.fetched, emptyList(), snap.details))).first { it.id == "ripple" }.virtual)
        // a wrong type leaves the item out, as for the other fields
        assertTrue(FeedSnapshot.parse("""{"firmwares": [{"id": "x", "virtual": "yes", "rfm1": {"file": "x-1.rfm1"}}]}""".toByteArray(), feed).firmware.isEmpty())
    }
}
