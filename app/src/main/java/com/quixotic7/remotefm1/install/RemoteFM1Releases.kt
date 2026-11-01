// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Install/RemoteFM1Releases.swift; changes: Kotlin, a
// StateFlow instead of @Observable, HttpURLConnection instead of URLSession, MiniJson instead of Codable.
// The RemoteFM1 firmware releases the website's installer offers (https://remotefm1.com/installer/releases.json,
// newest first): version, identity (FM-1_52XXYYZZ once installed), licence, notes, the default package
// (firmware/remote-<version>.fwsc) and for most a USB-only one. The last list fetched and read is kept in the cache
// folder, so the page works offline with what it saw.
package com.quixotic7.remotefm1.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** a RemoteFM1 version: major.minor.patch */
data class RemoteFM1Version(val major: Int, val minor: Int, val patch: Int) : Comparable<RemoteFM1Version> {
    /** the identity a release with this version installs as */
    val identity: String get() = IDENTITY_PREFIX + "%02d%02d%02d".format(major, minor, patch)

    override fun toString() = "$major.$minor.$patch"

    override fun compareTo(other: RemoteFM1Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        const val IDENTITY_PREFIX = "FM-1_52"

        /** the oldest RemoteFM1 this app's link speaks */
        val appMinimum = RemoteFM1Version(0, 2, 2)

        /** "0.2.3", "v0.2.3", "0.2.3-dev" (the leading numbers); null when there are none */
        fun parse(string: String): RemoteFM1Version? {
            var s = string.trim()
            if (s.startsWith("v") || s.startsWith("V")) s = s.drop(1)
            val head = s.takeWhile { it.isDigit() || it == '.' }
            val parts = head.split(".").map { it.toIntOrNull() }
            if (parts.size !in 2..3 || parts.any { it == null }) return null
            return RemoteFM1Version(parts[0]!!, parts[1]!!, if (parts.size > 2) parts[2]!! else 0)
        }

        /** FM-1_52XXYYZZ is XX.YY.ZZ; null for anything else (a dev build's FM-1_5200, 0.1.x's FM-1_015) */
        fun ofIdentity(identity: String): RemoteFM1Version? {
            if (!identity.startsWith(IDENTITY_PREFIX)) return null
            val digits = identity.drop(IDENTITY_PREFIX.length)
            if (digits.length != 6 || !digits.all { it in '0'..'9' }) return null
            val n = digits.toInt()
            return RemoteFM1Version(n / 10000, (n / 100) % 100, n % 100)
        }
    }
}

/** one package to download: a release's default or USB-only file, its SHA-256 and the identity it must carry */
data class FirmwarePackageRef(val version: String, val kind: Kind, val file: String, val sha256: String, val identity: String) {
    enum class Kind { standard, usbOnly }

    val title: String get() = "RemoteFM1 $version" + if (kind == Kind.usbOnly) " (USB only)" else ""

    companion object {
        fun of(version: String, kind: Kind, file: String, sha256: String, identity: String) =
            FirmwarePackageRef(version, kind, file, sha256.lowercase(), identity)
    }
}

/** one release in releases.json (its field names) */
data class RemoteFM1Release(
    val version: String,
    val date: String,
    val tag: String,
    val identity: String,
    val license: String,
    val sha256: String,
    val usbOnly: Variant?,
    val notes: List<String>,
) {
    data class Variant(val file: String, val sha256: String)

    val parsedVersion: RemoteFM1Version? get() = RemoteFM1Version.parse(version)

    fun pkg(kind: FirmwarePackageRef.Kind): FirmwarePackageRef? = when (kind) {
        FirmwarePackageRef.Kind.standard -> FirmwarePackageRef.of(version, kind, "remote-$version.fwsc", sha256, identity)
        FirmwarePackageRef.Kind.usbOnly -> usbOnly?.let { FirmwarePackageRef.of(version, kind, it.file, it.sha256, identity) }
    }

    /** a RemoteFM1 identity FM1Kit can check afterwards (0.1.x answers as V15, FM-1_015) */
    val installable: Boolean get() = RemoteFM1Version.ofIdentity(identity) != null && parsedVersion != null

    /** "28 Oct 2026" (releases.json: 2026-10-28) */
    val displayDate: String
        get() = runCatching {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(date)!!
            SimpleDateFormat("d MMM yyyy", Locale.UK).format(d)
        }.getOrDefault(date)
}

/** releases.json */
data class RemoteFM1ReleaseList(val releases: List<RemoteFM1Release>) {
    companion object {
        /** @throws InstallerFetchError.BadList, ReleaseDecodeError */
        fun parse(data: ByteArray): RemoteFM1ReleaseList {
            val root = try {
                MiniJson.parse(String(data, Charsets.UTF_8))
            } catch (e: JsonError) {
                throw ReleaseDecodeError(e.message ?: "")
            }
            val list = ((root as? Map<*, *>)?.get("releases") as? List<*>) ?: throw ReleaseDecodeError("no releases array")
            val out = list.map { r ->
                val o = r as? Map<*, *> ?: throw ReleaseDecodeError("a release isn't an object")
                fun s(k: String) = o[k] as? String ?: throw ReleaseDecodeError("a release has no $k")
                val u = o["usbOnly"]?.let { v ->
                    val m = v as? Map<*, *> ?: throw ReleaseDecodeError("usbOnly isn't an object")
                    RemoteFM1Release.Variant(m["file"] as? String ?: throw ReleaseDecodeError("usbOnly has no file"),
                                             m["sha256"] as? String ?: throw ReleaseDecodeError("usbOnly has no sha256"))
                }
                val notes = (o["notes"] as? List<*>)?.map { it as? String ?: throw ReleaseDecodeError("a note isn't text") }
                    ?: throw ReleaseDecodeError("a release has no notes")
                RemoteFM1Release(s("version"), s("date"), s("tag"), s("identity"), s("license"), s("sha256"), u, notes)
            }
            if (out.isEmpty()) throw InstallerFetchError.BadList("it lists no releases")
            for (r in out) {
                if (r.sha256.length != 64 || !r.sha256.all { Character.digit(it, 16) >= 0 }) {
                    throw InstallerFetchError.BadList("${r.version}'s SHA-256 isn't one")
                }
            }
            return RemoteFM1ReleaseList(out)
        }
    }
}

/** releases.json doesn't read as the list (iOS: a DecodingError) */
class ReleaseDecodeError(why: String) : Exception(why)

sealed class InstallerFetchError : Exception() {
    data class Http(val code: Int, val what: String) : InstallerFetchError()
    data class BadList(val why: String) : InstallerFetchError()

    val description: String
        get() = when (this) {
            is Http -> "The website answered $code for $what."
            is BadList -> "The list of releases can't be read: $why."
        }

    override val message: String get() = description
}

/** where the installer's files come from (the website; tests give their own) */
fun interface InstallerFetching {
    suspend fun data(url: String): ByteArray
}

/** HTTPS: anything but 200 is an error (needs the INTERNET permission) */
class HttpFetcher : InstallerFetching {
    override suspend fun data(url: String): ByteArray = withContext(Dispatchers.IO) {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 30_000
            c.readTimeout = 30_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", "RemoteFM1-Android")
            val code = c.responseCode
            if (code != 200) throw InstallerFetchError.Http(code, url.substringAfterLast('/'))
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

/** The releases, fetched from the website (or the last good copy): newest first, as releases.json has them. */
class RemoteFM1Releases(val cacheDirectory: File, private val fetcher: InstallerFetching = HttpFetcher()) {
    data class State(
        val releases: List<RemoteFM1Release> = emptyList(),
        /** the list's time, ms (the cache file's, when it came from there) */
        val fetchedAt: Long? = null,
        val fromCache: Boolean = false,
        /** the last fetch's, in words (null: it worked) */
        val error: String? = null,
        val fetching: Boolean = false,
    ) {
        /** the newest release this app can install */
        val newest: RemoteFM1Release? get() = releases.firstOrNull { it.installable }

        fun release(identity: String): RemoteFM1Release? = releases.firstOrNull { it.identity == identity }

        /** a newer release than [version] (the unit's); null: none, or the version is unknown */
        fun update(over: RemoteFM1Version?): RemoteFM1Release? {
            val n = newest ?: return null
            val nv = n.parsedVersion ?: return null
            return if (over != null && nv > over) n else null
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val busy = AtomicBoolean(false)
    @Volatile private var lastAttempt: Long? = null

    init {
        loadCache()
    }

    val releases: List<RemoteFM1Release> get() = _state.value.releases
    val newest: RemoteFM1Release? get() = _state.value.newest
    fun release(identity: String) = _state.value.release(identity)
    fun update(over: RemoteFM1Version?) = _state.value.update(over)

    private val cacheFile get() = File(cacheDirectory, "releases.json")

    /** fetches the list; kept as the last good copy on success; on failure the cached copy stays (error says why) */
    suspend fun refresh() {
        if (!busy.compareAndSet(false, true)) return
        _state.update { it.copy(fetching = true) }
        lastAttempt = System.currentTimeMillis()
        try {
            val data = fetcher.data(LIST_URL)
            val list = RemoteFM1ReleaseList.parse(data)
            _state.update { it.copy(releases = list.releases, fetchedAt = System.currentTimeMillis(), fromCache = false, error = null) }
            withContext(Dispatchers.IO) {
                runCatching {
                    cacheDirectory.mkdirs()
                    val tmp = File(cacheDirectory, "releases.json.tmp")
                    tmp.writeBytes(data)
                    if (!tmp.renameTo(cacheFile)) { cacheFile.delete(); tmp.renameTo(cacheFile) }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.update { it.copy(error = words(e)) }
            if (_state.value.releases.isEmpty()) loadCache()
        } finally {
            _state.update { it.copy(fetching = false) }
            busy.set(false)
        }
    }

    /** refresh() unless one was tried in the last [ageMs] */
    suspend fun refreshIfStale(ageMs: Long = 600_000) {
        val t = lastAttempt
        if (t != null && System.currentTimeMillis() - t < ageMs) return
        refresh()
    }

    /** the last good copy, if any */
    fun loadCache() {
        val data = runCatching { cacheFile.readBytes() }.getOrNull() ?: return
        val list = runCatching { RemoteFM1ReleaseList.parse(data) }.getOrNull() ?: return
        _state.update { it.copy(releases = list.releases, fetchedAt = cacheFile.lastModified(), fromCache = true) }
    }

    companion object {
        const val SITE = "https://remotefm1.com/installer/"
        const val LIST_URL = SITE + "releases.json"
        fun packageURL(file: String) = SITE + "firmware/" + file

        fun defaultCache(cacheDir: File) = File(cacheDir, "RemoteFM1Installer")

        /** an error in words (iOS's words for URLError and DecodingError) */
        fun words(e: Throwable): String = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> "No internet connection."
            is SocketTimeoutException -> "The website didn't answer in time."
            is ReleaseDecodeError -> "The list of releases can't be read."
            is InstallerFetchError -> e.description
            is IOException -> e.localizedMessage ?: e.toString()
            else -> e.localizedMessage ?: e.toString()
        }
    }
}
