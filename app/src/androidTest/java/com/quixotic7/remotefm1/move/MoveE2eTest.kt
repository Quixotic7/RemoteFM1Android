// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// End to end against Move Remote's simulator (abletonmoveremote's moveremote-sim, on the Mac: the emulator reaches it at
// 10.0.2.2), over the real socket thread and rfm1cs-abletonmove's session: scripts/move_e2e.sh starts the simulator, runs this with
// `-e moveSim 10.0.2.2:<port>`, waits for the link, then presses through the simulator's test FIFO: a pad (an FM-1
// key), Track 4, steps 1 and 5, Play. This side checks the key played, the LEDs and screens went out, the mode, and
// the sequencer's notes reached the firmware (the echo core) on the beat; (0.5.10) the firmware browser with the
// bundled cores as its list: the script's Track 1 held + step 1, Felucca's pad (cell 2, A-Z) and the jog's click ask
// for a load, done here as the app does (the core into the engine, progress 100, slot 0 said back); then the socket is
// cut and the session connects again by itself. Skipped without the argument.
package com.quixotic7.remotefm1.move

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.RfmEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveE2eTest {
    @Test fun againstTheSimulator() {
        val sim = InstrumentationRegistry.getArguments().getString("moveSim")
        assumeTrue("no -e moveSim host:port", !sim.isNullOrEmpty())
        val e = RfmEngine(null)
        val h = MoveNative.create(null)
        try {
            assertTrue(e.testLoadEcho())
            assertTrue(e.boot())
            e.midiOutEnable(2)
            e.setClock(RfmEngine.CLOCK_CALLER)
            e.moveAttach(h)
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir)
            val list = MoveCatalog.build(com.quixotic7.remotefm1.firmware.CoreInfo.builtInList(RfmEngine.cores()), emptyMap(),
                emptyList(), { com.quixotic7.remotefm1.firmware.InstallState.NotInstalled }, emptyMap())
            assertEquals("Felucca's pad in the script: ${list.map { it.id }}", 2, list.indexOfFirst { it.id == "felucca" })
            MoveNative.fwList(h, list)
            MoveNative.start(h, sim!!)
            val panel = IntArray(56)
            val pk = IntArray(64)
            val ons = ArrayList<Long>()
            var keyZero = false
            var connected = false
            var ms = 0L
            val end = SystemClock.elapsedRealtime() + 60_000
            var last = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() < end) {
                // the device clock at the wall clock's pace (the Move's taps are timed by it)
                val now = SystemClock.elapsedRealtime()
                val d = (now - last).toInt()
                if (d > 0) { e.run(d); ms += d; last = now }
                e.panel(panel)
                if (panel[1] and 1 != 0) keyZero = true
                val n = e.midiOutTake(pk)
                for (i in 0 until n) if ((pk[i] shr 8 and 0xF0) == 0x90 && (pk[i] ushr 24) > 0) ons += ms
                val st = MoveNative.status(h)
                if (st.state == MoveNative.CONNECTED && !connected) { connected = true; Log.i("RFMMoveE2e", "connected: ${st.text}") }
                if (connected && keyZero && st.mode == 3 && ons.size >= 3) break
                SystemClock.sleep(2)
            }
            // (connected 3 s more: the script reads the simulator's state and screen shot meanwhile)
            val hold = SystemClock.elapsedRealtime() + 3000
            while (SystemClock.elapsedRealtime() < hold) {
                val now = SystemClock.elapsedRealtime()
                if (now > last) { e.run((now - last).toInt()); last = now }
                SystemClock.sleep(2)
            }
            val st = MoveNative.status(h)
            Log.i("RFMMoveE2e", "state ${st.state} '${st.text}' name ${st.name} via ${st.via} key0 $keyZero mode ${st.mode} " +
                "inputs ${st.inputs} leds ${st.leds} screens ${st.screens} seqMidi ${st.seqMidi} note-ons at $ons")
            assertTrue("connected: ${st.text}", connected)
            assertEquals(MoveNative.CONNECTED, st.state)
            assertTrue("the simulator's name", st.name.isNotEmpty())
            assertTrue("the line: ${st.text}", st.text.startsWith("${st.name} connected over ") &&
                st.text.endsWith("(TCP): Sequencer mode. Track 1..4 pick the mode."))
            assertTrue("a pad played key 0", keyZero)
            assertTrue("LEDs written: ${st.leds}", st.leds >= 48)
            assertTrue("screens sent: ${st.screens}", st.screens >= 2)
            assertEquals("Sequencer mode", 3, st.mode)
            assertTrue("note-ons $ons", ons.size >= 3)
            assertTrue("step 5 after step 1: ${ons[1] - ons[0]} ms", kotlin.math.abs(ons[1] - ons[0] - 500) <= 2)
            assertTrue("the bar: ${ons[2] - ons[0]} ms", kotlin.math.abs(ons[2] - ons[0] - 2000) <= 2)
            // the firmware browser: the script's presses ask for Felucca; loaded here, then slot 0 said back
            val req = IntArray(4)
            val data = ByteArray(MoveNative.projectMax)
            var load: String? = null
            val asked = SystemClock.elapsedRealtime() + 20_000
            while (load == null && SystemClock.elapsedRealtime() < asked) {
                val now = SystemClock.elapsedRealtime()
                if (now > last) { e.run((now - last).toInt()); last = now }
                while (true) {
                    val n = MoveNative.takeRequest(h, req, data)
                    if (n < 0) break
                    if (req[0] == MoveNative.REQ_FW_LOAD) load = String(data, 0, n, Charsets.US_ASCII)
                }
                SystemClock.sleep(2)
            }
            Log.i("RFMMoveE2e", "browser: FW_LOAD ${load ?: "never"}; ${MoveNative.testFw(h).lineSequence().first()}")
            assertEquals("the jog's click loads Felucca", "felucca", load)
            assertEquals(null, e.load("felucca"))
            assertTrue(e.boot())
            MoveNative.fwProgress(h, "felucca", 100)
            MoveNative.fwSlot(h, "felucca")
            val said = SystemClock.elapsedRealtime() + 3000
            while (MoveNative.testFw(h).lineSequence().first().split(' ')[3] != "felucca" && SystemClock.elapsedRealtime() < said) {
                val now = SystemClock.elapsedRealtime()
                if (now > last) { e.run((now - last).toInt()); last = now }
                SystemClock.sleep(2)
            }
            assertEquals("felucca", MoveNative.testFw(h).lineSequence().first().split(' ')[3])
            assertEquals("felucca", e.status().optString("core_id"))
            // the socket cut under the session: lost, RETRYING, connected again by itself (~1 s)
            MoveNative.testDrop(h)
            var retrying = false
            val back = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < back) {
                val now = SystemClock.elapsedRealtime()
                if (now > last) { e.run((now - last).toInt()); last = now }
                val s2 = MoveNative.status(h)
                if (s2.state == MoveNative.RETRYING) retrying = true
                if (retrying && s2.state == MoveNative.CONNECTED) break
                SystemClock.sleep(2)
            }
            val s3 = MoveNative.status(h)
            Log.i("RFMMoveE2e", "after the cut: state ${s3.state} '${s3.text}' connects ${s3.connects}")
            assertTrue("lost after the cut", retrying)
            assertEquals("connected again", MoveNative.CONNECTED, s3.state)
            assertEquals(2, s3.connects)
        } finally {
            MoveNative.stop(h)
            e.moveAttach(0)
            e.setClock(RfmEngine.CLOCK_NONE)
            SystemClock.sleep(200)
            MoveNative.free(h)
            e.close()
        }
    }
}
