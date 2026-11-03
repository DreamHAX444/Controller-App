package com.livetracker.controller.core.tracker.camera.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

/**
 * Result returned by camera domain repository operations.
 *
 * Provides explicit, deterministic outcomes for:
 * - Success
 * - Unsupported devices
 * - Denied permissions
 * - Invalid lifecycle state transitions
 * - Command dispatch / runtime failures
 */
sealed interface CameraResult {

    data class Success(
        val state: TrackerCameraState
    ) : CameraResult

    data class UnsupportedDevice(
        val deviceId: TrackerId,
        val reason: String = "Tracker device does not support camera capability"
    ) : CameraResult

    data class PermissionDenied(
        val deviceId: TrackerId,
        val reason: String = "Camera service permission is denied"
    ) : CameraResult

    data class InvalidStateTransition(
        val currentState: StreamLifecycleState,
        val attemptedAction: String,
        val reason: String
    ) : CameraResult

    data class CommandFailed(
        val deviceId: TrackerId,
        val reason: String,
        val cause: Throwable? = null
    ) : CameraResult

    val isSuccess: Boolean
        get() = this is Success
}
