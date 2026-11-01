// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelsTest {
    @Test
    fun silenceAndSquare() {
        val silent = FloatArray(200)
        assertEquals(0.0, Levels.rms(silent), 0.0)
        assertEquals(-1, Levels.firstSound(silent))
        val square = FloatArray(200) { if ((it / 2) % 2 == 0) 0.5f else -0.5f }
        assertEquals(0.5, Levels.rms(square), 1e-9)
        assertEquals(0.5f, Levels.peak(square), 0f)
        val late = FloatArray(200).also { it[2 * 70 + 1] = 0.25f }
        assertEquals(70, Levels.firstSound(late))
    }
}
