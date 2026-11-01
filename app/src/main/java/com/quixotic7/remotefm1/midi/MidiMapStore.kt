// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/MIDI/MidiMapStore.swift
// The learned map is global (as the website's prefs.midiMap): one map for the app, in midi-map.json in the app's files
// (iOS keeps the same file in its App Group; the format and ids are the same, so the file moves between the apps).
// Android has one process and no AU, so iOS's Darwin notification is a plain listener list here:
//   MidiMapStore  the map, its changes (saved at once); compose state moves with it ([gen])
//   follow(sink)  an engine that keeps the map: applied now and on every change (the main thread, one writer)
package com.quixotic7.remotefm1.midi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** an engine the map is given to (RfmEngine.setMidiMap) */
fun interface MidiMapSink {
    fun setMidiMap(entries: IntArray, speed: Float)
}

class MidiMapStore(val file: File) {
    var map: MidiMap = read(file)
        private set
    /** moves with every change (Compose reads it) */
    var gen by mutableIntStateOf(0)
        private set
    private val sinks = ArrayList<MidiMapSink>()

    val mappings: List<MidiMapping> get() = map.mappings
    val speed: Double get() = map.speed
    val boundTargets: Set<String> get() = map.boundTargets
    fun sourcesOf(target: String) = map.sourcesOf(target)

    /** the engine keeps this store's map: given it now and after every change */
    fun follow(s: MidiMapSink) {
        sinks.add(s)
        s.setMidiMap(map.entries, map.speed.toFloat())
    }

    fun unfollow(s: MidiMapSink) { sinks.remove(s) }

    fun bind(src: String, target: String, mode: MidiMode? = null): MidiMapping? {
        val m = map.copy()
        val mp = m.bind(src, target, mode)
        if (mp != null) change(m)
        return mp
    }

    fun setMode(src: String, mode: MidiMode) = change(map.copy().also { it.setMode(src, mode) })

    fun remove(src: String): Boolean {
        val m = map.copy()
        val ok = m.remove(src)
        change(m)
        return ok
    }

    /** a target's bindings removed; how many */
    fun forget(target: String): Int {
        val m = map.copy()
        val n = m.forget(target)
        change(m)
        return n
    }

    fun clear() = change(map.copy().also { it.clear() })
    fun setSpeed(v: Double) = change(map.copy().also { it.setSpeed(v) })

    /** reads the file again (a restore put another one there) */
    fun load() {
        val m = read(file)
        if (m != map) { map = m; gen++; push() }
    }

    // saved (whole, then renamed over the old one), the engines given it
    private fun change(m: MidiMap) {
        if (m == map) return
        map = m
        gen++
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(m.json)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        push()
    }

    private fun push() {
        val e = map.entries
        val sp = map.speed.toFloat()
        sinks.toList().forEach { it.setMidiMap(e, sp) }
    }

    companion object {
        const val FILE_NAME = "midi-map.json"

        fun defaultFile(filesDir: File) = File(filesDir, FILE_NAME)

        /** the map in a file (none, or unreadable: empty) */
        fun read(f: File): MidiMap = MidiMap.fromJson(runCatching { f.readText() }.getOrNull())
    }
}
