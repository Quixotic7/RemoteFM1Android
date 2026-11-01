// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/ScreenProtector.swift (FilmGeometry)
// The screen protector's outline in the faceplate's coordinates: the display's rounded square and a strip out to the
// red tab, and the film folded back from the tab by a travel t (dp along the peel's direction). Pure Kotlin.
package com.quixotic7.remotefm1.ui.faceplate

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class FPoint(val x: Float, val y: Float)

class FilmGeometry(val display: PRect, val radius: Float, val tab: PRect) {
    /** the peel's direction: from the tab toward the display's centre */
    val dirX: Float
    val dirY: Float = 0f
    /** the tab's travel that folds the whole film over */
    val length: Float
    /** the film's pieces (convex polygons, one orientation) */
    val pieces: List<List<FPoint>>
    private val back: Float

    init {
        val cx = tab.midX
        val cy = tab.midY
        dirX = if (tab.midX >= display.midX) -1f else 1f
        val sq = ArrayList<FPoint>()
        val r = min(radius, min(display.w / 2, display.h / 2))
        val corners = listOf(Triple(display.maxX - r, display.maxY - r, 0.0), Triple(display.minX + r, display.maxY - r, 90.0),
                             Triple(display.minX + r, display.minY + r, 180.0), Triple(display.maxX - r, display.minY + r, 270.0))
        for ((x, y, a0) in corners) {
            for (i in 0..6) {
                val a = (a0 + i * 15) * Math.PI / 180
                sq.add(FPoint(x + r * cos(a).toFloat(), y + r * sin(a).toFloat()))
            }
        }
        val x0 = if (dirX < 0) display.maxX - r else display.minX + r
        val strip = listOf(FPoint(x0, tab.minY), FPoint(cx, tab.minY), FPoint(cx, tab.maxY), FPoint(x0, tab.maxY))
        pieces = listOf(oriented(sq), oriented(strip))
        val proj = pieces.flatten().map { (it.x - cx) * dirX + (it.y - cy) * dirY }
        val reach = proj.maxOrNull() ?: 0f
        back = min(0f, proj.minOrNull() ?: 0f)
        length = 2 * max(reach - back, 1f)
    }

    /** the fold (0..1) from the finger's movement */
    fun fold(dx: Float, dy: Float): Float = min(1f, max(0f, (dx * dirX + dy * dirY) / length))

    class Folded(val left: List<List<FPoint>>, val flap: List<List<FPoint>>, val tab: FPoint)

    /** the film folded by travel t: what's left on, the folded-back flap (mirrored across the fold line, halfway
     *  between the tab's place and the finger) and the tab's centre (carried with the finger) */
    fun folded(t: Float): Folded {
        val cx = tab.midX
        val cy = tab.midY
        val h = max(0f, t) / 2
        val along = { p: FPoint -> (p.x - cx) * dirX + (p.y - cy) * dirY - back - h }
        val left = pieces.map { clip(it) { p -> along(p) } }.filter { it.size > 2 }
        val flap = if (h <= 0) emptyList() else pieces.map { clip(it) { p -> -along(p) } }
            .filter { it.size > 2 && abs(area(it)) > 0.5f }
            .map { poly -> poly.map { p -> val d = along(p); FPoint(p.x - 2 * d * dirX, p.y - 2 * d * dirY) } }
        return Folded(left, flap, FPoint(cx + dirX * 2 * h, cy + dirY * 2 * h))
    }

    companion object {
        /** the tab's square beside a display, as on the FM-1: right of it, level with its top, overlapping a little */
        fun tab(display: PRect): PRect {
            val t = Math.round(display.w * 0.13f).toFloat()
            val o = Math.round(t * 0.15f).toFloat()
            return PRect(display.maxX - o, display.minY + o, t, t)
        }

        /** whether a let-go at this fold peels the film off */
        fun peels(fold: Float) = fold > 0.33f

        fun clip(poly: List<FPoint>, keep: (FPoint) -> Float): List<FPoint> {
            val out = ArrayList<FPoint>()
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                val fa = keep(a)
                val fb = keep(b)
                if (fa >= 0) out.add(a)
                if ((fa >= 0) != (fb >= 0)) {
                    val k = fa / (fa - fb)
                    out.add(FPoint(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k))
                }
            }
            return out
        }

        fun area(p: List<FPoint>): Float {
            var s = 0f
            for (i in p.indices) {
                val a = p[i]
                val b = p[(i + 1) % p.size]
                s += a.x * b.y - b.x * a.y
            }
            return s / 2
        }

        fun oriented(p: List<FPoint>) = if (area(p) < 0) p.reversed() else p
    }
}
