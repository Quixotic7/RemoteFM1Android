// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/CartTests.swift; changes: JUnit 4 and runBlocking; plus
// the Android case (a transport whose port the panel holds: BANKS and NAME inside a pause, done() before the panel
// starts) and SysexAssembler; PickerFolderTests is iOS's file picker and isn't ported.
// DX7Cart (the parser and its fixes) and CartSender against a fake transport, panel and clock: the panel paused,
// 400 ms, the fixed dump's bytes exactly, 1 s between dumps, Bluetooth in 512-byte parts, the panel started again;
// 0.3.1's banks (SA_INFO), NAME's golden bytes and the busy BANKS polled until the new name shows.
package com.quixotic7.remotefm1.cart

import com.quixotic7.remotefm1.install.SysexAssembler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CartTest {
    companion object {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

        /** a cartridge of the real shape: 32 voices "VOICE 01".."VOICE 32", varied data bytes, a right checksum */
        fun dump(channel: Int = 0, seed: Int = 0): ByteArray {
            val o = java.io.ByteArrayOutputStream()
            o.write(b(0xF0, 0x43, channel, 0x09, 0x20, 0x00))
            for (k in 0 until 32) {
                for (i in 0 until 118) o.write((i * 7 + k * 13 + seed) and 0x7F)
                o.write("VOICE %02d  ".format(k + 1).toByteArray().copyOf(10))
            }
            val a = o.toByteArray()
            return a + b(DX7Cart.checksum(a, 6, 4102), 0xF7)
        }

        val two = listOf(UserBank(0, 1, 0, "BRASS 1"), UserBank(4, 2, 0, "PIANO 2"))
    }

    private fun parseError(block: () -> Unit): DX7Cart.ParseError? = try {
        block(); null
    } catch (e: DX7Cart.ParseError) {
        e
    }

    @Test fun valid() {
        val d = dump()
        assertEquals(4104, d.size)
        val c = DX7Cart.parse(d)
        assertArrayEquals(d, c.bytes)
        assertFalse(c.checksumFixed)
        assertEquals(32, c.voices.size)
        assertEquals("VOICE 01", c.voices.first())
        assertEquals("VOICE 32", c.voices.last())
    }

    @Test fun badChecksumFixed() {
        val d = dump()
        d[4102] = ((d[4102] + 5) and 0x7F).toByte()
        val c = DX7Cart.parse(d)
        assertTrue(c.checksumFixed)
        assertArrayEquals(dump(), c.bytes)
        assertEquals("checksum corrected", CartFile.of("a.syx", d).fixes)
    }

    @Test fun channelFixed() {
        val c = DX7Cart.parse(dump(channel = 5))
        assertEquals(5, c.originalChannel)
        assertEquals(0, c.bytes[2].toInt())
        assertArrayEquals(dump(), c.bytes)
    }

    @Test fun rejected() {
        assertEquals(DX7Cart.ParseError.WrongSize(4103), parseError { DX7Cart.parseFile(dump().copyOf(4103)) })
        assertEquals(DX7Cart.ParseError.Empty, parseError { DX7Cart.parseFile(ByteArray(0)) })
        val single = dump().also { it[3] = 0x00 }               // (format 0: a single voice's header)
        assertEquals(DX7Cart.ParseError.NotCartridge, parseError { DX7Cart.parse(single) })
        val other = dump().also { it[1] = 0x41 }
        assertEquals(DX7Cart.ParseError.NotDX7, parseError { DX7Cart.parse(other) })
        val high = dump().also { it[100] = 0x80.toByte() }
        assertEquals(DX7Cart.ParseError.BadByte(100), parseError { DX7Cart.parse(high) })
        val f = CartFile.of("x.syx", ByteArray(163))
        assertTrue(f.carts.isEmpty())
        assertNotNull(f.error)
    }

    @Test fun twoDumpsInOneFile() {
        assertEquals(2, DX7Cart.parseFile(dump() + dump(seed = 3)).size)
    }

    // ---- the sender

    /** one timeline of what happened, in order */
    class Log {
        val events = mutableListOf<String>()
        @Volatile var now = 0
        @Synchronized fun add(s: String) { events.add("$now $s") }
    }

    class FakeClock(val log: Log) : CartClock {
        override suspend fun sleep(ms: Int) = synchronized(log) { log.now += ms }
    }

    class FakePanel(val log: Log) : CartPanelPausing {
        var running = true
        override suspend fun pauseForCart(): Boolean {
            log.add("pause")
            val was = running
            running = false
            return was
        }

        override fun resumeAfterCart(restart: Boolean) {
            log.add("resume $restart")
            running = restart
        }
    }

    class Made(val s: CartSender, val t: FakeCartTransport, val p: FakePanel, val log: Log)

    private fun make(ports: List<CartPort>, fm1: FakeFM1? = null, shares: Boolean = false): Made {
        val log = Log()
        val t = FakeCartTransport(ports, fm1, shares)
        t.onSend = { log.add("send ${it.size}") }
        val p = FakePanel(log)
        return Made(CartSender(t, p, FakeClock(log), simulated = true), t, p, log)
    }

    private fun make031(fm1: FakeFM1, bluetooth: Boolean = false, shares: Boolean = false) =
        make(listOf(CartPort("1", "FM-1", bluetooth)), fm1, shares)

    private val CartSender.st get() = state.value

    @Test fun sendsFixedDumpsInOrder() = runBlocking {
        val m = make(listOf(CartPort("1", "FM-1", false)))
        val bad = dump(channel = 3)
        bad[4102] = (bad[4102].toInt() xor 0x11).toByte()
        m.s.add(CartFile.of("a.syx", bad))
        m.s.add(CartFile.of("b.syx", dump(seed = 9)))
        m.s.send()
        assertEquals(CartSender.State.Sent(2), m.s.st.state)
        // (BANKS first, no reply in 1 s: 0.3.0, the plain send)
        assertEquals(listOf("0 send 8", "1000 pause", "1400 send 4104", "2400 send 4104", "2400 resume true"), m.log.events)
        assertEquals(CartSender.Firmware.v030, m.s.st.firmware)
        val sent = m.t.sent
        assertArrayEquals(dump(), sent[1].second)
        assertArrayEquals(dump(seed = 9), sent[2].second)
        assertEquals(listOf("1", "1", "1"), sent.map { it.first })
        assertTrue(m.p.running)
        assertEquals("Sent 2 cartridges: the FM-1 should now show the last one's USER bank.", m.s.st.resultText)
    }

    @Test fun bluetoothPaced() = runBlocking {
        val m = make(listOf(CartPort("b", "FM-1 7938", true)))
        m.s.add(CartFile.of("a.syx", dump()))
        m.s.send()
        assertEquals(10, m.t.sent.size)                          // BANKS, then 8 × 512 + 8
        assertArrayEquals(dump(), m.t.sent.drop(1).map { it.second }.reduce { a, c -> a + c })
        assertEquals("1000 pause", m.log.events[1])
        assertEquals("1400 send 512", m.log.events[2])
        assertEquals("2200 send 8", m.log.events[10])
        assertEquals("Sent: the FM-1 should now show the new USER bank.", m.s.st.resultText)
    }

    @Test fun severalPortsAsks() = runBlocking {
        val m = make(listOf(CartPort("1", "FM-1", false), CartPort("2", "FM-1 7938", true)))
        m.s.add(CartFile.of("a.syx", dump()))
        m.s.send()
        assertEquals(CartSender.State.Failed("Choose which FM-1 to send to."), m.s.st.state)
        assertTrue("the panel isn't touched", m.log.events.isEmpty())
        m.s.choose("1")
        m.s.send()
        assertEquals(listOf("1", "1"), m.t.sent.map { it.first })
    }

    @Test fun noPortNoCart() = runBlocking {
        val m = make(emptyList())
        m.s.add(CartFile.of("a.syx", dump()))
        m.s.send()
        assertTrue(m.s.st.state is CartSender.State.Failed)
        val m2 = make(listOf(CartPort("1", "FM-1", false)))
        m2.s.add(CartFile.of("bad.syx", b(0xF0, 0xF7)))
        m2.s.send()
        assertEquals(CartSender.State.Failed("No cartridge to send."), m2.s.st.state)
        assertTrue(m.log.events.isEmpty())
    }

    // ---- 0.3.1's banks (CartBanks.kt)

    @Test fun pack7() {
        val x = ByteArray(256) { it.toByte() }
        val p = SAInfo.pack7(x)
        assertEquals(256 + (256 + 6) / 7, p.size)
        assertTrue(p.all { it >= 0 })
        assertArrayEquals(x, SAInfo.unpack7(p))
        // remote_proto's golden ECHO and the spec's TARGET slot 4
        assertArrayEquals(b(0x0C, 0x00, 0x7F, 0x00, 0x7F, 0x01, 0x02, 0x03, 0x01, 0x2A),
                          SAInfo.pack7(b(0x00, 0x7F, 0x80, 0xFF, 1, 2, 3, 0xAA)))
        assertArrayEquals(b(0xF0, 0x7D, 0x52, 0x46, 0x0B, 0x00, 0x02, 0x04, 0xF7), SAInfo.encode(0x0B, b(2, 4)))
        val r = SAInfo.decode(b(0xF0, 0x7D, 0x52, 0x46, 0x4B, 0x00, 0x02, 0x04, 0x00, 0xF7))
        assertEquals(0x4B, r?.first)
        assertArrayEquals(b(2, 4, 0), r?.second)
        assertNull(SAInfo.decode(b(0xF0, 0x43, 0xF7)))
    }

    @Test fun bankListBody() {
        val l = BankList(next = 1, banks = listOf(UserBank(0, 0x0102_0304, 0xDEAD_BEEFL, "BRASS 1"),
                                                  UserBank(4, 9, 0x8000_0001L, "PIANO 2")))
        assertArrayEquals(b(0, 16, 2, 0, 1, 16), l.body.copyOf(6))
        assertArrayEquals(b(4, 3, 2, 1), l.body.copyOfRange(7, 11))
        assertEquals(l, BankList.of(SAInfo.unpack7(SAInfo.pack7(l.body))))
        assertNull(BankList.of(b(0, 16, 3, 0, 0, 16)))
        assertEquals(0xCBF4_3926L, crc32("123456789".toByteArray()))
    }

    @Test fun listWithoutPause() = runBlocking {
        val m = make031(FakeFM1(banks = two))
        m.s.refresh()
        assertEquals(CartSender.Firmware.v031, m.s.st.firmware)
        assertEquals(listOf("USER1", "USER5"), m.s.st.banks?.banks?.map { it.label })
        assertEquals(listOf("BRASS 1", "PIANO 2"), m.s.st.banks?.banks?.map { it.name })
        assertEquals(1, m.s.st.banks?.next)
        assertEquals("no pause for the list", listOf("0 send 8"), m.log.events)
        assertEquals(CartSender.State.Idle, m.s.st.state)
    }

    @Test fun sendConfirmedByStored() = runBlocking {
        val m = make031(FakeFM1(banks = two), bluetooth = true)
        m.s.add(CartFile.of("a.syx", dump()))
        m.s.add(CartFile.of("b.syx", dump(seed = 4)))
        m.s.send()
        val st = m.s.st
        assertEquals(CartSender.State.Sent(2), st.state)
        assertEquals(listOf(1, 2), st.storedSlots)
        assertEquals("Stored as USER2, USER3.", st.resultText)
        assertEquals("the list read again", listOf(0, 1, 2, 4), st.banks?.banks?.map { it.slot })
        assertEquals("VOICE 01", st.banks?.banks?.get(1)?.name)
        assertEquals(crc32(dump(), 6, 4102), st.banks?.banks?.get(1)?.crc)
        assertEquals("Bluetooth still paced", 16, m.t.sent.count { it.second.size == 512 })
        assertEquals(1, m.log.events.count { it.endsWith("pause") })
        assertEquals("${m.log.now} resume true", m.log.events.last())
        assertTrue(m.p.running)
        assertEquals("400 ms, 2 × 8 Bluetooth gaps, no 1 s wait: STORED says when", 2000, m.log.now)
    }

    @Test fun replace() = runBlocking {
        val fm1 = FakeFM1(banks = two)
        val m = make031(fm1)
        m.s.refresh()
        m.s.replace(4, CartFile.of("new.syx", dump(seed = 7)))
        assertEquals(CartSender.State.Done("USER5 replaced with new.syx."), m.s.st.state)
        assertEquals(2, m.s.st.banks?.banks?.size)
        assertEquals(3L, m.s.st.banks?.banks?.get(1)?.arrival)
        assertArrayEquals("TARGET before the dump", b(2, 4), SAInfo.decode(m.t.sent[1].second)?.second)
        assertEquals(4104, m.t.sent[2].second.size)
        assertEquals("0 pause", m.log.events[1])
        // a refused dump says so in words
        fm1.nextDumpStatus = 1
        m.s.replace(0, CartFile.of("x.syx", dump()))
        assertEquals(SAInfo.words(1), m.s.st.resultText)
        // a file of two
        m.s.replace(0, CartFile.of("two.syx", dump() + dump(seed = 1)))
        val e = (m.s.st.state as? CartSender.State.Failed)?.text ?: ""
        assertTrue(e, e.contains("holds 2"))
    }

    @Test fun deleteAndRename() = runBlocking {
        val fm1 = FakeFM1(banks = two)
        val m = make031(fm1)
        m.s.refresh()
        m.s.delete(0)
        assertEquals(CartSender.State.Done("USER1 removed."), m.s.st.state)
        assertEquals(listOf(4), m.s.st.banks?.banks?.map { it.slot })
        assertTrue(m.log.events.any { it.endsWith("pause") })
        m.s.delete(0)
        assertEquals(SAInfo.words(2), m.s.st.resultText)
        val before = m.log.events.count { it.endsWith("pause") }
        m.s.rename(4, "My Pianos!!!")
        assertEquals(CartSender.State.Done("USER5 renamed My Pianos!."), m.s.st.state)
        assertEquals("My Pianos!", m.s.st.banks?.banks?.first()?.name)
        assertEquals("NAME needs no pause", before, m.log.events.count { it.endsWith("pause") })
        fm1.inSession = true
        m.s.delete(4)
        assertEquals(SAInfo.words(4), m.s.st.resultText)
    }

    /** NAME's bytes against docs/CART-UPLOAD.md / sa_ubank.c's sa_info_cmd: `01 slot name[1..10]`, rf_pack7 groups */
    @Test fun nameGoldenBytes() {
        val body = CartSender.nameBody(0, "DX Base")!!
        assertArrayEquals(b(0x01, 0x00, 0x44, 0x58, 0x20, 0x42, 0x61, 0x73, 0x65), body)
        assertArrayEquals(b(0xF0, 0x7D, 0x52, 0x46, 0x0B, 0x00, 0x01, 0x00, 0x44, 0x58, 0x20, 0x42, 0x61, 0x00, 0x73, 0x65, 0xF7),
                          SAInfo.encode(SAInfo.REQUEST, body))
        assertEquals("10 characters at most (len <= 12)", 12, CartSender.nameBody(15, "ABCDEFGHIJKL")?.size)
        val e = CartSender.nameBody(1, "é1")!!
        assertArrayEquals("non-ASCII as spaces", b(0x20, 0x20, 0x31), e.copyOfRange(e.size - 3, e.size))
        assertNull(CartSender.nameBody(1, "   "))
    }

    /** the unit writes the name a moment after its reply: the list is read until it shows it; statuses in words */
    @Test fun renameWaitsForTheWriteAndSaysWhy() = runBlocking {
        val fm1 = FakeFM1(banks = two)
        fm1.nameLag = 4                                         // busy, old name, 4 BANKS replies
        val m = make031(fm1)
        m.s.refresh()
        m.s.rename(0, "DX Base")
        assertEquals(CartSender.State.Done("USER1 renamed DX Base."), m.s.st.state)
        assertEquals("DX Base", m.s.st.banks?.banks?.first()?.name)
        fm1.nameLag = 100
        m.s.rename(0, "Other")
        val e = (m.s.st.state as? CartSender.State.Failed)?.text ?: run { fail("not failed"); "" }
        assertTrue(e, e.contains("still lists USER1 as \"DX Base\""))
        m.s.rename(0, "Again")
        assertEquals("Rename: ${SAInfo.words(3)} (status 3)", m.s.st.resultText)
        m.s.rename(7, "Empty")
        assertEquals("Rename: ${SAInfo.words(2)} (status 2)", m.s.st.resultText)
        fm1.silent = true
        m.s.rename(4, "X")
        assertEquals("Rename: the FM-1 didn't answer NAME (no reply in 1000 ms).", m.s.st.resultText)
    }

    @Test fun silentIsV030() = runBlocking {
        val m = make031(FakeFM1(silent = true))
        m.s.refresh()
        assertEquals(CartSender.Firmware.v030, m.s.st.firmware)
        assertNull(m.s.st.banks)
        assertEquals("1 s timeout", 1000, m.log.now)
        m.s.replace(0, CartFile.of("a.syx", dump()))
        assertTrue(m.s.st.state is CartSender.State.Failed)
        m.s.add(CartFile.of("a.syx", dump()))
        m.s.send()
        assertEquals(CartSender.State.Sent(1), m.s.st.state)
        assertEquals("Sent: the FM-1 should now show the new USER bank.", m.s.st.resultText)
        assertEquals(4104, m.t.sent.last().second.size)
    }

    @Test fun noStoredTimesOut() = runBlocking {
        val fm1 = FakeFM1()
        val m = make031(fm1)
        m.s.refresh()
        fm1.silent = true                                       // (answers nothing from now on)
        m.s.add(CartFile.of("a.syx", dump()))
        val t0 = m.log.now
        m.s.send()
        assertEquals("Cartridge 1: the FM-1 didn't confirm it. Check its screen.", m.s.st.resultText)
        assertTrue(m.log.now - t0 >= 2400)
        assertTrue("the panel started again", m.p.running)
    }

    // ---- Android: the panel's link holds the port

    /** BANKS and NAME go while the panel is paused, the port let go (done) before the panel starts again */
    @Test fun sharedPortPausesForEveryRequest() = runBlocking {
        val fm1 = FakeFM1(banks = two)
        val m = make031(fm1, shares = true)
        m.s.refresh()
        assertEquals(CartSender.Firmware.v031, m.s.st.firmware)
        assertEquals(listOf("0 pause", "400 send 8", "400 resume true"), m.log.events)
        assertEquals(1, m.t.doneCalls)
        fm1.nameLag = 2
        m.s.rename(0, "DX Base")
        assertEquals(CartSender.State.Done("USER1 renamed DX Base."), m.s.st.state)
        assertEquals(2, m.log.events.count { it.endsWith("pause") })
        assertTrue(m.log.events.last().endsWith("resume true"))
        assertEquals(2, m.t.doneCalls)
        // a send from scratch: BANKS inside the pause too, then the dump and STORED, the list again, one pause
        val m2 = make031(FakeFM1(banks = two), shares = true)
        m2.s.add(CartFile.of("a.syx", dump()))
        m2.s.send()
        assertEquals("Stored as USER2.", m2.s.st.resultText)
        assertEquals(listOf("0 pause", "400 send 8", "400 send 4104", "400 send 8", "400 resume true"), m2.log.events)
        assertTrue(m2.p.running)
    }

    @Test fun sysexAssembler() {
        val got = mutableListOf<ByteArray>()
        val a = SysexAssembler({ got.add(it) })
        a.feed(b(0xF0, 0x7D, 0x52))
        a.feed(b(0xF8, 0x46, 0x4B, 0x00, 0xF7, 0x90, 0x40, 0x7F, 0xF0, 0x01, 0x90, 0x02, 0xF7, 0xF0, 0x05, 0xF7))
        assertEquals(2, got.size)
        assertArrayEquals(b(0xF0, 0x7D, 0x52, 0x46, 0x4B, 0x00, 0xF7), got[0])
        assertArrayEquals("a status byte ends a SysEx", b(0xF0, 0x05, 0xF7), got[1])
    }
}
