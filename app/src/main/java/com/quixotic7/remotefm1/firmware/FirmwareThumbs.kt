// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/FirmwareThumbs.swift (FirmwareThumbs, ThumbCapture,
// FirmwareInfo).
//
// The firmware list's thumbnails. Shipped first: assets/thumbs/<firmwareID>.png (iOS's Resources/Thumbs, each
// firmware's LCD after a chord of white keys and PLAY). Others (a firmware the app doesn't know): its own LCD, captured
// once it has run ~3 s after boot, as a 240x240 PNG in files/Thumbs/<firmwareID>.png. An all-black (or nearly) frame
// isn't kept: the capture retries on the next frames up to ~10 s after the 3 s, then gives up until the firmware boots
// again. FirmwareInfo: what the Firmware card says about the running firmware, from the site catalog's details by the
// firmware's id, else the core's own.
package com.quixotic7.remotefm1.firmware

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.URI
import java.util.UUID

class FirmwareThumbs(
    /** files/Thumbs */
    val dir: File,
    /** a shipped thumbnail's PNG bytes (assets/thumbs/<id>.png), null none */
    private val shipped: (String) -> ByteArray? = { null },
    /** RGBA (alpha ignored) as a PNG into the file; false: not written */
    private val writePng: (ByteArray, Int, Int, File) -> Boolean = { a, w, h, f -> androidPng(a, w, h, f) },
) {
    private val _generation = MutableStateFlow(0)
    /** bumped when a thumbnail is written (the tiles read again) */
    val generation: StateFlow<Int> = _generation
    private val shippedIds = HashMap<String, Boolean>()

    fun file(id: String): File = File(dir, id.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("") + ".png")

    fun hasShipped(id: String): Boolean = synchronized(shippedIds) { shippedIds.getOrPut(id) { shipped(id) != null } }

    fun exists(id: String): Boolean = hasShipped(id) || file(id).isFile

    /** the thumbnail's PNG bytes: the shipped one, else the captured one; null none */
    fun png(id: String): ByteArray? = shipped(id) ?: file(id).takeIf { it.isFile }?.readBytes()

    /** the thumbnail as a Bitmap (Android) */
    fun bitmap(id: String): Bitmap? = png(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /** writes the frame (RGBA, 240x240) as the firmware's thumbnail; false: blank or not written */
    fun save(rgba: ByteArray, id: String, width: Int = 240, height: Int = 240): Boolean {
        if (id.isEmpty() || rgba.size < width * height * 4 || isBlank(rgba)) return false
        dir.mkdirs()
        val tmp = File(dir, ".${UUID.randomUUID()}.png")
        if (!runCatching { writePng(rgba, width, height, tmp) }.getOrDefault(false)) {
            tmp.delete()
            return false
        }
        val dst = file(id)
        dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            return false
        }
        _generation.value++
        return true
    }

    companion object {
        /** the app's: files/Thumbs, shipped ones from assets/thumbs */
        fun android(ctx: Context): FirmwareThumbs {
            val am = ctx.assets
            return FirmwareThumbs(File(ctx.filesDir, "Thumbs"), shipped = { id ->
                runCatching { am.open("thumbs/$id.png").use { it.readBytes() } }.getOrNull()
            })
        }

        /** nearly all black: fewer than 0.5 % of the pixels with a channel over 24 */
        fun isBlank(rgba: ByteArray): Boolean {
            val n = rgba.size / 4
            if (n == 0) return true
            var lit = 0
            for (i in 0 until n) {
                val o = i * 4
                if ((rgba[o].toInt() and 0xFF) > 24 || (rgba[o + 1].toInt() and 0xFF) > 24 || (rgba[o + 2].toInt() and 0xFF) > 24) lit++
            }
            return lit * 200 < n
        }

        fun androidPng(rgba: ByteArray, w: Int, h: Int, to: File): Boolean {
            val px = IntArray(w * h) { i ->
                val o = i * 4
                (0xFF shl 24) or ((rgba[o].toInt() and 0xFF) shl 16) or ((rgba[o + 1].toInt() and 0xFF) shl 8) or (rgba[o + 2].toInt() and 0xFF)
            }
            val bm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            return try {
                to.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bm.recycle()
            }
        }
    }
}

/**
 * When to take a firmware's thumbnail: fed every new frame (RfmEngine.preview) with the running firmware's id and
 * whether it booted. [now] in seconds (SystemClock.elapsedRealtime() / 1000.0 in the app).
 */
class ThumbCapture(val thumbs: FirmwareThumbs, var delay: Double = 3.0, var window: Double = 10.0) {
    private var id = ""
    private var since: Double? = null
    private var done = false

    /** true when this frame was written */
    fun frame(rgba: ByteArray, id: String?, booted: Boolean, now: Double): Boolean {
        val fid = id ?: ""
        if (!booted || fid.isEmpty()) {
            since = null
            return false
        }
        if (fid != this.id || since == null) {
            this.id = fid
            since = now
            done = thumbs.exists(fid)
        }
        val s = since ?: return false
        if (done || now - s < delay) return false
        if (now - s > delay + window) {
            done = true
            return false
        }
        if (thumbs.save(rgba, fid)) {
            done = true
            return true
        }
        return false
    }
}

/** what the Firmware card says about a firmware */
data class FirmwareInfo(
    val id: String = "", val name: String = "", val version: String = "", val author: String = "", val tag: String = "",
    val about: String = "", val license: String = "",
    /** the firmware's source on GitHub (or wherever its manifest points); null: none known ("View source") */
    val source: String? = null,
) {
    companion object {
        /** from the catalog's details by the firmware's id (built-in cores and imported files alike), else the core's
         *  own */
        fun lookup(c: CoreInfo, details: Map<String, AvailableFirmware>): FirmwareInfo {
            var name = c.name
            var author = ""
            var tag = ""
            var about = ""
            var license = c.license
            val d = details[c.firmwareID] ?: details[c.id] ?: details[c.firmwareID.lowercase()]
            if (d != null) {
                if (name.isEmpty()) name = d.displayName
                author = d.author
                tag = d.summary
                about = d.about
                if (license.isEmpty()) license = d.license
            }
            if (about.isEmpty()) about = c.credits.trim()
            // the catalog's source (at its version), its repo; the core's manifest otherwise
            val links = ArrayList<String>()
            if (d != null) {
                val same = FirmwareVersion.compare(d.version, c.version) == 0
                if (same || d.repo.isEmpty()) links.add(d.sourceURL)
                if (d.repo.isNotEmpty()) links.add("https://github.com/${d.repo}")
            }
            links.add(c.sourceURL)
            val source = links.firstOrNull { s ->
                s.isNotEmpty() && runCatching { URI(s).scheme?.lowercase() }.getOrNull().let { it == "https" || it == "http" }
            }
            return FirmwareInfo(c.firmwareID, name, c.version, author, tag, about, license, source)
        }
    }
}
