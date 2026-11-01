// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import org.json.JSONObject

/**
 * A standalone libremotefm1 fake unit (rfm_link_jni.cpp), for the debug build's virtual MIDI device (FakeFm1Service):
 * the link reaches it through Android's own MIDI service and AMidi, exactly as it reaches a real FM-1 over USB.
 * [sxRateBps]: its simulated wire (0: as fast as Android carries it). Thread-safe.
 */
class FakeUnit(sxRateBps: Int = 0) : AutoCloseable {
    private var h = nativeNew(sxRateBps)

    /** bytes the host sent (one MidiReceiver.onSend) */
    fun rx(data: ByteArray, off: Int, len: Int) = nativeRx(h, data, off, len)

    /** its timers, then what it sends the host (null: nothing) */
    fun poll(): ByteArray? = nativePoll(h)

    fun key(k: Int, down: Boolean) = nativeKey(h, k, down)
    fun trs(msgs: ByteArray) = nativeTrs(h, msgs)

    /** {"session","hellos","sessions_started","frames","acks","tile_bytes","decode_errs","lit_keys","rx_chunks",...} */
    fun state(): JSONObject = JSONObject(nativeState(h))

    override fun close() {
        if (h != 0L) {
            nativeFree(h)
            h = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("rfmcore")
        }

        @JvmStatic private external fun nativeNew(sxRateBps: Int): Long
        @JvmStatic private external fun nativeFree(h: Long)
        @JvmStatic private external fun nativeRx(h: Long, data: ByteArray, off: Int, len: Int)
        @JvmStatic private external fun nativePoll(h: Long): ByteArray?
        @JvmStatic private external fun nativeKey(h: Long, k: Int, down: Boolean)
        @JvmStatic private external fun nativeTrs(h: Long, msgs: ByteArray)
        @JvmStatic private external fun nativeState(h: Long): String
    }
}
