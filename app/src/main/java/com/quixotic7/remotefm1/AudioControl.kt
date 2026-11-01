// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The audio-control thread (after the second FM-1 test: a stream's close blocked the main thread until Android killed
// the app, and after the relaunch an open took 8 s while the audio server was still busy). Pure Kotlin (tested on the
// JVM with a fake back end: AudioControlTest).
//
// - The UI never waits: it states what it wants (want(): sound on or off, the outputs to try in order) and returns.
//   Only the latest want matters (sound on/off/on before the thread looks = one open, or none if it still plays).
//   Sound off silences the stream at once and closes it only after 300 ms, so off/on spam reuses the stream.
// - One thread ("RFMAudioCtl") does all the stream work. Each call that may block in Android's audio server (open +
//   start, close) runs on a worker thread of its own while the control thread waits for it with a time limit:
//   a close that hasn't returned in 2 s is abandoned (its worker finishes it whenever Android lets it; the stream is
//   silenced first and never rendered again, RFMOboe.h) and the next stream is opened anyway; an open that hasn't
//   returned in 5 s shows "busy", is waited for up to 20 s (or until the sound isn't wanted on that output), then
//   abandoned (its worker closes what it opens late); at most 2 such opens pending before a new one is tried.
// - A stream that plays no frames for 2 s is stopped: a USB output's then counts as "didn't start" (onNotice
//   NOTICE_USB_FAILED: the app picks Automatic) and the next output is tried; any other's is retried.
// - Failures retry with backoff (0.5, 1, 2, 4, 8 s), then the state stays Failed until the want changes (or its
//   retry number: the user turned the sound on again).
// - The state goes to [onState] (on the control thread; the app posts it to the main thread).
package com.quixotic7.remotefm1

/** The stream calls (RfmEngine's native streams; a fake in the JVM tests). Any of them may block. */
interface StreamBackend {
    class Opened(val handle: Long, val error: String?)
    class Info(val frames: Long, val dead: Boolean, val deviceId: Int, val text: String)

    /** a stream on [out] (by its id), never landing on [forbidden]; not started */
    fun open(out: AudioOut, forbidden: IntArray): Opened
    /** started (it plays silence until [activate]); null or the error */
    fun start(handle: Long): String?
    /** the stream that renders the engine from now on */
    fun activate(handle: Long)
    /** the stream silent (if it is the active one), kept open */
    fun deactivate(handle: Long)
    /** silenced at once, stopped, closed, freed: the handle is gone after (may block for long) */
    fun close(handle: Long)
    /** frames played, dead (disconnected), the device; [refresh]: the stream's getters asked again */
    fun info(handle: Long, refresh: Boolean): Info
    /** the engine's clock to the audio, if it is the timer's (a clock the main thread stopped stays stopped) */
    fun claimClock(): Boolean
    /** the engine's clock back to the timer, if it is the audio's */
    fun releaseClock()
}

/** What the app wants: the sound [on], on the first of [outs] that plays (picked, then Automatic's), out of [all]. */
data class AudioWant(
    val on: Boolean = false,
    val outs: List<AudioOut> = emptyList(),
    val all: List<AudioOut> = emptyList(),
    val retry: Int = 0,                                  // bumped when the user turns the sound on: failures forgotten
)

sealed class AudioState {
    object Off : AudioState() { override fun toString() = "Off" }
    data class Opening(val out: AudioOut, val attempt: Int) : AudioState()
    /** an open hasn't returned for 5 s: the tablet's audio system is busy */
    data class Busy(val out: AudioOut?) : AudioState()
    data class Playing(val out: AudioOut, val deviceId: Int, val text: String) : AudioState()
    /** nothing plays; [retryInMs] > 0: trying again then, 0: given up until the want changes */
    data class Failed(val reason: String, val retryInMs: Long) : AudioState()

    /** what the main page says under the picker */
    val line: String get() = when (this) {
        Off -> ""
        is Opening -> "Opening ${out.title}…"
        is Busy -> "The tablet's audio system is busy" + (out?.let { " (opening ${it.title})" } ?: "") + "; waiting…"
        is Playing -> "Playing on ${out.title}"
        is Failed -> "Sound failed: $reason" + if (retryInMs > 0) "; trying again in %.1f s".format(retryInMs / 1000.0) else ""
    }
}

data class AudioTimeouts(
    val close: Long = 2000,             // a close not back by then is abandoned
    val openBusy: Long = 5000,          // an open not back by then: Busy
    val openGiveUp: Long = 20000,       // ... and abandoned by then
    val stall: Long = 2000,             // a stream whose frames don't move for this long is stopped
    val poll: Long = 100,               // the control thread looks at its stream this often
    val refresh: Long = 500,            // the stream's getters refreshed this often
    val offGrace: Long = 300,           // sound off: silent at once, closed only after this (off/on spam: no reopen)
    val backoff: List<Long> = listOf(500, 1000, 2000, 4000, 8000),
    val maxOpensPending: Int = 2,       // abandoned opens still in Android: no new open while this many are
)

class AudioControl(
    private val backend: StreamBackend,
    private val t: AudioTimeouts = AudioTimeouts(),
    private val log: (String) -> Unit = {},
    private val onNotice: (String, AudioOut) -> Unit = { _, _ -> },
    private val onState: (AudioState) -> Unit = {},
) {
    private val lock = Object()
    private var want = AudioWant()
    private var wantSeq = 0L
    private var quit = false
    private var pendingOpens = 0                         // open workers not back yet (abandoned ones included)
    private val thread = Thread({ run() }, "RFMAudioCtl").apply { isDaemon = true; start() }

    // counters (any thread reads them)
    @Volatile var opens = 0; private set
    @Volatile var closes = 0; private set
    @Volatile var abandonedCloses = 0; private set
    @Volatile var abandonedOpens = 0; private set
    @Volatile var stalls = 0; private set
    @Volatile var disconnects = 0; private set
    @Volatile var state: AudioState = AudioState.Off; private set
    /** the longest a close took or was waited for (ms) */
    @Volatile var longestClose = 0L; private set

    /** (any thread, never blocks) what the app wants now; only the latest counts */
    fun want(w: AudioWant) {
        synchronized(lock) {
            if (w == want) return
            want = w
            wantSeq++
            lock.notifyAll()
        }
    }

    /** (any thread, never blocks) the thread closes its stream (waiting at most the close limit) and ends */
    fun shutdown() {
        synchronized(lock) {
            quit = true
            lock.notifyAll()
        }
    }

    /** tests: waits up to [ms] for the thread to end */
    fun join(ms: Long) = thread.join(ms)

    private class Live(val h: Long, val out: AudioOut, var frames: Long, var advancedAt: Long, var refreshedAt: Long,
                       var played: Boolean = false, var silencedAt: Long = 0)
    private class Job { var done = false; var abandoned = false; var h = 0L; var err: String? = null }

    private fun now() = System.nanoTime() / 1_000_000

    private fun report(s: AudioState) {
        if (s == state) return
        state = s
        log("audio: $s")
        onState(s)
    }

    private fun run() {
        var cur: Live? = null
        var seenSeq = -1L
        var round = 0                                    // failed rounds in a row for this want
        var nextTry = 0L
        var givenUp = false
        var lastRetry = -1
        val banned = HashSet<Int>()                      // outputs that didn't start (USB) until the want changes

        fun failed(reason: String) {
            if (round >= t.backoff.size) {
                givenUp = true
                report(AudioState.Failed(reason, 0))
                return
            }
            val d = t.backoff[round++]
            nextTry = now() + d
            report(AudioState.Failed(reason, d))
        }

        while (true) {
            val w: AudioWant
            val seq: Long
            synchronized(lock) {
                if (quit) {
                    cur?.let { close(it, "shutdown") }
                    report(AudioState.Off)
                    return
                }
                w = want
                seq = wantSeq
            }
            if (seq != seenSeq) {
                seenSeq = seq
                banned.clear()
                if (w.retry != lastRetry || !w.on || givenUp) {   // (asked again, or new outputs: failures forgotten)
                    lastRetry = w.retry
                    round = 0
                    nextTry = 0
                    givenUp = false
                }
            }
            val outs = w.outs.filter { it.id !in banned }
            val target = if (w.on) outs.firstOrNull() else null

            // the stream playing: still wanted, still alive, still moving?
            cur?.let { c ->
                val n = now()
                val refresh = n - c.refreshedAt >= t.refresh
                if (refresh) c.refreshedAt = n
                val i = backend.info(c.h, refresh)
                if (i.frames != c.frames) {
                    c.frames = i.frames
                    c.advancedAt = n
                    if (!c.played) {
                        c.played = true
                        round = 0                        // (it plays: the failures before it are forgotten)
                    }
                }
                val stalled = n - c.advancedAt > t.stall
                if (!w.on && c.silencedAt == 0L) {      // (silent now; closed after the grace unless wanted again)
                    backend.releaseClock()
                    backend.deactivate(c.h)
                    c.silencedAt = n
                    report(AudioState.Off)
                }
                val why = when {
                    !w.on -> if (n - c.silencedAt >= t.offGrace) "sound off" else null
                    target == null -> "no output to play on"
                    c.out.id != target.id -> "${target.title} wanted"
                    i.dead -> "disconnected"
                    stalled -> "no frames for ${n - c.advancedAt} ms"
                    else -> null
                }
                if (why != null) {
                    cur = null
                    close(c, why)
                    if (i.dead) disconnects++
                    if (stalled && !i.dead && c.out.id == target?.id) {
                        stalls++
                        if (c.out.usb) {
                            banned += c.out.id
                            log("audio: ${c.out.title} played nothing: not tried again")
                            onNotice(NOTICE_USB_FAILED, c.out)
                        } else {
                            failed("${c.out.title} played nothing")
                        }
                    }
                } else if (w.on) {
                    if (c.silencedAt != 0L) {             // (wanted again within the grace: the same stream)
                        c.silencedAt = 0
                        c.advancedAt = n
                        backend.activate(c.h)
                        backend.claimClock()
                        log("audio: the stream on ${c.out.title} again (no reopen)")
                    }
                    if ((state as? AudioState.Playing)?.deviceId != i.deviceId)
                        report(AudioState.Playing(c.out, i.deviceId, i.text))
                }
            }

            if (w.on && cur == null) {
                when {
                    outs.isEmpty() -> report(AudioState.Failed("no output to play on", 0))
                    givenUp || now() < nextTry -> {}
                    pendingOpens >= t.maxOpensPending -> report(AudioState.Busy(outs.first()))
                    else -> {
                        when (val r = openFirst(w, outs, round + 1)) {
                            is Live -> cur = r
                            is String -> failed(r)
                            else -> {}                   // (the want changed while it opened)
                        }
                        continue
                    }
                }
            } else if (!w.on && cur == null) {
                report(AudioState.Off)
            }

            synchronized(lock) {
                if (!quit && wantSeq == seenSeq) {
                    var wait = t.poll
                    if (w.on && cur == null && !givenUp && nextTry > 0) wait = minOf(wait, maxOf(1, nextTry - now()))
                    lock.wait(wait)
                }
            }
        }
    }

    /** a started, active stream on the first of [outs] that opens; a String: why none did; null: the want changed */
    private fun openFirst(w: AudioWant, outs: List<AudioOut>, attempt: Int): Any? {
        var why = "no output"
        for (out in outs) {
            when (val r = openOn(w, out, attempt)) {
                is Live -> return r
                is String -> { why = "${out.title}: $r"; log("audio: $why") }
                else -> return null
            }
        }
        return why
    }

    private fun openOn(w: AudioWant, out: AudioOut, attempt: Int): Any? {
        report(AudioState.Opening(out, attempt))
        val forbidden = AudioRoute.forbidden(w.all, out)
        val job = Job()
        synchronized(lock) { pendingOpens++ }
        opens++
        val t0 = now()
        Thread({
            var h = 0L
            var err: String?
            try {
                val o = backend.open(out, forbidden)
                err = o.error
                if (err == null) {
                    err = backend.start(o.handle)
                    if (err != null) backend.close(o.handle) else h = o.handle
                }
            } catch (e: Throwable) {
                err = e.toString()
            }
            var late = false
            synchronized(lock) {
                pendingOpens--
                if (job.abandoned) late = h != 0L else { job.h = h; job.err = err; job.done = true }
                lock.notifyAll()
            }
            if (late) {
                log("audio: an abandoned open of ${out.title} came back after ${now() - t0} ms: closed")
                backend.close(h)
            }
        }, "RFMAudioOpen").apply { isDaemon = true; start() }
        var busy = false
        synchronized(lock) {
            while (!job.done) {
                val el = now() - t0
                // (an open that is merely slow is waited for, whatever the want does meanwhile: the loop then closes it
                // if it isn't wanted. One that is busy is let go when the sound is no longer wanted on it.)
                if (quit || el >= t.openGiveUp || (busy && !stillWants(out))) {
                    job.abandoned = true
                    abandonedOpens++
                    log("audio: open of ${out.title} abandoned after $el ms" + if (el >= t.openGiveUp) " (no answer)" else " (not wanted now)")
                    return if (el >= t.openGiveUp) "the tablet's audio system did not answer in ${el / 1000} s" else null
                }
                if (el >= t.openBusy && !busy) {
                    busy = true
                    report(AudioState.Busy(out))
                }
                lock.wait(maxOf(1, minOf(t.poll, (if (busy) t.openGiveUp else t.openBusy) - el)))
            }
        }
        val el = now() - t0
        if (job.err != null) return job.err
        backend.activate(job.h)
        backend.claimClock()
        val n = now()
        val i = backend.info(job.h, true)
        log("audio: playing on ${out.title} (device ${i.deviceId}, ${i.text}) after $el ms")
        report(AudioState.Playing(out, i.deviceId, i.text))
        return Live(job.h, out, i.frames, n, n)
    }

    // (under the lock) the latest want still has the sound on, with [out] among its outputs
    private fun stillWants(out: AudioOut): Boolean = want.on && want.outs.any { it.id == out.id }

    private fun close(c: Live, why: String) {
        log("audio: closing the stream on ${c.out.title} ($why)")
        backend.releaseClock()
        closes++
        val job = Job()
        val t0 = now()
        Thread({
            try { backend.close(c.h) } catch (e: Throwable) { log("audio: close: $e") }
            val el = now() - t0
            synchronized(lock) {
                job.done = true
                if (job.abandoned) log("audio: the abandoned close on ${c.out.title} came back after $el ms")
                lock.notifyAll()
            }
        }, "RFMAudioClose").apply { isDaemon = true; start() }
        synchronized(lock) {
            while (!job.done) {
                val el = now() - t0
                if (el >= t.close) {
                    job.abandoned = true
                    abandonedCloses++
                    longestClose = maxOf(longestClose, el)
                    log("audio: the close on ${c.out.title} is stuck after $el ms: abandoned, going on")
                    return
                }
                lock.wait(maxOf(1, t.close - el))
            }
        }
        longestClose = maxOf(longestClose, now() - t0)
    }

    companion object {
        const val NOTICE_USB_FAILED = "usb_failed"
    }
}
