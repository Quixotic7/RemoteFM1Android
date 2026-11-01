// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Faceplate/Theme.swift
// The faceplate's colours (design/README.md section 6, design/tokens.json): the five presets read from the bundled
// tokens.json (assets/tokens.json), the fixed colours, and the rules that derive a custom theme from its five chosen
// colours. Pure Kotlin (org.json only): tested on the JVM. ThemeStore keeps the choice.
package com.quixotic7.remotefm1.ui.theme

import org.json.JSONObject
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** an sRGB colour as 0..1 components; written as "#RRGGBB" */
data class Rgb(val r: Double, val g: Double, val b: Double) {
    init {
        require(r in 0.0..1.0 && g in 0.0..1.0 && b in 0.0..1.0)
    }

    val hex: String
        get() = "#%02X%02X%02X".format((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())

    override fun toString() = hex

    /** 0xAARRGGBB, opaque */
    val argb: Int
        get() = (0xFF shl 24) or ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()

    /** WCAG relative luminance */
    val luminance: Double get() = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)

    /** WCAG contrast ratio, 1..21 */
    fun contrast(o: Rgb): Double {
        val a = luminance
        val b = o.luminance
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    data class Lab(val l: Double, val a: Double, val b: Double)

    val lab: Lab
        get() {
            val R = lin(r); val G = lin(g); val B = lin(b)
            val x = (0.4124 * R + 0.3576 * G + 0.1805 * B) / WX
            val y = (0.2126 * R + 0.7152 * G + 0.0722 * B) / WY
            val z = (0.0193 * R + 0.1192 * G + 0.9505 * B) / WZ
            fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
            val fx = f(x); val fy = f(y); val fz = f(z)
            return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
        }

    /** the same colour with its L* moved by [dl] (+ lighter) */
    fun lightness(dl: Double): Rgb {
        val c = lab
        return fromLab(min(max(c.l + dl, 0.0), 100.0), c.a, c.b)
    }

    companion object {
        private const val WX = 0.95047
        private const val WY = 1.0
        private const val WZ = 1.08883

        fun of(r: Double, g: Double, b: Double) = Rgb(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))

        fun hex(s: String): Rgb? {
            var t = s.trim()
            if (t.startsWith("#")) t = t.substring(1)
            if (t.length != 6) return null
            val v = t.toLongOrNull(16) ?: return null
            return Rgb(((v shr 16) and 255) / 255.0, ((v shr 8) and 255) / 255.0, (v and 255) / 255.0)
        }

        fun argb(c: Int) = Rgb(((c shr 16) and 255) / 255.0, ((c shr 8) and 255) / 255.0, (c and 255) / 255.0)

        internal fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        internal fun gam(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

        fun fromLab(l: Double, a: Double, b: Double): Rgb {
            val fy = (l + 16) / 116
            val fx = fy + a / 500
            val fz = fy - b / 200
            fun fi(t: Double) = if (t * t * t > 216.0 / 24389) t * t * t else (116 * t - 16) / (24389.0 / 27)
            val x = fi(fx) * WX; val y = fi(fy) * WY; val z = fi(fz) * WZ
            val R = 3.2406 * x - 1.5372 * y - 0.4986 * z
            val G = -0.9689 * x + 1.8758 * y + 0.0415 * z
            val B = 0.0557 * x - 0.2040 * y + 1.0570 * z
            return of(gam(max(0.0, R)), gam(max(0.0, G)), gam(max(0.0, B)))
        }
    }
}

/** One faceplate theme: the roles of tokens.json ("roles") */
data class Theme(
    val name: String,
    val body: Rgb, val label: Rgb, val bed: Rgb, val key: Rgb, val hi: Rgb, val lo: Rgb, val keyText: Rgb,
    val knob: Rgb, val mark: Rgb, val bezel: Rgb, val lcd: Rgb, val ink: Rgb, val led: Rgb, val ledOff: Rgb,
) {
    /** the five colours a user picks (Faceplate, Keys and buttons, Knobs, LEDs, Display bezel) */
    data class Choice(val body: Rgb, val key: Rgb, val knob: Rgb, val led: Rgb, val bezel: Rgb)

    val choice: Choice get() = Choice(body, key, knob, led, bezel)

    operator fun get(role: String): Rgb? = when (role) {
        "body" -> body; "label" -> label; "bed" -> bed; "key" -> key; "hi" -> hi; "lo" -> lo; "keyText" -> keyText
        "knob" -> knob; "mark" -> mark; "bezel" -> bezel; "lcd" -> lcd; "ink" -> ink; "led" -> led; "ledOff" -> ledOff
        else -> null
    }

    /** iOS's Codable form: {"name": .., "body": "#RRGGBB", ...} (the custom theme as stored) */
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        for (r in ROLES) put(r, this@Theme[r]!!.hex)
    }

    companion object {
        val ROLES = listOf("body", "label", "bed", "key", "hi", "lo", "keyText", "knob", "mark", "bezel", "lcd", "ink",
                           "led", "ledOff")
        const val CUSTOM_NAME = "My theme"
        val NEAR_WHITE = Rgb.hex("#F6F0E8")!!
        val NEAR_BLACK = Rgb.hex("#1B1714")!!

        /** a theme from a role -> "#RRGGBB" table; null when a role is missing or not a colour */
        fun of(name: String, roles: (String) -> String?): Theme? {
            val c = ROLES.associateWith { r -> roles(r)?.let { Rgb.hex(it) } ?: return null }
            return Theme(name, c["body"]!!, c["label"]!!, c["bed"]!!, c["key"]!!, c["hi"]!!, c["lo"]!!, c["keyText"]!!,
                         c["knob"]!!, c["mark"]!!, c["bezel"]!!, c["lcd"]!!, c["ink"]!!, c["led"]!!, c["ledOff"]!!)
        }

        fun fromJson(o: JSONObject): Theme? = of(o.optString("name", CUSTOM_NAME)) { o.optString(it, "").ifEmpty { null } }

        /** the text colour, near-white or near-black, with the better contrast on [bg] */
        fun text(bg: Rgb): Rgb = if (bg.contrast(NEAR_WHITE) >= bg.contrast(NEAR_BLACK)) NEAR_WHITE else NEAR_BLACK

        /** a custom theme from its five colours (section 6's rules); lcd / ink (the mock LCD) from [base] */
        fun derived(c: Choice, name: String = CUSTOM_NAME, base: Theme? = null): Theme {
            val bed = c.key.lightness(-6.0)
            return Theme(name, body = c.body, label = text(c.body), bed = bed, key = c.key, hi = c.key.lightness(8.0),
                         lo = c.key.lightness(-18.0), keyText = text(c.key), knob = c.knob,
                         mark = if (c.knob.lab.l < 55) NEAR_WHITE else NEAR_BLACK, bezel = c.bezel,
                         lcd = base?.lcd ?: Rgb.hex("#C9D6EA")!!, ink = base?.ink ?: Rgb.hex("#13203A")!!, led = c.led,
                         ledOff = Rgb.fromLab(min(bed.lab.l + 20, 100.0), 0.0, 0.0))
        }
    }
}

/** tokens.json: the presets in the design's order, the default, the fixed colours */
class ThemeTokens(json: String) {
    val presets: List<Theme>
    val defaultName: String
    val fixed: Map<String, Rgb>             // indicatorLed, playGlyph, recGlyph, linkedDot
    val sheet: Map<String, Rgb>             // fixedColors.sheet

    init {
        val root = JSONObject(json)
        val themes = root.getJSONObject("themes")
        val keys = themes.keys().asSequence().toList()
        val names = ORDER.filter { it in keys } + keys.filter { it !in ORDER }.sorted()
        presets = names.mapNotNull { n -> themes.optJSONObject(n)?.let { o -> Theme.of(n) { o.optString(it, "").ifEmpty { null } } } }
        require(presets.isNotEmpty()) { "no themes" }
        defaultName = root.optString("defaultTheme", presets[0].name)
        val f = mutableMapOf<String, Rgb>()
        val s = mutableMapOf<String, Rgb>()
        root.optJSONObject("fixedColors")?.let { fc ->
            for (k in fc.keys()) {
                val v = fc.get(k)
                if (v is String) Rgb.hex(v)?.let { f[k] = it }
                if (k == "sheet" && v is JSONObject) for (sk in v.keys()) Rgb.hex(v.optString(sk))?.let { s[sk] = it }
            }
        }
        fixed = f
        sheet = s
    }

    companion object {
        /** the design's order of the presets (JSON objects have none) */
        val ORDER = listOf("Tangerine", "Midnight", "Mint", "Lilac", "Cream")

        /** (only if the asset were missing: Tangerine alone) */
        const val FALLBACK = """{"defaultTheme":"Tangerine","themes":{"Tangerine":{"body":"#EE7A3C","label":"#3A1606","bed":"#874332","key":"#924E3A","hi":"#A9614B","lo":"#64301F","keyText":"#FBEDE3","knob":"#1B1715","mark":"#F4EDE6","bezel":"#141210","lcd":"#C9D6EA","ink":"#13203A","led":"#FFE6A8","ledOff":"#6F6668"}}}"""
    }
}
