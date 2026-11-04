// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// (G, RemoteFM1IOS dc771c8, bd0711a, 41e8471) RemoteFM1 0.3.1 running in the app's engine as a cartridge target: it
// answers BANKS (0 of 16), takes a cartridge (STORED), which the app keeps (CartKeep); the bank copy then puts it on a
// pretend 0.3.1 FM-1 (FakeFM1) with its name, and refuses plainly with the debug virtual FM-1 (FakeFm1Service:
// libremotefm1's fake unit, which answers no bank requests, as RemoteFM1 0.3.0 wouldn't). No real FM-1 is touched:
// the virtual one is a MidiDeviceService inside this app.
// The .rfm1: files/test-rfm1/remotefm1-*.rfm1 (pushed, as scripts/emu_firmwaretest.sh does), else skipped.
package com.quixotic7.remotefm1.cart

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quixotic7.remotefm1.FakeFm1Service
import com.quixotic7.remotefm1.RfmEngine
import com.quixotic7.remotefm1.managers.UsbMidiDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoreCartTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** the engine as the cartridge page sees the running core (AppMidi's managerAttach / Send / Take) */
    private class EngineMidi(val e: RfmEngine) : CoreMidi {
        private var d = UsbMidiDecoder()
        private val p = IntArray(256)
        override fun attach(on: Boolean) { e.midiOutEnable(if (on) 2 else 0); d = UsbMidiDecoder() }
        override suspend fun send(bytes: ByteArray): Boolean {
            repeat(200) { if (e.sysexIn(bytes)) return true; delay(5) }
            return false
        }
        override fun take(): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            while (true) {
                val n = e.midiOutTake(p)
                for (i in 0 until n) d.feed(p[i])?.let { out.add(it) }
                if (n < p.size) break
            }
            return out
        }
    }

    private fun cart(seed: Int): DX7Cart {
        val b = ByteArray(4104)
        intArrayOf(0xF0, 0x43, 0x00, 0x09, 0x20, 0x00).forEachIndexed { i, v -> b[i] = v.toByte() }
        var sum = 0
        for (i in 0 until 4096) {
            // (voice names printable: bytes 118..127 of each voice)
            val v = if (i % 128 in 118..127) 0x41 + (i * seed) % 26 else (i * seed + 3) and 0x7F
            b[6 + i] = v.toByte()
            sum = (sum + v) and 0x7F
        }
        b[4102] = ((128 - sum) and 0x7F).toByte()
        b[4103] = 0xF7.toByte()
        return DX7Cart.parse(b)
    }

    private fun remoteFm1(): File? =
        File(ctx.filesDir, "test-rfm1").listFiles()?.firstOrNull { it.name.startsWith("remotefm1-") && it.name.endsWith(".rfm1") }

    private val timing = CartSender.Timing(afterPause = 50, reply = 1500, stored = 3000, nameSettle = 1500)

    @Test fun remoteFm1InTheAppTakesACartridgeAndTheBankCopyReachesAPretendFm1() = runBlocking {
        val f = remoteFm1()
        assumeTrue("no remotefm1-*.rfm1 in files/test-rfm1", f != null)
        val keep = File(ctx.cacheDir, "cartkeep-test").apply { deleteRecursively(); mkdirs() }
        val before = CartKeep.folder
        CartKeep.folder = keep
        val e = RfmEngine()
        try {
            assertNull(e.loadWasm(f!!.path))
            assertTrue(e.boot(null))
            e.setClock(RfmEngine.CLOCK_TIMER)
            delay(1500)
            val core = CoreCartTransport { EngineMidi(e) }
            val fake = FakeFM1()
            val t = CombinedCartTransport(core, FakeCartTransport(fm1 = fake)) { true }
            val s = CartSender(t, null, SystemCartClock, timing, simulated = true)
            s.rescan()
            assertEquals(listOf("core", "fake"), s.state.value.ports.map { it.id })
            s.choose("core")
            s.refresh()
            assertEquals("RemoteFM1 0.3.1 in the engine answers BANKS", CartSender.Firmware.v031, s.state.value.firmware)
            assertEquals(0, s.state.value.banks?.banks?.size)
            val c = cart(7)
            s.add("test.syx", c.bytes)
            s.send()
            assertTrue("sent: ${s.state.value.state}", s.state.value.state is CartSender.State.Sent)
            assertEquals("stored in the app", 1, s.state.value.banks?.banks?.size)
            assertEquals(CartKeep.crc(c), s.state.value.banks!!.banks[0].crc)
            assertEquals("kept", 1, keep.list()!!.size)
            s.sync()
            val r = s.state.value.lastSync!!
            assertNull("${r.failed}", r.failed)
            assertEquals(1, r.copied["FM-1 (simulated)"]?.size)
            assertEquals(setOf(CartKeep.crc(c)), fake.slotsNow.values.map { it.crc }.toSet())
            s.sync()
            assertEquals("again: nothing to copy", 1, s.state.value.lastSync!!.both)
            assertTrue(s.state.value.lastSync!!.copied.isEmpty())
            withMain { core.close() }
        } finally {
            e.close()
            CartKeep.folder = before
            keep.deleteRecursively()
        }
        Unit
    }

    /** the debug virtual FM-1 answers no bank list: the copy says so and sends nothing */
    @Test fun theBankCopyRefusesPlainlyWithTheVirtualFm1() = runBlocking {
        val f = remoteFm1()
        assumeTrue("no remotefm1-*.rfm1 in files/test-rfm1", f != null)
        FakeFm1Service.enable(ctx, true)
        val e = RfmEngine()
        try {
            assertNull(e.loadWasm(f!!.path))
            assertTrue(e.boot(null))
            e.setClock(RfmEngine.CLOCK_TIMER)
            val core = CoreCartTransport { EngineMidi(e) }
            val t = CombinedCartTransport(core, AndroidCartTransport(ctx, com.quixotic7.remotefm1.Fm1Candidate.VIRTUAL_NAME)) { true }
            val s = CartSender(t, null, SystemCartClock, timing, simulated = false)
            var tries = 0
            while (s.state.value.syncPair == null && tries++ < 50) { s.rescan(); delay(200) }
            assumeTrue("the virtual FM-1 wasn't listed: ${s.state.value.ports}", s.state.value.syncPair != null)
            s.sync()
            val st = s.state.value.state
            assertTrue("$st", st is CartSender.State.Failed && st.text.contains("didn't list its banks"))
            withMain { core.close() }
        } finally {
            e.close()
            FakeFm1Service.enable(ctx, false)
        }
        Unit
    }

    private suspend fun withMain(block: () -> Unit) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
}
