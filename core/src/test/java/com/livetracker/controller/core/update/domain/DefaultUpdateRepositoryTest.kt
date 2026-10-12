package com.livetracker.controller.core.update.domain

import android.content.Intent
import com.livetracker.controller.core.update.downloader.UpdateDownloader
import com.livetracker.controller.core.update.installer.InstallationRequestResult
import com.livetracker.controller.core.update.installer.PackageInstallerEvent
import com.livetracker.controller.core.update.installer.PackageInstallerStatusBus
import com.livetracker.controller.core.update.installer.UpdateInstaller
import com.livetracker.controller.core.update.model.UpdateFailureReason
import com.livetracker.controller.core.update.model.UpdateManifest
import com.livetracker.controller.core.update.model.UpdateState
import com.livetracker.controller.core.update.provider.UpdateHttpException
import com.livetracker.controller.core.update.provider.UpdateNetworkException
import com.livetracker.controller.core.update.provider.UpdateProvider
import com.livetracker.controller.core.update.storage.InMemoryUpdatePreferencesRepository
import com.livetracker.controller.core.update.verification.UpdateVerifier
import com.livetracker.controller.core.update.verification.VerificationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultUpdateRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repoScope: CoroutineScope

    private lateinit var tempUpdatesDir: File
    private lateinit var preferences: InMemoryUpdatePreferencesRepository
    private lateinit var fakeProvider: FakeUpdateProvider
    private lateinit var fakeDownloader: FakeUpdateDownloader
    private lateinit var fakeVerifier: FakeUpdateVerifier
    private lateinit var fakeInstaller: FakeUpdateInstaller

    private val testAppId = "com.livetracker.controller"
    private val currentVersionCode = 1L
    private val currentVersionName = "1.0.0"
    private val manifestUrl = "https://example.com/update-manifest.json"

    private lateinit var repository: DefaultUpdateRepository

    @Before
    fun setUp() {
        repoScope = CoroutineScope(SupervisorJob() + testDispatcher)
        tempUpdatesDir = File(System.getProperty("java.io.tmpdir"), "trace_updates_test_${System.currentTimeMillis()}")
        tempUpdatesDir.mkdirs()

        preferences = InMemoryUpdatePreferencesRepository()
        fakeProvider = FakeUpdateProvider()
        fakeDownloader = FakeUpdateDownloader()
        fakeVerifier = FakeUpdateVerifier()
        fakeInstaller = FakeUpdateInstaller()

        repository = DefaultUpdateRepository(
            appId = testAppId,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            manifestUrl = manifestUrl,
            provider = fakeProvider,
            downloader = fakeDownloader,
            verifier = fakeVerifier,
            installer = fakeInstaller,
            preferences = preferences,
            updatesDirectory = tempUpdatesDir,
            scope = repoScope,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        tempUpdatesDir.deleteRecursively()
        repoScope.cancel()
    }

    private fun sampleManifest(
        versionCode: Long = 2L,
        versionName: String = "1.1",
        appId: String = testAppId,
        channel: String = "stable",
        mandatory: Boolean = false
    ): UpdateManifest {
        return UpdateManifest(
            schemaVersion = 1,
            appId = appId,
            channel = channel,
            versionCode = versionCode,
            versionName = versionName,
            publishedAt = "2026-10-12T10:00:00Z",
            releaseNotes = "Test release notes",
            apkUrl = "https://example.com/trace.apk",
            apkSizeBytes = 1024L,
            sha256 = "681720ae3f40302523937472d83071dde9fb141deef3745ce6d2864d7a602a72",
            signingCertSha256 = "b1c5128cd81bd989c0dd05d5cd5eb8f332b7283428e28dfe81100d5a0ba606c2",
            mandatory = mandatory
        )
    }

    @Test
    fun checkForUpdates_newerVersionAvailable_emitsUpdateAvailable() = runTest(testDispatcher) {
        fakeProvider.result = Result.success(sampleManifest(versionCode = 2L))

        val result = repository.checkForUpdates(force = false)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertTrue(info.isUpdateAvailable)
        assertEquals(2L, info.latestVersionCode)

        val state = repository.updateState.value
        assertTrue(state is UpdateState.UpdateAvailable)
        assertEquals(2L, (state as UpdateState.UpdateAvailable).updateInfo.latestVersionCode)
    }

    @Test
    fun checkForUpdates_alreadyUpToDate_emitsUpToDate() = runTest(testDispatcher) {
        fakeProvider.result = Result.success(sampleManifest(versionCode = 1L))

        val result = repository.checkForUpdates(force = false)
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertFalse(info.isUpdateAvailable)

        val state = repository.updateState.value
        assertTrue(state is UpdateState.UpToDate)
        assertEquals(1L, (state as UpdateState.UpToDate).currentVersionCode)
    }

    @Test
    fun checkForUpdates_channelMismatch_failsWithIncompatibleChannel() = runTest(testDispatcher) {
        fakeProvider.result = Result.success(sampleManifest(channel = "beta"))

        val result = repository.checkForUpdates(force = false)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        val state = repository.updateState.value
        assertTrue(state is UpdateState.Failed)
        assertEquals(UpdateFailureReason.MANIFEST_INCOMPATIBLE_CHANNEL, (state as UpdateState.Failed).reason)
    }

    @Test
    fun checkForUpdates_appIdMismatch_failsWithIncompatibleAppId() = runTest(testDispatcher) {
        fakeProvider.result = Result.success(sampleManifest(appId = "com.other.app"))

        val result = repository.checkForUpdates(force = false)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        val state = repository.updateState.value
        assertTrue(state is UpdateState.Failed)
        assertEquals(UpdateFailureReason.MANIFEST_INCOMPATIBLE_APP_ID, (state as UpdateState.Failed).reason)
    }

    @Test
    fun checkForUpdates_networkFailure_failsWithNetworkReason() = runTest(testDispatcher) {
        fakeProvider.result = Result.failure(UpdateNetworkException("No connection"))

        val result = repository.checkForUpdates(force = false)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        val state = repository.updateState.value
        assertTrue(state is UpdateState.Failed)
        assertEquals(UpdateFailureReason.NETWORK_UNAVAILABLE, (state as UpdateState.Failed).reason)
        assertTrue((state as UpdateState.Failed).canRetry)
    }

    @Test
    fun checkForUpdates_http404ManifestNotFound_failsWithResourceNotFoundReason() = runTest(testDispatcher) {
        fakeProvider.result = Result.failure(
            UpdateHttpException(
                statusCode = 404,
                failureReason = UpdateFailureReason.RESOURCE_NOT_FOUND,
                message = "Update manifest not found (HTTP 404) at https://example.com/manifest.json"
            )
        )

        val result = repository.checkForUpdates(force = false)
        advanceUntilIdle()

        assertTrue(result.isFailure)
        val state = repository.updateState.value
        assertTrue(state is UpdateState.Failed)
        val failed = state as UpdateState.Failed
        assertEquals(UpdateFailureReason.RESOURCE_NOT_FOUND, failed.reason)
        assertTrue(failed.message.contains("HTTP 404"))
        assertTrue(failed.canRetry)
    }

    @Test
    fun checkForUpdates_dismissedVersionNotForced_emitsUpToDateUntilForced() = runTest(testDispatcher) {
        preferences.setDismissedVersionCode(2L)
        fakeProvider.result = Result.success(sampleManifest(versionCode = 2L, mandatory = false))

        // Normal check honors dismissal
        repository.checkForUpdates(force = false)
        advanceUntilIdle()
        assertTrue(repository.updateState.value is UpdateState.UpToDate)

        // Force check overrides dismissal
        repository.checkForUpdates(force = true)
        advanceUntilIdle()
        assertTrue(repository.updateState.value is UpdateState.UpdateAvailable)
    }

    @Test
    fun startDownload_progressAndSuccess_transitionsToReadyToInstall() = runTest(testDispatcher) {
        val manifest = sampleManifest(versionCode = 2L)
        fakeProvider.result = Result.success(manifest)
        repository.checkForUpdates(force = false)
        advanceUntilIdle()

        fakeDownloader.downloadResultProvider = { targetFile ->
            targetFile.writeText("fake apk binary")
            Result.success(targetFile)
        }
        fakeVerifier.verificationResult = VerificationResult.Success

        val downloadResult = repository.startDownload()
        advanceUntilIdle()

        assertTrue(downloadResult.isSuccess)
        val state = repository.updateState.value
        assertTrue(state is UpdateState.ReadyToInstall)
        val ready = state as UpdateState.ReadyToInstall
        assertEquals(2L, ready.updateInfo.latestVersionCode)
        assertTrue(File(ready.apkFilePath).exists())
    }

    @Test
    fun startDownload_verificationFailure_deletesFileAndFails() = runTest(testDispatcher) {
        val manifest = sampleManifest(versionCode = 2L)
        fakeProvider.result = Result.success(manifest)
        repository.checkForUpdates(force = false)
        advanceUntilIdle()

        fakeDownloader.downloadResultProvider = { targetFile ->
            targetFile.writeText("corrupted apk binary")
            Result.success(targetFile)
        }
        fakeVerifier.verificationResult = VerificationResult.Failure(
            reason = UpdateFailureReason.CHECKSUM_MISMATCH,
            details = "SHA-256 mismatch"
        )

        val downloadResult = repository.startDownload()
        advanceUntilIdle()

        assertTrue(downloadResult.isFailure)
        val state = repository.updateState.value
        assertTrue(state is UpdateState.Failed)
        assertEquals(UpdateFailureReason.CHECKSUM_MISMATCH, (state as UpdateState.Failed).reason)

        // Target APK file must be deleted on failure
        val targetApk = File(tempUpdatesDir, "trace_update_2.apk")
        assertFalse(targetApk.exists())
    }

    @Test
    fun startDownload_cancellation_transitionsToCancelled() = runTest(testDispatcher) {
        val manifest = sampleManifest(versionCode = 2L)
        fakeProvider.result = Result.success(manifest)
        repository.checkForUpdates(force = false)
        advanceUntilIdle()

        fakeDownloader.downloadResultProvider = {
            Result.failure(CancellationException("Download cancelled by user"))
        }

        val result = repository.startDownload()
        advanceUntilIdle()

        assertTrue(result.isFailure)
        assertTrue(repository.updateState.value is UpdateState.Cancelled)
    }

    @Test
    fun installDownloadedUpdate_requiresPermission_failsWithPermissionDeniedReason() = runTest(testDispatcher) {
        val manifest = sampleManifest(versionCode = 2L)
        fakeProvider.result = Result.success(manifest)
        repository.checkForUpdates(force = false)
        advanceUntilIdle()

        fakeDownloader.downloadResultProvider = { targetFile ->
            targetFile.writeText("fake apk binary")
            Result.success(targetFile)
        }
        fakeVerifier.verificationResult = VerificationResult.Success
        repository.startDownload()
        advanceUntilIdle()

        fakeInstaller.installResult = Result.success(InstallationRequestResult.RequiresSettingsPermission())

        repository.installDownloadedUpdate()
        advanceUntilIdle()

        val state = repository.updateState.value
        assertTrue(state is UpdateState.Failed)
        assertEquals(UpdateFailureReason.INSTALL_PERMISSION_DENIED, (state as UpdateState.Failed).reason)
    }

    @Test
    fun installDownloadedUpdate_systemConfirmationLaunched_transitionsToAwaitingConfirmation() = runTest(testDispatcher) {
        val manifest = sampleManifest(versionCode = 2L)
        fakeProvider.result = Result.success(manifest)
        repository.checkForUpdates(force = false)
        advanceUntilIdle()

        fakeDownloader.downloadResultProvider = { targetFile ->
            targetFile.writeText("fake apk binary")
            Result.success(targetFile)
        }
        fakeVerifier.verificationResult = VerificationResult.Success
        repository.startDownload()
        advanceUntilIdle()

        fakeInstaller.installResult = Result.success(InstallationRequestResult.SystemConfirmationLaunched)

        repository.installDownloadedUpdate()
        advanceUntilIdle()

        val state = repository.updateState.value
        assertTrue(state is UpdateState.AwaitingSystemConfirmation)
    }

    @Test
    fun packageInstallerBroadcast_successEvent_transitionsToInstalled() = runTest(testDispatcher) {
        val manifest = sampleManifest(versionCode = 2L)
        fakeProvider.result = Result.success(manifest)
        repository.checkForUpdates(force = false)
        advanceUntilIdle()

        // Broadcast success event
        PackageInstallerStatusBus.emitEvent(PackageInstallerEvent.Success(sessionId = 1))
        advanceUntilIdle()

        val state = repository.updateState.value
        assertTrue(state is UpdateState.Installed)
        val installed = state as UpdateState.Installed
        assertEquals(2L, installed.installedVersionCode)
    }

    @Test
    fun dismissUpdate_resetsAvailableStateToIdle() = runTest(testDispatcher) {
        val manifest = sampleManifest(versionCode = 2L)
        fakeProvider.result = Result.success(manifest)
        repository.checkForUpdates(force = false)
        advanceUntilIdle()

        assertTrue(repository.updateState.value is UpdateState.UpdateAvailable)

        repository.dismissUpdate(2L)
        advanceUntilIdle()

        assertEquals(UpdateState.Idle, repository.updateState.value)
        assertEquals(2L, preferences.dismissedVersionCode.value)
    }

    // --- Fakes ---

    private class FakeUpdateProvider : UpdateProvider {
        var result: Result<UpdateManifest> = Result.failure(IllegalStateException("Unconfigured"))
        override suspend fun fetchManifest(manifestUrl: String): Result<UpdateManifest> = result
    }

    private class FakeUpdateDownloader : UpdateDownloader {
        var downloadResultProvider: (File) -> Result<File> = { targetFile ->
            targetFile.writeText("fake")
            Result.success(targetFile)
        }
        var wasCancelled = false

        override suspend fun downloadApk(
            url: String,
            targetFile: File,
            expectedSizeBytes: Long,
            onProgress: (bytesDownloaded: Long, totalBytes: Long, progressPercent: Int) -> Unit
        ): Result<File> {
            onProgress(expectedSizeBytes / 2, expectedSizeBytes, 50)
            onProgress(expectedSizeBytes, expectedSizeBytes, 100)
            return downloadResultProvider(targetFile)
        }

        override fun cancel() {
            wasCancelled = true
        }
    }

    private class FakeUpdateVerifier : UpdateVerifier {
        var verificationResult: VerificationResult = VerificationResult.Success

        override suspend fun verifyApk(
            apkFile: File,
            manifest: UpdateManifest,
            expectedAppId: String,
            currentVersionCode: Long,
            trustedCertSha256: String?
        ): VerificationResult = verificationResult
    }

    private class FakeUpdateInstaller : UpdateInstaller {
        var installResult: Result<InstallationRequestResult> = Result.success(InstallationRequestResult.SystemConfirmationLaunched)
        var canInstall: Boolean = true

        override fun canRequestPackageInstalls(): Boolean = canInstall
        override fun getManageUnknownAppSourcesIntent(): Intent? = null
        override suspend fun installApk(apkFile: File): Result<InstallationRequestResult> = installResult
    }
}
