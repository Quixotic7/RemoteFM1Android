// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The Kotlin port of FM1Kit's protocol on its own (FM1Kit's own tests aren't ported; these check the bits a port can
// get wrong): the 7-bit stream packing, the handshake and its reply, read requests and answers, identities, FwscPackage
// on RemoteFM1 0.2.3's real package, and InstallProgress never going backwards.
package com.quixotic7.remotefm1.install

import com.quixotic7.remotefm1.install.fm1kit.FM1Identity
import com.quixotic7.remotefm1.install.fm1kit.FM1Message
import com.quixotic7.remotefm1.install.fm1kit.FM1ReadRequest
import com.quixotic7.remotefm1.install.fm1kit.FwscFailure
import com.quixotic7.remotefm1.install.fm1kit.FwscPackage
import com.quixotic7.remotefm1.install.fm1kit.InstallProgress
import com.quixotic7.remotefm1.install.fm1kit.SevenBit
import com.quixotic7.remotefm1.install.fm1kit.UpdateEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FM1KitPortTest {
    private fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    @Test fun sevenBitStream() {
        // one bit stream, LSB first: 0xFF -> 0x7F, 0x01 (not the MSB-byte scheme)
        assertArrayEquals(b(0x7F, 0x01), SevenBit.pack(b(0xFF)))
        assertArrayEquals(b(0x00, 0x7E, 0x03), SevenBit.pack(b(0x00, 0xFF)))
        val x = ByteArray(1000) { (it * 37).toByte() }
        val p = SevenBit.pack(x)
        assertTrue(p.all { it >= 0 })
        assertArrayEquals(x, SevenBit.unpack(p))
    }

    @Test fun handshakeAndRequests() {
        val id = FM1Identity.parse("FM-1_52000203")!!
        assertEquals(id, FM1Identity.fromHandshakeReply(id.handshakeReply()))
        val reply = id.handshakeReply()
        reply[10] = (reply[10].toInt() xor 1).toByte()
        assertNull("a changed byte", FM1Identity.fromHandshakeReply(reply))
        val r = FM1ReadRequest(address = 0x1234, length = 512)
        assertEquals(r, FM1ReadRequest.parse(r.encoded()))
        val data = ByteArray(512) { it.toByte() }
        val (rr, d) = FM1ReadRequest.parseResponse(r.response(data))!!
        assertEquals(r, rr)
        assertArrayEquals(data, d)
        assertTrue(FM1ReadRequest(address = FM1Message.writeFinishAddress, length = 8).isFinish)
        assertEquals(10, FM1Message.handshakeQuery.size)
    }

    @Test fun identities() {
        val l = FM1Identity.parse("ota-FM-1_900")!!
        assertTrue(l.isLoader)
        assertEquals("FM-1", l.hardwareModel)
        val armed = FM1Identity.parse("ota-FM1C_db1642b2b6fa5c2c")!!
        assertEquals("db1642b2b6fa5c2c", armed.armedBuildPrefix)
        assertEquals("FM-1", armed.hardwareModel)
        assertTrue(FM1Identity.parse("FM-1_8000012")!!.isOpenFirmware)
        assertFalse(FM1Identity.parse("FM-1_8010300")!!.isOpenFirmware)
        assertNull(FM1Identity.parse("FM-1"))
        assertNull(FM1Identity.parse("FM 1_9"))
        assertEquals("FM-1_15", FM1Identity.of("FM-1", 15).text)
    }

    @Test fun realPackage() {
        val data = javaClass.getResourceAsStream("/RemoteFM1/remote-0.2.3.fwsc")!!.use { it.readBytes() }
        val p = FwscPackage.read(data)
        assertEquals("FM-1_52000203", p.product)
        assertEquals(data.size - 20, p.logicalImage.size)
        assertTrue(p.isFirmware)
        try {
            FwscPackage.read(data.copyOf(500))
        } catch (e: FwscFailure) {
            assertEquals(FwscFailure.TooShort, e)
        }
        val broken = data.copyOf()
        broken[300_000] = (broken[300_000].toInt() xor 0x55).toByte()
        val e = runCatching { FwscPackage.read(broken) }.exceptionOrNull()
        assertTrue("$e", e is FwscFailure.DamagedPart || e is FwscFailure.DamagedHeader)
    }

    @Test fun progressNeverGoesBack() {
        var p = InstallProgress()
        val events = listOf(UpdateEvent.Staging(10), UpdateEvent.WaitingForLoader,
                            UpdateEvent.LoaderFound(FM1Identity.parse("ota-FM-1_9")!!),
                            UpdateEvent.Writing(0.5, 10), UpdateEvent.Writing(0.2, 11), UpdateEvent.Checking(0.5, 3),
                            UpdateEvent.Rebooting, UpdateEvent.Finished(FM1Identity.parse("FM-1_52000203")!!))
        var last = 0.0
        for (e in events) {
            p = p.record(e)
            assertTrue("$e", p.fraction >= last)
            last = p.fraction
        }
        assertEquals(1.0, p.fraction, 0.0)
        assertTrue(p.isFinished)
        assertEquals(InstallProgress.Stage.Restarting, p.stage)
    }
}
