// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS c47ac4f RFMKit/Sources/RFMKit/CorePlayer+AudioIn.swift (ain1 in the standalone app);
// changes: Android. The input is an Oboe input stream (native/rfmcore/RFMOboeInput.cpp) that feeds the engine's input
// ring (resampled to 44.1 kHz there), opened and closed on a worker thread of its own (both may block in Android);
// the permission is RECORD_AUDIO, asked by the Firmware page when the user turns the input on (never before).
//
// A firmware whose manifest wants or needs audio input ("ain1") gets the device's mic or an audio interface, the
// user's pick, once they turn it on. On and the input chosen are kept per firmware (iOS's keys: ain.on.<id>,
// ain.port.<id>; here the port is an AudioDeviceInfo id, 0 the default). The round trip reported to the firmware:
// the output's and the input's latencies, the engine's render-ahead and the input ring's level. The firmware monitors
// its input itself; the app plays nothing of it.
package com.quixotic7.remotefm1

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

class AudioInput(private val ctx: Context, private val engine: RfmEngine, private val prefs: SharedPreferences) {
    /** an input the user can pick (AudioDeviceInfo id; 0: Android's default) */
    data class Port(val id: Int, val name: String)

    /** the engine's input is on now (the main thread's view) */
    var live by mutableStateOf(false)
        private set
    /** the input device in use (0: the default) */
    var portInUse by mutableStateOf(0)
        private set
    /** why the input couldn't open ("" none) */
    var problem by mutableStateOf("")
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "RFMAudioIn").apply { isDaemon = true } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var sent: Want? = null                     // (the main thread's) the last want handed to the worker
    private val modes = HashMap<String, Int>()          // .rfm1 path -> its ain1 mode

    /** what the input should be: on for [firmwareID] on [port], given the output's latency and the engine's */
    data class Want(val on: Boolean, val firmwareID: String = "", val port: Int = 0, val outMs: Double = 0.0,
                    val engineMs: Double = 0.0)

    fun isOn(firmwareID: String): Boolean = prefs.getBoolean(KEY_ON + firmwareID, false)
    fun preferredPort(firmwareID: String): Int = prefs.getInt(KEY_PORT + firmwareID, 0)

    /** the microphone permission as it stands (never asks) */
    val granted: Boolean
        get() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** (the Firmware page, after the permission was asked when turning on) on or off for [firmwareID], the input kept */
    fun set(firmwareID: String, on: Boolean, port: Int? = null) {
        prefs.edit().putBoolean(KEY_ON + firmwareID, on).apply { if (port != null) putInt(KEY_PORT + firmwareID, port) }.apply()
        Log.i(TAG, "input ${if (on) "on" else "off"} for $firmwareID${port?.let { ", port $it" } ?: ""}")
    }

    /** an .rfm1's ain1 mode (0 none, 1 wants, 2 needs), read once per file */
    fun mode(path: String): Int {
        if (path.isEmpty()) return 0
        return modes.getOrPut(path) { RfmEngine.audioInOf(path).coerceAtLeast(0) }
    }

    /** the inputs Android offers now (mic, headset, USB interface ...) */
    fun ports(): List<Port> {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return emptyList()
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource && it.type in SOURCES }
            .map { Port(it.id, label(it)) }.distinctBy { it.name }     // (phones list each built-in mic)
    }

    /** (the main thread; cheap, every poll) the input made to match [w]: only a change goes to the worker */
    fun want(w: Want) {
        val s = sent
        // (latencies only matter for a new open, or once the output's becomes known)
        if (s != null && s.on == w.on && s.firmwareID == w.firmwareID && s.port == w.port &&
            (s.outMs > 0 || w.outMs <= 0)) return
        sent = w
        worker.execute { apply(w) }
    }

    private var open: Want? = null                      // (the worker's) what is open now

    private fun apply(w: Want) {
        val o = open
        if (!w.on) {
            if (o != null) {
                engine.audioInSet(false, 0, 0)
                engine.inputStop()
                open = null
                Log.i(TAG, "input off")
            }
            post(false, 0, "")
            return
        }
        if (o == null || o.port != w.port || o.firmwareID != w.firmwareID) {
            if (o != null) engine.inputStop()
            val err = engine.inputStart(w.port)
            if (err != null) {
                engine.audioInSet(false, 0, 0)
                open = null
                Log.w(TAG, "input: $err")
                post(false, 0, "The audio input didn't open: $err")
                return
            }
        }
        open = w
        val inMs = engine.inputLatencyMs().coerceAtLeast(0.0)
        val trip = ((inMs + w.outMs.coerceAtLeast(0.0) + w.engineMs) / 1000.0 * 44100).toInt() + RING_FRAMES
        engine.audioInSet(true, trip, RING_FRAMES)
        Log.i(TAG, "input live on ${w.port} for ${w.firmwareID}: round trip $trip frames (in %.1f ms, out %.1f ms, engine %.1f ms)"
            .format(inMs, w.outMs, w.engineMs))
        post(true, w.port, "")
    }

    private fun post(on: Boolean, port: Int, why: String) = main.post {
        live = on
        portInUse = port
        problem = why
    }

    fun shutdown() {
        worker.execute { apply(Want(false)) }
        worker.shutdown()
    }

    companion object {
        const val TAG = "RFMAudioIn"
        const val KEY_ON = "ain.on."
        const val KEY_PORT = "ain.port."
        /** the input ring's level at 44.1 kHz: its own share of the round trip (iOS AudioInput.ringFrames) */
        const val RING_FRAMES = 512
        private val SOURCES = setOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

        fun label(d: AudioDeviceInfo): String = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset (${d.productName})"
            else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Input ${d.id}"
        }

        /** the Firmware page's note (iOS's wording) */
        fun note(name: String, mode: Int): String = if (mode == 2) "$name needs audio in: it plays silence without it."
            else "$name can take audio in: the microphone, a headset or an audio interface."
    }
}
