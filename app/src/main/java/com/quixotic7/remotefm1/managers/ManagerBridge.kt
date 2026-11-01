// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Managers/ManagerBridge.swift
// The firmwares' own web managers (iOS PLAN.md section 18 step 5), as remotefm1.com hosts them: managers/<id>/. In
// the app the page gets a whole Web MIDI injected at document start (ManagerPage.WEB_MIDI): one port each way named as
// the firmware's USB port, sysex granted, the site's fm1-shim.js told to stand aside. What the page sends goes to the
// running firmware's MIDI in (SysEx whole: rfme_sysex_in; the rest: rfme_midi_in to the firmware); what the firmware
// sends (rfme_midi_out_take's USB-MIDI packets, put back together by UsbMidiDecoder) goes to the page.
//   ManagerList     the managers the site has, by firmware id, and each firmware's USB port name
//   UsbMidiDecoder  USB-MIDI event packets -> whole MIDI messages (SysEx across packets)
package com.quixotic7.remotefm1.managers

/** the site's managers (web/managers/managers.json at RemoteFM1's main, as remotefm1.com serves them) */
object ManagerList {
    const val HOST = "remotefm1.com"
    const val BASE = "https://remotefm1.com/managers/"

    /** firmware id -> its manager's folder on the site */
    val managers: Map<String, String> = mapOf(
        "choralroot" to "choralroot", "purplemonkey" to "choralroot", "felucca" to "felucca", "melodee" to "melodee",
        "jiant" to "jiant", "ghoulbox" to "ghoulbox",
    )

    /** each firmware's real USB MIDI port name: the name the page's port takes (managers.json "ports") */
    val ports: Map<String, String> = mapOf(
        "choralroot" to "ChoralRoot FM-1", "purplemonkey" to "ChoralRoot FM-1", "coralflower" to "CoralFlower FM-1",
        "felucca" to "Felucca", "ghoulbox" to "Felucca", "amb" to "Felucca", "obervave" to "Felucca", "frodhes" to "Felucca",
        "melodee" to "Melodee FM-1", "jiant" to "JIANT FM", "fomni" to "FoMni FM-1", "fimba" to "FiMba FM-1",
        "loom" to "LOOM-8 FM-1", "duell" to "DUELL", "minitone" to "Felucca", "fmini" to "FM-1 Midi", "skiff" to "Felucca",
        "grimm" to "Felucca", "pigeon" to "PIGEON", "tonitor" to "FM-1 Tonitor", "speak37" to "Speak37",
    )

    /** the manager page for a firmware, null: none */
    fun url(firmwareId: String): String? = managers[firmwareId]?.let { "$BASE$it/" }

    fun port(firmwareId: String, name: String): String = ports[firmwareId] ?: name

    /** the page may talk to the player: https on remotefm1.com only */
    fun trusted(url: String?): Boolean {
        val u = runCatching { java.net.URI(url ?: return false) }.getOrNull() ?: return false
        return u.scheme == "https" && u.host == HOST
    }
}

/** USB-MIDI event packets (cable 0, the CIN in the low nibble, bytes 1..3 above it: rfme_midi_out_take's packing) back
 *  into whole messages; SysEx over several packets */
class UsbMidiDecoder {
    private val sysex = java.io.ByteArrayOutputStream()

    /** the message this packet completes, if any */
    fun feed(p: Int): ByteArray? {
        val cin = p and 0xF
        val b = byteArrayOf((p ushr 8).toByte(), (p ushr 16).toByte(), (p ushr 24).toByte())
        return when (cin) {
            0x4 -> {                                    // SysEx starts or goes on: three bytes
                if (b[0] == 0xF0.toByte()) sysex.reset()
                if (sysex.size() < MAX_SYSEX) sysex.write(b, 0, 3)
                null
            }
            0x5, 0x6, 0x7 -> {                          // SysEx ends with 1 / 2 / 3 bytes (or a 1-byte system common)
                val n = cin - 4
                if (cin == 0x5 && b[0] != 0xF7.toByte() && sysex.size() == 0) return byteArrayOf(b[0])
                sysex.write(b, 0, n)
                val end = sysex.toByteArray()
                sysex.reset()
                if (end.isNotEmpty() && end[0] == 0xF0.toByte()) end else null
            }
            0x2 -> b.copyOf(2)                          // two-byte system common
            0x3 -> b.copyOf(3)                          // three-byte system common
            0x8, 0x9, 0xA, 0xB, 0xE -> b.copyOf(3)
            0xC, 0xD -> b.copyOf(2)
            0xF -> b.copyOf(1)                          // single byte (real time)
            else -> null
        }
    }

    companion object {
        const val MAX_SYSEX = 64 * 1024

        /** a status byte's message length (CorePlayer.messageLength) */
        fun messageLength(st: Int): Int = when (st) {
            in 0xC0..0xDF, 0xF1, 0xF3 -> 2
            in 0x80..0xBF, in 0xE0..0xEF, 0xF2 -> 3
            else -> 1
        }
    }
}
