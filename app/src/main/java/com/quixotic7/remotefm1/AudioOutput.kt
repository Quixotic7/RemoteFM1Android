// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported in part from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMKit/AudioRoute.swift (the route's name, the USB hint,
// what a route change asks of the engine) and CorePlayer.swift (audioConfigurationChanged: a new stream at the new
// rate). Android has no system route picker, so the app lists AudioManager's outputs and opens the Oboe stream on
// the one picked (its device id; 0: the system default). With the FM-1 on USB it is also a USB sound card and
// Android may send the sound there; the picker chooses the tablet's speaker or headphones instead.
package com.quixotic7.remotefm1

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** One output Android offers. */
data class AudioOut(val id: Int, val name: String, val type: Int) {
    val usb: Boolean get() = type in USB_TYPES
    val kind: String get() = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "line"
        AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG -> "dock"
        else -> "type $type"
    }
    /** "Speaker", "FM-1 (USB)", "Galaxy Buds (Bluetooth)" */
    val title: String get() = if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "Speaker" else "$name ($kind)"

    companion object {
        val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        /** the outputs worth offering for music (no earpiece, telephony, buses, SCO, remote submix) */
        val MUSIC_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG,
        )
    }
}

/** What the route rules decide (pure: tested on the JVM). */
object AudioRoute {
    enum class Action { NONE, RENAME, RESTART }

    /**
     * After an output came or went: nothing while the sound is off; the stream reopened when it died (a disconnect)
     * or the device it was asked to play on is gone (then on the default); else only the names read again.
     */
    fun action(soundOn: Boolean, streamOpen: Boolean, streamDead: Boolean, requested: Int, present: Collection<Int>): Action {
        if (!soundOn) return Action.NONE
        if (!streamOpen || streamDead) return Action.RESTART
        if (requested != 0 && requested !in present) return Action.RESTART
        return Action.RENAME
    }

    /** the device to ask for: the picked one while Android still offers it, else the default (0) */
    fun device(requested: Int, present: Collection<Int>): Int = if (requested != 0 && requested in present) requested else 0

    /** the row's value: the stream's device, or what the picker asked for */
    fun name(outs: List<AudioOut>, playingOn: Int): String =
        outs.firstOrNull { it.id == playingOn }?.title ?: if (playingOn > 0) "device $playingOn" else "System default"

    /** a hint under the row when the sound goes to a USB sound card (the FM-1) */
    fun hint(outs: List<AudioOut>, playingOn: Int): String? =
        if (outs.firstOrNull { it.id == playingOn }?.usb == true)
            "The sound goes out of the FM-1's USB audio. Pick Audio output for the tablet's speaker or headphones."
        else null
}

/** AudioManager's outputs, kept current by an AudioDeviceCallback; [onChange] on the main thread. */
class AudioOutputs(ctx: Context, private val onChange: (List<AudioOut>) -> Unit) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    var list: List<AudioOut> = read()
        private set
    var changes = 0
        private set

    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed("added", added)
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed("removed", removed)
    }

    init {
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    }

    private fun changed(what: String, devs: Array<out AudioDeviceInfo>) {
        val outs = devs.filter { it.isSink }
        val now = read()
        if (outs.isEmpty() || now == list) return               // (registering reports what is there already)
        changes++
        list = now
        Log.i(TAG, "outputs $what: ${outs.joinToString { "${it.id} ${it.productName} (type ${it.type})" }}; now ${list.map { it.title }}")
        onChange(list)
    }

    private fun read(): List<AudioOut> = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .filter { it.type in AudioOut.MUSIC_TYPES }
        .map { AudioOut(it.id, it.productName?.toString()?.ifBlank { null } ?: "Output ${it.id}", it.type) }
        .distinctBy { it.id }

    fun close() = am.unregisterAudioDeviceCallback(cb)

    companion object {
        const val TAG = "RFMAudio"
    }
}
