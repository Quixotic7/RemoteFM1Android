// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 App/MainScreen.swift: 9494ebe (Send to another device, an opened .rfm1transfer
// and the From another device page, a newer version's state loaded into its own version: otherVersion, loadLabel,
// loadAnywhere) and a67b1cf + 3ea5b25 (the launch notice: checkUpdates, closeNotice).
package com.quixotic7.remotefm1.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.quixotic7.remotefm1.Mode
import com.quixotic7.remotefm1.data.DataFiles
import com.quixotic7.remotefm1.data.Incoming
import com.quixotic7.remotefm1.data.SavedState
import com.quixotic7.remotefm1.data.TransferFile
import com.quixotic7.remotefm1.firmware.FirmwareStore
import com.quixotic7.remotefm1.firmware.UpdateNotice
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetPage
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.theme.Palette
import java.io.File
import java.time.LocalDate
import kotlin.concurrent.thread

object Transfers {
    private val main = Handler(Looper.getMainLooper())

    /** "0.1.0 (1)" */
    fun appVersion(ctx: Context): String = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()})"
    }.getOrDefault("")

    fun appBuild(ctx: Context): Int = runCatching {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    }.getOrDefault(0)

    /** "Pixel Tablet" */
    fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    /** Send to another device…: one .rfm1transfer (every state, each firmware's current memory, their firmware files)
     *  to the share sheet; [note] gets a failure */
    fun send(a: AppUi, ctx: Context, busy: (Boolean) -> Unit, note: (String) -> Unit) {
        val m = a.model
        busy(true)
        m.saveForBackup {
            val keys = m.catalog.map { it.id }
            val app = appVersion(ctx)
            val device = deviceName()
            thread(name = "rfm-transfer") {
                val r = runCatching {
                    val cur = m.safety.currentFlashes(keys)
                    val lib = m.fw.library
                    val data = TransferFile.make(m.stateLib, cur, lib.files.value, { f -> runCatching { lib.path(f.sha256).readBytes() }.getOrNull() },
                        device, app)
                    val dir = File(ctx.cacheDir, "share/transfer").apply { listFiles()?.forEach { it.delete() }; mkdirs() }
                    val f = File(dir, "RemoteFM1 from ${device.replace("/", "-")} ${LocalDate.now()}.${TransferFile.EXTENSION}")
                    DataFiles.writeAtomic(f, data)
                    f
                }
                main.post {
                    busy(false)
                    r.onSuccess { f -> a.share?.invoke(f, TransferFile.MIME) }
                        .onFailure { e -> note("Can't make the file: ${e.message}") }
                }
            }
        }
    }

    /** a .rfm1transfer opened: its firmware files and states imported (off the main thread), then the transfer page */
    fun open(a: AppUi, ctx: Context, uri: Uri) {
        val m = a.model
        thread(name = "rfm-transfer-open") {
            val cr = ctx.contentResolver
            val name = runCatching { Incoming.name(cr, uri) }.getOrDefault("The file")
            val r = runCatching {
                val d = TransferFile.decode(Incoming.read(cr, uri, max = 512 shl 20), m.stateLib.codec)
                TransferFile.importInto(d, m.stateLib, m.fw.library, ctx.cacheDir) {
                    m.fw.rescan().map { it.firmwareID to it.version }
                }
            }
            main.post {
                m.refreshCatalog()
                r.onSuccess { a.transfer = it; a.transferNote = "" }
                    .onFailure { e -> a.transfer = null; a.transferNote = "$name wasn't read: ${e.message}" }
                a.page = SheetPageId.TRANSFER
                a.ui.sheetOpen = true
            }
        }
    }

    /** a file's name says .rfm1transfer */
    fun isTransfer(name: String) = name.lowercase().endsWith(".${TransferFile.EXTENSION}")

    /** a state made with a newer version: the way to its own version (here, or on GitHub); null none */
    fun otherVersion(a: AppUi, s: SavedState): String? {
        if (!s.newer(a.model.current?.version ?: "")) return null
        if (a.model.catalog.any { it.firmwareID == s.firmwareID && it.version == s.version }) return "Switch to ${s.version} and load"
        if (a.model.fw.store.version(s.firmwareID, s.version) != null) return "Get ${s.version} and load"
        return null
    }

    /** what Load does for a state of the transfer page */
    fun loadLabel(a: AppUi, s: SavedState): String {
        val cores = a.model.catalog
        if (cores.any { it.firmwareID == s.firmwareID && it.version == s.version }) return "Load"
        if (a.model.fw.store.version(s.firmwareID, s.version) != null) return "Get ${s.version} and load"
        cores.firstOrNull { it.firmwareID == s.firmwareID }?.let { return "Load into ${it.version}" }
        return "Not here"
    }

    /** s loaded into its own version of the firmware: the one here, else downloaded (GitHub's releases), else the
     *  firmware's other version here (the version note says so). [done] on the main thread: what happened */
    fun loadAnywhere(a: AppUi, s: SavedState, done: (String) -> Unit) {
        val m = a.model
        val name = s.firmwareName.ifEmpty { s.firmwareID }
        val finish = { key: String ->
            if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
            m.select(key)
            m.loadState(s)
            done(m.message)
        }
        m.catalog.firstOrNull { it.firmwareID == s.firmwareID && it.version == s.version }?.let { finish(it.id); return }
        val store = m.fw.store
        thread(name = "rfm-get-version") {
            store.loadVersions()
            val e = store.version(s.firmwareID, s.version)
            val f = e?.let { store.install(it) }
            main.post {
                m.refreshCatalog()
                when {
                    f != null -> finish(f.key)
                    e != null -> done(store.failure.value.ifEmpty { store.message.value }.let { "$name ${s.version}: $it" })
                    else -> {
                        val c = m.catalog.firstOrNull { it.firmwareID == s.firmwareID && it.id == m.selected }
                            ?: m.catalog.firstOrNull { it.firmwareID == s.firmwareID }
                        if (c != null) finish(c.id) else done("$name ${s.version} isn't on this ${a.deviceWord} or in Get firmware.")
                    }
                }
            }
        }
    }
}

/** From another device: what an opened .rfm1transfer brought, each firmware's current memory to load with one tap */
@Composable
fun TransferPage(a: AppUi) {
    val store = a.model.fw.store
    val progress by store.progress.collectAsState()
    store.allVersions.collectAsState().value.size
    LaunchedEffect(Unit) { store.loadVersionsAsync() }
    var note by remember { mutableStateOf("") }
    SheetPage("From another device", back = { a.page = null }) {
        val r = a.transfer
        if (r != null) {
            SheetNote(r.text)
            if (r.current.isNotEmpty()) {
                SheetSectionTitle("Its current memory")
                SheetList {
                    r.current.forEachIndexed { i, s ->
                        SheetRow("${s.firmwareName.ifEmpty { s.firmwareID }} ${s.version}", value = Transfers.loadLabel(a, s),
                                 divider = i < r.current.size - 1, chevron = false, enabled = progress == null, tag = "transfer-load-$i") {
                            Transfers.loadAnywhere(a, s) { note = it }
                        }
                    }
                }
                SheetNote("Load makes the firmware here play what it played on the other device. The memory here is kept as a " +
                          "safety copy first. Every state that came in is in its firmware's States page.")
            }
            if (r.missing.isNotEmpty()) {
                SheetNote("Not on this ${a.deviceWord}: ${r.missing.joinToString(", ")}. Get it in Get firmware, then load its " +
                          "states from its States page.")
            }
        }
        progress?.let { p: FirmwareStore.Progress -> SheetNote("Getting ${p.id}… ${progressText(p)}") }
        if (a.transferNote.isNotEmpty()) SheetNote(a.transferNote, Palette.orange)
        if (note.isNotEmpty()) SheetNote(note)
    }
}

/**
 * The launch notice: once per launch (not for launches with options: the screenshots, the tests; the "updatenotice"
 * extra shows it anyway, its dismissal ignored), one dialog when a newer build is out (release/android.json) or
 * RemoteFM1's own firmware has an update; Get it / Get firmware / Close. Closed, it stays away until the set changes.
 */
@Composable
fun UpdateNoticeHost(a: AppUi) {
    val ctx = LocalContext.current
    var notice by remember { mutableStateOf<UpdateNotice?>(null) }
    val force = a.launch.updateNotice
    LaunchedEffect(Unit) {
        if (!force && a.launch != UiLaunch()) return@LaunchedEffect
        val prefs = ctx.getSharedPreferences("rfm1", Context.MODE_PRIVATE)
        val build = Transfers.appBuild(ctx)
        val fw = a.model.fw
        val builtIn = a.model.catalog.filter { !it.imported }
        thread(name = "rfm-update-notice") {
            val latest = UpdateNotice.latest()
            fw.store.refreshIfOlder(FirmwareStore.DAY_MS)
            // (only RemoteFM1's own firmware: the owner, 2026-11-02; the other firmware's updates stay in Get firmware)
            val ups = fw.store.updates(builtIn).filter { it.id in UpdateNotice.FIRMWARE_IDS }.map { it.displayName to it.version }
            val n = UpdateNotice.make(build, latest, ups) ?: return@thread
            if (!force && n.dismissed(prefs.getString(UpdateNotice.DISMISSED_KEY, null))) return@thread
            Handler(Looper.getMainLooper()).post { notice = n }
        }
    }
    val n = notice ?: return
    val close = {
        ctx.getSharedPreferences("rfm1", Context.MODE_PRIVATE).edit().putString(UpdateNotice.DISMISSED_KEY, n.signature).apply()
        notice = null
    }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.testTag("update-notice"),
        title = { Text(n.title) },
        text = { Text(n.message(Transfers.appBuild(ctx))) },
        confirmButton = {
            if (n.newBuild != null && n.url != null) {
                TextButton({ close(); runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                    Text("Get it")
                }
            } else if (n.firmware.isNotEmpty()) {
                TextButton({ close(); a.openSheet(SheetPageId.GET_FIRMWARE) }) { Text("Get firmware") }
            }
        },
        dismissButton = {
            if (n.newBuild != null && n.firmware.isNotEmpty()) {
                TextButton({ close(); a.openSheet(SheetPageId.GET_FIRMWARE) }) { Text("Get firmware") }
            }
            TextButton(close) { Text("Close") }
        },
    )
}
