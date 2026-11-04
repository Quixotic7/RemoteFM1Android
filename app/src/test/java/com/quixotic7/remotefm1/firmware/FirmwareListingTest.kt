// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Tests/RFMKitTests/FirmwareListingTests.swift (testOnlyTheNewestVersionListed,
// testSorting; testReleaseDates is in FirmwareStoreTest.olderVersionsFromReleases, testCatalogEditorAndPort's feed half in
// FirmwareFeedsTest; testHeadroomAndScreenOnDropouts is the engine's).
package com.quixotic7.remotefm1.firmware

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FirmwareListingTest {
    private fun core(id: String, fw: String, v: String, wasm: Boolean, sha: String = "") =
        CoreInfo(id, name = fw, version = v, kind = if (wasm) "wasm" else "native", coreID = fw, sha256 = sha)

    @Test fun onlyTheNewestVersionListed() {
        val cores = listOf(core("choralroot", "choralroot", "0.26", false), core("felucca", "felucca", "1.6.1", false),
            core("rfm1:choralroot@0.28", "choralroot", "0.28", true), core("rfm1:choralroot@0.29", "choralroot", "0.29", true),
            core("rfm1:felucca", "felucca", "1.6.1", true), core("rfm1:rainbow@1.1.4", "rainbow", "1.1.4", true),
            core("rfm1:rainbow@1.1.5", "rainbow", "1.1.5-rb", true))
        assertEquals("older versions, and an import the same as the built-in",
            setOf("choralroot", "rfm1:choralroot@0.28", "rfm1:felucca", "rfm1:rainbow@1.1.4"), FirmwareListing.superseded(cores))
        assertEquals(listOf("felucca", "rfm1:choralroot@0.29", "rfm1:rainbow@1.1.5"),
            FirmwareListing.visible(cores, "felucca").map { it.id })
        assertEquals("the selected one stays listed", 4, FirmwareListing.visible(cores, "rfm1:choralroot@0.28").size)
        // a rebuild of the same version (RemoteFM1FW keeps the version): the one added last is listed
        val r1 = core("rfm1:rb-old", "rainbow", "1.1.5-rb", true, "old")
        val r2 = core("rfm1:rb-new", "rainbow", "1.1.5-rb", true, "new")
        val added = mapOf("old" to Instant.ofEpochSecond(1), "new" to Instant.ofEpochSecond(2))
        assertEquals(listOf("rfm1:rb-new"), FirmwareListing.visible(listOf(r1, r2), "", added).map { it.id })
        assertEquals(listOf("rfm1:rb-new"), FirmwareListing.visible(listOf(r2, r1), "", added).map { it.id })
        val files = listOf(ImportedFirmware("old", "rainbow", "R", "1.1.5-rb", "", 1, Instant.ofEpochSecond(1)),
            ImportedFirmware("new", "rainbow", "R", "1.1.5-rb", "", 1, Instant.ofEpochSecond(2)))
        assertEquals(added, FirmwareListing.addedDates(files))
    }

    @Test fun sorting() {
        val d = FirmwareListing.MapStore()
        val cores = listOf(core("a", "a", "1", false), core("b", "b", "1", false), core("c", "c", "1", false))
        FirmwareListing.used(cores[2], Instant.ofEpochSecond(100), d)
        FirmwareListing.used(cores[1], Instant.ofEpochSecond(200), d)
        assertEquals(listOf("b", "c", "a"), FirmwareListing.sorted(cores, FirmwareListing.Sort.USED, { null }, d).map { it.id })
        assertEquals(Instant.ofEpochSecond(200), FirmwareListing.lastUsed("b", d))
        val dates = mapOf("a" to Instant.ofEpochSecond(50), "c" to Instant.ofEpochSecond(60))
        assertEquals(listOf("c", "a", "b"), FirmwareListing.sorted(cores, FirmwareListing.Sort.UPDATED, { dates[it] }, d).map { it.id })
        assertEquals(listOf("a", "b", "c"), FirmwareListing.sorted(cores, FirmwareListing.Sort.NAME, { dates[it] }, d).map { it.id })
        assertEquals(FirmwareListing.Sort.UPDATED, FirmwareListing.Sort.of("updated"))
        assertEquals(FirmwareListing.Sort.NAME, FirmwareListing.Sort.of(null))
    }
}
