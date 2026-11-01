// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS 0d1a98b RFMKit/Sources/RFMKit/CorePlayer.swift (the flash part: bootSelected's read,
// saveNow, saveStopped, takeFlash, write, powerCycle, refresh's take and reboot).
//
// A core's flash kept in files/cores/<id>.bin (FlashFile: safe writes, .prev, the sidecar). A boot reads it (none: a
// fresh flash); the clock copies the image every 30 device seconds when it changed, and [refresh] (the app's tick)
// writes the copy on a background thread. flash_sync and a save also come with a core switch, Pattern mode, the app
// going to the background, Restart and the service stopping (AppModel). A firmware that reboots itself (halted, exit
// code 0) is powered on again from its flash, as FM1VST does. Control calls are for one thread (the main thread or a
// test's).
package com.quixotic7.remotefm1

import android.os.Handler
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CoreFlash(
    private val engine: RfmEngine,
    val dir: File,
    /** a core id's version (the sidecar's), null when unknown */
    private val versionOf: (String) -> String? = { null },
) {
    var bootedId: String? = null
        private set
    var saves = 0
        private set
    var lastSaveMs = 0L                         // System.currentTimeMillis() of the last write queued
        private set
    var lastError = ""
        private set
    var reboots = 0
        private set
    private var buf = ByteArray(0)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rfm-flash").apply { isDaemon = true } }

    init {
        dir.mkdirs()
    }

    fun file(id: String) = File(dir, "$id.bin")

    /**
     * After a load: the core [id] powered on from its file (a fresh flash when it has none). Returns null when it
     * did not boot, else the note ("" or why the saved memory was not used as it was).
     */
    fun boot(id: String): String? {
        val f = file(id)
        val r = io.submit<FlashFile.Read> { FlashFile.read(f, engine.flashSize) }.get()
        if (r.note.isNotEmpty()) Log.e(TAG, "flash ${f.name}: ${r.note}")
        if (!engine.boot(r.data)) return null
        bootedId = id
        buf = ByteArray(engine.flashSize)
        Log.i(TAG, "booted $id from ${if (r.data == null) "a fresh flash" else f.name + if (r.fromPrev) " (.prev)" else ""}")
        return r.note
    }

    /** the core let go (switching cores): its flash saved first */
    fun unload() {
        saveStopped()
        engine.unload()
        bootedId = null
    }

    /** flash_sync with the clock stopped, the image queued for writing */
    fun saveStopped() {
        val id = bootedId ?: return
        if (buf.isEmpty()) return
        if (engine.finalFlash(buf)) write(buf.copyOf(), id)
    }

    /** a copy the clock made, queued for writing; true when there was one */
    fun takeFlash(): Boolean {
        val id = bootedId ?: return false
        if (buf.isEmpty()) return false
        val ok = engine.takeFlash(buf)
        if (ok) write(buf.copyOf(), id)
        return ok
    }

    /**
     * flash_sync and the image written now, also while the clock runs (asked of the clock, then waited for on
     * [main] for up to 0.5 s; the clock stopped for it if it did not answer); [done] gets true when a file was
     * written.
     */
    fun saveNow(main: Handler, clockRunning: Boolean, done: (Boolean) -> Unit = {}) {
        val id = bootedId ?: return done(false)
        if (!clockRunning) {
            saveStopped()
            return done(true)
        }
        takeFlash()                                             // (an older copy waiting: written first)
        engine.requestFlash()
        var tries = 0
        fun poll() {
            if (bootedId != id) return done(false)
            if (takeFlash()) return done(true)
            if (++tries < 50) {
                main.postDelayed({ poll() }, 10)
                return
            }
            Log.e(TAG, "flash: the clock did not answer; saved with the clock stopped")
            saveStopped()
            done(true)
        }
        main.postDelayed({ poll() }, 10)
    }

    /** the firmware off and on again from its flash (a halted core, or the user's Restart); null or the error */
    fun powerCycle(): String? {
        bootedId ?: return "no firmware running"
        val err = engine.powerCycle()
        if (err != null) {
            bootedId = null
            return err
        }
        reboots++
        takeFlash()                                             // (the image it kept, offered for saving)
        return null
    }

    /**
     * The app's tick: a copy the clock made written; a firmware that rebooted itself (exit 0) powered on again.
     * Returns a message when that happened.
     */
    fun refresh(status: org.json.JSONObject): String? {
        takeFlash()
        if (status.optInt("halted") != 0 && status.optInt("halt_code") == 0x100 && bootedId != null) {
            val err = powerCycle()
            return err?.let { "restart failed: $it" } ?: "the firmware rebooted (exit 0): powered on again from its flash"
        }
        return null
    }

    /** waits until the queued writes are on disk (up to [ms]) */
    fun flush(ms: Long = 2000) {
        runCatching { io.submit {}.get(ms, TimeUnit.MILLISECONDS) }
    }

    private fun write(data: ByteArray, id: String) {
        val f = file(id)
        val version = versionOf(id) ?: ""
        io.execute {
            try {
                FlashFile.write(data, f, firmwareID = id, version = version)
                lastError = ""
            } catch (e: Exception) {
                lastError = "${f.name}: ${e.message}"
                Log.e(TAG, "flash: ${f.path}: $e")
            }
        }
        saves++
        lastSaveMs = System.currentTimeMillis()
    }

    companion object {
        const val TAG = "RFMFlash"
    }
}
