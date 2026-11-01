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
import androidx.compose.animation.slideInVertically
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
import com.quixotic7.remotefm1.ui.sheet.PullDownSheet
import com.quixotic7.remotefm1.ui.sheet.ThemeEditor

@Composable
fun MainScreen(a: AppUi, askNotifications: () -> Unit) {
    val ui = a.ui
    LaunchedEffect(a) {
        snapshotFlow { Triple(ui.page, ui.deck, ui.knobSet) }.collect { a.saveView() }
    }
    LaunchedEffect(a) {
        snapshotFlow { a.current?.name }.collect { ui.firmwareName = it }
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
        if (!ui.sheetOpen && !ui.editorOpen && !ui.displayAlone) {
            val p by a.appFm1.prompter.prompt.collectAsState()
            Box(Modifier.fillMaxSize().padding(bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
                InstallPromptCard(p, install = { a.openSheet(SheetPageId.INSTALL) }, notNow = { a.appFm1.prompter.notNow() })
            }
        }
        PullDownSheet(ui.sheetOpen, { a.closeSheet() }) { SheetContent(a) }
        InstallProgressCover(a.appFm1.installer)
        AnimatedVisibility(ui.editorOpen, enter = slideInVertically(tween(300)) { it }, exit = slideOutVertically(tween(300)) { it }) {
            ThemeEditor(a.themes, a.feed) { ui.editorOpen = false }
        }
        AnimatedVisibility(a.overlay != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            val pages = Pages(a.model, done = { a.overlay = null }, bluetooth = { a.overlay = Overlay.BLUETOOTH },
                              askNotifications = askNotifications)
            OverlayPage {
                when (a.overlay) {
                    Overlay.DIAGNOSTICS -> pages.DiagnosticsPage()
                    Overlay.BLUETOOTH -> pages.BluetoothPage()
                    null -> {}
                }
            }
        }
    }
}
