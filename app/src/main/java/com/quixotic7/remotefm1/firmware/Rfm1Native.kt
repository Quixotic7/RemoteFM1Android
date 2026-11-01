// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// The .rfm1 loader's checks for Kotlin: native/rfmcore/RFMWasmCore.cpp (iOS's, through rfm_wasm_jni.cpp): every check
// of the format (FM1VST docs/RFM1-FORMAT.md section 7 step 1) and one instantiation in wasm3. Loading a file into an
// engine is RfmEngine.loadWasm.
package com.quixotic7.remotefm1.firmware

/** what the loader says of a valid file (RFMWasm.h rfmw_info_t) */
data class Rfm1Info(
    val id: String, val name: String, val version: String, val family: String, val license: String,
    val sha256: String, val abi: Int, val flashSize: Int, val memoryBytes: Int, val fileSize: Long, val loadUs: Long,
    // a Fantasy CFW: its manifest's "virtual"; it imports rfm1_host_1; its "folders" ("audio:r code:r data:rw")
    val isVirtual: Boolean = false, val usesFolder: Boolean = false, val folders: String = "",
)

/** the loader, behind an interface so the JVM tests can stand in for it */
interface Rfm1Checker {
    /** the file checked: its info, or the one-line reason it was refused (Result.failure's message) */
    fun check(path: String): Result<Rfm1Info>
    /** "manifest", "credits", "license.<n>": "" none */
    fun text(path: String, what: String): String
}

class Rfm1Refused(reason: String) : Exception(reason)

object Rfm1Native : Rfm1Checker {
    init {
        System.loadLibrary("rfmcore")
    }

    override fun check(path: String): Result<Rfm1Info> {
        val j = MiniJson.parse(nativeCheck(path)).obj() ?: return Result.failure(Rfm1Refused("not a valid .rfm1 file"))
        if (j.int("ok") != 1L) return Result.failure(Rfm1Refused(j.str("error") ?: "not a valid .rfm1 file"))
        return Result.success(Rfm1Info(
            id = j.str("id") ?: "", name = j.str("name") ?: "", version = j.str("version") ?: "",
            family = j.str("family") ?: "", license = j.str("license") ?: "", sha256 = j.str("sha256") ?: "",
            abi = (j.int("abi") ?: 0).toInt(), flashSize = (j.int("flash_size") ?: 0).toInt(),
            memoryBytes = (j.int("memory_bytes") ?: 0).toInt(), fileSize = j.int("file_size") ?: 0,
            loadUs = j.int("load_us") ?: 0, isVirtual = j.int("virtual") == 1L, usesFolder = j.int("uses_folder") == 1L,
            folders = j.str("folders") ?: ""))
    }

    /**
     * The memory the next instances may have (RFMWasmCore.cpp refuses "needs N MB of memory" above it; 0: no check).
     * Set from [MemoryBudget] just before a load and cleared after, so a file's check on import or at launch never
     * depends on the memory free at that moment.
     */
    fun setMemoryBudget(bytes: Long) = nativeSetMemoryBudget(bytes)

    override fun text(path: String, what: String): String = nativeText(path, what)

    /** the file's SHA-256 without the format's checks ("" unreadable) */
    fun sha256(path: String): String = nativeSha256(path)

    @JvmStatic private external fun nativeCheck(path: String): String
    @JvmStatic private external fun nativeText(path: String, what: String): String
    @JvmStatic private external fun nativeSha256(path: String): String
    @JvmStatic private external fun nativeSetMemoryBudget(bytes: Long)
}
