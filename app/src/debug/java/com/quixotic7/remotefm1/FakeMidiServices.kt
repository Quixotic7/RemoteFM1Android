// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// (Debug builds only) A6's virtual MIDI devices for the emulator tests, after FakeFm1Service: a MIDI keyboard ("RFM
// Test Keyboard": it plays what the test sends) and an OMX-27 ("OMX-27": REMOTE mode as omx_mode_remote.cpp speaks it,
// the website's tools/omx-fake.js: MODE 51 05 09 answered 52 02, LEDs and the screen staged and latched, each frame
// ACKed). Through Android's MIDI service, as a USB device would be. Disabled until a test enables them.
package com.quixotic7.remotefm1

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver

private fun enable(ctx: Context, cls: Class<*>, on: Boolean) {
    ctx.packageManager.setComponentEnabledSetting(ComponentName(ctx, cls),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP)
}

class FakeControllerService : MidiDeviceService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = emptyArray()

    /** the keyboard plays [bytes]; false: nobody listens */
    fun play(bytes: ByteArray): Boolean {
        val r = outputPortReceivers.firstOrNull() ?: return false
        return runCatching { r.send(bytes, 0, bytes.size) }.isSuccess
    }

    companion object {
        const val NAME = "RFM Test Keyboard"
        @Volatile var instance: FakeControllerService? = null
            private set
        fun enable(ctx: Context, on: Boolean) = enable(ctx, FakeControllerService::class.java, on)
    }
}

class FakeOmxService : MidiDeviceService() {
    @Volatile var remote = false
    val leds = IntArray(27 * 3)
    private val ledStage = IntArray(27 * 3)
    val frame = ByteArray(512)
    private val stage = ByteArray(512)
    @Volatile var frames = 0
    @Volatile var modes = 0
    private val buf = java.io.ByteArrayOutputStream()

    private val rx = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            synchronized(buf) {
                for (i in offset until offset + count) {
                    val b = msg[i].toInt() and 0xFF
                    if (b == 0xF0) buf.reset()
                    buf.write(b)
                    if (b == 0xF7) { receive(buf.toByteArray()); buf.reset() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf(rx)

    private fun out(d: ByteArray) { runCatching { outputPortReceivers.firstOrNull()?.send(d, 0, d.size) } }
    private fun sx(vararg b: Int) = ByteArray(b.size + 5) { i -> when (i) { 0 -> 0xF0.toByte(); 1 -> 0x7D; 2, 3 -> 0; b.size + 4 -> 0xF7.toByte(); else -> b[i - 4].toByte() } }

    private fun receive(d: ByteArray) {
        if (d.size < 6 || d[1].toInt() != 0x7D || d[2].toInt() != 0 || d[3].toInt() != 0) return
        val c = d[4].toInt() and 0xFF
        val p = (5 until d.size - 1).map { d[it].toInt() and 0xFF }
        if (c == 0x51 && p.firstOrNull() == 0x05) {
            if (p.size > 1 && p[1] == 9) {
                if (remote) out(sx(0x52, 0x03))
                remote = true
                modes++
                out(sx(0x52, 0x02))
            }
            return
        }
        if (!remote) return
        when {
            c == 0x59 && p.size >= 4 && p[0] < 27 -> for (j in 0..2) ledStage[p[0] * 3 + j] = p[1 + j]
            c == 0x5A && p.size >= 2 -> { var i = 0; while (i < p[1] && p[0] + i < 27) { for (j in 0..2) ledStage[(p[0] + i) * 3 + j] = p[2 + 3 * i + j]; i++ } }
            c == 0x5B -> synchronized(leds) { ledStage.copyInto(leds) }
            c == 0x5C && p.isNotEmpty() && p[0] < 16 -> {
                var s = 1
                var i = 0
                while (i < 32) {
                    val n = minOf(7, 32 - i)
                    val hi = p[s++]
                    for (j in 0 until n) { stage[p[0] * 32 + i + j] = ((p[s] and 0x7F) or (((hi shr j) and 1) shl 7)).toByte(); s++ }
                    i += 7
                }
            }
            c == 0x5D -> {
                synchronized(frame) { stage.copyInto(frame) }
                frames++
                out(sx(0x52, 0x04))
            }
        }
    }

    // the OMX's own events
    fun key(k: Int, ev: Int) = out(sx(0x51, 0x00, k, ev))
    fun press(k: Int) = key(k, 1)
    fun release(k: Int) { key(k, 0); key(k, 3) }
    fun leave() { remote = false; out(sx(0x52, 0x03)) }
    fun lit(k: Int): Boolean = synchronized(leds) { leds[k * 3] > 0 || leds[k * 3 + 1] > 0 || leds[k * 3 + 2] > 0 }
    fun litPixels(): Int = synchronized(frame) { frame.sumOf { Integer.bitCount(it.toInt() and 0xFF) } }

    companion object {
        const val NAME = "OMX-27"
        @Volatile var instance: FakeOmxService? = null
            private set
        fun enable(ctx: Context, on: Boolean) = enable(ctx, FakeOmxService::class.java, on)
    }
}
