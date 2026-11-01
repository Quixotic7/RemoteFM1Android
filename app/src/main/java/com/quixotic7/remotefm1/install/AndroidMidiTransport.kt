// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Where the installer finds the FM-1 on Android: FM1Kit's MIDITransport over android.media.midi, USB devices only
// (what iOS's USBOnlyTransport is over CoreMIDI): no Bluetooth MIDI (too slow, and a dropped link mid-write leaves the
// FM-1 in its loader; a unit on both would also answer twice, which FM1Kit refuses), no virtual devices. Each device's
// input/output port pairs are listed as "<device name> <port name>", so FM1Kit's name rule picks the FM-1's ("FM-1",
// "Felucca Update"...). A pair is opened with its own MidiDevice: the input port is ours alone while open (the app's
// link must have closed its own: PanelLinkPausing). Writes go in parts of at most 1000 bytes (MidiInputPort.send's
// limit is 1015). SysexAssembler (also the cartridge sender's) puts F0 .. F7 back together.
package com.quixotic7.remotefm1.install

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
import android.util.Log
import com.quixotic7.remotefm1.install.fm1kit.MIDIConnection
import com.quixotic7.remotefm1.install.fm1kit.MIDIPortDescriptor
import com.quixotic7.remotefm1.install.fm1kit.MIDITransport
import com.quixotic7.remotefm1.install.fm1kit.MIDITransportError
import com.quixotic7.remotefm1.install.fm1kit.SysexInbox
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.time.Duration

/** Whole SysEx messages out of a MIDI byte stream (realtime bytes inside are skipped; another status ends one). */
class SysexAssembler(private val out: (ByteArray) -> Unit, private val max: Int = 65536) {
    private val buf = java.io.ByteArrayOutputStream()
    private var inSysex = false

    @Synchronized
    fun feed(b: ByteArray, offset: Int = 0, count: Int = b.size) {
        for (i in offset until offset + count) {
            val x = b[i].toInt() and 0xFF
            when {
                x == 0xF0 -> { buf.reset(); buf.write(x); inSysex = true }
                x == 0xF7 -> if (inSysex) {
                    buf.write(x)
                    inSysex = false
                    out(buf.toByteArray())
                    buf.reset()
                }
                x >= 0xF8 -> Unit
                x >= 0x80 -> { inSysex = false; buf.reset() }
                inSysex -> if (buf.size() < max) buf.write(x) else { inSysex = false; buf.reset() }
            }
        }
    }
}

/** android.media.midi, USB devices only (the installer's transport on a phone or tablet). */
class AndroidMidiTransport(context: Context) : MIDITransport {
    private val mm = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val thread = HandlerThread("RFMInstallMidi").apply { start() }
    private val handler = Handler(thread.looper)
    private val gone = java.util.Collections.synchronizedSet(HashSet<Int>())

    init {
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                gone.add(info.id)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            mm?.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, { handler.post(it) }, cb)
        } else {
            @Suppress("DEPRECATION") mm?.registerDeviceCallback(cb, handler)
        }
    }

    private fun devices(): List<MidiDeviceInfo> {
        val m = mm ?: return emptyList()
        val all: Collection<MidiDeviceInfo> = if (Build.VERSION.SDK_INT >= 33)
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM) else @Suppress("DEPRECATION") m.devices.toList()
        return all.filter { it.type == MidiDeviceInfo.TYPE_USB }
    }

    private fun name(info: MidiDeviceInfo): String {
        val p = info.properties
        return listOf(p.getString(MidiDeviceInfo.PROPERTY_NAME), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
            .firstOrNull { !it.isNullOrBlank() }?.trim() ?: "MIDI device ${info.id}"
    }

    override suspend fun ports(): List<MIDIPortDescriptor> {
        if (mm == null) throw MIDITransportError.Unavailable("no MIDI service")
        val out = mutableListOf<MIDIPortDescriptor>()
        for (info in devices()) {
            val ins = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }.sortedBy { it.portNumber }
            val outs = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.sortedBy { it.portNumber }
            val serial = info.properties.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER)
            for (k in 0 until minOf(ins.size, outs.size)) {
                val pn = ins[k].name.takeIf { it.isNotBlank() } ?: outs[k].name
                val n = listOf(name(info), pn).filter { it.isNotBlank() }.distinct().joinToString(" ")
                out.add(MIDIPortDescriptor("${info.id}:${ins[k].portNumber}:${outs[k].portNumber}", n, serial))
            }
        }
        return out
    }

    override suspend fun open(port: MIDIPortDescriptor): MIDIConnection {
        val m = mm ?: throw MIDITransportError.Unavailable("no MIDI service")
        val parts = port.id.split(":").mapNotNull { it.toIntOrNull() }
        if (parts.size != 3) throw MIDITransportError.PortGone
        val (id, inPort, outPort) = parts
        val info = devices().firstOrNull { it.id == id } ?: throw MIDITransportError.PortGone
        val dev: MidiDevice = suspendCancellableCoroutine { k ->
            m.openDevice(info, { d -> if (k.isActive) k.resume(d) else runCatching { d?.close() } }, handler)
        } ?: throw MIDITransportError.PortGone
        val input = dev.openInputPort(inPort)
        val output = dev.openOutputPort(outPort)
        if (input == null || output == null) {
            runCatching { input?.close() }
            runCatching { output?.close() }
            runCatching { dev.close() }
            Log.w(TAG, "${port.name}: its ports are in use")
            throw MIDITransportError.Unavailable("the FM-1's MIDI port is in use (another app, or the panel still has it)")
        }
        return Connection(port, id, dev, input, output)
    }

    private inner class Connection(override val port: MIDIPortDescriptor, val deviceId: Int, val dev: MidiDevice,
                                   val input: MidiInputPort, val output: MidiOutputPort) : MIDIConnection {
        private val inbox = SysexInbox()
        private val asm = SysexAssembler({ inbox.deliver(it) })
        @Volatile private var closed = false
        private val receiver = object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = asm.feed(msg, offset, count)
        }

        init {
            output.connect(receiver)
        }

        override val isLost: Boolean get() = closed || deviceId in gone

        override fun send(sysex: ByteArray) {
            if (isLost) throw MIDITransportError.PortGone
            try {
                var o = 0
                while (o < sysex.size) {
                    val n = minOf(CHUNK, sysex.size - o)
                    input.send(sysex, o, n)
                    o += n
                }
            } catch (e: IOException) {
                throw MIDITransportError.SendFailed(e.message ?: e.toString())
            } catch (e: IllegalArgumentException) {
                throw MIDITransportError.SendFailed(e.message ?: e.toString())
            }
        }

        override suspend fun receive(timeout: Duration): ByteArray? = inbox.next(timeout)
        override fun drain() = inbox.drain()

        override fun close() {
            if (closed) return
            closed = true
            inbox.close()
            runCatching { output.disconnect(receiver) }
            runCatching { output.close() }
            runCatching { input.close() }
            runCatching { dev.close() }
        }
    }

    companion object {
        private const val TAG = "RFMInstall"
        const val CHUNK = 1000
    }
}
