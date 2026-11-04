// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/Faceplate.swift
// The on-screen FM-1 (design/README.md sections 1-4): the whole view is the faceplate, in one of three views
//   Full      everything in the hardware's arrangement (phone portrait with the scrolling keybed, phone landscape,
//             tablet landscape, tablet portrait: FaceplateLayout)
//   Controls  a big display, one knob row (MAIN | KN 1-4) and a deck: BUTTONS (6 x 2 + OCT) or KEYS
//   Screen    the display alone
// Sideways swipes on the background move between the views (page dots and the sheet's tiles do the same); in
// Controls a sideways swipe on the knob row switches its set, and one in the deck switches the deck. The grabber (a
// tap, or a swipe down) opens the pull-down sheet. The feed is polled every frame while a faceplate is on screen.
package com.quixotic7.remotefm1.ui.faceplate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.ui.theme.Palette
import com.quixotic7.remotefm1.ui.theme.RfmFonts
import com.quixotic7.remotefm1.ui.theme.Theme
import com.quixotic7.remotefm1.ui.theme.color
import kotlin.math.abs
import kotlin.math.max

/**
 * The faceplate filling its space. [kind]: null picks the layout from the size. [chrome]: the status row / grabber
 * and the page dots. [poll]: this one polls the feed (the theme editor's preview doesn't).
 */
@Composable
fun FaceplateView(feed: PanelFeed, ui: FaceplateUi, theme: Theme, chip: PanelChip, protector: ScreenProtector,
                  modifier: Modifier = Modifier, kind: FaceplateLayout.Kind? = null, chrome: Boolean = true,
                  poll: Boolean = true, onWord: (() -> Unit)? = null, openSheet: () -> Unit = {}) {
    if (poll) {
        LaunchedEffect(feed) {
            try {
                while (true) withFrameNanos { feed.poll() }
            } finally {
                feed.releaseAll()                   // (no faceplate on screen holds anything now)
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(theme.body.color)) {
        val w = maxWidth.value
        val h = maxHeight.value
        val k = kind ?: FaceplateLayout.Kind.pick(w, h)
        // the background: sideways swipes change the view (controls on top take their own touches)
        Box(Modifier.fillMaxSize().swipe { ui.move(it) })
        Crossfade(ui.page, animationSpec = tween(250), label = "page") { page ->
            when (page) {
                FaceplateUi.Page.FULL -> FullView(feed, ui, theme, chip, protector, k, w, h, chrome, onWord, openSheet)
                FaceplateUi.Page.CONTROLS -> ControlsView(feed, ui, theme, chip, w, h, chrome, onWord, openSheet)
                FaceplateUi.Page.SCREEN -> ScreenOnlyView(feed, ui, theme, chip, w, h, chrome, openSheet, onWord)
            }
        }
        AnimatedVisibility(ui.displayAlone, enter = fadeIn(tween(250)) + scaleIn(tween(250), 0.92f),
                           exit = fadeOut(tween(250)) + scaleOut(tween(250), 0.92f)) {
            FullDisplay(feed, ui)
        }
    }
}

/** a sideways swipe (unconsumed touches only): -1 (to the right: the previous) or +1 */
fun Modifier.swipe(go: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        var dx = 0f
        var dy = 0f
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (c.isConsumed) return@awaitEachGesture
            dx = (c.position.x - down.position.x) / density
            dy = (c.position.y - down.position.y) / density
            if (!c.pressed) break
        }
        if (abs(dx) > 60 && abs(dx) > abs(dy) * 1.3f) go(if (dx < 0) 1 else -1)
    }
}

// ---------------------------------------------------------------- status row, grabber, page dots

@Composable
private fun StatusRow(theme: Theme, chip: PanelChip, spec: FaceplateLayout.Status, m: FaceplateLayout.Mapper,
                      word: String, onWord: (() -> Unit)?, openSheet: () -> Unit) {
    val y = m.y(spec.y)
    val h = m.len(32f)
    val s = m.s
    val rw = (m.ref.w - 2 * spec.margin) * m.sx
    val x = m.ox + m.ref.w * m.sx / 2 - rw / 2
    Box(Modifier.place(PRect(x, y, rw, h))) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Chip(theme, chip, spec.chipHeight * s, spec.chipPad * s, spec.chipFont * s)
            Spacer(Modifier.weight(1f))
            BasicText(
                word,
                Modifier.height(44.dp).wrapContentSize(Alignment.CenterEnd).testTag("word")
                    .then(if (onWord != null) Modifier.clickable(remember { MutableInteractionSource() }, null) { onWord() } else Modifier),
                style = TextStyle(fontFamily = RfmFonts.hardware, fontSize = dsp(spec.wordFont * s),
                                  letterSpacing = dsp(spec.wordFont * s * spec.wordTracking), color = theme.label.color),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = dsp(spec.wordFont * s * 0.6f), maxFontSize = dsp(spec.wordFont * s)),
            )
        }
        Grabber(theme, spec.grabberWidth * s, spec.barWidth * s, 44f, openSheet, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun Chip(theme: Theme, chip: PanelChip, height: Float, pad: Float, font: Float) {
    val dot = when (chip) {
        PanelChip.LINKED -> Palette.linkedDot
        PanelChip.IN_USE -> Palette.sheetAccent
        else -> theme.label.color
    }
    Row(Modifier.height(height.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.14f))
            .padding(horizontal = pad.dp).testTag("chip"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot.copy(alpha = if (chip == PanelChip.SEARCHING) 0.5f else 1f)))
        BasicText(chip.text, style = TextStyle(fontFamily = RfmFonts.ui, fontWeight = FontWeight.SemiBold,
                                               fontSize = dsp(font), color = theme.label.color), maxLines = 1)
    }
}

/** the pull-down handle: a tap or a swipe down opens the sheet (88 x 44 hit area at least) */
@Composable
fun Grabber(theme: Theme, width: Float, bar: Float, height: Float, open: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(max(width, 88f).dp, max(height, 44f).dp).testTag("grabber")
            .semantics { contentDescription = "Open controls, or swipe down"; role = Role.Button }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var dy = 0f
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        dy = (c.position.y - down.position.y) / density
                        if (abs(c.position.x - down.position.x) / density > 8 || abs(dy) > 8) moved = true
                        if (!c.pressed) break
                    }
                    if (!moved || dy > 20) open()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(bar.dp, 5.dp).clip(RoundedCornerShape(50)).background(theme.label.color.copy(alpha = 0.55f)))
    }
}

@Composable
private fun PageDots(theme: Theme, ui: FaceplateUi, scale: Float, cx: Float, cy: Float) {
    Row(Modifier.centredAt(cx, cy, 132f, 44f)) {
        for (p in FaceplateUi.Page.entries) {
            val on = ui.page == p
            val dw by animateFloatAsState(if (on) 22f else 8f, tween(250), label = "dot")
            Box(Modifier.size(44.dp).clickable(remember { MutableInteractionSource() }, null) { ui.page = p }
                .semantics { contentDescription = when (p) { FaceplateUi.Page.FULL -> "Full view"; FaceplateUi.Page.CONTROLS -> "Controls view"; else -> "Screen view" } }
                .testTag("dot-${p.raw}"),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size((dw * scale).dp, (8 * scale).dp).clip(RoundedCornerShape(50))
                    .background(theme.label.color.copy(alpha = if (on) 1f else 0.35f)))
            }
        }
    }
}

// ---------------------------------------------------------------- Full

@Composable
private fun FullView(feed: PanelFeed, ui: FaceplateUi, theme: Theme, chip: PanelChip, protector: ScreenProtector,
                     kind: FaceplateLayout.Kind, w: Float, h: Float, chrome: Boolean, onWord: (() -> Unit)?,
                     openSheet: () -> Unit) {
    val L = FaceplateLayout.full(kind)
    val m = FaceplateLayout.Mapper(kind.ref, PSize(w, h))
    val s = m.s
    Box(Modifier.fillMaxSize()) {
        if (chrome && L.status != null) {
            StatusRow(theme, chip, L.status, m, ui.word, onWord, openSheet)
        } else if (chrome && L.grabber != null) {
            val gr = L.grabber
            Grabber(theme, gr.w * s, 40 * s, gr.h * s, openSheet,
                    Modifier.centredAt(w / 2, max(22f, m.y(gr.midY)), max(gr.w * s, 88f), max(gr.h * s, 44f)))
        }
        KnobGrid(PanelKnob.MAIN, L.main, m, theme, feed)
        val o = L.oct
        OctWell(theme, feed, o.width * m.sx, o.height * m.sy, o.pad * s, o.gap * s, o.radius * s, o.buttonRadius * s,
                o.font * s, Modifier.place(m.rect(o.x, o.y, o.width, o.height)))
        val ds = L.displaySize * s
        val dr = PRect(m.x(L.displayX) + L.displaySize * m.sx / 2 - ds / 2, m.y(L.displayY), ds, ds)
        ProtectedDisplay(feed, theme, ds, ui, Modifier.place(dr))
        when (val kn = L.kn) {
            is FaceplateLayout.KnRow.Row -> KnRow(m, kn, theme, feed)
            is FaceplateLayout.KnRow.Grid -> KnobGrid(PanelKnob.KN, kn.c, m, theme, feed)
        }
        val b = L.buttons
        ButtonGrid(theme, feed, b.bw * m.sx, b.bh * m.sy, b.gap * s,
                   Modifier.place(PRect(m.x(b.x), m.y(b.y), 6 * b.bw * m.sx + 5 * b.gap * s + 20, 2 * b.bh * m.sy + b.gap * s + 20)))
        val kr = m.rect(L.keybed)
        KeybedView(theme, feed, kr.w, kr.h,
                   L.keyWidth?.let { KeybedGeometry.Layout.Scroll(it * m.sx) } ?: KeybedGeometry.Layout.Row,
                   ui.stepNumbers, ui.fullOffset, { ui.fullOffset = it }, Modifier.place(kr))
        if (chrome && L.pageDots) PageDots(theme, ui, s, w / 2, h - 18 * m.sy - 12)
        // the screen protector: its film over the display, its red tab beside the screen (the wide layouts only)
        if (kind.landscape) {
            val geo = remember(dr) { FilmGeometry(dr, max(10f, Math.round(ds * 0.09f).toFloat()), FilmGeometry.tab(dr)) }
            ScreenFilm(geo, protector, w, h)
        }
    }
}

/** knobs in a 2 x 2 grid of cells width/2 wide (the canvas's grid, items centred) */
@Composable
private fun KnobGrid(knobs: List<PanelKnob>, c: FaceplateLayout.Cluster, m: FaceplateLayout.Mapper, theme: Theme, feed: PanelFeed) {
    val size = c.knob * m.s
    val cell = FaceplateLayout.knobCell(size)
    val colW = c.width * m.sx / 2
    knobs.forEachIndexed { i, k ->
        val col = i % 2
        val row = i / 2
        KnobView(k, size, theme, feed, labelWidth = colW,
                 modifier = Modifier.centredAt(m.x(c.x) + colW * (col + 0.5f),
                                               m.y(c.y) + row * (cell.h + c.rowGap * m.sy) + cell.h / 2, cell.w, cell.h))
    }
}

/** KN1-4 spread across a width (its first slot, led wide, left empty: the FM-1's red square there is the screen
 *  protector's tab, ScreenFilm) */
@Composable
private fun KnRow(m: FaceplateLayout.Mapper, r: FaceplateLayout.KnRow.Row, theme: Theme, feed: PanelFeed) {
    val size = r.knob * m.s
    val cell = FaceplateLayout.knobCell(size)
    val w = r.width * m.sx
    val ledW = r.led * m.s
    val gap = (w - ledW - 4 * cell.w) / 4
    val cy = m.y(r.y) + cell.h / 2
    PanelKnob.KN.forEachIndexed { i, k ->
        KnobView(k, size, theme, feed, labelWidth = cell.w + gap,
                 modifier = Modifier.centredAt(m.x(r.x) + ledW + gap * (i + 1) + cell.w * (i + 0.5f), cy, cell.w, cell.h))
    }
}

// ---------------------------------------------------------------- Controls

@Composable
private fun ControlsView(feed: PanelFeed, ui: FaceplateUi, theme: Theme, chip: PanelChip, w: Float, h: Float,
                         chrome: Boolean, onWord: (() -> Unit)?, openSheet: () -> Unit) {
    val landscape = w > h
    val L = FaceplateLayout.controls(landscape)
    val m = FaceplateLayout.Mapper(L.ref, PSize(w, h), uniform = w > 600)
    val s = m.s
    fun toggleSet() { ui.knobSet = if (ui.knobSet == FaceplateUi.KnobSet.MAIN) FaceplateUi.KnobSet.KN else FaceplateUi.KnobSet.MAIN }
    Box(Modifier.fillMaxSize()) {
        if (chrome && L.status != null) {
            StatusRow(theme, chip, L.status, m, ui.word, onWord, openSheet)
        } else if (chrome && L.grabber != null) {
            val gr = L.grabber
            Grabber(theme, gr.w * s, 40 * s, gr.h * s, openSheet,
                    Modifier.centredAt(w / 2, max(22f, m.y(gr.midY)), max(gr.w * s, 88f), max(gr.h * s, 44f)))
        }
        val ds = L.displaySize * s
        ProtectedDisplay(feed, theme, ds, ui,
                         Modifier.place(PRect(m.x(L.displayX) + L.displaySize * m.sx / 2 - ds / 2, m.y(L.displayY), ds, ds)))
        // the deck's background: a sideways swipe here changes the deck
        Box(Modifier.place(m.rect(L.deck)).swipe { d -> ui.deck = if (d > 0) FaceplateUi.Deck.KEYS else FaceplateUi.Deck.BUTTONS })
        // the knob row (its gaps swipe the set too)
        Box(Modifier.place(m.rect(L.knobRow)).swipe { toggleSet() })
        val size = L.knob * s
        val cell = FaceplateLayout.knobCell(size)
        val rw = L.knobRow.w * m.sx
        val gap = (rw - 4 * cell.w) / 3
        Crossfade(ui.knobSet, animationSpec = tween(200), label = "knobs") { set ->
            Box(Modifier.fillMaxSize()) {
                (if (set == FaceplateUi.KnobSet.MAIN) PanelKnob.MAIN else PanelKnob.KN).forEachIndexed { i, k ->
                    KnobView(k, size, theme, feed, onSwipe = { toggleSet() },
                             modifier = Modifier.centredAt(m.x(L.knobRow.x) + i * (cell.w + gap) + cell.w / 2,
                                                           m.y(L.knobRow.y) + cell.h / 2, cell.w, cell.h))
                }
            }
        }
        SetSwitch(theme, ui, s, Modifier.place(m.rect(L.setSwitch)))
        DeckSwitch(theme, ui, s, Modifier.place(m.rect(L.deckSwitch)))
        Crossfade(ui.deck, animationSpec = tween(200), label = "deck") { deck ->
            Box(Modifier.fillMaxSize()) {
                if (deck == FaceplateUi.Deck.BUTTONS) {
                    val b = L.buttons
                    ButtonGrid(theme, feed, b.bw * m.sx, b.bh * m.sy, b.gap * s,
                               Modifier.place(PRect(m.x(b.x), m.y(b.y), 6 * b.bw * m.sx + 5 * b.gap * s + 20, 2 * b.bh * m.sy + b.gap * s + 20)))
                    val o = L.oct
                    OctWell(theme, feed, o.width * m.sx, o.height * m.sy, o.pad * s, o.gap * s, o.radius * s, o.buttonRadius * s,
                            o.font * s, Modifier.place(m.rect(o.x, o.y, o.width, o.height)))
                } else {
                    val kr = m.rect(L.keybed)
                    KeybedView(theme, feed, kr.w, kr.h, KeybedGeometry.Layout.Scroll(L.keyWidth * m.sx), ui.stepNumbers,
                               ui.deckOffset, { ui.deckOffset = it }, Modifier.place(kr))
                }
            }
        }
        if (chrome) PageDots(theme, ui, s, w / 2, h - 18 * m.sy - 12)
    }
}

/** MAIN | KN 1-4 */
@Composable
private fun SetSwitch(theme: Theme, ui: FaceplateUi, scale: Float, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy((6 * scale).dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        for ((t, set) in listOf("MAIN" to FaceplateUi.KnobSet.MAIN, "KN 1–4" to FaceplateUi.KnobSet.KN)) {
            val on = ui.knobSet == set
            val f = 10 * scale
            Box(Modifier.height(44.dp).clickable(remember { MutableInteractionSource() }, null) { ui.knobSet = set }
                .testTag("set-${set.raw}"), contentAlignment = Alignment.Center) {
                BasicText(t, Modifier.height((32 * scale).dp).clip(RoundedCornerShape(50))
                    .background(if (on) Color.Black.copy(alpha = 0.18f) else Color.Transparent)
                    .padding(horizontal = f.dp).wrapContentSize(Alignment.Center),
                          style = TextStyle(fontFamily = RfmFonts.hardware, fontSize = dsp(f), letterSpacing = dsp(f * 0.12f),
                                            color = theme.label.color))
            }
        }
    }
}

/** BUTTONS | KEYS */
@Composable
private fun DeckSwitch(theme: Theme, ui: FaceplateUi, scale: Float, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.16f)).padding((3 * scale).dp)) {
        for ((t, d) in listOf("BUTTONS" to FaceplateUi.Deck.BUTTONS, "KEYS" to FaceplateUi.Deck.KEYS)) {
            val on = ui.deck == d
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                .background(if (on) theme.key.color else Color.Transparent)
                .clickable(remember { MutableInteractionSource() }, null) { ui.deck = d }.testTag("deck-${d.raw}"),
                contentAlignment = Alignment.Center) {
                BasicText(t, style = TextStyle(fontFamily = RfmFonts.hardware, fontSize = dsp(12 * scale),
                                               letterSpacing = dsp(12 * scale * 0.1f),
                                               color = if (on) theme.keyText.color else theme.label.color,
                                               textAlign = TextAlign.Center))
            }
        }
    }
}

// ---------------------------------------------------------------- Screen

@Composable
private fun ScreenOnlyView(feed: PanelFeed, ui: FaceplateUi, theme: Theme, chip: PanelChip, w: Float, h: Float,
                           chrome: Boolean, openSheet: () -> Unit, onWord: (() -> Unit)? = null) {
    val portrait = h >= w
    val ref = if (portrait) FaceplateLayout.Kind.PHONE_PORTRAIT.ref else FaceplateLayout.Kind.PHONE_LANDSCAPE.ref
    val m = FaceplateLayout.Mapper(ref, PSize(w, h), uniform = true)
    val margin = 16 * m.s
    val top = if (portrait) 0f else 22f
    val side = max(40f, minOf(w - 2 * margin, h - 2 * margin - 2 * top))
    Box(Modifier.fillMaxSize()) {
        if (chrome) {
            if (portrait && w < 600) {
                StatusRow(theme, chip, FaceplateLayout.phoneStatus, m, ui.word, onWord, openSheet)   // (F, iOS 836a983: the firmware's name)
            } else if (portrait) {
                StatusRow(theme, chip, FaceplateLayout.padStatus,
                          FaceplateLayout.Mapper(FaceplateLayout.Kind.PAD_PORTRAIT.ref, PSize(w, h)), ui.word, onWord, openSheet)
            } else {
                Grabber(theme, 88f, 40f, 20f, openSheet, Modifier.centredAt(w / 2, 22f, 88f, 44f))
            }
        }
        ProtectedDisplay(feed, theme, side, ui, Modifier.centredAt(w / 2, h / 2, side, side))
    }
}
