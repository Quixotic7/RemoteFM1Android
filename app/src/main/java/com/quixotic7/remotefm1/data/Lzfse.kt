// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
//
// The states' flash compression: LZFSE, as iOS's NSData.compressed(using: .lzfse) (Apple's Compression framework).
// Lzfse is Apple's open-source lzfse in librfmlzfse.so (native/lzfse); it writes and reads the same stream, so the
// library's <uuid>.flash files and the .rfm1state files move between the two apps. FlashCodec lets the JVM tests use a
// host build of the same library (or a stand-in where none can be built).
package com.quixotic7.remotefm1.data

interface FlashCodec {
    /** [raw] compressed; null when it can't be (the caller then stores it as it is, as iOS does) */
    fun compress(raw: ByteArray): ByteArray?

    /** [z] decompressed, expecting [size] bytes; null when it can't be read or isn't [size] bytes */
    fun decompress(z: ByteArray, size: Int): ByteArray?
}

object Lzfse : FlashCodec {
    /** a flash larger than this is refused (a damaged or hostile file asking for a huge buffer) */
    const val MAX_FLASH = 64 shl 20

    @Volatile private var loaded: Boolean? = null

    /** librfmlzfse loaded (the app's; once) */
    val available: Boolean
        get() = loaded ?: synchronized(this) {
            loaded ?: runCatching { System.loadLibrary("rfmlzfse") }.isSuccess.also { loaded = it }
        }

    /** (the JVM tests) the library loaded from a host build at [path] */
    fun loadFrom(path: String): Boolean = synchronized(this) {
        runCatching { System.load(path) }.isSuccess.also { if (it) loaded = true }
    }

    override fun compress(raw: ByteArray): ByteArray? =
        if (raw.isEmpty() || !available) null else runCatching { nEncode(raw) }.getOrNull()

    override fun decompress(z: ByteArray, size: Int): ByteArray? {
        if (z.isEmpty() || size <= 0 || size > MAX_FLASH || !available) return null
        val out = runCatching { nDecode(z, size + 1) }.getOrNull() ?: return null   // (one more: a longer one shows)
        return if (out.size == size) out else null
    }

    @JvmStatic private external fun nEncode(src: ByteArray): ByteArray?
    @JvmStatic private external fun nDecode(src: ByteArray, cap: Int): ByteArray?
}
