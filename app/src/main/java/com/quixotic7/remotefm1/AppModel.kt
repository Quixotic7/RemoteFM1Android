// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The app's one model (process-wide, outlives the activity; the foreground service keeps the process): the FM-1 link
// and the engine on it, the sound source, the mode and the core. A1's part of RemoteFM1IOS's RemoteController (the
// link's state read ~15 times a second) and CorePlayer (Pattern vs Core mode, the core picker, the sound). A2: each
// core's flash kept (CoreFlash: files/cores/<id>.bin, saved when iOS saves), Restart (a power cycle), the audio
// output picked from Android's list and the stream reopened when its device goes (AudioOutputs, AudioRoute).
package com.quixotic7.remotefm1

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer

/** How the app was launched (adb: `am start -n .../.MainActivity --ez fake true`; RemoteFM1IOS's -fake / -fakeble). */
data class LaunchOptions(
    val fake: Boolean = false,          // libremotefm1's fake unit in the app (no MIDI device touched)
    val fakeBle: Boolean = false,       // the fake as a Bluetooth unit
    val virtual: Boolean = false,       // (debug builds) the Device back end on the virtual FM-1 (FakeFm1Service)
    val source: String? = null,         // "fm1" / "tablet"
    val mode: String? = null,           // "pattern" / "core"
    val core: String? = null,           // a core id
    val sound: Boolean? = null,
) {
    val backend: Backend get() = if (fake || fakeBle) Backend.Fake(fakeBle) else Backend.Device

    companion object {
        fun from(extras: android.os.Bundle?): LaunchOptions {
            if (extras == null) return LaunchOptions()
            fun flag(k: String) = extras.getBoolean(k, false) || extras.getString(k) == "1" || extras.getString(k) == "true"
            return LaunchOptions(
                fake = flag("fake"), fakeBle = flag("fakeble"), virtual = flag("virtual"),
                source = extras.getString("source"), mode = extras.getString("mode"), core = extras.getString("core"),
                sound = if (extras.containsKey("sound")) flag("sound") else null,
            )
        }
    }
}

enum class Source { TABLET, FM1 }
enum class Mode { PATTERN, CORE }

class AppModel private constructor(private val ctx: Context, val options: LaunchOptions) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("rfm1", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    val backend = options.backend
    val link = RfmLink(backend, virtualName = if (options.virtual) Fm1Candidate.VIRTUAL_NAME else "")
    val engine = RfmEngine(link)
    val cores: List<JSONObject>
    val flash = CoreFlash(engine, File(ctx.filesDir, "cores")) { id -> cores.firstOrNull { it.getString("id") == id }?.getString("version") }
    val outputs = AudioOutputs(ctx) { outputsChanged(it) }

    // ---- what the UI shows (Compose state, written on the main thread)
    var source by mutableStateOf(Source.FM1)
        private set
    var mode by mutableStateOf(Mode.PATTERN)
        private set
    var selected by mutableStateOf("choralroot")
        private set
    var soundOn by mutableStateOf(false)
        private set
    var patternOn by mutableStateOf(true)
        private set
    var state by mutableStateOf<LinkState>(LinkState.Stopped())
        private set
    var statusText by mutableStateOf("off")
        private set
    var lastLog by mutableStateOf("")
        private set
    var hello by mutableStateOf<UnitHello?>(null)
        private set
    var stats by mutableStateOf(LinkStats())
        private set
    var events by mutableStateOf<List<PanelEvent>>(emptyList())     // newest first
        private set
    var heldKeys by mutableStateOf(0L)
        private set
    var heldButtons by mutableStateOf(0L)
        private set
    var master by mutableStateOf<Int?>(null)
        private set
    var engineStatus by mutableStateOf(JSONObject())
        private set
    var message by mutableStateOf("")
        private set
    /** the output picked (an AudioDeviceInfo id; 0: the system default) */
    var requestedOutput by mutableIntStateOf(0)
        private set
    var outputList by mutableStateOf<List<AudioOut>>(emptyList())
        private set
    var routeChanges by mutableIntStateOf(0)
        private set
    var audioRestarts by mutableIntStateOf(0)
        private set
    /** the selection asked for (a launch extra or the saved one) is not in this build: what was taken instead */
    var fallbackNote = ""
        private set
    var midiEntries by mutableStateOf<List<RfmMidi.Entry>>(emptyList())
        private set
    var bleConnecting by mutableStateOf(false)
        private set
    val previewFrame = mutableIntStateOf(0)                          // moves when [preview] changed
    val preview: Bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)

    val reconnect = ReconnectPolicy(prefs.getString(KEY_BLE, null))
    private var lastSeq = 0L
    private var linkSeq = 0
    private var coreSeq = 0
    private var searchingSince: Long? = null
    private val rgba = ByteArray(240 * 240 * 4)
    private var booted: String? = null
    var playingListener: ((Boolean, String) -> Unit)? = null

    init {
        RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir)
        val a = RfmEngine.cores().getJSONArray("cores")
        cores = (0 until a.length()).map { a.getJSONObject(it) }.sortedBy { ORDER.indexOf(it.getString("id")).let { i -> if (i < 0) 99 else i } }
        val wanted = options.core ?: prefs.getString(KEY_CORE, null)
        selected = pick(wanted, cores.map { it.getString("id") }) ?: "choralroot"
        if (wanted != null && wanted != selected) fallbackNote = "$wanted is not in this build: ${nameOf(selected)} instead"
        if (options.core != null) prefs.edit().putString(KEY_CORE, selected).apply()     // (a launch extra is remembered too)
        outputList = outputs.list
        engine.setMidi()
        enableVirtual(options.virtual)                      // (listed to other apps only when asked for)
        RfmMidi.start(ctx, link.let { if (options.virtual) Fm1Candidate.VIRTUAL_NAME else "" })
        RfmMidi.addListener { main.post { link.rescan(); refreshMidi() } }
        Log.i(TAG, "backend $backend, cores ${cores.map { it.getString("id") }}, last Bluetooth unit ${reconnect.address}")
        val src = when (options.source) {
            "tablet" -> Source.TABLET
            "fm1" -> Source.FM1
            else -> if (prefs.getString(KEY_SOURCE, "fm1") == "tablet") Source.TABLET else Source.FM1
        }
        useSource(src)
        val m = when (options.mode) {
            "core" -> Mode.CORE
            "pattern" -> Mode.PATTERN
            else -> if (prefs.getString(KEY_MODE, "pattern") == "core") Mode.CORE else Mode.PATTERN
        }
        useMode(m)
        options.sound?.let { setSound(it) }
        tick()
    }

    // ---------------------------------------------------------------- control (the main thread)
    fun useSource(s: Source) {
        source = s
        prefs.edit().putString(KEY_SOURCE, if (s == Source.FM1) "fm1" else "tablet").apply()
        if (s == Source.FM1) link.start() else link.stop()
        if (mode == Mode.CORE) engine.attach(s == Source.FM1)
        playingChanged()
    }

    fun useMode(m: Mode) {
        if (m == Mode.CORE) {
            if (!bootSelected()) {
                mode = Mode.PATTERN
                return
            }
            mode = m
            engine.attach(source == Source.FM1)
            applyClock()
        } else {
            mode = m
            stopAudio()
            engine.setClock(RfmEngine.CLOCK_NONE)
            flash.saveStopped()
            engine.attach(false)
        }
        prefs.edit().putString(KEY_MODE, if (mode == Mode.CORE) "core" else "pattern").apply()
        playingChanged()
    }

    fun select(id: String) {
        if (cores.none { it.getString("id") == id }) return
        selected = id
        prefs.edit().putString(KEY_CORE, id).apply()
        if (id != booted) fallbackNote = ""
        if (mode == Mode.CORE) {
            val sound = soundOn
            stopAudio()
            if (!bootSelected()) useMode(Mode.PATTERN) else {
                soundOn = sound
                applyClock()
            }
        }
        playingChanged()
    }

    fun setSound(on: Boolean) {
        soundOn = on
        if (mode == Mode.CORE) applyClock()
        playingChanged()
    }

    fun setPattern(on: Boolean) {
        patternOn = on
        link.setPattern(on)
    }

    fun restartLink() {
        link.stop()
        if (source == Source.FM1) link.start()
    }

    /** Restart: the firmware powered off and on again from its flash (iOS CorePlayer.powerCycle) */
    fun restartFirmware() {
        if (booted == null) return
        val err = flash.powerCycle()
        if (err != null) {
            booted = null
            message = "restart failed: $err"
        } else {
            message = ""
        }
    }

    /** the app goes to the background (MainActivity.onStop): the flash saved now, the clock left running */
    fun didEnterBackground() {
        if (booted == null) return
        val clock = engine.status().optInt("clock")
        flash.saveNow(main, clock == RfmEngine.CLOCK_AUDIO || clock == RfmEngine.CLOCK_TIMER) { ok ->
            Log.i(TAG, "background: flash ${if (ok) "saved" else "not saved"}")
        }
    }

    /** the output to play on (0: the system default); the stream reopened on it when the sound is on */
    fun chooseOutput(id: Int) {
        requestedOutput = id
        if (mode == Mode.CORE && soundOn) restartAudio("output picked")
    }

    private fun outputsChanged(list: List<AudioOut>) {
        outputList = list
        routeChanges++
        val a = engine.status().getJSONObject("audio")
        val present = list.map { it.id }
        if (requestedOutput != 0 && requestedOutput !in present) requestedOutput = 0
        when (AudioRoute.action(mode == Mode.CORE && soundOn, a.optInt("open") != 0, a.optInt("dead") != 0,
                                a.optInt("requested_device"), present)) {
            AudioRoute.Action.RESTART -> restartAudio("output gone")
            else -> {}
        }
    }

    // a new stream on the device now wanted, at its rate and burst (the engine learns the new buffer size again):
    // iOS's audioConfigurationChanged
    private fun restartAudio(why: String) {
        if (mode != Mode.CORE || !soundOn) return
        audioRestarts++
        Log.i(TAG, "audio: reopening the stream ($why)")
        engine.setClock(RfmEngine.CLOCK_TIMER)
        stopAudio()
        applyClock()
    }

    private fun bootSelected(): Boolean {
        if (booted == selected) return true
        if (booted != null) {                                   // (another core: its flash saved, let go)
            flash.unload()
            booted = null
        }
        val err = engine.load(selected)
        if (err != null) {
            message = "cannot load $selected: $err"
            return false
        }
        val note = flash.boot(selected)
        if (note == null) {
            message = "cannot boot $selected"
            return false
        }
        booted = selected
        message = listOf(fallbackNote, note).filter { it.isNotEmpty() }.joinToString(" ")
        return true
    }

    private fun applyClock() {
        if (soundOn) {
            val present = outputs.list.map { it.id }
            val dev = AudioRoute.device(requestedOutput, present)
            var err = engine.startAudio(dev)
            if (err != null && dev != 0) err = engine.startAudio(0)     // (the picked one refused: the default)
            if (err != null) {
                message = "sound: $err"
                soundOn = false
                engine.setClock(RfmEngine.CLOCK_TIMER)
            }
        } else {
            stopAudio()
            engine.setClock(RfmEngine.CLOCK_TIMER)
        }
    }

    private fun stopAudio() {
        if (engine.status().getJSONObject("audio").optInt("open") != 0) engine.stopAudio(RfmEngine.CLOCK_TIMER)
    }

    private fun nameOf(id: String) = cores.firstOrNull { it.getString("id") == id }?.getString("name") ?: id

    /** the playing stream's device (0: none) */
    val playingOn: Int get() = engineStatus.optJSONObject("audio")?.takeIf { it.optInt("open") != 0 }?.optInt("device_id") ?: 0

    val coreName: String
        get() = cores.firstOrNull { it.getString("id") == selected }?.let { "${it.getString("name")} ${it.getString("version")}" }
            ?: selected

    /** what the notification says: "RemoteFM1 is playing ChoralRoot" */
    val playingText: String
        get() = if (mode == Mode.CORE) "RemoteFM1 is playing ${cores.firstOrNull { it.getString("id") == selected }?.getString("name") ?: selected}"
        else "RemoteFM1: the test pattern on the FM-1"

    /** the engine or the link is running: the foreground service keeps them in the background */
    val playing: Boolean get() = source == Source.FM1 || (mode == Mode.CORE && soundOn)

    private fun playingChanged() = playingListener?.invoke(playing, playingText)

    // ---------------------------------------------------------------- Bluetooth
    fun connectBluetooth(device: android.bluetooth.BluetoothDevice) {
        bleConnecting = true
        RfmMidi.openBluetooth(device) { ok ->
            main.post {
                bleConnecting = false
                if (ok) {
                    reconnect.connected(device.address)
                    prefs.edit().putString(KEY_BLE, device.address).apply()
                } else {
                    reconnect.failed(SystemClock.elapsedRealtime())
                }
            }
        }
    }

    fun disconnectBluetooth() {
        reconnect.address?.let { RfmMidi.closeBluetooth(it) }
        reconnect.pause()
    }

    fun forgetBluetooth() {
        disconnectBluetooth()
        reconnect.forget()
        prefs.edit().remove(KEY_BLE).apply()
    }

    private fun mayConnectBluetooth(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // the remembered unit reopened at launch and after it dropped (ReconnectPolicy)
    private var wasOpen = false
    private fun reconnectTick(now: Long) {
        val addr = reconnect.address ?: return
        if (backend.isFake || !mayConnectBluetooth()) return
        val open = RfmMidi.bluetoothOpen(addr)
        if (wasOpen && !open) reconnect.failed(now)          // (it dropped)
        wasOpen = open
        val usbLinked = RfmMidi.entries().any { it.nativeId > 0 && it.desc.type == MidiDesc.TYPE_USB }
        if (reconnect.shouldTry(now, source == Source.FM1, open || usbLinked, bleConnecting)) {
            val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
            val dev = runCatching { bm.adapter?.getRemoteDevice(addr) }.getOrNull() ?: return
            Log.i(TAG, "reopening the Bluetooth unit $addr (try ${reconnect.attempts + 1})")
            reconnect.failed(now)                            // (the next try's time; connected() resets it)
            connectBluetooth(dev)
        }
    }

    // ---------------------------------------------------------------- reading the link and the engine
    private fun tick() {
        refresh()
        main.postDelayed({ tick() }, 66)
    }

    private fun refreshMidi() {
        midiEntries = RfmMidi.entries()
    }

    fun refresh() {
        val st = link.status()
        val (s, searching) = LinkState.of(st.optInt("state"), st.optInt("session_state", -1), st.optInt("wanted") != 0,
                                          st.optInt("ble") != 0, st.optString("text"),
                                          searchingSince?.let { SystemClock.elapsedRealtime() - it })
        if (searching) searchingSince = searchingSince ?: SystemClock.elapsedRealtime() else searchingSince = null
        if (s != state) state = s
        st.optString("text").let { if (it != statusText) statusText = it }
        st.optString("log").let { if (it != lastLog) lastLog = it }
        LinkStatus.hello(st).let { if (it != hello) hello = it }
        LinkStatus.stats(st).let { if (it != stats) stats = it }
        val new = link.events(lastSeq)
        if (new.isNotEmpty()) {
            lastSeq = new.last().id
            events = (new.reversed() + events).take(MAX_EVENTS)
        }
        val p = link.panel()
        if (p[0] != heldKeys) heldKeys = p[0]
        if (p[1] != heldButtons) heldButtons = p[1]
        val mv = if (p[2] < 0) null else p[2].toInt()
        if (mv != master) master = mv
        engineStatus = engine.status()
        if (mode == Mode.CORE && booted != null) {
            flash.refresh(engineStatus)?.let { message = it }
            val a = engineStatus.getJSONObject("audio")
            if (soundOn && a.optInt("open") != 0 && a.optInt("dead") != 0) restartAudio("stream disconnected")
        }
        // the screen being sent: the core's in Core mode, the link's pattern otherwise
        val seq: Int
        if (mode == Mode.CORE) {
            seq = engine.preview(coreSeq, rgba)
            if (seq != coreSeq) coreSeq = seq
        } else {
            seq = link.preview(linkSeq, rgba)
            if (seq != linkSeq) linkSeq = seq
        }
        if (seq != previewFrame.intValue) {
            preview.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            previewFrame.intValue = seq
        }
        if (midiEntries.size != RfmMidi.entries().size) refreshMidi()
        reconnectTick(SystemClock.elapsedRealtime())
    }

    private fun enableVirtual(on: Boolean) {
        // (debug builds have FakeFm1Service; a release build has no such component)
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(ComponentName(ctx.packageName, "$PKG.FakeFm1Service"),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }.onFailure { Log.w(TAG, "no virtual FM-1 in this build: $it") }
    }

    /** The app quits (the notification's Stop): SESSION 0, everything closed. */
    fun shutdown() {
        stopAudio()
        engine.setClock(RfmEngine.CLOCK_NONE)
        flash.saveStopped()
        flash.flush()
        link.stop()
        source = Source.TABLET
        playingChanged()
    }

    companion object {
        const val TAG = "RFMApp"
        const val PKG = "com.quixotic7.remotefm1"
        const val MAX_EVENTS = 12
        private const val KEY_CORE = "core"
        private const val KEY_SOURCE = "source"
        private const val KEY_MODE = "mode"
        private const val KEY_BLE = "ble_address"
        /** the cores' order in the picker (RemoteFM1IOS CorePlayer.order) */
        val ORDER = listOf("choralroot", "felucca", "melodee", "fizz", "saadet", "boardwalk", "loom")

        @Volatile private var instance: AppModel? = null

        /** The model, made once per process with the first activity's launch options. */
        fun get(ctx: Context, options: LaunchOptions = LaunchOptions()): AppModel =
            instance ?: synchronized(this) { instance ?: AppModel(ctx.applicationContext, options).also { instance = it } }

        fun peek(): AppModel? = instance

        /** the core to select: [wanted] if this build has it, else the first of [ORDER] it has (iOS CorePlayer.pick) */
        fun pick(wanted: String?, ids: List<String>): String? =
            wanted?.takeIf { it in ids } ?: ORDER.firstOrNull { it in ids } ?: ids.firstOrNull()
    }
}
