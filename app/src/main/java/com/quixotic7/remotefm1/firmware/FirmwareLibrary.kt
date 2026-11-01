// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Firmware.swift (ImportedFirmware, FirmwareDetails,
// FirmwareCatalog, FirmwareIndex, FirmwareLibrary).
//
// Imported firmware (PLAN.md A4): .rfm1 files the user adds ("Add firmware", "Open with RemoteFM1", a share, Get
// firmware). Android has no Files-app folder per app, so the app's files/Firmware is iOS's mirror: each valid file as
// <sha256>.rfm1 and index.json (format 1: sha256, id, name, version, license, file_size, added, file, as iOS writes
// it). An import copies the file in, checks it with the .rfm1 loader (RFMWasmCore.cpp, iOS's checks) and keeps it
// only when it is valid: a refused file is not kept (iOS keeps it in Documents with its error; here nothing would
// show it), the import returns its entry with the reason. "file" is the name it came with (a label: two files of
// one name become "x.rfm1" and "x 2.rfm1", as on iOS).
//
// Threads: every method may be called from any thread (one lock); a check costs an instantiation (tens to hundreds
// of ms), so call import and sync off the main thread. [files] and [message] are StateFlows for the UI.
package com.quixotic7.remotefm1.firmware

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** one .rfm1 file the app knows (valid: error null) */
data class ImportedFirmware(
    val sha256: String,
    val coreID: String,                         // the firmware's id ("felucca")
    val name: String,
    val version: String,
    val license: String,
    val fileSize: Long,
    val added: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS),
    val file: String = "",                      // the name it was added under ("" unknown)
    val error: String? = null,                  // why it can't be played (then it is not kept)
) {
    /** the catalog key (CoreInfo.id) */
    val key: String get() = FirmwareLibrary.KEY_PREFIX + sha256

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "sha256" to sha256, "id" to coreID, "name" to name, "version" to version, "license" to license,
        "file_size" to fileSize, "added" to added.truncatedTo(ChronoUnit.SECONDS).toString(), "file" to file,
    ).also { if (error != null) it["error"] = error }

    companion object {
        fun fromMap(m: Map<String, Any?>): ImportedFirmware? {
            val sha = m.str("sha256") ?: return null
            return ImportedFirmware(sha256 = sha, coreID = m.str("id") ?: return null, name = m.str("name") ?: "",
                version = m.str("version") ?: "", license = m.str("license") ?: "", fileSize = m.int("file_size") ?: 0,
                added = m.str("added")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
                file = m.str("file") ?: "", error = m.str("error"))
        }
    }
}

/** index.json */
object FirmwareIndex {
    fun encode(list: List<ImportedFirmware>): String =
        MiniJson.write(mapOf("format" to 1, "firmware" to list.map { it.toMap() }), pretty = true, sortKeys = true)

    fun decode(text: String): List<ImportedFirmware> {
        val j = MiniJson.parse(text).obj() ?: throw IOException("index.json: not an object")
        return j["firmware"].arr()?.mapNotNull { it.obj()?.let(ImportedFirmware::fromMap) } ?: throw IOException("index.json: no firmware")
    }
}

/** what an About screen shows of an imported file, from its manifest and sections */
data class FirmwareDetails(
    val authors: List<String> = emptyList(),
    val sourceURL: String = "",
    val glueURL: String = "",
    val builtBy: String = "",
    val credits: String = "",
    /** rfm1.license.<n>: the first line is the licence's name */
    val licenses: List<String> = emptyList(),
) {
    companion object {
        /** read from the file (empty when it isn't a valid .rfm1) */
        fun read(path: String, r: Rfm1Checker): FirmwareDetails {
            val m = runCatching { MiniJson.parse(r.text(path, "manifest")).obj() }.getOrNull()
            val lic = ArrayList<String>()
            for (i in 0 until 64) {
                val t = r.text(path, "license.$i")
                if (t.isEmpty()) break
                lic.add(t)
            }
            return FirmwareDetails(
                authors = m?.get("authors").arr()?.mapNotNull { it as? String } ?: emptyList(),
                sourceURL = m?.str("source_url") ?: "", glueURL = m?.str("glue_url") ?: "", builtBy = m?.str("built_by") ?: "",
                credits = r.text(path, "credits"), licenses = lic)
        }
    }
}

class FirmwareLibrary(
    /** files/Firmware */
    val dir: File,
    private val checker: Rfm1Checker,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val _files = MutableStateFlow<List<ImportedFirmware>>(emptyList())
    private val _message = MutableStateFlow("")
    private val details = HashMap<String, FirmwareDetails>()

    /** the valid files, in name order */
    val files: StateFlow<List<ImportedFirmware>> = _files
    /** the last import's or delete's line ("Added AMB-1 1.3", "x.rfm1: <why>", "Removed AMB-1 1.3") */
    val message: StateFlow<String> = _message

    val indexFile: File get() = File(dir, INDEX)

    fun path(sha256: String): File = File(dir, "$sha256.rfm1")

    /** the valid file of a catalog key */
    fun entry(key: String): ImportedFirmware? = _files.value.firstOrNull { it.key == key }

    /** the index read again and checked against the folder: entries whose file went are dropped; a <sha256>.rfm1
     *  not in the index (an interrupted import) is checked and added, or removed when refused */
    fun sync(): List<ImportedFirmware> = synchronized(lock) {
        dir.mkdirs()
        val old = runCatching { FirmwareIndex.decode(indexFile.readText()) }.getOrElse { emptyList() }
        val list = old.filter { it.error == null && path(it.sha256).isFile }.distinctBy { it.sha256 }.toMutableList()
        val known = list.map { it.sha256 }.toSet()
        for (f in dir.listFiles().orEmpty()) {
            val n = f.name
            if (n.startsWith(".import-")) { f.delete(); continue }      // (a copy cut off)
            if (!n.endsWith(".rfm1") || n.removeSuffix(".rfm1") in known) continue
            val r = checker.check(f.path)
            val info = r.getOrNull()
            if (info == null || info.sha256 != n.removeSuffix(".rfm1")) {
                log("sync: ${f.name} removed: ${r.exceptionOrNull()?.message ?: "its hash is not its name"}")
                f.delete()
                continue
            }
            list.add(entryOf(info, f.length(), uniqueName("${info.id}-${info.version}.rfm1", list)))
        }
        // (the .tmp of an index write cut off)
        File(dir, "$INDEX.tmp").delete()
        val sorted = sortedFiles(list)
        if (sorted != old) writeIndex(sorted)
        _files.value = sorted
        log("synced ${sorted.size} file(s) in ${dir.path}")
        sorted
    }

    /**
     * A file from elsewhere (a picked document, "Open with", a share, a download) copied in and checked. [name]: the
     * name it came with. Returns its entry (error set when it isn't a valid .rfm1: nothing is kept then).
     */
    fun import(input: InputStream, name: String): ImportedFirmware = synchronized(lock) {
        dir.mkdirs()
        val label = (name.substringAfterLast('/').ifBlank { "firmware.rfm1" }).let { if (it.lowercase().endsWith(".rfm1")) it else "$it.rfm1" }
        val tmp = File(dir, ".import-${UUID.randomUUID().toString().take(8)}.rfm1")
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > MAX_SIZE) throw IOException("larger than 64 MiB")
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
                out.fd.sync()
            }
        } catch (e: IOException) {
            tmp.delete()
            val f = ImportedFirmware("", "", label, "", "", size, file = label, error = "can't read it (${e.message})")
            _message.value = "$label: ${f.error}"
            return f
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        _files.value.firstOrNull { it.sha256 == sha }?.let { have ->
            tmp.delete()
            _message.value = "${have.file} is already here"
            return have
        }
        val r = checker.check(tmp.path)
        val info = r.getOrNull()
        if (info == null) {
            tmp.delete()
            val why = (r.exceptionOrNull()?.message ?: "not a valid .rfm1 file").removePrefix("${tmp.name}: ")
            val f = ImportedFirmware(sha, "", label, "", "", size, file = label, error = why)
            _message.value = "$label: $why"
            log("import $label: refused: $why")
            return f
        }
        val dst = path(sha)
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            val f = ImportedFirmware(sha, info.id, info.name, info.version, info.license, size, file = label,
                error = "couldn't be stored")
            _message.value = "$label: couldn't be stored"
            return f
        }
        val f = entryOf(info, size, uniqueName(label, _files.value))
        val list = sortedFiles(_files.value + f)
        writeIndex(list)
        _files.value = list
        _message.value = "Added ${f.name} ${f.version}"
        log("imported $label: ${_message.value}")
        f
    }

    fun import(file: File): ImportedFirmware = file.inputStream().use { import(it, file.name) }

    /** the file removed (and its index entry) */
    fun delete(f: ImportedFirmware) = synchronized(lock) {
        if (f.sha256.isNotEmpty()) path(f.sha256).delete()
        val list = _files.value.filter { it.sha256 != f.sha256 }
        writeIndex(list)
        _files.value = list
        details.remove(f.sha256)
        _message.value = "Removed ${if (f.error == null) "${f.name} ${f.version}" else f.file}"
        log(_message.value)
    }

    /** an entry's manifest, credits and licence texts (read once per file) */
    fun details(sha256: String): FirmwareDetails = synchronized(lock) {
        details.getOrPut(sha256) { if (path(sha256).isFile) FirmwareDetails.read(path(sha256).path, checker) else FirmwareDetails() }
    }

    /** the valid files as catalog cores (name, version, SHA-256 order) */
    fun cores(): List<CoreInfo> = _files.value.map { f ->
        CoreInfo(id = f.key, kind = "wasm", coreID = f.coreID, name = f.name, version = f.version, license = f.license,
            sha256 = f.sha256, path = path(f.sha256).path, fileSize = f.fileSize, fileName = f.file)
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.version }, { it.sha256 }))

    private fun entryOf(info: Rfm1Info, size: Long, file: String) =
        ImportedFirmware(info.sha256, info.id, info.name, info.version, info.license, size, file = file)

    private fun sortedFiles(l: List<ImportedFirmware>) =
        l.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: ImportedFirmware -> it.file }.thenBy { it.sha256 })

    /** "x.rfm1", else "x 2.rfm1", "x 3.rfm1" (as iOS names copies in its folder) */
    private fun uniqueName(n: String, have: List<ImportedFirmware>): String {
        val taken = have.map { it.file.lowercase() }.toSet()
        if (n.lowercase() !in taken) return n
        val stem = n.dropLast(5)
        var i = 2
        while ("$stem $i.rfm1".lowercase() in taken) i++
        return "$stem $i.rfm1"
    }

    private fun writeIndex(list: List<ImportedFirmware>) {
        val tmp = File(dir, "$INDEX.tmp")
        tmp.outputStream().use {
            it.write(FirmwareIndex.encode(list).toByteArray())
            it.fd.sync()
        }
        if (!tmp.renameTo(indexFile)) throw IOException("index.json: rename failed")
    }

    companion object {
        const val KEY_PREFIX = "rfm1:"
        const val INDEX = "index.json"
        /** the most an .rfm1 may be (the format's memory cap is 32 MiB) */
        const val MAX_SIZE = 64L shl 20

        fun isKey(key: String) = key.startsWith(KEY_PREFIX)
    }
}
