// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Tests/RFMKitTests/CartSyncTests.swift (bd0711a, 41e8471); changes: JUnit 4
// and runBlocking; plus the in-app target's rules (dc771c8: no pause for it, another target forgets the banks, the
// combined ports).
// Copying RemoteFM1 in the app's banks to an FM-1: from the app's kept copies (CartKeep), by CRC, one way.
package com.quixotic7.remotefm1.cart

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** two pretend 0.3.1 units, one as "RemoteFM1 in this app", one as an FM-1 */
class TwoUnits : CartTransport {
    val app = FakeFM1()
    val dev = FakeFM1()
    @Volatile private var heard: ((ByteArray) -> Unit)? = null
    override fun ports() = listOf(CoreCartTransport.PORT, CartPort("fm1", "FM-1", false))
    override fun listen(handler: (ByteArray) -> Unit) { heard = handler }
    override suspend fun send(bytes: ByteArray, port: CartPort) {
        val u = if (port.id == CoreCartTransport.PORT.id) app else dev
        heard?.let { h -> for (m in u.receive(bytes)) h(m) }
    }
}

/** a panel that counts its pauses */
class CountingPanel : CartPanelPausing {
    var pauses = 0
    override suspend fun pauseForCart(): Boolean { pauses++; return true }
    override fun resumeAfterCart(restart: Boolean) {}
}

class CartSyncTest {
    private fun cart(seed: Int): DX7Cart {
        val b = ByteArray(4104)
        val head = intArrayOf(0xF0, 0x43, 0x00, 0x09, 0x20, 0x00)
        for (i in head.indices) b[i] = head[i].toByte()
        var sum = 0
        for (i in 0 until 4096) {
            val v = (i * seed + 3) and 0x7F
            b[6 + i] = v.toByte()
            sum = (sum + v) and 0x7F
        }
        b[4102] = ((128 - sum) and 0x7F).toByte()
        b[4103] = 0xF7.toByte()
        return DX7Cart.parse(b)
    }

    private val timing = CartSender.Timing(reply = 50, stored = 50, poll = 1, namePoll = 1, nameSettle = 6, afterPause = 0)

    @Test fun syncCopiesTheAppsBanksToTheFM1() = runBlocking {
        val dir = Files.createTempDirectory("keep").toFile()
        val (a, b, c) = Triple(cart(3), cart(5), cart(7))
        val t = TwoUnits()
        val panel = CountingPanel()
        val s = CartSender(t, panel, SystemCartClock, timing, simulated = true)
        // a and c in the app (c never kept: put there another way), b on both
        t.app.receive(a.bytes)
        t.app.receive(b.bytes)
        t.app.receive(c.bytes)
        t.dev.receive(b.bytes)
        CartKeep.keep(a, dir)
        CartKeep.keep(b, dir)
        assertArrayEquals(b.bytes, CartKeep.cart(CartKeep.crc(b), dir)?.bytes)
        assertNull(CartKeep.cart(CartKeep.crc(c), dir))
        val before = CartKeep.folder
        CartKeep.folder = dir
        try {
            s.rescan()
            assertNotNull(s.state.value.syncPair)
            s.sync()
        } finally {
            CartKeep.folder = before
        }
        val r = s.state.value.lastSync!!
        assertNull(r.failed)
        assertEquals("a", 1, r.copied["FM-1"]?.size)
        assertNull("one way: nothing back to the app", r.copied[CoreCartTransport.PORT.name])
        assertEquals(1, r.both)
        assertEquals("c: the app never had a copy", 1, r.noCopy.size)
        assertEquals(setOf(a, b).map(CartKeep::crc).toSet(), t.dev.slotsNow.values.map { it.crc }.toSet())
        assertEquals(setOf(a, b, c).map(CartKeep::crc).toSet(), t.app.slotsNow.values.map { it.crc }.toSet())
        assertTrue(CartSender.syncText(r).contains("no copy of"))
        assertEquals("the FM-1 paused once, the app never", 1, panel.pauses)
        assertEquals("its name as in the app", t.app.slotsNow.values.first { it.crc == CartKeep.crc(a) }.name,
                     t.dev.slotsNow.values.first { it.crc == CartKeep.crc(a) }.name)
        dir.deleteRecursively()
        Unit
    }

    @Test fun keepOncePerCrcAndFindAHandPutFile() {
        val dir = Files.createTempDirectory("keep").toFile()
        val a = cart(11)
        CartKeep.keep(a, dir)
        CartKeep.keep(a, dir)
        assertEquals(1, dir.list()!!.size)
        assertTrue(dir.list()!![0].endsWith(" ${CartKeep.hex(CartKeep.crc(a))}.syx"))
        val other = cart(13)
        File(dir, "mine.SYX").writeBytes(other.bytes)                 // (a .syx put there by hand)
        assertArrayEquals(other.bytes, CartKeep.cart(CartKeep.crc(other), dir)?.bytes)
        assertNull("no folder: nothing", CartKeep.cart(CartKeep.crc(a), null))
        dir.deleteRecursively()
    }

    @Test fun sendingToTheAppKeepsACopyWithoutAPause() = runBlocking {
        val dir = Files.createTempDirectory("keep").toFile()
        val t = TwoUnits()
        val panel = CountingPanel()
        val s = CartSender(t, panel, SystemCartClock, timing, simulated = true)
        val before = CartKeep.folder
        CartKeep.folder = dir
        try {
            s.rescan()
            s.choose(CoreCartTransport.PORT.id)
            assertTrue(s.state.value.inApp)
            s.add("x.syx", cart(17).bytes)
            s.send()
        } finally {
            CartKeep.folder = before
        }
        assertTrue(s.state.value.state is CartSender.State.Sent)
        assertEquals(0, panel.pauses)
        assertEquals(1, t.app.slotsNow.size)
        assertEquals(0, t.dev.slotsNow.size)
        assertEquals("kept", 1, dir.list()!!.size)
        // another target: its banks are read again
        assertNotNull(s.state.value.banks)
        s.choose("fm1")
        assertNull(s.state.value.banks)
        assertEquals(CartSender.Firmware.unknown, s.state.value.firmware)
        dir.deleteRecursively()
        Unit
    }

    @Test fun combinedListsTheAppOnlyWhileRemoteFM1Runs() = runBlocking {
        var running = false
        val dev = FakeCartTransport()
        val core = CoreCartTransport { null }
        val c = CombinedCartTransport(core, dev) { running }
        assertEquals(listOf("fake"), c.ports().map { it.id })
        running = true
        assertEquals(listOf("core", "fake"), c.ports().map { it.id })
        c.send(byteArrayOf(0xF0.toByte(), 0xF7.toByte()), dev.ports()[0])
        assertEquals(1, dev.sent.size)
        assertFalse(c.sharesPanelPort)
    }
}
