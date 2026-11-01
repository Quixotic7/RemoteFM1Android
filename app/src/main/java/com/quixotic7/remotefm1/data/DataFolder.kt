// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported in part from RemoteFM1IOS edf2039 App/MainScreen.swift (openState, backUp, restore: their words).
//
// Android's side of the user's data folders and files:
//   DataFolder  the folder the user picks once (Settings › Your data; ACTION_OPEN_DOCUMENT_TREE): the permission kept
//               (takePersistableUriPermission), the Uri in the app's preferences. It holds States/ (the mirror) and
//               Backups/. None picked: the mirror is off and a backup goes to the share sheet.
//   Incoming    a .rfm1state (or a backup .zip) opened from a file manager, a download or a share (ACTION_VIEW,
//               ACTION_SEND, ACTION_SEND_MULTIPLE): its Uris, bytes and name; and the words the States page shows.
package com.quixotic7.remotefm1.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

object DataFolder {
    private const val PREFS = "rfm.data"
    private const val KEY = "folder"

    /** the picked folder, while the app still holds its permission (null: none) */
    fun uri(ctx: Context): Uri? {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val u = Uri.parse(s)
        val held = ctx.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission && it.isWritePermission }
        return if (held) u else null
    }

    /** (the picker's result) the folder kept, with its permission; false: the permission couldn't be kept */
    fun set(ctx: Context, tree: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching { ctx.contentResolver.takePersistableUriPermission(tree, flags) }.isSuccess
        if (!ok) return false
        val old = uri(ctx)
        if (old != null && old != tree) runCatching { ctx.contentResolver.releasePersistableUriPermission(old, flags) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tree.toString()).apply()
        return true
    }

    /** the folder forgotten (its files stay) */
    fun clear(ctx: Context) {
        val old = uri(ctx)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (old != null) runCatching { ctx.contentResolver.releasePersistableUriPermission(old, flags) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /** the picked folder as a DataTree (null: none) */
    fun tree(ctx: Context): DataTree? = uri(ctx)?.let { SafTree(ctx.contentResolver, it) }

    /** the mirror on the picked folder (null: none picked, the mirror is off) */
    fun mirror(ctx: Context, library: StateLibrary): StateMirror? = tree(ctx)?.let { StateMirror(it, library) }

    /** the Your data page's note (iOS's, the folder named instead of Files) */
    fun backupNote(folderName: String?): String =
        "Back up everything writes every state, each firmware's current memory and the newest safety copies into " +
            (if (folderName != null) "$folderName › Backups" else "one .zip to share") +
            ". Restore adds a backup's states to the library; nothing is overwritten."

    /** "Backed up 3 states, 2 firmware memories and 6 safety copies into …" */
    fun backedUp(m: Backup.Manifest, where: String): String =
        "Backed up ${m.states.size} state${if (m.states.size == 1) "" else "s"}, ${m.current.size} firmware memor" +
            "${if (m.current.size == 1) "y" else "ies"} and ${m.safetyCopies.size} safety cop" +
            "${if (m.safetyCopies.size == 1) "y" else "ies"} into $where."

    fun backupFailed(e: Throwable) = "The backup failed: ${e.message}"

    /** a restore's words, with the firmwares not installed named (their states wait in the library) */
    fun restored(r: Backup.RestoreReport, installedFirmwareIDs: Set<String>): String {
        val missing = r.imported.filter { it.firmwareID !in installedFirmwareIDs }
            .map { it.firmwareName.ifEmpty { it.firmwareID } }.toSortedSet()
        return r.text + if (missing.isEmpty()) "" else " Not installed: ${missing.joinToString(", ")} (its states wait in the library)."
    }
}

object Incoming {
    /** the files an intent brings (VIEW's data, SEND's stream, SEND_MULTIPLE's streams) */
    fun uris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun stream(i: Intent): Uri? = if (android.os.Build.VERSION.SDK_INT >= 33)
        i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> = (if (android.os.Build.VERSION.SDK_INT >= 33)
        i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
        ?: emptyList()

    /** a Uri's name as the file manager shows it ("" when unknown) */
    fun name(cr: ContentResolver, u: Uri): String {
        if (u.scheme == ContentResolver.SCHEME_CONTENT) runCatching {
            cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    /** a Uri's bytes (at most [max]) */
    fun read(cr: ContentResolver, u: Uri, max: Int = 96 shl 20): ByteArray {
        val input = cr.openInputStream(u) ?: throw IOException("can't be opened")
        return input.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(65536)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw IOException("is too big")
            }
            out.toByteArray()
        }
    }

    /** what a Uri holds: a backup zip (by its name or the zip magic), else a state file */
    fun isZip(name: String, head: ByteArray) =
        name.endsWith(".zip", ignoreCase = true) || (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())

    /** an opened .rfm1state imported; the result for the notice (iOS openState) */
    fun importState(cr: ContentResolver, u: Uri, library: StateLibrary): Result<StateLibrary.Imported> =
        runCatching { library.importBytes(read(cr, u)) }
            .recoverCatching { e -> throw IOException("${name(cr, u).ifEmpty { "The file" }} wasn't imported: ${e.message}") }

    /**
     * The States page's notice after an opened file (iOS's words): [installed] whether a firmware with the state's id
     * is installed (the UI selects it and opens its States page), [inCatalog] whether Get firmware lists it.
     */
    fun notice(i: StateLibrary.Imported, installed: Boolean, inCatalog: Boolean): String {
        val s = i.state
        val what = if (i.duplicate) "\"${s.name}\" is already in the library." else "Imported \"${s.name}\"."
        if (installed) return "$what Load it below."
        val name = s.firmwareName.ifEmpty { s.firmwareID }
        return "$what It's a state of $name ${s.version}, which isn't installed" +
            if (!inCatalog) ": add that firmware to load it." else ": get it, then load the state from its States page."
    }
}
