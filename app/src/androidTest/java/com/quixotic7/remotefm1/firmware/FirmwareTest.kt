// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported in part from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/WasmTests.swift (the loader's refusals, AMB-1's
// checks and texts, AMB-1 boots, draws and sounds, Felucca and ChoralRoot repeat bit for bit in a second instance,
// instances share nothing, a power cycle re-instantiates) and ImportTests.swift (the library with the real loader,
// an imported Felucca next to the built-in one). A4's "done when": the Melodee and Felucca .rfm1 from the catalog
// (the real feeds) install and play next to the built-in cores. Plus the render cost per buffer of each .rfm1
// through the render-ahead ring, and a thumbnail captured from a running firmware.
//
// The .rfm1 files: AMB-1 from the test APK's assets (iOS's fixture); Felucca, ChoralRoot and Melodee from
// files/test-rfm1 (scripts/emu_firmwaretest.sh pushes them), else downloaded once through FirmwareStore from the
// feeds (size and SHA-256 checked). Numbers go into files/firmwaretest.json, one object per test.
package com.quixotic7.remotefm1.firmware

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.Levels
import com.quixotic7.remotefm1.RfmEngine
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** one engine over an .rfm1 on the caller's clock (iOS WasmTestCore) */
class WasmCoreUnderTest(val path: String) : AutoCloseable {
    val e = RfmEngine()

    init {
        val err = e.loadWasm(path)
        if (err != null) {
            e.close()
            throw AssertionError("loadWasm ${File(path).name}: $err")
        }
    }

    fun boot(flash: ByteArray? = null) {
        assertTrue("boot ${File(path).name}", e.boot(flash))
        e.setClock(RfmEngine.CLOCK_CALLER)
    }

    fun run(ms: Int) = e.run(ms)
    val status: JSONObject get() = e.status()
    val lcd: Int get() = e.lcdHash
    fun key(k: Int, down: Boolean) = e.event(RfmEngine.EV_KEY, k, if (down) 1 else 0)
    fun flash(): ByteArray = if (e.flashSize == 0) ByteArray(0) else ByteArray(e.flashSize).also { if (!e.finalFlash(it)) return ByteArray(0) }

    /** settle, then D4 held for [holdMs]: the hold's audio */
    fun chord(settle: Int = 1500, holdMs: Int = 1500): RfmEngine.Audio {
        run(settle)
        key(KEY_D4, true)
        val a = run(holdMs)
        key(KEY_D4, false)
        run(100)
        return a
    }

    override fun close() = e.close()

    companion object {
        const val KEY_D4 = 9
    }
}

@RunWith(AndroidJUnit4::class)
class FirmwareTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val r = JSONObject()
    private var name = ""

    @After fun done() = save(name, r)

    // ---- the loader

    /** tiny modules made here, each refused with its one-line reason (iOS testRefusesBrokenFiles) */
    @Test fun refusesBrokenFiles() {
        name = "refusals"
        val w = WasmBytes
        val cases = listOf(
            Triple("magic.rfm1", "hello, not wasm".toByteArray(), "not a WebAssembly module"),
            Triple("nomanifest.rfm1", w.header + w.types + w.memory(1, 1), "no rfm1 section"),
            Triple("import.rfm1", w.header + w.manifest + w.types + w.imports("env", "foo") + w.memory(1, 1),
                "imports env.foo, which the .rfm1 format doesn't allow"),
            Triple("wasiother.rfm1", w.header + w.manifest + w.types + w.imports("wasi_snapshot_preview1", "random_get"),
                "imports wasi_snapshot_preview1.random_get"),
            Triple("wrongsig.rfm1", w.header + w.manifest + w.types + w.imports("wasi_snapshot_preview1", "fd_close"),
                "has the signature () -> (), not (i32) -> (i32)"),
            Triple("grow.rfm1", w.header + w.manifest + w.types + w.memory(1, null), "its memory can grow"),
            Triple("grow2.rfm1", w.header + w.manifest + w.types + w.memory(1, 4), "its memory can grow"),
            Triple("big.rfm1", w.header + w.manifest + w.types + w.memory(1024, 1024), "at most 32 MiB"),
            Triple("noexports.rfm1", w.header + w.manifest + w.types + w.memory(1, 1), "its memory is not exported"),
            Triple("badjson.rfm1", w.header + w.custom("rfm1", "{nope".toByteArray()) + w.types, "not a JSON object"),
            Triple("noid.rfm1", w.header + w.custom("rfm1",
                """{"rfm1":1,"abi":6,"name":"x","version":"1","license":"MIT","source_url":"u"}""".toByteArray()), "no \"id\""),
            Triple("v2.rfm1", w.header + w.custom("rfm1", """{"rfm1":2,"abi":6}""".toByteArray()), "format version 2"),
            Triple("truncated.rfm1", (w.header + w.manifest + w.types).let { it.copyOf(it.size - 2) }, "runs past the end"),
        )
        val dir = File(ctx.cacheDir, "rfm1-refusals").also { it.deleteRecursively(); it.mkdirs() }
        val got = JSONObject()
        for ((n, bytes, want) in cases) {
            val f = File(dir, n).also { it.writeBytes(bytes) }
            val res = Rfm1Native.check(f.path)
            assertTrue(n, res.isFailure)
            val why = res.exceptionOrNull()!!.message!!
            assertTrue("$n: $why", why.contains(want))
            assertFalse("$n: one line", why.contains('\n'))
            got.put(n, why)
            // the engine refuses it too, with the same reason
            RfmEngine().use { e -> assertEquals(n, why, e.loadWasm(f.path)) }
        }
        assertTrue(Rfm1Native.check("/nonexistent/x.rfm1").isFailure)
        r.put("reasons", got).put("pass", true)
        dir.deleteRecursively()
    }

    @Test fun ambChecksAndTexts() {
        name = "amb_check"
        val p = amb().path
        val t0 = SystemClock.elapsedRealtimeNanos()
        val i = Rfm1Native.check(p).getOrThrow()
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        assertEquals("amb", i.id)
        assertEquals("AMB-1", i.name)
        assertEquals("1.3", i.version)
        assertEquals("MPL-2.0", i.license)
        assertEquals(AMB_SHA, i.sha256)
        assertEquals(6, i.abi)
        assertEquals(8 * 1048576, i.memoryBytes)
        assertEquals(214_629L, i.fileSize)
        assertEquals(AMB_SHA, Rfm1Native.sha256(p))
        assertTrue(Rfm1Native.text(p, "manifest").contains("\"Charles Vestal\""))
        assertTrue(Rfm1Native.text(p, "license.0").startsWith("MPL-2.0"))
        assertTrue(Rfm1Native.text(p, "license.2").startsWith("GPL-3.0-only"))
        assertEquals("", Rfm1Native.text(p, "license.9"))
        r.put("check_ms", ms).put("instantiate_ms", i.loadUs / 1000.0).put("pass", true)
    }

    // ---- running

    @Test fun ambBootsDrawsAndSounds() {
        name = "amb_plays"
        WasmCoreUnderTest(amb().path).use { c ->
            c.boot()
            val s0 = c.status
            assertEquals("wasm", s0.getString("kind"))
            assertEquals("amb", s0.getString("core_id"))
            assertEquals(0, s0.getInt("slot"))
            assertEquals(1, s0.getInt("booted"))
            val a = c.chord()
            val s = c.status
            assertEquals(0, s.getInt("halted"))
            assertTrue("amb drew nothing", s.getInt("lcd_writes") > 0)
            assertTrue("amb is silent (rms ${a.rms})", a.rms > 0.0005)
            assertNotEquals(0, c.lcd)
            r.put("rms", a.rms).put("peak", a.peak).put("lcd_writes", s.getInt("lcd_writes")).put("pass", true)
        }
    }

    /** Felucca, ChoralRoot and Melodee: sound, screen, a second instance repeats the first bit for bit; then each one's
     *  render cost through the render-ahead ring (48 kHz, 192-frame buffers, a key held for 2 s) */
    @Test fun feluccaChoralRootMelodeePlayAndTheirRenderCost() {
        name = "rfm1_play"
        for (id in listOf("felucca", "choralroot", "melodee")) {
            val path = rfm1(id).path
            val t0 = SystemClock.elapsedRealtimeNanos()
            val a = WasmCoreUnderTest(path)
            val b = WasmCoreUnderTest(path)
            val loadMs = (SystemClock.elapsedRealtimeNanos() - t0) / 2e6
            val o = JSONObject().put("file", File(path).name).put("load_ms", loadMs)
            try {
                a.boot()
                b.boot()
                val t1 = SystemClock.elapsedRealtimeNanos()
                val ra = a.chord()
                val wall = (SystemClock.elapsedRealtimeNanos() - t1) / 1e9
                val rb = b.chord()
                assertEquals(id, 0, a.status.getInt("halted"))
                assertTrue("$id is silent (rms ${ra.rms})", ra.rms > 0.0005)
                assertTrue("$id drew nothing", a.status.getInt("lcd_writes") > 0)
                assertEquals(id, ra.frames, rb.frames)
                assertEquals("$id: two instances' audio differs", ra.hash, rb.hash)
                assertEquals("$id: two instances' screens differ", a.lcd, b.lcd)
                o.put("rms", ra.rms).put("peak", ra.peak).put("hash", ra.hash).put("x_realtime_caller", 3.1 / wall)
            } finally {
                a.close()
                b.close()
            }
            // the render cost: a fresh instance through the ring as the audio callback takes it
            val e = RfmEngine()
            try {
                assertEquals(null, e.loadWasm(path))
                assertTrue(e.boot())
                e.renderOffline(48000, 48000.0, 192)                     // (1 s: the boot)
                e.resetStats()
                e.key(WasmCoreUnderTest.KEY_D4, true)
                val t2 = SystemClock.elapsedRealtimeNanos()
                val held = e.renderOffline(2 * 48000, 48000.0, 192)!!
                val wall = (SystemClock.elapsedRealtimeNanos() - t2) / 1e6
                e.key(WasmCoreUnderTest.KEY_D4, false)
                val s = e.status()
                val rms = Levels.rms(held)
                o.put("ring_rms", rms).put("core_avg_us", s.getInt("core_avg_us")).put("core_max_us", s.getInt("core_max_us"))
                    .put("core_load_pct", s.getInt("core_load_pct")).put("frame_max_us", s.getInt("frame_max_us"))
                    .put("render_avg_us", s.getInt("render_avg_us")).put("core_wakes", s.getLong("core_wakes"))
                    .put("core_frames", s.getLong("core_frames")).put("x_realtime", 2000.0 / wall)
                    .put("buffer_us", 192 * 1e6 / 48000)
                    // (offline the core thread renders ahead in a few long wakes: its time over the 192-frame buffers)
                    .put("core_us_per_buffer", s.getDouble("core_avg_us") * s.getLong("core_wakes") / (s.getLong("core_frames") / 192.0))
                    .put("wall_us_per_buffer", wall * 1000 / (2 * 48000 / 192.0))
                assertTrue("$id: silent through the ring", rms > 1e-5)
                assertEquals("wasm", s.getString("kind"))
            } finally {
                e.close()
            }
            Log.i(TAG, "$id: $o")
            r.put(id, o)
        }
        r.put("pass", true)
    }

    /** two instances of one file: a key on one doesn't reach the other (its audio stays bit for bit that of an
     *  untouched third); each has its own flash (iOS testInstancesIsolated) */
    @Test fun twoInstancesShareNoMemory() {
        name = "isolated"
        val path = rfm1("felucca").path
        val a = WasmCoreUnderTest(path)
        val b = WasmCoreUnderTest(path)
        val ref = WasmCoreUnderTest(path)
        try {
            for (c in listOf(a, b, ref)) {
                c.boot()
                c.run(1500)
            }
            for (k in listOf(9, 13, 16)) a.key(k, true)                // (a chord: D4 F#4 A4)
            val ra = a.run(1500)
            val rb = b.run(1500)
            val rr = ref.run(1500)
            assertTrue(ra.rms > 0.0005)
            assertTrue("the keys changed nothing", ra.hash != rr.hash || a.lcd != ref.lcd)
            assertEquals("keys on one instance reached another: rms ${rb.rms} vs ${rr.rms}", rr.hash, rb.hash)
            assertEquals("keys on one instance changed another's screen", ref.lcd, b.lcd)
            assertEquals(a.e.flashSize, b.e.flashSize)
            val fa = a.flash()
            val fb = b.flash()
            assertTrue(fa.isNotEmpty())
            // a different flash staged into a fourth instance stays there; the others' stay as they were
            val marked = fa.copyOf()
            if (marked.size > 100) marked[marked.size - 7] = (marked[marked.size - 7].toInt() xor 0x5A).toByte()
            WasmCoreUnderTest(path).use { c ->
                c.boot(marked)
                c.run(200)
                val fc = c.flash()
                assertEquals(marked.size, fc.size)
                if (fc.size > 100) assertEquals(marked[marked.size - 7], fc[fc.size - 7])
            }
            assertTrue("an instance's flash moved when another's did", b.flash().contentEquals(fb))
            r.put("file", File(path).name).put("rms_keyed", ra.rms).put("rms_other", rb.rms).put("flash_bytes", fa.size).put("pass", true)
        } finally {
            a.close(); b.close(); ref.close()
        }
    }

    /** the power cycle re-instantiates from the same bytes (iOS testPowerCycleReinstantiates) */
    @Test fun powerCycleReinstantiates() {
        name = "power_cycle"
        WasmCoreUnderTest(amb().path).use { c ->
            c.boot()
            c.run(500)
            assertEquals(null, c.e.powerCycle())
            c.e.setClock(RfmEngine.CLOCK_CALLER)
            val a = c.chord(800, 800)
            assertEquals(1, c.status.getInt("booted"))
            assertTrue(a.rms > 0.0005)
            r.put("rms", a.rms).put("pass", true)
        }
    }

    // ---- the library, the catalog, Get firmware

    /** the library with the real loader: a valid file kept, a bad one refused with its reason and not kept; the
     *  imported Felucca next to the built-in one, both playing (iOS testImportedFeluccaNextToTheNativeOne) */
    @Test fun libraryWithTheRealLoader() {
        name = "library"
        val dir = File(ctx.cacheDir, "test-library").also { it.deleteRecursively() }
        val lib = FirmwareLibrary(dir, Rfm1Native)
        lib.sync()
        val a = lib.import(amb())
        assertEquals(null, a.error)
        assertEquals("AMB-1", a.name)
        val bad = lib.import("junk".toByteArray().inputStream(), "junk.rfm1")
        assertTrue(bad.error!!, bad.error!!.startsWith("not a WebAssembly module"))
        assertFalse(bad.error!!.contains(".import-"))
        assertEquals(1, lib.files.value.size)
        val f = lib.import(rfm1("felucca"))
        assertEquals("felucca", f.coreID)
        assertEquals(5, lib.details(AMB_SHA).licenses.size)

        assertTrue(RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir) >= 6)
        val cat = CorePicker.catalog(CoreInfo.builtInList(RfmEngine.cores()), lib.cores())
        val native = cat.first { it.id == "felucca" }
        val wasm = cat.first { it.imported && it.firmwareID == "felucca" }
        assertEquals("native", native.kind)
        assertEquals("wasm", wasm.kind)
        assertEquals("rfm1:" + wasm.sha256, wasm.id)
        assertEquals(native.version, wasm.version)
        assertEquals(cat.size, cat.map { it.id }.toSet().size)
        assertEquals("choralroot", CorePicker.pick(null, cat))
        assertEquals(CoreRef.Resolved(wasm.id, "", true), CoreRef(wasm).resolve(cat))
        assertEquals(CoreRef.Resolved("felucca", "", true), CoreRef(native).resolve(cat))
        val out = JSONObject()
        for (c in listOf(native, wasm)) {
            RfmEngine().use { e ->
                assertEquals(null, if (c.imported) e.loadWasm(c.path) else e.load(c.id))
                assertTrue(e.boot())
                e.setClock(RfmEngine.CLOCK_CALLER)
                e.run(1500)
                e.event(RfmEngine.EV_KEY, WasmCoreUnderTest.KEY_D4, 1)
                val au = e.run(1000)
                assertEquals(c.kind, e.status().getString("kind"))
                assertTrue("${c.title} is silent", au.rms > 0.0005)
                out.put(c.kind, au.rms)
            }
        }
        r.put("felucca_rms", out).put("pass", true)
        dir.deleteRecursively()
    }

    /** A4's "done when": the real feeds read, Melodee and Felucca installed from them (size, SHA-256 and the loader
     *  checked), both playing next to the built-in cores; Melodee is listed (no built-in one), the built-in Felucca of
     *  the same version hides the catalog's from the list */
    @Test fun catalogInstallsMelodeeAndFelucca() {
        name = "catalog_install"
        val dir = File(ctx.cacheDir, "test-catalog").also { it.deleteRecursively() }
        val lib = FirmwareLibrary(dir, Rfm1Native)
        lib.sync()
        val store = FirmwareStore(lib, cacheFile = File(dir.parentFile, "test-catalog-cache/feeds.json"), tmpDir = ctx.cacheDir)
        val t0 = SystemClock.elapsedRealtime()
        store.refresh()
        assumeTrue("no network: ${store.problem.value}", store.available.value.isNotEmpty())
        r.put("check_ms", SystemClock.elapsedRealtime() - t0).put("report", store.report.value.joinToString(" | "))
            .put("problem", store.problem.value).put("available", store.available.value.size)
        assertTrue(RfmEngine.scan(ctx.applicationInfo.nativeLibraryDir) >= 6)
        val builtIn = CoreInfo.builtInList(RfmEngine.cores())
        val listed = store.list(builtIn).map { it.id }
        assertTrue("melodee not listed: $listed", "melodee" in listed)
        // the built-in Felucca hides the catalog's of its version or older; a newer one is offered
        val fel = store.available.value.first { it.id == "felucca" }
        val felBuiltIn = builtIn.first { it.id == "felucca" }
        assertEquals("felucca ${fel.version} vs built in ${felBuiltIn.version}",
            FirmwareVersion.less(felBuiltIn.version, fel.version), "felucca" in listed)
        r.put("felucca_listed", "felucca" in listed).put("felucca_catalog", fel.version).put("felucca_built_in", felBuiltIn.version)
        for (id in listOf("melodee", "felucca")) {
            val e = store.available.value.first { it.id == id }
            assertEquals(InstallState.NotInstalled, store.state(e))
            val t1 = SystemClock.elapsedRealtime()
            val f = store.install(e)
            assertNotNull("$id: ${store.failure.value}", f)
            assertEquals("Installed ${f!!.name} ${f.version}.", store.message.value)
            assertEquals(InstallState.Installed(e.version), store.state(e))
            r.put(id, JSONObject().put("version", f.version).put("bytes", f.fileSize).put("install_ms", SystemClock.elapsedRealtime() - t1)
                .put("from", e.downloads.first().url))
        }
        val cat = CorePicker.catalog(builtIn, lib.cores())
        assertEquals(builtIn.size + 2, cat.size)
        for (c in cat.filter { it.imported }) {
            RfmEngine().use { e ->
                assertEquals(null, e.loadWasm(c.path))
                assertTrue(e.boot())
                e.setClock(RfmEngine.CLOCK_CALLER)
                e.run(1500)
                e.event(RfmEngine.EV_KEY, WasmCoreUnderTest.KEY_D4, 1)
                val au = e.run(1000)
                assertTrue("${c.title} is silent", au.rms > 0.0005)
                r.getJSONObject(c.firmwareID).put("rms", au.rms)
            }
        }
        // the details for the Firmware card
        val info = FirmwareInfo.lookup(cat.first { it.firmwareID == "melodee" }, store.details)
        assertTrue(info.about.isNotEmpty())
        assertNotNull(info.source)
        r.put("melodee_card", JSONObject().put("author", info.author).put("tag", info.tag).put("source", info.source))
        r.put("pass", true)
        dir.deleteRecursively()
    }

    /** the LCD of a running firmware captured ~3 s after boot as a real PNG (240 x 240), not again after that */
    @Test fun thumbnailCaptured() {
        name = "thumbnail"
        val dir = File(ctx.cacheDir, "test-thumbs").also { it.deleteRecursively() }
        val thumbs = FirmwareThumbs(dir)
        val cap = ThumbCapture(thumbs)
        assertTrue(FirmwareThumbs.android(ctx).exists("felucca"))         // (shipped)
        WasmCoreUnderTest(amb().path).use { c ->
            c.boot()
            val rgba = ByteArray(240 * 240 * 4)
            var have = 0
            var written = -1
            for (ms in 0 until 6000 step 100) {
                c.run(100)
                have = c.e.preview(have, rgba)
                if (cap.frame(rgba, "test-amb", true, ms / 1000.0)) written = ms
            }
            assertTrue("no thumbnail written", written in 3000..5000)
            val png = thumbs.png("test-amb")!!
            val bm = BitmapFactory.decodeByteArray(png, 0, png.size)
            assertEquals(240, bm.width)
            assertEquals(240, bm.height)
            r.put("written_at_ms", written).put("png_bytes", png.size).put("pass", true)
        }
        dir.deleteRecursively()
    }

    // ---- the files

    private fun amb(): File {
        val f = File(ctx.cacheDir, "amb-1.3.rfm1")
        if (!f.isFile || f.length() != 214_629L) {
            InstrumentationRegistry.getInstrumentation().context.assets.open("firmware/amb-1.3.rfm1").use { i ->
                f.outputStream().use { i.copyTo(it) }
            }
        }
        return f
    }

    /** files/test-rfm1/<id>-*.rfm1 (pushed), else the feeds' copy downloaded once into cache/test-rfm1 */
    private fun rfm1(id: String): File {
        File(ctx.filesDir, "test-rfm1").listFiles()?.sortedBy { it.name }?.firstOrNull { it.name.startsWith("$id-") && it.name.endsWith(".rfm1") }
            ?.let { return it }
        val lib = FirmwareLibrary(File(ctx.cacheDir, "test-rfm1"), Rfm1Native)
        lib.sync()
        lib.files.value.firstOrNull { it.coreID == id }?.let { return lib.path(it.sha256) }
        val store = synchronized(FirmwareTest::class.java) {
            feeds ?: FirmwareStore(lib, cacheFile = File(ctx.cacheDir, "test-rfm1-cache/feeds.json"), tmpDir = ctx.cacheDir)
                .also { it.refresh(); feeds = it }
        }
        val e = store.available.value.firstOrNull { it.id == id }
        assumeTrue("no $id .rfm1 pushed and none from the feeds (${store.problem.value})", e != null)
        val f = store.install(e!!)
        assumeTrue("$id: ${store.failure.value}", f != null)
        return lib.path(f!!.sha256)
    }

    companion object {
        const val TAG = "RFMFirmwareTest"
        const val AMB_SHA = "ccacdb2801a8ac39aa86128f1650b071775c4fa131446011189f77a34961f053"
        private var feeds: FirmwareStore? = null

        @Synchronized
        fun save(name: String, r: JSONObject) {
            if (name.isEmpty()) return
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val f = File(ctx.filesDir, "firmwaretest.json")
            val all = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
            if (!r.has("pass")) r.put("pass", false)
            all.put(name, r)
            f.writeText(all.toString(2))
            Log.i(TAG, "$name: $r")
        }
    }
}

/** tiny WebAssembly modules, for the loader's refusals (iOS WasmBytes) */
object WasmBytes {
    val header = byteArrayOf(0, 0x61, 0x73, 0x6D, 1, 0, 0, 0)
    fun leb(v0: Int): ByteArray {
        var v = v0
        val out = ByteArrayOutputStream()
        do {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.write(b)
        } while (v != 0)
        return out.toByteArray()
    }
    fun name(s: String) = leb(s.toByteArray().size) + s.toByteArray()
    fun section(id: Int, body: ByteArray) = byteArrayOf(id.toByte()) + leb(body.size) + body
    fun custom(n: String, payload: ByteArray) = section(0, name(n) + payload)
    const val MANIFEST = """{"rfm1":1,"abi":6,"id":"tiny","name":"Tiny","version":"0.1","license":"MIT","source_url":"https://example.invalid/tiny@0"}"""
    val manifest get() = custom("rfm1", MANIFEST.toByteArray())
    /** one type: () -> () */
    val types = section(1, byteArrayOf(1, 0x60, 0, 0))
    fun imports(module: String, field: String) = section(2, byteArrayOf(1) + name(module) + name(field) + byteArrayOf(0, 0))
    /** flags 0 (no maximum: growable) or 1 (initial = maximum) */
    fun memory(initial: Int, max: Int?) =
        section(5, byteArrayOf(1) + if (max == null) byteArrayOf(0) + leb(initial) else byteArrayOf(1) + leb(initial) + leb(max))
}
