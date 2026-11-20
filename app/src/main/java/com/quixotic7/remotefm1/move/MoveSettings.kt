// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// What the Move link keeps in the app's preferences, as the web player keeps it in its prefs (RemoteFM1 18c6a82
// web/site/js/move-link.js): the core's settings image (rfm_move_save's bytes, base64: "move"), the address
// ("moveAddr"; the web's moveUrl), connect at start ("moveAuto": set by Connect, cleared by Disconnect) and the project
// last open ("moveProject", 0..31); (0.5.10) the firmware's level the Move set ("moveLevel", 0..127: the app has no
// output level of its own, so it is kept and not applied; the library blob keeps it too).
package com.quixotic7.remotefm1.move

import java.util.Base64

/** where they live (SharedPreferences in the app; a map in the tests) */
interface MovePrefs {
    fun string(key: String): String?
    fun setString(key: String, v: String?)
    fun bool(key: String): Boolean
    fun setBool(key: String, v: Boolean)
    fun int(key: String, def: Int): Int
    fun setInt(key: String, v: Int)
}

class MoveSettings(private val p: MovePrefs) {
    /** the core's settings image, null none (the defaults) */
    var image: ByteArray?
        get() = p.string(KEY_IMAGE)?.let { try { Base64.getDecoder().decode(it) } catch (_: IllegalArgumentException) { null } }
        set(v) = p.setString(KEY_IMAGE, v?.let { Base64.getEncoder().encodeToString(it) })

    /** keeps [v] only when it differs from what is kept; true when it changed */
    fun keepImage(v: ByteArray): Boolean {
        if (image?.contentEquals(v) == true) return false
        image = v
        return true
    }

    var address: MoveAddress
        get() = MoveAddress.parse(p.string(KEY_ADDR)) ?: MoveAddress.DEFAULT
        set(v) = p.setString(KEY_ADDR, v.text)

    var auto: Boolean
        get() = p.bool(KEY_AUTO)
        set(v) = p.setBool(KEY_AUTO, v)

    /** the project last open (0..31; 0 when none was kept or it is out of range) */
    var project: Int
        get() = p.int(KEY_PROJECT, 0).let { if (MoveProjects.validSlot(it)) it else 0 }
        set(v) { if (MoveProjects.validSlot(v)) p.setInt(KEY_PROJECT, v) }

    /** the Move's level for the firmware (FW_LEVEL, 0..127; -1 never set) */
    var level: Int
        get() = p.int(KEY_LEVEL, -1)
        set(v) = p.setInt(KEY_LEVEL, v.coerceIn(0, 127))

    companion object {
        const val KEY_LEVEL = "moveLevel"
        const val KEY_IMAGE = "move"
        const val KEY_ADDR = "moveAddr"
        const val KEY_AUTO = "moveAuto"
        const val KEY_PROJECT = "moveProject"
    }
}
