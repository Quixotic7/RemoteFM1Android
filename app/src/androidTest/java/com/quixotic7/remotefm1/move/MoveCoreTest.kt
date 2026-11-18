// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Ableton Move's core on the engine (native/rfmcore/MoveLink.cpp through rfmm_* and move_jni.cpp), without a
// network: the network thread's side is the tests' (rfmm_test_*), the firmware the echo core (its MIDI in back out).
// Input to actions (a pad plays an FM-1 key with its velocity, a button by label, a knob a detent every two ticks,
// a Track tap changes the mode), the LEDs and the screen out, the sequencer's notes into the firmware on the beat (the
// caller's device clock, and the audio clock's frames through the render-ahead ring), the metronome mixed into the
// output, Sample's take cut on bar lines, and the projects' requests answered.
package com.quixotic7.remotefm1.move

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quixotic7.remotefm1.RfmEngine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MoveCoreTest {
    private var engine: RfmEngine? = null
    private var h = 0L
    private val panel = IntArray(56)

    @After fun done() {
        engine?.moveAttach(0)
        engine?.setClock(RfmEngine.CLOCK_NONE)
        if (h != 0L) MoveNative.free(h)
        engine?.close()
    }

    /** the echo core on the caller's clock, a Move core on it, "connected" */
    private fun setup(clock: Int = RfmEngine.CLOCK_CALLER, settings: ByteArray? = null): RfmEngine {
        val e = RfmEngine(null)
        engine = e
        assertTrue(e.testLoadEcho())
        assertTrue(e.boot())
        e.midiOutEnable(2)
        e.setClock(clock)
        audio = clock == RfmEngine.CLOCK_AUDIO
        if (audio) e.setOffline(true)
        h = MoveNative.create(settings)
        e.moveAttach(h)
        MoveNative.testConnected(h, true)
        step(5)
        return e
    }

    private var audio = false
    /** [ms] of the device clock: the caller's, or the audio clock's renders (10 ms each, offline) */
    private fun step(ms: Int) { if (audio) engine!!.renderBlocks(maxOf(1, ms / 10), 480, 48000.0) else engine!!.run(ms) }
    private fun keys(): Int { engine!!.panel(panel); return panel[1] }
    private fun labels(): Int { engine!!.panel(panel); return panel[2] }
    private fun enc(role: Int): Int { engine!!.panel(panel); return panel[3 + role] }
    private fun press(packet: Int) = MoveNative.testInput(h, packet)
    private fun tap(cc: Int) { press(MoveNative.cc(cc, 127)); press(MoveNative.cc(cc, 0)) }
    private fun stepTap(i: Int) { press(MoveNative.note(16 + i, 127)); press(MoveNative.note(16 + i, 0)) }
    private fun leds(): List<ByteArray> {
        val b = ByteArray(6 * 256)
        val out = ArrayList<ByteArray>()
        while (true) {
            val n = MoveNative.testTakeLeds(h, b)
            for (i in 0 until n) out += b.copyOfRange(6 * i, 6 * i + 6)
            if (n < 256) return out
        }
    }

    @Test fun padsButtonsAndKnobsPlayThePanel() {
        setup()
        assertEquals(MoveNative.CONNECTED, MoveNative.status(h).state)
        // FM-1 mode: the second row from the top (pads 16..23, notes 84..) are the white keys F3..F4
        press(MoveNative.note(84, 90))
        step(12)
        assertEquals(1 shl 0, keys())
        press(MoveNative.note(85, 90))
        step(12)
        assertEquals((1 shl 0) or (1 shl 2), keys())
        press(MoveNative.note(84, 0)); press(MoveNative.note(85, 0))
        step(12)
        assertEquals(0, keys())
        // Play (CC 85) is the FM-1's PLAY: matrix button 12 (proto/rfm1.h's order, as the unit sends it), which the
        // firmware's own wiring names (the echo core's is 1:1, so here the label numbered 12)
        val e = engine!!
        val play = (0 until 14).first { e.matrixButtonId(it) == 12 }
        press(MoveNative.cc(85, 127))
        step(12)
        assertEquals(1 shl play, labels())
        press(MoveNative.cc(85, 0))
        step(12)
        assertEquals(0, labels())
        // knob 1 (CC 71) is PRESETS, matrix encoder 6: a detent every two ticks
        val presets = (0 until 7).first { e.matrixEncoderId(it) == 6 }
        val before = enc(presets)
        repeat(4) { press(MoveNative.cc(71, 1)) }
        step(12)
        assertEquals(before + 2, enc(presets))
        assertTrue(MoveNative.status(h).inputs >= 10)
    }

    @Test fun ledsAndScreenComeBack() {
        setup()
        step(40)
        val first = leds()
        assertTrue("every LED at a new connection: ${first.size}", first.size >= 48)
        val shot = ByteArray(1024)
        assertTrue(MoveNative.testTakeScreen(h, shot))
        assertTrue("the FM-1 mode drawing", shot.any { it.toInt() != 0 })
        step(40)
        assertEquals("nothing changed: no LED written", 0, leds().size)
        press(MoveNative.note(84, 100))                 // a pressed pad shows the mode colour
        step(10)
        val pad = leds().filter { it[1].toInt() == 0 && it[2].toInt() == 84 }
        assertEquals(1, pad.size)
        assertEquals(1, pad[0][0].toInt())              // (RGB)
        press(MoveNative.note(84, 0))
        tap(40)                                          // Track 4: the sequencer, its screen named
        step(40)
        assertEquals(3, MoveNative.status(h).mode)
        assertTrue(MoveNative.testTakeScreen(h, shot))
        // a lost link lets go of everything; a new one paints every LED again
        press(MoveNative.note(86, 100))
        step(12)
        MoveNative.testConnected(h, false)
        step(12)
        assertEquals(0, keys())
        MoveNative.testConnected(h, true)
        step(20)
        assertTrue(leds().size >= 48)
    }

    /** Track 4, steps 1 and 5 tapped (the root), Play: the notes into the firmware 500 ms apart at 120 BPM */
    private fun program() {
        tap(40)
        step(5)
        stepTap(0)
        step(5)
        stepTap(4)
        step(5)
        tap(85)                                          // Play
    }

    private fun noteOns(pk: IntArray): List<Int> = pk.filter { (it shr 8 and 0xF0) == 0x90 && (it ushr 24) > 0 }

    @Test fun sequencerOnTheDeviceClock() {
        val e = setup()
        program()
        val at = ArrayList<Int>()
        val note = ArrayList<Int>()
        val pk = IntArray(64)
        for (ms in 0 until 2200) {
            step(1)
            val n = e.midiOutTake(pk)
            for (i in 0 until n) if ((pk[i] shr 8 and 0xF0) == 0x90 && (pk[i] ushr 24) > 0) { at += ms; note += pk[i] shr 16 and 127 }
        }
        assertTrue("notes: $at", at.size >= 3)
        assertEquals("step 1 then step 5 (500 ms), then step 1 again (2 s bar)", 500, at[1] - at[0])
        assertEquals(1500, at[2] - at[1])
        assertEquals(note[0], note[1])
        assertTrue(MoveNative.status(h).playing)
        assertTrue(MoveNative.status(h).seqMidi >= 3)
    }

    @Test fun sequencerFramesOnTheAudioClock() {
        val e = setup(RfmEngine.CLOCK_AUDIO)
        e.renderBlocks(20, 480, 48000.0)
        program()
        val pk = e.renderMidiAt(500, 480, 48000.0)       // 5 s at 48 kHz
        val ons = ArrayList<Int>()
        for (i in pk.indices step 2) if ((pk[i + 1] shr 8 and 0xF0) == 0x90 && (pk[i + 1] ushr 24) > 0) ons += pk[i]
        assertTrue("note-ons at $ons", ons.size >= 4)
        // a 16th at 120 BPM and 48 kHz is 6000 frames: step 5 is 24000 after step 1, the bar 96000; the device clock's
        // millisecond (48 frames at 48 kHz, 44.1 kHz inside) is the grain
        assertTrue("${ons[1] - ons[0]}", abs(ons[1] - ons[0] - 24000) <= 96)
        assertTrue("${ons[2] - ons[0]}", abs(ons[2] - ons[0] - 96000) <= 96)
        assertTrue("${ons[3] - ons[1]}", abs(ons[3] - ons[1] - 96000) <= 96)
    }

    @Test fun metronomeAndSampleOnTheAudioClock() {
        val e = setup(RfmEngine.CLOCK_AUDIO)
        e.renderBlocks(20, 480, 48000.0)
        program()
        // Shift (CC 49) + step 6: the metronome on
        press(MoveNative.cc(49, 127)); stepTap(5); press(MoveNative.cc(49, 0))
        val lv = DoubleArray(2)
        e.renderBlocks(100, 480, 48000.0, lv)            // 1 s
        assertTrue("clicks: ${MoveNative.status(h).clicks}", MoveNative.status(h).clicks >= 1)
        assertTrue("a click heard over the silent echo core: ${lv[1]}", lv[1] > 0.05)
        // Sample (CC 118): armed, records from the next bar line; again: ends at the next one
        tap(118)
        e.renderBlocks(300, 480, 48000.0)                // 3 s
        tap(118)
        e.renderBlocks(400, 480, 48000.0)                // 4 s: past the next bar line
        val out = IntArray(4)
        val data = ByteArray(MoveNative.projectMax)
        val reqs = ArrayList<IntArray>()
        while (true) { if (MoveNative.takeRequest(h, out, data) < 0) break; reqs += out.copyOf() }
        val start = reqs.firstOrNull { it[0] == MoveNative.REQ_SAMPLE_START }
        val stop = reqs.firstOrNull { it[0] == MoveNative.REQ_SAMPLE_STOP }
        assertNotNull("start in $reqs", start)
        assertNotNull("stop in ${reqs.map { it.toList() }}", stop)
        assertEquals(48000, start!![1 + 1])
        assertTrue("bars ${stop!![1]}", stop[1] >= 1)
        assertEquals(1200, stop[2])
        assertEquals("exactly the bars: ${stop[3]}", stop[1] * 96000, stop[3])
        val pcm = ShortArray(2 * 8192)
        var got = 0
        while (true) { val n = MoveNative.takeAudio(h, pcm); if (n == 0) break; got += n }
        assertTrue("$got frames for a take of ${stop[3]}", got >= stop[3] && got < stop[3] + 960)
    }

    @Test fun projectsAndSettingsRequests() {
        setup()
        val empty = MoveNative.testEmptyProject()!!
        assertEquals(0, MoveNative.projectCheck(empty))
        assertTrue(MoveNative.projectCheck(empty.copyOf(empty.size - 1)) != 0)
        MoveNative.projectsInit(h, 1, 0, empty)
        step(5)
        // a mode change is a settings change: its image to keep
        tap(41)                                          // Track 3: Control mode
        step(5)
        val out = IntArray(4)
        val data = ByteArray(MoveNative.projectMax)
        var settings: ByteArray? = null
        while (true) { val n = MoveNative.takeRequest(h, out, data); if (n < 0) break; if (out[0] == MoveNative.REQ_SETTINGS) settings = data.copyOf(n) }
        assertNotNull(settings)
        assertArrayEquals(byteArrayOf(0x52, 0x4d, 0x56), settings!!.copyOf(3))
        // a new core from that image starts in Control mode
        val h2 = MoveNative.create(settings)
        assertEquals(0, MoveNative.status(h2).state)
        MoveNative.free(h2)
        // a step entered, then the autosave: the project's blob, valid
        tap(40); step(5); stepTap(0); step(5)
        MoveNative.projectFlush(h)
        step(5)
        var blob: ByteArray? = null
        var slot = -1
        while (true) { val n = MoveNative.takeRequest(h, out, data); if (n < 0) break; if (out[0] == MoveNative.REQ_PROJECT_SAVE) { blob = data.copyOf(n); slot = out[1] } }
        assertNotNull("the autosave", blob)
        assertEquals(0, slot)
        assertEquals(0, MoveNative.projectCheck(blob!!))
        assertTrue(blob.size > empty.size)
        assertArrayEquals(MoveProjects.withColor(blob, 5), MoveNative.withColor(blob, 5))
    }
}
