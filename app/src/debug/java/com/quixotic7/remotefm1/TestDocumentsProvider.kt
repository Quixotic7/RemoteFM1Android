// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Debug builds only: a Storage Access Framework provider over files/testdocs/, for the instrumented tests of
// SafRemoteFolder (a tree the tests can open without the system picker: the provider is the app's own, so no grant is
// needed). Declared without the DOCUMENTS_PROVIDER filter, so it never shows in the picker. Document ids: "root",
// "root/<path>".
package com.quixotic7.remotefm1

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

class TestDocumentsProvider : DocumentsProvider() {
    private val base: File get() = File(context!!.filesDir, "testdocs").apply { mkdirs() }

    private fun file(id: String): File {
        if (id == ROOT) return base
        if (!id.startsWith("$ROOT/")) throw FileNotFoundException(id)
        return File(base, id.removePrefix("$ROOT/"))
    }

    private fun idOf(f: File): String = if (f == base) ROOT else "$ROOT/" + f.relativeTo(base).path

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)).apply {
            newRow().add(Root.COLUMN_ROOT_ID, "test").add(Root.COLUMN_DOCUMENT_ID, ROOT).add(Root.COLUMN_TITLE, "Test docs")
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        }

    private fun row(c: MatrixCursor, f: File) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, idOf(f)).add(Document.COLUMN_DISPLAY_NAME, if (f == base) "testdocs" else f.name)
            .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (f.isDirectory) null else f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
                                        else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE)
    }

    private fun cols(p: Array<out String>?) = p ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        queries++
        return MatrixCursor(cols(projection)).apply { row(this, f) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val d = file(parentDocumentId)
        if (!d.isDirectory) throw FileNotFoundException(parentDocumentId)
        childQueries++
        return MatrixCursor(cols(projection)).apply { d.listFiles()?.forEach { row(this, it) } }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        if (!f.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val d = file(parentDocumentId)
        if (!d.isDirectory) throw FileNotFoundException(parentDocumentId)
        var f = File(d, displayName)
        if (f.exists() || renameNext) {                       // (as providers do: "x (1)")
            renameNext = false
            var i = 1
            while (File(d, "$displayName ($i)").exists()) i++
            f = File(d, "$displayName ($i)")
        }
        val ok = if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        if (!ok) throw IllegalStateException("cannot make $displayName")
        return idOf(f)
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        if (!f.delete()) throw IllegalStateException("cannot delete $documentId")
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == ROOT && documentId.startsWith("$ROOT/") || documentId.startsWith("$parentDocumentId/")

    companion object {
        const val ROOT = "root"
        /** (tests) queries served, to see the cache work */
        @Volatile var queries = 0
        @Volatile var childQueries = 0
        /** (tests) the next createDocument names its file "x (1)" */
        @Volatile var renameNext = false
    }
}
