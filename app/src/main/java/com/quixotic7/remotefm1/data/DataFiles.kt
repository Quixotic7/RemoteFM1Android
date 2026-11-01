// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
//
// Small file helpers for the data package: a whole file written atomically (a temp file beside it, renamed in, as
// Swift's .atomic), and a log that works on the JVM too.
package com.quixotic7.remotefm1.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object DataFiles {
    /** [data] written to [f] whole: a temp file beside it, renamed in */
    @Throws(IOException::class)
    fun writeAtomic(f: File, data: ByteArray) {
        val dir = f.absoluteFile.parentFile ?: throw IOException("no folder for $f")
        mkdirs(dir)
        val tmp = File(dir, ".${f.name}.${java.util.UUID.randomUUID().toString().take(8)}.tmp")
        try {
            FileOutputStream(tmp).use { it.write(data) }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw IOException("cannot write ${f.name}")
            }
        } finally {
            tmp.delete()
        }
    }

    /** [d] made with its parents (another thread making it at once is fine) */
    @Throws(IOException::class)
    fun mkdirs(d: File) {
        if (!d.mkdirs() && !d.isDirectory) throw IOException("cannot make $d")
    }

    /** android.util.Log when there is one (the app), else stderr (JVM tests) */
    fun log(msg: String) {
        val ok = runCatching { android.util.Log.i("RFMData", msg) }.isSuccess
        if (!ok) System.err.println("RFMData: $msg")
    }
}
