// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
//
// A small JSON reader and writer for the data files (pure Kotlin: org.json is a stub in JVM tests). The writer
// writes what Swift's JSONEncoder writes with .prettyPrinted and .sortedKeys ("key" : value, two-space indents, "/"
// escaped, dates as seconds), so a file made here reads the same on iOS and diffs cleanly; the reader takes any JSON.
// Values: Map<String, Any?> (written sorted), List<Any?>, String, Int / Long (integers), Double, Boolean, null.
package com.quixotic7.remotefm1.data

import java.io.IOException
import java.math.BigDecimal

class JsonError(msg: String) : IOException(msg)

object Json {
    /** [v] as Swift's JSONEncoder writes it: pretty, sorted keys ([pretty] false: compact, sorted) */
    fun write(v: Any?, pretty: Boolean = true): String = StringBuilder().also { put(it, v, 0, pretty) }.toString()

    private fun put(b: StringBuilder, v: Any?, depth: Int, pretty: Boolean) {
        when (v) {
            null -> b.append("null")
            is String -> quote(b, v)
            is Boolean -> b.append(v)
            is Int, is Long, is Short, is Byte -> b.append(v.toString())
            is Double -> b.append(number(v))
            is Float -> b.append(number(v.toDouble()))
            is Map<*, *> -> {
                val keys = v.keys.map { it as String }.sorted()
                if (keys.isEmpty()) { b.append(if (pretty) "{\n\n${ind(depth)}}" else "{}"); return }
                b.append('{')
                keys.forEachIndexed { i, k ->
                    if (i > 0) b.append(',')
                    if (pretty) b.append('\n').append(ind(depth + 1))
                    quote(b, k)
                    b.append(if (pretty) " : " else ":")
                    put(b, v[k], depth + 1, pretty)
                }
                if (pretty) b.append('\n').append(ind(depth))
                b.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { b.append(if (pretty) "[\n\n${ind(depth)}]" else "[]"); return }
                b.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) b.append(',')
                    if (pretty) b.append('\n').append(ind(depth + 1))
                    put(b, x, depth + 1, pretty)
                }
                if (pretty) b.append('\n').append(ind(depth))
                b.append(']')
            }
            else -> throw IllegalArgumentException("json: can't write ${v::class.java}")
        }
    }

    private fun ind(d: Int) = "  ".repeat(d)

    /** whole seconds without a fraction; else the shortest decimal (as Swift prints a Double) */
    fun number(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else BigDecimal.valueOf(d).toPlainString()

    private fun quote(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '/' -> b.append("\\/")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        b.append('"')
    }

    /** any JSON text; numbers come back as Long when integral and in range, else Double */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw JsonError("json: text after the value at ${p.i}")
        return v
    }

    /** a JSON object, or a JsonError */
    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: throw JsonError("json: not an object")

    private class Parser(val t: String) {
        var i = 0
        var depth = 0
        fun ws() { while (i < t.length && (t[i] == ' ' || t[i] == '\n' || t[i] == '\r' || t[i] == '\t')) i++ }
        fun fail(what: String): Nothing = throw JsonError("json: $what at $i")

        fun value(): Any? {
            ws()
            if (i >= t.length) fail("a value expected")
            return when (val c = t[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!t.startsWith(w, i)) fail("'$w' expected")
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            if (++depth > 64) fail("too deep")
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (t.getOrNull(i) == '}') { i++; depth--; return m }
            while (true) {
                ws()
                if (t.getOrNull(i) != '"') fail("a key expected")
                val k = str()
                ws()
                if (t.getOrNull(i) != ':') fail("':' expected")
                i++
                m[k] = value()
                ws()
                when (t.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; depth--; return m }
                    else -> fail("',' or '}' expected")
                }
            }
        }

        fun arr(): List<Any?> {
            if (++depth > 64) fail("too deep")
            i++
            val l = ArrayList<Any?>()
            ws()
            if (t.getOrNull(i) == ']') { i++; depth--; return l }
            while (true) {
                l.add(value())
                ws()
                when (t.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; depth--; return l }
                    else -> fail("',' or ']' expected")
                }
            }
        }

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= t.length) fail("an unterminated string")
                val c = t[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= t.length) fail("an unterminated string")
                        when (val e = t[i++]) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            '"', '\\', '/' -> b.append(e)
                            'u' -> {
                                if (i + 4 > t.length) fail("a bad \\u escape")
                                b.append(t.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("a bad \\u escape"))
                                i += 4
                            }
                            else -> fail("a bad escape")
                        }
                    }
                    c < ' ' -> fail("a control character in a string")
                    else -> b.append(c)
                }
            }
        }

        fun num(): Any {
            val s = i
            if (t[i] == '-') i++
            while (i < t.length && (t[i].isDigit() || t[i] in ".eE+-")) i++
            val n = t.substring(s, i)
            if (n.none { it in ".eE" }) n.toLongOrNull()?.let { return it }
            return n.toDoubleOrNull() ?: fail("a bad number '$n'")
        }
    }
}

/** typed reads of a decoded object (a missing or wrong field: a JsonError, or null for the optional ones) */
internal class JsonFields(val m: Map<String, Any?>, val what: String) {
    fun str(k: String): String = m[k] as? String ?: throw JsonError("$what: no $k")
    fun strOr(k: String, d: String): String = m[k] as? String ?: d
    fun num(k: String): Double = (m[k] as? Number)?.toDouble() ?: throw JsonError("$what: no $k")
    fun int(k: String): Int = intOrNull(k) ?: throw JsonError("$what: no $k")
    fun intOrNull(k: String): Int? = when (val v = m[k]) {
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else null
        is Double -> if (v == Math.floor(v) && Math.abs(v) < 2e9) v.toInt() else null
        else -> null
    }
    fun boolOrNull(k: String): Boolean? = m[k] as? Boolean
    @Suppress("UNCHECKED_CAST")
    fun objOrNull(k: String): Map<String, Any?>? = m[k] as? Map<String, Any?>
    fun list(k: String): List<Any?> = m[k] as? List<Any?> ?: throw JsonError("$what: no $k")
}
