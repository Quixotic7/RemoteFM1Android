// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A Fantasy CFW (FM1VST docs/VIRTUAL-CARTS.md sections 1-2): an .rfm1 for RemoteFM1 only, its manifest's
// "virtual": true. Read here from the file itself (its rfm1 section, its memory, whether it imports rfm1_host_1), so
// the app knows one before the native loader does: the rules the web engine's inspect() has for it (RemoteFM1 60a1b8b
// web/engines/rfm1/engine.js) applied when a file is added and before it loads; the badge; its "folders".
// The native checks (RFMWasmCore.cpp) stay the authority for everything else: this only refuses earlier.
//   needs     names of host capabilities: "fs1" (the RemoteFM1 folder) is the one known; another refuses the file
//             ("needs a newer RemoteFM1")
//   limits    a Fantasy CFW: memory up to 512 MiB, a file under 16 MiB; other files as before (the native checks)
//   memory    before a load, the declared memory against what this device can give now (MemoryBudget)
package com.quixotic7.remotefm1.firmware

import java.io.File
import java.io.RandomAccessFile

object FantasyCfw {
    const val PAGE = 65536L
    const val MAX_VIRTUAL_MEMORY = 512L shl 20
    const val MAX_VIRTUAL_FILE = 16L shl 20
    const val HOST_MODULE = "rfm1_host_1"
    val KNOWN_NEEDS = setOf("fs1")
    val FOLDER_ROOTS = listOf("audio", "code", "data")
    /** the word the app uses */
    const val NAME = "Fantasy CFW"

    /** what the file says of itself */
    data class Scan(
        val manifest: String?,                   // the rfm1 section's text (null: none)
        val memoryBytes: Long,                   // the declared memory (initial pages), 0: none
        val hostImports: List<String>,           // the rfm1_host_1 functions it imports
        val fileSize: Long,
    )

    /** a file's Fantasy CFW fields (a plain .rfm1: virtual false, nothing else) */
    data class Info(
        val virtual: Boolean = false,
        val needs: List<String> = emptyList(),
        val folders: Map<String, String> = emptyMap(),
        val memoryBytes: Long = 0,
        val usesFolder: Boolean = false,         // imports rfm1_host_1 or needs fs1
    ) {
        /** "reads audio, code · writes data" (the web player's words) */
        val access: String get() = accessLine(folders)
    }

    // ---- the file

    /** its sections walked (only the custom, import and memory sections read); null: not a WebAssembly module */
    fun scan(f: File): Scan? = runCatching { RandomAccessFile(f, "r").use { scan(it, f.length()) } }.getOrNull()

    private fun scan(r: RandomAccessFile, len: Long): Scan? {
        if (len < 8) return null
        val head = ByteArray(8)
        r.readFully(head)
        if (head[0] != 0.toByte() || head[1] != 'a'.code.toByte() || head[2] != 's'.code.toByte() || head[3] != 'm'.code.toByte()) return null
        var at = 8L
        var manifest: String? = null
        var memory = 0L
        val host = ArrayList<String>()
        fun leb(): Long {
            var v = 0L
            var sh = 0
            while (true) {
                val x = r.read()
                if (x < 0 || sh > 35) throw IllegalStateException("bad LEB")
                v = v or ((x and 0x7F).toLong() shl sh)
                if (x and 0x80 == 0) return v
                sh += 7
            }
        }
        while (at < len) {
            r.seek(at)
            val id = r.read()
            val size = leb()
            val body = r.filePointer
            val end = body + size
            if (end > len) return null
            when (id) {
                0 -> {
                    val nl = leb()
                    if (nl <= 64 && r.filePointer + nl <= end) {
                        val nb = ByteArray(nl.toInt())
                        r.readFully(nb)
                        if (String(nb) == "rfm1" && manifest == null && end - r.filePointer <= (1 shl 20)) {
                            val pb = ByteArray((end - r.filePointer).toInt())
                            r.readFully(pb)
                            manifest = String(pb, Charsets.UTF_8)
                        }
                    }
                }
                2 -> if (size <= (1 shl 20)) {
                    val b = ByteArray(size.toInt())
                    r.readFully(b)
                    host.addAll(hostImports(b))
                }
                5 -> {
                    val n = leb()
                    if (n >= 1) {
                        val flags = r.read()
                        val init = leb()
                        memory = init * PAGE
                        if (flags and 1 != 0) leb()
                    }
                }
            }
            at = end
        }
        return Scan(manifest, memory, host, len)
    }

    /** the import section's rfm1_host_1 function names */
    private fun hostImports(b: ByteArray): List<String> {
        var at = 0
        fun u(): Int {
            var v = 0
            var sh = 0
            while (true) {
                val x = b[at++].toInt() and 0xFF
                v = v or ((x and 0x7F) shl sh)
                if (x and 0x80 == 0) return v
                sh += 7
            }
        }
        fun name(): String { val n = u(); val s = String(b, at, n, Charsets.UTF_8); at += n; return s }
        val out = ArrayList<String>()
        val n = u()
        for (i in 0 until n) {
            val mod = name()
            val fld = name()
            when (b[at++].toInt()) {
                0 -> u()                                    // function: its type
                1 -> { at++; val fl = u(); u(); if (fl and 1 != 0) u() }       // table
                2 -> { val fl = u(); u(); if (fl and 1 != 0) u() }             // memory
                3 -> { at += 2 }                            // global
                else -> return out
            }
            if (mod == HOST_MODULE) out.add(fld)
        }
        return out
    }

    // ---- the rules

    /** why the file can't be used ("it needs a newer RemoteFM1 …"), or null (the native checks then decide); [s] null:
     *  not a module (the native checks say why) */
    fun refusal(s: Scan?): String? {
        s ?: return null
        val m = s.manifest?.let { runCatching { MiniJson.parse(it).obj() }.getOrNull() } ?: return null
        val v = m["virtual"]
        if (v != null && v !is Boolean) return "its manifest's \"virtual\" is not true or false"
        val virtual = v == true
        val needsRaw = m["needs"]
        if (needsRaw != null && (needsRaw !is List<*> || needsRaw.any { it !is String })) return "its manifest's \"needs\" is not a list of names"
        val needs = (needsRaw as? List<*>)?.map { it as String } ?: emptyList()
        val unknown = needs.filter { it !in KNOWN_NEEDS }
        if (unknown.isNotEmpty()) return "it needs a newer RemoteFM1 (it needs ${unknown.joinToString(", ") { "\"$it\"" }})"
        if (needs.isNotEmpty() && !virtual) return "its manifest lists \"needs\" but is not \"virtual\" (a Fantasy CFW)"
        val fo = m["folders"]
        if (fo != null) {
            val o = fo.obj() ?: return "its manifest's \"folders\" is not an object"
            for ((k, x) in o) if (k in FOLDER_ROOTS && x != "r" && x != "rw") return "its manifest's \"folders\" gives $k \"$x\" (only \"r\" or \"rw\")"
        }
        if (s.hostImports.isNotEmpty() && !(virtual && "fs1" in needs))
            return "it imports $HOST_MODULE (the RemoteFM1 folder) but its manifest doesn't say \"virtual\": true with \"fs1\" in \"needs\""
        if (virtual && s.fileSize >= MAX_VIRTUAL_FILE) return "larger than 16 MiB: a Fantasy CFW's file is under 16 MiB"
        if (virtual && s.memoryBytes > MAX_VIRTUAL_MEMORY)
            return "its memory is ${s.memoryBytes shr 20} MiB: at most 512 MiB, for a Fantasy CFW"
        return null
    }

    fun refusal(f: File): String? = refusal(scan(f))

    /** the Fantasy CFW fields of a file that passed [refusal] (a plain .rfm1: Info()) */
    fun info(s: Scan?): Info {
        val m = s?.manifest?.let { runCatching { MiniJson.parse(it).obj() }.getOrNull() } ?: return Info(memoryBytes = s?.memoryBytes ?: 0)
        val virtual = m["virtual"] == true
        val needs = (m["needs"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        val folders = m["folders"].obj()?.filter { (k, v) -> k in FOLDER_ROOTS && (v == "r" || v == "rw") }
            ?.mapValues { it.value as String } ?: emptyMap()
        return Info(virtual, needs, folders, s.memoryBytes, usesFolder = s.hostImports.isNotEmpty() || "fs1" in needs)
    }

    fun info(f: File): Info = info(scan(f))

    /** the manifest's folders, short: "reads audio, code · writes data"; "no files" */
    fun accessLine(folders: Map<String, String>): String {
        val r = FOLDER_ROOTS.filter { folders[it] != null }
        val w = if (folders["data"] == "rw") listOf("data") else emptyList()
        return listOf(if (r.isEmpty()) "" else "reads ${r.joinToString(", ")}", if (w.isEmpty()) "" else "writes ${w.joinToString(", ")}")
            .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "no files" }
    }
}

/**
 * Whether this device can give a firmware its declared memory now. wasm3 (with the lazily zeroed memory) maps the
 * whole linear memory at load; a cart that then touches it all would have the app killed if the RAM isn't there. The
 * budget: what is free above the system's low-memory line, less a reserve for the app itself, and at most the larger of
 * the app's large heap class and a quarter of the RAM (the share an app may take without pushing others out). Up to
 * 32 MiB (every .rfm1 before Fantasy CFWs) is never refused.
 */
object MemoryBudget {
    const val RESERVE = 128L shl 20

    /** the budget in bytes from ActivityManager's numbers */
    fun budget(availMem: Long, threshold: Long, totalMem: Long, largeMemoryClassMb: Int): Long {
        val free = availMem - threshold - RESERVE
        val share = maxOf(largeMemoryClassMb.toLong() shl 20, totalMem / 4)
        return maxOf(0, minOf(free, share))
    }

    /** memory any firmware had before Fantasy CFWs (the format's cap): never refused, as before */
    const val ALWAYS = 32L shl 20

    /** "needs N MB of memory", or null when it fits */
    fun refusal(declared: Long, budget: Long): String? =
        if (declared <= ALWAYS || declared <= budget) null else "needs ${(declared + (1 shl 20) - 1) shr 20} MB of memory"

    /** the device's budget now */
    fun budget(ctx: android.content.Context): Long {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return Long.MAX_VALUE
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return budget(mi.availMem, mi.threshold, mi.totalMem, am.largeMemoryClass)
    }

    /**
     * The native loader's budget for [budget]: it refuses when the declared memory plus 16 MiB is over its budget, so
     * this gives it max(budget, [ALWAYS]) + 16 MiB and both refuse the same files (up to 32 MiB never); 0 (no check)
     * when the budget is unknown.
     */
    fun nativeBudget(budget: Long): Long = if (budget >= Long.MAX_VALUE / 2) 0 else maxOf(budget, ALWAYS) + (16L shl 20)

    /** a load's check: the file's declared memory against the budget now; null: go ahead */
    fun check(ctx: android.content.Context, path: String): String? {
        val mem = FantasyCfw.scan(File(path))?.memoryBytes ?: return null
        return refusal(mem, budget(ctx))
    }
}
