// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported in part from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMKit/AudioRoute.swift (the route's name, the USB hint,
// what a route change asks of the engine) and CorePlayer.swift (audioConfigurationChanged: a new stream at the new
// rate). Android has no system route picker, so the app lists AudioManager's outputs and opens the Oboe stream on
// the one picked, always by its device id: Android's own default would be the FM-1's USB audio as soon as it is
// plugged in, and sound to it over the Qualcomm USB offload restarted a Galaxy Tab A9+ (README "Known issues"). So:
// USB outputs are offered only with Settings › Sound through USB devices (experimental) on, and the sound never goes
// to one unless the user picked it (then shared, no MMAP); the pick is kept as a kind
// (speaker, headphones, Bluetooth; USB only until the app quits); plugging the FM-1 in or out never moves the sound
// to or from it; when the picked output goes, the sound goes to the speaker (or headphones, Bluetooth).
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
    /** what the picker remembers: [AudioRoute.SPEAKER], HEADPHONES, BLUETOOTH, USB, OTHER */
    val pickKind: String get() = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioRoute.HEADPHONES
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioRoute.BLUETOOTH
        in USB_TYPES -> AudioRoute.USB
        else -> AudioRoute.OTHER
    }
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

    const val AUTO = "auto"
    const val SPEAKER = "speaker"
    const val HEADPHONES = "headphones"
    const val BLUETOOTH = "bluetooth"
    const val USB = "usb"
    const val OTHER = "other"

    /** the warning with the experimental setting and the USB row (the owner's Galaxy Tab A9+, both FM-1 tests) */
    const val USB_WARNING = "On a Galaxy Tab A9+ sound through the FM-1's USB audio once restarted the tablet, and " +
        "later played nothing and made the FM-1 drop off USB. Use the speaker, headphones or Bluetooth."

    /** the picks the picker offers: Automatic, then each kind Android lists now (USB last) */
    fun kinds(outs: List<AudioOut>): List<String> =
        listOf(AUTO) + listOf(SPEAKER, HEADPHONES, BLUETOOTH, OTHER, USB).filter { k -> outs.any { it.pickKind == k } }

    /** the picker's chip for a kind: "Speaker", "FM-1 (USB)", "Galaxy Buds (Bluetooth)" */
    fun kindTitle(outs: List<AudioOut>, kind: String): String = when (kind) {
        AUTO -> "Automatic"
        SPEAKER -> "Speaker"
        else -> outs.firstOrNull { it.pickKind == kind }?.title ?: kind
    }

    /** what is saved: the kind, but never USB (a relaunch after a restart must not play to the FM-1 again) */
    fun saved(kind: String): String = if (kind == USB) AUTO else kind

    /**
     * The output to play on for the pick [kind]: that kind's device while Android lists it; otherwise (and for
     * Automatic) the first of wired headphones, Bluetooth, other non-USB outputs, the speaker. A USB output only
     * when [kind] is USB. Null: nothing but USB outputs, and USB not picked (the sound stays off).
     */
    fun choose(outs: List<AudioOut>, kind: String): AudioOut? {
        if (kind == USB) outs.firstOrNull { it.usb }?.let { return it }
        if (kind != AUTO && kind != USB) outs.firstOrNull { !it.usb && it.pickKind == kind }?.let { return it }
        return listOf(HEADPHONES, BLUETOOTH, OTHER, SPEAKER).firstNotNullOfOrNull { k -> outs.firstOrNull { !it.usb && it.pickKind == k } }
    }

    /** the outputs the stream must not land on: every USB output but the one chosen */
    fun forbidden(outs: List<AudioOut>, chosen: AudioOut?): IntArray =
        outs.filter { it.usb && it.id != chosen?.id }.map { it.id }.toIntArray()

    /**
     * After an output came or went, or the stream died: nothing while the sound is off; a new stream when it died,
     * never ran, or its device is gone; a move between the non-USB outputs when the pick says so (headphones plugged
     * in with Automatic); never to or from a USB output by itself (the FM-1 plugged in or out). Else only the names.
     */
    fun action(soundOn: Boolean, streamOpen: Boolean, streamDead: Boolean, playingOn: Int, outs: List<AudioOut>,
               chosen: AudioOut?): Action {
        if (!soundOn) return Action.NONE
        if (!streamOpen || streamDead) return Action.RESTART
        val now = outs.firstOrNull { it.id == playingOn } ?: return Action.RESTART
        if (chosen != null && !chosen.usb && !now.usb && chosen.id != now.id) return Action.RESTART
        return Action.RENAME
    }

    /** the row's value: the stream's device */
    fun name(outs: List<AudioOut>, playingOn: Int): String =
        outs.firstOrNull { it.id == playingOn }?.title ?: if (playingOn > 0) "device $playingOn" else "nothing"

    /** a note under the row when the sound goes to a USB sound card (the FM-1) */
    fun hint(outs: List<AudioOut>, playingOn: Int): String? =
        if (outs.firstOrNull { it.id == playingOn }?.usb == true) "The sound goes out of the FM-1's USB audio. $USB_WARNING"
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
