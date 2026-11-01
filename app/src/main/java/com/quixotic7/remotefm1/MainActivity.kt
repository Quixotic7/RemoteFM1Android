// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A1's screens (the faceplate is A3's): the main page (sound source, mode, the core, the sound, the screen being
 * sent), Diagnostics (RemoteFM1IOS App/DiagnosticsScreen.swift's cards: Unit, Link, Panel, Core, and the MIDI devices
 * Android reports) and the Bluetooth page (scan, connect, the remembered unit). Launch extras (adb `am start --ez`):
 * fake, fakeble, virtual (debug), source fm1|tablet, mode pattern|core, core <id>, sound, output <kind>,
 * page diagnostics|bluetooth|settings. Settings: sound through USB devices (experimental, off by default).
 */
class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    private var page by mutableStateOf(Page.MAIN)

    enum class Page { MAIN, DIAGNOSTICS, BLUETOOTH, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppModel.get(this, LaunchOptions.from(intent.extras))
        model.playingListener = { playing, text -> PlaybackService.update(applicationContext, playing, text) }
        PlaybackService.update(applicationContext, model.playing, model.playingText)
        handle(intent)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }

    // the app goes to the background: the flash saved (iOS's didEnterBackground); the sound and link go on
    override fun onStop() {
        super.onStop()
        model.didEnterBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // a USB device the manifest's filter matched (the FM-1 plugged in): logged; Android's MIDI service opens it and
    // RfmMidi finds it as a MIDI device (no USB permission is needed for that)
    private fun handle(i: Intent?) {
        when (i?.getStringExtra("page")) {
            "diagnostics" -> page = Page.DIAGNOSTICS
            "bluetooth" -> page = Page.BLUETOOTH
            "settings" -> page = Page.SETTINGS
        }
        if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            Log.i(AppModel.TAG, "USB attached: ${d?.let { "%04x:%04x %s / %s".format(it.vendorId, it.productId, it.manufacturerName, it.productName) }}")
            model.link.rescan()
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    @Composable
    private fun App() {
        BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
        Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(), color = Theme.background, contentColor = Color.White) {
            when (page) {
                Page.MAIN -> MainPage()
                Page.DIAGNOSTICS -> DiagnosticsPage()
                Page.BLUETOOTH -> BluetoothPage()
                Page.SETTINGS -> SettingsPage()
            }
        }
    }

    // ---------------------------------------------------------------- the main page
    @Composable
    private fun MainPage() {
        val m = model
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RemoteFM1", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                StatusPill(m.state, m.backend.isFake, m.source)
            }
            Label("Sound source")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(m.source == Source.TABLET, { m.useSource(Source.TABLET) }, { Text("This tablet · play by touch, FM-1 unused") })
                FilterChip(m.source == Source.FM1, { askNotifications(); m.useSource(Source.FM1) }, { Text("FM-1 · its keys and screen") })
            }
            Label("Mode")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(m.mode == Mode.PATTERN, { m.useMode(Mode.PATTERN) }, { Text("Test pattern") })
                FilterChip(m.mode == Mode.CORE, { m.useMode(Mode.CORE) }, { Text("Firmware core") })
                Spacer(Modifier.width(12.dp))
                if (m.mode == Mode.CORE) {
                    FilterChip(m.soundOn, { askNotifications(); m.setSound(!m.soundOn) }, { Text(if (m.soundOn) "Sound on" else "Sound off") })
                } else {
                    FilterChip(m.patternOn, { m.setPattern(!m.patternOn) }, { Text("Moving") })
                }
            }
            Label("Firmware")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                m.cores.forEach { c ->
                    val id = c.getString("id")
                    FilterChip(m.selected == id, { m.select(id) }, { Text(c.getString("name")) })
                }
            }
            if (m.mode == Mode.CORE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ m.restartFirmware() }) { Text("Restart firmware") }
                    Text("Its memory is kept (saved ${m.flash.saves} times this session)", fontSize = 12.sp, color = Theme.dim)
                }
            }
            Label("Audio output")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val outs = m.pickable
                AudioRoute.kinds(outs).forEach { k ->
                    FilterChip(m.outputPick == k, { m.chooseOutput(k) }, { Text(AudioRoute.kindTitle(outs, k)) })
                }
            }
            if (m.usbAudio && m.outputList.any { it.usb }) Text(AudioRoute.USB_WARNING, fontSize = 12.sp, color = Theme.accent)
            if (m.mode == Mode.CORE && m.soundOn) {
                val st = m.audioState
                Text(st.line, fontSize = 12.sp, color = if (st is AudioState.Failed || st is AudioState.Busy) Theme.accent else Theme.dim)
                AudioRoute.hint(m.outputList, m.playingOn)?.let { Text(it, fontSize = 12.sp, color = Theme.teal) }
            }
            if (m.message.isNotEmpty()) Text(m.message, color = Theme.accent, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Preview(260)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(m.statusText, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    m.hello?.let { Text("${it.name} ${it.firmwareVersion} · ${if (it.isBluetooth) "Bluetooth" else "USB"}", fontSize = 13.sp) }
                    if (m.mode == Mode.CORE) Text(m.coreName + if (m.soundOn) " · " + AudioRoute.name(m.outputList, m.playingOn) else "", fontSize = 13.sp)
                    Button({ page = Page.BLUETOOTH }) { Text("Bluetooth…") }
                    OutlinedButton({ page = Page.DIAGNOSTICS }) { Text("Diagnostics") }
                    OutlinedButton({ page = Page.SETTINGS }) { Text("Settings") }
                }
            }
        }
    }

    @Composable
    private fun Preview(size: Int) {
        val frame = model.previewFrame.intValue
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(Theme.card)) {
            if (frame != 0) {
                Image(model.preview.asImageBitmap().also { frame }, contentDescription = "the screen being sent to the FM-1",
                      modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
        }
    }

    // ---------------------------------------------------------------- Diagnostics
    @Composable
    private fun DiagnosticsPage() {
        val m = model
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Diagnostics", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                OutlinedButton({ m.restartLink() }) { Text("Restart link") }
                Button({ page = Page.MAIN }) { Text("Done") }
                StatusPill(m.state, m.backend.isFake, m.source)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Preview(300)
                    PanelCard()
                    MidiCard()
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (m.mode == Mode.CORE) CoreCard()
                    UnitCard()
                    LinkCard()
                }
            }
        }
    }

    @Composable
    private fun UnitCard() {
        Card("Unit (HELLO_R)") {
            val h = model.hello
            if (h == null) {
                Mono("No unit has answered yet.", Theme.dim)
            } else {
                Rows(listOf(
                    "name" to h.name,
                    "firmware" to "${h.firmwareVersion} (ver ${h.version})",
                    "proto" to "${h.proto}",
                    "identity" to h.identity,
                    "link" to (if (h.isBluetooth) "Bluetooth" else "USB") + " (HELLO_R)" +
                        model.stats.frameLink.let { if (it.isNotEmpty()) ", Android: $it" else "" },
                    "session" to (if (h.session) "running" else "none") + " (when it answered)",
                    "transports" to "0x%02X ".format(h.transports) + h.transportNames,
                    "caps" to "0x%04X ".format(h.caps) + h.capNames,
                    "sxq" to "${h.sxq}",
                    "body max" to "SysEx ${h.sxBodyMax}, bulk ${h.bkBodyMax}",
                    "screen" to "${h.screenW}x${h.screenH}, ${h.maxInFlight} in flight",
                ))
            }
        }
    }

    @Composable
    private fun LinkCard() {
        val s = model.stats
        Card("Link") {
            Rows(listOf(
                "state" to model.statusText,
                "frames" to "${s.frames}, acks ${s.acks}, ${"%.0f".format(s.fps)} fps",
                "ack ms" to "last ${s.ackMsLast}, avg ${s.ackMsAvg}, max ${s.ackMsMax}, timeouts ${s.ackTimeouts}",
                "in flight" to "${s.inFlight} frames, pending ${s.pending}",
                "bytes" to "wire ${s.wireBytes}, body ${s.bodyBytes}",
                "budget" to "${s.budget} B/frame, cap ${s.wireCap}, every ${s.frameMs} ms",
                "ble" to if (s.ble) "1, HOLD ${if (s.hold) "on" else "off"}" else "0",
                "held" to "${s.held}, reduced ${s.reduced}, refined ${s.refined}",
                "sessions" to "${s.sessions}, discoveries ${s.discoveries}, runs ${s.runs}",
                "events" to "${s.eventsIn}, dropped ${s.eventsDropped}, last " +
                    if (s.lastEventAgeMs < 0) "none" else "%.1f s ago".format(s.lastEventAgeMs / 1000.0),
                "PANEL_R" to "${s.panelR} heard, units lost ${s.lost}, session restarts ${maxOf(0L, s.sessions - 1)}, decode errors ${s.decodeErrs}",
                "touch" to s.touchText(model.hello),
                "TRS MIDI in" to "${s.trsMidiIn}" + if (s.trsMidiDropped > 0) ", dropped ${s.trsMidiDropped}" else "",
                "Android MIDI" to "${s.midiLinks} links, rx ${s.rxBytes} B, tx ${s.txBytes} B, short writes ${s.txShort}",
                "port drops" to "${s.linkDrops} (silent reopens ${s.silentReopens}, write fails ${s.writeFails}, read errors ${s.rxErrors})",
                "MIDI setup" to "${RfmMidi.changes} changes",
            ))
            if (model.lastLog.isNotEmpty()) Mono(model.lastLog, Theme.dim, 11)
        }
    }

    @Composable
    private fun PanelCard() {
        val m = model
        Card("Panel") {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (k in 0 until 27) Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (m.heldKeys and (1L shl k) != 0L) Theme.accent else Color.White.copy(alpha = 0.12f)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PanelEvent.buttonNames.forEachIndexed { b, n ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(3.dp))
                        .background(if (m.heldButtons and (1L shl b) != 0L) Theme.teal else Color.White.copy(alpha = 0.08f))
                        .padding(vertical = 3.dp), contentAlignment = Alignment.Center) { Text(n, fontSize = 9.sp, maxLines = 1) }
                }
            }
            Mono("MASTER ${m.master ?: "–"}", Theme.dim)
            if (m.events.isEmpty()) Mono("No events yet: press a key on the FM-1.", Theme.dim)
            else m.events.forEach { Mono(it.text) }
        }
    }

    @Composable
    private fun CoreCard() {
        val s = model.engineStatus
        val a = s.optJSONObject("audio")
        val clock = listOf("stopped", "audio", "timer (no sound)", "caller").getOrElse(s.optInt("clock")) { "?" }
        Card("Core") {
            Rows(listOf(
                "firmware" to model.coreName,
                "state" to if (s.optInt("halted") != 0) "halted" else if (s.optInt("booted") != 0) "running, clock $clock" else "not booted",
                "attached" to if (s.optInt("attached") != 0) "yes: the FM-1's panel plays it" else "no",
                "device" to "%.1f s, %d screens, %d keys".format(s.optInt("dev_ms") / 1000.0, s.optInt("snapshots"), s.optInt("keys_pressed")),
                "core load" to "${s.optInt("cpu_pct")} % (its own); core thread ${s.optInt("core_load_pct")} %",
                "render" to "avg ${s.optInt("render_avg_us")} us, max ${s.optInt("render_max_us")} us",
                "audio" to if (a != null && a.optInt("running") != 0)
                    "${a.optString("api")} ${a.optInt("rate")} Hz x${a.optInt("channels")}, ${a.optString("sharing")}" +
                        if (a.optInt("performance") == 12) ", low latency" else ""
                else if (a != null && a.optInt("dead") != 0) "disconnected: reopening" else "off",
                "output" to AudioRoute.name(model.outputList, model.playingOn) + " (pick: ${AudioRoute.kindTitle(model.outputList, model.outputPick)})" +
                    ", device ${model.playingOn}" + if (a != null && a.optInt("open") != 0)
                        ", MMAP ${a.optInt("mmap")}" + if (a.optInt("usb_safe") != 0) ", USB careful mode" else "" else "",
                "stream" to if (a != null && a.optInt("open") != 0)
                    "burst ${a.optInt("frames_per_burst")}, buffer ${a.optInt("buffer_size")}/${a.optInt("buffer_capacity")}, " +
                        "latency ${a.optDouble("latency_ms").let { if (it.isNaN() || it < 0) "?" else "%.1f ms".format(it) }}"
                else "–",
                "xruns" to "${a?.optInt("xruns") ?: 0} (stream), underruns ${s.optInt("underruns")} (engine, ${s.optInt("underrun_frames")} frames)",
                "route" to "${model.routeChanges} output changes, ${model.audio.opens} opens, ${model.audio.closes} closes, " +
                    "${a?.optInt("disconnects") ?: 0} disconnects",
                "audio thread" to "${model.audioState}; stuck closes ${model.audio.abandonedCloses}, stuck opens " +
                    "${model.audio.abandonedOpens}, stalls ${model.audio.stalls}, streams ${a?.optInt("streams") ?: 0}",
                "latency" to "${s.optInt("latency_frames")} frames (ring ${s.optInt("ring_target")}" +
                    (s.optDouble("rate").takeIf { it > 0 }?.let { ", %.1f ms".format(s.optInt("latency_frames") * 1000.0 / it) } ?: "") + ")",
                "flash" to "${model.flash.dir.name}/${model.flash.bootedId ?: "–"}.bin, ${s.optInt("flash_size") / 1024} KB, " +
                    "${model.flash.saves} saves, ${model.flash.reboots} restarts" +
                    model.flash.lastError.let { if (it.isNotEmpty()) ", error: $it" else "" },
            ))
        }
    }

    @Composable
    private fun MidiCard() {
        Card("MIDI devices (Android)") {
            val list = model.midiEntries
            if (list.isEmpty()) Mono("None: plug the FM-1 in over USB, or connect it on the Bluetooth page.", Theme.dim)
            list.forEach { e ->
                val d = e.desc
                Mono("${d.displayName} · ${d.transportName}" + (d.usbVid?.let { " %04x:%04x".format(it, d.usbPid ?: 0) } ?: ""))
                Mono("  " + when {
                    e.nativeId > 0 -> "linked: ${e.candidacy.why}"
                    e.opening -> "opening…"
                    e.error.isNotEmpty() -> e.error
                    else -> "not used: ${e.candidacy.why}"
                }, Theme.dim, 11)
            }
        }
    }

    // ---------------------------------------------------------------- Bluetooth
    @Composable
    private fun BluetoothPage() {
        val ctx = LocalContext.current
        var found by remember { mutableStateOf<List<BleScanner.Found>>(emptyList()) }
        var tick by remember { mutableStateOf(0) }
        val scanner = remember { BleScanner(ctx) { found = it; tick++ } }
        var missing by remember { mutableStateOf(scanner.missing()) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            missing = scanner.missing()
            if (missing.isEmpty()) scanner.start()
        }
        DisposableEffect(Unit) {
            if (missing.isEmpty()) scanner.start()
            onDispose { scanner.stop() }
        }
        val m = model
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bluetooth", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Button({ page = Page.MAIN }) { Text("Done") }
            }
            Text("An FM-1 running RemoteFM1 advertises as \"FM-1 XXXX\". Connect it here; the app reopens it next time.",
                 fontSize = 13.sp, color = Theme.dim)
            if (missing.isNotEmpty()) {
                Button({ ask.launch(missing.toTypedArray()) }) {
                    Text(if (Build.VERSION.SDK_INT >= 31) "Allow nearby devices" else "Allow location (needed to scan on Android 10 and 11)")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ scanner.start() }, enabled = !scanner.scanning) { Text(if (scanner.scanning) "Scanning…" else "Scan again") }
                    scanner.error?.let { Text(it, color = Theme.accent, fontSize = 13.sp) }
                    if (m.bleConnecting) Text("connecting…", fontSize = 13.sp)
                }
            }
            m.reconnect.address?.let { addr ->
                Card("Remembered unit") {
                    Mono("$addr · " + if (RfmMidi.bluetoothOpen(addr)) "open" else if (m.reconnect.paused) "disconnected" else "not open")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ m.disconnectBluetooth() }) { Text("Disconnect") }
                        OutlinedButton({ m.forgetBluetooth() }) { Text("Forget") }
                    }
                }
            }
            Card("Bluetooth MIDI devices nearby") {
                if (found.isEmpty()) Mono(if (scanner.scanning) "Looking…" else "None found.", Theme.dim)
                found.sortedByDescending { it.isFm1 }.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name.ifEmpty { "(no name)" }, fontWeight = if (f.isFm1) FontWeight.Bold else FontWeight.Normal)
                            Mono("${f.address} · ${f.rssi} dBm" + if (f.isFm1) " · FM-1" else " · not an FM-1: no HELLO goes to it", Theme.dim, 11)
                        }
                        Button({ scanner.stop(); m.connectBluetooth(f.device) }, enabled = !m.bleConnecting) {
                            Text(if (RfmMidi.bluetoothOpen(f.address)) "Open" else "Connect")
                        }
                    }
                }
            }
            MidiCard()
            Text("$tick", color = Color.Transparent, fontSize = 1.sp)
        }
    }

    // ---------------------------------------------------------------- Settings
    @Composable
    private fun SettingsPage() {
        val m = model
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Button({ page = Page.MAIN }) { Text("Done") }
            }
            Card("Audio") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Sound through USB devices (experimental)")
                        Text(AudioRoute.USB_WARNING, fontSize = 12.sp, color = Theme.accent)
                    }
                    Switch(m.usbAudio, { m.useUsbAudio(it) })
                }
            }
        }
    }

    // ---------------------------------------------------------------- bits
    @Composable
    private fun StatusPill(state: LinkState, fake: Boolean, source: Source) {
        val c = when (state) {
            is LinkState.Connected -> Color(0xFF34C759)
            LinkState.Searching, LinkState.Starting, LinkState.Lost, LinkState.Stopping -> Color(0xFFFFCC00)
            LinkState.NotFound, is LinkState.InUse -> Color(0xFFFF9500)
            is LinkState.Error -> Color(0xFFFF3B30)
            is LinkState.Stopped -> Color.Gray
        }
        val title = if (source == Source.TABLET) "This tablet" else state.title + if (fake) " (fake)" else ""
        Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }

    @Composable
    private fun Label(t: String) = Text(t.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Theme.dim)

    @Composable
    private fun Card(title: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Theme.card).padding(12.dp),
               verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(title)
            content()
        }
    }

    @Composable
    private fun Rows(rows: List<Pair<String, String>>) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEach { (k, v) ->
                Row {
                    Text(k, Modifier.width(110.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Theme.dim)
                    Text(v, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun Mono(t: String, color: Color = Color.Unspecified, size: Int = 12) =
        Text(t, fontFamily = FontFamily.Monospace, fontSize = size.sp, color = color)

    object Theme {
        val background = Color(0xFF0F1217)
        val card = Color(0xFF1C1F26)
        val accent = Color(0xFFFF8C29)
        val teal = Color(0xFF29C7BF)
        val dim = Color(0xFF9AA0AA)
    }
}
