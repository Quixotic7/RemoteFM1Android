// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Install/InstallPrompt.swift; changes: Kotlin; FM1Ports reads
// RfmMidi's device descriptions (MidiDesc) instead of CoreMIDI's list; times are ms; the prompter reads the app through
// InstallPromptSource and keeps "Not now" in a PromptMemory (SharedPreferences in the app); no view (the UI agent's).
// The app's prompt to put RemoteFM1 on an FM-1 (iOS PLAN.md section 13), so the install page is found. Nothing here
// sends MIDI: an FM-1 is seen in Android's MIDI device list, and RemoteFM1 is whatever answers the app's link's HELLO.
//
//   FM1Ports      each MIDI device that is an FM-1: Fm1Candidate's rule (the link sends HELLO to these), or a USB
//                 device named like an FM-1 with other USB IDs (another firmware: RemoteFM1 always has 1209:0001, so the
//                 link never talks to it). Bluetooth: by its name only
//   the prompt    install: a candidate has been there 6 s while the app's link searched and nothing answered ("isn't
//                 answering as RemoteFM1"), or a USB device with other IDs for 2 s ("isn't running RemoteFM1"); over
//                 Bluetooth only, it says a USB cable is needed. update: the unit answered with an older RemoteFM1 than
//                 releases.json's newest (a smaller notice)
//   never         while an install runs or the install page has the FM-1, while another instance holds the unit, or
//                 with the fake unit, unless a forced prompt is given (screenshots: install | unanswered | bluetooth | update)
// "Not now" is remembered per release version, so a newer release asks again.
package com.quixotic7.remotefm1.install

import com.quixotic7.remotefm1.Fm1Candidate
import com.quixotic7.remotefm1.MidiDesc
import com.quixotic7.remotefm1.UnitHello
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** an FM-1 in Android's MIDI device list */
data class FM1Port(
    val name: String,
    val bluetooth: Boolean,
    /** the link sends it HELLO (Fm1Candidate); false: a USB FM-1 with other USB IDs (another firmware) */
    val candidate: Boolean,
)

object FM1Ports {
    /** what a device is (null: not an FM-1) */
    fun classify(d: MidiDesc, virtualName: String = ""): FM1Port? {
        val bluetooth = d.type == MidiDesc.TYPE_BLUETOOTH
        val name = d.inputPorts.firstOrNull { !it.isNullOrBlank() } ?: d.displayName     // (iOS: the endpoint's name)
        if (Fm1Candidate.check(d, virtualName).candidate) return FM1Port(d.displayName, bluetooth, true)
        if (d.type != MidiDesc.TYPE_USB || d.usbVid == null) return null
        if (d.inputPorts.isEmpty() || d.outputPorts.isEmpty()) return null
        val all = (listOf(d.name, d.product, d.usbName) + d.inputPorts + d.outputPorts).filterNotNull()
        if (all.any { it.contains("update", ignoreCase = true) }) return null
        val names = all.joinToString(" ").lowercase()
        if (!names.contains("fm-1") && !names.contains("felucca")) return null
        return FM1Port(name, false, false)
    }

    fun scan(devices: List<MidiDesc>, virtualName: String = ""): List<FM1Port> = devices.mapNotNull { classify(it, virtualName) }
}

/** the prompt's rules, without a clock or MIDI of its own (tests drive it) */
class InstallPromptState {
    sealed class Prompt {
        data object None : Prompt()
        /** unanswered: a candidate the link sent HELLO and nothing answered; false: another firmware's USB IDs.
         *  bluetoothOnly: it must be connected with a USB cable to install */
        data class Install(val unanswered: Boolean, val bluetoothOnly: Boolean) : Prompt()
        /** a newer RemoteFM1 (its version) for the unit that answered */
        data class Update(val version: String, val bluetooth: Boolean) : Prompt()

        val isInstall: Boolean get() = this is Install

        val title: String
            get() = when (this) {
                None -> ""
                is Install -> if (unanswered) "This FM-1 isn't answering as RemoteFM1. Install it?"
                    else "This FM-1 isn't running RemoteFM1. Install it?"
                is Update -> "RemoteFM1 $version is available for this FM-1"
            }

        val detail: String
            get() = when (this) {
                None -> ""
                is Install -> (if (unanswered) "It may run another firmware. If it runs RemoteFM1, turn it off and on again. " else "") +
                    if (bluetoothOnly) "To install, connect it to this device with a USB cable."
                    else "RemoteFM1 lets this app use the FM-1's screen, keys and knobs. It installs over USB."
                is Update -> if (bluetooth) "Install it over a USB cable." else "Tap to see what's new and install it."
            }
    }

    data class Input(
        /** not the fake unit (or a forced prompt) */
        val enabled: Boolean = true,
        /** an install runs, or the install page has the FM-1 */
        val installing: Boolean = false,
        /** another instance holds the unit */
        val heldElsewhere: Boolean = false,
        /** the app's link is looking for a unit and nothing answers */
        val searching: Boolean = false,
        /** a RemoteFM1 unit answered HELLO (its session starts or runs) */
        val answered: Boolean = false,
        val unitVersion: RemoteFM1Version? = null,
        val unitOverBluetooth: Boolean = false,
        /** releases.json's newest installable release */
        val newest: RemoteFM1Version? = null,
        val ports: List<FM1Port> = emptyList(),
        /** the release "Not now" was said for ("" not known then) */
        val notNowInstall: String? = null,
        val notNowUpdate: String? = null,
    )

    var prompt: Prompt = Prompt.None
        private set
    private var candidateSince: Long? = null
    private var otherSince: Long? = null

    fun update(i: Input, nowMs: Long): Prompt {
        prompt = decide(i, nowMs)
        return prompt
    }

    private fun decide(i: Input, now: Long): Prompt {
        if (!i.enabled || i.installing || i.heldElsewhere) {
            candidateSince = null
            otherSince = null
            return Prompt.None
        }
        if (i.answered) {
            candidateSince = null
            otherSince = null
            val v = i.unitVersion ?: return Prompt.None
            val n = i.newest ?: return Prompt.None
            if (n <= v || i.notNowUpdate == n.toString()) return Prompt.None
            return Prompt.Update(n.toString(), i.unitOverBluetooth)
        }
        val cands = i.ports.filter { it.candidate }
        val others = i.ports.filter { !it.candidate }
        candidateSince = if (i.searching && cands.isNotEmpty()) candidateSince ?: now else null
        otherSince = if (others.isEmpty()) null else otherSince ?: now
        if (i.notNowInstall == (i.newest?.toString() ?: "")) return Prompt.None
        otherSince?.let { if (now - it >= OTHER_WAIT_MS) return Prompt.Install(unanswered = false, bluetoothOnly = false) }
        candidateSince?.let {
            if (now - it >= ANSWER_WAIT_MS) return Prompt.Install(unanswered = true, bluetoothOnly = cands.all { c -> c.bluetooth })
        }
        return Prompt.None
    }

    companion object {
        /** how long a candidate has to answer HELLO (a unit just plugged in boots first) */
        const val ANSWER_WAIT_MS = 6000L
        /** a device with other USB IDs: settled */
        const val OTHER_WAIT_MS = 2000L
    }
}

/** what the prompter reads from the app, about once a second (AppModel implements it) */
interface InstallPromptSource {
    /** the device backend (not the fake unit) */
    val real: Boolean
    /** another instance holds the unit (never on Android today) */
    val heldElsewhere: Boolean get() = false
    /** the link's state is connected or starting */
    val answered: Boolean
    /** the link runs and is searching, not found or lost */
    val searching: Boolean
    val hello: UnitHello?
    /** the FM-1s in the MIDI device list now (FM1Ports.scan of RfmMidi.entries()' descriptions) */
    fun ports(): List<FM1Port>
}

/** where "Not now" is kept (SharedPreferences in the app) */
interface PromptMemory {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/** the app's prompt: reads the link, the installer and the MIDI list about once a second */
class InstallPrompter(
    private val source: InstallPromptSource,
    private val installer: FirmwareInstaller,
    private val memory: PromptMemory,
    /** a forced prompt (screenshots: "install" | "unanswered" | "bluetooth" | "update"; null: none) */
    forced: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _prompt = MutableStateFlow<InstallPromptState.Prompt>(InstallPromptState.Prompt.None)
    val prompt: StateFlow<InstallPromptState.Prompt> = _prompt.asStateFlow()

    /** the app shows the install page (the installer has, or may take, the FM-1) */
    var installPageOpen = false
        set(v) {
            val changed = v != field
            field = v
            if (changed) tick()
        }

    private val machine = InstallPromptState()
    private var forcedPrompt: InstallPromptState.Prompt? = when (forced) {
        null -> null
        "unanswered" -> InstallPromptState.Prompt.Install(unanswered = true, bluetoothOnly = false)
        "bluetooth" -> InstallPromptState.Prompt.Install(unanswered = true, bluetoothOnly = true)
        "update" -> InstallPromptState.Prompt.Update(installer.releases.newest?.version ?: "0.2.3", false)
        else -> InstallPromptState.Prompt.Install(unanswered = false, bluetoothOnly = false)
    }
    private var ports: List<FM1Port> = emptyList()
    private var scanned = 0L
    private var fetched = false
    private var poller: Job? = null
    private var scope: CoroutineScope? = null

    /** polls once a second in [scope] (the app's main scope) */
    fun start(scope: CoroutineScope) {
        if (poller != null) return
        this.scope = scope
        poller = scope.launch {
            while (isActive) {
                tick()
                delay(1000)
            }
        }
    }

    fun stop() {
        poller?.cancel()
        poller = null
    }

    /** "Not now": this prompt stays away until a newer release */
    fun notNow() {
        when (val p = _prompt.value) {
            is InstallPromptState.Prompt.Install -> memory.set(NOT_NOW_INSTALL_KEY, installer.releases.newest?.version ?: "")
            is InstallPromptState.Prompt.Update -> memory.set(NOT_NOW_UPDATE_KEY, p.version)
            InstallPromptState.Prompt.None -> return
        }
        forcedPrompt = null
        _prompt.value = InstallPromptState.Prompt.None
        tick()
    }

    fun tick() {
        forcedPrompt?.let { f ->
            _prompt.value = if (installPageOpen || installer.isBusy) InstallPromptState.Prompt.None else f
            return
        }
        val now = clock()
        val real = source.real
        if (real && now - scanned >= 2000) {
            ports = source.ports()
            scanned = now
        }
        var unitVersion: RemoteFM1Version? = null
        var ble = false
        val answered = source.answered
        val h = source.hello
        if (answered && h != null) {
            unitVersion = RemoteFM1Version.ofIdentity(h.identity) ?: RemoteFM1Version.parse(h.firmwareVersion)
            ble = h.isBluetooth
            if (real && !fetched && unitVersion != null) {       // (the list once per launch, for the update notice)
                fetched = true
                scope?.launch { installer.releases.refreshIfStale(3_600_000) }
            }
        }
        val st = installer.state.value
        val i = InstallPromptState.Input(
            enabled = real,
            installing = installPageOpen || st.isBusy || st.panelPaused,
            heldElsewhere = source.heldElsewhere,
            searching = source.searching,
            answered = answered,
            unitVersion = unitVersion,
            unitOverBluetooth = ble,
            newest = installer.releases.newest?.parsedVersion,
            ports = ports,
            notNowInstall = memory.get(NOT_NOW_INSTALL_KEY),
            notNowUpdate = memory.get(NOT_NOW_UPDATE_KEY),
        )
        _prompt.value = machine.update(i, now)
    }

    companion object {
        const val NOT_NOW_INSTALL_KEY = "rfm1.installPrompt.notNow"
        const val NOT_NOW_UPDATE_KEY = "rfm1.updateNotice.notNow"
    }
}
