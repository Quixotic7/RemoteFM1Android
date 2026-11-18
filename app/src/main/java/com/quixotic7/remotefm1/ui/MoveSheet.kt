// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Settings › Ableton Move: the web player's Move section (RemoteFM1 18c6a82 web/site/js/move-link.js mountMove) in the
// sheet's style. The address (move.local, an IP address, or USB-C's 172.16.254.1), Connect / Disconnect, the status,
// the Move's battery and the sequencer's clock, a short how-to; the sequencer's 32 projects (Share, Delete, Back up all,
// Import) and Sample's takes (Share, Delete).
package com.quixotic7.remotefm1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.move.MoveAddress
import com.quixotic7.remotefm1.move.MoveLink
import com.quixotic7.remotefm1.move.MoveProjects
import com.quixotic7.remotefm1.ui.sheet.SheetButton
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetPage
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.sheet.divider
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette
import java.io.File
import java.text.DateFormat
import java.util.Date

/** what the Settings row shows */
fun moveRowValue(m: MoveLink): String = when {
    m.live -> m.info.name.ifEmpty { "Connected" }
    m.want && m.tone == MoveLink.Tone.BUSY -> "Connecting…"
    else -> "Off"
}

@Composable
fun MovePage(a: AppUi) {
    val m = a.model.midi.move
    val cache = androidx.compose.ui.platform.LocalContext.current.cacheDir
    SheetPage("Ableton Move", "Settings", back = { a.page = SheetPageId.SETTINGS }) {
        Text("An Ableton Move running Move Remote becomes this FM-1's controller: its pads play the keys (or MIDI), its " +
             "buttons and knobs are the FM-1's, and the app lights its pads and draws its screen. Track 1..4: FM-1, MIDI, " +
             "Control, Sequencer.", Modifier.padding(top = 12.dp, bottom = 10.dp), style = ui(13, 400, Palette.sheetMuted2))
        val on = m.want && m.tone != MoveLink.Tone.BAD
        var addr by remember { mutableStateOf(m.address.text) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(addr, { addr = it }, Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Palette.sheetRaised)
                .padding(horizontal = 10.dp, vertical = 10.dp).testTag("move-address"),
                enabled = !on, textStyle = ui(15, 600, if (on) Palette.sheetMuted else Palette.sheetText), singleLine = true,
                cursorBrush = SolidColor(Palette.sheetAccent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!on) m.connect(addr) }),
                decorationBox = { inner -> if (addr.isEmpty()) Text(MoveAddress.DEFAULT_HOST, style = ui(15, 500, Palette.sheetMuted)); inner() })
            SheetButton("USB-C", enabled = !on, tag = "button-move-usb") { addr = MoveAddress.USB_ADDR }
        }
        Row(Modifier.padding(top = 10.dp)) { SheetButton(m.buttonTitle, accent = !on, tag = "button-move") { m.toggle(addr) } }
        val bad = Color(1f, 0.42f, 0.4f)
        SheetStatus(m.detail, dot = when (m.tone) {
            MoveLink.Tone.OFF -> Palette.sheetDim
            MoveLink.Tone.BUSY -> Palette.sheetAccent
            MoveLink.Tone.ON -> Palette.linkedDot
            MoveLink.Tone.BAD -> bad
        }, pulsing = m.tone == MoveLink.Tone.BUSY,
            color = if (m.tone == MoveLink.Tone.ON) Palette.linkedDot else if (m.tone == MoveLink.Tone.BAD) bad else Palette.sheetMuted)
        val i = m.info
        if (m.live) {
            SheetList(Modifier.padding(top = 12.dp)) {
                SheetRow("Battery", value = if (i.battery < 0) "--" else "${i.battery} %", chevron = false, tag = "move-battery") {}
                SheetRow("Connected over", value = i.via.ifEmpty { "–" }, chevron = false) {}
                SheetRow("Sequencer", value = if (!i.ticking || i.bpm10 <= 0) "waits for a firmware" else
                    "${if (i.playing) "playing" else "stopped"}, " +
                    "${if (i.clock == "firmware") "clock from the firmware" else "clock INT"} ${"%.1f".format(i.bpm10 / 10.0)} BPM",
                    chevron = false, divider = false, tag = "move-clock") {}
            }
        }
        SheetNote("Start Move Remote on the Move first. Wi-Fi: move.local (or the Move's address); USB-C: " +
                  "${MoveAddress.USB_ADDR}, when this device brings up the Move's USB network. Once connected, it connects " +
                  "by itself when the app opens. ${MoveLink.LEAVE}")

        SheetSectionTitle("Sequencer projects")
        val ps = m.projectList
        SheetList {
            if (ps.isEmpty()) SheetRow("No projects yet", chevron = false, divider = false, enabled = false) {}
            ps.forEachIndexed { k, p ->
                val cur = m.live && i.project == p.slot
                ProjectRow(p.name + if (cur) " (open)" else "", "${"%.1f".format(p.bytes.size / 1024.0)} KB, ${date(p.saved)}",
                           divider = k < ps.size - 1, share = { shareBytes(a, cache, m.backup(p.slot)) }) { m.deleteProject(p.slot) }
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton("Import…", tag = "button-move-import") { a.pickFiles?.invoke(AppUi.PICK_MOVE) }
            SheetButton("Back up all", enabled = ps.isNotEmpty()) { shareBytes(a, cache, m.backup(null)) }
        }
        SheetNote("Track 4 on the Move is the sequencer, Shift + step 1 its 32 projects. They are kept in this app " +
                  "(not in the firmware's saves) as ${MoveProjects.EXT_ONE} files, the web player's and the iPhone app's " +
                  "format: Share one, or import one or a set (${MoveProjects.EXT_SET}) into the first free slot.")

        SheetSectionTitle("Sample's takes")
        val ts = m.takeList
        SheetList {
            if (ts.isEmpty()) SheetRow("No takes yet", chevron = false, divider = false, enabled = false) {}
            ts.forEachIndexed { k, f ->
                ProjectRow(f.name, "${"%.1f".format(f.length() / 1048576.0)} MB, ${date(f.lastModified())}",
                           divider = k < ts.size - 1, share = { a.share?.invoke(shareCopy(cache, f.name, f.readBytes()), "audio/wav") }) {
                    m.deleteTake(f)
                }
            }
        }
        SheetNote("Sample on the Move records the firmware's sound from a bar line to a bar line (whole bars), named " +
                  "with its tempo and bars. Shift + step 6 is the metronome, heard here and never in a take.")
    }
}

@Composable
private fun ProjectRow(title: String, meta: String, divider: Boolean, share: () -> Unit, delete: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().divider(divider).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = ui(15, 600, Palette.sheetText), maxLines = 1)
                Text(meta, style = ui(12, 500, Palette.sheetMuted))
            }
            SheetButton("Share", action = share)
            SheetButton(if (asking) "Delete?" else "Delete", accent = asking) { if (asking) { asking = false; delete() } else asking = true }
        }
    }
}

private fun date(ms: Long): String = if (ms <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** a file for the share sheet: the FileProvider's cache share/move/ */
private fun shareCopy(cache: File, name: String, bytes: ByteArray): File {
    val dir = File(cache, "share/move").apply { mkdirs() }
    return File(dir, name).apply { writeBytes(bytes) }
}

private fun shareBytes(a: AppUi, cache: File, f: Pair<String, ByteArray>?) {
    if (f == null) return
    a.share?.invoke(shareCopy(cache, f.first, f.second), "application/octet-stream")
}
