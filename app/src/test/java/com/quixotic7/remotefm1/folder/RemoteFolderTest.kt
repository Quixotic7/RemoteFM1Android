// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The RemoteFM1 folder's platform side through RemoteFolderHost (what the JNI calls) on FileRemoteFolder over a
// temporary folder laid out as RemoteFM1 60a1b8b web/engines/rfm1/fs-test.mjs's: every op, its errors, the sandbox
// (a symlink out), no folder, fs_changed. The paths are what the shared C++ hands over (folder paths, Fs.folderPath).
package com.quixotic7.remotefm1.folder

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

class RemoteFolderTest {
    private lateinit var tmp: File
    private lateinit var root: File
    private val kick = ByteArray(3 * 1048576 + 17) { i -> ((i * 7 + (i shr 9)) and 255).toByte() }

    private fun put(p: String, b: ByteArray) { File(root, p).apply { parentFile.mkdirs(); writeBytes(b) } }
    private fun put(p: String, s: String) = put(p, s.toByteArray())
    private fun b(p: String) = p.toByteArray()

    @Before fun setUp() {
        tmp = Files.createTempDirectory("fs-test-").toFile()
        root = File(tmp, "RemoteFM1")
        put("audio/kick.wav", kick)
        put("audio/sub/x.wav", "x")
        put("audio/.hidden", "h")
        put("audio/B.wav", "b"); put("audio/a.wav", "a"); put("audio/Z", "z"); put("audio/é.wav", "e")
        put("code/fscart/droplets/a.lua", "print('a')")
        put("code/other/secret.lua", "secret")
        File(tmp, "outside.txt").writeText("outside")
        Files.createSymbolicLink(File(root, "code/fscart/escape").toPath(), File(tmp, "outside.txt").toPath())
        RemoteFolderHost.use(FileRemoteFolder(root))
    }

    @After fun tearDown() {
        RemoteFolderHost.use(null)
        tmp.renameTo(File(tmp.path + "-done"))           // (left for a look; the OS clears tmp)
    }

    private fun stat(p: String): Pair<Int, LongArray> { val o = LongArray(3); return RemoteFolderHost.stat(b(p), o) to o }
    private fun list(p: String): Pair<Int, String> {
        val o = arrayOfNulls<ByteArray>(1)
        val r = RemoteFolderHost.list(b(p), o)
        return r to (o[0]?.let { Fs.unpack(it).joinToString(" ") { e -> (if (e.dir) "/" else "") + e.text } } ?: "")
    }
    private fun read(p: String, off: Long, len: Int, buf: ByteBuffer = ByteBuffer.allocateDirect(len)) =
        RemoteFolderHost.read(b(p), off, buf, len) to buf
    private fun write(p: String, s: String, off: Long): Int {
        val d = s.toByteArray()
        return RemoteFolderHost.write(b(p), off, ByteBuffer.wrap(d), d.size)
    }
    private fun disk(p: String) = File(root, "data/fscart/$p").readText()

    @Test fun stat() {
        val (r, o) = stat("audio/kick.wav")
        assertEquals(0, r)
        assertEquals(kick.size.toLong(), o[0])
        assertEquals(0L, o[2])
        assertTrue(Math.abs(o[1] - System.currentTimeMillis() / 1000) < 600)
        assertEquals(1L, stat("audio").second[2])
        assertEquals(0L, stat("audio").second[0])
        assertEquals(Fs.ENOENT, stat("audio/none.wav").first)
    }

    @Test fun read() {
        val (r, buf) = read("audio/kick.wav", 1000, 4096)
        assertEquals(4096, r)
        val got = ByteArray(4096).also { buf.duplicate().apply { position(0) }.get(it) }
        assertArrayEquals(kick.copyOfRange(1000, 5096), got)
        assertEquals(1048576, read("audio/kick.wav", 0, 1048576).first)
        assertEquals(10, read("audio/kick.wav", kick.size - 10L, 100).first)       // (past the end: what is left)
        assertEquals(0, read("audio/kick.wav", kick.size.toLong(), 100).first)     // (at the end)
        assertEquals(0, read("audio/kick.wav", 1L shl 40, 100).first)              // (an i64 offset)
        assertEquals(Fs.EIO, read("audio/kick.wav", -5, 100).first)
        assertEquals(Fs.EIO, read("audio", 0, 100).first)                          // (a folder)
        assertEquals(Fs.ENOENT, read("audio/none.wav", 0, 100).first)
        // into a buffer from its position (a staging buffer's slot)
        val big = ByteBuffer.allocateDirect(64)
        big.position(16)
        assertEquals(10, RemoteFolderHost.read(b("code/fscart/droplets/a.lua"), 0, big, 48))
        assertEquals("print('a')", ByteArray(10).also { big.duplicate().apply { position(16) }.get(it) }.decodeToString())
    }

    @Test fun list() {
        assertEquals(6 to "B.wav Z a.wav kick.wav /sub é.wav", list("audio"))
        assertEquals(2 to "/droplets escape", list("code/fscart"))
        assertEquals(0 to "", list("data/fscart"))
        assertTrue("data/fscart made on demand", File(root, "data/fscart").isDirectory)
        assertEquals(Fs.ENOENT, list("audio/none").first)
        assertEquals(Fs.ENOENT, list("audio/kick.wav").first)
    }

    @Test fun writeMkdirDelete() {
        assertEquals(5, write("data/fscart/save.txt", "hello", -1))
        assertEquals("hello", disk("save.txt"))
        assertEquals(6, write("data/fscart/save.txt", " world", 5))
        assertEquals("hello world", disk("save.txt"))
        assertEquals(2, write("data/fscart/save.txt", "HE", 0))
        assertEquals("HEllo world", disk("save.txt"))
        assertEquals(1, write("data/fscart/save.txt", "x", -1))
        assertEquals("x", disk("save.txt"))
        assertEquals(0, RemoteFolderHost.mkdir(b("data/fscart/sub")))
        assertEquals(0, RemoteFolderHost.mkdir(b("data/fscart/sub")))
        assertEquals(1, write("data/fscart/sub/f.bin", "f", -1))
        assertEquals(Fs.ENOENT, write("data/fscart/nofolder/f.bin", "f", -1))
        assertTrue(RemoteFolderHost.delete(b("data/fscart/sub")) < 0)          // (a folder with a file in it)
        assertEquals(0, RemoteFolderHost.delete(b("data/fscart/sub/f.bin")))
        assertEquals(0, RemoteFolderHost.delete(b("data/fscart/sub")))
        assertFalse(File(root, "data/fscart/sub").exists())
        assertEquals(Fs.ENOENT, stat("data/fscart/sub").first)
        assertEquals(Fs.ENOENT, RemoteFolderHost.delete(b("data/fscart/none")))
        assertEquals(Fs.EIO, RemoteFolderHost.mkdir(b("data/fscart/save.txt")))   // (a file is there)
        // a write into a fresh cart's data/<id>/: its folder made on demand
        assertEquals(2, write("data/newcart/x.bin", "hi", -1))
    }

    @Test fun theHostRefusesWhatTheC__ShouldHave() {
        // (defence in depth: writes outside data/<id>/, "..", ".", empty segments, an area's top)
        for (p in listOf("audio/x.wav", "code/fscart/x.lua", "data/fscart", "data", "data/fscart/../other/x", "data/fscart//x"))
            assertEquals(p, Fs.EACCES, write(p, "x", -1))
        assertEquals(Fs.EACCES, RemoteFolderHost.mkdir(b("audio/new")))
        assertEquals(Fs.EACCES, RemoteFolderHost.delete(b("code/fscart/droplets/a.lua")))
        assertEquals(Fs.EACCES, read("audio/../code/other/secret.lua", 0, 16).first)
        assertEquals(Fs.EACCES, read("code/../other/secret.lua", 0, 16).first)
        assertEquals(Fs.EACCES, stat("etc/passwd").first)
        assertEquals(Fs.EACCES, RemoteFolderHost.read(byteArrayOf(0x61, 0x75, 0x64, 0x69, 0x6f, 0x2f, 0xff.toByte()), 0,
                                                     ByteBuffer.allocate(16), 16))
    }

    @Test fun aSymlinkOutOfTheFolderIsRefused() {
        assertEquals(Fs.EACCES, read("code/fscart/escape", 0, 16).first)
        assertEquals(Fs.EACCES, stat("code/fscart/escape").first)
        // an area that is itself a symlink out
        Files.createSymbolicLink(File(root, "code/linked").toPath(), tmp.toPath())
        assertEquals(Fs.EACCES, read("code/linked/outside.txt", 0, 16).first)
    }

    @Test fun noFolderIsEnofolder() {
        RemoteFolderHost.use(null)
        assertEquals(Fs.ENOFOLDER, stat("audio/kick.wav").first)
        assertEquals(Fs.ENOFOLDER, list("audio").first)
        assertEquals(Fs.ENOFOLDER, read("audio/kick.wav", 0, 16).first)
        assertEquals(Fs.ENOFOLDER, write("data/fscart/x", "x", -1))
        assertEquals(Fs.ENOFOLDER, RemoteFolderHost.mkdir(b("data/fscart/d")))
        assertEquals(Fs.ENOFOLDER, RemoteFolderHost.delete(b("data/fscart/d")))
        assertFalse(RemoteFolderHost.ready)
    }

    @Test fun fsChangedMoves() {
        val c0 = RemoteFolderHost.changed()
        RemoteFolderHost.noteChanged()
        RemoteFolderHost.noteChanged()
        assertEquals(c0 + 2, RemoteFolderHost.changed())
        RemoteFolderHost.use(FileRemoteFolder(root))                         // (another folder picked)
        assertEquals(c0 + 3, RemoteFolderHost.changed())
        assertTrue(RemoteFolderHost.status.value.ready)
    }

    @Test fun ripplesTestFolder() {
        // (FM1VST core-glue/ripple/test_folder: code/ripple/droplets/*.lua, lib/a1lib.lua) as ripple lists it
        for (n in listOf("a1tone", "a2err", "a3loop", "a4data")) put("code/ripple/droplets/$n.lua", "-- $n")
        put("code/ripple/lib/a1lib.lua", "-- lib")
        assertEquals(4 to "a1tone.lua a2err.lua a3loop.lua a4data.lua", list("code/ripple/droplets"))
        assertEquals(2 to "/droplets /lib", list("code/ripple"))
        assertEquals(0 to "", list("data/ripple"))
    }
}
