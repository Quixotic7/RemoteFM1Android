// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
package com.quixotic7.remotefm1

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/** A0's screen: the core's name, its LCD, its status, a key to hold and the audio switch (the faceplate is A3's). */
class MainActivity : ComponentActivity() {
    private var engine: RfmEngine? = null
    private var coreName = "no core"
    private var startError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RfmEngine.scan(applicationInfo.nativeLibraryDir)
        val cores = RfmEngine.cores().getJSONArray("cores")
        val choral = (0 until cores.length()).map { cores.getJSONObject(it) }.firstOrNull { it.getString("id") == CORE }
        val e = RfmEngine()
        engine = e
        if (choral == null) {
            startError = "ChoralRoot not found in ${applicationInfo.nativeLibraryDir}"
        } else {
            coreName = "${choral.getString("name")} ${choral.getString("version")}"
            startError = e.load(CORE) ?: run {
                e.boot()
                e.setClock(RfmEngine.CLOCK_TIMER)
                null
            }
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen(e) } }
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }

    @Composable
    private fun Screen(e: RfmEngine) {
        var status by remember { mutableStateOf("") }
        var playing by remember { mutableStateOf(false) }
        var audioError by remember { mutableStateOf<String?>(null) }
        var lcdSeq by remember { mutableIntStateOf(0) }
        val rgba = remember { ByteArray(240 * 240 * 4) }
        val bitmap = remember { Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888) }
        LaunchedEffect(Unit) {
            while (true) {
                val s = e.status()
                val a = s.getJSONObject("audio")
                status = "booted ${s.getInt("booted")}  clock ${s.getInt("clock")}  device ${s.getInt("dev_ms")} ms\n" +
                    "renders ${s.getLong("renders")}  underruns ${s.getInt("underruns")}  core ${s.getInt("core_load_pct")} %\n" +
                    "audio ${a.getString("api")} ${a.getInt("rate")} Hz  burst ${a.getInt("frames_per_burst")}  " +
                    "rms ${"%.4f".format(a.getDouble("rms"))}"
                val seq = e.preview(lcdSeq, rgba)
                if (seq != lcdSeq) {
                    lcdSeq = seq
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                }
                delay(100)
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(coreName, fontSize = 28.sp)
                startError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Image(bitmap = bitmap.asImageBitmap().also { lcdSeq }, contentDescription = "the core's screen",
                      modifier = Modifier.size(240.dp))
                Text(status, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = {
                        if (playing) {
                            e.stopAudio()
                            playing = false
                        } else {
                            audioError = e.startAudio()
                            playing = audioError == null
                        }
                    }) { Text(if (playing) "Stop audio" else "Start audio") }
                    HoldKey(e)
                }
                audioError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    // a note key, down while touched
    @Composable
    private fun HoldKey(e: RfmEngine) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 160.dp, height = 48.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        e.key(KEY, true)
                        waitForUpOrCancellation()
                        e.key(KEY, false)
                    }
                },
        ) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) { Text("Hold key C") }
            }
        }
    }

    companion object {
        const val CORE = "choralroot"
        const val KEY = 7       // C4: key 0 is F3 (MIDI 53)
    }
}
