// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 App/AppMidi.swift, and CorePlayer.swift's midiSettings and web manager parts
// (A6) the app's MIDI side, made once with the model and kept as long as the process: the faceplate's PanelFeed on the
// engine (process-wide, so a rotation keeps it and the OMX-27 plays with no activity), the learned map
// (MidiMapStore, files/midi-map.json, followed by the engine), the MIDI settings (iOS's dictionary in the app's
// preferences), the controllers' input (ControllerInput: never the FM-1, never the OMX-27 while it is in remote mode),
// MIDI learn on the faceplate (MidiLearnMode) and the OMX-27 (OmxLink, its target the same PanelFeed through
// PanelOmxTarget). A web manager's MIDI: managerAttach / managerSend / managerTake. The main thread.
package com.quixotic7.remotefm1

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.quixotic7.remotefm1.managers.UsbMidiDecoder
import com.quixotic7.remotefm1.midi.ControllerInput
import com.quixotic7.remotefm1.midi.LearnEngine
import com.quixotic7.remotefm1.midi.MidiMapStore
import com.quixotic7.remotefm1.midi.MidiMode
import com.quixotic7.remotefm1.midi.MidiSettings
import com.quixotic7.remotefm1.omx.OmxAndroidMidi
import com.quixotic7.remotefm1.omx.OmxLink
import com.quixotic7.remotefm1.omx.OmxMainClock
import com.quixotic7.remotefm1.omx.OmxPrefs
import com.quixotic7.remotefm1.omx.OmxScreen
import com.quixotic7.remotefm1.omx.PanelOmxTarget
import com.quixotic7.remotefm1.ui.faceplate.EnginePanel
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.ui.faceplate.MidiLearnMode
import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import com.quixotic7.remotefm1.ui.faceplate.ToastCenter
import kotlinx.coroutines.delay

class AppMidi(ctx: Context, private val engine: RfmEngine, private val link: RfmLink, private val prefs: SharedPreferences) {
    val feed = PanelFeed(EnginePanel(engine))
    val store = MidiMapStore(MidiMapStore.defaultFile(ctx.filesDir))
    var settings by mutableStateOf(MidiSettings.fromJson(prefs.getString(MidiSettings.PREFS_KEY, null)))
        private set
    val input = ControllerInput(ctx, prefs) { b, n -> engine.midiIn(b, 0, n) }
    private val learnEngine = object : LearnEngine {
        override fun learn(armed: Boolean) = engine.learn(armed)
        override fun learnTake(msg: ByteArray) = engine.learnTake(msg)
    }
    val learn = MidiLearnMode(feed, learnEngine, store)
    val omxTarget = PanelOmxTarget(feed)
    val omx: OmxLink

    init {
        store.follow { e, sp -> engine.setMidiMap(e, sp) }
        applySettings()
        val omxPrefs = object : OmxPrefs {
            override fun string(key: String) = prefs.getString(key, null)
            override fun setString(key: String, v: String) = prefs.edit().putString(key, v).apply()
            override fun bool(key: String) = prefs.getBoolean(key, false)
            override fun setBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()
        }
        val screen = OmxScreen.of(ctx)
        omx = OmxLink(omxTarget, OmxAndroidMidi(ctx), OmxMainClock(), omxPrefs) { screen.render(it) }
        omx.log = { android.util.Log.i("RFMOmx", it) }
        input.exclude = { d -> omx.ownsDevice(d.id, d.displayName) }
        omx.onActive = { input.reconnect() }
        omx.onToast = { t, ok -> feed.toast.show(t, if (ok) ToastCenter.Tone.OK else ToastCenter.Tone.NEUTRAL) }
        feed.onKnobTouched = { omx.knobUsed(it) }
        omx.auto()
    }

    // ---------------------------------------------------------------- the settings (iOS CorePlayer.midiSettings)
    fun update(s: MidiSettings) {
        if (s == settings) return
        settings = s
        prefs.edit().putString(MidiSettings.PREFS_KEY, s.toJson()).apply()
        applySettings()
    }

    private fun applySettings() {
        val m = settings
        engine.setMidi(m.keyChannel.engineValue, m.transpose, m.keyNotesToFirmware, m.useLearnedMap)
        link.setKeyTouch(m.keyTouchFlags)
    }

    /** Learn: the sheet closes, the faceplate picks controls (a MIDI input on: All when it was None) */
    fun startLearn(ui: FaceplateUi) {
        if (input.selection == ControllerInput.Selection.None) input.select(ControllerInput.Selection.All)
        learn.start(ui)
    }

    /** the app going away: the OMX's LEDs and screen cleared */
    fun willClose() = omx.willClose()

    /** -demomap (screenshots): a few mappings, as if learned */
    fun demoMap() {
        store.clear()
        store.bind("cc:1:21", "KN1", MidiMode.ABS)
        store.bind("cc:1:22", "KN2", MidiMode.REL64)
        store.bind("cc:1:7", "MASTER")
        store.bind("note:10:36", "PLAY")
    }

    // ---------------------------------------------------------------- a web manager (iOS section 18 step 5)
    private var decoder = UsbMidiDecoder()
    private val packets = IntArray(256)
    var managerOpen = false
        private set

    /** a web manager open: the firmware's MIDI out kept on every clock (and handed to managerTake), or not */
    fun managerAttach(on: Boolean) {
        managerOpen = on
        engine.midiOutEnable(if (on) 2 else 0)
        decoder = UsbMidiDecoder()
        if (on) while (engine.midiOutTake(packets) > 0) {}      // (nothing old)
    }

    /** one message from the page to the firmware's MIDI in: SysEx whole (retried while the queue is full), else as the
     *  firmware's own MIDI in (not the keys, not the learned map). false: not sent */
    suspend fun managerSend(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0].toInt() and 0x80 == 0) return false
        if (bytes[0] == 0xF0.toByte()) {
            repeat(200) {
                if (engine.sysexIn(bytes)) return true
                if (bytes.last() != 0xF7.toByte() || bytes.size > 12_000) return false
                delay(5)
            }
            return false
        }
        var ok = true
        var i = 0
        while (i < bytes.size) {                               // (several short messages in one send)
            val st = bytes[i].toInt() and 0xFF
            val n = UsbMidiDecoder.messageLength(st)
            if (st and 0x80 == 0 || i + n > bytes.size) return false
            ok = engine.midiIn(bytes, i, n, toFirmware = true) && ok
            i += n
        }
        return ok
    }

    /** what the firmware sent since the last call, as whole messages */
    fun managerTake(): List<ByteArray> {
        var out: ArrayList<ByteArray>? = null
        while (true) {
            val n = engine.midiOutTake(packets)
            if (n == 0) break
            for (i in 0 until n) decoder.feed(packets[i])?.let { (out ?: ArrayList<ByteArray>().also { o -> out = o }).add(it) }
            if (n < packets.size) break
        }
        return out ?: emptyList()
    }
}
