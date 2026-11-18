// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Ableton Move's JNI (native/rfmcore/move_jni.cpp over rfmm_*, include/RFMMove.h: RemoteFM1IOS ad33e95's API, with
// Android's Sample queue and test entries). A handle is an rfmm_t pointer: rfm1cs-abletonmove's session and core (on
// the engine's core thread once RfmEngine.moveAttach has it), the socket's thread, and the requests the app answers.
package com.quixotic7.remotefm1.move

object MoveNative {
    init { System.loadLibrary("rfmcore") }

    // RFMMove.h RFMM_* (states: the session's RFM1CS_*) and RFMM_REQ_* (requests)
    const val OFF = 0
    const val CONNECTING = 1
    const val CONNECTED = 2
    const val RETRYING = 3
    const val FAILED = 4                    // it gave up (another protocol version)
    const val ERROR = FAILED
    const val REQ_SETTINGS = 7
    const val REQ_PROJECT_SAVE = 8
    const val REQ_PROJECT_LOAD = 9
    const val REQ_PROJECT_DELETE = 10
    const val REQ_PROJECT_CUT = 13
    const val REQ_PROJECT_COPY = 14
    const val REQ_PROJECT_PASTE = 15
    const val REQ_PROJECT_COLOR = 16
    const val REQ_SAMPLE_START = 12
    const val REQ_SAMPLE_STOP = 112

    val projectMax: Int by lazy { nativeProjectMax() }

    class Status(v: IntArray, nameText: String) {
        val state = v[0]
        val via = v[1]                      // MR_VIA_*: 0 Wi-Fi, 1 USB, 2 this computer; -1 never connected
        val battery = v[2]
        val mode = v[3]
        val project = v[4]
        val playing = v[5] != 0
        val ticking = v[6] != 0             // a firmware runs the core
        val inputs = v[7]
        val leds = v[8]
        val screens = v[9]
        val connects = v[10]
        val seqMidi = v[11]
        val following = v[12] != 0
        val bpm10 = v[13]
        val clicks = v[14]
        val name = nameText.substringBefore('\n')
        val text = nameText.substringAfter('\n', "")
    }

    fun create(settings: ByteArray?): Long = nativeNew(settings)
    fun free(h: Long) = nativeFree(h)
    /** connects (and again while wanted); host: "address" or "address:port" */
    fun start(h: Long, host: String) = nativeStart(h, host)
    fun stop(h: Long) = nativeStop(h)
    fun status(h: Long): Status { val v = IntArray(15); return Status(v, nativeStatus(h, v)) }
    /** the next request: out = kind, slot, value, total; its data in [data]; the data's length, -1 none */
    fun takeRequest(h: Long, out: IntArray, data: ByteArray): Int = nativeTakeRequest(h, out, data)
    fun projectsInit(h: Long, dir: Int, slot: Int, blob: ByteArray?) = nativeProjectsInit(h, dir, slot, blob)
    fun projectDir(h: Long, bits: Int) = nativeProjectDir(h, bits)
    fun projectInfo(h: Long, slot: Int, blob: ByteArray) = nativeProjectInfo(h, slot, blob)
    fun projectAnswer(h: Long, slot: Int, blob: ByteArray?) = nativeProjectAnswer(h, slot, blob)
    fun projectFlush(h: Long) = nativeProjectFlush(h)
    fun projectCheck(blob: ByteArray): Int = nativeProjectCheck(blob)
    fun withColor(blob: ByteArray, color: Int): ByteArray = nativeWithColor(blob, color)
    fun testEmptyProject(): ByteArray? = nativeTestEmptyProject()
    fun takeAudio(h: Long, stereo: ShortArray): Int = nativeTakeAudio(h, stereo)

    // tests: a fake Move in place of the socket (it answers HELLO and sends a STATUS every 500 ms); down: the link cut
    // (lost, tried again in 1 s); testDrop: the real socket cut
    fun testInput(h: Long, packet: Int) = nativeTestInput(h, packet)
    fun testConnected(h: Long, up: Boolean) = nativeTestConnected(h, up)
    fun testDrop(h: Long) = nativeTestDrop(h)
    fun testTakeLeds(h: Long, out: ByteArray): Int = nativeTestTakeLeds(h, out)
    fun testTakeScreen(h: Long, out: ByteArray): Boolean = nativeTestTakeScreen(h, out)

    /** an INPUT record's USB-MIDI packet (cable 0) for a note on / off or a CC, as the surface sends it */
    fun note(n: Int, vel: Int): Int = if (vel > 0) 0x09 or (0x90 shl 8) or (n shl 16) or (vel shl 24) else 0x08 or (0x80 shl 8) or (n shl 16)
    fun cc(n: Int, v: Int): Int = 0x0B or (0xB0 shl 8) or (n shl 16) or (v shl 24)

    @JvmStatic private external fun nativeNew(settings: ByteArray?): Long
    @JvmStatic private external fun nativeFree(h: Long)
    @JvmStatic private external fun nativeStart(h: Long, host: String)
    @JvmStatic private external fun nativeStop(h: Long)
    @JvmStatic private external fun nativeStatus(h: Long, out: IntArray): String
    @JvmStatic private external fun nativeTakeRequest(h: Long, out: IntArray, data: ByteArray): Int
    @JvmStatic private external fun nativeProjectsInit(h: Long, dir: Int, slot: Int, blob: ByteArray?)
    @JvmStatic private external fun nativeProjectDir(h: Long, bits: Int)
    @JvmStatic private external fun nativeProjectInfo(h: Long, slot: Int, blob: ByteArray)
    @JvmStatic private external fun nativeProjectAnswer(h: Long, slot: Int, blob: ByteArray?)
    @JvmStatic private external fun nativeProjectFlush(h: Long)
    @JvmStatic private external fun nativeProjectCheck(blob: ByteArray): Int
    @JvmStatic private external fun nativeWithColor(blob: ByteArray, color: Int): ByteArray
    @JvmStatic private external fun nativeProjectMax(): Int
    @JvmStatic private external fun nativeTestEmptyProject(): ByteArray?
    @JvmStatic private external fun nativeTakeAudio(h: Long, stereo: ShortArray): Int
    @JvmStatic private external fun nativeTestInput(h: Long, packet: Int)
    @JvmStatic private external fun nativeTestConnected(h: Long, up: Boolean)
    @JvmStatic private external fun nativeTestDrop(h: Long)
    @JvmStatic private external fun nativeTestTakeLeds(h: Long, out: ByteArray): Int
    @JvmStatic private external fun nativeTestTakeScreen(h: Long, out: ByteArray): Boolean
}
