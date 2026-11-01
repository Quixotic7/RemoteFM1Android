// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/FirmwareFeeds.swift (FirmwareVersion).
package com.quixotic7.remotefm1.firmware

/**
 * Version order: dot parts as numbers ("1.6.1" < "1.6.10", "1.0" == "1.0.0"), a pre-release below its release
 * ("0.12.0-beta.12" < "0.12.0"), pre-release parts as semver compares them; a leading "v" and "+build" ignored.
 */
object FirmwareVersion {
    fun less(a: String, b: String): Boolean = compare(a, b) < 0

    /** -1, 0, 1 */
    fun compare(a: String, b: String): Int {
        val (ac, ap) = split(a)
        val (bc, bp) = split(b)
        val r = parts(ac, bc, pad = true)
        if (r != 0) return r
        return when {
            ap.isEmpty() && bp.isEmpty() -> 0
            ap.isEmpty() -> 1
            bp.isEmpty() -> -1
            else -> parts(ap, bp, pad = false)
        }
    }

    internal fun split(v: String): Pair<String, String> {
        var s = v.trim { it == ' ' || it == '\t' }
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        s.indexOf('+').let { if (it >= 0) s = s.substring(0, it) }
        val dash = s.indexOf('-')
        return if (dash < 0) s to "" else s.substring(0, dash) to s.substring(dash + 1)
    }

    /** pad: a missing part counts as 0 (the release part); else fewer parts sort first (semver's pre-release) */
    private fun parts(a: String, b: String, pad: Boolean): Int {
        val x = a.split('.')
        val y = b.split('.')
        for (i in 0 until maxOf(x.size, y.size)) {
            if (i >= x.size || i >= y.size) {
                if (!pad) return if (x.size < y.size) -1 else 1
                val r = one(if (i < x.size) x[i] else "0", if (i < y.size) y[i] else "0")
                if (r != 0) return r
                continue
            }
            val r = one(x[i], y[i])
            if (r != 0) return r
        }
        return 0
    }

    private fun num(s: String): Long? = if (s.isNotEmpty() && s.all { it.isDigit() || it == '+' || it == '-' }) s.toLongOrNull() else null

    private fun one(a: String, b: String): Int {
        val x = num(a)
        val y = num(b)
        return when {
            x != null && y != null -> x.compareTo(y).coerceIn(-1, 1)
            x != null -> -1                     // (a number below a word, as semver)
            y != null -> 1
            else -> natural(a, b)
        }
    }

    /** Foundation's .numeric compare: runs of digits as numbers, the rest by character */
    internal fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return if (na.length < nb.length) -1 else 1
                val c = na.compareTo(nb)
                if (c != 0) return c.coerceIn(-1, 1)
            } else {
                if (a[i] != b[j]) return if (a[i] < b[j]) -1 else 1
                i++
                j++
            }
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }
}
