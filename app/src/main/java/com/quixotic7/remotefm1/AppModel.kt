// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The app's one model (process-wide, outlives the activity; the foreground service keeps the process): the FM-1 link
// and the engine on it, the sound source, the mode and the core. A1's part of RemoteFM1IOS's RemoteController (the
// link's state read ~15 times a second) and CorePlayer (Pattern vs Core mode, the core picker, the sound). A2: each
// core's flash kept (CoreFlash: files/cores/<id>.bin, saved when iOS saves), Restart (a power cycle), the audio
// output picked from Android's list and the stream reopened when its device goes (AudioOutputs, AudioRoute). After
// the second FM-1 test: the main thread never touches an Oboe stream; it says what it wants ([pushAudio]) and the
// audio-control thread (AudioControl) does the work and reports back ([audioState]).
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
import com.quixotic7.remotefm1.data.DataChanges
import com.quixotic7.remotefm1.data.DataSafety
import com.quixotic7.remotefm1.data.FlashHistory
import com.quixotic7.remotefm1.data.StateLibrary
import com.quixotic7.remotefm1.firmware.CoreInfo
import com.quixotic7.remotefm1.firmware.CoreRef
import com.quixotic7.remotefm1.firmware.FirmwareCenter
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
    val output: String? = null,         // an audio output kind (AudioRoute.AUTO, SPEAKER, .., USB)
) {
    val backend: Backend get() = if (fake || fakeBle) Backend.Fake(fakeBle) else Backend.Device

    companion object {
        fun from(extras: android.os.Bundle?): LaunchOptions {
            if (extras == null) return LaunchOptions()
            fun flag(k: String) = extras.getBoolean(k, false) || extras.getString(k) == "1" || extras.getString(k) == "true"
            // (A8) the fake and virtual units are for tests: a release build ignores them
            val dbg = BuildConfig.DEBUG
            return LaunchOptions(
                fake = dbg && flag("fake"), fakeBle = dbg && flag("fakeble"), virtual = dbg && flag("virtual"),
                source = extras.getString("source"), mode = extras.getString("mode"), core = extras.getString("core"),
                sound = if (extras.containsKey("sound")) flag("sound") else null,
                output = extras.getString("output"),
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
    /** (A4, wired in A3) the firmware: built-in cores and imported .rfm1 files by catalog key, Get firmware */
    val fw: FirmwareCenter = FirmwareCenter.android(ctx)
    /** (A5, wired in A3) safety copies, named states, the player's data hooks */
    val history = FlashHistory(FlashHistory.defaultRoot(ctx.filesDir))
    val stateLib = StateLibrary(StateLibrary.defaultRoot(ctx.filesDir))
    val safety = DataSafety(history, stateLib, { fw.flashFile(it) }) { k ->
        // (the flash size checked against a state's: the running engine's; the descriptor's flash_size can differ from
        // the image the device keeps: ChoralRoot 0.26.1 says 1048580, its flash image is 1048576)
        fw.core(k)?.let { DataSafety.Firmware(it.id, it.firmwareID, it.name, it.kind, it.version, it.sha256,
                                              if (k == booted) engine.flashSize else 0) }
    }
    val flash = CoreFlash(engine, File(ctx.filesDir, "cores"), versionOf = { fw.core(it)?.version },
                          fileOf = { fw.flashFile(it) }, idOf = { fw.core(it)?.firmwareID ?: it })
    /** the firmware to pick from (built in, then imported) */
    var catalog by mutableStateOf<List<CoreInfo>>(emptyList())
        private set
    /** moves when the states or the safety copies changed (DataChanges) */
    var dataGen by mutableIntStateOf(0)
        private set
    val outputs = AudioOutputs(ctx) { outputsChanged(it) }
    val audio = AudioControl(engine.streams, log = { Log.i(TAG, it) },
                             onNotice = { what, out -> main.post { audioNotice(what, out) } },
                             onState = { st -> main.post { audioState = st } })
    /** (A6) the MIDI side: the faceplate's feed, the learned map, the settings, controllers, learn, the OMX-27 */
    val midi = AppMidi(ctx, engine, link, prefs)
    /** (A5's wiped-memory card) the copy from before a version change the new version seems to have erased */
    var wipedCopy by mutableStateOf<FlashHistory.Snapshot?>(null)
        private set
    private val mirrorThread = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "RFMMirror") }
    private val mirrorQueued = java.util.concurrent.atomic.AtomicBoolean(false)

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
    /** the output picked, as a kind (AudioRoute.AUTO, SPEAKER, HEADPHONES, BLUETOOTH, USB); USB is never saved */
    var outputPick by mutableStateOf(AudioRoute.AUTO)
        private set
    var outputList by mutableStateOf<List<AudioOut>>(emptyList())
        private set
    var routeChanges by mutableIntStateOf(0)
        private set
    var audioRestarts by mutableIntStateOf(0)
        private set
    /** what the audio-control thread reports: Opening…, Playing on X, Busy, Failed */
    var audioState by mutableStateOf<AudioState>(AudioState.Off)
        private set
    /** Settings › Sound through USB devices (experimental): USB outputs listed in the picker (off by default) */
    var usbAudio by mutableStateOf(false)
        private set
    private var audioRetry = 0
    /** the selection asked for (a launch extra or the saved one) is not in this build: what was taken instead */
    var fallbackNote = ""
        private set
    var midiEntries by mutableStateOf<List<RfmMidi.Entry>>(emptyList())
        private set
    var bleConnecting by mutableStateOf(false)
        private set
    val previewFrame = mutableIntStateOf(0)                          // moves when [preview] changed
    /** (A3) Diagnostics shows [preview]: copied only while it does (the faceplate reads the engine itself) */
    var previewWanted = false
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
        fw.start(CoreInfo.builtInList(RfmEngine.cores()))
        catalog = fw.catalog.value
        fw.wireStore(changed = { main.post { catalog = fw.catalog.value } }, replace = { old, new ->
            // (an update replaces older files: a safety copy first; the selection follows to the new key)
            old.forEach { o -> runCatching { safety.safetyCopy(o.key, FlashHistory.Reason.BeforeUpdate(o.version, new.version)) } }
            main.post {
                catalog = fw.catalog.value
                if (old.any { it.key == selected }) select(new.key)
            }
        })
        fw.checkAtLaunch()
        flash.onWritten = { k, d -> safety.written(k, d) }
        DataChanges.add { main.post { dataGen++; wipedCopy = safety.wipedCopy } }
        DataChanges.add { mirrorSync() }
        mirrorSync()
        val r = fw.resolveSelection(options.core, CoreRef.fromJson(prefs.getString(KEY_CORE_REF, null)), prefs.getString(KEY_CORE, null))
        selected = r?.key ?: pick(options.core, cores.map { it.getString("id") }) ?: "choralroot"
        fallbackNote = r?.note ?: ""
        saveSelection()
        outputList = outputs.list
        outputPick = prefs.getString(KEY_OUTPUT, AudioRoute.AUTO)?.takeIf { it != AudioRoute.USB } ?: AudioRoute.AUTO
        usbAudio = prefs.getBoolean(KEY_USB_AUDIO, false)
        enableVirtual(options.virtual)                      // (listed to other apps only when asked for)
        RfmMidi.start(ctx, link.let { if (options.virtual) Fm1Candidate.VIRTUAL_NAME else "" })
        RfmMidi.addListener { main.post { if (!linkPaused) link.rescan(); refreshMidi() } }
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
            else -> if (prefs.getString(KEY_MODE, "core") == "core") Mode.CORE else Mode.PATTERN   // (A3: the faceplate plays the core, as on iOS)
        }
        useMode(m)
        options.output?.let { chooseOutput(it) }
        setSound(options.sound ?: prefs.getBoolean(KEY_SOUND, true), remember = options.sound == null)   // (A3: on at launch, as on iOS)
        tick()
    }

    // ---------------------------------------------------------------- control (the main thread)
    fun useSource(s: Source) {
        if (s != source) Log.i(TAG, "user: Sound source ${if (s == Source.FM1) "FM-1" else "this tablet (the link stops)"}")
        source = s
        prefs.edit().putString(KEY_SOURCE, if (s == Source.FM1) "fm1" else "tablet").apply()
        if (s == Source.FM1) link.start() else link.stop()
        if (mode == Mode.CORE) engine.attach(s == Source.FM1)
        playingChanged()
    }

    fun useMode(m: Mode) {
        Log.i(TAG, "mode: ${if (m == Mode.CORE) "firmware core $selected" else "test pattern"}")
        if (m == Mode.CORE) {
            if (!bootSelected()) {
                mode = Mode.PATTERN
                return
            }
            mode = m
            engine.attach(source == Source.FM1)
            engine.clockSwap(RfmEngine.CLOCK_NONE, RfmEngine.CLOCK_TIMER)  // (the core runs; the audio's when it plays)
            pushAudio()
        } else {
            mode = m
            pushAudio()                                         // (the stream closes on the audio thread)
            engine.setClock(RfmEngine.CLOCK_NONE)               // (now: a stream still open plays silence)
            flash.saveStopped()
            engine.attach(false)
        }
        prefs.edit().putString(KEY_MODE, if (mode == Mode.CORE) "core" else "pattern").apply()
        playingChanged()
    }

    fun select(id: String) {
        if (fw.core(id) == null) return
        selected = id
        saveSelection()
        if (id != booted) fallbackNote = ""
        if (mode == Mode.CORE) {
            // (the stream plays on: the engine keeps the clock through the load and boot, and its ring plays
            // silence meanwhile)
            if (!bootSelected()) useMode(Mode.PATTERN) else {
                engine.clockSwap(RfmEngine.CLOCK_NONE, RfmEngine.CLOCK_TIMER)
                pushAudio()
            }
        }
        playingChanged()
    }

    fun setSound(on: Boolean, remember: Boolean = true) {
        Log.i(TAG, "user: sound ${if (on) "on" else "off"} (output pick $outputPick)")
        soundOn = on
        if (remember) prefs.edit().putBoolean(KEY_SOUND, on).apply()
        if (on) audioRetry++                                    // (turned on again: past failures forgotten)
        pushAudio()
        playingChanged()
    }

    fun setPattern(on: Boolean) {
        patternOn = on
        link.setPattern(on)
    }

    fun restartLink() {
        Log.i(TAG, "user: Restart link")
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

    // ---------------------------------------------------------------- (A5, wired in A3) the firmware's data
    // iOS CorePlayer's saveState / loadState / restore / resetFlash: the flash saved (or the core let go) first, the
    // data step (DataSafety), then the firmware powered on again from its new flash. Each says what happened in
    // [message]; a refused step says why.

    /** the running core powered on again from its flash file (after a load, restore or reset wrote it) */
    private fun rebootFromFile() {
        booted = null
        if (mode != Mode.CORE) return
        if (!bootSelected()) useMode(Mode.PATTERN) else {
            engine.clockSwap(RfmEngine.CLOCK_NONE, RfmEngine.CLOCK_TIMER)
            pushAudio()
        }
    }

    private val clockRunning: Boolean
        get() = engine.status().optInt("clock").let { it == RfmEngine.CLOCK_AUDIO || it == RfmEngine.CLOCK_TIMER }

    /** Save state…: the memory saved now, then kept under [name] with MASTER ([master]) */
    fun saveState(name: String, master: Int?) {
        val key = selected
        val write = {
            message = try { safety.savedMessage(safety.saveState(key, name.trim(), master = master)) }
            catch (e: Exception) { e.message ?: "The state wasn't saved." }
        }
        if (booted == key) flash.saveNow(main, clockRunning) { flash.flush(); write() } else write()
    }

    /** a state's memory loaded (a safety copy of the current one first), MASTER sent */
    fun loadState(s: com.quixotic7.remotefm1.data.SavedState) {
        val key = selected
        val l = try { safety.prepareLoad(key, s) } catch (e: Exception) { message = e.message ?: "The state wasn't loaded."; return }
        if (booted == key) { flash.unload(); flash.flush() }
        safety.writeFlash(key, l.flash)
        rebootFromFile()
        l.master?.let { engine.event(4, 0, it) }               // (RFMC_EV_MASTER)
        message = l.message
    }

    /** a safety copy put back (the current memory kept as a copy first) */
    fun restoreCopy(snap: FlashHistory.Snapshot) {
        val key = selected
        val d = try { safety.prepareRestore(key, snap) } catch (e: Exception) { message = e.message ?: "Not restored."; return }
        if (booted == key) { flash.unload(); flash.flush() }
        safety.writeFlash(key, d)
        rebootFromFile()
        message = safety.restoreMessage(snap)
    }

    /** Reset firmware…: a copy of the memory kept, then the firmware started with a clean one */
    fun resetFlash() {
        val key = selected
        if (booted == key) { flash.unload(); flash.flush() }
        safety.reset(key)
        rebootFromFile()
        message = safety.resetMessage(key)
    }

    /** a safety copy of the running firmware's memory, saved first (a web manager opened: "before manager write") */
    fun safetyCopyNow(reason: FlashHistory.Reason) {
        val key = selected
        val copy = { kotlin.concurrent.thread(name = "RFMSafety") { runCatching { safety.safetyCopy(key, reason) } } }
        if (booted == key) flash.saveNow(main, clockRunning) { flash.flush(); copy() } else copy()
    }

    /** (Back up everything) the running core's memory saved now; then [done] on the main thread */
    fun saveForBackup(done: () -> Unit) {
        if (booted == null) return done()
        flash.saveNow(main, clockRunning) { flash.flush(); done() }
    }

    /** the app goes to the background (MainActivity.onStop): the flash saved now, the clock left running */
    fun didEnterBackground() {
        if (booted == null) return
        val clock = engine.status().optInt("clock")
        flash.saveNow(main, clock == RfmEngine.CLOCK_AUDIO || clock == RfmEngine.CLOCK_TIMER) { ok ->
            Log.i(TAG, "background: flash ${if (ok) "saved" else "not saved"}")
        }
    }

    /** the output to play on, as a kind (AudioRoute.*); the stream reopened on it when the sound is on */
    fun chooseOutput(kind: String) {
        Log.i(TAG, "user: audio output $kind")
        outputPick = kind
        prefs.edit().putString(KEY_OUTPUT, AudioRoute.saved(kind)).apply()
        pushAudio()
    }

    /** Settings › Sound through USB devices (experimental); remembered. Off: USB outputs are not offered. */
    fun useUsbAudio(on: Boolean) {
        Log.i(TAG, "user: sound through USB devices ${if (on) "on" else "off"}")
        usbAudio = on
        prefs.edit().putBoolean(KEY_USB_AUDIO, on).apply()
        if (!on && outputPick == AudioRoute.USB) outputPick = AudioRoute.AUTO
        pushAudio()
    }

    /** the outputs the picker offers: USB ones only with the experimental setting on */
    val pickable: List<AudioOut> get() = if (usbAudio) outputList else outputList.filterNot { it.usb }

    // the audio thread's notices: a USB output that played nothing (the Galaxy Tab A9+): Automatic again
    private fun audioNotice(what: String, out: AudioOut) {
        if (what == AudioControl.NOTICE_USB_FAILED) {
            if (outputPick == AudioRoute.USB) outputPick = AudioRoute.AUTO
            message = "Sound through USB didn't start on this tablet (${out.title}): back to Automatic."
            pushAudio()
        }
    }

    // what the audio-control thread should do now (never waits): the sound on in Core mode with a core booted, on the
    // picked output, then Automatic's; never a USB output unless picked (and the setting on)
    private fun pushAudio() {
        val list = outputs.list
        val ok = if (usbAudio) list else list.filterNot { it.usb }
        val pick = if (!usbAudio && outputPick == AudioRoute.USB) AudioRoute.AUTO else outputPick
        val first = AudioRoute.choose(ok, pick)
        val auto = AudioRoute.choose(ok, AudioRoute.AUTO)
        val outs = listOfNotNull(first, auto?.takeIf { it.id != first?.id })
        audio.want(AudioWant(mode == Mode.CORE && soundOn && booted != null, outs, list, audioRetry))
    }

    private fun outputsChanged(list: List<AudioOut>) {
        outputList = list
        routeChanges++
        if (outputPick == AudioRoute.USB && list.none { it.usb }) {
            outputPick = AudioRoute.AUTO                        // (the picked FM-1 went: never back to it by itself)
            Log.i(TAG, "audio: the picked USB output went away; the pick is Automatic again")
        }
        pushAudio()
    }

    private fun bootSelected(): Boolean {
        if (booted == selected) return true
        if (booted != null) {                                   // (another core: its flash saved, let go)
            flash.unload()
            booted = null
        }
        // (A4) a built-in core's flash from before the catalog keys adopted; another version's flash: a copy first (A5)
        runCatching { fw.adoptBuiltInFlash(selected) { d, id, v, from, to -> history.record(d, id, v, FlashHistory.Reason.BeforeUpdate(from, to)) } }
        runCatching { safety.checkVersion(selected) }
        val err = when (val t = fw.loadTarget(selected)) {
            is FirmwareCenter.LoadTarget.Native -> engine.load(t.id)
            is FirmwareCenter.LoadTarget.Wasm -> engine.loadWasm(t.path)
            null -> "not in the catalog"
        }
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

    private fun nameOf(id: String) = fw.core(id)?.name ?: id

    /** the selection remembered as iOS's CoreRef (and the plain id for older builds) */
    private fun saveSelection() {
        val c = fw.core(selected) ?: return
        prefs.edit().putString(KEY_CORE_REF, CoreRef(c).toJson()).putString(KEY_CORE, selected).apply()
    }

    // ---------------------------------------------------------------- (A7, wired in A3) the FM-1 lent out
    /** an install or a cartridge send has the FM-1: the link stopped and kept closed (no reopen, rescan or retry) */
    var linkPaused = false
        private set

    /** the panel's session ended and its ports closed until [resumeLink]; whether it was running */
    suspend fun pauseLink(): Boolean {
        val ran = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val r = source == Source.FM1
            linkPaused = true
            link.stop()
            r
        }
        repeat(60) {
            val st = link.status()
            if (st.optInt("wanted") == 0 && LinkState.of(st.optInt("state"), st.optInt("session_state", -1), false,
                    st.optInt("ble") != 0, st.optString("text"), null).first is LinkState.Stopped) return ran
            kotlinx.coroutines.delay(50)
        }
        return ran
    }

    /** the FM-1 given back; [restart]: the panel's session starts again */
    fun resumeLink(restart: Boolean) {
        main.post {
            linkPaused = false
            if (restart && source == Source.FM1) link.start()
            link.rescan()
        }
    }

    /** (A5) the wiped-memory card answered: the copy put back (Restore it), or the new memory kept */
    fun answerWiped(restore: Boolean) {
        val c = safety.answerWiped(restore)
        wipedCopy = safety.wipedCopy
        if (c != null) restoreCopy(c)
    }

    // (A5) the States mirror (the folder the user picked): synced off the main thread at launch, on a library change
    // and back in the foreground; changes that come while it runs make one more run

    fun mirrorSync() {
        if (!mirrorQueued.compareAndSet(false, true)) return
        mirrorThread.execute {
            mirrorQueued.set(false)
            runCatching { com.quixotic7.remotefm1.data.DataFolder.mirror(ctx, stateLib)?.sync() }
                .onSuccess { r -> if (r != null && (r.written + r.imported + r.removed + r.moved > 0 || r.failed.isNotEmpty()))
                    Log.i(TAG, "mirror: ${r.written} written, ${r.imported} imported, ${r.moved} moved, ${r.removed} removed, failed ${r.failed}") }
                .onFailure { Log.w(TAG, "mirror: $it") }
        }
    }

    /** a line for the sheet (an opened file's notice) */
    fun showMessage(t: String) { message = t }

    /** the catalog read again (a file added or deleted) */
    fun refreshCatalog() {
        fw.rescan()
        catalog = fw.catalog.value
    }

    /** (A4) an .rfm1 added (Add firmware…, Open with): copied in and checked off the main thread; played when good */
    fun addFirmware(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        fw.open(resolver, uri) { f ->
            main.post {
                refreshCatalog()
                if (f.error == null) {
                    select(f.key)
                    if (mode != Mode.CORE) useMode(Mode.CORE)
                } else {
                    message = "${f.file.ifEmpty { "The file" }}: ${f.error}"
                }
            }
        }
    }

    /** the running firmware's catalog entry */
    val current: CoreInfo? get() = catalog.firstOrNull { it.id == selected }

    /** the playing stream's device (0: none) */
    val playingOn: Int get() = engineStatus.optJSONObject("audio")?.takeIf { it.optInt("open") != 0 }?.optInt("device_id") ?: 0

    val coreName: String
        get() = fw.core(selected)?.let { "${it.name} ${it.version}" }
            ?: selected

    /** what the notification says: "RemoteFM1 is playing ChoralRoot" */
    val playingText: String
        get() = if (mode == Mode.CORE) "RemoteFM1 is playing ${nameOf(selected)}"
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
        if (linkPaused) return                                  // (A7: an install or a cartridge has the FM-1)
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
        }
        // the screen being sent: the core's in Core mode, the link's pattern otherwise (only while Diagnostics shows it)
        if (!previewWanted) {
            if (midiEntries.size != RfmMidi.entries().size) refreshMidi()
            reconnectTick(SystemClock.elapsedRealtime())
            return
        }
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
        midi.willClose()
        soundOn = false
        pushAudio()                                             // (closed on the audio thread)
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
        private const val KEY_CORE_REF = "coreRef"
        private const val KEY_SOURCE = "source"
        private const val KEY_MODE = "mode"
        private const val KEY_BLE = "ble_address"
        private const val KEY_OUTPUT = "audio_output"
        private const val KEY_USB_AUDIO = "usb_audio"
        private const val KEY_SOUND = "sound"
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
