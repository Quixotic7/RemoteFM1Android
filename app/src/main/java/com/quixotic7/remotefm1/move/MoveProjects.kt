// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1 18c6a82 web/site/js/move-projects.js (the file format, pack / unpack) and RemoteFM1IOS ad33e95
// RFMKit/Sources/RFMKit/Move/MoveProjects.swift (the store): the Ableton Move sequencer's 32 projects, kept apart from
// the firmwares' saves. One file a slot in the app's files/move: move-project-01.rfmmove .. -32, each in the web
// player's file format, so a file goes between the web, iOS and Android as it is. Writes go to a temporary file first,
// then take the name. The file (Backup and Import too):
//   "RMVP"  magic (4 bytes)
//   u8      version (1)
//   u8      kind: 1 one project (.rfmmove), 2 a set (.rfmmoveset)
//   u16     count: 1..32 entries
//   then each entry: u8 slot (0..31, unique), u8 flags (0), u32 saved (Unix seconds, 0 unknown), u32 len, len bytes
// little-endian, nothing after the last entry, a file over MAX_FILE bytes not read; each blob checked by the core
// (rfm_move_project_check) when it is read.
package com.quixotic7.remotefm1.move

import java.io.File

class MoveProject(val slot: Int, val bytes: ByteArray, val saved: Long /* ms since 1970, 0 unknown */) {
    val name: String get() = MoveProjects.projectName(slot)
}

class MoveProjects(val dir: File, private val check: (ByteArray) -> Int) {
    /** every project, by slot */
    @Synchronized fun list(): List<MoveProject> =
        (0 until SLOTS).mapNotNull { get(it) }

    /** the slot's project (null: none, or a file that is not one) */
    @Synchronized fun get(slot: Int): MoveProject? {
        if (!validSlot(slot)) return null
        val f = file(slot)
        if (!f.isFile) return null
        return try {
            val e = unpackFile(f.readBytes(), check).entries.first()
            MoveProject(slot, e.bytes, e.saved)
        } catch (_: Exception) { null }
    }

    /** stores [bytes] in [slot] (saved: now, or the date given); throws when it can't */
    @Synchronized fun put(slot: Int, bytes: ByteArray, saved: Long = System.currentTimeMillis()): MoveProject {
        require(validSlot(slot)) { "no slot $slot" }
        dir.mkdirs()
        val p = MoveProject(slot, bytes, saved)
        val f = file(slot)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeBytes(packFile(listOf(p), KIND_ONE))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "could not write ${f.name}" }
        }
        return p
    }

    @Synchronized fun remove(slot: Int) { if (validSlot(slot)) file(slot).delete() }

    @Synchronized fun dirBits(): Int = dirBits(list())

    private fun file(slot: Int) = File(dir, fileName(slot))

    companion object {
        const val SLOTS = 32
        const val BLOB_MAX = 39072                  // RFM_MOVE_PROJECT_MAX
        val MAGIC = byteArrayOf(0x52, 0x4d, 0x56, 0x50)
        const val FILE_VERSION = 1
        const val KIND_ONE = 1
        const val KIND_SET = 2
        const val HEAD = 8
        const val ENTRY = 10
        const val MAX_FILE = HEAD + SLOTS * (ENTRY + BLOB_MAX)
        const val EXT_ONE = ".rfmmove"
        const val EXT_SET = ".rfmmoveset"
        const val SET_NAME = "move-projects$EXT_SET"

        fun validSlot(s: Int) = s in 0 until SLOTS
        fun dirBits(ps: List<MoveProject>): Int = ps.fold(0) { b, p -> if (validSlot(p.slot)) b or (1 shl p.slot) else b }
        fun projectName(slot: Int) = "Project ${slot + 1}"
        fun fileName(slot: Int) = "move-project-%02d%s".format(slot + 1, EXT_ONE)
        fun firstFree(bits: Int): Int { for (s in 0 until SLOTS) if ((bits ushr s) and 1 == 0) return s; return -1 }

        /** a project blob with its colour (byte 18, 0..16) set and its FNV-1a check (the last 4 bytes) made again */
        fun withColor(bytes: ByteArray, color: Int): ByteArray {
            val b = bytes.copyOf()
            val n = b.size - 4
            if (n <= 18) return b
            b[18] = color.toByte()
            var h = 0x811C9DC5.toInt()
            for (i in 0 until n) h = (h xor (b[i].toInt() and 255)) * 16777619
            for (k in 0 until 4) b[n + k] = (h ushr (8 * k)).toByte()
            return b
        }

        /** the file's bytes for these projects */
        fun packFile(ps: List<MoveProject>, kind: Int = if (ps.size == 1) KIND_ONE else KIND_SET): ByteArray {
            val n = ps.fold(HEAD) { a, p -> a + ENTRY + p.bytes.size }
            val out = ByteArray(n)
            MAGIC.copyInto(out)
            out[4] = FILE_VERSION.toByte(); out[5] = kind.toByte()
            le16(out, 6, ps.size)
            var o = HEAD
            for (p in ps) {
                out[o] = p.slot.toByte(); out[o + 1] = 0
                le32(out, o + 2, if (p.saved > 0) (p.saved / 1000).toInt() else 0)
                le32(out, o + 6, p.bytes.size)
                p.bytes.copyInto(out, o + ENTRY)
                o += ENTRY + p.bytes.size
            }
            return out
        }

        class Unpacked(val kind: Int, val entries: List<MoveProject>)

        /** a file's projects; throws IllegalArgumentException saying what is wrong. [check]: 0 for a valid blob
         *  (rfm_move_project_check) */
        fun unpackFile(bytes: ByteArray?, check: (ByteArray) -> Int): Unpacked {
            fun bad(why: String): Nothing = throw IllegalArgumentException("Not a Move project file: $why.")
            if (bytes == null) bad("nothing to read")
            if (bytes.size > MAX_FILE) bad("it is ${bytes.size} bytes, more than a full set ($MAX_FILE)")
            if (bytes.size < HEAD || (0 until 4).any { bytes[it] != MAGIC[it] }) bad("it does not start with RMVP")
            if (bytes[4].toInt() != FILE_VERSION) bad("version ${bytes[4]}; this app reads version $FILE_VERSION")
            val kind = bytes[5].toInt()
            val count = u16(bytes, 6)
            if (kind != KIND_ONE && kind != KIND_SET) bad("kind $kind")
            if (count < 1 || count > SLOTS || (kind == KIND_ONE && count != 1)) bad("$count projects")
            val entries = ArrayList<MoveProject>()
            val seen = HashSet<Int>()
            var o = HEAD
            for (i in 0 until count) {
                if (o + ENTRY > bytes.size) bad("project ${i + 1} is cut off")
                val slot = bytes[o].toInt() and 255
                val t = u32(bytes, o + 2)
                val len = u32(bytes, o + 6)
                if (!validSlot(slot) || slot in seen) bad("slot ${slot + 1} ${if (slot in seen) "twice" else "out of range"}")
                if (len < 1 || len > BLOB_MAX) bad("project ${i + 1} is $len bytes")
                if (o + ENTRY + len > bytes.size) bad("project ${i + 1} is cut off")
                val blob = bytes.copyOfRange(o + ENTRY, o + ENTRY + len.toInt())
                val r = try { check(blob) } catch (_: Exception) { -1 }
                if (r != 0) bad("project ${i + 1} (slot ${slot + 1}) is damaged")
                seen += slot
                entries += MoveProject(slot, blob, if (t > 0) t * 1000 else System.currentTimeMillis())
                o += ENTRY + len.toInt()
            }
            if (o != bytes.size) bad("${bytes.size - o} bytes after the last project")
            return Unpacked(kind, entries)
        }

        private fun le16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        private fun le32(b: ByteArray, o: Int, v: Int) { for (k in 0 until 4) b[o + k] = (v ushr (8 * k)).toByte() }
        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
        private fun u32(b: ByteArray, o: Int): Long =
            (0 until 4).fold(0L) { a, k -> a or ((b[o + k].toLong() and 255) shl (8 * k)) }
    }
}
