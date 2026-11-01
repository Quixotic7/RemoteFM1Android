// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported in part from RemoteFM1IOS 0d1a98b RFMKit/Tests/RFMKitTests/CoreTests.swift (testEachCoreBootsAndPlays,
// testFizz, testSaadet, testBoardwalk, testLoom, testFlashRoundTrip, testHaltedCoreIsPowerCycled).
package com.quixotic7.remotefm1

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** One engine on the caller's clock (iOS CoreTests' TestCore). */
class TestCore(val id: String) : AutoCloseable {
    val e = RfmEngine()

    init {
        val err = e.load(id)
        if (err != null) {
            e.close()
            throw AssertionError("load $id: $err")
        }
    }

    fun boot(flash: ByteArray? = null) {
        assertTrue("boot $id", e.boot(flash))
        e.setClock(RfmEngine.CLOCK_CALLER)
    }

    fun run(ms: Int) = e.run(ms)
    val status: JSONObject get() = e.status()
    val lcd: Int get() = e.lcdHash
    fun key(k: Int, down: Boolean) = e.event(RfmEngine.EV_KEY, k, if (down) 1 else 0)
    fun button(label: Int, down: Boolean) = assertTrue("$id button $label", e.button(label, down))
    fun knob(role: Int, detents: Int) = assertTrue("$id knob $role", e.knob(role, detents))
    fun tap(label: Int) {
        button(label, true)
        run(60)
        button(label, false)
        run(100)
    }
    fun param(name: String) = e.param(name)

    /** flash_sync and the image now */
    fun flash(): ByteArray = ByteArray(e.flashSize).also { assertTrue(e.finalFlash(it)) }

    override fun close() = e.close()
}

/**
 * A2's core test: every core boots, draws, sounds with a key held (or its own demo), its flash survives a reboot
 * and a power cycle (written to files and read back through CoreFlash where iOS's tests check a setting), the render
 * load through the render-ahead ring is measured, sound off and on keeps the core's state, and the Oboe stream is
 * reopened after a disconnect. Numbers go into files/coretest.json, one object per test (scripts/emu_coretest.sh).
 */
@RunWith(AndroidJUnit4::class)
class CoresTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val r = JSONObject()
    private var name = ""
    private lateinit var flashDir: File

    @Before fun setUp() {
        assertEquals("cores in the APK", IDS.size, RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir))
        flashDir = File(ctx.cacheDir, "test-cores").also { it.deleteRecursively(); it.mkdirs() }
    }

    @After fun done() {
        flashDir.deleteRecursively()
        save(name, r)
    }

    /** each core: a fresh flash, 3 device seconds, the screen drawn; a held D4 sounds (iOS testEachCoreBootsAndPlays) */
    @Test fun eachCoreBootsAndPlays() {
        name = "each_core"
        for (id in IDS) TestCore(id).use { c ->
            val t0 = SystemClock.elapsedRealtimeNanos()
            c.boot()
            val bootMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            val blank = c.lcd
            val a0 = c.run(3000)
            val s0 = c.status
            val h0 = c.lcd
            assertEquals(id, 2999, s0.getInt("dev_ms"))
            assertTrue(id, s0.getInt("lcd_writes") > 0)
            if (h0 == blank) assertTrue("$id: the LCD never changed and is (nearly) one colour", c.e.colours > 8)
            for (k in STEP_PRELUDE[id].orEmpty()) {
                c.key(k, true); c.run(60); c.key(k, false); c.run(100)
            }
            KEY_PRELUDE[id]?.let { c.tap(it) }
            c.key(KEY_D4, true)
            val a1 = c.run(500)
            c.key(KEY_D4, false)
            c.run(300)
            val s1 = c.status
            r.put(id, JSONObject().put("boot_ms", bootMs).put("idle_rms", a0.rms).put("idle_peak", a0.peak)
                .put("key_rms", a1.rms).put("key_peak", a1.peak).put("frames", a1.frames)
                .put("lcd_writes", s1.getInt("lcd_writes")).put("colours", c.e.colours).put("flash_kb", c.e.flashSize / 1024))
            assertEquals(id, 1 + STEP_PRELUDE[id].orEmpty().size, s1.getInt("keys_pressed"))
            assertTrue("$id: a held D4 is silent (peak ${a1.peak})", a1.peak > 64)
            assertTrue(id, a1.rms > 1e-4)
            assertTrue(id, kotlin.math.abs(a1.frames - 22050) <= 128)
            assertEquals(id, 0, s1.getInt("halted"))
        }
        r.put("pass", true)
    }

    /** each core through the render-ahead ring at 48 kHz in 192-frame renders (the core thread), a key held: its load */
    @Test fun eachCoreRenderLoad() {
        name = "render_load"
        for (id in IDS) TestCore(id).use { c ->
            assertTrue(c.e.boot())
            c.e.renderOffline(48000, 48000.0, 192)                         // (1 s: the boot)
            for (k in STEP_PRELUDE[id].orEmpty()) {
                c.key(k, true); c.e.renderOffline(2880, 48000.0, 192); c.key(k, false); c.e.renderOffline(4800, 48000.0, 192)
            }
            KEY_PRELUDE[id]?.let {
                c.e.button(it, true); c.e.renderOffline(2880, 48000.0, 192); c.e.button(it, false); c.e.renderOffline(4800, 48000.0, 192)
            }
            c.e.resetStats()
            c.key(KEY_D4, true)
            val t0 = SystemClock.elapsedRealtimeNanos()
            val held = c.e.renderOffline(2 * 48000, 48000.0, 192)!!
            val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
            c.key(KEY_D4, false)
            val s = c.status
            val rms = Levels.rms(held)
            r.put(id, JSONObject().put("rms", rms).put("peak", Levels.peak(held).toDouble())
                .put("core_avg_us", s.getInt("core_avg_us")).put("core_max_us", s.getInt("core_max_us"))
                .put("core_load_pct", s.getInt("core_load_pct")).put("frame_max_us", s.getInt("frame_max_us"))
                .put("render_avg_us", s.getInt("render_avg_us")).put("cpu_pct", s.getInt("cpu_pct"))
                .put("x_realtime", 2000.0 / wall).put("underruns", s.getInt("underruns")))
            assertTrue("$id: silent through the ring", rms > 1e-5)
        }
        r.put("pass", true)
    }

    /** FIZZ: its screen draws; nothing before PLAY; PLAY starts the demo project's pattern (iOS testFizz) */
    @Test fun fizzDemo() {
        name = "fizz"
        TestCore("fizz").use { c ->
            c.boot()
            c.run(1500)
            val colours = c.e.colours
            assertTrue("FIZZ's screen is (nearly) one colour", colours > 8)
            assertEquals("FIZZ sounds before PLAY", 0, c.run(500).peak)
            c.button(RfmEngine.B_PLAY, true); c.run(60); c.button(RfmEngine.B_PLAY, false)
            var peak = 0
            var rms = 0.0
            repeat(8) { c.run(500).let { a -> peak = maxOf(peak, a.peak); rms = maxOf(rms, a.rms) } }
            r.put("colours", colours).put("demo_peak", peak).put("demo_rms", rms)
            assertTrue("FIZZ's demo pattern is silent after PLAY", peak > 64)
            assertEquals(0, c.status.getInt("halted"))
        }
        r.put("pass", true)
    }

    /** Saadet draws its page (iOS testSaadet) */
    @Test fun saadetDraws() {
        name = "saadet"
        TestCore("saadet").use { c ->
            c.boot()
            c.run(1500)
            r.put("colours", c.e.colours)
            assertTrue("Saadet's screen is (nearly) one colour", c.e.colours > 8)
            assertEquals(0, c.status.getInt("halted"))
        }
        r.put("pass", true)
    }

    /** Boardwalk: its attract tune with no key; PLAY steps into the cabinet, HOME back out (iOS testBoardwalk) */
    @Test fun boardwalkAttract() {
        name = "boardwalk"
        TestCore("boardwalk").use { c ->
            c.boot()
            val tune = c.run(1500)
            assertTrue("Boardwalk's screen is (nearly) one colour", c.e.colours > 8)
            assertTrue("the attract tune is silent", tune.peak > 64)
            val walk = c.lcd
            c.button(RfmEngine.B_PLAY, true); c.run(60); c.button(RfmEngine.B_PLAY, false); c.run(500)
            val cabinet = c.lcd
            assertNotEquals("PLAY did not step into the cabinet", walk, cabinet)
            c.button(RfmEngine.B_HOME, true); c.run(60); c.button(RfmEngine.B_HOME, false); c.run(500)
            assertNotEquals("HOME did not step out", cabinet, c.lcd)
            r.put("tune_peak", tune.peak).put("tune_rms", tune.rms)
            assertEquals(0, c.status.getInt("halted"))
        }
        r.put("pass", true)
    }

    /** LOOM-8: four kicks written on T1's steps, PLAY plays them (iOS testLoom) */
    @Test fun loomKicks() {
        name = "loom"
        TestCore("loom").use { c ->
            c.boot()
            c.run(500)
            assertTrue(c.e.colours > 8)
            for (k in STEP_PRELUDE.getValue("loom")) {
                c.key(k, true); c.run(60); c.key(k, false); c.run(100)
            }
            c.button(RfmEngine.B_PLAY, true); c.run(60); c.button(RfmEngine.B_PLAY, false)
            val a = c.run(2000)
            r.put("kicks_peak", a.peak).put("kicks_rms", a.rms)
            assertTrue("LOOM-8's written kicks are silent", a.peak > 64)
            assertEquals(0, c.status.getInt("halted"))
        }
        r.put("pass", true)
    }

    /** ChoralRoot's tempo one detent up and SAVE held, written as the app writes it (CoreFlash, files/cores/<id>.bin),
     *  read back into a new engine: the tempo kept (iOS testFlashRoundTrip, through the app's flash files) */
    @Test fun choralRootTempoSurvivesThroughTheFlashFile() {
        name = "choralroot_flash"
        TestCore("choralroot").use { c ->
            val f = CoreFlash(c.e, flashDir) { "0.26.1" }
            assertEquals("", f.boot("choralroot"))
            c.e.setClock(RfmEngine.CLOCK_CALLER)
            c.run(600)
            assertEquals(120, c.param("Tempo"))
            c.knob(RfmEngine.E_SELECT, 1)
            c.run(300)
            c.button(RfmEngine.B_SAVE, true); c.run(1000); c.button(RfmEngine.B_SAVE, false); c.run(600)
            assertEquals(121, c.param("Tempo"))
            f.saveStopped()
            f.flush()
        }
        val file = File(flashDir, "choralroot.bin")
        assertTrue(file.isFile)
        val meta = FlashFile.meta(file)!!
        assertEquals("choralroot", meta.firmwareID)
        assertEquals("0.26.1", meta.version)
        assertEquals(FlashFile.sha256(file.readBytes()), meta.sha256)
        r.put("file_bytes", file.length()).put("sha256", meta.sha256)
        TestCore("choralroot").use { c ->
            val f = CoreFlash(c.e, flashDir)
            assertEquals("", f.boot("choralroot"))
            c.e.setClock(RfmEngine.CLOCK_CALLER)
            assertArrayEquals("the image staged back is not the file", file.readBytes(), c.flash())
            c.run(1000)
            assertEquals("the saved tempo did not survive the reboot", 121, c.param("Tempo"))
        }
        // the file damaged: the .prev of an earlier save is used, with the note
        TestCore("choralroot").use { c ->
            val f = CoreFlash(c.e, flashDir)
            assertEquals("", f.boot("choralroot"))
            f.saveStopped()                                            // (a second save: the first becomes .prev)
            f.flush()
        }
        assertTrue(FlashFile.prevFile(file).isFile)
        file.writeBytes(ByteArray(10))
        TestCore("choralroot").use { c ->
            val f = CoreFlash(c.e, flashDir)
            val note = f.boot("choralroot")
            r.put("damaged_note", note)
            assertEquals("The saved memory was damaged (10 bytes, not ${c.e.flashSize}): started from the copy saved before it.", note)
            c.e.setClock(RfmEngine.CLOCK_CALLER)
            c.run(1000)
            assertEquals(121, c.param("Tempo"))
        }
        r.put("pass", true)
    }

    /** Felucca's palette from its menu, saved through CoreFlash: a reboot's screen is not a fresh one's */
    @Test fun feluccaPaletteSurvivesThroughTheFlashFile() {
        name = "felucca_flash"
        TestCore("felucca").use { c ->
            val f = CoreFlash(c.e, flashDir)
            assertEquals("", f.boot("felucca"))
            c.e.setClock(RfmEngine.CLOCK_CALLER)
            c.run(1500)
            c.button(RfmEngine.B_HOME, true); c.run(1200); c.button(RfmEngine.B_HOME, false); c.run(400)
            c.knob(RfmEngine.E_K1, 1); c.run(400)
            c.button(RfmEngine.B_HOME, true); c.run(100); c.button(RfmEngine.B_HOME, false); c.run(600)
            f.saveStopped()
            f.flush()
        }
        TestCore("felucca").use { saved ->
            TestCore("felucca").use { fresh ->
                assertEquals("", CoreFlash(saved.e, flashDir).boot("felucca"))
                saved.e.setClock(RfmEngine.CLOCK_CALLER)
                fresh.boot()
                saved.run(1500)
                fresh.run(1500)
                assertNotEquals("felucca's saved palette did not come back", saved.lcd, fresh.lcd)
            }
        }
        r.put("pass", true)
    }

    /** ChoralRoot's own reboot (OCT- OCT+ HOME held: exit(0)): the app's tick (CoreFlash.refresh) powers it on again
     *  from its flash and writes the image; the tempo kept (iOS testHaltedCoreIsPowerCycled) */
    @Test fun haltedCoreIsPowerCycled() {
        name = "power_cycle"
        TestCore("choralroot").use { c ->
            val f = CoreFlash(c.e, flashDir) { "0.26.1" }
            f.boot("choralroot")
            c.e.setClock(RfmEngine.CLOCK_CALLER)
            c.run(600)
            c.knob(RfmEngine.E_SELECT, 1)
            c.run(300)
            c.button(RfmEngine.B_SAVE, true); c.run(1000); c.button(RfmEngine.B_SAVE, false); c.run(600)
            for (b in listOf(RfmEngine.B_OCTDN, RfmEngine.B_OCTUP, RfmEngine.B_HOME)) c.button(b, true)
            c.run(6500)
            val h = c.status
            assertEquals("the update gesture did not halt the firmware", 1, h.getInt("halted"))
            assertEquals(0x100, h.getInt("halt_code"))
            val msg = f.refresh(h)
            r.put("message", msg)
            assertEquals("the firmware rebooted (exit 0): powered on again from its flash", msg)
            assertEquals(1, f.reboots)
            f.flush()
            val s = c.status
            assertEquals(0, s.getInt("halted"))
            assertEquals(1, s.getInt("booted"))
            c.e.setClock(RfmEngine.CLOCK_CALLER)                          // (the power cycle gave the clock back)
            c.run(1000)
            assertEquals(121, c.param("Tempo"))
            assertTrue("the power cycle's image was not written", File(flashDir, "choralroot.bin").isFile)
        }
        // the user's Restart on every core: the image kept bit for bit, a setting of its own where iOS knows one
        for (id in IDS) TestCore(id).use { c ->
            c.boot()
            c.run(1500)
            for (k in STEP_PRELUDE[id].orEmpty()) {
                c.key(k, true); c.run(60); c.key(k, false); c.run(100)
            }
            KEY_PRELUDE[id]?.let { c.tap(it) }
            c.run(500)
            val before = c.flash()
            assertNull(id, c.e.powerCycle())
            val offered = ByteArray(c.e.flashSize)
            assertTrue("$id: the power cycle's image was not offered for saving", c.e.takeFlash(offered))
            assertArrayEquals("$id: the image offered is not the one before", before, offered)
            assertEquals(id, 1, c.status.getInt("booted"))
            c.e.setClock(RfmEngine.CLOCK_CALLER)
            c.run(1000)
            val after = c.flash()
            val diff = before.indices.count { before[it] != after[it] }
            r.put(id, JSONObject().put("bytes_changed_by_the_reboot", diff).put("used_kb", before.count { it != 0xFF.toByte() } / 1024))
            // a reboot from the image: the image staged back reads back the same (ChoralRoot and Felucca, as iOS
            // checks; FIZZ writes its own flash as it boots, so for the others the bytes that moved are only counted)
            TestCore(id).use { d ->
                d.boot(after)
                val back = d.flash()
                val moved = after.indices.count { after[it] != back[it] }
                r.getJSONObject(id).put("bytes_changed_by_a_boot_from_it", moved)
                if (id == "choralroot" || id == "felucca") assertArrayEquals("$id: the image staged back is not the image read", after, back)
            }
        }
        r.put("pass", true)
    }

    /** sound off and on: the core on the timer clock, then the audio's, then the timer's again; its state kept (no
     *  reboot: device time goes on, the tempo set before stays) */
    @Test fun soundOffAndOnKeepsTheCore() {
        name = "sound_switch"
        TestCore("choralroot").use { c ->
            c.boot()
            c.run(600)
            c.knob(RfmEngine.E_SELECT, 1)
            c.run(300)
            assertEquals(121, c.param("Tempo"))
            c.e.setClock(RfmEngine.CLOCK_TIMER)
            SystemClock.sleep(500)
            val t1 = c.status.getInt("dev_ms")
            val err = c.e.startAudio(speakerId(ctx))
            r.put("start_error", err ?: "")
            if (err == null) {
                SystemClock.sleep(800)
                val a = c.status.getJSONObject("audio")
                r.put("stream", a)
                assertEquals(1, a.getInt("running"))
                c.e.stopAudio(RfmEngine.CLOCK_TIMER)
            }
            SystemClock.sleep(300)
            val s = c.status
            r.put("dev_ms_timer", t1).put("dev_ms_end", s.getInt("dev_ms"))
            assertEquals(RfmEngine.CLOCK_TIMER, s.getInt("clock"))
            assertTrue("device time did not go on: $t1 -> ${s.getInt("dev_ms")}", s.getInt("dev_ms") > t1 + 500)
            assertEquals("the switch rebooted the core", 121, c.e.param("Tempo", anyClock = true))
            assertEquals(1, s.getInt("booted"))
        }
        r.put("pass", true)
    }

    /** a disconnect (the stream closed under us, as when the FM-1's USB audio goes): dead, then reopened as AppModel
     *  does (the timer's clock, a new stream), the core playing again; the count kept. The emulator can't add or
     *  remove an audio device, so the disconnect is the test hook's (RFMOboe rfmo_test_disconnect) */
    @Test fun oboeReopensAfterADisconnect() {
        name = "oboe_reopen"
        TestCore("choralroot").use { c ->
            assertTrue(c.e.boot())
            val dev = speakerId(ctx)
            val err = c.e.startAudio(dev)
            r.put("start_error", err ?: "")
            assertNull("start: $err", err)
            SystemClock.sleep(1500)                                        // (past ChoralRoot's start)
            c.e.key(KEY_D4, true, 100)
            SystemClock.sleep(600)
            val first = c.status.getJSONObject("audio")
            r.put("first", first)
            assertTrue("silent before the disconnect", first.getDouble("rms") > 1e-4)
            c.e.testDisconnect()
            val dead = c.status.getJSONObject("audio")
            assertEquals(1, dead.getInt("dead"))
            assertEquals(1, dead.getInt("disconnects"))
            // AppModel.restartAudio
            c.e.setClock(RfmEngine.CLOCK_TIMER)
            c.e.stopAudio(RfmEngine.CLOCK_TIMER)
            c.e.resetStats()
            assertNull(c.e.startAudio(dev))
            c.e.key(KEY_D4, false)
            c.e.key(KEY_D4, true, 100)
            SystemClock.sleep(800)
            val s = c.status
            val again = s.getJSONObject("audio")
            r.put("again", again).put("engine_underruns", s.getInt("underruns")).put("ring_target", s.getInt("ring_target"))
            assertEquals(0, again.getInt("dead"))
            assertEquals(1, again.getInt("running"))
            assertEquals(1, again.getInt("disconnects"))
            assertTrue(again.getInt("callbacks") > 10)
            assertTrue("silent after the reopen", again.getDouble("rms") > 1e-4)
            assertEquals(RfmEngine.CLOCK_AUDIO, s.getInt("clock"))
            c.e.key(KEY_D4, false)
            c.e.stopAudio(RfmEngine.CLOCK_NONE)
        }
        // the outputs Android lists now
        val am = ctx.getSystemService(AudioManager::class.java)
        r.put("outputs", am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "${it.id} ${it.productName} type ${it.type}" })
        r.put("pass", true)
    }

    companion object {
        const val TAG = "RFMCoreTest"
        val IDS = listOf("choralroot", "felucca", "fizz", "saadet", "boardwalk", "loom")
        /** a button tapped before a key sounds: FIZZ's keys are its steps until REC; LOOM-8 plays with PLAY */
        val KEY_PRELUDE = mapOf("fizz" to RfmEngine.B_REC, "loom" to RfmEngine.B_PLAY)
        /** LOOM-8 opens T1 (a drum kit) on its Steps page: four kicks written first */
        val STEP_PRELUDE = mapOf("loom" to listOf(2, 7, 14, 21))
        const val KEY_D4 = 9                                            // (key 0 is F3)

        /** the device's own speaker (0: none listed, the default), so a test never plays into a USB sound card */
        fun speakerId(ctx: Context): Int = ctx.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.id ?: 0

        @Synchronized
        fun save(name: String, r: JSONObject) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val f = File(ctx.filesDir, "coretest.json")
            val all = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
            if (!r.has("pass")) r.put("pass", false)
            all.put(name, r)
            f.writeText(all.toString(2))
            Log.i(TAG, "$name: $r")
        }
    }
}
