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
    // the firmware browser (rfm1cs-abletonmove 0.5.10, one firmware and no FX): data is the firmware's id
    const val REQ_FW_LOAD = 17                  // slot 0, value: its index in the list
    const val REQ_FW_UNLOAD = 18
    const val REQ_FW_DOWNLOAD = 20
    const val REQ_FW_LIBRARY = 21               // data: the library blob
    const val REQ_FW_LEVEL = 22                 // slot 0, value 0..127
    const val FW_FAILED = -1                    // fwProgress: RFM_MOVE_FW_FAILED, _BUSY ("Too busy")
    const val FW_BUSY = -2

    val projectMax: Int by lazy { nativeProjectMax() }
    /** the library blob's largest size (RFM_MOVE_FW_LIB_BYTES) */
    val libraryMax: Int by lazy { nativeLibraryMax() }

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
        val tone = v[15]                    // text's RFM1CS_TONE_*: TONE_OFF, _BUSY, _ON, _BAD
        val attempt = v[16]                 // the session's try (tests)
        private val parts = nameText.split('\n')
        val name = parts[0]
        /** the status line (rfm1cs_status_text: the web player's and iOS's words) */
        val text = parts.getOrElse(1) { "" }
        /** the last live link's loss, as a toast (rfm1cs_lost_text); "" none */
        val toast = parts.getOrElse(2) { "" }
    }

    const val TONE_OFF = 0
    const val TONE_BUSY = 1
    const val TONE_ON = 2
    const val TONE_BAD = 3

    /** the project file (.rfmmove / .rfmmoveset, "RMVP") by rfm1cs-abletonmove's store.c: the web player's and the
     *  iPhone app's format, byte for byte as Android's own Kotlin wrote it before */
    object FileCodec : MoveFileCodec {
        override fun pack(ps: List<MoveProject>, kind: Int): ByteArray =
            nativeFilePack(IntArray(ps.size) { ps[it].slot },
                IntArray(ps.size) { val t = ps[it].saved; if (t > 0) (t / 1000).toInt() else 0 },
                Array(ps.size) { ps[it].bytes }, kind)
                ?: throw IllegalArgumentException("Can't write a Move project file of ${ps.size} projects.")

        override fun unpack(bytes: ByteArray?): MoveProjects.Unpacked {
            val meta = IntArray(1 + 2 * MoveProjects.SLOTS)
            val why = arrayOfNulls<String>(1)
            val blobs = nativeFileUnpack(bytes, meta, why)
                ?: throw IllegalArgumentException(why[0] ?: "Not a Move project file.")
            val now = System.currentTimeMillis()
            return MoveProjects.Unpacked(meta[0], blobs.mapIndexed { i, b ->
                val t = meta[2 + 2 * i].toLong() and 0xFFFFFFFFL
                MoveProject(meta[1 + 2 * i], b, if (t > 0) t * 1000 else now)
            })
        }
    }

    /** the core and its session (one firmware, no FX: rfm_move_fw_caps 0); settings and library as stored (null: none) */
    fun create(settings: ByteArray?, library: ByteArray? = null): Long = nativeNew(settings, library)
    fun free(h: Long) = nativeFree(h)
    /** connects (and again while wanted); host: "address", "address:port" or "[v6]:port" (rfm1cs_tcp_connect's);
     *  shown: the address the status texts name (blank: host) */
    fun start(h: Long, host: String, shown: String = "") = nativeStart(h, host, shown)
    fun stop(h: Long) = nativeStop(h)
    fun status(h: Long): Status { val v = IntArray(17); return Status(v, nativeStatus(h, v)) }
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

    /** the Move's firmware browser: the whole catalog (a new list each time; the core keeps it until the next) */
    fun fwList(h: Long, l: List<MoveFw>) {
        val meta = ByteArray(3 * l.size)
        l.forEachIndexed { i, e ->
            meta[3 * i] = e.cats.toByte(); meta[3 * i + 1] = e.cat.toByte(); meta[3 * i + 2] = if (e.virt) 1 else 0
        }
        nativeFwList(h, Array(l.size) { l[it].id }, Array(l.size) { l[it].name }, Array(l.size) { l[it].version },
            Array(l.size) { l[it].installed }, Array(l.size) { l[it].desc }, Array(l.size) { l[it].thumb }, meta)
    }
    /** a download landed: that entry's installed version (null: removed) */
    fun fwInstalled(h: Long, id: String, version: String?) = nativeFwInstalled(h, id, version)
    /** what slot 0 holds now (null: nothing) */
    fun fwSlot(h: Long, id: String?) = nativeFwSlot(h, id)
    /** a load's or download's progress 0..100 (100 done), [FW_FAILED], [FW_BUSY] */
    fun fwProgress(h: Long, id: String, pct: Int) = nativeFwProgress(h, id, pct)
    /** (tests) the core's firmware state: "caps active track slot0 n", then "id|version|installed|cats|cat|virt|thumb"
     *  a line (installed "-": none; thumb 1: given) */
    fun testFw(h: Long): String = nativeTestFw(h)

    // tests: a fake Move in place of the socket (it answers HELLO and sends a STATUS every 500 ms); down: the link cut
    // (lost, tried again in 1 s); testDrop: the real socket cut; testClose: a close from that try's socket (0: the
    // current one; an older try's is ignored)
    fun testInput(h: Long, packet: Int) = nativeTestInput(h, packet)
    fun testConnected(h: Long, up: Boolean) = nativeTestConnected(h, up)
    fun testDrop(h: Long) = nativeTestDrop(h)
    fun testClose(h: Long, attempt: Int) = nativeTestClose(h, attempt)
    fun testTakeLeds(h: Long, out: ByteArray): Int = nativeTestTakeLeds(h, out)
    fun testTakeScreen(h: Long, out: ByteArray): Boolean = nativeTestTakeScreen(h, out)

    /** an INPUT record's USB-MIDI packet (cable 0) for a note on / off or a CC, as the surface sends it */
    fun note(n: Int, vel: Int): Int = if (vel > 0) 0x09 or (0x90 shl 8) or (n shl 16) or (vel shl 24) else 0x08 or (0x80 shl 8) or (n shl 16)
    fun cc(n: Int, v: Int): Int = 0x0B or (0xB0 shl 8) or (n shl 16) or (v shl 24)

    @JvmStatic private external fun nativeNew(settings: ByteArray?, library: ByteArray?): Long
    @JvmStatic private external fun nativeLibraryMax(): Int
    @JvmStatic private external fun nativeFwList(h: Long, ids: Array<String>, names: Array<String>, versions: Array<String>,
                                                 installed: Array<String?>, descs: Array<String?>, thumbs: Array<ByteArray?>,
                                                 meta: ByteArray)
    @JvmStatic private external fun nativeFwInstalled(h: Long, id: String, version: String?)
    @JvmStatic private external fun nativeFwSlot(h: Long, id: String?)
    @JvmStatic private external fun nativeFwProgress(h: Long, id: String, pct: Int)
    @JvmStatic private external fun nativeTestFw(h: Long): String
    @JvmStatic private external fun nativeFree(h: Long)
    @JvmStatic private external fun nativeStart(h: Long, host: String, shown: String)
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
    @JvmStatic private external fun nativeFilePack(slots: IntArray, saved: IntArray, blobs: Array<ByteArray>, kind: Int): ByteArray?
    @JvmStatic private external fun nativeFileUnpack(file: ByteArray?, meta: IntArray, why: Array<String?>): Array<ByteArray>?
    @JvmStatic private external fun nativeTestEmptyProject(): ByteArray?
    @JvmStatic private external fun nativeTakeAudio(h: Long, stereo: ShortArray): Int
    @JvmStatic private external fun nativeTestInput(h: Long, packet: Int)
    @JvmStatic private external fun nativeTestConnected(h: Long, up: Boolean)
    @JvmStatic private external fun nativeTestDrop(h: Long)
    @JvmStatic private external fun nativeTestClose(h: Long, attempt: Int)
    @JvmStatic private external fun nativeTestTakeLeds(h: Long, out: ByteArray): Int
    @JvmStatic private external fun nativeTestTakeScreen(h: Long, out: ByteArray): Boolean
}
