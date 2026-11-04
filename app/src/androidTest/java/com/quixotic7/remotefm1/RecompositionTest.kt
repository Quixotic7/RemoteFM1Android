// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// (G, RemoteFM1IOS 391497e + 14c9b96, menus) the model's status is polled every 66 ms; the main screen and the
// Firmware page must not recompose with it. iOS counted 144 main-screen redraws in 12 s on the Firmware page, then 5.
// Here: 12 s with a firmware running and the Firmware page open, the clock advancing as the screen does.
package com.quixotic7.remotefm1

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quixotic7.remotefm1.ui.Recompositions
import com.quixotic7.remotefm1.ui.SheetPageId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecompositionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val m get() = AppModel.peek()!!

    @org.junit.Before fun manualClock() { rule.mainClock.autoAdvance = false }

    private fun run(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            rule.mainClock.advanceTimeBy(16)
            Thread.sleep(16)
        }
    }

    @Test fun firmwarePageIsQuietWhileTheStatusIsPolled() {
        rule.runOnUiThread {
            if (m.source != Source.TABLET) m.useSource(Source.TABLET)
            if (m.selected != "fizz") m.select("fizz")
            if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
        }
        run(4000)
        assertTrue("FIZZ runs", m.coreRunning)
        val a = (rule.activity as MainActivity).appUi
        rule.runOnUiThread { a.openSheet(SheetPageId.FIRMWARE) }
        run(2000)                                                   // (opened, settled)
        val main0 = Recompositions.main
        val page0 = Recompositions.firmwarePage
        run(12_000)
        val main = Recompositions.main - main0
        val page = Recompositions.firmwarePage - page0
        Log.i("RFMRecompose", "in 12 s on the Firmware page: main screen $main, Firmware page $page (before: $main0, $page0)")
        assertTrue("main screen recomposed $main times in 12 s", main <= 12)
        assertTrue("Firmware page recomposed $page times in 12 s", page <= 12)
    }
}
