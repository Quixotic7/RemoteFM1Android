// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/FaceplateTests.swift (ThemeTests, KeybedGeometryTests,
// FaceplateLayoutTests) and its button-name rules: the tokens and themes, the derivation of a custom theme, the
// keybed's geometry, the layouts, the firmware's button names, the knob's detents, the film's fold.
package com.quixotic7.remotefm1.ui

import com.quixotic7.remotefm1.ui.faceplate.ButtonNames
import com.quixotic7.remotefm1.ui.faceplate.FaceplateLayout
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.ui.faceplate.FilmGeometry
import com.quixotic7.remotefm1.ui.faceplate.KeybedGeometry
import com.quixotic7.remotefm1.ui.faceplate.KnobSpec
import com.quixotic7.remotefm1.ui.faceplate.PRect
import com.quixotic7.remotefm1.ui.faceplate.PSize
import com.quixotic7.remotefm1.ui.faceplate.PanelLabel
import com.quixotic7.remotefm1.ui.theme.Rgb
import com.quixotic7.remotefm1.ui.theme.Theme
import com.quixotic7.remotefm1.ui.theme.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class ThemeTest {
    private val tokens = ThemeTokens(File("src/main/assets/tokens.json").readText())

    @Test fun tokensLoadFivePresets() {
        assertEquals(listOf("Tangerine", "Midnight", "Mint", "Lilac", "Cream"), tokens.presets.map { it.name })
        assertEquals("Tangerine", tokens.defaultName)
        assertEquals("#EE7A3C", tokens.presets[0].body.hex)
        assertEquals("#6F6668", tokens.presets[0].ledOff.hex)
        assertEquals("#E8622A", tokens.presets[4].knob.hex)
        assertEquals("#FF3B2F", tokens.fixed["indicatorLed"]?.hex)
        assertEquals("#3BD47A", tokens.fixed["linkedDot"]?.hex)
        assertEquals("#FF8A4C", tokens.sheet["accent"]?.hex)
        for (p in tokens.presets) for (r in Theme.ROLES) assertNotNull("${p.name}.$r", p[r])
    }

    @Test fun hexAndLabRoundTrip() {
        for (h in listOf("#EE7A3C", "#1E2230", "#A8DCC8", "#000000", "#FFFFFF", "#924E3A")) {
            val c = Rgb.hex(h)!!
            assertEquals(h, c.hex)
            val l = c.lab
            assertEquals(h, Rgb.fromLab(l.l, l.a, l.b).hex)
        }
        assertNull(Rgb.hex("#12345"))
        assertEquals(21.0, Rgb.hex("#FFFFFF")!!.contrast(Rgb.hex("#000000")!!), 0.01)
    }

    @Test fun derivationRules() {
        for (p in tokens.presets) {
            val d = Theme.derived(p.choice, base = p)
            val kl = p.key.lab.l
            assertEquals("${p.name} hi", kl + 8, d.hi.lab.l, 0.6)
            assertEquals("${p.name} lo", max(0.0, kl - 18), d.lo.lab.l, 0.6)
            assertEquals("${p.name} bed", kl - 6, d.bed.lab.l, 0.6)
            assertEquals("${p.name} ledOff", d.bed.lab.l + 20, d.ledOff.lab.l, 0.8)
            assertEquals(0.0, abs(d.ledOff.lab.a) + abs(d.ledOff.lab.b), 1.5)
            assertTrue(d.keyText.contrast(d.key) >= 4.5)
            assertTrue(d.label.contrast(d.body) >= 4.5)
            assertEquals(if (p.knob.lab.l < 55) Theme.NEAR_WHITE else Theme.NEAR_BLACK, d.mark)
            assertEquals(p.lcd, d.lcd)
            assertEquals("${p.name} hi vs design", p.hi.lab.l, d.hi.lab.l, 4.0)
        }
        val light = Theme.derived(Theme.Choice(Rgb.hex("#F4F1EA")!!, Rgb.hex("#E8E2D6")!!, Rgb.hex("#F0F0F0")!!,
                                               Rgb.hex("#FF0000")!!, Rgb.hex("#111111")!!))
        assertEquals(Theme.NEAR_BLACK, light.keyText)
        assertEquals(Theme.NEAR_BLACK, light.label)
        assertEquals(Theme.NEAR_BLACK, light.mark)
        assertEquals(Theme.CUSTOM_NAME, light.name)
    }

    @Test fun presetContrast() {
        for (p in tokens.presets) {
            assertTrue(p.name, p.keyText.contrast(p.key) >= 4.5)
            assertTrue(p.name, p.label.contrast(p.body) >= 4.5)
        }
    }

    @Test fun customThemeJsonRoundTrip() {
        val t = Theme.derived(tokens.presets[1].choice, base = tokens.presets[1]).copy(led = Rgb.hex("#00FF00")!!)
        val back = Theme.fromJson(t.toJson())!!
        assertEquals(t.toJson().toString(), back.toJson().toString())
        assertEquals("#00FF00", back.led.hex)
    }
}

class KeybedGeometryTest {
    private fun check(g: KeybedGeometry, what: String) {
        val nat = g.keys.filter { it.natural }
        val sharp = g.keys.filter { !it.natural }
        assertEquals(what, 16, nat.size)
        assertEquals(what, 11, sharp.size)
        assertEquals(what, (0 until 27).toSet(), g.keys.map { it.index }.toSet())
        assertEquals(what, (0 until 16).toList(), nat.map { it.step })
        for (s in sharp) {
            val below = nat.first { it.index == s.index - 1 }
            val above = nat.first { it.index == s.index + 1 }
            assertEquals("$what: ${KeybedGeometry.keyName(s.index)} on its gap", (below.rect.maxX + above.rect.minX) / 2, s.rect.midX, 0.01f)
            assertEquals(what, below.rect.w * 0.86f, s.rect.w, 0.01f)
            assertTrue(what, s.rect.maxY < below.rect.minY)
        }
        for (i in 1 until 16) {
            assertEquals(what, g.pitch, nat[i].rect.minX - nat[i - 1].rect.minX, 0.01f)
            assertTrue(what, nat[i].rect.minX > nat[i - 1].rect.maxX)
        }
        for (n in listOf("B3", "E4", "B4", "E5")) {
            val k = (0 until 27).first { KeybedGeometry.keyName(it) == n }
            assertFalse("$what: no sharp after $n", sharp.any { it.index == k + 1 })
        }
        val row = g.layout == KeybedGeometry.Layout.Row
        for (k in g.keys) {
            if (!row && k.rect.maxX > g.stage.w) continue
            val cx = k.rect.midX + if (row) 0f else g.stage.minX + g.shift(0f)
            val cy = k.rect.midY + if (row) 0f else g.stage.minY
            assertEquals("$what: hit ${k.index}", k.index, g.keyAt(cx, cy)?.index)
        }
    }

    @Test fun rowLayouts() {
        for ((w, h) in listOf(744f to 170f, 1098f to 362f, 754f to 476f, 800f to 220f)) {
            val g = KeybedGeometry(PSize(w, h), KeybedGeometry.Layout.Row)
            check(g, "row ${w}x$h")
            val nat = g.keys.filter { it.natural }
            assertEquals(g.pad, nat.first().rect.minX, 0.01f)
            assertEquals(w - g.pad, nat.last().rect.maxX, 0.01f)
        }
        val g = KeybedGeometry(PSize(800f, 220f), KeybedGeometry.Layout.Row)
        assertEquals(11f, g.pad)
        assertEquals(6f, g.gap)
        assertEquals(49f, g.pitch)
        assertEquals(79f, g.keys.first { !it.natural }.rect.h)
        assertEquals(18f, g.radius)
    }

    @Test fun scrollLayout() {
        val g = KeybedGeometry(PSize(358f, 272f), KeybedGeometry.Layout.Scroll(40f))
        check(g, "scroll")
        assertEquals(3f, g.gap)
        assertEquals(43f, g.pitch)
        assertEquals(30f, g.stripHeight)
        assertEquals((358 - 2 * g.pad + 3) / 43, g.visible, 0.001f)
        assertEquals(16 - g.visible, g.maxOffset, 0.001f)
        assertEquals(4f, g.defaultOffset)
        val px = g.stage.minX + 20
        val py = g.stage.maxY - 10
        assertEquals(0, g.keyAt(px, py, 0f)?.index)        // F3
        assertEquals(7, g.keyAt(px, py, 4f)?.index)        // C4
        assertNull("the strip is not a key", g.keyAt(30f, g.pad + 5))
        assertEquals(9, g.stripIndex(g.stripCell(9).midX))
        assertEquals(0f, g.offsetCentering(0))
        assertEquals(g.maxOffset, g.offsetCentering(15), 0.001f)
        // (RemoteFM1IOS 391497e) let go anywhere: whole keys (no half key at the left); the right end as far as it goes
        var o = 0f
        while (o <= g.maxOffset) {
            val s = g.snap(o)
            assertTrue("$o -> $s", s == Math.round(s).toFloat() || s == g.maxOffset)
            assertTrue("$o -> $s", kotlin.math.abs(s - o) <= 0.5f + 0.001f)
            o += 0.13f
        }
        assertTrue((0 until 16).all { val c = g.offsetCentering(it); c == Math.round(c).toFloat() || c == g.maxOffset })
    }

    @Test fun slidingFingerFindsTheKeyBeside() {
        val g = KeybedGeometry(PSize(800f, 220f), KeybedGeometry.Layout.Row)
        val a = g.keys.first { it.index == 0 }
        val gx = a.rect.maxX + g.gap / 2                  // in the gap after F3
        assertNull(g.keyAt(gx, a.rect.midY))
        assertNotNull(g.keyNear(gx, a.rect.midY, slop = g.gap))
    }

    @Test fun spokenNames() {
        assertEquals("F3", KeybedGeometry.spokenName(0))
        assertEquals("C sharp 4", KeybedGeometry.spokenName(8))
        assertEquals("G5", KeybedGeometry.spokenName(26))
    }
}

class FaceplateLayoutTest {
    @Test fun pick() {
        assertEquals(FaceplateLayout.Kind.PHONE_PORTRAIT, FaceplateLayout.Kind.pick(390f, 844f))
        assertEquals(FaceplateLayout.Kind.PHONE_PORTRAIT, FaceplateLayout.Kind.pick(402f, 874f))
        assertEquals(FaceplateLayout.Kind.PHONE_LANDSCAPE, FaceplateLayout.Kind.pick(874f, 402f))
        assertEquals(FaceplateLayout.Kind.PAD_LANDSCAPE, FaceplateLayout.Kind.pick(1194f, 834f))
        assertEquals(FaceplateLayout.Kind.PAD_PORTRAIT, FaceplateLayout.Kind.pick(834f, 1194f))
        // Android: the Pixel Tablet emulator (1280 x 800 dp), and a phone emulated on it (393 x 852 dp)
        assertEquals(FaceplateLayout.Kind.PAD_LANDSCAPE, FaceplateLayout.Kind.pick(1280f, 800f))
        assertEquals(FaceplateLayout.Kind.PHONE_PORTRAIT, FaceplateLayout.Kind.pick(393f, 852f))
        assertEquals(FaceplateLayout.Kind.PHONE_LANDSCAPE, FaceplateLayout.Kind.pick(852f, 393f))
    }

    @Test fun mapping() {
        val m = FaceplateLayout.Mapper(PSize(390f, 844f), PSize(390f, 844f))
        assertEquals(PRect(16f, 530f, 358f, 272f), m.rect(16f, 530f, 358f, 272f))
        val m2 = FaceplateLayout.Mapper(PSize(390f, 844f), PSize(780f, 1688f))
        assertEquals(PRect(32f, 1060f, 716f, 544f), m2.rect(16f, 530f, 358f, 272f))
        assertEquals(2f, m2.s)
        // far from the reference aspect: uniform and centred
        val m3 = FaceplateLayout.Mapper(PSize(390f, 844f), PSize(780f, 844f))
        assertEquals(1f, m3.sx)
        assertEquals(195f, m3.ox)
    }

    @Test fun everyLayoutFitsItsReference() {
        for (k in FaceplateLayout.Kind.entries) {
            val f = FaceplateLayout.full(k)
            assertTrue(k.name, f.keybed.maxX <= k.ref.w && f.keybed.maxY <= k.ref.h)
            assertTrue(k.name, f.buttons.x + f.buttons.size.w <= k.ref.w + 1)
        }
        for (l in listOf(false, true)) {
            val c = FaceplateLayout.controls(l)
            assertTrue(c.deck.maxY <= c.ref.h && c.keybed.maxX <= c.ref.w)
        }
    }

    @Test fun viewsAndWord() {
        val ui = FaceplateUi()
        ui.move(1); assertEquals(FaceplateUi.Page.CONTROLS, ui.page)
        ui.move(1); assertEquals(FaceplateUi.Page.SCREEN, ui.page)
        ui.move(1); assertEquals(FaceplateUi.Page.SCREEN, ui.page)
        ui.move(-1); assertEquals(FaceplateUi.Page.CONTROLS, ui.page)
        assertEquals("REMOTE FM1", FaceplateUi.word(null))
        assertEquals("REMOTE FM1", FaceplateUi.word("  "))
        assertEquals("CHORALROOT", FaceplateUi.word("ChoralRoot"))
    }

    @Test fun knobDetents() {
        // 9 dp a detent, up = clockwise, toward zero
        assertEquals(0, KnobSpec.detents(-8f))
        assertEquals(1, KnobSpec.detents(-9f))
        assertEquals(3, KnobSpec.detents(-30f))
        assertEquals(-2, KnobSpec.detents(20f))
        // MASTER: 220 dp sweep the whole 0..1023
        assertEquals(1023, KnobSpec.master(0, -220f))
        assertEquals(500, KnobSpec.master(500, 0f))
    }

    @Test fun filmPeels() {
        val d = PRect(198f, 20f, 172f, 172f)
        val g = FilmGeometry(d, 15f, FilmGeometry.tab(d))
        assertEquals(-1f, g.dirX)
        assertEquals(0f, g.fold(0f, 0f))
        assertEquals(1f, g.fold(-10_000f, 0f))
        assertTrue(g.folded(0f).flap.isEmpty())
        assertFalse(g.folded(g.length * 0.3f).flap.isEmpty())
        assertFalse(FilmGeometry.peels(0.3f))
        assertTrue(FilmGeometry.peels(0.34f))
        assertTrue("fully folded: nothing left on", g.folded(g.length).left.sumOf { abs(FilmGeometry.area(it)).toDouble() } < 1.0)
    }
}

class ButtonNamesTest {
    @Test fun rules() {
        assertEquals(ButtonNames.PRINTED, ButtonNames(null).labels)
        // the firmware's names, trimmed and upper-cased; blank: printed; OCT-/OCT+ never renamed
        val fw = listOf(" fx ", "key", "Bass", "", null, "glo", "Home", "save", "perf", "seq", "loop", "rec", "DOWN", "UP")
        val n = ButtonNames(fw)
        assertEquals("FX", n.label(PanelLabel.FX))
        assertFalse(n.isRenamed(PanelLabel.FX))
        assertEquals("KEY", n.label(PanelLabel.SEL))
        assertTrue(n.isRenamed(PanelLabel.SEL))
        assertEquals("BASS", n.label(PanelLabel.ENV))
        assertEquals("LFO", n.label(PanelLabel.LFO))
        assertFalse(n.isRenamed(PanelLabel.LFO))
        assertEquals("EDIT", n.label(PanelLabel.EDIT))
        assertEquals("PERF", n.label(PanelLabel.ARP))
        assertEquals("LOOP", n.label(PanelLabel.PLAY))
        assertEquals("OCT−", n.label(PanelLabel.OCT_DOWN))
        assertFalse(n.isRenamed(PanelLabel.OCT_UP))
        assertTrue(n.anyRenamed)
        // the setting off, or a list not 14 long: all printed
        assertEquals(ButtonNames.PRINTED, ButtonNames(fw, use = false).labels)
        assertEquals(ButtonNames.PRINTED, ButtonNames(fw.dropLast(1)).labels)
    }
}
