// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS ca42bbc RFMKit/Tests/RFMKitTests/SkinTests.swift
// Skins (Skin.kt, RemoteFM1IOS docs/RFM1-FORMAT.md section 8): an .rfm1's rfm1.skin sections, read back (from bytes
// and from the file); the user's background in SkinStore; which skin shows. Images here are a stand-in format
// ("IMG w h"), so the rules run on the JVM; the real decoding (PNG, scaling to 2048 px) and a skinned file that
// still loads and plays are in the instrumented SkinDeviceTest.
package com.quixotic7.remotefm1.ui.theme

import android.content.SharedPreferences
import com.quixotic7.remotefm1.data.tempDir
import com.quixotic7.remotefm1.firmware.FakeRfm1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class SkinTest {
    private data class Img(override val width: Int, override val height: Int) : SkinImage

    /** "IMG w h", scaled down like ImageDecoder */
    private object Fake : SkinDecoder {
        override fun decode(data: ByteArray, maxPixels: Int): SkinImage? {
            val p = String(data).split(" ")
            if (p.size != 3 || p[0] != "IMG") return null
            val w = p[1].toInt()
            val h = p[2].toInt()
            val m = max(w, h)
            if (m <= maxPixels) return Img(w, h)
            val s = maxPixels.toDouble() / m
            return Img((w * s).roundToInt(), (h * s).roundToInt())
        }

        override fun jpeg(img: SkinImage) = "IMG ${img.width} ${img.height}".toByteArray()
    }

    private fun png(w: Int, h: Int) = "IMG $w $h".toByteArray()

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

    /** a custom section as rfm1_skin.py writes it */
    private fun custom(name: String, payload: ByteArray): ByteArray {
        val body = leb(name.toByteArray().size) + name.toByteArray() + payload
        return byteArrayOf(0) + leb(body.size) + body
    }

    private val cart: File get() = FakeRfm1.amb

    private fun skinned(json: String, portrait: ByteArray? = null, landscape: ByteArray? = null): File {
        var d = cart.readBytes() + custom(SkinFile.SECTION, json.toByteArray())
        if (portrait != null) d += custom(SkinFile.BACKGROUND, portrait)
        if (landscape != null) d += custom(SkinFile.BACKGROUND_LANDSCAPE, landscape)
        return File(tempDir("skin"), "skinned.rfm1").also { it.writeBytes(d) }
    }

    @Test fun noSkin() {
        assertNull(SkinFile.read(cart, "x", Fake))
        assertTrue(SkinFile.sections(cart.readBytes()).isEmpty())
        assertTrue(SkinFile.sections(cart).isEmpty())
        assertTrue("not a module", SkinFile.sections("nope, not wasm".toByteArray()).isEmpty())
    }

    @Test fun aSkinIsReadBack() {
        val f = skinned("""{"skin":1,"dim":0.3,"theme":{"body":"#102030","key":"#405060","knob":"#111111","led":"#FFEE00","bezel":"#000000"}}""",
                        portrait = png(300, 600), landscape = png(600, 300))
        for (s in listOf(SkinFile.read(f, "rfm1:abc", Fake)!!, SkinFile.read(f.readBytes(), "rfm1:abc", Fake)!!)) {
            assertEquals("rfm1:abc", s.id)
            assertEquals(0.3, s.dim, 1e-9)
            assertEquals(300, s.portrait?.width)
            assertEquals(600, s.image(wide = true)?.width)
            assertEquals(300, s.image(wide = false)?.width)
            assertEquals("#102030", s.theme?.body?.hex)
            assertEquals("#FFEE00", s.theme?.led?.hex)
            assertEquals("derived: the label for contrast on the body", Theme.text(s.theme!!.body), s.theme!!.label)
        }
        assertEquals(setOf(SkinFile.SECTION, SkinFile.BACKGROUND, SkinFile.BACKGROUND_LANDSCAPE), SkinFile.sections(f).keys)
    }

    @Test fun allFourteenRolesAreTakenAsGiven() {
        val roles = Theme.ROLES.mapIndexed { i, r -> "\"$r\":\"#0000${"%02X".format(i)}\"" }.joinToString(",")
        val s = SkinFile.read(skinned("""{"skin":1,"theme":{$roles}}"""), "x", Fake)!!
        assertEquals("#00000D", s.theme?.ledOff?.hex)
        assertEquals("#000001", s.theme?.label?.hex)
        assertEquals(SkinFile.NAME, s.theme?.name)
    }

    @Test fun onlyAnImageOrColoursMakeASkin() {
        assertNull("nothing to show", SkinFile.read(skinned("""{"skin":1,"dim":0.5}"""), "x", Fake))
        assertNull("another version", SkinFile.read(skinned("""{"skin":2}""", portrait = png(8, 8)), "x", Fake))
        assertNull("not JSON", SkinFile.read(skinned("""skin""", portrait = png(8, 8)), "x", Fake))
        assertNull("not a colour", SkinFile.read(skinned("""{"skin":1,"theme":{"body":"red","key":"#00FF00","knob":"#0000FF","led":"#FFFFFF","bezel":"#000000"}}"""), "x", Fake))
        val only = SkinFile.read(skinned("""{"skin":1,"theme":{"body":"#FF0000","key":"#00FF00","knob":"#0000FF","led":"#FFFFFF","bezel":"#000000"}}"""), "x", Fake)!!
        assertNull(only.portrait)
        assertEquals("#FF0000", only.theme?.body?.hex)
        assertEquals("dim clamped", 0.8, SkinFile.read(skinned("""{"skin":1,"dim":3}""", portrait = png(8, 8)), "x", Fake)!!.dim, 1e-9)
    }

    @Test fun bigImagesAreScaledAndOversizedOnesRefused() {
        assertEquals(SkinFile.MAX_PIXELS, SkinFile.decode(png(3000, 1500), Fake)?.let { max(it.width, it.height) })
        assertNull(SkinFile.decode(ByteArray(SkinFile.MAX_IMAGE_BYTES + 1), Fake))
        assertNull(SkinFile.decode("not an image".toByteArray(), Fake))
        // an image section over 4 MiB: no image
        val big = png(10, 10) + ByteArray(SkinFile.MAX_IMAGE_BYTES)
        assertNull(SkinFile.read(skinned("""{"skin":1}""", portrait = big), "x", Fake))
    }

    @Test fun theUsersBackground() {
        val prefs = MemPrefs()
        val dir = tempDir("skins")
        val store = SkinStore(prefs, dir, Fake)
        assertNull(store.skin(null))
        assertFalse(store.setImage("nope".toByteArray()))
        assertTrue(store.setImage(png(400, 800)))
        assertNotNull("a built-in core: the user's image", store.skin("felucca"))
        val s = store.skin("felucca")!!
        assertEquals(800, s.portrait?.height)
        assertEquals(0.25, s.dim, 1e-9)
        assertTrue(File(dir, "background.jpg").isFile)
        store.dim = 0.5

        val again = SkinStore(prefs, dir, Fake)                    // the app started again
        assertTrue(again.hasImage)
        assertEquals(0.5, again.dim, 1e-9)

        val fw = skinned("""{"skin":1,"dim":0.1}""", portrait = png(32, 32))
        store.fileFor = { if (it == "abc") fw.path else null }
        assertEquals("the firmware's own skin wins", "rfm1:abc", store.skin("rfm1:abc")?.id)
        assertEquals("a firmware without one: the user's", s.id, store.skin("rfm1:other")?.id)
        store.firmwareSkins = false
        assertEquals("Firmware skins off", s.id, store.skin("rfm1:abc")?.id)
        assertFalse(SkinStore(prefs, dir, Fake).firmwareSkins)
        store.firmwareSkins = true

        store.removeImage()
        assertEquals("rfm1:abc", store.skin("rfm1:abc")?.id)
        assertNull(store.skin(null))
        assertFalse(File(dir, "background.jpg").exists())
        again.load()
        assertFalse(again.hasImage)
    }

    @Test fun skinsAreEqualByIdDimAndColours() {
        val a = Skin("user:1", Img(1, 1), dim = 0.25)
        assertEquals(a, Skin("user:1", Img(2, 2), dim = 0.25))
        assertFalse(a == Skin("user:2", Img(1, 1), dim = 0.25))
        assertFalse(a == Skin("user:1", Img(1, 1), dim = 0.5))
    }

    @Test fun aFirmwareSkinIsReadOffTheMainThreadAndOnce() {
        val jobs = ArrayList<() -> Unit>()
        var reads = 0
        val fw = skinned("""{"skin":1}""", portrait = png(32, 32))
        val store = SkinStore(null, tempDir("skins"), Fake, fileFor = { reads++; fw.path }, background = { jobs.add(it) })
        assertEquals("the user's image: read in the background too", 1, jobs.size)
        jobs.removeAt(0)()
        assertNull("not read yet", store.skin("rfm1:abc"))
        assertNull("asked again: one read queued", store.skin("rfm1:abc"))
        assertEquals(1, jobs.size)
        jobs.removeAt(0)()
        assertEquals("rfm1:abc", store.skin("rfm1:abc")?.id)
        assertEquals(1, reads)
        assertTrue(jobs.isEmpty())
    }
}

/** SharedPreferences in memory (the JVM has no Android) */
class MemPrefs : SharedPreferences {
    val m = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = m
    override fun getString(k: String, d: String?) = m[k] as? String ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?) = @Suppress("UNCHECKED_CAST") (m[k] as? MutableSet<String>) ?: d
    override fun getInt(k: String, d: Int) = m[k] as? Int ?: d
    override fun getLong(k: String, d: Long) = m[k] as? Long ?: d
    override fun getFloat(k: String, d: Float) = m[k] as? Float ?: d
    override fun getBoolean(k: String, d: Boolean) = m[k] as? Boolean ?: d
    override fun contains(k: String) = m.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val e = HashMap<String, Any?>()
        var clear = false
        override fun putString(k: String, v: String?) = apply { e[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { e[k] = v }
        override fun putInt(k: String, v: Int) = apply { e[k] = v }
        override fun putLong(k: String, v: Long) = apply { e[k] = v }
        override fun putFloat(k: String, v: Float) = apply { e[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { e[k] = v }
        override fun remove(k: String) = apply { e[k] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) m.clear()
            for ((k, v) in e) if (v == null) m.remove(k) else m[k] = v
        }
    }
}
