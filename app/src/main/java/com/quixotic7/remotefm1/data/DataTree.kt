// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
//
// A folder the mirror, the backups and Restore work in, by relative paths ("Fizz/Pads.rfm1state"). Android has no
// Files-app folder per app as iOS has, so the user picks a folder once (Settings › Your data) and the app keeps a
// persisted permission to it: SafTree (the Storage Access Framework). FileTree is the same on java.io.File (the JVM
// tests, and any folder of the app's own). Hidden names (".x") are skipped by files() and dirs(), as iOS's
// enumerator's .skipsHiddenFiles.
package com.quixotic7.remotefm1.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

interface DataTree {
    /** every file under [dir] (relative to the tree, "/" between names), hidden files and folders skipped */
    fun files(dir: String = ""): List<String>
    /** the folders right under [dir] (their relative paths), hidden ones skipped */
    fun dirs(dir: String = ""): List<String>
    fun exists(rel: String): Boolean
    fun isDir(rel: String): Boolean
    fun read(rel: String): ByteArray?
    fun open(rel: String): InputStream?
    /** [data] written to [rel] (its folders made; an existing file replaced) */
    fun write(rel: String, data: ByteArray)
    /** a new or replaced file at [rel], written through the stream */
    fun openWrite(rel: String, mime: String = "application/octet-stream"): OutputStream
    /** a file moved (or renamed) to [to], its folders made */
    fun move(from: String, to: String)
    fun delete(rel: String)
    /** a folder removed when it has nothing in it (hidden files count as nothing) */
    fun deleteIfEmpty(dir: String)

    companion object {
        fun join(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"
        fun parent(rel: String) = rel.substringBeforeLast('/', "")
        fun name(rel: String) = rel.substringAfterLast('/')
        fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            else -> "application/octet-stream"                  // (keeps the name as given: .rfm1state, .json)
        }
    }
}

class FileTree(val root: File) : DataTree {
    private fun f(rel: String) = if (rel.isEmpty()) root else File(root, rel)

    override fun files(dir: String): List<String> {
        val out = ArrayList<String>()
        fun walk(d: File, rel: String) {
            for (c in d.listFiles()?.sortedBy { it.name } ?: return) {
                if (c.name.startsWith(".")) continue
                val r = DataTree.join(rel, c.name)
                if (c.isDirectory) walk(c, r) else out.add(r)
            }
        }
        walk(f(dir), dir)
        return out
    }

    override fun dirs(dir: String): List<String> =
        f(dir).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.map { DataTree.join(dir, it.name) }
            ?.sorted() ?: emptyList()

    override fun exists(rel: String) = f(rel).exists()
    override fun isDir(rel: String) = f(rel).isDirectory
    override fun read(rel: String): ByteArray? = runCatching { f(rel).readBytes() }.getOrNull()
    override fun open(rel: String): InputStream? = runCatching { f(rel).inputStream() }.getOrNull()
    override fun write(rel: String, data: ByteArray) = DataFiles.writeAtomic(f(rel), data)

    override fun openWrite(rel: String, mime: String): OutputStream {
        val t = f(rel)
        t.parentFile?.mkdirs()
        return t.outputStream()
    }

    override fun move(from: String, to: String) {
        val t = f(to)
        t.parentFile?.mkdirs()
        if (!f(from).renameTo(t)) throw IOException("cannot move $from to $to")
    }

    override fun delete(rel: String) { f(rel).delete() }

    override fun deleteIfEmpty(dir: String) {
        val d = f(dir)
        val left = d.listFiles() ?: return
        if (left.all { it.isFile && it.name.startsWith(".") }) { left.forEach { it.delete() }; d.delete() }
    }
}

/** a folder the user picked (ACTION_OPEN_DOCUMENT_TREE), by the Storage Access Framework */
class SafTree(private val cr: ContentResolver, val treeUri: Uri) : DataTree {
    private class Doc(val id: String, val name: String, val mime: String) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
    }

    private val rootID: String = DocumentsContract.getTreeDocumentId(treeUri)
    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun children(id: String): List<Doc> {
        val u = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
        val out = ArrayList<Doc>()
        cr.query(u, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        return out
    }

    /** the document at [rel] (null: none) */
    private fun find(rel: String): Doc? {
        if (rel.isEmpty()) return Doc(rootID, "", Document.MIME_TYPE_DIR)
        var cur = Doc(rootID, "", Document.MIME_TYPE_DIR)
        for (part in rel.split('/')) {
            if (part.isEmpty()) continue
            cur = children(cur.id).firstOrNull { it.name == part } ?: return null
        }
        return cur
    }

    /** the folder at [rel], made when missing */
    private fun folder(rel: String): Doc {
        var cur = Doc(rootID, "", Document.MIME_TYPE_DIR)
        for (part in rel.split('/')) {
            if (part.isEmpty()) continue
            val next = children(cur.id).firstOrNull { it.name == part }
            cur = if (next != null) {
                if (!next.isDir) throw IOException("$part is a file, not a folder")
                next
            } else {
                val u = DocumentsContract.createDocument(cr, uri(cur.id), Document.MIME_TYPE_DIR, part)
                    ?: throw IOException("cannot make the folder $part")
                Doc(DocumentsContract.getDocumentId(u), part, Document.MIME_TYPE_DIR)
            }
        }
        return cur
    }

    override fun files(dir: String): List<String> {
        val out = ArrayList<String>()
        fun walk(id: String, rel: String) {
            for (c in children(id).sortedBy { it.name }) {
                if (c.name.startsWith(".")) continue
                val r = DataTree.join(rel, c.name)
                if (c.isDir) walk(c.id, r) else out.add(r)
            }
        }
        val d = find(dir) ?: return out
        if (d.isDir) walk(d.id, dir)
        return out
    }

    override fun dirs(dir: String): List<String> {
        val d = find(dir) ?: return emptyList()
        return children(d.id).filter { it.isDir && !it.name.startsWith(".") }.map { DataTree.join(dir, it.name) }.sorted()
    }

    override fun exists(rel: String) = find(rel) != null
    override fun isDir(rel: String) = find(rel)?.isDir == true
    override fun read(rel: String): ByteArray? = runCatching { open(rel)?.use { it.readBytes() } }.getOrNull()

    override fun open(rel: String): InputStream? {
        val d = find(rel) ?: return null
        if (d.isDir) return null
        return runCatching { cr.openInputStream(uri(d.id)) }.getOrNull()
    }

    override fun write(rel: String, data: ByteArray) {
        openWrite(rel, DataTree.mimeFor(rel)).use { it.write(data) }
    }

    override fun openWrite(rel: String, mime: String): OutputStream {
        val name = DataTree.name(rel)
        val parent = folder(DataTree.parent(rel))
        val have = children(parent.id).firstOrNull { it.name == name }
        val u = if (have != null && !have.isDir) uri(have.id) else
            DocumentsContract.createDocument(cr, uri(parent.id), mime, name) ?: throw IOException("cannot make $rel")
        return cr.openOutputStream(u, "wt") ?: throw FileNotFoundException(rel)
    }

    override fun move(from: String, to: String) {
        // (copied, then the old one removed: works on every provider; the files are small)
        val d = read(from) ?: throw FileNotFoundException(from)
        write(to, d)
        delete(from)
    }

    override fun delete(rel: String) {
        val d = find(rel) ?: return
        runCatching { DocumentsContract.deleteDocument(cr, uri(d.id)) }
    }

    override fun deleteIfEmpty(dir: String) {
        val d = find(dir) ?: return
        if (!d.isDir || dir.isEmpty()) return
        val left = children(d.id)
        if (left.all { !it.isDir && it.name.startsWith(".") }) runCatching { DocumentsContract.deleteDocument(cr, uri(d.id)) }
    }
}
