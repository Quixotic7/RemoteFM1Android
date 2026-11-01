// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Updater/UpdateEvent.swift, WriteProgress.swift and
// InstallProgress.swift. Copyright (c) 2026 Drew Robinson, MIT licence (LICENSES/FM1Kit-MIT.txt). Kotlin port for
// RemoteFM1 Android: IndexSet is a BitSet over image offsets; InstallProgress is immutable (record returns the next).
package com.quixotic7.remotefm1.install.fm1kit

import java.util.BitSet

/** Progress reported during an update, in the order the steps happen. */
sealed class UpdateEvent {
    data class Connected(val identity: FM1Identity) : UpdateEvent()
    /** the device checks the package before restarting into its loader; [requests] reads so far */
    data class Staging(val requests: Int) : UpdateEvent()
    data object WaitingForLoader : UpdateEvent()
    data class LoaderFound(val identity: FM1Identity) : UpdateEvent()
    /** [fraction] (0..1) of the application area read */
    data class Writing(val fraction: Double, val requests: Int) : UpdateEvent()
    /** the loader reads the image again (not every loader does) */
    data class Checking(val fraction: Double, val requests: Int) : UpdateEvent()
    data object Rebooting : UpdateEvent()
    data class Finished(val identity: FM1Identity) : UpdateEvent()
}

/**
 * How far the loader has got, whatever order it reads in (V15's reads backwards, Felucca's forwards then again): the
 * share of the application area read; a run of full-size re-reads after half of it starts a checking pass.
 */
internal class WriteProgress(private val region: IntRange) {
    private val written = BitSet()
    private val checked = BitSet()
    private var rereads = mutableListOf<IntRange>()
    private var requests = 0
    private var isChecking = false
    private var lastEvent: UpdateEvent? = null
    private val regionCount = if (region.isEmpty()) 0 else region.last - region.first + 1

    private fun clamp(r: IntRange): IntRange {
        val a = maxOf(r.first, region.first)
        val b = minOf(r.last, region.last)
        return a..b
    }

    private fun BitSet.has(r: IntRange) = nextClearBit(r.first) > r.last

    fun record(request: FM1ReadRequest): UpdateEvent {
        val start = request.address.toInt()
        val range = clamp(start until start + request.length)
        requests++
        if (range.isEmpty() || region.isEmpty()) return repeatedEvent()
        val isNew = !written.has(range)
        if (isChecking && isNew) {             // new ground after all: still writing
            isChecking = false
            checked.clear()
            requests = 1
        }
        if (isChecking) {
            checked.set(range.first, range.last + 1)
            return report(checkingEvent())
        }
        val mightBeChecking = !isNew && request.length == FM1Message.maximumReadLength && writtenFraction() >= CHECKING_THRESHOLD
        if (mightBeChecking) rereads.add(range) else rereads = mutableListOf()
        if (rereads.size >= REREADS_BEFORE_CHECKING) {
            isChecking = true
            requests = rereads.size
            for (r in rereads) checked.set(r.first, r.last + 1)
            rereads = mutableListOf()
            return report(checkingEvent())
        }
        written.set(range.first, range.last + 1)
        return report(UpdateEvent.Writing(writtenFraction(), requests))
    }

    private fun writtenFraction(): Double =
        if (regionCount == 0) 1.0 else minOf(written.cardinality().toDouble() / regionCount, 1.0)

    private fun checkingEvent() =
        UpdateEvent.Checking(minOf(checked.cardinality().toDouble() / written.cardinality(), 1.0), requests)

    private fun repeatedEvent(): UpdateEvent = when (val e = lastEvent) {
        is UpdateEvent.Checking -> UpdateEvent.Checking(e.fraction, requests)
        is UpdateEvent.Writing -> UpdateEvent.Writing(e.fraction, requests)
        else -> UpdateEvent.Writing(0.0, requests)
    }

    private fun report(e: UpdateEvent): UpdateEvent {
        lastEvent = e
        return e
    }

    companion object {
        const val CHECKING_THRESHOLD = 0.5
        const val REREADS_BEFORE_CHECKING = 3
    }
}

/** An install's progress as one bar that never goes backwards, and a friendly stage. */
data class InstallProgress(
    val stage: Stage = Stage.GettingReady,
    /** 0..1, never down */
    val fraction: Double = 0.0,
    /** the FM-1 runs the new firmware */
    val isFinished: Boolean = false,
) {
    enum class Stage(val title: String) {
        GettingReady("Getting ready"), Installing("Installing"), Checking("Checking"), Restarting("Restarting")
    }

    /** the bar moved on for [event] */
    fun record(event: UpdateEvent): InstallProgress = when (event) {
        is UpdateEvent.Connected -> advance(Stage.GettingReady, 0.0)
        is UpdateEvent.Staging -> advance(Stage.GettingReady,
                                          STAGING_SHARE * (1 - 1 / (1 + event.requests / STAGING_SCALE)))
        UpdateEvent.WaitingForLoader -> advance(Stage.Restarting, STAGING_SHARE)
        is UpdateEvent.LoaderFound -> advance(Stage.Installing, LOADER_FOUND_AT)
        is UpdateEvent.Writing -> advance(Stage.Installing, LOADER_FOUND_AT + (WRITING_ENDS_AT - LOADER_FOUND_AT) * event.fraction)
        is UpdateEvent.Checking -> advance(Stage.Checking, WRITING_ENDS_AT + (CHECKING_ENDS_AT - WRITING_ENDS_AT) * event.fraction)
        UpdateEvent.Rebooting -> advance(Stage.Restarting, CHECKING_ENDS_AT)
        is UpdateEvent.Finished -> advance(Stage.Restarting, 1.0).copy(isFinished = true)
    }

    private fun advance(s: Stage, v: Double) = copy(stage = s, fraction = maxOf(fraction, minOf(v, 1.0)))

    companion object {
        const val STAGING_SHARE = 0.15
        const val LOADER_FOUND_AT = 0.22
        const val WRITING_ENDS_AT = 0.8
        const val CHECKING_ENDS_AT = 0.92
        const val STAGING_SCALE = 50.0
    }
}
