// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/OMX27/PanelOmxTarget.swift
// The app's panel as the OMX-27's target: its keys and buttons held as the OMX's own source in the PanelFeed (a
// touch on the screen letting go never lets go of the OMX's, and omxReleaseAll lets go of the OMX's only), turns and
// MASTER through the feed (rfme_event: the main thread, its one producer), the Keyboard view's notes straight to the
// firmware's MIDI in (to_firmware), K5's pickup as MASTER's mark and toast. The key LEDs are read from the panel
// snapshot, polled here when the faceplate's own frame hasn't (no faceplate on screen, the app in the background).
package com.quixotic7.remotefm1.omx

import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import com.quixotic7.remotefm1.ui.faceplate.PanelKnob
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel

class PanelOmxTarget(val feed: PanelFeed) : OmxTarget {
    override fun omxKey(k: Int, down: Boolean) {
        if (down) feed.keyDown(k, PanelFeed.Source.OMX) else feed.keyUp(k, PanelFeed.Source.OMX)
    }

    override fun omxButton(l: PanelLabel, down: Boolean) = feed.button(l, down, PanelFeed.Source.OMX)

    override fun omxTurn(k: PanelKnob, detents: Int) = feed.turn(k, detents, PanelFeed.Source.OMX)

    /** (nothing shown: the OMX's own screen names the knob) */
    override fun omxSelect(k: PanelKnob) {}

    override fun omxMaster(v: Int) {
        feed.master(v, PanelFeed.Source.OMX)
        feed.pickup.hide()
    }

    override fun omxPickup(pot: Int, at: Int, first: Boolean) = feed.pickupAt(pot, at, first)

    override fun omxFirmwareMidi(bytes: ByteArray) = feed.midiToFirmware(bytes)

    override val omxMasterNow: Int get() = feed.knobs.master

    override fun omxKeyLevel(k: Int): Int {
        if (k !in 0 until 27) return 0
        feed.pollPanel(MAX_AGE_MS)
        return feed.lights.keys[k]
    }

    override fun omxReleaseAll() = feed.releaseAll(PanelFeed.Source.OMX)

    companion object {
        /** how old the panel snapshot may be when the OMX reads the LEDs */
        const val MAX_AGE_MS = 8L
    }
}
