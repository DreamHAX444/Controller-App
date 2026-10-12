package com.livetracker.controller.core.update.model

/**
 * Deterministic lifecycle states for the update domain layer.
 */
sealed interface UpdateState {
    object Idle : UpdateState

    object Checking : UpdateState

    data class UpToDate(
        val lastCheckedEpochMs: Long,
        val currentVersionCode: Long,
        val currentVersionName: String
    ) : UpdateState

    data class UpdateAvailable(
        val updateInfo: UpdateInfo
    ) : UpdateState

    data class Downloading(
        val updateInfo: UpdateInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int
    ) : UpdateState

    data class DownloadPaused(
        val updateInfo: UpdateInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UpdateState

    data class Downloaded(
        val updateInfo: UpdateInfo,
        val apkFilePath: String
    ) : UpdateState

    data class Verifying(
        val updateInfo: UpdateInfo
    ) : UpdateState

    data class ReadyToInstall(
        val updateInfo: UpdateInfo,
        val apkFilePath: String
    ) : UpdateState

    data class AwaitingSystemConfirmation(
        val updateInfo: UpdateInfo
    ) : UpdateState

    data class Installing(
        val updateInfo: UpdateInfo
    ) : UpdateState

    data class Installed(
        val installedVersionCode: Long,
        val installedVersionName: String
    ) : UpdateState

    data class Failed(
        val reason: UpdateFailureReason,
        val message: String,
        val canRetry: Boolean
    ) : UpdateState

    object Cancelled : UpdateState
}
