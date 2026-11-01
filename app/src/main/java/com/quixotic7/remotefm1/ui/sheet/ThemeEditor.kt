// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/ThemeEditor.swift
// The theme editor (design/README.md section 6, canvas/Theme.dc.html): a live preview (the tablet-landscape faceplate
// scaled down, so it always shows the real arrangement), the preset tiles, and five colours: Faceplate, Keys and
// buttons, Knobs, LEDs and the display's bezel. Changing a colour derives the rest (Theme.derived). Done keeps the
// result (an edited theme is stored as "My theme"); Save as my theme stores it; Cancel leaves the theme as it was.
// Android has no system colour picker: a row opens its own hue / saturation / brightness sliders in place.
package com.quixotic7.remotefm1.ui.sheet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quixotic7.remotefm1.ui.faceplate.FaceplateLayout
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.ui.faceplate.FaceplateView
import com.quixotic7.remotefm1.ui.faceplate.PanelChip
import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import com.quixotic7.remotefm1.ui.faceplate.ScreenProtector
import com.quixotic7.remotefm1.ui.theme.Palette
import com.quixotic7.remotefm1.ui.theme.RfmFonts
import com.quixotic7.remotefm1.ui.theme.Rgb
import com.quixotic7.remotefm1.ui.theme.Theme
import com.quixotic7.remotefm1.ui.theme.ThemeStore
import com.quixotic7.remotefm1.ui.theme.color
import kotlin.math.min

private enum class Part(val label: String) { BODY("Faceplate"), KEY("Keys and buttons"), KNOB("Knobs"), LED("LEDs"), BEZEL("Display bezel") }

@Composable
fun ThemeEditor(store: ThemeStore, feed: PanelFeed, close: () -> Unit) {
    var draft by remember { mutableStateOf(store.current) }
    var edited by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Part?>(null) }
    val previewUi = remember { FaceplateUi() }
    val noFilm = remember { ScreenProtector(null).also { it.set(false) } }
    fun color(p: Part) = when (p) { Part.BODY -> draft.body; Part.KEY -> draft.key; Part.KNOB -> draft.knob; Part.LED -> draft.led; Part.BEZEL -> draft.bezel }
    fun set(p: Part, c: Rgb) {
        val ch = draft.choice
        val n = when (p) {
            Part.BODY -> ch.copy(body = c); Part.KEY -> ch.copy(key = c); Part.KNOB -> ch.copy(knob = c)
            Part.LED -> ch.copy(led = c); Part.BEZEL -> ch.copy(bezel = c)
        }
        if (n == ch) return
        draft = Theme.derived(n, base = draft)
        edited = true
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.sheetBackground).testTag("theme-editor")
        .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {}) {   // (the faceplate below takes no touches)
        val width = min(maxWidth.value - 32, 560f)
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
               horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 600.dp)) {
                // header: Cancel, Theme, Done
                Box(Modifier.fillMaxWidth().height(44.dp).padding(top = 0.dp)) {
                    Text("Cancel", Modifier.align(Alignment.CenterStart).clickable { close() }.padding(horizontal = 4.dp, vertical = 10.dp)
                        .testTag("editor-cancel"), style = ui(15, 600, Palette.sheetMuted2))
                    Text("Theme", Modifier.align(Alignment.Center), style = TextStyle(fontFamily = RfmFonts.title, fontWeight = FontWeight.Bold,
                                                                                     fontSize = 17.sp, color = Palette.sheetText))
                    Pill("Done", Palette.sheetAccent, Palette.sheetBackground, Modifier.align(Alignment.CenterEnd).testTag("editor-done")) {
                        if (edited) store.saveCustom(draft) else store.select(draft.name)
                        close()
                    }
                }
                // the preview: the tablet-landscape faceplate, no chrome, scaled into the card
                val inner = width - 24
                val k = inner / 1194f
                Box(Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(28.dp))
                    .background(draft.body.color).padding(12.dp)) {
                    Box(Modifier.size(inner.dp, (834 * k).dp).clip(RoundedCornerShape(16.dp)).clipToBounds()) {
                        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(1194.dp, 834.dp)
                            .graphicsLayer { scaleX = k; scaleY = k; transformOrigin = TransformOrigin(0f, 0f) }) {
                            FaceplateView(feed, previewUi, draft, PanelChip.EMULATOR, noFilm, kind = FaceplateLayout.Kind.PAD_LANDSCAPE,
                                          chrome = false, poll = false)
                        }
                        Box(Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                        })
                    }
                }
                SheetSectionTitle("Presets", top = 28)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in store.all) {
                        val on = !edited && draft.name == t.name
                        Column(Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (on) Palette.sheetRaised else Color.Transparent)
                            .clickable { draft = t; edited = false }.testTag("preset-${t.name}"),
                               horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(48.dp).border(2.dp, if (on) Palette.sheetAccent else Color.Transparent, CircleShape),
                                contentAlignment = Alignment.Center) { ThemeSwatch(t, false, ringed = false) }
                            Text(t.name, Modifier.padding(top = 3.dp), style = ui(11, 600), maxLines = 1)
                        }
                    }
                }
                SheetSectionTitle("Customize", top = 24)
                SheetList {
                    Part.entries.forEachIndexed { i, p ->
                        val c = color(p)
                        Row(Modifier.fillMaxWidth().height(44.dp).divider(i < Part.entries.size - 1 || open == p)
                            .clickable { open = if (open == p) null else p }.padding(horizontal = 16.dp).testTag("part-${p.name}"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(c.color).border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape))
                            Text(p.label, Modifier.weight(1f), style = ui(15, 600))
                            Text(c.hex, style = TextStyle(fontFamily = RfmFonts.mono, fontSize = 13.sp, color = Palette.sheetMuted2))
                            Glyph(if (open == p) "minus" else "right", Palette.sheetMuted2, 13)
                        }
                        if (open == p) ColourSliders(c, i < Part.entries.size - 1) { set(p, it) }
                    }
                }
                Box(Modifier.padding(vertical = 24.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                    .border(1.dp, Palette.sheetBorder, RoundedCornerShape(50)).clickable { store.saveCustom(draft); close() }
                    .testTag("editor-save"), contentAlignment = Alignment.Center) {
                    Text("Save as my theme", style = ui(15, 700))
                }
            }
        }
    }
}

/** hue, saturation, brightness for one colour, in place of iOS's system picker */
@Composable
private fun ColourSliders(c: Rgb, divider: Boolean, set: (Rgb) -> Unit) {
    val hsv = remember { FloatArray(3) }
    android.graphics.Color.colorToHSV(c.argb, hsv)
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    fun push() = set(Rgb.argb(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))))
    Column(Modifier.fillMaxWidth().divider(divider).padding(horizontal = 16.dp, vertical = 4.dp)) {
        val colors = SliderDefaults.colors(thumbColor = Palette.sheetText, activeTrackColor = Palette.sheetAccent,
                                           inactiveTrackColor = Palette.sheetRaised)
        for ((name, value, range) in listOf(Triple("Hue", h, 0f..360f), Triple("Saturation", s, 0f..1f), Triple("Brightness", v, 0f..1f))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.widthIn(min = 84.dp), style = ui(12, 600, Palette.sheetMuted2))
                Slider(value, { x -> when (name) { "Hue" -> h = x; "Saturation" -> s = x; else -> v = x }; push() },
                       Modifier.weight(1f), valueRange = range, colors = colors)
            }
        }
    }
}
