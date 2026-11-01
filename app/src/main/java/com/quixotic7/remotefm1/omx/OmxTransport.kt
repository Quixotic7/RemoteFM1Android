// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/OMX27/OmxTransport.swift
// What OmxLink talks through, behind interfaces so the JVM tests drive it with a fake OMX-27 and a fake clock:
//   OmxTransport   the MIDI ports: the ports now, one input listened to and one output sent to, setup changes.
//                  OmxAndroidMidi is Android's MidiManager: a byte stream both ways (no UMP, unlike iOS's CoreMIDI),
//                  SysEx reassembled F0..F7 and handed over on the main thread; delayed sends (the screen's chunks,
//                  2 ms apart) on a sender thread.
//   OmxClock       milliseconds and timers on the main thread (OmxMainClock: a Handler on the main looper)
// omxPair picks the OMX's ports: an output whose name says OMX and the input of the same name (else the first OMX
// input), as the website's omx27.js. Here "input" is a port the app reads (a device's output port) and "output" one
// it sends to (a device's input port), as on iOS.
package com.quixotic7.remotefm1.omx

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor

/** a MIDI port: [id] unique while connected, [device] the MidiDeviceInfo id, [port] its port number */
data class OmxPort(val id: Int, val name: String, val device: Int = 0, val port: Int = 0)

data class OmxPair(val input: OmxPort, val output: OmxPort) {
    val name: String get() = output.name
}

/** an output whose name says OMX and the input of the same name (else the first OMX input) */
fun omxPair(inputs: List<OmxPort>, outputs: List<OmxPort>): OmxPair? {
    val outs = outputs.filter { OmxWire.isOmxPortName(it.name) }
    val ins = inputs.filter { OmxWire.isOmxPortName(it.name) }
    for (o in outs) {
        val i = ins.firstOrNull { it.name == o.name } ?: ins.firstOrNull()
        if (i != null) return OmxPair(i, o)
    }
    return null
}

interface OmxTransport {
    /** starts (setup changes come to onSetupChange, on the main thread); null when it runs, else why not */
    fun start(onSetupChange: () -> Unit): String?
    /** no more setup changes, nothing listened to */
    fun stop()
    /** the ports there now: the ones to read, the ones to send to */
    fun ports(): Pair<List<OmxPort>, List<OmxPort>>
    /** listens to input (each SysEx whole, F0..F7, on the main thread) and sends to output from now on */
    fun open(input: OmxPort, output: OmxPort, onMessage: (ByteArray) -> Unit)
    /** stops listening and sending */
    fun close()
    /** a whole message to the output, delayMs from now */
    fun send(bytes: ByteArray, delayMs: Double)
    /** why the ports could not be opened (Android: another app has them), null: no problem known */
    val problem: String? get() = null
}

interface OmxTimer { fun cancel() }

interface OmxClock {
    /** milliseconds, any origin */
    val now: Double
    fun after(ms: Double, f: () -> Unit): OmxTimer
    fun every(ms: Double, f: () -> Unit): OmxTimer
}

/** timers on the main looper */
class OmxMainClock : OmxClock {
    private val h = Handler(Looper.getMainLooper())
    override val now: Double get() = SystemClock.uptimeMillis().toDouble()

    private inner class T(val ms: Long, val repeat: Boolean, val f: () -> Unit) : OmxTimer, Runnable {
        @Volatile var dead = false
        override fun run() {
            if (dead) return
            if (repeat) h.postDelayed(this, ms) else dead = true
            f()
        }
        override fun cancel() { dead = true; h.removeCallbacks(this) }
    }

    override fun after(ms: Double, f: () -> Unit): OmxTimer = T(maxOf(0L, ms.toLong()), false, f).also { h.postDelayed(it, it.ms) }
    override fun every(ms: Double, f: () -> Unit): OmxTimer = T(maxOf(1L, ms.toLong()), true, f).also { h.postDelayed(it, it.ms) }
}

/** SysEx from a byte stream that may split it: each whole message F0..F7 (real-time bytes inside are skipped; any
 *  other status byte drops the one being gathered) */
class OmxSysexAssembler {
    private val buf = java.io.ByteArrayOutputStream()
    private var inside = false

    fun feed(bytes: ByteArray, off: Int = 0, len: Int = bytes.size - off): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (i in off until off + len) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0xF0) { buf.reset(); buf.write(b); inside = true; continue }
            if (!inside) continue
            when {
                b == 0xF7 -> { buf.write(b); out.add(buf.toByteArray()); buf.reset(); inside = false }
                b >= 0xF8 -> {}
                b >= 0x80 -> { buf.reset(); inside = false }
                buf.size() < MAX_LENGTH -> buf.write(b)
                else -> { buf.reset(); inside = false }
            }
        }
        return out
    }

    companion object { const val MAX_LENGTH = 4096 }
}

/** Android's MidiManager: the devices' ports; one device (or two) opened for the pair */
class OmxAndroidMidi(ctx: Context) : OmxTransport {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sender = HandlerThread("RFMOmxSend").apply { start() }
    private val out = Handler(sender.looper)
    private var mm: MidiManager? = null
    private var cb: MidiManager.DeviceCallback? = null
    private var onSetup: (() -> Unit)? = null
    private val devices = HashMap<Int, MidiDevice>()
    private var reading: MidiOutputPort? = null
    @Volatile private var writing: MidiInputPort? = null
    @Volatile private var generation = 0
    @Volatile private var opened = false
    @Volatile override var problem: String? = null
        private set

    override fun start(onSetupChange: () -> Unit): String? {
        onSetup = onSetupChange
        if (cb != null) return null
        val m = app.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return "This device has no MIDI service."
        mm = m
        val c = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) { Log.i(TAG, "added ${nameOf(info)}"); onSetup?.invoke() }
            override fun onDeviceRemoved(info: MidiDeviceInfo) { Log.i(TAG, "removed ${nameOf(info)}"); onSetup?.invoke() }
        }
        if (Build.VERSION.SDK_INT >= 33) m.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, Executor { main.post(it) }, c)
        else @Suppress("DEPRECATION") m.registerDeviceCallback(c, main)
        cb = c
        // (a device added while the callback was being registered: looked for once more)
        main.postDelayed({ onSetup?.invoke() }, 500)
        return null
    }

    override fun stop() {
        onSetup = null
        close()
        cb?.let { runCatching { mm?.unregisterDeviceCallback(it) } }
        cb = null
    }

    private fun infos(): Collection<MidiDeviceInfo> {
        val m = mm ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33) m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
        else @Suppress("DEPRECATION") m.devices.toList()
    }

    private fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return listOf(p.getString(MidiDeviceInfo.PROPERTY_NAME), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
            .firstOrNull { !it.isNullOrBlank() }?.trim() ?: "MIDI device ${info.id}"
    }

    override fun ports(): Pair<List<OmxPort>, List<OmxPort>> {
        val ins = ArrayList<OmxPort>()
        val outs = ArrayList<OmxPort>()
        for (info in infos()) {
            val n = nameOf(info)
            for (p in info.ports) {
                val port = OmxPort(info.id * 64 + p.portNumber * 2 + if (p.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) 0 else 1,
                                   n, info.id, p.portNumber)
                if (p.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) ins.add(port) else outs.add(port)
            }
        }
        Log.i(TAG, "ports: in ${ins.map { it.name }}, out ${outs.map { it.name }}")
        return ins.sortedBy { it.id } to outs.sortedBy { it.id }
    }

    override fun open(input: OmxPort, output: OmxPort, onMessage: (ByteArray) -> Unit) {
        close()
        val m = mm ?: return
        val gen = generation
        problem = null
        opened = true
        val byId = infos().associateBy { it.id }
        val asm = OmxSysexAssembler()
        // one open per device (the pair is usually one device), tried again a few times: a device just added (a
        // virtual one still binding, a USB one settling) may not open at first
        val waiting = HashMap<Int, ArrayList<(MidiDevice) -> Unit>>()
        fun openNow(id: Int, tries: Int) {
            val info = byId[id] ?: return
            m.openDevice(info, { d ->
                if (gen != generation) { runCatching { d?.close() }; return@openDevice }
                if (d == null) {
                    if (tries > 1) main.postDelayed({ if (gen == generation) openNow(id, tries - 1) }, 400)
                    else problem = "Android could not open it (another app may have it)."
                    return@openDevice
                }
                devices[id] = d
                waiting.remove(id)?.forEach { it(d) }
            }, main)
        }
        fun withDevice(id: Int, f: (MidiDevice) -> Unit) {
            devices[id]?.let { return f(it) }
            waiting[id]?.let { it.add(f); return }
            waiting[id] = arrayListOf(f)
            openNow(id, 4)
        }
        withDevice(input.device) { d ->
            val r = runCatching { d.openOutputPort(input.port) }.getOrNull()
            if (r == null) { problem = "its MIDI out would not open (another app may have it)."; return@withDevice }
            reading = r
            r.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    val whole = synchronized(asm) { asm.feed(msg, offset, count) }
                    if (whole.isNotEmpty()) main.post { if (gen == generation) whole.forEach(onMessage) }
                }
            })
        }
        withDevice(output.device) { d ->
            val w = runCatching { d.openInputPort(output.port) }.getOrNull()
            if (w == null) { problem = "its MIDI in is in use by another app."; Log.w(TAG, "${output.name}: input port busy"); return@withDevice }
            writing = w
            // (what was sent while the device opened: now, in order)
            val q = synchronized(early) { early.toList().also { early.clear() } }
            q.forEach { send(it.first, it.second) }
        }
    }

    private val early = ArrayList<Pair<ByteArray, Double>>()   // sends before the port opened (this pair's)

    override fun close() {
        generation++
        opened = false
        synchronized(early) { early.clear() }
        reading?.let { runCatching { it.close() } }
        reading = null
        val w = writing
        writing = null
        // (messages already queued still go out: the port is closed after them)
        out.post { w?.let { runCatching { it.close() } } }
        val ds = devices.values.toList()
        devices.clear()
        out.post { ds.forEach { runCatching { it.close() } } }
    }

    override fun send(bytes: ByteArray, delayMs: Double) {
        val w = writing
        if (w == null) {
            if (opened) synchronized(early) { if (early.size < 256) early.add(bytes to delayMs) }
            return
        }
        val r = Runnable { runCatching { w.send(bytes, 0, bytes.size) } }
        if (delayMs > 0) out.postDelayed(r, delayMs.toLong().coerceAtLeast(1)) else out.post(r)
    }

    companion object { const val TAG = "RFMOmx" }
}
