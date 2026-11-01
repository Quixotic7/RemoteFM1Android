// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The route rules (iOS: AudioRoute.action, name, hint) and the default core (iOS CoreTests.testDefaultCoreFallsBack).
package com.quixotic7.remotefm1

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRouteTest {
    private val speaker = AudioOut(2, "SM-X210", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val fm1 = AudioOut(31, "FM-1", AudioDeviceInfo.TYPE_USB_DEVICE)
    private val buds = AudioOut(40, "Galaxy Buds", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    @Test fun actions() {
        val present = listOf(2, 31)
        assertEquals(AudioRoute.Action.NONE, AudioRoute.action(false, false, false, 0, present))
        assertEquals(AudioRoute.Action.RENAME, AudioRoute.action(true, true, false, 0, present))
        assertEquals(AudioRoute.Action.RENAME, AudioRoute.action(true, true, false, 31, present))
        assertEquals(AudioRoute.Action.RESTART, AudioRoute.action(true, true, true, 0, present))       // (disconnected)
        assertEquals(AudioRoute.Action.RESTART, AudioRoute.action(true, false, false, 0, present))     // (never ran)
        assertEquals(AudioRoute.Action.RESTART, AudioRoute.action(true, true, false, 40, present))     // (the picked one gone)
    }

    @Test fun device() {
        assertEquals(31, AudioRoute.device(31, listOf(2, 31)))
        assertEquals(0, AudioRoute.device(40, listOf(2, 31)))
        assertEquals(0, AudioRoute.device(0, listOf(2, 31)))
    }

    @Test fun namesAndHint() {
        val outs = listOf(speaker, fm1, buds)
        assertEquals("Speaker", AudioRoute.name(outs, 2))
        assertEquals("FM-1 (USB)", AudioRoute.name(outs, 31))
        assertEquals("Galaxy Buds (Bluetooth)", AudioRoute.name(outs, 40))
        assertEquals("System default", AudioRoute.name(outs, 0))
        assertEquals("device 9", AudioRoute.name(outs, 9))
        assertNull(AudioRoute.hint(outs, 2))
        assertEquals("The sound goes out of the FM-1's USB audio. Pick Audio output for the tablet's speaker or headphones.",
                     AudioRoute.hint(outs, 31))
    }

    @Test fun defaultCoreFallsBack() {
        val all = listOf("boardwalk", "choralroot", "felucca", "fizz", "loom", "saadet")
        assertEquals("felucca", AppModel.pick("felucca", all))
        assertEquals("choralroot", AppModel.pick(null, all))
        assertEquals("choralroot", AppModel.pick("nosuchcore", all))
        assertEquals("fizz", AppModel.pick("choralroot", listOf("fizz")))
        assertNull(AppModel.pick("choralroot", emptyList()))
    }
}
