// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Move's address as typed (RemoteFM1 18c6a82 web/site/js/move-link.js normalizeUrl, hostOf): "move.local", an IPv4
// or IPv6 address, "host:port", "[v6]:port", or a ws:// / http:// URL as the web player keeps them. Android speaks Move
// Remote's plain TCP (mr_client), not its WebSocket, so only the host and the port are taken; the path is ignored.
package com.quixotic7.remotefm1.move

data class MoveAddress(val host: String, val port: Int) {
    /** a name for mDNS (.local): Android's resolver may not answer it, MoveMdns asks the network itself */
    val isMdns: Boolean get() = host.lowercase().trimEnd('.').endsWith(".local")
    val isLiteral: Boolean get() = isIpv4(host) || host.contains(':')
    val isUsb: Boolean get() = host == USB_ADDR
    /** what the address field shows: the host, with the port only when it is not 7720 */
    val text: String get() = (if (host.contains(':')) "[$host]" else host) + if (port != PORT) ":$port" else ""

    companion object {
        const val PORT = 7720                       // MR_PORT
        const val USB_ADDR = "172.16.254.1"         // MR_USB_ADDR: the Move's end of its USB-C network link
        const val DEFAULT_HOST = "move.local"
        val DEFAULT = MoveAddress(DEFAULT_HOST, PORT)

        /** the address typed (blank: move.local); null when it can't be one */
        fun parse(typed: String?): MoveAddress? {
            var a = (typed ?: "").trim()
            if (a.isEmpty()) return DEFAULT
            val scheme = Regex("^(wss?|https?)://", RegexOption.IGNORE_CASE).find(a)
            if (scheme != null) a = a.substring(scheme.value.length)
            a = a.substringBefore('/').substringBefore('?').substringBefore('#')
            if (a.contains('@')) a = a.substringAfterLast('@')
            if (a.isEmpty()) return null
            var host: String
            var port = PORT
            if (a.startsWith("[")) {                // [v6] or [v6]:port
                val end = a.indexOf(']')
                if (end < 0) return null
                host = a.substring(1, end)
                val rest = a.substring(end + 1)
                if (rest.isNotEmpty()) {
                    if (!rest.startsWith(":")) return null
                    port = rest.substring(1).toIntOrNull() ?: return null
                }
            } else if (a.count { it == ':' } > 1) {  // a bare IPv6 address
                host = a
            } else if (a.contains(':')) {
                host = a.substringBefore(':')
                port = a.substringAfter(':').toIntOrNull() ?: return null
            } else {
                host = a
            }
            if (port !in 1..65535 || host.isEmpty()) return null
            if (!host.contains(':') && !Regex("^[A-Za-z0-9.-]+$").matches(host)) return null
            return MoveAddress(host, port)
        }

        fun isIpv4(s: String): Boolean {
            val p = s.split('.')
            return p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() <= 255 }
        }
    }
}
