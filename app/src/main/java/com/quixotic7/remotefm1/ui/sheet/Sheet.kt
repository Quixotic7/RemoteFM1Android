// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/Sheet.swift (and SafetyCopies.swift's SheetAsk,
// FirmwareTiles.swift's FirmwareThumb)
// The pull-down sheet (design/README.md section 3, canvas/PhoneSheet.dc.html) and its building blocks: the panel that
// comes down from the top over a dimmed faceplate, its header, section titles, the Sound source cards, the View tiles,
// the theme swatches, the list rows, toggles, a stepper, a segmented row, a sub-page with a back button, notes, pill
// buttons and the inline questions. No menus, popovers or dialogs in here (iOS's rule): a choice is a page, a question
// is asked again in the row itself.
package com.quixotic7.remotefm1.ui.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.ui.theme.Palette
import com.quixotic7.remotefm1.ui.theme.RfmFonts
import com.quixotic7.remotefm1.ui.theme.Theme
import com.quixotic7.remotefm1.ui.theme.ThemeStore
import com.quixotic7.remotefm1.ui.theme.color
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

fun ui(size: Int, weight: Int = 400, color: Color = Palette.sheetText) =
    TextStyle(fontFamily = RfmFonts.ui, fontWeight = FontWeight(weight), fontSize = size.sp, color = color)

/** the sheet: dims the faceplate, comes down from the top; Done, a tap on the dimmed part or a swipe up closes it */
@Composable
fun PullDownSheet(open: Boolean, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val full = maxHeight.value
        val w = min(maxWidth.value, 560f)
        val h = if (full < 500) full - 6 else min(full * 0.83f, 760f)
        AnimatedVisibility(open, enter = fadeIn(tween(280)), exit = fadeOut(tween(280))) {
            Box(Modifier.fillMaxSize().background(Color(10, 8, 6).copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, null) { close() }
                .semantics { contentDescription = "Close controls" }.testTag("sheet-dim"))
        }
        AnimatedVisibility(open, Modifier.align(Alignment.TopCenter),
                           enter = slideInVertically(tween(280)) { -it }, exit = slideOutVertically(tween(280)) { -it }) {
            val drag = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            Column(
                Modifier.width(w.dp).height(h.dp)
                    .offset { IntOffset(0, (min(0f, drag.value) * density).roundToInt()) }
                    .shadow(20.dp, RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                    .background(Palette.sheetBackground, RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .testTag("sheet")
                    .pointerInput(Unit) {
                        // a swipe up closes it (unconsumed touches: the lists scroll on their own)
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dy = 0f
                            var dx = 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                dx = (c.position.x - down.position.x) / density
                                dy = (c.position.y - down.position.y) / density
                                if (!c.pressed) break
                                if (!c.isConsumed && dy < -12 && abs(dy) > abs(dx)) scope.launch { drag.snapTo(dy) }
                            }
                            if (dy < -60 && drag.value < 0) close()
                            scope.launch { drag.animateTo(0f, tween(200)) }
                        }
                    },
            ) {
                Column(Modifier.weight(1f).fillMaxWidth().statusBarsPadding().padding(top = 9.dp)
                    .padding(horizontal = 20.dp), content = content)
                // the bottom grabber: swipe up (or tap) to close
                Box(Modifier.align(Alignment.CenterHorizontally).size(88.dp, 32.dp)
                    .clickable(remember { MutableInteractionSource() }, null) { close() }
                    .semantics { contentDescription = "Close controls, or swipe up"; role = Role.Button }
                    .testTag("sheet-grabber"),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp, 5.dp).clip(RoundedCornerShape(50)).background(Palette.sheetDim))
                }
            }
        }
    }
}

@Composable
fun SheetHeader(title: String, done: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = TextStyle(fontFamily = RfmFonts.title, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                                      letterSpacing = (-0.44).sp, color = Palette.sheetText))
        Spacer(Modifier.weight(1f))
        Pill("Done", Palette.sheetRaised, Palette.sheetText, Modifier.testTag("sheet-done"), done)
    }
}

@Composable
fun Pill(title: String, bg: Color, fg: Color, modifier: Modifier = Modifier, action: () -> Unit) {
    Box(modifier.height(44.dp).clip(RoundedCornerShape(50)).background(bg).clickable { action() }
        .padding(horizontal = 16.dp).semantics { role = Role.Button }, contentAlignment = Alignment.Center) {
        Text(title, style = ui(14, 700, fg))
    }
}

@Composable
fun SheetSectionTitle(text: String, top: Int = 20, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.fillMaxWidth().padding(top = top.dp, bottom = 10.dp),
         style = ui(11, 600, Palette.sheetMuted).copy(letterSpacing = (11 * 0.16).sp))
}

/** a Sound source card: a dot, a title, a line under it */
@Composable
fun RowScope.SourceCard(title: String, sub: String, selected: Boolean, dot: Color, tag: String, action: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.weight(1f).heightIn(min = 78.dp).clip(shape)
            .background(if (selected) Palette.sheetRaised else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Palette.sheetAccent else Palette.sheetBorder, shape)
            .clickable { action() }.semantics { this.selected = selected }.testTag(tag)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) dot else Palette.sheetDim))
            Text(title, style = ui(15, 700))
        }
        Text(sub, style = ui(12, 400, Palette.sheetMuted2), maxLines = 2)
    }
}

/** the View tiles: Full, Panel (Controls with the buttons), Keys (Controls with the keys), Screen */
@Composable
fun ViewTiles(ui: FaceplateUi, pick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in listOf("Full", "Panel", "Keys", "Screen")) {
            val on = when (t) {
                "Full" -> ui.page == FaceplateUi.Page.FULL
                "Panel" -> ui.page == FaceplateUi.Page.CONTROLS && ui.deck == FaceplateUi.Deck.BUTTONS
                "Keys" -> ui.page == FaceplateUi.Page.CONTROLS && ui.deck == FaceplateUi.Deck.KEYS
                else -> ui.page == FaceplateUi.Page.SCREEN
            }
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier.weight(1f).height(96.dp).clip(shape).background(if (on) Palette.sheetRaised else Color.Transparent)
                    .border(if (on) 1.5.dp else 1.dp, if (on) Palette.sheetAccent else Palette.sheetBorder, shape)
                    .clickable {
                        when (t) {
                            "Full" -> ui.page = FaceplateUi.Page.FULL
                            "Panel" -> { ui.page = FaceplateUi.Page.CONTROLS; ui.deck = FaceplateUi.Deck.BUTTONS }
                            "Keys" -> { ui.page = FaceplateUi.Page.CONTROLS; ui.deck = FaceplateUi.Deck.KEYS }
                            else -> ui.page = FaceplateUi.Page.SCREEN
                        }
                        pick()
                    }
                    .semantics { contentDescription = "$t view"; selected = on }.testTag("tile-$t"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Wire(t)
                Spacer(Modifier.height(9.dp))
                Text(t, style = ui(12, if (on) 700 else 600))
            }
        }
    }
}

/** the tiles' mini wireframes */
@Composable
private fun Wire(t: String) {
    val fg = Palette.sheetText
    val dim = Palette.sheetDim
    val acc = Palette.sheetAccent
    val r = RoundedCornerShape(3.dp)
    Box(Modifier.size(48.dp, 40.dp), contentAlignment = Alignment.Center) {
        when (t) {
            "Full" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.height(20.dp).width(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(12.dp).height(20.dp).background(dim, r))
                    Box(Modifier.width(20.dp).height(20.dp).background(fg, r))
                    Box(Modifier.weight(1f).height(20.dp).background(dim, r))
                }
                Box(Modifier.width(48.dp).height(16.dp).background(acc, r))
            }
            "Panel", "Keys" -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val s = if (t == "Panel") 22 else 20
                Box(Modifier.size(s.dp).background(fg, r))
                Box(Modifier.width(48.dp).height((40 - 4 - s).dp).background(if (t == "Panel") dim else acc, r))
            }
            else -> Box(Modifier.size(38.dp).background(fg, RoundedCornerShape(4.dp)))
        }
    }
}

/** a theme's swatch: body, bed, LED, ringed when chosen */
@Composable
fun ThemeSwatch(theme: Theme, selected: Boolean, ringed: Boolean = true) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(if (selected && ringed) Palette.sheetAccent else Color.Transparent),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(theme.body.color), contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(theme.bed.color), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(theme.led.color))
            }
        }
    }
}

/** the preset swatches (and the user's theme) and Edit */
@Composable
fun ThemeSwatches(store: ThemeStore, edit: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in store.all) {
                Box(Modifier.clip(CircleShape).clickable { store.select(t.name) }
                    .semantics { contentDescription = "${t.name} theme"; selected = store.current.name == t.name }
                    .testTag("swatch-${t.name}")) {
                    ThemeSwatch(t, store.current.name == t.name)
                }
            }
        }
        if (edit != null) {
            Box(Modifier.height(44.dp).clip(RoundedCornerShape(50)).border(1.dp, Palette.sheetBorder, RoundedCornerShape(50))
                .clickable { edit() }.padding(horizontal = 16.dp).testTag("theme-edit"), contentAlignment = Alignment.Center) {
                Text("Edit", style = ui(13, 600))
            }
        }
    }
}

/** a list (the sheet's rows) on its rounded surface */
@Composable
fun SheetList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.sheetSurface), content = content)
}

fun Modifier.divider(on: Boolean): Modifier = if (!on) this else drawBehind {
    drawRect(Palette.sheetLine, Offset(0f, size.height - 1.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
}

/** a chevron ›, a check ✓ or a ‹ drawn (the sheet has no icon font) */
@Composable
fun Glyph(kind: String, color: Color, size: Int = 13) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width
        val p = Path()
        when (kind) {
            "right" -> { p.moveTo(s * 0.35f, s * 0.15f); p.lineTo(s * 0.7f, s * 0.5f); p.lineTo(s * 0.35f, s * 0.85f) }
            "left" -> { p.moveTo(s * 0.65f, s * 0.15f); p.lineTo(s * 0.3f, s * 0.5f); p.lineTo(s * 0.65f, s * 0.85f) }
            "check" -> { p.moveTo(s * 0.12f, s * 0.55f); p.lineTo(s * 0.4f, s * 0.82f); p.lineTo(s * 0.9f, s * 0.2f) }
            "plus" -> { p.moveTo(s * 0.5f, s * 0.15f); p.lineTo(s * 0.5f, s * 0.85f); p.moveTo(s * 0.15f, s * 0.5f); p.lineTo(s * 0.85f, s * 0.5f) }
            "minus" -> { p.moveTo(s * 0.15f, s * 0.5f); p.lineTo(s * 0.85f, s * 0.5f) }
            "up-right" -> { p.moveTo(s * 0.25f, s * 0.75f); p.lineTo(s * 0.75f, s * 0.25f); p.moveTo(s * 0.35f, s * 0.25f); p.lineTo(s * 0.75f, s * 0.25f); p.lineTo(s * 0.75f, s * 0.65f) }
            "code" -> { p.moveTo(s * 0.3f, s * 0.25f); p.lineTo(s * 0.05f, s * 0.5f); p.lineTo(s * 0.3f, s * 0.75f)
                p.moveTo(s * 0.7f, s * 0.25f); p.lineTo(s * 0.95f, s * 0.5f); p.lineTo(s * 0.7f, s * 0.75f) }
        }
        drawPath(p, color, style = Stroke(s * 0.16f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun SheetRow(title: String, value: String? = null, divider: Boolean = true, chevron: Boolean = true, check: Boolean = false,
             badge: Int = 0, enabled: Boolean = true, tag: String? = null, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).divider(divider)
            .clickable(enabled = enabled) { action() }
            .semantics { selected = check }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val a = if (enabled) 1f else 0.4f
        Text(title, Modifier.weight(1f), style = ui(15, 600, Palette.sheetText.copy(alpha = a)), maxLines = 1,
             overflow = TextOverflow.Ellipsis)
        if (badge > 0) {
            Box(Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(50)).background(Palette.sheetAccent)
                .padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                Text("$badge", style = ui(12, 700, Palette.sheetBackground))
            }
        }
        if (value != null) Text(value, style = ui(15, 500, Palette.sheetMuted2.copy(alpha = a)), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
        if (check) Glyph("check", Palette.sheetAccent, 14)
        if (chevron) Glyph("right", Palette.sheetMuted2.copy(alpha = a), 13)
    }
}

/** a toggle row */
@Composable
fun SheetToggle(title: String, on: Boolean, divider: Boolean = true, tag: String? = null, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).divider(divider).clickable { set(!on) }
            .then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = ui(15, 600))
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = Palette.sheetAccent, checkedThumbColor = Color.White,
                                                       uncheckedTrackColor = Palette.sheetRaised, uncheckedBorderColor = Palette.sheetBorder,
                                                       uncheckedThumbColor = Palette.sheetMuted2))
    }
}

/** a row with a − value + stepper (Transpose) */
@Composable
fun SheetStepper(title: String, value: Int, range: IntRange, divider: Boolean = true, text: (Int) -> String = { if (it > 0) "+$it" else "$it" },
                 set: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).divider(divider).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = ui(15, 600), maxLines = 1)
        Text(text(value), style = ui(15, 500, Palette.sheetMuted2))
        Row(Modifier.clip(RoundedCornerShape(50)).background(Palette.sheetRaised), verticalAlignment = Alignment.CenterVertically) {
            for ((g, d) in listOf("minus" to -1, "plus" to 1)) {
                val can = (value + d) in range
                Box(Modifier.size(44.dp, 32.dp).clickable(enabled = can) { set(value + d) }, contentAlignment = Alignment.Center) {
                    Glyph(g, if (can) Palette.sheetText else Palette.sheetDim, 13)
                }
                if (d < 0) Box(Modifier.size(1.dp, 18.dp).background(Palette.sheetLine))
            }
        }
    }
}

/** a sub-page of the sheet: "‹ back" and a title, then its content (scrolls) */
@Composable
fun SheetPage(title: String, backTitle: String = "Controls", back: () -> Unit, scroll: ScrollState = rememberScrollState(),
              content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(44.dp).clickable { back() }.testTag("sheet-back"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Glyph("left", Palette.sheetMuted2, 15)
            Text(backTitle, style = ui(15, 600, Palette.sheetMuted2))
        }
        Text(title, Modifier.padding(bottom = 6.dp).testTag("sheet-title"),
             style = TextStyle(fontFamily = RfmFonts.title, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                               letterSpacing = (-0.4).sp, color = Palette.sheetText))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(bottom = 12.dp), content = content)
    }
}

/** small print in the sheet */
@Composable
fun SheetNote(text: String, color: Color = Palette.sheetMuted) {
    Text(text, Modifier.fillMaxWidth().padding(top = 8.dp), style = ui(12, 400, color))
}

/** a pill button in the sheet (accent: the prominent one) */
@Composable
fun SheetButton(title: String, accent: Boolean = false, enabled: Boolean = true, tag: String? = null, action: () -> Unit) {
    Box(Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(if (accent) Palette.sheetAccent else Palette.sheetRaised)
        .clickable(enabled = enabled) { action() }.then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(title, style = ui(14, 700, (if (accent) Palette.sheetBackground else Palette.sheetText).copy(alpha = if (enabled) 1f else 0.4f)))
    }
}

/** a question asked again inline: the question on its own lines, then Cancel and the red button */
@Composable
fun SheetAsk(question: String, action: String, cancel: () -> Unit, confirm: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question, style = ui(13, 600))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            SheetButton("Cancel", action = cancel)
            Pill(action, Palette.red, Color.White, Modifier.testTag("ask-confirm"), confirm)
        }
    }
}

/** a question in one row (Delete X?): Cancel and the red button */
@Composable
fun SheetConfirmRow(question: String, action: String, cancel: () -> Unit, confirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(question, Modifier.weight(1f), style = ui(13, 600), maxLines = 2)
        SheetButton("Cancel", action = cancel)
        Pill(action, Palette.red, Color.White, Modifier.testTag("ask-confirm"), confirm)
    }
}

/** a firmware's thumbnail, or its name on a colour of its own */
@Composable
fun FirmwareThumb(image: ImageBitmap?, id: String, name: String, corner: Int = 14, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(corner.dp)
    Box(modifier.aspectRatio(1f).clip(shape).background(Color.Black), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            val h = hue(id.ifEmpty { name })
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.hsv(h, 0.55f, 0.55f), Color.hsv(h, 0.7f, 0.28f)))))
            Text(name.ifEmpty { "?" }, Modifier.padding(8.dp), maxLines = 2, textAlign = TextAlign.Center,
                 style = TextStyle(fontFamily = RfmFonts.title, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                   color = Color.White.copy(alpha = 0.92f)))
        }
    }
}

/** a stable hue per firmware (iOS's djb2 over the id), 0..360 */
fun hue(s: String): Float {
    var h = 5381L
    for (c in s.codePoints().toArray()) h = (h * 33 + c) and 0xFFFFFFFFL
    return (h % 360).toFloat()
}
