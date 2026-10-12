package com.livetracker.controller.core.update.verification

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Android implementation of [ApkPackageInspector] extracting metadata and certificate fingerprints
 * via Android's PackageManager.
 */
class DefaultAndroidApkInspector(
    private val context: Context
) : ApkPackageInspector {

    override fun inspectApk(apkFile: File): ApkArchiveMetadata? {
        return try {
            val packageManager = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

            val packageInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return null

            val packageName = packageInfo.packageName ?: return null
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            val versionName = packageInfo.versionName ?: ""
            val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                packageInfo.applicationInfo?.minSdkVersion ?: 26
            } else {
                26
            }

            val certFingerprints = mutableListOf<String>()
            val digest = MessageDigest.getInstance("SHA-256")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo
                val signatures = if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else null

                signatures?.forEach { sig ->
                    val hash = digest.digest(sig.toByteArray())
                    certFingerprints.add(toHexString(hash))
                }
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures?.forEach { sig ->
                    val hash = digest.digest(sig.toByteArray())
                    certFingerprints.add(toHexString(hash))
                }
            }

            ApkArchiveMetadata(
                packageName = packageName,
                versionCode = versionCode,
                versionName = versionName,
                minSdk = minSdk,
                signingCertificatesSha256 = certFingerprints
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun toHexString(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString().lowercase()
    }
}
