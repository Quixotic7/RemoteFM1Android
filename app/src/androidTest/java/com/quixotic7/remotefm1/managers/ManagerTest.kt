// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A6's "done when" for the web managers (the emulator, network on): ChoralRoot runs, Firmware › Open its editor shows
// remotefm1.com's ChoralRoot manager in the WebView, the injected Web MIDI is asked for (the page's own shim stands
// aside), the page's SysEx reaches the core, its replies come back, and the device manager says it is connected and
// names the running firmware (as on iOS: the manager connected and read it). Its safety copy ("before manager write")
// is kept.
package com.quixotic7.remotefm1.managers

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quixotic7.remotefm1.AppModel
import com.quixotic7.remotefm1.MainActivity
import com.quixotic7.remotefm1.Mode
import com.quixotic7.remotefm1.Source
import com.quixotic7.remotefm1.ui.Overlay
import com.quixotic7.remotefm1.ui.SheetPageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ManagerTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val m get() = AppModel.peek()!!

    // (the faceplate draws every frame: the UI's clock is moved by hand)
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

    private fun page(js: String): String? {
        val b = ManagerBridge.current ?: return null
        var out: String? = null
        val l = CountDownLatch(1)
        rule.runOnUiThread { b.evaluate(js) { out = it; l.countDown() } }
        l.await(5, TimeUnit.SECONDS)
        return out
    }

    @Test fun choralRootsManagerReadsTheRunningFirmware() {
        rule.runOnUiThread {
            if (m.source != Source.TABLET) m.useSource(Source.TABLET)
            if (m.selected != "choralroot") m.select("choralroot")
            if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
        }
        assertTrue("ChoralRoot runs", until(15_000) { m.engineStatus.optInt("booted") != 0 })
        val copies0 = m.safety.safetyCopies("choralroot").size
        val a = (rule.activity as MainActivity).appUi
        rule.runOnUiThread { a.openSheet(SheetPageId.FIRMWARE) }
        rule.mainClock.advanceTimeBy(800)
        assertTrue("Firmware › Open its editor is offered", rule.onAllNodesWithTag("row-editor").fetchSemanticsNodes().isNotEmpty())
        rule.runOnUiThread { a.overlay = Overlay.MANAGER }          // (what the row does)
        assertTrue("the editor is open", until(5_000) { a.overlay == Overlay.MANAGER && ManagerBridge.current != null })
        val b = ManagerBridge.current!!
        assertTrue("the page asked for MIDI (the injected Web MIDI)", until(45_000) { b.midiAsked })
        assertEquals("the site's shim stands aside", "true", page("String(!!(window.__fm1shim && window.__fm1shim.app))")?.trim('"'))
        var state = ""
        var ok = false
        val end = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < end && !ok) {
            state = page("document.getElementById('conn').textContent + '|' + document.getElementById('fw').textContent") ?: ""
            ok = state.contains("connected|") && state.contains("ChoralRoot")
            if (!ok) Thread.sleep(500)
        }
        android.util.Log.i("RFMManagerTest", "the page says: $state; sent ${b.sent}, received ${b.received}")
        assertTrue("the manager connected and read the firmware: $state (sent ${b.sent}, received ${b.received})", ok)
        assertTrue("the page's SysEx reached the core", b.sent > 0)
        assertTrue("the core's replies came back", b.received > 0)
        assertTrue("a safety copy before manager write",
                   until(5_000) { m.safety.safetyCopies("choralroot").let { it.size > copies0 || it.any { s -> s.reason.contains("manager") } } })
        rule.runOnUiThread { a.overlay = null }                      // (Done)
        assertTrue(until(3_000) { a.overlay == null && ManagerBridge.current == null })
    }
}
