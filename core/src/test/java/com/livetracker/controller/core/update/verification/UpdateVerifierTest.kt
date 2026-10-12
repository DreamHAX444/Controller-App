package com.livetracker.controller.core.update.verification

import com.livetracker.controller.core.update.model.UpdateFailureReason
import com.livetracker.controller.core.update.model.UpdateManifest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class UpdateVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val appId = "com.livetracker.controller"
    private val currentVersion = 1L
    private lateinit var testApk: File
    private lateinit var testApkSha256: String
    private var testApkSize: Long = 0L

    @Before
    fun setUp() {
        testApk = tempFolder.newFile("test_release.apk")
        val content = "FAKE_APK_BINARY_PAYLOAD_FOR_UNIT_TESTING".toByteArray()
        testApk.writeBytes(content)
        testApkSize = content.size.toLong()

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content)
        val sb = StringBuilder()
        for (b in hash) sb.append(String.format("%02x", b.toInt() and 0xFF))
        testApkSha256 = sb.toString().lowercase()
    }

    private fun createManifest(
        versionCode: Long = 2L,
        appId: String = this.appId,
        sha256: String = testApkSha256,
        sizeBytes: Long = testApkSize,
        certSha256: String? = null
    ): UpdateManifest {
        return UpdateManifest(
            schemaVersion = 1,
            appId = appId,
            channel = "stable",
            versionCode = versionCode,
            versionName = "2.0",
            minimumSupportedVersionCode = 1,
            mandatory = false,
            publishedAt = "2026-10-12T10:00:00Z",
            releaseNotes = "Release notes",
            apkUrl = "https://example.com/app.apk",
            apkSizeBytes = sizeBytes,
            sha256 = sha256,
            signingCertSha256 = certSha256
        )
    }

    @Test
    fun validApkAndManifest_returnsSuccess() = runTest {
        val verifier = DefaultUpdateVerifier()
        val manifest = createManifest()

        val result = verifier.verifyApk(
            apkFile = testApk,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = currentVersion
        )

        assertTrue("Expected verification success but got $result", result is VerificationResult.Success)
    }

    @Test
    fun missingFile_failsWithDownloadInterrupted() = runTest {
        val verifier = DefaultUpdateVerifier()
        val nonExistent = File(tempFolder.root, "non_existent.apk")
        val manifest = createManifest()

        val result = verifier.verifyApk(
            apkFile = nonExistent,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = currentVersion
        )

        assertTrue(result is VerificationResult.Failure)
        assertEquals(UpdateFailureReason.DOWNLOAD_INTERRUPTED, (result as VerificationResult.Failure).reason)
    }

    @Test
    fun mismatchedSize_failsWithFileSizeMismatch() = runTest {
        val verifier = DefaultUpdateVerifier()
        val manifest = createManifest(sizeBytes = testApkSize + 100)

        val result = verifier.verifyApk(
            apkFile = testApk,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = currentVersion
        )

        assertTrue(result is VerificationResult.Failure)
        assertEquals(UpdateFailureReason.FILE_SIZE_MISMATCH, (result as VerificationResult.Failure).reason)
    }

    @Test
    fun mismatchedAppId_failsWithManifestIncompatibleAppId() = runTest {
        val verifier = DefaultUpdateVerifier()
        val manifest = createManifest(appId = "com.other.app")

        val result = verifier.verifyApk(
            apkFile = testApk,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = currentVersion
        )

        assertTrue(result is VerificationResult.Failure)
        assertEquals(UpdateFailureReason.MANIFEST_INCOMPATIBLE_APP_ID, (result as VerificationResult.Failure).reason)
    }

    @Test
    fun versionDowngradeOrEqual_failsWithVersionDowngradeRejected() = runTest {
        val verifier = DefaultUpdateVerifier()
        val manifest = createManifest(versionCode = 1L) // equal to currentVersion

        val result = verifier.verifyApk(
            apkFile = testApk,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = 1L
        )

        assertTrue(result is VerificationResult.Failure)
        assertEquals(UpdateFailureReason.VERSION_DOWNGRADE_REJECTED, (result as VerificationResult.Failure).reason)
    }

    @Test
    fun corruptedChecksum_failsWithChecksumMismatch() = runTest {
        val verifier = DefaultUpdateVerifier()
        val manifest = createManifest(sha256 = "0000000000000000000000000000000000000000000000000000000000000000")

        val result = verifier.verifyApk(
            apkFile = testApk,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = currentVersion
        )

        assertTrue(result is VerificationResult.Failure)
        assertEquals(UpdateFailureReason.CHECKSUM_MISMATCH, (result as VerificationResult.Failure).reason)
    }

    @Test
    fun packageInspectorCertMismatch_failsWithSignatureVerificationFailed() = runTest {
        val fakeInspector = object : ApkPackageInspector {
            override fun inspectApk(apkFile: File): ApkArchiveMetadata {
                return ApkArchiveMetadata(
                    packageName = appId,
                    versionCode = 2L,
                    versionName = "2.0",
                    minSdk = 26,
                    signingCertificatesSha256 = listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                )
            }
        }
        val verifier = DefaultUpdateVerifier(packageInspector = fakeInspector)
        val manifest = createManifest(certSha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

        val result = verifier.verifyApk(
            apkFile = testApk,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = currentVersion
        )

        assertTrue(result is VerificationResult.Failure)
        assertEquals(UpdateFailureReason.SIGNATURE_VERIFICATION_FAILED, (result as VerificationResult.Failure).reason)
    }

    @Test
    fun packageInspectorCertMatch_succeeds() = runTest {
        val trustedCert = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff"
        val fakeInspector = object : ApkPackageInspector {
            override fun inspectApk(apkFile: File): ApkArchiveMetadata {
                return ApkArchiveMetadata(
                    packageName = appId,
                    versionCode = 2L,
                    versionName = "2.0",
                    minSdk = 26,
                    signingCertificatesSha256 = listOf(trustedCert)
                )
            }
        }
        val verifier = DefaultUpdateVerifier(packageInspector = fakeInspector)
        val manifest = createManifest(certSha256 = trustedCert)

        val result = verifier.verifyApk(
            apkFile = testApk,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = currentVersion
        )

        assertTrue(result is VerificationResult.Success)
    }
}
