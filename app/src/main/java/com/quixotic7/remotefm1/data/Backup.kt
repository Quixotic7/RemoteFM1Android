// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/StateFile.swift (Backup).
//
// Back up everything: one .zip, "RemoteFM1 backup <yyyy-MM-dd HHmm>.zip", holding the folder iOS writes (its zip
// unzips to it): "RemoteFM1 backup <date>/" with States/<Firmware>/<name>.rfm1state, Current/<Firmware>
// current.rfm1state (each firmware's memory now), Safety copies/<Firmware>/... (the newest 3 per firmware),
// manifest.json (iOS's fields) and README.txt. Into Backups/ of the picked folder, or the cache to share without one.
// Restore: a backup's States and Current imported (a .zip, a folder, or .rfm1state files; a folder or zip that isn't a
// backup: every .rfm1state in it), duplicates skipped, nothing overwritten, unreadable files named.
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.FlashFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Backup {
    /** a firmware's flash as it is now (the caller reads it: the app's files) */
    data class Current(val firmwareID: String, val firmwareName: String, val kind: String, val version: String,
                       val sha256: String = "", val flash: ByteArray)

    data class Item(val file: String, val name: String, val firmwareID: String, val version: String, val date: Double,
                    val flashSHA256: String) {
        fun toJson(): Map<String, Any?> = mapOf("file" to file, "name" to name, "firmwareID" to firmwareID,
            "version" to version, "date" to date, "flashSHA256" to flashSHA256)

        companion object {
            fun fromJson(m: Map<String, Any?>) = JsonFields(m, "backup item").run {
                Item(str("file"), str("name"), str("firmwareID"), str("version"), num("date"), str("flashSHA256"))
            }
        }
    }

    data class Manifest(
        val format: Int = 1,
        val date: Double,
        val app: String,
        val states: MutableList<Item> = ArrayList(),
        val current: MutableList<Item> = ArrayList(),
        val safetyCopies: MutableList<Item> = ArrayList(),
    ) {
        fun toJson(): Map<String, Any?> = mapOf("format" to format, "date" to date, "app" to app,
            "states" to states.map { it.toJson() }, "current" to current.map { it.toJson() },
            "safetyCopies" to safetyCopies.map { it.toJson() })

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun fromJson(m: Map<String, Any?>) = JsonFields(m, "backup").run {
                fun items(k: String) = list(k).map { Item.fromJson(it as Map<String, Any?>) }.toMutableList()
                Manifest(int("format"), num("date"), str("app"), items("states"), items("current"), items("safetyCopies"))
            }
        }
    }

    class RestoreReport {
        val imported = ArrayList<SavedState>()
        var duplicates = 0
        val failed = ArrayList<String>()

        /** another report's counts added (several files picked) */
        fun add(o: RestoreReport) { imported += o.imported; duplicates += o.duplicates; failed += o.failed }

        /** plain words: "Imported 3 states (2 already in the library)." */
        val text: String
            get() {
                var t = if (imported.isEmpty()) "No new states" else "Imported ${imported.size} state${if (imported.size == 1) "" else "s"}"
                if (duplicates > 0) t += " ($duplicates already in the library)"
                t += "."
                if (failed.isNotEmpty())
                    t += " ${failed.size} file${if (failed.size == 1) "" else "s"} couldn't be read: " + failed.take(3).joinToString("; ")
                return t
            }
    }

    const val SAFETY_COPIES_KEPT = 3
    /** the backups' folder in the picked folder */
    const val DIR = "Backups"
    /** a backup's entries at most this big (a hostile zip) */
    private const val MAX_ENTRY = 96 shl 20

    val README = """
        |RemoteFM1 backup
        |
        |States: every state in the library, one .rfm1state per state, by firmware.
        |Current: each firmware's memory as it was at the backup ("<Firmware> current").
        |Safety copies: the newest 3 safety copies of each firmware.
        |manifest.json lists them.
        |
        |To restore on Android: Settings › Restore from backup…, then pick the backup's .zip (or this folder, unzipped). On an iPhone or iPad: tap the .zip in Files to unzip it, then Settings › Restore from backup… and pick this folder. The states in States and Current are added to the library; one already there is skipped, nothing is overwritten. Any single .rfm1state can also be opened by itself.
        |
        |""".trimMargin()

    fun folderName(date: Double): String {
        val f = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US)
        return "RemoteFM1 backup ${f.format(Date((date * 1000).toLong()))}"
    }

    /** the backup's file name in [tree]'s Backups (a second one in the same minute: " 2") */
    fun zipPath(tree: DataTree, date: Double): String {
        var n = 2
        var p = "$DIR/${folderName(date)}.zip"
        while (tree.exists(p)) { p = "$DIR/${folderName(date)} $n.zip"; n++ }
        return p
    }

    /**
     * A backup written to [out] as a zip whose top folder is [folder] (folderName(date) by default); its manifest.
     * [out] is closed. Damaged states are left out (the rest kept).
     */
    fun writeZip(out: OutputStream, library: StateLibrary, history: FlashHistory, current: List<Current>,
                 app: String = "", date: Double = DataDates.now(), folder: String = folderName(date)): Manifest {
        val m = Manifest(date = date, app = app)
        val used = HashSet<String>()
        val codec = library.codec
        fun place(dir: String, base0: String): String {
            val base = zipSafe(StateFile.safeName(base0))
            var rel = "$dir/$base.${StateFile.EXTENSION}"
            var i = 2
            while (rel.lowercase() in used) { rel = "$dir/$base $i.${StateFile.EXTENSION}"; i++ }
            used.add(rel.lowercase())
            return rel
        }
        ZipOutputStream(out).use { z ->
            fun entry(rel: String, data: ByteArray) {
                z.putNextEntry(ZipEntry("$folder/$rel").apply { time = (date * 1000).toLong() })
                z.write(data)
                z.closeEntry()
            }
            fun put(s: SavedState, flash: ByteArray, rel: String): Item {
                entry(rel, StateFile.encode(s, flash, codec))
                return Item(rel, s.name, s.firmwareID, s.version, s.date, FlashFile.sha256(flash))
            }
            val states = library.allStates()
            val names = HashMap<String, String>()           // firmware id → name
            for (s in states) names[s.firmwareID] = s.firmwareName
            for (c in current) names[c.firmwareID] = c.firmwareName
            fun folderOf(id: String) = StateFile.safeName(names[id]?.ifEmpty { null } ?: id)

            for (s in states.sortedWith(compareBy<SavedState>({ it.firmwareID }, { it.date }))) {
                val f = runCatching { library.flash(s) }.getOrNull() ?: continue   // (damaged: left out)
                m.states.add(put(s, f, place("States/${folderOf(s.firmwareID)}", s.name)))
            }
            val seen = HashSet<String>()
            for (c in current) {
                if (c.flash.isEmpty() || !seen.add(FlashFile.sha256(c.flash))) continue
                val s = SavedState(UUID.randomUUID().toString().uppercase(), "${c.firmwareName} current",
                    "Backed up ${DataDates.numeric(date)}", c.firmwareID, c.firmwareName, c.kind, c.version, c.sha256,
                    date, 0, "", origin = "backup")
                m.current.add(put(s, c.flash, place("Current", s.name)))
            }
            val ids = history.root.list()?.filter { !it.startsWith(".") }?.sorted() ?: emptyList()
            for (id in ids) {
                for (snap in history.list(id).take(SAFETY_COPIES_KEPT)) {
                    val d = history.data(snap) ?: continue
                    val fname = names[snap.firmwareID] ?: snap.firmwareID
                    val label = "$fname ${DataDates.short(snap.date)} ${snap.reason}"
                    val s = SavedState(snap.id, label, "A safety copy (${snap.reason})", snap.firmwareID, fname, "",
                        snap.version, "", snap.date, 0, "", origin = "safety copy")
                    m.safetyCopies.add(put(s, d, place("Safety copies/${folderOf(snap.firmwareID)}", label)))
                }
            }
            entry("manifest.json", Json.write(m.toJson()).toByteArray())
            entry("README.txt", README.toByteArray())
        }
        return m
    }

    /** (a name in the zip) the narrow and no-break spaces of a date as plain spaces, "→" as "-": every unzipper
     *  makes those names (Info-ZIP's unzip fails on some of them in a Java-made zip) */
    internal fun zipSafe(s: String) = s.replace('\u202F', ' ').replace('\u00A0', ' ').replace('\u2007', ' ').replace("→", "-")

    /** a written backup read back: every file the manifest lists there, decoded and its hash the manifest's */
    fun check(zip: InputStream, m: Manifest, codec: FlashCodec = Lzfse): Boolean {
        val all = readZip(zip)
        val top = all.keys.firstOrNull { it.endsWith("/manifest.json") }?.removeSuffix("manifest.json") ?: return false
        val mj = all["${top}manifest.json"] ?: return false
        if (runCatching { Manifest.fromJson(Json.obj(String(mj))) }.getOrNull() != m) return false
        return (m.states + m.current + m.safetyCopies).all { i ->
            val d = all[top + i.file] ?: return false
            runCatching { FlashFile.sha256(StateFile.decode(d, codec).flash) == i.flashSHA256 }.getOrDefault(false)
        }
    }

    // ---- Restore

    /** a zip's States and Current imported (a zip that isn't a backup: every .rfm1state in it) */
    fun restoreZip(zip: InputStream, library: StateLibrary): RestoreReport {
        val all = try { readZip(zip) } catch (e: IOException) {
            return RestoreReport().apply { failed.add("the zip: ${e.message}") }
        }
        val manifests = all.keys.filter { it == "manifest.json" || it.endsWith("/manifest.json") }
        val tops = manifests.map { it.removeSuffix("manifest.json") }
        val picked = all.keys.filter { k ->
            isState(k) && (tops.isEmpty() || tops.any { t -> k.startsWith("${t}States/") || k.startsWith("${t}Current/") })
        }
        return importAll(picked.sorted().map { it to all[it]!! }, library)
    }

    /** a folder's States and Current imported (a folder that isn't a backup: every .rfm1state in it) */
    fun restoreTree(tree: DataTree, dir: String, library: StateLibrary): RestoreReport {
        val isBackup = tree.exists(DataTree.join(dir, "manifest.json"))
        val tops = if (isBackup) listOf("States", "Current").map { DataTree.join(dir, it) } else listOf(dir)
        val files = tops.flatMap { t -> if (tree.isDir(t) || t.isEmpty()) tree.files(t).filter(::isState) else emptyList() }
        val r = RestoreReport()
        for (rel in files.sorted()) {
            val d = tree.read(rel)
            if (d == null) { r.failed.add("${DataTree.name(rel)}: can't be read"); continue }
            r.add(importAll(listOf(rel to d), library))
        }
        return r
    }

    /** .rfm1state files (their names and bytes) imported */
    fun restoreFiles(files: List<Pair<String, ByteArray>>, library: StateLibrary): RestoreReport = importAll(files, library)

    private fun importAll(files: List<Pair<String, ByteArray>>, library: StateLibrary): RestoreReport {
        val r = RestoreReport()
        for ((path, d) in files) {
            try {
                val i = library.importBytes(d)
                if (i.duplicate) r.duplicates++ else r.imported.add(i.state)
            } catch (e: Exception) { r.failed.add("${path.substringAfterLast('/')}: ${e.message}") }
        }
        return r
    }

    private fun isState(p: String) =
        p.substringAfterLast('.', "").equals(StateFile.EXTENSION, ignoreCase = true) &&
            p.split('/').none { it.startsWith(".") || it == "__MACOSX" }

    /** every file in a zip (folders and oversized entries left out) */
    private fun readZip(zip: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zip).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val b = ByteArrayOutputStream()
                val buf = ByteArray(65536)
                var total = 0L
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_ENTRY) throw IOException("${e.name} is too big")
                    b.write(buf, 0, n)
                }
                out[e.name.trimStart('/')] = b.toByteArray()
            }
        }
        return out
    }
}
