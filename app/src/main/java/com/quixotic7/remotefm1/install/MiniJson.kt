// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// A small JSON reader for releases.json (Android's org.json is a stub in the JVM tests): objects as Map, arrays as
// List, strings, numbers as Double, true/false, null. Throws JsonError on anything malformed.
package com.quixotic7.remotefm1.install

class JsonError(why: String) : Exception(why)

object MiniJson {
    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw JsonError("extra text at ${p.i}")
        return v
    }

    private class P(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(): Any? {
            if (i >= s.length) throw JsonError("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw JsonError("bad literal at $i")
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonError("expected a key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw JsonError("expected : at $i")
                i++
                ws()
                m[k] = value()
                ws()
                if (i >= s.length) throw JsonError("unexpected end")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return m }
                throw JsonError("expected , or } at $i")
            }
        }

        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (i < s.length && s[i] == ']') { i++; return a }
            while (true) {
                ws()
                a.add(value())
                ws()
                if (i >= s.length) throw JsonError("unexpected end")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return a }
                throw JsonError("expected , or ] at $i")
            }
        }

        fun str(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw JsonError("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonError("unterminated string")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonError("bad escape")
                                b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw JsonError("bad escape"))
                                i += 4
                            }
                            else -> throw JsonError("bad escape")
                        }
                    }
                    else -> if (c < ' ') throw JsonError("control character in a string") else b.append(c)
                }
            }
        }

        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw JsonError("bad value at $st")
        }
    }
}
