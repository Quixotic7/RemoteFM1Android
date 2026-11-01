// SPDX-License-Identifier: MPL-2.0
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/OmxTests.swift
// The OMX-27 in REMOTE mode (omx/): the website's web/site/test.mjs OMX checks, ported (the wire, the packing against
// remote_test.py's vectors, the session's pacing on a fake clock, the views, LEDs, zones, K5's pickup), the SysEx
// reassembly, and OmxLink against a fake OMX-27 (as the website's tools/omx-fake.js: no MIDI device is touched) on a
// fake clock. Android has a byte stream, not UMP: iOS's UMP SysEx7 case is not here. The screen itself (Android's
// Canvas) is the emulator's OmxScreenTest; here the link draws with a stand-in.
package com.quixotic7.remotefm1.omx

import com.quixotic7.remotefm1.ui.faceplate.PanelKnob
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

private fun hex(b: ByteArray, from: Int = 0, to: Int = b.size) = (from until to).joinToString("") { "%02x".format(b[it].toInt() and 0xFF) }
private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
private fun cmd(m: ByteArray) = m[4].toInt() and 0xFF

class OmxWireTest {
    private val X = OmxWire

    @Test fun modeCommandAndLatch() {
        assertEquals(9, X.MODE_REMOTE)
        assertEquals("f07d0000510509f7", hex(X.enterRemote()))
        assertEquals("f07d00005bf7", hex(X.sysex(0x5B)))
    }

    @Test fun parse() {
        assertEquals(OmxWire.Message.Key(13, 3), X.parse(X.keyMsg(13, 3)))
        assertEquals(OmxWire.Message.Enc(-4), X.parse(bytes(0xF0, 0x7D, 0, 0, 0x51, 1, 0, 4, 0xF7)))
        assertEquals(OmxWire.Message.Enc(3), X.parse(X.encMsg(3)))
        assertEquals(OmxWire.Message.Pot(4, 100, 12345), X.parse(X.potMsg(4, 100, 12345)))
        assertEquals(OmxWire.Message.EncBtn(1), X.parse(bytes(0xF0, 0x7D, 0, 0, 0x51, 2, 1, 0xF7)))
        assertEquals(OmxWire.Message.Status(4), X.parse(X.statusMsg(4)))
        assertNull(X.parse(bytes(0xF0, 0x7E, 0, 0, 0x51, 0, 1, 1, 0xF7)))
        assertNull(X.parse(bytes(0x90, 60, 100)))
        assertNull("a key past 26 is none", X.parse(X.keyMsg(27, 1)))
        assertNull("a pot past K5 is none", X.parse(X.potMsg(5, 1)))
        assertEquals("amt 0 is 1", OmxWire.Message.Enc(1), X.parse(bytes(0xF0, 0x7D, 0, 0, 0x51, 1, 2, 0, 0xF7)))
    }

    @Test fun enc7() {
        assertEquals("05007f7f00000000" + "0101", hex(X.enc7(bytes(0x80, 0x7F, 0xFF, 0, 0, 0, 0, 0x81))))
        assertEquals(37, X.enc7(ByteArray(32)).size)
    }

    /** remote_test.py's _pack and _enc7 (PIL, rotate 180) on this image gave these bytes (the website's test.mjs) */
    @Test fun packAsRemoteTestPy() {
        val bits = ByteArray(128 * 32)
        for (y in 0 until 32) for (x in 0 until 128) if ((x * 7 + y * 13) % 11 < 3 || x == y || (x < 9 && y < 4)) bits[y * 128 + x] = 1
        val fb = X.pack(bits)
        assertEquals("dab809b51637adc3ea0bdc5dfe376069213bb6c3a8f3349e3141860eb666a25f",
                     hex(MessageDigest.getInstance("SHA-256").digest(fb)))
        assertEquals("8410430c30c208218618618410430c30", hex(fb, 0, 16))
        assertEquals("10430c30c20821f6f9f3f4f8f3fcf0f2", hex(fb, 496, 512))
        assertEquals("240821061861041048430c30420821060418610410430c3049420821061861040010430c30", hex(X.enc7(fb, 160, 192)))
        assertArrayEquals("unpack undoes it", bits, X.unpack(fb))
        assertArrayEquals("dec7 undoes enc7", fb.copyOf(32), X.dec7(X.enc7(fb, 0, 32), 32))
    }

    @Test fun portName() {
        assertTrue(X.isOmxPortName("OMX-27"))
        assertTrue(X.isOmxPortName("omx-27-v3 MIDI 1"))
        assertFalse(X.isOmxPortName("FM-1"))
        assertFalse(X.isOmxPortName(null))
    }

    @Test fun ledMessages() {
        val want = MutableList(27) { OmxRGB.OFF }
        assertEquals(0, X.ledMessages(want, want).size)
        val all = X.ledMessages(want, null)
        assertEquals("unknown: one batch of all 27, then the latch", listOf(0x5A, 0x5B), all.map(::cmd))
        assertEquals(5 + 2 + 81 + 1, all[0].size)
        val sent = want.toList()
        want[3] = OmxRGB(1, 2, 3)
        assertEquals(listOf("f07d00005903010203f7", "f07d00005bf7"), X.ledMessages(want, sent).map { hex(it) })
        for (i in 0 until 9) want[i] = OmxRGB(5, 5, 5)
        assertEquals("past 8 changed: a batch", 0x5A, cmd(X.ledMessages(want, sent)[0]))
    }

    @Test fun frameMessages() {
        val fb = ByteArray(512)
        val first = X.frameMessages(fb, null)
        assertEquals(17, first.size)
        assertEquals(5 + 1 + 37 + 1, first[0].size)
        assertEquals(0, X.frameMessages(fb, fb).size)
        val last = fb.copyOf()
        fb[33] = 0xFF.toByte()
        val one = X.frameMessages(fb, last)
        assertEquals(listOf(0x5C, 0x5D), one.map(::cmd))
        assertEquals(1, one[0][5].toInt())
    }

    /** changed LEDs only, a batch past 8, one frame in flight (ACK or 250 ms), 15 fps, only changed chunks */
    @Test fun sessionPacing() {
        var t = 0.0
        val sent = ArrayList<ByteArray>()
        val delays = ArrayList<Double>()
        val s = OmxSession(send = { b, d -> sent.add(b); delays.add(d) }, now = { t })
        fun cmds() = sent.map(::cmd)
        s.enter()
        assertEquals(listOf(hex(X.enterRemote())), sent.map { hex(it) })
        assertFalse("nothing more before 52 02", s.tick())
        s.feed(X.statusMsg(2)); sent.clear(); delays.clear()
        s.tick()
        assertTrue(s.active)
        assertEquals(1, cmds().count { it == 0x5A })
        assertEquals(16, cmds().count { it == 0x5C })
        assertEquals(1, cmds().count { it == 0x5D })
        assertEquals("the chunks 2 ms apart", (0 until 17).map { it * 2.0 }, delays.takeLast(17))
        sent.clear()
        val leds = MutableList(27) { OmxRGB.OFF }
        leds[5] = OmxRGB(127, 0, 0)
        s.setLeds(leds)
        val f2 = ByteArray(512)
        f2[100] = 1
        s.setFrame(f2)
        t = 10.0; s.tick()
        assertEquals("within 30 ms nothing more goes; the frame waits for its ACK", 0, sent.size)
        t = 40.0; s.tick()
        assertEquals("one changed LED: 59 5 r g b, then 5B", listOf(89, 91), cmds())
        s.feed(X.statusMsg(4)); t = 80.0; sent.clear(); s.tick()
        assertEquals("after the ACK, only the changed chunk (3) and 5D", listOf(92, 93), cmds())
        assertEquals(3, sent[0][5].toInt())
        sent.clear(); f2[0] = 9; s.setFrame(f2); t = 100.0; s.tick()
        assertEquals("no ACK yet: no frame", 0, sent.size)
        t = 340.0; s.tick()
        assertEquals("a lost ACK: the next frame after 250 ms", listOf(92, 93), cmds())
        s.feed(X.statusMsg(4)); sent.clear(); t = 360.0; s.tick()
        assertEquals("the same frame and LEDs: nothing", 0, sent.size)
        s.clear()
        assertEquals(1, cmds().count { it == 0x5A })
        assertEquals(16, cmds().count { it == 0x5C })
        val batch = sent.first { cmd(it) == 0x5A }
        assertTrue("clear() turns every LED off and blanks the screen", (7 until batch.size - 1).all { batch[it].toInt() == 0 })
        s.feed(X.statusMsg(3)); sent.clear(); t = 1000.0; s.setFrame(f2); s.tick()
        assertFalse(s.active)
        assertEquals("after 52 03 (REMOTE left) nothing goes", 0, sent.size)
        s.feed(X.statusMsg(2)); t = 1100.0; s.tick()
        assertTrue(s.active)
        assertEquals("52 02 again: everything sent again", 16, sent.count { cmd(it) == 0x5C })
    }

    @Test fun assemblerReassemblesSplitSysex() {
        val a = OmxSysexAssembler()
        val m = X.keyMsg(15, 1)
        val n = X.statusMsg(2)
        assertEquals(0, a.feed(m.copyOfRange(0, 3)).size)
        val got = a.feed(m.copyOfRange(3, 6) + bytes(0xF8) + m.copyOfRange(6, m.size))
        assertEquals("split, a clock byte inside", listOf(hex(m)), got.map { hex(it) })
        assertEquals(listOf(hex(n), hex(n)), a.feed(bytes(0x90, 60, 100) + n + n).map { hex(it) })
        assertEquals("a status byte inside drops it", 0, a.feed(bytes(0xF0, 0x7D, 0x90, 60, 0xF7)).size)
        assertEquals("a new F0 starts again", listOf(hex(n)), a.feed(bytes(0xF0, 1, 0xF0) + n).map { hex(it) })
    }

    @Test fun pair() {
        val a = OmxPort(1, "OMX-27")
        val b = OmxPort(2, "OMX-27")
        val c = OmxPort(3, "FM-1")
        assertNull(omxPair(listOf(c), listOf(c)))
        assertNull(omxPair(emptyList(), listOf(b)))
        assertEquals(OmxPair(a, b), omxPair(listOf(c, a), listOf(c, b)))
        val d = OmxPort(4, "omx other")
        assertEquals("the input of the same name first", a, omxPair(listOf(d, a), listOf(b))?.input)
        assertEquals("else the first OMX input", d, omxPair(listOf(d), listOf(b))?.input)
    }
}

class OmxViewsTest {
    private val D = 1
    private val U = 0
    private val Q = 3
    private fun down(v: OmxViews, k: Int, t: Double = 0.0) = v.key(k, D, t)
    private fun up(v: OmxViews, k: Int, t: Double = 0.0) = v.key(k, U, t)
    private fun click(v: OmxViews, k: Int, t: Double) = v.key(k, D, t) + v.key(k, U, t + 40) + v.key(k, Q, t + 40)
    private fun key(k: Int, down: Boolean) = OmxAction.Key(k, down)
    private fun btn(l: PanelLabel, down: Boolean) = OmxAction.Button(l, down)

    @Test fun fm1Keys() {
        assertEquals(16, OmxViews.FM1_WHITES.size)
        assertEquals(11, OmxViews.FM1_SHARPS.size)
        assertEquals(listOf(54, 56, 58, 61, 63, 66, 68, 70, 73, 75, 78), OmxViews.FM1_SHARPS.map { 53 + it })
        assertEquals("F3", OmxPanel.noteName(0))
        assertEquals("G5", OmxPanel.noteName(26))
        assertEquals("F#5", OmxPanel.noteName(OmxViews.FM1_SHARPS[10]))
    }

    @Test fun panelOrdersByName() {
        val e = mapOf("SELECT" to 0, "ALGO" to 1, "PRESETS" to 2, "K1" to 3, "K2" to 4, "K3" to 5, "K4" to 6, "MASTER" to 7)
        val k = mapOf("SELECT" to PanelKnob.SELECT, "ALGO" to PanelKnob.ALGORITHM, "PRESETS" to PanelKnob.PRESETS, "K1" to PanelKnob.K1,
                      "K2" to PanelKnob.K2, "K3" to PanelKnob.K3, "K4" to PanelKnob.K4, "MASTER" to PanelKnob.MASTER)
        for ((n, r) in e) assertEquals(n, r, k[n]!!.ordinal)
        val b = listOf("FX", "SEL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ", "PLAY", "REC", "OCT-", "OCT+")
        for (l in PanelLabel.entries) assertEquals(b[l.ordinal], l.legend.replace("−", "-"))
        assertEquals(b, OmxPanel.labels)
    }

    @Test fun views() {
        val v = OmxViews()
        assertEquals(OmxView.BASE, v.view); assertEquals(PanelKnob.SELECT, v.knob); assertEquals(0, v.range)
        assertEquals("Base R1: key 15 (F4) plays the FM-1's first F (key 0, F3)", listOf(key(0, true)), down(v, 15))
        assertEquals("Base: its release lets it go", listOf(key(0, false)), up(v, 15))
        assertEquals(listOf(key(1, true)), down(v, 3))
        assertEquals(listOf(key(1, false)), up(v, 3))
        assertEquals("Base R1: key 26 (C6) plays C5", listOf(key(19, true)), down(v, 26))
        up(v, 26)
        down(v, 12); up(v, 12)
        assertEquals(1, v.range)
        assertEquals("Base R2: key 15 plays the second F (key 12)", listOf(key(12, true)), down(v, 15))
        assertEquals("Base R2: key 23 (G5) the FM-1's top G5", listOf(key(26, true)), down(v, 23))
        up(v, 15); up(v, 23)
        assertEquals(emptyList<OmxAction>(), down(v, 24)); assertEquals(emptyList<OmxAction>(), down(v, 9))
        assertNull("Base R2: past the FM-1's", v.roleOf(26))
        up(v, 24); up(v, 9)
        assertEquals(listOf(btn(PanelLabel.OCT_DOWN, true)), down(v, 1))
        assertEquals(listOf(btn(PanelLabel.OCT_DOWN, false)), up(v, 1))
        assertEquals("Base: keys 1 and 2 hold OCT- and OCT+", listOf(btn(PanelLabel.OCT_UP, true)), down(v, 2))
        up(v, 2)
        assertEquals("Base: OCT- and OCT+ pressed are not selected", PanelLabel.PLAY, v.button)
        // the knob quick keys: held, KN2 with key 7, the whites still play
        down(v, 13)
        assertEquals(OmxViews.QK.KNOB, v.overlay()); assertEquals("KNOBS", v.screen().title)
        assertEquals(listOf(OmxAction.Select(PanelKnob.K2)), down(v, 7))
        assertEquals("knob quick keys: key 7 selects KN2", PanelKnob.K2, v.knob)
        up(v, 7)
        assertEquals(listOf(OmxAction.Turn(PanelKnob.K2, 3)), v.enc(3))
        assertEquals("the encoder turns the selected knob, its amt as detents", listOf(OmxAction.Turn(PanelKnob.K2, -2)), v.enc(-2))
        assertEquals(emptyList<OmxAction>(), v.enc(0))
        assertTrue("knob quick keys: the whites still play", down(v, 20).firstOrNull() is OmxAction.Key)
        assertEquals("key 10 does nothing", emptyList<OmxAction>(), down(v, 10))
        up(v, 20); up(v, 10); up(v, 13)
        assertNull("knob quick keys: let go, they close", v.overlay())
        // double click latches, a click closes
        click(v, 13, 1000.0)
        assertNull("a single click on 13 does not latch", v.overlay())
        click(v, 13, 3000.0); click(v, 13, 3200.0)
        assertEquals(OmxViews.QK.KNOB, v.overlay()); assertTrue("a double click on 13 latches", v.knobLatched)
        assertEquals("latched: key 3 selects SEL", listOf(OmxAction.Select(PanelKnob.SELECT)), down(v, 3))
        up(v, 3)
        click(v, 13, 5000.0)
        assertNull("latched: a single click closes them", v.overlay())
        // the button quick keys
        down(v, 14)
        assertEquals(OmxViews.QK.BTN, v.overlay()); assertEquals("BUTTONS", v.screen().title)
        assertEquals("button quick keys: key 25 presses PLAY", listOf(btn(PanelLabel.PLAY, true)), down(v, 25))
        assertEquals(listOf(btn(PanelLabel.PLAY, false)), up(v, 25))
        assertEquals(listOf(btn(PanelLabel.HOME, true)), down(v, 21)); assertEquals(PanelLabel.HOME, v.button)
        assertEquals(listOf(btn(PanelLabel.FX, true)), down(v, 5)); assertEquals(PanelLabel.FX, v.button)
        up(v, 21); up(v, 5)
        assertEquals(emptyList<OmxAction>(), down(v, 16))
        assertEquals("the white keys (16) and keys 3, 4 do not play", emptyList<OmxAction>(), down(v, 3))
        up(v, 16); up(v, 3)
        down(v, 1); up(v, 1)
        assertEquals("button quick keys: OCT- is selectable here", PanelLabel.OCT_DOWN, v.button)
        up(v, 14)
        assertEquals(listOf(btn(PanelLabel.OCT_DOWN, true)), down(v, 0))
        assertEquals("AUX presses the selected button (OCT-) while held", listOf(btn(PanelLabel.OCT_DOWN, false)), up(v, 0))
        // Seq: the 1:1 map, AUX toggled by K4
        v.pot(0, 40)
        assertEquals(OmxView.SEQ, v.view); assertEquals("SEQ", v.screen().title)
        val seqKeys = ArrayList<Int>()
        for (k in 1..26) { (down(v, k).firstOrNull() as? OmxAction.Key)?.let { seqKeys.add(it.k) }; up(v, k) }
        assertEquals(OmxViews.FM1_SHARPS.take(10), seqKeys.take(10))
        assertEquals("Seq: blacks 1..10 the first 10 sharps, whites 11..26 the whites", OmxViews.FM1_WHITES, seqKeys.drop(10))
        assertEquals("Seq: AUX is the button quick key at first", listOf(btn(PanelLabel.OCT_DOWN, true)), down(v, 0)); up(v, 0)
        v.pot(3, 100)
        assertTrue(v.auxBlack)
        assertEquals(listOf(key(OmxViews.FM1_SHARPS[10], true)), down(v, 0))
        assertTrue("Seq: K4 up, AUX is the 11th sharp (F#5)", v.screen().line.contains("AUX F#5"))
        up(v, 0); v.pot(3, 10)
        assertFalse("Seq: K4 down, the button quick key again", v.auxBlack)
        // Keyboard: notes, channel K3, octave K4
        v.pot(0, 70)
        assertEquals(OmxView.KEYS, v.view)
        v.pot(2, 20); v.pot(3, 95)
        assertEquals(listOf(OmxAction.Midi(listOf(0x92, 60 + 24, 100))), down(v, 12))
        assertTrue("Keyboard: channel 3, octave +2: key 12 sends C6 on ch 3", v.screen().line.startsWith("CH 3  OCT +2"))
        v.pot(3, 64)
        assertEquals("the note-off is the note that went down, after an octave change", listOf(OmxAction.Midi(listOf(0x82, 84, 0))), up(v, 12))
        assertEquals("Keyboard: AUX is the button quick key", listOf(btn(PanelLabel.OCT_DOWN, true)), down(v, 0)); up(v, 0)
        // Quick Keys
        v.pot(0, 120)
        assertEquals(OmxView.QUICK, v.view); assertEquals("QUICK KEYS", v.screen().title)
        assertEquals(listOf(OmxAction.Select(PanelKnob.K4)), down(v, 9)); assertEquals(PanelKnob.K4, v.knob); up(v, 9)
        assertEquals(listOf(btn(PanelLabel.PLAY, true)), down(v, 23)); assertEquals(PanelLabel.PLAY, v.button)
        assertEquals("Quick Keys: key 23 selects PLAY and presses it", listOf(btn(PanelLabel.PLAY, false)), up(v, 23))
        assertEquals(listOf(btn(PanelLabel.FX, true)), down(v, 11))
        assertEquals(listOf(btn(PanelLabel.HOME, true)), down(v, 19))
        assertEquals("Quick Keys: 11 FX, 19 HOME, 17 nothing", emptyList<OmxAction>(), down(v, 17))
        assertEquals(listOf(btn(PanelLabel.FX, false), btn(PanelLabel.HOME, false)), v.releaseAll())
        assertEquals("releaseAll lets every held key and button go", 0, v.heldCount)
    }

    @Test fun sharedHolds() {
        val v = OmxViews()
        assertEquals(listOf(key(19, true)), down(v, 26))             // C6 -> C5 in R1
        down(v, 12)                                                   // R2
        assertEquals("key 19 (C5) in R2 is the FM-1's C5 too: already down", emptyList<OmxAction>(), down(v, 19))
        assertEquals(emptyList<OmxAction>(), up(v, 26))
        assertEquals("up with the last", listOf(key(19, false)), up(v, 19))
        assertEquals("an up without a down: nothing", emptyList<OmxAction>(), up(v, 19))
        assertEquals(emptyList<OmxAction>(), v.key(27, D))
    }

    @Test fun zones() {
        assertEquals(0, OmxViews.zoneOf(0, 4, null)); assertEquals(0, OmxViews.zoneOf(31, 4, null))
        assertEquals(1, OmxViews.zoneOf(32, 4, null)); assertEquals(3, OmxViews.zoneOf(127, 4, null))
        assertEquals(0, OmxViews.zoneOf(33, 4, 0)); assertEquals(1, OmxViews.zoneOf(35, 4, 0))
        assertEquals(1, OmxViews.zoneOf(30, 4, 1)); assertEquals("a zone changes only 3 past its edge", 0, OmxViews.zoneOf(28, 4, 1))
        val v = OmxViews()
        assertEquals(emptyList<OmxAction>(), v.pot(0, 33))
        assertEquals(listOf(OmxAction.View(OmxView.SEQ)), v.pot(0, 36))
        assertEquals("K1: no view flicker around an edge", emptyList<OmxAction>(), v.pot(0, 30))
        assertEquals(listOf(OmxAction.Theme(5)), v.pot(1, 127, 0, 1000.0))
        assertEquals("THEME NIGHT", v.screen(1500.0).line)
        assertNotEquals("THEME NIGHT", v.screen(3000.0).line)
        assertEquals(6, OmxViews.THEMES.size)
        assertEquals(5, OmxViews(v.saved()).theme)
        assertEquals("bad values ignored", 0, OmxViews(OmxViewPrefs(theme = 99, knob = 42)).theme)
        assertEquals(PanelKnob.SELECT, OmxViews(OmxViewPrefs(theme = 99, knob = 42)).knob)
        assertEquals("MASTER is no encoder knob", PanelKnob.SELECT, OmxViews(OmxViewPrefs(knob = PanelKnob.MASTER.ordinal)).knob)
        val p = OmxViewPrefs(2, PanelKnob.K3.ordinal, PanelLabel.REC.ordinal, true, 9, -2)
        assertEquals("every choice kept", p, OmxViews(p).saved())
        assertEquals("the stored form round-trips", p, OmxViewPrefs.fromJson(p.toJson()))
        assertEquals(1, OmxViews(OmxViewPrefs(ch = 17, oct = 4)).saved().ch)
        assertEquals(0, OmxViews(OmxViewPrefs(ch = 17, oct = 4)).saved().oct)
    }

    /** Range 2: the playing keys past the FM-1's G5 lit dark red; Range 1 has none */
    @Test fun deadKeys() {
        val v = OmxViews()
        fun red(l: List<OmxRGB>, k: Int) = l[k].r > 0 && l[k].g == 0 && l[k].b == 0
        fun dead(l: List<OmxRGB>) = (1 until 27).filter { red(l, it) }
        assertEquals("Range 1: no dead keys", emptyList<Int>(), dead(v.leds()))
        v.range = 1
        val l = v.leds()
        assertEquals(listOf(9, 10, 24, 25, 26), dead(l))
        assertTrue("dimmer than a shortcut", l[24].r < 64)
        v.key(14, 1, 0.0)
        assertFalse("the button quick keys cover the dead keys", red(v.leds(), 24))
    }

    /** shortcuts at 50% and full when pressed or selected; mirrored keys dark unless the FM-1 lights them */
    @Test fun leds() {
        val v = OmxViews()
        val lv = IntArray(27)
        fun l() = v.leds { lv[it] }
        var l = l()
        assertEquals("64,0,0", "${l[0]}"); assertEquals("0,64,64", "${l[1]}")
        assertEquals("64,127,0", "${l[11]}"); assertEquals("64,32,0", "${l[12]}")
        assertEquals(OmxRGB.OFF, l[15]); assertEquals(OmxRGB.OFF, l[3])
        lv[0] = 255; lv[1] = 40
        l = l()
        assertEquals("127,127,127", "${l[15]}")
        assertTrue("the level sets the brightness", l[3].ints.all { it in 1..59 } && l[3].r == l[3].b)
        v.theme = 1; l = l()
        assertEquals("another theme, another hue", OmxViews.THEMES[1].key.map { minOf(127, jsRound(it / 2.0)) }, l[15].ints)
        v.theme = 0
        v.key(0, 1); l = l()
        assertEquals("AUX full while pressed", "127,0,0", "${l[0]}"); v.key(0, 0)
        v.key(13, 1); l = l()
        assertEquals("127,0,64", "${l[13]}"); assertEquals("0,0,127", "${l[3]}")
        assertEquals("0,0,64", "${l[4]}"); assertEquals("64,0,0", "${l[6]}")
        assertEquals("knob quick keys: the toggle full, SEL full, the others 50%; F mirrors", "127,127,127", "${l[15]}")
        v.key(13, 0); v.key(14, 1); l = l()
        assertEquals("0,127,64", "${l[25]}"); assertEquals("64,32,0", "${l[21]}")
        assertEquals(OmxRGB.OFF, l[15]); assertEquals("button quick keys: PLAY full, HOME 50%; the whites dark", OmxRGB.OFF, l[3])
        v.key(14, 0)
        v.pot(0, 70); v.key(12, 1); l = l()
        assertEquals("127,127,127", "${l[12]}"); assertEquals("Keyboard: a held key lights", OmxRGB.OFF, l[13])
        v.key(12, 0)
        v.pot(0, 40); lv.fill(0); lv[OmxViews.FM1_WHITES[3]] = 255; lv[OmxViews.FM1_SHARPS[0]] = 96; l = l()
        assertEquals("127,127,127", "${l[14]}"); assertEquals("79,79,79", "${l[1]}")
        assertEquals("Seq mirrors each FM-1 key's LED (and AUX the button quick key)", 3, l.count { it.lit })
        v.theme = 5
        assertEquals("Night: dimmer shortcuts", "32,0,0", "${v.leds()[0]}")
    }

    /** K5: MASTER with pickup (MasterPot, as a learned MASTER pot) */
    @Test fun masterPickup() {
        val v = OmxViews()
        var a = v.pot(4, 10, 10 shl 7, 0.0, 768)
        val p = a[0] as OmxAction.Pickup
        assertTrue(p.first); assertEquals(768, p.at)
        assertTrue(v.screen(10.0).line, Regex("^K5 \\d+% TO 75%$").matches(v.screen(10.0).line))
        a = v.pot(4, 50, 50 shl 7, 0.0, 768)
        assertEquals("still waiting", listOf(OmxAction.Pickup(jsRound((50 shl 7) * 1023.0 / 16383), 768, false)), a)
        a = v.pot(4, 100, 100 shl 7, 0.0, 768)
        assertEquals("crossing MASTER picks it up", listOf(OmxAction.Master(jsRound(12800 * 1023.0 / 16383))), a)
        val m = (a[0] as OmxAction.Master).v
        a = v.pot(4, 64, 64 shl 7, 0.0, m)
        val m2 = (a[0] as OmxAction.Master).v
        assertTrue("K5 then follows", kotlin.math.abs(m2 - 512) <= 1)
        assertEquals("MASTER 50%", v.screen(10.0).line)
        a = v.pot(4, 66, 66 shl 7, 0.0, 300)
        assertTrue("MASTER moved by something else: pickup again", a[0] is OmxAction.Pickup)
        assertEquals("a pot already at MASTER takes it at once", listOf(OmxAction.Master(767)), MasterPot().feed(767, 1023, 768))
        val s = PickupState()
        assertFalse(pickedUp(s, 10.0, 50.0, 2.0))
        assertTrue("crossed", pickedUp(s, 60.0, 50.0, 2.0))
        assertTrue("stays picked", pickedUp(s, 0.0, 50.0, 2.0))
    }

    @Test fun labels() {
        val v = OmxViews()
        val l = v.labels()
        assertEquals("AUX", l[0]); assertEquals("OCT-", l[1]); assertEquals("R1", l[11]); assertEquals("KNOBS", l[13])
        assertEquals("BTNS", l[14]); assertEquals("F3", l[15])
        v.pot(0, 70)
        assertEquals("C4", v.labels()[12])
    }
}

// ---------------------------------------------------------------- the link, against a fake OMX-27

/** the clock: timers fire as time is moved on */
class FakeClock : OmxClock {
    override var now = 0.0
    inner class T(var at: Double, val every: Double?, val seq: Int, val f: () -> Unit) : OmxTimer {
        var dead = false
        override fun cancel() { dead = true }
    }
    private val timers = ArrayList<T>()
    private var seq = 0
    override fun after(ms: Double, f: () -> Unit): OmxTimer = add(ms, null, f)
    override fun every(ms: Double, f: () -> Unit): OmxTimer = add(ms, ms, f)
    private fun add(ms: Double, every: Double?, f: () -> Unit): T = T(now + maxOf(0.0, ms), every, ++seq, f).also { timers.add(it) }
    fun advance(ms: Double) {
        val end = now + ms
        while (true) {
            timers.removeAll { it.dead }
            val t = timers.filter { it.at <= end }.minWithOrNull(compareBy<T>({ it.at }, { it.seq })) ?: break
            now = t.at
            if (t.every != null) t.at += t.every else t.dead = true
            t.f()
        }
        now = end
    }
}

/** a fake OMX-27 behind a fake transport (the website's tools/omx-fake.js: REMOTE mode as the firmware speaks it) */
class FakeOmx(val clock: FakeClock) : OmxTransport {
    var connected = true
    var remote = false
    var leds = MutableList(27) { OmxRGB.OFF }
    private var ledStage = MutableList(27) { OmxRGB.OFF }
    var frame = ByteArray(512)
    private val stage = ByteArray(512)
    var frames = 0; var acks = 0; var modes = 0
    val got = ArrayList<ByteArray>()
    val pots = IntArray(5)
    var onSetup: (() -> Unit)? = null
    var onMessage: ((ByteArray) -> Unit)? = null
    var started = false; var opened = false
    val inPort = OmxPort(271, "OMX-27", 7, 0)
    val outPort = OmxPort(272, "OMX-27", 7, 0)
    val other = OmxPort(9, "Some keyboard", 3, 0)

    override fun start(onSetupChange: () -> Unit): String? { onSetup = onSetupChange; started = true; return null }
    override fun stop() { onSetup = null; started = false; close() }
    override fun ports() = if (connected) listOf(other, inPort) to listOf(other, outPort) else listOf(other) to listOf(other)
    override fun open(input: OmxPort, output: OmxPort, onMessage: (ByteArray) -> Unit) { this.onMessage = onMessage; opened = true }
    override fun close() { onMessage = null; opened = false }
    override fun send(bytes: ByteArray, delayMs: Double) {
        if (!connected || !opened) return
        if (delayMs > 0) clock.after(delayMs) { receive(bytes) } else receive(bytes)
    }

    /** to the host, after ms */
    fun out(d: ByteArray, ms: Double = 0.0) {
        clock.after(ms) { if (connected) onMessage?.invoke(d) }
    }

    private fun sx(vararg b: Int) = bytes(0xF0, 0x7D, 0, 0, *b, 0xF7)

    /** the host's messages, as omx_mode_remote.cpp takes them */
    fun receive(d: ByteArray) {
        got.add(d)
        if (d.size < 6 || d[0] != 0xF0.toByte() || d[1].toInt() != 0x7D || d[2].toInt() != 0 || d[3].toInt() != 0) return
        val c = d[4].toInt() and 0xFF
        val p = (5 until d.size - 1).map { d[it].toInt() and 0xFF }
        if (c == 0x51 && p.firstOrNull() == 0x05) {
            if (p.size > 1 && p[1] == 9) {
                val was = remote
                if (was) out(sx(0x52, 0x03))
                remote = true
                out(sx(0x52, 0x02), if (was) 3.0 else 0.0)
                modes++
            } else if (remote) { remote = false; out(sx(0x52, 0x03)) }
            return
        }
        if (!remote) return
        when {
            c == 0x59 && p.size >= 4 && p[0] < 27 -> ledStage[p[0]] = OmxRGB(p[1], p[2], p[3])
            c == 0x5A && p.size >= 2 -> { var i = 0; while (i < p[1] && p[0] + i < 27) { ledStage[p[0] + i] = OmxRGB(p[2 + 3 * i], p[3 + 3 * i], p[4 + 3 * i]); i++ } }
            c == 0x5B -> leds = ledStage.toMutableList()
            c == 0x5C && p.isNotEmpty() && p[0] < 16 -> {
                var s = 1
                var i = 0
                while (i < 32) {
                    val n = minOf(7, 32 - i)
                    val hi = p[s++]
                    for (j in 0 until n) { stage[p[0] * 32 + i + j] = ((p[s] and 0x7F) or (((hi shr j) and 1) shl 7)).toByte(); s++ }
                    i += 7
                }
            }
            c == 0x5D -> {
                frame = stage.copyOf()
                frames++
                clock.after(3.0) { acks++; out(bytes(0xF0, 0x7D, 0, 0, 0x52, 0x04, 0xF7)) }
            }
        }
    }

    // the OMX's own events
    fun press(k: Int) = out(OmxWire.keyMsg(k, 1))
    fun release(k: Int) { out(OmxWire.keyMsg(k, 0)); out(OmxWire.keyMsg(k, 3)) }
    fun enc(d: Int) = out(OmxWire.encMsg(d))
    fun pot(p: Int, v: Int) { pots[p] = v; out(OmxWire.potMsg(p, v)) }
    fun leave() { remote = false; out(sx(0x52, 0x03)) }
    fun pickRemote() { remote = true; out(sx(0x52, 0x02)) }
    fun unplug() { connected = false; remote = false; onSetup?.invoke() }
    fun plug() { connected = true; onSetup?.invoke() }
    val screenBits: ByteArray get() = OmxWire.unpack(frame)
}

/** the panel, recorded */
class FakeTarget : OmxTarget {
    val keys = HashSet<Int>(); val buttons = HashSet<PanelLabel>(); val turns = HashMap<PanelKnob, Int>(); var selected: PanelKnob? = null
    var masterNow = 768; var masterSets = 0; val pickups = ArrayList<Triple<Int, Int, Boolean>>(); val midi = ArrayList<List<Int>>()
    var releases = 0
    val levels = IntArray(27)
    var link: OmxLink? = null
    override fun omxKey(k: Int, down: Boolean) { if (down) keys.add(k) else keys.remove(k) }
    override fun omxButton(l: PanelLabel, down: Boolean) { if (down) buttons.add(l) else buttons.remove(l) }
    override fun omxTurn(k: PanelKnob, detents: Int) { turns[k] = (turns[k] ?: 0) + detents; link?.knobUsed(k) }
    override fun omxSelect(k: PanelKnob) { selected = k }
    override fun omxMaster(v: Int) { masterNow = v; masterSets++ }
    override fun omxPickup(pot: Int, at: Int, first: Boolean) { pickups.add(Triple(pot, at, first)) }
    override fun omxFirmwareMidi(bytes: ByteArray) { midi.add(bytes.map { it.toInt() and 0xFF }) }
    override val omxMasterNow: Int get() = masterNow
    override fun omxKeyLevel(k: Int) = levels[k]
    override fun omxReleaseAll() { releases++; keys.clear(); buttons.clear() }
}

class MapPrefs : OmxPrefs {
    val m = HashMap<String, Any>()
    override fun string(key: String) = m[key] as? String
    override fun setString(key: String, v: String) { m[key] = v }
    override fun bool(key: String) = m[key] as? Boolean ?: false
    override fun setBool(key: String, v: Boolean) { m[key] = v }
}

/** the screen stand-in: something lit, different for every text (OmxScreen draws on the emulator) */
fun fakeRender(s: OmxViews.Screen): ByteArray {
    val bits = ByteArray(128 * 32)
    val h = "${s.title}|${s.line}|${s.view}".hashCode()
    for (i in 0 until 200) bits[((h.toLong() and 0xFFFF) * 7 + i * 13).mod(bits.size.toLong()).toInt()] = 1
    return OmxWire.pack(bits)
}

class OmxLinkTest {
    private lateinit var clock: FakeClock
    private lateinit var omx: FakeOmx
    private lateinit var target: FakeTarget
    private lateinit var link: OmxLink
    private lateinit var prefs: MapPrefs
    private val toasts = ArrayList<Pair<String, Boolean>>()
    private val actives = ArrayList<Boolean>()

    @Before fun setUp() {
        prefs = MapPrefs()
        clock = FakeClock()
        omx = FakeOmx(clock)
        target = FakeTarget()
        make()
    }

    private fun make() {
        link = OmxLink(target, omx, clock, prefs, ::fakeRender)
        target.link = link
        toasts.clear(); actives.clear()
        link.onToast = { t, ok -> toasts.add(t to ok) }
        link.onActive = { actives.add(it) }
    }

    private fun pass(ms: Double = 120.0) = clock.advance(ms)

    private fun connectLive() {
        link.connect()
        pass(300.0)
        assertTrue(link.active)
    }

    @Test fun handshakeLedsAndScreen() {
        assertEquals("Not connected.", link.detail)
        assertTrue("before Connect, nothing goes to the OMX-27", omx.got.isEmpty())
        link.connect()
        assertEquals("Connect sends the MODE command (51 05 09)", "f07d0000510509f7", hex(omx.got.first()))
        assertEquals(OmxLink.Tone.BUSY, link.tone); assertEquals("Asking OMX-27 for remote mode...", link.detail)
        assertTrue(prefs.bool("rfm1.omxAuto"))
        pass(300.0)
        assertTrue(link.active); assertEquals(OmxLink.Tone.ON, link.tone)
        assertEquals("OMX-27 in remote mode: Base view. To leave remote mode on the OMX-27, hold AUX and the encoder.", link.detail)
        assertEquals(listOf("OMX-27 in remote mode: its keys, knobs and screen play the FM-1."), toasts.map { it.first })
        assertEquals(true, toasts.first().second)
        assertEquals(listOf(true), actives)
        assertEquals("Disconnect", link.buttonTitle)
        assertTrue(omx.frames > 0)
        assertEquals(omx.frames, omx.acks)
        assertTrue("Base: Range 1 lit, AUX at half", omx.leds[11].lit && omx.leds[0].r > 0 && omx.leds[0].r < 100)
        assertTrue("the screen shows something", omx.screenBits.sumOf { it.toInt() } > 100)
        assertArrayEquals(OmxWire.unpack(fakeRender(link.views.screen(clock.now))), omx.screenBits)
        assertTrue(link.owns(271)); assertTrue(link.owns(0, "OMX-27")); assertFalse(link.owns(9))
        assertTrue(link.ownsDevice(7, null)); assertFalse(link.ownsDevice(3, "Some keyboard"))
    }

    @Test fun noAnswer() {
        val quiet = object : OmxTransport {
            override fun start(onSetupChange: () -> Unit): String? = null
            override fun stop() {}
            override fun ports() = listOf(OmxPort(1, "OMX-27")) to listOf(OmxPort(2, "OMX-27"))
            override fun open(input: OmxPort, output: OmxPort, onMessage: (ByteArray) -> Unit) {}
            override fun close() {}
            override fun send(bytes: ByteArray, delayMs: Double) {}
        }
        link = OmxLink(target, quiet, clock, prefs, ::fakeRender)
        link.connect()
        pass(2400.0)
        assertEquals(OmxLink.Tone.BUSY, link.tone)
        pass(200.0)
        assertEquals(OmxLink.Tone.BAD, link.tone)
        assertEquals("OMX-27 did not answer: it needs the OMX-27 firmware with REMOTE mode. Connect asks again.", link.detail)
        assertEquals("Try again", link.buttonTitle)
    }

    /** (Android) the OMX's MIDI in held by another app: said plainly */
    @Test fun portBusy() {
        val busy = object : OmxTransport by omx {
            override val problem: String get() = "its MIDI in is in use by another app."
            override fun send(bytes: ByteArray, delayMs: Double) {}
        }
        link = OmxLink(target, busy, clock, prefs, ::fakeRender)
        link.connect()
        pass(2600.0)
        assertEquals(OmxLink.Tone.BAD, link.tone)
        assertEquals("OMX-27: its MIDI in is in use by another app. Connect asks again.", link.detail)
    }

    @Test fun noOmxThenPluggedIn() {
        omx.connected = false
        link.connect()
        assertEquals("Plug in your OMX-27: it connects when it appears.", link.detail)
        assertTrue(omx.got.isEmpty())
        omx.plug()
        pass(300.0)
        assertTrue("plugged in: remote mode by itself", link.active)
    }

    @Test fun keysEncoderAndButtons() {
        connectLive()
        omx.press(15); pass()
        assertEquals("Base: OMX key 15 holds the FM-1's first F (key 0) in Range 1", setOf(0), target.keys)
        omx.release(15); pass()
        assertEquals("...and lets it go", emptySet<Int>(), target.keys)
        omx.press(12); omx.release(12); omx.press(23); pass()
        assertEquals("Range 2: key 23 holds the FM-1's top key (G5)", setOf(26), target.keys)
        omx.release(23); omx.press(11); omx.release(11); pass()
        omx.press(13); pass()
        omx.press(7); pass()
        omx.release(7); omx.release(13); pass()
        assertEquals(PanelKnob.K2, target.selected)
        omx.enc(1); omx.enc(2); pass()
        assertEquals("13 + 7 picks KN2; the encoder turns it 3 detents", mapOf(PanelKnob.K2 to 3), target.turns)
        omx.press(14); pass()
        omx.press(25); pass()
        assertEquals("14 held: key 25 holds PLAY", setOf(PanelLabel.PLAY), target.buttons)
        omx.release(25); pass()
        assertEquals(emptySet<PanelLabel>(), target.buttons)
        omx.release(14); omx.press(0); pass()
        assertEquals("AUX then presses the selected button (PLAY)", setOf(PanelLabel.PLAY), target.buttons)
        omx.release(0); pass()
        // the panel turns a knob: the encoder turns that one next
        link.knobUsed(PanelKnob.K4)
        omx.enc(-1); pass()
        assertEquals(-1, target.turns[PanelKnob.K4])
        // the prefs kept
        val saved = OmxViewPrefs.fromJson(prefs.string("rfm1.omx"))
        assertEquals(PanelLabel.PLAY.ordinal, saved?.button)
        assertEquals("kept on select (knobUsed alone is not saved, as the website)", PanelKnob.K2.ordinal, saved?.knob)
    }

    @Test fun seqMirrorsTheFm1sLeds() {
        connectLive()
        omx.pot(0, 40); pass(250.0)
        assertEquals(OmxView.SEQ, link.views.view)
        assertEquals("OMX-27 in remote mode: Seq view. To leave remote mode on the OMX-27, hold AUX and the encoder.", link.detail)
        for ((k, v) in listOf(0 to 255, 1 to 255, 12 to 96, 22 to 40, 25 to 255, 26 to 255)) target.levels[k] = v
        pass(200.0)
        val l = omx.leds
        assertTrue("Seq: OMX 11 (F3), 1 (F#3), 18 (F4), 10 (D#5), 26 (G5) light with their FM-1 keys, the rest dark",
                   l[11].lit && l[1].lit && l[18].lit && l[26].lit && l[10].lit && !l[12].lit && !l[2].lit)
        assertTrue("the FM-1's level sets the brightness", l[11].r > l[18].r && l[18].r > 0)
    }

    @Test fun keyboardToFirmware() {
        connectLive()
        omx.pot(0, 70); pass(200.0)
        omx.press(12); pass(); omx.release(12); pass()
        assertEquals("Keyboard: key 12 sends C4 on and off to the firmware", listOf(listOf(144, 60, 100), listOf(128, 60, 0)), target.midi)
        assertEquals("...and holds no panel key", emptySet<Int>(), target.keys)
    }

    @Test fun potsCoalescedAndMasterPickup() {
        connectLive()
        val m0 = target.masterNow
        omx.pot(4, 10); pass()
        assertEquals("K5 far from MASTER: MASTER stays", m0, target.masterNow)
        assertEquals(1, target.pickups.size)
        assertEquals(true, target.pickups.first().third)
        val at = jsRound(m0 * 127.0 / 1023)
        omx.pot(4, at - 5); pass(); omx.pot(4, at + 1); pass()
        omx.pot(4, 64); pass()
        assertEquals("K5 picks MASTER up, then sets it", jsRound((64 shl 7) * 1023.0 / 16383), target.masterNow)
        assertEquals("MASTER 50%", link.views.screen(clock.now).line)
        // a stream: 20 messages within one tick: one application
        val sets = target.masterSets
        for (v in 65..84) omx.pot(4, v)
        clock.advance(0.0)          // (delivered)
        clock.advance(10.0)         // (one tick)
        assertEquals("the latest pot value per 10 ms tick", 1, target.masterSets - sets)
        assertEquals(jsRound((84 shl 7) * 1023.0 / 16383), target.masterNow)
    }

    @Test fun modeAgainStaysConnected() {
        connectLive()
        toasts.clear()
        link.connect(); pass(300.0)
        assertTrue(link.active); assertEquals(OmxLink.Tone.ON, link.tone)
        assertEquals("MODE again: 52 03 + 52 02 is still connected (no toast)", 0, toasts.size)
        assertTrue(omx.modes >= 2)
        assertEquals(listOf(true), actives)
    }

    @Test fun leftRemoteModeReleases() {
        connectLive()
        omx.press(15); omx.press(0); pass()
        assertEquals(setOf(0), target.keys); assertEquals(setOf(PanelLabel.PLAY), target.buttons)
        toasts.clear()
        omx.leave(); pass(30.0)
        assertTrue("within 50 ms: not yet", link.active)
        pass(100.0)
        assertFalse(link.active)
        assertEquals(emptySet<Int>(), target.keys); assertEquals("release on leave", emptySet<PanelLabel>(), target.buttons)
        assertTrue(target.releases >= 1)
        assertEquals("OMX-27 left remote mode: pick REMOTE on it again, or Connect.", link.detail)
        assertEquals(listOf("OMX-27 left remote mode."), toasts.map { it.first })
        assertEquals(listOf(true, false), actives)
        assertFalse("out of REMOTE mode: its port is the MIDI input's again", link.owns(271))
        val frames = omx.frames
        omx.pickRemote(); pass(400.0)
        assertTrue(link.active); assertTrue("52 02 again: the screen drawn again", omx.frames > frames)
    }

    @Test fun unpluggedAndBack() {
        connectLive()
        omx.press(15); pass()
        omx.unplug(); pass(300.0)
        assertFalse(link.active)
        assertEquals("OMX-27 unplugged: it connects again when it is back.", link.detail)
        assertEquals(emptySet<Int>(), target.keys)
        omx.plug(); pass(600.0)
        assertTrue("plugged in again: remote mode by itself", link.active)
        omx.press(15); pass()
        assertEquals("...and its keys play again", setOf(0), target.keys)
    }

    @Test fun disconnectClearsAndAuto() {
        connectLive()
        omx.press(15); pass()
        link.disconnect(); pass(400.0)
        assertTrue("Disconnect: every LED off, the screen blank", omx.leds.all { it == OmxRGB.OFF } && omx.frame.all { it.toInt() == 0 })
        assertEquals("Disconnected. The OMX-27 stays in remote mode: to leave remote mode on the OMX-27, hold AUX and the encoder.", link.detail)
        assertEquals(OmxLink.Tone.OFF, link.tone); assertFalse(link.want); assertEquals(emptySet<Int>(), target.keys)
        assertFalse(prefs.bool("rfm1.omxAuto")); assertFalse(omx.started)
        assertEquals("Connect my OMX-27", link.buttonTitle)
        link.auto(); pass(300.0)
        assertFalse("not connected last time: auto does nothing", link.active)
        link.connect(); pass(300.0)
        // a new start (the OMX still in REMOTE mode: 52 03 + 52 02): connected by itself
        link.willClose()
        make()
        link.auto(); pass(300.0)
        assertTrue("connected last time: auto connects", link.active)
    }
}
