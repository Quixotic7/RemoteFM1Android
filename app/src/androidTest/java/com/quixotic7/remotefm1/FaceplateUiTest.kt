// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A3 on the emulator: the faceplate's touches reach the engine (a recording PanelEngine: keys, buttons by the
// firmware's btn_id, encoder detents from a drag, MASTER), several fingers on the keys, the views, the LEDs coming
// back from a panel snapshot; and in the app itself (the real engine running a core): the sheet opens from the grabber
// and from the firmware's name, a theme swatch changes the theme, a touch key reaches the core (keys_pressed).
package com.quixotic7.remotefm1

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quixotic7.remotefm1.ui.faceplate.ButtonNames
import com.quixotic7.remotefm1.ui.faceplate.FaceplateLayout
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.ui.faceplate.FaceplateView
import com.quixotic7.remotefm1.ui.faceplate.PanelChip
import com.quixotic7.remotefm1.ui.faceplate.PanelEngine
import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel
import com.quixotic7.remotefm1.ui.faceplate.ScreenProtector
import com.quixotic7.remotefm1.ui.theme.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** records what the faceplate sends; btn_id = label + 100, encoder id = role + 200 */
class RecordingEngine : PanelEngine {
    val events = mutableListOf<Triple<Int, Int, Int>>()
    var names = ButtonNames.STANDARD
    var gen = 1
    override fun event(type: Int, index: Int, value: Int): Boolean { events.add(Triple(type, index, value)); return true }
    override fun matrixButton(label: Int) = label + 100
    override fun matrixEncoder(role: Int) = role + 200
    override fun panel(out: IntArray) = 0
    override fun preview(have: Int, buf: ByteBuffer) = have
    override val buttonNamesGen get() = gen
    override fun buttonNames() = names
    fun of(type: Int) = events.filter { it.first == type }
}

@RunWith(AndroidJUnit4::class)
class FaceplateUiTest {
    @get:Rule val rule = createComposeRule()

    private fun tick() = rule.mainClock.advanceTimeBy(300)

    private val tokens by lazy {
        ThemeTokens(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.assets
                        .open("tokens.json").bufferedReader().readText())
    }

    private fun show(kind: FaceplateLayout.Kind, w: Int, h: Int, ui: FaceplateUi = FaceplateUi()): Pair<RecordingEngine, PanelFeed> {
        val e = RecordingEngine()
        val feed = PanelFeed(e)
        rule.mainClock.autoAdvance = false          // (the faceplate polls the feed every frame: never idle otherwise)
        rule.setContent {
            Box(Modifier.size(w.dp, h.dp)) {
                FaceplateView(feed, remember { ui }, tokens.presets[0], PanelChip.EMULATOR, remember { ScreenProtector(null) }, kind = kind)
            }
        }
        tick()
        return e to feed
    }

    @Test fun keyTouchesReachTheEngine() {
        val (e, feed) = show(FaceplateLayout.Kind.PAD_LANDSCAPE, 1194, 834)
        // the lower row's first natural: F3, key 0 (the keybed's bottom-left)
        rule.onNodeWithTag("keybed").performTouchInput {
            down(0, Offset(width * 0.03f, height * 0.85f))
            down(1, Offset(width * 0.97f, height * 0.85f))      // G5, key 26, a second finger
        }
        tick()
        assertEquals(listOf(Triple(RfmEngine.EV_KEY, 0, 1), Triple(RfmEngine.EV_KEY, 26, 1)), e.of(RfmEngine.EV_KEY))
        assertTrue(feed.lights.keyDown(0) && feed.lights.keyDown(26))
        // slide the first finger onto the next natural (G3, key 2): the new key down, then the old one up
        rule.onNodeWithTag("keybed").performTouchInput { moveTo(0, Offset(width * 0.03f + width / 16, height * 0.85f)) }
        rule.onNodeWithTag("keybed").performTouchInput { up(0); up(1) }
        tick()
        assertEquals(listOf(Triple(1, 0, 1), Triple(1, 26, 1), Triple(1, 2, 1), Triple(1, 0, 0), Triple(1, 2, 0), Triple(1, 26, 0)),
                     e.of(RfmEngine.EV_KEY))
    }

    @Test fun buttonsGoThroughTheFirmwaresMap() {
        val (e, _) = show(FaceplateLayout.Kind.PAD_LANDSCAPE, 1194, 834)
        rule.onNodeWithTag("button-SEQ").performTouchInput { down(center) }
        tick()
        rule.onNodeWithTag("button-SEQ").performTouchInput { up() }
        rule.onNodeWithTag("button-OCT+").performClick()
        tick()
        assertEquals(listOf(Triple(2, PanelLabel.SEQ.ordinal + 100, 1), Triple(2, PanelLabel.SEQ.ordinal + 100, 0),
                            Triple(2, PanelLabel.OCT_UP.ordinal + 100, 1), Triple(2, PanelLabel.OCT_UP.ordinal + 100, 0)),
                     e.of(RfmEngine.EV_BUTTON))
    }

    @Test fun encoderDragTurnsByDetents() {
        val (e, feed) = show(FaceplateLayout.Kind.PAD_LANDSCAPE, 1194, 834)
        // up 9 dp a detent: a 50 dp drag up turns 5 detents clockwise, then 27 dp down turns 3 back
        rule.onNodeWithTag("knob-KN1").performTouchInput {
            val d = 1.dp.toPx()
            down(center)
            for (i in 1..10) moveTo(center + Offset(0f, -5 * d * i))
            for (i in 1..6) moveTo(center + Offset(0f, -50 * d + 4.5f * d * i))
            up()
        }
        tick()
        val turns = e.of(RfmEngine.EV_ENC)
        assertTrue(turns.all { it.second == 200 + 3 })                  // KN1 is role 3
        assertEquals(2, turns.sumOf { it.third })
        assertEquals(5, turns.filter { it.third > 0 }.sumOf { it.third })
        assertEquals(2, feed.knobs.turns[3])
        // MASTER: absolute, drawn at once
        rule.onNodeWithTag("knob-Master").performTouchInput {
            down(center); moveTo(center + Offset(0f, 110.dp.toPx())); up()
        }
        tick()
        val m = e.of(PanelFeed.EV_MASTER).last().third
        assertTrue("MASTER went down (was 724): $m", m < 724 - 400)
    }

    @Test fun ledsComeBackFromTheSnapshot() {
        val (_, feed) = show(FaceplateLayout.Kind.PHONE_PORTRAIT, 390, 844)
        val p = IntArray(56)
        p[PanelFeed.I_BOOTED] = 1
        p[PanelFeed.I_KEY_LED + 7] = 255
        p[PanelFeed.I_BTN_LED + PanelLabel.PLAY.ordinal] = 200
        p[PanelFeed.I_ENC + 3] = 4
        p[PanelFeed.I_MASTER] = 100
        val v = feed.lights.version
        rule.runOnUiThread { feed.apply(p) }
        assertNotEquals(v, feed.lights.version)
        assertEquals(255, feed.lights.keys[7])
        assertEquals(200, feed.lights.buttons[PanelLabel.PLAY.ordinal])
        assertEquals(4, feed.knobs.turns[3])
        assertEquals(100, feed.knobs.master)
    }

    @Test fun pageDotsAndDeckSwitch() {
        val ui = FaceplateUi()
        show(FaceplateLayout.Kind.PHONE_PORTRAIT, 390, 844, ui)
        rule.onNodeWithTag("dot-controls").performClick()
        tick()
        assertEquals(FaceplateUi.Page.CONTROLS, ui.page)
        rule.onNodeWithTag("deck-keys").performClick()
        tick()
        assertEquals(FaceplateUi.Deck.KEYS, ui.deck)
        rule.onNodeWithTag("set-kn").performClick()
        tick()
        assertEquals(FaceplateUi.KnobSet.KN, ui.knobSet)
        rule.onNodeWithTag("knob-KN3").assertIsDisplayed()
    }
}

/** the whole app, the real engine running ChoralRoot on this device ("This tablet") */
@RunWith(AndroidJUnit4::class)
class AppScreenTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun tick() = rule.mainClock.advanceTimeBy(400)

    @org.junit.Before fun manualClock() { rule.mainClock.autoAdvance = false }

    @Test fun sheetOpensAndThemeSwitches() {
        rule.onNodeWithTag("grabber").performClick()
        tick()
        rule.onNodeWithTag("sheet").assertIsDisplayed()
        rule.onNodeWithTag("swatch-Midnight").performSemanticsAction(SemanticsActions.OnClick)
        tick()
        val prefs = rule.activity.getSharedPreferences("rfm1", 0)
        assertEquals("Midnight", prefs.getString("rfm1.theme", null))
        rule.onNodeWithTag("swatch-Tangerine").performSemanticsAction(SemanticsActions.OnClick); tick()
        rule.onNodeWithTag("sheet-done").performClick()
        tick()
        assertEquals("Tangerine", prefs.getString("rfm1.theme", null))
        // the firmware's name opens the sheet on Firmware
        rule.onNodeWithTag("word").performClick()
        tick()
        tick()
        assertEquals(com.quixotic7.remotefm1.ui.SheetPageId.FIRMWARE, rule.activity.appUi.page)
        assertTrue(rule.activity.appUi.ui.sheetOpen)
        rule.onNodeWithTag("firmware-card", useUnmergedTree = true).assertExists()
    }

    @Test fun aTouchKeyReachesTheCore() {
        val m = AppModel.peek()!!
        rule.onNodeWithTag("dot-full").performClick()
        rule.runOnUiThread {
            if (m.source != Source.TABLET) m.useSource(Source.TABLET)
            if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
        }
        repeat(50) { if (m.engine.status().optInt("booted") == 0) { tick(); Thread.sleep(100) } }
        val before = m.engine.status().optInt("keys_pressed")
        rule.onNodeWithTag("keybed").performTouchInput { down(Offset(width * 0.5f, height * 0.9f)) }
        var n = before
        repeat(50) { if (n <= before) { tick(); Thread.sleep(100); n = m.engine.status().optInt("keys_pressed") } }
        assertTrue("keys_pressed $before then $n", n > before)
        rule.onNodeWithTag("keybed").performTouchInput { up() }
    }
}
