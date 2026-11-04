// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/FirmwareListing.swift (836a983, 3828820, d88353e).
//
// How the firmware lists show the cores (the owner, 2026-11-01):
//   superseded   only each firmware's newest version is listed (older ones, built in or imported, confused
//                testers); an older one still loads a state made with it, and shows while it is the one selected
//   sorting      by name (the catalog's order), recently used (each firmware's last selection, kept by firmware id
//                under "firmware.lastUsed"), or recently updated (the release date of its newest version,
//                FirmwareStore.updated)
package com.quixotic7.remotefm1.firmware

import android.content.SharedPreferences
import java.time.Instant

object FirmwareListing {
    enum class Sort(val raw: String, val title: String) {
        NAME("name", "Name"), USED("used", "Recently used"), UPDATED("updated", "Recently updated");

        companion object {
            fun of(s: String?) = entries.firstOrNull { it.raw == s } ?: NAME
        }
    }

    const val USED_KEY = "firmware.lastUsed"
    const val SORT_KEY = "firmware.sort"

    /** where the last selections are kept (the app: SharedPreferences; tests: a map) */
    interface Store {
        fun read(): Map<String, Double>
        fun write(m: Map<String, Double>)
    }

    class MapStore : Store {
        private var m: Map<String, Double> = emptyMap()
        override fun read() = m
        override fun write(m: Map<String, Double>) { this.m = m }
    }

    /** "<id>=<seconds>" lines under USED_KEY */
    class PrefsStore(private val prefs: SharedPreferences) : Store {
        override fun read(): Map<String, Double> = (prefs.getString(USED_KEY, "") ?: "").lines().mapNotNull { l ->
            val i = l.lastIndexOf('=')
            if (i <= 0) null else l.substring(i + 1).toDoubleOrNull()?.let { l.substring(0, i) to it }
        }.toMap()

        override fun write(m: Map<String, Double>) {
            prefs.edit().putString(USED_KEY, m.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
        }
    }

    /**
     * The cores a newer version of the same firmware replaces in the lists: every older version, built in or
     * imported; of two of the same version, the built-in one stays (it runs natively), else the one added last (a
     * rebuild: RemoteFM1FW keeps the version when only the glue changed), else the first. [added]: by SHA-256
     */
    fun superseded(cores: List<CoreInfo>, added: Map<String, Instant> = emptyMap()): Set<String> {
        val best = LinkedHashMap<String, CoreInfo>()
        for (c in cores) {
            val b = best[c.firmwareID]
            if (b == null) {
                best[c.firmwareID] = c
                continue
            }
            val cmp = FirmwareVersion.compare(c.version, b.version)
            if (cmp > 0) best[c.firmwareID] = c
            else if (cmp == 0 && b.imported && !c.imported) best[c.firmwareID] = c
            else if (cmp == 0 && b.imported && c.imported &&
                (added[c.sha256] ?: Instant.MIN) > (added[b.sha256] ?: Instant.MIN)) best[c.firmwareID] = c
        }
        return cores.filter { best[it.firmwareID]?.id != it.id }.map { it.id }.toSet()
    }

    /** the cores to list: each firmware's newest version (and the selected one) */
    fun visible(cores: List<CoreInfo>, selected: String, added: Map<String, Instant> = emptyMap()): List<CoreInfo> {
        val hide = superseded(cores, added)
        return cores.filter { it.id !in hide || it.id == selected }
    }

    /** a firmware selected now (recently used) */
    fun used(c: CoreInfo, at: Instant = Instant.now(), store: Store) {
        store.write(store.read() + (c.firmwareID to at.toEpochMilli() / 1000.0))
    }

    fun lastUsed(firmwareID: String, store: Store): Instant? =
        store.read()[firmwareID]?.let { Instant.ofEpochMilli((it * 1000).toLong()) }

    /** the cores in this order (ties and unknown dates keep the given order, after the dated ones) */
    fun sorted(cores: List<CoreInfo>, by: Sort, updated: (String) -> Instant?, store: Store): List<CoreInfo> {
        if (by == Sort.NAME) return cores
        val used = if (by == Sort.USED) store.read() else emptyMap()
        val keyed = cores.mapIndexed { i, c ->
            Triple(i, c, if (by == Sort.USED) used[c.firmwareID]?.let { Instant.ofEpochMilli((it * 1000).toLong()) } else updated(c.firmwareID))
        }
        return keyed.sortedWith { a, b ->
            val x = a.third
            val y = b.third
            when {
                x != null && y != null && x != y -> y.compareTo(x)
                x != null && y == null -> -1
                x == null && y != null -> 1
                else -> a.first.compareTo(b.first)
            }
        }.map { it.second }
    }

    /** when each imported file came (by SHA-256): of two of the same version, the lists show the later */
    fun addedDates(files: List<ImportedFirmware>): Map<String, Instant> {
        val out = HashMap<String, Instant>()
        for (f in files) out[f.sha256] = maxOf(out[f.sha256] ?: f.added, f.added)
        return out
    }
}
