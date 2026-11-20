// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Move's own firmware browser (rfm1cs-abletonmove 0.5.10, "One firmware, no FX"): the app's firmware catalog as
// the core's list, the firmware's 64 x 64 1-bit thumbnails, and the library blob the core asks the app to keep.
//
// The list is the app's two lists in one, with their rules (the owner: "on the website and apps we still limit to just 1
// firmware and there are not schwung midi or audio FX"):
//   on this device   the Firmware page's cores (built in, then imported .rfm1 files), each firmware's newest version
//                    only (FirmwareListing.superseded): installed, loaded with a jog press as a pick on the Firmware page
//   to download      Get firmware's list (FirmwareStore.list: what the feeds offer to download, without what this build
//                    has built in): not installed, or an update of one that is; the download is the store's own install
// Nothing else: a firmware the app's lists don't show is not on the Move either. Ordered A-Z by name, as the catalog.
// Names and descriptions are ASCII (the Move's font); ids of 32 bytes or more are left out (the core refuses them).
// The thumbnail: RemoteFM1FW's card picture (FirmwareThumbs: the catalog's, the shipped one, the captured LCD) as
// remoteFM14Move's tools/build_move_catalog.py makes it (the mockup's gen_data.py): the centre square, 64 x 64, auto-
// contrast (1 % cut), an Atkinson dither on luma.
package com.quixotic7.remotefm1.move

import com.quixotic7.remotefm1.firmware.AvailableFirmware
import com.quixotic7.remotefm1.firmware.CoreInfo
import com.quixotic7.remotefm1.firmware.FirmwareListing
import com.quixotic7.remotefm1.firmware.FirmwareVersion
import com.quixotic7.remotefm1.firmware.InstallState
import java.io.File
import java.text.Normalizer
import java.time.Instant

/** one entry of the Move's list (rfm_move_fw_t), and what the app does with it */
data class MoveFw(
    val id: String,
    val name: String,
    val version: String,
    /** the version on this device; null: not here (Download) */
    val installed: String?,
    val desc: String?,
    val cats: Int,
    val cat: Int,
    val virt: Boolean,
    /** the app's catalog key a load picks; null: not on this device */
    val key: String? = null,
    /** what a download installs (Get firmware's entry); null: nothing to download */
    val download: AvailableFirmware? = null,
    /** 512 bytes, or null (the Move draws the initials) */
    val thumb: ByteArray? = null,
) {
    override fun equals(other: Any?) = other is MoveFw && other.id == id && other.name == name && other.version == version &&
        other.installed == installed && other.desc == desc && other.cats == cats && other.cat == cat && other.virt == virt &&
        other.key == key && (other.thumb ?: EMPTY).contentEquals(thumb ?: EMPTY)

    override fun hashCode() = id.hashCode()

    private companion object { val EMPTY = ByteArray(0) }
}

/** the app's firmware side, for the Move (AppModel's MoveFirmwareBridge) */
interface MoveFirmwareHost {
    /** anything that changes when the catalog, the install states or the thumbnails change (compared with ==) */
    fun stamp(): Any
    /** the Move's list now (slow the first time: the thumbnails) */
    fun catalog(): List<MoveFw>
    /** the firmware id playing now (the Move's slot 0), null none */
    fun running(): String?
    /** [key] loaded as the app does when it is picked; [done] (any thread) gets null, or what went wrong */
    fun load(key: String, done: (String?) -> Unit)
    /** the firmware unloaded (the test pattern again); [done] (any thread) after */
    fun unload(done: () -> Unit)
    /** [e] downloaded and installed by the store; [progress] 0..99; [done] gets the version installed, or null and
     *  whether another download was running (busy) */
    fun download(e: AvailableFirmware, progress: (Int) -> Unit, done: (String?, Boolean) -> Unit)
    /** (0.5.11) the app's volume 0..1 now (sent as slot 0's level at start), null: the app has none */
    fun volume(): Float? = null
    /** (0.5.11) the Move set slot 0's level (0..127): the app's volume follows it (100 = full, the web page's scale) */
    fun level(v: Int) {}
}

object MoveCatalog {
    /** the core's categories, steps 3..7 (RFM_MOVE_CAT_SYNTH 1 .. _OTHER 16) */
    val CATS = listOf("Synth", "Groovebox", "Chords", "Drums", "Other")
    const val ID_MAX = 32                           // RFM_MOVE_FW_ID_MAX (with its NUL)
    const val THUMB = 64
    const val THUMB_BYTES = THUMB * THUMB / 8

    /**
     * The Move's list from the app's: [cores] the Firmware page's catalog (FirmwareCenter.catalog), [added] the
     * imported files' dates (FirmwareListing.addedDates), [available] Get firmware's list (FirmwareStore.list),
     * [state] its install state, [details] the catalogs' card text by id (FirmwareStore.details), [virtualOf] an
     * imported core's manifest says Fantasy CFW, [thumb] the 512 bytes for a firmware id (null none).
     */
    fun build(cores: List<CoreInfo>, added: Map<String, Instant>, available: List<AvailableFirmware>,
              state: (AvailableFirmware) -> InstallState, details: Map<String, AvailableFirmware>,
              virtualOf: (CoreInfo) -> Boolean = { false }, thumb: (String) -> ByteArray? = { null }): List<MoveFw> {
        val hide = FirmwareListing.superseded(cores, added)
        val avail = LinkedHashMap<String, AvailableFirmware>()
        for (a in available) avail.putIfAbsent(a.id, a)
        val out = ArrayList<MoveFw>()
        val seen = HashSet<String>()
        for (c in cores) {
            if (c.id in hide) continue
            val id = c.firmwareID
            if (!seen.add(id)) continue
            val a = avail[id]
            val d = details[id] ?: a
            // (a newer version listed: an update; the store's state says so for an imported file, a built-in core's
            // newer .rfm1 is offered by Get firmware too)
            val update = a != null && (FirmwareVersion.less(c.version, a.version) || state(a) is InstallState.Update)
            val name = c.name.ifEmpty { d?.displayName ?: id }
            val (cats, cat) = cats(d?.category)
            out += MoveFw(id, ascii(name), ascii(if (update) a!!.version else c.version), ascii(c.version),
                desc(d), cats, cat, d?.virtual == true || virtualOf(c), key = c.id, download = if (update) a else null)
        }
        for (a in avail.values) {
            if (a.id in seen) continue
            seen.add(a.id)
            val d = details[a.id] ?: a
            val (cats, cat) = cats(d.category.ifEmpty { a.category })
            out += MoveFw(a.id, ascii(a.displayName), ascii(a.version), null, desc(d), cats, cat, a.virtual || d.virtual,
                key = null, download = a)
        }
        return out.filter { it.id.isNotEmpty() && it.id.length < ID_MAX && it.id.all { ch -> ch.code in 33..126 } }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            .map { it.copy(thumb = thumb(it.id)?.takeIf { t -> t.size == THUMB_BYTES }) }
    }

    /** the card text: what it is, else its one line (the Move's catalog); null none */
    private fun desc(d: AvailableFirmware?): String? =
        d?.let { ascii(it.about.ifEmpty { it.summary }).trim() }?.ifEmpty { null }

    /** a catalog category as (bits, the main one 0..4); unknown or none: Other */
    fun cats(category: String?): Pair<Int, Int> {
        val i = CATS.indexOfFirst { it.equals(category?.trim(), ignoreCase = true) }.let { if (it < 0) CATS.size - 1 else it }
        return (1 shl i) to i
    }

    /** ASCII for the Move's font: quotes, dashes, dots and accents made plain; the rest left out */
    fun ascii(s: String): String {
        val t = s.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
            .replace("–", "-").replace("—", "-").replace("…", "...").replace('·', '-')
            .replace(' ', ' ').replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        return Normalizer.normalize(t, Normalizer.Form.NFD).filter { it.code in 32..126 }
    }

    /**
     * A picture as the Move's thumbnail: 64 x 64, 1 bit, rows top first, MSB left (512 bytes). [argb] w x h pixels
     * (Bitmap.getPixels' order). The centre square, box-averaged to 64 x 64 on luma (ITU-R 601: PIL's "L"), auto-
     * contrast cutting 1 % at each end, then Atkinson's dither at 128.
     */
    fun thumb64(argb: IntArray, w: Int, h: Int): ByteArray {
        require(w > 0 && h > 0 && argb.size >= w * h)
        val n = THUMB
        val s = minOf(w, h)
        val x0 = (w - s) / 2
        val y0 = (h - s) / 2
        val px = DoubleArray(n * n)
        for (ty in 0 until n) for (tx in 0 until n) {
            val ax = x0 + tx * s / n
            val bx = maxOf(ax + 1, x0 + (tx + 1) * s / n)
            val ay = y0 + ty * s / n
            val by = maxOf(ay + 1, y0 + (ty + 1) * s / n)
            var sum = 0.0
            var k = 0
            for (y in ay until by) for (x in ax until bx) {
                val c = argb[y * w + x]
                sum += ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000.0
                k++
            }
            px[ty * n + tx] = sum / k
        }
        // autocontrast (cutoff 1 %): the histogram's ends cut, lo..hi stretched to 0..255
        val hist = IntArray(256)
        for (v in px) hist[v.toInt().coerceIn(0, 255)]++
        val cut = px.size / 100
        var lo = 0
        var c = 0
        while (lo < 255 && c + hist[lo] <= cut) { c += hist[lo]; lo++ }
        var hi = 255
        c = 0
        while (hi > 0 && c + hist[hi] <= cut) { c += hist[hi]; hi-- }
        if (hi > lo) for (i in px.indices) px[i] = ((px[i] - lo) * 255.0 / (hi - lo)).coerceIn(0.0, 255.0)
        // Atkinson: 1/8 of the error to six neighbours (3/4 of it kept)
        val out = ByteArray(THUMB_BYTES)
        for (y in 0 until n) for (x in 0 until n) {
            val o = px[y * n + x]
            val v = if (o >= 128) 255.0 else 0.0
            if (v > 0) out[y * 8 + x / 8] = (out[y * 8 + x / 8].toInt() or (0x80 shr (x % 8))).toByte()
            val e = (o - v) / 8
            for ((dx, dy) in NEIGHBOURS) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until n && yy in 0 until n) px[yy * n + xx] += e
            }
        }
        return out
    }

    private val NEIGHBOURS = listOf(1 to 0, 2 to 0, -1 to 1, 0 to 1, 1 to 1, 0 to 2)
}

/** the library blob (RFM_MOVE_ACT_FW_LIBRARY's bytes: favourites, colours, tags, slot 0, its level) in the app's
 *  files, as RemoteFM14Move keeps move/library.json's bytes: written whole (a temporary file renamed), read at start */
class MoveLibrary(val file: File, private val max: Int = 10244) {
    fun read(): ByteArray? = runCatching { file.takeIf { it.isFile && it.length() in 1..max.toLong() }?.readBytes() }.getOrNull()

    fun write(b: ByteArray): Boolean {
        if (b.isEmpty() || b.size > max) return false
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(b)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) } else true
        }.getOrDefault(false)
    }
}
