// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A1's link test, on libremotefm1's fake unit (nothing real is touched):
 *  - in this process (Backend.Fake, RemoteFM1IOS FakeLinkTests): the session, M1's test pattern's frames, the panel's
 *    events and the LEDs they light, Bluetooth pacing and HOLD, stop and start again; a core on the fake panel (a key
 *    press reaches ChoralRoot and makes sound); the unit's TRS MIDI in (TrsMidiTests) and TOUCH (TouchTests);
 *  - through Android's MIDI service (virtual*): the debug build's virtual MIDI device "RemoteFM1 Fake FM-1"
 *    (FakeFm1Service) found by RfmMidi, opened, handed to the native back end and driven over AMidi ports, as a real
 *    FM-1 over USB would be; how Android cuts SysEx is measured there.
 * Every number goes into files/linktest.json (scripts/emu_linktest.sh prints it) and the log (tag RFMLinkTest).
 */
@RunWith(AndroidJUnit4::class)
class LinkTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val r = JSONObject()
    private var name = ""
    private var link: RfmLink? = null
    private var engine: RfmEngine? = null

    @After
    fun done() {
        engine?.let {
            it.attach(false)
            it.close()
        }
        engine = null
        link?.close()
        link = null
        save(name, r)
    }

    private fun status(): JSONObject = link!!.status()
    private fun fake(): JSONObject = link!!.fakeState()
    private fun est(): JSONObject = engine!!.status()

    /** polls [ok] (rendering [renders] blocks of 256 between tries when an offline engine runs) */
    private fun until(seconds: Double, render: Boolean = false, ok: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + (seconds * 1000).toLong()
        while (SystemClock.elapsedRealtime() < end) {
            if (render) engine!!.renderBlocks(1, 256)
            if (ok()) return true
            SystemClock.sleep(5)
        }
        return ok()
    }

    private fun fakeLink(ble: Boolean = false, pattern: Boolean = true, noTouch: Boolean = false): RfmLink {
        val l = RfmLink(Backend.Fake(ble), fakeNoTouch = noTouch)
        l.setPattern(pattern)
        link = l
        return l
    }

    private fun connect(seconds: Double = 8.0, render: Boolean = false): Long {
        val t0 = SystemClock.elapsedRealtime()
        link!!.start()
        assertTrue("no session: ${status()}", until(seconds, render) { status().getInt("state") == LinkState.CONNECTED })
        return SystemClock.elapsedRealtime() - t0
    }

    // ---------------------------------------------------------------- the fake in this process
    @Test
    fun patternSessionFramesAcknowledged() {
        name = "pattern"
        fakeLink()
        r.put("connect_ms", connect())
        val st0 = status()
        assertEquals("RemoteFM1", st0.getString("name"))
        assertEquals(2, st0.getInt("proto"))
        assertEquals(0, st0.getInt("hello_ble"))
        assertTrue(until(5.0) { status().getInt("acks") >= 20 })
        SystemClock.sleep(2000)
        val st = status()
        val f = fake()
        r.put("frames", st.getInt("frames")).put("acks", st.getInt("acks")).put("fps", st.getDouble("fps"))
            .put("wire_bytes", st.getInt("wire_bytes")).put("budget", st.getInt("budget"))
            .put("ack_ms_avg", st.getInt("ack_ms_avg")).put("fake_session", f.getInt("session"))
            .put("fake_frames", f.getInt("frames")).put("text", st.getString("text"))
        assertTrue(st.getInt("frames") > 0)
        assertTrue(st.getInt("wire_bytes") > 0)
        assertTrue(st.getInt("budget") > 0)
        assertEquals(0, st.getInt("ble"))
        assertEquals(1, f.getInt("session"))
        val rgba = ByteArray(240 * 240 * 4)
        assertNotEquals(0, link!!.preview(0, rgba))
        r.put("pass", true)
    }

    @Test
    fun panelEventsLightTheLeds() {
        name = "panel"
        val l = fakeLink(pattern = false)
        connect()
        fun hasEvent(kind: PanelEvent.Kind, index: Int, value: Int) =
            l.events(0).any { it.kind == kind && it.index == index && it.value == value }
        l.fakeKey(5, true)
        assertTrue("key 5 down", until(3.0) { hasEvent(PanelEvent.Kind.KEY, 5, 1) && l.panel()[0] and (1L shl 5) != 0L })
        assertTrue("key 5's LED", until(3.0) { fake().getLong("lit_keys") and (1L shl 5) != 0L })
        l.fakeButton(12, true)                                  // PLAY
        assertTrue("PLAY", until(3.0) { l.panel()[1] and (1L shl 12) != 0L && fake().getLong("lit_buttons") and (1L shl 12) != 0L })
        l.fakeKey(5, false)
        l.fakeButton(12, false)
        assertTrue("all up", until(3.0) { l.panel()[0] == 0L && l.panel()[1] == 0L && fake().getLong("lit_keys") == 0L })
        l.fakeEncoder(2, 3)
        assertTrue("encoder 2 +3", until(3.0) { hasEvent(PanelEvent.Kind.ENCODER, 2, 3) })
        l.fakeMaster(700)
        assertTrue("MASTER", until(3.0) { kotlin.math.abs(l.panel()[2] - 700) <= 16 })
        assertEquals("key 5 A#3 up", l.events(0).last { it.kind == PanelEvent.Kind.KEY }.text)
        r.put("events", l.events(0).size).put("pass", true)
    }

    @Test
    fun bluetoothFakeIsPacedAndHolds() {
        name = "bluetooth_fake"
        fakeLink(ble = true)
        r.put("connect_ms", connect())
        val st = status()
        assertEquals(1, st.getInt("ble"))
        assertEquals(1, st.getInt("hello_ble"))
        assertEquals(1, st.getInt("hold"))
        assertNotEquals(0, st.getInt("caps") and (1 shl 7))
        assertTrue("held frames acknowledged", until(15.0) { status().getInt("held") > 0 && status().getInt("acks") > 0 })
        assertTrue(fake().getInt("held_frames") > 0)
        val s2 = status()
        r.put("budget", s2.getInt("budget")).put("wire_cap", s2.getInt("wire_cap")).put("frame_ms", s2.getInt("frame_ms"))
            .put("held", s2.getInt("held")).put("acks", s2.getInt("acks")).put("text", s2.getString("text")).put("pass", true)
    }

    @Test
    fun stopEndsTheSessionAndStartsAgain() {
        name = "stop_start"
        val l = fakeLink()
        connect()
        l.stop()
        assertTrue(until(3.0) { status().getInt("state") == LinkState.OFF && status().getInt("wanted") == 0 })
        assertEquals(0, fake().getInt("session"))
        connect()
        r.put("sessions", status().getInt("sessions")).put("runs", status().getInt("runs")).put("pass", true)
    }

    /** Core mode: ChoralRoot on the fake panel; a key on the fake reaches it and makes sound, its screen reaches the fake */
    @Test
    fun coreRunsOnTheFakePanel() {
        name = "core_on_fake"
        RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir)
        val l = fakeLink()
        val e = RfmEngine(l)
        engine = e
        assertEquals(null, e.load("choralroot"))
        assertTrue(e.boot())
        e.setClock(RfmEngine.CLOCK_AUDIO)
        e.setOffline(true)
        e.attach(true)
        r.put("connect_ms", connect(10.0, render = true))
        val lv = DoubleArray(2)
        e.renderBlocks(200, 240, 48000.0, lv)                  // (1 s: the boot)
        val f0 = fake().getInt("frames")
        r.put("before_rms", lv[0])
        val k0 = est().getInt("keys_pressed")
        l.fakeKey(7, true, 100)
        assertTrue("the key reached the core", until(3.0, render = true) { est().getInt("keys_pressed") > k0 })
        e.renderBlocks(200, 240, 48000.0, lv)                  // (1 s held)
        r.put("held_rms", lv[0]).put("held_peak", lv[1])
        l.fakeKey(7, false)
        e.renderBlocks(100, 240, 48000.0, null)
        // (offline renders run far ahead of the wall clock: the session sends the newest screen at its own pace)
        assertTrue("the core's screen reached the fake", until(3.0, render = true) { fake().getInt("frames") > f0 })
        val st = status()
        val f = fake()
        r.put("fake_frames_from_core", f.getInt("frames") - f0).put("acks", st.getInt("acks"))
            .put("events_applied", est().getInt("events_applied")).put("key_lit", f.getLong("lit_keys") and (1L shl 7) != 0L)
        assertTrue("held RMS ${lv[0]}", r.getDouble("held_rms") > 0.001)
        r.put("pass", true)
    }

    // ---------------------------------------------------------------- TRS MIDI in (TrsMidiTests)
    private fun echo(noTouch: Boolean = false, flags: Int? = null, keysOn: Boolean = false): RfmEngine {
        val l = fakeLink(noTouch = noTouch)
        flags?.let { l.setKeyTouch(it) }
        val e = RfmEngine(l)
        engine = e
        assertTrue(e.testLoadEcho())
        assertTrue(e.boot())
        e.setClock(RfmEngine.CLOCK_AUDIO)
        e.setOffline(true)
        if (keysOn) {
            e.midiOutEnable(1)
            e.setMidi(0, 0, false, true)                       // (the keys on: TRS notes still go to the firmware)
        }
        e.attach(true)
        connect(10.0, render = true)
        return e
    }

    private fun pkt(m: IntArray): Int {
        val cin = if (m[0] >= 0xF8) 0xF else m[0] shr 4
        return cin or (m[0] shl 8) or ((if (m.size > 1) m[1] else 0) shl 16) or ((if (m.size > 2) m[2] else 0) shl 24)
    }

    private fun trs(msgs: List<IntArray>): List<Int> {
        link!!.fakeTrs(ByteArray(msgs.sumOf { it.size }).also { b ->
            var i = 0
            msgs.forEach { m -> m.forEach { b[i++] = it.toByte() } }
        })
        val out = ArrayList<Int>()
        val end = SystemClock.elapsedRealtime() + 3000
        while (out.size < msgs.size && SystemClock.elapsedRealtime() < end) {
            out += engine!!.renderBlocks(1).toList()
            SystemClock.sleep(5)
        }
        return out
    }

    @Test
    fun trsMessagesReachTheCoreSplitAndInOrder() {
        name = "trs_split"
        val e = echo(keysOn = true)
        assertNotEquals(0, status().getInt("caps") and 0x200)
        e.renderBlocks(4)
        val msgs = listOf(intArrayOf(0x90, 60, 100), intArrayOf(0xF8), intArrayOf(0xB3, 7, 64), intArrayOf(0xC0, 5),
                          intArrayOf(0xFA), intArrayOf(0xE1, 0, 64), intArrayOf(0xD2, 9), intArrayOf(0x80, 60, 0),
                          intArrayOf(0xFC))
        assertEquals(msgs.map { pkt(it) }, trs(msgs))
        val st = status()
        assertEquals(msgs.size, st.getInt("trs_midi_in"))
        assertEquals(0, st.getInt("trs_midi_dropped"))
        assertEquals("a TRS note played a key", 0, est().getInt("keys_pressed"))
        r.put("trs_midi_in", st.getInt("trs_midi_in")).put("pass", true)
    }

    @Test
    fun trsLongBurstSpansSeveralBodies() {
        name = "trs_burst"
        val e = echo(keysOn = true)
        e.renderBlocks(4)
        val msgs = ArrayList<IntArray>()
        for (n in 1..40) {
            msgs.add(intArrayOf(0x90, n, n))
            if (n % 5 == 0) msgs.add(intArrayOf(0xF8))
        }
        assertEquals(msgs.map { pkt(it) }, trs(msgs))
        assertEquals(msgs.size, status().getInt("trs_midi_in"))
        r.put("trs_midi_in", msgs.size).put("pass", true)
    }

    // ---------------------------------------------------------------- TOUCH (TouchTests)
    private fun pressed(k: Int, vel: Int): Boolean {
        link!!.fakeKey(k, true, vel)
        val before = est().getInt("keys_pressed")
        return until(3.0, render = true) { est().getInt("keys_pressed") > before }
    }

    @Test
    fun touchAskedAfterAckAndConfirmed() {
        name = "touch_confirmed"
        echo()
        assertNotEquals(0, status().getInt("caps") and 0x400)
        assertTrue(until(3.0, true) { status().getInt("touch") == 3 })
        assertEquals(3, status().getInt("touch_wanted"))
        assertTrue(status().getInt("touch_sent") >= 1)
        assertEquals(3, fake().getInt("touch"))
        assertTrue(fake().getInt("touch_msgs") >= 1)
        val t = LinkStats.touchText(status().getInt("caps"), 3, 3, 1)
        assertTrue(t, t.contains("confirms velocity + pressure"))
        r.put("pass", true)
    }

    @Test
    fun touchVelocityAndPressureReachTheCore() {
        name = "touch_core"
        echo()
        assertTrue(until(3.0, true) { status().getInt("touch") == 3 })
        assertTrue(pressed(4, 37))
        assertEquals(4 shl 8 or 37, est().getInt("key_vel_last"))
        link!!.fakePress(4, 90)                                 // (90 * 100 + 63) / 127 = 71
        assertTrue(until(3.0, true) { est().getInt("key_lean_last") == 4 shl 8 or 71 })
        link!!.fakePress(4, 127)
        assertTrue(until(3.0, true) { est().getInt("key_lean_last") == 4 shl 8 or 100 })
        val evs = link!!.events(0)
        assertTrue(evs.any { it.kind == PanelEvent.Kind.KEY && it.index == 4 && it.value == 1 && it.velocity == 37 })
        assertTrue(evs.any { it.kind == PanelEvent.Kind.PRESSURE && it.index == 4 && it.value == 90 })
        link!!.fakeKey(4, false)
        assertTrue(pressed(5, 120))
        assertEquals(5 shl 8 or 120, est().getInt("key_vel_last"))
        r.put("pass", true)
    }

    @Test
    fun touchOffSendsNoTouch() {
        name = "touch_off"
        echo(flags = 0)
        assertNotEquals(0, status().getInt("caps") and 0x400)
        assertEquals(0, status().getInt("touch_sent"))
        assertEquals(0, fake().getInt("touch_msgs"))
        assertTrue(pressed(2, 37))
        assertEquals("off: velocity 100", 2 shl 8 or 100, est().getInt("key_vel_last"))
        link!!.fakePress(2, 90)
        until(0.6, true) { false }
        assertEquals(0, est().getInt("key_lean_last"))
        r.put("pass", true)
    }

    @Test
    fun touchTurnedOffInASession() {
        name = "touch_toggle"
        echo()
        assertTrue(until(3.0, true) { fake().getInt("touch") == 3 })
        link!!.setKeyTouch(0)
        assertTrue("TOUCH 0 not sent", until(3.0, true) { fake().getInt("touch") == 0 && status().getInt("touch") == 0 })
        assertTrue(pressed(7, 20))
        assertEquals(7 shl 8 or 100, est().getInt("key_vel_last"))
        link!!.setKeyTouch(3)
        assertTrue(until(3.0, true) { fake().getInt("touch") == 3 && status().getInt("touch") == 3 })
        r.put("pass", true)
    }

    @Test
    fun touchOlderFirmwareNothingChanges() {
        name = "touch_old"
        echo(noTouch = true)
        assertEquals(0, status().getInt("caps") and 0x400)
        assertEquals(0, status().getInt("touch_sent"))
        assertEquals(0, fake().getInt("touch_msgs"))
        assertTrue(pressed(3, 37))
        assertEquals(3 shl 8 or 100, est().getInt("key_vel_last"))
        val t = LinkStats.touchText(status().getInt("caps"), 3, 0, 0)
        assertTrue(t, t.startsWith("not in this firmware"))
        r.put("pass", true)
    }

    // ---------------------------------------------------------------- through Android's MIDI service (AMidi)
    private fun virtualService(): FakeFm1Service {
        FakeFm1Service.enable(ctx, true)
        RfmMidi.start(ctx, Fm1Candidate.VIRTUAL_NAME)
        val end = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < end) {
            val svc = FakeFm1Service.instance
            if (svc != null && RfmMidi.entries().any { it.nativeId > 0 && it.desc.type == MidiDesc.TYPE_VIRTUAL }) return svc
            SystemClock.sleep(20)
        }
        throw AssertionError("the virtual FM-1 was not found and opened: ${RfmMidi.entries()}")
    }

    @Test
    fun virtualDeviceSessionOverAMidi() {
        name = "virtual"
        val t0 = SystemClock.elapsedRealtime()
        val svc = virtualService()
        r.put("found_and_opened_ms", SystemClock.elapsedRealtime() - t0)
        val e = RfmMidi.entries().first { it.nativeId > 0 }
        r.put("device", e.desc.logLine).put("candidacy", e.candidacy.why)
        val l = RfmLink(Backend.Device, virtualName = Fm1Candidate.VIRTUAL_NAME)
        link = l
        l.setPattern(true)
        r.put("connect_ms", connect(10.0))
        val st0 = status()
        assertEquals("RemoteFM1", st0.getString("name"))
        assertTrue(st0.getString("frame_link"), st0.getString("frame_link").contains(Fm1Candidate.VIRTUAL_NAME))
        // the pattern for 10 s (its four screens, the noise the worst case), the wire as fast as Android carries it
        val a0 = status().getInt("acks")
        val w0 = status().getInt("wire_bytes")
        val s0 = SystemClock.elapsedRealtime()
        SystemClock.sleep(10000)
        val dt = (SystemClock.elapsedRealtime() - s0) / 1000.0
        val st = status()
        val fs = svc.fake().state()
        r.put("acks_10s", st.getInt("acks") - a0).put("wire_kBps", (st.getInt("wire_bytes") - w0) / dt / 1000.0)
            .put("fps", st.getDouble("fps")).put("ack_ms_avg", st.getInt("ack_ms_avg")).put("ack_ms_max", st.getInt("ack_ms_max"))
            .put("ack_timeouts", st.getInt("ack_timeouts")).put("budget", st.getInt("budget"))
            .put("host_tx_bytes", st.getInt("tx_bytes")).put("host_rx_bytes", st.getInt("rx_bytes"))
            .put("host_short_writes", st.getInt("tx_short")).put("ble", st.getInt("ble"))
            .put("unit_rx_chunks", fs.getInt("rx_chunks")).put("unit_rx_bytes", fs.getInt("rx_bytes"))
            .put("unit_rx_max_chunk", fs.getInt("rx_max")).put("unit_decode_errs", fs.getInt("decode_errs"))
            .put("unit_frames", fs.getInt("frames")).put("unit_tx_chunks", svc.outChunks).put("unit_tx_max", svc.outMax)
        assertTrue("acks over AMidi", st.getInt("acks") - a0 > 20)
        assertEquals(0, fs.getInt("decode_errs"))
        // a key on the virtual unit comes back as a panel event, and lights its LED there
        svc.fake().key(9, true)
        assertTrue("key 9 over AMidi", until(3.0) { l.events(0).any { it.kind == PanelEvent.Kind.KEY && it.index == 9 && it.value == 1 } })
        assertTrue("key 9's LED on the virtual unit", until(3.0) { svc.fake().state().getLong("lit_keys") and (1L shl 9) != 0L })
        svc.fake().key(9, false)
        // TRS MIDI in over the service
        svc.fake().trs(byteArrayOf(0x90.toByte(), 60, 100, 0x80.toByte(), 60, 0))
        assertTrue("TRS MIDI in over AMidi", until(3.0) { status().getInt("trs_midi_in") >= 2 })
        l.stop()
        assertTrue(until(3.0) { svc.fake().state().getInt("session") == 0 })
        r.put("pass", true)
    }

    /** ChoralRoot on the virtual unit over AMidi: a key there plays the core */
    @Test
    fun virtualCoreKeyPlays() {
        name = "virtual_core"
        RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir)
        val svc = virtualService()
        val l = RfmLink(Backend.Device, virtualName = Fm1Candidate.VIRTUAL_NAME)
        link = l
        val e = RfmEngine(l)
        engine = e
        assertEquals(null, e.load("choralroot"))
        assertTrue(e.boot())
        e.setClock(RfmEngine.CLOCK_AUDIO)
        e.setOffline(true)
        e.attach(true)
        r.put("connect_ms", connect(10.0, render = true))
        e.renderBlocks(200, 240, 48000.0, null)
        val k0 = est().getInt("keys_pressed")
        val tk = SystemClock.elapsedRealtime()
        svc.fake().key(7, true)
        assertTrue(until(3.0, render = true) { est().getInt("keys_pressed") > k0 })
        r.put("key_to_core_ms", SystemClock.elapsedRealtime() - tk)
        val lv = DoubleArray(2)
        e.renderBlocks(200, 240, 48000.0, lv)
        svc.fake().key(7, false)
        r.put("held_rms", lv[0]).put("unit_frames", svc.fake().state().getInt("frames"))
        assertTrue("held RMS ${lv[0]}", lv[0] > 0.001)
        r.put("pass", true)
    }

    // ---------------------------------------------------------------- what the first FM-1 test found (2026-11-01)
    private fun virtualLink(): RfmLink {
        val l = RfmLink(Backend.Device, virtualName = Fm1Candidate.VIRTUAL_NAME)
        l.setPattern(false)
        link = l
        return l
    }

    private fun keyComesBack(svc: FakeFm1Service, l: RfmLink, k: Int): Boolean {
        val after = l.events(0).lastOrNull()?.id ?: 0L
        svc.fake().key(k, true)
        val ok = until(3.0) { l.events(after).any { it.kind == PanelEvent.Kind.KEY && it.index == k && it.value == 1 } }
        svc.fake().key(k, false)
        return ok
    }

    /** the app killed (its SESSION 0 never reached the unit) and opened again while the unit keeps its session:
     *  the new link finds it, starts a new session, and the unit's keys come back as events */
    @Test
    fun virtualRelaunchWhileTheUnitKeepsItsSession() {
        name = "virtual_relaunch"
        val svc = virtualService()
        svc.dropIn = false
        svc.muteOut = false
        val l1 = virtualLink()
        connect(10.0)
        assertTrue("first link: keys", keyComesBack(svc, l1, 3))
        val started0 = svc.fake().state().getInt("sessions_started")
        svc.dropIn = true                                       // (SESSION 0 lost)
        l1.close()
        link = null
        svc.dropIn = false
        assertEquals("the unit kept its session", 1, svc.fake().state().getInt("session"))
        val l2 = virtualLink()
        r.put("reconnect_ms", connect(10.0))
        assertTrue("a new SESSION", until(3.0) { svc.fake().state().getInt("sessions_started") > started0 })
        assertTrue("second link: keys", keyComesBack(svc, l2, 4))
        val st = status()
        r.put("sessions_started", svc.fake().state().getInt("sessions_started")).put("rx_bytes", st.getInt("rx_bytes"))
            .put("panel_r", st.getInt("panel_r")).put("pass", true)
    }

    /** RfmMidi sees the device twice (the device callback and a scan, two USB_DEVICE_ATTACHED rescans): one device
     *  for the link, one port pair, keys come back */
    @Test
    fun virtualSeenTwiceIsOpenedOnce() {
        name = "virtual_twice"
        val svc = virtualService()
        RfmMidi.start(ctx, Fm1Candidate.VIRTUAL_NAME)          // (a second scan of the same devices)
        RfmMidi.start(ctx, Fm1Candidate.VIRTUAL_NAME)
        val l = virtualLink()
        connect(10.0)
        l.rescan()
        l.rescan()
        SystemClock.sleep(500)
        val devs = org.json.JSONArray(RfmMidi.nativeList())
        val mine = (0 until devs.length()).map { devs.getJSONObject(it) }.filter { it.getString("name") == Fm1Candidate.VIRTUAL_NAME }
        r.put("native_devices", mine.size).put("midi_links", status().getInt("midi_links"))
        assertEquals("native devices: $devs", 1, mine.size)
        assertEquals(1, status().getInt("midi_links"))
        assertEquals(1, RfmMidi.entries().count { it.nativeId > 0 && it.desc.type == MidiDesc.TYPE_VIRTUAL })
        assertTrue(keyComesBack(svc, l, 5))
        r.put("pass", true)
    }

    /** a write the MIDI service does not take (A1: the session marked the link dead and nothing reopened it while the
     *  device stayed: the screen frozen, the keys dead): the link is dropped and opened again, keys come back */
    @Test
    fun virtualFailedWriteReopensTheLink() {
        name = "virtual_write_fail"
        val svc = virtualService()
        svc.dropIn = false
        svc.muteOut = false
        val l = virtualLink()
        connect(10.0)
        val sessions0 = status().getInt("sessions")
        RfmMidi.testFailWrites(1)
        assertTrue("the write failure seen: ${status()}", until(3.0) { status().getInt("write_fails") >= 1 })
        val t0 = SystemClock.elapsedRealtime()
        assertTrue("reconnected: ${status()}", until(10.0) {
            status().getInt("state") == LinkState.CONNECTED && status().getInt("sessions") > sessions0
        })
        r.put("recover_ms", SystemClock.elapsedRealtime() - t0)
        val st = status()
        assertTrue(st.getInt("link_drops") >= 1)
        assertEquals(1, st.getInt("midi_links"))
        assertTrue("keys after the reopen", keyComesBack(svc, l, 6))
        r.put("write_fails", st.getInt("write_fails")).put("link_drops", st.getInt("link_drops")).put("pass", true)
    }

    /** the unit's side goes quiet (no PANEL_R, nothing at all): the session looks for it again, and after ~6 s the
     *  ports are opened afresh; once it talks again the session comes back and keys work */
    @Test
    fun virtualSilentInputIsReopened() {
        name = "virtual_silent"
        val svc = virtualService()
        svc.dropIn = false
        svc.muteOut = false
        val l = virtualLink()
        connect(10.0)
        svc.muteOut = true
        val t0 = SystemClock.elapsedRealtime()
        assertTrue("unit lost", until(5.0) { status().getInt("state") != LinkState.CONNECTED })
        r.put("lost_ms", SystemClock.elapsedRealtime() - t0)
        assertTrue("ports reopened: ${status()}", until(15.0) { status().getInt("silent_reopens") >= 1 })
        r.put("reopen_ms", SystemClock.elapsedRealtime() - t0)
        svc.muteOut = false
        assertTrue("back: ${status()}", until(10.0) { status().getInt("state") == LinkState.CONNECTED })
        assertTrue("keys after silence", keyComesBack(svc, l, 8))
        val st = status()
        r.put("silent_reopens", st.getInt("silent_reopens")).put("lost", st.getInt("lost"))
            .put("last_event_age_ms", st.getInt("last_event_age_ms")).put("pass", true)
        assertTrue(st.getInt("last_event_age_ms") in 0..5000)
    }

    companion object {
        const val TAG = "RFMLinkTest"

        /** the virtual FM-1 is listed to other apps only while the tests run */
        @JvmStatic
        @AfterClass
        fun disableVirtual() {
            FakeFm1Service.enable(InstrumentationRegistry.getInstrumentation().targetContext, false)
        }

        @Synchronized
        fun save(name: String, r: JSONObject) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val f = File(ctx.filesDir, "linktest.json")
            val all = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
            if (!r.has("pass")) r.put("pass", false)
            all.put(name, r)
            f.writeText(all.toString(2))
            Log.i(TAG, "$name: $r")
        }
    }
}
