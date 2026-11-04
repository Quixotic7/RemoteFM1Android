// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 App/GetFirmware.swift (GetFirmwarePage, CatalogDetailPage, CatalogRow, CatalogAction)
// and RFMKit UI/FirmwareTiles.swift (GetMoreButton, ChipPicker): 836a983 (thumbnails, All / Not installed / Recently
// updated), 9494ebe (Older versions), d88353e (a same-version rebuild: "Get the new build"), ac7254d (Update all).
//
// Get firmware: the list (thumbnail, name, version, author, category, size, one line, and Install / Installed /
// Update, or the download's progress: a tap cancels it), Update all, the filters, a check, the footer; a row opens the
// firmware's page (its description, the button, Play once it is here, Delete asked again in place, its older versions,
// the details, the links). Firmware built into the app isn't listed.
package com.quixotic7.remotefm1.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.quixotic7.remotefm1.Mode
import com.quixotic7.remotefm1.firmware.AvailableFirmware
import com.quixotic7.remotefm1.firmware.FirmwareListing
import com.quixotic7.remotefm1.firmware.FirmwareStore
import com.quixotic7.remotefm1.firmware.FirmwareVersion
import com.quixotic7.remotefm1.firmware.ImportedFirmware
import com.quixotic7.remotefm1.firmware.InstallState
import com.quixotic7.remotefm1.ui.sheet.FirmwareEntry
import com.quixotic7.remotefm1.ui.sheet.FirmwareThumb
import com.quixotic7.remotefm1.ui.sheet.Glyph
import com.quixotic7.remotefm1.ui.sheet.SheetButton
import com.quixotic7.remotefm1.ui.sheet.SheetConfirmRow
import com.quixotic7.remotefm1.ui.sheet.SheetList
import com.quixotic7.remotefm1.ui.sheet.SheetNote
import com.quixotic7.remotefm1.ui.sheet.SheetPage
import com.quixotic7.remotefm1.ui.sheet.SheetRow
import com.quixotic7.remotefm1.ui.sheet.SheetSectionTitle
import com.quixotic7.remotefm1.ui.sheet.divider
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette
import java.time.Instant

/** the firmware lists' choices kept (iOS @AppStorage "firmware.sort", "getfirmware.filter") */
class FirmwareListPrefs(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("rfm1", Context.MODE_PRIVATE)
    val used = FirmwareListing.PrefsStore(prefs)
    var sort by mutableStateOf(FirmwareListing.Sort.of(prefs.getString(FirmwareListing.SORT_KEY, null)))
        private set
    var filter by mutableStateOf(prefs.getString(FILTER_KEY, "all") ?: "all")
        private set
    /** moves when a firmware was picked (Recently used re-sorts) */
    var usedGen by mutableStateOf(0)
        private set

    fun sort(s: FirmwareListing.Sort) { sort = s; prefs.edit().putString(FirmwareListing.SORT_KEY, s.raw).apply() }
    fun filter(f: String) { filter = f; prefs.edit().putString(FILTER_KEY, f).apply() }
    fun picked() { usedGen++ }

    companion object {
        const val FILTER_KEY = "getfirmware.filter"
        @Volatile private var shared: FirmwareListPrefs? = null
        fun of(ctx: Context): FirmwareListPrefs = shared ?: synchronized(this) {
            shared ?: FirmwareListPrefs(ctx.applicationContext).also { shared = it }
        }
    }
}

/** the firmware the lists show: each firmware's newest version (and the selected one), in the chosen order (a
 *  composable caller collects store.allVersions: Recently updated's dates) */
fun shownCores(a: AppUi): List<com.quixotic7.remotefm1.firmware.CoreInfo> {
    val m = a.model
    val p = a.listPrefs
    p.usedGen
    return FirmwareListing.sorted(
        FirmwareListing.visible(m.catalog, m.selected, FirmwareListing.addedDates(m.fw.library.files.value)),
        p.sort, { m.fw.store.updated(it) }, p.used)
}

/** a row of chips, one picked (sorting, filters) */
@Composable
fun <T> ChipPicker(options: List<Pair<T, String>>, selected: T, tag: String = "chip", pick: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((v, t) in options) {
            val on = v == selected
            Box(Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (on) Palette.sheetAccent else Palette.sheetSurface)
                .then(if (on) Modifier else Modifier.border(1.dp, Palette.sheetBorder, RoundedCornerShape(50)))
                .clickable { pick(v) }.testTag("$tag-$v").padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(t, style = ui(13, 700, if (on) Palette.sheetBackground else Palette.sheetText))
            }
        }
    }
}

/** the big "Get more firmware" button under the built-in firmware (testers missed the row at the bottom) */
@Composable
fun GetMoreButton(count: Int, updates: Int, action: () -> Unit) {
    val more = if (count > 0) "$count to download" else "Download firmware from the catalog"
    val sub = if (updates > 0) "$more · $updates update${if (updates == 1) "" else "s"}" else more
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.sheetAccent).clickable { action() }
        .testTag("get-more").padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(Palette.sheetBackground), contentAlignment = Alignment.Center) {
            Text("↓", style = ui(17, 800, Palette.sheetAccent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Get more firmware", style = ui(17, 800, Palette.sheetBackground))
            Text(sub, style = ui(13, 600, Palette.sheetBackground.copy(alpha = 0.8f)))
        }
        if (updates > 0) Box(Modifier.heightIn(min = 24.dp).widthIn(min = 24.dp).clip(RoundedCornerShape(50))
            .background(Palette.sheetBackground).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text("$updates", style = ui(13, 800, Palette.sheetAccent))
        }
        Glyph("right", Palette.sheetBackground, 15)
    }
}

/** how many Get firmware lists that aren't on this device (the Get more button) */
fun toDownload(a: AppUi): Int {
    val fw = a.model.fw
    val builtIn = a.model.catalog.filter { !it.imported }
    return fw.store.list(builtIn).count { fw.store.state(it) == InstallState.NotInstalled }
}

/** Get firmware: the list, and a firmware's page */
@Composable
internal fun GetFirmwarePages(a: AppUi, back: () -> Unit) {
    val fw = a.model.fw
    val store = fw.store
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        fw.pageOpened()
        store.loadVersionsAsync()                                   // (the release dates: Recently updated)
    }
    val available by store.available.collectAsState()
    val checking by store.checking.collectAsState()
    val problem by store.problem.collectAsState()
    val progress by store.progress.collectAsState()
    val message by store.message.collectAsState()
    val failure by store.failure.collectAsState()
    val updating by store.updatingAll.collectAsState()
    val versions by store.allVersions.collectAsState()
    val report by store.report.collectAsState()
    val files by fw.library.files.collectAsState()
    files.size
    ThumbsWatcher(a)
    val builtIn = a.model.catalog.filter { !it.imported }
    val all = available.filter { !it.builtIn(builtIn) }
    val notes: @Composable () -> Unit = {
        if (failure.isNotEmpty()) SheetNote(failure, Palette.orange) else if (message.isNotEmpty()) SheetNote(message)
    }
    val o = open
    if (o != null) {
        val e = all.firstOrNull { it.id == o }
        if (e == null) {
            SheetPage(o, "Get firmware", back = { open = null }) {
                SheetNote(if (checking) "Getting the list…" else "This firmware isn't in the list any more.")
            }
            return
        }
        CatalogDetail(a, store, e, progress, versions.size, notes) { open = null }
        return
    }
    val filter = a.listPrefs.filter
    versions.size
    val list = when (filter) {
        "notInstalled" -> all.filter { store.state(it) == InstallState.NotInstalled }
        "updated" -> all.withIndex().sortedWith { x, y ->
            val p = store.updated(x.value.id)
            val q = store.updated(y.value.id)
            when {
                p != null && q != null && p != q -> q.compareTo(p)
                p != null && q == null -> -1
                p == null && q != null -> 1
                else -> x.index.compareTo(y.index)
            }
        }.map { it.value }
        else -> all
    }
    val updates = all.filter { store.state(it) is InstallState.Update }
    SheetPage("Get firmware", "Firmware", back = back) {
        // (every update in one go, one after another: each the same install as its own Update button)
        val u = updating
        if (updates.isNotEmpty() || u != null) {
            Box(Modifier.padding(top = 12.dp)) {
                SheetButton(if (u != null) "Updating ${maxOf(1, u.first)} of ${u.second}… (tap to stop)" else "Update all (${updates.size})",
                            accent = u == null, tag = "update-all") {
                    if (u != null) store.stopUpdates() else store.updateAllAsync(updates)
                }
            }
        }
        Box(Modifier.padding(top = 12.dp)) {
            ChipPicker(listOf("all" to "All", "notInstalled" to "Not installed", "updated" to "Recently updated"), filter, "filter") {
                a.listPrefs.filter(it)
            }
        }
        SheetSectionTitle(when (filter) { "notInstalled" -> "Not installed"; "updated" -> "Newest releases first"; else -> "Available" }, top = 14)
        if (list.isEmpty() && all.isNotEmpty()) {
            SheetNote(if (filter == "notInstalled") "Every firmware in the catalog is on this ${a.deviceWord}." else "No firmware here.")
        } else if (list.isEmpty()) {
            SheetNote(if (checking) "Getting the list…" else problem.ifEmpty { "No firmware to download yet." },
                      if (problem.isEmpty() || checking) Palette.sheetMuted else Palette.orange)
        } else {
            SheetList {
                list.forEachIndexed { i, it ->
                    CatalogRow(a, it, store.state(it), if (progress?.id == it.id) progress else null, progress != null, i < list.size - 1,
                               open = { open = it.id }, install = { store.installAsync(it) }, cancel = { store.cancel() })
                }
            }
        }
        notes()
        SheetList(Modifier.padding(top = 16.dp)) {
            SheetRow(if (checking) "Checking…" else "Check for updates", divider = false, chevron = false, enabled = !checking) {
                store.refreshAsync()
            }
        }
        if (problem.isNotEmpty() && list.isNotEmpty()) SheetNote(problem, Palette.orange)
        SheetNote(footer(store))
        if (report.isNotEmpty()) SheetNote("Last check: " + report.joinToString("; "))
    }
}

/** the catalog's images arrive after a check: the tiles read them again */
@Composable
fun ThumbsWatcher(a: AppUi) {
    val g by a.model.fw.thumbs.generation.collectAsState()
    LaunchedEffect(g) { (a.firmware as? AppFirmwarePages)?.thumbsChanged() }
}

/** a row of the list: the text opens the firmware's page, the button installs */
@Composable
private fun CatalogRow(a: AppUi, item: AvailableFirmware, state: InstallState, p: FirmwareStore.Progress?, busy: Boolean,
                       divider: Boolean, open: () -> Unit, install: () -> Unit, cancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).divider(divider).clickable { open() }
        .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp).testTag("catalog-${item.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FirmwareThumb(a.firmware.thumbnail(item.id), item.id, item.displayName, 10,
                      Modifier.size(56.dp).border(1.dp, Palette.sheetBorder, RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BadgeLine(catalogFantasy(a, item)) {
                Text(item.displayName + "  " + item.version + if (item.beta) "  BETA" else "", style = ui(15, 600), maxLines = 1)
            }
            Text(listOf(item.author, item.category, size(item.size)).filter { s -> s.isNotEmpty() && s != "–" }.joinToString(" · "),
                 style = ui(12, 500, Palette.sheetMuted), maxLines = 1)
            if (item.summary.isNotEmpty()) Text(item.summary, style = ui(12, 400, Palette.sheetMuted2), maxLines = 1)
        }
        CatalogAction(state, p, busy, install, cancel)
    }
}

/** a catalog entry is a Fantasy CFW: the installed file's manifest when it is here (it decides), else the catalog's flag */
private fun catalogFantasy(a: AppUi, e: AvailableFirmware): Boolean {
    val lib = a.model.fw.library
    val f = lib.files.value.firstOrNull { it.error == null && it.sha256.isNotEmpty() && it.sha256 == e.sha256 }
    return if (f != null) lib.details(f.sha256).fantasy.virtual else e.virtual
}

internal fun size(n: Long): String = when {
    n <= 0 -> "–"
    n < 1000 -> "$n bytes"
    n < 1_000_000 -> "${(n + 500) / 1000} KB"
    else -> "%.1f MB".format(n / 1_000_000.0)
}

internal fun progressText(p: FirmwareStore.Progress): String =
    p.fraction?.let { "Downloading… ${Math.round(it * 100)} %" } ?: "Downloading… ${size(p.received)}"

private fun footer(store: FirmwareStore): String {
    val names = store.feeds.map { it.name }
    val from = "The list comes from ${if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()}."
    val c = store.checked
    val whenText = if (c == null) " Not checked yet." else {
        val ago = (Instant.now().epochSecond - c.epochSecond)
        " Checked " + when {
            ago < 60 -> "just now"
            ago < 3600 -> "${ago / 60} min ago"
            ago < 86400 -> "${ago / 3600} h ago"
            else -> "${ago / 86400} days ago"
        } + "."
    }
    return from + whenText + " Firmware built into the app isn't listed."
}

/** Install, Update, Installed, or the download's progress (a tap cancels) */
@Composable
private fun CatalogAction(state: InstallState, p: FirmwareStore.Progress?, busy: Boolean, install: () -> Unit, cancel: () -> Unit) {
    if (p != null) {
        Text("${p.fraction?.let { Math.round(it * 100) } ?: "…"} % ✕", Modifier.height(44.dp).clickable { cancel() }.padding(horizontal = 8.dp, vertical = 12.dp),
             style = ui(13, 700, Palette.sheetAccent))
        return
    }
    when (state) {
        is InstallState.Installed -> Text("✓ Installed", Modifier.padding(horizontal = 6.dp), style = ui(13, 600, Palette.sheetMuted2))
        else -> {
            val accent = state is InstallState.Update
            Box(Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (accent) Palette.sheetAccent else Palette.sheetRaised)
                .clickable(enabled = !busy) { install() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(if (accent) "Update" else "Install", style = ui(13, 700, if (accent) Palette.sheetBackground else Palette.sheetText))
            }
        }
    }
}

/** an installed file played: selected, the firmware run, back to the Firmware page */
internal fun play(a: AppUi, f: ImportedFirmware) {
    val m = a.model
    m.refreshCatalog()
    m.select(f.key)
    if (m.mode != Mode.CORE) m.useMode(Mode.CORE)
    a.page = SheetPageId.FIRMWARE
}

@Composable
private fun CatalogDetail(a: AppUi, store: FirmwareStore, e: AvailableFirmware, p: FirmwareStore.Progress?, versionsGen: Int,
                          notes: @Composable () -> Unit, back: () -> Unit) {
    val m = a.model
    val lib = m.fw.library
    val state = store.state(e)
    val mine = lib.files.value.filter { it.error == null && (it.coreID == e.id || it.sha256 == e.sha256) }
    val here = mine.filter { it.coreID == e.id }.reduceOrNull { x, y -> if (FirmwareVersion.less(x.version, y.version)) y else x }
    val uri = LocalUriHandler.current
    var asking by remember(e.id) { mutableStateOf(false) }
    SheetPage(e.displayName, "Get firmware", back = back) {
        if (catalogFantasy(a, e)) Box(Modifier.padding(bottom = 6.dp)) { FantasyBadge() }
        Text(listOf(e.version, if (e.beta) "BETA" else "", if (e.author.isNotEmpty()) "· ${e.author}" else "").filter { it.isNotEmpty() }.joinToString(" "),
             style = ui(15, 600, Palette.sheetMuted2))
        val text = e.about.ifEmpty { e.summary }
        if (text.isNotEmpty()) Text(text, Modifier.padding(top = 12.dp), style = ui(14))
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p != null) {
                LinearProgressIndicator(progress = { (p.fraction ?: 0.0).toFloat() }, Modifier.fillMaxWidth(), color = Palette.sheetAccent,
                                        trackColor = Palette.sheetRaised)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(progressText(p), Modifier.weight(1f), style = ui(13, 600, Palette.sheetMuted2))
                    SheetButton("Cancel") { store.cancel() }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (state) {
                        InstallState.NotInstalled -> SheetButton("Install · ${size(e.size)}", accent = true, tag = "catalog-install") { store.installAsync(e) }
                        is InstallState.Update -> {
                            val rebuild = FirmwareVersion.compare(state.version, e.version) == 0
                            SheetButton(if (rebuild) "Get the new build" else "Update to ${e.version}", accent = true, tag = "catalog-update") {
                                store.installAsync(e)
                            }
                            Text(if (rebuild) "${state.version} is here, an older build" else "${state.version} is here",
                                 style = ui(13, 500, Palette.sheetMuted2))
                        }
                        is InstallState.Installed -> {
                            val f = here ?: lib.files.value.firstOrNull { it.error == null && it.sha256 == e.sha256 }
                            if (f != null) SheetButton("Play ${f.name}", accent = true, tag = "catalog-play") { play(a, f) }
                            Text("✓ Installed ${state.version}", style = ui(13, 600, Palette.sheetMuted2))
                        }
                    }
                }
                if (state is InstallState.Update) SheetNote("The update replaces the version here. Saved sounds and projects that used it open with the new one.")
                if (mine.isNotEmpty()) {
                    val what = if (mine.size == 1) "${mine[0].name} ${mine[0].version}" else "${mine.size} versions"
                    SheetList {
                        if (asking) SheetConfirmRow("Delete $what from this ${a.deviceWord}?", "Delete", { asking = false }) {
                            asking = false
                            mine.forEach { f -> a.firmware.delete(FirmwareEntry(f.key, f.coreID, f.name, f.version, true))?.invoke() }
                        } else SheetRow("Delete $what…", divider = false, chevron = false) { asking = true }
                    }
                }
            }
        }
        notes()
        OlderVersions(a, store, e, versionsGen)
        SheetSectionTitle("Details")
        SheetList {
            val rows = listOfNotNull("Version" to e.version, e.author.takeIf { it.isNotEmpty() }?.let { "Author" to it },
                                     e.category.takeIf { it.isNotEmpty() }?.let { "Category" to it },
                                     "Licence" to e.license.ifEmpty { "In the file" }, "Size" to size(e.size),
                                     state.installedVersion?.let { "On this device" to it },
                                     "SHA-256" to if (e.sha256.isEmpty()) "–" else e.sha256.take(16) + "…")
            rows.forEachIndexed { i, (k, v) -> SheetRow(k, value = v, divider = i < rows.size - 1, chevron = false) {} }
        }
        val links = listOf("Source code" to e.sourceURL, "Website" to e.site).filter { it.second.startsWith("http") }
        if (links.isNotEmpty()) SheetList(Modifier.padding(top = 12.dp)) {
            links.forEachIndexed { i, (k, u) ->
                SheetRow(k, value = android.net.Uri.parse(u).host, divider = i < links.size - 1) { runCatching { uri.openUri(u) } }
            }
        }
        SheetNote("From ${e.downloads.map { it.feed }.toSortedSet().joinToString(" and ")}. Before it's added, the file is checked " +
                  "against its size and SHA-256, and by the app's .rfm1 loader. It runs only when you pick it.")
    }
}

/** the firmware's other versions on GitHub: Install (beside the one here), or Play when it is here */
@Composable
private fun OlderVersions(a: AppUi, store: FirmwareStore, e: AvailableFirmware, versionsGen: Int) {
    versionsGen
    val others = store.versions(e.id).filter { it.version != e.version }
    if (others.isEmpty()) return
    val busy = store.progress.collectAsState().value != null
    val files = a.model.fw.library.files.value
    SheetSectionTitle("Older versions")
    SheetList {
        others.forEachIndexed { i, o ->
            val f = files.firstOrNull { it.error == null && it.coreID == e.id && it.version == o.version }
            SheetRow(o.version, value = if (f != null) "Play" else "Install · ${size(o.size)}", divider = i < others.size - 1,
                     chevron = false, enabled = !busy, tag = "older-${o.version}") {
                if (f != null) play(a, f) else store.installAsync(o)
            }
        }
    }
    SheetNote("For a state made with an older version. Installing one keeps the version here; they share the firmware's " +
              "memory, and a safety copy is kept if one resets it.")
}
