// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/PanelMidiTests.swift
// (A6) the faceplate's MIDI side, without a core: touches and the OMX-27 holding keys and buttons as two sources
// (PanelOmxTarget), MASTER's pickup mark and its timing, MIDI learn on the faceplate (MidiLearnMode: the bar's text,
// the bound marks, Forget, the toasts), touches on a knob telling the OMX which knob its encoder turns. (On the
// emulator: PanelFeed holds an Android Bitmap.)
package com.quixotic7.remotefm1

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.midi.MidiMapStore
import com.quixotic7.remotefm1.midi.MidiMapping
import com.quixotic7.remotefm1.midi.MidiMode
import com.quixotic7.remotefm1.midi.MidiTarget
import com.quixotic7.remotefm1.omx.PanelOmxTarget
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.ui.faceplate.MidiLearnMode
import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import com.quixotic7.remotefm1.ui.faceplate.PanelKnob
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel
import com.quixotic7.remotefm1.ui.faceplate.PickupFeed
import com.quixotic7.remotefm1.ui.faceplate.ToastCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PanelMidiTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun bit(k: Int) = 1 shl k
    private fun onMain(f: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(f)

    /** a key held by a finger and by the OMX: down until both let go; the screen's releaseAll keeps the OMX's */
    @Test fun omxHoldsAreItsOwn() = onMain {
        val feed = PanelFeed(null)
        val omx = PanelOmxTarget(feed)
        omx.omxKey(5, true)
        feed.keyDown(5)
        feed.keyDown(7)
        assertTrue(feed.holdsKey(5, PanelFeed.Source.OMX))
        assertTrue(feed.holdsKey(5, PanelFeed.Source.SCREEN))
        feed.releaseAll()                                   // (the faceplate went away)
        assertFalse(feed.holdsKey(5, PanelFeed.Source.SCREEN))
        assertFalse(feed.holdsKey(7, PanelFeed.Source.SCREEN))
        assertTrue(feed.holdsKey(5, PanelFeed.Source.OMX))
        assertEquals("the OMX's key stays down", bit(5), feed.lights.touchedKeys)
        omx.omxKey(5, true)                                 // (again: still one hold)
        omx.omxKey(5, false)
        assertEquals(0, feed.lights.touchedKeys)

        // a finger lifting doesn't let go of the OMX's hold, and the other way round
        omx.omxKey(9, true)
        feed.keyDown(9)
        feed.keyUp(9)
        assertEquals(bit(9), feed.lights.touchedKeys)
        feed.keyDown(9)
        omx.omxKey(9, false)
        assertEquals(bit(9), feed.lights.touchedKeys)
        feed.keyUp(9)
        assertEquals(0, feed.lights.touchedKeys)

        // buttons the same way; omxReleaseAll lets go of the OMX's only
        omx.omxButton(PanelLabel.PLAY, true)
        feed.button(PanelLabel.HOME, true)
        feed.button(PanelLabel.PLAY, true)
        omx.omxKey(3, true)
        feed.keyDown(4)
        omx.omxReleaseAll()
        assertFalse(feed.holds(PanelLabel.PLAY, PanelFeed.Source.OMX))
        assertTrue(feed.holds(PanelLabel.PLAY, PanelFeed.Source.SCREEN))
        assertEquals(bit(PanelLabel.HOME.ordinal) or bit(PanelLabel.PLAY.ordinal), feed.lights.touchedLabels)
        assertEquals(bit(4), feed.lights.touchedKeys)
        feed.releaseAll()
        assertEquals(0, feed.lights.touchedLabels)
        assertEquals(0, feed.lights.touchedKeys)
        feed.keyUp(4)                                       // (a late lift: nothing to let go)
        assertEquals(0, feed.lights.touchedKeys)
    }

    /** a touch on a knob tells the OMX (knobUsed); the OMX's own turns and MASTER don't */
    @Test fun knobTouches() = onMain {
        val feed = PanelFeed(null)
        val omx = PanelOmxTarget(feed)
        val used = ArrayList<PanelKnob>()
        feed.onKnobTouched = { used.add(it) }
        feed.turn(PanelKnob.K2, 1)
        feed.master(300)
        omx.omxTurn(PanelKnob.K3, 2)
        omx.omxMaster(400)
        assertEquals(listOf(PanelKnob.K2, PanelKnob.MASTER), used)
        assertEquals(2, feed.knobs.turns[PanelKnob.K3.ordinal])
        assertEquals(400, feed.knobs.master)
        assertEquals(400, omx.omxMasterNow)
    }

    /** the mark shows where the pot is, for 4 s after it last moved; MASTER reached (-1) hides it */
    @Test fun pickupMarkTiming() = onMain {
        val feed = PanelFeed(null)
        val p = IntArray(56)
        p[PanelFeed.I_MASTER] = 512
        p[PanelFeed.I_MASTER_POT] = -1
        feed.apply(p, 100_000)
        assertNull(feed.pickup.pot)
        assertNull("the first snapshot only seeds pickup_seq", feed.toast.current)

        p[PanelFeed.I_MASTER_POT] = 200
        p[PanelFeed.I_PICKUP_SEQ] = 1
        feed.apply(p, 101_000)
        assertEquals(200, feed.pickup.pot)
        assertEquals("MASTER: turn the pot to 50% to pick it up", feed.toast.current?.text)
        feed.pickup.expire(104_900)
        assertEquals(200, feed.pickup.pot)
        p[PanelFeed.I_MASTER_POT] = 260                     // (it moved: 4 s again from now)
        feed.apply(p, 104_000)
        feed.pickup.expire(106_000)
        assertEquals(260, feed.pickup.pot)
        feed.pickup.expire(108_000)
        assertNull(feed.pickup.pot)

        p[PanelFeed.I_MASTER_POT] = 300                     // (the same wait: no new word)
        feed.toast.hide()
        feed.apply(p, 110_000)
        assertEquals(300, feed.pickup.pot)
        assertNull(feed.toast.current)
        p[PanelFeed.I_MASTER_POT] = -1                      // picked up
        feed.apply(p, 111_000)
        assertNull(feed.pickup.pot)

        // the OMX's K5: the same mark, the word on `first` only; MASTER set by it hides the mark
        val omx = PanelOmxTarget(feed)
        omx.omxPickup(900, 1023, true)
        assertEquals(900, feed.pickup.pot)
        assertEquals("MASTER: turn the pot to 100% to pick it up", feed.toast.current?.text)
        feed.toast.hide()
        omx.omxPickup(950, 1023, false)
        assertEquals(950, feed.pickup.pot)
        assertNull(feed.toast.current)
        omx.omxMaster(1000)
        assertNull(feed.pickup.pot)
        assertEquals("MASTER: turn the pot to 0% to pick it up", PickupFeed.text(0))
    }

    /** learn mode on the faceplate: nothing picked, picked and waiting, bound (the toast, the mark), Forget, Done */
    @Test fun learnMode() = onMain {
        val f = File(ctx.cacheDir, "panelmidi-map.json").also { it.delete() }
        val store = MidiMapStore(f)
        val feed = PanelFeed(null)
        val mode = MidiLearnMode(feed, null, store)
        val ui = FaceplateUi()
        ui.page = FaceplateUi.Page.SCREEN
        ui.sheetOpen = true
        store.bind("note:1:60", "PLAY")
        feed.keyDown(3)
        mode.start(ui)
        assertFalse(ui.sheetOpen)
        assertEquals("the controls to tap are on the panel", FaceplateUi.Page.FULL, ui.page)
        assertEquals("what the screen held let go", 0, feed.lights.touchedKeys)
        assertTrue(feed.learn.active)
        assertNotNull(feed.onTick)
        assertEquals(setOf("PLAY"), feed.learn.bound)
        assertNull(feed.learn.selected)
        assertEquals("Tap a knob or button, then move a control on your MIDI device", feed.learn.text)
        assertFalse(feed.learn.canForget)

        feed.learn.pick(MidiTarget.knob(PanelKnob.K1).id)
        assertEquals("KN1", feed.learn.selected)
        assertEquals("KN1: now turn a knob or a pot on your MIDI device", feed.learn.text)
        feed.learn.pick(MidiTarget.button(PanelLabel.SEQ).id)
        assertEquals("SEQ: now press a button or a key on your MIDI device", feed.learn.text)
        feed.learn.pick(MidiTarget.knob(PanelKnob.K1).id)

        // a note on a knob: refused, said in red
        mode.learn.take(byteArrayOf(0x90.toByte(), 60, 100))?.let { mode.took(it) }
        assertEquals(ToastCenter.Tone.BAD, feed.toast.current?.tone)
        // a CC binds: the toast, the mark, the bar
        mode.learn.take(byteArrayOf(0xB0.toByte(), 21, 64))?.let { mode.took(it) }
        assertEquals("KN1 ← CC 21, channel 1 (absolute)", feed.toast.current?.text)
        assertEquals(ToastCenter.Tone.OK, feed.toast.current?.tone)
        mode.tick()
        assertEquals(setOf("PLAY", "KN1"), feed.learn.bound)
        assertEquals("KN1 ← CC 21 ch 1", feed.learn.text)
        assertTrue(feed.learn.canForget)

        // the map changed elsewhere (the sheet): seen on the next tick
        store.bind("cc:2:5", "KN1", MidiMode.REL2C)
        mode.tick()
        assertEquals("KN1 ← CC 21 ch 1, CC 5 ch 2", feed.learn.text)

        feed.learn.forget()
        assertEquals("KN1: its 2 bindings forgotten", feed.toast.current?.text)
        assertEquals(setOf("PLAY"), feed.learn.bound)
        assertFalse(feed.learn.canForget)
        feed.learn.forget()
        assertEquals("KN1 has no binding", feed.toast.current?.text)

        feed.learn.done()
        assertFalse(feed.learn.active)
        assertNull(feed.learn.selected)
        assertEquals(emptySet<String>(), feed.learn.bound)
        assertNull(feed.onTick)
        feed.learn.pick(MidiTarget.knob(PanelKnob.K2).id)   // (not learning: nothing)
        assertNull(mode.learn.selected)
        f.delete()
    }

    @Test fun learnTexts() {
        assertEquals("PLAY ← note 36, channel 10", MidiLearnMode.boundText(MidiMapping("note:10:36", "PLAY")))
        assertEquals("KN2 ← CC 22, channel 1 (relative 64±)", MidiLearnMode.boundText(MidiMapping("cc:1:22", "KN2", MidiMode.REL64)))
        assertEquals("MASTER: now turn a knob or a pot on your MIDI device", MidiLearnMode.barText("MASTER", emptyList()))
    }
}
