// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/ScreenProtector.swift
// The display up close and its film:
//   ScreenFilm       the clear protective film the FM-1 ships with: a faint sheen over the display, its solid red tab
//                    right of the screen, level with its top; on the wide (landscape) layouts only, as the FM-1 looks.
//                    Dragging the tab across the screen folds the film back (the fold halfway between the tab's place
//                    and the finger); let go early and it springs back, past a third of the way it peels off and is
//                    gone, the red square with it. Remembered (ScreenProtector). Only the tab takes touches.
//   ProtectedDisplay the faceplate's display, and a double tap that shows the display alone
//   FullDisplay      the display alone: black, square, as large as fits; a double tap or a swipe down closes it
package com.quixotic7.remotefm1.ui.faceplate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.ui.theme.Palette
import com.quixotic7.remotefm1.ui.theme.Theme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun polys(list: List<List<FPoint>>, d: Float): Path = Path().apply {
    for (poly in list) {
        if (poly.isEmpty()) continue
        moveTo(poly[0].x * d, poly[0].y * d)
        for (i in 1 until poly.size) lineTo(poly[i].x * d, poly[i].y * d)
        close()
    }
}

/** the film over the Full view (in the faceplate's coordinates, which [w] x [h] dp fill); drawn while it is on */
@Composable
fun ScreenFilm(geo: FilmGeometry, protector: ScreenProtector, w: Float, h: Float) {
    if (!protector.on) return
    val t = remember(geo) { Animatable(protector.startFold * geo.length) }
    var leaving by remember { mutableStateOf(false) }
    val leave = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun peel() {
        leaving = true
        scope.launch {
            launch { leave.animateTo(1f, tween(300)) }
            t.animateTo(geo.length, tween(300))
            protector.set(false)
        }
    }
    val lv = leave.value
    val shiftX = geo.dirX * geo.length * 0.3f * lv
    val shiftY = geo.dirY * geo.length * 0.3f * lv - geo.length * 0.2f * lv
    Box(Modifier.size(w.dp, h.dp).graphicsLayer {
        translationX = shiftX * density
        translationY = shiftY * density
        alpha = 1f - lv
    }) {
        Canvas(Modifier.size(w.dp, h.dp)) {
            val d = density
            val f = geo.folded(t.value)
            val left = polys(f.left, d)
            // (the gloss across the film itself: the display and its tab)
            val g0 = Offset(geo.display.x * d, geo.display.y * d)
            val g1 = Offset(geo.tab.maxX * d, geo.display.maxY * d)
            val full = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.01f),
                                                   Color.White.copy(alpha = 0.07f)), g0, g1)
            drawPath(left, full)
            drawPath(left, Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                                                g0, g1))
            drawPath(left, Color.White.copy(alpha = 0.10f), style = Stroke(1 * d))
            if (f.flap.isNotEmpty()) {
                val flap = polys(f.flap, d)
                if (t.value > 0) translate(geo.dirX * 3 * d, geo.dirY * 3 * d + 2 * d) {
                    drawPath(flap, Color.Black.copy(alpha = 0.22f))
                }
                drawPath(flap, Color(0.96f, 0.96f, 0.96f).copy(alpha = 0.28f))
            }
        }
        // the tab: the solid red square, carried with the finger (a 44 dp hit area)
        val size = geo.tab.w
        val tab = geo.folded(t.value).tab
        Box(
            Modifier.place(PRect(tab.x - 22, tab.y - 22, 44f, 44f)).testTag("protector-tab")
                .semantics { contentDescription = "Screen protector" }
                .pointerInput(geo, leaving) {
                    if (leaving) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var dx = 0f
                        var dy = 0f
                        var vx = 0f
                        var lastX = down.position.x
                        var lastT = down.uptimeMillis
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            // (positions move with the tab: sum the deltas)
                            val delta = c.position - c.previousPosition
                            dx += delta.x / density
                            dy += delta.y / density
                            val dt = max(1L, c.uptimeMillis - lastT)
                            vx = (c.position.x - lastX) / density / dt * 1000f
                            lastX = c.position.x
                            lastT = c.uptimeMillis
                            scope.launch { t.snapTo(geo.fold(dx, dy) * geo.length) }
                        }
                        val predicted = geo.fold(dx + vx * 0.25f, dy)
                        if (FilmGeometry.peels(max(predicted, t.value / geo.length))) peel()
                        else scope.launch { t.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size.dp)) {
                drawRoundRect(Palette.indicatorLed, Offset.Zero, Size(this.size.width, this.size.height),
                              CornerRadius(this.size.width * 0.22f))
            }
        }
    }
}

/** the faceplate's display: a double tap shows the display alone */
@Composable
fun ProtectedDisplay(feed: PanelFeed, theme: Theme, size: Float, ui: FaceplateUi, modifier: Modifier = Modifier) {
    DisplayView(feed, theme, size, modifier.testTag("display").pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { ui.displayAlone = true })
    })
}

/** the display alone, filling the view: black, square, as large as fits */
@Composable
fun FullDisplay(feed: PanelFeed, ui: FaceplateUi) {
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).testTag("display-alone")
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { ui.displayAlone = false }) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var dragging = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        dx = (c.position.x - down.position.x) / density
                        dy = (c.position.y - down.position.y) / density
                        if (!dragging && max(abs(dx), abs(dy)) > 16) dragging = true
                        if (dragging) {
                            c.consume()
                            scope.launch { drag.snapTo(max(0f, dy)) }
                        }
                    }
                    if (dragging && (dy > 80 || abs(dx) > 120)) ui.displayAlone = false
                    scope.launch { drag.animateTo(0f, tween(200)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val side = min(maxWidth.value, maxHeight.value)
        Canvas(Modifier.size(side.dp).offset { IntOffset(0, (drag.value * density).roundToInt()) }
            .semantics { contentDescription = "FM-1 display" }) {
            feed.frames
            drawImage(feed.image, IntOffset.Zero, IntSize(240, 240), IntOffset.Zero,
                      IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.None)
        }
    }
}
