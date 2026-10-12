package com.livetracker.controller.core.update.domain

import com.livetracker.controller.core.update.downloader.UpdateDownloader
import com.livetracker.controller.core.update.installer.InstallationRequestResult
import com.livetracker.controller.core.update.installer.PackageInstallerEvent
import com.livetracker.controller.core.update.installer.PackageInstallerStatusBus
import com.livetracker.controller.core.update.installer.UpdateInstaller
import com.livetracker.controller.core.update.model.UpdateFailureReason
import com.livetracker.controller.core.update.model.UpdateInfo
import com.livetracker.controller.core.update.model.UpdateManifest
import com.livetracker.controller.core.update.model.UpdateState
import com.livetracker.controller.core.update.provider.UpdateHttpException
import com.livetracker.controller.core.update.provider.UpdateNetworkException
import com.livetracker.controller.core.update.provider.UpdateProvider
import com.livetracker.controller.core.update.provider.UpdateTimeoutException
import com.livetracker.controller.core.update.storage.UpdatePreferencesRepository
import com.livetracker.controller.core.update.verification.UpdateVerifier
import com.livetracker.controller.core.update.verification.VerificationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class DefaultUpdateRepository(
    override val appId: String,
    override val currentVersionCode: Long,
    override val currentVersionName: String,
    private val manifestUrl: String,
    private val provider: UpdateProvider,
    private val downloader: UpdateDownloader,
    private val verifier: UpdateVerifier,
    private val installer: UpdateInstaller,
    private val preferences: UpdatePreferencesRepository,
    private val updatesDirectory: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : UpdateRepository {

    private val mutex = Mutex()
    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private var activeManifest: UpdateManifest? = null
    private var downloadedApkFile: File? = null

    init {
        // Observe system PackageInstaller broadcast events
        scope.launch {
            PackageInstallerStatusBus.events.collect { event ->
                when (event) {
                    is PackageInstallerEvent.PendingUserActionLaunched -> {
                        val current = _updateState.value
                        if (current is UpdateState.Installing) {
                            _updateState.value = UpdateState.AwaitingSystemConfirmation(current.updateInfo)
                        }
                    }

                    is PackageInstallerEvent.Success -> {
                        val manifest = activeManifest
                        _updateState.value = UpdateState.Installed(
                            installedVersionCode = manifest?.versionCode ?: (currentVersionCode + 1),
                            installedVersionName = manifest?.versionName ?: currentVersionName
                        )
                    }

                    is PackageInstallerEvent.Failure -> {
                        _updateState.value = UpdateState.Failed(
                            reason = UpdateFailureReason.INSTALLATION_FAILED,
                            message = event.message,
                            canRetry = true
                        )
                    }
                }
            }
        }
    }

    override suspend fun checkForUpdates(force: Boolean): Result<UpdateInfo> = withContext(ioDispatcher) {
        mutex.withLock {
            val currentState = _updateState.value
            if (currentState is UpdateState.Checking || currentState is UpdateState.Downloading) {
                return@withContext Result.failure(IllegalStateException("Update operation is already in progress"))
            }

            _updateState.value = UpdateState.Checking

            val manifestResult = provider.fetchManifest(manifestUrl)
            if (manifestResult.isFailure) {
                val exception = manifestResult.exceptionOrNull()
                val (reason, message) = mapExceptionToFailure(exception)
                _updateState.value = UpdateState.Failed(reason, message, canRetry = true)
                return@withContext Result.failure(exception ?: IOException(message))
            }

            val manifest = manifestResult.getOrThrow()
            activeManifest = manifest

            // 1. Channel check
            val currentChannel = preferences.selectedChannel.value
            if (manifest.channel.isNotBlank() && manifest.channel != currentChannel) {
                val reason = UpdateFailureReason.MANIFEST_INCOMPATIBLE_CHANNEL
                val message = "Manifest release channel '${manifest.channel}' does not match client channel '$currentChannel'"
                _updateState.value = UpdateState.Failed(reason, message, canRetry = false)
                return@withContext Result.failure(IllegalStateException(message))
            }

            // 2. Application ID check
            if (manifest.appId != appId) {
                val reason = UpdateFailureReason.MANIFEST_INCOMPATIBLE_APP_ID
                val message = "Manifest target package '${manifest.appId}' does not match installed application '$appId'"
                _updateState.value = UpdateState.Failed(reason, message, canRetry = false)
                return@withContext Result.failure(IllegalStateException(message))
            }

            // 3. Version comparison
            val isNewer = manifest.versionCode > currentVersionCode
            val nowMs = System.currentTimeMillis()
            preferences.setLastCheckedEpochMs(nowMs)

            val updateInfo = UpdateInfo(
                isUpdateAvailable = isNewer,
                isMandatory = manifest.mandatory,
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName,
                latestVersionCode = manifest.versionCode,
                latestVersionName = manifest.versionName,
                releaseNotes = manifest.releaseNotes,
                apkSizeBytes = manifest.apkSizeBytes,
                publishedAt = manifest.publishedAt,
                manifest = manifest
            )

            if (!isNewer) {
                _updateState.value = UpdateState.UpToDate(
                    lastCheckedEpochMs = nowMs,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName
                )
                return@withContext Result.success(updateInfo)
            }

            // If newer, check if user dismissed this version (unless mandatory or force check)
            val dismissedVersion = preferences.dismissedVersionCode.value
            if (!force && !manifest.mandatory && dismissedVersion == manifest.versionCode) {
                _updateState.value = UpdateState.UpToDate(
                    lastCheckedEpochMs = nowMs,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName
                )
            } else {
                _updateState.value = UpdateState.UpdateAvailable(updateInfo)
            }

            Result.success(updateInfo)
        }
    }

    override suspend fun startDownload(): Result<Unit> = withContext(ioDispatcher) {
        val manifest = activeManifest
            ?: return@withContext Result.failure(IllegalStateException("No active update available to download"))

        val updateInfo = when (val state = _updateState.value) {
            is UpdateState.UpdateAvailable -> state.updateInfo
            is UpdateState.Failed -> {
                UpdateInfo(
                    isUpdateAvailable = true,
                    isMandatory = manifest.mandatory,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName,
                    latestVersionCode = manifest.versionCode,
                    latestVersionName = manifest.versionName,
                    releaseNotes = manifest.releaseNotes,
                    apkSizeBytes = manifest.apkSizeBytes,
                    publishedAt = manifest.publishedAt,
                    manifest = manifest
                )
            }
            else -> return@withContext Result.failure(IllegalStateException("Cannot start download in state ${_updateState.value}"))
        }

        if (!updatesDirectory.exists()) {
            updatesDirectory.mkdirs()
        }

        val targetApk = File(updatesDirectory, "trace_update_${manifest.versionCode}.apk")
        downloadedApkFile = targetApk

        // Check if cached file already matches SHA-256 and size
        if (targetApk.exists() && targetApk.length() == manifest.apkSizeBytes) {
            _updateState.value = UpdateState.Verifying(updateInfo)
            val cachedVerification = verifier.verifyApk(
                apkFile = targetApk,
                manifest = manifest,
                expectedAppId = appId,
                currentVersionCode = currentVersionCode,
                trustedCertSha256 = manifest.signingCertSha256
            )
            if (cachedVerification is VerificationResult.Success) {
                _updateState.value = UpdateState.ReadyToInstall(updateInfo, targetApk.absolutePath)
                return@withContext Result.success(Unit)
            } else {
                targetApk.delete()
            }
        }

        _updateState.value = UpdateState.Downloading(
            updateInfo = updateInfo,
            bytesDownloaded = 0,
            totalBytes = manifest.apkSizeBytes,
            progressPercent = 0
        )

        val downloadResult = downloader.downloadApk(
            url = manifest.apkUrl,
            targetFile = targetApk,
            expectedSizeBytes = manifest.apkSizeBytes,
            onProgress = { bytesDownloaded, totalBytes, progressPercent ->
                _updateState.value = UpdateState.Downloading(
                    updateInfo = updateInfo,
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    progressPercent = progressPercent
                )
            }
        )

        if (downloadResult.isFailure) {
            val ex = downloadResult.exceptionOrNull()
            if (ex is CancellationException) {
                _updateState.value = UpdateState.Cancelled
                return@withContext Result.failure(ex)
            }
            val (reason, message) = mapExceptionToFailure(ex)
            _updateState.value = UpdateState.Failed(reason, message, canRetry = true)
            return@withContext Result.failure(ex ?: IOException(message))
        }

        // Verification phase
        _updateState.value = UpdateState.Verifying(updateInfo)
        val verificationResult = verifier.verifyApk(
            apkFile = targetApk,
            manifest = manifest,
            expectedAppId = appId,
            currentVersionCode = currentVersionCode,
            trustedCertSha256 = manifest.signingCertSha256
        )

        when (verificationResult) {
            is VerificationResult.Success -> {
                _updateState.value = UpdateState.ReadyToInstall(updateInfo, targetApk.absolutePath)
                Result.success(Unit)
            }

            is VerificationResult.Failure -> {
                targetApk.delete()
                _updateState.value = UpdateState.Failed(
                    reason = verificationResult.reason,
                    message = verificationResult.details,
                    canRetry = true
                )
                Result.failure(SecurityException(verificationResult.details))
            }
        }
    }

    override fun cancelDownload() {
        downloader.cancel()
        _updateState.value = UpdateState.Cancelled
    }

    override suspend fun installDownloadedUpdate(): Result<InstallationRequestResult> = withContext(ioDispatcher) {
        val currentState = _updateState.value
        if (currentState !is UpdateState.ReadyToInstall) {
            return@withContext Result.failure(IllegalStateException("Cannot install update: state is $currentState"))
        }

        val apkFile = downloadedApkFile ?: File(currentState.apkFilePath)
        if (!apkFile.exists()) {
            _updateState.value = UpdateState.Failed(
                reason = UpdateFailureReason.DOWNLOAD_INTERRUPTED,
                message = "Downloaded APK file not found at ${apkFile.absolutePath}",
                canRetry = true
            )
            return@withContext Result.failure(IOException("Downloaded APK file not found"))
        }

        _updateState.value = UpdateState.Installing(currentState.updateInfo)

        val installResult = installer.installApk(apkFile)
        if (installResult.isFailure) {
            val ex = installResult.exceptionOrNull()
            _updateState.value = UpdateState.Failed(
                reason = UpdateFailureReason.INSTALLATION_FAILED,
                message = ex?.message ?: "Installation initiation failed",
                canRetry = true
            )
            return@withContext Result.failure(ex ?: IOException("Installation initiation failed"))
        }

        when (val reqResult = installResult.getOrThrow()) {
            is InstallationRequestResult.RequiresSettingsPermission -> {
                _updateState.value = UpdateState.Failed(
                    reason = UpdateFailureReason.INSTALL_PERMISSION_DENIED,
                    message = "Application requires permission to install packages from this source.",
                    canRetry = true
                )
            }

            is InstallationRequestResult.SystemConfirmationLaunched -> {
                _updateState.value = UpdateState.AwaitingSystemConfirmation(currentState.updateInfo)
            }

            is InstallationRequestResult.CompletedImmediately -> {
                _updateState.value = UpdateState.Installed(
                    installedVersionCode = currentState.updateInfo.latestVersionCode,
                    installedVersionName = currentState.updateInfo.latestVersionName
                )
            }
        }

        installResult
    }

    override suspend fun retry() {
        when (_updateState.value) {
            is UpdateState.Failed -> {
                if (activeManifest != null) {
                    startDownload()
                } else {
                    checkForUpdates(force = true)
                }
            }
            is UpdateState.Cancelled -> {
                if (activeManifest != null) {
                    startDownload()
                }
            }
            else -> checkForUpdates(force = true)
        }
    }

    override suspend fun dismissUpdate(versionCode: Long) {
        preferences.setDismissedVersionCode(versionCode)
        val current = _updateState.value
        if (current is UpdateState.UpdateAvailable && current.updateInfo.latestVersionCode == versionCode) {
            _updateState.value = UpdateState.Idle
        }
    }

    override fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    private fun mapExceptionToFailure(ex: Throwable?): Pair<UpdateFailureReason, String> {
        return when (ex) {
            is UpdateNetworkException -> UpdateFailureReason.NETWORK_UNAVAILABLE to (ex.message ?: "Network unavailable")
            is UpdateTimeoutException -> UpdateFailureReason.TIMEOUT to "Connection timed out"
            is UpdateHttpException -> ex.failureReason to (ex.message ?: "HTTP ${ex.statusCode} error")
            is SecurityException -> UpdateFailureReason.SIGNATURE_VERIFICATION_FAILED to (ex.message ?: "Security verification failed")
            else -> UpdateFailureReason.UNKNOWN to (ex?.message ?: "An unexpected error occurred")
        }
    }
}
