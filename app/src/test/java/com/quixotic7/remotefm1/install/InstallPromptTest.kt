// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/InstallPromptTests.swift; changes: JUnit 4; the port rule
// is fed MidiDesc (Android's MIDI descriptions) instead of CoreMIDI's names; the prompter test drives an
// InstallPromptSource fake and a map for "Not now"; the Keys from MIDI and delete-selection tests belong to other parts.
package com.quixotic7.remotefm1.install

import com.quixotic7.remotefm1.MidiDesc
import com.quixotic7.remotefm1.UnitHello
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InstallPromptTest {
    private val t0 = 1_000_000L
    private val usbFM1 = FM1Port("FM-1", bluetooth = false, candidate = true)
    private val bleFM1 = FM1Port("FM-1 7938", bluetooth = true, candidate = true)
    private val stock = FM1Port("FM-1", bluetooth = false, candidate = false)
    private val wait = InstallPromptState.ANSWER_WAIT_MS
    private val none = InstallPromptState.Prompt.None
    private fun install(unanswered: Boolean, ble: Boolean) = InstallPromptState.Prompt.Install(unanswered, ble)

    private fun input(ports: List<FM1Port> = emptyList(), searching: Boolean = true,
                      newest: RemoteFM1Version? = RemoteFM1Version(0, 2, 3)) =
        InstallPromptState.Input(ports = ports, searching = searching, newest = newest)

    @Test fun candidateThatNeverAnswers() {
        val m = InstallPromptState()
        val i = input(listOf(usbFM1))
        assertEquals(none, m.update(i, t0))
        assertEquals(none, m.update(i, t0 + 3000))
        assertEquals(install(true, false), m.update(i, t0 + wait))
        // the unit answers at last: gone, and the wait starts again after it is lost
        val a = i.copy(answered = true, unitVersion = RemoteFM1Version(0, 2, 3))
        assertEquals(none, m.update(a, t0 + 7000))
        assertEquals(none, m.update(i, t0 + 8000))
        assertEquals(install(true, false), m.update(i, t0 + 8000 + wait))
        // unplugged: gone, and the wait starts again
        assertEquals(none, m.update(input(), t0 + 20000))
        assertEquals(none, m.update(i, t0 + 21000))
    }

    @Test fun onlyWhileTheLinkAsks() {
        val m = InstallPromptState()
        val off = input(listOf(usbFM1), searching = false)
        assertEquals(none, m.update(off, t0))
        assertEquals(none, m.update(off, t0 + 60000))
        assertEquals(none, m.update(input(listOf(usbFM1)), t0 + 61000))
        assertEquals(install(true, false), m.update(input(listOf(usbFM1)), t0 + 61000 + wait))
    }

    @Test fun anotherFirmwaresUSBIDs() {
        val m = InstallPromptState()
        val i = input(listOf(stock), searching = false)      // (the link never talks to it: no search needed)
        assertEquals(none, m.update(i, t0))
        assertEquals(install(false, false), m.update(i, t0 + InstallPromptState.OTHER_WAIT_MS))
        assertEquals("This FM-1 isn't running RemoteFM1. Install it?", m.prompt.title)
    }

    @Test fun bluetoothOnly() {
        val m = InstallPromptState()
        val i = input(listOf(bleFM1))
        m.update(i, t0)
        val p = m.update(i, t0 + wait)
        assertEquals(install(true, true), p)
        assertEquals("This FM-1 isn't answering as RemoteFM1. Install it?", p.title)
        assertTrue(p.detail.contains("USB cable"))
        // a USB one too: it can be installed
        val m2 = InstallPromptState()
        val both = input(listOf(bleFM1, usbFM1))
        m2.update(both, t0)
        assertEquals(install(true, false), m2.update(both, t0 + 10000))
    }

    @Test fun neverWhileInstallingOrHeldOrFake() {
        for (k in 0 until 3) {
            val m = InstallPromptState()
            val base = input(listOf(usbFM1, stock))
            val i = when (k) {
                0 -> base.copy(installing = true)
                1 -> base.copy(heldElsewhere = true)
                else -> base.copy(enabled = false)
            }
            assertEquals(none, m.update(i, t0))
            assertEquals("case $k", none, m.update(i, t0 + 30000))
            // over: the waits start from then, not from before
            val free = input(listOf(usbFM1))
            assertEquals(none, m.update(free, t0 + 31000))
            assertEquals(install(true, false), m.update(free, t0 + 31000 + wait))
        }
    }

    @Test fun notNowPerRelease() {
        val m = InstallPromptState()
        var i = input(listOf(stock)).copy(notNowInstall = "0.2.3")
        m.update(i, t0)
        assertEquals(none, m.update(i, t0 + 30000))
        i = i.copy(newest = RemoteFM1Version(0, 2, 4))          // a newer release asks again
        assertEquals(install(false, false), m.update(i, t0 + 31000))
        // Not now said with no list (""): quiet while there is none
        val j = input(listOf(stock), newest = null).copy(notNowInstall = "")
        m.update(j, t0 + 40000)
        assertEquals(none, m.update(j, t0 + 50000))
    }

    @Test fun updateNotice() {
        val m = InstallPromptState()
        var i = input(listOf(usbFM1)).copy(answered = true, unitVersion = RemoteFM1Version(0, 2, 2))
        assertEquals(InstallPromptState.Prompt.Update("0.2.3", false), m.update(i, t0))
        assertEquals("RemoteFM1 0.2.3 is available for this FM-1", m.prompt.title)
        i = i.copy(unitOverBluetooth = true)
        assertEquals(InstallPromptState.Prompt.Update("0.2.3", true), m.update(i, t0 + 1000))
        i = i.copy(notNowUpdate = "0.2.3")
        assertEquals(none, m.update(i, t0 + 2000))
        i = i.copy(newest = RemoteFM1Version(0, 2, 4))
        assertEquals(InstallPromptState.Prompt.Update("0.2.4", true), m.update(i, t0 + 3000))
        i = i.copy(unitVersion = RemoteFM1Version(0, 2, 4))      // up to date
        assertEquals(none, m.update(i, t0 + 4000))
        i = i.copy(unitVersion = null)                          // a version it can't read: nothing
        assertEquals(none, m.update(i, t0 + 5000))
        i = i.copy(unitVersion = RemoteFM1Version(0, 2, 2), heldElsewhere = true)
        assertEquals(none, m.update(i, t0 + 6000))
    }

    private fun usb(name: String, vid: Int? = 0x1209, pid: Int? = 0x0001, port: String = name) = MidiDesc(
        id = 1, type = MidiDesc.TYPE_USB, name = name, product = name, usbVid = vid, usbPid = pid,
        inputPorts = listOf(port), outputPorts = listOf(port))

    @Test fun portRule() {
        assertEquals(FM1Port("ChoralRoot FM-1", false, true), FM1Ports.classify(usb("ChoralRoot FM-1")))
        assertEquals(true, FM1Ports.classify(usb("Felucca"))?.candidate)
        // another USB ID, an FM-1's name: another firmware; any other device: nothing
        assertEquals(FM1Port("FM-1 Midi", false, false), FM1Ports.classify(usb("FM-1", 0x1234, 0x5678, "FM-1 Midi")))
        assertNull(FM1Ports.classify(usb("Launchkey", 0x1234, 0x5678, "Launchkey MIDI")))
        assertNull(FM1Ports.classify(usb("FM-1", 0x1234, 0x5678, "FM-1 Update")))
        assertNull(FM1Ports.classify(usb("Felucca", port = "Felucca Update")))
        // Bluetooth: the name only
        val ble = MidiDesc(id = 2, type = MidiDesc.TYPE_BLUETOOTH, name = "FM-1 7938", inputPorts = listOf("Bluetooth"),
                           outputPorts = listOf("Bluetooth"))
        assertEquals(FM1Port("FM-1 7938", true, true), FM1Ports.classify(ble))
        assertNull(FM1Ports.classify(ble.copy(name = "KeyStep")))
        // virtual devices: never (the app's own fake FM-1 service included)
        assertNull(FM1Ports.classify(ble.copy(type = MidiDesc.TYPE_VIRTUAL)))
    }

    // ---- the prompter

    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("rfm-prompt").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    class Source : InstallPromptSource {
        override var real = false
        override var answered = false
        override var searching = false
        override var hello: UnitHello? = null
        var list: List<FM1Port> = emptyList()
        override fun ports() = list
    }

    class Memory : PromptMemory {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) { map[key] = value }
    }

    private fun installer(): FirmwareInstaller {
        val f = InstallerFetching { throw java.net.UnknownHostException() }
        return FirmwareInstaller(RemoteFM1Releases(dir, f), FirmwarePackages(dir, f), null,
                                 { throw IllegalStateException("no transport") }, true)
    }

    @Test fun prompterForcedPromptAndNotNow() {
        val src = Source()
        val mem = Memory()
        val inst = installer()
        // the fake unit: never by itself
        val quiet = InstallPrompter(src, inst, mem)
        quiet.tick()
        assertEquals(none, quiet.prompt.value)
        // a forced prompt: shown, hidden while the install page is open, Not now remembered for the release
        val p = InstallPrompter(src, inst, mem, forced = "bluetooth")
        p.tick()
        assertEquals(install(true, true), p.prompt.value)
        p.installPageOpen = true
        assertEquals(none, p.prompt.value)
        p.installPageOpen = false
        assertEquals(install(true, true), p.prompt.value)
        p.notNow()
        assertEquals(none, p.prompt.value)
        assertEquals("", mem.get(InstallPrompter.NOT_NOW_INSTALL_KEY))     // (no list here)
        val u = InstallPrompter(src, inst, mem, forced = "update")
        u.tick()
        assertEquals(InstallPromptState.Prompt.Update("0.2.3", false), u.prompt.value)
        u.notNow()
        assertEquals("0.2.3", mem.get(InstallPrompter.NOT_NOW_UPDATE_KEY))
    }

    /** the real path: a candidate searched for, unanswered, then the prompt; ports read at most every 2 s */
    @Test fun prompterReadsTheSource() {
        val src = Source().apply { real = true; searching = true; list = listOf(usbFM1) }
        var now = t0
        val p = InstallPrompter(src, installer(), Memory(), clock = { now })
        p.tick()
        assertEquals(none, p.prompt.value)
        now += wait
        p.tick()
        assertEquals(install(true, false), p.prompt.value)
        p.installPageOpen = true
        assertEquals(none, p.prompt.value)
    }
}
