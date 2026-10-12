package com.livetracker.controller.core.update.domain

import com.livetracker.controller.core.update.installer.InstallationRequestResult
import com.livetracker.controller.core.update.model.UpdateInfo
import com.livetracker.controller.core.update.model.UpdateState
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain repository coordinating the update lifecycle: checking, downloading, verifying, and installing.
 */
interface UpdateRepository {
    val updateState: StateFlow<UpdateState>
    val currentVersionCode: Long
    val currentVersionName: String
    val appId: String

    /**
     * Checks the online release endpoint for newer versions.
     * If [force] is true, ignores previous user dismissal.
     */
    suspend fun checkForUpdates(force: Boolean = false): Result<UpdateInfo>

    /**
     * Begins downloading the available update artifact.
     */
    suspend fun startDownload(): Result<Unit>

    /**
     * Cancels any active download operation.
     */
    fun cancelDownload()

    /**
     * Commits the verified downloaded update artifact to Android's package installer.
     */
    suspend fun installDownloadedUpdate(): Result<InstallationRequestResult>

    /**
     * Retries the failed operation (checking, downloading, or installing).
     */
    suspend fun retry()

    /**
     * Dismisses an optional update prompt until the next release.
     */
    suspend fun dismissUpdate(versionCode: Long)

    /**
     * Resets the repository state to Idle.
     */
    fun resetState()
}
