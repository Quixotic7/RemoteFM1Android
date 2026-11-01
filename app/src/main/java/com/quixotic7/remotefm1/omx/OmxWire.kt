// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/OMX27/OmxWire.swift
// The OMX-27's REMOTE mode, the wire: the website's js/omx27.js, ported as is (OMX-27 firmware q7-2026-3:
// SYSEX_SPEC.md "REMOTE mode", omx_mode_remote.cpp, tools/remote_test.py).
//   every message    F0 7D 00 00 <cmd> <payload> F7
//   host -> OMX      51 05 <MODE_REMOTE> enter REMOTE mode (the input-injection MODE command)
//                    59 idx r g b / 5A start count rgb... stage LEDs (7-bit), 5B latch them
//                    5C chunk enc7(32 bytes) stage a screen chunk (SSD1306 pages, 180 deg), 5D latch the frame
//   OMX -> host      51 00 key ev (0 up 1 down 2 hold 3 quick click, after the up), 51 01 dir amt (0 CCW 2 CW),
//                    51 02 z (encoder button), 51 03 pot v7 hi lo; 52 02 entered, 52 03 left, 52 04 frame ACK
// OmxSession keeps the OMX's LEDs and screen as the app wants them: only what changed, LEDs at most every 30 ms,
// one frame in flight (its ACK, or 250 ms), at most 15 frames a second.
package com.quixotic7.remotefm1.omx

object OmxWire {
    const val CMD_INPUT = 0x51
    const val CMD_STATUS = 0x52
    const val CMD_LED = 0x59
    const val CMD_LED_BATCH = 0x5A
    const val CMD_LED_SHOW = 0x5B
    const val CMD_DRAW = 0x5C
    const val CMD_DRAW_UPD = 0x5D
    const val STATUS_ACTIVITY = 0x01
    const val STATUS_ON = 0x02
    const val STATUS_OFF = 0x03
    const val STATUS_ACK = 0x04
    /** config.h OMXMode: MIDI DRUM CHORDS FORM S1 S2 GRIDS EUCLID OM REMOTE CONFIG */
    const val MODE_REMOTE = 9
    const val EV_UP = 0
    const val EV_DOWN = 1
    const val EV_HOLD = 2
    const val EV_QUICK = 3
    const val N_LED = 27
    const val W = 128
    const val H = 32
    const val FB_BYTES = 512
    const val CHUNK = 32
    const val N_CHUNK = 16
    const val LED_MS = 30.0
    const val FRAME_MS = 1000.0 / 15
    const val ACK_MS = 250.0
    const val CHUNK_GAP_MS = 2.0

    /** the port: its name says OMX */
    fun isOmxPortName(name: String?): Boolean = name?.contains("omx", ignoreCase = true) == true

    fun sysex(cmd: Int, data: List<Int> = emptyList()): ByteArray {
        val out = ByteArray(6 + data.size)
        out[0] = 0xF0.toByte(); out[1] = 0x7D; out[2] = 0; out[3] = 0; out[4] = cmd.toByte()
        for ((i, v) in data.withIndex()) out[5 + i] = (v and 0x7F).toByte()
        out[out.size - 1] = 0xF7.toByte()
        return out
    }

    fun enterRemote(): ByteArray = sysex(CMD_INPUT, listOf(0x05, MODE_REMOTE))

    /** 8-bit bytes -> 7-bit groups: a byte of high bits, then up to 7 low parts (remote_test.py _enc7) */
    fun enc7(d: ByteArray, from: Int = 0, to: Int = d.size): ByteArray {
        val n0 = to - from
        val out = ByteArray(n0 + (n0 + 6) / 7)
        var o = 0
        var i = from
        while (i < to) {
            val n = minOf(7, to - i)
            var hi = 0
            for (j in 0 until n) hi = hi or (((d[i + j].toInt() shr 7) and 1) shl j)
            out[o++] = hi.toByte()
            for (j in 0 until n) out[o++] = (d[i + j].toInt() and 0x7F).toByte()
            i += 7
        }
        return out
    }

    fun dec7(src: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len)
        var s = 0
        fun at(i: Int) = if (i < src.size) src[i].toInt() and 0xFF else 0
        var i = 0
        while (i < len) {
            val n = minOf(7, len - i)
            val hi = at(s++)
            for (j in 0 until n) out[i + j] = ((at(s++) and 0x7F) or (((hi shr j) and 1) shl 7)).toByte()
            i += 7
        }
        return out
    }

    /** a 128 x 32 image (bits[y * 128 + x], 1 lit) -> the SSD1306 buffer, 180 deg (remote_test.py _pack) */
    fun pack(bits: ByteArray): ByteArray {
        val fb = ByteArray(FB_BYTES)
        for (y in 0 until H) for (x in 0 until W) {
            if (bits[(H - 1 - y) * W + (W - 1 - x)].toInt() != 0) {
                val i = x + (y shr 3) * W
                fb[i] = (fb[i].toInt() or (1 shl (y and 7))).toByte()
            }
        }
        return fb
    }

    fun unpack(fb: ByteArray): ByteArray {
        val bits = ByteArray(W * H)
        for (y in 0 until H) for (x in 0 until W) {
            if (fb[x + (y shr 3) * W].toInt() and (1 shl (y and 7)) != 0) bits[(H - 1 - y) * W + (W - 1 - x)] = 1
        }
        return bits
    }

    /** a message from the OMX */
    sealed class Message {
        data class Key(val key: Int, val ev: Int) : Message()
        data class Enc(val delta: Int) : Message()
        data class EncBtn(val z: Int) : Message()
        data class Pot(val pot: Int, val v: Int, val hires: Int) : Message()
        data class Status(val s: Int) : Message()
    }

    /** a message from the OMX, decoded; anything else null */
    fun parse(d: ByteArray): Message? {
        if (d.size < 7 || d[0] != 0xF0.toByte() || d[1].toInt() != 0x7D || d[2].toInt() != 0 || d[3].toInt() != 0 ||
            d[d.size - 1] != 0xF7.toByte()) return null
        val cmd = d[4].toInt() and 0xFF
        val a = (5 until d.size - 1).map { d[it].toInt() and 0xFF }
        if (cmd == CMD_STATUS) return Message.Status(a[0])
        if (cmd != CMD_INPUT) return null
        val sub = a[0]
        if (sub == 0x00 && a.size >= 3) return if (a[1] < N_LED) Message.Key(a[1], a[2]) else null
        if (sub == 0x01 && a.size >= 3) { val amt = maxOf(1, a[2]); return Message.Enc(if (a[1] == 2) amt else -amt) }
        if (sub == 0x02 && a.size >= 2) return Message.EncBtn(if (a[1] != 0) 1 else 0)
        if (sub == 0x03 && a.size >= 5) return if (a[1] < 5) Message.Pot(a[1], a[2], (a[3] shl 7) or a[4]) else null
        return null
    }

    // the OMX's side (the fake OMX-27 in the tests speaks it)
    fun keyMsg(k: Int, ev: Int) = sysex(CMD_INPUT, listOf(0x00, k, ev))
    fun encMsg(delta: Int) = sysex(CMD_INPUT, listOf(0x01, if (delta < 0) 0 else 2, minOf(127, kotlin.math.abs(delta))))
    fun potMsg(p: Int, v: Int, hires: Int? = null): ByteArray {
        val h = hires ?: (v shl 7)
        return sysex(CMD_INPUT, listOf(0x03, p, v, (h shr 7) and 0x7F, h and 0x7F))
    }
    fun statusMsg(s: Int) = sysex(CMD_STATUS, listOf(s))

    /** the LEDs as they should be (27 colours, 0..127) against what the OMX has (null: unknown) -> the messages */
    fun ledMessages(want: List<OmxRGB>, sent: List<OmxRGB>?): List<ByteArray> {
        val changed = (0 until N_LED).filter { sent == null || want[it] != sent[it] }
        if (changed.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>()
        if (changed.size > 8) out.add(sysex(CMD_LED_BATCH, listOf(0, N_LED) + want.flatMap { it.ints }))
        else changed.forEach { out.add(sysex(CMD_LED, listOf(it) + want[it].ints)) }
        out.add(sysex(CMD_LED_SHOW))
        return out
    }

    /** a frame (512 bytes) against the last one sent (null: none) -> its changed chunks and the latch, or [] when the same */
    fun frameMessages(fb: ByteArray, last: ByteArray?): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (c in 0 until N_CHUNK) {
            val a = c * CHUNK
            if (last != null && (0 until CHUNK).all { fb[a + it] == last[a + it] }) continue
            out.add(sysex(CMD_DRAW, listOf(c) + enc7(fb, a, a + CHUNK).map { it.toInt() and 0xFF }))
        }
        if (out.isNotEmpty()) out.add(sysex(CMD_DRAW_UPD))
        return out
    }
}

/** an OMX-27 LED colour (7-bit on the wire) */
data class OmxRGB(val r: Int, val g: Int, val b: Int) {
    val ints: List<Int> get() = listOf(r, g, b)
    val lit: Boolean get() = r > 0 || g > 0 || b > 0
    /** "r,g,b" (as the website's tests write it) */
    override fun toString() = "$r,$g,$b"

    companion object {
        val OFF = OmxRGB(0, 0, 0)
        /** clamped to a byte, as iOS's UInt8(clamping:) */
        fun of(r: Int, g: Int, b: Int) = OmxRGB(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}

/** the OMX's LEDs and screen as the app wants them, sent paced (js/omx27.js OmxSession) */
class OmxSession(val send: (ByteArray, Double) -> Unit, val now: () -> Double) {
    /** in REMOTE mode (52 02 seen, no 52 03 since) */
    var active = false
        private set
    var wantLeds: List<OmxRGB> = List(OmxWire.N_LED) { OmxRGB.OFF }
        private set
    private var sentLeds: List<OmxRGB>? = null
    private var ledsAt = -1e9
    var wantFrame = ByteArray(OmxWire.FB_BYTES)
        private set
    private var lastFrame: ByteArray? = null
    private var frameAt = -1e9
    var pending = false
        private set

    data class Stats(var frames: Int = 0, var acks: Int = 0, var chunks: Int = 0, var ledMsgs: Int = 0)
    val stats = Stats()

    /** the MODE command; what the OMX shows is unknown until it says it is in REMOTE mode */
    fun enter() = send(OmxWire.enterRemote(), 0.0)

    /** a message from the OMX: its event (null: none); the status ones change the session */
    fun feed(d: ByteArray): OmxWire.Message? {
        val m = OmxWire.parse(d) ?: return null
        if (m is OmxWire.Message.Status) {
            when (m.s) {
                OmxWire.STATUS_ACK -> { pending = false; stats.acks++ }
                OmxWire.STATUS_ON -> { active = true; reset() }
                OmxWire.STATUS_OFF -> { active = false; pending = false }
            }
        }
        return m
    }

    fun reset() {
        sentLeds = null
        lastFrame = null
        pending = false
        ledsAt = -1e9
        frameAt = -1e9
    }

    fun setLeds(c: List<OmxRGB>) { wantLeds = c.map { OmxRGB(it.r and 127, it.g and 127, it.b and 127) } }
    fun setFrame(fb: ByteArray) { wantFrame = fb.copyOf() }

    /** sends what is due; true when something went */
    fun tick(): Boolean {
        if (!active) return false
        val t = now()
        var sent = false
        if (t - ledsAt >= OmxWire.LED_MS) {
            val ms = OmxWire.ledMessages(wantLeds, sentLeds)
            if (ms.isNotEmpty()) {
                ms.forEach { send(it, 0.0) }
                sentLeds = wantLeds
                ledsAt = t
                stats.ledMsgs += ms.size
                sent = true
            }
        }
        if (pending && t - frameAt >= OmxWire.ACK_MS) pending = false     // (a lost ACK: no wedge)
        if (!pending && t - frameAt >= OmxWire.FRAME_MS) {
            val ms = OmxWire.frameMessages(wantFrame, lastFrame)
            if (ms.isNotEmpty()) {
                ms.forEachIndexed { i, m -> send(m, i * OmxWire.CHUNK_GAP_MS) }
                lastFrame = wantFrame
                frameAt = t
                pending = true
                stats.frames++
                stats.chunks += ms.size - 1
                sent = true
            }
        }
        return sent
    }

    /** every LED off and a blank screen, now (Disconnect, the app closing); the OMX stays in REMOTE mode */
    fun clear() {
        val dark = List(OmxWire.N_LED) { OmxRGB.OFF }
        val blank = ByteArray(OmxWire.FB_BYTES)
        OmxWire.ledMessages(dark, null).forEach { send(it, 0.0) }
        OmxWire.frameMessages(blank, null).forEachIndexed { i, m -> send(m, i * OmxWire.CHUNK_GAP_MS) }
        wantLeds = dark
        wantFrame = blank
        reset()
    }
}
