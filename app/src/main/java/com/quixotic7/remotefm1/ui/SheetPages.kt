// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 App/MainScreen.swift (the sheet's pages), RFMKit UI/FirmwareTiles.swift
// (FirmwareInfoCard, FirmwareTileGrid), UI/AboutFirmware.swift, Faceplate/StatesList.swift, Faceplate/SafetyCopies.swift
// The pull-down sheet's pages, in iOS's order and wording: Controls (the firmware's data rows, Sound source, View,
// Theme, then Firmware, MIDI & audio, Settings), Firmware (its card, This firmware, the data rows, the built-in tiles,
// Imported, Get firmware / Add firmware), About this firmware, States, Safety copies, MIDI & audio (and its Audio
// output page), Settings. iOS has no dialog in the sheet: About is a page here, questions are asked in the row.
package com.quixotic7.remotefm1.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quixotic7.remotefm1.AudioRoute
import com.quixotic7.remotefm1.AudioState
import com.quixotic7.remotefm1.LinkState
import com.quixotic7.remotefm1.Mode
import com.quixotic7.remotefm1.Source
import com.quixotic7.remotefm1.ui.faceplate.PanelChip
import com.quixotic7.remotefm1.ui.sheet.FirmwareEntry
import com.quixotic7.remotefm1.ui.sheet.FirmwareThumb
import com.quixotic7.remotefm1.ui.sheet.Glyph
import com.quixotic7.remotefm1.ui.sheet.SafetyItem
import com.quixotic7.remotefm1.ui.sheet.SheetAsk
import com.quixotic7.remotefm1.ui.sheet.SheetButton
import com.quixotic7.remotefm1.ui.sheet.SheetHeader
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetPage
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.sheet.SheetStepper
import com.quixotic7.remotefm1.ui.sheet.SheetToggle
import com.quixotic7.remotefm1.ui.sheet.SourceCard
import com.quixotic7.remotefm1.ui.sheet.StateItem
import com.quixotic7.remotefm1.ui.sheet.ThemeSwatches
import com.quixotic7.remotefm1.ui.sheet.ViewTiles
import com.quixotic7.remotefm1.ui.sheet.divider
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette
import java.text.DateFormat
import java.util.Date

/** the sheet's content: its main page or a sub-page */
@Composable
fun SheetContent(a: AppUi) {
    AnimatedContent(a.page, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "sheetpage") { p ->
        Box(Modifier.fillMaxSize()) {
            when (p) {
                null -> MainSheet(a)
                SheetPageId.FIRMWARE -> FirmwarePage(a)
                SheetPageId.ABOUT -> AboutPage(a)
                SheetPageId.STATES -> StatesPage(a)
                SheetPageId.SAFETY -> SafetyPage(a)
                SheetPageId.GET_FIRMWARE -> a.firmware.getFirmwarePage?.invoke { a.page = SheetPageId.FIRMWARE }
                    ?: LaterPage("Get firmware", "Firmware", "The list of firmware to download comes with the firmware milestone (A4).") { a.page = SheetPageId.FIRMWARE }
                SheetPageId.MIDI -> MidiAudioPage(a)
                SheetPageId.AUDIO_OUTPUT -> AudioOutputPage(a)
                SheetPageId.MIDI_INPUT -> MidiInputPage(a)
                SheetPageId.KEY_CHANNEL -> KeyChannelPage(a)
                SheetPageId.MIDI_MODE -> MidiModePage(a)
                SheetPageId.TRANSFER -> TransferPage(a)
                SheetPageId.SETTINGS -> SettingsPage(a)
                SheetPageId.INSTALL -> a.fm1.installPage?.invoke { a.page = SheetPageId.SETTINGS }
                    ?: LaterPage("Install RemoteFM1", "Settings", "Installing RemoteFM1 on the FM-1 comes with milestone A7.") { a.page = SheetPageId.SETTINGS }
                SheetPageId.CART -> a.fm1.cartPage?.invoke { a.page = if (a.cartBackTitle == "Firmware") SheetPageId.FIRMWARE else SheetPageId.SETTINGS }
                    ?: LaterPage("Send a DX7 cartridge", "Settings", "Sending cartridges comes with milestone A7.") { a.page = SheetPageId.SETTINGS }
            }
        }
    }
}

@Composable
private fun LaterPage(title: String, back: String, note: String, go: () -> Unit) {
    SheetPage(title, back, go) { SheetNote(note) }
}

private fun date(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

// ---------------------------------------------------------------- Controls (the main page)

@Composable
private fun MainSheet(a: AppUi) {
    val m = a.model
    Column(Modifier.fillMaxSize()) {
        SheetHeader("Controls") { a.closeSheet() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            SheetSectionTitle(a.current?.let { "${it.name} ${it.version}" } ?: "Firmware", top = 18)
            DataRows(a, from = null)
            // (F, iOS 836a983: was "Sound source", read as where the sound comes out. This picks where the firmware
            // runs; where the sound comes out is Audio output, right under it)
            SheetSectionTitle("Firmware runs on")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceCard("This ${a.deviceWord}", "Emulator, play by touch", m.source == Source.TABLET, Palette.sheetAccent,
                           "source-tablet") { a.pick(Source.TABLET) }
                SourceCard("My FM-1", fm1Line(a), m.source == Source.FM1,
                           if (a.chip == PanelChip.IN_USE) Palette.sheetAccent else Palette.linkedDot, "source-fm1") { a.pick(Source.FM1) }
            }
            // (the FM-1 card's actions: the Bluetooth pairing, as on iOS)
            if (m.source == Source.FM1) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SheetButton("Bluetooth…", tag = "button-bluetooth") { a.overlay = Overlay.BLUETOOTH }
                }
                SheetNote("The FM-1 plays through its own speaker and headphone jack; this ${a.deviceWord} is its remote.")
            } else {
                SheetList(Modifier.padding(top = 10.dp)) {
                    SheetRow("Audio output", value = AudioRoute.kindTitle(m.pickable, m.outputPick), divider = false, tag = "row-output-main") {
                        a.page = SheetPageId.AUDIO_OUTPUT
                    }
                }
            }
            SheetSectionTitle("View · or swipe sideways")
            ViewTiles(a.ui)
            SheetList(Modifier.padding(top = 12.dp)) {
                SheetToggle("Firmware's button names", a.buttonNames, tag = "toggle-names") { a.useButtonNames(it) }
                SheetToggle("Screen protector", a.protector.on, divider = false, tag = "toggle-protector") { a.protector.set(it) }
            }
            SheetNote("Double-tap the display to see it alone; double-tap again or swipe down to close.")
            SheetSectionTitle("Theme")
            ThemeSwatches(a.themes) { a.ui.editorOpen = true }
            SheetSectionTitle("Background")
            com.quixotic7.remotefm1.ui.sheet.SkinSettings(a.skins, a.pickBackground?.let { p -> { a.skinFailed = false; p() } }, a.skinFailed)
            SheetList(Modifier.padding(top = 20.dp)) {
                SheetRow("Firmware", value = a.current?.name ?: "–", badge = a.firmware.updates, tag = "row-firmware") { a.page = SheetPageId.FIRMWARE }
                SheetRow("MIDI & audio", tag = "row-midi") { a.page = SheetPageId.MIDI }
                SheetRow("Settings", divider = false, tag = "row-settings") { a.page = SheetPageId.SETTINGS }
            }
            if (m.message.isNotEmpty()) SheetNote(m.message, Palette.orange)
        }
    }
}

private fun fm1Line(a: AppUi): String {
    val m = a.model
    return when (val s = m.state) {
        is LinkState.InUse -> "In use by another app"
        is LinkState.Connected -> "Remote control · ${if (s.bluetooth) "Bluetooth" else "USB"}${if (m.backend.isFake) " (fake)" else ""}"
        LinkState.NotFound -> "Not found · plug in or pair over Bluetooth"
        else -> "Remote control · USB or Bluetooth"
    }
}

/** the running firmware's data: Save state…, States, Safety copies, Back up everything, Reset firmware… */
@Composable
private fun DataRows(a: AppUi, from: SheetPageId?) {
    val none = a.current == null
    val d = a.data
    var asking by remember { mutableStateOf(false) }
    SheetList {
        SheetRow("Save state…", chevron = false, enabled = !none, tag = "row-savestate") { a.openData(SheetPageId.STATES, from, saving = true) }
        SheetRow("States", value = "${d.states.size}", enabled = !none, tag = "row-states") { a.openData(SheetPageId.STATES, from) }
        SheetRow("Safety copies", value = "${d.safetyCopies.size}", enabled = !none, tag = "row-safety") { a.openData(SheetPageId.SAFETY, from) }
        SheetRow(if (d.backingUp) "Backing up…" else "Back up everything", chevron = false, tag = "row-backup") { if (!d.backingUp) d.backUp() }
        d.shareBackup?.let { SheetRow("Share the backup…", chevron = false, action = it) }
        if (asking) {
            SheetAsk("Start ${a.current?.name ?: "the firmware"} with a clean memory? A copy of the current one is kept.", "Reset",
                     cancel = { asking = false }, confirm = { asking = false; d.reset() })
        } else {
            SheetRow("Reset firmware…", divider = false, chevron = false, enabled = !none, tag = "row-reset") { asking = true }
        }
    }
    if (d.backupNote.isNotEmpty()) SheetNote(d.backupNote)
}

// ---------------------------------------------------------------- Firmware

@Composable
private fun FirmwarePage(a: AppUi) {
    val m = a.model
    val all = a.firmware.entries
    val builtIn = all.filter { !it.imported }
    val imported = all.filter { it.imported }
    val c = a.current
    SheetPage(c?.name ?: "Firmware", back = { a.page = null }) {
        androidx.compose.runtime.SideEffect { Recompositions.firmwarePage++ }
        if (c != null) {
            FirmwareInfoCard(a, c)
            SheetNote("${c.version}${if (c.imported) " · imported" else " · built in"}${if (a.running) " · running" else ""}")
            PerfNote(a)                                         // (E: PerfGuard, a firmware too heavy here)
            AudioInputSection(a, c)                             // (E: ain1, iOS c47ac4f)
        }
        SheetSectionTitle("This firmware", top = 12)
        SheetList {
            // (G, iOS f47cdd4 + dc771c8) the firmware's own web editor (the catalog's link, else the author's page the
            // app knows); RemoteFM1's is its cartridge page; the screen reader for the firmwares that speak to it
            val details = m.fw.store.details
            if (c != null && com.quixotic7.remotefm1.managers.ManagerList.editor(c.firmwareId, details) != null) {
                SheetRow("Open its editor", value = "presets, backups", tag = "row-editor") { a.overlay = Overlay.MANAGER }
            } else if (c?.firmwareId == com.quixotic7.remotefm1.cart.CoreCartTransport.FIRMWARE_ID) {
                SheetRow("Open its editor", value = "DX7 cartridges", tag = "row-editor") {
                    a.cartBackTitle = "Firmware"; a.page = SheetPageId.CART
                }
            }
            if (c != null && com.quixotic7.remotefm1.managers.ManagerList.hasReader(c.firmwareId)) {
                SheetRow("Screen reader", value = "reads the screen out", tag = "row-reader") { a.overlay = Overlay.READER }
            }
            SheetRow("About this firmware", enabled = c != null, tag = "row-about") { a.page = SheetPageId.ABOUT }
            SheetRow("Restart the firmware", divider = false, chevron = false, tag = "row-restart") { m.restartFirmware() }
        }
        Box(Modifier.padding(top = 12.dp)) { Column { DataRows(a, from = SheetPageId.FIRMWARE) } }
        // (a Fantasy CFW that reads files) its RemoteFM1 folder
        c?.let { a.firmware.fantasy(it) }?.takeIf { it.virtual && it.usesFolder }?.let { FantasyFolderSection(a, it) }
        // (F, iOS 836a983) the lists sorted; the big Get more firmware button under the built-ins
        a.model.fw.store.allVersions.collectAsState().value.size
        SheetSectionTitle("Sort")
        ChipPicker(com.quixotic7.remotefm1.firmware.FirmwareListing.Sort.entries.map { it to it.title }, a.listPrefs.sort, "sort") {
            a.listPrefs.sort(it)
        }
        SheetSectionTitle("Built in", top = 14)
        FirmwareTileGrid(a, builtIn)
        Box(Modifier.padding(top = 14.dp)) {
            GetMoreButton(toDownload(a), a.firmware.updates) { a.page = SheetPageId.GET_FIRMWARE }
        }
        SheetSectionTitle("Imported")
        if (imported.isNotEmpty()) {
            FirmwareTileGrid(a, imported)
            Spacer(Modifier.height(10.dp))
        }
        SheetList {
            SheetRow("Add firmware file…", divider = false, enabled = a.firmware.addFirmware != null, tag = "row-addfirmware") {
                a.firmware.addFirmware?.invoke()
            }
        }
        SheetNote(if (imported.isEmpty()) "Add .rfm1 firmware files here, or open one from a file manager." else
                      "Long-press an imported firmware to delete it.")
        if (m.mode != Mode.CORE) {
            SheetNote("The test pattern is on (Settings · Diagnostics): the keys play nothing.")
            Box(Modifier.padding(top = 8.dp)) { SheetButton("Run the firmware") { m.useMode(Mode.CORE) } }
        }
        if (m.message.isNotEmpty()) SheetNote(m.message, Palette.orange)
    }
}

/** the running firmware's card at the top of the Firmware page */
@Composable
private fun FirmwareInfoCard(a: AppUi, e: FirmwareEntry) {
    val info = a.firmware.card(e)
    var more by remember(e.id) { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.sheetSurface)
        .padding(14.dp).testTag("firmware-card"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FirmwareThumb(a.firmware.thumbnail(e.firmwareId), e.firmwareId, info.name, 12,
                          Modifier.size(92.dp).border(1.dp, Palette.sheetBorder, RoundedCornerShape(12.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(info.name, style = TextStyle(fontFamily = com.quixotic7.remotefm1.ui.theme.RfmFonts.title,
                                                  fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Palette.sheetText), maxLines = 2)
                Text(listOf(info.version, if (a.running) "running" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                     style = ui(13, 600, Palette.sheetAccent))
                if (info.fantasy) FantasyBadge(Modifier.padding(vertical = 2.dp))
                if (info.author.isNotEmpty()) Text("by ${info.author}", style = ui(13, 500, Palette.sheetMuted2), maxLines = 2)
                if (info.tag.isNotEmpty()) Text(info.tag, Modifier.padding(top = 3.dp), style = ui(13, 600), maxLines = 3)
            }
        }
        if (info.about.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info.about, style = ui(13, 400, Palette.sheetMuted), maxLines = if (more) Int.MAX_VALUE else 3,
                     overflow = TextOverflow.Ellipsis)
                if (info.about.length > 140) Text(if (more) "Less" else "More", Modifier.clickable { more = !more },
                                                  style = ui(13, 700, Palette.sheetAccent))
            }
        }
        info.source?.let { u ->
            Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(50)).background(Palette.sheetAccent)
                .clickable { runCatching { uri.openUri(u) } }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph("code", Palette.sheetBackground, 14)
                Text(if ("github" in u) "View source on GitHub" else "View source", Modifier.weight(1f),
                     style = ui(15, 700, Palette.sheetBackground))
                Glyph("up-right", Palette.sheetBackground, 13)
            }
        }
    }
}

/** the firmware list as a grid of boxes (a tap switches; a long press offers an imported one's Delete inline) */
@Composable
private fun FirmwareTileGrid(a: AppUi, cores: List<FirmwareEntry>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = maxOf(1, ((maxWidth.value + 10) / (110 + 10)).toInt()).coerceAtMost(6)
        val tw = (maxWidth.value - (cols - 1) * 10) / cols
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in cores.chunked(cols)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in row) FirmwareTile(a, c, tw)
                }
            }
        }
    }
}

@Composable
private fun FirmwareTile(a: AppUi, c: FirmwareEntry, w: Float) {
    val on = c.id == a.model.selected
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.width(w.dp).clip(shape).background(if (on) Palette.sheetRaised else Palette.sheetSurface)
        .clickable { a.model.select(c.id) }.testTag("tile-fw-${c.id}").padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            FirmwareThumb(a.firmware.thumbnail(c.firmwareId), c.firmwareId, c.name, 14,
                          Modifier.fillMaxWidth().border(if (on) 2.5.dp else 1.dp, if (on) Palette.sheetAccent else Palette.sheetBorder,
                                                         RoundedCornerShape(14.dp)))
            if (on) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).clip(RoundedCornerShape(50))
                .background(Palette.sheetAccent), contentAlignment = Alignment.Center) { Glyph("check", Palette.sheetBackground, 11) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(c.name, style = ui(13, 700, if (on) Palette.sheetAccent else Palette.sheetText), maxLines = 1)
            Text(c.version, style = ui(11, 500, Palette.sheetMuted2), maxLines = 1)
        }
    }
}

/** About this firmware (a page: no dialogs in the sheet) */
@Composable
private fun AboutPage(a: AppUi) {
    val c = a.current
    SheetPage("About this firmware", "Firmware", back = { a.page = SheetPageId.FIRMWARE }) {
        if (c == null) return@SheetPage
        val (rows, text) = a.firmware.about(c)
        SheetSectionTitle("${c.name} ${c.version}", top = 12)
        if (a.firmware.fantasy(c)?.virtual == true) Box(Modifier.padding(bottom = 10.dp)) { FantasyBadge() }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.sheetSurface).padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for ((k, v) in rows) Row {
                Text(k, Modifier.width(96.dp), style = ui(12, 500, Palette.sheetMuted).copy(fontFamily = FontFamily.Monospace))
                Text(v, style = ui(12, 500).copy(fontFamily = FontFamily.Monospace))
            }
        }
        SheetSectionTitle("RemoteFM1")
        SheetNote(text)
        a.firmware.delete(c)?.let { del -> Box(Modifier.padding(top = 12.dp)) { SheetButton("Delete ${c.name} ${c.version}") { del(); a.page = SheetPageId.FIRMWARE } } }
    }
}

// ---------------------------------------------------------------- States, Safety copies

private val SheetPageId?.backTitle get() = if (this == null) "Controls" else "Firmware"

@Composable
private fun StatesPage(a: AppUi) {
    val c = a.current
    val d = a.data
    SheetPage("States", a.dataBack.backTitle, back = { a.page = a.dataBack }) {
        SheetSectionTitle(c?.let { "${it.name} ${it.version}" } ?: "Firmware", top = 12)
        StatesList(a, c?.name ?: "", d.states)
        SheetNote("A state is the firmware's memory (its patches, banks, sequences) with MASTER, the octave and the keys' " +
                  "touch, under a name. A state saved here opens in RemoteFM1 on an iPhone or iPad, and the other way. " +
                  "Loading keeps a safety copy of the current memory first. Share… sends one as a .rfm1state file.")
        SheetList(Modifier.padding(top = 12.dp)) {
            SheetRow("Safety copies", value = "${d.safetyCopies.size}", divider = false) { a.page = SheetPageId.SAFETY; a.saveOpen = false }
        }
        if (d.message.isNotEmpty()) SheetNote(d.message)
    }
}

/** Save as… (a name, "<Firmware> <date>" to start with), the states newest first, Load asked again inline, and per
 *  state Rename, Share… and Delete (asked again) */
@Composable
private fun StatesList(a: AppUi, firmware: String, states: List<StateItem>) {
    val d = a.data
    var saving by remember { mutableStateOf(a.saveOpen) }
    var saveName by remember { mutableStateOf(if (a.saveOpen) d.defaultName(firmware) else "") }
    var ask by remember { mutableStateOf<String?>(null) }           // "load:<id>" / "delete:<id>"
    var editing by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    SheetList {
        if (saving) {
            NameField(saveName, { saveName = it }, "Save", cancel = { saving = false }) { saving = false; d.saveState(saveName) }
        } else {
            SheetRow("Save as…", divider = false, chevron = false, tag = "row-saveas") { saveName = d.defaultName(firmware); saving = true }
        }
    }
    if (states.isEmpty()) {
        SheetNote("No states of $firmware yet. Save as… keeps the firmware's memory under a name.")
        return
    }
    SheetList(Modifier.padding(top = 12.dp)) {
        states.forEachIndexed { i, s ->
            val div = i < states.size - 1
            Box(Modifier.divider(div)) {
                when {
                    ask == "load:${s.id}" -> Column {
                        SheetAsk(s.loadQuestion.ifEmpty { "Load \"${s.name}\"? A safety copy of the current memory is kept first." },
                                 s.loadAction.ifEmpty { if (s.note.isEmpty()) "Load" else "Load anyway" }, { ask = null }) { ask = null; d.loadState(s) }
                        // (F, iOS 9494ebe) a newer version's state: its own version, here or from GitHub
                        s.otherVersion?.let { t ->
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                                SheetButton(t, accent = true, tag = "load-other") { ask = null; d.loadOtherVersion(s) }
                            }
                        }
                    }
                    ask == "delete:${s.id}" -> SheetAsk("Delete \"${s.name}\" from the library?", "Delete", { ask = null }) {
                        ask = null; editing = null; d.deleteState(s)
                    }
                    editing == s.id -> Column {
                        NameField(editName, { editName = it }, "Rename", cancel = { editing = null }) { editing = null; d.renameState(s, editName) }
                        Row(Modifier.fillMaxWidth()) {
                            d.shareState?.let { sh ->
                                Text("Share…", Modifier.height(40.dp).clickable { sh(s) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                     style = ui(14, 700, Palette.sheetAccent))
                            }
                            Spacer(Modifier.weight(1f))
                            Text("Delete…", Modifier.height(40.dp).clickable { ask = "delete:${s.id}" }.padding(horizontal = 16.dp, vertical = 10.dp),
                                 style = ui(14, 700, Palette.red))
                        }
                    }
                    else -> Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.name, style = ui(15, 600), maxLines = 1)
                            Text(listOf(date(s.date), s.version, s.note).filter { it.isNotEmpty() }.joinToString(" · "),
                                 style = ui(12, 500, if (s.note.isEmpty()) Palette.sheetMuted2 else Palette.orange), maxLines = 1)
                        }
                        Text("…", Modifier.size(40.dp, 44.dp).clickable { editName = s.name; editing = s.id; ask = null }.padding(top = 8.dp),
                             style = ui(18, 700, Palette.sheetMuted))
                        Text("Load", Modifier.height(44.dp).clickable { ask = "load:${s.id}"; editing = null }.padding(horizontal = 6.dp, vertical = 12.dp),
                             style = ui(14, 700, Palette.sheetAccent))
                    }
                }
            }
        }
    }
}

@Composable
private fun NameField(text: String, set: (String) -> Unit, action: String, cancel: () -> Unit, confirm: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(text, set, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.sheetRaised)
            .padding(horizontal = 10.dp, vertical = 10.dp).testTag("name-field"),
                       textStyle = ui(15, 600), singleLine = true, cursorBrush = SolidColor(Palette.sheetAccent),
                       keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { confirm() }))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SheetButton("Cancel", action = cancel)
            SheetButton(action, accent = true, enabled = text.isNotBlank(), tag = "name-confirm", action = confirm)
        }
    }
}

@Composable
private fun SafetyPage(a: AppUi) {
    val d = a.data
    var asking by remember { mutableStateOf<String?>(null) }
    SheetPage("Safety copies", a.dataBack.backTitle, back = { a.page = a.dataBack }) {
        SheetSectionTitle(a.current?.let { "${it.name} ${it.version}" } ?: "Firmware", top = 12)
        val copies = d.safetyCopies
        if (copies.isEmpty()) {
            SheetNote("No safety copies yet: one is kept before an update, a delete, a reset or a restore, and every few " +
                      "minutes while the memory changes.")
        } else {
            SheetList {
                copies.forEachIndexed { i, s: SafetyItem ->
                    Box(Modifier.divider(i < copies.size - 1)) {
                        if (asking == s.id) {
                            SheetAsk("Restore the copy of ${date(s.date)}? A copy of the current memory is kept first.", "Restore",
                                     { asking = null }) { asking = null; d.restore(s) }
                        } else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
                                   verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(date(s.date), style = ui(15, 600), maxLines = 1)
                                Text(s.reason + if (s.version.isEmpty()) "" else " · ${s.version}", style = ui(12, 500, Palette.sheetMuted2), maxLines = 1)
                            }
                            Text("Restore", Modifier.height(44.dp).clickable { asking = s.id }.padding(horizontal = 6.dp, vertical = 12.dp),
                                 style = ui(14, 700, Palette.sheetAccent))
                        }
                    }
                }
            }
        }
        SheetNote("A copy of the firmware's memory is kept before an update, a delete, a reset or a restore, and while you " +
                  "play: the last 10 saves and one a day for a week. Restore puts a copy back (the current memory is kept " +
                  "as a copy first).")
        if (d.message.isNotEmpty()) SheetNote(d.message)
    }
}

// ---------------------------------------------------------------- MIDI & audio

@Composable
private fun MidiAudioPage(a: AppUi) {
    val m = a.model
    val midi = m.midi
    val ms = midi.settings
    val scroll = rememberScrollState()
    var knobsAt by remember { mutableStateOf(-1) }
    var omxAt by remember { mutableStateOf(-1) }
    // (screenshots: -sheetscroll knobs | omx)
    LaunchedEffect(knobsAt, omxAt) {
        val to = when (a.launch.sheetScroll) { "knobs" -> knobsAt; "omx" -> omxAt; else -> -1 }
        if (to >= 0) scroll.scrollTo(to)
    }
    SheetPage("MIDI & audio", back = { a.page = null }, scroll = scroll) {
        SheetSectionTitle("Audio", top = 12)
        SheetList {
            SheetToggle("Sound", m.soundOn, tag = "toggle-sound") { m.setSound(it) }
            SheetRow("Audio output", value = AudioRoute.kindTitle(m.pickable, m.outputPick), divider = false, tag = "row-output") {
                a.page = SheetPageId.AUDIO_OUTPUT
            }
        }
        LiveAudioLines(m)
        SheetList(Modifier.padding(top = 12.dp)) {
            SheetToggle("Sound through USB devices (experimental)", m.usbAudio, divider = false, tag = "toggle-usbaudio") { m.useUsbAudio(it) }
        }
        SheetNote(AudioRoute.USB_WARNING, if (m.usbAudio) Palette.orange else Palette.sheetMuted)
        SheetSectionTitle("MIDI")
        SheetList {
            SheetRow("MIDI input", value = midiInputValue(midi.input), tag = "row-midiinput") { a.page = SheetPageId.MIDI_INPUT }
            KeyChannelRows(ms.keyChannel, ms.keyNotesToFirmware, { midi.update(ms.copy(keyNotesToFirmware = it)) }) {
                a.page = SheetPageId.KEY_CHANNEL
            }
            SheetStepper("Transpose", ms.transpose, -2..2, text = { if (it == 0) "0" else "${if (it > 0) "+" else "−"}${kotlin.math.abs(it)} oct" }) {
                midi.update(ms.copy(transpose = it))
            }
            SheetToggle("Velocity & pressure from my FM-1", ms.keyTouch, tag = "toggle-keytouch") { midi.update(ms.copy(keyTouch = it)) }
            SheetRow("Bluetooth MIDI devices", tag = "row-bluetooth") { a.overlay = Overlay.BLUETOOTH }
            SheetRow("Look for the FM-1 again", divider = false, chevron = false) { m.link.rescan() }
        }
        SheetNote("Keys from MIDI: notes on that channel (or any) play the keys, transposed by octaves. Notes on other " +
                  "channels, notes outside the keys, every note when Off, and whatever the learned knobs don't take go to " +
                  "the firmware's MIDI in. Velocity & pressure: the FM-1's keys play at their own velocity and send their " +
                  "pressure to the firmware (RemoteFM1 0.3.1 or later; off: velocity 100).")
        SheetNote("FM-1 link: ${m.statusText}")
        Box(Modifier.onGloballyPositioned { knobsAt = it.positionInParent().y.toInt() }) {
            Column {
                MidiMapSection(midi.store, ms.useLearnedMap, { midi.update(ms.copy(useLearnedMap = it)) }, a.feed.names,
                               pickMode = { a.modeSource = it; a.page = SheetPageId.MIDI_MODE }, learn = { midi.startLearn(a.ui) })
            }
        }
        Box(Modifier.onGloballyPositioned { omxAt = it.positionInParent().y.toInt() }) {
            Column {
                SheetSectionTitle("OMX-27")
                OmxSection(midi.omx)
            }
        }
    }
}

@Composable
private fun MidiInputPage(a: AppUi) {
    SheetPage("MIDI input", "MIDI & audio", back = { a.page = SheetPageId.MIDI }) { MidiInputList(a.model.midi.input) }
}

@Composable
private fun KeyChannelPage(a: AppUi) {
    val midi = a.model.midi
    SheetPage("Keys from MIDI", "MIDI & audio", back = { a.page = SheetPageId.MIDI }) {
        KeyChannelList(midi.settings.keyChannel) { midi.update(midi.settings.copy(keyChannel = it)) }
    }
}

@Composable
private fun MidiModePage(a: AppUi) {
    SheetPage("How it turns", "MIDI & audio", back = { a.page = SheetPageId.MIDI }) {
        MidiModeList(a.model.midi.store, a.modeSource ?: "")
    }
}

/** the outputs to choose from (Android has no system route picker: a page, a check on the chosen one) */
@Composable
private fun AudioOutputPage(a: AppUi) {
    val m = a.model
    SheetPage("Audio output", "MIDI & audio", back = { a.page = SheetPageId.MIDI }) {
        val outs = m.pickable
        val kinds = AudioRoute.kinds(outs)
        SheetList(Modifier.padding(top = 12.dp)) {
            kinds.forEachIndexed { i, k ->
                SheetRow(AudioRoute.kindTitle(outs, k), chevron = false, check = m.outputPick == k, divider = i < kinds.size - 1,
                         tag = "output-$k") { m.chooseOutput(k) }
            }
        }
        if (m.soundOn) Live { SheetNote("Playing on ${AudioRoute.name(m.outputList, m.playingOn)}.") }
        if (m.usbAudio && m.outputList.any { it.usb }) SheetNote(AudioRoute.USB_WARNING, Palette.orange)
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsPage(a: AppUi) {
    val d = a.data
    SheetPage("Settings", back = { a.page = null }) {
        BackgroundSection(a)
        SheetSectionTitle("Keys")
        SheetList {
            SheetToggle("Step numbers on the keys", a.ui.stepNumbers, divider = false, tag = "toggle-steps") { a.useStepNumbers(it) }
        }
        SheetNote("Shows 1–16 on the lower keys, for a firmware in a step mode.")
        SheetSectionTitle("Diagnostics")
        SheetList {
            SheetRow("Diagnostics", divider = false, tag = "row-diagnostics") { a.overlay = Overlay.DIAGNOSTICS }
        }
        SheetNote("Diagnostics has the link, the core, the unit's HELLO_R and the test pattern mode.")
        SheetSectionTitle("FM-1 firmware")
        SheetList {
            SheetRow("Install RemoteFM1 on the FM-1", value = a.fm1.installBadge, tag = "row-install") { a.page = SheetPageId.INSTALL }
            SheetRow("Send a DX7 cartridge", value = if (a.fm1.cartBusy) "sending…" else null, divider = false, tag = "row-cart") {
                a.cartBackTitle = "Settings"; a.page = SheetPageId.CART
            }
        }
        SheetNote("A DX7 cartridge (.syx) becomes a user bank on RemoteFM1 0.3.0 or later.")
        SheetSectionTitle("Your data")
        SheetList {
            SheetRow(if (d.backingUp) "Backing up…" else "Back up everything", chevron = false) { if (!d.backingUp) d.backUp() }
            d.shareBackup?.let { SheetRow("Share the backup…", chevron = false, action = it) }
            SheetRow("Restore from backup…", chevron = false, enabled = d.restoreBackup != null) { d.restoreBackup?.invoke() }
            // (F, iOS 9494ebe) one .rfm1transfer for another phone, tablet, iPhone or iPad
            d.sendTransfer?.let { SheetRow("Send to another device…", chevron = false, tag = "row-transfer", action = it) }
            SheetRow("Folder for backups…", divider = false, chevron = false, enabled = a.pickFolder != null) { a.pickFolder?.invoke() }
        }
        SheetNote(d.backupNote.ifEmpty {
            "Back up everything writes every state, each firmware's current memory and the newest safety copies into " +
                "one backup. Restore adds a backup's states to the library; nothing is overwritten."
        })
        if (d.sendTransfer != null) SheetNote("Send to another device makes one file with every state, each firmware's current " +
            "memory and the firmware files they need. Send it or save it, then open it on the other device (Android, iPhone " +
            "or iPad): everything is added there, and its current memory loads with one tap.")
    }
}

/** (G, RemoteFM1IOS dfbc827 + e527b74: the battery) what the app does when you leave it or lock the screen: one row a
 *  choice, a check on the chosen */
@Composable
private fun BackgroundSection(a: AppUi) {
    val b = a.model.battery
    val now = b.policy
    SheetSectionTitle("When you leave the app", top = 12)
    SheetList {
        for (mode in com.quixotic7.remotefm1.BackgroundMode.entries) {
            SheetRow(mode.title, chevron = false, check = mode == now.mode, tag = "bg-${mode.raw}") { b.use(now.copy(mode = mode)) }
        }
        SheetToggle("Stop after 10 quiet minutes", now.idleStop, divider = false, tag = "toggle-idlestop") { b.use(now.copy(idleStop = it)) }
    }
    SheetNote(now.mode.detail + " Quiet: no key, knob, MIDI or sound. Pause in the notification stops it too; it saves " +
              "first and plays again when you come back." + (if (b.lastStop.isEmpty()) "" else " Last stopped: ${b.lastStop}."))
}

/** (G, iOS 14c9b96: the status read only in small Live views) what reads the engine's status, polled every 66 ms, in
 *  a scope of its own: only these lines recompose with it, not the page */
@Composable
private fun Live(content: @Composable () -> Unit) = content()

@Composable
private fun LiveAudioLines(m: com.quixotic7.remotefm1.AppModel) {
    AudioRoute.hint(m.outputList, m.playingOn)?.let { SheetNote(it) }
    val st = m.audioState
    SheetNote(if (m.mode == Mode.CORE && m.soundOn) st.line + m.engineStatus.let { s ->
        val rate = s.optDouble("rate")
        if (rate > 0) "; %.1f ms ahead, %d underruns".format(s.optInt("latency_frames") * 1000.0 / rate, s.optInt("underruns")) else ""
    } else "No sound: the firmware runs on a timer.",
              if (st is AudioState.Failed || st is AudioState.Busy) Palette.orange else Palette.sheetMuted)
}
