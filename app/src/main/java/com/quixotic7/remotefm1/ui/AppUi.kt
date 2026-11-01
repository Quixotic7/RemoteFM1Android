// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 App/MainScreen.swift (LaunchOptions, the view per source, the sheet's routes)
// The app screen's state around the AppModel: the faceplate (FaceplateUi, its PanelFeed on the engine), the theme,
// the screen protector, the sheet's page, the full-screen pages (Diagnostics, Bluetooth), the models of the pages
// other milestones fill in, and the launch extras for screenshots (scripts/emu_shots.sh).
package com.quixotic7.remotefm1.ui

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.quixotic7.remotefm1.AppModel
import com.quixotic7.remotefm1.LinkState
import com.quixotic7.remotefm1.Mode
import com.quixotic7.remotefm1.Source
import com.quixotic7.remotefm1.ui.faceplate.EnginePanel
import com.quixotic7.remotefm1.ui.faceplate.FaceplateUi
import com.quixotic7.remotefm1.ui.faceplate.PanelChip
import com.quixotic7.remotefm1.ui.faceplate.PanelFeed
import com.quixotic7.remotefm1.ui.faceplate.ScreenProtector
import com.quixotic7.remotefm1.ui.sheet.DataPagesModel
import com.quixotic7.remotefm1.ui.sheet.FM1PagesModel
import com.quixotic7.remotefm1.ui.sheet.FirmwareEntry
import com.quixotic7.remotefm1.ui.sheet.FirmwarePagesModel
import com.quixotic7.remotefm1.ui.theme.ThemeStore

/** the faceplate's launch extras (adb `am start ... --es view controls`; RemoteFM1IOS's -view, -deck, ...) */
data class UiLaunch(
    val view: String? = null,           // full | controls | screen
    val deck: String? = null,           // buttons | keys
    val knobs: String? = null,          // main | kn
    val sheet: Boolean = false,         // the sheet open
    val sheetPage: String? = null,      // firmware | about | states | safetycopies | midi | audiooutput | settings | install | cart | getfirmware
    val theme: String? = null,          // a theme's name, used without being stored
    val themeEditor: Boolean = false,
    val protector: Boolean? = null,     // on | off (stored)
    val peel: Float? = null,            // the film drawn that far peeled (0..1)
    val displayAlone: Boolean = false,
    val steps: Boolean = false,
    val page: String? = null,           // (A1's) diagnostics | bluetooth | settings
    val installDemo: String? = null,    // (A7) check | writing | finished | cutoff: the install page runs by itself (simulated)
    val cartDemo: Boolean = false,      // (A7) made-up banks on a simulated FM-1
    val fm1Prompt: String? = null,      // (A7) install | unanswered | bluetooth | update: the prompt forced
) {
    companion object {
        fun from(b: Bundle?): UiLaunch {
            if (b == null) return UiLaunch()
            fun s(k: String) = b.get(k)?.toString()
            fun flag(k: String) = s(k).let { it == "true" || it == "1" || it == "on" }
            return UiLaunch(
                view = s("view"), deck = s("deck"), knobs = s("knobs"), sheet = flag("sheet"), sheetPage = s("sheetpage"),
                theme = s("theme"), themeEditor = flag("themeeditor"),
                protector = s("protector")?.let { it == "on" || it == "true" || it == "1" },
                peel = s("peel")?.toFloatOrNull(), displayAlone = flag("displayalone"), steps = flag("steps"), page = s("page"),
                installDemo = s("installdemo"), cartDemo = flag("cartdemo"), fm1Prompt = s("fm1prompt"),
            )
        }
    }
}

/** the sheet's pages (null: its main page, "Controls") */
enum class SheetPageId(val raw: String) {
    FIRMWARE("firmware"), ABOUT("about"), STATES("states"), SAFETY("safetycopies"), GET_FIRMWARE("getfirmware"),
    MIDI("midi"), AUDIO_OUTPUT("audiooutput"), SETTINGS("settings"), INSTALL("install"), CART("cart");

    companion object { fun of(s: String?) = entries.firstOrNull { it.raw == s } }
}

/** a page over everything (iOS's full-screen covers) */
enum class Overlay { DIAGNOSTICS, BLUETOOTH }

class AppUi(ctx: Context, val model: AppModel, launch: UiLaunch) {
    private val prefs = ctx.getSharedPreferences("rfm1", Context.MODE_PRIVATE)
    val ui = FaceplateUi()
    val feed = PanelFeed(EnginePanel(model.engine))
    val themes = ThemeStore(prefs, ThemeStore.tokens(ctx), launch.theme)
    val protector = ScreenProtector(prefs)
    var page by mutableStateOf<SheetPageId?>(null)
    var overlay by mutableStateOf<Overlay?>(null)
    /** where States and Safety copies go back to (null: the main page) */
    var dataBack by mutableStateOf<SheetPageId?>(SheetPageId.FIRMWARE)
    var saveOpen by mutableStateOf(false)
    var buttonNames by mutableStateOf(prefs.getBoolean(KEY_BUTTON_NAMES, true))
        private set
    /** "This phone" or "This tablet" */
    val deviceWord: String = if (ctx.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

    /** (MainActivity) a document picker: [PICK_FIRMWARE], [PICK_BACKUP], [PICK_CART]; the share sheet for a file */
    var pickFiles: ((String) -> Unit)? = null
    var share: ((java.io.File, String) -> Unit)? = null
    /** (MainActivity) the folder picker for Your data */
    var pickFolder: (() -> Unit)? = null

    // the pages other milestones fill in: A4's firmware/ and A5's data/ wired (WiredPages.kt); A7: install/ + cart/
    var firmware: FirmwarePagesModel = AppFirmwarePages(this)
    val appData = AppDataPages(this, ctx)
    var data: DataPagesModel = appData
    val appFm1 = AppFm1Pages(this, ctx, prefs, launch)
    var fm1: FM1PagesModel = appFm1
    /** (the cartridge page) Replace…: the bank slot the picked file goes to; null: added to the list */
    var cartReplaceSlot: Int? = null

    init {
        model.engine.setButtonNames(buttonNames)
        loadView(model.source)
        launch.view?.let { FaceplateUi.Page.of(it) }?.let { ui.page = it }
        launch.deck?.let { FaceplateUi.Deck.of(it) }?.let { ui.deck = it }
        launch.knobs?.let { FaceplateUi.KnobSet.of(it) }?.let { ui.knobSet = it }
        ui.stepNumbers = launch.steps || prefs.getBoolean(KEY_STEPS, false)
        launch.protector?.let { protector.set(it) }
        launch.peel?.let { protector.startFold = it }
        page = SheetPageId.of(launch.sheetPage)
        if (launch.sheet || page != null) ui.sheetOpen = true
        if (launch.themeEditor) ui.editorOpen = true
        if (launch.displayAlone) ui.displayAlone = true
        when (launch.page) {
            "diagnostics" -> overlay = Overlay.DIAGNOSTICS
            "bluetooth" -> overlay = Overlay.BLUETOOTH
            "settings" -> { page = SheetPageId.SETTINGS; ui.sheetOpen = true }
        }
    }

    val current: FirmwareEntry? get() = firmware.entries.firstOrNull { it.id == model.selected }

    val chip: PanelChip
        get() = if (model.source != Source.FM1) PanelChip.EMULATOR else when (model.state) {
            is LinkState.InUse -> PanelChip.IN_USE
            is LinkState.Connected -> PanelChip.LINKED
            else -> PanelChip.SEARCHING
        }

    val running: Boolean get() = model.mode == Mode.CORE && model.engineStatus.optInt("booted") != 0

    fun openSheet(p: SheetPageId? = null) {
        page = p
        ui.sheetOpen = true
    }

    fun closeSheet() {
        ui.sheetOpen = false
    }

    /** States (Save state…: its field open) or Safety copies, back to [from] */
    fun openData(p: SheetPageId, from: SheetPageId?, saving: Boolean = false) {
        dataBack = from
        saveOpen = saving
        page = p
    }

    fun useButtonNames(on: Boolean) {
        buttonNames = on
        prefs.edit().putBoolean(KEY_BUTTON_NAMES, on).apply()
        model.engine.setButtonNames(on)
    }

    fun useStepNumbers(on: Boolean) {
        ui.stepNumbers = on
        prefs.edit().putBoolean(KEY_STEPS, on).apply()
    }

    // ---- the view per sound source (iOS: the last view used with that source)
    private fun viewKey(s: Source) = "rfm1.view.${if (s == Source.FM1) "fm1" else "phone"}"

    fun saveView() {
        prefs.edit().putString(viewKey(model.source), "${ui.page.raw},${ui.deck.raw},${ui.knobSet.raw}").apply()
    }

    private fun loadView(s: Source) {
        val v = prefs.getString(viewKey(s), "")!!.split(",")
        ui.page = FaceplateUi.Page.of(v.getOrNull(0)) ?: FaceplateUi.Page.FULL
        ui.deck = FaceplateUi.Deck.of(v.getOrNull(1)) ?: FaceplateUi.Deck.BUTTONS
        ui.knobSet = FaceplateUi.KnobSet.of(v.getOrNull(2)) ?: FaceplateUi.KnobSet.MAIN
    }

    /** the sound source picked on the sheet (the last view of that source comes back) */
    fun pick(s: Source) {
        if (s == model.source) {
            if (s == Source.FM1) model.useSource(s)
            return
        }
        saveView()
        model.useSource(s)
        loadView(s)
    }

    companion object {
        const val KEY_BUTTON_NAMES = "rfm1.buttonNames"
        const val KEY_STEPS = "rfm1.stepNumbers"
        const val PICK_FIRMWARE = "firmware"
        const val PICK_BACKUP = "backup"
        const val PICK_CART = "cart"
    }
}
