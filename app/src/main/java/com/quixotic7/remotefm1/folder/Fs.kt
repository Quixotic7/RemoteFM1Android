// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A Fantasy CFW's file API (FM1VST docs/VIRTUAL-CARTS.md sections 3-4, capability fs1): its codes, the path rules and
// the LIST packing, as RemoteFM1's web engine (RemoteFM1 60a1b8b web/engines/rfm1/engine.js FileApi) and rfm1_run's
// --folder have them. The shared C++ (RFMWasmCore.cpp's RFMFileApi) checks every request first; the Kotlin back ends
// (RemoteFolder) check the folder paths again (defence in depth).
//   a cart's path   "/audio/kick.wav", "/code/droplets/a.lua", "/data/save.txt" (virtual, absolute)
//   a folder path   "audio/kick.wav", "code/<id>/droplets/a.lua", "data/<id>/save.txt" (inside RemoteFM1/)
package com.quixotic7.remotefm1.folder

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object Fs {
    // ops
    const val STAT = 1
    const val LIST = 2
    const val READ = 3
    const val WRITE = 4
    const val DELETE = 5
    const val MKDIR = 6

    // results < 0
    const val PENDING = -1
    const val ENOENT = -2
    const val EACCES = -3
    const val EIO = -4
    const val ENOSPC = -5
    const val EBUSY = -6
    const val ECANCELED = -7
    const val ENOFOLDER = -8

    const val MAX_INFLIGHT = 32
    const val MAX_LEN = 1 shl 20
    const val MAX_PATH = 1024
    const val MAX_NAME = 255
    /** a folder path is the cart's with code/<id>/ or data/<id>/ in front: a little longer */
    const val MAX_FOLDER_PATH = MAX_PATH + 64
    val ROOTS = listOf("audio", "code", "data")

    fun isWrite(op: Int) = op == WRITE || op == DELETE || op == MKDIR

    /** a cart's path mapped onto the folder: [root] audio / code / data, [top] the area itself ("/data") */
    data class Mapped(val root: String, val top: Boolean, val path: String)

    /** UTF-8 bytes -> a string; null when they aren't valid UTF-8 */
    fun utf8(b: ByteArray, off: Int = 0, len: Int = b.size): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, off, len)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /**
     * The cart's path (UTF-8 bytes) -> its folder path, or null when section 3 refuses it (FS_EACCES): it starts with
     * "/", then audio, code or data; no empty, "." or ".." segment, no trailing "/", no NUL or backslash, valid UTF-8,
     * at most [MAX_PATH] bytes and names of at most 255. [id]: the cart's id (code/<id>, data/<id>).
     */
    fun folderPath(p: ByteArray, id: String): Mapped? {
        if (p.size < 2 || p.size > MAX_PATH || p[0] != '/'.code.toByte()) return null
        for (c in p) if (c == 0.toByte() || c == '\\'.code.toByte()) return null
        val s = utf8(p) ?: return null
        val seg = s.substring(1).split('/')
        if (seg[0] !in ROOTS) return null
        for (x in seg) if (x.isEmpty() || x == "." || x == ".." || x.toByteArray().size > MAX_NAME) return null
        val root = seg[0]
        val rest = seg.drop(1)
        val head = if (root == "audio") listOf("audio") else listOf(root, id)
        return Mapped(root, rest.isEmpty(), (head + rest).joinToString("/"))
    }

    /** the manifest's "folders" against an op: 0, or FS_EACCES (writes only in /data below its top, with "rw") */
    fun access(op: Int, m: Mapped, folders: Map<String, String>): Int {
        val mode = folders[m.root] ?: ""
        val ok = if (isWrite(op)) m.root == "data" && mode == "rw" && !m.top else mode.contains('r')
        return if (ok) 0 else EACCES
    }

    /**
     * A folder path checked again by the back ends: 0, or FS_EACCES. audio[/...], code/<id>[/...], data/<id>[/...];
     * no empty, "." or ".." segment, no NUL or backslash; writes ([write]) only below data/<id>/.
     */
    fun checkFolderPath(rel: String, write: Boolean): Int {
        if (rel.isEmpty() || rel.toByteArray().size > MAX_FOLDER_PATH) return EACCES
        if (rel.any { it == '\u0000' || it == '\\' }) return EACCES
        val seg = rel.split('/')
        for (x in seg) if (x.isEmpty() || x == "." || x == ".." || x.toByteArray().size > MAX_NAME) return EACCES
        if (seg[0] !in ROOTS) return EACCES
        if (seg[0] != "audio" && seg.size < 2) return EACCES
        if (write && (seg[0] != "data" || seg.size < 3)) return EACCES
        return 0
    }

    /** the area a folder path lies in: "audio", "code/<id>" or "data/<id>" (made on demand by the back ends) */
    fun areaOf(rel: String): String {
        val seg = rel.split('/')
        return if (seg[0] == "audio") "audio" else "${seg[0]}/${seg[1]}"
    }

    /** one LIST entry: its name's UTF-8 bytes */
    class Entry(val name: ByteArray, val dir: Boolean) {
        val text: String get() = String(name, Charsets.UTF_8)
    }

    /** a folder's entries as LIST gives them: no hidden ones (a leading "."), names of 1-255 bytes without "/", sorted
     *  by their bytes (unsigned) */
    fun sortEntries(entries: List<Pair<String, Boolean>>): List<Entry> =
        entries.filter { (n, _) -> n.isNotEmpty() && !n.startsWith(".") && '/' !in n && '\u0000' !in n }
            .map { (n, d) -> Entry(n.toByteArray(), d) }.filter { it.name.size <= MAX_NAME }
            .sortedWith { a, b -> compareBytes(a.name, b.name) }

    fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (x != 0) return x
        }
        return a.size - b.size
    }

    /** every entry packed {u8 dir, u8 len, name}, in order (the back ends' list answer) */
    fun packAll(sorted: List<Entry>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (e in sorted) {
            out.write(if (e.dir) 1 else 0)
            out.write(e.name.size)
            out.write(e.name)
        }
        return out.toByteArray()
    }

    /** [packAll]'s bytes read back */
    fun unpack(b: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        var at = 0
        while (at + 2 <= b.size) {
            val n = b[at + 1].toInt() and 0xFF
            if (at + 2 + n > b.size) break
            out.add(Entry(b.copyOfRange(at + 2, at + 2 + n), b[at] != 0.toByte()))
            at += 2 + n
        }
        return out
    }

    /**
     * LIST's page: the entries from [from] on that fit in [cap] bytes, never one split. Returns the bytes and the
     * count; the count is FS_EIO when the first one doesn't fit, 0 at the end.
     */
    fun packPage(sorted: List<Entry>, from: Int, cap: Int): Pair<ByteArray, Int> {
        val out = java.io.ByteArrayOutputStream()
        var k = 0
        var at = 0
        for (i in from until sorted.size) {
            val e = sorted[i]
            if (at + 2 + e.name.size > cap) break
            out.write(if (e.dir) 1 else 0)
            out.write(e.name.size)
            out.write(e.name)
            at += 2 + e.name.size
            k++
        }
        if (k == 0 && from < sorted.size) return Pair(ByteArray(0), EIO)
        return Pair(out.toByteArray(), k)
    }

    /** STAT's 16-byte record: size (u64), mtime (u32 s), is_dir (u32), little-endian */
    fun statRecord(size: Long, mtimeSec: Long, dir: Boolean): ByteArray =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(maxOf(0, size))
            .putInt(mtimeSec.coerceIn(0, 0xFFFFFFFFL).toInt()).putInt(if (dir) 1 else 0).array()

    /** an IOException's message -> FS_ENOSPC when the disk is full, else FS_EIO */
    fun ioCode(e: Throwable): Int {
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            "ENOSPC" in m || "No space left" in m -> ENOSPC
            "EACCES" in m || "Permission denied" in m || e is SecurityException -> EACCES
            "ENOENT" in m || e is java.io.FileNotFoundException -> ENOENT
            else -> EIO
        }
    }
}
