// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor

/**
 * The FM-1 link's MIDI devices (the Kotlin half of native/rfmcore/android/rfm_android: what CoreMIDI's device list
 * is on iOS). MidiManager's byte-stream devices, now and as they come and go (registerDeviceCallback): each is
 * described (MidiDesc, logged under RFMMidi), checked by Fm1Candidate, and a candidate is opened and handed to the
 * native back end, whose link runs open their ports on it. Bluetooth LE MIDI devices are opened with
 * openBluetoothDevice (the Bluetooth page, or the remembered unit at launch). All of it runs on one HandlerThread.
 */
object RfmMidi {
    private const val TAG = "RFMMidi"

    /** One device as Diagnostics shows it. */
    data class Entry(val desc: MidiDesc, val candidacy: Candidacy, val nativeId: Int = -1, val opening: Boolean = false,
                     val error: String = "")

    private lateinit var ctx: Context
    private var mm: MidiManager? = null
    private val thread = HandlerThread("RFMMidi").apply { start() }
    private val handler = Handler(thread.looper)
    private val exec = Executor { handler.post(it) }
    private var started = false

    @Volatile var virtualName: String = ""
        private set
    private val entries = LinkedHashMap<Int, Entry>()          // by MidiDeviceInfo.id
    private val open = HashMap<Int, MidiDevice>()              // open devices (handed down or being)
    private val bleOpen = HashMap<String, MidiDevice>()        // openBluetoothDevice's, by address
    private val blePending = HashSet<String>()                 // openBluetoothDevice called, no answer yet
    private val listeners = mutableListOf<() -> Unit>()
    @Volatile var changes = 0
        private set

    /** Starts watching MIDI devices ([virtualName]: also the tests' loopback device of that name). */
    fun start(context: Context, virtualName: String = "") {
        handler.post {
            this.virtualName = virtualName
            if (started) {
                scanAll()
                return@post
            }
            ctx = context.applicationContext
            val m = ctx.getSystemService(Context.MIDI_SERVICE) as? MidiManager
            mm = m
            if (m == null) {
                Log.w(TAG, "no MIDI service")
                return@post
            }
            started = true
            val cb = object : MidiManager.DeviceCallback() {
                override fun onDeviceAdded(info: MidiDeviceInfo) = consider(info)
                override fun onDeviceRemoved(info: MidiDeviceInfo) = removed(info)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                m.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, exec, cb)
            } else {
                @Suppress("DEPRECATION")
                m.registerDeviceCallback(cb, handler)
            }
            scanAll()
        }
    }

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }

    private fun changed() {
        changes++
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    /** The devices seen, newest state (any thread). */
    fun entries(): List<Entry> = synchronized(entries) { entries.values.toList() }

    /** A candidate is open and handed to the link (any thread). */
    fun linked(): Boolean = entries().any { it.nativeId > 0 }

    private fun linked(id: Int): Boolean = synchronized(entries) { (entries[id]?.nativeId ?: -1) > 0 }

    fun linkedBluetooth(address: String): Boolean = entries().any { it.nativeId > 0 && it.desc.btAddress == address }

    private fun scanAll() {
        val m = mm ?: return
        val infos: Collection<MidiDeviceInfo> = if (Build.VERSION.SDK_INT >= 33)
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM) else @Suppress("DEPRECATION") m.devices.toList()
        infos.forEach { consider(it) }
    }

    private fun put(id: Int, e: Entry) {
        synchronized(entries) { entries[id] = e }
        changed()
    }

    /** MidiDeviceInfo -> MidiDesc (the rule's input; everything Android reports is logged) */
    fun describe(info: MidiDeviceInfo): MidiDesc {
        val p = info.properties
        val usb: UsbDevice? = if (Build.VERSION.SDK_INT >= 33)
            p.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") p.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE)
        val bt: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            p.getParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") p.getParcelable(MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE)
        val btName = if (bt != null && mayConnect()) runCatching { bt.name }.getOrNull() else null
        val ports = info.ports
        val ins = ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }.sortedBy { it.portNumber }.map { it.name }
        val outs = ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.sortedBy { it.portNumber }.map { it.name }
        val d = MidiDesc(
            id = info.id, type = info.type,
            name = p.getString(MidiDeviceInfo.PROPERTY_NAME),
            manufacturer = p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
            product = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            usbVid = usb?.vendorId, usbPid = usb?.productId,
            usbName = usb?.let { runCatching { it.productName }.getOrNull() },
            btName = btName, btAddress = bt?.address,
            inputPorts = if (ins.size == info.inputPortCount) ins else List(info.inputPortCount) { null },
            outputPorts = if (outs.size == info.outputPortCount) outs else List(info.outputPortCount) { null },
        )
        val extra = p.keySet().filter {
            it !in setOf(MidiDeviceInfo.PROPERTY_NAME, MidiDeviceInfo.PROPERTY_MANUFACTURER, MidiDeviceInfo.PROPERTY_PRODUCT,
                         MidiDeviceInfo.PROPERTY_USB_DEVICE, MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE)
        }.joinToString { k -> "$k=${p.get(k)}" }
        Log.i(TAG, "device ${d.logLine}" + (if (extra.isNotEmpty()) " other: $extra" else ""))
        return d
    }

    private fun mayConnect(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // (the handler thread)
    private fun consider(info: MidiDeviceInfo) {
        if (synchronized(entries) { entries[info.id]?.let { it.nativeId > 0 || it.opening } } == true) {
            Log.i(TAG, "#${info.id}: seen again, already ${if (linked(info.id)) "handed to the link" else "being opened"}")
            return
        }
        val d = describe(info)
        val c = Fm1Candidate.check(d, virtualName)
        Log.i(TAG, "#${d.id} ${d.displayName}: ${if (c.candidate) "candidate" else "not a candidate"} (${c.why})")
        if (!c.candidate) {
            put(info.id, Entry(d, c))
            return
        }
        // a Bluetooth device we are opening ourselves is handed down from that open
        if (d.btAddress != null && d.btAddress in blePending) {
            put(info.id, Entry(d, c))
            return
        }
        bleOpen[d.btAddress]?.let { dev ->
            if (dev.info.id == info.id) {
                handDown(info.id, d, c, dev)
                return
            }
        }
        put(info.id, Entry(d, c, opening = true))
        mm?.openDevice(info, { dev ->
            if (dev == null) {
                put(info.id, Entry(d, c, error = "could not open it"))
                Log.w(TAG, "#${d.id} ${d.displayName}: openDevice failed")
            } else {
                handDown(info.id, d, c, dev)
            }
        }, handler)
    }

    private fun handDown(id: Int, d: MidiDesc, c: Candidacy, dev: MidiDevice) {
        open[id] = dev
        val nid = nativeAddDevice(dev, d.displayName, d.type, c.inPort, c.outPort, virtualName)
        if (nid <= 0) {
            Log.w(TAG, "#$id ${d.displayName}: the native back end refused it")
            put(id, Entry(d, c, error = "AMidi could not take it"))
            return
        }
        Log.i(TAG, "#$id ${d.displayName}: handed to the link (native $nid; input port ${c.inPort} we send to, " +
            "output port ${c.outPort} we read)")
        put(id, Entry(d, c, nativeId = nid))
    }

    private fun removed(info: MidiDeviceInfo) {
        val e = synchronized(entries) { entries.remove(info.id) }
        Log.i(TAG, "#${info.id} removed (${e?.desc?.displayName})")
        if (e != null && e.nativeId > 0) nativeRemoveDevice(e.nativeId)   // (waits for the links on it to close)
        open.remove(info.id)?.let { runCatching { it.close() } }
        bleOpen.entries.removeAll { it.value.info.id == info.id }
        changed()
    }

    /** Opens a Bluetooth LE MIDI device (needs BLUETOOTH_CONNECT on Android 12+); [done] on the handler thread. */
    fun openBluetooth(device: BluetoothDevice, done: (Boolean) -> Unit) {
        handler.post {
            val m = mm
            if (m == null) {
                done(false)
                return@post
            }
            if (bleOpen.containsKey(device.address)) {
                done(true)
                return@post
            }
            if (!blePending.add(device.address)) return@post
            val t0 = SystemClock.elapsedRealtime()
            Log.i(TAG, "opening Bluetooth ${device.address}")
            try {
                m.openBluetoothDevice(device, { dev ->
                    blePending.remove(device.address)
                    if (dev == null) {
                        Log.w(TAG, "Bluetooth ${device.address}: open failed after ${SystemClock.elapsedRealtime() - t0} ms")
                        done(false)
                        return@openBluetoothDevice
                    }
                    Log.i(TAG, "Bluetooth ${device.address}: open in ${SystemClock.elapsedRealtime() - t0} ms, id ${dev.info.id}")
                    bleOpen[device.address] = dev
                    val info = dev.info
                    val d = describe(info)
                    val c = Fm1Candidate.check(d, virtualName)
                    Log.i(TAG, "#${d.id} ${d.displayName}: ${if (c.candidate) "candidate" else "not a candidate"} (${c.why})")
                    val have = synchronized(entries) { entries[info.id]?.nativeId ?: -1 } > 0
                    if (c.candidate && !have) handDown(info.id, d, c, dev) else if (!have) put(info.id, Entry(d, c))
                    done(true)
                }, handler)
            } catch (e: SecurityException) {
                blePending.remove(device.address)
                Log.w(TAG, "Bluetooth ${device.address}: $e")
                done(false)
            }
        }
    }

    /** Closes the Bluetooth device at [address] (the user's Disconnect). */
    fun closeBluetooth(address: String) {
        handler.post {
            val dev = bleOpen.remove(address) ?: return@post
            val id = dev.info.id
            val e = synchronized(entries) { entries.remove(id) }
            if (e != null && e.nativeId > 0) nativeRemoveDevice(e.nativeId)
            open.remove(id)
            runCatching { dev.close() }
            changed()
        }
    }

    fun bluetoothOpen(address: String): Boolean = bleOpen.containsKey(address)

    /** The native list (Diagnostics): [{"id","name","transport","in","out","linked","gone"}] */
    fun nativeList(): String = nativeDevices()

    init {
        System.loadLibrary("rfmcore")
    }

    @JvmStatic private external fun nativeAddDevice(device: MidiDevice, name: String, transport: Int, inPort: Int,
                                                    outPort: Int, virtualName: String): Int
    @JvmStatic private external fun nativeRemoveDevice(id: Int)
    @JvmStatic private external fun nativeTestFailWrites(n: Int)

    /** tests: the next [n] writes to any MIDI link fail (as a MIDI service that stopped taking bytes) */
    fun testFailWrites(n: Int) = nativeTestFailWrites(n)
    @JvmStatic private external fun nativeDevices(): String
}
