// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Sample's takes (the Move's audio loop: whole bars of the firmware's sound, from a bar line to a bar line). The web
// player puts each into its recorder's Takes, named "<take>-<bpm>bpm-<bars>bars" (RemoteFM1 18c6a82 web/site/js/
// rec-ui.js loopStart / loopStop). Android has no recorder, so each take is a 16-bit stereo WAV in files/Move/takes at
// the engine's rate, listed on the Ableton Move page (Share, Delete). The audio is streamed in while it records.
package com.quixotic7.remotefm1.move

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MoveTakes(val dir: File) {
    private var file: File? = null
    private var raf: RandomAccessFile? = null
    private var rate = 0
    private var stamp = ""
    /** frames written to the take recording now */
    var frames = 0L
        private set
    val recording: Boolean get() = raf != null

    /** a take begins at [rate] Hz (one that was open ends first, as it is) */
    fun start(rate: Int, now: Long = System.currentTimeMillis()) {
        if (raf != null) finish(0, 0)
        dir.mkdirs()
        this.rate = rate
        stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val f = File(dir, "move-sample-$stamp.part")
        file = f
        raf = RandomAccessFile(f, "rw").apply { setLength(0); write(header(0)) }
        frames = 0
    }

    /** [n] frames of interleaved stereo */
    fun write(stereo: ShortArray, n: Int) {
        val r = raf ?: return
        val b = ByteArray(4 * n)
        for (i in 0 until 2 * n) { val v = stereo[i].toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
        r.write(b)
        frames += n
    }

    /** the take ends: its header, its name with the tempo and the bars; returns the file (null: nothing recorded) */
    fun finish(bars: Int, bpm10: Int): File? {
        val r = raf ?: return null
        val f = file!!
        raf = null
        file = null
        r.seek(0)
        r.write(header(frames))
        r.close()
        if (frames == 0L) { f.delete(); return null }
        val bpm = if (bpm10 % 10 != 0) "%.1f".format(Locale.US, bpm10 / 10.0) else (bpm10 / 10).toString()
        val name = if (bars > 0) "move-sample-$stamp-${bpm}bpm-$bars${if (bars == 1) "bar" else "bars"}.wav" else "move-sample-$stamp.wav"
        val to = File(dir, name)
        return if (f.renameTo(to)) to else f
    }

    /** the takes, newest first */
    fun list(): List<File> = (dir.listFiles { f -> f.name.endsWith(".wav") }?.toList() ?: emptyList()).sortedByDescending { it.lastModified() }

    private fun header(frames: Long): ByteArray {
        val data = frames * 4
        val b = ByteArray(44)
        fun s(o: Int, t: String) = t.toByteArray(Charsets.US_ASCII).copyInto(b, o)
        fun u32(o: Int, v: Long) { for (k in 0 until 4) b[o + k] = (v ushr (8 * k)).toByte() }
        fun u16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        s(0, "RIFF"); u32(4, 36 + data); s(8, "WAVE"); s(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, 2)
        u32(24, rate.toLong()); u32(28, rate * 4L); u16(32, 4); u16(34, 16); s(36, "data"); u32(40, data)
        return b
    }
}
