// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/BackgroundPolicy.swift and App/Battery.swift (dfbc827,
// e527b74); changes: Kotlin, SharedPreferences, a Handler; the lock screen's Now Playing is the foreground service's
// media notification (PlaybackService: Pause / Stop, Play after a stop).
// The battery (2026-11-01, the owner: users found the app drains the battery in the background). The app keeps
// playing in the background (the foreground service); this decides when it stops instead:
//   BackgroundMode    the setting: keep playing / only with an FM-1 connected / stop when I leave the app
//   IdleWatch         how long nothing happened: no input (keys, panel, MIDI) and no sound above -60 dBFS
//   BackgroundPolicy  the decision, from the setting, the FM-1's link and the idle time
//   Battery           in the background, every 15 s: the screen redrawn every 250 ms when no FM-1 shows it, and the
//                     firmware stopped (saved first, its clock and the sound off) when the policy says so; it runs
//                     again when the app comes back or Play is pressed in the notification
package com.quixotic7.remotefm1

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class BackgroundMode(val raw: String, val title: String, val detail: String) {
    ALWAYS("always", "Keep playing", "The firmware keeps playing with the screen locked. This uses the battery."),
    WITH_FM1("withFM1", "Only with an FM-1 connected", "It keeps playing while an FM-1 is connected, and stops when none is."),
    NEVER("never", "Stop playing", "It stops when you leave the app or lock the screen.");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

data class BackgroundPolicy(val mode: BackgroundMode = BackgroundMode.ALWAYS, val idleStop: Boolean = true) {
    /** stop after this long with nothing happening, in any mode (seconds) */
    val idleAfter: Long = 600

    /** in the background: stop now? (null: keep going) and why */
    fun stop(fm1Linked: Boolean, idleForS: Long): String? {
        when (mode) {
            BackgroundMode.NEVER -> return "set to stop when leaving the app"
            BackgroundMode.WITH_FM1 -> if (!fm1Linked) return "no FM-1 connected"
            BackgroundMode.ALWAYS -> {}
        }
        if (idleStop && idleForS >= idleAfter) return "nothing played for ${idleAfter / 60} minutes"
        return null
    }

    fun save(p: SharedPreferences) {
        p.edit().putString(MODE_KEY, mode.raw).putBoolean(IDLE_KEY, idleStop).apply()
    }

    companion object {
        const val MODE_KEY = "rfm1.background"
        const val IDLE_KEY = "rfm1.idleStop"

        fun load(p: SharedPreferences) =
            BackgroundPolicy(BackgroundMode.of(p.getString(MODE_KEY, null)) ?: BackgroundMode.ALWAYS, p.getBoolean(IDLE_KEY, true))
    }
}

/** what the background policy looks at: the device clock, the last sound, and a count that moves with any input */
data class Activity(val devMs: Long = 0, val lastSoundMs: Long = 0, val inputs: Long = 0) {
    companion object {
        /** from the engine's status (RfmEngine.status) */
        fun of(st: org.json.JSONObject) = Activity(st.optLong("dev_ms"), st.optLong("last_sound_ms"),
            st.optLong("keys_pressed") + st.optLong("events_applied") + st.optLong("midi_in") + st.optLong("midi_to_core"))
    }
}

/** how long nothing happened, from Activity samples (times in ms) */
class IdleWatch(now: Long) {
    private var last: Activity? = null
    private var since = now

    /** a new sample: anything new (an input, a sound) restarts the quiet time */
    fun feed(a: Activity, now: Long) {
        val l = last
        if (l == null || l.inputs != a.inputs || l.lastSoundMs != a.lastSoundMs) since = now
        last = a
    }

    fun idleForMs(now: Long): Long = now - since

    fun reset(now: Long) {
        since = now
        last = null
    }
}

/** the battery in the app (the main thread) */
class Battery(private val prefs: SharedPreferences, private val host: Host) {
    /** what Battery needs of the app (AppModel) */
    interface Host {
        val coreMode: Boolean
        val fm1Linked: Boolean
        val activity: Activity
        val suspended: Boolean
        fun setFrameMs(ms: Int)
        fun suspendForBattery(why: String)
        fun resumeFromBattery()
    }

    var policy by mutableStateOf(BackgroundPolicy.load(prefs))
        private set
    /** why the firmware was stopped last ("" none), shown in Settings */
    var lastStop by mutableStateOf("")
        private set

    private val main = Handler(Looper.getMainLooper())
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    private val watch = IdleWatch(clock())
    private var background = false
    private val loop = object : Runnable {
        override fun run() {
            check()
            main.postDelayed(this, CHECK_MS)
        }
    }

    fun use(p: BackgroundPolicy) {
        policy = p
        p.save(prefs)
    }

    fun enteredBackground() {
        if (background) return
        background = true
        watch.reset(clock())
        main.removeCallbacks(loop)
        check()
        main.postDelayed(loop, CHECK_MS)
    }

    fun becameActive() {
        background = false
        main.removeCallbacks(loop)
        host.setFrameMs(FRAME_MS)
        if (host.suspended) host.resumeFromBattery()
    }

    /** (the notification's Play) */
    fun play() {
        watch.reset(clock())
        host.resumeFromBattery()
        if (background) { main.removeCallbacks(loop); main.postDelayed(loop, CHECK_MS) }
    }

    /** (the notification's Pause / Stop) */
    fun stop(why: String) {
        lastStop = why
        main.removeCallbacks(loop)
        host.suspendForBattery(why)
    }

    private fun check() {
        // (the screen redrawn every 250 ms in the background unless an FM-1 shows it: its screen is the firmware's)
        host.setFrameMs(if (background && !host.fm1Linked) BACKGROUND_FRAME_MS else FRAME_MS)
        if (host.suspended || !host.coreMode) return
        watch.feed(host.activity, clock())
        policy.stop(host.fm1Linked, watch.idleForMs(clock()) / 1000)?.let {
            Log.i(TAG, "battery: stopping ($it)")
            stop(it)
        }
    }

    companion object {
        const val TAG = "RFMBattery"
        const val CHECK_MS = 15_000L
        const val FRAME_MS = 15
        const val BACKGROUND_FRAME_MS = 250
    }
}
