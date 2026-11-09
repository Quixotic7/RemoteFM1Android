// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/FirmwareInstallTests.swift; changes: JUnit 4 and
// runBlocking; the lease test (RemoteController + PanelSession) is replaced by the fake panel's order around FM1Kit's
// messages (Android has no AU lease). releases.json and its cache, the package checks (SHA-256, FwscPackage, the
// identity) and FirmwareInstaller on the ported SimulatedFM1 with RemoteFM1 0.2.3's real package
// (test/resources/RemoteFM1): a full install, a refused checksum and identity (nothing reaches the FM-1, the panel never
// paused), a cut-off install resumed, a unit waiting in its loader, a switch from another firmware, no FM-1, and the
// panel paused before FM1Kit's first message and restarted after the last. No network, no MIDI.
package com.quixotic7.remotefm1.install

import com.quixotic7.remotefm1.UnitHello
import com.quixotic7.remotefm1.install.fm1kit.FM1Identity
import com.quixotic7.remotefm1.install.fm1kit.FwscPackage
import com.quixotic7.remotefm1.install.fm1kit.MIDIConnection
import com.quixotic7.remotefm1.install.fm1kit.MIDIPortDescriptor
import com.quixotic7.remotefm1.install.fm1kit.MIDITransport
import com.quixotic7.remotefm1.install.fm1kit.MIDITransportError
import com.quixotic7.remotefm1.install.fm1kit.SimulatedFM1
import com.quixotic7.remotefm1.install.fm1kit.UpdaterTiming
import com.quixotic7.remotefm1.install.fm1kit.refusesV15OverOtherBootloaders
import com.quixotic7.remotefm1.install.fm1kit.usesFeluccaUpdateCode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FirmwareInstallTest {
    class FakeFetcher(files: Map<String, ByteArray>) : InstallerFetching {
        private val files = HashMap(files)
        val requests = mutableListOf<String>()
        @Volatile var offline = false

        @Synchronized fun set(name: String, data: ByteArray) { files[name] = data }

        override suspend fun data(url: String): ByteArray {
            val name = url.substringAfterLast('/')
            val (d, off) = synchronized(this) { requests.add(name); files[name] to offline }
            if (off) throw UnknownHostException("remotefm1.com")
            return d ?: throw InstallerFetchError.Http(404, name)
        }
    }

    /** the app's link, as the installer sees it; [log] also has the transport's traffic (the order matters) */
    class FakePanel(val log: MutableList<String> = mutableListOf()) : PanelLinkPausing {
        var running = true
        val calls = mutableListOf<String>()
        override var unitHello: UnitHello? = null

        override suspend fun pauseForInstall(): Boolean {
            calls.add("pause")
            synchronized(log) { log.add("pause") }
            val was = running
            running = false
            return was
        }

        override fun resumeAfterInstall(restart: Boolean) {
            calls.add("resume($restart)")
            synchronized(log) { log.add("resume($restart)") }
            if (restart) running = true
        }
    }

    /** a transport with no ports (no FM-1 connected) */
    object NoPorts : MIDITransport {
        override suspend fun ports(): List<MIDIPortDescriptor> = emptyList()
        override suspend fun open(port: MIDIPortDescriptor): MIDIConnection = throw MIDITransportError.PortGone
    }

    /** the SimulatedFM1 with every message sent to it logged */
    class Logged(val base: SimulatedFM1, val log: MutableList<String>) : MIDITransport {
        override suspend fun ports() = base.ports()
        override suspend fun open(port: MIDIPortDescriptor): MIDIConnection {
            val c = base.open(port)
            return object : MIDIConnection by c {
                override fun send(sysex: ByteArray) {
                    synchronized(log) { log.add("send") }
                    c.send(sysex)
                }
            }
        }
    }

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("rfm-install").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/RemoteFM1/$name")!!.use { it.readBytes() }

    private fun fetcher() = FakeFetcher(mapOf("releases.json" to fixture("releases.json"), PACKAGE_FILE to fixture(PACKAGE_FILE)))

    class Made(val i: FirmwareInstaller, val sim: SimulatedFM1, val panel: FakePanel, val fetcher: FakeFetcher, val log: MutableList<String>)

    /** an installer on a SimulatedFM1, the fake panel and fetcher; the list fetched */
    private fun installer(options: SimulatedFM1.Options = device(), f: FakeFetcher? = null,
                          transport: InstallTransportFactory? = null): Made = runBlocking {
        val fetch = f ?: fetcher()
        val log = mutableListOf<String>()
        val panel = FakePanel(log)
        val sim = SimulatedFM1(options)
        val factory = transport ?: InstallTransportFactory { pkg ->
            panel.calls.add("transport")
            sim.prepare(pkg)
            Logged(sim, log)
        }
        val releases = RemoteFM1Releases(dir, fetch)
        releases.refresh()
        val i = FirmwareInstaller(releases, FirmwarePackages(File(dir, "packages"), fetch), panel, factory, true, fastTiming)
        Made(i, sim, panel, fetch, log)
    }

    // ---- releases.json

    @Test fun releasesAndVersions() = runBlocking {
        val list = RemoteFM1ReleaseList.parse(fixture("releases.json"))
        assertEquals("0.2.3", list.releases.first().version)
        val r = list.releases[0]
        assertEquals(FirmwarePackageRef.of("0.2.3", FirmwarePackageRef.Kind.standard, PACKAGE_FILE, PACKAGE_SHA, "FM-1_52000203"),
                     r.pkg(FirmwarePackageRef.Kind.standard))
        assertEquals("remote-usb-0.2.3.fwsc", r.pkg(FirmwarePackageRef.Kind.usbOnly)?.file)
        assertTrue(r.installable)
        assertEquals("28 Oct 2026", r.displayDate)
        val old = list.releases.first { it.version == "0.1.1" }
        assertFalse("0.1.1 answers as V15 (FM-1_015)", old.installable)
        assertNull(old.pkg(FirmwarePackageRef.Kind.usbOnly))

        assertEquals(RemoteFM1Version(0, 2, 3), RemoteFM1Version.ofIdentity("FM-1_52000203"))
        assertEquals(RemoteFM1Version(1, 10, 5), RemoteFM1Version.ofIdentity("FM-1_52011005"))
        assertNull("a dev build has no version", RemoteFM1Version.ofIdentity("FM-1_5200"))
        assertNull(RemoteFM1Version.ofIdentity("FM-1_015"))
        assertNull(RemoteFM1Version.ofIdentity("ota-FM-1_900"))
        assertEquals("FM-1_52000203", RemoteFM1Version(0, 2, 3).identity)
        // from 0.3.7: FM-1_72XXYYZZ (QUADRO also uses FM-1_52); both read as RemoteFM1
        assertEquals("FM-1_52000306", RemoteFM1Version(0, 3, 6).identity)
        assertEquals("FM-1_72000307", RemoteFM1Version(0, 3, 7).identity)
        assertEquals("FM-1_72010000", RemoteFM1Version(1, 0, 0).identity)
        assertEquals(RemoteFM1Version(0, 3, 7), RemoteFM1Version.ofIdentity("FM-1_72000307"))
        assertEquals(RemoteFM1Version(0, 3, 6), RemoteFM1Version.ofIdentity("FM-1_52000306"))
        assertNull("a dev build has no version", RemoteFM1Version.ofIdentity("FM-1_7200"))
        assertNull(RemoteFM1Version.ofIdentity("FM-1_6200030"))
        assertTrue(RemoteFM1Version.isDevBuild("FM-1_7200") && RemoteFM1Version.isDevBuild("FM-1_5200"))
        assertFalse(RemoteFM1Version.isDevBuild("FM-1_72000307"))
        assertEquals("RemoteFM1 0.3.7", InstallSummary.nameOf("FM-1_72000307"))
        assertEquals("RemoteFM1 0.3.6", InstallSummary.nameOf("FM-1_52000306"))
        assertEquals("a RemoteFM1 development build", InstallSummary.nameOf("FM-1_7200"))
        assertEquals("a RemoteFM1 development build", InstallSummary.nameOf("FM-1_5200"))
        assertEquals(RemoteFM1Version(0, 2, 2), RemoteFM1Version.parse("v0.2.2"))
        assertEquals(RemoteFM1Version(0, 2, 2), RemoteFM1Version.parse("0.2.2-dev+5"))
        assertNull(RemoteFM1Version.parse("remote"))
        assertTrue(RemoteFM1Version(0, 2, 9) < RemoteFM1Version(0, 10, 0))
        for (x in list.releases.filter { it.installable }) {
            assertEquals("${x.version}'s identity", x.parsedVersion, RemoteFM1Version.ofIdentity(x.identity))
        }

        val releases = RemoteFM1Releases(dir, fetcher())
        releases.refresh()
        assertEquals("0.2.3", releases.newest?.version)
        assertEquals("0.2.3", releases.update(RemoteFM1Version(0, 2, 2))?.version)
        assertNull(releases.update(RemoteFM1Version(0, 2, 3)))
        assertNull(releases.update(null))
        assertEquals("0.2.2", releases.release("FM-1_52000202")?.version)

        assertTrue(runCatching { RemoteFM1ReleaseList.parse("""{"releases":[]}""".toByteArray()) }.isFailure)
        assertTrue(runCatching { RemoteFM1ReleaseList.parse("<html>".toByteArray()) }.isFailure)
    }

    /** the last good list is kept: a later fetch that fails (offline, or a broken list) keeps it, with the reason */
    @Test fun releasesCache() = runBlocking {
        val f = fetcher()
        val a = RemoteFM1Releases(dir, f)
        assertTrue(a.releases.isEmpty())
        a.refresh()
        assertFalse(a.state.value.fromCache)
        assertNull(a.state.value.error)
        assertTrue(File(dir, "releases.json").exists())

        f.offline = true
        val b = RemoteFM1Releases(dir, f)
        assertTrue(b.state.value.fromCache)
        assertEquals("the cached list", "0.2.3", b.newest?.version)
        b.refresh()
        assertEquals("No internet connection.", b.state.value.error)
        assertEquals("0.2.3", b.newest?.version)

        f.offline = false
        f.set("releases.json", "{\"releases\": 3}".toByteArray())
        b.refresh()
        assertNotNull(b.state.value.error)
        assertEquals("a broken list replaced the good one", "0.2.3", b.newest?.version)
        val c = RemoteFM1Releases(dir, f)
        assertEquals("a broken list was cached", "0.2.3", c.newest?.version)
    }

    // ---- the package

    @Test fun packageChecks() = runBlocking {
        val data = fixture(PACKAGE_FILE)
        val ref = FirmwarePackageRef.of("0.2.3", FirmwarePackageRef.Kind.standard, PACKAGE_FILE, PACKAGE_SHA, "FM-1_52000203")
        val ok = FirmwarePackages.verify(data, ref)
        assertEquals("FM-1_52000203", ok.pkg.product)
        assertEquals(PACKAGE_SHA, ok.pkg.sha256)
        assertEquals(611_496, ok.size)
        assertTrue(ok.pkg.isFirmware)
        println("RemoteFM1 0.2.3: Felucca's update code ${ok.pkg.usesFeluccaUpdateCode}, " +
                "refuses V15 over other bootloaders ${ok.pkg.refusesV15OverOtherBootloaders}")

        // one byte changed: refused on its SHA-256, before FM1Kit reads it
        val bad = data.copyOf()
        bad[bad.size / 2] = (bad[bad.size / 2].toInt() xor 0x01).toByte()
        try {
            FirmwarePackages.verify(bad, ref)
            fail("a changed file was accepted")
        } catch (e: PackageProblem.ChecksumMismatch) {
            assertEquals(PACKAGE_SHA, e.expected)
            assertNotEquals(PACKAGE_SHA, e.got)
        }
        // the right file, listed as another identity: refused
        val other = ref.copy(identity = "FM-1_52000202")
        try {
            FirmwarePackages.verify(data, other)
            fail("a wrong identity was accepted")
        } catch (e: PackageProblem) {
            assertEquals(PackageProblem.IdentityMismatch(PACKAGE_FILE, "FM-1_52000202", "FM-1_52000203"), e)
        }
        // not a package at all (its SHA-256 listed as right): FwscPackage refuses it
        val junk = ByteArray(4000) { 0x5A }
        try {
            FirmwarePackages.verify(junk, ref.copy(sha256 = FwscPackage.sha256Hex(junk)))
            fail("junk was accepted")
        } catch (e: PackageProblem.Unreadable) {
            Unit
        }

        // fetch: downloaded once, then from the cache (checked again); a tampered download is neither used nor kept
        val f = fetcher()
        val store = FirmwarePackages(File(dir, "p"), f)
        store.fetch(ref)
        store.fetch(ref)
        assertEquals(listOf(PACKAGE_FILE), f.requests)
        store.directory.deleteRecursively()
        f.set(PACKAGE_FILE, bad)
        try {
            store.fetch(ref)
            fail("a tampered download was accepted")
        } catch (e: PackageProblem) {
            Unit
        }
        assertFalse(File(store.directory, PACKAGE_FILE).exists())
    }

    // ---- the flow on the SimulatedFM1

    /** RemoteFM1 0.2.2 -> 0.2.3: checked (a firmware switch to FM1Kit: confirmed), installed, the panel paused first */
    @Test fun fullInstall() = runBlocking {
        val m = installer()
        val i = m.i
        assertEquals("the newest release's default package", PACKAGE_FILE, i.selected?.file)
        m.panel.unitHello = UnitHello(identity = "FM-1_52000202")
        assertEquals(RemoteFM1Version(0, 2, 2), i.unitVersion)
        assertEquals("0.2.3", i.updateAvailable?.version)

        i.check()
        var st = i.state.value
        assertEquals(st.problem?.details ?: "", FirmwareInstaller.Stage.ready, st.stage)
        val s = st.summary!!
        assertEquals(InstallSummary.Kind.RemoteFM1(RemoteFM1Version(0, 2, 2)), s.kind)
        assertEquals("FM-1_52000202", s.deviceIdentity)
        assertEquals("RemoteFM1 0.2.2", s.deviceName)
        assertEquals("FM-1_52000203", s.pkg.identity)
        assertEquals(611_496, s.size)
        assertTrue(s.needsConfirm)
        assertEquals("FM1Kit spoke before the panel let go", listOf("pause", "transport"), m.panel.calls)
        assertTrue(st.panelPaused)

        i.install()                                         // (not confirmed: nothing happens)
        assertEquals(FirmwareInstaller.Stage.ready, i.state.value.stage)
        assertEquals("FM-1_52000202", m.sim.currentIdentity?.text)

        i.setConfirmed(true)
        val t0 = System.nanoTime()
        i.install()
        println("install of RemoteFM1 0.2.3 on the SimulatedFM1: %.1f s".format((System.nanoTime() - t0) / 1e9))
        st = i.state.value
        assertEquals(st.problem?.details ?: "", FirmwareInstaller.Stage.finished, st.stage)
        assertEquals("FM-1_52000203", st.installed)
        assertEquals("FM-1_52000203", m.sim.currentIdentity?.text)
        assertEquals(1.0, st.progress.fraction, 0.0)
        assertTrue(st.progress.isFinished)
        assertEquals("finished: FM-1_52000203", st.events.last())
        assertTrue(st.events.toString(), st.events.contains("loader: ota-FM-1_9"))
        assertEquals(listOf("pause", "transport", "transport", "resume(true)"), m.panel.calls)
        assertTrue("the panel didn't restart", m.panel.running)
        assertFalse(st.panelPaused)
        assertEquals("(HELLO_R's, until the link has the new one)", RemoteFM1Version(0, 2, 2), i.unitVersion)
        m.panel.unitHello = null
        assertEquals(RemoteFM1Version(0, 2, 3), i.unitVersion)
        assertNull(i.updateAvailable)

        i.leave()
        assertEquals(FirmwareInstaller.Stage.idle, i.state.value.stage)
        assertEquals("leave resumed twice", 4, m.panel.calls.size)
    }

    /** the panel lets go before FM1Kit's first message to the FM-1 and comes back after its last */
    @Test fun panelPausedAndResumedAroundTheInstall() = runBlocking {
        val m = installer()
        m.i.check()
        m.i.setConfirmed(true)
        m.i.install()
        assertEquals(FirmwareInstaller.Stage.finished, m.i.state.value.stage)
        val log = synchronized(m.log) { m.log.toList() }
        assertEquals("pause", log.first())
        assertEquals("resume(true)", log.last())
        assertEquals(1, log.count { it == "pause" })
        assertEquals(1, log.count { it.startsWith("resume") })
        assertTrue(log.count { it == "send" } > 2000)        // (the whole image twice: felucca-bsp's second read)
    }

    /** a tampered download: refused; the panel is never paused and FM1Kit never makes a transport */
    @Test fun checksumMismatchRefused() = runBlocking {
        val f = fetcher()
        val bad = fixture(PACKAGE_FILE)
        bad[100_000] = (bad[100_000].toInt() xor 0x80).toByte()
        f.set(PACKAGE_FILE, bad)
        val m = installer(f = f)
        m.i.check()
        val st = m.i.state.value
        assertEquals(FirmwareInstaller.Stage.failed, st.stage)
        assertEquals("This package was refused", st.problem?.headline)
        assertTrue(st.problem!!.explanation.contains("SHA-256"))
        assertEquals(true, st.problem?.deviceFine)
        assertEquals(false, st.problem?.canResume)
        assertEquals(emptyList<String>(), m.panel.calls)
        assertEquals("FM-1_52000202", m.sim.currentIdentity?.text)
        m.i.install()
        assertEquals(FirmwareInstaller.Stage.failed, m.i.state.value.stage)
        assertEquals(emptyList<String>(), m.panel.calls)
    }

    /** releases.json says another identity than the package carries: refused before anything reaches the FM-1 */
    @Test fun identityMismatchRefused() = runBlocking {
        val f = fetcher()
        val text = String(fixture("releases.json")).replace("\"FM-1_52000203\"", "\"FM-1_52000299\"")
        f.set("releases.json", text.toByteArray())
        val m = installer(f = f)
        assertEquals("FM-1_52000299", m.i.selected?.identity)
        m.i.check()
        val st = m.i.state.value
        assertEquals(FirmwareInstaller.Stage.failed, st.stage)
        assertTrue(st.problem?.explanation ?: "", st.problem!!.explanation.contains("installs as FM-1_52000203, not FM-1_52000299"))
        assertEquals(emptyList<String>(), m.panel.calls)
    }

    /** the cable pulled at half the write: the FM-1 waits in its loader; the panel stays off it (also on leave);
     *  "Finish the install" resumes with the same package */
    @Test fun cutOffInstallResumed() = runBlocking {
        val m = installer(device { it.unplugDuringWriteAt = 0.5 })
        val i = m.i
        i.check()
        i.setConfirmed(true)
        i.install()
        assertEquals(FirmwareInstaller.Stage.failed, i.state.value.stage)
        val p = i.state.value.problem!!
        assertTrue(p.details, p.canResume)
        assertFalse(p.deviceFine)
        assertEquals("The install was interrupted", p.headline)
        assertTrue("${m.sim.currentIdentity}", m.sim.currentIdentity?.isLoader == true)
        assertTrue(i.choiceLocked)
        i.releases.newest?.pkg(FirmwarePackageRef.Kind.usbOnly)?.let { usb ->
            i.select(usb)
            assertEquals("the choice changed under a cut-off install", PACKAGE_FILE, i.selected?.file)
        }
        i.leave()
        assertEquals("leave forgot the cut-off install", FirmwareInstaller.Stage.failed, i.state.value.stage)
        assertTrue(i.state.value.panelPaused)
        assertFalse("the panel came back to an FM-1 in its loader", m.panel.calls.any { it.startsWith("resume") })

        i.resume()
        val st = i.state.value
        assertEquals(st.problem?.details ?: "", FirmwareInstaller.Stage.finished, st.stage)
        assertEquals("FM-1_52000203", st.installed)
        assertEquals("FM-1_52000203", m.sim.currentIdentity?.text)
        assertEquals("resume(true)", m.panel.calls.last())
    }

    /** a unit already waiting in its loader (an install cut off earlier, or the app closed): offered the resume */
    @Test fun unitWaitingInItsLoader() = runBlocking {
        val m = installer(device { it.startsInRecoveryMode = true })
        val i = m.i
        i.check()
        assertEquals(i.state.value.problem?.details ?: "", FirmwareInstaller.Stage.ready, i.state.value.stage)
        assertEquals(InstallSummary.Kind.MidUpdate, i.state.value.summary?.kind)
        assertFalse(i.state.value.summary!!.needsConfirm)
        i.leave()
        assertEquals("leave dropped the loader's resume", FirmwareInstaller.Stage.ready, i.state.value.stage)
        assertFalse(m.panel.calls.any { it.startsWith("resume") })
        i.install()
        assertEquals(i.state.value.problem?.details ?: "", FirmwareInstaller.Stage.finished, i.state.value.stage)
        assertEquals("FM-1_52000203", m.sim.currentIdentity?.text)
    }

    /** from another firmware: a switch (projects and samples), confirmed; the same release again: a reinstall */
    @Test fun switchAndReinstall() = runBlocking {
        val a = installer(device("FM-1_900")).i
        a.check()
        assertEquals(InstallSummary.Kind.Switching, a.state.value.summary?.kind)
        assertEquals("another firmware", a.state.value.summary?.deviceName)
        a.setConfirmed(true)
        a.install()
        assertEquals(a.state.value.problem?.details ?: "", FirmwareInstaller.Stage.finished, a.state.value.stage)

        val b = installer(device("FM-1_52000203")).i
        b.check()
        assertEquals(InstallSummary.Kind.SameIdentity, b.state.value.summary?.kind)
        assertTrue(b.state.value.summary!!.needsConfirm)
    }

    /** nothing on USB: the words say USB, and Bluetooth can't; the panel comes back when the page is left */
    @Test fun noFM1() = runBlocking {
        val panel = FakePanel()
        val f = fetcher()
        val releases = RemoteFM1Releases(dir, f)
        releases.refresh()
        val i = FirmwareInstaller(releases, FirmwarePackages(File(dir, "p"), f), panel, { NoPorts }, true, fastTiming)
        i.check()
        assertEquals(FirmwareInstaller.Stage.failed, i.state.value.stage)
        assertEquals("No FM-1 answered over USB", i.state.value.problem?.headline)
        assertTrue(i.state.value.problem!!.explanation.contains("Bluetooth can't"))
        assertEquals(listOf("pause"), panel.calls)
        i.leave()
        assertEquals(listOf("pause", "resume(true)"), panel.calls)
        assertEquals(FirmwareInstaller.Stage.idle, i.state.value.stage)
    }

    /** the words for a few of FM1Kit's errors, and InstallSummary's names */
    @Test fun wordsAndNames() {
        assertEquals("RemoteFM1 0.2.2", InstallSummary.nameOf("FM-1_52000202"))
        assertEquals("M-VAVE V15 (or RemoteFM1 0.1)", InstallSummary.nameOf("FM-1_015"))
        assertEquals("a RemoteFM1 development build", InstallSummary.nameOf("FM-1_5200"))
        assertEquals("its update loader (part-way through an update)", InstallSummary.nameOf("ota-FM-1_900"))
        assertEquals("M-VAVE's open firmware", InstallSummary.nameOf("FM-1_8000012"))
        assertEquals("another firmware", InstallSummary.nameOf("FM-1_916"))
        val p = FirmwareInstaller.problemFor(UnknownHostException("x"))
        assertEquals("The download didn't work", p.headline)
        assertEquals("No internet connection. Nothing was sent to the FM-1.", p.explanation)
        assertEquals(FM1Identity.parse("FM-1_015"), FM1Identity.officialV15)
    }

    companion object {
        const val PACKAGE_FILE = "remote-0.2.3.fwsc"
        const val PACKAGE_SHA = "211f06f63e8ca09de2812c017db9bb229713ce8e5a102eba887031551b2b403c"

        val fastTiming = UpdaterTiming().apply {
            afterUpgradeCommand = 20.milliseconds
            finishSettle = 20.milliseconds
            stagingIdleLimit = 2.seconds
            loaderBoot = 20.milliseconds
            loaderSearch = 3.seconds
            writeIdleLimit = 2.seconds
            firmwareBoot = 20.milliseconds
            returnSearch = 3.seconds
            scanInterval = 30.milliseconds
            handshakeWait = 200.milliseconds
        }

        /** a SimulatedFM1 running RemoteFM1 0.2.2 (or [installed]), with felucca-bsp's loader's second read, fast */
        fun device(installed: String = "FM-1_52000202", configure: (SimulatedFM1.Options) -> Unit = {}): SimulatedFM1.Options =
            SimulatedFM1.Options().apply {
                installedIdentity = FM1Identity.parse(installed)!!
                stagingRequestCount = 5
                requestInterval = Duration.ZERO
                rebootDuration = 100.milliseconds
                responseTimeout = 1.seconds
                checksAfterWriting = true
                serialNumber = null
                configure(this)
            }
    }
}
