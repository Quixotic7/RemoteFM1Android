// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/KeybedGeometry.swift
// The keybed's geometry (design/canvas/Keys.dc.html, README section 5.3): 27 keys F3..G5, 16 naturals in the lower
// row, 11 sharps in the upper row each centred on the gap between its two naturals, an LED slot in every key. Two
// layouts: Row (all keys across the width) and Scroll (naturals at a fixed width, a range strip of 16 mini cells
// above them). Shared by the drawing and the touch tracking, so a touch lands on the key that is drawn.
package com.quixotic7.remotefm1.ui.faceplate

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private fun rnd(v: Float) = Math.round(v).toFloat()

class KeybedGeometry(val size: PSize, val layout: Layout, stepMode: Boolean = false) {
    sealed class Layout {
        data object Row : Layout()
        /** a natural's width (the pitch is this plus the gap) */
        data class Scroll(val keyWidth: Float) : Layout()
    }

    data class Key(
        val index: Int,          // note key 0 (F3) .. 26 (G5): the panel's key number
        val natural: Boolean,
        val step: Int,           // naturals: 0..15 (the sequencer's step); sharps -1
        val rect: PRect,         // in the stage's content coordinates (scroll offset 0)
        val radius: Float,
        val ledW: Float,
        val ledH: Float,
    )

    val pad: Float
    val gap: Float
    val stripHeight: Float
    val stripGap: Float
    val stage: PRect
    val pitch: Float
    val visible: Float
    val maxOffset: Float
    val radius: Float
    val keys: List<Key>             // the 16 naturals then the 11 sharps

    init {
        val W = size.w
        val H = size.h
        pad = clamp(rnd(min(W, H) * 0.05f), 8f, 18f)
        gap = clamp(rnd(W * 0.008f), 3f, 10f)
        radius = rnd(min(W, H) * 0.08f)
        var ox = 0f
        var oy = 0f
        when (layout) {
            is Layout.Scroll -> {
                stripHeight = clamp(rnd(H * 0.11f), 22f, 34f)
                stripGap = max(8f, gap + 4)
                pitch = layout.keyWidth + gap
                val sy = pad + stripHeight + stripGap
                stage = PRect(pad, sy, W - 2 * pad, H - sy - pad)
                visible = (stage.w + gap) / pitch
                maxOffset = max(0f, 16 - visible)
            }
            Layout.Row -> {
                stripHeight = 0f
                stripGap = 0f
                pitch = (W - 2 * pad + gap) / 16
                stage = PRect(0f, 0f, W, H)
                visible = 16f
                maxOffset = 0f
                ox = pad
                oy = pad
            }
        }
        val kw = pitch - gap
        val rowH = if (layout == Layout.Row) H - 2 * pad else stage.h
        val vg = gap + 2
        val bh = rnd(rowH * 0.4f)
        val wh = rnd(rowH - bh - vg - 4)
        val bw = kw * 0.86f
        val lw = max(3f, rnd(kw * 0.08f))
        val list = ArrayList<Key>(27)
        NATURALS.forEachIndexed { i, semi ->
            list.add(Key(semi, true, i, PRect(ox + i * pitch, oy + bh + vg, kw, wh), rnd(min(kw * 0.42f, 18f)),
                         lw, rnd(wh * (if (stepMode) 0.34f else 0.42f))))
        }
        for (after in SHARP_AFTER) {
            val cx = ox + (after + 1) * pitch - gap / 2          // the centre of the gap after natural `after`
            list.add(Key(NATURALS[after] + 1, false, -1, PRect(cx - bw / 2, oy, bw, bh), rnd(min(bw * 0.42f, 18f)),
                         lw, rnd(bh * 0.42f)))
        }
        keys = list
    }

    /** the content's x shift for a scroll offset (in naturals) */
    fun shift(offset: Float): Float = -clamp(offset, 0f, maxOffset) * pitch

    /** the default scroll offset (four naturals in, around C4) */
    val defaultOffset: Float get() = min(maxOffset, 4f)

    /** the offset that centres natural [i] */
    fun offsetCentering(i: Int): Float = clamp(i - visible / 2 + 0.5f, 0f, maxOffset)

    private fun local(px: Float, py: Float, offset: Float): Pair<Float, Float> =
        if (layout is Layout.Scroll) Pair(px - stage.minX - shift(offset), py - stage.minY) else Pair(px, py)

    /** the key under a point in the keybed's coordinates (scroll offset applied); null: none (a gap, the strip) */
    fun keyAt(px: Float, py: Float, offset: Float = 0f): Key? {
        if (!stage.contains(px, py) && layout != Layout.Row) return null
        val (lx, ly) = local(px, py, offset)
        return keys.firstOrNull { it.rect.contains(lx, ly) }
    }

    /** the key nearest a point within [slop] (gaps count for the key beside them), for a sliding finger */
    fun keyNear(px: Float, py: Float, offset: Float = 0f, slop: Float): Key? {
        keyAt(px, py, offset)?.let { return it }
        val (lx, ly) = local(px, py, offset)
        return keys.firstOrNull { lx >= it.rect.minX - slop && lx <= it.rect.maxX + slop && ly >= it.rect.minY && ly <= it.rect.maxY }
    }

    /** the range strip's cells (scroll), in the keybed's coordinates */
    fun stripCell(i: Int): PRect {
        val w = (size.w - 2 * pad - 15 * 2) / 16
        return PRect(pad + i * (w + 2), pad, w, stripHeight)
    }

    /** the strip's frame around the naturals in view (scroll) */
    fun stripWindow(offset: Float): PRect {
        val mw = size.w - 2 * pad
        val o = clamp(offset, 0f, maxOffset)
        return PRect(pad + o / 16 * mw, pad - 3, min(visible, 16f) / 16 * mw, stripHeight + 6)
    }

    /** the natural (strip cell) under an x in the keybed's coordinates */
    fun stripIndex(x: Float): Int {
        val mw = size.w - 2 * pad
        return floor(clamp((x - pad) / mw * 16, 0f, 15.999f)).toInt()
    }

    companion object {
        /** semitones above F3 of the 16 naturals */
        val NATURALS = intArrayOf(0, 2, 4, 6, 7, 9, 11, 12, 14, 16, 18, 19, 21, 23, 24, 26)
        /** the naturals a sharp follows (its gap is between natural i and i + 1) */
        val SHARP_AFTER = intArrayOf(0, 1, 2, 4, 5, 7, 8, 9, 11, 12, 14)

        fun clamp(v: Float, a: Float, b: Float) = max(a, min(b, v))

        private val NOTE = arrayOf("F", "F#", "G", "G#", "A", "A#", "B", "C", "C#", "D", "D#", "E")

        /** "F3", "C#4": a key's name (F3 is key 0) */
        fun keyName(k: Int): String = NOTE[k % 12] + (3 + (k + 5) / 12)

        /** "F3", "C sharp 4": a key's name for TalkBack */
        fun spokenName(k: Int): String = keyName(k).replace("#", " sharp ")
    }
}
