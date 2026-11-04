// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS ca42bbc RFMKit/Sources/RFMKit/Faceplate/SkinSettings.swift
// The sheet's Background section (Skin.kt): the user's image (the photo picker), its dim, and whether a firmware's own
// skin shows. [pick] null leaves out choosing the image (no picker here); the settings still apply.
package com.quixotic7.remotefm1.ui.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.ui.theme.Palette
import com.quixotic7.remotefm1.ui.theme.SkinStore

@Composable
fun SkinSettings(skins: SkinStore, pick: (() -> Unit)?, failed: Boolean) {
    SheetList {
        if (pick != null) {
            SheetRow("Background image", value = if (skins.hasImage) "Photo" else "None", tag = "row-background") { pick() }
            if (skins.hasImage) {
                SheetRow("Remove background image", chevron = false, tag = "row-background-remove") { skins.removeImage() }
            }
        }
        if (skins.hasImage) {
            Row(Modifier.fillMaxWidth().height(52.dp).divider(true).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dim", style = ui(15, 600))
                Slider(skins.dim.toFloat(), { skins.dim = it.toDouble() }, Modifier.weight(1f).testTag("skin-dim")
                           .semantics { contentDescription = "Dim the background image" },
                       valueRange = 0f..SkinStore.MAX_DIM.toFloat(),
                       colors = SliderDefaults.colors(thumbColor = Palette.sheetAccent, activeTrackColor = Palette.sheetAccent,
                                                      inactiveTrackColor = Palette.sheetRaised))
            }
        }
        SheetToggle("Firmware skins", skins.firmwareSkins, divider = false, tag = "toggle-firmwareskins") { skins.firmwareSkins = it }
    }
    if (failed) SheetNote("That photo couldn't be read. Try another one.", Palette.orange)
    SheetNote("A firmware that comes with its own skin shows it while it plays. Turn off Firmware skins to keep " +
              "your background and theme for every firmware.")
}
