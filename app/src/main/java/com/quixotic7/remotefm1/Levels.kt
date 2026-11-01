// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import kotlin.math.abs
import kotlin.math.sqrt

/** Levels of interleaved stereo float audio (full scale 1.0). */
object Levels {
    /** RMS over frames [from] until [to] of L R interleaved [s], both channels. */
    fun rms(s: FloatArray, from: Int = 0, to: Int = s.size / 2): Double {
        var sum = 0.0
        var n = 0
        for (f in from until to) {
            val l = s[2 * f].toDouble()
            val r = s[2 * f + 1].toDouble()
            sum += l * l + r * r
            n += 2
        }
        return if (n > 0) sqrt(sum / n) else 0.0
    }

    fun peak(s: FloatArray, from: Int = 0, to: Int = s.size / 2): Float {
        var p = 0f
        for (i in 2 * from until 2 * to) p = maxOf(p, abs(s[i]))
        return p
    }

    /** The first frame from [from] on whose level is above [threshold], -1 none. */
    fun firstSound(s: FloatArray, from: Int = 0, threshold: Float = 0.001f): Int {
        for (f in from until s.size / 2) if (abs(s[2 * f]) > threshold || abs(s[2 * f + 1]) > threshold) return f
        return -1
    }
}
