// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/UI/FirmwareTiles.swift (FirmwareSidebar: 836a983, ac7254d).
//
// The firmware sidebar (the owner, 2026-11-01): a swipe in from the left edge shows one column of firmware (each
// firmware's newest version, in the lists' order; on Android the edge is a 200 dp band at mid-height, kept from the
// system's back gesture); a tap switches to it and closes the sidebar; a swipe back, a tap on
// the dimmed screen or Back closes it. It keeps its scroll while closed, and isn't drawn then. "Get more firmware" at
// the bottom opens Get firmware.
package com.quixotic7.remotefm1.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.Mode
import com.quixotic7.remotefm1.ui.sheet.FirmwareThumb
import com.quixotic7.remotefm1.ui.sheet.Glyph
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** the sidebar over the faceplate, and its left-edge handle while closed */
@Composable
fun FirmwareSidebar(a: AppUi) {
    val m = a.model
    val store = m.fw.store
    store.allVersions.collectAsState().value.size              // (Recently updated's dates)
    LaunchedEffect(Unit) { store.loadVersionsAsync(86_400_000) }  // (at launch, at most once a day)
    ThumbsWatcher(a)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = minOf(320f, maxWidth.value * 0.78f)
        val full = with(density) { (w + 24).dp.toPx() }
        // 0: closed, 1: open (a drag moves it between)
        val shown = remember { Animatable(if (a.sidebarOpen) 1f else 0f) }
        LaunchedEffect(a.sidebarOpen) { shown.animateTo(if (a.sidebarOpen) 1f else 0f, tween(260)) }
        fun close() { a.sidebarOpen = false }
        BackHandler(a.sidebarOpen) { close() }
        val f = shown.value
        if (f > 0.001f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f * f))
                .then(if (a.sidebarOpen) Modifier.clickable(remember { MutableInteractionSource() }, null) { close() } else Modifier))
            Column(Modifier.offset { IntOffset(((f - 1f) * full).roundToInt(), 0) }.width(w.dp).fillMaxHeight()
                .background(Palette.sheetBackground.copy(alpha = 0.97f))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { scope.launch { if (shown.value < 0.75f) close() else shown.animateTo(1f, tween(180)) } },
                    ) { _, dx -> scope.launch { shown.snapTo((shown.value + dx / full).coerceIn(0f, 1f)) } }
                }
                .semantics { contentDescription = "Firmware sidebar" }.testTag("firmware-sidebar")) {
                val cores = shownCores(a)
                LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp),
                           verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text("FIRMWARE", Modifier.padding(start = 6.dp, bottom = 4.dp), style = ui(13, 800, Palette.sheetMuted))
                    }
                    items(cores, key = { it.id }) { c ->
                        val on = c.id == m.selected
                        Row(Modifier.fillMaxWidth().clip14().background(if (on) Palette.sheetRaised else Color.Transparent)
                            .clickable {
                                m.select(c.id)
                                if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
                                close()
                            }
                            .semantics { selected = on; contentDescription = "${c.name} ${c.version}" }
                            .testTag("sidebar-${c.id}").padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FirmwareThumb(a.firmware.thumbnail(c.firmwareID), c.firmwareID, c.name, 10,
                                          Modifier.size(52.dp).border(if (on) 2.dp else 1.dp, if (on) Palette.sheetAccent else Palette.sheetBorder,
                                                                      RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(c.name, style = ui(15, 700, if (on) Palette.sheetAccent else Palette.sheetText), maxLines = 1)
                                Text(c.version + if (c.imported) "" else " · built in", style = ui(12, 500, Palette.sheetMuted2), maxLines = 1)
                            }
                            if (on) Box(Modifier.size(20.dp).clip14(50).background(Palette.sheetAccent), contentAlignment = Alignment.Center) {
                                Glyph("check", Palette.sheetBackground, 11)
                            }
                        }
                    }
                    item {
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 52.dp).clip14()
                            .border(1.5.dp, Palette.sheetAccent, RoundedCornerShape(14.dp))
                            .clickable { close(); a.openSheet(SheetPageId.GET_FIRMWARE) }.testTag("sidebar-getmore"),
                            contentAlignment = Alignment.Center) {
                            Text("↓  Get more firmware", style = ui(15, 700, Palette.sheetAccent))
                        }
                    }
                }
            }
        }
        // (the edge: a drag in from the left opens it; not over the status row's chip. Android's back gesture starts at
        // the same edge: a 200 dp band at mid-height is kept from it, the most the system allows an edge)
        if (!a.sidebarOpen) {
            Box(Modifier.align(Alignment.CenterStart).padding(top = 90.dp).width(18.dp).height(200.dp).systemGestureExclusion().alpha(0f)
                .pointerInput(Unit) {
                    var moved = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { moved = 0f },
                        onDragEnd = {
                            if (moved > 60.dp.toPx()) a.sidebarOpen = true
                            else scope.launch { shown.animateTo(0f, tween(160)) }
                        },
                    ) { _, dx ->
                        moved += dx
                        scope.launch { shown.snapTo((moved / full).coerceIn(0f, 1f)) }
                    }
                }.testTag("sidebar-edge"))
        }
    }
}

private fun Modifier.clip14(percent: Int = -1) = clip(if (percent >= 0) RoundedCornerShape(percent) else RoundedCornerShape(14.dp))
