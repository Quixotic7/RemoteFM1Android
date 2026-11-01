// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/MidiMapTests.swift
// The learned map without an engine: the website's web/site/test.mjs "MIDI learn" checks (targets, parsing, the mode
// guess, the list's lines, rebinding, learn, the stored form), the store and its follower, the controller input's
// byte decoding (MidiParser: Android's byte stream, iOS's UMP case for case) and its FM-1 rule. (The map's running:
// the emulator's MidiEngineTest.)
package com.quixotic7.remotefm1.midi

import com.quixotic7.remotefm1.MidiDesc
import com.quixotic7.remotefm1.ui.faceplate.PanelKnob
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

fun cc(n: Int, v: Int, ch: Int = 1) = byteArrayOf((0xB0 or (ch - 1)).toByte(), n.toByte(), v.toByte())
fun noteOn(n: Int, v: Int = 100, ch: Int = 1) = byteArrayOf((0x90 or (ch - 1)).toByte(), n.toByte(), v.toByte())
fun noteOff(n: Int, ch: Int = 1) = byteArrayOf((0x80 or (ch - 1)).toByte(), n.toByte(), 0)

class MidiMapTest {
    @Test fun targets() {
        assertEquals(22, MidiTarget.all.size)
        assertEquals(listOf("SELECT", "ALGORITHM", "PRESETS", "KN1", "KN2", "KN3", "KN4"), MidiTarget.all.filter { it.isEncoder }.map { it.id })
        assertEquals(PanelKnob.K1, MidiTarget.named("KN1")?.encoder)
        assertEquals(PanelLabel.HOME, MidiTarget.named("HOME")?.button)
        assertEquals(PanelLabel.OCT_UP, MidiTarget.named("OCT+")?.button)
        assertEquals(PanelLabel.OCT_DOWN, MidiTarget.named("OCT-")?.button)
        assertEquals("MASTER", MidiTarget.knob(PanelKnob.MASTER).id)
        assertEquals("FX", MidiTarget.button(PanelLabel.FX).id)
        // the engine's numbers: knob roles (RFME_E_K4 6), MASTER (RFME_T_MASTER 7), the buttons by label (8 + RFME_B_OCTUP)
        assertEquals(6, MidiTarget.named("KN4")?.engine)
        assertEquals(7, MidiTarget.named("MASTER")?.engine)
        assertEquals(8 + 13, MidiTarget.named("OCT+")?.engine)
    }

    @Test fun parse() {
        val p = MidiMessage.of(noteOn(60, 0, 3))
        assertEquals("cc:1:21", MidiMessage.of(cc(21, 5))?.source)
        assertEquals(MidiMessage.Kind.NOTE, p?.kind)
        assertEquals(false, p?.on)                                  // (note-on at 0 is a note-off)
        assertEquals(3, p?.channel)
        assertEquals("note:3:60", p?.source)
        assertNull(MidiMessage.of(byteArrayOf(0xE0.toByte(), 0, 64)))  // (pitch bend: none)
        assertNull(MidiMessage.of(byteArrayOf(0xB0.toByte(), 1)))
        assertTrue(MidiSource.isValid("cc:16:127"))
        for (bad in listOf("cc:0:1", "cc:17:1", "cc:1:128", "pc:1:1", "cc:01:1", "cc:1", "note:1:1:1")) assertFalse(bad, MidiSource.isValid(bad))
    }

    @Test fun guessMode() {
        val g = MidiMode::guess
        assertEquals("values hugging 64", listOf(MidiMode.REL64, MidiMode.REL64, MidiMode.REL64),
                     listOf(g(listOf(65, 65, 66)), g(listOf(63, 62, 63)), g(listOf(65))))
        assertEquals("1..15 / 113..127", listOf(MidiMode.REL2C, MidiMode.REL2C, MidiMode.REL2C),
                     listOf(g(listOf(1, 1, 2)), g(listOf(127, 126, 127)), g(listOf(1, 127, 1))))
        assertEquals("a pot sweeping (even at the bottom, or through 64)", List(5) { MidiMode.ABS },
                     listOf(g(listOf(40, 42, 45)), g(listOf(10, 11, 12, 13)), g(listOf(60, 61, 62, 63, 64, 65)), g(listOf(0, 127)), g(emptyList())))
    }

    @Test fun labelsAndConsumption() {
        val m = MidiMap(listOf(MidiMapping("note:1:60", "HOME"), MidiMapping("cc:1:21", "KN1"), MidiMapping("note:1:61", "KN1")))
        assertEquals("a stored note on a knob is dropped", 2, m.mappings.size)
        assertEquals(MidiMode.ABS, m.mappings[1].mode)
        assertEquals("KN1 ← CC 21 ch 1 · absolute", m.mappings[1].description)
        assertEquals("HOME ← note 60 ch 1", m.mappings[0].description)
        assertEquals("CC 7, channel 2", MidiSource.label("cc:2:7", long = true))
        assertEquals(listOf("absolute", "relative 64±", "relative 2's comp.", "relative sign bit"), MidiMode.entries.map { it.label })
        assertEquals("KN1 takes a CC: move a knob or a pot", MidiMap.canBind("KN1", MidiMessage.of(noteOn(60))))
        assertEquals("", MidiMap.canBind("HOME", MidiMessage.of(noteOn(60))))
        assertEquals("no such control", MidiMap.canBind("NOPE", MidiMessage.of(cc(1, 1))))
        // the engine's entries (5 ints each: src, channel, number, target, mode)
        val e = m.entries
        assertEquals(10, e.size)
        assertEquals(listOf(MidiMap.SRC_NOTE, 0, 60, 8 + PanelLabel.HOME.ordinal), e.slice(0..3))
        assertEquals(listOf(MidiMap.SRC_CC, 21, PanelKnob.K1.ordinal, 0), listOf(e[5], e[7], e[8], e[9]))
    }

    /** test.mjs's "rebinding and forgetting; learn" (its one feed: MidiEngineTest's learn seeds the pot) */
    @Test fun learn() {
        val store = MidiMapStore(tempFile())
        val l = MidiLearn(store, null)
        assertNull("off, nothing taken", l.take(cc(21, 5)))
        l.start()
        l.select("KN1")
        val r1 = l.take(cc(21, 40)) as MidiLearn.Result.Bound
        assertTrue(r1.fresh)
        assertEquals(MidiMapping("cc:1:21", "KN1", MidiMode.ABS), r1.mapping)
        assertEquals(1, store.mappings.size)
        l.take(cc(21, 41)); l.take(cc(21, 42)); l.take(cc(21, 43))
        assertEquals("a pot sweeping stays absolute", MidiMode.ABS, store.map.get("cc:1:21")?.mode)
        l.select("KN2")
        val r2 = l.take(cc(30, 65)); l.take(cc(30, 65)); l.take(cc(30, 66))
        assertEquals(MidiLearn.Result.Bound(MidiMapping("cc:1:30", "KN2", MidiMode.REL64), true), r2)
        assertEquals("an encoder sending 65s is binary offset", MidiMode.REL64, store.map.get("cc:1:30")?.mode)
        l.select("KN3")
        l.take(cc(21, 44))
        assertEquals("binding a source already bound moves it", "KN3", store.map.get("cc:1:21")?.target)
        assertEquals(2, store.mappings.size)
        assertEquals("the newest last", "cc:1:21", store.mappings.last().src)
        l.take(cc(22, 50))
        assertEquals("one target takes several sources", 2, store.sourcesOf("KN3").size)
        l.select("KN4")
        assertEquals(MidiLearn.Result.Rejected("KN4 takes a CC: move a knob or a pot"), l.take(noteOn(60)))
        assertFalse(store.map.has("note:1:60"))
        l.select("HOME")
        val rb = l.take(noteOn(60))
        val ro = l.take(noteOff(60))
        val rx = l.take(noteOff(61))
        assertEquals(MidiLearn.Result.Bound(MidiMapping("note:1:60", "HOME"), true), rb)
        assertEquals("its note-off is consumed", MidiLearn.Result.None, ro)
        assertNull("another note-off flows on", rx)
        l.select("KN3")
        assertEquals(2, l.forgetSelected())
        assertFalse(store.map.has("cc:1:21"))
        assertFalse(store.map.has("cc:1:22"))
        assertTrue(store.map.has("cc:1:30"))
        // the stored form round-trips; bad records are left out
        val back = MidiMap.fromJson(store.map.json)
        assertEquals(2, back.mappings.size)
        assertEquals(MidiMode.REL64, back.get("cc:1:30")?.mode)
        assertEquals(store.map, back)
        val bad = """{"midiMap":[{"src":"cc:0:1","target":"KN1"},{"src":"cc:1:1","target":"NOPE"},null,{"src":"cc:1:2","target":"KN1","mode":"zz"},{"src":"note:1:3","target":"FX","mode":"rel64"}],"knobSpeed":3}"""
        val b = MidiMap.fromJson(bad)
        assertEquals(listOf(MidiMapping("cc:1:2", "KN1", MidiMode.ABS), MidiMapping("note:1:3", "FX")), b.mappings)
        assertEquals(1.0, b.speed, 0.0)
        // the website's prefs (and iOS's midi-map.json) read as they are
        val web = """{"midiMap":[{"src":"cc:1:21","target":"KN1","mode":"rel2c"},{"src":"note:10:36","target":"OCT-"}],"knobSpeed":0.5}"""
        val w = MidiMap.fromJson(web)
        assertEquals(listOf(MidiMapping("cc:1:21", "KN1", MidiMode.REL2C), MidiMapping("note:10:36", "OCT-")), w.mappings)
        assertEquals(0.5, w.speed, 0.0)
        // iOS writes sorted keys and a whole number for 1x; both read
        assertEquals(1.0, MidiMap.fromJson("""{"knobSpeed":1,"midiMap":[]}""").speed, 0.0)
        assertEquals(2.0, MidiMap.fromJson("""{"knobSpeed":2.0,"midiMap":[]}""").speed, 0.0)
    }

    /** the store: saved at once, read back by another store; its engines (followers) get it at once */
    @Test fun storeAndFollower() {
        val f = tempFile()
        val a = MidiMapStore(f)
        var got: Pair<IntArray, Float>? = null
        a.follow { e, sp -> got = e to sp }
        assertEquals(0, got!!.first.size)
        a.bind("cc:1:21", "KN1", MidiMode.REL64)
        a.setSpeed(2.0)
        assertEquals("the follower has it at once", a.map.entries.toList(), got!!.first.toList())
        assertEquals(2f, got!!.second)
        val b = MidiMapStore(f)
        assertEquals(a.map, b.map)
        assertEquals(2.0, b.speed, 0.0)
        assertEquals(1, a.forget("KN1"))
        b.load()
        assertTrue(b.mappings.isEmpty())
        a.setSpeed(3.0)
        assertEquals("0.5, 1 or 2", 1.0, a.speed, 0.0)
        a.bind("cc:1:9", "MASTER", MidiMode.REL64)
        assertNull("a mode only on an encoder's CC", a.map.get("cc:1:9")?.mode)
        assertNull(a.bind("note:1:9", "MASTER"))
        a.clear()
        assertEquals(0, got!!.first.size)
        f.delete()
    }

    /** a controller's bytes (iOS testControllerDecode's cases, as a byte stream) */
    @Test fun controllerDecode() {
        val bytes = byteArrayOf(0x90.toByte(), 0x3C, 0x64,                  // note-on 60 100
                                0xF8.toByte(),                              // clock (real time): not passed
                                0xF0.toByte(), 0x01, 0x02, 0x03, 0xF7.toByte(),   // SysEx: not passed
                                0xC1.toByte(), 0x05,                        // program change, channel 2: one data byte
                                0xB0.toByte(), 0x15, 0x40,                  // CC 21 64
                                0x16, 0x41,                                 // running status: CC 22 65
                                0x90.toByte(), 0x3D, 0xF8.toByte(), 0x00)   // a clock byte inside a note
        assertEquals(listOf(listOf(0x90, 0x3C, 0x64), listOf(0xC1, 0x05), listOf(0xB0, 0x15, 0x40), listOf(0xB0, 0x16, 0x41),
                            listOf(0x90, 0x3D, 0x00)), MidiParser.messages(bytes))
        // split over several sends
        val out = ArrayList<List<Int>>()
        val p = MidiParser { m, n -> out.add((0 until n).map { m[it].toInt() and 0xFF }) }
        p.feed(byteArrayOf(0xB0.toByte())); p.feed(byteArrayOf(0x07)); p.feed(byteArrayOf(0x40, 0x07, 0x41))
        assertEquals(listOf(listOf(0xB0, 0x07, 0x40), listOf(0xB0, 0x07, 0x41)), out)
        // the FM-1 is never a controller: its USB IDs, else the names (RemoteMidi.c's rule, the website's name test)
        assertTrue(ControllerInput.isFm1(MidiDesc(1, MidiDesc.TYPE_USB, "Port 1", usbVid = 0x1209, usbPid = 0x0001, outputPorts = listOf("out"))))
        assertFalse("a USB device: its IDs decide", ControllerInput.isFm1(MidiDesc(2, MidiDesc.TYPE_USB, "FM-1", usbVid = 0x1234, usbPid = 0x5678)))
        assertTrue(ControllerInput.isFm1(MidiDesc(3, MidiDesc.TYPE_BLUETOOTH, "FM-1 7938")))
        assertFalse(ControllerInput.isFm1(MidiDesc(4, MidiDesc.TYPE_BLUETOOTH, "Launchkey MIDI")))
        assertTrue("the debug build's virtual FM-1", ControllerInput.isFm1(MidiDesc(5, MidiDesc.TYPE_VIRTUAL, "RemoteFM1 Fake FM-1")))
        assertFalse(ControllerInput.isFm1(MidiDesc(6, MidiDesc.TYPE_VIRTUAL, "RFM Test Keyboard")))
        assertTrue(ControllerInput.unitName("RemoteFM1 out"))
        assertTrue(ControllerInput.unitName("fm-1 7938"))
        assertFalse(ControllerInput.unitName("FM-1 updater"))
        assertFalse(ControllerInput.unitName("OMX-27"))
        // the picked input, as the website stores it
        assertEquals(ControllerInput.Selection.All, ControllerInput.Selection.of(null))
        assertEquals(ControllerInput.Selection.All, ControllerInput.Selection.of("*"))
        assertEquals(ControllerInput.Selection.None, ControllerInput.Selection.of(""))
        assertEquals(ControllerInput.Selection.One("Launchkey"), ControllerInput.Selection.of("Launchkey"))
        assertEquals("Launchkey", ControllerInput.Selection.One("Launchkey").stored)
    }

    companion object {
        fun tempFile(): File = File.createTempFile("rfm-midimap-", ".json").also { it.delete() }
    }
}
