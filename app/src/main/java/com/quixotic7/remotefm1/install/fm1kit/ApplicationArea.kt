// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Package/ApplicationArea.swift (Copyright (c) 2026 Drew
// Robinson, MIT licence, LICENSES/FM1Kit-MIT.txt), whose layout and cipher follow M-Flasher's formats.rs
// (https://github.com/jvitkauskas/m-flasher):
//
// MIT License, Copyright (c) 2026 Julius Vitkauskas
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software
// and associated documentation files (the "Software"), to deal in the Software without
// restriction, including without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
// Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or
// substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
// BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
// DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
//
// Kotlin port for RemoteFM1 Android: only what refusesV15OverOtherBootloaders needs (the table and app.bin).
package com.quixotic7.remotefm1.install.fm1kit

/**
 * The application area of FM-1 flash, from 0x4000: a table of files (such as app.bin) and regions, then the files,
 * enciphered in 32-byte blocks keyed by the chip key and the block's offset.
 */
internal class ApplicationArea(val flash: ByteArray, val chipKey: Int) {
    class Entry(val dataCRC: Int, val address: Int, val length: Int, val attribute: Int, val name: String)

    fun table(): List<Entry> {
        val out = mutableListOf<Entry>()
        for (index in 0 until 16) {
            val raw = bytes(32 * index, 32)
            if (raw.size != 32 || FirmwareContainer.crc16(raw, 2, 32) != FirmwareContainer.integer(raw, 0, 2)) break
            var end = 16
            while (end < 32 && raw[end].toInt() != 0) end++
            out.add(Entry(FirmwareContainer.integer(raw, 2, 2), FirmwareContainer.integer(raw, 4, 4),
                          FirmwareContainer.integer(raw, 8, 4), raw.u(12), String(raw, 16, end - 16, Charsets.US_ASCII)))
        }
        return out
    }

    /** app.bin deciphered, if the table names one inside the flash */
    fun code(): ByteArray? {
        val app = table().firstOrNull { it.name == "app.bin" } ?: return null
        if (START + app.address + app.length > flash.size) return null
        return bytes(app.address, app.length)
    }

    /** bytes of the app area from [offset] within it, deciphered */
    fun bytes(offset: Int, count: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(count + 64)
        var block = offset - offset % 32
        while (block < offset + count && START + block + 32 <= flash.size) {
            val start = START + block
            out.write(FirmwareContainer.decipher(flash, start, start + 32, chipKey xor ((block ushr 2) and 0xFFFF)))
            block += 32
        }
        val plain = out.toByteArray()
        val skip = offset % 32
        if (skip >= plain.size) return ByteArray(0)
        return plain.copyOfRange(skip, minOf(plain.size, skip + count))
    }

    companion object {
        const val START = 0x4000
        const val FM1_CHIP_KEY = 0x980F
    }
}

private val feluccaLoaderMarker = "FELUCCA-LOADER-1".toByteArray(Charsets.US_ASCII)
private val bootloaderCheckMarker = "AC79BLID".toByteArray(Charsets.US_ASCII)
private val v15FlashImageSHA256 =
    FM1BuildID.knownHex("c1068a403c04a6378a626afb66cc217f72d13f10aa200e983b7a9817cc1dac03").bytes

private fun ByteArray.containsBytes(p: ByteArray): Boolean {
    if (p.isEmpty()) return true
    outer@ for (i in 0..size - p.size) {
        for (k in p.indices) if (this[i + k] != p[k]) continue@outer
        return true
    }
    return false
}

private val FwscPackage.firmwareCode: ByteArray?
    get() = flashImage?.let { ApplicationArea(it, ApplicationArea.FM1_CHIP_KEY).code() }

/** the firmware uses Felucca's platform's update code (AMB-1, HYP-1, DUB-1, Estampie, LOF-1, LOOM-8, Felucca) */
val FwscPackage.usesFeluccaUpdateCode: Boolean get() = firmwareCode?.containsBytes(feluccaLoaderMarker) ?: false

/**
 * The firmware, once running, refuses V15 over USB-MIDI if the FM-1's bootloader has been changed (Felucca's original
 * update check without the fixed AC79BLID check or V15's flash SHA-256); going back then needs a computer.
 */
val FwscPackage.refusesV15OverOtherBootloaders: Boolean
    get() {
        val code = firmwareCode ?: return false
        return code.containsBytes(feluccaLoaderMarker) && !code.containsBytes(bootloaderCheckMarker) &&
            !code.containsBytes(v15FlashImageSHA256)
    }
