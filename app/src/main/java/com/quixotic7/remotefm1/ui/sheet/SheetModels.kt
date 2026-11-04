// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// What the sheet's pages need from the milestones built beside A3, as small interfaces: the firmware library and
// catalog (A4: firmware/), the data safety pages (A5: data/), the FM-1's own firmware and cartridges (A7: install/,
// cart/), MIDI (A6). Each has a stand-in here (the built-in cores only; empty lists) used until its package is wired
// in (TODO(wire) in MainScreen.kt), and by the previews and tests. The pages follow RemoteFM1IOS's MainScreen.swift.
package com.quixotic7.remotefm1.ui.sheet

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** one firmware the app can run (iOS CoreInfo, as the pages show it) */
data class FirmwareEntry(
    val id: String,              // the player's key (a built-in core's id, an imported file's key)
    val firmwareId: String,      // the firmware's id (thumbnails, the catalog, states)
    val name: String,
    val version: String,
    val imported: Boolean = false,
    val license: String = "",
)

/** what the Firmware page's card says about a firmware (iOS FirmwareInfo) */
data class FirmwareCard(
    val id: String, val name: String, val version: String,
    val author: String = "", val tag: String = "", val about: String = "", val license: String = "",
    val source: String? = null,
    /** a Fantasy CFW (its manifest's "virtual": true) */
    val fantasy: Boolean = false,
)

/** A4: the firmware here (built in and imported), its card and thumbnail, Get firmware and Add firmware */
interface FirmwarePagesModel {
    val entries: List<FirmwareEntry>
    fun card(e: FirmwareEntry): FirmwareCard
    fun thumbnail(firmwareId: String): ImageBitmap?
    /** "About this firmware": label: value rows and the text under them */
    fun about(e: FirmwareEntry): Pair<List<Pair<String, String>>, String>
    /** firmware here with a newer version to download (the badge) */
    val updates: Int
    /** Get firmware's page (A4), null: the row says it comes later */
    val getFirmwarePage: (@Composable (back: () -> Unit) -> Unit)?
    /** Add firmware… (A4: a document picker), null: not yet */
    val addFirmware: (() -> Unit)?
    /** an imported file's delete (A4), null: none */
    fun delete(e: FirmwareEntry): (() -> Unit)?
    /** an imported file's Fantasy CFW fields (FantasyCfw.Info), null: a built-in core or unknown */
    fun fantasy(e: FirmwareEntry): com.quixotic7.remotefm1.firmware.FantasyCfw.Info? = null
}

/** a named state (iOS SavedState as the list shows it) */
data class StateItem(val id: String, val name: String, val date: Long, val version: String, val note: String = "",
                     val loadQuestion: String = "",
                     /** (F, iOS 3828820) "Load", or "Load anyway" for a newer version's state ("": by the note) */
                     val loadAction: String = "",
                     /** (F, iOS 9494ebe) a newer version's state: the way to its own version ("Get 0.29 and load") */
                     val otherVersion: String? = null)

/** a safety copy (iOS FlashHistory.Snapshot as the list shows it) */
data class SafetyItem(val id: String, val date: Long, val reason: String, val version: String)

/** A5: the running firmware's states, safety copies, Reset, Back up everything / Restore */
interface DataPagesModel {
    val states: List<StateItem>
    val safetyCopies: List<SafetyItem>
    fun saveState(name: String)
    fun loadState(s: StateItem)
    fun renameState(s: StateItem, name: String)
    fun deleteState(s: StateItem)
    /** Share… (a .rfm1state through the share sheet), null: none */
    val shareState: ((StateItem) -> Unit)?
    fun restore(s: SafetyItem)
    fun reset()
    /** Back up everything; the note says what happened */
    fun backUp()
    val backingUp: Boolean
    val backupNote: String
    /** Share the backup…, null until there is one */
    val shareBackup: (() -> Unit)?
    /** Restore from backup… (a folder or files), null: not yet */
    val restoreBackup: (() -> Unit)?
    /** (F) the state loaded into its own version (otherVersion's button) */
    fun loadOtherVersion(s: StateItem) {}
    /** (F, iOS 9494ebe) Send to another device…: one .rfm1transfer to the share sheet; null: not here */
    val sendTransfer: (() -> Unit)? get() = null
    /** "<Firmware> <date>": the Save as… field's first name */
    fun defaultName(firmware: String): String
    val message: String
}

/** A7: Install RemoteFM1 on the FM-1, Send a DX7 cartridge (rows and their pages) */
interface FM1PagesModel {
    val installBadge: String?
    val cartBusy: Boolean
    val installPage: (@Composable (back: () -> Unit) -> Unit)?
    val cartPage: (@Composable (back: () -> Unit) -> Unit)?
}

// ---------------------------------------------------------------- the stand-ins

/** the built-in cores only, thumbnails from assets/thumbs (TODO(wire) A4: firmware/'s library and catalog) */
class BuiltInFirmwarePages(private val ctx: Context, private val list: () -> List<FirmwareEntry>) : FirmwarePagesModel {
    private val thumbs = HashMap<String, ImageBitmap?>()
    override val entries get() = list()
    override fun card(e: FirmwareEntry) = FirmwareCard(e.firmwareId, e.name, e.version, license = e.license)
    override fun thumbnail(firmwareId: String): ImageBitmap? = thumbs.getOrPut(firmwareId) {
        runCatching { ctx.assets.open("thumbs/$firmwareId.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
    override fun about(e: FirmwareEntry) = Pair(
        listOf("licence" to e.license.ifEmpty { "–" }, "id" to e.id),
        "This app is free software under the GNU General Public License, version 3 (GPL-3.0-only). The firmware above " +
            "is free software under ${e.license.ifEmpty { "its licence" }}; it runs as built from its source, through " +
            "FM1VST's core interface.")
    override val updates = 0
    override val getFirmwarePage: (@Composable (back: () -> Unit) -> Unit)? = null
    override val addFirmware: (() -> Unit)? = null
    override fun delete(e: FirmwareEntry): (() -> Unit)? = null
}

/** no states or copies yet (TODO(wire) A5: data/'s StateLibrary, FlashHistory, Backup) */
open class EmptyDataPages : DataPagesModel {
    override val states: List<StateItem> = emptyList()
    override val safetyCopies: List<SafetyItem> = emptyList()
    override fun saveState(name: String) { note = "Saving states comes with the data milestone (A5)." }
    override fun loadState(s: StateItem) {}
    override fun renameState(s: StateItem, name: String) {}
    override fun deleteState(s: StateItem) {}
    override val shareState: ((StateItem) -> Unit)? = null
    override fun restore(s: SafetyItem) {}
    override fun reset() { note = "Reset comes with the data milestone (A5)." }
    override fun backUp() { note = "Back up everything comes with the data milestone (A5)." }
    override val backingUp = false
    private var note by mutableStateOf("")
    override val backupNote get() = note
    override val shareBackup: (() -> Unit)? = null
    override val restoreBackup: (() -> Unit)? = null
    override fun defaultName(firmware: String) =
        "$firmware ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())}"
    override val message get() = note
}

/** (TODO(wire) A7: install/ and cart/) */
object NoFM1Pages : FM1PagesModel {
    override val installBadge: String? = null
    override val cartBusy = false
    override val installPage: (@Composable (back: () -> Unit) -> Unit)? = null
    override val cartPage: (@Composable (back: () -> Unit) -> Unit)? = null
}
