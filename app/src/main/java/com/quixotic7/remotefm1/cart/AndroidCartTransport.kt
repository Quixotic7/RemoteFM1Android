// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Cart/CartTransport+App.swift (CoreMIDICartTransport);
// changes: android.media.midi instead of CoreMIDI. The FM-1's MIDI devices are the link's candidates (Fm1Candidate: never
// the updater's port, never another device), USB and Bluetooth (a Bluetooth unit is listed once the app has opened it:
// the Bluetooth page). An input port is one client's at a time on Android, and the app's link holds the FM-1's while it
// runs, so every request goes while the panel is paused (sharesPanelPort); the port is opened at the first send and
// closed at done(). Writes go in parts of at most 1000 bytes (MidiInputPort.send's limit). The replies (0.3.1's
// SA_INFO_R) come from the device's output port, which can be shared: connected while the sender lives, SysEx put back
// together by SysexAssembler.
package com.quixotic7.remotefm1.cart

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.OpenableColumns
import android.util.Log
import com.quixotic7.remotefm1.Fm1Candidate
import com.quixotic7.remotefm1.MidiDesc
import com.quixotic7.remotefm1.install.SysexAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

class AndroidCartTransport(context: Context, private val virtualName: String = "") : CartTransport {
    private val mm = context.applicationContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val thread = HandlerThread("RFMCartMidi").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    @Volatile private var heard: ((ByteArray) -> Unit)? = null

    private class Open(val dev: MidiDevice, var input: MidiInputPort?, var output: MidiOutputPort?, var receiver: MidiReceiver?)
    private val open = HashMap<Int, Open>()             // by MidiDeviceInfo.id
    private val cands = HashMap<Int, Pair<MidiDeviceInfo, Pair<Int, Int>>>()   // id -> info, (inPort, outPort)

    override val sharesPanelPort: Boolean get() = true

    override fun listen(handler: (ByteArray) -> Unit) {
        heard = handler
    }

    private fun describe(info: MidiDeviceInfo): MidiDesc {
        val p = info.properties
        val ins = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }.sortedBy { it.portNumber }.map { it.name }
        val outs = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.sortedBy { it.portNumber }.map { it.name }
        val usb = if (Build.VERSION.SDK_INT >= 33)
            p.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, android.hardware.usb.UsbDevice::class.java)
        else @Suppress("DEPRECATION") p.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE)
        return MidiDesc(
            id = info.id, type = info.type, name = p.getString(MidiDeviceInfo.PROPERTY_NAME),
            manufacturer = p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER), product = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            usbVid = usb?.vendorId, usbPid = usb?.productId, usbName = usb?.let { runCatching { it.productName }.getOrNull() },
            inputPorts = if (ins.size == info.inputPortCount) ins else List(info.inputPortCount) { null },
            outputPorts = if (outs.size == info.outputPortCount) outs else List(info.outputPortCount) { null },
        )
    }

    override fun ports(): List<CartPort> {
        val m = mm ?: return emptyList()
        val all: Collection<MidiDeviceInfo> = if (Build.VERSION.SDK_INT >= 33)
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM) else @Suppress("DEPRECATION") m.devices.toList()
        val out = mutableListOf<CartPort>()
        synchronized(lock) {
            cands.clear()
            for (info in all) {
                val d = describe(info)
                val c = Fm1Candidate.check(d, virtualName)
                if (!c.candidate) continue
                cands[info.id] = info to (c.inPort to c.outPort)
                out.add(CartPort(info.id.toString(), d.displayName, info.type == MidiDeviceInfo.TYPE_BLUETOOTH))
            }
            // a device gone (unplugged, restarted): let go of what was open on it
            for (id in open.keys.filter { it !in cands }) {
                val o = open.remove(id) ?: continue
                runCatching { o.receiver?.let { r -> o.output?.disconnect(r) } }
                runCatching { o.output?.close() }
                runCatching { o.input?.close() }
                runCatching { o.dev.close() }
            }
        }
        for (p in out) handler.post { connectOutput(p.id.toInt()) }
        return out
    }

    private suspend fun device(id: Int): Open {
        synchronized(lock) { open[id] }?.let { return it }
        val m = mm ?: throw IOException("no MIDI service")
        val info = synchronized(lock) { cands[id]?.first } ?: throw IOException("the FM-1 has gone")
        val dev: MidiDevice = suspendCancellableCoroutine { k ->
            m.openDevice(info, { d -> if (k.isActive) k.resume(d) else runCatching { d?.close() } }, handler)
        } ?: throw IOException("the FM-1 couldn't be opened")
        return synchronized(lock) {
            open[id] ?: Open(dev, null, null, null).also { open[id] = it }
        }.also { if (it.dev !== dev) runCatching { dev.close() } }
    }

    /** the replies: the output port connected (shared with the link, which also reads it) */
    private fun connectOutput(id: Int) {
        val m = mm ?: return
        if (synchronized(lock) { open[id]?.output } != null) return
        val (info, ports) = synchronized(lock) { cands[id] } ?: return
        m.openDevice(info, { dev ->
            if (dev == null) return@openDevice
            val o = synchronized(lock) { open[id] ?: Open(dev, null, null, null).also { open[id] = it } }
            if (o.dev !== dev) { runCatching { dev.close() } }
            if (o.output != null) return@openDevice
            val port = o.dev.openOutputPort(ports.second) ?: return@openDevice
            val asm = SysexAssembler({ msg -> heard?.invoke(msg) })
            val r = object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = asm.feed(msg, offset, count)
            }
            port.connect(r)
            synchronized(lock) { o.output = port; o.receiver = r }
        }, handler)
    }

    override suspend fun send(bytes: ByteArray, port: CartPort) {
        val id = port.id.toIntOrNull() ?: throw IOException("no such FM-1")
        val o = device(id)
        val input = synchronized(lock) { o.input } ?: run {
            val inPort = synchronized(lock) { cands[id]?.second?.first } ?: throw IOException("the FM-1 has gone")
            val p = o.dev.openInputPort(inPort)
                ?: throw IOException("the FM-1's MIDI port is in use (another app, or the panel still has it)")
            synchronized(lock) { o.input = p }
            p
        }
        withContext(Dispatchers.IO) {
            var off = 0
            while (off < bytes.size) {
                val n = minOf(CHUNK, bytes.size - off)
                input.send(bytes, off, n)
                off += n
            }
        }
    }

    override fun done() {
        synchronized(lock) {
            for (o in open.values) {
                o.input?.let { runCatching { it.close() } }
                o.input = null
            }
        }
    }

    /** lets go of everything (the app's end) */
    fun close() {
        synchronized(lock) {
            for (o in open.values) {
                runCatching { o.receiver?.let { r -> o.output?.disconnect(r) } }
                runCatching { o.output?.close() }
                runCatching { o.input?.close() }
                runCatching { o.dev.close() }
            }
            open.clear()
        }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "RFMCart"
        const val CHUNK = 1000

        /** an .syx opened from a file manager, a download or a share (Open with RemoteFM1) */
        suspend fun read(context: Context, uri: Uri): CartFile = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val name = runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "cartridge.syx"
            try {
                val data = cr.openInputStream(uri)?.use { s ->
                    // (a cartridge file is a few KB; more than 1 MB isn't one)
                    val o = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        if (o.size() > 1_048_576) throw IOException("it is larger than 1 MB")
                    }
                    o.toByteArray()
                } ?: throw IOException("it can't be opened")
                CartFile.of(name, data)
            } catch (e: Exception) {
                Log.w(TAG, "$uri: $e")
                CartFile.unreadable(name, e.message ?: e.toString())
            }
        }
    }
}
