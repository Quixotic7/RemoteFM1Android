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

    private val phones = AudioOut(7, "Wired", AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
    private val R = AudioRoute

    /** the FM-1 on USB is never chosen unless picked; the pick is a kind; gone kinds fall back, USB never by itself */
    @Test fun choose() {
        val withFm1 = listOf(fm1, speaker)                                 // (Android lists the FM-1 first)
        assertEquals(speaker, R.choose(withFm1, R.AUTO))
        assertEquals(speaker, R.choose(withFm1, R.SPEAKER))
        assertEquals(fm1, R.choose(withFm1, R.USB))
        assertEquals(speaker, R.choose(withFm1, R.BLUETOOTH))              // (no Bluetooth now: not USB either)
        assertEquals(buds, R.choose(listOf(fm1, speaker, buds), R.AUTO))
        assertEquals(phones, R.choose(listOf(fm1, speaker, buds, phones), R.AUTO))
        assertEquals(speaker, R.choose(listOf(fm1, speaker, buds, phones), R.SPEAKER))
        assertEquals(speaker, R.choose(listOf(speaker), R.USB))            // (the picked FM-1 gone: the speaker)
        assertNull(R.choose(listOf(fm1), R.AUTO))                          // (only USB, not picked: no sound)
        assertEquals(fm1, R.choose(listOf(fm1), R.USB))
    }

    @Test fun forbidden() {
        val outs = listOf(fm1, speaker, buds)
        assertEquals(listOf(31), R.forbidden(outs, speaker).toList())
        assertEquals(emptyList<Int>(), R.forbidden(outs, fm1).toList())
        assertEquals(listOf(31), R.forbidden(outs, null).toList())
    }

    @Test fun savedNeverUsb() {
        assertEquals(R.AUTO, R.saved(R.USB))
        assertEquals(R.SPEAKER, R.saved(R.SPEAKER))
        assertEquals(R.BLUETOOTH, R.saved(R.BLUETOOTH))
    }

    @Test fun actions() {
        val both = listOf(fm1, speaker)
        assertEquals(AudioRoute.Action.NONE, R.action(false, false, false, 0, both, speaker))
        assertEquals(AudioRoute.Action.RESTART, R.action(true, false, false, 0, both, speaker))     // (never ran)
        assertEquals(AudioRoute.Action.RESTART, R.action(true, true, true, 2, both, speaker))       // (disconnected)
        // the FM-1 plugged in while the speaker plays: stays on the speaker
        assertEquals(AudioRoute.Action.RENAME, R.action(true, true, false, 2, both, R.choose(both, R.AUTO)))
        // the FM-1 plugged in while the speaker plays with USB picked: still no jump to it by itself
        assertEquals(AudioRoute.Action.RENAME, R.action(true, true, false, 2, both, R.choose(both, R.USB)))
        // playing on the FM-1 (picked), it goes: a new stream (on the speaker: choose)
        assertEquals(AudioRoute.Action.RESTART, R.action(true, true, false, 31, listOf(speaker), R.choose(listOf(speaker), R.USB)))
        // playing on the FM-1, another output comes: stays
        assertEquals(AudioRoute.Action.RENAME, R.action(true, true, false, 31, listOf(fm1, speaker, buds), R.choose(listOf(fm1, speaker, buds), R.USB)))
        // headphones plugged in with Automatic: moves there (not USB)
        val p = listOf(fm1, speaker, phones)
        assertEquals(AudioRoute.Action.RESTART, R.action(true, true, false, 2, p, R.choose(p, R.AUTO)))
        assertEquals(AudioRoute.Action.RENAME, R.action(true, true, false, 2, p, R.choose(p, R.SPEAKER)))
    }

    @Test fun pickerRows() {
        val outs = listOf(fm1, speaker, buds)
        assertEquals(listOf(R.AUTO, R.SPEAKER, R.BLUETOOTH, R.USB), R.kinds(outs))
        assertEquals(listOf(R.AUTO, R.SPEAKER), R.kinds(listOf(speaker)))
        assertEquals("Automatic", R.kindTitle(outs, R.AUTO))
        assertEquals("FM-1 (USB)", R.kindTitle(outs, R.USB))
        assertEquals("Galaxy Buds (Bluetooth)", R.kindTitle(outs, R.BLUETOOTH))
    }

    @Test fun namesAndHint() {
        val outs = listOf(speaker, fm1, buds)
        assertEquals("Speaker", R.name(outs, 2))
        assertEquals("FM-1 (USB)", R.name(outs, 31))
        assertEquals("Galaxy Buds (Bluetooth)", R.name(outs, 40))
        assertEquals("nothing", R.name(outs, 0))
        assertEquals("device 9", R.name(outs, 9))
        assertNull(R.hint(outs, 2))
        assertEquals("The sound goes out of the FM-1's USB audio. ${R.USB_WARNING}", R.hint(outs, 31))
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
