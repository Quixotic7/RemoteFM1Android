// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
//
// The data tests' helpers: the real LZFSE on the JVM (native/lzfse built for this Mac with cc into a temp folder, the
// JNI loaded with System.load), else a stand-in codec (Deflater) so the format and library logic still run; a clock
// the test moves; temp folders.
package com.quixotic7.remotefm1.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater

/** a clock the test moves (seconds since 1970) */
class TestClock(var t: Double)

object LzfseHost {
    /** the host build loaded (once); false when there's no compiler or no sources */
    val loaded: Boolean by lazy { build() }

    private fun build(): Boolean {
        val src = listOf(File("../native/lzfse"), File("native/lzfse")).firstOrNull { File(it, "lzfse_jni.c").isFile }
            ?: return false
        val home = File(System.getProperty("java.home"))
        val inc = File(home, "include")
        val os = if (System.getProperty("os.name").lowercase().contains("mac")) "darwin" else "linux"
        if (!File(inc, "jni.h").isFile) return false
        val out = Files.createTempDirectory("rfm-lzfse").toFile()
        val lib = File(out, if (os == "darwin") "librfmlzfse.dylib" else "librfmlzfse.so")
        val cmd = mutableListOf("cc", "-O2", "-shared", "-fPIC", "-I${inc.path}", "-I${File(inc, os).path}", "-o", lib.path,
            File(src, "lzfse_jni.c").path)
        cmd += File(src, "src").listFiles()!!.filter { it.name.endsWith(".c") }.map { it.path }
        val ok = runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0 || run { System.err.println(log); false }
        }.getOrDefault(false)
        return ok && Lzfse.loadFrom(lib.path)
    }
}

/** a stand-in for LZFSE where none can be built (the format's logic tested; not iOS-compatible) */
object FakeCodec : FlashCodec {
    override fun compress(raw: ByteArray): ByteArray? {
        val d = Deflater(9).apply { setInput(raw); finish() }
        val buf = ByteArray(raw.size + 1024)
        val n = d.deflate(buf)
        return if (d.finished()) buf.copyOf(n) else null
    }

    override fun decompress(z: ByteArray, size: Int): ByteArray? = runCatching {
        val i = Inflater().apply { setInput(z) }
        val out = ByteArray(size + 1)
        val n = i.inflate(out)
        if (!i.finished() || n != size) null else out.copyOf(n)
    }.getOrNull()
}

object TestCodec {
    /** the real LZFSE when it builds here, else the stand-in */
    val codec: FlashCodec get() = if (LzfseHost.loaded) Lzfse else FakeCodec
}

fun tempDir(prefix: String = "rfm-data"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }
