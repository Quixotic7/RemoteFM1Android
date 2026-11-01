// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Protocol/FM1Message.swift and SevenBit.swift.
// Copyright (c) 2026 Drew Robinson, MIT licence (LICENSES/FM1Kit-MIT.txt). Kotlin port for RemoteFM1 Android:
// bytes are ByteArray, addresses Long; otherwise as FM1Kit.
package com.quixotic7.remotefm1.install.fm1kit

/** byte i as 0..255 */
internal fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

internal fun bytesOf(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

/**
 * The FM-1 update protocol's 8-to-7-bit packing: the whole message is one bit stream, least significant bit first, cut
 * into 7-bit SysEx data bytes (not the common "MSB byte + 7 bytes" scheme).
 */
object SevenBit {
    fun pack(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): ByteArray {
        val out = ByteArray(((to - from) * 8 + 6) / 7)
        var acc = 0L
        var bits = 0
        var n = 0
        for (i in from until to) {
            acc = acc or ((bytes[i].toLong() and 0xFF) shl bits)
            bits += 8
            while (bits >= 7) {
                out[n++] = (acc and 0x7F).toByte()
                acc = acc ushr 7
                bits -= 7
            }
        }
        if (bits > 0) out[n++] = (acc and 0x7F).toByte()
        return if (n == out.size) out else out.copyOf(n)
    }

    /** any top bit is ignored; the padding bits that end the last byte are dropped */
    fun unpack(septets: ByteArray, from: Int = 0, to: Int = septets.size): ByteArray {
        val out = ByteArray((to - from) * 7 / 8)
        var acc = 0L
        var bits = 0
        var n = 0
        for (i in from until to) {
            acc = acc or ((septets[i].toLong() and 0x7F) shl bits)
            bits += 7
            while (bits >= 8) {
                out[n++] = (acc and 0xFF).toByte()
                acc = acc ushr 8
                bits -= 8
            }
        }
        return if (n == out.size) out else out.copyOf(n)
    }
}

/**
 * Messages of the FM-1 USB-MIDI update protocol (AL-255/FM-1-RE docs/io/11-ota-protocol.md). Every update message is
 * SysEx; the device drives the transfer with read requests for ranges of the logical image. Unpacked:
 *
 *     00 59 cmd | body_length:u24le | flash_type:u8 | address:u32le | length:u24le | data | checksum
 */
object FM1Message {
    val handshakeQuery = bytesOf(0xF0, 0x00, 0x32, 0x45, 0x00, 0x00, 0x00, 0x40, 0x7F, 0xF7)
    /** sent twice: to the running firmware (staging), then to the update loader (writing) */
    val upgradeCommand = bytesOf(0xF0, 0x22, 0x24, 0x35, 0x7F, 0xF7)
    const val stageFinishAddress = 0xE000_0000L
    const val writeFinishAddress = 0xF000_0000L
    val finishPayload: ByteArray = "success".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    const val maximumReadLength = 512

    internal val prefix = bytesOf(0x00, 0x59)
    internal const val readCommand = 0x30
    internal const val identityCommand = 0x11
    internal const val capabilityCommand = 0x31
    internal const val armCommand = 0x32
    internal const val fixedFieldsLength = 8
    internal const val headerLength = 6

    internal object Offset {
        const val command = 2
        const val bodyLength = 3
        const val flashType = 6
        const val address = 7
        const val length = 11
        const val data = 14
    }

    internal fun frame(unpacked: ByteArray): ByteArray = byteArrayOf(0xF0.toByte()) + SevenBit.pack(unpacked) +
        byteArrayOf(0xF7.toByte())

    /** strips F0 .. F7 and unpacks; null when not a complete SysEx message */
    internal fun unframe(sysex: ByteArray): ByteArray? {
        if (sysex.size < 2 || sysex.u(0) != 0xF0 || sysex.u(sysex.size - 1) != 0xF7) return null
        return SevenBit.unpack(sysex, 1, sysex.size - 1)
    }

    /** the complement of the bytes' sum, modulo 256 */
    internal fun checksum(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var s = 0
        for (i in from until to) s += b.u(i)
        return s.inv() and 0xFF
    }

    internal fun command(command: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        frame(header(command, payload.size) + payload + byteArrayOf(checksum(payload).toByte()))

    /** (command, payload), or null when not a complete, correctly checksummed message */
    internal fun parseCommand(sysex: ByteArray): Pair<Int, ByteArray>? {
        val body = unframe(sysex) ?: return null
        if (body.size <= headerLength || !body.startsWith(prefix)) return null
        if (readLE(body, Offset.bodyLength, 3).toInt() != body.size - headerLength - 1) return null
        if (checksum(body, headerLength, body.size - 1) != body.u(body.size - 1)) return null
        return body.u(Offset.command) to body.copyOfRange(headerLength, body.size - 1)
    }

    internal fun header(command: Int, bodyLength: Int): ByteArray =
        prefix + byteArrayOf(command.toByte()) + le(bodyLength.toLong(), 3)

    internal fun le(value: Long, n: Int): ByteArray = ByteArray(n) { ((value ushr (8 * it)) and 0xFF).toByte() }

    internal fun readLE(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = v or ((b[off + k].toLong() and 0xFF) shl (8 * k))
        return v
    }
}

internal fun ByteArray.startsWith(p: ByteArray): Boolean {
    if (size < p.size) return false
    for (i in p.indices) if (this[i] != p[i]) return false
    return true
}

/** A read request from the device: [length] bytes of the image at [address]. */
data class FM1ReadRequest(val flashType: Int = 0, val address: Long, val length: Int) {
    val isFinish: Boolean get() = address == FM1Message.stageFinishAddress || address == FM1Message.writeFinishAddress

    /** as the device sends it (the simulated device, tests) */
    fun encoded(): ByteArray = FM1Message.frame(body(flashType, address, length, ByteArray(0)))

    /** the host's answer, carrying [data] */
    fun response(data: ByteArray): ByteArray = FM1Message.frame(body(flashType, address, data.size, data))

    fun finishResponse(): ByteArray = response(FM1Message.finishPayload)

    companion object {
        /** a request from the device; null for anything else or a wrong checksum */
        fun parse(sysex: ByteArray): FM1ReadRequest? {
            val o = FM1Message.Offset
            val body = FM1Message.unframe(sysex) ?: return null
            if (body.size != FM1Message.headerLength + FM1Message.fixedFieldsLength + 1) return null
            if (!body.startsWith(FM1Message.prefix) || body.u(o.command) != FM1Message.readCommand) return null
            if (FM1Message.checksum(body, o.flashType, o.data) != body.u(o.data)) return null
            return FM1ReadRequest(body.u(o.flashType), FM1Message.readLE(body, o.address, 4),
                                  FM1Message.readLE(body, o.length, 3).toInt())
        }

        /** a host's answer, as a device reads it: the request it answers and its data */
        fun parseResponse(sysex: ByteArray): Pair<FM1ReadRequest, ByteArray>? {
            val o = FM1Message.Offset
            val body = FM1Message.unframe(sysex) ?: return null
            if (body.size < FM1Message.headerLength + FM1Message.fixedFieldsLength + 1) return null
            if (!body.startsWith(FM1Message.prefix) || body.u(o.command) != FM1Message.readCommand) return null
            val bodyLength = FM1Message.readLE(body, o.bodyLength, 3).toInt()
            val length = FM1Message.readLE(body, o.length, 3).toInt()
            val ci = o.data + length
            if (bodyLength != length + FM1Message.fixedFieldsLength || body.size != ci + 1) return null
            if (FM1Message.checksum(body, o.flashType, ci) != body.u(ci)) return null
            val r = FM1ReadRequest(body.u(o.flashType), FM1Message.readLE(body, o.address, 4), length)
            return r to body.copyOfRange(o.data, ci)
        }

        private fun body(flashType: Int, address: Long, length: Int, data: ByteArray): ByteArray {
            var b = FM1Message.header(FM1Message.readCommand, data.size + FM1Message.fixedFieldsLength)
            b += byteArrayOf(flashType.toByte())
            b += FM1Message.le(address, 4)
            b += FM1Message.le(length.toLong(), 3)
            b += data
            return b + byteArrayOf(FM1Message.checksum(b, FM1Message.Offset.flashType).toByte())
        }
    }
}
