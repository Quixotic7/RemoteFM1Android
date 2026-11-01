// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/LearnViews.swift and Toast.swift (ToastView)
// (A6) what MIDI learn and MASTER's pickup draw on the faceplate (js/panel.js drawLearn, drawPotAt; css/site.css
// .learn-bar): each learnable control outlined (dashed; solid when it has a binding, with a dot on a knob), the picked
// one pulsing; the bar over the keybed (its line, Forget when the picked control has bindings, Done); the pot's
// position on MASTER while it waits. FaceplateOverlay is the bar and the toast, laid over everything.
package com.quixotic7.remotefm1.ui.faceplate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette
import com.quixotic7.remotefm1.ui.theme.RfmFonts
import com.quixotic7.remotefm1.ui.theme.Theme
import com.quixotic7.remotefm1.ui.theme.color
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** 0..1..0, while [on] (the picked control's pulse) */
@Composable
private fun pulse(on: Boolean): Float {
    if (!on) return 0f
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    return p
}

/** a knob's outline while learning (the label's colour, around the knob body of [size] dp) */
@Composable
fun BoxScope.LearnRing(target: String, size: Float, theme: Theme, learn: LearnFeed) {
    if (!learn.active) return
    val sel = learn.selected == target
    val bound = target in learn.bound
    val p = pulse(sel)
    val col = theme.label.color
    Canvas(Modifier.matchParentSize()) {
        val r = (size / 2 + 6) * density
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val w = (if (sel) 2.5f + 1.5f * p else if (bound) 2f else 1.5f) * density
        val a = if (sel) 0.55f + 0.45f * p else if (bound) 0.95f else 0.6f
        if (sel) drawCircle(col.copy(alpha = 0.25f * p), r, c, style = Stroke(w + 5 * density))
        drawCircle(col.copy(alpha = a), r, c, style = Stroke(w, pathEffect = if (sel || bound) null else
            PathEffect.dashPathEffect(floatArrayOf(4 * density, 4 * density))))
        if (bound) {
            val d = Offset(c.x + r * 0.71f, c.y - r * 0.71f)
            drawCircle(theme.body.color, 4.5f * density, d)
            drawCircle(col, 3f * density, d)
        }
    }
}

/** a button's outline while learning (the LED's colour, in its well) */
@Composable
fun BoxScope.LearnOutline(target: String, radius: Float, theme: Theme, learn: LearnFeed) {
    if (!learn.active) return
    val sel = learn.selected == target
    val bound = target in learn.bound
    val p = pulse(sel)
    val col = theme.led.color
    Canvas(Modifier.matchParentSize()) {
        val w = (if (sel) 2.5f + 1.5f * p else if (bound) 2f else 1.2f) * density
        val a = if (sel) 0.55f + 0.45f * p else if (bound) 0.95f else 0.5f
        val o = -2 * density
        outline(col.copy(alpha = a), o, (radius + 2) * density, w, if (sel || bound) null else
            PathEffect.dashPathEffect(floatArrayOf(3 * density, 4 * density)))
        if (sel) outline(col.copy(alpha = 0.25f * p), o, (radius + 2) * density, w + 4 * density, null)
    }
}

private fun DrawScope.outline(c: Color, o: Float, r: Float, w: Float, effect: PathEffect?) {
    drawRoundRect(c, Offset(o, o), Size(size.width - 2 * o, size.height - 2 * o), CornerRadius(r), style = Stroke(w, pathEffect = effect))
}

/** the waiting pot's position on MASTER: a small hollow dot outside the knob */
@Composable
fun BoxScope.PotMark(size: Float, theme: Theme, pickup: PickupFeed) {
    val v = pickup.pot ?: return
    val col = theme.label.color.copy(alpha = 0.75f)
    Canvas(Modifier.matchParentSize().testTag("pot-mark")) {
        val a = Math.toRadians(-135 + v / 1023.0 * 270)
        val r = (size / 2 + 5) * density
        val c = Offset(this.size.width / 2 + r * sin(a).toFloat(), this.size.height / 2 - r * cos(a).toFloat())
        drawCircle(col, 2.6f * density, c, style = Stroke(1.5f * density))
    }
}

/** MIDI learn's bar: the dot, MIDI LEARN, the line, Forget, Done */
@Composable
fun LearnBar(learn: LearnFeed) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
            .shadow(14.dp, shape).clip(shape).background(Palette.sheetBackground).border(1.dp, Palette.sheetAccent, shape)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("learn-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val p = pulse(true)
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(Palette.sheetAccent.copy(alpha = 0.35f + 0.65f * p)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("MIDI LEARN", style = TextStyle(fontFamily = RfmFonts.hardware, fontSize = 11.sp, letterSpacing = (11 * 0.14).sp,
                                                 color = Palette.sheetAccent))
            Text(learn.text, style = ui(13, 500), maxLines = 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (learn.canForget) Pill("Forget", false, "learn-forget") { learn.forget() }
            Pill("Done", true, "learn-done") { learn.done() }
        }
    }
}

@Composable
private fun Pill(t: String, accent: Boolean, tag: String, action: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clickable { action() }.testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.height(36.dp).clip(RoundedCornerShape(50)).background(if (accent) Palette.sheetAccent else Palette.sheetRaised)
            .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(t, style = ui(13, 700, if (accent) Palette.sheetBackground else Palette.sheetText))
        }
    }
}

/** the toast, in the sheet's dark style with a tinted edge (site.css .toast) */
@Composable
fun ToastView(center: ToastCenter) {
    val t = center.current
    LaunchedEffect(t?.id) {
        if (t != null) {
            delay((t.seconds * 1000).toLong())
            center.hide(t.id)
        }
    }
    AnimatedVisibility(t != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        val shown = t ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(14.dp)
        val edge = when (shown.tone) {
            ToastCenter.Tone.OK -> Palette.linkedDot.copy(alpha = 0.55f)
            ToastCenter.Tone.BAD -> Color(1f, 100 / 255f, 100 / 255f, 0.6f)
            ToastCenter.Tone.NEUTRAL -> Palette.sheetBorder
        }
        Box(Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).shadow(14.dp, shape).clip(shape)
                .background(Palette.sheetBackground.copy(alpha = 0.97f)).border(1.dp, edge, shape)
                .padding(horizontal = 16.dp, vertical = 10.dp).testTag("toast"), contentAlignment = Alignment.Center) {
            Text(shown.text, style = ui(13, 500).copy(textAlign = TextAlign.Center), maxLines = 2)
        }
    }
}

/** the faceplate's bottom: the toast, above MIDI learn's bar while it is on (laid over everything; only the bar takes
 *  touches). [barAtTop]: the bar over the top instead (the Controls view's buttons reach the bottom) */
@Composable
fun FaceplateOverlay(feed: PanelFeed, bottom: Int = 14, barAtTop: Boolean = false) {
    Column(Modifier.fillMaxSize().padding(bottom = bottom.dp), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (feed.learn.active && barAtTop) Box(Modifier.padding(top = 6.dp)) { LearnBar(feed.learn) }
        Spacer(Modifier.weight(1f))
        ToastView(feed.toast)
        if (feed.learn.active && !barAtTop) LearnBar(feed.learn)
    }
}
