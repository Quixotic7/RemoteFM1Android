// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/MidiSheet.swift (and Sheet.swift's SheetSegmented,
// SheetStatus; App/MainScreen.swift's midiPage, midiInputPage)
// (A6) the sheet's MIDI pieces (js/sheet.js's Playing section, js/omx-link.js mountOmx), in Sheet.kt's style:
//   MidiMapSection  Knobs from MIDI: Learn, Use learned knobs, the knob speed, the learned mappings (an encoder's
//                   mode, remove), Clear all (asked again inline)
//   MidiModeList    an encoder mapping's mode, a page of its own (opened from MidiMapSection's row)
//   MidiInputList   the MIDI input: all, none, or one device (ControllerInput)
//   OmxSection      the OMX-27: what it does, Connect / Disconnect, its status, the note
//   KeyChannelRows  Keys from MIDI (its value opens KeyChannelList) and "...and reach the firmware's MIDI in"
//   KeyChannelList  the Keys from MIDI page: Any, Channel 1–16, Off
// Choices open a page in the sheet, never a menu or a dialog, as on iOS.
package com.quixotic7.remotefm1.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.midi.ControllerInput
import com.quixotic7.remotefm1.midi.KeyChannel
import com.quixotic7.remotefm1.midi.MidiMap
import com.quixotic7.remotefm1.midi.MidiMapStore
import com.quixotic7.remotefm1.midi.MidiMapping
import com.quixotic7.remotefm1.midi.MidiMode
import com.quixotic7.remotefm1.midi.MidiSource
import com.quixotic7.remotefm1.omx.OmxLink
import com.quixotic7.remotefm1.omx.OmxViews
import com.quixotic7.remotefm1.ui.faceplate.ButtonNames
import com.quixotic7.remotefm1.ui.sheet.Glyph
import com.quixotic7.remotefm1.ui.sheet.SheetAsk
import com.quixotic7.remotefm1.ui.sheet.SheetButton
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.sheet.SheetToggle
import com.quixotic7.remotefm1.ui.sheet.divider
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette

/** a row with a few choices side by side (Knob speed) */
@Composable
fun SheetSegmented(title: String, options: List<String>, selected: Int, divider: Boolean = true, tag: String? = null,
                   pick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).divider(divider).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = ui(15, 600), maxLines = 1)
        Row(Modifier.clip(RoundedCornerShape(50)).background(Palette.sheetRaised).padding(3.dp)) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                Box(Modifier.heightIn(min = 30.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(50))
                        .background(if (on) Palette.sheetAccent else Color.Transparent).clickable { pick(i) }
                        .then(if (tag != null) Modifier.testTag("$tag-$i") else Modifier).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center) {
                    Text(o, style = ui(13, if (on) 700 else 600, if (on) Palette.sheetBackground else Palette.sheetText), maxLines = 1)
                }
            }
        }
    }
}

/** a status line with its dot (pulsing while busy) */
@Composable
fun SheetStatus(text: String, dot: Color, pulsing: Boolean = false, color: Color = Palette.sheetMuted) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val a = if (pulsing) {
            val t = rememberInfiniteTransition(label = "status")
            val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
            0.35f + 0.65f * p
        } else 1f
        Box(Modifier.padding(top = 4.dp).size(8.dp).alpha(a).clip(CircleShape).background(dot))
        Text(text, style = ui(12, 500, color))
    }
}

/** Keys from MIDI (a row that opens the page) and whether those notes reach the firmware too (dimmed when Off) */
@Composable
fun ColumnScope.KeyChannelRows(channel: KeyChannel, toFirmware: Boolean, setToFirmware: (Boolean) -> Unit, open: () -> Unit) {
    SheetRow("Keys from MIDI", value = channel.label, tag = "row-keychannel", action = open)
    Box(Modifier.alpha(if (channel == KeyChannel.Off) 0.45f else 1f)) {
        SheetToggle("...and reach the firmware's MIDI in", toFirmware, tag = "toggle-keystofirmware") {
            if (channel != KeyChannel.Off) setToFirmware(it)
        }
    }
}

/** the Keys from MIDI page's content: Any, Channel 1–16, Off, the current one checked */
@Composable
fun KeyChannelList(channel: KeyChannel, set: (KeyChannel) -> Unit) {
    KeyChannel.groups.forEachIndexed { g, group ->
        SheetList(Modifier.padding(top = if (g == 0) 12.dp else 14.dp)) {
            group.forEachIndexed { i, k ->
                SheetRow(k.longLabel, divider = i < group.size - 1, chevron = false, check = k == channel,
                         tag = "keychannel-${k.engineValue}") { set(k) }
            }
        }
        if (g == 0) SheetNote("Notes on every channel play the keys.")
        if (g == 2) SheetNote("Every note goes to the firmware's MIDI in; none play the keys.")
    }
    SheetNote("Notes on other channels go to the firmware's MIDI in.")
}

/** an encoder mapping's mode, as a page: how its CC values turn the knob */
@Composable
fun MidiModeList(store: MidiMapStore, src: String) {
    store.gen
    val m = store.mappings.firstOrNull { it.src == src }
    val mode = m?.mode
    if (m == null || mode == null) {
        SheetNote("This mapping is gone.")
        return
    }
    Text("${m.target} ← ${MidiSource.label(m.src)}", Modifier.padding(top = 8.dp, bottom = 10.dp), style = ui(15, 700))
    SheetList {
        MidiMode.entries.forEachIndexed { i, md ->
            SheetRow(md.label, divider = i < MidiMode.entries.size - 1, chevron = false, check = md == mode,
                     tag = "mode-${md.raw}") { store.setMode(m.src, md) }
        }
    }
    SheetNote("How the controller's CC values turn the knob. Absolute: a pot or fader (0–127). The relative ones are " +
              "endless encoders: 64 ± n, two's complement (1 up, 127 down), or a sign bit (1 up, 65 down). Learn " +
              "guesses it; change it if the knob jumps or turns the wrong way.")
}

/** Knobs from MIDI: Learn, Use learned knobs, Knob speed, the mappings, Clear all */
@Composable
fun MidiMapSection(store: MidiMapStore, useMap: Boolean?, setUseMap: (Boolean) -> Unit, names: ButtonNames,
                   pickMode: (String) -> Unit, learn: () -> Unit, top: Int = 20) {
    store.gen
    val maps = store.mappings
    var confirming by remember { mutableStateOf(false) }
    if (maps.isEmpty()) confirming = false
    SheetSectionTitle("Knobs from MIDI", top = top)
    SheetList {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).divider(true).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("MIDI learn", style = ui(15, 600))
                Text(if (maps.isEmpty()) "Your controller's knobs, pots and buttons on the panel" else "${maps.size} learned",
                     style = ui(12, 400, Palette.sheetMuted2))
            }
            SheetButton("Learn", accent = true, tag = "button-learn", action = learn)
        }
        if (useMap != null) SheetToggle("Use learned knobs", useMap, tag = "toggle-usemap", set = setUseMap)
        SheetSegmented("Knob speed", listOf("½×", "1×", "2×"), MidiMap.SPEEDS.indexOf(store.speed).takeIf { it >= 0 } ?: 1,
                       divider = false, tag = "speed") { store.setSpeed(MidiMap.SPEEDS[it]) }
    }
    SheetNote("Tap Learn, then a knob or button on the panel, then move a control on your MIDI device. Knob speed: " +
              "detents per CC step, for learned knobs." + if (useMap == null) "" else " Use learned knobs off: the " +
              "mappings are kept but do nothing, and their messages go on to the keys and the firmware. Learn still works.")
    if (maps.isNotEmpty()) {
        SheetList(Modifier.padding(top = 12.dp).testTag("mappings")) {
            maps.forEachIndexed { i, m -> MappingRow(store, m, names, i < maps.size - 1, pickMode) }
        }
        Box(Modifier.padding(top = 10.dp)) {
            if (confirming) {
                SheetList { SheetAsk("Clear every mapping?", "Clear all", cancel = { confirming = false }) { confirming = false; store.clear() } }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.height(44.dp).clip(RoundedCornerShape(50)).border(1.dp, Palette.sheetBorder, RoundedCornerShape(50))
                    .clickable { confirming = true }.padding(horizontal = 16.dp).testTag("button-clearall"),
                    contentAlignment = Alignment.Center) { Text("Clear all", style = ui(13, 600)) }
            }
        }
    }
}

@Composable
private fun MappingRow(store: MidiMapStore, m: MidiMapping, names: ButtonNames, divider: Boolean, pickMode: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).divider(divider).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Palette.sheetText)) { append(names.title(m.target)) }
            withStyle(SpanStyle(color = Palette.sheetMuted)) { append(" ← ") }
            withStyle(SpanStyle(color = Palette.sheetMuted2)) { append(MidiSource.label(m.src)) }
        }, Modifier.weight(1f), style = ui(14, 500), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val mode = m.mode
        if (mode != null) {
            Box(Modifier.heightIn(min = 44.dp).clickable { pickMode(m.src) }.testTag("modepill-${m.src}"), contentAlignment = Alignment.Center) {
                Row(Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(Palette.sheetRaised).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(mode.short, style = ui(13, 600), maxLines = 1)
                    Glyph("right", Palette.sheetText, 10)
                }
            }
        }
        Box(Modifier.size(40.dp, 44.dp).clickable { store.remove(m.src) }.testTag("remove-${m.src}"), contentAlignment = Alignment.Center) {
            Text("×", style = ui(18, 700, Palette.sheetMuted2))
        }
    }
}

/** the MIDI input row's value: All, None, the device's name */
fun midiInputValue(input: ControllerInput): String = when (val s = input.selection) {
    ControllerInput.Selection.All -> "All"
    ControllerInput.Selection.None -> "None"
    is ControllerInput.Selection.One -> input.sources.firstOrNull { it.key == s.key }?.name ?: "Not connected"
}

/** the MIDI input's choices: All inputs, None, each device (the FM-1 and the OMX-27 in remote mode left out) */
@Composable
fun MidiInputList(input: ControllerInput) {
    val sel = input.selection
    val sources = input.sources
    val missing = sel is ControllerInput.Selection.One && sources.none { it.key == sel.key }
    SheetList(Modifier.padding(top = 12.dp)) {
        SheetRow("All inputs", chevron = false, check = sel == ControllerInput.Selection.All, tag = "input-all") {
            input.select(ControllerInput.Selection.All)
        }
        SheetRow("None", divider = sources.isNotEmpty() || missing, chevron = false, check = sel == ControllerInput.Selection.None,
                 tag = "input-none") { input.select(ControllerInput.Selection.None) }
        sources.forEachIndexed { i, s ->
            SheetRow(s.name, value = if (s.error.isNotEmpty()) "can't open" else null, divider = i < sources.size - 1 || missing,
                     chevron = false, check = sel == ControllerInput.Selection.One(s.key), tag = "input-${s.name}") {
                input.select(ControllerInput.Selection.One(s.key))
            }
        }
        if (missing) SheetRow("The device picked before", value = "not connected", divider = false, chevron = false, check = true) {}
    }
    sources.filter { it.error.isNotEmpty() }.forEach { SheetNote("${it.name}: ${it.error}.", Palette.orange) }
    SheetNote(if (sources.isEmpty()) "No MIDI device found: plug one in over USB, or pair one over Bluetooth."
              else "${sources.size} found. A MIDI keyboard plays the keys; its knobs can be learned. The FM-1, and an " +
                  "OMX-27 in remote mode, are not listed.")
}

/** js/omx-link.js mountOmx: the OMX-27 as the FM-1's controller */
@Composable
fun OmxSection(link: OmxLink) {
    val live = link.want && link.tone != OmxLink.Tone.BAD
    Text("An OMX-27 becomes this FM-1's controller: its keys, encoder and pots play it, and the app lights its keys and " +
         "draws its screen. K1 picks the view (Base, Seq, Keyboard, Quick Keys), K2 the colours, K5 is MASTER.",
         Modifier.padding(bottom = 10.dp), style = ui(13, 400, Palette.sheetMuted2))
    Row { SheetButton(link.buttonTitle, accent = !live, tag = "button-omx") { link.toggle() } }
    val bad = Color(1f, 0.42f, 0.4f)
    SheetStatus(link.detail, dot = when (link.tone) {
        OmxLink.Tone.OFF -> Palette.sheetDim
        OmxLink.Tone.BUSY -> Palette.sheetAccent
        OmxLink.Tone.ON -> Palette.linkedDot
        OmxLink.Tone.BAD -> bad
    }, pulsing = link.tone == OmxLink.Tone.BUSY,
        color = if (link.tone == OmxLink.Tone.ON) Palette.linkedDot else if (link.tone == OmxLink.Tone.BAD) bad else Palette.sheetMuted)
    SheetNote("Needs the OMX-27 firmware with REMOTE mode, connected over USB. Once connected, it connects by itself " +
              "when the app opens or the OMX-27 is plugged in. ${OmxViews.THEMES.size} colour themes on K2. ${OmxLink.LEAVE}")
    Spacer(Modifier.height(4.dp))
}
