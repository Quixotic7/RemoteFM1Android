// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/FirmwareFeeds.swift; updated to d629aa0 (fe93170,
// e91d519: RemoteFM1FW's catalog; 9494ebe, 3828820: every release's versions and dates; f02182b: the catalog's
// editor and port; d88353e: a same-version rebuild is an update).
//
// Get firmware: the .rfm1 files the app can download, from a list of feeds.
//
//   a feed       a URL the app reads: a site catalog (remotefm1.com/catalog.json: firmwares[], those with an rfm1
//                object can be downloaded, its file relative to the catalog's URL) or a GitHub release (the API's
//                releases/latest: its <id>-<version>.rfm1 assets, with size and sha256 digest)
//   the merge    one entry per firmware id: its newest version (FirmwareVersion), the downloads of that version from
//                every feed kept as alternatives; names, authors and descriptions filled in from a site catalog's
//                entry of the same id
//   the state    an entry against the imported files (ImportedFirmware: the manifest's id and version): Install,
//                Installed, or Update when every file of that id is older
//
// The cache (FirmwareStore) keeps iOS's JSON field names, so the two apps read each other's.
package com.quixotic7.remotefm1.firmware

import java.net.URI
import java.time.Instant

/** a place the app reads the list from */
data class FirmwareFeed(val kind: Kind, val url: String, val name: String) {
    enum class Kind(val raw: String) {
        SITE("site"),           // a catalog.json like remotefm1.com's
        GITHUB("github"),       // a GitHub release (api.github.com/repos/<owner>/<repo>/releases/latest)
        CATALOG("catalog");     // RemoteFM1FW's catalog/catalog.json: card text and a thumbnail by manifest id (no downloads)

        companion object {
            fun of(s: String?) = entries.firstOrNull { it.raw == s }
        }
    }

    val id: String get() = url

    fun toMap(): Map<String, Any?> = linkedMapOf("kind" to kind.raw, "url" to url, "name" to name)

    companion object {
        val REMOTEFM1 = FirmwareFeed(Kind.SITE, "https://remotefm1.com/catalog.json", "remotefm1.com")
        val RELEASES = FirmwareFeed(Kind.GITHUB, "https://api.github.com/repos/Quixotic7/RemoteFM1FW/releases/latest",
            "the RemoteFM1FW releases on GitHub")
        /** every RemoteFM1FW release, newest first (the older versions; read when a page needs them, not with the feeds) */
        const val ALL_RELEASES = "https://api.github.com/repos/Quixotic7/RemoteFM1FW/releases?per_page=100"
        /** RemoteFM1FW's card text and thumbnails (catalog/SCHEMA.md); its images sit beside it in images/ */
        val FW_CATALOG = FirmwareFeed(Kind.CATALOG, "https://raw.githubusercontent.com/Quixotic7/RemoteFM1FW/main/catalog/catalog.json",
            "the RemoteFM1FW catalog")
        /** the feeds the app reads, in order (the first one's details win, a later one fills what it left empty) */
        val DEFAULTS = listOf(REMOTEFM1, RELEASES, FW_CATALOG)

        fun fromMap(m: Map<String, Any?>?): FirmwareFeed? {
            m ?: return null
            return FirmwareFeed(Kind.of(m.str("kind")) ?: return null, m.str("url") ?: return null, m.str("name") ?: "")
        }
    }
}

/** a firmware the app can download */
data class AvailableFirmware(
    val id: String,                             // the firmware's id (its manifest's)
    var name: String = "",
    var version: String = "",
    var author: String = "",
    var category: String = "",
    var summary: String = "",                   // one line
    var about: String = "",                     // the description
    var license: String = "",
    var sourceURL: String = "",
    var site: String = "",
    /** (the site catalog's `editor`) the firmware's own web editor / manager page; "" none */
    var editor: String = "",
    /** (RemoteFM1FW's catalog) the thumbnail's address; "" none */
    var image: String = "",
    /** (RemoteFM1FW's catalog) the firmware's USB MIDI port name, which its editor looks for; "" not given */
    var port: String = "",
    var repo: String = "",                      // "owner/name" on GitHub
    var beta: Boolean = false,
    /** a Fantasy CFW (catalog.json's `virtual`, at the item or in its rfm1; the file's manifest decides once here) */
    var virtual: Boolean = false,
    var downloads: List<Download> = emptyList(),    // the same version from each feed, the first tried first
    /** (the RemoteFM1FW releases) when this version was first released; null: not known */
    var released: Instant? = null,
) {
    data class Download(
        val url: String,
        val feed: String,                       // the feed's name
        val sha256: String,                     // lower-case hex; "" when the feed gives none
        val size: Long,                         // 0 when the feed gives none
        val fileName: String,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf("url" to url, "feed" to feed, "sha256" to sha256, "size" to size, "fileName" to fileName)

        companion object {
            fun fromMap(m: Map<String, Any?>?): Download? {
                m ?: return null
                return Download(m.str("url") ?: return null, m.str("feed") ?: "", m.str("sha256") ?: "", m.int("size") ?: 0,
                    m.str("fileName") ?: "")
            }
        }
    }

    val size: Long get() = downloads.firstOrNull { it.size > 0 }?.size ?: 0
    val sha256: String get() = downloads.firstOrNull { it.sha256.isNotEmpty() }?.sha256 ?: ""
    val fileName: String get() = downloads.firstOrNull()?.fileName ?: "$id-$version.rfm1"
    val displayName: String get() = name.ifEmpty { nameFromId(id) }

    /** the empty fields taken from o (a site catalog's entry for this id, or another feed's). sameVersion false: o
     *  describes another version (its source link then is the repo's, not that version's tag) */
    fun fill(o: AvailableFirmware, sameVersion: Boolean) {
        if (name.isEmpty()) name = o.name
        if (author.isEmpty()) author = o.author
        if (category.isEmpty()) category = o.category
        if (summary.isEmpty()) summary = o.summary
        if (about.isEmpty()) about = o.about
        if (license.isEmpty()) license = o.license
        if (site.isEmpty()) site = o.site
        if (repo.isEmpty()) repo = o.repo
        if (editor.isEmpty()) editor = o.editor
        if (image.isEmpty()) image = o.image
        if (port.isEmpty()) port = o.port
        if (sourceURL.isEmpty()) sourceURL = if (sameVersion || o.repo.isEmpty()) o.sourceURL else "https://github.com/${o.repo}"
        if (sameVersion && o.beta) beta = true
        if (o.virtual) virtual = true
    }

    /** RemoteFM1FW's catalog entry's card text, image, editor and port over what another feed said */
    fun takeCatalog(c: AvailableFirmware) {
        if (c.summary.isNotEmpty()) summary = c.summary
        if (c.about.isNotEmpty()) about = c.about
        if (c.image.isNotEmpty()) image = c.image
        if (c.editor.isNotEmpty()) editor = c.editor
        if (c.port.isNotEmpty()) port = c.port
        if (c.virtual) virtual = true
    }

    /** against the imported files: the same file, else the newest valid file of the same id */
    fun state(files: List<ImportedFirmware>): InstallState {
        val shas = downloads.map { it.sha256 }.filter { it.isNotEmpty() }.toSet()
        val here = files.filter { it.error == null && (it.coreID == id || it.sha256 in shas) }
        here.firstOrNull { it.sha256 in shas }?.let { return InstallState.Installed(it.version) }
        val newest = here.reduceOrNull { a, b -> if (FirmwareVersion.less(a.version, b.version)) b else a } ?: return InstallState.NotInstalled
        if (FirmwareVersion.less(newest.version, version)) return InstallState.Update(newest.version)
        // the same version, another file: a rebuild (RemoteFM1FW keeps a firmware's own version when only the .rfm1
        // glue changed; the SHA-256 tells): offered as an update
        if (shas.isNotEmpty() && FirmwareVersion.compare(newest.version, version) == 0) return InstallState.Update(newest.version)
        return InstallState.Installed(newest.version)
    }

    /** a built-in core of this id, of this version or newer (not offered: the built-in one runs natively) */
    fun builtIn(cores: List<CoreInfo>): Boolean = cores.any { !it.imported && it.firmwareID == id && !FirmwareVersion.less(it.version, version) }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "name" to name, "version" to version, "author" to author, "category" to category, "summary" to summary,
        "about" to about, "license" to license, "sourceURL" to sourceURL, "site" to site, "editor" to editor, "image" to image,
        "port" to port, "repo" to repo, "beta" to beta, "isVirtual" to virtual, "downloads" to downloads.map { it.toMap() },
    ).also { m -> released?.let { m["released"] = it.toString() } }

    companion object {
        /** "coralflower" -> "Coralflower" */
        fun nameFromId(id: String) = id.take(1).uppercase() + id.drop(1)

        /** (a cache from an older build lacks the newer fields: those take their defaults) */
        fun fromMap(m: Map<String, Any?>?): AvailableFirmware? {
            m ?: return null
            return AvailableFirmware(m.str("id") ?: return null, name = m.str("name") ?: "", version = m.str("version") ?: "",
                author = m.str("author") ?: "", category = m.str("category") ?: "", summary = m.str("summary") ?: "",
                about = m.str("about") ?: "", license = m.str("license") ?: "", sourceURL = m.str("sourceURL") ?: "",
                site = m.str("site") ?: "", editor = m.str("editor") ?: "", image = m.str("image") ?: "", port = m.str("port") ?: "",
                repo = m.str("repo") ?: "", beta = m.bool("beta") ?: false,
                virtual = m.bool("isVirtual") ?: m.bool("virtual") ?: false,      // (iOS's name; "virtual": an older Android cache)
                downloads = m["downloads"].arr()?.mapNotNull { Download.fromMap(it.obj()) } ?: emptyList(),
                released = m.str("released")?.let { runCatching { Instant.parse(it) }.getOrNull() })
        }
    }
}

/** what an entry is to this device */
sealed class InstallState {
    object NotInstalled : InstallState() { override fun toString() = "NotInstalled" }
    data class Installed(val version: String) : InstallState()
    /** the newest version here, older than the entry's */
    data class Update(val version: String) : InstallState()

    val installedVersion: String?
        get() = when (this) {
            NotInstalled -> null
            is Installed -> version
            is Update -> version
        }
}

/** one feed as last read */
data class FeedSnapshot(
    val feed: FirmwareFeed,
    val fetched: Instant,
    val firmware: List<AvailableFirmware>,                      // the downloadable ones
    val details: Map<String, AvailableFirmware> = emptyMap(),   // a site catalog: every firmware's details by id
    /** a GitHub release's tag (null: not a release, or a cache from an older build) */
    val release: String? = null,
) {
    class ParseError : Exception("not a feed's answer")

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "feed" to feed.toMap(), "fetched" to fetched.toString(), "firmware" to firmware.map { it.toMap() },
        "details" to details.mapValues { it.value.toMap() }).also { m -> release?.let { m["release"] = it } }

    companion object {
        fun fromMap(m: Map<String, Any?>?): FeedSnapshot? {
            m ?: return null
            val feed = FirmwareFeed.fromMap(m["feed"].obj()) ?: return null
            val fetched = m.str("fetched")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            return FeedSnapshot(feed, fetched, m["firmware"].arr()?.mapNotNull { AvailableFirmware.fromMap(it.obj()) } ?: return null,
                m["details"].obj()?.mapNotNull { (k, v) -> AvailableFirmware.fromMap(v.obj())?.let { k to it } }?.toMap() ?: emptyMap(),
                m.str("release"))
        }

        /** a feed's response read (a firmware whose fields don't parse is left out) */
        fun parse(data: ByteArray, feed: FirmwareFeed, fetched: Instant = Instant.now()): FeedSnapshot {
            val root = runCatching { MiniJson.parse(data).obj() }.getOrNull() ?: throw ParseError()
            return when (feed.kind) {
                FirmwareFeed.Kind.SITE -> {
                    val items = root["firmwares"].arr() ?: throw ParseError()
                    val list = ArrayList<AvailableFirmware>()
                    val details = LinkedHashMap<String, AvailableFirmware>()
                    for (it in items) {
                        val f = runCatching { SiteItem.of(it.obj()) }.getOrNull() ?: continue
                        if (f.id.isEmpty()) continue
                        val e = AvailableFirmware(f.id, name = f.name ?: "", version = f.version ?: "",
                            author = f.authorFull ?: f.author ?: "", category = f.category ?: "", summary = f.tag ?: "",
                            about = f.what ?: f.blurb ?: "", license = f.license ?: "", sourceURL = f.source ?: "",
                            site = f.site ?: "", editor = f.editor ?: "", repo = f.repo ?: "", beta = f.beta ?: false,
                            virtual = f.virtual == true || f.rfm1?.virtual == true)
                        details[f.id] = e.copy()
                        val r = f.rfm1 ?: continue
                        val path = r.url ?: r.file ?: continue
                        val url = runCatching { URI(feed.url).resolve(URI(path)).toString() }.getOrNull() ?: continue
                        if (!r.version.isNullOrEmpty() && r.version != e.version) {
                            e.version = r.version
                            e.beta = r.version.contains("-")
                            if (e.repo.isNotEmpty()) e.sourceURL = "https://github.com/${e.repo}"
                        }
                        if (!r.authors.isNullOrEmpty() && f.authorFull == null) e.author = r.authors.joinToString(", ")
                        e.downloads = listOf(AvailableFirmware.Download(url, feed.name, (r.sha256 ?: "").lowercase(), r.size ?: 0,
                            r.fileName ?: url.substringAfterLast('/')))
                        list.add(e)
                    }
                    FeedSnapshot(feed, fetched, list, details)
                }
                FirmwareFeed.Kind.GITHUB -> {
                    val assets = root["assets"].arr() ?: throw ParseError()
                    val list = ArrayList<AvailableFirmware>()
                    for (a in assets) {
                        val m = a.obj() ?: throw ParseError()
                        val name = m.str("name") ?: throw ParseError()
                        val dl = m.str("browser_download_url") ?: throw ParseError()
                        val size = runCatching { m.optInt("size") }.getOrElse { throw ParseError() }
                        val digest = runCatching { m.optStr("digest") }.getOrElse { throw ParseError() } ?: ""
                        val (id, version) = idAndVersion(name) ?: continue
                        list.add(AvailableFirmware(id, version = version, downloads = listOf(AvailableFirmware.Download(dl, feed.name,
                            if (digest.startsWith("sha256:")) digest.drop(7).lowercase() else "", size ?: 0, name))))
                    }
                    FeedSnapshot(feed, fetched, list, release = runCatching { root.optStr("tag_name") }.getOrNull())
                }
                FirmwareFeed.Kind.CATALOG -> {
                    val items = root["firmwares"].obj() ?: throw ParseError()
                    val details = LinkedHashMap<String, AvailableFirmware>()
                    for ((id, v) in items) {
                        val f = v.obj() ?: continue
                        if (id.isEmpty()) continue
                        val e = runCatching {
                            AvailableFirmware(id, name = f.optStr("name") ?: "", author = f.optStr("author") ?: "",
                                category = f.optStr("category") ?: "", summary = f.optStr("tag") ?: "", about = f.optStr("what") ?: "",
                                site = f.optStr("site") ?: "", port = f.optStr("port") ?: "", beta = f.optBool("beta") ?: false,
                                virtual = f.optBool("virtual") ?: false).also { e ->
                                f.optStr("editor")?.let { u -> if (https(u)) e.editor = u }
                                f.optStr("image")?.let { i -> resolve(feed.url, i)?.takeIf(::https)?.let { e.image = it } }
                            }
                        }.getOrNull() ?: continue                       // (a field of the wrong type: left out)
                        details[id] = e
                    }
                    FeedSnapshot(feed, fetched, emptyList(), details)
                }
            }
        }

        private fun https(u: String) = runCatching { URI(u).scheme?.lowercase() == "https" }.getOrDefault(false)
        private fun resolve(base: String, path: String) = runCatching { URI(base).resolve(URI(path)).toString() }.getOrNull()

        /** every version in a list of GitHub releases (newest release first): by id, one entry a version (the newest
         *  release's file), newest version first; each with the date of the first release that had it */
        fun versions(data: ByteArray, feed: String): Map<String, List<AvailableFirmware>> {
            val list = runCatching { MiniJson.parse(data).arr() }.getOrNull() ?: throw ParseError()
            val out = LinkedHashMap<String, MutableList<AvailableFirmware>>()
            for (item in list) {
                val r = item.obj() ?: continue
                if (r.bool("draft") == true) continue
                val published = r.str("published_at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val assets = r["assets"].arr() ?: continue
                for (a in assets) {
                    val m = a.obj() ?: continue
                    val name = m.str("name") ?: continue
                    val url = m.str("browser_download_url") ?: continue
                    val (id, version) = idAndVersion(name) ?: continue
                    if (!https(url)) continue
                    // (newest release first, the releases cumulative: an older release with this version moves its date back)
                    val have = out[id]?.firstOrNull { it.version == version }
                    if (have != null) {
                        if (published != null) have.released = published
                        continue
                    }
                    val digest = m.str("digest") ?: ""
                    out.getOrPut(id) { ArrayList() }.add(AvailableFirmware(id, version = version, beta = version.contains("-"),
                        released = published, downloads = listOf(AvailableFirmware.Download(url, feed,
                            if (digest.startsWith("sha256:")) digest.drop(7).lowercase() else "", m.int("size") ?: 0, name))))
                }
            }
            return out.mapValues { (_, v) -> v.sortedWith { a, b -> FirmwareVersion.compare(b.version, a.version) } }
        }

        /** every snapshot's details as one, by id: the first feed's entry, its empty fields filled from the later ones;
         *  RemoteFM1FW's catalog's text, image, editor and port always (the owner: "Always use the Catalog
         *  descriptions and thumbnails") */
        fun details(snaps: List<FeedSnapshot>): Map<String, AvailableFirmware> {
            val out = LinkedHashMap<String, AvailableFirmware>()
            for (s in snaps) for ((k, v) in s.details) {
                val have = out[k]
                if (have != null) have.fill(v, sameVersion = false) else out[k] = v.copy()
            }
            for (s in snaps) if (s.feed.kind == FirmwareFeed.Kind.CATALOG) for ((k, v) in s.details) out[k]?.takeCatalog(v)
            return out
        }

        /** "<id>-<version>.rfm1" (the id may hold dashes: the version starts at the first "-" before a digit) */
        fun idAndVersion(name: String): Pair<String, String>? {
            if (!name.lowercase().endsWith(".rfm1")) return null
            val stem = name.dropLast(5)
            var i = 0
            while (true) {
                val dash = stem.indexOf('-', i)
                if (dash < 0) return null
                val next = dash + 1
                if (next < stem.length && (stem[next].isDigit() || (stem[next] == 'v' && next + 1 < stem.length && stem[next + 1].isDigit()))) {
                    val id = stem.substring(0, dash).lowercase()
                    return if (id.isEmpty()) null else id to stem.substring(next)
                }
                i = next
            }
        }

        /** every feed's list as one: one entry per id, its newest version; the same version from several feeds keeps
         *  each download (in feed order); details filled in from the site catalogs. Sorted by name */
        fun merge(snaps: List<FeedSnapshot>): List<AvailableFirmware> {
            val details = details(snaps)
            val out = LinkedHashMap<String, AvailableFirmware>()
            for (s in snaps) {
                for (orig in s.firmware) {
                    val e = orig.copy()
                    details[e.id]?.let { d -> e.fill(d, FirmwareVersion.compare(d.version, e.version) == 0) }
                    val have = out[e.id]
                    if (have == null) {
                        out[e.id] = e
                        continue
                    }
                    when (FirmwareVersion.compare(e.version, have.version)) {
                        1 -> {
                            e.fill(have, sameVersion = false)
                            out[e.id] = e
                        }
                        0 -> {
                            val more = e.downloads.filter { d -> have.downloads.none { it.url == d.url } }
                            have.downloads = have.downloads + more
                            have.fill(e, sameVersion = true)
                        }
                        else -> have.fill(e, sameVersion = false)
                    }
                }
            }
            val catalog = HashMap<String, AvailableFirmware>()
            for (s in snaps) if (s.feed.kind == FirmwareFeed.Kind.CATALOG) for ((k, v) in s.details) if (k !in catalog) catalog[k] = v
            return out.values.map { e ->
                catalog[e.id]?.let { e.takeCatalog(it) }
                if (e.name.isEmpty()) e.name = AvailableFirmware.nameFromId(e.id)
                if (e.version.contains("-")) e.beta = true
                e
            }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        }
    }
}

/** remotefm1.com/catalog.json's item (the fields used); a field of the wrong type leaves the item out */
private class SiteItem(
    val id: String, val name: String?, val version: String?, val author: String?, val authorFull: String?,
    val category: String?, val tag: String?, val what: String?, val blurb: String?, val license: String?,
    val source: String?, val repo: String?, val site: String?, val editor: String?, val beta: Boolean?, val rfm1: Rfm1?,
    val virtual: Boolean? = null,
) {
    class Rfm1(val file: String?, val url: String?, val sha256: String?, val version: String?, val fileName: String?,
               val size: Long?, val authors: List<String>?, val virtual: Boolean? = null)

    companion object {
        fun of(m: Map<String, Any?>?): SiteItem? {
            m ?: return null
            val r = m["rfm1"]?.let { v ->
                val o = v.obj() ?: throw WrongType("rfm1")
                Rfm1(o.optStr("file"), o.optStr("url"), o.optStr("sha256"), o.optStr("version"), o.optStr("fileName"),
                    o.optInt("size"), o["authors"]?.let { a -> a.arr()?.map { it as? String ?: throw WrongType("authors") } ?: throw WrongType("authors") },
                    o.optBool("virtual"))
            }
            return SiteItem(m.str("id") ?: return null, m.optStr("name"), m.optStr("version"), m.optStr("author"),
                m.optStr("authorFull"), m.optStr("category"), m.optStr("tag"), m.optStr("what"), m.optStr("blurb"),
                m.optStr("license"), m.optStr("source"), m.optStr("repo"), m.optStr("site"), m.optStr("editor"),
                m.optBool("beta"), r, m.optBool("virtual"))
        }
    }
}
