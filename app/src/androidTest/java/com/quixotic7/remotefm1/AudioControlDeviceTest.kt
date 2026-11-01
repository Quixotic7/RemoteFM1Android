// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The audio-control thread on a real Oboe (after the second FM-1 test's ANRs): sound on/off/on spam and output
// switches while playing, from the main thread as the app does them; a close stuck for 5 s in the stream (RFMOboe's
// test hook) and an open stuck for 7 s keep the main thread free (a probe on its looper measures the longest gap) and
// the sound comes back on the speaker. Only the speaker (or other non-USB outputs): never a USB sound card. Numbers
// go into files/coretest.json (scripts/emu_coretest.sh prints them).
package com.quixotic7.remotefm1

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class AudioControlDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = Handler(Looper.getMainLooper())
    private val r = JSONObject()
    private var name = ""
    private lateinit var e: RfmEngine
    private lateinit var ctl: AudioControl
    private lateinit var all: List<AudioOut>
    private lateinit var speaker: AudioOut
    @Volatile private var state: AudioState = AudioState.Off
    private val states = ArrayList<String>()

    // the main-thread probe: a runnable every 5 ms (and the app's 15 Hz status read); the longest gap between runs
    @Volatile private var probing = false
    private val lastRun = AtomicLong(0)
    private val worst = AtomicLong(0)
    private var lastStatus = 0L
    private val probe = object : Runnable {
        override fun run() {
            val n = SystemClock.uptimeMillis()
            val prev = lastRun.getAndSet(n)
            if (prev > 0) worst.set(maxOf(worst.get(), n - prev))
            if (n - lastStatus >= 66) {                          // (AppModel.refresh: the engine's status at 15 Hz)
                lastStatus = n
                e.status()
            }
            if (probing) main.postDelayed(this, 5)
        }
    }

    @Before fun setUp() {
        assertEquals(6, RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir))
        e = RfmEngine()
        assertNull(e.load("choralroot"))
        assertTrue(e.boot())
        e.setClock(RfmEngine.CLOCK_TIMER)
        val am = ctx.getSystemService(AudioManager::class.java)
        all = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in AudioOut.MUSIC_TYPES }
            .map { AudioOut(it.id, it.productName?.toString() ?: "out ${it.id}", it.type) }.distinctBy { it.id }
        speaker = all.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: throw AssertionError("no speaker in $all")
        r.put("outputs", all.joinToString { "${it.id} ${it.title}" })
        ctl = AudioControl(e.streams, log = { Log.i(TAG, it) }, onState = { st ->
            main.post { state = st; synchronized(states) { states += "${SystemClock.uptimeMillis()} $st" } }
        })
        SystemClock.sleep(1500)                                  // (past ChoralRoot's start)
    }

    @After fun tearDown() {
        stopProbe()
        e.testAudioHang(0, 0)
        ctl.shutdown()
        ctl.join(5000)
        // (an abandoned close may still be in Android: the engine goes only after it is back; it would be safe anyway)
        until(8000) { e.status().getJSONObject("audio").optInt("streams") == 0 }
        e.close()
        r.put("states", synchronized(states) { states.takeLast(12).joinToString(" | ") })
        CoresTest.save(name, r)
    }

    private fun startProbe() {
        worst.set(0)
        lastRun.set(0)
        probing = true
        main.post(probe)
    }

    private fun stopProbe() {
        probing = false
        SystemClock.sleep(20)
    }

    private fun onMain(f: () -> Unit) = main.post(f)
    private fun want(on: Boolean, vararg outs: AudioOut, retry: Int = 0) = onMain { ctl.want(AudioWant(on, outs.toList(), all, retry)) }

    private fun until(ms: Long, f: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (f()) return true
            SystemClock.sleep(5)
        }
        return f()
    }

    private val audio: JSONObject get() = e.status().getJSONObject("audio")

    /** playing on [out]: the controller says so, the active stream's frames move and the key held is heard */
    private fun sounding(out: AudioOut): Boolean {
        val s = state
        if (s !is AudioState.Playing || s.out.id != out.id) return false
        val a = audio
        return a.optInt("running") == 1 && a.optDouble("rms") > 1e-4
    }

    private fun startOnSpeaker() {
        e.key(KEY_D4, true, 100)
        want(true, speaker)
        assertTrue("not playing on the speaker: $state ${audio}", until(8000) { sounding(speaker) })
    }

    @Test fun soundOnOffSpam() {
        name = "audio_spam"
        startProbe()
        startOnSpeaker()
        val opens0 = ctl.opens
        // 40 taps, 10 ms apart, from the main thread (as the Sound chip does), ending on
        for (i in 0 until 40) {
            val on = i % 2 == 1
            want(on, speaker, retry = i)
            SystemClock.sleep(10)
        }
        val t0 = SystemClock.uptimeMillis()
        assertTrue("not back: $state ${audio}", until(8000) { sounding(speaker) && ctl.state is AudioState.Playing })
        SystemClock.sleep(1000)
        assertTrue("stopped again: $state", sounding(speaker))
        val a = audio
        r.put("opens_for_40_taps", ctl.opens - opens0).put("closes", ctl.closes).put("back_after_ms", SystemClock.uptimeMillis() - t0)
            .put("main_worst_ms", worst.get()).put("streams", a.optInt("streams")).put("stream", a)
        assertTrue("main thread blocked ${worst.get()} ms", worst.get() < 200)
        assertEquals(1, a.optInt("streams"))
        assertTrue("${ctl.opens - opens0} opens for 40 taps", ctl.opens - opens0 <= 2)
        r.put("pass", true)
    }

    @Test fun outputSwitchesWhilePlaying() {
        name = "audio_switch"
        startProbe()
        startOnSpeaker()
        val others = all.filter { !it.usb && it.id != speaker.id && it.type in SWITCHABLE }
        r.put("other_outputs", others.joinToString { it.title })
        // between the speaker and each other non-USB output Android lists (and Automatic's list, the same speaker)
        val seq = if (others.isEmpty()) List(20) { if (it % 2 == 0) listOf(speaker) else listOf(speaker, speaker) }
        else List(20) { if (it % 2 == 0) listOf(others[it / 2 % others.size], speaker) else listOf(speaker) }
        for (outs in seq) {
            onMain { ctl.want(AudioWant(true, outs, all, 0)) }
            SystemClock.sleep(150)
        }
        onMain { ctl.want(AudioWant(true, listOf(speaker), all, 0)) }
        assertTrue("not on the speaker: $state", until(8000) { sounding(speaker) })
        val a = audio
        r.put("opens", ctl.opens).put("main_worst_ms", worst.get()).put("streams", a.optInt("streams"))
        assertTrue("main thread blocked ${worst.get()} ms", worst.get() < 200)
        if (others.isEmpty()) assertEquals("the same output: no reopen", 1, ctl.opens)
        r.put("pass", true)
    }

    /** the second FM-1 test's ANR: a close that doesn't return (5 s here). The main thread stays free, the sound is
     *  back on the speaker after the close limit (2 s) plus an open */
    @Test fun stuckCloseKeepsTheAppResponsive() {
        name = "audio_stuck_close"
        startOnSpeaker()
        startProbe()
        e.testAudioHang(5000, 0)
        val tOff = SystemClock.uptimeMillis()
        want(false, speaker)
        SystemClock.sleep(500)                                   // (past the 300 ms grace: the close has begun, stuck)
        val t0 = SystemClock.uptimeMillis()
        want(true, speaker, retry = 1)                           // (the owner turned the sound on again)
        assertTrue("not back: $state ${audio}", until(10000) { sounding(speaker) })
        val back = SystemClock.uptimeMillis() - t0
        val two = audio.optInt("streams")                        // (the stuck one still closing)
        // and a disconnect whose close hangs: a new stream again
        val t1 = SystemClock.uptimeMillis()
        val opens1 = ctl.opens
        e.testDisconnect()
        assertTrue("not reopened: $state ${audio}", until(10000) { ctl.opens > opens1 && sounding(speaker) })
        val back2 = SystemClock.uptimeMillis() - t1
        val worstMs = worst.get()
        stopProbe()
        r.put("sound_back_after_stuck_close_ms", back).put("from_the_sound_off_ms", t0 - tOff + back).put("sound_back_after_disconnect_with_stuck_close_ms", back2)
            .put("main_worst_ms", worstMs).put("streams_while_stuck", two).put("abandoned_closes", ctl.abandonedCloses)
            .put("stream", audio)
        assertTrue("main thread blocked $worstMs ms", worstMs < 200)
        assertTrue("abandoned ${ctl.abandonedCloses}", ctl.abandonedCloses >= 2)
        assertTrue("sound back after $back ms", back < 5000)
        e.testAudioHang(0, 0)
        assertTrue("the stuck closes came back", until(8000) { audio.optInt("streams") == 1 })
        r.put("pass", true)
    }

    /** the relaunch in the second FM-1 test: an open that took 8 s. Busy shown after 5 s, playing when it returns,
     *  the main thread free throughout */
    @Test fun slowOpenShowsBusy() {
        name = "audio_slow_open"
        startProbe()
        e.testAudioHang(0, 7000)
        e.key(KEY_D4, true, 100)
        val t0 = SystemClock.uptimeMillis()
        want(true, speaker)
        assertTrue("busy not shown: $state", until(7000) { state is AudioState.Busy })
        val busyAt = SystemClock.uptimeMillis() - t0
        // the user toggles meanwhile (as the owner did)
        want(false, speaker)
        SystemClock.sleep(50)
        want(true, speaker, retry = 1)
        e.testAudioHang(0, 0)
        assertTrue("not playing: $state ${audio}", until(25000) { sounding(speaker) })
        val back = SystemClock.uptimeMillis() - t0
        r.put("busy_after_ms", busyAt).put("playing_after_ms", back).put("main_worst_ms", worst.get())
            .put("abandoned_opens", ctl.abandonedOpens).put("opens", ctl.opens)
        assertTrue("main thread blocked ${worst.get()} ms", worst.get() < 200)
        r.put("pass", true)
    }

    private fun assertNull(v: Any?) = org.junit.Assert.assertNull("$v", v)

    companion object {
        const val TAG = "RFMCoreTest"
        const val KEY_D4 = CoresTest.KEY_D4
        val SWITCHABLE = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                               AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
                               AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }
}
