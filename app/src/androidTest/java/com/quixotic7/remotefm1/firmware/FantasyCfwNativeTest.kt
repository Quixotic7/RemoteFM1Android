// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS 7a7cc49 RFMKit/Tests/RFMKitTests/FantasyCFWTests.swift (itself the web player's
// fs-test.mjs): Fantasy CFW (FM1VST docs/VIRTUAL-CARTS.md) in the .rfm1 loader: what it lets in (virtual, needs, the
// rfm1_host_1 imports and their signatures, the memory and file limits, the memory check), and the file API (section 4)
// against a temporary RemoteFM1 folder (a POSIX one, RFMFileApi's fsPosixFolder): every op, its errors, the sandbox,
// the limits, cancel, and that an answer reaches the module only between two export calls. The module: the test APK's
// assets/fantasy/fs_cart.wasm (fs_cart.c). Then fm1-ripple, the first Fantasy CFW (assets/fantasy/ripple-0.4.rfm1 and
// its test folder, from FM1VST's virtual-carts worktree): its droplets read, one started, sound; the erroring droplet
// doesn't trap.
// Android's own: the file API through the app's folder (rfm_folder_jni.cpp -> RemoteFolderHost -> FileRemoteFolder),
// and ripple imported, found again by a library's sync (a restart) and loaded and played from it over that folder.
package com.quixotic7.remotefm1.firmware

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.RfmEngine
import com.quixotic7.remotefm1.folder.FileRemoteFolder
import com.quixotic7.remotefm1.folder.RemoteFolder
import com.quixotic7.remotefm1.folder.RemoteFolderHost
import com.quixotic7.remotefm1.folder.RemoteFolderNative
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

private object Op { const val STAT = 1; const val LIST = 2; const val READ = 3; const val WRITE = 4; const val DELETE = 5; const val MKDIR = 6 }
private object E { const val PENDING = -1; const val ENOENT = -2; const val EACCES = -3; const val EIO = -4; const val EBUSY = -6; const val ECANCELED = -7; const val ENOFOLDER = -8 }

/** module surgery (fs-test.mjs's, iOS Surgery) */
private object Surgery {
    fun leb(v0: Int) = WasmBytes.leb(v0)
    fun custom(name: String, payload: ByteArray): ByteArray {
        val nb = name.toByteArray()
        val body = leb(nb.size) + nb + payload
        return byteArrayOf(0) + leb(body.size) + body
    }
    fun json(m: Map<String, Any>): String = JSONObject(m).toString()
    fun withManifest(b: ByteArray, m: Map<String, Any>) = b + custom("rfm1", json(m).toByteArray())
    /** the sections: (id, start, end) */
    fun sections(b: ByteArray): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        var at = 8
        while (at < b.size) {
            val start = at
            val id = b[at].toInt() and 0xFF
            at++
            var r = 0
            var sh = 0
            while (true) {
                val x = b[at++].toInt() and 0xFF
                r += (x and 0x7F) shl sh
                sh += 7
                if (x and 0x80 == 0) break
            }
            at += r
            out.add(Triple(id, start, at))
        }
        return out
    }
    /** the memory: initial = maximum = pages */
    fun withMemory(b: ByteArray, pages: Int): ByteArray {
        val mem = sections(b).first { it.first == 5 }
        val nb = byteArrayOf(1, 1) + leb(pages) + leb(pages)
        return b.copyOfRange(0, mem.second) + byteArrayOf(5) + leb(nb.size) + nb + b.copyOfRange(mem.third, b.size)
    }
    fun rename(b: ByteArray, from: String, to: String): ByteArray {
        val f = from.toByteArray()
        val t = to.toByteArray()
        val c = b.copyOf()
        for (i in 0..(b.size - f.size)) {
            if ((f.indices).all { b[i + it] == f[it] }) {
                t.copyInto(c, i)
                break
            }
        }
        return c
    }
}

@RunWith(AndroidJUnit4::class)
class FantasyCfwNativeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var root: File
    private var n = 0
    private var hostBefore: RemoteFolder? = null
    private val r = JSONObject()
    private var name = ""

    private val man: Map<String, Any> = mapOf("rfm1" to 1, "abi" to 6, "id" to "fscart", "name" to "FS Cart", "version" to "1.0",
        "license" to "MIT", "source_url" to "https://example.com/fscart@0")
    private val fantasy: Map<String, Any> = man + mapOf("virtual" to true, "needs" to JSONArray(listOf("fs1")),
        "folders" to JSONObject(mapOf("audio" to "r", "code" to "r", "data" to "rw")))
    private fun with(base: Map<String, Any>, changes: Map<String, Any>) = base + changes

    private fun asset(p: String): ByteArray = assets.open(p).use { it.readBytes() }
    private val cart by lazy { asset("fantasy/fs_cart.wasm") }
    private val plain by lazy { asset("fantasy/fs_cart_plain.wasm") }

    @Before fun setUp() {
        root = File(ctx.cacheDir, "fantasy-${System.nanoTime()}").also { it.mkdirs() }
        hostBefore = RemoteFolderHost.folder
        Rfm1TestNative.setFolder(null)
        Rfm1Native.setMemoryBudget(0)
    }

    @After fun tearDown() {
        Rfm1TestNative.setFolder(null)
        Rfm1Native.setMemoryBudget(0)
        RemoteFolderHost.use(hostBefore)
        RemoteFolderNative.sync()
        root.deleteRecursively()
        FirmwareTest.save(name, r)
    }

    private fun file(b: ByteArray): String {
        n++
        return File(root, "m$n.rfm1").also { it.writeBytes(b) }.path
    }
    /** null: accepted; else the reason */
    private fun refused(b: ByteArray): String? = Rfm1Native.check(file(b)).exceptionOrNull()?.message
    private fun info(b: ByteArray): Rfm1Info? = Rfm1Native.check(file(b)).getOrNull()

    // ---- the loader

    @Test fun whatAFantasyCfwMayBe() {
        name = "fantasy_loader"
        val p = info(Surgery.withManifest(plain, man))!!
        assertFalse(p.isVirtual)
        assertFalse(p.usesFolder)
        val f = info(Surgery.withManifest(cart, fantasy))
        assertNotNull(refused(Surgery.withManifest(cart, fantasy)) ?: "", f)
        assertTrue(f!!.isVirtual)
        assertTrue(f.usesFolder)
        assertEquals("audio:r code:r data:rw", f.folders)

        fun expect(b: ByteArray, pattern: String, what: String) {
            val m = refused(b)
            assertNotNull(what, m)
            assertTrue("$what: $m", Regex(pattern).containsMatchIn(m!!))
        }
        expect(Surgery.withManifest(cart, man), """imports rfm1_host_1 .*"virtual": true with "fs1"""", "rfm1_host_1 in a plain manifest")
        expect(Surgery.withManifest(cart, with(fantasy, mapOf("needs" to JSONArray()))), "imports rfm1_host_1", "virtual without fs1")
        expect(Surgery.withManifest(cart, with(fantasy, mapOf("needs" to JSONArray(listOf("fs1", "midi9"))))),
            """needs a newer RemoteFM1 \(it needs "midi9"\)""", "an unknown need")
        expect(Surgery.withManifest(plain, with(man, mapOf("needs" to JSONArray(listOf("fs1"))))), """not "virtual"""", "needs without virtual")
        expect(Surgery.withManifest(Surgery.rename(cart, "fs_changed", "fs_chang3d"), fantasy),
            """rfm1_host_1\.fs_chang3d, which this app doesn't know""", "an unknown host function")
        expect(Surgery.withManifest(Surgery.rename(cart, "rfm1_host_1", "rfm1_host_2"), fantasy),
            """imports rfm1_host_2\.\w+, which the \.rfm1 format doesn't allow""", "another host module")
        expect(Surgery.withManifest(cart, with(fantasy, mapOf("folders" to JSONObject(mapOf("data" to "w"))))), """folders.*"w"""", "a folder mode w")
        expect(Surgery.withManifest(Surgery.withMemory(plain, 640), man), "40 MiB: at most 32 MiB", "40 MiB, not virtual")
        val big = info(Surgery.withManifest(Surgery.withMemory(plain, 640), with(man, mapOf("virtual" to true))))
        assertNotNull("40 MiB, virtual (no files): loads", big)
        assertEquals(40 shl 20, big!!.memoryBytes)
        assertTrue(big.isVirtual)
        expect(Surgery.withManifest(Surgery.withMemory(cart, 8208), fantasy), "513 MiB: at most 512 MiB", "513 MiB")
        // (a 512 MiB instance: lazily allocated, so it costs no RAM until touched)
        val huge = info(Surgery.withManifest(Surgery.withMemory(cart, 8192), fantasy))
        assertNotNull("512 MiB accepted", huge)
        assertEquals(512 shl 20, huge!!.memoryBytes)
        val pad = Surgery.custom("pad", ByteArray(16 shl 20))
        expect(Surgery.withManifest(cart, fantasy) + pad, "16 MiB", "a virtual file of 16 MiB")
        assertNull("the same size, not virtual: accepted (64 MiB as today)", refused(Surgery.withManifest(plain, man) + pad))

        // a wrong signature: one import fs_request (i32) -> i32
        val m = "rfm1_host_1".toByteArray()
        val fld = "fs_request".toByteArray()
        val imp = byteArrayOf(1) + Surgery.leb(m.size) + m + Surgery.leb(fld.size) + fld + byteArrayOf(0, 0)
        val wrong = WasmBytes.header + byteArrayOf(1, 6, 1, 0x60, 1, 0x7F, 1, 0x7F, 2) + Surgery.leb(imp.size) + imp +
            Surgery.custom("rfm1", Surgery.json(fantasy).toByteArray())
        expect(wrong, """fs_request has the signature \(i32\) -> \(i32\), not \(i32, i32, i32, i64, i32, i32\) -> \(i32\)""", "a wrong signature")
        r.put("pass", true)
    }

    @Test fun memoryCheck() {
        name = "fantasy_memory"
        val b = Surgery.withManifest(Surgery.withMemory(cart, 4096), fantasy)      // 256 MiB
        Rfm1Native.setMemoryBudget(200L shl 20)
        val m = refused(b)
        assertTrue(m ?: "accepted", m?.contains("needs 256 MB of memory; this device can give it 200 MB now") == true)
        Rfm1Native.setMemoryBudget(2L shl 30)
        assertNull(refused(b))
        // the app's budget: up to 32 MiB never refused, as MemoryBudget.refusal
        Rfm1Native.setMemoryBudget(MemoryBudget.nativeBudget(0))
        assertNull(refused(Surgery.withManifest(Surgery.withMemory(cart, 512), fantasy)))
        assertNotNull(refused(Surgery.withManifest(Surgery.withMemory(cart, 528), fantasy)))
        r.put("pass", true)
    }

    // ---- the file API

    private val kick = ByteArray(3 * 1048576 + 17) { (it * 7 + (it shr 9)).toByte() }

    private fun put(p: String, b: ByteArray) {
        val f = File(root, "RemoteFM1/$p")
        f.parentFile!!.mkdirs()
        f.writeBytes(b)
    }

    private fun folder() {
        put("audio/kick.wav", kick)
        put("audio/sub/x.wav", "x".toByteArray())
        put("audio/.hidden", "h".toByteArray())
        put("audio/B.wav", "b".toByteArray()); put("audio/a.wav", "a".toByteArray()); put("audio/Z", "z".toByteArray())
        put("audio/é.wav", "e".toByteArray())
        put("code/fscart/droplets/a.lua", "print('a')".toByteArray())
        put("code/other/secret.lua", "secret".toByteArray())
        File(root, "outside.txt").writeText("outside")
        android.system.Os.symlink(File(root, "outside.txt").path, File(root, "RemoteFM1/code/fscart/escape").path)
    }

    /** one instance of the test module over a folder (null: the app's) */
    private class Cart(path: String, folder: String?) : AutoCloseable {
        val i = Rfm1TestNative.Inst(path, folder)
        val buf: Int = i.call("t_buf").toInt()
        val P get() = buf + 1536 * 1024

        init {
            call("t_demo", 0)                           // (the module's own demo off: these calls alone)
        }

        override fun close() = i.close()
        fun call(name: String, vararg args: Long): Int {
            val r = i.call(name, *args)
            return if (r == Long.MIN_VALUE) Int.MIN_VALUE else r.toInt()
        }
        val mem: ByteBuffer get() = i.mem
        fun request(op: Int, path: ByteArray, off: Long = 0, len: Int = 0, at: Int? = null): Int {
            val m = mem
            for ((k, b) in path.withIndex()) m.put(P + k, b)
            return call("t_request", op.toLong(), P.toLong(), path.size.toLong(), off, (at ?: buf).toLong(), len.toLong() and 0xFFFFFFFFL)
        }
        fun request(op: Int, path: String, off: Long = 0, len: Int = 0, at: Int? = null) = request(op, path.toByteArray(), off, len, at)
        fun settle() = i.settle(10_000)
        /** a request, settled, polled: (id, result) */
        fun run(op: Int, path: String, off: Long = 0, len: Int = 0): Pair<Int, Int> {
            val id = request(op, path, off, len)
            if (id <= 0) return Pair(id, id)
            settle()
            return Pair(id, call("t_poll", id.toLong()))
        }
        fun bytes(n: Int, at: Int? = null): ByteArray {
            val m = mem
            val a = at ?: buf
            return ByteArray(n) { m.get(a + it) }
        }
        fun u64(at: Int): Long = (0 until 8).fold(0L) { s, k -> s or ((mem.get(at + k).toLong() and 0xFF) shl (8 * k)) }
        fun u32(at: Int): Long = (0 until 4).fold(0L) { s, k -> s or ((mem.get(at + k).toLong() and 0xFF) shl (8 * k)) }
        fun list(p: String, len: Int = 4096, off: Long = 0): Pair<Int, List<String>> {
            val (_, r) = run(Op.LIST, p, off, len)
            val out = ArrayList<String>()
            var at = buf
            val m = mem
            for (k in 0 until maxOf(r, 0)) {
                val d = m.get(at).toInt()
                val nl = m.get(at + 1).toInt() and 0xFF
                out.add((if (d != 0) "/" else "") + String(ByteArray(nl) { m.get(at + 2 + it) }, Charsets.UTF_8))
                at += 2 + nl
            }
            return Pair(r, out)
        }
        fun write(p: String, s: String, off: Long): Int {
            val b = s.toByteArray()
            val m = mem
            for ((k, x) in b.withIndex()) m.put(buf + k, x)
            return run(Op.WRITE, p, off, b.size).second
        }
    }

    private fun cartFile(manifest: Map<String, Any>) = file(Surgery.withManifest(cart, manifest))

    @Test fun fileApi() {
        name = "fantasy_file_api"
        folder()
        Cart(cartFile(fantasy), File(root, "RemoteFM1").path).use { c -> fileApiChecks(c, File(root, "RemoteFM1"), posix = true) }
        r.put("pass", true)
    }

    /** iOS testFileAPI's checks; [dir]: the folder on disk; [posix]: fsPosixFolder (else the app's, through JNI) */
    private fun fileApiChecks(c: Cart, dir: File, posix: Boolean) {
        // STAT
        var x = c.run(Op.STAT, "/audio/kick.wav", 0, 16)
        assertEquals(16, x.second)
        assertEquals(kick.size.toLong(), c.u64(c.buf))
        assertEquals(0L, c.u32(c.buf + 12))
        assertTrue("mtime now", Math.abs(c.u32(c.buf + 8) - System.currentTimeMillis() / 1000) < 600)
        x = c.run(Op.STAT, "/audio", 0, 16)
        assertEquals(16, x.second); assertEquals("STAT /audio: a folder", 1L, c.u32(c.buf + 12))
        assertEquals("STAT into 15 bytes", E.EIO, c.run(Op.STAT, "/audio/kick.wav", 0, 15).second)
        x = c.run(Op.STAT, "/audio/none.wav", 0, 16)
        assertTrue(x.first > 0); assertEquals("STAT of a missing file: an id, then ENOENT", E.ENOENT, x.second)

        // completion only between export calls
        val id = c.request(Op.READ, "/audio/kick.wav", 1000, 4096)
        assertTrue(c.i.waitIdle(10_000))                       // (the answer is back: not yet in memory)
        val spun = c.call("t_spin", id.toLong(), 100_000)
        for (k in 0 until 4096) c.mem.put(c.buf + k, 0xEE.toByte())
        c.i.apply()                                             // (what each tick does first)
        val after = c.call("t_poll", id.toLong())
        assertEquals("the answer stays out of the module during a call", E.PENDING, spun)
        assertEquals(4096, after)
        assertTrue("READ 4096 at 1000: the bytes", c.bytes(4096).contentEquals(kick.copyOfRange(1000, 5096)))

        // READ
        x = c.run(Op.READ, "/audio/kick.wav", 0, 2 * 1048576)
        assertEquals("READ of 2 MiB: 1 MiB (the limit)", 1048576, x.second)
        assertTrue(c.bytes(1048576).contentEquals(kick.copyOfRange(0, 1048576)))
        assertEquals("READ past the end", 10, c.run(Op.READ, "/audio/kick.wav", (kick.size - 10).toLong(), 100).second)
        assertEquals("READ at the end", 0, c.run(Op.READ, "/audio/kick.wav", kick.size.toLong(), 100).second)
        assertEquals("READ at 2^40", 0, c.run(Op.READ, "/audio/kick.wav", 1L shl 40, 100).second)
        assertEquals(E.EIO, c.request(Op.READ, "/audio/kick.wav", -5, 100))
        assertEquals(E.EIO, c.request(Op.READ, "/audio/kick.wav", 1L shl 60, 100))
        assertEquals("past the memory's end", E.EIO, c.request(Op.READ, "/audio/kick.wav", 0, 64, at = c.mem.capacity() - 10))
        x = c.run(Op.READ, "/code/droplets/a.lua", 0, 100)
        assertEquals(10, x.second); assertEquals("its own code/fscart/", "print('a')", String(c.bytes(10)))

        // LIST
        var l = c.list("/audio")
        assertEquals("sorted by bytes, folders marked, no hidden", "B.wav Z a.wav kick.wav /sub é.wav", l.second.joinToString(" "))
        val pages = ArrayList<String>()
        var off = 0L
        while (true) {
            val p = c.list("/audio", 12, off)
            if (p.first <= 0) { pages.add(p.first.toString()); break }
            pages.add(p.second.joinToString(",")); off += p.first
        }
        assertEquals("12-byte pages, never split", "B.wav,Z | a.wav | kick.wav | /sub | é.wav | 0", pages.joinToString(" | "))
        assertEquals("LIST into 4 bytes", E.EIO, c.list("/audio", 4).first)
        assertEquals(if (posix) "/droplets escape" else "/droplets", c.list("/code").second.joinToString(" "))
        l = c.list("/data")
        assertEquals(0, l.first)
        assertTrue("data/fscart/ made on demand", File(dir, "data/fscart").isDirectory)

        // WRITE, MKDIR, DELETE
        fun disk(p: String) = runCatching { File(dir, "data/fscart/$p").readText() }.getOrDefault("")
        assertEquals(5, c.write("/data/save.txt", "hello", -1)); assertEquals("hello", disk("save.txt"))
        assertEquals(6, c.write("/data/save.txt", " world", 5)); assertEquals("hello world", disk("save.txt"))
        assertEquals(2, c.write("/data/save.txt", "HE", 0)); assertEquals("HEllo world", disk("save.txt"))
        assertEquals(1, c.write("/data/save.txt", "x", -1)); assertEquals("-1 truncates", "x", disk("save.txt"))
        assertEquals(0, c.run(Op.MKDIR, "/data/sub").second); assertEquals("MKDIR twice", 0, c.run(Op.MKDIR, "/data/sub").second)
        assertEquals(1, c.write("/data/sub/f.bin", "f", -1))
        assertEquals("into a folder that isn't there", E.ENOENT, c.write("/data/nofolder/f.bin", "f", -1))
        assertTrue("DELETE of a folder with a file in it", c.run(Op.DELETE, "/data/sub").second < 0)
        assertEquals(0, c.run(Op.DELETE, "/data/sub/f.bin").second)
        assertEquals(0, c.run(Op.DELETE, "/data/sub").second)
        assertFalse(File(dir, "data/fscart/sub").exists())
        assertEquals(E.ENOENT, c.run(Op.STAT, "/data/sub", 0, 16).second)

        // access (section 3) and the sandbox: refused at once
        val accText = listOf(Op.WRITE to "/audio/x.wav", Op.WRITE to "/code/x.lua", Op.MKDIR to "/audio/new", Op.DELETE to "/code/droplets/a.lua",
            Op.DELETE to "/data", Op.MKDIR to "/data", Op.READ to "/audio/../code/other/secret.lua", Op.READ to "/code/../other/secret.lua",
            Op.READ to "/code/./droplets/a.lua", Op.READ to "//audio/kick.wav", Op.READ to "/audio//kick.wav", Op.LIST to "/audio/",
            Op.READ to "audio/kick.wav", Op.READ to "/etc/passwd", Op.READ to "/", Op.READ to "/Audio/kick.wav", Op.READ to "/audio/kick.wav\u0000",
            Op.READ to "/audio\\kick.wav", Op.READ to "/audio/" + "x".repeat(1100))
        val acc = accText.map { it.first to it.second.toByteArray() } +
            listOf(Op.READ to byteArrayOf(0x2F, 0x61, 0x75, 0x64, 0x69, 0x6F, 0x2F, 0xFF.toByte()))
        val bad = acc.filter { c.request(it.first, it.second, 0, 16) != E.EACCES }
        assertTrue("${acc.size} accesses outside section 3: not EACCES: ${bad.map { String(it.second).take(40) }}", bad.isEmpty())
        if (posix) {
            x = c.run(Op.READ, "/code/escape", 0, 16)
            assertTrue(x.first > 0); assertEquals("a symlink out of the folder", E.EACCES, x.second)
        }
        assertEquals(E.EIO, c.request(9, "/audio/kick.wav".toByteArray(), 0, 16))
        assertEquals(E.EIO, c.request(0, "/audio/kick.wav".toByteArray(), 0, 16))

        // 32 in flight
        val ids = (0 until 32).map { c.request(Op.STAT, "/audio/kick.wav", 0, 16, at = c.buf + 16 * it) }
        val over = c.request(Op.STAT, "/audio/kick.wav", 0, 16)
        c.settle()
        val res = ids.map { c.call("t_poll", it.toLong()) }
        assertTrue(ids.all { it > 0 } && ids.toSet().size == 32)
        assertEquals("the 33rd", E.EBUSY, over)
        assertTrue(res.all { it == 16 })
        assertTrue("room again once polled", c.request(Op.STAT, "/audio", 0, 16) > 0)
        c.settle()

        // cancel
        for (k in 0 until 64) c.mem.put(c.buf + k, 0x55)
        val cid = c.request(Op.READ, "/audio/kick.wav", 0, 64)
        c.call("t_cancel", cid.toLong())
        c.settle()
        assertTrue("the buffer untouched", c.bytes(64).all { it == 0x55.toByte() })
        assertEquals(E.ECANCELED, c.call("t_poll", cid.toLong()))
        assertEquals("then the id is free", E.ENOENT, c.call("t_poll", cid.toLong()))
        assertEquals(E.ENOENT, c.call("t_poll", 123456))

        // fs_changed
        val c0 = c.call("t_changed")
        c.i.bump(); c.i.bump()
        assertEquals(c0 + 2, c.call("t_changed"))
        if (posix) RemoteFolderNative.folderChanged() else RemoteFolderHost.noteChanged()
        assertEquals("the app's folder changed", c0 + 3, c.call("t_changed"))
    }

    @Test fun foldersTheManifestListsAndNoFolder() {
        name = "fantasy_folders"
        folder()
        val dir = File(root, "RemoteFM1").path
        Cart(cartFile(with(fantasy, mapOf("folders" to JSONObject(mapOf("audio" to "r"))))), dir).use { a ->
            assertEquals(E.EACCES, a.request(Op.READ, "/code/droplets/a.lua", 0, 16))
            assertEquals(E.EACCES, a.request(Op.LIST, "/data", 0, 64))
            assertTrue(a.request(Op.READ, "/audio/kick.wav", 0, 16) > 0)
            a.settle()
        }
        Cart(cartFile(with(fantasy, mapOf("folders" to JSONObject(mapOf("audio" to "r", "data" to "r"))))), dir).use { b ->
            assertEquals("data r: no writes", E.EACCES, b.request(Op.WRITE, "/data/x", -1, 1))
            assertTrue(b.request(Op.LIST, "/data", 0, 64) > 0)
            b.settle()
        }
        // no folder: an id, PENDING, then ENOFOLDER after the next apply
        Cart(cartFile(fantasy), null).use { c ->
            val id = c.request(Op.READ, "/audio/kick.wav", 0, 16)
            assertTrue(id > 0)
            assertEquals(E.PENDING, c.call("t_poll", id.toLong()))
            c.i.apply()
            assertEquals(E.ENOFOLDER, c.call("t_poll", id.toLong()))
            // the app's folder (rfmw_set_folder) once set
            Rfm1TestNative.setFolder(dir)
            assertEquals("the app's folder", 16, c.run(Op.STAT, "/audio/kick.wav", 0, 16).second)
        }
        r.put("pass", true)
    }

    // ---- Android: the app's folder through JNI (rfm_folder_jni.cpp -> RemoteFolderHost -> FileRemoteFolder)

    @Test fun fileApiThroughTheAppsFolder() {
        name = "fantasy_jni_folder"
        folder()
        val dir = File(root, "RemoteFM1")
        RemoteFolderNative.start()
        RemoteFolderHost.use(null)
        Cart(cartFile(fantasy), null).use { c ->
            // none picked: ENOFOLDER
            var id = c.request(Op.STAT, "/audio/kick.wav", 0, 16)
            assertTrue(id > 0)
            c.i.apply()
            assertEquals(E.ENOFOLDER, c.call("t_poll", id.toLong()))
            // picked: the same checks as on the POSIX folder (FileRemoteFolder lists symlinks as files and follows none
            // out of the folder: /code/escape is not listed... see fileApiChecks)
            val ch0 = c.call("t_changed")
            File(dir, "code/fscart/escape").delete()
            RemoteFolderHost.use(FileRemoteFolder(dir))
            assertTrue("a picked folder moves fs_changed", c.call("t_changed") > ch0)
            val reads0 = RemoteFolderNative.reads
            fileApiChecks(c, dir, posix = false)
            assertTrue("READs counted", RemoteFolderNative.reads > reads0)
            // the IO thread is attached to the JVM once: many requests in a row, no leak of local refs
            repeat(2000) {
                id = c.request(Op.READ, "/audio/kick.wav", (it * 512).toLong(), 512)
                c.settle()
                assertEquals(512, c.call("t_poll", id.toLong()))
            }
            // forgotten again: ENOFOLDER
            RemoteFolderHost.use(null)
            id = c.request(Op.READ, "/audio/kick.wav", 0, 16)
            c.i.apply()
            assertEquals(E.ENOFOLDER, c.call("t_poll", id.toLong()))
        }
        r.put("pass", true)
    }

    // ---- fm1-ripple

    /** an asset folder copied out ([to] made) */
    private fun copyAssets(from: String, to: File) {
        to.mkdirs()
        for (n in assets.list(from).orEmpty()) {
            val p = "$from/$n"
            val kids = assets.list(p).orEmpty()
            if (kids.isEmpty()) assets.open(p).use { i -> File(to, n).outputStream().use { i.copyTo(it) } }
            else copyAssets(p, File(to, n))
        }
    }

    private fun ripple(): File = File(root, "ripple-0.4.rfm1").also { f -> assets.open("fantasy/ripple-0.4.rfm1").use { i -> f.outputStream().use { i.copyTo(it) } } }

    private fun rippleFolder(): File = File(root, "RemoteFM1").also { copyAssets("fantasy/ripple_folder", it) }

    /** booted, its droplets read, PLAY on the first (a1tone), the chord: its RMS; the engine [c] left running */
    private fun playA1(c: WasmCoreUnderTest, readsBefore: Long, out: JSONObject): RfmEngine.Audio {
        c.boot()
        c.run(3000)
        assertTrue("ripple read its droplets and lib from the folder (${RemoteFolderNative.reads - readsBefore})",
            RemoteFolderNative.reads - readsBefore >= 5)
        c.e.button(RfmEngine.B_PLAY, true); c.run(60); c.e.button(RfmEngine.B_PLAY, false)
        c.run(1440)
        for (k in listOf(9, 13, 16)) c.key(k, true)
        val a = c.run(1500)
        for (k in listOf(9, 13, 16)) c.key(k, false)
        val s = c.status
        out.put("reads", RemoteFolderNative.reads - readsBefore).put("rms", a.rms).put("peak", a.peak)
            .put("lcd_writes", s.getInt("lcd_writes"))
        Log.i(TAG, "ripple: ${RemoteFolderNative.reads - readsBefore} folder reads, rms ${a.rms}, halted ${s.getInt("halted")}")
        assertEquals(0, s.getInt("halted"))
        assertTrue("a1tone is silent (rms ${a.rms})", a.rms > 0.0005)
        return a
    }

    /** ripple-0.4 with FM1VST's test folder (a POSIX folder): the droplets read, a1tone started (PLAY), the chord
     *  sounds; then a2err (OCT+ in the list): its runtime error shows, the module doesn't trap, and a1tone plays again */
    @Test fun rippleReadsItsDropletsAndPlays() {
        name = "fantasy_ripple"
        val path = ripple().path
        val dir = rippleFolder()
        Rfm1TestNative.setFolder(dir.path)
        val i = Rfm1Native.check(path).getOrElse { throw AssertionError("ripple refused: ${it.message}") }
        assertTrue(i.isVirtual)
        assertTrue(i.usesFolder)
        assertEquals("ripple", i.id)
        WasmCoreUnderTest(path).use { c ->
            playA1(c, RemoteFolderNative.reads, r)
            erroringDroplet(c)
        }
        r.put("pass", true)
    }

    /** HOME held (back to the list), OCT+ (a2err), PLAY, a key and SEL: its error, no trap; HOME, OCT-, PLAY: sound */
    private fun erroringDroplet(c: WasmCoreUnderTest) {
        fun press(b: Int, ms: Int = 60) { c.e.button(b, true); c.run(ms); c.e.button(b, false); c.run(200) }
        press(RfmEngine.B_HOME, 1300)                           // (held a second: back to the list)
        c.run(500)
        press(RfmEngine.B_OCTUP)
        shot(c, "ripple-list.png")
        val lcdList = c.lcd
        press(RfmEngine.B_PLAY)
        c.run(1000)
        c.key(9, true); c.run(200); c.key(9, false)
        press(RfmEngine.B_SEL)
        c.run(1000)
        shot(c, "ripple-a2err.png")
        val s = c.status
        assertEquals("a2err trapped the module", 0, s.getInt("halted"))
        assertNotEquals("a2err: nothing on the screen changed", lcdList, c.lcd)
        r.put("a2err_halted", s.getInt("halted"))
        // HOME (the error screen back to the list), OCT- (a1tone), PLAY: it plays again
        press(RfmEngine.B_HOME)
        c.run(300)
        press(RfmEngine.B_OCTDN)
        press(RfmEngine.B_PLAY)
        c.run(1500)
        for (k in listOf(9, 13, 16)) c.key(k, true)
        val a = c.run(1500)
        for (k in listOf(9, 13, 16)) c.key(k, false)
        assertEquals(0, c.status.getInt("halted"))
        assertTrue("a1tone after a2err is silent (rms ${a.rms})", a.rms > 0.0005)
        r.put("rms_after_a2err", a.rms)
    }

    /** the LCD into files/<name> (a PNG, to look at) */
    private fun shot(c: WasmCoreUnderTest, name: String) {
        val rgba = ByteArray(240 * 240 * 4)
        c.e.preview(0, rgba)
        val px = IntArray(240 * 240) { i ->
            val o = i * 4
            (0xFF shl 24) or ((rgba[o].toInt() and 0xFF) shl 16) or ((rgba[o + 1].toInt() and 0xFF) shl 8) or (rgba[o + 2].toInt() and 0xFF)
        }
        val bm = android.graphics.Bitmap.createBitmap(px, 240, 240, android.graphics.Bitmap.Config.ARGB_8888)
        File(ctx.filesDir, name).outputStream().use { bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** ripple through the app's own path: imported into a library (accepted), found again by a new library's sync (a
     *  restart), loaded from it and played over the app's folder (JNI -> RemoteFolderHost -> FileRemoteFolder) */
    @Test fun rippleImportedSyncedAndPlayedOverTheAppsFolder() {
        name = "fantasy_ripple_app"
        val libDir = File(root, "lib")
        val lib = FirmwareLibrary(libDir, Rfm1Native)
        lib.sync()
        val e = lib.import(ripple())
        assertNull(e.error, e.error)
        assertEquals("ripple", e.coreID)
        assertTrue(lib.details(e.sha256).fantasy.virtual)
        val again = FirmwareLibrary(libDir, Rfm1Native)
        again.sync()
        val f = again.files.value.firstOrNull { it.sha256 == e.sha256 }
        assertNotNull("ripple kept by a sync (a restart)", f)
        val path = again.path(f!!.sha256).path
        assertNull(FantasyCfw.refusal(File(path)))
        assertNull(MemoryBudget.check(ctx, path))
        RemoteFolderNative.start()
        RemoteFolderHost.use(FileRemoteFolder(rippleFolder()))
        Rfm1Native.setMemoryBudget(MemoryBudget.nativeBudget(MemoryBudget.budget(ctx)))
        WasmCoreUnderTest(path).use { c ->
            Rfm1Native.setMemoryBudget(0)
            playA1(c, RemoteFolderNative.reads, r)
        }
        r.put("pass", true)
    }

    companion object {
        const val TAG = "RFMFantasyTest"
    }
}
