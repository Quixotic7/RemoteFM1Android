// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/MIDI/ControllerInput.swift
// The app's MIDI input from controllers (M8): USB and Bluetooth MIDI keyboards and controllers through Android's
// MidiManager, connected as the user picked: all (the default), none, or one (by its name; remembered, also while it
// is away). Never the FM-1 (Fm1Candidate's rule; a virtual device: the website's name test) and never a device
// `exclude` claims (the OMX-27 while it is in REMOTE mode). Each device's output ports are opened and read; channel
// messages (MidiParser) go to the engine as host MIDI (rfme_midi_in: learn, the learned map, notes play keys, the rest
// to the firmware). Android may refuse to open a device or a port another app holds: the source then says so.
// The device work runs on one HandlerThread; the list the UI shows is Compose state, set on the main thread.
package com.quixotic7.remotefm1.midi

import android.content.Context
import android.content.SharedPreferences
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.quixotic7.remotefm1.Fm1Candidate
import com.quixotic7.remotefm1.MidiDesc
import com.quixotic7.remotefm1.RfmMidi
import java.util.concurrent.Executor

/** where a controller's channel messages go (RfmEngine.midiIn; any thread) */
fun interface MidiInSink {
    fun midiIn(data: ByteArray, len: Int)
}

class ControllerInput(ctx: Context, private val prefs: SharedPreferences, sink: MidiInSink?) {
    /** a device that can be picked */
    data class Source(val key: String, val name: String, val connected: Boolean, val error: String = "")

    /** the website's prefs.midi: "*" all, "" none, else the device's key */
    sealed class Selection {
        data object All : Selection()
        data object None : Selection()
        data class One(val key: String) : Selection()

        val stored: String get() = when (this) { All -> "*"; None -> ""; is One -> key }

        companion object {
            fun of(s: String?): Selection = if (s == null || s == "*") All else if (s.isEmpty()) None else One(s)
        }
    }

    /** the devices that can be picked (not the FM-1, not excluded), as Android lists them */
    var sources by mutableStateOf<List<Source>>(emptyList())
        private set
    var selection by mutableStateOf(Selection.of(prefs.getString(KEY, null)))
        private set
    /** a device someone else owns (the OMX-27 in REMOTE mode): never connected here. Call reconnect() when it changes */
    @Volatile var exclude: ((MidiDesc) -> Boolean)? = null
    @Volatile var sink: MidiInSink? = sink

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("RFMControllers").apply { start() }
    private val handler = Handler(thread.looper)
    private var mm: MidiManager? = null

    private class Open(val info: MidiDeviceInfo, var device: MidiDevice? = null, val ports: ArrayList<MidiOutputPort> = ArrayList(),
                       var error: String = "", var opening: Boolean = false)

    private val open = HashMap<Int, Open>()            // by MidiDeviceInfo.id (the handler thread)

    init {
        handler.post {
            val m = app.getSystemService(Context.MIDI_SERVICE) as? MidiManager
            mm = m
            if (m == null) return@post
            val cb = object : MidiManager.DeviceCallback() {
                override fun onDeviceAdded(info: MidiDeviceInfo) = scan()
                override fun onDeviceRemoved(info: MidiDeviceInfo) {
                    open.remove(info.id)?.let { close(it) }
                    scan()
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                m.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, Executor { handler.post(it) }, cb)
            } else {
                @Suppress("DEPRECATION")
                m.registerDeviceCallback(cb, handler)
            }
            scan()
        }
    }

    fun select(s: Selection) {
        if (s == selection) return
        selection = s
        prefs.edit().putString(KEY, s.stored).apply()
        reconnect()
    }

    /** the devices listed again and the picked ones connected (a setup change, `exclude` changed) */
    fun reconnect() { handler.post { scan() } }

    // (the handler thread)
    private fun scan() {
        val m = mm ?: return
        val infos: Collection<MidiDeviceInfo> = if (Build.VERSION.SDK_INT >= 33)
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM) else @Suppress("DEPRECATION") m.devices.toList()
        val sel = selection
        val list = ArrayList<Source>()
        val want = HashSet<Int>()
        for (info in infos) {
            if (info.outputPortCount == 0) continue
            val d = RfmMidi.describe(info)
            if (isFm1(d) || exclude?.invoke(d) == true) continue
            val key = keyOf(d)
            val on = when (sel) {
                Selection.All -> true
                Selection.None -> false
                is Selection.One -> sel.key == key
            }
            if (on) {
                want.add(info.id)
                connect(info, d)
            }
            val o = open[info.id]
            list.add(Source(key, d.displayName, on && o != null && o.ports.isNotEmpty(), if (on) o?.error ?: "" else ""))
        }
        for (id in open.keys.toList()) if (id !in want) open.remove(id)?.let { close(it) }
        main.post { if (list != sources) sources = list }
    }

    private fun connect(info: MidiDeviceInfo, d: MidiDesc, tries: Int = 4) {
        if (open.containsKey(info.id) && tries == 4) return
        val o = open[info.id] ?: Open(info, opening = true).also { open[info.id] = it }
        mm?.openDevice(info, { dev ->
            if (open[info.id] !== o) { runCatching { dev?.close() }; return@openDevice }
            if (dev == null && tries > 1) {
                // (a device just added may not open at first: a virtual one still binding, a USB one settling)
                handler.postDelayed({ if (open[info.id] === o) connect(info, d, tries - 1) }, 400)
                return@openDevice
            }
            o.opening = false
            if (dev == null) {
                o.error = "Android could not open it (another app may have it)"
                Log.w(TAG, "${d.displayName}: openDevice failed")
                scan()
                return@openDevice
            }
            o.device = dev
            for (p in 0 until info.outputPortCount) {
                val port = runCatching { dev.openOutputPort(p) }.getOrNull()
                if (port == null) {
                    Log.w(TAG, "${d.displayName}: output port $p would not open")
                    continue
                }
                val parser = MidiParser { b, n -> this.sink?.midiIn(b, n) }
                port.connect(object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = parser.feed(msg, offset, count)
                })
                o.ports.add(port)
            }
            if (o.ports.isEmpty()) o.error = "its MIDI out would not open (another app may have it)"
            Log.i(TAG, "${d.displayName}: ${o.ports.size} of ${info.outputPortCount} output ports read")
            scan()
        }, handler)
    }

    private fun close(o: Open) {
        o.ports.forEach { runCatching { it.close() } }
        o.ports.clear()
        runCatching { o.device?.close() }
        o.device = null
    }

    companion object {
        const val TAG = "RFMControllers"
        const val KEY = "rfm1.midiInput"

        /** the key a device is remembered by: its name (Android's ids change with every connection) */
        fun keyOf(d: MidiDesc): String = d.displayName

        /** the website's test for a port that is the FM-1 (RemoteFM1 on it), where the USB IDs can't tell */
        fun unitName(name: String): Boolean {
            val n = name.lowercase()
            return (n.contains("fm-1") || n.contains("remotefm1")) && !n.contains("update")
        }

        /** never a controller: an FM-1 candidate (with no virtual loopback), or a virtual device the name test calls one */
        fun isFm1(d: MidiDesc): Boolean {
            val c = Fm1Candidate.check(d.copy(inputPorts = d.inputPorts.ifEmpty { listOf("in") },
                                              outputPorts = d.outputPorts.ifEmpty { listOf("out") }))
            // (where the USB IDs can't tell: a virtual device, Bluetooth, USB without its IDs)
            return c.candidate || ((d.type != MidiDesc.TYPE_USB || d.usbVid == null) && unitName(d.displayName))
        }
    }
}
