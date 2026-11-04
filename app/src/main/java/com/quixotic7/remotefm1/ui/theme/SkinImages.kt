// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS ca42bbc RFMKit/Sources/RFMKit/Faceplate/Skin.swift (SkinFile.decode / jpeg, SkinBackground)
// Skins on Android (Skin.kt): images decoded once with ImageDecoder (EXIF orientation applied, scaled down to 2048 px
// while decoding, hardware bitmaps when it can), and the skin drawn behind the faceplate in a layer of its own, so the
// faceplate's redraws don't draw the image again.
package com.quixotic7.remotefm1.ui.theme

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** a decoded Bitmap */
class BitmapSkinImage(val bitmap: Bitmap) : SkinImage {
    val image: ImageBitmap = bitmap.asImageBitmap()
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

object AndroidSkinDecoder : SkinDecoder {
    override fun decode(data: ByteArray, maxPixels: Int): SkinImage? = runCatching {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data))) { d, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val m = max(w, h)
            if (m > maxPixels) {
                val s = maxPixels.toDouble() / m
                d.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }
        }
        BitmapSkinImage(bmp)
    }.getOrNull()

    override fun jpeg(img: SkinImage): ByteArray? {
        val b = (img as? BitmapSkinImage)?.bitmap ?: return null
        val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) ?: return null else b
        return try {
            ByteArrayOutputStream().use { o -> if (soft.compress(Bitmap.CompressFormat.JPEG, 85, o)) o.toByteArray() else null }
        } finally {
            if (soft !== b) soft.recycle()
        }
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "rfm1-skins").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** SkinStore's background (one thread) and main thread */
    val background: (() -> Unit) -> Unit = { f -> io.execute(f) }
    val post: (() -> Unit) -> Unit = { f -> if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post(f) }
}

/** the skin filling its frame: the image (cropped to fill), the faceplate's colour over it by the dim */
@Composable
fun SkinBackground(skin: Skin, tint: Color, wide: Boolean, modifier: Modifier = Modifier) {
    val img = (skin.image(wide) as? BitmapSkinImage)?.image ?: return
    Box(modifier.fillMaxSize().graphicsLayer().testTag("skin")) {
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (skin.dim > 0) Box(Modifier.fillMaxSize().background(tint.copy(alpha = skin.dim.toFloat())))
    }
}
