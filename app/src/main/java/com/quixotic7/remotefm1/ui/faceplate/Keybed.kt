// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/Keybed.swift
// The LED keybed (design/README.md 5.3, canvas/Keys.dc.html): the keys drawn from KeybedGeometry, each with its LED
// slot mirroring the firmware's LED, in a Row or a Scroll layout. Touch: true multitouch, one note per finger, a
// finger sliding onto another key plays it (glissando); the keys never scroll on touch. In the Scroll layout the range
// strip above scrolls them (tap a cell to centre it, or drag the strip).
package com.quixotic7.remotefm1.ui.faceplate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.ui.theme.RfmFonts
import com.quixotic7.remotefm1.ui.theme.Theme
import com.quixotic7.remotefm1.ui.theme.color
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** [offset]: naturals scrolled past (-1: the geometry's default); [setOffset] moves it */
@Composable
fun KeybedView(theme: Theme, feed: PanelFeed, w: Float, h: Float, layout: KeybedGeometry.Layout, stepNumbers: Boolean,
               offset: Float, setOffset: (Float) -> Unit, modifier: Modifier = Modifier) {
    val g = remember(w, h, layout, stepNumbers) { KeybedGeometry(PSize(w, h), layout, stepNumbers) }
    val off = if (offset < 0) g.defaultOffset else min(offset, g.maxOffset)
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(0f) }
    val stepStyle = TextStyle(fontFamily = RfmFonts.hardware, color = theme.keyText.color)
    Box(modifier.size(w.dp, h.dp).semantics {
        contentDescription = if (stepNumbers) "Keyboard as 16-step sequencer" else "Keyboard, F3 to G5"
    }) {
        Canvas(Modifier.size(w.dp, h.dp)) {
            val d = density
            well(theme.bed.color, g.radius * d, 3 * d, 0.2f)
            feed.lights.version
            val L = feed.lights
            val ledOff = theme.ledOff.color
            val led = theme.led.color
            if (layout is KeybedGeometry.Layout.Scroll) {
                val first = off.toInt()
                for (i in 0 until 16) {
                    val c = g.stripCell(i)
                    val k = KeybedGeometry.NATURALS[i]
                    val level = L.keys[k]
                    val inView = i >= first && i < off + g.visible
                    drawRoundRect(if (inView) theme.hi.color else theme.key.color, Offset(c.x * d, c.y * d),
                                  Size(c.w * d, c.h * d), CornerRadius(4 * d))
                    val a = LedLevel.alpha(level)
                    val bw = c.w * 0.55f
                    val bx = (c.x + (c.w - bw) / 2) * d
                    val by = (c.maxY - 4 - 3) * d
                    if (LedLevel.lit(level)) glowRoundRect(led, bx, by, bw * d, 3 * d, 2 * d, 3 * d)
                    else drawRoundRect(if (a > 0) led.copy(alpha = a) else ledOff, Offset(bx, by), Size(bw * d, 3 * d), CornerRadius(2 * d))
                }
                val wr = g.stripWindow(off)
                drawRoundRect(theme.keyText.color, Offset(wr.x * d, wr.y * d), Size(wr.w * d, wr.h * d), CornerRadius(7 * d),
                              style = Stroke(2 * d))
            }
            val st = g.stage
            clipRect(st.x * d, st.y * d, st.maxX * d, st.maxY * d) {
                translate((st.x + (if (layout is KeybedGeometry.Layout.Scroll) g.shift(off) else 0f) - (if (layout is KeybedGeometry.Layout.Row) st.x else 0f)) * d,
                          (if (layout is KeybedGeometry.Layout.Scroll) st.y else 0f) * d) {
                    for (k in g.keys) {
                        val r = k.rect
                        val down = L.keyDown(k.index)
                        val dy = if (down) 1.5f * d else 0f
                        capFace(if (down) theme.hi.color else theme.key.color, theme.hi.color, theme.lo.color, k.radius * d,
                                4 * d, dy, x = r.x * d, y = r.y * d, w = r.w * d, h = r.h * d)
                        val level = L.keys[k.index]
                        val a = LedLevel.alpha(level)
                        val showStep = stepNumbers && k.natural
                        val nf = min(14f, max(8f, Math.round(r.w * 0.22f).toFloat()))
                        val spacing = Math.round(r.h * 0.08f).toFloat()
                        val textH = if (showStep) nf * 1.3f else 0f
                        val groupH = k.ledH + if (showStep) spacing + textH else 0f
                        val lx = r.x + (r.w - k.ledW) / 2
                        val ly = r.y + (r.h - groupH) / 2
                        if (LedLevel.lit(level)) {
                            glowRoundRect(led, lx * d, ly * d + dy, k.ledW * d, k.ledH * d, 3 * d, 5 * d)
                        } else {
                            drawRoundRect(ledOff, Offset(lx * d, ly * d + dy), Size(k.ledW * d, k.ledH * d), CornerRadius(3 * d))
                            if (a > 0) drawRoundRect(led.copy(alpha = a), Offset(lx * d, ly * d + dy), Size(k.ledW * d, k.ledH * d), CornerRadius(3 * d))
                        }
                        if (showStep) {
                            val t = measurer.measure("${k.step + 1}", stepStyle.copy(fontSize = (nf * d).toSp()))
                            drawText(t, topLeft = Offset((r.x + r.w / 2) * d - t.size.width / 2f,
                                                         (ly + k.ledH + spacing) * d + dy + (textH * d - t.size.height) / 2))
                        }
                    }
                }
            }
        }
        if (layout is KeybedGeometry.Layout.Scroll) {
            // the strip: tap a cell to centre it (animated); drag along it: the frame follows the finger
            val curOff by rememberUpdatedState(off)
            Box(Modifier.place(PRect(0f, 0f, w, g.pad + g.stripHeight + g.stripGap / 2)).testTag("keybed-strip")
                .pointerInput(g) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var moved = false
                        var last = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            last = c.position
                            c.consume()
                            if (abs(c.position.x - down.position.x) / density > 4) {
                                moved = true
                                val mw = g.size.w - 2 * g.pad
                                setOffset(KeybedGeometry.clamp((c.position.x / density - g.pad) / mw * 16 - g.visible / 2, 0f, g.maxOffset))
                            }
                        }
                        // (a tap: centre that cell; let go after a drag: onto whole keys)
                        run {
                            val mw = g.size.w - 2 * g.pad
                            val from = if (!moved) curOff
                                       else KeybedGeometry.clamp((last.x / density - g.pad) / mw * 16 - g.visible / 2, 0f, g.maxOffset)
                            val to = if (!moved) g.offsetCentering(g.stripIndex(last.x / density)) else g.snap(from)
                            scope.launch {
                                anim.snapTo(from)
                                anim.animateTo(to, tween(200)) { setOffset(value) }
                            }
                        }
                    }
                })
        }
        KeyTouchArea(g, off, feed)
    }
}

/** the keys' touch surface over the stage: each finger plays the key under it */
@Composable
private fun KeyTouchArea(g: KeybedGeometry, offset: Float, feed: PanelFeed) {
    val geo by rememberUpdatedState(g)
    val off by rememberUpdatedState(offset)
    val held = remember { HashMap<PointerId, Int>() }
    DisposableEffect(Unit) {
        onDispose {
            for (k in held.values) feed.keyUp(k)
            held.clear()
        }
    }
    Box(Modifier.place(g.stage).testTag("keybed").pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent()
                val gg = geo
                for (c in ev.changes) {
                    // a point in the stage -> the keybed's coordinates
                    val px = c.position.x / density + if (gg.layout is KeybedGeometry.Layout.Scroll) gg.stage.x else 0f
                    val py = c.position.y / density + if (gg.layout is KeybedGeometry.Layout.Scroll) gg.stage.y else 0f
                    val id = c.id
                    if (c.pressed && !c.previousPressed) {
                        if (feed.learnActive) continue                 // (MIDI learn: the keys play nothing)
                        val k = gg.keyNear(px, py, off, gg.gap / 2)?.index ?: continue
                        held[id] = k
                        feed.keyDown(k)
                        c.consume()
                    } else if (c.pressed) {
                        val k = gg.keyNear(px, py, off, gg.gap)?.index ?: continue   // (off the keys: the last one stays down)
                        val old = held[id]
                        if (old != null) {
                            if (old != k) {
                                held[id] = k
                                feed.keyDown(k)                        // (the new one first: a legato glissando)
                                feed.keyUp(old)
                            }
                        } else if (!feed.learnActive) {
                            held[id] = k
                            feed.keyDown(k)
                        }
                        c.consume()
                    } else if (!c.pressed && c.previousPressed) {
                        held.remove(id)?.let { feed.keyUp(it) }
                        c.consume()
                    }
                }
            }
        }
    })
}
