// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/OmxTests.swift (OmxScreenTests)
// The OMX-27's 128 x 32 screen drawn with Android's Canvas and the app's Chakra Petch Bold: the view's marks, the title
// above, the line below, a long title shrunk to fit left of the marks; packed and unpacked the same.
package com.quixotic7.remotefm1.omx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OmxScreenTest {
    private val screen = OmxScreen.of(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun screen() {
        val v = OmxViews()
        val s = v.screen()
        assertEquals(OmxViews.Screen("FM-1", "R1  SEL  AUX PLAY", OmxView.BASE), s)
        val bits = screen.bits(s)
        assertTrue("the screen shows something", bits.sumOf { it.toInt() } > 100)
        fun on(x: Int, y: Int) = bits[y * 128 + x].toInt() != 0
        assertTrue("view 0: a block", on(104, 2) && on(107, 7))
        assertTrue("the others: a line", on(110, 7) && !on(110, 2) && !on(110, 6))
        // the title in the top part, the line in the bottom one, both from x 1
        val rows = (0 until 32).map { y -> (0 until 100).any { on(it, y) } }
        assertTrue(rows.subList(4, 18).contains(true)); assertTrue(rows.subList(22, 31).contains(true))
        assertFalse("nothing below the line's baseline but descenders", (0 until 128).any { on(it, 31) })
        assertArrayEquals(bits, OmxWire.unpack(screen.render(s)))
        // a long title shrinks to fit
        val long = screen.bits(OmxViews.Screen("QUICK KEYS", "KN4  AUX PLAY", OmxView.QUICK))
        fun lit(x: Int, y: Int) = long[y * 128 + x].toInt() != 0
        assertFalse("the title stays left of the marks", (0 until 18).any { y -> (102 until 104).any { lit(it, y) } })
        assertFalse((8 until 18).any { y -> (104 until 128).any { lit(it, y) } })
        assertTrue("and still reaches across", (0 until 18).any { y -> (60 until 101).any { lit(it, y) } })
    }
}
