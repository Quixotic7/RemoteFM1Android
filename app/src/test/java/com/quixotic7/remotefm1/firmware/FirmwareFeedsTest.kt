// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/FirmwareStoreTests.swift (testVersionOrder,
// testFileNames, testSiteCatalog, testGitHubRelease, testMerge, testInstallState): version order, the two feeds read
// from saved copies of their answers (resources/firmware: iOS's fixtures, trimmed), the merge, the install state.
package com.quixotic7.remotefm1.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FirmwareFeedsTest {
    private fun entry(id: String, version: String, sha: String, size: Long, urls: List<String>) =
        AvailableFirmware(id, name = id.uppercase(), version = version,
            downloads = urls.map { AvailableFirmware.Download(it, "test", sha, size, "$id-$version.rfm1") })

    private fun file(id: String, v: String, sha: String = java.util.UUID.randomUUID().toString()) =
        ImportedFirmware(sha, id, id, v, "", 1)

    @Test fun versionOrder() {
        val less = listOf(
            "1.6.1" to "1.6.10", "0.26.1" to "0.27", "0.12.0-beta.12" to "0.12.0", "0.12.0-beta.2" to "0.12.0-beta.12",
            "1.0-alpha" to "1.0-beta", "1.0-beta" to "1.0-beta.1", "1.0-rc.1" to "1.0", "0.9.9" to "1.0", "1.2" to "v1.10",
            "1.0.0" to "1.0.1", "2" to "10")
        for ((a, b) in less) {
            assertTrue("$a < $b", FirmwareVersion.less(a, b))
            assertFalse("not $b < $a", FirmwareVersion.less(b, a))
        }
        for ((a, b) in listOf("1.0" to "1.0.0", "v1.3" to "1.3", "1.3+build.5" to "1.3", "0.11" to "0.11")) {
            assertEquals("$a == $b", 0, FirmwareVersion.compare(a, b))
        }
        assertTrue(CoreRef.older("1.6.2-beta.1", "1.6.2"))
    }

    @Test fun fileNames() {
        assertEquals("choralroot" to "0.26.1", FeedSnapshot.idAndVersion("choralroot-0.26.1.rfm1"))
        assertEquals("fizz" to "0.12.0-beta.12", FeedSnapshot.idAndVersion("fizz-0.12.0-beta.12.rfm1"))
        assertEquals("hortator-bt" to "1.0", FeedSnapshot.idAndVersion("hortator-bt-1.0.rfm1"))
        assertEquals("x0x" to "v2.1", FeedSnapshot.idAndVersion("x0x-v2.1.rfm1"))
        assertNull(FeedSnapshot.idAndVersion("noversion.rfm1"))
        assertNull(FeedSnapshot.idAndVersion("amb-1.3.zip"))
    }

    @Test fun siteCatalog() {
        val s = FeedSnapshot.parse(FakeRfm1.catalogJSON, FirmwareFeed.REMOTEFM1)
        assertEquals(7, s.details.size)                                 // (every firmware: details for the other feeds)
        assertEquals(listOf("fizz", "s1vave"), s.firmware.map { it.id }.sorted())  // (only those with an .rfm1)
        val fizz = s.firmware.first { it.id == "fizz" }
        assertEquals("FIZZ", fizz.name)
        assertEquals("0.12.0-beta.12", fizz.version)
        assertEquals("Michael P Jones (Quixotic7)", fizz.author)
        assertEquals("Groovebox", fizz.category)
        assertEquals("FM TONE step sequencer", fizz.summary)
        assertTrue(fizz.about.startsWith("A step sequencer for FM TONE"))
        assertTrue(fizz.beta)
        assertEquals(1, fizz.downloads.size)
        assertEquals("https://remotefm1.com/fw/fizz-0.12.0-beta.12.rfm1", fizz.downloads[0].url)
        assertEquals("e511348e9716f3144e7e4b7679a2a563af093322c7b9d2ed043d03fd5508bd99", fizz.sha256)
        assertEquals(1_260_254L, fizz.size)
        assertEquals("fizz-0.12.0-beta.12.rfm1", fizz.fileName)
        assertThrows(FeedSnapshot.ParseError::class.java) { FeedSnapshot.parse("<html>".toByteArray(), FirmwareFeed.REMOTEFM1) }
    }

    @Test fun gitHubRelease() {
        val s = FeedSnapshot.parse(FakeRfm1.releaseJSON, FirmwareFeed.RELEASES)
        assertEquals(listOf("amb", "choralroot", "coralflower", "felucca", "s1vave"), s.firmware.map { it.id })
        val a = s.firmware[0]
        assertEquals("1.3", a.version)
        assertEquals(FakeRfm1.AMB_SHA, a.sha256)
        assertEquals(214_629L, a.size)
        assertEquals("https://github.com/Quixotic7/RemoteFM1FW/releases/download/2026-10-29/amb-1.3.rfm1", a.downloads[0].url)
        assertEquals("", a.name)                                        // (filled in by the merge)
        assertThrows(FeedSnapshot.ParseError::class.java) {
            FeedSnapshot.parse("{\"message\":\"Not Found\"}".toByteArray(), FirmwareFeed.RELEASES)
        }
    }

    @Test fun merge() {
        val site = FeedSnapshot.parse(FakeRfm1.catalogJSON, FirmwareFeed.REMOTEFM1)
        val rel = FeedSnapshot.parse(FakeRfm1.releaseJSON, FirmwareFeed.RELEASES)
        val m = FeedSnapshot.merge(listOf(site, rel))
        assertEquals(listOf("amb", "choralroot", "coralflower", "felucca", "fizz", "s1vave"), m.map { it.id })   // (by name)
        val s1 = m.first { it.id == "s1vave" }
        assertEquals(listOf(FirmwareFeed.REMOTEFM1.name, FirmwareFeed.RELEASES.name), s1.downloads.map { it.feed })
        assertEquals(1, s1.downloads.map { it.sha256 }.toSet().size)
        // a release-only file: the catalog's details, the file's version (the catalog's 0.27 is the FM-1 package's)
        val cr = m.first { it.id == "choralroot" }
        assertEquals("ChoralRoot", cr.name)
        assertEquals("0.26.1", cr.version)
        assertEquals("Chords", cr.category)
        assertEquals("https://github.com/Quixotic7/ChoralRootFM1", cr.sourceURL)   // (the repo, not 0.27's tag)
        assertFalse(cr.about.isEmpty())
        val amb = m.first { it.id == "amb" }
        assertEquals("AMB-1", amb.name)
        assertEquals("MPL-2.0", amb.license)
        assertTrue(amb.sourceURL.contains("fm1-amb"))
        // no site catalog: the name from the id
        assertEquals("Coralflower", FeedSnapshot.merge(listOf(rel)).first { it.id == "coralflower" }.name)
        // a newer version in a later feed wins, with only its own download
        val newer = rel.copy(feed = FirmwareFeed(FirmwareFeed.Kind.GITHUB, "https://example.com/feed", "an author's feed"),
            firmware = listOf(entry("amb", "1.4", "ab", 9, listOf("https://example.com/amb-1.4.rfm1"))))
        val amb2 = FeedSnapshot.merge(listOf(site, rel, newer)).first { it.id == "amb" }
        assertEquals("1.4", amb2.version)
        assertEquals(1, amb2.downloads.size)
        assertEquals("AMB", amb2.name)                                  // (the entry's own name; empty fields from the others)
        assertEquals("MPL-2.0", amb2.license)
        // the merge leaves its input alone (the cached snapshots)
        assertEquals("", rel.firmware.first { it.id == "amb" }.name)
    }

    @Test fun installState() {
        val e = entry("felucca", "1.6.2", "new", 1, listOf("https://x/f.rfm1"))
        assertEquals(InstallState.NotInstalled, e.state(emptyList()))
        assertEquals(InstallState.Update("1.6.1"), e.state(listOf(file("felucca", "1.6.1"))))
        assertEquals(InstallState.Installed("1.6.2"), e.state(listOf(file("felucca", "1.6.1"), file("felucca", "1.6.2"))))
        assertEquals(InstallState.Installed("1.7"), e.state(listOf(file("felucca", "1.7"))))            // (a newer one by hand)
        assertEquals(InstallState.Installed("0.1"), e.state(listOf(file("other", "0.1", sha = "new")))) // (the same file)
        assertEquals(InstallState.NotInstalled, e.state(listOf(file("felucca", "1.6.1").copy(error = "bad"))))
        assertEquals(InstallState.NotInstalled, e.state(listOf(file("amb", "1.3"))))
        // a built-in core of the same id and version (or newer) hides it; an older built-in one doesn't
        assertTrue(e.builtIn(listOf(CoreInfo("felucca", version = "1.6.2"))))
        assertFalse(e.builtIn(listOf(CoreInfo("felucca", version = "1.6.1"))))
    }

    /** the cache's form (iOS's Codable field names) read back; an older cache without the newer fields still reads */
    @Test fun snapshotRoundTrip() {
        val site = FeedSnapshot.parse(FakeRfm1.catalogJSON, FirmwareFeed.REMOTEFM1, Instant.parse("2026-10-29T10:00:00Z"))
        val back = FeedSnapshot.fromMap(MiniJson.parse(MiniJson.write(site.toMap())).obj())
        assertEquals(site, back)
        val old = """{"feed":{"kind":"github","url":"${FirmwareFeed.RELEASES.url}","name":"x"},"fetched":"2026-10-29T10:00:00Z",
            "firmware":[{"id":"amb","version":"1.3","downloads":[{"url":"https://a/amb-1.3.rfm1","feed":"x","sha256":"","size":5,"fileName":"amb-1.3.rfm1"}]}],
            "details":{}}"""
        val s = FeedSnapshot.fromMap(MiniJson.parse(old).obj())!!
        assertEquals("", s.firmware[0].editor)
        assertEquals(5L, s.firmware[0].size)
    }
}
