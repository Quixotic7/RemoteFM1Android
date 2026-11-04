// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS c47ac4f App/MainScreen.swift (audioInputSection): the Firmware page's Audio input for a
// firmware that wants or needs it (ain1): Use audio input (RECORD_AUDIO asked on the first turn on), the inputs to
// pick from while it is on, the note, and why it couldn't when it couldn't. Also the PerfGuard note (a firmware too
// heavy for this device), iOS CorePlayer.perfNote.
package com.quixotic7.remotefm1.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.quixotic7.remotefm1.AudioInput
import com.quixotic7.remotefm1.ui.sheet.FirmwareEntry
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.sheet.SheetToggle
import com.quixotic7.remotefm1.ui.theme.Palette

/** (ain1) the selected firmware's audio input, when its manifest lists it; nothing otherwise */
@Composable
fun AudioInputSection(a: AppUi, c: FirmwareEntry) {
    val m = a.model
    val ain = m.audioIn
    val mode = m.audioInMode(c.id)
    if (mode <= 0) return
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }                 // (re-read the setting after a change)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            ain.set(c.firmwareId, true)
            note = ""
        } else {
            note = "RemoteFM1 may not use the microphone. Allow it in Settings › Apps › RemoteFM1 › Permissions."
        }
        tick++
        m.audioInChanged()
    }
    tick
    val on = ain.isOn(c.firmwareId) && ain.granted
    SheetSectionTitle("Audio input", top = 14)
    SheetList {
        SheetToggle("Use audio input", on && ain.live, divider = on && ain.live, tag = "toggle-audioin") { v ->
            if (v && !ain.granted) {
                ask.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                ain.set(c.firmwareId, v)
                tick++
                m.audioInChanged()
            }
        }
        if (on && ain.live) {
            val ports = ain.ports()
            val now = ain.portInUse
            ports.forEachIndexed { i, p ->
                SheetRow(p.name, divider = i < ports.size - 1, chevron = false,
                         check = p.id == now || (now == 0 && i == 0)) {
                    ain.set(c.firmwareId, true, p.id)
                    tick++
                    m.audioInChanged()
                }
            }
        }
    }
    SheetNote(AudioInput.note(c.name, mode))
    val why = note.ifEmpty { ain.problem }
    if (why.isNotEmpty()) SheetNote(why, Palette.orange)
}

/** (PerfGuard) the running Wasm firmware can't keep up on this device */
@Composable
fun PerfNote(a: AppUi) {
    val n = a.model.perfNote
    if (n.isNotEmpty()) SheetNote(n, Palette.orange)
}
