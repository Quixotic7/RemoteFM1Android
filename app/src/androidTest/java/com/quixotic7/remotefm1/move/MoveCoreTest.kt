// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Ableton Move's core on the engine (native/rfmcore/MoveLink.cpp on rfm1cs-abletonmove's session, through rfmm_*
// and move_jni.cpp), without a network: a fake Move in place of the socket (rfmm_test_*: it answers the session's HELLO
// and sends a STATUS every 500 ms), the firmware the echo core (its MIDI in back out).
// Input to actions (a pad plays an FM-1 key with its velocity, a button by label, a knob a detent every two ticks,
// a Track tap changes the mode), the LEDs and the screen out, the sequencer's notes into the firmware on the beat (the
// caller's device clock, and the audio clock's frames through the render-ahead ring), the metronome mixed into the
// output, Sample's take cut on bar lines, the projects' requests answered, a lost link tried again (a stale try's
// close ignored), the project file (rfm1cs_file_*: byte for byte the golden files Android's own writer made; a blob of
// the core's 40136-byte maximum read).
package com.quixotic7.remotefm1.move

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.RfmEngine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import android.os.SystemClock
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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
        await("connected") { MoveNative.status(h).state == MoveNative.CONNECTED }
        return e
    }

    /** the device clock a millisecond at a time (with the wall clock's: the session's timers) until [ok] */
    private fun await(what: String, ms: Long = 3000, ok: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!ok()) {
            assertTrue("waited for $what", SystemClock.elapsedRealtime() < end)
            step(1)
            SystemClock.sleep(1)
        }
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
        await("the Sequencer's screen (33 ms on the wall clock)") { MoveNative.testTakeScreen(h, shot) }
        // a lost link lets go of everything; a new one paints every LED again
        press(MoveNative.note(86, 100))
        step(12)
        MoveNative.testConnected(h, false)
        step(12)
        assertEquals(0, keys())
        assertEquals(MoveNative.RETRYING, MoveNative.status(h).state)
        MoveNative.testConnected(h, true)
        step(20)
        await("connected again") { MoveNative.status(h).state == MoveNative.CONNECTED }
        assertTrue(leds().size >= 48)
    }

    /** the link cut: lost (everything let go, the project saved), RETRYING, then connected again by itself after 1 s */
    @Test fun aLostLinkIsTriedAgain() {
        setup()
        val empty = MoveNative.testEmptyProject()!!
        MoveNative.projectsInit(h, 1, 0, empty)
        tap(40); step(5); stepTap(0); step(5)           // a step entered: the project changed
        drainRequests()
        assertEquals(1, MoveNative.status(h).connects)
        MoveNative.testConnected(h, false)
        step(5)
        val st = MoveNative.status(h)
        assertEquals(MoveNative.RETRYING, st.state)
        assertEquals("The link closed: trying again in 1 s...", st.text)       // (rfm1cs_status_text's words)
        assertEquals(MoveNative.TONE_BUSY, st.tone)
        assertEquals("Test: link lost (the link closed).", st.toast)
        assertTrue("flushed on lost", drainRequests().any { it[0] == MoveNative.REQ_PROJECT_SAVE })
        val t0 = SystemClock.elapsedRealtime()
        await("the retry", 4000) { MoveNative.status(h).state == MoveNative.CONNECTED }
        assertTrue("after ~1 s", SystemClock.elapsedRealtime() - t0 >= 800)
        assertEquals(2, MoveNative.status(h).connects)
        assertEquals("Sequencer mode kept", 3, MoveNative.status(h).mode)
        assertTrue("every LED again", leds().size >= 48)
        assertTrue(MoveNative.status(h).text, MoveNative.status(h).text.startsWith("Test connected over this computer (TCP): Sequencer mode."))
    }

    /** a close from an older try's socket (EV_OPEN's attempt id) is ignored; the current try's is a loss */
    @Test fun aStaleTrysCloseIsIgnored() {
        setup()
        val a1 = MoveNative.status(h).attempt
        assertTrue(a1 != 0)
        MoveNative.testConnected(h, false)
        step(5)
        assertEquals(MoveNative.RETRYING, MoveNative.status(h).state)
        await("the retry", 4000) { MoveNative.status(h).state == MoveNative.CONNECTED }
        val a2 = MoveNative.status(h).attempt
        assertTrue("a new try: $a1 -> $a2", a2 != a1)
        MoveNative.testClose(h, a1)                     // the first socket closing late
        step(20)
        assertEquals(MoveNative.CONNECTED, MoveNative.status(h).state)
        assertEquals(2, MoveNative.status(h).connects)
        MoveNative.testClose(h, a2)
        step(5)
        assertEquals(MoveNative.RETRYING, MoveNative.status(h).state)
    }

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("rmvp/$name").use { it.readBytes() }

    /** rfm1cs_file_pack / _unpack (MoveNative.FileCodec) write and read exactly the files Android's own Kotlin wrote
     *  (assets/rmvp: rfm1cs-abletonmove tests/rmvp; MoveTest's KotlinRmvp makes the same bytes on the JVM) */
    @Test fun theProjectFileIsTheSharedOne() {
        val a = asset("blob_a.bin")
        val b = asset("blob_b.bin")
        assertEquals(0, MoveNative.projectCheck(a))
        val c = MoveNative.FileCodec
        val one = asset("android.rfmmove")
        val set = asset("android.rfmmoveset")
        assertArrayEquals(one, c.pack(listOf(MoveProject(3, a, 1_794_909_600_000L)), MoveProjects.KIND_ONE))
        assertArrayEquals(one, c.pack(listOf(MoveProject(3, a, 1_794_909_600_000L))))
        assertArrayEquals(set, c.pack(listOf(MoveProject(0, a, 1_794_909_600_000L), MoveProject(5, b, 1_794_990_615_000L))))
        val u = c.unpack(set)
        assertEquals(MoveProjects.KIND_SET, u.kind)
        assertEquals(listOf(0, 5), u.entries.map { it.slot })
        assertEquals(listOf(1_794_909_600_000L, 1_794_990_615_000L), u.entries.map { it.saved })
        assertArrayEquals(a, u.entries[0].bytes)
        assertArrayEquals(b, u.entries[1].bytes)
        assertArrayEquals(set, c.pack(u.entries))
        // saved 0 (unknown): read as now
        val t0 = System.currentTimeMillis()
        assertTrue(c.unpack(c.pack(listOf(MoveProject(1, a, 0)))).entries[0].saved >= t0)
        // the store's file for a slot is that file (files/move/move-project-NN.rfmmove)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "move-file-test").apply { deleteRecursively(); mkdirs() }
        val store = MoveProjects(dir, c)
        store.put(3, a, 1_794_909_600_000L)
        assertArrayEquals(one, File(dir, "move-project-04.rfmmove").readBytes())
        assertArrayEquals(a, store.get(3)!!.bytes)
        dir.deleteRecursively()
        // refused, in the web player's words (this app)
        fun refused(f: ByteArray?, says: String) {
            try { c.unpack(f); fail("accepted: $says") } catch (e: IllegalArgumentException) { assertEquals(says, e.message) }
        }
        refused(null, "Not a Move project file: nothing to read.")
        refused(byteArrayOf(1, 2, 3), "Not a Move project file: it does not start with RMVP.")
        refused(one.copyOf().also { it[4] = 2 }, "Not a Move project file: version 2; this app reads version 1.")
        refused(one.copyOf().also { it[5] = 3 }, "Not a Move project file: kind 3.")
        refused(one.copyOf().also { it[6] = 2 }, "Not a Move project file: 2 projects.")
        refused(one.copyOf().also { it[8] = 40 }, "Not a Move project file: slot 41 out of range.")
        refused(one.copyOf(one.size - 1), "Not a Move project file: project 1 is cut off.")
        refused(one + byteArrayOf(0), "Not a Move project file: 1 bytes after the last project.")
        refused(one.copyOf().also { it[40] = (it[40].toInt() xor 0x55).toByte() }, "Not a Move project file: project 1 (slot 4) is damaged.")
        refused(c.pack(listOf(MoveProject(4, a, 0), MoveProject(4, b, 0))), "Not a Move project file: slot 5 twice.")
        refused(ByteArray(8 + 32 * (10 + MoveNative.projectMax) + 1).also { byteArrayOf(0x52, 0x4d, 0x56, 0x50).copyInto(it) },
            "Not a Move project file: it is 1284681 bytes, more than a full set (1284680).")   // (0.5.0: 40136 a project)
    }

    /** the largest project the core makes (4096 notes, 2048 CC locks: RFM_MOVE_PROJECT_MAX, 40136 bytes since 0.5.0's
     *  version 6) is a valid blob, and its file is written and read (the old Kotlin reader stopped at 39072) */
    @Test fun aFullProjectOf40136Bytes() {
        val empty = MoveNative.testEmptyProject()!!
        val offNotes = 24 + 16 * 4 + 256 * 2                // (project.c OFF_NOTES: the note count, then the notes)
        assertEquals(0, (empty[offNotes].toInt() and 255) or (empty[offNotes + 1].toInt() shl 8))
        val o = java.io.ByteArrayOutputStream()
        o.write(empty, 0, offNotes)
        o.write(byteArrayOf(0x00, 0x10))                    // 4096 notes: clip, step, note, vel, micro, len16
        for (i in 0 until 4096) o.write(byteArrayOf((i / 256).toByte(), (i % 256).toByte(), 60, 100, 0, 24, 0))
        o.write(byteArrayOf(0x00, 0x08))                    // 2048 locks: clip, step, cc, value
        for (i in 0 until 2048) o.write(byteArrayOf((i / 256).toByte(), (i % 256).toByte(), 1, 64))
        o.write(empty, offNotes + 4, empty.size - offNotes - 4)   // (the extension, the check)
        val full = MoveProjects.withColor(o.toByteArray(), empty[18].toInt())   // (the FNV-1a made again)
        assertEquals(40136, full.size)
        assertEquals(MoveNative.projectMax, full.size)
        assertEquals(0, MoveNative.projectCheck(full))
        val f = MoveNative.FileCodec.pack(listOf(MoveProject(9, full, 1_794_909_600_000L)))
        assertEquals(8 + 10 + 40136, f.size)
        assertArrayEquals(full, MoveNative.FileCodec.unpack(f).entries[0].bytes)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "move-full-test").apply { deleteRecursively(); mkdirs() }
        val store = MoveProjects(dir, MoveNative.FileCodec)
        store.put(9, full)
        assertArrayEquals(full, store.get(9)!!.bytes)
        dir.deleteRecursively()
        // one byte more is not a project
        assertTrue(MoveNative.projectCheck(full + byteArrayOf(0)) != 0)
    }

    /** PROJECT_LOAD answered with a blob that is not a project: an empty project there (Android's behaviour) */
    @Test fun anInvalidProjectLoadsEmpty() {
        setup()
        val empty = MoveNative.testEmptyProject()!!
        MoveNative.projectsInit(h, 1, 0, empty)
        step(5)
        assertEquals(0, MoveNative.status(h).project)
        MoveNative.projectAnswer(h, 3, byteArrayOf(1, 2, 3, 4, 5))
        step(5)
        await("the slot") { MoveNative.status(h).project == 3 }
    }

    private fun drainRequests(): List<IntArray> {
        val out = IntArray(4)
        val data = ByteArray(MoveNative.projectMax)
        val reqs = ArrayList<IntArray>()
        while (MoveNative.takeRequest(h, out, data) >= 0) reqs += out.copyOf()
        return reqs
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
