// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A small JSON reader and writer for the firmware files (index.json, the feeds, their cache), in plain Kotlin so the
// JVM tests run it (android's org.json is a stub there). Objects are LinkedHashMap<String, Any?>, arrays List<Any?>,
// numbers Long (no fraction or exponent) or Double, true / false, null.
package com.quixotic7.remotefm1.firmware

import java.io.IOException

object MiniJson {
    class ParseError(msg: String) : IOException(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw ParseError("JSON: text after the value at ${p.i}")
        return v
    }

    fun parse(bytes: ByteArray): Any? = parse(String(bytes, Charsets.UTF_8))

    /** [v] as JSON; [pretty]: 2-space indents; object keys sorted when [sortKeys] */
    fun write(v: Any?, pretty: Boolean = false, sortKeys: Boolean = false): String =
        StringBuilder().also { out(it, v, pretty, sortKeys, 0) }.toString()

    private fun out(b: StringBuilder, v: Any?, pretty: Boolean, sort: Boolean, depth: Int) {
        fun nl(d: Int) {
            if (pretty) {
                b.append('\n')
                repeat(d) { b.append("  ") }
            }
        }
        when (v) {
            null -> b.append("null")
            is Boolean -> b.append(v)
            is Int, is Long, is Short, is Byte -> b.append(v.toString())
            is Double, is Float -> {
                val d = v.toDouble()
                if (!d.isFinite()) b.append("null")
                else if (d == Math.rint(d) && kotlin.math.abs(d) < 9.0e15) b.append(d.toLong())
                else b.append(d.toString())
            }
            is Number -> b.append(v.toString())
            is String -> str(b, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { b.append("{}"); return }
                b.append('{')
                val keys = v.keys.map { it.toString() }.let { if (sort) it.sorted() else it }
                keys.forEachIndexed { n, k ->
                    if (n > 0) b.append(',')
                    nl(depth + 1)
                    str(b, k)
                    b.append(if (pretty) " : " else ":")
                    out(b, v[k], pretty, sort, depth + 1)
                }
                nl(depth)
                b.append('}')
            }
            is Iterable<*> -> {
                val l = v.toList()
                if (l.isEmpty()) { b.append("[]"); return }
                b.append('[')
                l.forEachIndexed { n, x ->
                    if (n > 0) b.append(',')
                    nl(depth + 1)
                    out(b, x, pretty, sort, depth + 1)
                }
                nl(depth)
                b.append(']')
            }
            else -> str(b, v.toString())
        }
    }

    private fun str(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) {
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append("\\u%04x".format(c.code))
                else -> b.append(c)
            }
        }
        b.append('"')
    }

    private class Parser(val t: String) {
        var i = 0

        fun ws() {
            while (i < t.length && (t[i] == ' ' || t[i] == '\t' || t[i] == '\n' || t[i] == '\r')) i++
        }

        fun fail(what: String): Nothing = throw ParseError("JSON: $what at $i")

        fun value(depth: Int): Any? {
            if (depth > 64) fail("too deep")
            ws()
            if (i >= t.length) fail("a value expected")
            return when (val c = t[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!t.startsWith(w, i)) fail("'$w' expected")
            i += w.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < t.length && t[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= t.length || t[i] != '"') fail("a key expected")
                val k = string()
                ws()
                if (i >= t.length || t[i] != ':') fail("':' expected")
                i++
                m[k] = value(depth + 1)
                ws()
                if (i < t.length && t[i] == ',') { i++; continue }
                if (i < t.length && t[i] == '}') { i++; return m }
                fail("',' or '}' expected")
            }
        }

        fun arr(depth: Int): List<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (i < t.length && t[i] == ']') { i++; return a }
            while (true) {
                a.add(value(depth + 1))
                ws()
                if (i < t.length && t[i] == ',') { i++; continue }
                if (i < t.length && t[i] == ']') { i++; return a }
                fail("',' or ']' expected")
            }
        }

        fun string(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= t.length) fail("an unterminated string")
                val c = t[i++]
                when {
                    c == '"' -> return b.toString()
                    c < ' ' -> fail("a control character in a string")
                    c != '\\' -> b.append(c)
                    else -> {
                        if (i >= t.length) fail("an unterminated escape")
                        when (val x = t[i++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > t.length) fail("a short \\u escape")
                                val u = t.substring(i, i + 4).toIntOrNull(16) ?: fail("a bad \\u escape")
                                i += 4
                                b.append(u.toChar())
                            }
                            else -> fail("a bad escape '\\$x'")
                        }
                    }
                }
            }
        }

        fun number(): Any {
            val s = i
            if (t[i] == '-') i++
            while (i < t.length && (t[i].isDigit() || t[i] in ".eE+-")) i++
            val n = t.substring(s, i)
            if (n.none { it == '.' || it == 'e' || it == 'E' }) n.toLongOrNull()?.let { return it }
            return n.toDoubleOrNull() ?: fail("a bad number '$n'")
        }
    }
}

// ---- reading a parsed value: typed fields, absent or the wrong type as null

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
internal fun Any?.arr(): List<Any?>? = this as? List<Any?>
internal fun Map<String, Any?>.str(k: String): String? = this[k] as? String
internal fun Map<String, Any?>.int(k: String): Long? = when (val v = this[k]) {
    is Long -> v
    is Double -> if (v == Math.rint(v)) v.toLong() else null
    else -> null
}
internal fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean

/** a field that is absent or null is fine (null), one of another type is not ([WrongType]) */
internal class WrongType(k: String) : IOException("JSON: $k has the wrong type")
internal fun Map<String, Any?>.optStr(k: String): String? = when (val v = this[k]) {
    null -> null
    is String -> v
    else -> throw WrongType(k)
}
internal fun Map<String, Any?>.optInt(k: String): Long? = when (val v = this[k]) {
    null -> null
    else -> int(k) ?: throw WrongType(k)
}
internal fun Map<String, Any?>.optBool(k: String): Boolean? = when (val v = this[k]) {
    null -> null
    is Boolean -> v
    else -> throw WrongType(k)
}
