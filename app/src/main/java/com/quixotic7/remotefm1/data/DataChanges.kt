// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/StateLibrary.swift (postChange / didChange) and
// Faceplate/SafetyCopies.swift (the date shown).
//
// Every change of the states or the safety copies tells the listeners (iOS: a Darwin notification, forwarded as
// StateLibrary.didChange). Android has one process, so a plain list: listeners are called on the thread that made
// the change, and hop to the main thread themselves. Also the dates as the pages show them.
package com.quixotic7.remotefm1.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object DataChanges {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var generation = 0L
        private set

    fun add(l: () -> Unit) { listeners.add(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }

    /** a change made: every listener told */
    fun post() {
        generation++
        for (l in listeners) runCatching { l() }
    }
}

object DataDates {
    /** seconds since 1970 now */
    fun now(): Double = System.currentTimeMillis() / 1000.0

    /** "Oct 26, 2026, 12:33 AM" (iOS: date.formatted(date: .abbreviated, time: .shortened)), in the device's locale */
    fun short(t: Double, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
            .format(Instant.ofEpochMilli((t * 1000).toLong()))

    /** "10/26/26, 12:33 AM" (iOS: date.formatted()) */
    fun numeric(t: Double, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT).withLocale(locale).withZone(zone)
            .format(Instant.ofEpochMilli((t * 1000).toLong()))
}
