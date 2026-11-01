// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Package/FwscPackage.swift and FirmwareContainer.swift.
// Copyright (c) 2026 Drew Robinson, MIT licence (LICENSES/FM1Kit-MIT.txt). Kotlin port for RemoteFM1 Android;
// ranges are half-open IntRanges (a until b); SHA-256 through java.security.
package com.quixotic7.remotefm1.install.fm1kit

import java.security.MessageDigest

/** Why a file couldn't be read as an FM-1 package. */
sealed class FwscFailure : Exception() {
    data object TooShort : FwscFailure()
    data object TooLarge : FwscFailure()
    data class UnreadableProduct(val text: String) : FwscFailure()
    data class UnexpectedModel(val model: String) : FwscFailure()
    data object DamagedHeader : FwscFailure()
    data class UnexpectedChip(val chip: String) : FwscFailure()
    data class DamagedPart(val name: String) : FwscFailure()
    data class MissingPart(val part: String) : FwscFailure()

    /** for the person who chose the file */
    val description: String
        get() = when (this) {
            TooShort -> "This file is too short to be an FM-1 firmware package."
            TooLarge -> "This file is too large to be an FM-1 firmware package."
            is UnreadableProduct -> "This file doesn't carry an FM-1 product identity (it reads \"$text\")."
            is UnexpectedModel -> "This package is for a $model, not an FM-1."
            DamagedHeader -> "This package's header is damaged, so it can't be installed. Download it again."
            is UnexpectedChip -> "This package is for a $chip chip, not the FM-1's AC791N."
            is DamagedPart -> "Part of this package ($name) is damaged, so it can't be installed. Download it again."
            is MissingPart -> "This package has no usable $part, so it can't be installed."
        }

    override val message: String get() = description
}

/**
 * A .fwsc firmware package (official and custom firmwares alike). The first 20 blocks of 0x30 bytes carry 0x2F image
 * bytes and one marker byte; the markers spell the product identity (marker i is character + i + 1; 0x7D unused). The
 * logical image (what the device reads) is the file less those 20 bytes: a JieLi container whose header, table and
 * parts are all checked here, with a firmware image and an update loader for the AC791N.
 */
class FwscPackage private constructor(
    /** the file exactly as downloaded */
    val fileBytes: ByteArray,
    /** what the device reports once this is installed */
    val identity: FM1Identity,
    /** the bytes the device reads, from 0 */
    val logicalImage: ByteArray,
    internal val container: FirmwareContainer,
) {
    /** lower-case hex SHA-256 of the file */
    val sha256: String = sha256Hex(fileBytes)

    /** lower-case hex SHA-256 of the update loader it brings */
    val loaderSHA256: String = sha256Hex(logicalImage, container.loader.range.first, container.loader.range.last + 1)

    val product: String get() = identity.text

    /** brings a firmware (false: only an installer, as m-bootloader's) */
    val isFirmware: Boolean get() = container.flash != null

    /** the firmware image as stored in flash from 0; null for an installer package */
    internal val flashImage: ByteArray?
        get() = container.flash?.let { logicalImage.copyOfRange(it.range.first, it.range.last + 1) }

    /** the container's header and table, before its first part */
    internal val headerRange: IntRange
        get() = 0 until minOf(container.flash?.offset ?: container.loader.offset, container.loader.offset)

    /** the firmware image's boot area (its first 16 KB) in the logical image; null for an installer */
    internal val bootAreaRange: IntRange?
        get() = container.flash?.let { it.offset until minOf(it.offset + 0x4000, it.offset + it.size) }

    companion object {
        internal const val BLOCK_COUNT = 20
        internal const val BLOCK_SIZE = 0x30
        internal const val IMAGE_BYTES_PER_BLOCK = 0x2F
        internal const val UNUSED_MARKER = 0x7D
        internal const val MAXIMUM_FILE_LENGTH = 2_097_152
        const val EXPECTED_MODEL = "FM-1"

        /** @throws FwscFailure when the file isn't an FM-1 package */
        fun read(bytes: ByteArray): FwscPackage {
            if (bytes.size <= BLOCK_COUNT * BLOCK_SIZE) throw FwscFailure.TooShort
            if (bytes.size > MAXIMUM_FILE_LENGTH) throw FwscFailure.TooLarge
            val product = decodeProduct(bytes)
            val identity = FM1Identity.parse(product)
            if (identity == null || !product.all { it.code < 0x80 && !it.isWhitespace() }) {
                throw FwscFailure.UnreadableProduct(product)
            }
            if (identity.model != EXPECTED_MODEL) throw FwscFailure.UnexpectedModel(identity.model)
            val image = stripMarkers(bytes)
            val container = FirmwareContainer.read(image)
            return FwscPackage(bytes.copyOf(), identity, image, container)
        }

        internal fun decodeProduct(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (block in 0 until BLOCK_COUNT) {
                val marker = bytes.u(block * BLOCK_SIZE + IMAGE_BYTES_PER_BLOCK)
                if (marker != UNUSED_MARKER) sb.append(((marker - block - 1) and 0xFF).toChar())
            }
            return sb.toString()
        }

        internal fun stripMarkers(bytes: ByteArray): ByteArray {
            val out = ByteArray(bytes.size - BLOCK_COUNT)
            var n = 0
            for (block in 0 until BLOCK_COUNT) {
                System.arraycopy(bytes, block * BLOCK_SIZE, out, n, IMAGE_BYTES_PER_BLOCK)
                n += IMAGE_BYTES_PER_BLOCK
            }
            System.arraycopy(bytes, BLOCK_COUNT * BLOCK_SIZE, out, n, bytes.size - BLOCK_COUNT * BLOCK_SIZE)
            return out
        }

        /** 64 lower-case hex digits */
        fun sha256Hex(b: ByteArray, from: Int = 0, to: Int = b.size): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(b, from, to - from)
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}

/** Official M-VAVE releases, recognised by size and SHA-256 (FM1Kit doesn't include the files). */
data class OfficialFirmware(val name: String, val size: Int, val sha256: String, val buildID: FM1BuildID) {
    companion object {
        val v15 = OfficialFirmware("FM-1 V15", 699_956,
            "db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a",
            FM1BuildID.knownHex("db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a"))
        val openV12 = OfficialFirmware("FM1 Open V12", 475_700,
            "2dd4f2d2f020851f8797752371704a9a541aa747f4e20419d629de9aa2c66f43",
            FM1BuildID.knownHex("426b7fa5cf1626bceaa05e8106b9a8b0eaad7f49d8b2c157303f15be347596aa"))
        const val STOCK_LOADER_SHA256 = "ef1f2db89b011fa53e71df363f0122de0ea896618c53fb53e8da676322004541"
        val known = listOf(v15, openV12)

        fun matching(p: FwscPackage): OfficialFirmware? = known.firstOrNull { it.size == p.fileBytes.size && it.sha256 == p.sha256 }
    }
}

/**
 * The JieLi container in a package's logical image: a 64-byte header and 80-byte table entries, each enciphered
 * (decipher), with checksums of the header, the table and every part (format as kagaimiq's MIT jl-misctools).
 */
internal class FirmwareContainer private constructor(val flash: Entry?, val loader: Entry, val userData: Entry?) {
    class Entry(fields: ByteArray) {
        val type = integer(fields, 0, 2)
        val name = text(fields, 64, fields.size)
        val offset = integer(fields, 8, 4)
        val size = integer(fields, 12, 4)
        val storedSize = integer(fields, 16, 4)
        val encipheredLength = integer(fields, 24, 4)
        val checksum = integer(fields, 4, 2)
        val isEnciphered: Boolean get() = encipheredLength != 0
        val range: IntRange get() = offset until offset + size

        /** the space it takes up, cut off at the end of an image this long (half-open start, end) */
        fun stored(len: Int): Pair<Int, Int> {
            val a = offset.coerceIn(0, len)
            val b = (offset + storedSize).coerceIn(0, len)
            return a to maxOf(a, b)
        }
    }

    /** the firmware image's application area, after its boot area (empty for an installer) */
    val applicationRange: IntRange
        get() {
            val f = flash ?: return 0 until 0
            return if (f.size > BOOT_AREA_LENGTH) (f.offset + BOOT_AREA_LENGTH) until (f.offset + f.size) else f.range
        }

    companion object {
        const val HEADER_LENGTH = 64
        const val ENTRY_LENGTH = 80
        const val MAXIMUM_ENTRIES = 12
        const val CHIP = "AC791N"
        const val FLASH_TYPE = 0
        const val LOADER_TYPE = 100
        const val USER_DATA_TYPE = 50
        const val USER_DATA_NAME = "USR"
        val FLASH_SIZES = 16_384..1_044_480
        const val FLASH_SECTOR_SIZE = 4096
        val LOADER_SIZES = 1 until 32_768
        const val BOOT_AREA_LENGTH = 0x4000

        private fun overlaps(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean =
            !(b.second <= a.first || a.second <= b.first || a.first == a.second || b.first == b.second)

        fun read(image: ByteArray): FirmwareContainer {
            if (image.size < HEADER_LENGTH) throw FwscFailure.DamagedHeader
            val header = decipher(image, 0, HEADER_LENGTH)
            val count = integer(header, 8, 2)
            if (integer(header, 0, 2) != crc16(header, 2, header.size) || integer(header, 4, 4) != image.size) {
                throw FwscFailure.DamagedHeader
            }
            val chip = text(header, 16, 32)
            if (chip != CHIP) throw FwscFailure.UnexpectedChip(chip)
            val tableEnd = HEADER_LENGTH + count * ENTRY_LENGTH
            if (count !in 1..MAXIMUM_ENTRIES || tableEnd > image.size ||
                integer(header, 2, 2) != crc16(image, HEADER_LENGTH, tableEnd)) {
                throw FwscFailure.DamagedHeader
            }
            val entries = mutableListOf<Entry>()
            for (index in 0 until count) {
                val start = HEADER_LENGTH + index * ENTRY_LENGTH
                val e = Entry(decipher(image, start, start + ENTRY_LENGTH))
                // padding may run past the end of the image (Felucca 1.6.1's loader does), the part may not
                val stored = e.stored(image.size)
                if (e.offset < tableEnd || e.storedSize < e.size || e.offset + e.size > image.size ||
                    (e.isEnciphered && e.encipheredLength != e.storedSize) ||
                    entries.any { overlaps(stored, it.stored(image.size)) }) {
                    throw FwscFailure.DamagedHeader
                }
                if (!e.isEnciphered && crc16(image, e.offset, e.offset + e.size) != e.checksum) {
                    throw FwscFailure.DamagedPart(e.name)
                }
                entries.add(e)
            }
            val flashes = entries.filter { it.type == FLASH_TYPE }
            val loaders = entries.filter { it.type == LOADER_TYPE }
            // a package of nothing but its loader brings an installer, which the bootloader runs
            val isInstaller = flashes.isEmpty() && entries.size == 1 && loaders.size == 1
            val flash = flashes.firstOrNull()
            if (!(isInstaller || (flashes.size == 1 && flash != null && !flash.isEnciphered && isFlashSize(flash.size)))) {
                throw FwscFailure.MissingPart("firmware image")
            }
            val loader = loaders.firstOrNull()
            if (loaders.size != 1 || loader == null || loader.isEnciphered || loader.size !in LOADER_SIZES) {
                throw FwscFailure.MissingPart("update loader")
            }
            return FirmwareContainer(flash, loader, entries.firstOrNull { it.type == USER_DATA_TYPE && it.name == USER_DATA_NAME })
        }

        fun isFlashSize(size: Int) = size in FLASH_SIZES && size % FLASH_SECTOR_SIZE == 0

        /** CRC-16, polynomial 0x1021, from 0 */
        fun crc16(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
            var v = 0
            for (i in from until to) {
                v = v xor (b.u(i) shl 8)
                for (k in 0 until 8) v = if (v and 0x8000 == 0) (v shl 1) and 0xFFFF else ((v shl 1) xor 0x1021) and 0xFFFF
            }
            return v
        }

        /** each byte XORed with the low byte of a 16-bit key that starts at [key] and steps as the CRC-16 register */
        fun decipher(b: ByteArray, from: Int = 0, to: Int = b.size, key: Int = 0xFFFF): ByteArray {
            var k = key and 0xFFFF
            return ByteArray(to - from) { i ->
                val out = (b[from + i].toInt() xor k) and 0xFF
                k = if (k and 0x8000 == 0) (k shl 1) and 0xFFFF else ((k shl 1) xor 0x1021) and 0xFFFF
                out.toByte()
            }
        }

        fun integer(b: ByteArray, off: Int, n: Int): Int {
            var v = 0
            for (k in 0 until n) v = v or (b.u(off + k) shl (8 * k))
            return v
        }

        fun text(b: ByteArray, from: Int, to: Int): String {
            var end = from
            while (end < to && b[end].toInt() != 0) end++
            return String(b, from, end - from, Charsets.UTF_8)
        }
    }
}
