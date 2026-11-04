// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/CorePlayer.swift (the data part: write's rolling copy,
// checkVersion, adoptBuiltInFlash, safetyCopy, resetFlash, restore, noteSave / wipedCopy / answerWiped, saveState,
// loadState, currentFlashes) as plain functions the player calls; the core itself (unload, boot, MASTER) stays the
// player's (AppModel with CoreFlash).
//
// The flash file of a catalog key is CoreFlash's files/cores/<key>.bin. A firmware is told by [firmwareOf]: its own id
// (the history's and the library's folder), name, kind, version, the .rfm1's hash, its flash size.
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import java.io.File

class DataSafety(
    val history: FlashHistory,
    val library: StateLibrary,
    /** a catalog key's flash file (CoreFlash.file) */
    private val flashFile: (String) -> File,
    /** a catalog key's firmware (null: not in the catalog) */
    private val firmwareOf: (String) -> Firmware?,
) {
    data class Firmware(val key: String, val firmwareID: String, val name: String, val kind: String = "native",
                        val version: String, val sha256: String = "", val flashSize: Int = 0)

    /** what a load writes: the flash, then MASTER and the kept settings (null: leave the player's) */
    data class Load(val state: SavedState, val flash: ByteArray, val master: Int?, val transpose: Int?,
                    val keyTouch: Boolean?, val message: String)

    /** a refused step, in plain words (the player shows it as its message) */
    class Refused(msg: String) : Exception(msg)

    private val lock = Any()
    private var watch: Triple<String, FlashHistory.Snapshot, Int>? = null     // (key, the copy, saves left)

    /** (the wiped-memory card) the copy from before a version change the new version seems to have erased */
    @Volatile var wipedCopy: FlashHistory.Snapshot? = null
        private set

    // ---- hooks on the flash's way

    /** (CoreFlash.onWritten, the flash thread) a written flash: a rolling safety copy, and the wipe watch fed */
    fun written(key: String, data: ByteArray) {
        noteSave(key, data)
        val f = firmwareOf(key)
        history.record(data, f?.firmwareID ?: key, f?.version ?: "", FlashHistory.Reason.Rolling)
    }

    /**
     * (before CoreFlash.boot) the flash file was written by another version of this firmware: a copy first ("before
     * update a→b"; a file from before the sidecars: "first safety copy"), the sidecar then names this version. A real
     * version change of a memory that held something: the next 6 saves are watched (wipedCopy).
     */
    fun checkVersion(key: String) {
        val f = firmwareOf(key) ?: return
        val file = flashFile(key)
        val bytes = FlashFile.read(file, 0).data ?: return
        val m = FlashFile.meta(file)
        if (m?.version == f.version) return
        val reason = if (m != null) FlashHistory.Reason.BeforeUpdate(m.version, f.version) else FlashHistory.Reason.FirstCopy
        val copy = history.record(bytes, f.firmwareID, m?.version ?: "", reason)
        if (m != null && copy != null && !FlashFile.looksErased(bytes)) synchronized(lock) { watch = Triple(key, copy, 6) }
        DataFiles.log("flash ${file.name}: ${reason.text}: a safety copy")
        runCatching {
            FlashFile.writeMeta(FlashFile.Meta(f.firmwareID, f.version, bytes.size, FlashFile.sha256(bytes), DataDates.now()), file)
        }
    }

    /** (a save) the first few after a version change: an (almost) erased memory means the new version reset it */
    fun noteSave(key: String, data: ByteArray) {
        synchronized(lock) {
            val w = watch ?: return
            if (w.first != key) return
            watch = if (w.third > 1) w.copy(third = w.third - 1) else null
            if (FlashFile.looksErased(data)) {
                watch = null
                wipedCopy = w.second
                DataChanges.post()
                DataFiles.log("flash $key: wiped after the update; offering ${w.second.id}")
            }
        }
    }

    /** the wiped-memory card answered: the copy to restore (Restore it), or null (Keep the new one) */
    fun answerWiped(restore: Boolean): FlashHistory.Snapshot? {
        val c = wipedCopy ?: return null
        wipedCopy = null
        DataChanges.post()
        return if (restore) c else null
    }

    /**
     * (a boot of an imported firmware with no memory of its own) a built-in core of the same firmware that this build
     * no longer has left its memory as <firmware id>.bin: it becomes this one's (copied, the old file kept, a safety
     * copy taken). [hasBuiltIn]: the catalog still has that built-in core (then nothing is done).
     */
    fun adoptBuiltInFlash(f: Firmware, hasBuiltIn: Boolean) {
        val to = flashFile(f.key)
        if (f.kind != "wasm" || to.exists() || hasBuiltIn) return
        val old = File(to.parentFile, "${f.firmwareID}.bin")
        val d = FlashFile.read(old, 0).data?.takeIf { it.isNotEmpty() } ?: return
        val from = FlashFile.meta(old)?.version ?: ""
        history.record(d, f.firmwareID, from,
            FlashHistory.Reason.BeforeUpdate(if (from.isEmpty()) "built in" else "built in $from", f.version))
        runCatching { FlashFile.write(d, to, f.firmwareID, from) }
            .onFailure { DataFiles.log("flash: adopting ${old.name}: $it") }
    }

    // ---- risky steps (the player saves the running flash first: CoreFlash.saveNow, then flush)

    /** a safety copy of a key's saved flash; null: none to copy (or the same as the newest, unless an event) */
    fun safetyCopy(key: String, reason: FlashHistory.Reason): FlashHistory.Snapshot? {
        val file = flashFile(key)
        val d = FlashFile.read(file, 0).data ?: return null
        val f = firmwareOf(key)
        return history.record(d, f?.firmwareID ?: key, FlashFile.meta(file)?.version ?: f?.version ?: "", reason)
    }

    /** the safety copies of a key's firmware, newest first */
    fun safetyCopies(key: String): List<FlashHistory.Snapshot> =
        history.list(firmwareOf(key)?.firmwareID ?: key)

    /**
     * Reset firmware…: a safety copy ("before reset"), then the flash file, .prev and sidecar removed. The player
     * unloads the core before (CoreFlash.unload, flush) and boots it fresh after. Returns the copy (null: none).
     */
    fun reset(key: String): FlashHistory.Snapshot? {
        val copy = safetyCopy(key, FlashHistory.Reason.BeforeReset)
        FlashFile.remove(flashFile(key))
        return copy
    }

    fun resetMessage(key: String) =
        "${firmwareOf(key)?.name ?: key} started with a clean memory; a copy of the old one is in Safety copies."

    /** a safety copy's bytes to restore into [key] (a "before restore" copy of the current flash taken first) */
    fun prepareRestore(key: String, s: FlashHistory.Snapshot): ByteArray {
        val f = firmwareOf(key)
        val d = if (f != null && s.firmwareID == f.firmwareID) history.data(s) else null
        d ?: throw Refused("That safety copy can't be read.")
        safetyCopy(key, FlashHistory.Reason.BeforeRestore)
        return d
    }

    fun restoreMessage(s: FlashHistory.Snapshot) = "Restored the copy of ${DataDates.short(s.date)} (${s.reason})."

    /**
     * (the player, after unloading the core: CoreFlash.unload, flush) [d] written as [key]'s flash, the sidecar this
     * version's; then the player boots it. Used by a restore and a state's load.
     */
    fun writeFlash(key: String, d: ByteArray) {
        val f = firmwareOf(key)
        FlashFile.write(d, flashFile(key), f?.firmwareID ?: key, f?.version ?: "")
    }

    // ---- named states

    /** the states of a key's firmware, newest first */
    fun states(key: String): List<SavedState> = library.list(firmwareOf(key)?.firmwareID ?: key)

    /** the key's saved flash (saved first by the player) as a new state */
    fun saveState(key: String, name: String, note: String = "", master: Int? = null, transpose: Int? = null,
                  keyTouch: Boolean? = null): SavedState {
        val f = firmwareOf(key) ?: throw Refused("No firmware to save.")
        val d = FlashFile.read(flashFile(key), 0).data ?: throw Refused("${f.name} has no memory saved yet: play it first.")
        return library.save(name, note, f.firmwareID, f.name, f.kind, f.version, f.sha256, d, master, transpose, keyTouch,
            origin = "app")
    }

    fun savedMessage(s: SavedState) = "Saved \"${s.name}\"."

    /**
     * A state's load prepared: another firmware's or a wrong-size flash refused, the flash read and checked, then a
     * "before load state" safety copy of the current memory. The player then unloads, writeFlash(key, flash), boots,
     * sends MASTER (RFMC_EV_MASTER) and sets the transpose and the keys' touch, and shows [Load.message].
     */
    fun prepareLoad(key: String, s: SavedState): Load {
        val f = firmwareOf(key)
        if (f == null || s.firmwareID != f.firmwareID) throw Refused("That state is for another firmware.")
        val d = try { library.flash(s) } catch (e: Exception) { throw Refused(e.message ?: "That state's memory can't be read.") }
        if (f.flashSize > 0 && d.size != f.flashSize)
            throw Refused("That state's memory is ${d.size} bytes; ${f.name} ${f.version}'s is ${f.flashSize}.")
        safetyCopy(key, FlashHistory.Reason.BeforeLoadState)
        val v = s.versionNote(f.version)
        val msg = "Loaded \"${s.name}\"" + (if (v.isEmpty()) "." else " ($v).") + " The memory before it is in Safety copies."
        return Load(s, d, s.master?.coerceIn(0, 1023), s.keptTranspose, s.keyTouch, msg)
    }

    /** (Back up everything) every key's saved memory as it is now (the player saves the running one first) */
    fun currentFlashes(keys: List<String>): List<Backup.Current> = keys.mapNotNull { k ->
        val f = firmwareOf(k) ?: return@mapNotNull null
        val file = flashFile(k)
        val d = FlashFile.read(file, 0).data ?: return@mapNotNull null
        // (iOS 9494ebe: the versions of an imported firmware share one memory: the version that last wrote it)
        val v = FlashFile.meta(file)?.version ?: ""
        Backup.Current(f.firmwareID, f.name, f.kind, v.ifEmpty { f.version }, f.sha256, d)
    }
}
