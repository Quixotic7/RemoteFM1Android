// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

/**
 * The Bluetooth page's scan (Android has no system MIDI pairing panel like iOS's CABTMIDICentralViewController):
 * Bluetooth LE devices advertising the BLE-MIDI service, for [SCAN_MS]. Needs BLUETOOTH_SCAN (Android 12+, declared
 * neverForLocation) or the fine location permission (Android 10 and 11); [permissions] says which to ask for.
 */
class BleScanner(private val ctx: Context, private val onChange: (List<Found>) -> Unit) {
    data class Found(val device: BluetoothDevice, val name: String, val address: String, val rssi: Int) {
        val isFm1: Boolean get() = Fm1Candidate.nameSaysFm1(name)
    }

    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()
    var scanning = false
        private set
    var error: String? = null
        private set

    private val cb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull() ?: ""
            found[r.device.address] = Found(r.device, name, r.device.address, r.rssi)
            main.post { onChange(found.values.toList()) }
        }

        override fun onScanFailed(errorCode: Int) {
            error = "scan failed ($errorCode)"
            scanning = false
            Log.w(TAG, "scan failed: $errorCode")
            main.post { onChange(found.values.toList()) }
        }
    }

    /** What is missing to scan and connect ([] none). */
    fun missing(): List<String> = permissions().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    fun start() {
        error = null
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            error = "Bluetooth is off"
            onChange(found.values.toList())
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            error = "no Bluetooth LE scanner"
            onChange(found.values.toList())
            return
        }
        if (missing().isNotEmpty()) {
            error = "permission needed"
            onChange(found.values.toList())
            return
        }
        found.clear()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, cb)
            scanning = true
            main.postDelayed({ stop() }, SCAN_MS)
        } catch (e: SecurityException) {
            error = "permission refused"
        }
        onChange(found.values.toList())
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        scanning = false
        runCatching {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        }
        onChange(found.values.toList())
    }

    companion object {
        private const val TAG = "RFMBle"
        const val SCAN_MS = 15000L
        val MIDI_SERVICE: java.util.UUID = java.util.UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")

        fun permissions(): List<String> =
            if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
