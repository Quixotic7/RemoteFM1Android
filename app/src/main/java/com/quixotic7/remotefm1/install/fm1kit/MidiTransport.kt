// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Transport/MIDITransport.swift.
// Copyright (c) 2026 Drew Robinson, MIT licence (LICENSES/FM1Kit-MIT.txt). Kotlin port for RemoteFM1 Android:
// suspend functions instead of async; SysexInbox over a coroutine Channel; changes() left out (nothing here uses it);
// sendFailed carries Android's words instead of an OSStatus.
package com.quixotic7.remotefm1.install.fm1kit

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** A MIDI input/output pair that might be an FM-1. */
data class MIDIPortDescriptor(
    /** stable while it stays connected */
    val id: String,
    /** as the system reports it, matched against FM1Updater.probeablePortNameFragments */
    val name: String,
    /** the USB serial number, when the firmware reports one (Felucca reports none) */
    val serialNumber: String? = null,
)

/** Something that lists MIDI port pairs and opens them for SysEx (Android MIDI, or SimulatedFM1). */
interface MIDITransport {
    suspend fun ports(): List<MIDIPortDescriptor>

    /** @throws MIDITransportError when the pair has gone or can't be opened */
    suspend fun open(port: MIDIPortDescriptor): MIDIConnection
}

/** An open port pair. */
interface MIDIConnection {
    val port: MIDIPortDescriptor

    /** the device behind it has gone (unplugged, or restarted and re-enumerated) */
    val isLost: Boolean

    /** one complete F0 .. F7 message; @throws MIDITransportError */
    fun send(sysex: ByteArray)

    /** the next complete SysEx message; null on time-out or once closed */
    suspend fun receive(timeout: Duration): ByteArray?

    fun drain()

    /** again: nothing */
    fun close()
}

/** Why a MIDI connection couldn't be made or used. */
sealed class MIDITransportError : Exception() {
    data class Unavailable(val reason: String) : MIDITransportError()
    data object PortGone : MIDITransportError()
    data class SendFailed(val why: String) : MIDITransportError()

    val description: String
        get() = when (this) {
            is Unavailable -> "MIDI isn't available: $reason."
            PortGone -> "The MIDI port has gone."
            is SendFailed -> "Sending MIDI failed ($why)."
        }

    override val message: String get() = description
}

/** A single-reader queue of received SysEx with a timed wait; the oldest is dropped when 256 are unread. */
class SysexInbox {
    private val ch = Channel<ByteArray>(CAPACITY, BufferOverflow.DROP_OLDEST)

    /** (any thread) ignored after close() */
    fun deliver(sysex: ByteArray) {
        ch.trySend(sysex)
    }

    suspend fun next(timeout: Duration): ByteArray? {
        ch.tryReceive().getOrNull()?.let { return it }
        if (!timeout.isPositive()) return null
        return withTimeoutOrNull(timeout) { ch.receiveCatching().getOrNull() }
    }

    fun drain() {
        while (ch.tryReceive().isSuccess) {
        }
    }

    fun close() {
        ch.close()
        drain()
    }

    companion object {
        const val CAPACITY = 256
    }
}
