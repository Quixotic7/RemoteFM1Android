// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Tests/RFMKitTests/FirmwareListingTests.swift (testHeadroomAndScreenOnDropouts)
package com.quixotic7.remotefm1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfGuardTest {
    private class S(var kind: String = "wasm", var booted: Boolean = true, var coreID: String = "rainbow",
                    var wakes: Long = 100, var avgUs: Long = 100, var frames: Long = 48000, var rate: Double = 48000.0,
                    var underruns: Long = 0)

    private fun PerfGuard.up(s: S, name: String, t: Long) =
        update(s.kind, s.booted, s.coreID, s.wakes, s.avgUs, s.frames, s.rate, s.underruns, name, t)

    @Test fun headroomAndScreenOnDropouts() {
        val g = PerfGuard()
        val s = S()
        var t = 0L
        g.up(s, "Rainbow Mode", t)
        assertEquals("a Wasm core starts with more headroom", PerfGuard.WASM_HEADROOM_US, g.headroomUs)
        assertEquals("and a 30 fps screen", 33, g.frameMs)
        val notes = mutableListOf<String>()
        val frames = mutableListOf<Int>()
        repeat(4) {                                             // 40 % load, 20 dropouts each window
            t += 2000
            s.wakes += 1000; s.frames += 96000; s.avgUs = 800; s.underruns += 20
            notes += g.up(s, "Rainbow Mode", t)
            frames += g.frameMs
        }
        assertEquals(listOf(50, 66, 66, 66), frames)
        assertEquals(PerfGuard.MAX_HEADROOM_US, g.headroomUs)
        assertEquals("more headroom and a slower screen first, no note", listOf("", ""), notes.take(2))
        assertTrue("still dropping out with nothing left: the note", notes.last().contains("too heavy"))

        s.coreID = "choralroot"                                 // another Wasm firmware: its own start
        g.up(s, "ChoralRoot", t + 2000)
        assertEquals(PerfGuard.WASM_HEADROOM_US, g.headroomUs)
        assertEquals(33, g.frameMs)
        s.coreID = "rainbow"                                    // back: what it needed, kept
        g.up(s, "Rainbow Mode", t + 4000)
        assertEquals(PerfGuard.MAX_HEADROOM_US, g.headroomUs)
        assertEquals(66, g.frameMs)
        s.kind = "native"; s.coreID = ""                        // a built-in core: the defaults
        g.up(s, "FIZZ", t + 6000)
        assertEquals(PerfGuard.DEFAULT_HEADROOM_US, g.headroomUs)
        assertEquals(PerfGuard.DEFAULT_FRAME_MS, g.frameMs)
    }

    @Test fun noteOnlyWhenHeavy() {
        assertEquals("", PerfGuard.note(0.5, 2, "X"))
        assertTrue(PerfGuard.note(0.95, 0, "X").startsWith("X is too heavy for this device (95% of the audio time)"))
        assertTrue(PerfGuard.note(0.4, 3, "X").contains("40% of the audio time, 3 dropouts"))
    }
}
