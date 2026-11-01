// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/StateFile.swift (StateMirror).
//
// The States mirror: <picked folder>/States/<Firmware>/<state>.rfm1state kept like the library, so a file manager
// (and a computer over USB) sees every state; a .rfm1state the user puts there is imported. The library wins: a file
// renamed or removed there comes back; a state deleted in the app leaves. A ledger (.mirror.json) says which files
// the mirror wrote. Without a picked folder the mirror is off (DataFolder). Run it off the main thread: at launch, on
// a library change (DataChanges) and back in the foreground.
package com.quixotic7.remotefm1.data

class StateMirror(val tree: DataTree, val library: StateLibrary) {
    data class Report(
        var written: Int = 0, var moved: Int = 0, var removed: Int = 0, var imported: Int = 0, var duplicates: Int = 0,
        val failed: MutableList<String> = ArrayList(),          // "<file>: why"
        val importedStates: MutableList<SavedState> = ArrayList(),
    )

    private data class Entry(val path: String, val name: String, val note: String)

    private val lock = Any()

    /** the folder made like the library (see the top); files found there imported first */
    fun sync(): Report = synchronized(lock) { syncLocked() }

    private fun syncLocked(): Report {
        val r = Report()
        if (tree.read(README)?.toString(Charsets.UTF_8) != README_TEXT)
            runCatching { tree.write(README, README_TEXT.toByteArray()) }
        val ledger = readLedger()

        // the files there: their ids (manifest only)
        val files = LinkedHashMap<String, String>()               // relative path → state id
        for (rel in tree.files(DIR)) {
            if (!rel.substringAfterLast('.', "").equals(StateFile.EXTENSION, ignoreCase = true)) continue
            val d = tree.read(rel) ?: continue
            try { files[rel] = StateFile.stateID(d) } catch (e: Exception) { r.failed.add("$rel: ${e.message}") }
        }
        val lib = LinkedHashMap<String, SavedState>()
        for (s in library.allStates()) lib.putIfAbsent(s.id, s)

        // new files (not the mirror's): imported
        for ((rel, id) in files.entries.sortedBy { it.key }) {
            if (lib[id] != null || ledger[id] != null) continue
            try {
                val i = library.importBytes(tree.read(rel) ?: throw java.io.IOException("can't be read"))
                if (i.duplicate) { r.duplicates++; continue }
                r.imported++
                r.importedStates.add(i.state)
                lib[i.state.id] = i.state
                if (i.state.id == id) ledger[id] = Entry(rel, "", "")      // (renamed below)
            } catch (e: Exception) { r.failed.add("$rel: ${e.message}") }
        }
        // the mirror's files of states gone from the library: removed
        for ((id, e) in ledger.entries.toList()) {
            if (lib[id] != null) continue
            if (files[e.path] == id) { tree.delete(e.path); r.removed++ }
            ledger.remove(id)
        }
        // every state at its place
        val byPath = files
        val taken = HashSet<String>()
        for ((rel, id) in byPath) if (lib[id] == null) taken.add(rel.lowercase())   // (the user's other files)
        for (s in lib.values.sortedWith(compareBy<SavedState>({ it.date }, { it.id }))) {
            val folder = DataTree.join(DIR, StateFile.safeName(s.firmwareName.ifEmpty { s.firmwareID }))
            val base = StateFile.safeName(s.name)
            var rel = "$folder/$base.${StateFile.EXTENSION}"
            var n = 2
            while (rel.lowercase() in taken || (byPath[rel] != null && byPath[rel] != s.id)) {
                rel = "$folder/$base $n.${StateFile.EXTENSION}"; n++
            }
            taken.add(rel.lowercase())
            val have = ledger[s.id]?.let { if (byPath[it.path] == s.id) it.path else null }
                ?: byPath.entries.firstOrNull { it.value == s.id }?.key
            try {
                if (have != null && have != rel) {
                    tree.move(have, rel)
                    r.moved++
                }
                val e = ledger[s.id]
                if (have == null || e == null || e.name != s.name || e.note != s.note) {
                    tree.write(rel, StateFile.encode(s, library.flash(s), library.codec))
                    if (have == null) r.written++
                }
                ledger[s.id] = Entry(rel, s.name, s.note)
            } catch (e: Exception) { r.failed.add("$rel: ${e.message}") }
        }
        // empty firmware folders left by a rename or a delete
        for (d in tree.dirs(DIR)) tree.deleteIfEmpty(d)
        runCatching { writeLedger(ledger) }
        return r
    }

    private fun readLedger(): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        val text = tree.read(LEDGER)?.toString(Charsets.UTF_8) ?: return out
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val files = Json.obj(text)["files"] as Map<String, Any?>
            for ((id, v) in files) {
                @Suppress("UNCHECKED_CAST") val m = JsonFields(v as Map<String, Any?>, "ledger")
                out[id] = Entry(m.str("path"), m.strOr("name", ""), m.strOr("note", ""))
            }
        }
        return out
    }

    private fun writeLedger(l: Map<String, Entry>) {
        val files = l.mapValues { (_, e) -> mapOf("path" to e.path, "name" to e.name, "note" to e.note) }
        tree.write(LEDGER, Json.write(mapOf("files" to files), pretty = false).toByteArray())
    }

    companion object {
        /** the mirror's folder in the picked folder */
        const val DIR = "States"
        const val README = "$DIR/README.txt"
        const val LEDGER = "$DIR/.mirror.json"

        val README_TEXT = """
            |RemoteFM1 states
            |
            |Each folder is a firmware; each .rfm1state file is a state saved in the app (the firmware's memory with MASTER, the octave and the keys' touch). The app keeps this folder like its library: rename, delete and load states in the app (Firmware › States); a file renamed or removed here comes back.
            |
            |To add a state, put its .rfm1state file anywhere in this folder: the app imports it the next time it opens (a state already in the library is skipped). Share a state from its … menu in the app, or open a .rfm1state file from a file manager, a download or a share. The iPhone and iPad app reads the same files.
            |
            |Back up everything (Settings) writes a backup .zip in Backups, beside this folder.
            |
            |""".trimMargin()
    }
}
