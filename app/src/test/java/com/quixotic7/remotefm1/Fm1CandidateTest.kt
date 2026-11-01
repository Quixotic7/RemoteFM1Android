// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fm1Candidate: RemoteMidi.c's rule on Android's device descriptions, and the device naming. */
class Fm1CandidateTest {
    private fun usb(vid: Int?, pid: Int?, name: String? = "FM-1", ins: List<String?> = listOf(null),
                    outs: List<String?> = listOf(null), product: String? = null) =
        MidiDesc(id = 1, type = MidiDesc.TYPE_USB, name = name, product = product, usbVid = vid, usbPid = pid,
                 inputPorts = ins, outputPorts = outs)

    private fun ble(name: String?, btName: String? = null) =
        MidiDesc(id = 2, type = MidiDesc.TYPE_BLUETOOTH, name = name, btName = btName, inputPorts = listOf(null),
                 outputPorts = listOf(null))

    @Test fun usbByItsIdsOnly() {
        val c = Fm1Candidate.check(usb(0x1209, 0x0001))
        assertTrue(c.candidate)
        assertEquals(0, c.inPort)
        assertEquals(0, c.outPort)
        // the IDs win over the name, both ways (as RemoteMidi.c: has_usb -> the ID alone)
        assertTrue(Fm1Candidate.check(usb(0x1209, 0x0001, name = "USB MIDI Device")).candidate)
        assertFalse(Fm1Candidate.check(usb(0x1209, 0x0002, name = "FM-1")).candidate)
        assertFalse(Fm1Candidate.check(usb(0x0582, 0x0001, name = "FM-1 lookalike")).candidate)   // (another maker)
    }

    @Test fun usbWithoutItsIdsByName() {
        assertTrue(Fm1Candidate.check(usb(null, null, name = "felucca FM-1")).candidate)
        assertTrue(Fm1Candidate.check(usb(null, null, name = null, product = "FM-1")).candidate)
        assertFalse(Fm1Candidate.check(usb(null, null, name = "Keystation 49")).candidate)
    }

    @Test fun bluetoothByNameOnly() {
        assertTrue(Fm1Candidate.check(ble("FM-1 7938")).candidate)
        assertTrue(Fm1Candidate.check(ble(null, btName = "FM-1 1F2D")).candidate)
        assertTrue(Fm1Candidate.check(ble("fm1 abcd")).candidate)
        assertFalse(Fm1Candidate.check(ble("WIDI Master")).candidate)
        assertFalse(Fm1Candidate.check(ble(null)).candidate)
    }

    @Test fun tolerantNames() {
        listOf("FM-1", "FM-1 7938", "ChoralRoot FM-1", "TransitFM1", "fm_1", "FM 1", "M-VAVE FM-1").forEach {
            assertTrue(it, Fm1Candidate.nameSaysFm1(it))
        }
        listOf("FM10", "FM-12", "FM-2", "Roland FM", "", "F-M1").forEach {
            assertFalse(it, Fm1Candidate.nameSaysFm1(it))
        }
    }

    @Test fun neverAnUpdater() {
        assertFalse(Fm1Candidate.check(usb(0x1209, 0x0001, name = "FM-1 update")).candidate)
        assertFalse(Fm1Candidate.check(ble("FM-1 Update")).candidate)
        // an updater port is skipped, another port is used
        val c = Fm1Candidate.check(usb(0x1209, 0x0001, ins = listOf("update", "MIDI"), outs = listOf("update", "MIDI")))
        assertTrue(c.candidate)
        assertEquals(1, c.inPort)
        assertEquals(1, c.outPort)
        assertFalse(Fm1Candidate.check(usb(0x1209, 0x0001, ins = listOf("Update"), outs = listOf(null))).candidate)
    }

    @Test fun needsBothDirections() {
        assertFalse(Fm1Candidate.check(usb(0x1209, 0x0001, ins = emptyList())).candidate)
        assertFalse(Fm1Candidate.check(usb(0x1209, 0x0001, outs = emptyList())).candidate)
    }

    @Test fun virtualOnlyAsTheTestLoopback() {
        val v = MidiDesc(id = 3, type = MidiDesc.TYPE_VIRTUAL, name = Fm1Candidate.VIRTUAL_NAME, inputPorts = listOf("in"),
                         outputPorts = listOf("out"))
        assertFalse(Fm1Candidate.check(v).candidate)
        assertTrue(Fm1Candidate.check(v, Fm1Candidate.VIRTUAL_NAME).candidate)
        assertFalse(Fm1Candidate.check(v.copy(name = "FM-1"), Fm1Candidate.VIRTUAL_NAME).candidate)   // (a name is not enough)
    }

    @Test fun naming() {
        assertEquals("FM-1", usb(0x1209, 1).displayName)
        assertEquals("FM-1 7938", ble(null, btName = "FM-1 7938").displayName)
        assertEquals("MIDI device 1", usb(1, 1, name = " ").displayName)
        val line = usb(0x1209, 0x0001).logLine
        assertTrue(line, line.contains("usb=1209:0001") && line.contains("USB") && line.contains("name=\"FM-1\""))
        assertEquals("Bluetooth", ble("x").transportName)
    }
}

/** ReconnectPolicy: the remembered Bluetooth unit's reopen rules. */
class ReconnectPolicyTest {
    @Test fun reopensAtLaunchWhenWanted() {
        val p = ReconnectPolicy("AA:BB")
        assertTrue(p.shouldTry(0, wanted = true, linked = false, opening = false))
        assertFalse(p.shouldTry(0, wanted = false, linked = false, opening = false))   // (This tablet: no link)
        assertFalse(p.shouldTry(0, wanted = true, linked = true, opening = false))     // (already linked, or on USB)
        assertFalse(p.shouldTry(0, wanted = true, linked = false, opening = true))
        assertFalse(ReconnectPolicy(null).shouldTry(0, true, false, false))
    }

    @Test fun backsOff() {
        val p = ReconnectPolicy("AA:BB")
        p.failed(1000)
        assertFalse(p.shouldTry(2999, true, false, false))
        assertTrue(p.shouldTry(3000, true, false, false))
        p.failed(3000)
        assertFalse(p.shouldTry(7999, true, false, false))
        assertTrue(p.shouldTry(8000, true, false, false))
        repeat(10) { p.failed(8000) }
        assertTrue(p.shouldTry(38000, true, false, false))       // (capped at 30 s)
        p.connected("AA:BB")
        assertTrue(p.shouldTry(8000, true, false, false))
        assertEquals(0, p.attempts)
    }

    @Test fun disconnectPausesForgetClears() {
        val p = ReconnectPolicy("AA:BB")
        p.pause()
        assertFalse(p.shouldTry(0, true, false, false))
        p.connected("CC:DD")
        assertEquals("CC:DD", p.address)
        assertTrue(p.shouldTry(0, true, false, false))
        p.forget()
        assertEquals(null, p.address)
        assertFalse(p.shouldTry(0, true, false, false))
    }
}

/** LinkModels: ported from RemoteFM1IOS's Models.swift (touchText as TouchTests checks it, event texts, the state). */
class LinkModelsTest {
    @Test fun touchText() {
        assertTrue(LinkStats.touchText(0x400, 3, 3, 1).contains("confirms velocity + pressure"))
        assertTrue(LinkStats.touchText(0x2F, 3, 0, 0).startsWith("not in this firmware"))
        assertEquals("off: velocity 100, no pressure (firmware has TOUCH)", LinkStats.touchText(0x400, 0, 0, 0))
        assertEquals("on (no FM-1 yet)", LinkStats.touchText(null, 3, 0, 0))
        assertTrue(LinkStats.touchText(0x400, 3, 1, 1).contains("(not yet)"))
    }

    @Test fun eventTexts() {
        assertEquals("key 5 A#3 up", PanelEvent.of(1, 1, 5, 0, 0, 0).text)
        assertEquals("key 4 A3 down vel 37", PanelEvent.of(2, 1, 4, 0, 1, 37).text)
        assertEquals("key 4 A3 pressure 90", PanelEvent.of(3, 5, 4, 0, 90, 0).text)
        assertEquals("button PLAY down (PANEL_R)", PanelEvent.of(4, 2, 12, 1, 1, 0).text)
        assertEquals("encoder 2 +3", PanelEvent.of(5, 3, 2, 0, 3, 0).text)
        assertEquals("MASTER 700", PanelEvent.of(6, 4, 0, 0, 700, 0).text)
        assertEquals("all up (session ended)", PanelEvent.of(7, 0x80, 0, 0, 0, 0).text)
        assertEquals("F3", PanelEvent.keyName(0))
        assertEquals("C4", PanelEvent.keyName(7))
    }

    @Test fun hello() {
        val h = UnitHello(transports = 0x31, caps = 0x6AF)
        assertEquals("USB BLE BLE heard", h.transportNames)
        assertEquals("shadow COPY RLE PAL PLAY green HOLD MIDI in TOUCH", h.capNames)
        assertEquals("none", UnitHello().capNames)
    }

    @Test fun state() {
        assertEquals(LinkState.Connected(true), LinkState.of(3, 2, true, true, "", null).first)
        assertEquals(LinkState.Starting, LinkState.of(2, 1, true, false, "", null).first)
        assertEquals(LinkState.Searching to true, LinkState.of(2, 0, true, false, "", 1000))
        assertEquals(LinkState.NotFound, LinkState.of(2, 0, true, false, "", 3001).first)
        assertEquals(LinkState.Searching to true, LinkState.of(0, -1, true, false, "", null))   // (about to start a run)
        assertEquals(LinkState.Stopped(""), LinkState.of(0, -1, false, false, "", null).first)
        assertEquals("Connected over USB", LinkState.Connected(false).title)
    }
}
