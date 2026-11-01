// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A Fantasy CFW in the app (FM1VST docs/VIRTUAL-CARTS.md; the web player's words, RemoteFM1 60a1b8b
// web/site/js/folder-ui.js): its badge (the firmware card, About, Get firmware), the RemoteFM1 folder's section on its
// Firmware page, and the prompt over the faceplate when one that reads files runs with no folder picked yet.
package com.quixotic7.remotefm1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quixotic7.remotefm1.firmware.FantasyCfw
import com.quixotic7.remotefm1.folder.RemoteFolderHost
import com.quixotic7.remotefm1.ui.sheet.SheetButton
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette

private val badgeText = Color(0xFFE9DBFF)
private val badgeLine = Color(0x8CC9AEE6)
private val badgeFill = Brush.horizontalGradient(listOf(Color(0x59A678FF), Color(0x4DFF80C4)))

/** "FANTASY CFW": an .rfm1 for RemoteFM1 only */
@Composable
fun FantasyBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.clip(shape).background(badgeFill).border(1.dp, badgeLine, shape).padding(horizontal = 6.dp, vertical = 1.dp)
        .testTag("fantasy-badge")) {
        Text(FantasyCfw.NAME.uppercase(), style = ui(12, 700, badgeText).copy(letterSpacing = 0.7.sp), maxLines = 1)
    }
}

/** the RemoteFM1 folder on a Fantasy CFW's Firmware page: where it is (or that none is picked), what the firmware
 *  reads and writes there, and Pick / Change */
@Composable
fun FantasyFolderSection(a: AppUi, info: FantasyCfw.Info) {
    val st by RemoteFolderHost.status.collectAsState()
    SheetSectionTitle("RemoteFM1 folder")
    SheetList {
        SheetRow(if (st.ready) "Change folder…" else "Pick your RemoteFM1 folder…",
                 value = if (st.ready) null else "not picked", divider = false, enabled = a.pickFolder != null,
                 tag = "row-remotefolder") { a.pickFolder?.invoke() }
    }
    SheetNote((if (st.ready) st.location.ifEmpty { "RemoteFM1" } + ": " else "") + "audio/, code/<id>/, data/<id>/. " +
              "This firmware ${info.access}. It's the folder States and Backups use.")
}

/** firmware ids whose prompt was answered Not now (until the app restarts) */
private val dismissed = mutableStateListOf<String>()

/** the prompt over the faceplate: a running Fantasy CFW that reads files, and no folder picked */
@Composable
fun FantasyFolderPrompt(a: AppUi) {
    val st by RemoteFolderHost.status.collectAsState()
    val c = a.current ?: return
    if (st.ready || !a.running || c.firmwareId in dismissed) return
    val info = a.firmware.fantasy(c) ?: return
    if (!info.virtual || !info.usesFolder) return
    Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))
        .background(Palette.sheetBackground).border(1.5.dp, badgeLine, RoundedCornerShape(20.dp)).padding(16.dp)
        .testTag("folder-prompt"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FantasyBadge() }
        Text("Pick your RemoteFM1 folder", style = ui(16, 700))
        Text("${c.name} reads its files from RemoteFM1/ in a folder you pick (the one for States and Backups).",
             style = ui(13, 400, Palette.sheetMuted2))
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton("Pick…", accent = true, enabled = a.pickFolder != null, tag = "folder-pick") { a.pickFolder?.invoke() }
            SheetButton("Not now", tag = "folder-notnow") { dismissed.add(c.firmwareId) }
        }
    }
}

/** (About) the rows and the sentence a Fantasy CFW adds */
object FantasyAbout {
    fun rows(info: FantasyCfw.Info): List<Pair<String, String>> =
        if (!info.virtual) emptyList() else listOf("kind" to FantasyCfw.NAME, "memory" to "${info.memoryBytes shr 20} MiB",
                                                   "folder" to info.access)

    fun text(info: FantasyCfw.Info): String =
        if (!info.virtual) "" else "\n\nA ${FantasyCfw.NAME} runs in RemoteFM1 only, never on an FM-1" +
            (if (info.usesFolder) "; it ${info.access} in your RemoteFM1 folder." else ".")
}

@Composable
internal fun BadgeLine(fantasy: Boolean, content: @Composable () -> Unit) {
    if (!fantasy) { content(); return }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.weight(1f, fill = false)) { content() }
        FantasyBadge()
    }
}
