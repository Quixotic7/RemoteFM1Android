// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS ca42bbc RFMKit/Sources/RFMKit/Faceplate/Skin.swift
// Skins: an image behind the faceplate (PLAN.md section 12; Brian White's idea).
//   the user's    the sheet's Background image: one photo for every firmware (the photo picker), kept in
//                 files/Skins (re-encoded, at most 2048 px); the dim lays the faceplate's colour over it, so the
//                 labels (chosen for contrast on that colour) stay legible
//   a firmware's  an .rfm1 may carry its own skin (RemoteFM1IOS docs/RFM1-FORMAT.md section 8): custom sections
//                 rfm1.skin (JSON: colours, dim) and rfm1.skin.background / rfm1.skin.background.landscape (PNG or
//                 JPEG). Shown while that firmware plays, unless "Firmware skins" is off
// The firmware's skin wins over the user's image (it was made for that firmware); with neither, the theme's colour.
// Pure Kotlin (org.json, Compose state): the images come through a SkinDecoder (SkinImages.kt on Android), so the
// rules are tested on the JVM. Files are read and images decoded on [SkinStore]'s background, never in composition.
package com.quixotic7.remotefm1.ui.theme

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** a decoded image (Android: a Bitmap, SkinImages.kt) */
interface SkinImage {
    val width: Int
    val height: Int
}

/** PNG / JPEG in and out (Android: ImageDecoder, SkinImages.kt) */
interface SkinDecoder {
    /** the image scaled down to [maxPixels] on its longer side (never up); null: not an image */
    fun decode(data: ByteArray, maxPixels: Int): SkinImage?

    /** a JPEG of the image (the user's background as stored) */
    fun jpeg(img: SkinImage): ByteArray?
}

/** what the faceplate draws behind itself */
@Immutable
class Skin(
    val id: String,                             // equal skins draw the same ("user:<stamp>", "rfm1:<sha256>")
    val portrait: SkinImage?,
    val landscape: SkinImage? = null,
    dim: Double = 0.0,
    val theme: Theme? = null,                   // a firmware skin's colours
) {
    /** 0..0.8: the faceplate's colour over the image (labels stay legible) */
    val dim: Double = if (dim.isNaN()) 0.0 else dim.coerceIn(0.0, SkinStore.MAX_DIM)

    /** the image for a view of this shape (a wide view: the landscape image, if there is one) */
    fun image(wide: Boolean): SkinImage? = if (wide) landscape ?: portrait else portrait ?: landscape

    override fun equals(other: Any?) = other is Skin && other.id == id && other.dim == dim && other.theme == theme
    override fun hashCode() = (id.hashCode() * 31 + dim.hashCode()) * 31 + (theme?.hashCode() ?: 0)
    override fun toString() = "Skin($id, dim $dim)"
}

/** an .rfm1's skin sections (RemoteFM1IOS docs/RFM1-FORMAT.md section 8) */
object SkinFile {
    const val SECTION = "rfm1.skin"
    const val BACKGROUND = "rfm1.skin.background"
    const val BACKGROUND_LANDSCAPE = "rfm1.skin.background.landscape"
    const val MAX_IMAGE_BYTES = 4 shl 20
    const val MAX_PIXELS = 2048

    /** the custom sections whose names start with "rfm1.skin" (a WebAssembly file's section 0s); empty if it isn't
     *  one */
    fun sections(b: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        walk(b.size.toLong(), { at, n -> b.copyOfRange(at.toInt(), (at + n).toInt()) }) { name, payload -> out[name] = payload() }
        return out
    }

    /** the same from a file, reading only the skin's sections (a section over [MAX_IMAGE_BYTES] is left out: no image
     *  that big is shown) */
    fun sections(f: File): Map<String, ByteArray> = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val out = HashMap<String, ByteArray>()
            walk(r.length(), { at, n -> ByteArray(n.toInt()).also { r.seek(at); r.readFully(it) } }) { name, payload -> out[name] = payload() }
            out
        }
    }.getOrElse { emptyMap() }

    /** walks the sections; [read] (offset, count) gives bytes; [found] (name, the payload's bytes on demand) */
    private fun walk(len: Long, read: (Long, Long) -> ByteArray, found: (String, () -> ByteArray) -> Unit) {
        if (len < 8) return
        val head = read(0, 8)
        if (head[0] != 0.toByte() || head[1] != 0x61.toByte() || head[2] != 0x73.toByte() || head[3] != 0x6D.toByte()) return
        var at = 8L
        // (read in small pieces: a LEB is at most 5 bytes)
        fun u32(): Long? {
            var v = 0L
            var shift = 0
            while (at < len && shift < 35) {
                val c = read(at, 1)[0].toInt() and 0xFF
                at++
                v = v or ((c and 0x7F).toLong() shl shift)
                if (c and 0x80 == 0) return v
                shift += 7
            }
            return null
        }
        while (at < len) {
            val id = read(at, 1)[0].toInt()
            at++
            val size = u32() ?: break
            if (size > len - at) break
            val end = at + size
            if (id == 0) {
                val n = u32()
                if (n != null && n <= end - at && n <= 256) {
                    val name = String(read(at, n), Charsets.UTF_8)
                    at += n
                    val start = at
                    if (name.startsWith(SECTION) && end - start <= MAX_IMAGE_BYTES) found(name) { read(start, end - start) }
                }
            }
            at = end
        }
    }

    /** the file's skin; null when it has none (no rfm1.skin section, or no image and no colours) */
    fun read(f: File, id: String, decoder: SkinDecoder): Skin? = read(sections(f), id, decoder)

    fun read(b: ByteArray, id: String, decoder: SkinDecoder): Skin? = read(sections(b), id, decoder)

    private fun read(s: Map<String, ByteArray>, id: String, decoder: SkinDecoder): Skin? {
        val j = s[SECTION] ?: return null
        val o = runCatching { JSONObject(String(j, Charsets.UTF_8)) }.getOrNull() ?: return null
        val v = o.opt("skin")
        if (v !is Number || v.toDouble() != 1.0) return null
        val p = s[BACKGROUND]?.let { decode(it, decoder) }
        val l = s[BACKGROUND_LANDSCAPE]?.let { decode(it, decoder) }
        val theme = o.optJSONObject("theme")?.let { t ->
            val m = HashMap<String, String>()
            for (k in t.keys()) m[k] = t.get(k) as? String ?: return@let null      // (iOS: [String: String] or none)
            theme(m)
        }
        if (p == null && l == null && theme == null) return null
        return Skin(id, p, l, (o.opt("dim") as? Number)?.toDouble() ?: 0.0, theme)
    }

    /** every role given: those; else the five a user picks (body, key, knob, led, bezel), the rest derived */
    fun theme(t: Map<String, String>): Theme? {
        Theme.of(NAME) { t[it] }?.let { return it }
        fun c(k: String) = t[k]?.let { Rgb.hex(it) }
        val choice = Theme.Choice(c("body") ?: return null, c("key") ?: return null, c("knob") ?: return null,
                                  c("led") ?: return null, c("bezel") ?: return null)
        return Theme.derived(choice, NAME)
    }

    const val NAME = "Firmware skin"

    /** a PNG or JPEG, scaled down to MAX_PIXELS on its longer side; null when over [maxBytes] or not an image */
    fun decode(d: ByteArray, decoder: SkinDecoder, maxBytes: Int = MAX_IMAGE_BYTES): SkinImage? =
        if (d.size > maxBytes) null else runCatching { decoder.decode(d, MAX_PIXELS) }.getOrNull()
}

/**
 * The user's background and the skin settings (SharedPreferences, like ThemeStore). [fileFor]: an imported
 * firmware's file by SHA-256. [background] runs file reads and decodes off the main thread; [post] brings their
 * results back to it (tests: both run at once).
 */
class SkinStore(
    private val prefs: SharedPreferences?,
    private val dir: File?,
    private val decoder: SkinDecoder,
    var fileFor: (String) -> String? = { null },
    private val background: (() -> Unit) -> Unit = { it() },
    private val post: (() -> Unit) -> Unit = { it() },
) {
    var userImage by mutableStateOf<SkinImage?>(null)
        private set
    /** changes with the image (the skin's id) */
    var stamp by mutableIntStateOf(0)
        private set
    private var dimState by mutableDoubleStateOf(prefs?.getFloat(KEY_DIM, DEFAULT_DIM.toFloat())?.toDouble() ?: DEFAULT_DIM)
    var dim: Double
        get() = dimState
        set(v) {
            val d = v.coerceIn(0.0, MAX_DIM)
            if (d != dimState) {
                dimState = d
                prefs?.edit()?.putFloat(KEY_DIM, d.toFloat())?.apply()
            }
        }
    private var firmwareState by mutableStateOf(prefs?.getBoolean(KEY_FIRMWARE, true) ?: true)
    var firmwareSkins: Boolean
        get() = firmwareState
        set(v) {
            if (v != firmwareState) {
                firmwareState = v
                prefs?.edit()?.putBoolean(KEY_FIRMWARE, v)?.apply()
            }
        }
    private val cache = HashMap<String, Skin?>()
    private val loading = HashSet<String>()
    private var loaded by mutableIntStateOf(0)          // (a firmware's skin came in: readers look again)

    init {
        load()
    }

    val imageFile: File? get() = dir?.let { File(it, "background.jpg") }

    val hasImage: Boolean get() = userImage != null

    /** the user's image, scaled down and stored; false if it isn't an image. Blocks: call it off the main thread */
    fun setImage(data: ByteArray): Boolean {
        val img = SkinFile.decode(data, decoder, maxBytes = Int.MAX_VALUE) ?: return false
        val jpeg = runCatching { decoder.jpeg(img) }.getOrNull() ?: return false
        val f = imageFile ?: return false
        val ok = runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeBytes(jpeg)
            if (!tmp.renameTo(f)) throw java.io.IOException("rename")
        }.isSuccess
        if (!ok) return false
        val s = (prefs?.getInt(KEY_STAMP, stamp) ?: stamp) + 1
        prefs?.edit()?.putInt(KEY_STAMP, s)?.apply()
        post {
            userImage = img
            stamp = s
        }
        return true
    }

    fun removeImage() {
        imageFile?.delete()
        val s = stamp + 1
        prefs?.edit()?.putInt(KEY_STAMP, s)?.apply()
        userImage = null
        stamp = s
    }

    /** reads the stored image (off the main thread) and settings again */
    fun load() {
        val s = prefs?.getInt(KEY_STAMP, 0) ?: 0
        if (s != stamp || userImage == null) {
            val f = imageFile
            background {
                val img = f?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
                    ?.let { SkinFile.decode(it, decoder, maxBytes = Int.MAX_VALUE) }
                post {
                    userImage = img
                    stamp = s
                }
            }
        }
        prefs?.let {
            dim = it.getFloat(KEY_DIM, DEFAULT_DIM.toFloat()).toDouble()
            firmwareSkins = it.getBoolean(KEY_FIRMWARE, true)
        }
    }

    /** a firmware's own skin (read once per file, on [background]; null until it is read); key: the catalog key
     *  ("rfm1:<sha256>"), built-in cores have none */
    fun firmwareSkin(key: String?): Skin? {
        if (key == null || !key.startsWith(KEY_PREFIX)) return null
        if (loaded < 0) return null                         // (read: composition looks again when one comes in)
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
            if (!loading.add(key)) return null
        }
        val sha = key.removePrefix(KEY_PREFIX)
        background {
            val s = runCatching { fileFor(sha)?.let { SkinFile.read(File(it), key, decoder) } }.getOrNull()
            post {
                synchronized(cache) {
                    cache[key] = s
                    loading.remove(key)
                }
                loaded++
            }
        }
        return synchronized(cache) { cache[key] }
    }

    /** what the faceplate shows for the firmware with this catalog key (null: no firmware playing in the app) */
    fun skin(key: String?): Skin? {
        if (firmwareSkins) firmwareSkin(key)?.let { return it }
        val img = userImage ?: return null
        return Skin("user:$stamp", img, dim = dim)
    }

    companion object {
        const val MAX_DIM = 0.8
        const val DEFAULT_DIM = 0.25
        const val KEY_DIM = "rfm1.skin.dim"
        const val KEY_FIRMWARE = "rfm1.skin.firmware"
        const val KEY_STAMP = "rfm1.skin.stamp"
        private const val KEY_PREFIX = "rfm1:"           // (FirmwareLibrary.KEY_PREFIX)
    }
}
