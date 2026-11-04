// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Cart/CartTransport+App.swift (CartSender.shared), updated to
// d629aa0 (dc771c8: RemoteFM1 in the app beside the FM-1s, CombinedCartTransport; bd0711a: CartKeep's folder);
// changes: Kotlin. The app's cartridge sender, made on first use: with the device backend Android MIDI
// (AndroidCartTransport); with the fake backend or a demo a FakeCartTransport with a pretend 0.3.1 FM-1 (FakeFM1; the
// demo with 4 banks), which sends nothing anywhere. With [core] given, RemoteFM1 running in the app is a target too.
package com.quixotic7.remotefm1.cart

import android.content.Context
import java.io.File

object AppCartSender {
    @Volatile private var made: CartSender? = null
    /** RemoteFM1 in the app as a target (made with the sender when the app gives its core) */
    @Volatile var coreTransport: CoreCartTransport? = null
        private set

    fun shared(context: Context, panel: CartPanelPausing, device: Boolean, demo: Boolean = false,
               core: (() -> CoreMidi?)? = null): CartSender =
        synchronized(this) {
            made?.let { return it }
            if (CartKeep.folder == null)
                CartKeep.folder = File(context.getExternalFilesDir(null) ?: context.filesDir, "Cartridges")
            val inApp = core?.let { CoreCartTransport(it) }
            coreTransport = inApp
            fun wrap(t: CartTransport): CartTransport = inApp?.let { CombinedCartTransport(it, t) } ?: t
            val s = if (device && !demo) {
                CartSender(wrap(AndroidCartTransport(context)), panel, simulated = false)
            } else {
                val banks = if (!demo) emptyList() else listOf("BRASS 1", "E.PIANO 2", "STRINGS", "BELLS").mapIndexed { i, n ->
                    UserBank(listOf(0, 1, 4, 6)[i], (i + 1).toLong(), 0, n)
                }
                CartSender(wrap(FakeCartTransport(fm1 = FakeFM1(banks = banks))), panel, simulated = true)
            }
            made = s
            s
        }

    fun peek(): CartSender? = made
}
