// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Updater/FM1Updater.swift, FM1Updater+Checks.swift,
// FM1Updater+Refusals.swift, InstallCheck.swift and UpdaterTiming.swift. Copyright (c) 2026 Drew Robinson, MIT licence
// (LICENSES/FM1Kit-MIT.txt). Kotlin port for RemoteFM1 Android: an ordinary class with suspend functions (FM1Kit's
// actor: one call at a time is the caller's job); voices, backups and the bootloader installer left out.
package com.quixotic7.remotefm1.install.fm1kit

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Timings for an update session (FM-1-RE's notes and M-VAVE's browser updater: each read answered at once). */
data class UpdaterTiming(
    var afterUpgradeCommand: Duration = Duration.ZERO,
    var stagingIdleLimit: Duration = 8.seconds,
    var loaderBoot: Duration = 3.seconds,
    var loaderSearch: Duration = 30.seconds,
    var writeIdleLimit: Duration = 60.seconds,
    var firmwareBoot: Duration = 3.seconds,
    var returnSearch: Duration = 40.seconds,
    var responseDelay: Duration = Duration.ZERO,
    var finishSettle: Duration = 600.milliseconds,
    var scanInterval: Duration = 1.seconds,
    var handshakeWait: Duration = 1.seconds,
    var commandReplyWait: Duration = 1500.milliseconds,
    var handshakeAttempts: Int = 2,
) {
    companion object {
        val standard = UpdaterTiming()
    }
}

/** What installing a package would do to the connected FM-1. */
enum class InstallKind {
    /** a different build of the open firmware it runs */
    update,
    /** a different firmware (also between two custom firmwares, which FM1Kit can't tell apart): may erase projects and samples */
    switchingFirmware,
    /** the firmware already running, not reinstalled (V15, or open firmware's same build) */
    reinstall,
    /** a package with the identity the custom firmware reports: a reinstall or another release of it */
    sameIdentity,
}

/** The connected FM-1 and what installing a package on it would do. */
data class InstallCheck(val device: FM1Identity, val capability: FM1Capability?, val kind: InstallKind) {
    companion object {
        internal fun of(device: FM1Identity, capability: FM1Capability?, pkg: FwscPackage, buildID: FM1BuildID?): InstallCheck {
            val kind = if (capability != null) {
                val sameBuild = buildID == capability.buildID
                val sameLine = pkg.identity.isOpenFirmware && capability.family == FM1Capability.FM1_FAMILY
                if (sameBuild) InstallKind.reinstall else if (sameLine) InstallKind.update else InstallKind.switchingFirmware
            } else if (device == pkg.identity) {
                if (device == FM1Identity.officialV15) InstallKind.reinstall else InstallKind.sameIdentity
            } else {
                InstallKind.switchingFirmware
            }
            return InstallCheck(device, capability, kind)
        }
    }
}

data class InstallOptions(
    val confirmsSwitchingFirmware: Boolean = false,
    val confirmsSameIdentity: Boolean = false,
    val buildID: FM1BuildID? = null,
)

/** A device found on a port, its connection still open (the caller closes it). */
class FoundDevice(val connection: MIDIConnection, val identity: FM1Identity)

/**
 * The FM-1 USB-MIDI update protocol over any MIDITransport: handshake, upgrade command, staging reads until
 * 0xE0000000, the ota- loader, upgrade command again, write reads until 0xF0000000, then the device back with the
 * package's identity. Refuses to start while more than one FM-1 answers. Cancelling mid-write leaves the device in its
 * loader, where resume() finishes it.
 */
class FM1Updater(val transport: MIDITransport, val timing: UpdaterTiming = UpdaterTiming.standard) {
    // ---- discovery

    suspend fun find(filter: (FM1Identity) -> Boolean = { true }): FoundDevice? {
        for (port in transport.ports()) {
            if (!isProbeable(port)) continue
            val c = runCatching { transport.open(port) }.getOrNull() ?: continue
            val id = handshake(c)
            if (id != null && filter(id)) return FoundDevice(c, id)
            c.close()
        }
        return null
    }

    suspend fun findAll(filter: (FM1Identity) -> Boolean = { true }): List<FoundDevice> {
        val found = mutableListOf<FoundDevice>()
        for (port in transport.ports()) {
            if (!isProbeable(port)) continue
            val c = runCatching { transport.open(port) }.getOrNull() ?: continue
            val id = handshake(c)
            if (id != null && filter(id)) found.add(FoundDevice(c, id)) else c.close()
        }
        return found
    }

    internal suspend fun findOnly(filter: (FM1Identity) -> Boolean): FoundDevice? {
        val found = findAll(filter)
        if (found.size > 1) {
            found.forEach { it.connection.close() }
            throw FM1UpdateError.MultipleDevices(found.size)
        }
        return found.firstOrNull()
    }

    /** what the connected FM-1 runs (only the handshake is sent); null: no FM-1 answered */
    suspend fun identify(): FM1Identity? {
        val d = find() ?: return null
        d.connection.close()
        return d.identity
    }

    internal suspend fun waitFor(limit: Duration, filter: (FM1Identity) -> Boolean): FoundDevice? {
        val deadline = TimeSource.Monotonic.markNow() + limit
        while (!deadline.hasPassedNow()) {
            findOnly(filter)?.let { return it }
            delay(timing.scanInterval)
        }
        return null
    }

    internal suspend fun handshake(c: MIDIConnection): FM1Identity? {
        c.drain()
        repeat(timing.handshakeAttempts) {
            if (runCatching { c.send(FM1Message.handshakeQuery) }.isFailure) return null
            val deadline = TimeSource.Monotonic.markNow() + timing.handshakeWait
            while (!deadline.hasPassedNow()) {
                val reply = c.receive(-deadline.elapsedNow()) ?: break
                FM1Identity.fromHandshakeReply(reply)?.let { return it }
            }
        }
        return null
    }

    // ---- checks

    /** what installing [pkg] on the connected FM-1 would do, changing nothing (handshake, open firmware's query) */
    suspend fun check(pkg: FwscPackage, buildID: FM1BuildID? = null): InstallCheck {
        val d = findOnly { !it.isLoader } ?: throw FM1UpdateError.DeviceNotFound
        try {
            return check(pkg, d, buildID ?: knownBuildID(pkg))
        } finally {
            d.connection.close()
        }
    }

    internal suspend fun check(pkg: FwscPackage, d: FoundDevice, buildID: FM1BuildID?): InstallCheck {
        if (!pkg.isFirmware) throw FM1UpdateError.NotFirmware(pkg.product)
        val cap = capability(d)
        if (d.identity.hardwareModel != pkg.identity.model && cap?.family != FM1Capability.FM1_FAMILY) {
            throw FM1UpdateError.ModelMismatch(d.identity.text, pkg.product)
        }
        return InstallCheck.of(d.identity, cap, pkg, buildID)
    }

    internal suspend fun query(c: MIDIConnection, command: Int, payload: ByteArray = ByteArray(0)): ByteArray? {
        c.drain()
        if (runCatching { c.send(FM1Message.command(command, payload)) }.isFailure) return null
        val deadline = TimeSource.Monotonic.markNow() + timing.commandReplyWait
        while (!deadline.hasPassedNow()) {
            val reply = c.receive(-deadline.elapsedNow()) ?: break
            val (cmd, p) = FM1Message.parseCommand(reply) ?: continue
            if (cmd == command) return p
        }
        return null
    }

    internal suspend fun capability(d: FoundDevice): FM1Capability? {
        if (!d.identity.isOpenFirmware) return null
        val p = query(d.connection, FM1Message.capabilityCommand) ?: throw FM1UpdateError.CapabilityUnavailable
        return FM1Capability.parse(p) ?: throw FM1UpdateError.CapabilityUnavailable
    }

    internal suspend fun arm(c: MIDIConnection, cap: FM1Capability, pkg: FwscPackage, buildID: FM1BuildID?) {
        if (!cap.isReady) throw FM1UpdateError.DeviceBusy
        val payload = FM1Capability.armPayload(cap.family, pkg.logicalImage.size, buildID ?: FM1BuildID.knownHex(pkg.sha256))
        val reply = query(c, FM1Message.armCommand, payload) ?: throw FM1UpdateError.ArmRefused(null)
        if (!reply.contentEquals(FM1Capability.ARM_ACCEPTED)) {
            throw FM1UpdateError.ArmRefused(if (reply.isEmpty()) -1 else reply.u(0))
        }
    }

    // ---- installing

    /** installs on a device running firmware; one in its loader takes resume() */
    suspend fun install(pkg: FwscPackage, options: InstallOptions = InstallOptions(),
                        events: (UpdateEvent) -> Unit = {}): FM1Identity {
        val device = findOnly { !it.isLoader } ?: throw FM1UpdateError.DeviceNotFound
        val buildID = options.buildID ?: knownBuildID(pkg)
        val staging: ServeOutcome
        try {
            val check = check(pkg, device, buildID)
            when (check.kind) {
                InstallKind.reinstall -> throw FM1UpdateError.AlreadyInstalled(device.identity.text)
                InstallKind.switchingFirmware -> if (!options.confirmsSwitchingFirmware) {
                    throw FM1UpdateError.SwitchNotConfirmed(device.identity.text, pkg.product)
                }
                InstallKind.sameIdentity -> if (!options.confirmsSameIdentity) {
                    throw FM1UpdateError.SameIdentityNotConfirmed(device.identity.text)
                }
                InstallKind.update -> Unit
            }
            events(UpdateEvent.Connected(device.identity))
            check.capability?.let { arm(device.connection, it, pkg, buildID) }
            device.connection.send(FM1Message.upgradeCommand)
            pause(timing.afterUpgradeCommand)
            staging = serve(device.connection, pkg.logicalImage, FM1UpdateError.Phase.staging, timing.stagingIdleLimit) { _, n ->
                events(UpdateEvent.Staging(n))
            }
        } finally {
            device.connection.close()
        }
        if (!staging.finished) {
            if (refusedBootloader(staging, pkg)) throw FM1UpdateError.BootloaderMismatch(staging.requests)
            if (refusedPackage(staging, pkg)) throw FM1UpdateError.PackageRefused(staging.requests)
            throw if (staging.lost) FM1UpdateError.Disconnected(FM1UpdateError.Phase.staging, staging.requests)
            else FM1UpdateError.Stalled(FM1UpdateError.Phase.staging, staging.requests)
        }
        events(UpdateEvent.WaitingForLoader)
        delay(timing.loaderBoot)
        // an armed open firmware's loader names the build it was armed with, which must be this one
        val model = pkg.identity.model
        val armedPrefix = (buildID ?: FM1BuildID.knownHex(pkg.sha256)).hex.take(16)
        val loader = waitFor(timing.loaderSearch) { it.isLoaderFor(model) && (it.armedBuildPrefix ?: armedPrefix) == armedPrefix }
            ?: throw FM1UpdateError.LoaderNotFound
        return write(pkg, loader, buildID, events)
    }

    /** finishes an update on a device waiting in its update loader (a cut-off write, or USB recovery mode) */
    suspend fun resume(pkg: FwscPackage, events: (UpdateEvent) -> Unit = {}): FM1Identity {
        if (!pkg.isFirmware) throw FM1UpdateError.NotFirmware(pkg.product)
        val model = pkg.identity.model
        val loader = findOnly { it.isLoaderFor(model) } ?: throw FM1UpdateError.LoaderNotFound
        return write(pkg, loader, knownBuildID(pkg), events)
    }

    private suspend fun write(pkg: FwscPackage, loader: FoundDevice, buildID: FM1BuildID?,
                              events: (UpdateEvent) -> Unit): FM1Identity {
        val writing: ServeOutcome
        try {
            events(UpdateEvent.LoaderFound(loader.identity))
            loader.connection.send(FM1Message.upgradeCommand)
            pause(timing.afterUpgradeCommand)
            val progress = WriteProgress(pkg.container.applicationRange)
            writing = serve(loader.connection, pkg.logicalImage, FM1UpdateError.Phase.writing, timing.writeIdleLimit) { r, _ ->
                events(progress.record(r))
            }
        } finally {
            loader.connection.close()
        }
        if (!writing.finished) {
            throw if (writing.lost) FM1UpdateError.Disconnected(FM1UpdateError.Phase.writing, writing.requests)
            else FM1UpdateError.Stalled(FM1UpdateError.Phase.writing, writing.requests)
        }
        events(UpdateEvent.Rebooting)
        delay(timing.firmwareBoot)
        val back = waitFor(timing.returnSearch) { !it.isLoader } ?: throw FM1UpdateError.DeviceDidNotReturn
        try {
            if (back.identity != pkg.identity) throw FM1UpdateError.IdentityMismatch(pkg.product, back.identity.text)
            // stock firmwares can't report their build: only open firmware's is checked
            if (buildID != null) {
                val cap = runCatching { capability(back) }.getOrNull()
                if (cap != null && cap.buildID != buildID) throw FM1UpdateError.BuildMismatch(buildID, cap.buildID)
            }
            events(UpdateEvent.Finished(back.identity))
            return back.identity
        } finally {
            back.connection.close()
        }
    }

    internal class ServeOutcome {
        var requests = 0
        var finished = false
        var lost = false
        var lastRead: IntRange? = null
    }

    /** answers read requests until the device asks to finish [phase], goes quiet for [idleLimit], or disappears */
    internal suspend fun serve(c: MIDIConnection, image: ByteArray, phase: FM1UpdateError.Phase, idleLimit: Duration,
                               progress: (FM1ReadRequest, Int) -> Unit): ServeOutcome {
        val finishAddress = if (phase == FM1UpdateError.Phase.staging) FM1Message.stageFinishAddress else FM1Message.writeFinishAddress
        val out = ServeOutcome()
        var lastActivity = TimeSource.Monotonic.markNow()
        while (true) {
            currentCoroutineContext().ensureActive()
            // checked on every pass: other messages don't count as activity
            if (lastActivity.elapsedNow() > idleLimit) return out
            val message = c.receive(1.seconds)
            if (message == null) {
                if (c.isLost) {
                    out.lost = true
                    return out
                }
                continue
            }
            val request = FM1ReadRequest.parse(message) ?: continue
            lastActivity = TimeSource.Monotonic.markNow()
            if (request.isFinish) {
                if (request.address != finishAddress || request.length != FM1Message.finishPayload.size) {
                    throw FM1UpdateError.UnexpectedFinish(phase)
                }
                // the device moves on once it has asked; a lost reply shows up as a failed reconnect
                runCatching { c.send(request.finishResponse()) }
                settle(c, request)
                out.finished = true
                return out
            }
            val start = request.address.toInt()
            if (request.address < 0 || request.length > FM1Message.maximumReadLength || start + request.length > image.size) {
                throw FM1UpdateError.BadRequest(request.address, request.length)
            }
            pause(timing.responseDelay)
            if (runCatching { c.send(request.response(image.copyOfRange(start, start + request.length))) }.isFailure) {
                out.lost = true
                return out
            }
            out.requests++
            out.lastRead = start until start + request.length
            progress(request, out.requests)
        }
    }

    /** the connection kept open a moment after a finish reply, answering any repeat of it */
    internal suspend fun settle(c: MIDIConnection, finish: FM1ReadRequest) {
        val deadline = TimeSource.Monotonic.markNow() + timing.finishSettle
        while (!c.isLost && !deadline.hasPassedNow()) {
            val m = c.receive(-deadline.elapsedNow()) ?: continue
            if (FM1ReadRequest.parse(m) == finish) runCatching { c.send(finish.finishResponse()) }
        }
    }

    private suspend fun pause(d: Duration) {
        if (d.isPositive()) delay(d)
    }

    companion object {
        /** only ports whose names contain one of these are probed, so SysEx never goes to other gear */
        val probeablePortNameFragments = listOf("fm-1", "fm1", "felucca", "ota", "composite", "usb-midi", "simulated",
                                                "sloop", "clipper", "sinco")

        fun isProbeable(port: MIDIPortDescriptor): Boolean {
            val n = port.name.lowercase()
            return probeablePortNameFragments.any { n.contains(it) }
        }

        internal fun knownBuildID(pkg: FwscPackage): FM1BuildID? = OfficialFirmware.matching(pkg)?.buildID

        /** staging stopped on a read of the boot area (Felucca's platform comparing bootloaders) */
        internal fun refusedBootloader(s: ServeOutcome, pkg: FwscPackage): Boolean {
            val boot = pkg.bootAreaRange ?: return false
            val last = s.lastRead ?: return false
            return last.first in boot
        }

        /** staging stopped, still connected, after only the header */
        internal fun refusedPackage(s: ServeOutcome, pkg: FwscPackage): Boolean {
            if (s.lost) return false
            val last = s.lastRead ?: return false
            return last.first in pkg.headerRange
        }
    }
}
