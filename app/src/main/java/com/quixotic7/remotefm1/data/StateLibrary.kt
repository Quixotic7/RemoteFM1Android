// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/StateLibrary.swift and StateFile.swift (the library's side:
// allStates, importState, importFile).
//
// Named states: <root>/<firmware id>/<uuid>.flash (the flash, LZFSE as on iOS; written by FlashFile: temp file,
// fsync, read back and compared, renamed in) and <uuid>.json (SavedState, iOS's fields and JSON). Root files/States.
// One pair of files per state and no index: a save writes its own new files (the flash first: an entry always names
// bytes that are there), a rename or note rewrites one entry atomically, a delete removes the entry, then the bytes.
// Every change tells DataChanges. The settings kept besides the flash: MASTER (the pot isn't in the flash),
// transpose and the keys' touch. Importing never overwrites: a state whose flash (by SHA-256) is already in the
// library for that firmware is skipped (the one there returned); an id already taken gets a new one.
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import java.io.File
import java.io.IOException
import java.util.UUID

/** one named state in the library */
data class SavedState(
    val id: String,                 // its files: <id>.json, <id>.flash
    val name: String,
    val note: String,
    val firmwareID: String,         // the firmware's own id
    val firmwareName: String,
    val kind: String,               // "native" | "wasm" (an imported .rfm1)
    val version: String,
    val sha256: String,             // an imported file's ("" for a built-in core)
    val date: Double,               // seconds since 1970
    val flashSize: Int,
    val flashSHA256: String,        // the uncompressed flash's
    val master: Int? = null,        // the MASTER pot, 0..1023; null: not known
    val transpose: Int? = null,     // the octave (iOS AUSettings.transpose)
    val keyTouch: Boolean? = null,  // the keys' touch (iOS AUSettings.keyTouch)
    val origin: String,             // "app" | "au" | "backup" | "safety copy": where it was saved (shown, nothing else)
) {
    /** "made with 1.6.1" when [version] isn't the running one ("" when it is, or unknown) */
    fun versionNote(running: String): String =
        if (version.isEmpty() || running.isEmpty() || version == running) "" else "made with $version"

    /** the question before a load: a state from another version of the firmware says so plainly */
    fun loadQuestion(running: String): String {
        val keep = "The current memory is kept as a safety copy first."
        if (versionNote(running).isEmpty()) return "Load \"$name\"? $keep"
        return "\"$name\" was made with version $version; this is $running. Another version may not read it, or " +
            "may reset it. Load it anyway? $keep"
    }

    /** the Load button's word: "Load", or "Load anyway" for another version's state */
    fun loadAction(running: String): String = if (versionNote(running).isEmpty()) "Load" else "Load anyway"

    /** the kept transpose, clamped to -2..2 (null: keep the player's) */
    val keptTranspose: Int? get() = transpose?.coerceIn(-2, 2)

    fun toJson(): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("id" to id, "name" to name, "note" to note, "firmwareID" to firmwareID,
            "firmwareName" to firmwareName, "kind" to kind, "version" to version, "sha256" to sha256, "date" to date,
            "flashSize" to flashSize, "flashSHA256" to flashSHA256, "origin" to origin)
        master?.let { m["master"] = it }                        // (Swift leaves a nil out)
        transpose?.let { m["transpose"] = it }
        keyTouch?.let { m["keyTouch"] = it }
        return m
    }

    companion object {
        fun fromJson(m: Map<String, Any?>): SavedState = JsonFields(m, "state").run {
            SavedState(str("id"), str("name"), str("note"), str("firmwareID"), str("firmwareName"), str("kind"),
                str("version"), str("sha256"), num("date"), int("flashSize"), str("flashSHA256"), intOrNull("master"),
                intOrNull("transpose"), boolOrNull("keyTouch"), str("origin"))
        }
    }
}

class StateLibrary(
    val root: File,
    val now: () -> Double = { DataDates.now() },
    val codec: FlashCodec = Lzfse,
) {
    class LibraryError(val why: Why) : IOException(why.text) {
        enum class Why(val text: String) {
            EMPTY("No memory to save."),
            MISSING("That state is no longer in the library."),
            DAMAGED("That state's memory can't be read."),
        }
    }

    data class Imported(val state: SavedState, val duplicate: Boolean)  // duplicate: already there (the library's)

    // ---- reading

    /** a firmware's states, newest first */
    fun list(firmwareID: String): List<SavedState> {
        val d = dir(firmwareID)
        val names = d.list()?.toList() ?: return emptyList()
        val set = names.toSet()
        val out = ArrayList<SavedState>()
        for (n in names) {
            if (!n.endsWith(".json") || n.startsWith(".")) continue
            val s = runCatching { SavedState.fromJson(Json.obj(File(d, n).readText())) }.getOrNull() ?: continue
            if ("${s.id}.flash" in set) out.add(s)                  // (a delete half done: not listed)
        }
        // bytes without an entry: a save cut short (an hour old; a save in progress has its entry within ms)
        for (n in names) {
            if (!n.endsWith(".flash") || n.removeSuffix(".flash") + ".json" in set) continue
            val f = File(d, n)
            if (now() - f.lastModified() / 1000.0 > 3600) f.delete()
        }
        return out.sortedWith(compareByDescending<SavedState> { it.date }.thenByDescending { it.id })
    }

    /** a state's flash, uncompressed and checked */
    fun flash(s: SavedState): ByteArray {
        val z = runCatching { flashFile(s).readBytes() }.getOrNull() ?: throw LibraryError(LibraryError.Why.MISSING)
        val raw = codec.decompress(z, s.flashSize)
        if (raw == null || raw.size != s.flashSize || FlashFile.sha256(raw) != s.flashSHA256)
            throw LibraryError(LibraryError.Why.DAMAGED)
        return raw
    }

    /** the entry as the library has it now (null: gone) */
    fun entry(s: SavedState): SavedState? =
        runCatching { SavedState.fromJson(Json.obj(entryFile(s).readText())) }.getOrNull()

    /** every state, every firmware */
    fun allStates(): List<SavedState> {
        val seen = HashSet<String>()
        val out = ArrayList<SavedState>()
        for (d in root.list()?.sorted() ?: emptyList()) {
            if (d.startsWith(".")) continue
            for (s in listDir(d)) if (seen.add(s.id)) out.add(s)
        }
        return out
    }

    private fun listDir(name: String): List<SavedState> {
        val d = File(root, name)
        val first = d.list()?.firstOrNull { it.endsWith(".json") && !it.startsWith(".") } ?: return emptyList()
        val s = runCatching { SavedState.fromJson(Json.obj(File(d, first).readText())) }.getOrNull() ?: return emptyList()
        return list(s.firmwareID)
    }

    // ---- writing

    /** a new state; its files written safely (the bytes first, checked), then the others told */
    fun save(name: String, note: String = "", firmwareID: String, firmwareName: String, kind: String, version: String,
             sha256: String = "", flash: ByteArray, master: Int? = null, transpose: Int? = null,
             keyTouch: Boolean? = null, origin: String): SavedState {
        if (flash.isEmpty() || firmwareID.isEmpty()) throw LibraryError(LibraryError.Why.EMPTY)
        val n = name.trim()
        val t = now()
        val s = SavedState(UUID.randomUUID().toString().uppercase(), n.ifEmpty { defaultName(firmwareName, t) }, note,
            firmwareID, firmwareName, kind, version, sha256, t, flash.size, FlashFile.sha256(flash), master, transpose,
            keyTouch, origin)
        add(s, flash)
        return s
    }

    /** (a save, an import) a state's files written: the bytes first, checked, then the entry; the others told */
    internal fun add(s: SavedState, flash: ByteArray) {
        val z = codec.compress(flash) ?: flash
        DataFiles.mkdirs(dir(s.firmwareID))                     // (first: two savers at once both find it there)
        FlashFile.write(z, flashFile(s), keepPrev = false)
        DataFiles.writeAtomic(entryFile(s), Json.write(s.toJson()).toByteArray())
        DataChanges.post()
    }

    /** a state renamed (and its note, when given) */
    fun rename(s: SavedState, name: String, note: String? = null): SavedState {
        var cur = entry(s) ?: throw LibraryError(LibraryError.Why.MISSING)   // (the newest entry: another's note kept)
        val n = name.trim()
        if (n.isNotEmpty()) cur = cur.copy(name = n)
        if (note != null) cur = cur.copy(note = note)
        if (!flashFile(s).exists()) throw LibraryError(LibraryError.Why.MISSING)
        DataFiles.writeAtomic(entryFile(cur), Json.write(cur.toJson()).toByteArray())
        DataChanges.post()
        return cur
    }

    /** a state removed (its entry, then its bytes) */
    fun delete(s: SavedState) {
        entryFile(s).delete()
        flashFile(s).delete()
        DataChanges.post()
    }

    /** a file's state into the library: its id kept when free; already there (the same flash for that firmware):
     *  nothing written, the library's returned */
    fun importState(f: StateFile.Decoded): Imported {
        if (f.flash.isEmpty() || f.state.firmwareID.isEmpty()) throw LibraryError(LibraryError.Why.EMPTY)
        val hash = FlashFile.sha256(f.flash)
        list(f.state.firmwareID).firstOrNull { it.flashSHA256 == hash }?.let { return Imported(it, true) }
        var s = f.state
        if (s.id.isEmpty() || entry(s) != null || s.id.contains("/") || s.id.contains("\\") || s.id.startsWith("."))
            s = s.copy(id = UUID.randomUUID().toString().uppercase())
        s = s.copy(flashSize = f.flash.size, flashSHA256 = hash)
        if (s.name.isBlank()) s = s.copy(name = defaultName(s.firmwareName, s.date))
        add(s, f.flash)
        return Imported(s, false)
    }

    /** a .rfm1state file's bytes imported (see importState) */
    fun importBytes(d: ByteArray): Imported = importState(StateFile.decode(d, codec))

    /** a .rfm1state file imported (see importState) */
    fun importFile(f: File): Imported = importBytes(f.readBytes())

    fun dir(firmwareID: String) = File(root, firmwareID.replace("/", "_"))
    private fun entryFile(s: SavedState) = File(dir(s.firmwareID), "${s.id}.json")
    internal fun flashFile(s: SavedState) = File(dir(s.firmwareID), "${s.id}.flash")

    companion object {
        /** files/States */
        fun defaultRoot(filesDir: File) = File(filesDir, "States")

        /** "<Firmware> <date>" */
        fun defaultName(firmware: String, date: Double = DataDates.now()): String =
            "${firmware.ifEmpty { "State" }} ${DataDates.short(date)}"
    }
}
