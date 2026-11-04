// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Tests/RFMKitTests/TransferTests.swift (testMakeReadAndImport; the older
// versions half is FirmwareStoreTest.olderVersionsFromReleases): the .rfm1transfer file made, read back, a damaged one
// refused, imported into a fresh device with its firmware file; and its manifest in iOS's form.
package com.quixotic7.remotefm1.data

import com.quixotic7.remotefm1.firmware.FakeRfm1
import com.quixotic7.remotefm1.firmware.FirmwareLibrary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class TransferTest {
    private val root = tempDir("rfm1-xfer")

    private fun device(n: String): Pair<StateLibrary, FirmwareLibrary> {
        val d = File(root, n)
        val fw = FirmwareLibrary(File(d, "Firmware"), FakeRfm1)
        fw.sync()
        return StateLibrary(File(d, "States"), codec = TestCodec.codec) to fw
    }

    private fun refused(d: ByteArray, want: TransferFile.FileError) {
        try {
            TransferFile.decode(d, TestCodec.codec)
            fail("read")
        } catch (e: TransferFile.FileError) {
            assertEquals(want, e)
        }
    }

    @Test fun makeReadAndImport() {
        val (states, fw) = device("phone")
        val amb = fw.import(FakeRfm1.amb)
        assertEquals(null, amb.error)
        val flash = ByteArray(4096) { (it and 0xFF).toByte() }
        states.save("Pads", firmwareID = "amb", firmwareName = "Amb", kind = "wasm", version = amb.version, flash = flash,
            master = 700, origin = "app")
        states.save("Old one", firmwareID = "fizz", firmwareName = "FIZZ", kind = "native", version = "0.9",
            flash = ByteArray(1024) { 3 }, origin = "app")
        val cur = listOf(Backup.Current("amb", "Amb", "wasm", amb.version, flash = ByteArray(4096) { 9 }),
            Backup.Current("loom", "LOOM-8", "native", "1.0", flash = ByteArray(4096) { 0xFF.toByte() }))   // (erased: left out)
        val data = TransferFile.make(states, cur, fw.files.value, { fw.path(it.sha256).readBytes() }, "Pixel", "0.1.0 (1)")

        val d = TransferFile.decode(data, TestCodec.codec)
        assertEquals("Pixel", d.device)
        assertEquals("0.1.0 (1)", d.app)
        assertEquals(3, d.states.size)
        assertEquals(listOf("Current memory from Pixel"), d.states.filter { it.second }.map { it.first.state.name })
        assertEquals("amb's file; FIZZ is built in", 1, d.firmware.size)
        assertEquals("amb-1.3.rfm1", d.firmware[0].first)

        // the manifest as iOS writes it
        val n = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
        val m = Json.obj(String(data, 12, n))
        assertEquals(setOf("format", "app", "device", "date", "entries"), m.keys)
        @Suppress("UNCHECKED_CAST")
        val entries = m["entries"] as List<Map<String, Any?>>
        assertEquals(setOf("kind", "name", "size", "sha256", "current"), entries[0].keys)
        assertEquals(setOf("kind", "name", "size", "sha256"), entries.last().keys)

        // damaged: one byte changed, cut short, not one
        val bad = data.copyOf()
        bad[bad.size - 10] = (bad[bad.size - 10].toInt() xor 0xFF).toByte()
        refused(bad, TransferFile.FileError.Damaged)
        refused(data.copyOf(data.size - 1), TransferFile.FileError.Damaged)
        refused("hello".toByteArray(), TransferFile.FileError.NotTransfer)

        // the tablet: nothing yet. Everything comes in; FIZZ 0.9's state waits (FIZZ is there, another version)
        val (iStates, iFw) = device("tablet")
        val r = TransferFile.importInto(d, iStates, iFw, File(root, "tmp")) {
            listOf("fizz" to "1.0") + iFw.files.value.map { it.coreID to it.version }
        }
        assertEquals(3, r.statesAdded)
        assertEquals(listOf("${amb.name} ${amb.version}"), r.firmwareAdded)
        assertTrue(r.failed.toString(), r.failed.isEmpty())
        assertEquals(listOf("amb"), r.current.map { it.firmwareID })
        assertTrue(r.missing.isEmpty())
        assertArrayEquals(ByteArray(4096) { 9 }, iStates.flash(r.current[0]))
        assertEquals(700, iStates.list("amb").first { it.name == "Pads" }.master)
        assertEquals(amb.sha256, iFw.files.value.first().sha256)
        assertEquals("From Pixel: 3 states added. Added ${amb.name} ${amb.version}.", r.text)

        // opened again: nothing doubled
        val again = TransferFile.importInto(d, iStates, iFw, File(root, "tmp")) { emptyList() }
        assertEquals(0, again.statesAdded)
        assertEquals(3, again.statesHere)
        assertEquals(1, again.firmwareHere)
        assertEquals("the current memory still offered", 1, again.current.size)
        assertEquals("nothing installed: both said", listOf("Amb ${amb.version}", "FIZZ 0.9"), again.missing)
        assertTrue(File(root, "tmp").listFiles().orEmpty().isEmpty())
    }

    /** a file from a newer app: said so */
    @Test fun newerFormat() {
        val json = """{"format":2,"app":"x","device":"y","date":0,"entries":[],"more":1}""".toByteArray()
        val n = json.size
        val d = "RFM1XFER".toByteArray() + byteArrayOf(n.toByte(), (n ushr 8).toByte(), 0, 0) + json
        refused(d, TransferFile.FileError.Newer(2))
        val ok = """{"format":1,"app":"x","device":"y","date":1800000000.5,"entries":[]}""".toByteArray()
        val e = TransferFile.decode("RFM1XFER".toByteArray() + byteArrayOf(ok.size.toByte(), 0, 0, 0) + ok)
        assertEquals("y", e.device)
        assertEquals(1800000000.5, e.date, 0.0)
    }
}
