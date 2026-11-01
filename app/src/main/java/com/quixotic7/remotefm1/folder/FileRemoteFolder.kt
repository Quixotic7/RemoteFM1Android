// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The RemoteFM1 folder on java.io.File ([root] is RemoteFM1/ itself): the JVM tests' folder, and any folder of the
// app's own. As rfm1_run --folder (FM1VST tools/rfm1_run/rfm1_run.c fs_do, fs_inside): a path, or for one not there
// yet its nearest folder, must resolve inside its area (no symlink out of the folder); a folder's size is 0.
package com.quixotic7.remotefm1.folder

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class FileRemoteFolder(val root: File) : RemoteFolder {
    override val location: String get() = root.path

    private fun f(rel: String) = File(root, rel)

    /** the area made on demand; then [rel] (or its nearest existing folder) inside it: else FS_EACCES */
    private fun inside(rel: String): Int {
        val area = f(Fs.areaOf(rel))
        if (!area.isDirectory && !area.mkdirs() && !area.isDirectory) return Fs.EIO
        val rootC = root.canonicalFile
        val areaC = area.canonicalFile
        if (!under(areaC, rootC)) return Fs.EACCES
        var t: File? = f(rel)
        while (t != null && !t.exists() && t != area) t = t.parentFile
        val tc = (t ?: area).canonicalFile
        return if (under(tc, areaC)) 0 else Fs.EACCES
    }

    private fun under(f: File, base: File) = f == base || f.path.startsWith(base.path + File.separator)

    override fun stat(path: String, out: LongArray): Int {
        inside(path).let { if (it != 0) return it }
        val t = f(path)
        if (!t.exists()) return Fs.ENOENT
        out[0] = if (t.isDirectory) 0 else t.length()
        out[1] = t.lastModified() / 1000
        out[2] = if (t.isDirectory) 1 else 0
        return 0
    }

    override fun list(path: String, out: Array<ByteArray?>): Int {
        inside(path).let { if (it != 0) return it }
        val t = f(path)
        if (!t.isDirectory) return Fs.ENOENT
        val kids = t.listFiles() ?: return Fs.EIO
        val sorted = Fs.sortEntries(kids.map { it.name to it.isDirectory })
        out[0] = Fs.packAll(sorted)
        return sorted.size
    }

    override fun read(path: String, offset: Long, buf: ByteBuffer, len: Int): Int {
        inside(path).let { if (it != 0) return it }
        val t = f(path)
        if (!t.exists()) return Fs.ENOENT
        if (t.isDirectory || offset < 0 || len < 0) return Fs.EIO
        RandomAccessFile(t, "r").use { raf -> return readAt(raf.channel, offset, buf, len) }
    }

    override fun write(path: String, offset: Long, buf: ByteBuffer, len: Int): Int {
        inside(path).let { if (it != 0) return it }
        val t = f(path)
        if (t.parentFile?.isDirectory != true) return Fs.ENOENT
        if (t.isDirectory || offset < -1 || len < 0) return Fs.EIO
        RandomAccessFile(t, "rw").use { raf ->
            if (offset < 0) raf.setLength(0)
            return writeAt(raf.channel, if (offset < 0) 0 else offset, buf, len)
        }
    }

    override fun delete(path: String): Int {
        inside(path).let { if (it != 0) return it }
        val t = f(path)
        if (!t.exists()) return Fs.ENOENT
        if (t.isDirectory && !t.list().isNullOrEmpty()) return Fs.EIO          // (not empty)
        return if (t.delete()) 0 else Fs.EIO
    }

    override fun mkdir(path: String): Int {
        inside(path).let { if (it != 0) return it }
        val t = f(path)
        if (t.isDirectory) return 0
        if (t.exists()) return Fs.EIO
        if (t.parentFile?.isDirectory != true) return Fs.ENOENT
        return if (t.mkdir() || t.isDirectory) 0 else Fs.EIO
    }

    companion object {
        /** [len] bytes (at most) at [offset] into [buf] from its position: the bytes read, 0 at the end */
        fun readAt(ch: java.nio.channels.FileChannel, offset: Long, buf: ByteBuffer, len: Int): Int {
            val b = buf.duplicate()
            b.limit(minOf(b.capacity(), b.position() + len))
            var pos = offset
            var n = 0
            while (b.hasRemaining()) {
                val r = ch.read(b, pos)
                if (r <= 0) break
                pos += r
                n += r
            }
            return n
        }

        fun writeAt(ch: java.nio.channels.FileChannel, offset: Long, buf: ByteBuffer, len: Int): Int {
            val b = buf.duplicate()
            if (b.position() + len > b.capacity()) throw IOException("the buffer is shorter than $len")
            b.limit(b.position() + len)
            var pos = offset
            var n = 0
            while (b.hasRemaining()) {
                val w = ch.write(b, pos)
                if (w <= 0) break
                pos += w
                n += w
            }
            return n
        }
    }
}
