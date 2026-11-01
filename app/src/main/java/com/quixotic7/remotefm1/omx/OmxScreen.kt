// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/OMX27/OmxScreen.swift
// The OMX-27's 128 x 32 screen, drawn (the website's omx-link.js render): the view's name large, its context small,
// four marks for K1's views; 1-bit at 50%, packed for the OMX. Chakra Petch Bold (the app's font), the title from
// 19 px down until it fits 100 px, the line from 11 px until it fits 126 px. Android's Canvas (the emulator tests draw it).
package com.quixotic7.remotefm1.omx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.quixotic7.remotefm1.R

class OmxScreen(private val face: Typeface) {
    private val bmp = Bitmap.createBitmap(OmxWire.W, OmxWire.H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private val fill = Paint().apply { color = Color.WHITE; isAntiAlias = false }
    private val text = Paint().apply { color = Color.WHITE; isAntiAlias = true; typeface = face }
    private val px = IntArray(OmxWire.W * OmxWire.H)

    /** the screen as 128 x 32 bits (bits[y * 128 + x], 1 lit, y down) */
    @Synchronized
    fun bits(s: OmxViews.Screen): ByteArray {
        canvas.drawColor(Color.BLACK)
        for (i in 0 until 4) {
            if (i == s.view.ordinal) rect(104 + i * 6, 2, 4, 6) else rect(104 + i * 6, 7, 4, 1)
        }
        text(s.title, 19, 10, 100f, 1f, 17f)
        text(s.line, 11, 7, 126f, 1f, 30f)
        bmp.getPixels(px, 0, OmxWire.W, 0, 0, OmxWire.W, OmxWire.H)
        return ByteArray(px.size) { if (Color.red(px[it]) >= 128) 1 else 0 }
    }

    /** the screen packed for the OMX (512 bytes) */
    fun render(s: OmxViews.Screen): ByteArray = OmxWire.pack(bits(s))

    private fun rect(x: Int, y: Int, w: Int, h: Int) = canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fill)

    /** (the canvas's loop: a size, then smaller while too wide, the last one tried kept) */
    private fun text(s: String, from: Int, min: Int, fits: Float, x: Float, baseline: Float) {
        var size = from
        do {
            text.textSize = size.toFloat()
            size--
        } while (text.measureText(s) > fits && size > min)
        canvas.drawText(s, x, baseline, text)
    }

    companion object {
        fun of(ctx: Context): OmxScreen =
            OmxScreen(ResourcesCompat.getFont(ctx, R.font.chakrapetch_bold) ?: Typeface.DEFAULT_BOLD)
    }
}
