// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// How heavy .rfm1 firmwares play in real time (the testers' JIANT, Rainbow Mode, X0X, Ravel; Felucca for comparison):
// each loaded, booted and played through the Oboe output for some seconds with PLAY pressed and a chord held, read
// as the app reads it (status every 66 ms, the screen every 16 ms). With the instrumentation argument guard=1 the
// app's PerfGuard drives the engine as AppModel does (headroom, screen rate, the core thread's priority); guard=0 is
// the engine left at its defaults (the app before PerfGuard). Numbers per firmware into files/perftest.json:
// underruns and silent frames, the core thread's load and longest wake, frame()'s longest / average, redraws per
// second, frame() calls deferred, the latency, and the offline speed (how many times real time the core renders).
//
// The .rfm1 files: files/test-rfm1/<id>-*.rfm1 (scripts/emu_perftest.sh pushes them), else skipped.
package com.quixotic7.remotefm1.firmware

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.CoresTest
import com.quixotic7.remotefm1.PerfGuard
import com.quixotic7.remotefm1.RfmEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PerfTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test fun heavyFirmwaresInRealTime() {
        val guardOn = args.getString("guard", "1") == "1"
        val seconds = args.getString("seconds", "12").toInt()
        val ids = args.getString("ids", "jiant,rainbow,x0x,ravel,felucca").split(",")
        val buffer = args.getString("buffer", "0").toInt()        // (>0: a paced callback of this size, not Oboe)
        val slow = args.getString("slow", "1").toFloat()           // (a slower CPU: each chunk this many times as long)
        val all = JSONObject().put("guard", guardOn).put("seconds", seconds).put("buffer", buffer).put("slow", slow)
        val dir = File(ctx.filesDir, "test-rfm1")
        for (id in ids) {
            val f = dir.listFiles()?.sortedBy { it.name }?.firstOrNull { it.name.startsWith("$id-") && it.name.endsWith(".rfm1") }
            if (f == null) { all.put(id, JSONObject().put("skipped", "not pushed")); continue }
            all.put(id, play(f, guardOn, PerfGuard(), seconds, buffer, slow))   // (a guard per engine, as the app's one per engine)
            File(ctx.filesDir, "perftest.json").writeText(all.toString(2))
        }
        File(ctx.filesDir, "perftest.json").writeText(all.toString(2))
        Log.i(TAG, all.toString())
        assumeTrue("nothing pushed", ids.any { all.getJSONObject(it).has("underruns") })
    }

    private fun play(f: File, guardOn: Boolean, guard: PerfGuard, seconds: Int, buffer: Int, slow: Float): JSONObject {
        val o = JSONObject().put("file", f.name)
        // the offline speed: 3 s held through the ring as fast as the core goes
        RfmEngine().use { e ->
            assertNull(e.loadWasm(f.path))
            assertTrue(e.boot())
            e.testSlowdown(slow)
            e.renderOffline(48000, 48000.0, 192)
            e.key(KEY_D4, true)
            e.resetStats()
            val t = SystemClock.elapsedRealtimeNanos()
            e.renderOffline(3 * 48000, 48000.0, 192)
            o.put("x_realtime_offline", 3e9 / (SystemClock.elapsedRealtimeNanos() - t))
            val s = e.status()
            o.put("offline_frame_max_us", s.getInt("frame_max_us"))
            if (s.has("frame_avg_us")) o.put("offline_frame_avg_us", s.getInt("frame_avg_us")).put("offline_frames_drawn", s.getLong("frames_drawn"))
        }
        // real time through Oboe
        RfmEngine().use { e ->
            assertNull(e.loadWasm(f.path))
            assertTrue(e.boot())
            e.testSlowdown(slow)
            if (buffer > 0) assertTrue(e.testPacedStart(buffer, 48000.0))
            else assertNull("audio", e.startAudio(CoresTest.speakerId(ctx)))
            try {
                SystemClock.sleep(1500)
                e.button(RfmEngine.B_PLAY, true); SystemClock.sleep(80); e.button(RfmEngine.B_PLAY, false)
                for (k in CHORD) e.key(k, true)
                SystemClock.sleep(1500)
                e.resetStats()
                val rgba = java.nio.ByteBuffer.allocateDirect(240 * 240 * 4)
                val t0 = SystemClock.elapsedRealtime()
                val lcd0 = e.status().getLong("lcd_writes")
                var have = 0
                var nextPoll = 0L
                var lastU = 0
                val windows = JSONArray()
                var nextWin = 2000L
                var s = e.status()
                while (SystemClock.elapsedRealtime() - t0 < seconds * 1000L) {
                    val now = SystemClock.elapsedRealtime() - t0
                    have = e.previewDirect(have, rgba)                  // (the faceplate, each display frame)
                    if (now >= nextPoll) {                              // (AppModel.refresh)
                        nextPoll = now + 66
                        s = e.status()
                        if (guardOn) guard.apply(e, s, f.name)
                    }
                    if (now >= nextWin) {
                        nextWin += 2000
                        val u = s.getInt("underruns")
                        windows.put(JSONObject().put("t", now / 1000).put("underruns", u - lastU)
                            .put("headroom_ms", guard.headroomUs / 1000).put("frame_ms", guard.frameMs))
                        lastU = u
                    }
                    SystemClock.sleep(16)
                }
                s = e.status()
                val wall = (SystemClock.elapsedRealtime() - t0) / 1000.0
                o.put("underruns", s.getInt("underruns")).put("underrun_frames", s.getInt("underrun_frames"))
                    .put("silent_pct", 100.0 * s.getInt("underrun_frames") / (wall * s.getDouble("rate").coerceAtLeast(1.0)))
                    .put("core_load_pct", s.getInt("core_load_pct")).put("core_avg_us", s.getInt("core_avg_us"))
                    .put("core_max_us", s.getInt("core_max_us")).put("frame_max_us", s.getInt("frame_max_us"))
                    .put("lcd_writes_per_s", (s.getLong("lcd_writes") - lcd0) / wall)
                    .put("latency_ms", s.getInt("latency_frames") * 1000.0 / s.getDouble("rate").coerceAtLeast(1.0))
                    .put("realtime", s.getInt("realtime")).put("rate", s.getDouble("rate"))
                    .put("xruns", s.getJSONObject("audio").getInt("xruns"))
                    .put("burst", s.getJSONObject("audio").getInt("frames_per_burst"))
                    .put("windows", windows)
                for (k in listOf("frame_avg_us", "frames_drawn", "frames_deferred", "perf_hint", "frame_ms")) if (s.has(k)) o.put(k, s.get(k))
                if (s.has("frames_drawn")) o.put("redraws_per_s", s.getLong("frames_drawn") / wall)
                if (guardOn) o.put("guard_headroom_ms", guard.headroomUs / 1000).put("guard_frame_ms", guard.frameMs)
                    .put("guard_note", guard.note).put("guard_load", guard.load)
            } finally {
                for (k in CHORD) e.key(k, false)
                if (buffer > 0) e.testPacedStop() else e.stopAudio(RfmEngine.CLOCK_NONE)
            }
        }
        Log.i(TAG, "${f.name}: $o")
        return o
    }

    companion object {
        const val TAG = "RFMPerfTest"
        const val KEY_D4 = 9
        val CHORD = listOf(9, 13, 16)
    }
}
