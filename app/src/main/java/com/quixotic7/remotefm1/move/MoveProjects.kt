// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS ad33e95 RFMKit/Sources/RFMKit/Move/MoveProjects.swift (the store): the Ableton Move
// sequencer's 32 projects, kept apart from the firmwares' saves. One file a slot in the app's files/move:
// move-project-01.rfmmove .. -32, each in the web player's file format ("RMVP", rfm1cs-abletonmove's store.h; packed
// and read by its rfm1cs_file_* through JNI: MoveNative.FileCodec), so a file goes between the web, iOS and Android as
// it is. Writes go to a temporary file first, then take the name.
package com.quixotic7.remotefm1.move

import java.io.File

class MoveProject(val slot: Int, val bytes: ByteArray, val saved: Long /* ms since 1970, 0 unknown */) {
    val name: String get() = MoveProjects.projectName(slot)
}

/** the project file's writer and reader: MoveNative.FileCodec (rfm1cs_file_pack / _unpack, every blob checked by the
 *  core) */
interface MoveFileCodec {
    /** the file for these projects (kind: MoveProjects.KIND_ONE or KIND_SET) */
    fun pack(ps: List<MoveProject>, kind: Int = if (ps.size == 1) MoveProjects.KIND_ONE else MoveProjects.KIND_SET): ByteArray
    /** a file's projects (saved 0 in the file: now); throws IllegalArgumentException saying what is wrong */
    fun unpack(bytes: ByteArray?): MoveProjects.Unpacked
}

class MoveProjects(val dir: File, val codec: MoveFileCodec) {
    /** every project, by slot */
    @Synchronized fun list(): List<MoveProject> =
        (0 until SLOTS).mapNotNull { get(it) }

    /** the slot's project (null: none, or a file that is not one) */
    @Synchronized fun get(slot: Int): MoveProject? {
        if (!validSlot(slot)) return null
        val f = file(slot)
        if (!f.isFile) return null
        return try {
            val e = codec.unpack(f.readBytes()).entries.first()
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
        tmp.writeBytes(codec.pack(listOf(p), KIND_ONE))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "could not write ${f.name}" }
        }
        return p
    }

    @Synchronized fun remove(slot: Int) { if (validSlot(slot)) file(slot).delete() }

    @Synchronized fun dirBits(): Int = dirBits(list())

    private fun file(slot: Int) = File(dir, fileName(slot))

    /** a file's kind (KIND_ONE, KIND_SET) and projects */
    class Unpacked(val kind: Int, val entries: List<MoveProject>)

    companion object {
        const val SLOTS = 32
        const val KIND_ONE = 1                      // RFM1CS_FILE_ONE: one project (.rfmmove)
        const val KIND_SET = 2                      // RFM1CS_FILE_SET: a set (.rfmmoveset)
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
    }
}
