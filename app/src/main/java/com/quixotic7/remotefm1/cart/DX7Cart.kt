// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Cart/DX7Cart.swift; changes: Kotlin, ByteArray.
// A DX7 32-voice cartridge (a .syx bulk dump) for RemoteFM1 0.3.0's user banks (RemoteFM1's docs/CART-UPLOAD.md): one
// SysEx of exactly 4104 bytes, F0 43 0n 09 20 00, 4096 bytes of packed voices (128 per voice, each 0..0x7F; a voice's
// name is bytes 118..127), the checksum (128 - (sum & 0x7F)) & 0x7F, F7. The unit drops anything else, so a dump is
// fixed before it is sent: the checksum recomputed (many files carry a wrong one) and the channel set to 0. A file may
// hold several dumps back to back: each is its own cartridge.
package com.quixotic7.remotefm1.cart

class DX7Cart private constructor(
    /** the dump as it is sent: channel 0, the right checksum */
    val bytes: ByteArray,
    /** the 32 voice names (trailing spaces trimmed) */
    val voices: List<String>,
    /** the file's checksum was wrong (fixed here) */
    val checksumFixed: Boolean,
    /** the file's channel (fixed to 0 here) */
    val originalChannel: Int,
) {
    sealed class ParseError : Exception() {
        data object Empty : ParseError()
        data class WrongSize(val size: Int) : ParseError()
        data object NotDX7 : ParseError()
        data object NotCartridge : ParseError()
        data class BadByte(val index: Int) : ParseError()
        data object NoEnd : ParseError()

        val description: String
            get() = when (this) {
                Empty -> "The file is empty."
                is WrongSize -> "This isn't a DX7 32-voice cartridge: it is $size bytes, a cartridge is $SIZE."
                NotDX7 -> "This isn't a Yamaha DX7 SysEx file."
                NotCartridge -> "This is a DX7 file, but not a 32-voice cartridge (a single voice or another dump)."
                is BadByte -> "The cartridge is damaged: byte $index is out of range."
                NoEnd -> "The cartridge is damaged: it doesn't end with F7."
            }

        override val message: String get() = description
    }

    override fun equals(other: Any?) = other is DX7Cart && other.bytes.contentEquals(bytes) && other.voices == voices &&
        other.checksumFixed == checksumFixed && other.originalChannel == originalChannel

    override fun hashCode() = bytes.contentHashCode()

    companion object {
        const val SIZE = 4104
        const val DATA_SIZE = 4096

        /** the cartridges in a file (one or more dumps back to back) */
        fun parseFile(data: ByteArray): List<DX7Cart> {
            if (data.isEmpty()) throw ParseError.Empty
            if (data.size % SIZE != 0) throw ParseError.WrongSize(data.size)
            return (0 until data.size step SIZE).map { parse(data.copyOfRange(it, it + SIZE)) }
        }

        /** one dump of exactly 4104 bytes */
        fun parse(b: ByteArray): DX7Cart {
            if (b.size != SIZE) throw if (b.isEmpty()) ParseError.Empty else ParseError.WrongSize(b.size)
            fun u(i: Int) = b[i].toInt() and 0xFF
            if (u(0) != 0xF0 || u(1) != 0x43) throw ParseError.NotDX7
            if (u(2) and 0xF0 != 0 || u(3) != 0x09 || u(4) != 0x20 || u(5) != 0x00) throw ParseError.NotCartridge
            if (u(SIZE - 1) != 0xF7) throw ParseError.NoEnd
            for (i in 6 until 6 + DATA_SIZE) if (u(i) > 0x7F) throw ParseError.BadByte(i)
            val out = b.copyOf()
            out[2] = 0
            val sum = checksum(b, 6, 6 + DATA_SIZE)
            out[SIZE - 2] = sum.toByte()
            val names = (0 until 32).map { k ->
                val o = 6 + k * 128 + 118
                String(CharArray(10) { val c = u(o + it); if (c in 0x20..0x7E) c.toChar() else ' ' }).trim()
            }
            return DX7Cart(out, names, u(SIZE - 2) != sum, u(2))
        }

        fun checksum(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
            var s = 0
            for (i in from until to) s += b[i].toInt() and 0xFF
            return (128 - (s and 0x7F)) and 0x7F
        }
    }
}
