// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Transport/SimulatedFM1.swift, SimulatedFM1+Transport.swift,
// SimulatedFM1+Bootloader.swift and SimulatedFM1+Commands.swift. Copyright (c) 2026 Drew Robinson, MIT licence
// (LICENSES/FM1Kit-MIT.txt). Kotlin port for RemoteFM1 Android: its sessions are coroutines on Dispatchers.Default;
// V15's voice banks, open firmware's project library and m-bootloader's installer are left out (an installer package
// just restarts it into the firmware it ran).
package com.quixotic7.remotefm1.install.fm1kit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A software FM-1 that speaks the update protocol (tests, the fake link, demos): it answers the handshake, pulls the
 * image after an upgrade command, restarts into an ota- loader between staging and writing, and comes back reporting
 * the installed package's identity. Every response is checked byte for byte, so encoding mistakes show up as stalls.
 * It is told the package up front with prepare().
 */
class SimulatedFM1(val options: Options = Options()) : MIDITransport {
    data class Options(
        var installedIdentity: FM1Identity = FM1Identity.of("FM-1", 900),
        /** start in the update loader (OCT− held at power-on, or a cut-off install) */
        var startsInRecoveryMode: Boolean = false,
        /** V15 refuses a package with its own identity */
        var refusesRunningFirmware: Boolean = true,
        var stagingRequestCount: Int = 74,
        var writeChunkLength: Int = 512,
        var writesBackwards: Boolean = false,
        /** read the whole image a second time after writing, as Felucca's loader does */
        var checksAfterWriting: Boolean = false,
        var requestInterval: Duration = 4.milliseconds,
        var rebootDuration: Duration = 1500.milliseconds,
        var responseTimeout: Duration = 5.seconds,
        /** the cable pulled once the write reaches this fraction */
        var unplugDuringWriteAt: Double? = null,
        var installedBuildID: FM1BuildID = FM1BuildID(ByteArray(FM1BuildID.BYTE_COUNT)),
        var capabilityState: Int = 0,
        var armReply: Int = 0,
        /** Felucca's USB device reports none */
        var serialNumber: String? = "SIMULATED-FM-1",
        var bootloaderVersion: String? = null,
        var comparesBootArea: Boolean = false,
        var refusesAfterHeader: Boolean = false,
    )

    internal sealed class Mode {
        data class Firmware(val identity: FM1Identity) : Mode()
        data object Loader : Mode()
        data object Offline : Mode()
    }

    private val lock = Any()
    private var mode: Mode = if (options.startsInRecoveryMode) Mode.Loader else Mode.Firmware(options.installedIdentity)
    private var generation = 0
    private var expected: FwscPackage? = null
    private var session: Job? = null
    private var hasUnpluggedOnce = false
    private var buildID: FM1BuildID = options.installedBuildID
    private var armedBuildID: FM1BuildID? = null
    private var bootloaderVersion: String? = options.bootloaderVersion
    private val eventLog = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** what it has done so far, oldest first */
    val events: List<String> get() = synchronized(lock) { eventLog.toList() }

    fun prepare(pkg: FwscPackage) = synchronized(lock) { expected = pkg }

    val currentBuildID: FM1BuildID get() = synchronized(lock) { buildID }

    /** what it would report now; null while it restarts */
    val currentIdentity: FM1Identity?
        get() = synchronized(lock) {
            when (val m = mode) {
                is Mode.Firmware -> m.identity
                Mode.Loader -> armedBuildID?.let { FM1Identity.parse("ota-FM1C_${it.hex.take(16)}") } ?: LOADER_IDENTITY
                Mode.Offline -> null
            }
        }

    internal fun isCurrent(gen: Int) = synchronized(lock) { generation == gen && mode != Mode.Offline }

    internal fun handle(sysex: ByteArray, c: SimulatedConnection) {
        if (sysex.contentEquals(FM1Message.handshakeQuery)) {
            currentIdentity?.let { c.toHost.deliver(it.handshakeReply()) }
            return
        }
        if (sysex.contentEquals(FM1Message.upgradeCommand)) {
            if (currentIdentity?.isOpenFirmware == true && synchronized(lock) { armedBuildID } == null) {
                log("Open firmware ignores the upgrade command until it's armed.")
                return
            }
            startSession(c)
            return
        }
        val cmd = FM1Message.parseCommand(sysex)
        if (cmd != null && (cmd.first == FM1Message.capabilityCommand || cmd.first == FM1Message.armCommand)) {
            handleOpenFirmwareCommand(cmd.first, cmd.second, c)
            return
        }
        c.fromHost.deliver(sysex)
    }

    private fun startSession(c: SimulatedConnection) {
        synchronized(lock) {
            val pkg = expected
            if (session != null || pkg == null) return
            val isLoader = mode == Mode.Loader
            val gen = generation
            session = scope.launch {
                if (isLoader) write(pkg, c) else stage(pkg, c)
                synchronized(lock) { if (generation == gen) session = null }
            }
        }
    }

    private suspend fun stage(pkg: FwscPackage, c: SimulatedConnection) {
        val image = pkg.logicalImage
        val length = minOf(FM1Message.maximumReadLength, image.size)
        val otherBootloader = options.comparesBootArea && synchronized(lock) { bootloaderVersion } != null
        val boot = if (options.refusesAfterHeader || !otherBootloader) null else pkg.bootAreaRange
        if (options.refusesAfterHeader || boot != null) {
            for (address in listOf(0, length) + (boot?.let { listOf(it.first) } ?: emptyList())) {
                val r = FM1ReadRequest(address = address.toLong(), length = length)
                if (!exchange(r, image.copyOfRange(address, address + length), c)) return
            }
            log(if (boot == null) "The package is refused after its header." else "The package's bootloader isn't the FM-1's.")
            return
        }
        for (index in 0 until options.stagingRequestCount) {
            val address = (index * length) % maxOf(image.size - length, 1)
            val r = FM1ReadRequest(address = address.toLong(), length = length)
            if (!exchange(r, image.copyOfRange(address, address + length), c)) return
        }
        if (options.refusesRunningFirmware && currentIdentity == pkg.identity && currentIdentity == FM1Identity.officialV15) {
            log("${pkg.product} is already running, so the package is refused.")
            return
        }
        val finish = FM1ReadRequest(address = FM1Message.stageFinishAddress, length = 8)
        if (!exchange(finish, FM1Message.finishPayload, c)) return
        if (!pkg.isFirmware) {
            val running = (synchronized(lock) { mode } as? Mode.Firmware) ?: return
            log("The installer isn't run here; restarting into ${running.identity.text}.")
            reboot(running)
            return
        }
        log("Staged ${pkg.product}; rebooting into the loader.")
        reboot(Mode.Loader)
    }

    private suspend fun write(pkg: FwscPackage, c: SimulatedConnection) {
        val image = pkg.logicalImage
        var addresses = (0 until image.size step options.writeChunkLength).toList()
        if (options.writesBackwards) addresses = addresses.reversed()
        val passes = if (options.checksAfterWriting) listOf(addresses, addresses) else listOf(addresses)
        for (pass in passes) {
            for ((index, address) in pass.withIndex()) {
                val at = options.unplugDuringWriteAt
                if (at != null && index.toDouble() / pass.size >= at && !synchronized(lock) { hasUnpluggedOnce }) {
                    synchronized(lock) { hasUnpluggedOnce = true }
                    log("Cable pulled at ${(at * 100).toInt()}%; the loader stays in update mode.")
                    reboot(Mode.Loader)
                    return
                }
                val length = minOf(options.writeChunkLength, image.size - address)
                val r = FM1ReadRequest(address = address.toLong(), length = length)
                if (!exchange(r, image.copyOfRange(address, address + length), c)) return
            }
        }
        val finish = FM1ReadRequest(address = FM1Message.writeFinishAddress, length = 8)
        if (!exchange(finish, FM1Message.finishPayload, c)) return
        log("Wrote ${pkg.product}; rebooting.")
        val pkgBuild = FM1Updater.knownBuildID(pkg) ?: FM1BuildID.knownHex(pkg.sha256)
        synchronized(lock) {
            buildID = armedBuildID ?: pkgBuild
            if (pkg.identity == FM1Identity.officialV15) bootloaderVersion = null
        }
        reboot(Mode.Firmware(pkg.identity))
    }

    private suspend fun exchange(r: FM1ReadRequest, expect: ByteArray, c: SimulatedConnection): Boolean {
        if (options.requestInterval.isPositive()) delay(options.requestInterval)
        if (!isCurrent(c.generation)) return false
        c.toHost.deliver(r.encoded())
        while (true) {
            val reply = c.fromHost.next(options.responseTimeout) ?: break
            val (answered, data) = FM1ReadRequest.parseResponse(reply) ?: continue
            if (answered.address != r.address || !data.contentEquals(expect)) {
                log("Bad response at 0x${r.address.toString(16)}; the device stops.")
                return false
            }
            return true
        }
        log("No response at 0x${r.address.toString(16)}; the device stops.")
        return false
    }

    /** gone from the port list, then back in [next] */
    internal suspend fun reboot(next: Mode) {
        synchronized(lock) {
            mode = Mode.Offline
            generation++
            if (next is Mode.Firmware) armedBuildID = null      // arming doesn't survive the loader
        }
        delay(options.rebootDuration)
        synchronized(lock) {
            mode = next
            generation++
            session = null
        }
    }

    private fun handleOpenFirmwareCommand(command: Int, payload: ByteArray, c: SimulatedConnection) {
        if (currentIdentity?.isOpenFirmware != true) return
        if (command == FM1Message.capabilityCommand) {
            val cap = FM1Capability(state = options.capabilityState, buildID = currentBuildID)
            c.toHost.deliver(FM1Message.command(command, cap.payload()))
            return
        }
        val expectedLength = synchronized(lock) { expected?.logicalImage?.size }
        if (options.armReply != 0 || payload.size != 6 + FM1BuildID.BYTE_COUNT || payload.u(0) != 1 ||
            payload.u(1) != FM1Capability.FM1_FAMILY || FM1Message.readLE(payload, 2, 4).toInt() != expectedLength) {
            log("Arming refused.")
            c.toHost.deliver(FM1Message.command(command, byteArrayOf(maxOf(options.armReply, 1).toByte())))
            return
        }
        val b = FM1BuildID(payload.copyOfRange(6, payload.size))
        synchronized(lock) { armedBuildID = b }
        log("Armed for build ${b.hex.take(16)}.")
        c.toHost.deliver(FM1Message.command(command, FM1Capability.ARM_ACCEPTED))
    }

    internal fun log(s: String) = synchronized(lock) { eventLog.add(s); Unit }

    // ---- as a MIDITransport: one port, gone while it restarts

    override suspend fun ports(): List<MIDIPortDescriptor> = synchronized(lock) {
        if (mode == Mode.Offline) emptyList()
        else listOf(MIDIPortDescriptor("simulated-$generation", PORT_NAME, options.serialNumber))
    }

    override suspend fun open(port: MIDIPortDescriptor): MIDIConnection = synchronized(lock) {
        if (port.id != "simulated-$generation" || mode == Mode.Offline) throw MIDITransportError.PortGone
        SimulatedConnection(this, port, generation)
    }

    companion object {
        const val PORT_NAME = "FM-1 Midi (simulated)"
        internal val LOADER_IDENTITY = FM1Identity.of("ota-FM-1", 9)
    }
}

/** A connection to the simulated device; it ends when the device restarts. */
internal class SimulatedConnection(private val device: SimulatedFM1, override val port: MIDIPortDescriptor,
                                   val generation: Int) : MIDIConnection {
    val toHost = SysexInbox()
    val fromHost = SysexInbox()

    override val isLost: Boolean get() = !device.isCurrent(generation)

    override fun send(sysex: ByteArray) {
        if (!device.isCurrent(generation)) throw MIDITransportError.PortGone
        device.handle(sysex, this)
    }

    override suspend fun receive(timeout: Duration): ByteArray? = toHost.next(timeout)
    override fun drain() = toHost.drain()
    override fun close() = toHost.close()
}
