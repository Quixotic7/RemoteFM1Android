// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/Theme.swift (ThemeStore, Palette) and Fonts.swift
// The chosen theme (SharedPreferences: the name, and the user's one theme as iOS's JSON), the fixed colours of
// tokens.json as Compose colours, and the four bundled OFL typefaces (res/font).
package com.quixotic7.remotefm1.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.quixotic7.remotefm1.R
import org.json.JSONObject

val Rgb.color: Color get() = Color(r.toFloat(), g.toFloat(), b.toFloat(), 1f)

/** the theme in use and the user's own (one slot, "My theme"); [override]: a theme used without being stored (the
 *  `theme` launch extra) */
class ThemeStore(private val prefs: SharedPreferences?, val tokens: ThemeTokens, private val override: String? = null) {
    val presets: List<Theme> = tokens.presets
    var custom by mutableStateOf<Theme?>(null)
        private set
    var current by mutableStateOf(presets.firstOrNull { it.name == tokens.defaultName } ?: presets[0])
        private set

    init {
        load()
    }

    /** every theme to choose from: the presets, then the user's */
    val all: List<Theme> get() = presets + listOfNotNull(custom)

    fun theme(name: String): Theme? = all.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun select(name: String) {
        val t = theme(name) ?: return
        current = t
        prefs?.edit()?.putString(KEY_SELECTED, t.name)?.apply()
    }

    /** stores [t] as the user's theme and selects it */
    fun saveCustom(t: Theme) {
        val c = t.copy(name = Theme.CUSTOM_NAME)
        custom = c
        current = c
        prefs?.edit()?.putString(KEY_CUSTOM, c.toJson().toString())?.putString(KEY_SELECTED, c.name)?.apply()
    }

    fun load() {
        prefs?.getString(KEY_CUSTOM, null)?.let { s -> runCatching { Theme.fromJson(JSONObject(s)) }.getOrNull()?.let { custom = it } }
        val name = override ?: prefs?.getString(KEY_SELECTED, null)
        if (name != null) theme(name)?.let { if (it != current) current = it }
    }

    companion object {
        const val KEY_SELECTED = "rfm1.theme"
        const val KEY_CUSTOM = "rfm1.theme.custom"

        @Volatile private var shared: ThemeTokens? = null

        /** the bundled copy of design/tokens.json (assets/tokens.json) */
        fun tokens(ctx: Context): ThemeTokens = shared ?: run {
            val t = runCatching { ThemeTokens(ctx.assets.open("tokens.json").bufferedReader().use { it.readText() }) }
                .getOrElse { ThemeTokens(ThemeTokens.FALLBACK) }
            shared = t
            Palette.load(t)
            t
        }
    }
}

/** the fixed colours (tokens.json fixedColors), with the design's values as defaults */
object Palette {
    private var t: ThemeTokens? = null
    internal fun load(tokens: ThemeTokens) { t = tokens }
    private fun f(k: String, d: String) = (t?.fixed?.get(k) ?: Rgb.hex(d)!!).color
    private fun s(k: String, d: String) = (t?.sheet?.get(k) ?: Rgb.hex(d)!!).color
    val indicatorLed get() = f("indicatorLed", "#FF3B2F")
    val playGlyph get() = f("playGlyph", "#4FE08A")
    val recGlyph get() = f("recGlyph", "#FF4436")
    val linkedDot get() = f("linkedDot", "#3BD47A")
    val sheetBackground get() = s("background", "#17140F")
    val sheetSurface get() = s("surface", "#211E19")
    val sheetRaised get() = s("raised", "#2A251F")
    val sheetLine get() = s("line", "#2E2A23")
    val sheetBorder get() = s("border", "#3A352D")
    val sheetText get() = s("text", "#F3EBDC")
    val sheetMuted get() = s("muted", "#A69C8A")
    val sheetMuted2 get() = s("muted2", "#B5AB99")
    val sheetAccent get() = s("accent", "#FF8A4C")
    val sheetDim = Color(0xFF6E6557)
    val red = Color(0.82f, 0.25f, 0.2f)
    val orange = Color(0xFFFF9F0A)
}

/** the UI's four typefaces (tokens.json "fonts"; SIL OFL, res/font) */
object RfmFonts {
    /** Chakra Petch 700: legends, knob labels, OCT, the wordmark, the faceplate's switches */
    val hardware = FontFamily(Font(R.font.chakrapetch_bold, FontWeight.Bold))

    /** DM Sans (variable) at 400..700: the chip, the sheet, the theme editor */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val ui = FontFamily(listOf(400, 500, 600, 700).map { w ->
        Font(R.font.dmsans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    })

    /** Unbounded 700: the sheet's and the editor's titles */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    val title = FontFamily(Font(R.font.unbounded, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))))

    /** DM Mono 500: hex values */
    val mono = FontFamily(Font(R.font.dmmono_medium, FontWeight.Medium))
}
