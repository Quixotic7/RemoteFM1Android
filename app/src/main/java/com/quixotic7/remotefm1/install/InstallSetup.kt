// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Install/FirmwareInstaller+App.swift and FM1KitLicence.swift;
// changes: Kotlin; no background task (an install runs in the app's process; the page keeps the screen on).
// The app's one installer (it outlives the page, so an install keeps going if the page closes). With the device
// backend: Android MIDI, USB only. With the fake backend: FM1Kit's SimulatedFM1 running RemoteFM1 0.2.2, so nothing is
// ever sent to a real FM-1 from there. Demo (screenshots): "check" | "writing" | "finished" | "cutoff" runs the page
// by itself on the SimulatedFM1 (cutoff: the simulated cable pulled at 45 %).
package com.quixotic7.remotefm1.install

import android.content.Context
import com.quixotic7.remotefm1.install.fm1kit.UpdaterTiming
import kotlin.time.Duration.Companion.milliseconds

object AppInstaller {
    @Volatile private var made: FirmwareInstaller? = null
    @Volatile private var demoStep: String? = null

    /** the app's installer, made on first use; [device]: the device backend (else a SimulatedFM1) */
    fun shared(context: Context, panel: PanelLinkPausing, device: Boolean, awake: InstallAwake = InstallAwake.none,
               demo: String? = null): FirmwareInstaller = synchronized(this) {
        made?.let { return it }
        val cache = RemoteFM1Releases.defaultCache(context.applicationContext.cacheDir)
        val releases = RemoteFM1Releases(cache)
        val packages = FirmwarePackages(java.io.File(cache, "packages"))
        val i = if (device && demo == null) {
            FirmwareInstaller(releases, packages, panel,
                              InstallTransport.reusing { AndroidMidiTransport(context) }, false, UpdaterTiming.standard, awake)
        } else {
            val o = InstallTransport.simulatedRemoteFM1(cutOffAt = if (demo == "cutoff") 0.45 else null)
            if (demo != null) o.requestInterval = 9.milliseconds
            FirmwareInstaller(releases, packages, panel, InstallTransport.simulated(o), true,
                              InstallTransport.simulatedTiming, awake)
        }
        demoStep = demo
        made = i
        i
    }

    fun peek(): FirmwareInstaller? = made

    /** the page appeared: the list refreshed (at most every 10 minutes); a demo runs once */
    suspend fun pageAppeared(i: FirmwareInstaller) {
        i.pageAppeared()
        val step = demoStep ?: return
        demoStep = null
        i.check()
        if (step == "check" || i.state.value.stage != FirmwareInstaller.Stage.ready) return
        i.setConfirmed(true)
        i.install()
    }
}

/** FM1Kit's credit and licence, as the install page's About shows them (LICENSES/FM1Kit-MIT.txt has both notices) */
object FM1KitCredit {
    const val LINE = "Installs with FM1Kit 0.4 (Drew Robinson, MIT licence, github.com/ocean/FM1Kit), which speaks the " +
        "FM-1's USB-MIDI update protocol (here as a Kotlin port). Neither FM1Kit nor RemoteFM1 is affiliated with " +
        "M-VAVE; installing custom firmware is at your own risk."

    val LICENCE = """
        MIT License

        Copyright (c) 2026 Drew Robinson

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all
        copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
        SOFTWARE.
    """.trimIndent()

    /** M-Flasher's notice (ApplicationArea.kt follows its formats.rs, as FM1Kit does) */
    const val MFLASHER = "Reads firmware packages' app area as M-Flasher does (Julius Vitkauskas, MIT licence, " +
        "github.com/jvitkauskas/m-flasher)."
}
