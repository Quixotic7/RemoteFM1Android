// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMKit/Models.swift (and RemoteController.swift's state(of:));
// changes: Kotlin; the state rule takes plain numbers (JVM-testable); Backend has the virtual loopback (tests).
// What the link publishes: its state, the unit's HELLO_R, the session's numbers, the panel's events.
package com.quixotic7.remotefm1

/** Which unit a link talks to. */
sealed class Backend {
    /** Android MIDI: HELLO only to devices that can be an FM-1 (Fm1Candidate). */
    object Device : Backend()

    /** libremotefm1's fake unit, in this process. */
    data class Fake(val bluetooth: Boolean = false) : Backend()

    val isFake: Boolean get() = this is Fake
}

sealed class LinkState {
    data class Stopped(val why: String = "") : LinkState()
    object Searching : LinkState()
    object NotFound : LinkState()                   // still searching, nothing answered for a while
    object Starting : LinkState()                   // found the unit, SESSION sent
    data class Connected(val bluetooth: Boolean) : LinkState()
    data class InUse(val why: String) : LinkState()
    object Lost : LinkState()                       // was connected; searching again
    data class Error(val why: String) : LinkState()
    object Stopping : LinkState()

    val title: String
        get() = when (this) {
            is Stopped -> "Stopped"
            Searching -> "Searching…"
            NotFound -> "Not found"
            Starting -> "Starting…"
            is Connected -> if (bluetooth) "Connected over Bluetooth" else "Connected over USB"
            is InUse -> "In use"
            Lost -> "Unit lost: searching…"
            is Error -> "Error"
            Stopping -> "Stopping…"
        }

    val isConnected: Boolean get() = this is Connected

    companion object {
        // RFMCore.h: RFMC_* and RFMC_S_*
        const val OFF = 0
        const val IN_USE = 1
        const val SEARCHING = 2
        const val CONNECTED = 3
        const val LOST = 4
        const val ERROR = 5
        const val STOPPING = 6
        const val S_STARTING = 1
        const val NOT_FOUND_AFTER_MS = 3000L

        /**
         * RemoteController.state(of:): the link's [state] and [sessionState] as the app shows them. [searchingForMs]: how
         * long it has been searching (null: it just started); returns the state and whether it is searching now.
         */
        fun of(state: Int, sessionState: Int, wanted: Boolean, ble: Boolean, text: String,
               searchingForMs: Long?): Pair<LinkState, Boolean> = when (state) {
            IN_USE -> InUse(text) to false
            ERROR -> Error(text) to false
            STOPPING -> Stopping to false
            CONNECTED -> Connected(ble) to false
            LOST -> Lost to false
            SEARCHING ->
                if (sessionState == S_STARTING) Starting to false
                else (if ((searchingForMs ?: 0L) > NOT_FOUND_AFTER_MS) NotFound else Searching) to true
            else -> if (wanted) Searching to true else Stopped("") to false
        }
    }
}

/** The unit's HELLO_R (docs/PROTOCOL.md section 2). */
data class UnitHello(
    val name: String = "", val version: String = "", val firmwareVersion: String = "", val identity: String = "",
    val proto: Int = 0,
    val transports: Int = 0,        // RFM_TR_* bits
    val usbMode: Int = 0,
    val caps: Int = 0,              // RFM_CAP_* bits
    val sxq: Int = 0,
    val sxBodyMax: Int = 0, val bkBodyMax: Int = 0,
    val screenW: Int = 0, val screenH: Int = 0,
    val maxInFlight: Int = 0,
    val isBluetooth: Boolean = false,   // rfm_hello_is_ble: this reply came over Bluetooth MIDI
    val session: Boolean = false,       // session8: a session is running (anyone's)
) {
    val transportNames: String get() = bits(transports, listOf("USB", "bulk", "SysEx heard", "bulk heard", "BLE", "BLE heard"))
    val capNames: String
        get() = bits(caps, listOf("shadow", "COPY", "RLE", "PAL", "battery", "PLAY green", "5 LED levels", "HOLD",
                                  "BLE fast", "MIDI in", "TOUCH"))

    companion object {
        fun bits(v: Int, names: List<String>): String {
            val on = names.indices.filter { v and (1 shl it) != 0 }.map { names[it] }
            return if (on.isEmpty()) "none" else on.joinToString(" ")
        }
    }
}

/** The session's numbers (rfm_session_stats_t through RemoteLink's status), and the Android back end's. */
data class LinkStats(
    val fps: Double = 0.0,
    val runs: Long = 0, val sessions: Long = 0, val discoveries: Long = 0,
    val frames: Long = 0, val acks: Long = 0, val pending: Long = 0, val inFlight: Long = 0,
    val ackMsLast: Long = 0, val ackMsAvg: Long = 0, val ackMsMax: Long = 0, val ackTimeouts: Long = 0,
    val wireBytes: Long = 0, val bodyBytes: Long = 0,
    val budget: Long = 0, val wireCap: Long = 0, val frameMs: Long = 0,
    val ble: Boolean = false, val hold: Boolean = false,
    val held: Long = 0, val reduced: Long = 0, val refined: Long = 0, val colors: Long = 0,
    val eventsIn: Long = 0, val eventsDropped: Long = 0, val snapshots: Long = 0,
    val trsMidiIn: Long = 0, val trsMidiDropped: Long = 0,
    val touchWanted: Int = 0, val touch: Int = 0, val touchSent: Long = 0,
    // Android: MIDI port pairs open, bytes in and out through AMidi, writes the MIDI service took short, the frame link
    val midiLinks: Long = 0, val rxBytes: Long = 0, val txBytes: Long = 0, val txShort: Long = 0,
    val frameLink: String = "",
    // PANEL_R heard, units lost, undecodable messages; links let go, reopened for silence, failed writes and reads;
    // ms since the last panel event (-1: none)
    val panelR: Long = 0, val lost: Long = 0, val decodeErrs: Long = 0, val linkDrops: Long = 0,
    val silentReopens: Long = 0, val writeFails: Long = 0, val rxErrors: Long = 0, val lastEventAgeMs: Long = -1,
) {
    fun touchText(hello: UnitHello?): String = touchText(hello?.caps, touchWanted, touch, touchSent)

    companion object {
        /** the Diagnostics line: whether the firmware has TOUCH (HELLO_R caps), what was asked, what PANEL_R confirms */
        fun touchText(caps: Int?, wanted: Int, inForce: Int, sent: Long): String {
            fun names(f: Int): String {
                val n = listOfNotNull(if (f and 1 != 0) "velocity" else null, if (f and 2 != 0) "pressure" else null)
                return if (n.isEmpty()) "none" else n.joinToString(" + ")
            }
            if (caps == null) return if (wanted == 0) "off" else "on (no FM-1 yet)"
            if (caps and 0x400 == 0) return "not in this firmware (RemoteFM1 0.3.1+): velocity 100, no pressure"
            if (wanted == 0) return "off: velocity 100, no pressure (firmware has TOUCH)"
            return "asked ${names(wanted)}; FM-1 confirms ${names(inForce)}" + (if (inForce == wanted) "" else " (not yet)") +
                ", $sent sent"
        }
    }
}

/** One panel event from the unit (matrix numbers, as the protocol has them). */
data class PanelEvent(
    val id: Long,                   // arrival order
    val kind: Kind,
    val index: Int,
    val value: Int,                 // key / button 1 down 0 up; encoder detents; MASTER 0..1023; pressure 0..127
    val velocity: Int = 0,          // a key down: its velocity (TOUCH), 0 none given
    val resync: Boolean = false,    // a PANEL_R correction, not a MIDI event
) {
    enum class Kind { KEY, BUTTON, ENCODER, MASTER, PRESSURE, RELEASE }

    val text: String
        get() {
            val r = if (resync) " (PANEL_R)" else ""
            return when (kind) {
                Kind.KEY -> "key $index ${keyName(index)} ${if (value != 0) "down" else "up"}" +
                    (if (value != 0 && velocity > 0) " vel $velocity" else "") + r
                Kind.PRESSURE -> "key $index ${keyName(index)} pressure $value$r"
                Kind.BUTTON -> "button ${buttonNames.getOrElse(index) { "$index" }} ${if (value != 0) "down" else "up"}$r"
                Kind.ENCODER -> "encoder $index ${if (value > 0) "+" else ""}$value$r"
                Kind.MASTER -> "MASTER $value$r"
                Kind.RELEASE -> "all up (session ended)"
            }
        }

    companion object {
        val buttonNames = listOf("OCT-", "OCT+", "FX", "SEL", "ENV", "LFO", "EDIT", "GLO", "HOME", "SAVE", "ARP", "SEQ",
                                 "PLAY", "REC")
        private val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        /** note key k (0 = F3, MIDI 53) */
        fun keyName(k: Int): String {
            val n = 53 + k
            return "${noteNames[n % 12]}${n / 12 - 1}"
        }

        /** from rfmc_event_t's fields (RFMC_EV_*: 1 key, 2 button, 3 encoder, 4 MASTER, 5 pressure, 0x80 release) */
        fun of(seq: Long, type: Int, index: Int, source: Int, value: Int, vel: Int): PanelEvent {
            val kind = when (type) {
                1 -> Kind.KEY
                2 -> Kind.BUTTON
                3 -> Kind.ENCODER
                4 -> Kind.MASTER
                5 -> Kind.PRESSURE
                else -> Kind.RELEASE
            }
            return PanelEvent(seq, kind, index, value, vel, source != 0)
        }
    }
}
