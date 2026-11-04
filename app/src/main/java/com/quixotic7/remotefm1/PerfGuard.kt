// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/CorePlayer.swift (PerfGuard, from 836a983, 3828820 and
// 14c9b96's updatePerf); changes: reads the engine's status JSON, and apply() does CorePlayer.updatePerf's engine calls.
package com.quixotic7.remotefm1

import android.util.Log
import org.json.JSONObject

/**
 * Whether a Wasm core keeps up: its core thread's time over the audio's in windows of ~2 s (from the status's
 * totals). The screen's frame() and the audio share that thread, and a core that redraws its whole screen each frame
 * (Rainbow Mode) drops out with a low average load. So a Wasm core redraws every 33 ms (30 fps), not every 15 ms, and
 * starts with 12 ms of headroom. On dropouts, each window: the headroom doubles (up to [MAX_HEADROOM_US]) and the
 * screen slows a step (50, then 66 ms). What a firmware needed is kept for this run of the app, so going back to it
 * doesn't drop out again. The note when it is over ~90 %, or 3 or more underruns in a window with nothing left to give.
 * Native cores: the defaults, never a note. Also: a Wasm core's thread is an ordinary high-priority one, not
 * SCHED_FIFO (rfme_set_realtime 0), since its redraws overrun any real-time budget.
 */
class PerfGuard {
    private class Window(val at: Long, val ticks: Double, val frames: Long, val underruns: Long)
    private var window: Window? = null
    private var core = ""
    private val learned = HashMap<String, Pair<Int, Int>>()     // id -> headroom us, frame step
    private var frameStep = 0
    private var realtimeKind = ""

    /** the last window's load (0..1+) */
    var load = 0.0
        private set
    var note = ""
        private set
    /** the ring's headroom this core needs (rfme_set_latency) */
    var headroomUs = DEFAULT_HEADROOM_US
        private set
    /** how often its screen redraws (rfme_set_frame_ms; the battery's slower rate wins) */
    var frameMs = DEFAULT_FRAME_MS
        private set

    private fun use(id: String) {
        if (core.isNotEmpty()) learned[core] = headroomUs to frameStep
        core = id
        if (id.isEmpty()) {
            headroomUs = DEFAULT_HEADROOM_US
            frameStep = 0
            frameMs = DEFAULT_FRAME_MS
        } else {
            val l = learned[id] ?: (WASM_HEADROOM_US to 0)
            headroomUs = l.first
            frameStep = l.second
            frameMs = WASM_FRAME_MS[frameStep]
        }
    }

    /** iOS update(_:name:now:), from the status's fields; [nowMs] any monotonic ms */
    fun update(kind: String, booted: Boolean, coreID: String, coreWakes: Long, coreAvgUs: Long, coreFrames: Long,
               rate: Double, underruns: Long, name: String, nowMs: Long): String {
        if (kind != "wasm" || !booted || coreWakes <= 0 || rate <= 0) {
            window = null
            note = ""
            if (kind != "wasm") { if (core.isNotEmpty()) use("") } else if (coreID != core) use(coreID)
            return note
        }
        if (coreID != core) use(coreID)
        val ticks = coreAvgUs.toDouble() * coreWakes.toDouble()
        val w = window
        if (w == null || coreFrames < w.frames || ticks < w.ticks) {
            window = Window(nowMs, ticks, coreFrames, underruns)    // (the first, or the stats were reset)
            return note
        }
        if (nowMs - w.at < 2000) return note
        val audioUs = (coreFrames - w.frames) / rate * 1e6
        load = if (audioUs > 0) (ticks - w.ticks) / audioUs else 0.0
        var under = if (underruns >= w.underruns) underruns - w.underruns else 0
        window = Window(nowMs, ticks, coreFrames, underruns)
        if (under > 0 && (headroomUs < MAX_HEADROOM_US || frameStep < WASM_FRAME_MS.size - 1)) {
            headroomUs = minOf(MAX_HEADROOM_US, headroomUs * 2)     // (dropouts: more headroom, a slower screen)
            frameStep = minOf(WASM_FRAME_MS.size - 1, frameStep + 1)
            frameMs = WASM_FRAME_MS[frameStep]
            learned[core] = headroomUs to frameStep
            under = 0
        }
        note = note(load, under, name)
        return note
    }

    /** CorePlayer.updatePerf: the status read, the engine told what changed (priority, headroom, screen rate) */
    fun apply(e: RfmEngine, s: JSONObject, name: String, nowMs: Long = android.os.SystemClock.elapsedRealtime()): String {
        val kind = s.optString("kind")
        if (kind.isNotEmpty() && kind != realtimeKind) {        // (an interpreted core: no real-time budget to overrun)
            realtimeKind = kind
            e.setRealtime(kind != "wasm")
        }
        val had = headroomUs
        val hadFrame = frameMs
        update(kind, s.optInt("booted") != 0, s.optString("core_id"), s.optLong("core_wakes"), s.optLong("core_avg_us"),
               s.optLong("core_frames"), s.optDouble("rate", 0.0), s.optLong("underruns"), name, nowMs)
        if (frameMs != hadFrame) e.setPerfFrameMs(frameMs)
        if (headroomUs != had || frameMs != hadFrame) {
            e.setLatency(headroomUs)
            runCatching { Log.i("RFMPerf", "core headroom ${headroomUs / 1000} ms, screen every $frameMs ms (${s.optString("core_id")})") }
        }
        return note
    }

    companion object {
        const val DEFAULT_HEADROOM_US = 6000        // (RFMEngine.cpp kDefaultHeadroomUs)
        const val WASM_HEADROOM_US = 12000
        const val MAX_HEADROOM_US = 40000
        const val DEFAULT_FRAME_MS = 15             // (RFMDevice.h frame_ms_)
        val WASM_FRAME_MS = intArrayOf(33, 50, 66)

        /** the note for a Wasm core's load (0..1+) and its underruns in the last window; "" when it keeps up */
        fun note(load: Double, underruns: Long, name: String): String {
            if (!(load > 0.9 || underruns >= 3)) return ""
            return "%s is too heavy for this device (%.0f%% of the audio time%s): expect crackles. A built-in core, or fewer instances, plays cleanly."
                .format(name, load * 100, if (underruns > 0) ", $underruns dropouts" else "")
        }
    }
}
