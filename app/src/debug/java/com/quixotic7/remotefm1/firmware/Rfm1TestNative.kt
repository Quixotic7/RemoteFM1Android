// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// (debug builds, for the instrumented tests) the .rfm1 loader's test C API (RFMWasm.h rfmw_inst_*, rfmw_set_folder)
// through native/rfmcore/rfm_wasm_test_jni.cpp: one instance outside an engine, any export by name, its memory, the
// file API's answers applied by hand; the app's folder as a POSIX directory.
package com.quixotic7.remotefm1.firmware

import java.nio.ByteBuffer

object Rfm1TestNative {
    init {
        System.loadLibrary("rfmcore")
    }

    /** the folder every Fantasy CFW uses, a POSIX directory (RemoteFM1/ itself); null: none */
    fun setFolder(path: String?) = nativeSetFolder(path)

    /** one instance of [path] over [folder] (null: the app's): it, or the loader's reason as an exception */
    class Inst(path: String, folder: String?) : AutoCloseable {
        val h: Long

        init {
            val err = arrayOfNulls<String>(1)
            h = nativeOpen(path, folder, err)
            if (h == 0L) throw IllegalStateException(err[0] ?: "not opened")
        }

        /** an export; Long.MIN_VALUE: none, or it trapped */
        fun call(name: String, vararg args: Long): Long = nativeCall(h, name, args)
        /** the linear memory (fixed while the instance lives) */
        val mem: ByteBuffer get() = nativeMemory(h)!!
        fun apply() = nativeApply(h)
        fun settle(ms: Int = 10_000) = nativeSettle(h, ms)
        fun waitIdle(ms: Int = 10_000) = nativeWait(h, ms)
        fun bump() = nativeBump(h)
        fun tick(ms: Int) = nativeTick(h, ms)
        override fun close() = nativeClose(h)
    }

    @JvmStatic private external fun nativeSetFolder(path: String?)
    @JvmStatic private external fun nativeOpen(path: String, folder: String?, err: Array<String?>): Long
    @JvmStatic private external fun nativeClose(h: Long)
    @JvmStatic private external fun nativeCall(h: Long, name: String, args: LongArray): Long
    @JvmStatic private external fun nativeMemory(h: Long): ByteBuffer?
    @JvmStatic private external fun nativeApply(h: Long)
    @JvmStatic private external fun nativeSettle(h: Long, ms: Int): Boolean
    @JvmStatic private external fun nativeWait(h: Long, ms: Int): Boolean
    @JvmStatic private external fun nativeBump(h: Long)
    @JvmStatic private external fun nativeTick(h: Long, ms: Int)
}
