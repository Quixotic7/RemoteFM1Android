// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/UpdateNotice.swift (a67b1cf, 3ea5b25).
//
// The launch notice (the owner, 2026-11-02: testers kept sending feedback from old builds): at the app's launch, one
// dismissible message when a newer app build is out (release/android.json in this repository, read from GitHub: its
// "build" is the APK's versionCode) or RemoteFM1's own firmware here has an update (not the others': too many; they
// stay in Get firmware's list). Closed, it stays closed until something newer comes out (the dismissed set is kept).
package com.quixotic7.remotefm1.firmware

import java.net.HttpURLConnection
import java.net.URL

/** release/android.json */
data class LatestBuild(val build: Int, val version: String? = null, val url: String? = null) {
    companion object {
        /** null: not one */
        fun parse(data: ByteArray): LatestBuild? {
            val m = runCatching { MiniJson.parse(data).obj() }.getOrNull() ?: return null
            val b = m.int("build") ?: return null
            return LatestBuild(b.toInt(), m.str("version"), m.str("url"))
        }
    }
}

data class UpdateNotice(
    val newBuild: Int?,                 // a newer app build (null: this one is the newest)
    val newVersion: String?,
    val url: String?,                   // where to get it (the GitHub release)
    val firmware: List<String>,         // firmware here with an update, by name
    val signature: String,              // what it tells, for "already closed"
) {
    val title: String get() = if (newBuild != null) "A new RemoteFM1 is out" else "Firmware updates"

    fun message(current: Int): String {
        val s = ArrayList<String>()
        if (newBuild != null) {
            s.add("Build $newBuild${newVersion?.let { " ($it)" } ?: ""} is on GitHub; you have build $current. " +
                "Please update before sending feedback.")
        }
        if (firmware.isNotEmpty()) {
            val names = if (firmware.size > 3) firmware.take(3) + "${firmware.size - 3} more" else firmware
            val list = if (names.size > 1) names.dropLast(1).joinToString(", ") + " and " + names.last() else names[0]
            s.add("${if (firmware.size == 1) "An update" else "Updates"} for $list in Get firmware.")
        }
        return s.joinToString("\n\n")
    }

    /** whether it was closed before (the same builds and versions); [stored] the kept signature */
    fun dismissed(stored: String?): Boolean = stored == signature

    companion object {
        /** the firmware whose updates the notice tells of: RemoteFM1's own */
        val FIRMWARE_IDS = setOf("remotefm1")
        const val LATEST_URL = "https://raw.githubusercontent.com/Quixotic7/RemoteFM1Android/main/release/android.json"
        const val DISMISSED_KEY = "updateNotice.dismissed"

        /** what to tell, null: nothing new. [updates]: Get firmware's entries in the Update state (name, its version) */
        fun make(build: Int, latest: LatestBuild?, updates: List<Pair<String, String>>): UpdateNotice? {
            val nb = latest?.build?.takeIf { it > build }
            val fw = updates.sortedWith { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.first, b.first) }
            if (nb == null && fw.isEmpty()) return null
            val sig = "b${nb ?: 0}|" + fw.joinToString(",") { "${it.first} ${it.second}" }
            return UpdateNotice(nb, if (nb != null) latest.version else null, latest?.url, fw.map { it.first }, sig)
        }

        /** release/android.json, null: not reachable (offline: no notice about the app); blocks */
        fun latest(timeoutMs: Int = 6000): LatestBuild? = runCatching {
            val c = URL(LATEST_URL).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            try {
                if (c.responseCode != 200) null else LatestBuild.parse(c.inputStream.use { it.readBytes() })
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
