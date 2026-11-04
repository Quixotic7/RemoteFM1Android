// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/Managers/ManagerBridge.swift (f47cdd4, 95d5d16, f02182b,
// 1d88c11, 391497e, dc771c8, f6e6eaa)
// The firmwares' own web editors (iOS PLAN.md section 18 step 5), opened at their authors' pages. The app injects a
// whole Web MIDI into the page (ManagerBridge.WEB_MIDI): one port named as the firmware's USB port, MIDI both ways
// through a @JavascriptInterface. What the page sends goes to the running firmware's MIDI in (SysEx whole:
// rfme_sysex_in; the rest: rfme_midi_in to the firmware); what the firmware sends (rfme_midi_out_take's USB-MIDI
// packets, put back together by UsbMidiDecoder) goes to the page.
//   ManagerList     the authors' editor pages by firmware id, each firmware's USB port name, the screen reader
//   UsbMidiDecoder  USB-MIDI event packets -> whole MIDI messages (SysEx across packets)
package com.quixotic7.remotefm1.managers

import com.quixotic7.remotefm1.firmware.AvailableFirmware

/** the firmwares' editors: always the author's own page (the owner, 2026-11-01). remotefm1.com's copies fell behind
 *  their authors' pages; the app needs no copy, as its Web MIDI works on any page */
object ManagerList {
    /** the authors' pages for the firmwares the catalog may not list an editor for (or before the catalog is read) */
    val authors: Map<String, String> = mapOf(
        "choralroot" to "https://quixotic7.github.io/ChoralRootFM1/webapp/installer/manager.html",
        "purplemonkey" to "https://quixotic7.github.io/ChoralRootFM1/webapp/installer/manager.html",
        "felucca" to "https://hugelton.github.io/Felucca/webapp/editor/",
        "melodee" to "https://keremimo.github.io/melodee/webapp/editor/",
        "jiant" to "https://juanjiant-bit.github.io/JIANT/webapp/editor/",
        "ghoulbox" to "https://jasonpersinger.me/ghoulbox-fm1-dungeon-synth/webapp/editor/",
        // (not in the site catalog's editors: Mutable Dreams is listed there as "mutable"; Rainbow Mode has Felucca's
        // editor on its own site. Hortator's site has one too, but its firmware answers no editor: RemoteFM1FW 5bcbb65)
        "mutabledreams" to "https://mutable-dreams.kidbee85.chatgpt.site/#device-editor",
        "rainbow" to "https://joshbartlettband-web.github.io/felucca-rainbow/webapp/editor/",
        "fizz" to "https://quixotic7.github.io/FIZZ/manager/",     // (built in; not in the catalogs)
    )

    /** each firmware's real USB MIDI port name (its USB iProduct string, which its editor matches): RemoteFM1FW's
     *  catalog `port` (31b8751) wins; this list is the fallback, the same names */
    val ports: Map<String, String> = mapOf(
        "amb" to "AMB-1 FM-1", "aurora" to "Felucca", "caravel" to "Caravel", "choralroot" to "ChoralRoot FM-1",
        "clipper" to "Clipper", "coralflower" to "CoralFlower FM-1", "duell" to "DUELL", "fartmachine" to "FoMni FM-1",
        "felucca" to "Felucca", "fimba" to "FiMba FM-1", "fmini" to "FM-1 Midi", "fomni" to "FoMni FM-1",
        "frasil" to "FRASIL FM-1", "frodhes" to "Felucca", "ghoulbox" to "Felucca", "grimm" to "Felucca",
        "hortator" to "Hortator", "hyp1" to "HYP-1 FM-1", "jiant" to "FM-1", "karma" to "FM-1 KARMA", "loom" to "LOOM-8 FM-1",
        "melodee" to "Melodee FM-1", "minitone" to "Felucca", "mutabledreams" to "Mutable Dreams FM-1",
        "niagaradrift" to "Niagara Drift FM-1", "obervave" to "Felucca", "pigeon" to "PIGEON",
        "purplemonkey" to "ChoralRoot FM-1", "rainbow" to "Felucca", "remotefm1" to "FM-1", "ripple" to "Ripple FM-1",
        "s1vave" to "Felucca", "skiff" to "Felucca", "sloop" to "Felucca", "speak37" to "Speak37", "tonitor" to "FM-1 Tonitor",
        "x0x" to "X0X FM-1", "zen1" to "ZEN-1 FM-1",
    )

    /** Charles Vestal's FM-1 Screen Reader: reads out what the screen shows, for any firmware with its announce.c.
     *  Opened like an editor, the app's Web MIDI in it */
    const val READER = "https://charlesvestal.github.io/fm1-reader/"
    /** the firmwares that speak to it (its README's list, 2026-11-01) */
    val readerFirmwares: Set<String> = setOf("x0x", "fomni", "amb", "polly", "monk", "mocky", "ctl", "ctl1")

    fun hasReader(firmwareId: String): Boolean = firmwareId in readerFirmwares

    /** the author's editor known to the app, null: none */
    fun url(firmwareId: String): String? = authors[firmwareId]

    /** the port the editor page sees: RemoteFM1FW's catalog `port`, else the app's list, else the firmware's name */
    fun port(firmwareId: String, name: String, details: Map<String, AvailableFirmware> = emptyMap()): String {
        details[firmwareId]?.port?.takeIf { it.isNotEmpty() }?.let { return it }
        return ports[firmwareId] ?: name
    }

    /** the editor page to open: the catalog's `editor` (the author's page, https only), else the one the app knows;
     *  null: none */
    fun editor(firmwareId: String, details: Map<String, AvailableFirmware>): String? {
        details[firmwareId]?.editor?.takeIf { https(it) }?.let { return it }
        return url(firmwareId)
    }

    /** a page the player talks to: any https page (the authors' sites; iOS gives every page its Web MIDI) */
    fun trusted(url: String?): Boolean = https(url)

    private fun https(url: String?): Boolean {
        val u = runCatching { java.net.URI(url ?: return false) }.getOrNull() ?: return false
        return u.scheme == "https" && !u.host.isNullOrEmpty()
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
