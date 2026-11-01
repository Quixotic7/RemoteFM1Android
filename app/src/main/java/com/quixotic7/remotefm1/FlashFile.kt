// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMKit/FlashHistory.swift (FlashFile only; FlashHistory is A5).
//
// Safe writes of one flash file: a temp file beside it, fsync, read back and compared (length and SHA-256), the old
// file kept as <name>.prev, the temp file renamed in. A sidecar <name without .bin>.json says which firmware version
// wrote it, with iOS's field names and format (firmwareID, version, size, sha256, date in seconds since 1970; sorted
// keys), so files and backups move between the two apps. A read that finds the file missing or damaged (unreadable,
// not the firmware's flash size) uses <name>.prev and says so. Pure Kotlin (java.io): tested on the JVM.
package com.quixotic7.remotefm1

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest

object FlashFile {
    /** which firmware wrote a flash file (the sidecar) */
    data class Meta(val firmwareID: String, val version: String, val size: Int, val sha256: String, val date: Double)

    /** [data] null: none (a fresh flash); [note]: what went wrong with the main file ("" when nothing did) */
    class Read(val data: ByteArray?, val fromPrev: Boolean = false, val note: String = "")

    class VerifyError(msg: String) : IOException(msg)

    fun prevFile(f: File) = File(f.path + ".prev")
    fun metaFile(f: File) = File(f.parentFile, f.name.substringBeforeLast('.', f.name) + ".json")

    fun sha256(d: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it) }

    /** a flash image with (almost) nothing in it: at least 99 % of its bytes erased (0xFF) or zero */
    fun looksErased(d: ByteArray): Boolean {
        if (d.isEmpty()) return true
        val blank = d.count { it == 0xFF.toByte() || it == 0.toByte() }
        return blank.toLong() * 100 >= d.size.toLong() * 99
    }

    /** [data] written safely to [f]; the sidecar's [firmwareID] and [version] (null: the sidecar left as it is) */
    @Throws(IOException::class)
    fun write(data: ByteArray, f: File, firmwareID: String? = null, version: String? = null,
              now: Double = System.currentTimeMillis() / 1000.0, keepPrev: Boolean = true) {
        val dir = f.absoluteFile.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot make $dir")
        val tmp = File(dir, ".${f.name}.tmp")
        tmp.delete()
        try {
            FileOutputStream(tmp).use { o ->
                o.write(data)
                o.flush()
                o.fd.sync()                                         // (fsync)
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        val back = tmp.readBytes()
        val hash = sha256(data)
        if (back.size != data.size || sha256(back) != hash) {
            tmp.delete()
            throw VerifyError("${f.name}: read back ${back.size} of ${data.size} bytes, not the same")
        }
        val prev = prevFile(f)
        if (keepPrev && f.exists()) {                               // (the previous good file kept)
            prev.delete()
            if (!f.renameTo(prev)) throw IOException("cannot keep ${f.name} as ${prev.name}")
        }
        if (!tmp.renameTo(f)) {                                     // (rename replaces on Android and Linux)
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("cannot rename ${tmp.name} to ${f.name}")
        }
        if (firmwareID != null && version != null) {
            runCatching { writeMeta(Meta(firmwareID, version, data.size, hash, now), f) }
        }
    }

    fun meta(f: File): Meta? = runCatching { decode(metaFile(f).readText()) }.getOrNull()

    /** the sidecar written whole (a temp file renamed in) */
    @Throws(IOException::class)
    fun writeMeta(m: Meta, f: File) {
        val mf = metaFile(f)
        val tmp = File(mf.parentFile, ".${mf.name}.tmp")
        tmp.writeText(encode(m))
        if (!tmp.renameTo(mf)) {
            mf.delete()
            if (!tmp.renameTo(mf)) throw IOException("cannot write ${mf.name}")
        }
    }

    /** the flash in [f]; [expectedSize]: the firmware's flash size (0: any non-empty file). Missing or damaged: .prev, with a note */
    fun read(f: File, expectedSize: Int): Read {
        fun good(d: ByteArray?) = d != null && d.isNotEmpty() && (expectedSize <= 0 || d.size == expectedSize)
        val main = if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
        if (good(main)) return Read(main)
        val exists = f.exists()
        val what = when {
            !exists -> "missing"
            main == null -> "unreadable"
            else -> "damaged (${main.size} bytes, not $expectedSize)"
        }
        val pf = prevFile(f)
        val prev = if (pf.isFile) runCatching { pf.readBytes() }.getOrNull() else null
        if (good(prev)) return Read(prev, true, "The saved memory was $what: started from the copy saved before it.")
        if (!exists && prev == null) return Read(null)              // (never saved: a fresh flash)
        return Read(null, false, "The saved memory was $what and no earlier copy was usable: a fresh memory.")
    }

    /** the file, its .prev and its sidecar removed (a reset) */
    fun remove(f: File) {
        listOf(f, prevFile(f), metaFile(f)).forEach { it.delete() }
    }

    // ---- the sidecar's JSON, as Swift's JSONEncoder writes it (.prettyPrinted, .sortedKeys, dates as seconds)
    fun encode(m: Meta): String = buildString {
        append("{\n")
        append("  \"date\" : ").append(number(m.date)).append(",\n")
        append("  \"firmwareID\" : ").append(quote(m.firmwareID)).append(",\n")
        append("  \"sha256\" : ").append(quote(m.sha256)).append(",\n")
        append("  \"size\" : ").append(m.size).append(",\n")
        append("  \"version\" : ").append(quote(m.version)).append("\n")
        append("}")
    }

    private fun number(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else BigDecimal.valueOf(d).toPlainString()

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '/' -> append("\\/")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    /** a flat JSON object of strings and numbers (what a sidecar is); throws on anything else */
    fun decode(text: String): Meta {
        val v = HashMap<String, Any>()
        var i = 0
        fun ws() { while (i < text.length && text[i].isWhitespace()) i++ }
        fun expect(c: Char) { ws(); if (i >= text.length || text[i] != c) throw IOException("sidecar: '$c' expected at $i"); i++ }
        fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                if (i >= text.length) throw IOException("sidecar: an unterminated string")
                val c = text[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = text[i++]
                        when (e) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> { b.append(text.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
        expect('{')
        ws()
        if (text.getOrNull(i) == '}') i++ else while (true) {
            val k = str()
            expect(':')
            ws()
            v[k] = if (text.getOrNull(i) == '"') str() else {
                val s = i
                while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
                text.substring(s, i).toDoubleOrNull() ?: throw IOException("sidecar: a value expected for $k")
            }
            ws()
            if (text.getOrNull(i) == ',') { i++; continue }
            expect('}')
            break
        }
        fun s(k: String) = v[k] as? String ?: throw IOException("sidecar: no $k")
        fun n(k: String) = v[k] as? Double ?: throw IOException("sidecar: no $k")
        return Meta(s("firmwareID"), s("version"), n("size").toInt(), s("sha256"), n("date"))
    }
}
