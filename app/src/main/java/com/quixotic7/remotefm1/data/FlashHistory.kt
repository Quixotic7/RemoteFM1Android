// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/FlashHistory.swift (FlashHistory; FlashFile is
// ../FlashFile.kt, A2).
//
// The safety copies of each firmware's flash: <root>/<id>/<uuid>.bin and <uuid>.json (one file pair per copy, no
// index; root files/FlashHistory). Copies taken before something risky (an update, a delete, a reset, a restore,
// loading a state, a web manager's write) are kept (the newest 30); rolling copies of the saves: the last 10 distinct
// ones and the newest of each day for 7 days ("daily"), at most one every 5 minutes. A copy identical to the newest
// one is not kept twice (a risky step's copy relabels it). Entries have iOS's fields and JSON, so a copy's files
// mean the same on both. Thread-safe; any thread. (iOS's move of step 1's history into the App Group isn't needed
// here; a step-1 index.json found in a folder is still split when read.)
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class FlashHistory(
    val root: File,
    val now: () -> Double = { DataDates.now() },
) {
    enum class Kind(val raw: String) { EVENT("event"), ROLLING("rolling"), DAILY("daily");
        companion object { fun of(s: String) = entries.firstOrNull { it.raw == s } ?: throw JsonError("snapshot: kind $s") }
    }

    /** why a copy was taken */
    sealed class Reason {
        object Rolling : Reason()
        data class BeforeUpdate(val from: String, val to: String) : Reason()
        data class BeforeDelete(val name: String) : Reason()
        object BeforeReset : Reason()
        object BeforeRestore : Reason()
        object BeforeLoadState : Reason()
        object BeforeManagerWrite : Reason()
        /** a flash saved before the sidecars (no version known) */
        object FirstCopy : Reason()
        data class Other(val s: String) : Reason()

        val text: String
            get() = when (this) {
                Rolling -> "rolling"
                is BeforeUpdate -> "before update ${from.ifEmpty { "?" }}→${to.ifEmpty { "?" }}"
                is BeforeDelete -> if (name.isEmpty()) "before delete" else "before deleting $name"
                BeforeReset -> "before reset"
                BeforeRestore -> "before restore"
                BeforeLoadState -> "before load state"
                BeforeManagerWrite -> "before manager write"
                FirstCopy -> "first safety copy"
                is Other -> s
            }

        val kind: Kind get() = if (this == Rolling) Kind.ROLLING else Kind.EVENT
    }

    data class Snapshot(
        val id: String,             // its file: <id>.bin
        val kind: Kind,
        val reason: String,         // "rolling", "daily", "before update 1.6.1→1.6.2", ...
        val firmwareID: String,
        val version: String,
        val date: Double,           // seconds since 1970
        val size: Int,
        val sha256: String,
    ) {
        fun toJson(): Map<String, Any?> = mapOf("id" to id, "kind" to kind.raw, "reason" to reason,
            "firmwareID" to firmwareID, "version" to version, "date" to date, "size" to size, "sha256" to sha256)

        companion object {
            fun fromJson(m: Map<String, Any?>): Snapshot = JsonFields(m, "snapshot").run {
                Snapshot(str("id"), Kind.of(str("kind")), str("reason"), str("firmwareID"), str("version"), num("date"),
                    int("size"), str("sha256"))
            }
        }
    }

    var keepRolling = 10
    var keepDays = 7
    var keepEvents = 30
    /** rolling copies at most this often, in seconds (the clock saves every 30 device s while the flash changes) */
    var rollingInterval = 5 * 60.0
    /** the days' boundaries */
    var zone: ZoneId = ZoneId.systemDefault()
    private val lock = Any()

    /** the copies of a firmware, newest first */
    fun list(firmwareID: String): List<Snapshot> = synchronized(lock) { ordered(load(firmwareID)).reversed() }

    /** a copy's bytes (null: missing, or not what was saved) */
    fun data(s: Snapshot): ByteArray? {
        val d = runCatching { File(dir(s.firmwareID), "${s.id}.bin").readBytes() }.getOrNull() ?: return null
        return if (FlashFile.sha256(d) == s.sha256) d else null
    }

    /**
     * A copy of [data] kept; null when none was needed (empty data, a rolling copy too soon or the same as the
     * newest). A risky step's copy that equals the newest copy relabels that copy rather than keeping the bytes twice.
     */
    fun record(data: ByteArray, firmwareID: String, version: String, reason: Reason): Snapshot? {
        if (data.isEmpty() || firmwareID.isEmpty()) return null
        return synchronized(lock) {
            val list = ordered(load(firmwareID)).toMutableList()
            val hash = FlashFile.sha256(data)
            val t = now()
            val last = list.lastOrNull()
            if (last != null && last.sha256 == hash) {
                var l = last
                if (reason.kind == Kind.EVENT && last.kind != Kind.EVENT) {
                    l = last.copy(kind = Kind.EVENT, reason = reason.text)
                    writeEntry(l)
                    DataChanges.post()
                }
                return@synchronized if (reason.kind == Kind.EVENT) l else null
            }
            if (reason == Reason.Rolling) {
                val r = list.lastOrNull { it.kind != Kind.EVENT }
                if (r != null && t - r.date < rollingInterval && t >= r.date) return@synchronized null
            }
            val s = Snapshot(UUID.randomUUID().toString().uppercase(), reason.kind, reason.text, firmwareID, version, t,
                data.size, hash)
            try {
                val d = dir(firmwareID)
                DataFiles.mkdirs(d)
                DataFiles.writeAtomic(File(d, "${s.id}.bin"), data)
                DataFiles.writeAtomic(entryFile(s), Json.write(s.toJson()).toByteArray())   // (after its bytes)
            } catch (e: Exception) {
                DataFiles.log("history $firmwareID: $e")
                return@synchronized null
            }
            list.add(s)
            prune(list)
            DataChanges.post()
            s
        }
    }

    /**
     * The rules: every event copy up to keepEvents; the last keepRolling rolling copies; the newest of each of the
     * last keepDays days (as "daily"); the rest removed (entry first, then bytes). Only entries whose label changed are
     * written: another writer's newer copies (not in [list]) are never touched.
     */
    internal fun prune(list: List<Snapshot>): List<Snapshot> {
        val t = now()
        val events = list.filter { it.kind == Kind.EVENT }.sortedByDescending { it.date }
        val rolling = list.filter { it.kind != Kind.EVENT }.sortedByDescending { it.date }
        val keep = events.take(keepEvents).map { it.id }.toMutableSet()
        val recent = rolling.take(keepRolling).map { it.id }.toSet()
        keep += recent
        val today = day(t)
        val first = today.minusDays((keepDays - 1).toLong())
        val days = HashSet<LocalDate>()
        for (r in rolling) {
            val d = day(r.date)
            if (!d.isBefore(first) && days.add(d)) keep += r.id
        }
        val out = ArrayList<Snapshot>()
        for (s0 in list) {
            if (s0.id in keep) {
                var s = s0
                if (s.kind != Kind.EVENT) {
                    val k = if (s.id in recent) Kind.ROLLING else Kind.DAILY
                    s = s.copy(kind = k, reason = k.raw)
                }
                if (s != s0) writeEntry(s)
                out.add(s)
            } else {
                entryFile(s0).delete()
                File(dir(s0.firmwareID), "${s0.id}.bin").delete()
            }
        }
        return out
    }

    private fun day(t: Double): LocalDate = Instant.ofEpochMilli((t * 1000).toLong()).atZone(zone).toLocalDate()

    /** oldest first (copies of the same moment: by id, so every reader agrees) */
    private fun ordered(l: List<Snapshot>) = l.sortedWith(compareBy<Snapshot>({ it.date }, { it.id }))

    fun dir(firmwareID: String) = File(root, firmwareID.replace("/", "_"))

    private fun entryFile(s: Snapshot) = File(dir(s.firmwareID), "${s.id}.json")

    /** every copy's entry in the folder (any writer's; a step-1 index split first) */
    private fun load(firmwareID: String): List<Snapshot> {
        val d = dir(firmwareID)
        splitIndex(d)
        val names = d.list() ?: return emptyList()
        return names.filter { it.endsWith(".json") && !it.startsWith(".") }.mapNotNull {
            runCatching { Snapshot.fromJson(Json.obj(File(d, it).readText())) }.getOrNull()
        }
    }

    /** (a relabel) the entry written again, unless another writer removed the copy meanwhile */
    private fun writeEntry(s: Snapshot) {
        val f = entryFile(s)
        if (!f.exists()) return
        runCatching { DataFiles.writeAtomic(f, Json.write(s.toJson()).toByteArray()) }
    }

    companion object {
        /** files/FlashHistory */
        fun defaultRoot(filesDir: File) = File(filesDir, "FlashHistory")

        /** (iOS step 1's layout) a firmware folder's index.json split into a <uuid>.json per copy, then removed */
        internal fun splitIndex(dir: File) {
            val index = File(dir, "index.json")
            if (!index.isFile) return
            val list = runCatching {
                (Json.parse(index.readText()) as List<*>).map {
                    @Suppress("UNCHECKED_CAST") Snapshot.fromJson(it as Map<String, Any?>)
                }
            }.getOrDefault(emptyList())
            for (s in list) {
                val f = File(dir, "${s.id}.json")
                if (!f.exists()) runCatching { DataFiles.writeAtomic(f, Json.write(s.toJson()).toByteArray()) }
            }
            index.delete()
        }
    }
}
