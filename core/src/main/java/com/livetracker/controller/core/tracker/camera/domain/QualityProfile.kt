package com.livetracker.controller.core.tracker.camera.domain

/**
 * Quality profile combining target resolution, target frame rate, and optional target bitrate.
 */
enum class QualityProfile(
    val resolution: CameraResolution,
    val targetFps: Int,
    val targetBitrateKbps: Int? = null
) {
    LOW(
        resolution = CameraResolution.VGA,
        targetFps = 15,
        targetBitrateKbps = 500
    ),
    MEDIUM(
        resolution = CameraResolution.HD_720P,
        targetFps = 24,
        targetBitrateKbps = 1500
    ),
    HIGH(
        resolution = CameraResolution.FULL_HD_1080P,
        targetFps = 30,
        targetBitrateKbps = 3000
    ),
    AUTO(
        resolution = CameraResolution.HD_720P,
        targetFps = 30,
        targetBitrateKbps = null // Adaptive
    );

    fun toCaptureConfig(
        facing: CameraFacing = CameraFacing.BACK,
        cameraId: String? = null
    ): CameraCaptureConfig {
        return CameraCaptureConfig(
            resolution = resolution,
            fps = targetFps,
            bitrateKbps = targetBitrateKbps,
            facing = facing,
            cameraId = cameraId
        )
    }
}
