// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Install/InstallFirmwarePage.swift (the page, its progress
// cover), Install/InstallPrompt.swift (InstallPromptView) and Cart/SendCartPage.swift, on A7's install/ and cart/.
// Settings › Install RemoteFM1 on the FM-1 and Send a DX7 cartridge; the prompt over the faceplate when the FM-1 on
// USB isn't running RemoteFM1 (or an older one). The panel lends the FM-1 to an install or a send (AppModel.pauseLink).
package com.quixotic7.remotefm1.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quixotic7.remotefm1.AppModel
import com.quixotic7.remotefm1.Backend
import com.quixotic7.remotefm1.LinkState
import com.quixotic7.remotefm1.LinkStatus
import com.quixotic7.remotefm1.RfmMidi
import com.quixotic7.remotefm1.UnitHello
import com.quixotic7.remotefm1.cart.AndroidCartTransport
import com.quixotic7.remotefm1.cart.AppCartSender
import com.quixotic7.remotefm1.cart.CartPanelPausing
import com.quixotic7.remotefm1.cart.CartSender
import com.quixotic7.remotefm1.install.AppInstaller
import com.quixotic7.remotefm1.install.FM1KitCredit
import com.quixotic7.remotefm1.install.FM1Port
import com.quixotic7.remotefm1.install.FM1Ports
import com.quixotic7.remotefm1.install.FirmwareInstaller
import com.quixotic7.remotefm1.install.FirmwarePackageRef
import com.quixotic7.remotefm1.install.InstallAwake
import com.quixotic7.remotefm1.install.InstallPromptSource
import com.quixotic7.remotefm1.install.InstallPromptState
import com.quixotic7.remotefm1.install.InstallPrompter
import com.quixotic7.remotefm1.install.InstallSummary
import com.quixotic7.remotefm1.install.PanelLinkPausing
import com.quixotic7.remotefm1.install.PromptMemory
import com.quixotic7.remotefm1.install.RemoteFM1Release
import com.quixotic7.remotefm1.install.RemoteFM1Version
import com.quixotic7.remotefm1.ui.sheet.FM1PagesModel
import com.quixotic7.remotefm1.ui.sheet.SheetButton
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetPage
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.sheet.SheetToggle
import com.quixotic7.remotefm1.ui.sheet.divider
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette
import com.quixotic7.remotefm1.ui.theme.RfmFonts
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** the app's panel as A7 sees it: the FM-1 lent to an install or a cartridge send, the prompt's view of the link */
class Fm1Panel(private val m: AppModel) : PanelLinkPausing, CartPanelPausing, InstallPromptSource {
    override val unitHello: UnitHello? get() = LinkStatus.hello(m.link.status())
    override suspend fun pauseForInstall() = m.pauseLink()
    override fun resumeAfterInstall(restart: Boolean) = m.resumeLink(restart)
    override suspend fun pauseForCart() = m.pauseLink()
    override fun resumeAfterCart(restart: Boolean) = m.resumeLink(restart)
    override val real: Boolean get() = m.backend is Backend.Device
    override val answered: Boolean get() = m.state is LinkState.Connected || m.state == LinkState.Starting
    override val searching: Boolean
        get() = m.link.status().optInt("wanted") != 0 &&
            (m.state == LinkState.Searching || m.state == LinkState.NotFound || m.state == LinkState.Lost)
    override val hello: UnitHello? get() = m.hello
    override fun ports(): List<FM1Port> = FM1Ports.scan(RfmMidi.entries().map { it.desc })
}

class AppFm1Pages(private val a: AppUi, ctx: Context, prefs: SharedPreferences, launch: UiLaunch) : FM1PagesModel {
    private val panel = Fm1Panel(a.model)
    private val device = a.model.backend is Backend.Device
    /** (MainActivity) the screen kept on during an install */
    var keepAwake: ((Boolean) -> Unit)? = null
    val installer: FirmwareInstaller = AppInstaller.shared(ctx, panel, device,
        InstallAwake({ keepAwake?.invoke(true) }, { keepAwake?.invoke(false) }), launch.installDemo)
    // (dc771c8) RemoteFM1 running in the app is a cartridge target too: its SysEx in and MIDI out
    val cart: CartSender = AppCartSender.shared(ctx, panel, device, launch.cartDemo, core = {
        object : com.quixotic7.remotefm1.cart.CoreMidi {
            override fun attach(on: Boolean) = a.model.midi.managerAttach(on)
            override suspend fun send(bytes: ByteArray) = a.model.midi.managerSend(bytes)
            override fun take() = a.model.midi.managerTake()
        }
    })
    val prompter = InstallPrompter(panel, installer, object : PromptMemory {
        override fun get(key: String) = prefs.getString(key, null)
        override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    }, forced = launch.fm1Prompt).also { it.start(MainScope()) }

    override val installBadge: String?
        get() = if (installer.isBusy) "installing…" else installer.updateAvailable?.let { "${it.version} available" }
            ?: installer.unitVersion?.toString()
    override val cartBusy get() = cart.busy
    override val installPage: @Composable (back: () -> Unit) -> Unit = { back -> InstallPage(a, installer, prompter, back) }
    override val cartPage: @Composable (back: () -> Unit) -> Unit = { back -> CartPage(a, cart, a.cartBackTitle, back) }

    /** a .syx opened with the app: added, the cartridge page opened */
    fun openSyx(ctx: Context, uri: android.net.Uri) {
        MainScope().launch {
            cart.add(AndroidCartTransport.read(ctx, uri))
            a.openSheet(SheetPageId.CART)
        }
    }
}

// ---------------------------------------------------------------- Install RemoteFM1 on the FM-1

@Composable
private fun InstallPage(a: AppUi, i: FirmwareInstaller, prompter: InstallPrompter, back: () -> Unit) {
    val st by i.state.collectAsState()
    val rel by i.releases.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showOlder by remember { mutableStateOf(false) }
    var showLicence by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { AppInstaller.pageAppeared(i) }
    DisposableEffect(Unit) {
        prompter.installPageOpen = true
        onDispose { prompter.installPageOpen = false }
    }
    fun leave() {
        if (i.isBusy) return
        i.leave()
        back()
    }
    SheetPage("Install RemoteFM1", "Settings", back = { leave() }) {
        // the FM-1
        SheetSectionTitle("The FM-1", top = 12)
        SheetList {
            val unit = st.summary?.let { if (it.deviceName.startsWith("RemoteFM1")) it.deviceName else it.deviceIdentity }
                ?: if (i.simulated) "Check the FM-1 to see" else i.unitVersion?.let { "RemoteFM1 $it" }
                    ?: a.model.hello?.identity?.takeIf { it.isNotEmpty() } ?: "not known yet"
            SheetRow("On it now", value = unit, chevron = false) {}
            SheetRow("Newest RemoteFM1", value = rel.newest?.let { "${it.version} · ${it.displayDate}" } ?: if (rel.fetching) "…" else "–",
                     divider = false, chevron = false) {}
        }
        val u = i.updateAvailable
        val v = i.unitVersion
        val n = rel.newest?.parsedVersion
        if (u != null) Status("Update available: RemoteFM1 ${u.version}", Palette.sheetAccent)
        else if (v != null && n != null && v >= n) Status("RemoteFM1 $v is the newest", Palette.linkedDot)
        rel.error?.let { e ->
            SheetNote(if (rel.releases.isEmpty()) "The list of releases couldn't be fetched: $e" else "Couldn't fetch the list ($e); showing the one from before.",
                      Palette.orange)
        }
        if (st.stage == FirmwareInstaller.Stage.idle) {
            // the package
            val installable = rel.releases.filter { it.installable }
            SheetSectionTitle("Install")
            if (installable.isNotEmpty()) {
                SheetList {
                    PackageRows(i, installable.first(), installable.size < 2)
                    if (installable.size > 1) {
                        SheetRow("Older releases", value = if (showOlder) "hide" else "${installable.size - 1}", divider = showOlder, chevron = false) {
                            showOlder = !showOlder
                        }
                        if (showOlder) installable.drop(1).forEachIndexed { k, r -> PackageRows(i, r, k == installable.size - 2) }
                    }
                }
                i.selectedRelease?.let { r ->
                    r.notes.take(3).forEach { SheetNote("• $it") }
                    r.parsedVersion?.let { pv -> if (pv < RemoteFM1Version.appMinimum)
                        SheetNote("This app needs RemoteFM1 ${RemoteFM1Version.appMinimum} or later for its panel.", Palette.orange) }
                    SheetNote("Licence ${r.license}. From remotefm1.com; its SHA-256 and identity are checked before anything is sent.")
                }
            } else {
                SheetNote(if (rel.fetching) "Fetching the list of releases…" else "No releases yet: check the internet connection.")
                if (!rel.fetching) Box(Modifier.padding(top = 8.dp)) { SheetButton("Try again") { scope.launch { i.releases.refresh() } } }
            }
            Warnings(a)
            if (i.panelHolder != null) SheetNote("RemoteFM1 in another app is using the FM-1.", Palette.orange)
            Box(Modifier.padding(top = 14.dp)) {
                SheetButton("Check the FM-1", accent = true, enabled = i.selected != null, tag = "install-check") { scope.launch { i.check() } }
            }
            SheetNote("Checking asks the FM-1 what it runs; nothing is written yet. The panel lets go of the FM-1 until you leave this page.")
        } else {
            when (st.stage) {
                FirmwareInstaller.Stage.preparing -> { SheetSectionTitle("Checking"); Status(st.activity, Palette.sheetAccent) }
                FirmwareInstaller.Stage.ready -> st.summary?.let { s ->
                    SheetSectionTitle("Ready")
                    SheetList {
                        SheetRow("On the FM-1", value = s.deviceName, chevron = false) {}
                        SheetRow("Installs", value = s.pkg.title, divider = s.needsConfirm, chevron = false) {}
                        if (s.needsConfirm) SheetToggle(confirmTitle(s), st.confirmed, divider = false) { i.setConfirmed(it) }
                    }
                    SheetNote(kindNote(s), if (s.kind == InstallSummary.Kind.Switching) Palette.orange else Palette.sheetMuted)
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (s.canInstall) SheetButton(if (s.kind == InstallSummary.Kind.MidUpdate) "Finish the install" else "Install", accent = true,
                                                      enabled = !s.needsConfirm || st.confirmed, tag = "install-go") { scope.launch { i.install() } }
                        SheetButton("Change") { i.reset() }
                    }
                    SheetNote("It reports ${s.deviceIdentity}; ${s.pkg.file} (${s.size / 1024} KB) installs as ${s.pkg.identity}.")
                    if (s.refusesV15) SheetNote("Going back to M-VAVE's V15 from it later may need a computer if the FM-1's bootloader was ever changed (m-bootloader, M-VAVE's open firmware).")
                }
                FirmwareInstaller.Stage.installing -> Status(st.phase, Palette.sheetAccent)
                FirmwareInstaller.Stage.finished -> {
                    SheetSectionTitle("Installed")
                    Status("The FM-1 now runs ${st.installed?.let { InstallSummary.nameOf(it) } ?: "the new firmware"} (${st.installed ?: "?"})", Palette.linkedDot)
                    SheetNote("The panel connects again by itself. You can leave this page.")
                    Box(Modifier.padding(top = 14.dp)) { SheetButton("Done", accent = true) { leave() } }
                }
                FirmwareInstaller.Stage.failed -> st.problem?.let { p ->
                    SheetSectionTitle("Not installed")
                    Text(p.headline, style = ui(16, 700))
                    SheetNote(p.explanation, Palette.sheetText)
                    SheetNote(if (p.deviceFine) "The FM-1 still works as it did." else "The FM-1 waits in its update loader until an install finishes.",
                              if (p.deviceFine) Palette.sheetMuted else Palette.orange)
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.canResume) SheetButton("Finish the install", accent = true) { scope.launch { i.resume() } }
                        SheetButton(if (p.canResume) "Check again" else "Try again", accent = !p.canResume) { scope.launch { i.check() } }
                    }
                    SheetRow(if (showDetails) "Hide details" else "Details", divider = false, chevron = false) { showDetails = !showDetails }
                    if (showDetails) Text(p.details + "\n" + st.events.takeLast(8).joinToString("\n"),
                                          style = TextStyle(fontFamily = RfmFonts.mono, fontSize = 10.sp, color = Palette.sheetMuted))
                }
                else -> {}
            }
            Warnings(a)
        }
        if (i.simulated) SheetNote("A simulated FM-1 (FM1Kit's SimulatedFM1): nothing is sent to a real one.", Palette.orange)
        SheetSectionTitle("About", top = 24)
        SheetNote(FM1KitCredit.LINE)
        SheetNote(FM1KitCredit.MFLASHER)
        SheetList(Modifier.padding(top = 8.dp)) {
            SheetRow(if (showLicence) "Hide FM1Kit's licence" else "FM1Kit's licence", divider = false, chevron = false) { showLicence = !showLicence }
        }
        if (showLicence) Text(FM1KitCredit.LICENCE, Modifier.padding(top = 8.dp),
                              style = TextStyle(fontFamily = RfmFonts.mono, fontSize = 10.sp, color = Palette.sheetMuted))
    }
}

@Composable
private fun PackageRows(i: FirmwareInstaller, r: RemoteFM1Release, last: Boolean) {
    val locked = i.choiceLocked
    r.pkg(FirmwarePackageRef.Kind.standard)?.let { p ->
        SheetRow("RemoteFM1 ${r.version}", value = "USB + Bluetooth", divider = !last || r.usbOnly != null, chevron = false,
                 check = i.selected == p, enabled = !locked) { i.select(p) }
    }
    r.pkg(FirmwarePackageRef.Kind.usbOnly)?.let { p ->
        SheetRow("RemoteFM1 ${r.version} USB only", value = "no Bluetooth", divider = !last, chevron = false,
                 check = i.selected == p, enabled = !locked) { i.select(p) }
    }
}

@Composable
private fun Warnings(a: AppUi) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.sheetRaised).padding(12.dp),
           verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("USB cable only: Bluetooth can't install firmware.", style = ui(12, 500))
        Text("Keep RemoteFM1 open and the ${a.deviceWord} awake until it finishes (about a minute).", style = ui(12, 500))
        Text("Don't unplug the FM-1 or switch it off while it writes.", style = ui(12, 500))
    }
}

@Composable
private fun Status(text: String, dot: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(top = 1.dp).height(8.dp).widthIn(min = 8.dp, max = 8.dp).clip(RoundedCornerShape(50)).background(dot))
        Text(text, style = ui(12, 500, Palette.sheetMuted))
    }
}

private fun confirmTitle(s: InstallSummary) = when (s.kind) {
    InstallSummary.Kind.SameIdentity -> "Reinstall it"
    InstallSummary.Kind.Switching -> "Switch, I've backed up"
    else -> "Yes, install it"
}

private fun kindNote(s: InstallSummary): String {
    val to = s.pkg.title
    return when (val k = s.kind) {
        is InstallSummary.Kind.RemoteFM1 -> {
            val v = RemoteFM1Version.parse(s.pkg.version) ?: return "Replaces RemoteFM1 ${k.from} with $to."
            if (v > k.from) "Updates RemoteFM1 ${k.from} to $v." else if (v < k.from) "Goes back from RemoteFM1 ${k.from} to $v."
            else "Installs RemoteFM1 $v again."
        }
        InstallSummary.Kind.Switching -> "This switches the FM-1 from ${s.deviceName} (${s.deviceIdentity}) to $to. Switching firmware " +
            "can erase the FM-1's projects and samples: back them up in its current firmware first."
        InstallSummary.Kind.SameIdentity -> "$to is already on the FM-1. Reinstalling it writes it again."
        InstallSummary.Kind.Reinstall -> "This can't be installed over itself."
        InstallSummary.Kind.MidUpdate -> "The FM-1 is waiting part-way through an update (it reports ${s.deviceIdentity}). Finish it with $to."
        else -> ""
    }
}

/** the install's progress, full screen (can't be dismissed: the write must not be left) */
@Composable
fun InstallProgressCover(i: FirmwareInstaller) {
    val st by i.state.collectAsState()
    if (st.stage != FirmwareInstaller.Stage.installing) return
    Box(Modifier.fillMaxSize().background(Palette.sheetBackground).clickable(enabled = true) {}.testTag("install-cover"),
        contentAlignment = Alignment.Center) {
        Column(Modifier.safeDrawingPadding().widthIn(max = 520.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Installing ${i.selected?.title ?: "RemoteFM1"}",
                 style = TextStyle(fontFamily = RfmFonts.title, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Palette.sheetText))
            LinearProgressIndicator(progress = { st.progress.fraction.toFloat() }, Modifier.fillMaxWidth().height(8.dp),
                                    color = Palette.sheetAccent, trackColor = Palette.sheetRaised)
            Text(st.phase, style = ui(15, 600, Palette.sheetMuted2))
            Text("Don't unplug the FM-1, switch it off or leave the app until it finishes.", style = ui(13, 500))
        }
    }
}

/** the prompt over the faceplate (iOS InstallPromptView): Install opens the install page, Not now hides it */
@Composable
fun InstallPromptCard(p: InstallPromptState.Prompt, install: () -> Unit, notNow: () -> Unit) {
    if (p == InstallPromptState.Prompt.None) return
    Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp)).background(Palette.sheetBackground)
        .padding(16.dp).testTag("install-prompt"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(p.title, style = ui(16, 700))
        Text(p.detail, style = ui(13, 400, Palette.sheetMuted2))
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.isInstall || p is InstallPromptState.Prompt.Update) SheetButton(if (p is InstallPromptState.Prompt.Update) "Update" else "Install", accent = true, action = install)
            SheetButton("Not now", action = notNow)
        }
    }
}

// ---------------------------------------------------------------- Send a DX7 cartridge

@Composable
private fun CartPage(a: AppUi, s: CartSender, backTitle: String, back: () -> Unit) {
    val st by s.state.collectAsState()
    // (the target is RemoteFM1 running in the app, not an FM-1)
    val toWhom = if (st.inApp) "RemoteFM1 in this app" else "the FM-1"
    val where = if (st.inApp) "in RemoteFM1 (this app)" else "on the FM-1"
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.quixotic7.remotefm1.cart.AppCartSender.coreTransport?.close() } }
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(st.files.lastOrNull()?.id) }
    var openBank by remember { mutableStateOf<Int?>(null) }
    var confirmRemove by remember { mutableStateOf<Int?>(null) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        s.rescan()
        s.refresh()
    }
    SheetPage("Send a DX7 cartridge", backTitle, back = { if (!s.busy) back() }) {
        SheetNote(if (st.firmware == CartSender.Firmware.v031)
            "RemoteFM1 keeps up to 16 DX7 32-voice cartridges (.syx) as user banks, USER1 to USER16, after the ROM banks. When all are used, a new one replaces the oldest. Installing other firmware erases them."
        else "RemoteFM1 0.3.0 keeps a DX7 32-voice cartridge (.syx) as a user bank, USER1 to USER3, after the ROM banks. A fourth replaces the oldest. Installing other firmware erases them.")
        // the banks on the FM-1
        val l = st.banks
        if (st.firmware == CartSender.Firmware.v031 && l != null) {
            SheetSectionTitle("Banks $where: ${l.banks.size} of ${l.max}")
            if (l.banks.isEmpty()) SheetNote("None yet. A cartridge sent goes to USER${l.next + 1}.") else {
                SheetList {
                    l.banks.forEachIndexed { k, b ->
                        SheetRow(b.label, value = b.name, divider = k < l.banks.size - 1 || openBank == b.slot, chevron = false) {
                            confirmRemove = null; newName = b.name; openBank = if (openBank == b.slot) null else b.slot
                        }
                        if (openBank == b.slot) Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (confirmRemove == b.slot) {
                                Text("Remove ${b.label} (${b.name}) from $toWhom? This can't be undone.", style = ui(13))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SheetButton("Remove", accent = true) { confirmRemove = null; openBank = null; scope.launch { s.delete(b.slot) } }
                                    SheetButton("Cancel") { confirmRemove = null }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SheetButton("Replace…", enabled = !st.busy) { a.cartReplaceSlot = b.slot; a.pickFiles?.invoke(AppUi.PICK_CART) }
                                    SheetButton("Remove", enabled = !st.busy) { confirmRemove = b.slot }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BasicTextField(newName, { newName = it.uppercase().take(10) }, Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                        .background(Palette.sheetRaised).padding(10.dp),
                                                   textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Palette.sheetText),
                                                   singleLine = true, cursorBrush = SolidColor(Palette.sheetAccent))
                                    SheetButton("Rename", enabled = !st.busy && newName.isNotBlank() && newName != b.name) {
                                        val n = newName; scope.launch { s.rename(b.slot, n) }
                                    }
                                }
                            }
                        }
                    }
                }
                SheetNote("A cartridge sent goes to USER${l.next + 1}${if (l.banks.any { it.slot == l.next }) ", replacing the oldest" else ""}.")
            }
            when (val x = st.state) {
                is CartSender.State.Working -> Status(x.text, Palette.sheetAccent)
                is CartSender.State.Done -> Status(x.text, Palette.linkedDot)
                is CartSender.State.Failed -> if (x.text.startsWith("Rename")) SheetNote(x.text, Palette.orange)
                else -> {}
            }
        } else if (st.firmware == CartSender.Firmware.v030) {
            SheetNote("RemoteFM1 0.3.0: up to 3 banks, sending only. Update RemoteFM1 to list, replace and remove banks.")
        }
        // the files
        SheetSectionTitle("Cartridges")
        if (st.files.isNotEmpty()) SheetList {
            st.files.forEachIndexed { k, f ->
                SheetRow(f.name, value = if (f.error != null) "can't send" else if (f.carts.size > 1) "${f.carts.size} banks" else null,
                         divider = k < st.files.size - 1 || shown == f.id, chevron = false) { shown = if (shown == f.id) null else f.id }
                if (shown == f.id) Column(Modifier.fillMaxWidth().divider(k < st.files.size - 1).padding(horizontal = 16.dp, vertical = 10.dp),
                                          verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (f.error != null) Text(f.error, style = ui(13, 400, Palette.orange)) else {
                        f.fixes?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, style = ui(12, 400, Palette.sheetMuted)) }
                        for (c in f.carts) c.voices.chunked(2).forEachIndexed { r, pair ->
                            Row {
                                pair.forEachIndexed { j, v ->
                                    Text("%2d  %s".format(r * 2 + j + 1, v), Modifier.weight(1f),
                                         style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.sheetText), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton(if (st.files.isEmpty()) "Choose .syx files…" else "Add files…", enabled = !st.busy, tag = "cart-add") {
                a.cartReplaceSlot = null; a.pickFiles?.invoke(AppUi.PICK_CART)
            }
            if (st.files.isNotEmpty()) SheetButton("Clear", enabled = !st.busy) { shown = null; s.clear() }
        }
        // the port
        if (st.ports.size > 1) {
            SheetSectionTitle("Send to")
            SheetList {
                st.ports.forEachIndexed { k, p ->
                    SheetRow(p.name, value = if (p.id == com.quixotic7.remotefm1.cart.CoreCartTransport.PORT.id) "in the app" else if (p.bluetooth) "Bluetooth" else "USB", divider = k < st.ports.size - 1, chevron = false,
                             check = st.chosen == p.id) { s.choose(p.id) }
                }
            }
        }
        if (st.target?.bluetooth == true) SheetNote("Over Bluetooth a cartridge takes about a second. USB is faster and surer.")
        // send
        val nCarts = st.carts.size
        SheetSectionTitle("Send")
        when (val x = st.state) {
            is CartSender.State.Sending -> {
                Status("Sending ${minOf(x.done + 1, x.total)} of ${x.total}…", Palette.sheetAccent)
                LinearProgressIndicator(progress = { x.done.toFloat() / maxOf(x.total, 1) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                                        color = Palette.sheetAccent, trackColor = Palette.sheetRaised)
            }
            else -> {
                if (st.ports.isEmpty()) SheetNote("No FM-1 found. Connect it with USB (or Bluetooth MIDI).", Palette.orange)
                Box(Modifier.padding(top = 4.dp)) {
                    SheetButton(if (nCarts <= 1) "Send to $toWhom" else "Send $nCarts cartridges to $toWhom", accent = true,
                                enabled = nCarts > 0 && st.target != null, tag = "cart-send") { scope.launch { s.send() } }
                }
                if (x !is CartSender.State.Done) st.resultText?.let { r ->
                    if (x is CartSender.State.Sent) Status(r, Palette.linkedDot) else SheetNote(r, Palette.orange)
                }
                if (!st.inApp) SheetNote("The app's panel lets go of the FM-1 while it sends, then connects again.")
            }
        }
        // (bd0711a, 41e8471) RemoteFM1 in the app and an FM-1 both here: the app's banks copied to the FM-1
        st.syncPair?.let { (_, dev) ->
            SheetSectionTitle("Sync")
            SheetButton("Copy this app's banks to ${dev.name}", enabled = !st.busy, tag = "cart-sync") { scope.launch { s.sync() } }
            st.lastSync?.let { r ->
                val text = r.failed ?: CartSender.syncText(r)
                val x = st.state
                if ((x is CartSender.State.Done && x.text == text) || (x is CartSender.State.Failed && x.text == text))
                    if (r.failed == null) Status(text, Palette.linkedDot) else SheetNote(text, Palette.orange)
            }
            SheetNote("Copies each bank RemoteFM1 in this app has and the FM-1 doesn't to the FM-1, with its name. The " +
                      "FM-1's own banks stay. RemoteFM1 can't send a bank back, so the app copies from the cartridges " +
                      "it has sent (Android/data/com.quixotic7.remotefm1/files/Cartridges; a .syx put there counts too).")
        }
        if (s.simulated) SheetNote("A simulated FM-1: nothing is sent to a real one.", Palette.orange)
        Spacer(Modifier.height(4.dp))
    }
}
