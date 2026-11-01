// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The RemoteFM1 folder inside the folder the user picked for States and Backups (DataFolder: ACTION_OPEN_DOCUMENT_TREE,
// a persisted permission), through the Storage Access Framework: <picked>/RemoteFM1/{audio,code,data}/, each made when
// first asked for. Reads and writes go through ContentResolver.openFileDescriptor, seekable (a FileChannel at the
// offset). SAF lookups are slow (a query per folder level), so a path's document id is cached once seen; a missing
// name is always looked up again (the user may have added it), and a cached id that no longer opens is dropped and
// looked up once more. The app's own writes, deletes and mkdirs drop what they touch; forget() drops everything.
// A few read handles stay open (streamed samples read one chunk at a time). Blocking: call it from an IO thread only.
package com.quixotic7.remotefm1.folder

import android.content.ContentResolver
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

class SafRemoteFolder(private val cr: ContentResolver, val treeUri: Uri) : RemoteFolder {
    private class Doc(val id: String, val dir: Boolean)
    private class Open(val pfd: ParcelFileDescriptor, val ch: FileChannel)

    private val treeRoot: String = DocumentsContract.getTreeDocumentId(treeUri)
    /** folder path ("" RemoteFM1 itself) -> its document */
    private val docs = ConcurrentHashMap<String, Doc>()
    private val lock = Any()
    /** document id -> an open read handle (at most [OPEN_MAX], the oldest closed first) */
    private val reads = object : LinkedHashMap<String, Open>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Open>): Boolean {
            if (size <= OPEN_MAX) return false
            close(eldest.value)
            return true
        }
    }

    override val location: String by lazy {
        val name = runCatching {
            cr.query(DocumentsContract.buildDocumentUriUsingTree(treeUri, treeRoot), arrayOf(Document.COLUMN_DISPLAY_NAME),
                     null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        "${name ?: "Your folder"} › $DIR"
    }

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun children(id: String): List<Triple<String, String, Boolean>> {
        val u = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
        val out = ArrayList<Triple<String, String, Boolean>>()
        cr.query(u, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
                 null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Triple(c.getString(0), c.getString(1) ?: "", c.getString(2) == Document.MIME_TYPE_DIR))
        } ?: throw FileNotFoundException("the folder can't be read")
        return out
    }

    /** a folder's children looked up, each cached; the one called [name] (null: none) */
    private fun child(parentRel: String, parent: Doc, name: String): Doc? {
        var hit: Doc? = null
        for ((id, n, dir) in children(parent.id)) {
            val d = Doc(id, dir)
            docs[join(parentRel, n)] = d
            if (n == name) hit = d
        }
        return hit
    }

    /** RemoteFM1/ itself, made when missing */
    private fun base(): Doc {
        docs[""]?.let { return it }
        val top = Doc(treeRoot, true)
        val have = child("\u0000", top, DIR)       // (the tree's children are not folder paths: cached under a key no path has)
        val d = when {
            have == null -> create(top, DIR, true)
            have.dir -> have
            else -> throw FileNotFoundException("$DIR is a file")
        }
        docs[""] = d
        return d
    }

    /** the document at [rel] (null: none); [makeArea]: the area (audio, code/<id>, data/<id>) made when missing */
    private fun find(rel: String, makeArea: Boolean = true): Doc? {
        docs[rel]?.let { return it }
        var cur = base()
        var at = ""
        val area = Fs.areaOf(rel)
        for (part in rel.split('/')) {
            val next = join(at, part)
            val d = docs[next] ?: child(at, cur, part)
            cur = when {
                d != null -> d
                makeArea && (next == area || area.startsWith("$next/")) -> create(cur, part, true).also { docs[next] = it }
                else -> return null
            }
            if (next != rel && !cur.dir) return null
            at = next
        }
        return cur
    }

    private fun create(parent: Doc, name: String, dir: Boolean): Doc {
        val u = DocumentsContract.createDocument(cr, uri(parent.id), if (dir) Document.MIME_TYPE_DIR else MIME, name)
            ?: throw java.io.IOException("cannot make $name")
        val id = DocumentsContract.getDocumentId(u)
        // (a provider may rename it, "x (1)": not the file asked for)
        val got = runCatching {
            cr.query(u, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        if (got != null && got != name) {
            runCatching { DocumentsContract.deleteDocument(cr, u) }
            throw java.io.IOException("the folder named it $got")
        }
        return Doc(id, dir)
    }

    /** [op] on the document at [rel] */
    private inline fun <T> withDoc(rel: String, op: (Doc?) -> T): T = retry(rel) { op(find(rel)) }

    /** [op]; when it fails as a stale cached id does (FileNotFound, IllegalArgument from the provider): what was cached
     *  of [rel]'s area dropped, [op] once more */
    private inline fun <T> retry(rel: String, op: () -> T): T = try {
        op()
    } catch (e: FileNotFoundException) {
        drop(Fs.areaOf(rel))
        op()
    } catch (e: IllegalArgumentException) {
        drop(Fs.areaOf(rel))
        op()
    }

    /** [rel] and everything under it forgotten (and its open handles closed) */
    private fun drop(rel: String) {
        val gone = docs.keys.filter { it == rel || it.startsWith("$rel/") }
        synchronized(lock) {
            for (k in gone) docs.remove(k)?.let { d -> reads.remove(d.id)?.let(::close) }
        }
    }

    private fun guard() {
        if (Looper.getMainLooper().isCurrentThread) throw IllegalStateException("the RemoteFM1 folder on the main thread")
    }

    override fun stat(path: String, out: LongArray): Int {
        guard()
        return withDoc(path) { d ->
            d ?: return@withDoc Fs.ENOENT
            cr.query(uri(d.id), arrayOf(Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE),
                     null, null, null)?.use { c ->
                if (!c.moveToFirst()) throw FileNotFoundException(path)
                val dir = c.getString(2) == Document.MIME_TYPE_DIR
                out[0] = if (dir || c.isNull(0)) 0 else c.getLong(0)
                out[1] = if (c.isNull(1)) 0 else c.getLong(1) / 1000
                out[2] = if (dir) 1 else 0
                0
            } ?: throw FileNotFoundException(path)
        }
    }

    override fun list(path: String, out: Array<ByteArray?>): Int {
        guard()
        return withDoc(path) { d ->
            d ?: return@withDoc Fs.ENOENT
            if (!d.dir) return@withDoc Fs.ENOENT
            val kids = children(d.id)
            for ((id, n, dir) in kids) docs[join(path, n)] = Doc(id, dir)
            val sorted = Fs.sortEntries(kids.map { it.second to it.third })
            out[0] = Fs.packAll(sorted)
            sorted.size
        }
    }

    override fun read(path: String, offset: Long, buf: ByteBuffer, len: Int): Int {
        guard()
        if (offset < 0 || len < 0) return Fs.EIO
        return withDoc(path) { d ->
            d ?: return@withDoc Fs.ENOENT
            if (d.dir) return@withDoc Fs.EIO
            val o = synchronized(lock) {
                reads[d.id] ?: run {
                    val pfd = cr.openFileDescriptor(uri(d.id), "r") ?: throw FileNotFoundException(path)
                    Open(pfd, FileInputStream(pfd.fileDescriptor).channel).also { reads[d.id] = it }
                }
            }
            try {
                FileRemoteFolder.readAt(o.ch, offset, buf, len)
            } catch (e: Exception) {
                synchronized(lock) { reads.remove(d.id)?.let(::close) }
                throw e
            }
        }
    }

    override fun write(path: String, offset: Long, buf: ByteBuffer, len: Int): Int {
        guard()
        if (offset < -1 || len < 0) return Fs.EIO
        val parentRel = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        return retry(path) {
            val parent = find(parentRel) ?: return Fs.ENOENT
            if (!parent.dir) return Fs.ENOENT
            val d = docs[path] ?: child(parentRel, parent, name) ?: create(parent, name, false).also { docs[path] = it }
            if (d.dir) return Fs.EIO
            synchronized(lock) { reads.remove(d.id)?.let(::close) }
            val pfd = cr.openFileDescriptor(uri(d.id), if (offset < 0) "rwt" else "rw") ?: throw FileNotFoundException(path)
            pfd.use { p ->
                FileOutputStream(p.fileDescriptor).channel.use { ch ->
                    if (offset < 0) ch.truncate(0)
                    FileRemoteFolder.writeAt(ch, if (offset < 0) 0 else offset, buf, len)
                }
            }
        }
    }

    override fun delete(path: String): Int {
        guard()
        val d = withDoc(path) { it } ?: return Fs.ENOENT
        if (d.dir && children(d.id).isNotEmpty()) return Fs.EIO           // (not empty)
        drop(path)
        return if (DocumentsContract.deleteDocument(cr, uri(d.id))) 0 else Fs.EIO
    }

    override fun mkdir(path: String): Int {
        guard()
        val have = withDoc(path) { it }
        if (have != null) return if (have.dir) 0 else Fs.EIO
        val parentRel = path.substringBeforeLast('/')
        val parent = find(parentRel) ?: return Fs.ENOENT
        if (!parent.dir) return Fs.ENOENT
        docs[path] = create(parent, path.substringAfterLast('/'), true)
        return 0
    }

    override fun forget() {
        synchronized(lock) {
            docs.clear()
            reads.values.forEach(::close)
            reads.clear()
        }
    }

    private fun close(o: Open) {
        runCatching { o.ch.close() }
        runCatching { o.pfd.close() }
    }

    companion object {
        const val DIR = "RemoteFM1"
        const val MIME = "application/octet-stream"          // (no extension added by the provider)
        private const val OPEN_MAX = 8
        private const val TAG = "RemoteFolder"

        private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

        fun log(m: String) = Log.i(TAG, m)
    }
}
