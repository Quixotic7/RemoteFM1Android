// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/TransferFile.swift (9494ebe).
//
// Send to another device (the owner, 2026-11-01: users copied files from one device to another and couldn't get the
// same state back). One .rfm1transfer file holds what another device needs:
//   every state in the library, as .rfm1state bytes
//   each firmware's current memory, as a state "Current memory from <device>" (the memory itself isn't in the files)
//   the .rfm1 file of each firmware those states were made with, when it is on this device (not the built-in ones)
// Layout: "RFM1XFER", the manifest's length (UInt32, little-endian), the manifest (JSON as iOS writes it: format, app,
// device, date in seconds since 1970, the entries: kind, name, size, sha256, current), then each entry's bytes in
// order. Read back: every length and checksum checked. Opened on the other device: the firmware files first (one
// already there is skipped), then the states (a state already in the library is skipped); the current memories are
// offered to load. The same bytes on iOS and Android: a file moves either way.
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import com.quixotic7.remotefm1.firmware.FirmwareLibrary
import com.quixotic7.remotefm1.firmware.ImportedFirmware
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

object TransferFile {
    const val EXTENSION = "rfm1transfer"
    /** iOS's UTI; on Android the type our own shares carry */
    const val TYPE_IDENTIFIER = "com.quixotic7.remotefm1.transfer"
    const val MIME = "application/vnd.quixotic7.remotefm1.transfer"
    const val FORMAT = 1
    private val MAGIC = "RFM1XFER".toByteArray(Charsets.US_ASCII)

    sealed class FileError(msg: String) : IOException(msg) {
        object NotTransfer : FileError("This isn't a RemoteFM1 transfer file.")
        data class Newer(val format: Int) :
            FileError("This file was made by a newer RemoteFM1 (format $format): update the app to open it.")
        object Damaged : FileError("This transfer file is damaged or cut short: send it again.")
    }

    class Decoded(
        val device: String = "",
        val app: String = "",
        val date: Double = 0.0,
        /** each state and whether it is a firmware's current memory */
        val states: List<Pair<StateFile.Decoded, Boolean>> = emptyList(),
        /** (file name, bytes) */
        val firmware: List<Pair<String, ByteArray>> = emptyList(),
    )

    /** a state to send: its flash, whether it is a current memory */
    class Item(val state: SavedState, val flash: ByteArray, val current: Boolean)

    /** the file's bytes */
    fun encode(states: List<Item>, firmware: List<Pair<String, ByteArray>>, device: String, app: String,
               date: Double = DataDates.now(), codec: FlashCodec = Lzfse): ByteArray {
        val entries = ArrayList<Map<String, Any?>>()
        val blobs = ArrayList<ByteArray>()
        for (s in states) {
            val b = StateFile.encode(s.state, s.flash, codec)
            entries.add(linkedMapOf("kind" to "state", "name" to s.state.name, "size" to b.size, "sha256" to FlashFile.sha256(b),
                "current" to s.current))
            blobs.add(b)
        }
        for ((n, d) in firmware) {
            entries.add(linkedMapOf("kind" to "firmware", "name" to n, "size" to d.size, "sha256" to FlashFile.sha256(d)))
            blobs.add(d)
        }
        val json = Json.write(linkedMapOf("format" to FORMAT, "app" to app, "device" to device, "date" to date, "entries" to entries))
            .toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(12 + json.size + blobs.sumOf { it.size })
        out.write(MAGIC)
        val n = json.size
        out.write(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()))
        out.write(json)
        for (b in blobs) out.write(b)
        return out.toByteArray()
    }

    fun decode(d: ByteArray, codec: FlashCodec = Lzfse): Decoded {
        if (d.size < MAGIC.size || !d.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw FileError.NotTransfer
        if (d.size < 12) throw FileError.Damaged
        val n = (d[8].toLong() and 0xFF) or ((d[9].toLong() and 0xFF) shl 8) or ((d[10].toLong() and 0xFF) shl 16) or
            ((d[11].toLong() and 0xFF) shl 24)
        if (n <= 0 || n > (16 shl 20) || d.size < 12 + n) throw FileError.Damaged
        val obj = runCatching { Json.obj(String(d, 12, n.toInt(), Charsets.UTF_8)) }.getOrNull() ?: throw FileError.Damaged
        val f = JsonFields(obj, "transfer")
        val format = runCatching { f.int("format") }.getOrNull() ?: throw FileError.Damaged
        if (format > FORMAT) throw FileError.Newer(format)
        @Suppress("UNCHECKED_CAST")
        val entries = runCatching {
            f.list("entries").map { e ->
                val x = JsonFields(e as Map<String, Any?>, "entry")
                Entry(x.str("kind"), x.str("name"), x.int("size"), x.str("sha256"), x.boolOrNull("current"))
            }
        }.getOrNull() ?: throw FileError.Damaged
        val device = runCatching { f.str("device") }.getOrNull() ?: throw FileError.Damaged
        val app = runCatching { f.str("app") }.getOrNull() ?: throw FileError.Damaged
        val date = runCatching { f.num("date") }.getOrNull() ?: throw FileError.Damaged
        var at = 12 + n.toInt()
        val states = ArrayList<Pair<StateFile.Decoded, Boolean>>()
        val firmware = ArrayList<Pair<String, ByteArray>>()
        for (e in entries) {
            if (e.size < 0 || d.size - at < e.size) throw FileError.Damaged
            val b = d.copyOfRange(at, at + e.size)
            at += e.size
            if (FlashFile.sha256(b) != e.sha256) throw FileError.Damaged
            when (e.kind) {
                "state" -> states.add(StateFile.decode(b, codec) to (e.current ?: false))
                "firmware" -> firmware.add(e.name to b)
                else -> {}                                  // (a kind from a newer app: left out)
            }
        }
        if (at != d.size) throw FileError.Damaged
        return Decoded(device, app, date, states, firmware)
    }

    private class Entry(val kind: String, val name: String, val size: Int, val sha256: String, val current: Boolean?)

    /**
     * The file for this device: every state, each current memory (a firmware's flash once; empty and erased ones left
     * out), and the .rfm1 files of the versions they were made with. [read]: an imported file's bytes (null: not read)
     */
    fun make(library: StateLibrary, current: List<Backup.Current>, files: List<ImportedFirmware>,
             read: (ImportedFirmware) -> ByteArray?, device: String, app: String, date: Double = DataDates.now()): ByteArray {
        val states = ArrayList<Item>()
        for (s in library.allStates().sortedWith(compareBy<SavedState>({ it.firmwareID }, { it.date }))) {
            runCatching { library.flash(s) }.getOrNull()?.let { states.add(Item(s, it, false)) }
        }
        val seen = HashSet<String>()
        for (c in current) {
            if (c.flash.isEmpty() || FlashFile.looksErased(c.flash) || !seen.add(FlashFile.sha256(c.flash))) continue
            val s = SavedState(UUID.randomUUID().toString().uppercase(), "Current memory from $device",
                "Sent ${DataDates.short(date)}", c.firmwareID, c.firmwareName, c.kind, c.version, c.sha256, date,
                c.flash.size, FlashFile.sha256(c.flash), origin = "transfer")
            states.add(Item(s, c.flash, true))
        }
        val wanted = states.map { "${it.state.firmwareID} ${it.state.version}" }.toSet()
        val firmware = ArrayList<Pair<String, ByteArray>>()
        val sent = HashSet<String>()
        for (f in files) {
            if (f.error != null || "${f.coreID} ${f.version}" !in wanted || !sent.add(f.sha256)) continue
            read(f)?.let { firmware.add(f.file.ifEmpty { "${f.coreID}-${f.version}.rfm1" } to it) }
        }
        return encode(states, firmware, device, app, date, library.codec)
    }

    /** what opening a file did */
    class Report(val device: String = "") {
        var statesAdded = 0
        var statesHere = 0
        val firmwareAdded = ArrayList<String>()
        var firmwareHere = 0
        val failed = ArrayList<String>()
        /** the current memories (the library's entries), to offer a load */
        val current = ArrayList<SavedState>()
        /** the states of firmware not on this device: "<name> <version>" */
        var missing: List<String> = emptyList()

        val text: String
            get() {
                var t = "From ${device.ifEmpty { "another device" }}: $statesAdded state${if (statesAdded == 1) "" else "s"} added"
                if (statesHere > 0) t += ", $statesHere already here"
                t += "."
                if (firmwareAdded.isNotEmpty()) t += " Added ${firmwareAdded.joinToString(", ")}."
                if (failed.isNotEmpty()) t += " Not read: ${failed.joinToString("; ")}."
                return t
            }
    }

    /** a decoded file into this device: the firmware files, then the states. [installed]: what is on this device then
     *  (firmware id and version), for the missing list. [tmpDir]: where firmware files are written before the import */
    fun importInto(d: Decoded, states: StateLibrary, firmware: FirmwareLibrary, tmpDir: File,
                   installed: () -> List<Pair<String, String>>): Report {
        val r = Report(d.device)
        tmpDir.mkdirs()
        val tmp = Files.createTempDirectory(tmpDir.toPath(), "rfm1-transfer-").toFile()
        try {
            for ((n, data) in d.firmware) {
                val sha = FlashFile.sha256(data)
                if (firmware.files.value.any { it.sha256 == sha && it.error == null }) { r.firmwareHere++; continue }
                var name = n.substringAfterLast('/')
                if (name.isEmpty() || name.startsWith(".") || !name.lowercase().endsWith(".rfm1")) name = "firmware-${sha.take(8)}.rfm1"
                try {
                    val u = File(tmp, name)
                    u.writeBytes(data)
                    val f = firmware.import(u)
                    if (f.error != null) {
                        runCatching { firmware.delete(f) }
                        r.failed.add("$name: ${f.error}")
                    } else r.firmwareAdded.add("${f.name} ${f.version}")
                } catch (e: Exception) {
                    r.failed.add("$name: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } finally {
            tmp.deleteRecursively()
        }
        for ((s, cur) in d.states) {
            try {
                val i = states.importState(s)
                if (i.duplicate) r.statesHere++ else r.statesAdded++
                if (cur) r.current.add(i.state)
            } catch (e: Exception) {
                r.failed.add("${s.state.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val ids = installed().map { it.first }.toSet()
        r.missing = d.states.map { it.first.state }.filter { it.firmwareID !in ids }
            .map { "${it.firmwareName.ifEmpty { it.firmwareID }} ${it.version}" }.toSortedSet().toList()
        return r
    }
}
