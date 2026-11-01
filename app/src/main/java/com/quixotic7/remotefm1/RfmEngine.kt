// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import org.json.JSONObject

/**
 * One FM-1 core engine (native/rfmcore: RFMEngine with its render-ahead core thread, the core loader, the Oboe
 * output), on the FM-1 [link] (which must outlive it) or headless (null). Control calls are for one thread (the main
 * thread or a test's); the clock's changes are serialised natively with the audio-control thread's ([streams]).
 * Close the engine only after its AudioControl has shut down.
 */
class RfmEngine(link: RfmLink? = null) : AutoCloseable {
    private var handle: Long = nativeCreate(link?.handle ?: 0L)

    /** Loads the core [id] (libfm1core_<id>.so); null on success, else the error. */
    fun load(id: String): String? = nativeLoad(handle, id).ifEmpty { null }

    /** (A4) Loads the .rfm1 file at [path] (wasm3: its own instance for this engine); null on success, else the reason. */
    fun loadWasm(path: String): String? = nativeLoadWasm(handle, path).ifEmpty { null }

    /** Powers the core on from [flash] (null: a fresh flash). */
    fun boot(flash: ByteArray? = null): Boolean = nativeBoot(handle, flash)

    fun setClock(owner: Int) = nativeSetClock(handle, owner)

    /** Attached: the engine plays the link's panel and shows on the unit (Core mode); off: the link's test pattern. */
    fun attach(on: Boolean) = nativeAttach(handle, on)

    /** Tests: the echo core (its MIDI in back out as USB-MIDI packets, silence). */
    fun testLoadEcho(): Boolean = nativeTestLoadEcho(handle)

    /** FM1VST's MIDI settings: [keyChannel] (0 any, -1 off, 1..16), [transpose] octaves, key notes to the firmware, the map. */
    fun setMidi(keyChannel: Int = 0, transpose: Int = 0, keyNotesToFirmware: Boolean = false, useMap: Boolean = true) =
        nativeSetMidi(handle, keyChannel, transpose, keyNotesToFirmware, useMap)

    /** The core's MIDI out kept for [renderBlocks] (1), or counted and dropped (0). */
    fun midiOutEnable(on: Int) = nativeMidiOutEnable(handle, on)

    fun setOffline(on: Boolean) = nativeSetOffline(handle, on)

    /**
     * Tests, with [CLOCK_AUDIO] and [setOffline] on: [count] renders of [frames] at [rate]; the core's MIDI out packets
     * (USB-MIDI, cin | b0 << 8 | b1 << 16 | b2 << 24) and the audio's RMS and peak into [level] if given.
     */
    fun renderBlocks(count: Int, frames: Int = 512, rate: Double = 44100.0, level: DoubleArray? = null): IntArray {
        val l = DoubleArray(3)
        val pk = nativeRenderBlocks(handle, count, frames, rate, l)
        if (level != null && level.size >= 2) {
            level[0] = if (l[1] > 0) kotlin.math.sqrt(l[0] / l[1]) else 0.0
            level[1] = l[2]
        }
        return pk
    }

    /** Note key 0..26 down or up, as the FM-1 sends it; [vel] 1..127, 0 the default. */
    fun key(key: Int, down: Boolean, vel: Int = 0): Boolean = nativeKey(handle, key, down, vel)

    /**
     * (Tests' synchronous path; the app uses [streams] on its audio-control thread.) Starts the Oboe stream on [deviceId] (0: Android's default, which the app never asks for: with an FM-1 on USB
     * that is the FM-1). [usbSafe]: a USB output the user picked (shared, no MMAP). [forbidden]: outputs it must not
     * end up on (the USB ones not picked; closed before a sample plays). Null on success, else the error.
     */
    fun startAudio(deviceId: Int = 0, usbSafe: Boolean = false, forbidden: IntArray = IntArray(0)): String? =
        nativeStartAudio(handle, deviceId, usbSafe, forbidden).ifEmpty { null }

    fun stopAudio(clockAfter: Int = CLOCK_TIMER) = nativeStopAudio(handle, clockAfter)

    // ---- the audio-control thread's streams (AudioControl.kt: each call may block in Android, never the main thread)
    /** The back end AudioControl drives: this engine's Oboe streams and its clock. */
    val streams: StreamBackend = object : StreamBackend {
        override fun open(out: AudioOut, forbidden: IntArray): StreamBackend.Opened {
            val h = LongArray(1)
            val err = nativeStreamOpen(handle, out.id, out.usb, forbidden, h).ifEmpty { null }
            return StreamBackend.Opened(h[0], err)
        }
        override fun start(handle: Long): String? = nativeStreamStart(handle).ifEmpty { null }
        override fun activate(handle: Long) = nativeStreamActivate(handle)
        override fun deactivate(handle: Long) = nativeStreamDeactivate(handle)
        override fun close(handle: Long) = nativeStreamClose(handle)
        override fun info(handle: Long, refresh: Boolean): StreamBackend.Info {
            val v = LongArray(11)
            nativeStreamInfo(handle, refresh, v)
            val api = if (v[6] == 2L) "AAudio" else if (v[6] == 1L) "OpenSL ES" else "?"
            val text = "$api ${if (v[7] == 0L) "exclusive" else "shared"}, performance ${v[8]}, MMAP ${v[5]}, " +
                "${v[9]} Hz, burst ${v[10]}" + if (v[4] != 0L) ", USB careful mode" else ""
            return StreamBackend.Info(v[0], v[2] != 0L, v[3].toInt(), text)
        }
        override fun claimClock(): Boolean = clockSwap(CLOCK_TIMER, CLOCK_AUDIO)
        override fun releaseClock() { clockSwap(CLOCK_AUDIO, CLOCK_TIMER) }
    }

    /** The clock to [to] only if it is [from] now (serialised with every other clock change); true: it moved. */
    fun clockSwap(from: Int, to: Int): Boolean = nativeClockSwap(handle, from, to)

    /** Tests: every stream close blocks [closeMs] inside, every open [openMs] before it opens (0 0: normal). */
    fun testAudioHang(closeMs: Int, openMs: Int = 0) = nativeTestAudioHang(handle, closeMs, openMs)

    /** The engine's and the audio stream's status (rfme_status, rfmo_status). */
    fun status(): JSONObject = JSONObject(nativeStatus(handle))

    fun resetStats() = nativeResetStats(handle)

    /** Tests: [frames] frames at [rate] through the render-ahead ring, [buffer] frames per render; L R interleaved. */
    fun renderOffline(frames: Int, rate: Double = 48000.0, buffer: Int = 192): FloatArray? =
        nativeRenderOffline(handle, frames, rate, buffer)

    // ---- the flash image (A2)
    val flashSize: Int get() = nativeFlashSize(handle)

    /** A copy the clock made (every 30 device s when the flash changed, after [requestFlash], a power cycle's) into
     *  [out] (flash size bytes); true when there was one. */
    fun takeFlash(out: ByteArray): Boolean = nativeTakeFlash(handle, out)

    /** Asks the clock for flash_sync and a copy at its next between hook (then [takeFlash]). */
    fun requestFlash() = nativeRequestFlash(handle)

    /** The clock stopped, flash_sync, the image now into [out], the clock given back; true: done. */
    fun finalFlash(out: ByteArray): Boolean = nativeFinalFlash(handle, out)

    /** The firmware powered off and on again from its flash (which is then offered to [takeFlash]); null or the error. */
    fun powerCycle(): String? = nativePowerCycle(handle).ifEmpty { null }

    fun unload() = nativeUnload(handle)

    // ---- the caller's clock (tests: [setClock] CLOCK_CALLER)
    class Audio(val frames: Long, val rms: Double, val peak: Int, val hash: Long)

    /** [ms] device milliseconds on this thread, the sound measured (rfme_run_ms). */
    fun run(ms: Int): Audio {
        val a = DoubleArray(4)
        nativeRunMs(handle, ms, a)
        return Audio(a[0].toLong(), a[1], a[2].toInt(), a[3].toLong())
    }

    /** A panel event as the FM-1 sends it (EV_KEY / EV_BUTTON / EV_ENC, matrix ids). */
    fun event(type: Int, index: Int, value: Int): Boolean = nativeEvent(handle, type, index, value)

    /** A button by label (B_*) down or up. */
    fun button(label: Int, down: Boolean): Boolean = nativeMatrixButton(handle, label).let { it >= 0 && event(EV_BUTTON, it, if (down) 1 else 0) }

    /** A knob by role (E_*) turned [detents]. */
    fun knob(role: Int, detents: Int): Boolean = nativeMatrixEncoder(handle, role).let { it >= 0 && event(EV_ENC, it, detents) }

    /** A parameter's value, null when the core has none by that name; [anyClock]: the clock stopped for the read. */
    fun param(name: String, anyClock: Boolean = false): Int? =
        nativeParam(handle, name, anyClock).takeIf { it != Long.MIN_VALUE }?.toInt()

    /** FNV-1a of the LCD as it is now (CALLER or NONE). */
    val lcdHash: Int get() = nativeLcdHash(handle)

    /** The colours on the LCD last published. */
    val colours: Int get() = nativeColours(handle)

    /** Tests: what a device disconnect does to the Oboe stream (closed, `dead`, counted). */
    fun testDisconnect() = nativeTestDisconnect(handle)

    /** The LCD (240 x 240 RGBA) into [rgba] when it changed since [have]; returns its number. */
    fun preview(have: Int, rgba: ByteArray): Int = nativePreview(handle, have, rgba)

    // ---- A3: the faceplate's reads (ui/faceplate/PanelFeed.kt), every frame, no allocations
    /** The LCD (240 x 240 RGBA) into the direct buffer [rgba] when it changed since [have]; returns its number. */
    fun previewDirect(have: Int, rgba: java.nio.ByteBuffer): Int = nativePreviewDirect(handle, have, rgba)

    /** rfme_panel into [out] (56 ints, PanelFeed's layout); returns its seq (0: none yet). */
    fun panel(out: IntArray): Int = nativePanel(handle, out)

    /** Moves when the running core or the button-names setting changed. */
    val buttonNamesGen: Int get() = nativeButtonNamesGen(handle)

    /** gen, loaded, use, then the 14 names (FM1P_B_* order), one per line. */
    fun buttonNamesText(): String = nativeButtonNames(handle)

    /** The matrix id of a button label (B_*), after boot; -1. */
    fun matrixButtonId(label: Int): Int = nativeMatrixButton(handle, label)

    /** The matrix encoder of a knob role (E_*), after boot; -1. */
    fun matrixEncoderId(role: Int): Int = nativeMatrixEncoder(handle, role)

    /** The "Firmware's button names" setting (rfme_set_button_names). */
    fun setButtonNames(use: Boolean) = nativeSetButtonNames(handle, use)

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        const val CLOCK_NONE = 0
        const val CLOCK_AUDIO = 1
        const val CLOCK_TIMER = 2
        const val CLOCK_CALLER = 3
        // RFMCore.h RFMC_EV_*, RFMEngine.h RFME_B_* / RFME_E_*
        const val EV_KEY = 1
        const val EV_BUTTON = 2
        const val EV_ENC = 3
        const val B_FX = 0; const val B_SEL = 1; const val B_ENV = 2; const val B_LFO = 3; const val B_EDIT = 4
        const val B_GLO = 5; const val B_HOME = 6; const val B_SAVE = 7; const val B_ARP = 8; const val B_SEQ = 9
        const val B_PLAY = 10; const val B_REC = 11; const val B_OCTDN = 12; const val B_OCTUP = 13
        const val E_SELECT = 0; const val E_ALGO = 1; const val E_PRESETS = 2; const val E_K1 = 3; const val E_K2 = 4
        const val E_K3 = 5; const val E_K4 = 6

        init {
            System.loadLibrary("rfmcore")
        }

        /** Finds the cores in [dir] (the app's nativeLibraryDir); returns how many. */
        fun scan(dir: String): Int = nativeScan(dir)

        /** {"cores": [{id, name, version, ...}], "errors": "..."} */
        fun cores(): JSONObject = JSONObject(nativeCores())

        @JvmStatic private external fun nativeScan(dir: String): Int
        @JvmStatic private external fun nativeCores(): String
        @JvmStatic private external fun nativeCreate(link: Long): Long
        @JvmStatic private external fun nativeAttach(h: Long, on: Boolean)
        @JvmStatic private external fun nativeTestLoadEcho(h: Long): Boolean
        @JvmStatic private external fun nativeSetMidi(h: Long, keyChannel: Int, transpose: Int, keyNotes: Boolean, useMap: Boolean)
        @JvmStatic private external fun nativeMidiOutEnable(h: Long, on: Int)
        @JvmStatic private external fun nativeSetOffline(h: Long, on: Boolean)
        @JvmStatic private external fun nativeRenderBlocks(h: Long, count: Int, frames: Int, rate: Double, level: DoubleArray): IntArray
        @JvmStatic private external fun nativeDestroy(h: Long)
        @JvmStatic private external fun nativeLoad(h: Long, id: String): String
        @JvmStatic private external fun nativeLoadWasm(h: Long, path: String): String
        @JvmStatic private external fun nativeBoot(h: Long, flash: ByteArray?): Boolean
        @JvmStatic private external fun nativeSetClock(h: Long, owner: Int)
        @JvmStatic private external fun nativeKey(h: Long, key: Int, down: Boolean, vel: Int): Boolean
        @JvmStatic private external fun nativeStartAudio(h: Long, device: Int, usbSafe: Boolean, forbidden: IntArray): String
        @JvmStatic private external fun nativeStopAudio(h: Long, clockAfter: Int)
        @JvmStatic private external fun nativeStatus(h: Long): String
        @JvmStatic private external fun nativeResetStats(h: Long)
        @JvmStatic private external fun nativeRenderOffline(h: Long, frames: Int, rate: Double, buffer: Int): FloatArray?
        @JvmStatic private external fun nativePreview(h: Long, have: Int, rgba: ByteArray): Int
        @JvmStatic private external fun nativeFlashSize(h: Long): Int
        @JvmStatic private external fun nativeTakeFlash(h: Long, out: ByteArray): Boolean
        @JvmStatic private external fun nativeRequestFlash(h: Long)
        @JvmStatic private external fun nativeFinalFlash(h: Long, out: ByteArray): Boolean
        @JvmStatic private external fun nativePowerCycle(h: Long): String
        @JvmStatic private external fun nativeUnload(h: Long)
        @JvmStatic private external fun nativeRunMs(h: Long, ms: Int, out: DoubleArray)
        @JvmStatic private external fun nativeEvent(h: Long, type: Int, index: Int, value: Int): Boolean
        @JvmStatic private external fun nativeMatrixButton(h: Long, label: Int): Int
        @JvmStatic private external fun nativeMatrixEncoder(h: Long, role: Int): Int
        @JvmStatic private external fun nativeParam(h: Long, name: String, anyClock: Boolean): Long
        @JvmStatic private external fun nativeLcdHash(h: Long): Int
        @JvmStatic private external fun nativeColours(h: Long): Int
        @JvmStatic private external fun nativeTestDisconnect(h: Long)
        @JvmStatic private external fun nativeStreamOpen(h: Long, device: Int, usbSafe: Boolean, forbidden: IntArray, out: LongArray): String
        @JvmStatic private external fun nativeStreamStart(s: Long): String
        @JvmStatic private external fun nativeStreamActivate(s: Long)
        @JvmStatic private external fun nativeStreamDeactivate(s: Long)
        @JvmStatic private external fun nativeStreamClose(s: Long)
        @JvmStatic private external fun nativeStreamInfo(s: Long, refresh: Boolean, out: LongArray)
        @JvmStatic private external fun nativeClockSwap(h: Long, from: Int, to: Int): Boolean
        @JvmStatic private external fun nativeTestAudioHang(h: Long, closeMs: Int, openMs: Int)
        @JvmStatic private external fun nativePreviewDirect(h: Long, have: Int, rgba: java.nio.ByteBuffer): Int
        @JvmStatic private external fun nativePanel(h: Long, out: IntArray): Int
        @JvmStatic private external fun nativeButtonNamesGen(h: Long): Int
        @JvmStatic private external fun nativeButtonNames(h: Long): String
        @JvmStatic private external fun nativeSetButtonNames(h: Long, use: Boolean)
    }
}
