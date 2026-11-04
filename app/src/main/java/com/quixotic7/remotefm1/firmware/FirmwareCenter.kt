// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The firmware side of the app in one place for AppModel and the pages (A4): the library of imported .rfm1 files,
// Get firmware (the store), the thumbnails and the Firmware card's details, and the rules iOS's CorePlayer and
// RemoteFM1App apply around them (the catalog, a saved choice resolved, the selection moved before a delete or an
// update, a built-in core's memory adopted by its .rfm1, "Open with RemoteFM1"). No UI and no engine calls: AppModel
// loads what [loadTarget] says (RfmEngine.load or RfmEngine.loadWasm).
//
// Ported in part from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/CorePlayer.swift (adoptBuiltInFlash, willDelete,
// catalogChanged) and App/RemoteFM1App.swift (the store's willReplace, open(url), coreArg).
package com.quixotic7.remotefm1.firmware

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.quixotic7.remotefm1.FlashFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

class FirmwareCenter(
    val library: FirmwareLibrary,
    val store: FirmwareStore,
    val thumbs: FirmwareThumbs,
    /** files/cores: the flash files (CoreFlash's dir) */
    val flashDir: File,
    private val log: (String) -> Unit = {},
) {
    private val _catalog = MutableStateFlow<List<CoreInfo>>(emptyList())
    private var builtIn: List<CoreInfo> = emptyList()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "rfm-firmware-lib").apply { isDaemon = true } }

    /** every core the app can pick: the built-in ones (CorePicker.ORDER), then the imported files */
    val catalog: StateFlow<List<CoreInfo>> = _catalog

    /** what to load for a catalog key: the built-in id, or the .rfm1 file's path */
    sealed class LoadTarget {
        data class Native(val id: String) : LoadTarget()
        data class Wasm(val path: String, val core: CoreInfo) : LoadTarget()
    }

    /** (at launch, before the first selection) the built-in cores (RfmEngine.cores(): CoreInfo.builtInList) and the
     *  library read; blocks for the index read (a launch: no file checks unless an import was cut off) */
    fun start(builtInCores: List<CoreInfo>) {
        builtIn = builtInCores
        library.sync()
        rescan()
    }

    /** the catalog read again (after an import, a delete, an update) */
    fun rescan(): List<CoreInfo> {
        val l = CorePicker.catalog(builtIn, library.cores())
        _catalog.value = l
        return l
    }

    fun core(key: String): CoreInfo? = _catalog.value.firstOrNull { it.id == key }

    fun loadTarget(key: String): LoadTarget? {
        val c = core(key) ?: return null
        return if (c.imported) LoadTarget.Wasm(c.path, c) else LoadTarget.Native(c.id)
    }

    /** the flash file of a catalog key: files/cores/<id>.bin, an imported firmware's rfm1-<its id>.bin */
    fun flashFile(key: String): File = File(flashDir, core(key)?.let(CorePicker::flashFileName) ?: "$key.bin")

    /**
     * The saved choice resolved at launch: [launchKey] (a launch extra: a key, a built-in id, or "rfm1:<id>" for an
     * imported firmware of that id), else [saved] (a CoreRef), else a plain id saved before A4 ([legacyId]). Returns
     * the key and the note to show ("" none); null when the catalog is empty.
     */
    fun resolveSelection(launchKey: String?, saved: CoreRef?, legacyId: String?): CoreRef.Resolved? {
        val cores = _catalog.value
        if (launchKey != null) {
            val k = launchArg(launchKey)
            val pick = CorePicker.pick(k, cores) ?: return null
            return CoreRef.Resolved(pick, if (pick != k) CorePicker.missingNote(k, pick, cores) else "", pick == k)
        }
        (saved ?: legacyId?.let { CoreRef(id = it) })?.let { return it.resolve(cores) }
        return CorePicker.pick(null, cores)?.let { CoreRef.Resolved(it, "", true) }
    }

    /** -core's argument (iOS coreArg): a catalog key, a built-in core's id, or "rfm1:<id or sha256>" */
    fun launchArg(a: String): String {
        if (!FirmwareLibrary.isKey(a)) return a
        val rest = a.removePrefix(FirmwareLibrary.KEY_PREFIX)
        return library.files.value.firstOrNull { it.coreID == rest || it.sha256 == rest }?.key ?: a
    }

    /** the key to select instead before [key] is deleted (null: it isn't the selection) */
    fun replacementBeforeDelete(key: String, selected: String): String? = CorePicker.replacement(key, selected, _catalog.value)

    /**
     * An imported file deleted (the UI's Delete). [selected]: the current selection; [select] is called first with
     * the replacement when it was the selection (and [beforeDelete] for a safety copy of its flash). Blocks briefly.
     */
    fun delete(key: String, selected: String, select: (String) -> Unit, beforeDelete: (CoreInfo) -> Unit = {}) {
        val c = core(key) ?: return
        val f = library.entry(key) ?: return
        beforeDelete(c)
        replacementBeforeDelete(key, selected)?.let(select)
        library.delete(f)
        rescan()
    }

    /**
     * Wire the store's callbacks: after an install the catalog is read again ([changed] tells AppModel); before an
     * update removes older files, [replace] gets (old keys, the new key): AppModel selects the new key if the
     * selection was one of the old (the flash is shared by id: rfm1-<id>.bin) and keeps a safety copy first
     * (FlashHistory, reason before update old.version → new.version).
     */
    fun wireStore(changed: () -> Unit, replace: (List<ImportedFirmware>, ImportedFirmware) -> Unit) {
        store.didChange = {
            rescan()
            changed()
        }
        store.willReplace = { old, new ->
            rescan()
            replace(old, new)
        }
    }

    /** an .rfm1 opened ("Open with", a share): copied in and checked, off the main thread; [done] (on the worker)
     *  gets the entry (error set: refused, with its one-line reason; else select its key and go to Core mode) */
    fun open(resolver: ContentResolver, uri: Uri, done: (ImportedFirmware) -> Unit) {
        worker.execute {
            val name = displayName(resolver, uri)
            val f = runCatching { resolver.openInputStream(uri)?.use { library.import(it, name) } }.getOrNull()
                ?: ImportedFirmware("", "", name, "", "", 0, file = name, error = "can't read it")
            rescan()
            log("open $uri ($name): ${f.error ?: "added ${f.key}"}")
            done(f)
        }
    }

    /** a file added by "Add firmware" (the system picker's Uri): the same as [open] */
    fun add(resolver: ContentResolver, uri: Uri, done: (ImportedFirmware) -> Unit) = open(resolver, uri, done)

    /** at launch: Get firmware's list checked if it is a day old (off the main thread) */
    fun checkAtLaunch() = store.refreshIfOlderAsync(FirmwareStore.DAY_MS)

    /** the Get firmware page opened: checked unless it was checked in the last 10 minutes */
    fun pageOpened() = store.refreshIfOlderAsync(FirmwareStore.PAGE_MS)

    /** the badge on Firmware / Get firmware: how many installed .rfm1 have a newer version listed */
    fun updateCount(): Int = store.updates(builtIn).size

    /** the Firmware card: the catalog's details for a core (name, author, tag, description, View source) */
    fun info(key: String): FirmwareInfo? {
        val c = core(key) ?: return null
        val src = if (c.imported && c.sourceURL.isEmpty()) c.copy(sourceURL = library.details(c.sha256).sourceURL) else c
        return FirmwareInfo.lookup(src, store.details)
    }

    /**
     * (a boot of an imported firmware with no memory of its own: call before reading its flash file) a built-in core
     * of the same firmware that this build no longer has (Melodee, 2026-10-31: the .rfm1 replaced it) left its memory
     * as <id>.bin: it becomes this one's (copied; the file kept). [safetyCopy] (data, firmware id, version, the
     * "from" text, the new version) is called first for FlashHistory's "before update" copy. True: adopted.
     */
    fun adoptBuiltInFlash(key: String, safetyCopy: (ByteArray, String, String, String, String) -> Unit = { _, _, _, _, _ -> }): Boolean =
        adoptBuiltInFlash(core(key) ?: return false, flashDir, builtIn, safetyCopy, log)

    companion object {
        const val TAG = "RFMFirmware"
        /** the app's: files/Firmware, files/Thumbs, cache/Firmware catalog/feeds.json, files/cores */
        fun android(ctx: Context): FirmwareCenter {
            val log: (String) -> Unit = { Log.i(TAG, it) }
            val lib = FirmwareLibrary(File(ctx.filesDir, "Firmware"), Rfm1Native, log)
            val thumbs = FirmwareThumbs.android(ctx)
            val store = FirmwareStore(lib, cacheFile = File(ctx.cacheDir, "Firmware catalog/feeds.json"), tmpDir = ctx.cacheDir,
                log = log, thumbs = thumbs)
            return FirmwareCenter(lib, store, thumbs, File(ctx.filesDir, "cores"), log)
        }

        /**
         * The .rfm1 an Intent opens (ACTION_VIEW's data, ACTION_SEND's stream), null when it opens none or another
         * kind of file (.rfm1state, .syx: theirs). The manifest also sends application/octet-stream here, so the name
         * decides: one ending .rfm1, or a name with no extension of another kind (the import's check refuses a file
         * that isn't an .rfm1, with its reason).
         */
        fun firmwareUri(intent: Intent?, resolver: ContentResolver): Uri? {
            intent ?: return null
            val uri = when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> stream(intent)
                else -> null
            }?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE } ?: return null
            return uri.takeIf { isFirmwareName(displayName(resolver, uri)) }
        }

        /** "x.rfm1", or a name with no other known extension ("download", "x.bin") */
        fun isFirmwareName(name: String): Boolean {
            val n = name.lowercase()
            if (n.endsWith(".rfm1")) return true
            return OTHER_EXTENSIONS.none { n.endsWith(it) }
        }

        private val OTHER_EXTENSIONS = listOf(".rfm1state", ".rfm1transfer", ".syx", ".zip", ".json", ".png", ".jpg", ".pdf", ".txt", ".apk", ".fwsc")

        @Suppress("DEPRECATION")
        private fun stream(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

        fun displayName(resolver: ContentResolver, uri: Uri): String {
            val n = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            return (n ?: uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.rfm1").ifBlank { "firmware.rfm1" }
        }

        /** see the member; pure (the JVM tests call it) */
        fun adoptBuiltInFlash(info: CoreInfo, flashDir: File, builtIn: List<CoreInfo>,
                              safetyCopy: (ByteArray, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
                              log: (String) -> Unit = {}): Boolean {
            if (!info.imported) return false
            val dst = File(flashDir, CorePicker.flashFileName(info))
            if (dst.exists() || builtIn.any { !it.imported && it.firmwareID == info.firmwareID }) return false
            val old = File(flashDir, "${info.firmwareID}.bin")
            val d = FlashFile.read(old, 0).data ?: return false
            if (d.isEmpty()) return false
            val fromVersion = FlashFile.meta(old)?.version ?: ""
            safetyCopy(d, info.firmwareID, fromVersion, if (fromVersion.isEmpty()) "built in" else "built in $fromVersion", info.version)
            return try {
                FlashFile.write(d, dst, firmwareID = info.firmwareID, version = fromVersion)
                log("flash: ${info.name} takes the built-in one's memory (${old.name})")
                true
            } catch (e: Exception) {
                log("flash: adopting ${old.name}: ${e.message}")
                false
            }
        }
    }
}
