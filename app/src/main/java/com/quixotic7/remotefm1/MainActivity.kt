// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.quixotic7.remotefm1.data.DataFolder
import com.quixotic7.remotefm1.data.Incoming
import com.quixotic7.remotefm1.data.StateFile
import com.quixotic7.remotefm1.firmware.FirmwareCenter
import java.io.File
import kotlinx.coroutines.launch
import com.quixotic7.remotefm1.cart.AndroidCartTransport
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.quixotic7.remotefm1.ui.AppUi
import com.quixotic7.remotefm1.ui.MainScreen
import com.quixotic7.remotefm1.ui.Overlay
import com.quixotic7.remotefm1.ui.SheetPageId
import com.quixotic7.remotefm1.ui.UiLaunch

/**
 * The app's one activity: the faceplate (ui/MainScreen.kt) edge to edge, the system bars hidden as on iOS. Launch
 * extras (adb `am start --ez` / `--es`): fake, fakeble, virtual (debug), source fm1|tablet, mode pattern|core,
 * core <id>, sound, output <kind>, page diagnostics|bluetooth|settings, and the faceplate's (UiLaunch): view, deck,
 * knobs, sheet, sheetpage, theme, themeeditor, protector on|off, peel 0..1, displayalone, steps.
 */
class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel
    internal lateinit var appUi: AppUi
    private var picking = AppUi.PICK_FIRMWARE
    // the document pickers (Add firmware…, Restore from backup…) and the Your data folder
    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> picked(uris) }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) appUi.appData.folderPicked(DataFolder.set(this, u))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        model = AppModel.get(this, LaunchOptions.from(intent.extras))
        model.playingListener = { playing, text -> PlaybackService.update(applicationContext, playing, text) }
        PlaybackService.update(applicationContext, model.playing, model.playingText)
        appUi = AppUi(this, model, UiLaunch.from(intent.extras))
        appUi.pickFiles = { kind ->
            picking = kind
            pick.launch(if (kind == AppUi.PICK_BACKUP) arrayOf("application/zip", "application/octet-stream", StateFile.MIME, "*/*") else arrayOf("*/*"))
        }
        appUi.pickFolder = { folder.launch(null) }
        appUi.share = { f, mime -> shareFile(f, mime) }
        appUi.appFm1.keepAwake = { on ->
            runOnUiThread {
                if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        handle(intent)
        setContent { MainScreen(appUi) { askNotifications() } }
    }

    // (A5) back in the foreground: the States mirror synced (off the main thread)
    override fun onStart() {
        super.onStart()
        model.mirrorSync()
    }

    // the app goes to the background: the flash saved (iOS's didEnterBackground); the sound and link go on
    override fun onStop() {
        super.onStop()
        model.didEnterBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
        when (intent.getStringExtra("page")) {
            "diagnostics" -> appUi.overlay = Overlay.DIAGNOSTICS
            "bluetooth" -> appUi.overlay = Overlay.BLUETOOTH
            "settings" -> appUi.openSheet(SheetPageId.SETTINGS)
        }
    }

    // a USB device the manifest's filter matched (the FM-1 plugged in): logged; Android's MIDI service opens it and
    // RfmMidi finds it as a MIDI device (no USB permission is needed for that)
    private fun handle(i: Intent?) {
        open(i)
        if (i?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            Log.i(AppModel.TAG, "USB attached: ${d?.let { "%04x:%04x %s / %s".format(it.vendorId, it.productId, it.manufacturerName, it.productName) }}")
            model.link.rescan()
        }
    }

    private fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        when (picking) {
            AppUi.PICK_BACKUP -> appUi.appData.restoreFrom(uris)
            AppUi.PICK_CART -> {
                val slot = appUi.cartReplaceSlot
                appUi.cartReplaceSlot = null
                val s = appUi.appFm1.cart
                kotlinx.coroutines.MainScope().launch {
                    if (slot != null) s.replace(slot, AndroidCartTransport.read(this@MainActivity, uris[0]))
                    else uris.forEach { s.add(AndroidCartTransport.read(this@MainActivity, it)) }
                }
            }
            else -> uris.forEach { model.addFirmware(contentResolver, it) }
        }
    }

    /** a file through the share sheet (the FileProvider: the cache's share/) */
    private fun shareFile(f: File, mime: String) {
        val u = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, f.name))
    }

    // a file opened with the app: a .rfm1state (imported, its States page), else an .rfm1 (added and played)
    private fun open(i: Intent?) {
        if (i == null || i.action == Intent.ACTION_MAIN || i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val uris = Incoming.uris(i)
        val states = uris.filter { Incoming.name(contentResolver, it).lowercase().endsWith(".${StateFile.EXTENSION}") }
        if (states.isNotEmpty()) {
            var note = ""
            for (u in states) {
                Incoming.importState(contentResolver, u, model.stateLib).onSuccess { r ->
                    val c = model.catalog.firstOrNull { it.firmwareID == r.state.firmwareID }
                    if (c != null && c.id != model.selected) model.select(c.id)
                    note = Incoming.notice(r, c != null, model.fw.store.available.value.any { it.id == r.state.firmwareID })
                }.onFailure { note = it.message ?: "Not imported." }
            }
            appUi.data.let { model.showMessage(note) }
            appUi.openData(SheetPageId.STATES, null)
            appUi.ui.sheetOpen = true
            return
        }
        // (A7) a DX7 cartridge: the cartridge page
        val syx = uris.filter { Incoming.name(contentResolver, it).lowercase().endsWith(".syx") || i.type?.contains("syx") == true || i.type?.contains("sysex") == true }
        if (syx.isNotEmpty()) {
            syx.forEach { appUi.appFm1.openSyx(this, it) }
            return
        }
        FirmwareCenter.firmwareUri(i, contentResolver)?.let { model.addFirmware(contentResolver, it) }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }
}
