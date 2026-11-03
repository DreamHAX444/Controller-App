package com.livetracker.controller.core.tracker.camera.domain

/**
 * Domain errors associated with camera operations and stream lifecycle.
 */
sealed interface CameraError {
    val message: String

    data class UnsupportedCapability(
        override val message: String = "Tracker device does not support camera capability"
    ) : CameraError

    data class PermissionDenied(
        override val message: String = "Camera service permission is denied"
    ) : CameraError

    data class DeviceNotConnected(
        override val message: String = "Tracker device is not connected"
    ) : CameraError

    data class DeviceNotFound(
        override val message: String = "Tracker device was not found"
    ) : CameraError

    data class InvalidState(
        override val message: String
    ) : CameraError

    data class CommandFailure(
        override val message: String,
        val cause: Throwable? = null
    ) : CameraError

    data class Timeout(
        override val message: String = "Camera command timed out"
    ) : CameraError
}
