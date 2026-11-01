// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Cart/CartTransport+App.swift (CartSender.shared); changes:
// Kotlin. The app's cartridge sender, made on first use: with the device backend Android MIDI (AndroidCartTransport);
// with the fake backend or a demo a FakeCartTransport with a pretend 0.3.1 FM-1 (FakeFM1; the demo with 4 banks),
// which sends nothing anywhere.
package com.quixotic7.remotefm1.cart

import android.content.Context

object AppCartSender {
    @Volatile private var made: CartSender? = null

    fun shared(context: Context, panel: CartPanelPausing, device: Boolean, demo: Boolean = false): CartSender =
        synchronized(this) {
            made?.let { return it }
            val s = if (device && !demo) {
                CartSender(AndroidCartTransport(context), panel, simulated = false)
            } else {
                val banks = if (!demo) emptyList() else listOf("BRASS 1", "E.PIANO 2", "STRINGS", "BELLS").mapIndexed { i, n ->
                    UserBank(listOf(0, 1, 4, 6)[i], (i + 1).toLong(), 0, n)
                }
                CartSender(FakeCartTransport(fm1 = FakeFM1(banks = banks)), panel, simulated = true)
            }
            made = s
            s
        }

    fun peek(): CartSender? = made
}
