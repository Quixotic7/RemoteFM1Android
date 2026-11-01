// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The sheet's page models on A4's firmware/ (FirmwareCenter: the catalog, thumbnails, Get firmware, Add firmware…,
// delete) and A5's data/ (DataSafety through AppModel: states, safety copies, Reset, Back up everything, Restore),
// and the Get firmware pages (RemoteFM1IOS edf2039 App/GetFirmware.swift: the list, a firmware's page).
package com.quixotic7.remotefm1.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.AppModel
import com.quixotic7.remotefm1.Mode
import com.quixotic7.remotefm1.data.Backup
import com.quixotic7.remotefm1.data.DataFolder
import com.quixotic7.remotefm1.data.FlashHistory
import com.quixotic7.remotefm1.data.SavedState
import com.quixotic7.remotefm1.data.StateFile
import com.quixotic7.remotefm1.data.StateLibrary
import com.quixotic7.remotefm1.firmware.AvailableFirmware
import com.quixotic7.remotefm1.firmware.FirmwareInfo
import com.quixotic7.remotefm1.firmware.FirmwareStore
import com.quixotic7.remotefm1.firmware.InstallState
import com.quixotic7.remotefm1.firmware.ThumbCapture
import com.quixotic7.remotefm1.ui.sheet.DataPagesModel
import com.quixotic7.remotefm1.ui.sheet.FirmwareCard
import com.quixotic7.remotefm1.ui.sheet.FirmwareEntry
import com.quixotic7.remotefm1.ui.sheet.FirmwarePagesModel
import com.quixotic7.remotefm1.ui.sheet.SafetyItem
import com.quixotic7.remotefm1.ui.sheet.SheetButton
import com.quixotic7.remotefm1.ui.sheet.SheetConfirmRow
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetPage
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.sheet.StateItem
import com.quixotic7.remotefm1.ui.sheet.divider
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlin.concurrent.thread

// ---------------------------------------------------------------- A4: firmware

class AppFirmwarePages(private val a: AppUi) : FirmwarePagesModel {
    private val m get() = a.model
    private val fw get() = m.fw
    private val thumbs = HashMap<String, ImageBitmap?>()
    /** moves when a thumbnail was written (the tiles read it again) */
    var thumbGen by mutableIntStateOf(0)
        private set
    private val capture = ThumbCapture(fw.thumbs)
    private val frame = ByteArray(240 * 240 * 4)

    init {
        // each new screen frame: the running firmware's thumbnail made once it has played a few seconds
        a.feed.onFrame = { buf ->
            val c = m.current
            if (c != null) {
                buf.get(frame)
                if (capture.frame(frame, c.firmwareID, a.running, System.currentTimeMillis() / 1000.0)) {
                    thumbs.remove(c.firmwareID)
                    thumbGen++
                }
            }
        }
    }

    override val entries: List<FirmwareEntry>
        get() = m.catalog.map { FirmwareEntry(it.id, it.firmwareID, it.name, it.version, it.imported, it.license) }

    override fun card(e: FirmwareEntry): FirmwareCard {
        val i: FirmwareInfo = fw.info(e.id) ?: return FirmwareCard(e.firmwareId, e.name, e.version, license = e.license)
        return FirmwareCard(i.id, i.name, i.version, i.author, i.tag, i.about, i.license, i.source,
                            fantasy = fantasy(e)?.virtual == true)
    }

    override fun fantasy(e: FirmwareEntry): com.quixotic7.remotefm1.firmware.FantasyCfw.Info? {
        val c = fw.core(e.id) ?: return null
        return if (c.imported) fw.library.details(c.sha256).fantasy else null
    }

    override fun thumbnail(firmwareId: String): ImageBitmap? {
        thumbGen
        return thumbs.getOrPut(firmwareId) { runCatching { fw.thumbs.bitmap(firmwareId)?.asImageBitmap() }.getOrNull() }
    }

    override fun about(e: FirmwareEntry): Pair<List<Pair<String, String>>, String> {
        val c = fw.core(e.id) ?: return Pair(emptyList(), "")
        val gpl = "This app is free software under the GNU General Public License, version 3 (GPL-3.0-only). "
        if (c.imported) {
            val d = fw.library.details(c.sha256)
            val rows = listOf("licence" to c.license, "authors" to d.authors.joinToString(", ").ifEmpty { "–" },
                              "id" to c.firmwareID, "file" to c.fileName.ifEmpty { "–" }, "size" to "${c.fileSize / 1024} KiB",
                              "SHA-256" to c.sha256.take(16) + "…") + FantasyAbout.rows(d.fantasy)
            val text = gpl + "The firmware above is an imported file under ${c.license.ifEmpty { "its licence" }}, from its " +
                "author's release; it runs in the app's WebAssembly interpreter (wasm3, MIT). Its source is at " +
                "${d.sourceURL.ifEmpty { "its author's site" }}." + FantasyAbout.text(d.fantasy) + (if (d.credits.isNotEmpty()) "\n\n${d.credits}" else "")
            return Pair(rows, text)
        }
        val rows = listOf("licence" to c.license.ifEmpty { "–" }, "family" to c.family.ifEmpty { "–" }, "core ABI" to "${c.abi}",
                          "flash" to "${c.flashSize / 1024} KiB")
        val text = gpl + "The firmware above is free software under ${c.license.ifEmpty { "its licence" }}; it runs as built " +
            "from the source named here, through FM1VST's core interface." +
            (if (c.sourceURL.isNotEmpty()) "\n\nSource: ${c.sourceURL}" else "") + (if (c.credits.isNotEmpty()) "\n\n${c.credits}" else "")
        return Pair(rows, text)
    }

    override val updates: Int get() = runCatching { fw.updateCount() }.getOrDefault(0)

    override val getFirmwarePage: (@Composable (back: () -> Unit) -> Unit) = { back -> GetFirmwarePages(a, back) }

    override val addFirmware: (() -> Unit) = { a.pickFiles?.invoke(AppUi.PICK_FIRMWARE) }

    override fun delete(e: FirmwareEntry): (() -> Unit)? = if (!e.imported) null else {
        { fw.delete(e.id, m.selected, select = { m.select(it) }) { c ->
            runCatching { m.safety.safetyCopy(c.id, FlashHistory.Reason.BeforeDelete("${c.name} ${c.version}")) } }
          m.refreshCatalog() }
    }
}

/** a catalog entry is a Fantasy CFW: the installed file's manifest when it is here (it decides), else the catalog's flag */
private fun fantasy(a: AppUi, e: AvailableFirmware): Boolean {
    val lib = a.model.fw.library
    val f = lib.files.value.firstOrNull { it.error == null && it.sha256.isNotEmpty() && it.sha256 == e.sha256 }
    return if (f != null) lib.details(f.sha256).fantasy.virtual else e.virtual
}

/** Get firmware: the list, and a firmware's page (iOS GetFirmwarePage, CatalogDetailPage) */
@Composable
private fun GetFirmwarePages(a: AppUi, back: () -> Unit) {
    val fw = a.model.fw
    val store = fw.store
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { fw.pageOpened() }
    val available by store.available.collectAsState()
    val checking by store.checking.collectAsState()
    val problem by store.problem.collectAsState()
    val progress by store.progress.collectAsState()
    val message by store.message.collectAsState()
    val failure by store.failure.collectAsState()
    val files by fw.library.files.collectAsState()
    files.size
    val builtIn = fw.catalog.value.filter { !it.imported }
    val list = available.filter { !it.builtIn(builtIn) }
    val notes: @Composable () -> Unit = {
        if (failure.isNotEmpty()) SheetNote(failure, Palette.orange) else if (message.isNotEmpty()) SheetNote(message)
    }
    val o = open
    val e = o?.let { id -> list.firstOrNull { it.id == id } }
    if (o != null) {
        if (e == null) {
            SheetPage(o, "Get firmware", back = { open = null }) {
                SheetNote(if (checking) "Getting the list…" else "This firmware isn't in the list any more.")
            }
            return
        }
        CatalogDetail(a, store, e, progress, notes) { open = null }
        return
    }
    SheetPage("Get firmware", "Firmware", back = back) {
        SheetSectionTitle("Available", top = 12)
        if (list.isEmpty()) {
            SheetNote(if (checking) "Getting the list…" else problem.ifEmpty { "No firmware to download yet." },
                      if (problem.isEmpty() || checking) Palette.sheetMuted else Palette.orange)
        } else {
            SheetList {
                list.forEachIndexed { i, it ->
                    val st = store.state(it)
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).divider(i < list.size - 1).clickable { open = it.id }
                        .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp).testTag("catalog-${it.id}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            BadgeLine(fantasy(a, it)) { Text(it.displayName + if (it.beta) "  BETA" else "", style = ui(15, 600), maxLines = 1) }
                            if (it.summary.isNotEmpty()) Text(it.summary, style = ui(12, 500, Palette.sheetMuted2), maxLines = 2)
                            Text(listOf(it.author, it.category, size(it.size)).filter { s -> s.isNotEmpty() && s != "–" }.joinToString(" · "),
                                 style = ui(12, 400, Palette.sheetMuted), maxLines = 1)
                        }
                        CatalogAction(st, if (progress?.id == it.id) progress else null, progress != null,
                                      install = { store.installAsync(it) }, cancel = { store.cancel() })
                    }
                }
            }
        }
        notes()
        SheetList(Modifier.padding(top = 16.dp)) {
            SheetRow(if (checking) "Checking…" else "Check for updates", divider = false, chevron = false, enabled = !checking) {
                store.refreshAsync()
            }
        }
        if (problem.isNotEmpty() && list.isNotEmpty()) SheetNote(problem, Palette.orange)
        SheetNote(footer(store))
    }
}

private fun size(n: Long): String = when {
    n <= 0 -> "–"
    n < 1000 -> "$n bytes"
    n < 1_000_000 -> "${(n + 500) / 1000} KB"
    else -> "%.1f MB".format(n / 1_000_000.0)
}

private fun progressText(p: FirmwareStore.Progress): String =
    p.fraction?.let { "Downloading… ${Math.round(it * 100)} %" } ?: "Downloading… ${size(p.received)}"

private fun footer(store: FirmwareStore): String {
    val names = store.feeds.map { it.name }
    val from = "The list comes from ${if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()}."
    val c = store.checked
    val whenText = if (c == null) " Not checked yet." else {
        val ago = (Instant.now().epochSecond - c.epochSecond)
        " Checked " + when {
            ago < 60 -> "just now"
            ago < 3600 -> "${ago / 60} min ago"
            ago < 86400 -> "${ago / 3600} h ago"
            else -> "${ago / 86400} days ago"
        } + "."
    }
    return from + whenText + " Firmware built into the app isn't listed."
}

/** Install, Update, Installed, or the download's progress (a tap cancels) */
@Composable
private fun CatalogAction(state: InstallState, p: FirmwareStore.Progress?, busy: Boolean, install: () -> Unit, cancel: () -> Unit) {
    if (p != null) {
        Text("${p.fraction?.let { Math.round(it * 100) } ?: "…"} % ✕", Modifier.height(44.dp).clickable { cancel() }.padding(horizontal = 8.dp, vertical = 12.dp),
             style = ui(13, 700, Palette.sheetAccent))
        return
    }
    when (state) {
        is InstallState.Installed -> Text("✓ Installed", Modifier.padding(horizontal = 6.dp), style = ui(13, 600, Palette.sheetMuted2))
        else -> {
            val accent = state is InstallState.Update
            Box(Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (accent) Palette.sheetAccent else Palette.sheetRaised)
                .clickable(enabled = !busy) { install() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(if (accent) "Update" else "Install", style = ui(13, 700, if (accent) Palette.sheetBackground else Palette.sheetText))
            }
        }
    }
}

@Composable
private fun CatalogDetail(a: AppUi, store: FirmwareStore, e: AvailableFirmware, p: FirmwareStore.Progress?,
                          notes: @Composable () -> Unit, back: () -> Unit) {
    val m = a.model
    val lib = m.fw.library
    val state = store.state(e)
    val mine = lib.files.value.filter { it.error == null && (it.coreID == e.id || it.sha256 == e.sha256) }
    val here = mine.filter { it.coreID == e.id }.maxByOrNull { it.version }
    val uri = LocalUriHandler.current
    var asking by remember(e.id) { mutableStateOf(false) }
    SheetPage(e.displayName, "Get firmware", back = back) {
        if (fantasy(a, e)) Box(Modifier.padding(bottom = 6.dp)) { FantasyBadge() }
        Text(listOf(e.version, if (e.beta) "BETA" else "", if (e.author.isNotEmpty()) "· ${e.author}" else "").filter { it.isNotEmpty() }.joinToString(" "),
             style = ui(15, 600, Palette.sheetMuted2))
        val text = e.about.ifEmpty { e.summary }
        if (text.isNotEmpty()) Text(text, Modifier.padding(top = 12.dp), style = ui(14))
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p != null) {
                LinearProgressIndicator(progress = { (p.fraction ?: 0.0).toFloat() }, Modifier.fillMaxWidth(), color = Palette.sheetAccent,
                                        trackColor = Palette.sheetRaised)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(progressText(p), Modifier.weight(1f), style = ui(13, 600, Palette.sheetMuted2))
                    SheetButton("Cancel") { store.cancel() }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (state) {
                        InstallState.NotInstalled -> SheetButton("Install · ${size(e.size)}", accent = true, tag = "catalog-install") { store.installAsync(e) }
                        is InstallState.Update -> {
                            SheetButton("Update to ${e.version}", accent = true) { store.installAsync(e) }
                            Text("${state.version} is here", style = ui(13, 500, Palette.sheetMuted2))
                        }
                        is InstallState.Installed -> {
                            val f = here ?: lib.files.value.firstOrNull { it.error == null && it.sha256 == e.sha256 }
                            if (f != null) SheetButton("Play ${f.name}", accent = true, tag = "catalog-play") {
                                m.refreshCatalog()
                                m.select(f.key)
                                if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
                                a.page = SheetPageId.FIRMWARE
                            }
                            Text("✓ Installed ${state.version}", style = ui(13, 600, Palette.sheetMuted2))
                        }
                    }
                }
                if (state is InstallState.Update) SheetNote("The update replaces the version here. Saved sounds and projects that used it open with the new one.")
                if (mine.isNotEmpty()) {
                    val what = if (mine.size == 1) "${mine[0].name} ${mine[0].version}" else "${mine.size} versions"
                    SheetList {
                        if (asking) SheetConfirmRow("Delete $what from this ${a.deviceWord}?", "Delete", { asking = false }) {
                            asking = false
                            mine.forEach { f -> a.firmware.delete(FirmwareEntry(f.key, f.coreID, f.name, f.version, true))?.invoke() }
                        } else SheetRow("Delete $what…", divider = false, chevron = false) { asking = true }
                    }
                }
            }
        }
        notes()
        SheetSectionTitle("Details")
        SheetList {
            val rows = listOfNotNull("Version" to e.version, e.author.takeIf { it.isNotEmpty() }?.let { "Author" to it },
                                     e.category.takeIf { it.isNotEmpty() }?.let { "Category" to it },
                                     "Licence" to e.license.ifEmpty { "In the file" }, "Size" to size(e.size),
                                     state.installedVersion?.let { "On this device" to it },
                                     "SHA-256" to if (e.sha256.isEmpty()) "–" else e.sha256.take(16) + "…")
            rows.forEachIndexed { i, (k, v) -> SheetRow(k, value = v, divider = i < rows.size - 1, chevron = false) {} }
        }
        val links = listOf("Source code" to e.sourceURL, "Website" to e.site).filter { it.second.startsWith("http") }
        if (links.isNotEmpty()) SheetList(Modifier.padding(top = 12.dp)) {
            links.forEachIndexed { i, (k, u) ->
                SheetRow(k, value = android.net.Uri.parse(u).host, divider = i < links.size - 1) { runCatching { uri.openUri(u) } }
            }
        }
        SheetNote("From ${e.downloads.map { it.feed }.toSortedSet().joinToString(" and ")}. Before it's added, the file is checked " +
                  "against its size and SHA-256, and by the app's .rfm1 loader. It runs only when you pick it.")
    }
}

// ---------------------------------------------------------------- A5: data

class AppDataPages(private val a: AppUi, private val ctx: Context) : DataPagesModel {
    private val m: AppModel get() = a.model
    private val byId = HashMap<String, SavedState>()
    private val snaps = HashMap<String, FlashHistory.Snapshot>()
    private var busy by mutableStateOf(false)
    private var note by mutableStateOf("")
    private var lastZip by mutableStateOf<File?>(null)

    private val running get() = m.current?.version ?: ""

    override val states: List<StateItem>
        get() {
            m.dataGen
            val l = runCatching { m.safety.states(m.selected) }.getOrDefault(emptyList())
            byId.clear()
            return l.map { s ->
                byId[s.id] = s
                StateItem(s.id, s.name, (s.date * 1000).toLong(), s.version, s.versionNote(running), s.loadQuestion(running))
            }
        }

    override val safetyCopies: List<SafetyItem>
        get() {
            m.dataGen
            val l = runCatching { m.safety.safetyCopies(m.selected) }.getOrDefault(emptyList())
            snaps.clear()
            return l.map { s -> snaps[s.id] = s; SafetyItem(s.id, (s.date * 1000).toLong(), s.reason, s.version) }
        }

    override fun saveState(name: String) = m.saveState(name, a.feed.knobs.master.takeIf { a.feed.knobs.masterKnown })
    override fun loadState(s: StateItem) { byId[s.id]?.let { m.loadState(it) } }
    override fun renameState(s: StateItem, name: String) { byId[s.id]?.let { runCatching { m.stateLib.rename(it, name.trim()) } } }
    override fun deleteState(s: StateItem) { byId[s.id]?.let { runCatching { m.stateLib.delete(it) } } }
    override val shareState: ((StateItem) -> Unit) = { s ->
        byId[s.id]?.let { st ->
            runCatching { StateFile.temporaryFile(st, m.stateLib, ctx.cacheDir) }
                .onSuccess { f -> a.share?.invoke(f, StateFile.MIME) }
                .onFailure { e -> note = "Can't share \"${st.name}\": ${e.message}" }
        }
    }
    override fun restore(s: SafetyItem) { snaps[s.id]?.let { m.restoreCopy(it) } }
    override fun reset() = m.resetFlash()

    override fun backUp() {
        if (busy) return
        busy = true
        note = ""
        m.saveForBackup {
            val keys = m.catalog.map { it.id }
            val app = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
            thread(name = "rfm-backup") {
                val r = runCatching {
                    val cur = m.safety.currentFlashes(keys)
                    val tree = DataFolder.tree(ctx)
                    val date = com.quixotic7.remotefm1.data.DataDates.now()
                    if (tree != null) {
                        val path = Backup.zipPath(tree, date)
                        val mf = tree.openWrite(path, "application/zip").use { out -> Backup.writeZip(out, m.stateLib, m.history, cur, app, date) }
                        Pair(DataFolder.backedUp(mf, "your folder › $path"), null)
                    } else {
                        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
                        val f = File(dir, "${Backup.folderName(date)}.zip")
                        val mf = FileOutputStream(f).use { Backup.writeZip(it, m.stateLib, m.history, cur, app, date) }
                        Pair(DataFolder.backedUp(mf, f.name) + " Share the backup… sends it.", f)
                    }
                }
                ctx.mainExecutor.execute {
                    busy = false
                    r.onSuccess { (t, f) -> note = t; if (f != null) lastZip = f }
                        .onFailure { e -> note = DataFolder.backupFailed(e) }
                }
            }
        }
    }

    override val backingUp get() = busy
    override val backupNote get() = note.ifEmpty { "" }
    override val shareBackup: (() -> Unit)? get() = lastZip?.let { f -> { a.share?.invoke(f, "application/zip") } }
    override val restoreBackup: (() -> Unit) = { a.pickFiles?.invoke(AppUi.PICK_BACKUP) }
    override fun defaultName(firmware: String) = StateLibrary.defaultName(firmware)
    override val message get() = m.message

    /** files picked for Restore from backup… (zips and .rfm1state files) */
    fun restoreFrom(uris: List<android.net.Uri>) {
        thread(name = "rfm-restore") {
            val cr = ctx.contentResolver
            val all = Backup.RestoreReport()
            val other = ArrayList<Pair<String, ByteArray>>()
            for (u in uris) runCatching {
                val name = com.quixotic7.remotefm1.data.Incoming.name(cr, u)
                val d = com.quixotic7.remotefm1.data.Incoming.read(cr, u)
                if (com.quixotic7.remotefm1.data.Incoming.isZip(name, d)) all.add(Backup.restoreZip(d.inputStream(), m.stateLib))
                else other.add(name to d)
            }
            if (other.isNotEmpty()) all.add(Backup.restoreFiles(other, m.stateLib))
            val ids = m.catalog.map { it.firmwareID }.toSet()
            ctx.mainExecutor.execute { note = DataFolder.restored(all, ids) }
        }
    }

    /** the Your data folder picked (Settings) */
    fun folderPicked(ok: Boolean) {
        com.quixotic7.remotefm1.folder.RemoteFolderHost.refresh(ctx)     // (a Fantasy CFW's RemoteFM1 folder is in it)
        note = if (ok) DataFolder.backupNote("Your folder") else "That folder can't be used."
    }

}
