// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The native side of the RemoteFM1 folder (native/rfmcore/android/rfm_folder_jni.cpp): the shared C++ file API
// (RFMFileApi) gets RemoteFolderHost as its folder while one is picked, and none (FS_ENOFOLDER) while none is.
// RemoteFolderHost tells this object when the folder comes or goes and when its files changed (fs_changed moves).
package com.quixotic7.remotefm1.folder

object RemoteFolderNative : RemoteFolderHost.Listener {
    init {
        System.loadLibrary("rfmcore")
    }

    @Volatile private var started = false

    /** once, on a Java thread (the app's class loader): the native folder wired to RemoteFolderHost, set as it is now */
    @Synchronized fun start() {
        if (started) return
        if (!nativeInit()) return
        started = true
        RemoteFolderHost.listener = this
        sync()
    }

    /** the native folder set again from RemoteFolderHost (a picked folder, or none); fs_changed moves */
    fun sync() {
        if (started) nativeSetFolder(RemoteFolderHost.ready)
    }

    override fun folderSet(present: Boolean) = nativeSetFolder(present)

    override fun folderChanged() = nativeChanged()

    /** fs_changed's counter (every Fantasy CFW's) */
    val changedCount: Int get() = nativeChangedCount()

    /** READs answered with bytes since launch, every Fantasy CFW (diagnostics, tests) */
    val reads: Long get() = nativeReads()

    @JvmStatic private external fun nativeInit(): Boolean
    @JvmStatic private external fun nativeSetFolder(present: Boolean)
    @JvmStatic private external fun nativeChanged()
    @JvmStatic private external fun nativeChangedCount(): Int
    @JvmStatic private external fun nativeReads(): Long
}
