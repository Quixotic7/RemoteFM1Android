// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/MIDI/ControllerInput.swift (decode: what reaches the engine)
// A controller's MIDI byte stream (Android's MidiReceiver.onSend gives bytes, not UMP as iOS's CoreMIDI does) into
// channel messages: status and one or two data bytes, running status kept. SysEx, clock and the other system messages
// are left out (real-time bytes may come anywhere, even inside a message: skipped). Pure, for the JVM tests.
package com.quixotic7.remotefm1.midi

class MidiParser(private val emit: (ByteArray, Int) -> Unit) {
    private val msg = ByteArray(3)
    private var status = 0          // the running status (0: none)
    private var need = 0            // data bytes this message takes
    private var have = 0
    private var sysex = false

    fun feed(b: ByteArray, off: Int = 0, len: Int = b.size) {
        for (i in off until off + len) byte(b[i].toInt() and 0xFF)
    }

    private fun byte(x: Int) {
        if (x >= 0xF8) return                               // (real time, anywhere)
        if (x >= 0x80) {
            sysex = x == 0xF0
            if (x >= 0xF0) {                                // (system common, SysEx, its end: none of ours; no running status)
                status = 0
                return
            }
            status = x
            need = if (x and 0xE0 == 0xC0) 1 else 2          // (program change, channel pressure: one data byte)
            have = 0
            msg[0] = x.toByte()
            return
        }
        if (sysex || status == 0) return
        msg[1 + have] = x.toByte()
        have++
        if (have == need) {
            emit(msg, 1 + need)
            have = 0                                        // (running status: the next data bytes are another)
        }
    }

    companion object {
        /** the messages in [b] (tests) */
        fun messages(b: ByteArray): List<List<Int>> {
            val out = ArrayList<List<Int>>()
            MidiParser { m, n -> out.add((0 until n).map { m[it].toInt() and 0xFF }) }.feed(b)
            return out
        }
    }
}
