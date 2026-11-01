// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/FirmwareStore.swift (and FirmwareThumbs.swift's
// FirmwareStore.details).
//
// Get firmware: FirmwareStore reads the feeds (FirmwareFeeds.kt), keeps the last good list of each in the app's cache
// (cache/Firmware catalog/feeds.json: shown offline, with its date), and downloads and installs one firmware at a time.
//
//   a check      every feed fetched at once (20 s timeout); a feed that fails keeps its cached list, and the page
//                says so. The app checks at launch when the list is a day old, and when the page opens (unless it
//                checked in the last 10 minutes)
//   an install   the download (https only) to a temporary file, with progress and Cancel; its size and SHA-256
//                checked against the feed's (a mismatch refuses it, then the next feed's copy is tried); then
//                FirmwareLibrary.import, the same path as a file added by hand (the .rfm1 loader checks it). A file the
//                loader refuses is not kept
//   an update    the same, then of the other files of the same firmware id and an older or equal version the newest
//                is kept (a way back if the new one doesn't read the memory) and the rest removed (willReplace first:
//                the app moves its selection to the new file). Saved choices of a removed file fall back to the same
//                id (CoreRef.resolve)
//
// Nothing here selects or runs a firmware. Threads: refresh and install block (call them off the main thread, or use
// the *Async forms, which run on the store's own threads); the state is StateFlows; callbacks come on the calling
// (worker) thread.
package com.quixotic7.remotefm1.firmware

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

/** what went wrong, in plain words ([message]) */
sealed class FirmwareStoreError(val text: String) : Exception(text) {
    object Offline : FirmwareStoreError("Can't reach the server. You may be offline: try again when you're connected.")
    class Server(val code: Int) : FirmwareStoreError("The server answered $code (${reason(code)}). Try again later.")
    class Unreadable(feed: String) : FirmwareStoreError("The list from $feed couldn't be read.")
    object NotHTTPS : FirmwareStoreError("The list gave an address that isn't secure (https), so nothing was downloaded.")
    object TooLarge : FirmwareStoreError("The download was bigger than the list says, so it wasn't installed.")
    class Size(val expected: Long, val got: Long) :
        FirmwareStoreError("The download was the wrong size ($got bytes, not $expected), so it wasn't installed.")
    object Checksum : FirmwareStoreError("The download didn't match its checksum, so it wasn't installed. Try again later.")
    class Invalid(val why: String) : FirmwareStoreError("The file isn't a valid .rfm1 firmware ($why), so it wasn't installed.")
    class Other(s: String) : FirmwareStoreError(s)
    object Cancelled : FirmwareStoreError("Cancelled.")

    override fun equals(other: Any?) = other is FirmwareStoreError && other.javaClass == javaClass && other.text == text
    override fun hashCode() = text.hashCode()

    companion object {
        /** HTTPURLResponse.localizedString's words for the common codes */
        fun reason(c: Int): String = when (c) {
            400 -> "bad request"; 401 -> "unauthorized"; 403 -> "forbidden"; 404 -> "not found"; 408 -> "request timed out"
            410 -> "no longer exists"; 429 -> "too many requests"; 500 -> "internal server error"; 502 -> "bad gateway"
            503 -> "service unavailable"; 504 -> "gateway timed out"
            else -> if (c in 500..599) "server error" else if (c in 400..499) "client error" else "unknown"
        }

        fun from(e: Throwable): FirmwareStoreError = when (e) {
            is FirmwareStoreError -> e
            is UnknownHostException, is java.net.ConnectException, is SocketTimeoutException, is java.net.NoRouteToHostException,
            is SSLException, is java.net.SocketException -> Offline
            else -> Other(e.message ?: e.javaClass.simpleName)
        }
    }
}

/** how the store reaches the network (tests use their own) */
interface FirmwareTransport {
    /** the body of a GET, or throws (FirmwareStoreError.Server for a non-2xx answer) */
    fun data(url: String): ByteArray

    /** the file at url written to [to]; at most [limit] bytes; progress(received, expected; 0 unknown); stops with
     *  FirmwareStoreError.Cancelled when [cancelled] says so */
    fun download(url: String, to: File, limit: Long, progress: (Long, Long) -> Unit, cancelled: () -> Boolean)
}

/** HttpURLConnection: no cache, a timeout, redirects followed (GitHub's downloads redirect) */
class HttpTransport(private val timeoutMs: Int = 20_000) : FirmwareTransport {
    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "RemoteFM1-Android")
        if (c.url.host == "api.github.com") c.setRequestProperty("Accept", "application/vnd.github+json")
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw FirmwareStoreError.Server(code)
        }
        return c
    }

    override fun data(url: String): ByteArray {
        val c = open(url)
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    override fun download(url: String, to: File, limit: Long, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val c = open(url)
        try {
            val expected = maxOf(0L, c.contentLengthLong)
            if (expected > limit) throw FirmwareStoreError.TooLarge
            c.inputStream.use { inp ->
                to.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var n = 0L
                    var last = 0L
                    while (true) {
                        if (cancelled()) throw FirmwareStoreError.Cancelled
                        val k = inp.read(buf)
                        if (k < 0) break
                        n += k
                        if (n > limit) throw FirmwareStoreError.TooLarge
                        out.write(buf, 0, k)
                        if (n - last >= 1 shl 16) {
                            last = n
                            progress(n, expected)
                        }
                    }
                    progress(n, expected)
                }
            }
        } finally {
            c.disconnect()
        }
    }
}

/** the list of available firmware and the one download */
class FirmwareStore(
    val library: FirmwareLibrary,
    val feeds: List<FirmwareFeed> = FirmwareFeed.DEFAULTS,
    private val transport: FirmwareTransport = HttpTransport(),
    /** the cached lists' file (the app: cacheDir/Firmware catalog/feeds.json) */
    val cacheFile: File,
    /** where downloads are written first (the app: cacheDir) */
    private val tmpDir: File = cacheFile.parentFile ?: File("."),
    private val now: () -> Instant = { Instant.now() },
    private val log: (String) -> Unit = {},
) {
    data class Progress(val id: String, val received: Long = 0, val expected: Long = 0) {
        val fraction: Double? get() = if (expected > 0) minOf(1.0, received.toDouble() / expected) else null
    }

    private val _snapshots = MutableStateFlow<List<FeedSnapshot>>(emptyList())
    private val _available = MutableStateFlow<List<AvailableFirmware>>(emptyList())
    private val _checking = MutableStateFlow(false)
    private val _problem = MutableStateFlow("")
    private val _report = MutableStateFlow<List<String>>(emptyList())
    private val _progress = MutableStateFlow<Progress?>(null)
    private val _message = MutableStateFlow("")
    private val _failure = MutableStateFlow("")

    /** each feed's last good list (feed order) */
    val snapshots: StateFlow<List<FeedSnapshot>> = _snapshots
    /** the merged list (every id, built-in ones too: [list] leaves those out) */
    val available: StateFlow<List<AvailableFirmware>> = _available
    val checking: StateFlow<Boolean> = _checking
    /** the last check's trouble ("" when every feed answered) */
    val problem: StateFlow<String> = _problem
    /** the last check, one line a feed: what came back (a byte count, firmware found, or how an unreadable answer starts) */
    val report: StateFlow<List<String>> = _report
    /** the download running */
    val progress: StateFlow<Progress?> = _progress
    /** the last install's result ("Installed AMB-1 1.3.", "Updated ...", "Cancelled."), and its error */
    val message: StateFlow<String> = _message
    val failure: StateFlow<String> = _failure

    /** before an update removes older files (old, new): the app moves its selection (a worker thread) */
    @Volatile var willReplace: ((List<ImportedFirmware>, ImportedFirmware) -> Unit)? = null
    /** after an install changed the firmware folder (a worker thread) */
    @Volatile var didChange: (() -> Unit)? = null

    private val lock = Any()
    @Volatile private var attempted: Instant? = null
    private val cancelFlag = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "rfm-firmware").apply { isDaemon = true } }

    init {
        loadCache()
    }

    /** when the list was read: the oldest feed's last good answer (null: never) */
    val checked: Instant? get() = _snapshots.value.minOfOrNull { it.fetched }

    /** the list a page shows: without firmware this build has built in (of that version or newer) */
    fun list(builtIn: List<CoreInfo>): List<AvailableFirmware> = _available.value.filter { !it.builtIn(builtIn) }

    fun state(e: AvailableFirmware): InstallState = e.state(library.files.value)

    /** the entries with a newer version than the one here (the badge counts them) */
    fun updates(builtIn: List<CoreInfo>): List<AvailableFirmware> = list(builtIn).filter { state(it) is InstallState.Update }

    /** every firmware's details by id (the Firmware card): the site catalogs' (not only the downloadable ones), then
     *  the merged list's */
    val details: Map<String, AvailableFirmware>
        get() {
            val out = LinkedHashMap<String, AvailableFirmware>()
            for (e in _available.value) out[e.id] = e
            for (s in _snapshots.value) for ((k, v) in s.details) if (k !in out) out[k] = v
            return out
        }

    // ---- the check

    /** every feed read again (a feed that fails keeps its cached list); blocks */
    fun refresh() {
        synchronized(lock) {
            if (_checking.value) return
            _checking.value = true
        }
        attempted = now()
        try {
            val tasks = feeds.map { f -> workers.submit(Callable { runCatching { transport.data(f.url) } }) }
            val snaps = ArrayList<FeedSnapshot>()
            val problems = ArrayList<String>()
            val lines = ArrayList<String>()
            val t = now()
            for ((i, task) in tasks.withIndex()) {
                val f = feeds[i]
                val r = runCatching { task.get() }.getOrElse { Result.failure(it) }
                val d = r.getOrNull()
                if (d != null) {
                    val s = runCatching { FeedSnapshot.parse(d, f, t) }.getOrNull()
                    if (s != null) {
                        snaps.add(s)
                        lines.add("${f.name}: ${d.size} bytes, ${s.firmware.size} to download")
                        continue
                    }
                    val head = String(d.copyOf(minOf(80, d.size)), Charsets.UTF_8).replace("\n", " ").trim()
                    lines.add("${f.name}: ${d.size} bytes, not a list: \"$head\"")
                    log("${f.url}: unreadable, ${d.size} bytes: $head")
                    problems.add(FirmwareStoreError.Unreadable(f.name).text)
                } else {
                    val e = FirmwareStoreError.from(r.exceptionOrNull() ?: IOException("no answer"))
                    log("${f.url}: ${e.text}")
                    lines.add("${f.name}: ${e.text}")
                    problems.add(if (e == FirmwareStoreError.Offline) e.text else "${f.name}: ${e.text}")
                }
                _snapshots.value.firstOrNull { it.feed == f }?.let { snaps.add(it) }
            }
            _snapshots.value = snaps
            _available.value = FeedSnapshot.merge(snaps)
            _report.value = lines
            _problem.value = problems.toSet().sorted().joinToString(" ")
            saveCache()
            log("checked ${feeds.size} feed(s): ${_available.value.size} firmware, ${problems.size} problem(s)")
        } finally {
            _checking.value = false
        }
    }

    /** a check when the list is older than [maxAgeMs] (or there is none), and no check was tried in the last
     *  [minLapseMs]; blocks. At launch: a day; the Get firmware page opening: 10 minutes */
    fun refreshIfOlder(maxAgeMs: Long, minLapseMs: Long = 60_000) {
        val t = now()
        attempted?.let { if (t.toEpochMilli() - it.toEpochMilli() < minLapseMs) return }
        val c = checked
        if (_snapshots.value.size == feeds.size && c != null && t.toEpochMilli() - c.toEpochMilli() < maxAgeMs) return
        refresh()
    }

    fun refreshAsync() { workers.execute { refresh() } }
    fun refreshIfOlderAsync(maxAgeMs: Long, minLapseMs: Long = 60_000) { workers.execute { refreshIfOlder(maxAgeMs, minLapseMs) } }

    private fun loadCache() {
        val d = runCatching { cacheFile.readText() }.getOrNull() ?: return
        val all = runCatching { MiniJson.parse(d).arr()?.mapNotNull { FeedSnapshot.fromMap(it.obj()) } }.getOrNull() ?: return
        _snapshots.value = feeds.mapNotNull { f -> all.firstOrNull { it.feed == f } }
        _available.value = FeedSnapshot.merge(_snapshots.value)
    }

    private fun saveCache() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(MiniJson.write(_snapshots.value.map { it.toMap() }))
            if (!tmp.renameTo(cacheFile)) tmp.delete()
        }.onFailure { log("cache: ${it.message}") }
    }

    // ---- install

    /**
     * [e] downloaded, checked and added (an update: older files of its id removed); blocks. Returns when it is done,
     * failed or was cancelled ([message] / [failure] say which). One at a time: null when another one runs.
     */
    fun install(e: AvailableFirmware): ImportedFirmware? {
        synchronized(lock) {
            if (_progress.value != null) return null
            _message.value = ""
            _failure.value = ""
            cancelFlag.set(false)
            _progress.value = Progress(e.id, 0, e.size)
        }
        try {
            return run(e)
        } finally {
            _progress.value = null
        }
    }

    /** [install] on the store's thread; [done] gets its result there */
    fun installAsync(e: AvailableFirmware, done: (ImportedFirmware?) -> Unit = {}) {
        workers.execute { done(install(e)) }
    }

    /** the running download stopped (nothing is added) */
    fun cancel() = cancelFlag.set(true)

    private fun run(e: AvailableFirmware): ImportedFirmware? {
        val dir = File(tmpDir, "rfm1-download-${UUID.randomUUID().toString().take(8)}")
        try {
            var last: FirmwareStoreError = FirmwareStoreError.Other("Nothing to download.")
            for (d in e.downloads) {
                if (!d.url.lowercase().startsWith("https://")) {
                    last = FirmwareStoreError.NotHTTPS
                    continue
                }
                try {
                    dir.mkdirs()
                    val file = File(dir, safeName(d.fileName, e))
                    file.delete()
                    log("download ${d.url}")
                    _progress.value = Progress(e.id, 0, d.size)
                    transport.download(d.url, file, if (d.size > 0) d.size else FirmwareLibrary.MAX_SIZE, { n, x ->
                        _progress.value?.let { p ->
                            if (p.id == e.id) _progress.value = p.copy(received = n, expected = if (d.size == 0L) x else p.expected)
                        }
                    }, { cancelFlag.get() })
                    if (cancelFlag.get()) throw FirmwareStoreError.Cancelled
                    verify(file, d)
                    return add(file, e)
                } catch (x: Throwable) {
                    if (cancelFlag.get() || x == FirmwareStoreError.Cancelled) {
                        _message.value = FirmwareStoreError.Cancelled.text
                        log("download of ${e.id} cancelled")
                        return null
                    }
                    last = FirmwareStoreError.from(x)
                    log("${d.url}: ${last.text}")
                    if (last is FirmwareStoreError.Invalid) break           // (another feed's copy is the same file)
                }
            }
            _failure.value = last.text
            return null
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    /** the checked file through the import path, then the older files of its id removed */
    private fun add(file: File, e: AvailableFirmware): ImportedFirmware {
        val f = library.import(file)
        if (f.error != null) {
            didChange?.invoke()
            throw FirmwareStoreError.Invalid(f.error)
        }
        // older versions of the same firmware: the newest of them is kept (a way back if the new one doesn't read the
        // memory), the rest removed; the user removes the kept one when they're happy
        val older = library.files.value.filter {
            it.error == null && it.coreID == f.coreID && it.sha256 != f.sha256 && !FirmwareVersion.less(f.version, it.version)
        }.sortedWith { a, b -> FirmwareVersion.compare(b.version, a.version) }
        val kept = older.firstOrNull()
        val old = older.drop(1)
        didChange?.invoke()
        if (older.isNotEmpty()) {
            willReplace?.invoke(older, f)
            for (o in old) library.delete(o)
            didChange?.invoke()
        }
        _message.value = if (older.isEmpty()) "Installed ${f.name} ${f.version}."
        else "Updated ${f.name} to ${f.version}. " + (kept?.let { "${it.version} is kept: delete it when you're happy." } ?: "")
        log("${_message.value} (${f.file}; kept ${kept?.file ?: "-"}; removed ${old.map { it.file }})")
        return f
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val PAGE_MS = 600_000L

        /** the size and SHA-256 the feed gave (either may be missing; the loader checks the file next) */
        fun verify(file: File, d: AvailableFirmware.Download) {
            val n = file.length()
            if (d.size > 0 && n != d.size) throw FirmwareStoreError.Size(d.size, n)
            if (d.sha256.isNotEmpty() && sha256(file) != d.sha256) throw FirmwareStoreError.Checksum
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { inp ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val k = inp.read(buf)
                    if (k < 0) break
                    md.update(buf, 0, k)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** the feed's file name, if it is a plain .rfm1 name; else <id>-<version>.rfm1 */
        fun safeName(n: String, e: AvailableFirmware): String {
            val ok = n.isNotEmpty() && !n.startsWith(".") && n.lowercase().endsWith(".rfm1") &&
                n.all { it.isLetterOrDigit() || it in "-_.+ " }
            return if (ok) n else "${e.id}-${e.version}.rfm1".replace("/", "_")
        }
    }
}
