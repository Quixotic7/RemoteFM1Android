// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The audio-control thread (AudioControl) against a fake stream back end that can hang on open or close, fail to
// start, never advance, or die: the UI's calls never wait, wants coalesce, a stuck close doesn't keep the sound off,
// a busy audio system is reported and retried, a silent USB output falls back to Automatic.
package com.quixotic7.remotefm1

import android.media.AudioDeviceInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AudioControlTest {
    private val speaker = AudioOut(2, "SM-X210", AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    private val buds = AudioOut(40, "Galaxy Buds", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
    private val fm1 = AudioOut(31, "FM-1", AudioDeviceInfo.TYPE_USB_DEVICE)
    private val all = listOf(fm1, speaker, buds)

    /** a stream back end whose calls can be made to hang, fail, play nothing or die */
    class Fake : StreamBackend {
        @Volatile var openDelay = 0L
        @Volatile var closeDelay = 0L
        @Volatile var startError: String? = null
        @Volatile var openError: (AudioOut) -> String? = { null }
        val silent: MutableSet<Int> = Collections.synchronizedSet(HashSet())   // devices whose streams never advance
        val opens = AtomicInteger()
        val starts = AtomicInteger()
        val closesBegun = AtomicInteger()
        val closesDone = AtomicInteger()
        val activations = AtomicInteger()
        val clock = AtomicInteger(RfmEngine.CLOCK_TIMER)
        @Volatile var active = 0L                        // the stream that renders
        @Volatile var twoActive = false                  // a stream made active while another still rendered
        private val next = AtomicLong(0)
        private class S(val out: AudioOut) { @Volatile var startedAt = 0L; @Volatile var dead = false }
        private val streams = Collections.synchronizedMap(HashMap<Long, S>())
        val open: Int get() = streams.size

        override fun open(out: AudioOut, forbidden: IntArray): StreamBackend.Opened {
            opens.incrementAndGet()
            if (openDelay > 0) Thread.sleep(openDelay)
            openError(out)?.let { return StreamBackend.Opened(0, it) }
            val h = next.incrementAndGet()
            streams[h] = S(out)
            return StreamBackend.Opened(h, null)
        }
        override fun start(handle: Long): String? {
            starts.incrementAndGet()
            startError?.let { return it }
            streams[handle]!!.startedAt = System.nanoTime()
            return null
        }
        override fun activate(handle: Long) {
            if (active != 0L) twoActive = true
            active = handle
            activations.incrementAndGet()
        }
        override fun deactivate(handle: Long) {
            if (active == handle) active = 0
        }
        override fun close(handle: Long) {
            closesBegun.incrementAndGet()
            if (active == handle) active = 0                    // (silenced first, as RFMOboe does)
            if (closeDelay > 0) Thread.sleep(closeDelay)
            streams.remove(handle)
            closesDone.incrementAndGet()
        }
        override fun info(handle: Long, refresh: Boolean): StreamBackend.Info {
            val s = streams[handle]!!
            val f = if (s.startedAt == 0L || s.out.id in silent) 0L else (System.nanoTime() - s.startedAt) / 20_833   // 48 kHz
            return StreamBackend.Info(f, s.dead, s.out.id, "fake")
        }
        override fun claimClock(): Boolean = clock.compareAndSet(RfmEngine.CLOCK_TIMER, RfmEngine.CLOCK_AUDIO)
        override fun releaseClock() { clock.compareAndSet(RfmEngine.CLOCK_AUDIO, RfmEngine.CLOCK_TIMER) }

        fun kill(id: Int) { synchronized(streams) { streams.values.filter { it.out.id == id }.forEach { it.dead = true } } }
    }

    private val fast = AudioTimeouts(close = 150, openBusy = 150, openGiveUp = 600, stall = 200, poll = 10, refresh = 50,
                                     backoff = listOf(30, 60, 120), offGrace = 100)
    private val fake = Fake()
    private val notices = Collections.synchronizedList(ArrayList<String>())
    private val states = Collections.synchronizedList(ArrayList<AudioState>())
    private val ctl = AudioControl(fake, fast, onNotice = { w, o -> notices += "$w ${o.id}" }, onState = { states += it })

    @After fun end() {
        ctl.shutdown()
        ctl.join(2000)
    }

    private fun on(vararg outs: AudioOut, retry: Int = 0) = AudioWant(true, outs.toList(), all, retry)
    private val off = AudioWant(false, listOf(speaker), all)

    private fun until(ms: Long, f: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (f()) return true
            Thread.sleep(2)
        }
        return f()
    }

    private fun playingOn(out: AudioOut) = (ctl.state as? AudioState.Playing)?.out?.id == out.id

    // the UI's call: never longer than a few ms, whatever the thread is stuck in
    private fun timedWant(w: AudioWant): Long {
        val t0 = System.nanoTime()
        ctl.want(w)
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test fun playsAndStops() {
        ctl.want(on(speaker))
        assertTrue(until(1000) { playingOn(speaker) })
        assertEquals(RfmEngine.CLOCK_AUDIO, fake.clock.get())
        ctl.want(off)
        assertTrue(until(1000) { ctl.state == AudioState.Off && fake.open == 0 })
        assertEquals(RfmEngine.CLOCK_TIMER, fake.clock.get())
        assertEquals(1, fake.opens.get())
    }

    /** on/off/on while an open is in progress: one open, playing at the end */
    @Test fun wantsCoalesce() {
        fake.openDelay = 100
        ctl.want(on(speaker))
        assertTrue(until(500) { ctl.state is AudioState.Opening })
        var worst = 0L
        repeat(50) { worst = maxOf(worst, timedWant(off), timedWant(on(speaker))) }
        assertTrue(until(1000) { playingOn(speaker) })
        Thread.sleep(100)
        assertEquals("opens", 1, fake.opens.get())
        assertEquals("closes", 0, fake.closesBegun.get())
        assertTrue("want() took $worst ms", worst < 20)
    }

    /** spam while playing: it ends playing with at most a few reopens, never two streams rendering */
    @Test fun spamWhilePlaying() {
        ctl.want(on(speaker))
        assertTrue(until(1000) { playingOn(speaker) })
        repeat(200) { i -> ctl.want(if (i % 2 == 0) off else on(if (i % 4 == 1) buds else speaker)) }
        ctl.want(on(speaker))
        assertTrue(until(2000) { playingOn(speaker) })
        Thread.sleep(100)
        assertTrue(playingOn(speaker))
        assertFalse(fake.twoActive)
        assertEquals(1, fake.open)
    }

    /** a close stuck for 3 s: abandoned after the limit, the next stream opened anyway; want() never waits */
    @Test fun stuckCloseIsAbandoned() {
        ctl.want(on(speaker))
        assertTrue(until(1000) { playingOn(speaker) })
        fake.closeDelay = 3000
        val t0 = System.nanoTime()
        var worst = timedWant(off)
        worst = maxOf(worst, timedWant(on(speaker)))
        worst = maxOf(worst, timedWant(off))
        worst = maxOf(worst, timedWant(on(buds)))                 // (an output switch while the close hangs)
        assertTrue(until(2000) { playingOn(buds) })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("want() took $worst ms", worst < 20)
        assertTrue("sound back after $ms ms", ms < fast.close + 400)
        assertTrue(ctl.abandonedCloses >= 1)
        assertFalse(fake.twoActive)
        assertEquals(RfmEngine.CLOCK_AUDIO, fake.clock.get())
    }

    /** an open that takes longer than openBusy: Busy shown, then playing when it returns */
    @Test fun slowOpenShowsBusy() {
        fake.openDelay = 400
        ctl.want(on(speaker))
        assertTrue(until(1000) { playingOn(speaker) })
        assertTrue(states.any { it is AudioState.Busy })
        assertEquals(0, ctl.abandonedOpens)
    }

    /** an open that never answers: given up, retried with backoff; the late stream closed when it comes back */
    @Test fun hungOpenIsRetried() {
        fake.openDelay = 1500
        ctl.want(on(speaker))
        assertTrue(until(1500) { ctl.abandonedOpens >= 1 })
        assertTrue(until(500) { states.any { it is AudioState.Failed && it.reason.contains("did not answer") } })
        fake.openDelay = 0
        assertTrue(until(3000) { playingOn(speaker) })
        assertTrue("the late stream closed", until(3000) { fake.open == 1 })
        assertFalse(fake.twoActive)
    }

    /** a sound-off while the open is busy: let go, Off at once */
    @Test fun offWhileBusy() {
        fake.openDelay = 2000
        ctl.want(on(speaker))
        assertTrue(until(1000) { ctl.state is AudioState.Busy })
        ctl.want(off)
        assertTrue(until(300) { ctl.state == AudioState.Off })
        assertTrue(until(3000) { fake.open == 0 })
    }

    /** start fails: retried with backoff, then given up until the user asks again */
    @Test fun failingStartBacksOffThenGivesUp() {
        fake.startError = "requestStart: ErrorInternal"
        ctl.want(on(speaker))
        assertTrue(until(2000) { (ctl.state as? AudioState.Failed)?.retryInMs == 0L })
        val tries = fake.starts.get()
        assertEquals(fast.backoff.size + 1, tries)
        Thread.sleep(200)
        assertEquals("no tries after giving up", tries, fake.starts.get())
        assertEquals(0, fake.open)
        fake.startError = null
        ctl.want(on(speaker, retry = 1))                         // (the user turned the sound on again)
        assertTrue(until(1000) { playingOn(speaker) })
    }

    /** the picked output refuses: Automatic's next */
    @Test fun fallsBackToTheNextOutput() {
        fake.openError = { if (it.id == buds.id) "openStream: ErrorInternal" else null }
        ctl.want(on(buds, speaker))
        assertTrue(until(1000) { playingOn(speaker) })
    }

    /** a USB stream that plays nothing: stopped after the stall limit, a notice, the next output */
    @Test fun silentUsbFallsBack() {
        fake.silent += fm1.id
        ctl.want(on(fm1, speaker))
        assertTrue(until(1000) { playingOn(fm1) })
        assertTrue(until(1500) { playingOn(speaker) })
        assertEquals(listOf("${AudioControl.NOTICE_USB_FAILED} ${fm1.id}"), notices.toList())
        assertEquals(1, ctl.stalls)
        Thread.sleep(300)
        assertTrue("not back to USB by itself", playingOn(speaker))
    }

    /** any other stream that plays nothing: retried */
    @Test fun silentSpeakerIsRetried() {
        fake.silent += speaker.id
        ctl.want(on(speaker))
        assertTrue(until(2000) { ctl.stalls >= 1 && fake.opens.get() >= 2 })
        fake.silent.clear()
        assertTrue(until(2000) { playingOn(speaker) && fake.open == 1 })
        Thread.sleep(300)
        val n = ctl.stalls
        Thread.sleep(400)
        assertEquals("still stalling", n, ctl.stalls)
        assertTrue(playingOn(speaker))
    }

    /** a disconnect: a new stream at once */
    @Test fun deadStreamReopens() {
        ctl.want(on(speaker))
        assertTrue(until(1000) { playingOn(speaker) })
        fake.kill(speaker.id)
        assertTrue(until(1000) { ctl.disconnects == 1 && playingOn(speaker) && fake.opens.get() == 2 })
    }

    /** the main thread stopped the clock (Test pattern): a stream opening later doesn't take it */
    @Test fun stoppedClockStaysStopped() {
        fake.clock.set(RfmEngine.CLOCK_NONE)
        ctl.want(on(speaker))
        assertTrue(until(1000) { playingOn(speaker) })
        assertEquals(RfmEngine.CLOCK_NONE, fake.clock.get())
        ctl.want(off)
        assertTrue(until(1000) { ctl.state == AudioState.Off })
        assertEquals(RfmEngine.CLOCK_NONE, fake.clock.get())
    }

    @Test fun noOutput() {
        ctl.want(AudioWant(true, emptyList(), listOf(fm1)))
        assertTrue(until(500) { ctl.state is AudioState.Failed })
        assertEquals(0, fake.opens.get())
    }

    /** off/on taps faster than the grace: the same stream, silent while off */
    @Test fun offOnSpamReusesTheStream() {
        ctl.want(on(speaker))
        assertTrue(until(1000) { playingOn(speaker) })
        repeat(20) {
            ctl.want(off)
            Thread.sleep(15)
            ctl.want(on(speaker, retry = it + 1))
            Thread.sleep(15)
        }
        assertTrue(until(500) { playingOn(speaker) })
        Thread.sleep(fast.offGrace + 100)
        assertEquals("opens", 1, fake.opens.get())
        assertEquals(RfmEngine.CLOCK_AUDIO, fake.clock.get())
        assertTrue(fake.active != 0L)
        ctl.want(off)
        assertTrue(until(100) { fake.active == 0L && ctl.state == AudioState.Off })      // (silent at once)
        assertTrue(until(1000) { fake.open == 0 })
    }

    @Test fun shutdownCloses() {
        ctl.want(on(speaker))
        assertTrue(until(1000) { playingOn(speaker) })
        ctl.shutdown()
        ctl.join(1000)
        assertEquals(0, fake.open)
    }
}
