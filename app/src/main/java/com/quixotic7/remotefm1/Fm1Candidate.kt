// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Which Android MIDI devices may be an FM-1, and so get RemoteFM1's HELLO. FM1VST v0.6.2 plugin/RemoteMidi.c's rule
// (fm1_remote_midi_candidate, RemoteCandidates.h; native/rfmcore/RFMMidiMap.cpp has it for CoreMIDI's names) applied
// to what Android's MidiDeviceInfo tells:
//   - USB with the UsbDevice property: a candidate exactly when it is 1209:0001 (RemoteFM1's USB IDs, shared with
//     ChoralRoot / TransitFM1 on the same unit); without it (it is missing on some makers' builds): by name;
//   - Bluetooth: by name only ("FM-1 7938", as RemoteFM1 0.2+ advertises), from the MIDI device's name, the Bluetooth
//     device's name or the product;
//   - virtual (a MidiDeviceService): never, except the tests' loopback with exactly the name the link was given;
//   - never an updater: a device or port whose name contains "update" (felucca-bsp's loader), never a device without
//     an input and an output port.
// Names are matched tolerantly: "FM-1", "FM 1", "FM_1" or "FM1" (any case) not followed by another digit.
// Nothing here touches Android, so the JVM tests (Fm1CandidateTest) run it as it is.
package com.quixotic7.remotefm1

/** What MidiDeviceInfo says about one device (MidiDevices.describe makes it). */
data class MidiDesc(
    val id: Int,
    val type: Int,                          // TYPE_USB 1, TYPE_VIRTUAL 2, TYPE_BLUETOOTH 3
    val name: String? = null,               // PROPERTY_NAME
    val manufacturer: String? = null,       // PROPERTY_MANUFACTURER
    val product: String? = null,            // PROPERTY_PRODUCT
    val usbVid: Int? = null, val usbPid: Int? = null,   // PROPERTY_USB_DEVICE's
    val usbName: String? = null,            // the UsbDevice's product name
    val btName: String? = null,             // PROPERTY_BLUETOOTH_DEVICE's name (when allowed to read it)
    val btAddress: String? = null,
    val inputPorts: List<String?> = emptyList(),    // ports we send to (getInputPortCount), with their names
    val outputPorts: List<String?> = emptyList(),   // ports we read
) {
    val transportName: String
        get() = when (type) {
            TYPE_USB -> "USB"
            TYPE_BLUETOOTH -> "Bluetooth"
            TYPE_VIRTUAL -> "virtual"
            else -> "type $type"
        }

    /** The name shown and logged: the MIDI name, else the product, the Bluetooth name, "MIDI device <id>". */
    val displayName: String
        get() = listOf(name, product, usbName, btName).firstOrNull { !it.isNullOrBlank() }?.trim() ?: "MIDI device $id"

    /** One log line: everything Android reported (what an FM-1 on USB says is wanted for the rule). */
    val logLine: String
        get() = "#$id $transportName name=${q(name)} manufacturer=${q(manufacturer)} product=${q(product)}" +
            (if (usbVid != null) " usb=%04x:%04x usbName=%s".format(usbVid, usbPid ?: 0, q(usbName)) else " usb=none") +
            (if (type == TYPE_BLUETOOTH) " bt=${q(btName)} ${btAddress ?: ""}" else "") +
            " in=${inputPorts.map { q(it) }} out=${outputPorts.map { q(it) }}"

    companion object {
        const val TYPE_USB = 1
        const val TYPE_VIRTUAL = 2
        const val TYPE_BLUETOOTH = 3
        private fun q(s: String?) = if (s == null) "null" else "\"$s\""
    }
}

/** The rule's answer: a candidate with the ports to use, or not, and why (for Diagnostics and the log). */
data class Candidacy(val candidate: Boolean, val why: String, val inPort: Int = -1, val outPort: Int = -1)

object Fm1Candidate {
    const val USB_VID = 0x1209
    const val USB_PID = 0x0001
    const val VIRTUAL_NAME = "RemoteFM1 Fake FM-1"      // the debug build's loopback device (FakeFm1Service)

    private val fm1 = Regex("fm[-_ ]?1(?!\\d)", RegexOption.IGNORE_CASE)

    /** The name says FM-1 ("FM-1 7938", "FM-1", "ChoralRoot FM-1", "FM1"; not "FM10", not "FM-12"). */
    fun nameSaysFm1(s: String?): Boolean = s != null && fm1.containsMatchIn(s)

    private fun isUpdate(s: String?) = s != null && s.contains("update", ignoreCase = true)

    /** The first port whose name is not an updater's, -1 none. */
    private fun port(names: List<String?>): Int = names.indexOfFirst { !isUpdate(it) }

    /** [virtualName]: the name a test's loopback device must have ("" none: virtual devices are never candidates). */
    fun check(d: MidiDesc, virtualName: String = ""): Candidacy {
        val names = listOf(d.name, d.product, d.usbName, d.btName)
        if (names.any { isUpdate(it) }) return Candidacy(false, "an updater (its name says update)")
        val inPort = port(d.inputPorts)
        val outPort = port(d.outputPorts)
        if (inPort < 0 || outPort < 0) return Candidacy(false, "no input and output port (other than an updater's)")
        return when (d.type) {
            MidiDesc.TYPE_USB ->
                if (d.usbVid != null && d.usbPid != null) {
                    if (d.usbVid == USB_VID && d.usbPid == USB_PID) Candidacy(true, "USB 1209:0001", inPort, outPort)
                    else Candidacy(false, "USB %04x:%04x is not 1209:0001".format(d.usbVid, d.usbPid))
                } else if (names.any { nameSaysFm1(it) }) {
                    Candidacy(true, "USB without its IDs, named FM-1", inPort, outPort)
                } else {
                    Candidacy(false, "USB without its IDs, not named FM-1")
                }
            MidiDesc.TYPE_BLUETOOTH ->
                if (names.any { nameSaysFm1(it) }) Candidacy(true, "Bluetooth, named FM-1", inPort, outPort)
                else Candidacy(false, "Bluetooth, not named FM-1")
            MidiDesc.TYPE_VIRTUAL ->
                if (virtualName.isNotEmpty() && d.name == virtualName) Candidacy(true, "the test loopback", inPort, outPort)
                else Candidacy(false, "a virtual device")
            else -> Candidacy(false, "unknown type ${d.type}")
        }
    }
}

/**
 * The Bluetooth unit the app reopens: the last one connected is remembered; at launch it is opened again (when the
 * app may), and after it drops the app tries again, later each time (2, 5, 10, 20, 30 s, then every 30 s), while
 * the FM-1 is the sound source and no FM-1 is linked otherwise (USB). The user's Disconnect forgets nothing but stops
 * the retries until they connect again; Forget clears the address. Pure: the caller gives the clock.
 */
class ReconnectPolicy(var address: String? = null) {
    private var failures = 0
    private var nextAt = 0L
    var paused = false
        private set

    /** a unit connected (the page or a reopen): remembered, retries reset */
    fun connected(addr: String) {
        address = addr
        failures = 0
        paused = false
        nextAt = 0L
    }

    /** an open failed or the unit dropped at [now] */
    fun failed(now: Long) {
        nextAt = now + delayMs(failures)
        failures++
    }

    /** the user disconnected: no retries until connected() */
    fun pause() {
        paused = true
    }

    fun forget() {
        address = null
        failures = 0
        paused = false
    }

    /** whether to try opening [address] now: wanted (the FM-1 is the sound source), not already linked, not paused */
    fun shouldTry(now: Long, wanted: Boolean, linked: Boolean, opening: Boolean): Boolean =
        address != null && wanted && !linked && !opening && !paused && now >= nextAt

    val attempts: Int get() = failures

    companion object {
        private val delays = longArrayOf(2000, 5000, 10000, 20000, 30000)
        fun delayMs(failures: Int): Long = delays[failures.coerceIn(0, delays.size - 1)]
    }
}
