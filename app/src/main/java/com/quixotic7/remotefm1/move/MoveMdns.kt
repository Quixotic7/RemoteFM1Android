// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// move.local on Android: the system resolver may not answer .local names, so the app asks the network itself. One
// mDNS question for the name's A record, sent to 224.0.0.251:5353 from an ordinary port (RFC 6762 section 6.7, a
// "legacy unicast" query: the Move's responder answers that port directly, with the question's id), under a
// WifiManager.MulticastLock (MoveLink holds it). The packet and its answer are plain functions for the JVM tests.
package com.quixotic7.remotefm1.move

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object MoveMdns {
    const val GROUP = "224.0.0.251"
    const val PORT = 5353

    /** the question: id, one A record for [name] (class IN), recursion not asked */
    fun query(name: String, id: Int): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v shr 8 and 255); o.write(v and 255) }
        u16(id); u16(0); u16(1); u16(0); u16(0); u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            require(b.size in 1..63) { "bad name" }
            o.write(b.size); o.write(b)
        }
        o.write(0)
        u16(1); u16(1)                              // A, IN
        return o.toByteArray()
    }

    /** the IPv4 address an answer gives for [name] (any record of the packet: answers or additionals), null none */
    fun answer(p: ByteArray, n: Int, name: String): String? {
        if (n < 12) return null
        fun u16(i: Int) = (p[i].toInt() and 255) shl 8 or (p[i + 1].toInt() and 255)
        if (u16(2) and 0x8000 == 0) return null      // (not a response)
        val qd = u16(4); val records = u16(6) + u16(8) + u16(10)
        var i = 12
        // a name at i (with pointers): the name and where the record goes on
        fun readName(at: Int): Pair<String, Int>? {
            val parts = ArrayList<String>()
            var j = at
            var end = -1
            var hops = 0
            while (true) {
                if (j >= n) return null
                val len = p[j].toInt() and 255
                when {
                    len == 0 -> { if (end < 0) end = j + 1; break }
                    len and 0xC0 == 0xC0 -> {
                        if (j + 1 >= n || ++hops > 16) return null
                        if (end < 0) end = j + 2
                        j = (len and 0x3F) shl 8 or (p[j + 1].toInt() and 255)
                    }
                    else -> {
                        if (j + 1 + len > n) return null
                        parts += String(p, j + 1, len, Charsets.US_ASCII)
                        j += 1 + len
                    }
                }
            }
            return parts.joinToString(".") to end
        }
        repeat(qd) {
            val (_, e) = readName(i) ?: return null
            i = e + 4
        }
        val want = name.trimEnd('.').lowercase()
        repeat(records) {
            val (nm, e) = readName(i) ?: return null
            if (e + 10 > n) return null
            val type = u16(e); val len = u16(e + 8)
            val data = e + 10
            if (data + len > n) return null
            if (type == 1 && len == 4 && nm.lowercase() == want)
                return (0 until 4).joinToString(".") { (p[data + it].toInt() and 255).toString() }
            i = data + len
        }
        return null
    }

    /** asks the network for [name]'s address: [tries] questions, [waitMs] each; null when nobody answered */
    fun resolve(name: String, tries: Int = 2, waitMs: Int = 1200): String? {
        val group = InetAddress.getByName(GROUP)
        DatagramSocket().use { s ->
            s.soTimeout = waitMs
            val buf = ByteArray(1500)
            repeat(tries) { t ->
                val id = (System.nanoTime().toInt() xor t) and 0xFFFF
                val q = query(name, id)
                s.send(DatagramPacket(q, q.size, group, PORT))
                val until = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < until) {
                    val pk = DatagramPacket(buf, buf.size)
                    try { s.receive(pk) } catch (_: SocketTimeoutException) { break }
                    answer(buf, pk.length, name)?.let { return it }
                }
            }
        }
        return null
    }
}
