package com.livetracker.controller.core.tracker.camera.domain

import kotlinx.serialization.Serializable

/**
 * Hardware camera sensor discovered on a remote tracker device.
 */
@Serializable
data class DiscoveredCameraInfo(
    val cameraId: String,
    val displayName: String,
    val facing: CameraFacing,
    val lensType: String = "PRIMARY",
    val isLogicalMultiCamera: Boolean = false,
    val physicalCameraIds: List<String> = emptyList(),
    val supportedResolutions: List<String> = emptyList()
) {
    companion object {
        val DEFAULT_CAMERAS = listOf(
            DiscoveredCameraInfo(
                cameraId = "0",
                displayName = "Back Wide 1x (0)",
                facing = CameraFacing.BACK,
                lensType = "PRIMARY"
            ),
            DiscoveredCameraInfo(
                cameraId = "2",
                displayName = "Back Ultra-Wide 0.5x (2)",
                facing = CameraFacing.BACK,
                lensType = "ULTRA_WIDE"
            ),
            DiscoveredCameraInfo(
                cameraId = "3",
                displayName = "Back Telephoto 3x (3)",
                facing = CameraFacing.BACK,
                lensType = "TELEPHOTO"
            ),
            DiscoveredCameraInfo(
                cameraId = "1",
                displayName = "Front Primary (1)",
                facing = CameraFacing.FRONT,
                lensType = "PRIMARY"
            )
        )
    }
}
