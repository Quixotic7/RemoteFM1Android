// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Install/FirmwareInstaller.swift (and the factory and timing
// of FirmwareInstaller+App.swift, the transports of InstallTransport.swift); changes: Kotlin, one StateFlow
// (InstallerState) instead of @Observable, suspend functions, no AU lease (Android has no other instance: panelHolder
// is null unless the app says otherwise).
// Installing RemoteFM1 on the FM-1 with FM1Kit's protocol (ported, MIT, fm1kit/) instead of the website's installer:
//
//   idle       a release and its package are chosen (default: the newest, its Bluetooth package)
//   preparing  the package downloaded and checked (FirmwarePackages: SHA-256, FwscPackage, the identity); then the
//              app's panel lets go of the FM-1 (PanelLinkPausing: its session ended, its MIDI ports closed); then FM1Kit
//              asks the FM-1 what it runs (identify: a unit in its loader is offered the resume; check)
//   ready      what is on the FM-1, what will be installed; a firmware switch (RemoteFM1 0.2.2 -> 0.2.3 is one to
//              FM1Kit) or a reinstall needs confirming
//   installing FM1Kit's install (or resume), its events folded by InstallProgress; the screen kept awake (InstallAwake)
//   finished   the identity the FM-1 reports now; the panel back (its session restarted if it was running)
//   failed     FM1Advice's words; "Finish the install" (resume, the same package) when it was cut off
//
// Leaving the page (leave()) gives the FM-1 back to the panel, except while it waits in its loader.
package com.quixotic7.remotefm1.install

import com.quixotic7.remotefm1.UnitHello
import com.quixotic7.remotefm1.install.fm1kit.FM1Advice
import com.quixotic7.remotefm1.install.fm1kit.FM1Identity
import com.quixotic7.remotefm1.install.fm1kit.FM1UpdateError
import com.quixotic7.remotefm1.install.fm1kit.FM1Updater
import com.quixotic7.remotefm1.install.fm1kit.FwscPackage
import com.quixotic7.remotefm1.install.fm1kit.InstallKind
import com.quixotic7.remotefm1.install.fm1kit.InstallOptions
import com.quixotic7.remotefm1.install.fm1kit.InstallProgress
import com.quixotic7.remotefm1.install.fm1kit.MIDITransport
import com.quixotic7.remotefm1.install.fm1kit.SimulatedFM1
import com.quixotic7.remotefm1.install.fm1kit.UpdateEvent
import com.quixotic7.remotefm1.install.fm1kit.UpdaterTiming
import com.quixotic7.remotefm1.install.fm1kit.refusesV15OverOtherBootloaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the installer (and the cartridge sender, CartPanelPausing) needs from the app's own link to the FM-1. The app's
 * link (RfmLink through AppModel) implements it; a fake in the tests.
 */
interface PanelLinkPausing {
    /** another instance holds the FM-1: its name ("" unnamed); null: nobody else (always, on Android today) */
    val panelHolder: String? get() = null

    /** the unit's HELLO_R, when the link has one */
    val unitHello: UnitHello?

    /** ends the panel's session and closes its MIDI ports to the FM-1, and keeps them closed (no reopen, no rescan
     *  hand-down) until resumeAfterInstall; returns whether the panel was running */
    suspend fun pauseForInstall(): Boolean

    /** lets go of the FM-1 again; restart: the panel's session starts again (and rescans: new MIDI devices) */
    fun resumeAfterInstall(restart: Boolean)
}

/** what installing the chosen package would do (from FM1Kit's check) */
data class InstallSummary(
    val deviceIdentity: String,
    val deviceName: String,
    val kind: Kind,
    val pkg: FirmwarePackageRef,
    val size: Int,
    /** the package's firmware refuses V15 once the FM-1's bootloader was changed (FM1Kit's check) */
    val refusesV15: Boolean,
) {
    sealed class Kind {
        /** another RemoteFM1 release is on it (FM1Kit: a firmware switch) */
        data class RemoteFM1(val from: RemoteFM1Version) : Kind()
        /** another firmware: switching can erase its projects and samples */
        data object Switching : Kind()
        /** this release is already on it: a reinstall */
        data object SameIdentity : Kind()
        /** FM1Kit won't (V15 over itself) */
        data object Reinstall : Kind()
        /** the FM-1 waits in its loader: finish the install (resume) */
        data object MidUpdate : Kind()
    }

    val needsConfirm: Boolean get() = kind is Kind.RemoteFM1 || kind == Kind.Switching || kind == Kind.SameIdentity
    val canInstall: Boolean get() = kind != Kind.Reinstall

    companion object {
        /** a firmware's name from what the FM-1 reports */
        fun nameOf(identity: String): String {
            RemoteFM1Version.ofIdentity(identity)?.let { return "RemoteFM1 $it" }
            if (identity == "FM-1_015") return "M-VAVE V15 (or RemoteFM1 0.1)"
            if (RemoteFM1Version.isDevBuild(identity)) return "a RemoteFM1 development build"
            FM1Identity.parse(identity)?.let {
                if (it.isLoader) return "its update loader (part-way through an update)"
                if (it.isOpenFirmware) return "M-VAVE's open firmware"
            }
            return "another firmware"
        }
    }
}

/** a problem, in words (FM1Advice for FM1Kit's errors) */
data class InstallProblem(
    val headline: String,
    val explanation: String,
    /** the FM-1 still works as before */
    val deviceFine: Boolean,
    /** cut off: FM1Kit's resume, with the same package */
    val canResume: Boolean,
    val details: String,
)

/** the screen kept awake during an install (FLAG_KEEP_SCREEN_ON; the app's foreground service keeps it running) */
class InstallAwake(val begin: () -> Unit, val end: () -> Unit) {
    companion object {
        val none = InstallAwake({}, {})
    }
}

/** makes (or reuses) the transport for one package; called before each check, install or resume */
fun interface InstallTransportFactory {
    fun make(pkg: FwscPackage): MIDITransport
}

object InstallTransport {
    /** a SimulatedFM1, made once and prepared for each package (so a cut-off install can be resumed on it) */
    fun simulated(options: SimulatedFM1.Options = SimulatedFM1.Options()): InstallTransportFactory {
        var device: SimulatedFM1? = null
        return InstallTransportFactory { pkg ->
            val d = device ?: SimulatedFM1(options).also { device = it }
            d.prepare(pkg)
            d
        }
    }

    /** one transport for the installer's life (Android MIDI, USB only: AndroidMidiTransport) */
    fun reusing(make: () -> MIDITransport): InstallTransportFactory {
        var t: MIDITransport? = null
        return InstallTransportFactory { t ?: make().also { t = it } }
    }

    /** a SimulatedFM1 running RemoteFM1 0.2.2 as felucca-bsp's loader behaves (the fake link, demos) */
    fun simulatedRemoteFM1(cutOffAt: Double? = null): SimulatedFM1.Options = SimulatedFM1.Options().apply {
        installedIdentity = FM1Identity.parse(RemoteFM1Version(0, 2, 2).identity)!!
        serialNumber = null                     // (felucca-bsp's USB device reports none)
        checksAfterWriting = true               // (its loader reads the image again)
        rebootDuration = 1200.milliseconds
        unplugDuringWriteAt = cutOffAt
    }

    /** shorter waits for the SimulatedFM1 (it restarts in about a second) */
    val simulatedTiming: UpdaterTiming
        get() = UpdaterTiming().apply {
            loaderBoot = 800.milliseconds
            firmwareBoot = 800.milliseconds
            scanInterval = 250.milliseconds
            loaderSearch = 10.seconds
            returnSearch = 10.seconds
        }
}

/** everything the install page shows */
data class InstallerState(
    val stage: FirmwareInstaller.Stage = FirmwareInstaller.Stage.idle,
    /** (preparing) what is happening */
    val activity: String = "",
    val summary: InstallSummary? = null,
    val problem: InstallProblem? = null,
    val progress: InstallProgress = InstallProgress(),
    /** (installing) "Writing 42 %" */
    val phase: String = "",
    /** (finished) the identity the FM-1 reports */
    val installed: String? = null,
    val panelPaused: Boolean = false,
    /** FM1Kit's events, oldest first (Diagnostics, tests) */
    val events: List<String> = emptyList(),
    val confirmed: Boolean = false,
    /** the package chosen; null: the newest release's default package */
    val choice: FirmwarePackageRef? = null,
) {
    val isBusy: Boolean get() = stage == FirmwareInstaller.Stage.preparing || stage == FirmwareInstaller.Stage.installing
    /** the choice is fixed: a cut-off install resumes with the same package */
    val choiceLocked: Boolean get() = isBusy || problem?.canResume == true || summary?.kind == InstallSummary.Kind.MidUpdate
}

class FirmwareInstaller(
    val releases: RemoteFM1Releases,
    private val packages: FirmwarePackages,
    private val panel: PanelLinkPausing?,
    private val transport: InstallTransportFactory,
    /** the FM-1 is FM1Kit's SimulatedFM1 (the fake link, tests, demos) */
    val simulated: Boolean,
    private val timing: UpdaterTiming = UpdaterTiming.standard,
    private val awake: InstallAwake = InstallAwake.none,
) {
    enum class Stage { idle, preparing, ready, installing, finished, failed }

    private val _state = MutableStateFlow(InstallerState())
    val state: StateFlow<InstallerState> = _state.asStateFlow()
    private val s get() = _state.value

    @Volatile private var restartPanel = false
    @Volatile private var verified: VerifiedPackage? = null

    // ---- what is chosen, what the FM-1 has

    val selected: FirmwarePackageRef? get() = s.choice ?: releases.newest?.pkg(FirmwarePackageRef.Kind.standard)
    val selectedRelease: RemoteFM1Release? get() = selected?.let { sel -> releases.releases.firstOrNull { it.version == sel.version } }
    val isBusy: Boolean get() = s.isBusy
    val choiceLocked: Boolean get() = s.choiceLocked

    fun select(ref: FirmwarePackageRef) {
        if (choiceLocked || ref == selected) return
        _state.update { it.copy(choice = ref) }
        reset()
    }

    fun setConfirmed(on: Boolean) = _state.update { it.copy(confirmed = on) }

    /** the RemoteFM1 version on the FM-1: HELLO_R's (the link's), else what FM1Kit last saw */
    val unitVersion: RemoteFM1Version?
        get() {
            panel?.unitHello?.let { h ->
                (RemoteFM1Version.ofIdentity(h.identity) ?: RemoteFM1Version.parse(h.firmwareVersion))?.let { return it }
            }
            return (s.installed ?: s.summary?.deviceIdentity)?.let { RemoteFM1Version.ofIdentity(it) }
        }

    /** a newer release than the FM-1's */
    val updateAvailable: RemoteFM1Release? get() = releases.update(unitVersion)

    val panelHolder: String? get() = panel?.panelHolder

    // ---- the flow

    /** the page appeared: the list refreshed (at most every 10 minutes) */
    suspend fun pageAppeared() = releases.refreshIfStale()

    /** downloads and checks the package, lets go of the FM-1, asks it what installing would do */
    suspend fun check() {
        if (isBusy) return
        val ref = selected ?: return
        _state.update {
            it.copy(problem = null, summary = null, installed = null, confirmed = false, stage = Stage.preparing,
                    activity = "Downloading ${ref.title}…")
        }
        try {
            val v = packages.fetch(ref)
            verified = v
            pausePanel()
            _state.update { it.copy(activity = "Asking the FM-1…") }
            val updater = makeUpdater(v)
            val id = updater.identify()
            val refuses = v.pkg.refusesV15OverOtherBootloaders
            if (id != null && id.isLoader) {
                val sum = InstallSummary(id.text, InstallSummary.nameOf(id.text), InstallSummary.Kind.MidUpdate, ref, v.size, refuses)
                _state.update { it.copy(summary = sum, stage = Stage.ready) }
                return
            }
            val c = updater.check(v.pkg)
            val kind = when (c.kind) {
                InstallKind.reinstall -> InstallSummary.Kind.Reinstall
                InstallKind.sameIdentity -> InstallSummary.Kind.SameIdentity
                InstallKind.update, InstallKind.switchingFirmware ->
                    RemoteFM1Version.ofIdentity(c.device.text)?.let { InstallSummary.Kind.RemoteFM1(it) } ?: InstallSummary.Kind.Switching
            }
            val sum = InstallSummary(c.device.text, InstallSummary.nameOf(c.device.text), kind, ref, v.size, refuses)
            _state.update { it.copy(summary = sum, stage = Stage.ready) }
        } catch (e: Throwable) {
            fail(e)
            if (e is CancellationException) throw e
        }
    }

    /** installs (or, on a unit waiting in its loader, finishes) the checked package */
    suspend fun install() {
        val st = s
        val sum = st.summary ?: return
        val v = verified ?: return
        if (st.stage != Stage.ready || !sum.canInstall || (sum.needsConfirm && !st.confirmed)) return
        val confirmed = st.confirmed
        run(v) { updater, sink ->
            if (sum.kind == InstallSummary.Kind.MidUpdate) updater.resume(v.pkg, sink)
            else updater.install(v.pkg, InstallOptions(confirmsSwitchingFirmware = confirmed, confirmsSameIdentity = confirmed), sink)
        }
    }

    /** a cut-off install: FM1Kit's resume with the same package */
    suspend fun resume() {
        val v = verified ?: return
        if (s.stage != Stage.failed || s.problem?.canResume != true) return
        run(v) { updater, sink -> updater.resume(v.pkg, sink) }
    }

    /** leaving the page: the FM-1 goes back to the panel (not while it waits in its loader); a result is forgotten */
    fun leave() {
        if (isBusy) return
        val midUpdate = s.problem?.let { !it.deviceFine } ?: (s.summary?.kind == InstallSummary.Kind.MidUpdate)
        if (midUpdate) return                   // (kept: the page offers the resume when it is opened again)
        if (s.panelPaused) resumePanel()
        reset()
    }

    /** back to the choice (a check's result dropped) */
    fun reset() {
        if (isBusy) return
        _state.update {
            it.copy(stage = Stage.idle, summary = null, problem = null, installed = null, confirmed = false,
                    progress = InstallProgress(), phase = "")
        }
    }

    // ---- internals

    private suspend fun run(v: VerifiedPackage, body: suspend (FM1Updater, (UpdateEvent) -> Unit) -> FM1Identity) {
        _state.update { it.copy(problem = null, progress = InstallProgress(), phase = "Getting ready", stage = Stage.installing) }
        awake.begin()
        try {
            pausePanel()
            val updater = makeUpdater(v)
            val id = body(updater) { e -> record(e) }
            record(UpdateEvent.Finished(id))
            _state.update { it.copy(installed = id.text, summary = null, stage = Stage.finished) }
            resumePanel()
        } catch (e: Throwable) {
            fail(e)
            if (e is CancellationException) throw e
        } finally {
            awake.end()
        }
    }

    private suspend fun pausePanel() {
        if (s.panelPaused) return
        _state.update { it.copy(activity = "Letting go of the FM-1…") }
        restartPanel = panel?.pauseForInstall() ?: false
        _state.update { it.copy(panelPaused = true) }
    }

    private fun resumePanel() {
        if (!s.panelPaused) return
        _state.update { it.copy(panelPaused = false) }
        panel?.resumeAfterInstall(restartPanel)
    }

    private fun makeUpdater(v: VerifiedPackage) = FM1Updater(transport.make(v.pkg), timing)

    internal fun record(e: UpdateEvent) {
        _state.update { st ->
            if (st.stage != Stage.installing) return@update st      // (an event after the end)
            var phase = st.phase
            var line: String? = null
            when (e) {
                is UpdateEvent.Connected -> line = "connected: ${e.identity}"
                is UpdateEvent.Staging -> {
                    phase = "Getting ready (the FM-1 checks the package)"
                    if (e.requests == 1) line = "staging"
                }
                UpdateEvent.WaitingForLoader -> { phase = "Restarting into its update loader"; line = "waiting for the loader" }
                is UpdateEvent.LoaderFound -> { phase = "Writing"; line = "loader: ${e.identity}" }
                is UpdateEvent.Writing -> phase = "Writing ${(e.fraction * 100).toInt()} %"
                is UpdateEvent.Checking -> phase = "Checking ${(e.fraction * 100).toInt()} %"
                UpdateEvent.Rebooting -> { phase = "Restarting with the new firmware"; line = "rebooting" }
                is UpdateEvent.Finished -> { phase = "Finished"; line = "finished: ${e.identity}" }
            }
            st.copy(progress = st.progress.record(e), phase = phase, events = if (line != null) logged(st.events, line) else st.events)
        }
    }

    private fun logged(events: List<String>, line: String): List<String> = (events + line).takeLast(40)

    internal fun fail(error: Throwable) {
        val p = problemFor(error)
        _state.update { it.copy(problem = p, stage = Stage.failed, events = logged(it.events, "failed: ${p.details}")) }
    }

    companion object {
        fun problemFor(error: Throwable): InstallProblem {
            val details = error.toString()
            return when (error) {
                is PackageProblem -> InstallProblem("This package was refused", error.description, true, false, details)
                is IOException, is InstallerFetchError, is ReleaseDecodeError -> InstallProblem(
                    "The download didn't work", RemoteFM1Releases.words(error) + " Nothing was sent to the FM-1.",
                    true, false, details)
                FM1UpdateError.DeviceNotFound -> InstallProblem("No FM-1 answered over USB",
                    "Connect the FM-1 with a USB cable (Bluetooth can't install firmware), switch it on, and try again. " +
                        "Close other apps that use the FM-1.", true, false, details)
                is FM1UpdateError.MultipleDevices -> InstallProblem("More than one FM-1 is connected",
                    "${error.count} FM-1s answered. Leave only the one to install on connected, then try again.",
                    true, false, details)
                else -> {
                    val a = FM1Advice.of(error)
                    var text = a.explanation
                    if (a.action == FM1Advice.Action.Resume) text += " \"Finish the install\" picks it up with the same package."
                    if (a.action == FM1Advice.Action.NeedsComputer) text += " That needs a computer (the FM-1's download mode)."
                    InstallProblem(a.headline, text, a.isDeviceFine, a.action == FM1Advice.Action.Resume, a.technicalDetails)
                }
            }
        }
    }
}
