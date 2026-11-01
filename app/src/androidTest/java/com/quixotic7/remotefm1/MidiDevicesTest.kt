// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A6's "done when" in the running app on the emulator, through Android's MIDI service with the debug build's virtual
// devices (FakeMidiServices.kt): a MIDI keyboard plays the keys (and None stops it), MIDI learn binds the keyboard's
// knob to KN1 through the faceplate and then the knob turns KN1, and the OMX-27 in REMOTE mode plays the panel, gets
// its LEDs and screen, is left out of the MIDI input while it is in remote mode, comes back when it is plugged in again,
// and is cleared by Disconnect.
package com.quixotic7.remotefm1

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.midi.ControllerInput
import com.quixotic7.remotefm1.omx.OmxLink
import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MidiDevicesTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val m get() = AppModel.peek()!!

    // (the faceplate draws every frame: the UI's clock is moved by hand)
    @org.junit.Before fun manualClock() { rule.mainClock.autoAdvance = false }
    private val p = IntArray(56)

    private fun ui(f: () -> Unit) = rule.runOnUiThread(f)

    /** waits (real time) for [ok], read on the main thread */
    private fun until(ms: Long, ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            var r = false
            rule.runOnUiThread { r = ok() }
            if (r) return true
            rule.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
        }
        return false
    }

    private fun core() = ui {
        if (m.source != Source.TABLET) m.useSource(Source.TABLET)
        if (m.selected != "choralroot") m.select("choralroot")
        if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
    }

    private fun panel(): IntArray { m.engine.panel(p); return p }
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @After fun cleanUp() {
        ui {
            m.midi.input.select(ControllerInput.Selection.All)
            if (m.midi.omx.want) m.midi.omx.disconnect()
            if (m.midi.learn.active) m.midi.learn.stop()
            m.midi.store.clear()
        }
        FakeControllerService.enable(ctx, false)
        FakeOmxService.enable(ctx, false)
    }

    @Test fun aKeyboardPlaysTheKeys() {
        core()
        assertTrue("the firmware runs", until(15_000) { m.engineStatus.optInt("booted") != 0 })
        FakeControllerService.enable(ctx, true)
        ui { m.midi.input.select(ControllerInput.Selection.All) }
        assertTrue("the keyboard is listed and read: ${m.midi.input.sources}",
                   until(10_000) { m.midi.input.sources.any { it.name == FakeControllerService.NAME && it.connected } })
        assertTrue(until(5_000) { FakeControllerService.instance != null })
        val kb = FakeControllerService.instance!!
        assertFalse("the FM-1 is never listed", m.midi.input.sources.any { ControllerInput.unitName(it.name) })
        val notes0 = m.engine.status().optLong("midi_notes")
        assertTrue(kb.play(b(0x90, 62, 100)))
        assertTrue("D4 holds key 9", until(3_000) { panel()[PanelFeed.I_KEYS] and (1 shl 9) != 0 })
        kb.play(b(0x80, 62, 0))
        assertTrue(until(3_000) { panel()[PanelFeed.I_KEYS] == 0 })
        assertEquals(1, m.engine.status().optLong("midi_notes") - notes0)
        // None: the keyboard is let go
        ui { m.midi.input.select(ControllerInput.Selection.None) }
        assertTrue(until(5_000) { m.midi.input.sources.none { it.connected } })
        val in0 = m.engine.status().optLong("midi_in")
        kb.play(b(0x90, 62, 100))
        Thread.sleep(500)
        assertEquals("None: nothing reaches the engine", in0, m.engine.status().optLong("midi_in"))
        // one device by its name, remembered
        ui { m.midi.input.select(ControllerInput.Selection.One(FakeControllerService.NAME)) }
        assertTrue(until(5_000) { m.midi.input.sources.any { it.name == FakeControllerService.NAME && it.connected } })
        assertEquals(FakeControllerService.NAME, ctx.getSharedPreferences("rfm1", 0).getString(ControllerInput.KEY, null))
    }

    /** "a learned knob turns KN1": Learn on the sheet, KN1 tapped on the faceplate, the keyboard's knob moved, Done;
     *  then the knob turns KN1 */
    @Test fun aLearnedKnobTurnsKn1() {
        core()
        assertTrue(until(15_000) { m.engineStatus.optInt("booted") != 0 })
        FakeControllerService.enable(ctx, true)
        ui { m.midi.store.clear(); m.midi.input.select(ControllerInput.Selection.All) }
        assertTrue(until(10_000) { m.midi.input.sources.any { it.name == FakeControllerService.NAME && it.connected } })
        assertTrue(until(5_000) { FakeControllerService.instance != null })
        val kb = FakeControllerService.instance!!
        val a = (rule.activity as MainActivity).appUi
        // (the sheet's Learn and a tap on KN1, as the faceplate does them: Compose's test clicks wait for an idle
        // that the faceplate's frame loop never gives)
        ui { a.openSheet(com.quixotic7.remotefm1.ui.SheetPageId.MIDI) }
        ui { m.midi.startLearn(a.ui) }
        assertTrue("learn on, the sheet closed", until(3_000) { m.midi.learn.active && !a.ui.sheetOpen })
        ui { a.feed.learn.pick("KN1") }
        assertTrue(until(2_000) { a.feed.learn.selected == "KN1" })
        for (v in listOf(40, 41, 42, 43)) kb.play(b(0xB0, 21, v))
        assertTrue("CC 21 bound to KN1 (learn polled on the faceplate's frames)",
                   until(5_000) { m.midi.store.map.get("cc:1:21")?.target == "KN1" })
        assertTrue("the toast says so", until(1_000) { a.feed.toast.current?.text?.startsWith("KN1 ← CC 21") == true })
        ui { a.feed.learn.done() }
        assertTrue(until(2_000) { !m.midi.learn.active })
        val k1 = panel()[PanelFeed.I_ENC + 3]
        kb.play(b(0xB0, 21, 44)); kb.play(b(0xB0, 21, 45))
        assertTrue("the knob turns KN1 (43 -> 45: 2 detents)", until(3_000) { panel()[PanelFeed.I_ENC + 3] - k1 == 2 })
    }

    @Test fun theOmx27PlaysThePanel() {
        core()
        assertTrue(until(15_000) { m.engineStatus.optInt("booted") != 0 })
        FakeOmxService.enable(ctx, true)
        val omx = m.midi.omx
        ui { omx.connect() }
        assertTrue("remote mode: ${omx.detail}", until(10_000) { omx.active && omx.tone == OmxLink.Tone.ON })
        val fake = FakeOmxService.instance!!
        assertTrue(fake.modes >= 1)
        assertTrue("its LEDs: Range 1 lit", until(3_000) { fake.lit(11) })
        assertTrue("its screen drawn", until(3_000) { fake.litPixels() > 100 })
        assertTrue("left out of the MIDI input in remote mode", until(5_000) { m.midi.input.sources.none { it.name == FakeOmxService.NAME } })
        fake.press(15)
        assertTrue("OMX key 15 holds the FM-1's first F", until(3_000) { a().feed.holdsKey(0, PanelFeed.Source.OMX) })
        assertTrue(until(3_000) { panel()[PanelFeed.I_KEYS] and 1 != 0 })
        fake.release(15)
        assertTrue(until(3_000) { !a().feed.holdsKey(0, PanelFeed.Source.OMX) })
        // unplugged and back: it connects by itself
        FakeOmxService.enable(ctx, false)
        assertTrue("unplugged: ${omx.detail}", until(10_000) { !omx.active && omx.detail.contains("unplugged") })
        FakeOmxService.enable(ctx, true)
        assertTrue("plugged in again: remote mode by itself (${omx.detail})", until(15_000) { omx.active })
        // Disconnect: the LEDs and the screen cleared; its port the MIDI input's again
        val f2 = FakeOmxService.instance!!
        assertTrue(until(3_000) { f2.lit(11) })
        ui { omx.disconnect() }
        assertTrue("Disconnect clears it", until(3_000) { (0 until 27).none { f2.lit(it) } && f2.litPixels() == 0 })
        assertTrue(until(5_000) { m.midi.input.sources.any { it.name == FakeOmxService.NAME } })
    }

    private fun a() = (rule.activity as MainActivity).appUi
}
