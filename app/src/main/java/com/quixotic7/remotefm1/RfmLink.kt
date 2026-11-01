// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import org.json.JSONArray
import org.json.JSONObject

/**
 * One FM-1 link (native/rfmcore: RFMCore's rfmc_t = RemoteLink + the test-pattern producer). [Backend.Device] talks to
 * the MIDI devices RfmMidi handed down (USB, Bluetooth; [virtualName]: also the tests' virtual loopback device of
 * exactly that name); [Backend.Fake]: libremotefm1's fake unit in this process. Every call returns at once.
 */
class RfmLink(val backend: Backend, fps: Int = 20, hostName: String = HOST_NAME, fakeNoTouch: Boolean = false,
              virtualName: String = "") : AutoCloseable {
    internal var handle: Long = nativeNew(if (backend.isFake) 1 else 0, (backend as? Backend.Fake)?.bluetooth == true,
                                          fakeNoTouch, fps, hostName, virtualName)
        private set

    fun start() = nativeStart(handle)
    fun stop() = nativeStop(handle)
    fun setPattern(on: Boolean) = nativeSetPattern(handle, on)
    fun rescan() = nativeRescan(handle)

    /** TOUCH flags: 1 velocity, 2 pressure (default both); 0 not asked. */
    fun setKeyTouch(flags: Int) = nativeSetKeyTouch(handle, flags)

    /** rfmc_status as JSON (RFMCore.h rfmc_status_t's field names). */
    fun status(): JSONObject = JSONObject(nativeStatus(handle))

    /** The panel's events after seq [after], oldest first. */
    fun events(after: Long): List<PanelEvent> {
        val a = JSONArray(nativeEvents(handle, after.toInt()))
        return (0 until a.length()).map {
            val e = a.getJSONArray(it)
            PanelEvent.of(e.getLong(0), e.getInt(1), e.getInt(2), e.getInt(3), e.getInt(4), e.getInt(5))
        }
    }

    /** Held keys, held buttons (bits), MASTER (-1 unknown). */
    fun panel(): LongArray = nativePanel(handle)

    /** The frame being sent (RGBA 240 x 240) into [rgba] when its number differs from [have]; returns its number. */
    fun preview(have: Int, rgba: ByteArray): Int = nativePreview(handle, have, rgba)

    // ---- the fake unit (Backend.Fake; no-ops otherwise)
    fun fakeKey(k: Int, down: Boolean, vel: Int = 0) = nativeFakeKey(handle, k, down, vel)
    fun fakePress(k: Int, p: Int) = nativeFakePress(handle, k, p)
    fun fakeButton(b: Int, down: Boolean) = nativeFakeButton(handle, b, down)
    fun fakeEncoder(e: Int, detents: Int) = nativeFakeEnc(handle, e, detents)
    fun fakeMaster(adc: Int) = nativeFakeMaster(handle, adc)
    fun fakeTrs(msgs: ByteArray) = nativeFakeTrs(handle, msgs)

    /** {"fake":1,"session","frames","acks","held_frames","hellos","touch","touch_msgs","lit_keys","lit_buttons"} */
    fun fakeState(): JSONObject = JSONObject(nativeFakeState(handle))

    /** Stops (SESSION 0, at most ~600 ms) and frees the link; an engine on it must be closed first. */
    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    companion object {
        const val HOST_NAME = "RFM1 Android"     // HELLO's body (at most 16 bytes)

        init {
            System.loadLibrary("rfmcore")
        }

        @JvmStatic private external fun nativeNew(backend: Int, fakeBle: Boolean, fakeNoTouch: Boolean, fps: Int,
                                                  hostName: String, virtualName: String): Long
        @JvmStatic private external fun nativeFree(h: Long)
        @JvmStatic private external fun nativeStart(h: Long)
        @JvmStatic private external fun nativeStop(h: Long)
        @JvmStatic private external fun nativeRescan(h: Long)
        @JvmStatic private external fun nativeSetPattern(h: Long, on: Boolean)
        @JvmStatic private external fun nativeSetKeyTouch(h: Long, flags: Int)
        @JvmStatic private external fun nativeStatus(h: Long): String
        @JvmStatic private external fun nativeEvents(h: Long, after: Int): String
        @JvmStatic private external fun nativePanel(h: Long): LongArray
        @JvmStatic private external fun nativePreview(h: Long, have: Int, rgba: ByteArray): Int
        @JvmStatic private external fun nativeFakeKey(h: Long, k: Int, down: Boolean, vel: Int)
        @JvmStatic private external fun nativeFakePress(h: Long, k: Int, p: Int)
        @JvmStatic private external fun nativeFakeButton(h: Long, b: Int, down: Boolean)
        @JvmStatic private external fun nativeFakeEnc(h: Long, e: Int, d: Int)
        @JvmStatic private external fun nativeFakeMaster(h: Long, adc: Int)
        @JvmStatic private external fun nativeFakeTrs(h: Long, msgs: ByteArray)
        @JvmStatic private external fun nativeFakeState(h: Long): String
    }
}

/** The link's status JSON as the app's models (RemoteController.swift's hello(of:) / stats(of:)). */
object LinkStatus {
    fun hello(j: JSONObject): UnitHello? = if (j.optInt("have_hello") == 0) null else UnitHello(
        name = j.optString("name"), version = j.optString("ver"), firmwareVersion = j.optString("fw_ver"),
        identity = j.optString("identity"), proto = j.optInt("proto"), transports = j.optInt("transports"),
        usbMode = j.optInt("usb_mode"), caps = j.optInt("caps"), sxq = j.optInt("sxq"),
        sxBodyMax = j.optInt("sx_body_max"), bkBodyMax = j.optInt("bk_body_max"), screenW = j.optInt("screen_w"),
        screenH = j.optInt("screen_h"), maxInFlight = j.optInt("max_in_flight"), isBluetooth = j.optInt("hello_ble") != 0,
        session = j.optInt("session") != 0,
    )

    fun stats(j: JSONObject): LinkStats = LinkStats(
        fps = j.optDouble("fps"), runs = j.optLong("runs"), sessions = j.optLong("sessions"),
        discoveries = j.optLong("discoveries"), frames = j.optLong("frames"), acks = j.optLong("acks"),
        pending = j.optLong("pending"), inFlight = j.optLong("in_flight"), ackMsLast = j.optLong("ack_ms_last"),
        ackMsAvg = j.optLong("ack_ms_avg"), ackMsMax = j.optLong("ack_ms_max"), ackTimeouts = j.optLong("ack_timeouts"),
        wireBytes = j.optLong("wire_bytes"), bodyBytes = j.optLong("body_bytes"), budget = j.optLong("budget"),
        wireCap = j.optLong("wire_cap"), frameMs = j.optLong("frame_ms"), ble = j.optInt("ble") != 0,
        hold = j.optInt("hold") != 0, held = j.optLong("held"), reduced = j.optLong("reduced"),
        refined = j.optLong("refined"), colors = j.optLong("colors"), eventsIn = j.optLong("events_in"),
        eventsDropped = j.optLong("events_dropped"), snapshots = j.optLong("snapshots"),
        trsMidiIn = j.optLong("trs_midi_in"), trsMidiDropped = j.optLong("trs_midi_dropped"),
        touchWanted = j.optInt("touch_wanted"), touch = j.optInt("touch"), touchSent = j.optLong("touch_sent"),
        midiLinks = j.optLong("midi_links"), rxBytes = j.optLong("rx_bytes"), txBytes = j.optLong("tx_bytes"),
        txShort = j.optLong("tx_short"), frameLink = j.optString("frame_link"),
        panelR = j.optLong("panel_r"), lost = j.optLong("lost"), decodeErrs = j.optLong("decode_errs"),
        linkDrops = j.optLong("link_drops"), silentReopens = j.optLong("silent_reopens"),
        writeFails = j.optLong("write_fails"), rxErrors = j.optLong("rx_errors"),
        lastEventAgeMs = j.optLong("last_event_age_ms", -1),
    )
}
