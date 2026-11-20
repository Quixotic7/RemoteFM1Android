// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Move's firmware browser on rfm1cs-abletonmove 0.5.10 (one firmware, no FX), through the app's own MoveLink: the
// core made as the app makes it (rfm_move_fw_caps 0), the catalog sent from a firmware host (the bundled cores, as the
// Firmware page lists them), the fake Move's presses (Track 1 held + step 1, a pad, the jog's click) asking for a load,
// the bundled firmware loaded into the engine and slot 0 said back (fw_slots), the library blob kept in its file and
// given to the next core, and Tracks 2 and 3 doing nothing with one slot. (0.5.11) A Fantasy CFW's virt and step 16's
// filter; the firmware's live labels after a load (RemoteFM1's labels_core fixture), cleared with the setting off and
// on unload, readouts forgotten; the app's volume and the Move's level both ways.
package com.quixotic7.remotefm1.move

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.RfmEngine
import com.quixotic7.remotefm1.firmware.AvailableFirmware
import com.quixotic7.remotefm1.firmware.CoreInfo
import com.quixotic7.remotefm1.firmware.InstallState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class MoveFirmwareTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var engine: RfmEngine? = null
    private var link: MoveLink? = null
    private var h = 0L
    private val extra = ArrayList<Long>()
    private lateinit var dir: File

    /** the app's side for the test: the bundled cores, a load done by the test's thread (the engine's core thread) */
    private class Host(val cores: List<CoreInfo>, val extra: List<MoveFw> = emptyList(), val vol: Float? = null) : MoveFirmwareHost {
        @Volatile var playing: String? = null
        val loads = ConcurrentLinkedQueue<Pair<String, (String?) -> Unit>>()
        val levels = ConcurrentLinkedQueue<Int>()
        override fun stamp(): Any = cores
        override fun catalog() = MoveCatalog.build(cores, emptyMap(), emptyList<AvailableFirmware>(),
            { InstallState.NotInstalled }, emptyMap()) + extra
        override fun volume() = vol
        override fun level(v: Int) { levels.add(v) }
        override fun running() = playing
        override fun load(key: String, done: (String?) -> Unit) { loads.add(key to done) }
        override fun unload(done: () -> Unit) { playing = null; done() }
        override fun download(e: AvailableFirmware, progress: (Int) -> Unit, done: (String?, Boolean) -> Unit) = done(null, true)
    }

    private class MapPrefs : MovePrefs {
        val m = HashMap<String, Any?>()
        override fun string(key: String) = m[key] as String?
        override fun setString(key: String, v: String?) { m[key] = v }
        override fun bool(key: String) = m[key] as Boolean? ?: false
        override fun setBool(key: String, v: Boolean) { m[key] = v }
        override fun int(key: String, def: Int) = m[key] as Int? ?: def
        override fun setInt(key: String, v: Int) { m[key] = v }
    }

    @After fun done() {
        engine?.moveAttach(0)
        engine?.setClock(RfmEngine.CLOCK_NONE)
        extra.forEach { MoveNative.free(it) }
        engine?.close()
        dir.deleteRecursively()
    }

    private fun step(ms: Int) = engine!!.run(ms)

    private fun await(what: String, ms: Long = 5000, ok: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + ms
        while (!ok()) {
            assertTrue("waited for $what: ${if (h != 0L) MoveNative.testFw(h).lineSequence().first() + " / " +
                MoveNative.testLabels(h).lineSequence().first() else ""}",
                SystemClock.elapsedRealtime() < end)
            step(1)
            SystemClock.sleep(1)
        }
    }

    private fun press(packet: Int) = MoveNative.testInput(h, packet)
    private fun cc(n: Int, v: Int) = press(MoveNative.cc(n, v))
    /** the core's firmware state: caps, active, the view's track, slot 0, the list's length */
    private fun head(): List<String> = MoveNative.testFw(h).lineSequence().first().split(' ')

    private fun setup(extra: List<MoveFw> = emptyList(), vol: Float? = null): Host {
        dir = File(ctx.cacheDir, "move-fw-test-${System.nanoTime()}").apply { mkdirs() }
        RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir)
        val e = RfmEngine(null)
        engine = e
        assertTrue(e.testLoadEcho())
        assertTrue(e.boot())
        e.setClock(RfmEngine.CLOCK_CALLER)
        val host = Host(CoreInfo.builtInList(RfmEngine.cores()), extra, vol)
        val main = Handler(Looper.getMainLooper())
        val l = MoveLink({ e.moveAttach(it) }, MoveSettings(MapPrefs()), MoveProjects(File(dir, "projects"), MoveNative.FileCodec),
            MoveTakes(File(dir, "takes")), MoveLibrary(File(dir, "library.bin"), MoveNative.libraryMax), { null }, { main.post(it) })
        l.firmware = host
        link = l
        h = l.testCore()
        MoveNative.testConnected(h, true)
        await("connected") { MoveNative.status(h).state == MoveNative.CONNECTED }
        await("the catalog") { head()[4].toInt() == host.cores.size + extra.size }
        return host
    }

    @Test fun oneSlotTheCatalogAndAJogPressLoad() {
        val host = setup()
        // the core: one firmware, no FX; the list the host built (every bundled core, installed, A-Z)
        val lines = MoveNative.testFw(h).lines().filter { it.isNotEmpty() }
        assertEquals("caps 0", "0", head()[0])
        val ids = lines.drop(1).map { it.substringBefore('|') }
        assertEquals(host.catalog().map { it.id }, ids)
        assertEquals(host.cores.map { it.firmwareID }.toSet(), ids.toSet())
        lines.drop(1).forEach { val f = it.split('|'); assertTrue(it, f[2] != "-" && f[2] == f[1]) }
        assertEquals("nothing loaded", "-", head()[3])
        // the projects view (Track 1 held + step 1): Track 1's firmware track; Tracks 2 and 3 do nothing
        cc(43, 127); press(MoveNative.note(16, 127)); press(MoveNative.note(16, 0)); cc(43, 0)
        step(20)
        assertEquals("Track 1's track", "0", head()[2])
        for (t in listOf(42, 41)) { cc(t, 127); cc(t, 0) }
        step(1200)
        assertEquals("Tracks 2 and 3: nothing", listOf("0", "0"), head().subList(1, 3))
        // Felucca's pad (All: the installed ones in the list's order), the jog's click: FW_LOAD to the app
        val cell = ids.indexOf("felucca")
        assertTrue(cell >= 0)
        val pad = 68 + (3 - cell / 8) * 8 + cell % 8
        press(MoveNative.note(pad, 100)); press(MoveNative.note(pad, 0))
        step(20)
        cc(3, 127); cc(3, 0)
        var asked: Pair<String, (String?) -> Unit>? = null
        await("FW_LOAD") { asked = host.loads.poll(); asked != null }
        assertEquals("felucca", asked!!.first)
        // the app's load: the bundled core into the engine (on this, the core thread), then done
        val e = engine!!
        assertEquals(null, e.load(asked!!.first))
        assertTrue(e.boot())
        host.playing = "felucca"
        asked!!.second(null)
        await("slot 0 said back") { head()[3] == "felucca" }
        assertEquals("felucca", e.status().optString("core_id"))
        // the library blob (FW_LIBRARY on a flush) kept in the app's file, and the next core starts from it
        link!!.flush()
        val lib = File(dir, "library.bin")
        await("the library kept") { lib.isFile && lib.length() > 0 }
        val b = lib.readBytes()
        assertEquals("RMVL", String(b, 0, 4))
        val h2 = MoveNative.create(null, b)
        extra += h2
        assertEquals("the stored library's slot 0", "felucca", MoveNative.testFw(h2).lineSequence().first().split(' ')[3])
        assertEquals("0", MoveNative.testFw(h2).lineSequence().first().split(' ')[0])
    }

    /** the settings image Android kept before (version 3, 80 bytes) is read by the 0.5.10 core (MIDI mode kept), and
     *  the next save is version 5, 87 bytes (RFM_MOVE_SETTINGS_BYTES): the core migrates it, nothing to do here */
    @Test fun anOldSettingsImageIsReadAndSavedAsVersion5() {
        dir = File(ctx.cacheDir, "move-fw-test-${System.nanoTime()}").apply { mkdirs() }
        val old = ByteArray(80).also { byteArrayOf(0x52, 0x4d, 0x56, 3, 80, 0, 1).copyInto(it) }   // (mode 1: MIDI)
        h = MoveNative.create(old)
        extra += h
        MoveNative.testConnected(h, true)
        val end = SystemClock.elapsedRealtime() + 5000
        while (MoveNative.status(h).state != MoveNative.CONNECTED) { assertTrue(SystemClock.elapsedRealtime() < end); SystemClock.sleep(5) }
        assertEquals("the old image's mode", 1, MoveNative.status(h).mode)
        cc(40, 127); cc(40, 0)                          // Track 4: the sequencer, a settings change
        val out = IntArray(4)
        val data = ByteArray(MoveNative.projectMax)
        var img: ByteArray? = null
        while (img == null && SystemClock.elapsedRealtime() < end + 3000) {
            val n = MoveNative.takeRequest(h, out, data)
            if (n >= 0 && out[0] == MoveNative.REQ_SETTINGS) img = data.copyOf(n) else if (n < 0) SystemClock.sleep(5)
        }
        assertNotNull("SETTINGS", img)
        assertEquals(87, img!!.size)
        assertEquals("version 5", 5, img!![3].toInt())
    }

    /** (0.5.11) the core's labels state: "given readouts level0", then the 49 labels ("1|text" given, "0|" not) */
    private fun labels(): List<String> = MoveNative.testLabels(h).lines()
    private fun lhead(): List<String> = labels()[0].split(' ')

    /** (0.5.11) a Fantasy CFW's entry reaches the core with virt (its badge), and step 16's filter lists it alone:
     *  that filter's first pad + the jog asks for it (the web's test.mjs "virt reaches the core") */
    @Test fun aFantasyCfwIsVirtOnTheCoreAndStep16ListsItAlone() {
        val ripple = MoveFw("ripple", "Ripple", "0.4", "0.4", null, 0, 255, true, key = "ripple")
        val host = setup(extra = listOf(ripple))
        val lines = MoveNative.testFw(h).lines().filter { it.isNotEmpty() }.drop(1)
        assertEquals("ripple|0.4|0.4|0|255|1|0", lines.first { it.startsWith("ripple|") })
        lines.filter { !it.startsWith("ripple|") }.forEach { assertEquals(it, "0", it.split('|')[5]) }
        // the browser (Track 1 held + step 1), step 16 (Fantasy), its first pad, the jog's click
        cc(43, 127); press(MoveNative.note(16, 127)); press(MoveNative.note(16, 0)); cc(43, 0)
        step(20)
        press(MoveNative.note(31, 100)); press(MoveNative.note(31, 0))
        step(20)
        press(MoveNative.note(92, 100)); press(MoveNative.note(92, 0))
        step(20)
        cc(3, 127); cc(3, 0)
        var asked: Pair<String, (String?) -> Unit>? = null
        await("FW_LOAD") { asked = host.loads.poll(); asked != null }
        assertEquals("step 16 lists the Fantasy CFW alone", "ripple", asked!!.first)
        asked!!.second("not here")
    }

    /** (0.5.11) the firmware's live labels (DYNAMIC-LABELS: RemoteFM1's labels_core fixture) reach the core after a
     *  load, whole; the "Firmware's button names" setting off clears them; readouts kept before a load or an unload are
     *  forgotten, and the unload clears the labels */
    @Test fun liveLabelsReachTheCoreAfterALoadAndGoOnUnload() {
        val host = setup()
        val e = engine!!
        MoveNative.testReadout(h, 3, "CUTOFF", "64")
        await("a readout kept") { lhead()[1] != "0" }
        assertEquals("no labels yet", "0", lhead()[0])
        val f = File(dir, "labels_core.rfm1")
        InstrumentationRegistry.getInstrumentation().context.assets.open("firmware/labels_core.rfm1").use { i ->
            f.outputStream().use { i.copyTo(it) }
        }
        assertEquals(null, e.loadWasm(f.path))
        assertTrue(e.boot())
        host.playing = "labeltest"
        await("its labels; the old firmware's readout forgotten on the load") { lhead()[0] == "5" && lhead()[1] == "0" }
        val l = labels()
        assertEquals("1|DRUMS", l[1 + 8])           // FX
        assertEquals("1|", l[1 + 9])                // SEL: no job
        assertEquals("0|", l[1 + 13])               // GLO: not given
        assertEquals("1|CUTOFF", l[1 + 3])          // KNOB 1
        assertEquals("1|STEP 1", l[1 + 22])         // W1
        assertEquals("1|MUTE", l[1 + 38])           // B1
        assertEquals("0|", l[1 + 0])
        // the "Firmware's button names" setting off: none (as the web page sends them only with it on); on: again
        e.setButtonNames(false)
        await("cleared with the setting off") { lhead()[0] == "0" }
        e.setButtonNames(true)
        await("back with it on") { lhead()[0] == "5" }
        // a readout, then the unload: both gone
        MoveNative.testReadout(h, 3, "CUTOFF", "80")
        await("a readout kept") { lhead()[1] != "0" }
        e.unload()
        host.playing = null
        await("cleared on unload") { head()[3] == "-" && lhead()[0] == "0" && lhead()[1] == "0" }
    }

    /** (0.5.11) the app's volume is slot 0's level (100 = full) at start and when it changes, with no level sent back;
     *  the Move's own level (Track 1 held + the volume knob) comes to the app; the library keeps it */
    @Test fun theAppsVolumeAndTheMovesLevelBothWays() {
        val host = setup(vol = 0.5f)
        await("the app's volume as the level at start") { lhead()[2] == "50" }
        link!!.pageLevel(0.8f)
        await("the app's volume changed") { lhead()[2] == "80" }
        step(300)
        assertTrue("no level back to the app: ${host.levels}", host.levels.isEmpty())
        // the Move's own edit needs a firmware in the slot (Track 1 held + the volume knob)
        host.playing = "felucca"
        await("slot 0 holds one") { head()[3] == "felucca" }
        cc(43, 127); cc(79, 5); cc(43, 0)
        await("the Move's level to the app") { host.levels.isNotEmpty() }
        assertEquals(85, host.levels.peek())
        assertEquals("85", lhead()[2])
        assertEquals(0.85f, MoveLink.volumeOf(host.levels.peek()!!), 1e-6f)
        link!!.flush()
        val lib = File(dir, "library.bin")
        await("the library keeps it") { lib.isFile && lib.length() > 6 && lib.readBytes()[6].toInt() == 85 }
    }

    @Test fun anUnloadEmptiesSlotZero() {
        val host = setup()
        host.playing = "choralroot"
        await("slot 0 follows the app's pick") { head()[3] == "choralroot" }
        host.playing = null
        await("and its unload") { head()[3] == "-" }
        assertNotNull(link)
    }
}
