// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDeviceService
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiReceiver
import android.util.Log

/**
 * (Debug builds only) a virtual MIDI device, "RemoteFM1 Fake FM-1", with libremotefm1's fake unit behind it: the link's
 * Device back end reaches it through Android's MIDI service and AMidi as it would a real FM-1 over USB, so the whole
 * Android MIDI path (discovery, openDevice, AMidi ports, SysEx cut into packets) is tested without hardware
 * (LinkTest's virtual cases, the app's `virtual` launch extra). Disabled until [enable]; never in a release build.
 * Every chunk that comes in and goes out is counted (rx_chunks / rx_max, tx), to show how Android splits SysEx.
 */
class FakeFm1Service : MidiDeviceService() {
    private val unit = FakeUnit(0)
    @Volatile private var running = true
    private var pump: Thread? = null
    @Volatile var outChunks = 0
        private set
    @Volatile var outMax = 0
        private set

    private val rx = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            unit.rx(msg, offset, count)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        pump = Thread({
            while (running) {
                val out = unit.poll()
                val r = outputPortReceivers.firstOrNull()
                if (out != null && r != null) {
                    var o = 0
                    while (o < out.size) {                   // (MidiPortImpl's packet: at most 1015 bytes of data)
                        val n = minOf(CHUNK, out.size - o)
                        runCatching { r.send(out, o, n) }
                        outChunks++
                        if (n > outMax) outMax = n
                        o += n
                    }
                }
                Thread.sleep(1)
            }
        }, "RFMFakeFm1").apply { start() }
        Log.i(TAG, "virtual FM-1 up")
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf(rx)

    override fun onDeviceStatusChanged(status: MidiDeviceStatus?) {
        Log.i(TAG, "status: $status")
    }

    override fun onDestroy() {
        running = false
        pump?.join(200)
        if (instance === this) instance = null
        unit.close()
        Log.i(TAG, "virtual FM-1 down")
        super.onDestroy()
    }

    fun fake(): FakeUnit = unit

    companion object {
        const val TAG = "RFMFakeFm1"
        const val CHUNK = 1015
        @Volatile var instance: FakeFm1Service? = null
            private set

        /** The virtual device is listed by Android's MIDI service while this component is enabled. */
        fun enable(ctx: Context, on: Boolean) {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, FakeFm1Service::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
    }
}
