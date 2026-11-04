// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// (G, RemoteFM1IOS dfbc827 + e527b74) the battery in the app: with "Stop playing" the firmware stops when the app goes
// to the background (saved, its clock and the sound off) and runs again on return; the notification's Pause stops it
// and Play runs it again; Settings shows "When you leave the app" with its three choices.
package com.quixotic7.remotefm1

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quixotic7.remotefm1.ui.SheetPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val m get() = AppModel.peek()!!

    @org.junit.Before fun manualClock() { rule.mainClock.autoAdvance = false }

    private fun until(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            var r = false
            rule.runOnUiThread { r = ok() }
            if (r) return true
            rule.mainClock.advanceTimeBy(100)
            Thread.sleep(100)
        }
        return false
    }

    private fun clock(): Int { var c = -1; rule.runOnUiThread { c = m.engine.status().optInt("clock") }; return c }

    @Test fun stopWhenLeavingAndPauseInTheNotification() {
        rule.runOnUiThread {
            if (m.source != Source.TABLET) m.useSource(Source.TABLET)
            if (m.selected != "fizz") m.select("fizz")
            if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
        }
        assertTrue("FIZZ runs", until(15_000) { m.coreRunning })
        val saved = m.battery.policy
        try {
            // Settings shows the choices
            val a = (rule.activity as MainActivity).appUi
            rule.runOnUiThread { a.openSheet(SheetPageId.SETTINGS) }
            rule.mainClock.advanceTimeBy(800)
            for (t in listOf("bg-always", "bg-withFM1", "bg-never", "toggle-idlestop"))
                assertTrue(t, rule.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty())
            rule.runOnUiThread { a.closeSheet() }

            rule.runOnUiThread { m.battery.use(BackgroundPolicy(BackgroundMode.NEVER, idleStop = true)) }
            rule.runOnUiThread { m.battery.enteredBackground() }        // (what onStop does)
            assertTrue("stopped on leaving", until(3_000) { m.suspended })
            assertEquals("the clock off", RfmEngine.CLOCK_NONE, clock())
            assertTrue("the reason is shown", m.battery.lastStop.contains("leaving"))
            rule.runOnUiThread { m.battery.becameActive() }             // (what onStart does)
            assertTrue("running again", until(3_000) { !m.suspended && clock() != RfmEngine.CLOCK_NONE })

            // the notification's Pause and Play (keep playing: nothing stops it otherwise)
            rule.runOnUiThread { m.battery.use(BackgroundPolicy(BackgroundMode.ALWAYS, idleStop = true)) }
            val ctx = rule.activity.applicationContext
            ctx.startService(Intent(ctx, PlaybackService::class.java).setAction(PlaybackService.ACTION_PAUSE))
            assertTrue("Pause stops it", until(5_000) { m.suspended })
            ctx.startService(Intent(ctx, PlaybackService::class.java).setAction(PlaybackService.ACTION_PLAY))
            assertTrue("Play runs it again", until(5_000) { !m.suspended })
            assertFalse(m.suspended)
        } finally {
            rule.runOnUiThread { m.battery.use(saved); m.battery.becameActive() }
        }
    }
}
