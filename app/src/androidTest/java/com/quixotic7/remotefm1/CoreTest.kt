// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A0's core test (A2's per-core cases are CoresTest): ChoralRoot from the APK's native library folder boots through the engine, plays after a key goes
 * down (3 s rendered offline through the render-ahead ring), and the Oboe stream is tried. Every number goes into
 * files/coretest.json under "a0" (scripts/emu_coretest.sh prints it) and the log (tag RFMCoreTest).
 */
@RunWith(AndroidJUnit4::class)
class CoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val result = JSONObject()

    @Test
    fun choralRootBootsAndPlays() {
        try {
            run()
        } finally {
            CoresTest.save("a0", result)
        }
    }

    private fun run() {
        val dir = ctx.applicationInfo.nativeLibraryDir
        val n = RfmEngine.scan(dir)
        val cores = RfmEngine.cores()
        result.put("native_lib_dir", dir)
        result.put("cores_found", n)
        result.put("cores", JSONArray((0 until cores.getJSONArray("cores").length()).map {
            val c = cores.getJSONArray("cores").getJSONObject(it)
            "${c.getString("id")} ${c.getString("version")}"
        }))
        result.put("scan_errors", cores.getString("errors"))

        RfmEngine().use { e ->
            val t0 = SystemClock.elapsedRealtimeNanos()
            val err = e.load(CORE)
            val t1 = SystemClock.elapsedRealtimeNanos()
            result.put("load_error", err ?: "")
            assertNull("load: $err", err)
            assertTrue(e.boot())
            val t2 = SystemClock.elapsedRealtimeNanos()
            result.put("load_ms", (t1 - t0) / 1e6)
            result.put("boot_ms", (t2 - t1) / 1e6)

            // 1 s with no key (the boot), then a key held for 3 s, all through the ring at 48 kHz in 192-frame renders
            val rate = 48000.0
            val buffer = 192
            val r0 = SystemClock.elapsedRealtimeNanos()
            val before = e.renderOffline(rate.toInt(), rate, buffer)
            val r1 = SystemClock.elapsedRealtimeNanos()
            assertNotNull(before)
            result.put("before_key_rms", Levels.rms(before!!))
            result.put("before_key_wall_ms", (r1 - r0) / 1e6)
            val afterBoot = e.status()
            result.put("dev_ms_after_boot_render", afterBoot.getInt("dev_ms"))

            e.resetStats()
            assertTrue(e.key(KEY, true, 100))
            val r2 = SystemClock.elapsedRealtimeNanos()
            val held = e.renderOffline(3 * rate.toInt(), rate, buffer)
            val r3 = SystemClock.elapsedRealtimeNanos()
            assertNotNull(held)
            e.key(KEY, false)
            val rms = Levels.rms(held!!)
            val first = Levels.firstSound(held)
            val s = e.status()
            result.put("key", KEY)
            result.put("held_rms", rms)
            result.put("held_peak", Levels.peak(held).toDouble())
            result.put("first_sound_ms", if (first < 0) -1.0 else first * 1000.0 / rate)
            result.put("held_wall_ms", (r3 - r2) / 1e6)
            result.put("held_audio_ms", 3000)
            result.put("realtime_factor", 3000.0 / ((r3 - r2) / 1e6))
            result.put("keys_pressed", s.getInt("keys_pressed"))
            result.put("renders", s.getLong("renders"))
            result.put("render_avg_us", s.getInt("render_avg_us"))
            result.put("render_max_us", s.getInt("render_max_us"))
            result.put("core_avg_us", s.getInt("core_avg_us"))
            result.put("core_max_us", s.getInt("core_max_us"))
            result.put("core_wakes", s.getLong("core_wakes"))
            result.put("core_load_pct", s.getInt("core_load_pct"))
            result.put("frame_max_us", s.getInt("frame_max_us"))
            result.put("latency_frames", s.getInt("latency_frames"))
            result.put("ring_target", s.getInt("ring_target"))
            result.put("underruns_offline", s.getInt("underruns"))
            result.put("realtime", s.getInt("realtime"))
            result.put("core_cpu_pct", s.getInt("cpu_pct"))
            assertEquals(1, s.getInt("booted"))
            assertTrue("held RMS $rms", rms > 0.0)

            // the Oboe stream for 2 s with the key held (the emulator may have no audio device; a real device plays
            // on its own speaker, never on a USB sound card such as an FM-1's)
            val audio = JSONObject()
            e.resetStats()
            val speaker = CoresTest.speakerId(ctx)
            audio.put("device_asked", speaker)
            val aerr = e.startAudio(speaker)
            audio.put("start_error", aerr ?: "")
            if (aerr == null) {
                e.key(KEY, true, 100)
                SystemClock.sleep(2000)
                val st = e.status()
                audio.put("engine_underruns", st.getInt("underruns"))
                audio.put("engine_renders", st.getLong("renders"))
                audio.put("render_avg_us", st.getInt("render_avg_us"))
                audio.put("render_max_us", st.getInt("render_max_us"))
                audio.put("latency_frames", st.getInt("latency_frames"))
                audio.put("ring_target", st.getInt("ring_target"))
                audio.put("core_load_pct", st.getInt("core_load_pct"))
                audio.put("stream", st.getJSONObject("audio"))
                e.key(KEY, false)
                e.stopAudio(RfmEngine.CLOCK_NONE)
            } else {
                audio.put("stream", e.status().getJSONObject("audio"))
            }
            result.put("oboe", audio)

            // a second load: the old copy unloaded (did its image leave memory?), a fresh one boots and plays
            val reloadErr = e.load(CORE)
            result.put("reload_error", reloadErr ?: "")
            assertNull("reload: $reloadErr", reloadErr)
            assertTrue(e.boot())
            e.renderOffline(rate.toInt() / 2, rate, buffer)
            e.key(KEY, true, 100)
            val again = e.renderOffline(rate.toInt() / 2, rate, buffer)
            e.key(KEY, false)
            result.put("reload_rms", Levels.rms(again!!))
            val info = RfmEngine.cores().getJSONArray("cores")
            for (i in 0 until info.length()) {
                val c = info.getJSONObject(i)
                if (c.getString("id") == CORE) {
                    result.put("copies_in_use", c.getInt("in_use"))
                    result.put("copies_retired", c.getInt("retired"))
                }
            }
            result.put("pass", true)
        }
    }

    companion object {
        const val TAG = "RFMCoreTest"
        const val CORE = "choralroot"
        const val KEY = 7
    }
}
