// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The RemoteFM1 folder (FM1VST docs/VIRTUAL-CARTS.md section 3) as the file API's platform side: six blocking ops on
// folder paths ("audio/kick.wav", "code/<id>/droplets", "data/<id>/save.txt", Fs.kt), one-to-one with the shared
// C++ RFMFileApi's platform interface (stat / list / read / write / delete / mkdir). The C++ checks and queues each
// request on the core thread, and its IO thread calls RemoteFolderHost below; nothing here runs on the main or the
// audio thread. On Android the folder is <the folder picked in Settings › Your data>/RemoteFM1/ (SafRemoteFolder);
// FileRemoteFolder is the same on java.io.File (the tests). Each area (audio/, code/<id>/, data/<id>/) is made the
// first time something asks for it.
package com.quixotic7.remotefm1.folder

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** every op returns 0 (or a count) or an Fs error code; [path] is a folder path (Fs.checkFolderPath) */
interface RemoteFolder {
    /** 0 or FS_*; out[0] size (0 for a folder), out[1] mtime (s), out[2] 1 for a folder */
    fun stat(path: String, out: LongArray): Int
    /** the number of entries or FS_*; out[0] every entry packed {u8 dir, u8 len, name}, sorted by name bytes, no
     *  hidden ones (Fs.sortEntries, Fs.packAll): the C++ pages through it */
    fun list(path: String, out: Array<ByteArray?>): Int
    /** bytes read into [buf] (from its position, at most [len]) at [offset]; 0 at the end; or FS_* */
    fun read(path: String, offset: Long, buf: ByteBuffer, len: Int): Int
    /** [len] bytes of [buf] (from its position) written at [offset] (-1: the file truncated first); the file made when
     *  missing (its folder must be there); bytes written or FS_* */
    fun write(path: String, offset: Long, buf: ByteBuffer, len: Int): Int
    /** a file, or an empty folder, removed: 0 or FS_* */
    fun delete(path: String): Int
    /** 0 (also when it is there already) or FS_* */
    fun mkdir(path: String): Int
    /** cached lookups and open files dropped (the folder may have changed outside the app) */
    fun forget() {}
    /** where it is, for the UI ("Music › RemoteFM1") */
    val location: String
}

/**
 * What the JNI's IO thread calls (static methods; paths as UTF-8 bytes, the C++ has checked them): the folder the user
 * picked, or FS_ENOFOLDER for everything when there is none. [changed] is fs_changed(): it moves when the folder is
 * picked again or forgotten, when the app comes back to the foreground (files may have been added meanwhile) and when
 * the app itself writes into it ([noteChanged]). The native file API isn't polled: [listener] (RemoteFolderNative) is
 * told when the folder comes or goes and when it changed, and moves its own counter.
 */
object RemoteFolderHost {
    @Volatile var folder: RemoteFolder? = null
        private set

    /** the native side (RemoteFolderNative), told of every change; null in the JVM tests */
    interface Listener {
        fun folderSet(present: Boolean)
        fun folderChanged()
    }
    @Volatile var listener: Listener? = null
    private val counter = AtomicInteger(0)
    private var treeUri: Uri? = null
    /** one thread for the app's own folder work (status, adding files); the JNI calls from its own IO thread */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "rfm-folder").apply { isDaemon = true } }

    /** what the UI shows: a folder or none, and where ("" while it is looked up) */
    data class Status(val ready: Boolean = false, val location: String = "")
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** the picked folder read again (at launch, after a pick or a clear); the counter moves when it changed */
    @Synchronized fun refresh(ctx: Context) {
        val u = com.quixotic7.remotefm1.data.DataFolder.uri(ctx)
        if (u == treeUri && (u == null) == (folder == null)) return
        treeUri = u
        folder = u?.let { SafRemoteFolder(ctx.applicationContext.contentResolver, it) }
        bump()
        publish()
        listener?.folderSet(folder != null)
    }

    /** (tests) a folder of their own; null: none */
    @Synchronized fun use(f: RemoteFolder?) {
        treeUri = null
        folder = f
        bump()
        publish()
        listener?.folderSet(f != null)
    }

    /** the status, its location looked up on [io] (a SAF query) */
    private fun publish() {
        val f = folder
        _status.value = Status(f != null, "")
        if (f != null) io.execute { val l = runCatching { f.location }.getOrDefault(""); if (folder === f) _status.value = Status(true, l) }
    }

    /** the folder may have changed (the app back in the foreground, or files the app added) */
    fun noteChanged() {
        folder?.forget()
        bump()
        listener?.folderChanged()
    }

    private fun bump() {
        counter.incrementAndGet()
    }

    val ready: Boolean get() = folder != null

    // ---- the JNI's calls (blocking, from the native IO thread)

    @JvmStatic fun changed(): Int = counter.get()

    @JvmStatic fun stat(path: ByteArray, out: LongArray): Int = call(path, false) { f, p -> f.stat(p, out) }

    @JvmStatic fun list(path: ByteArray, out: Array<ByteArray?>): Int = call(path, false) { f, p -> f.list(p, out) }

    @JvmStatic fun read(path: ByteArray, offset: Long, buf: ByteBuffer, len: Int): Int =
        call(path, false) { f, p -> f.read(p, offset, buf, len) }

    @JvmStatic fun write(path: ByteArray, offset: Long, buf: ByteBuffer, len: Int): Int =
        call(path, true) { f, p -> f.write(p, offset, buf, len) }

    @JvmStatic fun delete(path: ByteArray): Int = call(path, true) { f, p -> f.delete(p) }

    @JvmStatic fun mkdir(path: ByteArray): Int = call(path, true) { f, p -> f.mkdir(p) }

    private inline fun call(path: ByteArray, write: Boolean, op: (RemoteFolder, String) -> Int): Int {
        val f = folder ?: return Fs.ENOFOLDER
        val p = Fs.utf8(path) ?: return Fs.EACCES
        if (Fs.checkFolderPath(p, write) != 0) return Fs.EACCES
        return try {
            op(f, p)
        } catch (e: SecurityException) {
            Fs.ENOFOLDER                                    // (the permission went: as no folder)
        } catch (e: Throwable) {
            Fs.ioCode(e)
        }
    }
}
