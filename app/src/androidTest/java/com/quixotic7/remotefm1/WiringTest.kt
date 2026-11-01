// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A3's wiring of A4 and A5 in the running app (the emulator, network on): an .rfm1 from the live catalog installed,
// selected and played through the faceplate's keys; a state saved and loaded through the sheet's model; a backup zip
// written to the cache (no folder picked).
package com.quixotic7.remotefm1

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WiringTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun tick() = rule.mainClock.advanceTimeBy(400)
    private val m get() = AppModel.peek()!!

    @Before fun manualClock() { rule.mainClock.autoAdvance = false }

    /** waits (real time) for [ok], advancing the UI's clock */
    private fun until(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            var r = false
            rule.runOnUiThread { r = ok() }
            if (r) return true
            tick()
            Thread.sleep(150)
        }
        return false
    }

    private fun core() = rule.runOnUiThread {
        if (m.source != Source.TABLET) m.useSource(Source.TABLET)
        if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
    }

    @Test fun anRfm1FromTheCatalogPlaysThroughTheFaceplate() {
        core()
        val store = m.fw.store
        store.refresh()                                             // (the live list: network)
        val e = store.available.value.firstOrNull { it.id == "melodee" } ?: store.list(m.fw.catalog.value.filter { !it.imported }).first()
        val f = store.install(e)
        assertNotNull("installed ${e.id}: ${store.failure.value}", f)
        rule.runOnUiThread {
            m.refreshCatalog()
            m.select(f!!.key)
        }
        assertTrue("booted ${f!!.key}", until(20_000) { m.engineStatus.optInt("booted") != 0 && m.engineStatus.optString("kind") == "wasm" })
        rule.onNodeWithTag("dot-full").performClick()
        tick()
        val before = m.engine.status().optInt("keys_pressed")
        rule.onNodeWithTag("keybed").performTouchInput { down(Offset(width * 0.5f, height * 0.9f)) }
        assertTrue("the key reached ${f.name}", until(5_000) { m.engine.status().optInt("keys_pressed") > before })
        rule.onNodeWithTag("keybed").performTouchInput { up() }
        tick()
        rule.runOnUiThread { m.select("choralroot") }
    }

    @Test fun aStateSavesAndLoads() {
        core()
        rule.runOnUiThread { m.select("choralroot") }
        assertTrue(until(10_000) { m.engineStatus.optInt("booted") != 0 })
        val data = rule.activity.appUi.data
        val name = "A3 test ${System.currentTimeMillis()}"
        rule.runOnUiThread { data.saveState(name) }
        assertTrue("saved: ${m.message}", until(10_000) { data.states.any { it.name == name } })
        val s = data.states.first { it.name == name }
        rule.runOnUiThread { data.loadState(s) }
        assertTrue("loaded: ${m.message}", m.message.startsWith("Loaded \"$name\""))
        assertTrue(until(10_000) { m.engineStatus.optInt("booted") != 0 })
        assertTrue("a safety copy before the load: ${data.safetyCopies.take(4).map { it.reason + " " + it.date }}", until(5_000) {
            // (FlashHistory keeps one copy of the same memory: when the newest is already an event copy of it, e.g.
            // A6's "before manager write", that one stands for the load's)
            data.safetyCopies.firstOrNull()?.reason.let { it == "before load state" || it == "before manager write" }
        })
        rule.runOnUiThread { data.deleteState(s) }
        assertTrue(until(5_000) { data.states.none { it.name == name } })
    }

    @Test fun aBackupZipIsWritten() {
        core()
        val dir = File(rule.activity.cacheDir, "share")
        val before = dir.listFiles()?.size ?: 0
        val data = rule.activity.appUi.data
        rule.runOnUiThread { data.backUp() }
        assertTrue("backed up: ${data.backupNote}", until(20_000) { !data.backingUp && data.backupNote.startsWith("Backed up") })
        val zips = dir.listFiles { f -> f.name.endsWith(".zip") }.orEmpty()
        assertTrue(zips.size > 0 && (dir.listFiles()?.size ?: 0) >= before)
        assertNotNull(data.shareBackup)
        assertEquals("PK", String(zips.maxBy { it.lastModified() }.readBytes().copyOf(2)))
    }
}
