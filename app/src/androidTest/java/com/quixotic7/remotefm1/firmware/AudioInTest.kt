// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Tests/RFMKitTests/AudioInTests.swift (23bba90, c47ac4f): ain1, live audio
// input for an .rfm1 (RemoteFM1 web/engine/API.md section 6), through the engine: the manifest's "wants", the input
// ring, rfm1_audio_in_info / rfm1_audio_in before rfm1_audio. firmware/ain_cart.rfm1 (iOS's fixture, from RemoteFM1's
// web/engines/rfm1/fixtures) plays its input back: its output is the input. Android adds: the Oboe input opens on the
// emulator's mic and feeds the ring (RECORD_AUDIO granted by the test); Elasticat from the catalog, when pushed.
package com.quixotic7.remotefm1.firmware

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.RfmEngine
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioInTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val r = JSONObject()
    private var name = ""

    @After fun done() = FirmwareTest.save(name, r)

    private fun cart(): File {
        val f = File(ctx.cacheDir, "ain_cart.rfm1")
        InstrumentationRegistry.getInstrumentation().context.assets.open("firmware/ain_cart.rfm1").use { i ->
            f.outputStream().use { i.copyTo(it) }
        }
        return f
    }

    private fun tone(frames: Int, amp: Double = 0.5) = ShortArray(frames * 2) { k ->
        (amp * 32767 * sin((k / 2) * 2 * PI * 441 / 44100)).toInt().toShort()
    }

    @Test fun theManifestSaysItWantsInput() {
        name = "ain_manifest"
        assertEquals("wants", 1, RfmEngine.audioInOf(cart().path))
        assertEquals("a built-in style file without ain1", 0, RfmEngine.audioInOf(FirmwareTestFiles.amb(ctx).path))
        r.put("pass", true)
    }

    @Test fun inputComesThroughOnlyWhileLive() {
        name = "ain_live"
        WasmCoreUnderTest(cart().path).use { c ->
            c.boot()
            assertEquals(1, c.e.audioInMode)
            c.run(200)
            assertEquals("off: silence", 0, c.run(200).peak)
            val lcdOff = c.lcd
            c.e.audioInSet(true, 1234, 256)
            val t = tone(44100)
            var sound = c.run(1)
            for (k in 0 until 20) {                             // 20 x 50 ms of input, the core taking it as it runs
                c.e.audioInPush(t.copyOfRange(k * 2205 * 2, (k + 1) * 2205 * 2))
                sound = c.run(50)
            }
            assertTrue("live: the cart plays its input back (rms ${sound.rms})", sound.rms > 0.2)
            assertNotEquals("its screen: green while live", lcdOff, c.lcd)
            assertEquals(0, c.status.getJSONObject("audio_in").getInt("dropped_frames"))
            c.e.audioInSet(false, 0, 0)
            c.run(100)
            assertEquals("off again: silence", 0, c.run(100).peak)
            r.put("live_rms", sound.rms).put("pass", true)
        }
    }

    @Test fun floatInputIsResampledTo44k() {
        name = "ain_resample"
        WasmCoreUnderTest(cart().path).use { c ->
            c.boot()
            c.e.audioInSet(true, 0, 256)
            // 48 kHz mono, 1 s, in 480-frame buffers: about 44100 frames reach the ring, the tone's level kept
            var phase = 0.0
            var got = c.run(1)
            for (k in 0 until 100) {
                val buf = FloatArray(480) { (0.5 * sin(phase)).toFloat().also { phase += 2 * PI * 441 / 48000 } }
                c.e.audioInPushF32(buf, null, 480, 48000.0)
                if (k % 2 == 1) got = c.run(20)
            }
            assertTrue("a 0.5 sine: RMS about 0.35 (${got.rms})", got.rms > 0.3 && got.rms < 0.4)
            assertEquals(0, c.status.getJSONObject("audio_in").getInt("dropped_frames"))
            r.put("rms", got.rms).put("pass", true)
        }
    }

    /** the Oboe input on the emulator's mic: it opens, its callbacks run and feed the engine's ring */
    @Test fun oboeInputFeedsTheRing() {
        name = "ain_oboe"
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        WasmCoreUnderTest(cart().path).use { c ->
            c.boot()
            c.e.audioInSet(true, 2000, 512)
            val err = c.e.inputStart(0)
            assumeTrue("no input on this device: $err", err == null)
            try {
                SystemClock.sleep(600)
                val s = c.status.getJSONObject("audio_in")
                r.put("input", s)
                assertEquals(1, s.getInt("open"))
                assertTrue("callbacks ${s.getInt("callbacks")}", s.getInt("callbacks") > 0)
                c.run(200)                                      // (the core takes from the ring)
                val lat = c.e.inputLatencyMs()
                r.put("latency_ms", lat)
            } finally {
                c.e.inputStop()
            }
            assertEquals(0, c.status.getJSONObject("audio_in").getInt("open"))
            r.put("pass", true)
        }
    }

    /** Elasticat from the catalog (files/test-rfm1/elasticat-*.rfm1, pushed): says it takes input, boots, runs with a tone in */
    @Test fun elasticat() {
        name = "ain_elasticat"
        val f = File(ctx.filesDir, "test-rfm1").listFiles()?.firstOrNull { it.name.startsWith("elasticat-") }
        assumeTrue("no elasticat .rfm1 pushed", f != null)
        val mode = RfmEngine.audioInOf(f!!.path)
        assertTrue(mode > 0)
        WasmCoreUnderTest(f.path).use { c ->
            c.boot()
            assertTrue(c.e.audioInMode > 0)
            c.e.audioInSet(true, 2000, 512)
            val t = tone(44100)
            for (k in 0 until 20) {
                c.e.audioInPush(t.copyOfRange(k * 2205 * 2, (k + 1) * 2205 * 2))
                c.run(50)
            }
            assertEquals("running", 0, c.status.getInt("halted"))
            r.put("file", f.name).put("audio_in", mode).put("pass", true)
        }
    }
}

/** the test APK's AMB-1 copied out (FirmwareTest's) */
object FirmwareTestFiles {
    fun amb(ctx: android.content.Context): File {
        val f = File(ctx.cacheDir, "amb-1.3.rfm1")
        if (!f.isFile || f.length() != 214_629L) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("firmware/amb-1.3.rfm1").use { i ->
                f.outputStream().use { i.copyTo(it) }
            }
        }
        return f
    }
}
