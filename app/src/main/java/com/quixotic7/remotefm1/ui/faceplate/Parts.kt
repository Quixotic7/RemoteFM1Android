// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/Parts.swift
// The faceplate's parts (design/README.md section 5, canvas/Screen, Knob, Buttons, Keys): the display, a knob, the
// 6 x 2 button grid, the OCT well, and the shared drawing (a cap's face with its lit edge and hard drop, a recessed
// well, an LED's glow). Each reads only the part of the PanelFeed it shows (its counters are read while drawing, so
// an LED changing redraws that part alone). Touches go to the feed (the core).
package com.quixotic7.remotefm1.ui.faceplate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.quixotic7.remotefm1.midi.MidiTarget
import com.quixotic7.remotefm1.ui.theme.Palette
import com.quixotic7.remotefm1.ui.theme.RfmFonts
import com.quixotic7.remotefm1.ui.theme.Theme
import com.quixotic7.remotefm1.ui.theme.color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- shared drawing

/** a size in dp as a text size (fixed: the faceplate's legends don't follow the font scale, as on iOS) */
@Composable
fun dsp(v: Float): TextUnit = with(LocalDensity.current) { v.dp.toSp() }

/** a frame at a rect of the parent's coordinate space (the faceplate's absolute layout) */
fun Modifier.place(r: PRect): Modifier = this
    .offset { IntOffset((r.x * density).roundToInt(), (r.y * density).roundToInt()) }
    .size(max(0f, r.w).dp, max(0f, r.h).dp)

/** centred on (cx, cy) with size (w, h), in dp */
fun Modifier.centredAt(cx: Float, cy: Float, w: Float, h: Float): Modifier = place(PRect(cx - w / 2, cy - h / 2, w, h))

private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/** a rounded rect with a soft glow round it (an LED) */
fun DrawScope.glowRoundRect(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float, blur: Float) {
    drawIntoCanvas { c ->
        glowPaint.color = color.toArgb()
        glowPaint.setShadowLayer(blur, 0f, 0f, color.toArgb())
        c.nativeCanvas.drawRoundRect(x, y, x + w, y + h, r, r, glowPaint)
        glowPaint.clearShadowLayer()
    }
}

/** a key or button face: `key` with a 2 dp `hi` top edge (the design's inset highlight), `lo` [drop] under it */
fun DrawScope.capFace(face: Color, hi: Color, lo: Color, radius: Float, drop: Float, dy: Float = 0f,
                      x: Float = 0f, w: Float = size.width, h: Float = size.height, y: Float = 0f) {
    val cr = CornerRadius(radius)
    drawRoundRect(lo, Offset(x, y + dy + drop), Size(w, h), cr)
    val p = Path().apply { addRoundRect(RoundRect(x, y + dy, x + w, y + dy + h, cr)) }
    clipPath(p) {
        drawRect(hi, Offset(x, y + dy), Size(w, h))
        drawRoundRect(face, Offset(x, y + dy + 2.dp.toPx()), Size(w, h), cr)
    }
}

/** a recessed well (keybed, button well, OCT well): `bed` with a dark inset top edge */
fun DrawScope.well(bed: Color, radius: Float, inset: Float = 2.dp.toPx(), darkness: Float = 0.18f,
                   w: Float = size.width, h: Float = size.height) {
    val cr = CornerRadius(radius)
    val p = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, cr)) }
    clipPath(p) {
        drawRect(bed, size = Size(w, h))
        drawRect(Color.Black.copy(alpha = darkness), size = Size(w, h))
        drawRoundRect(bed, Offset(0f, inset), Size(w, h), cr)
    }
}

private fun rnd(v: Float) = Math.round(v).toFloat()

// ---------------------------------------------------------------- 5.1 Display

/** the bezel and the core's 240 x 240 framebuffer, nearest-neighbour, snapped to whole device pixels when that keeps
 *  at least 92 % of the room */
@Composable
fun DisplayView(feed: PanelFeed, theme: Theme, size: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    Canvas(modifier.size(size.dp).semantics { contentDescription = "FM-1 display" }) {
        val px = this.size.width
        val u = px / size                                       // px per dp
        val pad = max(6f, rnd(size * 0.045f))
        val br = max(10f, rnd(size * 0.09f))
        val lr = max(4f, rnd(size * 0.035f))
        val inner = size - 2 * pad
        val k = floor(inner * density / 240)
        val snapped = if (k >= 1 && k * 240 / density >= inner * 0.92f) k * 240 / density else inner
        drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(0f, 4 * u), Size(px, px), CornerRadius(br * u))
        drawRoundRect(theme.bezel.color, Offset.Zero, Size(px, px), CornerRadius(br * u))
        val s = snapped * u
        val o = (px - s) / 2
        val clip = Path().apply { addRoundRect(RoundRect(o, o, o + s, o + s, CornerRadius(lr * snapped / inner * u))) }
        feed.frames                                             // (redrawn on each frame)
        clipPath(clip) {
            drawRect(Color.Black, Offset(o, o), Size(s, s))
            drawImage(feed.image, srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                      srcSize = androidx.compose.ui.unit.IntSize(240, 240),
                      dstOffset = androidx.compose.ui.unit.IntOffset(o.roundToInt(), o.roundToInt()),
                      dstSize = androidx.compose.ui.unit.IntSize(s.roundToInt(), s.roundToInt()),
                      filterQuality = FilterQuality.None)
        }
    }
}

// ---------------------------------------------------------------- 5.2 Knob

object KnobSpec {
    const val DEGREES_PER_DETENT = 18f          // (20 detents a turn)
    const val POINTS_PER_DETENT = 9f
    const val MASTER_SWEEP_POINTS = 220f

    fun angle(k: PanelKnob, f: KnobFeed): Float =
        if (k == PanelKnob.MASTER) -135f + f.master / 1023f * 270f else f.turns[k.ordinal] * DEGREES_PER_DETENT

    /** the detents a vertical drag of [dy] dp has turned (up = clockwise), toward zero */
    fun detents(dy: Float): Int = (-dy / POINTS_PER_DETENT).toInt()

    /** MASTER after a drag of [dy] from [start] */
    fun master(start: Int, dy: Float): Int = start + (-dy / MASTER_SWEEP_POINTS * 1023).toInt()
}

/** a knob: its label above, the body; vertical drag turns it (up = clockwise), a sideways swipe goes to [onSwipe] */
@Composable
fun KnobView(knob: PanelKnob, size: Float, theme: Theme, feed: PanelFeed, modifier: Modifier = Modifier,
             labelWidth: Float? = null, onSwipe: ((Int) -> Unit)? = null) {
    val lf = max(9f, rnd(size * 0.17f))
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    Column(
        modifier
            .size((size + 28).dp, (lf + 6 + size + 6).dp)
            .testTag("knob-${knob.label}")
            .semantics { contentDescription = "${knob.label} knob" }
            .pointerInput(knob, onSwipe != null) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (feed.learnActive) { feed.learn.pick(MidiTarget.knob(knob).id); return@awaitEachGesture }
                    val start = feed.knobs.master
                    var axis = 0
                    var sent = 0
                    var last = down.position
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        last = c.position
                        val dx = (c.position.x - down.position.x) / density
                        val dy = (c.position.y - down.position.y) / density
                        if (!c.pressed) {
                            if (axis == 2 && abs(dx) > 40) onSwipe?.invoke(if (dx < 0) 1 else -1)
                            break
                        }
                        c.consume()
                        if (axis == 0) {
                            if (max(abs(dx), abs(dy)) <= 6) continue
                            axis = if (onSwipe != null && abs(dx) > abs(dy) * 1.2f) 2 else 1
                        }
                        if (axis != 1) continue
                        if (knob == PanelKnob.MASTER) {
                            feed.master(KnobSpec.master(start, dy))
                        } else {
                            val target = KnobSpec.detents(dy)
                            if (target != sent) {
                                feed.turn(knob, target - sent)
                                sent = target
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val lw = max(size, min(size + 28, labelWidth ?: (size + 28)) - 2)
        BasicText(
            knob.label.uppercase(),
            Modifier.width(lw.dp).height(lf.dp).wrapContentHeight(Alignment.CenterVertically, unbounded = true),
            style = TextStyle(fontFamily = RfmFonts.hardware, fontSize = dsp(lf), letterSpacing = dsp(lf * 0.08f),
                              color = theme.label.color, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = dsp(lf * 0.5f), maxFontSize = dsp(lf)),
        )
        Spacer(Modifier.height(6.dp))
        KnobBody(knob, size, theme, feed)
    }
}

@Composable
fun KnobBody(knob: PanelKnob, size: Float, theme: Theme, feed: PanelFeed) {
    Box(Modifier.size(size.dp)) {
        KnobFace(knob, size, theme, feed)
        LearnRing(MidiTarget.knob(knob).id, size, theme, feed.learn)
        if (knob == PanelKnob.MASTER) PotMark(size, theme, feed.pickup)
    }
}

@Composable
private fun KnobFace(knob: PanelKnob, size: Float, theme: Theme, feed: PanelFeed) {
    Canvas(Modifier.size(size.dp)) {
        val px = this.size.width
        val u = px / 100
        val r = px / 2
        val c = Offset(r, r)
        drawCircle(Color.Black.copy(alpha = 0.28f), r, c + Offset(0f, max(3f, rnd(size * 0.06f)) * density))
        drawCircle(theme.knob.color, r, c)
        drawCircle(Color.White.copy(alpha = 0.16f), r - 5 * u, c,
                   style = Stroke(8 * u, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.2f * u, 3.9f * u))))
        drawCircle(theme.knob.color, r - 14 * u, c)
        drawCircle(Color.White.copy(alpha = 0.07f), r - 14 * u, c)
        feed.knobs.version
        rotate(KnobSpec.angle(knob, feed.knobs), c) {
            drawRoundRect(theme.mark.color, Offset(r - 3.5f * u, r - 30 * u - 13.5f * u), Size(7 * u, 27 * u),
                          CornerRadius(3.5f * u))
        }
    }
}

// ---------------------------------------------------------------- 5.4 Buttons

/** one button of the grid or the OCT well; lit by the firmware's LED for it, pressed while touched */
@Composable
fun PanelButton(label: PanelLabel, theme: Theme, feed: PanelFeed, w: Float, h: Float, radius: Float, font: Float,
                drop: Float = 4f, modifier: Modifier = Modifier) {
    val ic = max(7f, rnd(h * 0.17f))
    Box(
        modifier
            .size(w.dp, h.dp)
            .testTag("button-${label.legend}")
            .semantics { contentDescription = label.spoken }
            .pointerInput(label) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (feed.learnActive) { feed.learn.pick(MidiTarget.button(label).id); return@awaitEachGesture }
                    if (!feed.holds(label)) feed.button(label, true)
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        val c = ev.changes.firstOrNull { it.id == down.id }
                        if (c == null || !c.pressed) break
                        c.consume()
                    }
                    feed.button(label, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            feed.lights.version
            val level = feed.lights.buttons[label.ordinal]
            val lit = LedLevel.lit(level)
            val down = feed.lights.labelDown(label.ordinal)
            capFace(if (lit || down) theme.hi.color else theme.key.color, theme.hi.color, theme.lo.color,
                    radius * density, drop * density, if (down) density else 0f)
        }
        ButtonLegend(label, theme, feed, ic, font)
        LearnOutline(MidiTarget.button(label).id, radius, theme, feed.learn)
    }
}

@Composable
private fun ButtonLegend(label: PanelLabel, theme: Theme, feed: PanelFeed, ic: Float, font: Float) {
    feed.lights.version
    val level = feed.lights.buttons[label.ordinal]
    val lit = LedLevel.lit(level)
    val down = feed.lights.labelDown(label.ordinal)
    val tint = if (lit) 1f else LedLevel.alpha(level) * 0.5f
    val legend = if (tint > 0) lerp(theme.keyText.color, theme.led.color, tint) else theme.keyText.color
    Column(Modifier.offset(y = if (down) 1.dp else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (label == PanelLabel.PLAY) {
            val g = feed.lights.playGreen
            Canvas(Modifier.size(ic.dp)) {
                val s = this.size.width
                val p = Path().apply { moveTo(s * 0.2f, s * 0.1f); lineTo(s * 0.9f, s * 0.5f); lineTo(s * 0.2f, s * 0.9f); close() }
                if (LedLevel.lit(g)) {
                    drawIntoCanvas { c ->
                        glowPaint.color = Palette.playGlyph.toArgb()
                        glowPaint.setShadowLayer(4 * density, 0f, 0f, Palette.playGlyph.toArgb())
                        c.nativeCanvas.drawPath(p.asAndroidPath(), glowPaint)
                        glowPaint.clearShadowLayer()
                    }
                } else drawPath(p, Palette.playGlyph)
            }
            Spacer(Modifier.height(3.dp))
        } else if (label == PanelLabel.REC) {
            Box(Modifier.size(ic.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size((ic * 0.8f).dp)) { drawCircle(Palette.recGlyph) }
            }
            Spacer(Modifier.height(3.dp))
        }
        BasicText(
            feed.names.label(label),
            Modifier.padding(horizontal = 3.dp),
            style = TextStyle(fontFamily = RfmFonts.hardware, fontSize = dsp(font), letterSpacing = dsp(font * 0.06f),
                              color = legend, textAlign = TextAlign.Center,
                              shadow = if (lit) Shadow(theme.led.color, blurRadius = 4 * LocalDensity.current.density) else null),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = dsp(font * 0.5f), maxFontSize = dsp(font)),
        )
    }
}

/** the 6 x 2 grid in its well (Buttons.dc.html); its size is [FaceplateLayout.Buttons.size]'s */
@Composable
fun ButtonGrid(theme: Theme, feed: PanelFeed, bw: Float, bh: Float, gap: Float, modifier: Modifier = Modifier) {
    val radius = rnd(bh * 0.24f)
    val font = max(10f, rnd(bh * 0.26f))
    val wellR = rnd(bh * 0.34f) + 6
    Box(modifier.size((6 * bw + 5 * gap + 20).dp, (2 * bh + gap + 20).dp)
        .drawBehind { well(theme.bed.color, wellR * density) }) {
        for (r in 0..1) for (i in 0..5) {
            val l = PanelLabel.GRID[r * 6 + i]
            val x = 10 + i * (bw + gap)
            val y = 10 + r * (bh + gap)
            PanelButton(l, theme, feed, bw, bh, radius, font, modifier = Modifier.offset(x.dp, y.dp))
            if (feed.names.isRenamed(l)) {
                // the printed name small above the top row / below the bottom row (in the well's margin)
                BasicText(l.legend, Modifier.offset(x.dp, (if (r == 0) y - 9 else y + bh).dp).width(bw.dp).height(9.dp)
                    .wrapContentSize(Alignment.Center),
                          style = TextStyle(fontFamily = RfmFonts.hardware, fontSize = dsp(7f), letterSpacing = dsp(0.4f),
                                            color = theme.label.color.copy(alpha = 0.75f), textAlign = TextAlign.Center),
                          maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------- 5.5 OCT- / OCT+

@Composable
fun OctWell(theme: Theme, feed: PanelFeed, w: Float, h: Float, pad: Float, gap: Float, radius: Float, buttonRadius: Float,
            font: Float, modifier: Modifier = Modifier) {
    Row(modifier.size(w.dp, h.dp).drawBehind { well(theme.bed.color, radius * density) }.padding(pad.dp)) {
        val bw = (w - 2 * pad - gap) / 2
        val bh = h - 2 * pad
        PanelButton(PanelLabel.OCT_DOWN, theme, feed, bw, bh, buttonRadius, font, drop = 3f)
        Spacer(Modifier.width(gap.dp))
        PanelButton(PanelLabel.OCT_UP, theme, feed, bw, bh, buttonRadius, font, drop = 3f)
    }
}
