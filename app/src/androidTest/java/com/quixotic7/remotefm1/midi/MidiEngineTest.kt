// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/MidiEngineTests.swift
// A6 in the engine (the caller's clock, ChoralRoot from the APK): a controller's MIDI (rfme_midi_in), the learned map
// (rfme_set_midi_map: test.mjs's MidiMap.feed checks, run where the app runs them), MASTER's pickup, learn diverting
// messages (rfme_learn), and MidiLearn end to end; the map under the render-ahead clock; a web manager's SysEx through
// the echo core and back (iOS ManagerTests.testSysExThroughTheFirmwareAndBack).
package com.quixotic7.remotefm1.midi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.RfmEngine
import com.quixotic7.remotefm1.managers.UsbMidiDecoder
import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

fun cc(n: Int, v: Int, ch: Int = 1) = byteArrayOf((0xB0 or (ch - 1)).toByte(), n.toByte(), v.toByte())
fun noteOn(n: Int, v: Int = 100, ch: Int = 1) = byteArrayOf((0x90 or (ch - 1)).toByte(), n.toByte(), v.toByte())
fun noteOff(n: Int, ch: Int = 1) = byteArrayOf((0x80 or (ch - 1)).toByte(), n.toByte(), 0)

@RunWith(AndroidJUnit4::class)
class MidiEngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var e: RfmEngine
    private val p = IntArray(56)

    // RFME_E_*, the panel's layout
    private val K1 = 3; private val K2 = 4; private val SELECT = 0
    private val B_FX = 0; private val B_HOME = 6
    private val KEYS_ANY = 0; private val KEYS_OFF = -1

    @Before fun setUp() {
        RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir)
        e = RfmEngine()
    }

    @After fun tearDown() {
        e.setClock(RfmEngine.CLOCK_NONE)
        e.close()
    }

    private fun core() {
        assertNull(e.load("choralroot"))
        assertTrue(e.boot())
        e.setClock(RfmEngine.CLOCK_CALLER)
        e.run(1500)
    }

    private fun send(d: ByteArray, firmware: Boolean = false) = assertTrue(e.midiIn(d, toFirmware = firmware))

    /** one message, then the panel as the clock publishes it */
    private fun feed(d: ByteArray): IntArray { send(d); return panel() }

    private fun panel(): IntArray { e.run(20); e.panel(p); return p.copyOf() }

    private fun map(list: List<MidiMapping>, speed: Double = 1.0) {
        val m = MidiMap(list, speed)
        e.setMidiMap(m.entries, m.speed.toFloat())
        e.run(2)                                        // (taken at the next millisecond)
    }

    private fun enc(p: IntArray, role: Int) = p[PanelFeed.I_ENC + role]
    private fun keys(p: IntArray) = p[PanelFeed.I_KEYS]
    private fun labels(p: IntArray) = p[PanelFeed.I_LABELS]
    private fun st(k: String) = e.status().optLong(k)

    /** an absolute pot on KN1 at each speed: 40 41 43 42 -> its detents (the first value only sets the start) */
    @Test fun absolutePotTurnsAnEncoder() {
        core()
        for ((n, c) in listOf(1.0 to listOf(0, 1, 3, 2), 2.0 to listOf(0, 2, 6, 4), 0.5 to listOf(0, 0, 1, 1)).withIndex()) {
            map(listOf(MidiMapping("cc:1:${21 + n}", "KN1", MidiMode.ABS)), c.first)
            val base = enc(panel(), K1)
            val got = listOf(40, 41, 43, 42).map { enc(feed(cc(21 + n, it)), K1) - base }
            assertEquals("absolute at ${c.first}x", c.second, got)
        }
    }

    /** a pot learn saw at 10, mapped at 1/2x: 11 12 13 -> 0 1 0, back to 10: -1 (the half step carries over) */
    @Test fun halfSpeedCarriesTheFraction() {
        core()
        e.learn(true)
        send(cc(24, 10))
        e.run(2)
        val msg = ByteArray(3)
        assertTrue(e.learnTake(msg))
        assertArrayEquals(cc(24, 10), msg)
        e.learn(false)
        map(listOf(MidiMapping("cc:1:24", "KN2", MidiMode.ABS)), 0.5)
        var last = enc(panel(), K2)
        val steps = listOf(11, 12, 13, 10).map { v -> val now = enc(feed(cc(24, v)), K2); (now - last).also { last = now } }
        assertEquals(listOf(0, 1, 0, -1), steps)
    }

    @Test fun relativeModes() {
        core()
        fun rel(n: Int, mode: MidiMode, vals: List<Int>, speed: Double = 1.0): List<Int> {
            map(listOf(MidiMapping("cc:1:$n", "SELECT", mode)), speed)
            var last = enc(panel(), SELECT)
            return vals.map { v -> val now = enc(feed(cc(n, v)), SELECT); (now - last).also { last = now } }
        }
        assertEquals("binary offset", listOf(1, 2, -1, -4, 0), rel(30, MidiMode.REL64, listOf(65, 66, 63, 60, 64)))
        assertEquals("two's complement", listOf(1, 3, -1, -3, -64), rel(31, MidiMode.REL2C, listOf(1, 3, 127, 125, 64)))
        assertEquals("sign bit", listOf(1, 3, -1, -3), rel(32, MidiMode.RELSIGN, listOf(1, 3, 65, 67)))
        assertEquals("the speed scales a relative encoder too", listOf(2), rel(33, MidiMode.REL64, listOf(65), 2.0))
    }

    /** a note and a CC on HOME: each presses and releases it; two sources hold it until both let go. A mapped message
     *  is consumed (no key, nothing to midi_in); another number or channel flows on; a note stored on a knob does too */
    @Test fun buttonsAndConsumption() {
        core()
        val home = 1 shl B_HOME
        map(listOf(MidiMapping("note:1:60", "HOME"), MidiMapping("cc:1:64", "HOME"), MidiMapping("cc:1:21", "KN1")))
        val held = listOf(noteOn(60), cc(64, 127), noteOff(60), cc(64, 0), cc(64, 63)).map { labels(feed(it)) and home != 0 }
        assertEquals(listOf(true, true, true, false, false), held)
        assertEquals("a mapped note plays no key", 0, keys(panel()))
        val m0 = st("midi_mapped")
        val c0 = st("midi_to_core")
        feed(cc(21, 3))
        assertEquals(1, st("midi_mapped") - m0)
        assertEquals("a mapped CC never reaches midi_in", c0, st("midi_to_core"))
        feed(cc(21, 3, 2))
        assertEquals("the same CC on another channel does", 1, st("midi_to_core") - c0)
        // a note stored on a knob is ignored by the engine too: it plays its key
        val bad = MidiMap(listOf(MidiMapping("note:1:62", "HOME"))).entries
        bad[3] = K1
        e.setMidiMap(bad, 1f)
        assertNotEquals(0, keys(feed(noteOn(62))) and (1 shl 9))
        feed(noteOff(62))
    }

    /** a new map lets go of what the old one held */
    @Test fun newMapReleasesItsButtons() {
        core()
        val fx = 1 shl B_FX
        map(listOf(MidiMapping("cc:1:80", "FX")))
        assertNotEquals(0, labels(feed(cc(80, 127))) and fx)
        map(emptyList())
        assertEquals(0, labels(panel()) and fx)
    }

    /** test.mjs's pickup checks: a pot away from MASTER waits (master_pot, pickup_seq once), crossing or reaching it
     *  picks MASTER up, MASTER moved by anything else: it waits again */
    @Test fun masterPickup() {
        core()
        val M = PanelFeed.I_MASTER
        val POT = PanelFeed.I_MASTER_POT
        val SEQ = PanelFeed.I_PICKUP_SEQ
        map(listOf(MidiMapping("cc:1:22", "MASTER"), MidiMapping("cc:1:23", "MASTER")))
        assertEquals("MASTER unknown (just booted): set at once", 516, feed(cc(22, 64))[M])
        assertEquals(-1, panel()[POT])
        e.event(PanelFeed.EV_MASTER, 0, 768)              // (96.0 in CC steps)
        val seq0 = panel()[SEQ]
        val a1 = feed(cc(22, 20)); val a2 = feed(cc(22, 40)); val a3 = feed(cc(22, 60))
        assertEquals("a pot below MASTER is ignored", listOf(768, 768, 768), listOf(a1[M], a2[M], a3[M]))
        assertEquals("where the pot is", listOf(161, 322, 483), listOf(a1[POT], a2[POT], a3[POT]))
        assertEquals("the first ignore says so once", (seq0 + 1) and 0xFFFF, a3[SEQ])
        val a4 = feed(cc(22, 100))
        assertEquals("crossing MASTER's position (60 -> 100) picks it up", 806, a4[M])
        assertEquals(-1, a4[POT])
        feed(cc(22, 127))
        assertEquals(1023, panel()[M])
        assertEquals("then it follows directly", 0, feed(cc(22, 0))[M])
        e.event(PanelFeed.EV_MASTER, 0, 512)              // (the screen moved MASTER)
        e.run(2)
        val a5 = feed(cc(22, 10))
        assertEquals("after MASTER moves by anything else, the pot waits again", 512, a5[M])
        assertEquals((seq0 + 2) and 0xFFFF, a5[SEQ])
        assertEquals("within 2 steps of MASTER (63 ~ 512) picks it up", 507, feed(cc(22, 63))[M])
        e.event(PanelFeed.EV_MASTER, 0, 768)
        e.run(2)
        assertEquals("a pot not seen before picks up at once within 2 steps", 765, feed(cc(23, 95))[M])
    }

    /** learn armed: CCs, note-ons and the note-offs of the notes it took go to its queue, nowhere else */
    @Test fun learnDivertsMessages() {
        core()
        map(listOf(MidiMapping("cc:1:21", "KN1")))
        val k1 = enc(panel(), K1)
        e.learn(true)
        val l0 = st("midi_learned")
        val c0 = st("midi_to_core")
        feed(noteOn(62))
        assertEquals("a note-on learn takes plays nothing", 0, keys(panel()))
        feed(noteOff(62))
        feed(cc(21, 5))
        feed(cc(21, 90))
        assertEquals("nor does a mapped CC", k1, enc(panel(), K1))
        feed(byteArrayOf(0xE0.toByte(), 0, 64))           // (pitch bend: not learn's)
        assertEquals(4, st("midi_learned") - l0)
        assertEquals(1, st("midi_to_core") - c0)
        val got = ArrayList<List<Byte>>()
        val msg = ByteArray(3)
        while (e.learnTake(msg)) got.add(msg.toList())
        assertEquals(listOf(noteOn(62), noteOff(62), cc(21, 5), cc(21, 90)).map { it.toList() }, got)
        e.learn(false)
        assertNotEquals("disarmed: notes play again", 0, keys(feed(noteOn(62))) and (1 shl 9))
        feed(noteOff(62))
        assertEquals("the pot turns from what learn saw (90)", 1, enc(feed(cc(21, 91)), K1) - k1)
    }

    /** rfme_midi_in: notes play keys (with the settings), to_firmware goes to midi_in and plays no key */
    @Test fun controllerNotes() {
        core()
        assertNotEquals("D4 holds key 9", 0, keys(feed(noteOn(62))) and (1 shl 9))
        assertEquals(0, keys(feed(noteOff(62))))
        assertEquals(1L, st("midi_notes"))
        val c0 = st("midi_to_core")
        send(noteOn(62), firmware = true)
        assertEquals("the firmware's MIDI in only", 0, keys(panel()))
        send(noteOff(62), firmware = true)
        e.run(10)
        assertEquals(2, st("midi_to_core") - c0)
        e.setMidi(0, 1, false, true)                    // (FM1VST's transpose, +1 octave: D3 plays key 9)
        assertNotEquals(0, keys(feed(noteOn(50))) and (1 shl 9))
        feed(noteOff(50))
        assertFalse("SysEx: not passed", e.midiIn(byteArrayOf(0xF0.toByte(), 1, 2)))
        assertFalse("no status byte", e.midiIn(byteArrayOf(0x40)))
    }

    /** the keys' channel: any plays on every channel; channel 2 plays only there, other channels go to midi_in; off
     *  sends every note there. A note-off lets go of the key its note-on pressed after the setting changed */
    @Test fun keyChannel() {
        core()
        fun keyed(ch: Int): Pair<Boolean, Long> {
            val c0 = st("midi_to_core")
            val k = keys(feed(noteOn(62, 100, ch))) and (1 shl 9) != 0
            feed(noteOff(62, ch))
            assertEquals(0, keys(panel()))
            return k to st("midi_to_core") - c0
        }
        assertTrue("any: channel 1", keyed(1).let { it.first && it.second == 0L })
        assertTrue("any: channel 9", keyed(9).first)
        e.setMidi(2, 0, false, true)
        assertTrue("channel 2 plays the keys", keyed(2).first)
        assertEquals(0L, keyed(2).second)
        val other = keyed(1)
        assertFalse("channel 1 does not", other.first)
        assertEquals("its note-on and note-off go to midi_in", 2L, other.second)
        e.setMidi(KEYS_OFF, 0, false, true)
        val off = keyed(2)
        assertFalse(off.first)
        assertEquals("off: every note to midi_in", 2L, off.second)
        // held across a change: the note-off still lets go of its key (and stays away from midi_in, as its note-on)
        e.setMidi(KEYS_ANY, 0, false, true)
        assertNotEquals(0, keys(feed(noteOn(62, 100, 3))) and (1 shl 9))
        e.setMidi(KEYS_OFF, 0, false, true)
        val c0 = st("midi_to_core")
        assertEquals("the key let go with the keys off", 0, keys(feed(noteOff(62, 3))))
        assertEquals(c0, st("midi_to_core"))
        e.setMidi(5, 0, false, true)
        assertNotEquals(0, keys(feed(noteOn(62, 100, 5))) and (1 shl 9))
        e.setMidi(6, 0, false, true)
        assertEquals("the key let go on another key channel", 0, keys(feed(noteOff(62, 5))))
    }

    /** the learned map off: a mapped CC and note flow on (the CC to midi_in, the note plays its key), what it held is
     *  let go; on again it plays */
    @Test fun learnedMapSwitch() {
        core()
        val fx = 1 shl B_FX
        map(listOf(MidiMapping("cc:1:21", "KN1", MidiMode.REL64), MidiMapping("cc:1:80", "FX"), MidiMapping("note:1:62", "HOME")))
        assertNotEquals(0, labels(feed(cc(80, 127))) and fx)
        e.setMidi(KEYS_ANY, 0, false, false)
        assertEquals("off: FX let go", 0, labels(panel()) and fx)
        val k1 = enc(panel(), K1)
        val c0 = st("midi_to_core")
        val m0 = st("midi_mapped")
        feed(cc(21, 65))
        assertEquals("KN1 does not turn", k1, enc(panel(), K1))
        assertEquals("the mapped CC reaches midi_in", 1, st("midi_to_core") - c0)
        assertEquals(m0, st("midi_mapped"))
        assertNotEquals("the mapped note plays its key", 0, keys(feed(noteOn(62))) and (1 shl 9))
        assertEquals(0, keys(feed(noteOff(62))))
        e.setMidi(KEYS_ANY, 0, false, true)
        assertEquals("on again: KN1 turns", 1, enc(feed(cc(21, 65)), K1) - k1)
        assertEquals("and the note is HOME's", 0, keys(feed(noteOn(62))))
        feed(noteOff(62))
    }

    /** render ahead (the audio clock, offline so the test's renders wait for the core thread): a controller's mapped
     *  CC turns KN1 on the core thread, and its note plays its key there (iOS: the AU's render MIDI; Android has none) */
    @Test fun underTheAudioClock() {
        assertNull(e.load("choralroot"))
        assertTrue(e.boot())
        e.setOffline(true)
        e.setClock(RfmEngine.CLOCK_AUDIO)
        e.renderBlocks(150)                              // (booted, the ring primed: ~1.7 s)
        val m = MidiMap(listOf(MidiMapping("cc:1:21", "KN1", MidiMode.REL64)))
        e.setMidiMap(m.entries, 1f)
        e.renderBlocks(5)
        e.panel(p)
        val k1 = enc(p, K1)
        repeat(3) { send(cc(21, 65)); e.renderBlocks(5) }
        send(noteOn(62))
        e.renderBlocks(20)
        e.panel(p)
        assertEquals(3, enc(p, K1) - k1)
        assertNotEquals(0, keys(p) and (1 shl 9))
        assertEquals(3L, st("midi_mapped"))
        e.setClock(RfmEngine.CLOCK_NONE)
        e.setOffline(false)
    }

    /** MidiLearn on an engine: KN1 picked, a pot moved on the controller binds it (absolute); after learn the pot turns
     *  from its last learned value (43 -> 44: 1 detent); a note on a knob is refused */
    @Test fun learnSeedsThePot() {
        core()
        val f = File(ctx.cacheDir, "midimap-test.json").also { it.delete() }
        val store = MidiMapStore(f)
        store.follow { en, sp -> e.setMidiMap(en, sp) }
        val host = object : LearnEngine {
            override fun learn(armed: Boolean) = e.learn(armed)
            override fun learnTake(msg: ByteArray) = e.learnTake(msg)
        }
        val l = MidiLearn(store, host)
        l.start()
        send(cc(21, 30))
        e.run(5)
        assertEquals("nothing picked: learn leaves it alone", emptyList<MidiLearn.Result>(), l.poll())
        l.select("KN1")
        for (v in listOf(40, 41, 42, 43)) send(cc(21, v))
        e.run(5)
        assertEquals(listOf(MidiLearn.Result.Bound(MidiMapping("cc:1:21", "KN1", MidiMode.ABS), true)), l.poll())
        assertEquals(MidiMode.ABS, store.map.get("cc:1:21")?.mode)
        l.select("KN2")
        send(noteOn(60))
        e.run(5)
        assertEquals(listOf(MidiLearn.Result.Rejected("KN2 takes a CC: move a knob or a pot")), l.poll())
        l.stop()
        send(noteOff(60))
        val k1 = enc(panel(), K1)
        assertEquals(1, enc(feed(cc(21, 44)), K1) - k1)
        f.delete()
    }

    /** (iOS ManagerTests) a 3 KB SysEx (a bank dump's size) into the firmware and back whole, each tail 1..3; not one
     *  SysEx message: refused */
    @Test fun sysExThroughTheFirmwareAndBack() {
        assertTrue(e.testLoadEcho())
        assertTrue(e.boot())
        e.setClock(RfmEngine.CLOCK_CALLER)
        e.midiOutEnable(2)
        val pk = IntArray(4096)
        for (tail in 0 until 3) {
            val body = ByteArray(3000 + tail) { ((it * 7) and 0x7F).toByte() }
            val msg = byteArrayOf(0xF0.toByte()) + body + byteArrayOf(0xF7.toByte())
            assertTrue(e.sysexIn(msg))
            val d = UsbMidiDecoder()
            val got = ArrayList<ByteArray>()
            for (i in 0 until 200) {
                if (got.isNotEmpty()) break
                e.run(5)
                while (true) {
                    val n = e.midiOutTake(pk)
                    for (j in 0 until n) d.feed(pk[j])?.let { got.add(it) }
                    if (n < pk.size) break
                }
            }
            assertEquals(1, got.size)
            assertArrayEquals("the SysEx came back whole (tail $tail)", msg, got[0])
        }
        assertFalse(e.sysexIn(byteArrayOf(0xF0.toByte(), 0x01, 0x90.toByte(), 0xF7.toByte())))
        assertFalse(e.sysexIn(byteArrayOf(0x90.toByte(), 0x3C, 0x64)))
    }
}
