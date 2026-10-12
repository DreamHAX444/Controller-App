package com.livetracker.controller.core.update.verification

import com.livetracker.controller.core.update.model.UpdateFailureReason
import com.livetracker.controller.core.update.model.UpdateManifest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Interface for verifying the integrity and security of a downloaded APK before handoff to package installer.
 */
interface UpdateVerifier {
    suspend fun verifyApk(
        apkFile: File,
        manifest: UpdateManifest,
        expectedAppId: String,
        currentVersionCode: Long,
        trustedCertSha256: String? = null
    ): VerificationResult
}

/**
 * Production implementation of [UpdateVerifier].
 * Verifies file existence, size, cryptographic SHA-256 checksum, package ID, version monotonicity,
 * and signing certificate fingerprints.
 */
class DefaultUpdateVerifier(
    private val packageInspector: ApkPackageInspector? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : UpdateVerifier {

    override suspend fun verifyApk(
        apkFile: File,
        manifest: UpdateManifest,
        expectedAppId: String,
        currentVersionCode: Long,
        trustedCertSha256: String?
    ): VerificationResult = withContext(ioDispatcher) {
        // 1. Existence and readability
        if (!apkFile.exists() || !apkFile.canRead()) {
            return@withContext VerificationResult.Failure(
                reason = UpdateFailureReason.DOWNLOAD_INTERRUPTED,
                details = "APK file does not exist or is unreadable at ${apkFile.absolutePath}"
            )
        }

        // 2. Byte size check
        val actualSizeBytes = apkFile.length()
        if (actualSizeBytes <= 0) {
            return@withContext VerificationResult.Failure(
                reason = UpdateFailureReason.FILE_SIZE_MISMATCH,
                details = "Downloaded APK file is empty (0 bytes)"
            )
        }
        if (manifest.apkSizeBytes > 0 && actualSizeBytes != manifest.apkSizeBytes) {
            return@withContext VerificationResult.Failure(
                reason = UpdateFailureReason.FILE_SIZE_MISMATCH,
                details = "File size mismatch: actual $actualSizeBytes bytes, manifest expected ${manifest.apkSizeBytes} bytes"
            )
        }

        // 3. Manifest Application ID check
        if (manifest.appId != expectedAppId) {
            return@withContext VerificationResult.Failure(
                reason = UpdateFailureReason.MANIFEST_INCOMPATIBLE_APP_ID,
                details = "Manifest application ID '${manifest.appId}' does not match installed application ID '$expectedAppId'"
            )
        }

        // 4. Version code monotonicity check
        if (manifest.versionCode <= currentVersionCode) {
            return@withContext VerificationResult.Failure(
                reason = UpdateFailureReason.VERSION_DOWNGRADE_REJECTED,
                details = "Update version code ${manifest.versionCode} is not greater than installed version code $currentVersionCode"
            )
        }

        // 5. SHA-256 checksum calculation & constant-time comparison
        val calculatedSha256 = calculateSha256(apkFile)
        val expectedSha256 = manifest.sha256.trim().lowercase()
        if (!isEqualHex(calculatedSha256, expectedSha256)) {
            return@withContext VerificationResult.Failure(
                reason = UpdateFailureReason.CHECKSUM_MISMATCH,
                details = "SHA-256 checksum mismatch. Calculated: $calculatedSha256, Expected: $expectedSha256"
            )
        }

        // 6. Deep APK Package archive inspection (if inspector is available)
        if (packageInspector != null) {
            val archiveMeta = packageInspector.inspectApk(apkFile)
                ?: return@withContext VerificationResult.Failure(
                    reason = UpdateFailureReason.PACKAGE_IDENTITY_MISMATCH,
                    details = "APK file could not be parsed as a valid Android package archive"
                )

            if (archiveMeta.packageName != expectedAppId) {
                return@withContext VerificationResult.Failure(
                    reason = UpdateFailureReason.PACKAGE_IDENTITY_MISMATCH,
                    details = "APK package name '${archiveMeta.packageName}' does not match installed app ID '$expectedAppId'"
                )
            }

            if (archiveMeta.versionCode <= currentVersionCode) {
                return@withContext VerificationResult.Failure(
                    reason = UpdateFailureReason.VERSION_DOWNGRADE_REJECTED,
                    details = "APK binary declares version code ${archiveMeta.versionCode}, which is not newer than $currentVersionCode"
                )
            }

            // Verify signing certificate fingerprint
            val certToVerify = trustedCertSha256 ?: manifest.signingCertSha256
            if (!certToVerify.isNullOrBlank()) {
                val expectedCertClean = certToVerify.trim().replace(":", "").lowercase()
                val certMatches = archiveMeta.signingCertificatesSha256.any {
                    val actualClean = it.trim().replace(":", "").lowercase()
                    isEqualHex(actualClean, expectedCertClean)
                }
                if (!certMatches) {
                    return@withContext VerificationResult.Failure(
                        reason = UpdateFailureReason.SIGNATURE_VERIFICATION_FAILED,
                        details = "APK signing certificate mismatch. Expected fingerprint: $expectedCertClean"
                    )
                }
            }
        }

        VerificationResult.Success
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        val sb = StringBuilder(hashBytes.size * 2)
        for (b in hashBytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString().lowercase()
    }

    private fun isEqualHex(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        val bytesA = a.toByteArray(Charsets.US_ASCII)
        val bytesB = b.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(bytesA, bytesB)
    }
}
