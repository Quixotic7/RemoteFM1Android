// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 App/MainScreen.swift
// The app's screen: the on-screen FM-1 (FaceplateView) playing the engine's core, the pull-down sheet over it, the
// theme editor, and the full-screen pages (Diagnostics, Bluetooth). The last view is remembered per sound source.
package com.quixotic7.remotefm1.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.quixotic7.remotefm1.ui.faceplate.FaceplateView
import com.quixotic7.remotefm1.ui.faceplate.FaceplateOverlay
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.managers.ManagerFor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.quixotic7.remotefm1.ui.sheet.PullDownSheet
import com.quixotic7.remotefm1.ui.sheet.ThemeEditor

@Composable
fun MainScreen(a: AppUi, askNotifications: () -> Unit) {
    val ui = a.ui
    androidx.compose.runtime.SideEffect { Recompositions.main++ }
    LaunchedEffect(a) {
        snapshotFlow { Triple(ui.page, ui.deck, ui.knobSet) }.collect { a.saveView() }
    }
    LaunchedEffect(a) {
        snapshotFlow { a.current?.name }.collect { ui.firmwareName = it }
    }
    // (G, iOS dc771c8) RemoteFM1 running in the app is a cartridge target while it runs
    LaunchedEffect(a) {
        snapshotFlow { a.current?.firmwareId == com.quixotic7.remotefm1.cart.CoreCartTransport.FIRMWARE_ID && a.model.mode == com.quixotic7.remotefm1.Mode.CORE }
            .collect { com.quixotic7.remotefm1.cart.CoreCartTransport.running = it }
    }
    // (A6) MIDI learn ends when the sheet or the theme editor opens (iOS: the host stops it when its sheet opens)
    LaunchedEffect(a) {
        snapshotFlow { ui.sheetOpen || ui.editorOpen }.collect { if (it) a.model.midi.learn.stop() }
    }
    BackHandler(a.overlay != null || ui.editorOpen || ui.sheetOpen || ui.displayAlone) {
        when {
            a.overlay != null -> a.overlay = null
            ui.editorOpen -> ui.editorOpen = false
            ui.displayAlone -> ui.displayAlone = false
            a.page != null -> a.page = null
            else -> a.closeSheet()
        }
    }
    Box(Modifier.fillMaxSize()) {
        FaceplateView(a.feed, ui, a.themes.current, a.chip, a.protector,
                      onWord = { a.openSheet(SheetPageId.FIRMWARE) }, openSheet = { a.openSheet() })
        // (A7) the install prompt over the faceplate, as on iOS
        if (!ui.sheetOpen && !ui.editorOpen && !ui.displayAlone && !a.feed.learn.active) {
            val p by a.appFm1.prompter.prompt.collectAsState()
            Column(Modifier.fillMaxSize().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                   horizontalAlignment = Alignment.CenterHorizontally) {
                // (A5) the wiped-memory card: the copy from before a version change, offered
                a.model.wipedCopy?.let { c ->
                    WipedMemoryCard(c, a.current?.name ?: "The firmware") { a.model.answerWiped(it) }
                }
                FantasyFolderPrompt(a)                  // (a Fantasy CFW with no RemoteFM1 folder)
                InstallPromptCard(p, install = { a.openSheet(SheetPageId.INSTALL) }, notNow = { a.appFm1.prompter.notNow() })
            }
        }
        // (G, iOS 391497e + dc771c8) a swipe in from the right edge: the running firmware's editor (RemoteFM1's: its
        // cartridge page)
        if (!ui.sheetOpen && !ui.editorOpen && !ui.displayAlone && a.overlay == null) EditorEdgeSwipe(a)
        // (F, iOS 836a983) the firmware sidebar: a swipe in from the left edge
        if (!ui.sheetOpen && !ui.editorOpen && !ui.displayAlone && !a.feed.learn.active && a.overlay == null) FirmwareSidebar(a)
        PullDownSheet(ui.sheetOpen, { a.closeSheet() }) { SheetContent(a) }
        UpdateNoticeHost(a)                         // (F, iOS a67b1cf) the launch notice
        InstallProgressCover(a.appFm1.installer)
        AnimatedVisibility(ui.editorOpen, enter = slideInVertically(tween(300)) { it }, exit = slideOutVertically(tween(300)) { it }) {
            ThemeEditor(a.themes, a.feed) { ui.editorOpen = false }
        }
        // (A6) the toast and MIDI learn's bar, over everything but the full-screen pages
        FaceplateOverlay(a.feed, barAtTop = ui.page == FaceplateUi.Page.CONTROLS)
        // (G, iOS eb3455c) the web editor slides in from the right as a layer, and out on Done or the back gesture
        val editorShown = a.overlay == Overlay.MANAGER || a.overlay == Overlay.READER
        var lastEditor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Overlay.MANAGER) }
        if (editorShown) lastEditor = a.overlay!!
        AnimatedVisibility(editorShown, enter = slideInHorizontally(tween(280)) { it }, exit = slideOutHorizontally(tween(280)) { it }) {
            ManagerFor(a.model, reader = lastEditor == Overlay.READER) { a.overlay = null }
        }
        AnimatedVisibility(a.overlay != null && !editorShown, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            val pages = Pages(a.model, done = { a.overlay = null }, bluetooth = { a.overlay = Overlay.BLUETOOTH },
                              askNotifications = askNotifications)
            OverlayPage {
                when (a.overlay) {
                    Overlay.DIAGNOSTICS -> pages.DiagnosticsPage()
                    Overlay.BLUETOOTH -> pages.BluetoothPage()
                    Overlay.MANAGER, Overlay.READER, null -> {}
                }
            }
        }
    }
}

/** (A5, iOS SafetyCopies.swift WipedMemoryCard) a firmware came up after a version change with an empty memory: the
 *  copy from before, offered */
@Composable
fun WipedMemoryCard(copy: com.quixotic7.remotefm1.data.FlashHistory.Snapshot, firmware: String, answer: (Boolean) -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Column(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth().clip(shape)
            .background(com.quixotic7.remotefm1.ui.theme.Palette.sheetBackground.copy(alpha = 0.98f))
            .border(1.5.dp, com.quixotic7.remotefm1.ui.theme.Palette.sheetAccent, shape).padding(16.dp).testTag("wiped-card"),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$firmware started with an empty memory", style = com.quixotic7.remotefm1.ui.sheet.ui(16, 700))
        Text("This version may not read what ${copy.version.ifEmpty { "the last version" }} saved. Your memory from before " +
             "the update is kept: restore it, or keep the new one.",
             style = com.quixotic7.remotefm1.ui.sheet.ui(13, 400, com.quixotic7.remotefm1.ui.theme.Palette.sheetMuted2))
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.quixotic7.remotefm1.ui.sheet.SheetButton("Restore it", accent = true, tag = "wiped-restore") { answer(true) }
            com.quixotic7.remotefm1.ui.sheet.SheetButton("Keep the new one", tag = "wiped-keep") { answer(false) }
        }
    }
}

/** (G, iOS RightEdgeSwipe) a thin strip on the right edge: a swipe in from it (to the left) opens the running firmware's
 *  editor, or RemoteFM1's cartridge page. Not over the status row (its firmware name) */
@Composable
private fun EditorEdgeSwipe(a: AppUi) {
    val c = a.current ?: return
    val hasEditor = com.quixotic7.remotefm1.managers.ManagerList.editor(c.firmwareId, a.model.fw.store.details) != null
    val isRemote = c.firmwareId == com.quixotic7.remotefm1.cart.CoreCartTransport.FIRMWARE_ID
    if (!hasEditor && !isRemote) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.padding(top = 90.dp).width(14.dp).fillMaxHeight().testTag("editor-edge")
            .pointerInput(hasEditor) {
                var dx = 0f
                detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = {
                    if (dx < -60.dp.toPx()) {
                        if (hasEditor) a.overlay = Overlay.MANAGER
                        else { a.cartBackTitle = "Firmware"; a.openSheet(SheetPageId.CART) }
                    }
                }) { ch, d -> ch.consume(); dx += d }
            })
    }
}

/** (G, iOS 391497e: 144 main-screen redraws in 12 s, then 5) recompositions counted, for the tests */
object Recompositions {
    @Volatile var main = 0
    @Volatile var firmwarePage = 0
}
