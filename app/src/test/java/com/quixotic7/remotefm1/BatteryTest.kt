// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Tests/RFMKitTests/BatteryTests.swift (the policy and the idle watch; the
// slower screen's test is the engine's, E's); plus PickerFolder's memory.
package com.quixotic7.remotefm1

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryTest {
    @Test fun policyDecides() {
        var p = BackgroundPolicy(BackgroundMode.ALWAYS, idleStop = true)
        assertNull(p.stop(fm1Linked = false, idleForS = 60))
        assertNotNull("10 quiet minutes stop it in any mode", p.stop(fm1Linked = true, idleForS = 601))
        p = p.copy(idleStop = false)
        assertNull("keep playing, no idle stop: never", p.stop(fm1Linked = false, idleForS = 9999))
        p = p.copy(mode = BackgroundMode.WITH_FM1)
        assertEquals("no FM-1 connected", p.stop(fm1Linked = false, idleForS = 0))
        assertNull(p.stop(fm1Linked = true, idleForS = 0))
        p = p.copy(mode = BackgroundMode.NEVER)
        assertNotNull(p.stop(fm1Linked = true, idleForS = 0))
    }

    @Test fun policyNamesAsOnIos() {
        assertEquals(listOf("always", "withFM1", "never"), BackgroundMode.entries.map { it.raw })
        assertEquals(BackgroundMode.WITH_FM1, BackgroundMode.of("withFM1"))
        assertNull(BackgroundMode.of("sometimes"))
        assertEquals(listOf("Keep playing", "Only with an FM-1 connected", "Stop playing"), BackgroundMode.entries.map { it.title })
    }

    @Test fun idleWatch() {
        val t0 = 1_000_000L
        val w = IdleWatch(t0)
        var a = Activity(devMs = 0, lastSoundMs = 0, inputs = 5)
        w.feed(a, t0)
        w.feed(a, t0 + 300_000)
        assertEquals("nothing new: quiet goes on", 300_000L, w.idleForMs(t0 + 300_000))
        a = a.copy(inputs = 6)                                  // (a key)
        w.feed(a, t0 + 400_000)
        assertEquals(50_000L, w.idleForMs(t0 + 450_000))
        a = a.copy(lastSoundMs = 9000)                          // (it played a sound)
        w.feed(a, t0 + 500_000)
        assertEquals(0L, w.idleForMs(t0 + 500_000))
    }

    @Test fun activityFromTheEngineStatus() {
        val st = JSONObject().put("dev_ms", 1234).put("last_sound_ms", 1200).put("keys_pressed", 2).put("events_applied", 3)
            .put("midi_in", 4).put("midi_to_core", 5)
        assertEquals(Activity(1234, 1200, 14), Activity.of(st))
        assertEquals("an engine without last_sound_ms yet", Activity(0, 0, 0), Activity.of(JSONObject()))
    }

    @Test fun pickerFolderRemembersTheLastPick() {
        val m = HashMap<String, String>()
        val store = object : PickerFolder.Store {
            override fun get(key: String) = m[key]
            override fun set(key: String, value: String) { m[key] = value }
        }
        val cart = PickerFolder(PickerFolder.CART, store)
        val fw = PickerFolder(PickerFolder.FIRMWARE, store)
        assertNull(cart.last)
        cart.remember("content://com.android.externalstorage.documents/document/primary%3ASyx%2Fbank.syx")
        assertEquals("content://com.android.externalstorage.documents/document/primary%3ASyx%2Fbank.syx", cart.last)
        assertNull("each kind its own", fw.last)
        cart.remember("file:///sdcard/x.syx")
        assertEquals("only documents", "content://com.android.externalstorage.documents/document/primary%3ASyx%2Fbank.syx", cart.last)
    }
}
