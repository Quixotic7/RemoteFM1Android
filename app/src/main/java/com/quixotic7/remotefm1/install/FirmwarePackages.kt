// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Install/FirmwarePackages.swift; changes: Kotlin, File.
// A RemoteFM1 package, downloaded and checked before anything reaches the FM-1: its SHA-256 must be releases.json's
// (else refused and not kept), FM1Kit must read it as an FM-1 package (FwscPackage: header, chip, every part's
// checksum, a firmware and a loader) and its product must be releases.json's identity. A checked package is kept in
// the cache (packages/<file>) and checked again whenever it is used.
package com.quixotic7.remotefm1.install

import com.quixotic7.remotefm1.install.fm1kit.FwscFailure
import com.quixotic7.remotefm1.install.fm1kit.FwscPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** a package that passed every check */
class VerifiedPackage(val ref: FirmwarePackageRef, val pkg: FwscPackage) {
    val size: Int get() = pkg.fileBytes.size
}

/** why a package was refused */
sealed class PackageProblem : Exception() {
    data class ChecksumMismatch(val file: String, val expected: String, val got: String) : PackageProblem()
    data class IdentityMismatch(val file: String, val expected: String, val got: String) : PackageProblem()
    data class Unreadable(val file: String, val why: String) : PackageProblem()

    val description: String
        get() = when (this) {
            is ChecksumMismatch -> "$file isn't the file the RemoteFM1 site lists (its SHA-256 differs), so it was not " +
                "installed. Try again; if it keeps happening, the download is being changed on its way."
            is IdentityMismatch -> "$file installs as $got, not $expected as the RemoteFM1 site says, so it was not installed."
            is Unreadable -> "$file can't be installed: $why"
        }

    override val message: String get() = description
}

/** Downloads and checks packages (FirmwareInstaller uses it). */
class FirmwarePackages(val directory: File, private val fetcher: InstallerFetching = HttpFetcher()) {
    /** from the cache if a checked copy is there, else downloaded; checked either way */
    suspend fun fetch(ref: FirmwarePackageRef): VerifiedPackage {
        val file = File(directory, ref.file)
        withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }?.let { data ->
            runCatching { verify(data, ref) }.getOrNull()?.let { return it }
        }
        val data = fetcher.data(RemoteFM1Releases.packageURL(ref.file))
        val ok = verify(data, ref)
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val tmp = File(directory, ref.file + ".tmp")
                tmp.writeBytes(data)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
        return ok
    }

    companion object {
        /** the checks, in order: SHA-256 (before FM1Kit reads a byte), FM1Kit's package checks, the identity */
        fun verify(data: ByteArray, ref: FirmwarePackageRef): VerifiedPackage {
            val sha = FwscPackage.sha256Hex(data)
            if (sha != ref.sha256) throw PackageProblem.ChecksumMismatch(ref.file, ref.sha256, sha)
            val pkg = try {
                FwscPackage.read(data)
            } catch (e: FwscFailure) {
                throw PackageProblem.Unreadable(ref.file, e.description)
            }
            if (!pkg.isFirmware) throw PackageProblem.Unreadable(ref.file, "it holds no firmware.")
            if (pkg.product != ref.identity) throw PackageProblem.IdentityMismatch(ref.file, ref.identity, pkg.product)
            return VerifiedPackage(ref, pkg)
        }
    }
}
