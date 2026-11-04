// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported in part from RemoteFM1IOS ca42bbc RFMKit/Tests/RFMKitTests/SkinTests.swift: the parts that need Android's
// image decoding and the real .rfm1 loader (the rules are the JVM's SkinTest). Real PNGs read back from an .rfm1's
// rfm1.skin sections; big images scaled to 2048 px, oversized ones refused; a skinned file still checks, boots and
// runs; the user's background stored as a JPEG; the faceplate draws the image with the faceplate's colour over it.
package com.quixotic7.remotefm1

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.firmware.Rfm1Native
import com.quixotic7.remotefm1.firmware.WasmCoreUnderTest
import com.quixotic7.remotefm1.ui.faceplate.FaceplateLayout
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.ui.faceplate.FaceplateView
import com.quixotic7.remotefm1.ui.faceplate.PanelChip
import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import com.quixotic7.remotefm1.ui.faceplate.ScreenProtector
import com.quixotic7.remotefm1.ui.theme.AndroidSkinDecoder
import com.quixotic7.remotefm1.ui.theme.SkinFile
import com.quixotic7.remotefm1.ui.theme.SkinStore
import com.quixotic7.remotefm1.ui.theme.ThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class SkinDeviceTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cart(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open("firmware/ain_cart.rfm1").use { it.readBytes() }

    private fun leb(v0: Int): ByteArray {
        var v = v0
        val out = ArrayList<Byte>()
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.add(b.toByte())
        } while (v != 0)
        return out.toByteArray()
    }

    private fun custom(name: String, payload: ByteArray): ByteArray {
        val body = leb(name.toByteArray().size) + name.toByteArray() + payload
        return byteArrayOf(0) + leb(body.size) + body
    }

    /** a PNG of one colour (iOS's test colour) */
    private fun png(w: Int, h: Int, argb: Int = 0xFF3366E6.toInt()): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawColor(argb)
        return ByteArrayOutputStream().use { o -> assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, o)); o.toByteArray() }
    }

    private fun skinned(json: String, portrait: ByteArray? = null, landscape: ByteArray? = null): File {
        var d = cart() + custom(SkinFile.SECTION, json.toByteArray())
        if (portrait != null) d += custom(SkinFile.BACKGROUND, portrait)
        if (landscape != null) d += custom(SkinFile.BACKGROUND_LANDSCAPE, landscape)
        return File(ctx.cacheDir, "skinned-${System.nanoTime()}.rfm1").also { it.writeBytes(d); it.deleteOnExit() }
    }

    @Test fun noSkin() {
        val f = File(ctx.cacheDir, "ain_cart.rfm1").also { it.writeBytes(cart()) }
        assertNull(SkinFile.read(f, "x", AndroidSkinDecoder))
    }

    @Test fun aSkinIsReadBack() {
        val f = skinned("""{"skin":1,"dim":0.3,"theme":{"body":"#102030","key":"#405060","knob":"#111111","led":"#FFEE00","bezel":"#000000"}}""",
                        portrait = png(300, 600), landscape = png(600, 300))
        val s = SkinFile.read(f, "rfm1:abc", AndroidSkinDecoder)
        assertNotNull(s)
        s!!
        assertEquals("rfm1:abc", s.id)
        assertEquals(0.3, s.dim, 1e-9)
        assertEquals(300, s.portrait?.width)
        assertEquals(600, s.image(wide = true)?.width)
        assertEquals(300, s.image(wide = false)?.width)
        assertEquals("#102030", s.theme?.body?.hex)
    }

    @Test fun theSkinnedFileStillLoadsAndPlays() {
        val f = skinned("""{"skin":1}""", portrait = png(64, 64))
        assertTrue(Rfm1Native.check(f.path).exceptionOrNull()?.message ?: "", Rfm1Native.check(f.path).isSuccess)
        WasmCoreUnderTest(f.path).use { c ->
            c.boot()
            c.run(50)
        }
    }

    @Test fun bigImagesAreScaledAndOversizedOnesRefused() {
        assertEquals(SkinFile.MAX_PIXELS, SkinFile.decode(png(3000, 1500), AndroidSkinDecoder)?.let { max(it.width, it.height) })
        assertEquals("never scaled up", 300, SkinFile.decode(png(300, 200), AndroidSkinDecoder)?.width)
        assertNull(SkinFile.decode(ByteArray(SkinFile.MAX_IMAGE_BYTES + 1), AndroidSkinDecoder))
        assertNull(SkinFile.decode("not an image".toByteArray(), AndroidSkinDecoder))
    }

    @Test fun theUsersBackgroundIsStoredAsAJpeg() {
        val dir = File(ctx.cacheDir, "skins-${System.nanoTime()}")
        val prefs = ctx.getSharedPreferences("skin-test", android.content.Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val store = SkinStore(prefs, dir, AndroidSkinDecoder)
        assertFalse(store.setImage("nope".toByteArray()))
        assertTrue(store.setImage(png(400, 800)))
        val jpeg = File(dir, "background.jpg").readBytes()
        assertEquals(0xFF.toByte(), jpeg[0]); assertEquals(0xD8.toByte(), jpeg[1])
        assertEquals(800, store.skin(null)?.portrait?.height)
        assertTrue(SkinStore(prefs, dir, AndroidSkinDecoder).hasImage)
        assertTrue("a photo bigger than 2048 px is kept at 2048", store.setImage(png(4000, 3000)))
        assertEquals(2048, SkinStore(prefs, dir, AndroidSkinDecoder).userImage?.width)
        store.removeImage()
        assertFalse(File(dir, "background.jpg").exists())
        dir.deleteRecursively()
    }

    /** the image filling the faceplate, its colour over it by the dim */
    @Test fun theFaceplateDrawsTheSkin() {
        val tokens = ThemeTokens(ctx.assets.open("tokens.json").bufferedReader().readText())
        val theme = tokens.presets[0]
        val skin = SkinFile.read(skinned("""{"skin":1,"dim":0.25}""", portrait = png(64, 64, 0xFF0000FF.toInt())), "rfm1:t", AndroidSkinDecoder)!!
        val feed = PanelFeed(RecordingEngine())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.size(400.dp, 300.dp)) {
                FaceplateView(feed, remember { FaceplateUi() }, theme, PanelChip.EMULATOR, remember { ScreenProtector(null) },
                              kind = FaceplateLayout.Kind.PAD_LANDSCAPE, chrome = false, skin = skin)
            }
        }
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("skin").assertExists()
        val px = rule.onRoot().captureToImage().toPixelMap()[2, 2]
        // blue under the body colour at 0.25
        val b = theme.body
        fun near(a: Float, e: Double) = abs(a - e) < 0.05
        assertTrue("$px", near(px.red, 0.75 * 0 + 0.25 * b.r) && near(px.green, 0.25 * b.g) && near(px.blue, 0.75 + 0.25 * b.b))
    }
}
