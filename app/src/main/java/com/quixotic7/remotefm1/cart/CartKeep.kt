// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/Cart/CartKeep.swift (bd0711a)
// The cartridges the app has sent, kept so a bank can be copied from RemoteFM1 in the app to an FM-1 (the owner,
// 2026-11-01). RemoteFM1 0.3.1 can list its banks (each with the CRC-32 of its 4,096 voice bytes) but not send one
// back, so a bank is copied from the app's own copy, found by that CRC: <folder>/<name> <crc>.syx (a .syx put there by
// hand counts too: its CRC is worked out). Android: the folder is the app's external files' Cartridges
// (Android/data/com.quixotic7.remotefm1/files/Cartridges, which a file manager or a computer over USB shows), set by
// AppCartSender; iOS: Documents/Cartridges.
package com.quixotic7.remotefm1.cart

import java.io.File

object CartKeep {
    /** where the copies go (set by the app; a temporary folder in the tests); null: nothing kept */
    @Volatile var folder: File? = null

    fun crc(c: DX7Cart): Long = crc32(c.bytes, 6, 4102)
    internal fun hex(crc: Long): String = "%08x".format(crc)

    /** a sent cartridge kept (once per CRC) */
    fun keep(c: DX7Cart, dir: File? = folder) {
        dir ?: return
        val h = hex(crc(c))
        dir.mkdirs()
        if (dir.list()?.any { it.endsWith(" $h.syx") } == true) return
        val first = (c.voices.firstOrNull() ?: "BANK").trim()
        val safe = first.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }.joinToString("").take(20)
        val f = File(dir, "${safe.ifEmpty { "BANK" }} $h.syx")
        runCatching {
            val tmp = File(dir, ".${f.name}.tmp")
            tmp.writeBytes(c.bytes)
            if (!tmp.renameTo(f)) { f.writeBytes(c.bytes); tmp.delete() }
        }
    }

    /** the kept cartridge with this CRC, null: none (by name first, then any .syx in the folder) */
    fun cart(crc: Long, dir: File? = folder): DX7Cart? {
        val names = dir?.list() ?: return null
        val h = hex(crc)
        val syx = names.filter { it.lowercase().endsWith(".syx") }.sortedByDescending { it.endsWith(" $h.syx") }
        for (n in syx) {
            val carts = runCatching { DX7Cart.parseFile(File(dir, n).readBytes()) }.getOrNull() ?: continue
            carts.firstOrNull { crc(it) == crc }?.let { return it }
        }
        return null
    }
}
