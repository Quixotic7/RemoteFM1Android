// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A6's "done when" for the web managers (the emulator, network on): ChoralRoot runs, Firmware › Open its editor shows
// ChoralRoot's manager at its author's page (RemoteFM1IOS 95d5d16: never remotefm1.com's copy) in the WebView, the injected Web MIDI is asked for (the page's own shim stands
// aside), the page's SysEx reaches the core, its replies come back, and the device manager says it is connected and
// names the running firmware (as on iOS: the manager connected and read it). Its safety copy ("before manager write")
// is kept.
package com.quixotic7.remotefm1.managers

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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

    /** (not inside until: page() waits for the main thread) */
    private fun pageUntil(ms: Long, js: String, want: String): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (page(js)?.trim('"') == want) return true
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
        // (a new copy; or, when an earlier test left an event copy of these very bytes, that copy is kept as it is,
        // as on iOS: the newest copy is an event copy of the flash as saved)
        val flash = java.io.File(rule.activity.filesDir, "cores/choralroot.bin")
        assertTrue("a safety copy before manager write",
                   until(5_000) { m.safety.safetyCopies("choralroot").let { it.size > copies0 || it.any { s -> s.reason.contains("manager") } ||
                       it.firstOrNull()?.let { s -> s.kind == com.quixotic7.remotefm1.data.FlashHistory.Kind.EVENT &&
                           s.sha256 == com.quixotic7.remotefm1.FlashFile.sha256(flash.readBytes()) } == true } })
        // (G, iOS 14c9b96) the page's confirm() is a real dialog: OK answers true (it was a Cancel: "Place all" did nothing)
        page("setTimeout(() => { window.__c = confirm('Place all 128 sounds?'); }, 0); 1")
        assertTrue("the dialog shows", until(5_000) { b.lastDialog?.isShowing == true })
        rule.runOnUiThread { b.lastDialog!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() }
        assertTrue("confirm() answered OK", pageUntil(5_000, "String(window.__c)", "true"))
        page("setTimeout(() => { window.__p = prompt('Name?', 'BRASS'); }, 0); 1")
        assertTrue(until(5_000) { b.lastDialog?.isShowing == true && b.dialogs >= 2 })
        rule.runOnUiThread { b.lastDialog!!.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick() }
        assertTrue("prompt() cancelled: null", pageUntil(5_000, "String(window.__p)", "null"))
        // (a download made in the page: to the share sheet's file)
        page("(() => { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob(['hello'])); " +
             "a.download = 'test-backup.syx'; document.body.appendChild(a); a.click(); })(); 1")
        assertTrue("the page's blob download was saved", until(10_000) { b.lastDownload == "test-backup.syx" })
        rule.runOnUiThread { a.overlay = null }                      // (Done)
        assertTrue(until(3_000) { a.overlay == null && ManagerBridge.current == null })
    }

    /** (G, iOS 391497e) a swipe in from the right edge opens the running firmware's editor; the back gesture closes it */
    @Test fun rightEdgeSwipeOpensTheEditor() {
        rule.runOnUiThread {
            if (m.source != Source.TABLET) m.useSource(Source.TABLET)
            if (m.selected != "choralroot") m.select("choralroot")
            if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
        }
        assertTrue(until(15_000) { m.engineStatus.optInt("booted") != 0 })
        val a = (rule.activity as MainActivity).appUi
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("editor-edge").performTouchInput { swipeLeft(startX = right - 1f, endX = right - 300f) }
        assertTrue("the editor is open", until(5_000) { a.overlay == Overlay.MANAGER })
        rule.mainClock.advanceTimeBy(600)                           // (recomposed: the back handler on)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue("back closes it", until(3_000) { a.overlay == null })
    }
}
