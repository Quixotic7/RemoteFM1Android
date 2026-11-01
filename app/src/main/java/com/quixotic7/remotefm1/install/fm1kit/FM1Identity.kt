// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Protocol/FM1Identity.swift and FM1Capability.swift.
// Copyright (c) 2026 Drew Robinson, MIT licence (LICENSES/FM1Kit-MIT.txt). Kotlin port for RemoteFM1 Android;
// Codable dropped (nothing stores identities here).
package com.quixotic7.remotefm1.install.fm1kit

/**
 * What a device (or a .fwsc package) says it is, e.g. FM-1_916 or ota-FM-1_009. An armed open firmware's loader
 * reports ota-FM1C_ and 16 hex digits of the build it was armed with (version 0). The number is set by whoever built
 * the firmware: many custom firmwares share one, so an identity narrows down what is installed but can't always name it.
 */
@ConsistentCopyVisibility
data class FM1Identity internal constructor(
    val text: String,
    val model: String,
    val version: Int,
    /** an armed open firmware's loader: the first 16 hex digits of the build; null otherwise */
    val armedBuildPrefix: String?,
) {
    /** the update loader runs (mid-update, or USB recovery mode) */
    val isLoader: Boolean get() = model.lowercase().startsWith(LOADER_PREFIX)

    /** M-VAVE's open firmware: FM-1_8000xxx, or FM1_COMMUNITY_n (it must be armed before an update) */
    val isOpenFirmware: Boolean get() = (model == "FM-1" && version in OPEN_VERSIONS) || model == "FM1_COMMUNITY"

    /** the model without any loader prefix, e.g. FM-1 */
    val hardwareModel: String
        get() = if (armedBuildPrefix != null) FwscPackage.EXPECTED_MODEL
        else if (isLoader) model.drop(LOADER_PREFIX.length) else model

    internal fun isLoaderFor(model: String): Boolean = isLoader && hardwareModel == model

    override fun toString(): String = text

    /** the handshake reply as the device sends it (simulated devices, tests) */
    fun handshakeReply(): ByteArray {
        val textLength = REPLY_LENGTH - FM1Message.headerLength - 1
        var body = FM1Message.header(FM1Message.identityCommand, textLength)
        body += text.toByteArray(Charsets.ISO_8859_1).take(textLength).toByteArray()
        body += ByteArray(REPLY_LENGTH - 1 - body.size)
        return FM1Message.frame(body + byteArrayOf(FM1Message.checksum(body, FM1Message.headerLength).toByte()))
    }

    companion object {
        const val LOADER_PREFIX = "ota-"
        private const val ARMED_LOADER_MODEL = "ota-FM1C"
        private const val ARMED_PREFIX_LENGTH = 16
        private val OPEN_VERSIONS = 8_000_000..8_000_999
        internal const val REPLY_LENGTH = 34

        /** M-VAVE's V15: FM-1_015 (built from its text; of("FM-1", 15) would be FM-1_15) */
        val officialV15: FM1Identity = parse("FM-1_015")!!

        private fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        /** printable ASCII ending in an underscore and digits; null otherwise */
        fun parse(text: String): FM1Identity? {
            val sep = text.lastIndexOf('_')
            if (sep <= 0) return null
            val model = text.substring(0, sep)
            val digits = text.substring(sep + 1)
            if (model.lowercase() == ARMED_LOADER_MODEL.lowercase()) {
                if (digits.length != ARMED_PREFIX_LENGTH || !digits.all { hex(it) }) return null
                return FM1Identity(text, model, 0, digits.lowercase())
            }
            if (!text.all { it.code in 0x21..0x7E } || digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
            val v = digits.toIntOrNull() ?: return null
            return FM1Identity(text, model, v, null)
        }

        fun of(model: String, version: Int): FM1Identity {
            require(model.isNotEmpty() && model.all { it.code in 0x21..0x7E }) { "An identity's model is printable ASCII without spaces." }
            require(version >= 0) { "An identity's version isn't negative." }
            return FM1Identity("${model}_$version", model, version, null)
        }

        /** the device's reply to the handshake query; null for anything else or a wrong checksum */
        fun fromHandshakeReply(sysex: ByteArray): FM1Identity? {
            val body = FM1Message.unframe(sysex) ?: return null
            if (body.size != REPLY_LENGTH || !body.startsWith(FM1Message.prefix)) return null
            if (body.u(FM1Message.Offset.command) != FM1Message.identityCommand) return null
            if (FM1Message.checksum(body, FM1Message.headerLength, REPLY_LENGTH - 1) != body.u(REPLY_LENGTH - 1)) return null
            val sb = StringBuilder()
            for (i in FM1Message.headerLength until REPLY_LENGTH - 1) {
                val c = body.u(i)
                if (c == 0) break
                if (c >= 0x80) return null
                sb.append(c.toChar())
            }
            return parse(sb.toString())
        }
    }
}

/** A firmware build's 32-byte identifier, which M-VAVE's open firmware reports and is armed with. */
class FM1BuildID internal constructor(bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == BYTE_COUNT) { "A build ID is 32 bytes." }
    }

    val hex: String get() = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    override fun equals(other: Any?) = other is FM1BuildID && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = hex

    companion object {
        const val BYTE_COUNT = 32

        /** exactly 64 hex digits (either case); null otherwise */
        fun fromHex(hex: String): FM1BuildID? {
            if (hex.length != BYTE_COUNT * 2) return null
            val b = ByteArray(BYTE_COUNT)
            for (i in 0 until BYTE_COUNT) {
                val h = Character.digit(hex[2 * i], 16)
                val l = Character.digit(hex[2 * i + 1], 16)
                if (h < 0 || l < 0) return null
                b[i] = ((h shl 4) or l).toByte()
            }
            return FM1BuildID(b)
        }

        internal fun knownHex(hex: String): FM1BuildID = fromHex(hex) ?: error("$hex isn't a build ID.")
    }
}

/**
 * What M-VAVE's open firmware reports about its update support (command 0x31); it must be armed (0x32) with the
 * package's length and build ID before the upgrade command. V15 and Felucca don't answer.
 */
data class FM1Capability(val family: Int = FM1_FAMILY, val state: Int = 0, val buildID: FM1BuildID, val errorCode: Int = 0) {
    init {
        require(family in 0..MAXIMUM_FAMILY) { "An update family is 0 to 7." }
    }

    val isReady: Boolean get() = state == 0

    fun payload(): ByteArray {
        var p = MAGIC + bytesOf(VERSION, family, state and 0xFF, LOADER_KIND)
        p += FM1Message.le(FM1Message.maximumReadLength.toLong(), 2)
        p += FM1Message.le(FLASH_LENGTH, 4)
        return p + buildID.bytes + byteArrayOf(errorCode.toByte())
    }

    companion object {
        const val FM1_FAMILY = 7
        internal const val PAYLOAD_LENGTH = 47
        internal val MAGIC = "FMC1".toByteArray(Charsets.US_ASCII)
        internal const val VERSION = 1
        internal const val MAXIMUM_FAMILY = 7
        internal const val LOADER_KIND = 1
        internal const val FLASH_LENGTH = 1_048_576L
        internal val ARM_ACCEPTED = byteArrayOf(0)

        /** the 47-byte FMC1 layout, version 1, M-VAVE's loader, 512-byte reads, 1 MB flash; null otherwise */
        fun parse(p: ByteArray): FM1Capability? {
            if (p.size != PAYLOAD_LENGTH || !p.startsWith(MAGIC) || p.u(4) != VERSION || p.u(5) > MAXIMUM_FAMILY) return null
            if (p.u(7) != LOADER_KIND) return null
            if (FM1Message.readLE(p, 8, 2) != FM1Message.maximumReadLength.toLong()) return null
            if (FM1Message.readLE(p, 10, 4) != FLASH_LENGTH) return null
            return FM1Capability(p.u(5), p.u(6), FM1BuildID(p.copyOfRange(14, 46)), p.u(46))
        }

        internal fun armPayload(family: Int, imageLength: Int, buildID: FM1BuildID): ByteArray =
            bytesOf(1, family) + FM1Message.le(imageLength.toLong(), 4) + buildID.bytes
    }
}
