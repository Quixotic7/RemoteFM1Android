// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/FaceplateLayout.swift
// The faceplate's layouts (design/README.md section 4): the canvas's frames, in points (dp here) at each reference
// size, and the mapping from a reference size to the real one. Positions follow the view's own width and height;
// square parts (knobs, the display) scale by the smaller of the two, so nothing stretches. When the aspect differs a
// lot from the reference, the layout scales uniformly and is centred instead. Pure Kotlin: tested on the JVM.
package com.quixotic7.remotefm1.ui.faceplate

import kotlin.math.max
import kotlin.math.min

/** a rectangle in dp (the layouts' and the keybed's geometry; Compose-free so the JVM tests use it) */
data class PRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val minX get() = x
    val minY get() = y
    val maxX get() = x + w
    val maxY get() = y + h
    val midX get() = x + w / 2
    val midY get() = y + h / 2
    fun contains(px: Float, py: Float) = px >= x && px <= maxX && py >= y && py <= maxY
    fun inset(dx: Float, dy: Float) = PRect(x + dx, y + dy, w - 2 * dx, h - 2 * dy)
}

data class PSize(val w: Float, val h: Float)

object FaceplateLayout {
    enum class Kind(val ref: PSize) {
        PHONE_PORTRAIT(PSize(390f, 844f)),
        PHONE_LANDSCAPE(PSize(844f, 390f)),
        PAD_LANDSCAPE(PSize(1194f, 834f)),
        PAD_PORTRAIT(PSize(834f, 1194f));

        val landscape get() = this == PHONE_LANDSCAPE || this == PAD_LANDSCAPE

        companion object {
            /** portrait under 600 dp wide is the phone's, landscape under 500 dp high the phone's, else the tablet's */
            fun pick(w: Float, h: Float): Kind =
                if (h >= w) (if (w < 600) PHONE_PORTRAIT else PAD_PORTRAIT) else (if (h < 500) PHONE_LANDSCAPE else PAD_LANDSCAPE)
        }
    }

    /** reference dp -> view dp */
    class Mapper(val ref: PSize, val size: PSize, uniform: Boolean = false) {
        val sx: Float
        val sy: Float
        val ox: Float
        val oy: Float
        val s: Float

        init {
            val ax = size.w / ref.w
            val ay = size.h / ref.h
            val m = min(ax, ay)
            if (uniform || max(ax, ay) / max(m, 0.0001f) > 1.12f) {
                sx = m; sy = m
                ox = (size.w - ref.w * m) / 2
                oy = (size.h - ref.h * m) / 2
            } else {
                sx = ax; sy = ay; ox = 0f; oy = 0f
            }
            s = min(sx, sy)
        }

        fun x(v: Float) = ox + v * sx
        fun y(v: Float) = oy + v * sy
        fun len(v: Float) = v * s
        fun rect(x: Float, y: Float, w: Float, h: Float) = PRect(x(x), y(y), w * sx, h * sy)
        fun rect(r: PRect) = rect(r.x, r.y, r.w, r.h)
    }

    /** the status row: chip left, grabber centre, wordmark right */
    data class Status(val y: Float, val margin: Float, val chipHeight: Float, val chipPad: Float, val chipFont: Float,
                      val grabberWidth: Float, val barWidth: Float, val wordFont: Float, val wordTracking: Float)

    /** knobs in a 2 x 2 grid (or a row) of cells `width / columns` wide */
    data class Cluster(val x: Float, val y: Float, val width: Float, val knob: Float, val rowGap: Float)

    /** the OCT- / OCT+ well */
    data class Well(val x: Float, val y: Float, val width: Float, val height: Float, val pad: Float, val gap: Float,
                    val radius: Float, val buttonRadius: Float, val font: Float)

    data class Buttons(val x: Float, val y: Float, val bw: Float, val bh: Float, val gap: Float) {
        val size get() = PSize(6 * bw + 5 * gap + 20, 2 * bh + gap + 20)
    }

    sealed class KnRow {
        /** the LED's slot then KN1-4 across [width] */
        data class Row(val x: Float, val y: Float, val width: Float, val knob: Float, val led: Float) : KnRow()
        /** 2 x 2, the LED below */
        data class Grid(val c: Cluster, val ledX: Float, val ledY: Float, val led: Float) : KnRow()
    }

    /** the Full view (Main, PhoneWide, PadWide, PadTall) */
    data class Full(val kind: Kind, val status: Status?, val grabber: PRect?, val main: Cluster, val oct: Well,
                    val displayX: Float, val displayY: Float, val displaySize: Float, val kn: KnRow, val buttons: Buttons,
                    val keybed: PRect, val keyWidth: Float?, val pageDots: Boolean)

    val phoneStatus = Status(50f, 16f, 28f, 11f, 12f, 88f, 40f, 12f, 0.14f)
    val padStatus = Status(26f, 48f, 30f, 12f, 13f, 96f, 48f, 13f, 0.16f)

    fun full(k: Kind): Full = when (k) {
        Kind.PHONE_PORTRAIT -> Full(k, phoneStatus, null,
            main = Cluster(16f, 96f, 148f, 46f, 8f),
            oct = Well(16f, 246f, 148f, 52f, 5f, 6f, 14f, 10f, 13f),
            displayX = 172f, displayY = 96f, displaySize = 202f,
            kn = KnRow.Row(16f, 316f, 358f, 46f, 10f),
            buttons = Buttons(21f, 400f, 48f, 42f, 8f),
            keybed = PRect(16f, 530f, 358f, 272f), keyWidth = 40f, pageDots = true)
        Kind.PHONE_LANDSCAPE -> Full(k, null, PRect(378f, 0f, 88f, 20f),
            main = Cluster(54f, 20f, 132f, 38f, 2f),
            oct = Well(54f, 144f, 132f, 48f, 3f, 4f, 12f, 9f, 12f),
            displayX = 198f, displayY = 20f, displaySize = 172f,
            kn = KnRow.Row(386f, 20f, 408f, 38f, 9f),
            buttons = Buttons(386f, 88f, 58f, 38f, 8f),
            keybed = PRect(50f, 206f, 744f, 170f), keyWidth = null, pageDots = false)
        Kind.PAD_LANDSCAPE -> Full(k, padStatus.copy(margin = 48f), null,
            main = Cluster(48f, 88f, 192f, 64f, 24f),
            oct = Well(48f, 334f, 192f, 54f, 5f, 6f, 15f, 11f, 15f),
            displayX = 272f, displayY = 88f, displaySize = 300f,
            kn = KnRow.Row(604f, 88f, 542f, 64f, 12f),
            buttons = Buttons(606f, 234f, 78f, 60f, 10f),
            keybed = PRect(48f, 424f, 1098f, 362f), keyWidth = null, pageDots = true)
        Kind.PAD_PORTRAIT -> Full(k, padStatus.copy(margin = 40f), null,
            main = Cluster(40f, 88f, 224f, 84f, 8f),
            oct = Well(40f, 590f, 220f, 54f, 5f, 6f, 15f, 11f, 15f),
            displayX = 272f, displayY = 88f, displaySize = 290f,
            kn = KnRow.Grid(Cluster(570f, 88f, 224f, 84f, 8f), 677f, 344f, 10f),
            buttons = Buttons(77f, 404f, 100f, 64f, 12f),
            keybed = PRect(40f, 660f, 754f, 476f), keyWidth = null, pageDots = true)
    }

    /** the Controls view (PhonePanel, PhoneKeys): a big display, one knob row with its MAIN | KN 1-4 switch, the
     *  BUTTONS | KEYS switch and the deck; landscape puts the display left and the rest on the right */
    data class Controls(val ref: PSize, val status: Status?, val grabber: PRect?, val displayX: Float, val displayY: Float,
                        val displaySize: Float, val knobRow: PRect, val knob: Float, val setSwitch: PRect,
                        val deckSwitch: PRect, val deck: PRect, val buttons: Buttons, val oct: Well, val keybed: PRect,
                        val keyWidth: Float)

    fun controls(landscape: Boolean): Controls =
        if (!landscape) Controls(Kind.PHONE_PORTRAIT.ref, phoneStatus, null, 25f, 92f, 340f,
            knobRow = PRect(12f, 444f, 366f, 71f), knob = 50f,
            setSwitch = PRect(115f, 523f, 160f, 32f),
            deckSwitch = PRect(95f, 565f, 200f, 36f),
            deck = PRect(0f, 605f, 390f, 196f),
            buttons = Buttons(15f, 611f, 50f, 48f, 8f),
            oct = Well(95f, 747f, 200f, 48f, 4f, 6f, 14f, 10f, 13f),
            keybed = PRect(16f, 611f, 358f, 186f), keyWidth = 40f)
        else Controls(Kind.PHONE_LANDSCAPE.ref, null, PRect(378f, 0f, 88f, 20f), 40f, 32f, 330f,
            knobRow = PRect(404f, 30f, 410f, 67f), knob = 46f,
            setSwitch = PRect(529f, 98f, 160f, 30f),
            deckSwitch = PRect(509f, 132f, 200f, 34f),
            deck = PRect(396f, 170f, 448f, 210f),
            buttons = Buttons(399f, 176f, 61f, 46f, 8f),
            oct = Well(509f, 304f, 200f, 46f, 4f, 6f, 14f, 10f, 13f),
            keybed = PRect(404f, 176f, 420f, 186f), keyWidth = 40f)

    /** the knob's cell: label above, the knob, room for its shadow (Knob.dc.html: size + 28 wide) */
    fun knobCell(size: Float): PSize {
        val lf = max(9f, Math.round(size * 0.17f).toFloat())
        return PSize(size + 28, lf + 6 + size + 6)
    }
}
