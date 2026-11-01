// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Tests/RFMKitTests/FirmwareStoreTests.swift (the store: the check, the
// cache shown offline, at most once a day; installs through a fake network: checked and imported (amb-1.3.rfm1), a
// checksum mismatch, the wrong size, another feed's copy, a file the loader refuses, a plain-http address, an update
// keeping the previous file, Cancel). The loader is FakeRfm1 here (the native one: FirmwareTest on the emulator).
package com.quixotic7.remotefm1.firmware

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** the network, from memory */
class FakeTransport : FirmwareTransport {
    val pages = HashMap<String, ByteArray>()
    val files = HashMap<String, ByteArray>()
    @Volatile var offline = false
    @Volatile var hang = false
    @Volatile var started = 0

    override fun data(url: String): ByteArray {
        if (offline) throw UnknownHostException(url)
        return synchronized(this) { pages[url] } ?: throw FirmwareStoreError.Server(404)
    }

    override fun download(url: String, to: File, limit: Long, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        started++
        if (hang) {
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 30_000) {
                if (cancelled()) throw FirmwareStoreError.Cancelled
                Thread.sleep(5)
            }
        }
        if (offline) throw UnknownHostException(url)
        val d = synchronized(this) { files[url] } ?: throw FirmwareStoreError.Server(404)
        if (d.size > limit) throw FirmwareStoreError.TooLarge
        progress(d.size / 2L, d.size.toLong())
        to.writeBytes(d)
        progress(d.size.toLong(), d.size.toLong())
    }
}

class FirmwareStoreTest {
    private lateinit var root: File
    private var clock = Instant.parse("2026-10-29T12:00:00Z")

    @Before fun setUp() {
        root = Files.createTempDirectory("rfm1-store").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private val amb: ByteArray get() = FakeRfm1.amb.readBytes()

    private fun library() = FirmwareLibrary(File(root, "Firmware"), FakeRfm1)

    private fun transport() = FakeTransport().apply {
        pages[FirmwareFeed.REMOTEFM1.url] = FakeRfm1.catalogJSON
        pages[FirmwareFeed.RELEASES.url] = FakeRfm1.releaseJSON
    }

    private fun store(lib: FirmwareLibrary, t: FakeTransport) =
        FirmwareStore(lib, transport = t, cacheFile = File(root, "cache/feeds.json"), tmpDir = File(root, "tmp"), now = { clock })

    private fun entry(id: String, version: String, sha: String, size: Long, urls: List<String>) =
        AvailableFirmware(id, name = id.uppercase(), version = version,
            downloads = urls.map { AvailableFirmware.Download(it, "test", sha, size, "$id-$version.rfm1") })

    @Test fun checkCachesAndShowsTheCacheOffline() {
        val lib = library()
        lib.sync()
        val t = transport()
        val s = store(lib, t)
        assertTrue(s.available.value.isEmpty())
        assertNull(s.checked)
        s.refresh()
        assertEquals(6, s.available.value.size)
        assertEquals("", s.problem.value)
        val whenChecked = s.checked!!
        assertTrue(s.cacheFile.isFile)
        assertEquals(5, s.list(listOf(CoreInfo("fizz", version = "0.12.0-beta.12"))).size)
        assertEquals(2, s.report.value.size)

        // offline: a new store shows the cached list and its date; a check keeps it and says why
        t.offline = true
        clock = clock.plusSeconds(120)
        val s2 = store(lib, t)
        assertEquals(s.available.value, s2.available.value)
        assertEquals(whenChecked, s2.checked)
        s2.refresh()
        assertEquals(6, s2.available.value.size)
        assertTrue(s2.problem.value, s2.problem.value.contains("offline"))
        assertEquals(whenChecked, s2.checked)

        // one feed down: the other's new answer, the cached list of the one that failed
        t.offline = false
        t.pages.remove(FirmwareFeed.RELEASES.url)
        s2.refresh()
        assertEquals(6, s2.available.value.size)
        assertTrue(s2.problem.value, s2.problem.value.contains("404"))
        assertTrue(s2.problem.value, s2.problem.value.contains("not found"))

        // at most once a day: a list from now isn't checked again
        t.pages.clear()
        val s3 = store(lib, t)
        s3.refreshIfOlder(FirmwareStore.DAY_MS)
        assertEquals("", s3.problem.value)
        // a day later it is
        clock = clock.plusSeconds(86_401)
        s3.refreshIfOlder(FirmwareStore.DAY_MS)
        assertTrue(s3.problem.value.contains("404"))
        // and not again within a minute, whatever its age
        t.pages[FirmwareFeed.REMOTEFM1.url] = FakeRfm1.catalogJSON
        t.pages[FirmwareFeed.RELEASES.url] = FakeRfm1.releaseJSON
        s3.refreshIfOlder(0)
        assertTrue(s3.problem.value.contains("404"))
    }

    @Test fun unreadableAnswerIsReported() {
        val t = transport()
        t.pages[FirmwareFeed.RELEASES.url] = "<html>rate limited</html>".toByteArray()
        val s = store(library(), t)
        s.refresh()
        assertEquals("The list from ${FirmwareFeed.RELEASES.name} couldn't be read.", s.problem.value)
        assertTrue(s.report.value[1], s.report.value[1].contains("not a list: \"<html>rate limited</html>\""))
        assertEquals(listOf("fizz", "s1vave"), s.available.value.map { it.id })
    }

    @Test fun installChecksAndImports() {
        val lib = library()
        lib.sync()
        val t = transport()
        val s = store(lib, t)
        s.refresh()
        val e = s.available.value.first { it.id == "amb" }
        t.files[e.downloads[0].url] = amb
        var changed = 0
        s.didChange = { changed++ }
        assertEquals(InstallState.NotInstalled, s.state(e))
        val f = s.install(e)
        assertEquals(FakeRfm1.AMB_SHA, f?.sha256)
        assertEquals("", s.failure.value)
        assertEquals("Installed AMB-1 1.3.", s.message.value)
        assertNull(s.progress.value)
        assertEquals(InstallState.Installed("1.3"), s.state(e))
        assertTrue(changed > 0)
        // as an import by hand: under the feed's name, stored by SHA-256, in the index
        assertEquals(listOf("amb-1.3.rfm1"), lib.files.value.map { it.file })
        assertTrue(lib.path(FakeRfm1.AMB_SHA).isFile)
        assertEquals(listOf("amb"), FirmwareLibrary(lib.dir, FakeRfm1).also { it.sync() }.cores().map { it.coreID })
        // the download's folder is gone
        assertTrue(File(root, "tmp").listFiles().orEmpty().isEmpty())
    }

    @Test fun checksumMismatchIsRefused() {
        val lib = library()
        lib.sync()
        val t = transport()
        val s = store(lib, t)
        val bytes = amb
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val e = entry("amb", "1.3", FakeRfm1.AMB_SHA, bytes.size.toLong(), listOf("https://a.test/amb-1.3.rfm1"))
        t.files[e.downloads[0].url] = bytes
        assertNull(s.install(e))
        assertEquals(FirmwareStoreError.Checksum.text, s.failure.value)
        assertTrue(lib.files.value.isEmpty())
        assertTrue(lib.dir.listFiles().orEmpty().none { it.name.endsWith(".rfm1") })

        // the wrong size: refused before the checksum
        val short = entry("amb", "1.3", FakeRfm1.AMB_SHA, bytes.size + 5L, listOf("https://a.test/amb-1.3.rfm1"))
        s.install(short)
        assertEquals(FirmwareStoreError.Size(bytes.size + 5L, bytes.size.toLong()).text, s.failure.value)

        // another feed's copy is tried next
        val two = entry("amb", "1.3", FakeRfm1.AMB_SHA, bytes.size.toLong(),
            listOf("https://a.test/amb-1.3.rfm1", "https://b.test/amb-1.3.rfm1"))
        t.files[two.downloads[1].url] = amb
        val g = s.install(two)
        assertEquals(FakeRfm1.AMB_SHA, g?.sha256)
        assertEquals("", s.failure.value)
    }

    @Test fun aFileTheLoaderRefusesIsRemoved() {
        val lib = library()
        lib.sync()
        val t = transport()
        val s = store(lib, t)
        val junk = "not an rfm1 at all".toByteArray()
        val e = entry("junk", "1.0", FakeRfm1.sha256(junk), junk.size.toLong(), listOf("https://a.test/junk-1.0.rfm1"))
        t.files[e.downloads[0].url] = junk
        assertNull(s.install(e))
        assertTrue(s.failure.value, s.failure.value.startsWith("The file isn't a valid .rfm1 firmware ("))
        assertTrue(s.failure.value, s.failure.value.contains("not a WebAssembly module"))
        assertTrue(lib.files.value.isEmpty())
        assertTrue(lib.dir.listFiles().orEmpty().none { it.name.endsWith(".rfm1") })
    }

    @Test fun notHTTPSIsRefused() {
        val t = transport()
        val s = store(library(), t)
        val e = entry("amb", "1.3", FakeRfm1.AMB_SHA, 214_629, listOf("http://a.test/amb-1.3.rfm1"))
        t.files[e.downloads[0].url] = amb
        s.install(e)
        assertEquals(FirmwareStoreError.NotHTTPS.text, s.failure.value)
        assertEquals(0, t.started)
    }

    /** an update: the new file in, the older ones of the same id out but the newest of them (willReplace told first) */
    @Test fun updateKeepsThePreviousFile() {
        // two other builds of AMB-1 1.3: the fixture with a custom section added (a different SHA-256, still valid)
        val other = amb + byteArrayOf(0x00, 0x06, 0x01, 'x'.code.toByte()) + "test".toByteArray()
        val older = amb + byteArrayOf(0x00, 0x06, 0x01, 'y'.code.toByte()) + "tset".toByteArray()
        val lib = library()
        lib.import(other.inputStream(), "amb-local.rfm1")
        lib.import(older.inputStream(), "amb-local-2.rfm1")
        assertEquals(2, lib.files.value.size)
        val before = lib.files.value.map { it.sha256 }.toSet()

        val t = transport()
        val s = store(lib, t)
        s.refresh()
        val e = s.available.value.first { it.id == "amb" }.let { it.copy(downloads = listOf(it.downloads[0])) }
        t.files[e.downloads[0].url] = amb
        var replaced: Pair<List<String>, String>? = null
        s.willReplace = { o, n -> replaced = o.map { it.sha256 } to n.sha256 }
        s.install(e)
        assertEquals("", s.failure.value)
        assertEquals("Updated AMB-1 to 1.3. 1.3 is kept: delete it when you're happy.", s.message.value)
        assertEquals(before, replaced?.first?.toSet())
        assertEquals(FakeRfm1.AMB_SHA, replaced?.second)
        // the new one and one previous build kept (a way back), the other removed
        assertEquals(2, lib.files.value.size)
        assertTrue(lib.files.value.any { it.sha256 == FakeRfm1.AMB_SHA })
        val old = lib.files.value.first { it.sha256 != FakeRfm1.AMB_SHA }
        // a saved choice of the kept file still finds that file; one of the removed file finds a build of its id
        val r = CoreRef("wasm", "amb", "1.3", old.sha256).resolve(lib.cores())
        assertEquals(FirmwareLibrary.KEY_PREFIX + old.sha256, r?.key)
        val gone = (before - lib.files.value.map { it.sha256 }.toSet()).first()
        assertTrue(!lib.path(gone).exists())
        assertEquals(true, CoreRef("wasm", "amb", "1.3", gone).resolve(lib.cores())?.keepFlash)
    }

    @Test fun cancel() {
        val lib = library()
        lib.sync()
        val t = transport()
        t.hang = true
        val s = store(lib, t)
        val e = entry("amb", "1.3", FakeRfm1.AMB_SHA, 214_629, listOf("https://a.test/amb-1.3.rfm1"))
        t.files[e.downloads[0].url] = amb
        val done = CountDownLatch(1)
        var got: ImportedFirmware? = ImportedFirmware("x", "x", "x", "x", "x", 0)
        s.installAsync(e) { got = it; done.countDown() }
        for (i in 0 until 400) if (t.started == 0) Thread.sleep(5)
        assertEquals("amb", s.progress.value?.id)
        assertNull(s.install(e))                                    // (one at a time)
        s.cancel()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(got)
        assertEquals("Cancelled.", s.message.value)
        assertEquals("", s.failure.value)
        assertNull(s.progress.value)
        assertTrue(lib.files.value.isEmpty())
    }

    @Test fun updatesAndDetails() {
        val lib = library()
        lib.import(FakeRfm1.amb)
        val t = transport()
        val s = store(lib, t)
        s.refresh()
        assertEquals(0, s.updates(emptyList()).size)                // (the same file: installed)
        // a newer AMB in a feed: one update (the badge)
        val newer = FirmwareStore(lib, listOf(FirmwareFeed.REMOTEFM1, FirmwareFeed.RELEASES,
            FirmwareFeed(FirmwareFeed.Kind.GITHUB, "https://example.com/feed", "an author's feed")), t,
            File(root, "cache2/feeds.json"), File(root, "tmp"), { clock })
        t.pages["https://example.com/feed"] = """{"assets":[{"name":"amb-1.4.rfm1","size":9,"browser_download_url":"https://example.com/amb-1.4.rfm1"}]}""".toByteArray()
        newer.refresh()
        assertEquals(listOf("amb"), newer.updates(emptyList()).map { it.id })
        assertEquals(InstallState.Update("1.3"), newer.state(newer.available.value.first { it.id == "amb" }))
        // details: every firmware of the site catalog, downloadable or not
        assertNotNull(s.details["felucca"])
        assertEquals("AMB-1", s.details["amb"]?.name)
    }
}
