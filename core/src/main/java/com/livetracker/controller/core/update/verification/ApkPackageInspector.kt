package com.livetracker.controller.core.update.verification

import java.io.File

/**
 * Metadata extracted from an APK file's binary manifest and signature.
 */
data class ApkArchiveMetadata(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val signingCertificatesSha256: List<String>
)

/**
 * Abstraction for extracting low-level Android package archive metadata and signature hashes from an APK file.
 */
interface ApkPackageInspector {
    /**
     * Inspects the APK file and returns parsed metadata, or null if invalid/unparseable.
     */
    fun inspectApk(apkFile: File): ApkArchiveMetadata?
}
