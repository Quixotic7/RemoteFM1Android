// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Ableton Move's firmware browser on the app's firmware (move/MoveFirmware.kt says what the Move lists): the
// Firmware page's catalog and Get firmware's list (FirmwareCenter), a load as the Firmware page picks one
// (AppModel.moveLoad: selected, Core mode; the running firmware's flash saved and let go first, as for any pick), an
// unload (the test pattern), a download through Get firmware's own install (FirmwareStore.install: the same checks,
// the same update rules, its progress on the page too), the thumbnails from the cards' pictures (FirmwareThumbs).
package com.quixotic7.remotefm1

import android.graphics.BitmapFactory
import android.os.Handler
import com.quixotic7.remotefm1.firmware.AvailableFirmware
import com.quixotic7.remotefm1.firmware.FirmwareListing
import com.quixotic7.remotefm1.move.MoveCatalog
import com.quixotic7.remotefm1.move.MoveFirmwareHost
import com.quixotic7.remotefm1.move.MoveFw
import java.util.concurrent.ConcurrentHashMap

class MoveFirmwareBridge(private val m: AppModel, private val main: Handler) : MoveFirmwareHost {
    private val fw get() = m.fw
    private val thumbs = ConcurrentHashMap<String, Any>()      // id -> 512 bytes, or NONE
    @Volatile private var thumbGen = -1

    override fun stamp(): Any = listOf(fw.catalog.value, fw.store.available.value, fw.library.files.value,
        fw.thumbs.generation.value)

    override fun catalog(): List<MoveFw> {
        val cores = fw.catalog.value
        val g = fw.thumbs.generation.value
        if (g != thumbGen) { thumbs.clear(); thumbGen = g }
        return MoveCatalog.build(cores, FirmwareListing.addedDates(fw.library.files.value),
            fw.store.list(cores.filter { !it.imported }), { fw.store.state(it) }, fw.store.details,
            virtualOf = { c -> c.imported && runCatching { fw.library.details(c.sha256).fantasy.virtual }.getOrDefault(false) },
            thumb = ::thumb)
    }

    /** the card's picture as the Move's 1-bit thumbnail (decoded at most ~256 px across), null none */
    private fun thumb(id: String): ByteArray? {
        val t = thumbs.getOrPut(id) {
            runCatching {
                val png = fw.thumbs.png(id) ?: return@runCatching null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(png, 0, png.size, o)
                var k = 1
                while (minOf(o.outWidth, o.outHeight) / (k * 2) >= 128) k *= 2
                val bm = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = k })
                    ?: return@runCatching null
                try {
                    val px = IntArray(bm.width * bm.height)
                    bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
                    MoveCatalog.thumb64(px, bm.width, bm.height)
                } finally {
                    bm.recycle()
                }
            }.getOrNull() ?: NONE
        }
        return t as? ByteArray
    }

    override fun running(): String? = m.runningFirmwareId

    override fun load(key: String, done: (String?) -> Unit) {
        main.post { done(runCatching { m.moveLoad(key) }.getOrElse { it.message ?: "The firmware didn't start." }) }
    }

    override fun unload(done: () -> Unit) {
        main.post {
            runCatching { m.moveUnload() }
            done()
        }
    }

    override fun download(e: AvailableFirmware, progress: (Int) -> Unit, done: (String?, Boolean) -> Unit) {
        val store = fw.store
        if (store.progress.value != null) return done(null, true)
        Thread({
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val watch = Thread({
                while (running.get()) {
                    store.progress.value?.takeIf { it.id == e.id }?.fraction?.let { progress((it * 99).toInt()) }
                    try { Thread.sleep(150) } catch (_: InterruptedException) { break }
                }
            }, "RFM Move download progress").apply { isDaemon = true; start() }
            val f = try { store.install(e) } catch (x: Exception) { null } finally { running.set(false); watch.interrupt() }
            if (f != null) {
                main.post { m.refreshCatalog() }
                done(f.version, false)
            } else {
                done(null, false)
            }
        }, "RFM Move download").apply { isDaemon = true; start() }
    }

    private companion object { val NONE = Any() }
}
