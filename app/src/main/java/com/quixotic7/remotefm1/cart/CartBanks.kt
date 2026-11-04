// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Cart/CartBanks.swift; changes: Kotlin, ByteArray.
// RemoteFM1 0.3.1's user-bank messages (docs/CART-UPLOAD.md "0.3.1: list, replace, delete, confirm" and PROTOCOL.md
// section 8): SA_INFO 0x0B requests and SA_INFO_R 0x4B replies, as RemoteFM1 SysEx F0 7D 52 46 cmd <body 7-in-8> F7
// (remote_proto.h's rf_pack7: groups of an MSB byte, bit k for data byte k, then up to 7 data bytes), little endian.
// The vendored libremotefm1 has no SA_INFO, so the small codec is here. FakeFM1 answers as 0.3.1 does (the tests, the
// fake link, demos), or not at all as 0.3.0.
package com.quixotic7.remotefm1.cart

object SAInfo {
    const val REQUEST = 0x0B
    const val REPLY = 0x4B
    private val head = byteArrayOf(0xF0.toByte(), 0x7D, 0x52, 0x46)

    enum class Op(val code: Int) { BANKS(0), NAME(1), TARGET(2), DELETE(3), STORED(4) }

    fun pack7(b: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(b.size + b.size / 7 + 1)
        var i = 0
        while (i < b.size) {
            val g = minOf(7, b.size - i)
            var msb = 0
            for (k in 0 until g) msb = msb or (((b[i + k].toInt() and 0xFF) shr 7) shl k)
            o.write(msb)
            for (k in 0 until g) o.write(b[i + k].toInt() and 0x7F)
            i += g
        }
        return o.toByteArray()
    }

    fun unpack7(p: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(p.size)
        var msb = 0
        var g = 0
        for (x0 in p) {
            val x = x0.toInt() and 0xFF
            if (g == 0) { msb = x; g = 1; continue }
            o.write((x and 0x7F) or (((msb shr (g - 1)) and 1) shl 7))
            g = if (g == 7) 0 else g + 1
        }
        return o.toByteArray()
    }

    fun encode(cmd: Int, body: ByteArray): ByteArray = head + byteArrayOf(cmd.toByte()) + pack7(body) + byteArrayOf(0xF7.toByte())

    /** a whole SysEx -> (cmd, body); null if it isn't RemoteFM1's */
    fun decode(m: ByteArray): Pair<Int, ByteArray>? {
        if (m.size < 6 || !(0 until 4).all { m[it] == head[it] } || (m[m.size - 1].toInt() and 0xFF) != 0xF7) return null
        return (m[4].toInt() and 0xFF) to unpack7(m.copyOfRange(5, m.size - 1))
    }

    /** what a status byte means, in plain words (null: done) */
    fun words(status: Int): String? = when (status) {
        0 -> null
        1 -> "The FM-1 refused the cartridge (its checksum, header or length)."
        2 -> "The FM-1 refused the request (no bank in that slot)."
        3 -> "The FM-1 was busy writing. Try again in a moment."
        4 -> "The FM-1 is in a session with a host. End it and try again."
        5 -> "The FM-1 couldn't write its flash. Its banks are as they were."
        else -> "The FM-1 answered status $status."
    }
}

/** one stored bank (BANKS) */
data class UserBank(val slot: Int, val arrival: Long, val crc: Long, val name: String) {
    val id: Int get() = slot
    /** as the unit shows it */
    val label: String get() = "USER${slot + 1}"
}

/** the BANKS reply */
data class BankList(val max: Int = 16, val busy: Boolean = false, val next: Int, val banks: List<UserBank>) {
    val body: ByteArray
        get() {
            val o = java.io.ByteArrayOutputStream()
            o.write(byteArrayOf(0, max.toByte(), banks.size.toByte(), (if (busy) 1 else 0).toByte(), next.toByte(), 16))
            for (k in banks) {
                o.write(k.slot)
                for (v in listOf(k.arrival, k.crc)) for (i in 0 until 4) o.write(((v shr (8 * i)) and 0xFF).toInt())
                o.write((k.name + " ".repeat(10)).toByteArray(Charsets.UTF_8).copyOf(10))
            }
            return o.toByteArray()
        }

    companion object {
        internal fun le32(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        internal fun name(b: ByteArray, from: Int, to: Int): String =
            String(CharArray(to - from) { val c = b[from + it].toInt() and 0xFF; if (c in 0x20..0x7E) c.toChar() else ' ' }).trim()

        /** a BANKS reply body (starting with op 0); null if it isn't one */
        fun of(b: ByteArray): BankList? {
            if (b.size < 6 || b[0].toInt() != 0) return null
            val count = b[2].toInt() and 0xFF
            if (b.size < 6 + count * 19) return null
            val banks = (0 until count).map { i ->
                val o = 6 + i * 19
                UserBank(b[o].toInt() and 0xFF, le32(b, o + 1), le32(b, o + 5), name(b, o + 9, o + 19))
            }
            return BankList(b[1].toInt() and 0xFF, b[3].toInt() != 0, b[4].toInt() and 0xFF, banks)
        }
    }
}

/** a pretend FM-1 for FakeCartTransport: 0.3.1 (16 slots, the replies, STORED after each dump) or, silent, 0.3.0 */
class FakeFM1(@Volatile var silent: Boolean = false, banks: List<UserBank> = emptyList()) {
    private val lock = Any()
    private val slots = HashMap<Int, UserBank>()
    private var arrivals = 0L
    private var target: Int? = null
    private var partial = java.io.ByteArrayOutputStream()
    /** a host session (TARGET, DELETE and dumps answer 4) */
    @Volatile var inSession = false
    /** what STORED says for the next dump (0 normally; 1, 3 or 5 to test) */
    @Volatile var nextDumpStatus = 0
    /** as the unit: a NAME is written a moment after its reply; BANKS answers busy with the old name this many times */
    @Volatile var nameLag = 0
    private var pendingName: Triple<Int, String, Int>? = null

    init {
        for (b in banks) {
            slots[b.slot] = b
            arrivals = maxOf(arrivals, b.arrival)
        }
    }

    /** (tests) the slots now */
    val slotsNow: Map<Int, UserBank> get() = synchronized(lock) { HashMap(slots) }

    val list: BankList
        get() = synchronized(lock) {
            val free = (0 until 16).firstOrNull { slots[it] == null }
            val oldest = slots.values.minByOrNull { it.arrival }?.slot ?: 0
            BankList(next = free ?: oldest, banks = slots.values.sortedBy { it.slot })
        }

    /** bytes as sent (whole messages or Bluetooth parts) -> the messages it sends back */
    fun receive(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        synchronized(lock) {
            for (x in bytes) {
                if ((x.toInt() and 0xFF) == 0xF0) partial = java.io.ByteArrayOutputStream()
                partial.write(x.toInt())
                if ((x.toInt() and 0xFF) == 0xF7) {
                    out += message(partial.toByteArray())
                    partial = java.io.ByteArrayOutputStream()
                }
            }
        }
        return if (silent) emptyList() else out
    }

    private fun reply(vararg b: Int) = SAInfo.encode(SAInfo.REPLY, ByteArray(b.size) { b[it].toByte() })
    private fun reply(b: ByteArray) = SAInfo.encode(SAInfo.REPLY, b)

    // (lock held)
    private fun message(m: ByteArray): List<ByteArray> {
        if (m.size == 4104 && (m[1].toInt() and 0xFF) == 0x43 && m[3].toInt() == 0x09) {
            val slot = target ?: list.next
            target = null
            if (inSession) return listOf(reply(4, slot, 4))
            if (nextDumpStatus != 0) {
                val st = nextDumpStatus
                nextDumpStatus = 0
                return listOf(reply(4, slot, st))
            }
            val name = BankList.name(m, 6 + 118, 6 + 128)
            arrivals++
            slots[slot] = UserBank(slot, arrivals, crc32(m, 6, 4102), name)
            return listOf(reply(4, slot, 0))
        }
        val (cmd, b) = SAInfo.decode(m) ?: return emptyList()
        if (cmd != SAInfo.REQUEST || b.isEmpty()) return emptyList()
        val op = b[0].toInt() and 0xFF
        val slot = if (b.size > 1) b[1].toInt() and 0xFF else 0
        val has = slots[slot] != null
        return when (op) {
            0 -> {
                pendingName?.let { p ->
                    if (p.third > 0) {
                        pendingName = p.copy(third = p.third - 1)
                        return listOf(reply(list.copy(busy = true).body))
                    }
                    slots[p.first]?.let { slots[p.first] = it.copy(name = p.second) }
                    pendingName = null
                }
                listOf(reply(list.body))
            }
            1 -> {
                if (!has || b.size <= 2 || b.size > 12) return listOf(reply(1, slot, 2))
                if (pendingName != null) return listOf(reply(1, slot, 3))
                pendingName = Triple(slot, BankList.name(b, 2, b.size), nameLag)
                listOf(reply(1, slot, 0))
            }
            2 -> {
                if (slot >= 16) return listOf(reply(2, slot, 2))
                if (inSession) return listOf(reply(2, slot, 4))
                target = slot
                listOf(reply(2, slot, 0))
            }
            3 -> {
                if (!has) return listOf(reply(3, slot, 2))
                if (inSession) return listOf(reply(3, slot, 4))
                slots.remove(slot)
                listOf(reply(3, slot, 0))
            }
            else -> listOf(reply(op, slot, 2))
        }
    }
}

/** zlib's CRC-32 (BANKS's crc of the 4,096 voice bytes) */
fun crc32(b: ByteArray, from: Int = 0, to: Int = b.size): Long {
    val c = java.util.zip.CRC32()
    c.update(b, from, to - from)
    return c.value
}
