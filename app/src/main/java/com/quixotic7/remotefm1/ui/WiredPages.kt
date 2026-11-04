// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The sheet's page models on A4's firmware/ (FirmwareCenter: the catalog, thumbnails, Get firmware, Add firmware…,
// delete) and A5's data/ (DataSafety through AppModel: states, safety copies, Reset, Back up everything, Restore),
// (the Get firmware pages: GetFirmware.kt).
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

    // (F, iOS 836a983 / 3828820) each firmware's newest version only, in the chosen order (shownCores)
    override val entries: List<FirmwareEntry>
        get() = shownCores(a).map { FirmwareEntry(it.id, it.firmwareID, it.name, it.version, it.imported, it.license) }

    /** (F) the catalog's images came: read again */
    fun thumbsChanged() {
        thumbs.clear()
        thumbGen++
    }

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

// (Get firmware's pages: GetFirmware.kt)

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
                StateItem(s.id, s.name, (s.date * 1000).toLong(), s.version, s.versionNote(running), s.loadQuestion(running),
                          s.loadAction(running), Transfers.otherVersion(a, s))
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
    // (F, iOS 9494ebe) a newer version's state into its own version; Send to another device…
    override fun loadOtherVersion(s: StateItem) { byId[s.id]?.let { st -> Transfers.loadAnywhere(a, st) { m.showMessage(it) } } }
    private var sending by mutableStateOf(false)
    override val sendTransfer: (() -> Unit) = { if (!sending) Transfers.send(a, ctx, { sending = it }) { note = it } }
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
                if (Transfers.isTransfer(name)) { Transfers.open(a, ctx, u); return@runCatching }     // (F: a transfer file)
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
