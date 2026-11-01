// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The JVM tests' stand-in for the native .rfm1 loader (Rfm1Native: wasm3 can't run here): a file is valid when it is
// a WebAssembly module with an rfm1 section whose manifest has an id, a name and a version; its texts are its custom
// sections. The real loader's checks run in the instrumented FirmwareTest.
package com.quixotic7.remotefm1.firmware

import java.io.File
import java.security.MessageDigest

object FakeRfm1 : Rfm1Checker {
    var checks = 0

    fun fixture(name: String): File =
        File(requireNotNull(FakeRfm1::class.java.classLoader?.getResource("firmware/$name")) { "no fixture $name" }.toURI())

    val amb: File get() = fixture("amb-1.3.rfm1")
    val catalogJSON: ByteArray get() = fixture("catalog-2026-10-29.json").readBytes()
    val releaseJSON: ByteArray get() = fixture("releases-latest-2026-10-29.json").readBytes()
    const val AMB_SHA = "ccacdb2801a8ac39aa86128f1650b071775c4fa131446011189f77a34961f053"

    fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** the module's custom sections, by name (null: not a module) */
    fun sections(b: ByteArray): Map<String, ByteArray>? {
        if (b.size < 8 || b[0] != 0.toByte() || b[1] != 'a'.code.toByte() || b[2] != 's'.code.toByte() || b[3] != 'm'.code.toByte()) return null
        val out = LinkedHashMap<String, ByteArray>()
        var at = 8
        fun u32(): Int {
            var r = 0
            var sh = 0
            while (true) {
                if (at >= b.size) throw IllegalStateException("past the end")
                val x = b[at++].toInt() and 0xFF
                r = r or ((x and 0x7F) shl sh)
                if (x and 0x80 == 0) return r
                sh += 7
            }
        }
        try {
            while (at < b.size) {
                val id = b[at++].toInt()
                val size = u32()
                val end = at + size
                if (end > b.size) return null
                if (id == 0) {
                    val n = u32()
                    val name = String(b, at, n)
                    out[name] = b.copyOfRange(at + n, end)
                }
                at = end
            }
        } catch (e: IllegalStateException) {
            return null
        }
        return out
    }

    override fun check(path: String): Result<Rfm1Info> {
        checks++
        val f = File(path)
        val b = f.readBytes()
        val s = sections(b) ?: return Result.failure(Rfm1Refused("${f.name}: not a WebAssembly module (no \\0asm magic): not an .rfm1 file"))
        val m = s["rfm1"]?.let { runCatching { MiniJson.parse(String(it)).obj() }.getOrNull() }
            ?: return Result.failure(Rfm1Refused("${f.name}: no rfm1 section (the manifest): a WebAssembly module, but not an .rfm1 file"))
        val id = m.str("id") ?: return Result.failure(Rfm1Refused("${f.name}: its manifest has no \"id\""))
        return Result.success(Rfm1Info(id, m.str("name") ?: "", m.str("version") ?: "", "", m.str("license") ?: "",
            sha256(b), 6, 0, 0, b.size.toLong(), 0))
    }

    override fun text(path: String, what: String): String {
        val s = sections(File(path).readBytes()) ?: return ""
        val k = when {
            what == "manifest" -> "rfm1"
            what == "credits" -> "rfm1.credits"
            what.startsWith("license.") -> "rfm1.$what"
            else -> return ""
        }
        return s[k]?.let { String(it) } ?: ""
    }
}
