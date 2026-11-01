// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// SafRemoteFolder on a real Storage Access Framework tree: the debug build's TestDocumentsProvider (files/testdocs/;
// the app's own provider, so no picker and no grant). Through RemoteFolderHost (what the JNI calls), from a thread of
// the test's own (never the main one): RemoteFM1/ and its areas made on demand, every op of RemoteFM1 60a1b8b
// web/engines/rfm1/fs-test.mjs at the platform level (seekable reads, writes at offsets and truncating, LIST sorted
// without hidden files, mkdir, delete), the cache (a second lookup costs no folder query; a file added or replaced
// outside the app is still seen), a provider that renames a new file, the main thread refused, no folder.
package com.quixotic7.remotefm1.folder

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.TestDocumentsProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class SafRemoteFolderTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var disk: File          // files/testdocs/Music: the tree the user "picked"
    private val root get() = File(disk, "RemoteFM1")
    private lateinit var saf: SafRemoteFolder
    private val kick = ByteArray(3 * 1048576 + 17) { i -> ((i * 7 + (i shr 9)) and 255).toByte() }

    private fun put(p: String, b: ByteArray) { File(root, p).apply { parentFile!!.mkdirs(); writeBytes(b) } }
    private fun put(p: String, s: String) = put(p, s.toByteArray())
    private fun b(p: String) = p.toByteArray()

    @Before fun setUp() {
        val base = File(ctx.filesDir, "testdocs")
        if (base.exists()) base.renameTo(File(ctx.cacheDir, "testdocs-old-${System.nanoTime()}"))
        disk = File(base, "Music").apply { mkdirs() }
        val tree = DocumentsContract.buildTreeDocumentUri("${ctx.packageName}.testdocs", "${TestDocumentsProvider.ROOT}/Music")
        saf = SafRemoteFolder(ctx.contentResolver, tree)
        RemoteFolderHost.use(saf)
    }

    @After fun tearDown() {
        RemoteFolderHost.use(null)
        saf.forget()
    }

    private fun stat(p: String): Pair<Int, LongArray> { val o = LongArray(3); return RemoteFolderHost.stat(b(p), o) to o }
    private fun list(p: String): Pair<Int, String> {
        val o = arrayOfNulls<ByteArray>(1)
        val r = RemoteFolderHost.list(b(p), o)
        return r to (o[0]?.let { Fs.unpack(it).joinToString(" ") { e -> (if (e.dir) "/" else "") + e.text } } ?: "")
    }
    private fun read(p: String, off: Long, len: Int): Pair<Int, ByteArray> {
        val buf = ByteBuffer.allocateDirect(len)
        val r = RemoteFolderHost.read(b(p), off, buf, len)
        return r to ByteArray(maxOf(0, r)).also { buf.duplicate().apply { position(0) }.get(it) }
    }
    private fun write(p: String, s: String, off: Long): Int {
        val d = s.toByteArray()
        return RemoteFolderHost.write(b(p), off, ByteBuffer.wrap(d), d.size)
    }

    @Test fun remoteFm1AndItsAreasAreMadeOnDemand() {
        assertFalse(root.exists())
        assertEquals(0 to "", list("data/ripple"))
        assertTrue(File(root, "data/ripple").isDirectory)
        assertEquals(0, stat("audio").first)
        assertTrue(File(root, "audio").isDirectory)
        assertEquals(Fs.ENOENT, stat("code/ripple/droplets/x.lua").first)
        assertTrue(File(root, "code/ripple").isDirectory)
        assertFalse(File(root, "code/ripple/droplets").exists())       // (only the area, never deeper)
        assertEquals("Music › RemoteFM1", saf.location)
    }

    @Test fun statReadList() {
        put("audio/kick.wav", kick)
        put("audio/sub/x.wav", "x")
        put("audio/.hidden", "h")
        put("audio/B.wav", "b"); put("audio/a.wav", "a"); put("audio/Z", "z"); put("audio/é.wav", "e")
        put("code/fscart/droplets/a.lua", "print('a')")
        val (r, o) = stat("audio/kick.wav")
        assertEquals(0, r)
        assertEquals(kick.size.toLong(), o[0])
        assertEquals(0L, o[2])
        assertTrue(Math.abs(o[1] - System.currentTimeMillis() / 1000) < 600)
        assertEquals(1L, stat("audio/sub").second[2])
        assertEquals(Fs.ENOENT, stat("audio/none.wav").first)

        val x = read("audio/kick.wav", 1000, 4096)
        assertEquals(4096, x.first)
        assertArrayEquals(kick.copyOfRange(1000, 5096), x.second)
        val mb = read("audio/kick.wav", 2 * 1048576, 1048576)
        assertArrayEquals(kick.copyOfRange(2 * 1048576, 3 * 1048576), mb.second)
        assertEquals(10, read("audio/kick.wav", kick.size - 10L, 100).first)
        assertEquals(0, read("audio/kick.wav", kick.size.toLong(), 100).first)
        assertEquals(0, read("audio/kick.wav", 1L shl 40, 100).first)
        assertArrayEquals(kick.copyOfRange(0, 64), read("audio/kick.wav", 0, 64).second)    // (backwards: seekable)
        assertEquals(Fs.EIO, read("audio/sub", 0, 16).first)
        assertEquals("print('a')", read("code/fscart/droplets/a.lua", 0, 100).second.decodeToString())

        assertEquals(6 to "B.wav Z a.wav kick.wav /sub é.wav", list("audio"))
        assertEquals(1 to "/droplets", list("code/fscart"))
        assertEquals(Fs.ENOENT, list("audio/none").first)
        assertEquals(Fs.ENOENT, list("audio/kick.wav").first)
    }

    @Test fun writeMkdirDelete() {
        fun onDisk(p: String) = File(root, "data/fscart/$p").readText()
        assertEquals(5, write("data/fscart/save.txt", "hello", -1))
        assertEquals("hello", onDisk("save.txt"))
        assertEquals(6, write("data/fscart/save.txt", " world", 5))
        assertEquals("hello world", onDisk("save.txt"))
        assertEquals(2, write("data/fscart/save.txt", "HE", 0))
        assertEquals("HEllo world", onDisk("save.txt"))
        assertEquals(1, write("data/fscart/save.txt", "x", -1))
        assertEquals("x", onDisk("save.txt"))
        assertEquals(1L, stat("data/fscart/save.txt").second[0])
        assertEquals(0, RemoteFolderHost.mkdir(b("data/fscart/sub")))
        assertEquals(0, RemoteFolderHost.mkdir(b("data/fscart/sub")))
        assertEquals(1, write("data/fscart/sub/f.bin", "f", -1))
        assertEquals(Fs.ENOENT, write("data/fscart/nofolder/f.bin", "f", -1))
        assertEquals(Fs.ENOENT, RemoteFolderHost.mkdir(b("data/fscart/no/deeper")))
        assertEquals(Fs.EIO, RemoteFolderHost.delete(b("data/fscart/sub")))       // (not empty)
        assertEquals(0, RemoteFolderHost.delete(b("data/fscart/sub/f.bin")))
        assertEquals(0, RemoteFolderHost.delete(b("data/fscart/sub")))
        assertFalse(File(root, "data/fscart/sub").exists())
        assertEquals(Fs.ENOENT, stat("data/fscart/sub").first)
        assertEquals(Fs.EIO, RemoteFolderHost.mkdir(b("data/fscart/save.txt")))
        assertEquals("save.txt", list("data/fscart").second)
        // writes outside data/<id>/: refused before the folder is touched
        assertEquals(Fs.EACCES, write("audio/x.wav", "x", -1))
        assertEquals(Fs.EACCES, write("data/fscart/../other/x", "x", -1))
    }

    @Test fun theCacheSavesQueriesAndStillSeesChanges() {
        put("code/ripple/droplets/a1tone.lua", "-- tone")
        assertEquals(0, stat("code/ripple/droplets/a1tone.lua").first)
        val q = TestDocumentsProvider.childQueries
        repeat(5) { assertEquals(0, stat("code/ripple/droplets/a1tone.lua").first) }
        assertEquals("a known path: no folder queries", q, TestDocumentsProvider.childQueries)
        // a file added outside the app (a file manager): a miss is always looked up again
        assertEquals(Fs.ENOENT, stat("code/ripple/droplets/a2err.lua").first)
        put("code/ripple/droplets/a2err.lua", "-- err")
        assertEquals(0, stat("code/ripple/droplets/a2err.lua").first)
        assertEquals(2 to "a1tone.lua a2err.lua", list("code/ripple/droplets"))
        // a folder removed and made again outside the app (the cached id is gone): looked up once more
        assertEquals("-- tone", read("code/ripple/droplets/a1tone.lua", 0, 64).second.decodeToString())
        File(root, "code/ripple/droplets").renameTo(File(root, "code/ripple/old"))
        put("code/ripple/droplets/a1tone.lua", "-- new")
        saf.forget()                                         // (what the app does back in the foreground)
        assertEquals("-- new", read("code/ripple/droplets/a1tone.lua", 0, 64).second.decodeToString())
        File(root, "code/ripple/droplets/a1tone.lua").delete()
        saf.forget()                                         // (an open read handle outlives a delete outside the app)
        assertEquals(Fs.ENOENT, read("code/ripple/droplets/a1tone.lua", 0, 64).first)
    }

    @Test fun aProviderThatRenamesANewFile() {
        assertEquals(Fs.ENOENT, stat("data/fscart/x.bin").first)        // (data/fscart made first)
        TestDocumentsProvider.renameNext = true
        assertTrue(write("data/fscart/x.bin", "x", -1) < 0)
        assertEquals("not left behind", 0 to "", list("data/fscart"))
        assertEquals(1, write("data/fscart/x.bin", "x", -1))
    }

    @Test fun neverOnTheMainThread() {
        put("audio/a.wav", "a")
        var r = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync { r = stat("audio/a.wav").first }
        assertEquals(Fs.EIO, r)
        assertEquals(0, stat("audio/a.wav").first)
    }

    @Test fun noFolderAndChanges() {
        val c0 = RemoteFolderHost.changed()
        RemoteFolderHost.noteChanged()
        assertEquals(c0 + 1, RemoteFolderHost.changed())
        RemoteFolderHost.use(null)
        assertEquals(Fs.ENOFOLDER, stat("audio").first)
        assertEquals(Fs.ENOFOLDER, write("data/fscart/x", "x", -1))
        assertEquals(c0 + 2, RemoteFolderHost.changed())
    }
}
