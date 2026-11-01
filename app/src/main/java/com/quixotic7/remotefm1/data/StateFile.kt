// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/StateFile.swift (StateFile; docs/RFM1STATE-FORMAT.md).
//
// One .rfm1state file: "RFM1STAT", the manifest's length (UInt32, little-endian, 1 ... 1 MiB), the manifest (JSON:
// format 1, the SavedState, the flash's encoding "lzfse" | "none" and its stored length), the flash. Read back:
// every length, the manifest, the decompressed size and SHA-256 checked; a bad file refused in plain words.
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import java.io.File
import java.io.IOException

object StateFile {
    const val EXTENSION = "rfm1state"
    /** iOS's UTI; on Android the type our own shares carry */
    const val TYPE_IDENTIFIER = "com.quixotic7.remotefm1.state"
    const val MIME = "application/vnd.quixotic7.remotefm1.state"
    const val FORMAT = 1
    private val MAGIC = "RFM1STAT".toByteArray(Charsets.US_ASCII)
    private const val MAX_MANIFEST = 1 shl 20

    /** a bad file, in plain words (iOS's FileError) */
    sealed class FileError(msg: String) : IOException(msg) {
        object NotState : FileError("This isn't a RemoteFM1 state file.")
        data class Truncated(val have: Int, val need: Int) :
            FileError("This state file is cut short ($have of $need bytes): it may not have finished copying.")
        data class Newer(val format: Int) :
            FileError("This state file was made by a newer RemoteFM1 (format $format): update the app to open it.")
        object BadManifest : FileError("This state file's description can't be read: it's damaged.")
        object Damaged : FileError("This state file's memory doesn't match its checksum: it's damaged.")
    }

    internal data class Manifest(val format: Int, val state: SavedState, val flashEncoding: String, val flashBytes: Int)

    /** a state read from a file */
    class Decoded(val state: SavedState, val flash: ByteArray)

    /** the file's bytes */
    fun encode(s: SavedState, flash: ByteArray, codec: FlashCodec = Lzfse): ByteArray {
        val st = s.copy(flashSize = flash.size, flashSHA256 = FlashFile.sha256(flash))
        val z = codec.compress(flash)
        val body = z ?: flash
        val m = mapOf("format" to FORMAT, "state" to st.toJson(), "flashEncoding" to (if (z == null) "none" else "lzfse"),
            "flashBytes" to body.size)
        val json = Json.write(m).toByteArray(Charsets.UTF_8)
        val out = ByteArray(12 + json.size + body.size)
        MAGIC.copyInto(out)
        val n = json.size
        out[8] = n.toByte(); out[9] = (n ushr 8).toByte(); out[10] = (n ushr 16).toByte(); out[11] = (n ushr 24).toByte()
        json.copyInto(out, 12)
        body.copyInto(out, 12 + json.size)
        return out
    }

    /** the manifest and where the flash starts (the flash not read) */
    internal fun manifest(d: ByteArray): Pair<Manifest, Int> {
        if (d.size < MAGIC.size || !d.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw FileError.NotState
        if (d.size < 12) throw FileError.Truncated(d.size, 12)
        val n = (d[8].toLong() and 0xFF) or ((d[9].toLong() and 0xFF) shl 8) or ((d[10].toLong() and 0xFF) shl 16) or
            ((d[11].toLong() and 0xFF) shl 24)
        if (n <= 0 || n > MAX_MANIFEST) throw FileError.BadManifest
        val end = 12 + n.toInt()
        if (d.size < end) throw FileError.Truncated(d.size, end)
        val text = String(d, 12, n.toInt(), Charsets.UTF_8)
        val obj = runCatching { Json.obj(text) }.getOrNull()
        val m = runCatching {
            val f = JsonFields(obj!!, "manifest")
            @Suppress("UNCHECKED_CAST")
            Manifest(f.int("format"), SavedState.fromJson(f.objOrNull("state")!!), f.str("flashEncoding"), f.int("flashBytes"))
        }.getOrElse {
            val f = (obj?.get("format") as? Long)?.toInt()
            if (f != null && f > FORMAT) throw FileError.Newer(f)
            throw FileError.BadManifest
        }
        if (m.format > FORMAT) throw FileError.Newer(m.format)
        return m to end
    }

    /** the state id a file names (the manifest only), or its FileError */
    fun stateID(d: ByteArray): String = manifest(d).first.state.id

    /** a file's state and flash, everything checked */
    fun decode(d: ByteArray, codec: FlashCodec = Lzfse): Decoded {
        val (m, at) = manifest(d)
        if (m.flashBytes < 0) throw FileError.BadManifest
        val need = at.toLong() + m.flashBytes
        if (d.size < need) throw FileError.Truncated(d.size, need.toInt())
        if (d.size.toLong() != need || m.state.flashSize <= 0) throw FileError.Damaged
        val body = d.copyOfRange(at, need.toInt())
        val raw = when (m.flashEncoding) {
            "none" -> body
            "lzfse" -> codec.decompress(body, m.state.flashSize) ?: throw FileError.Damaged
            else -> throw FileError.BadManifest
        }
        if (raw.size != m.state.flashSize || FlashFile.sha256(raw) != m.state.flashSHA256) throw FileError.Damaged
        return Decoded(m.state, raw)
    }

    fun read(f: File, codec: FlashCodec = Lzfse): Decoded = decode(f.readBytes(), codec)

    /** a name usable as a file name ("/" and ":" replaced; empty: "State") */
    fun safeName(s: String): String {
        var n = s.replace("/", "-").replace(":", "-").trim()
        while (n.startsWith(".")) n = n.substring(1)
        if (n.isEmpty()) return "State"
        return if (n.codePointCount(0, n.length) <= 120) n else n.substring(0, n.offsetByCodePoints(0, 120))
    }

    /** a <name>.rfm1state of a library state in [cacheDir]/share/<id>/ (to share through the FileProvider) */
    fun temporaryFile(s: SavedState, library: StateLibrary, cacheDir: File): File {
        val d = File(cacheDir, "share/${s.id}")
        d.listFiles()?.forEach { it.delete() }
        if (!d.isDirectory && !d.mkdirs()) throw IOException("cannot make $d")
        val f = File(d, "${safeName(s.name)}.$EXTENSION")
        DataFiles.writeAtomic(f, encode(s, library.flash(s), library.codec))
        return f
    }
}
