// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/CorePlayer.swift (CoreInfo, CoreRef, CorePlayer.order, pick,
// missingNote, replacement, flashURL, catalog's order).
//
// The firmware catalog the app picks from: the built-in cores (kind "native", libfm1core_<id>.so) then the imported
// .rfm1 files (kind "wasm", FirmwareLibrary). A built-in core's key is its id ("felucca"); an imported file's
// "rfm1:<sha256>", so an imported Felucca and the built-in one are separate entries. A saved choice is a CoreRef
// (kind, id, version, SHA-256): resolve finds the same file, else the same firmware with a note.
package com.quixotic7.remotefm1.firmware

import org.json.JSONObject

/** one entry of the firmware catalog: a built-in core (kind "native") or an imported .rfm1 (kind "wasm") */
data class CoreInfo(
    /** the catalog key: a built-in core's id; an imported file's "rfm1:<sha256>" */
    val id: String,
    val name: String = "",
    val version: String = "",
    val family: String = "",
    val license: String = "",
    val sourceURL: String = "",
    val credits: String = "",
    val gated: String = "",
    val abi: Int = 0,
    val flashSize: Int = 0,
    val kind: String = "native",
    /** the firmware's own id ("" = id: a built-in core) */
    val coreID: String = "",
    /** an imported file: its hash, its path in files/Firmware, its name as added, its size */
    val sha256: String = "",
    val path: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
) {
    val imported: Boolean get() = kind == "wasm"
    /** the firmware's own id (a saved state's, the flash file's) */
    val firmwareID: String get() = coreID.ifEmpty { id }
    /** "Felucca 1.6.1", "Felucca 1.6.1 (imported)" */
    val title: String get() = "$name $version" + if (imported) " (imported)" else ""

    companion object {
        /** a built-in core as RfmEngine.cores() lists it ({id, name, version, family, license, abi, flash_size}) */
        fun builtIn(j: JSONObject): CoreInfo = CoreInfo(
            id = j.optString("id"), coreID = j.optString("id"), name = j.optString("name"), version = j.optString("version"),
            family = j.optString("family"), license = j.optString("license"), sourceURL = j.optString("source_url"),
            gated = j.optString("gated"), abi = j.optInt("abi"), flashSize = j.optInt("flash_size"))

        /** RfmEngine.cores()'s list, in [CorePicker.ORDER] (then by id) */
        fun builtInList(cores: JSONObject): List<CoreInfo> {
            val a = cores.optJSONArray("cores") ?: return emptyList()
            return (0 until a.length()).map { builtIn(a.getJSONObject(it)) }.let(CorePicker::sorted)
        }
    }
}

/**
 * A saved choice of firmware (the app's selection, a state): its kind, id, version and, for an imported file, its
 * SHA-256. resolve finds it in a catalog: the same file; else the same id and kind (another version or build, with a
 * note); else the same id of the other kind (a note); else the first core (a note; the saved flash is not for it).
 */
data class CoreRef(val kind: String = "native", val id: String, val version: String = "", val sha256: String = "") {
    data class Resolved(val key: String, val note: String, val keepFlash: Boolean)

    constructor(c: CoreInfo) : this(c.kind, c.firmwareID, c.version, c.sha256)

    fun resolve(cores: List<CoreInfo>): Resolved? {
        val k = kind.ifEmpty { "native" }
        fun name(c: CoreInfo) = c.name.ifEmpty { c.firmwareID }
        fun newest(l: List<CoreInfo>) = l.firstOrNull { it.version == version } ?: l.reduceOrNull { a, b -> if (older(a.version, b.version)) b else a }
        if (k == "wasm") {
            cores.firstOrNull { it.imported && it.sha256 == sha256 }?.let { return Resolved(it.id, "", true) }
            newest(cores.filter { it.imported && it.firmwareID == id })?.let { c ->
                val note = if (c.version == version) "${name(c)} $version: another build of the file (${c.sha256.take(8)})"
                else "${name(c)} ${c.version} instead of $version"
                return Resolved(c.id, note, true)
            }
            cores.firstOrNull { !it.imported && it.firmwareID == id }?.let { c ->
                return Resolved(c.id, "${name(c)} $version (imported) is not here: the built-in ${name(c)} ${c.version} instead", true)
            }
        } else {
            cores.firstOrNull { !it.imported && it.id == id }?.let { return Resolved(it.id, "", true) }
            newest(cores.filter { it.imported && it.firmwareID == id })?.let { c ->
                return Resolved(c.id, "$id is not in this build: ${name(c)} ${c.version} (imported) instead", true)
            }
        }
        val first = CorePicker.pick(null, cores) ?: return null
        return Resolved(first, CorePicker.missingNote(if (k == "wasm") "$id $version (imported)" else id, first, cores), false)
    }

    /** the saved form ({"kind", "id", "version", "sha256"}, as iOS's UserDefaults dictionary) */
    fun toMap(): Map<String, String> = mapOf("kind" to kind, "id" to id, "version" to version, "sha256" to sha256)
    fun toJson(): String = MiniJson.write(toMap())

    companion object {
        /** version a before b ("1.6.1" < "1.6.10", "1.0-beta.2" < "1.0": FirmwareVersion) */
        fun older(a: String, b: String) = FirmwareVersion.less(a, b)

        fun of(kind: String, id: String, version: String = "", sha256: String = "") =
            CoreRef(kind.ifEmpty { "native" }, id, version, sha256)

        fun fromMap(m: Map<String, Any?>?): CoreRef? {
            val id = m?.get("id") as? String ?: return null
            if (id.isEmpty()) return null
            return of(m["kind"] as? String ?: "native", id, m["version"] as? String ?: "", m["sha256"] as? String ?: "")
        }

        fun fromJson(s: String?): CoreRef? = s?.let { runCatching { fromMap(MiniJson.parse(it).obj()) }.getOrNull() }
    }
}

object CorePicker {
    /** the cores' order in the picker and for a default: the first of these this build has */
    val ORDER = listOf("choralroot", "felucca", "melodee", "fizz", "saadet", "boardwalk", "loom")

    fun sorted(l: List<CoreInfo>): List<CoreInfo> =
        l.sortedWith(compareBy<CoreInfo>({ ORDER.indexOf(it.id).let { i -> if (i < 0) 99 else i } }, { it.id }))

    /** the catalog: the built-in cores in [ORDER], then the imported files (FirmwareLibrary.cores' order) */
    fun catalog(builtIn: List<CoreInfo>, imported: List<CoreInfo>): List<CoreInfo> = sorted(builtIn) + imported

    /** [preferred] if [cores] has it, else the first of [cores] (built-in cores first); null when there are none */
    fun pick(preferred: String?, cores: List<CoreInfo>): String? {
        if (preferred != null && cores.any { it.id == preferred }) return preferred
        return (cores.firstOrNull { !it.imported } ?: cores.firstOrNull())?.id
    }

    /** the status line for a core this build doesn't have */
    fun missingNote(missing: String, used: String, cores: List<CoreInfo>): String {
        val name = cores.firstOrNull { it.id == used }?.name ?: used
        return "$missing is not in this build: ${name.ifEmpty { used }} instead"
    }

    /**
     * The core to select when [key] is deleted: null when it isn't the selection; else the same firmware's other file
     * (its newest version), else its built-in core, else the first core (CoreRef.resolve without the deleted file).
     */
    fun replacement(deleting: String, selected: String, cores: List<CoreInfo>): String? {
        if (deleting != selected) return null
        val c = cores.firstOrNull { it.id == deleting } ?: return null
        return CoreRef(c).copy(version = "").resolve(cores.filter { it.id != deleting })?.key
    }

    /** the flash file's name of a catalog entry: <id>.bin, an imported firmware's rfm1-<its id>.bin (shared by its versions) */
    fun flashFileName(c: CoreInfo): String = if (c.imported) "rfm1-${c.firmwareID}.bin" else "${c.id}.bin"
}
