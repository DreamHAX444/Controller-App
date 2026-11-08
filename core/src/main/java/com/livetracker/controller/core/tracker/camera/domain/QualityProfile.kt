package com.livetracker.controller.core.tracker.camera.domain

/**
 * Quality profile combining target resolution, target frame rate, and optional target bitrate.
 */
enum class QualityProfile(
    val resolution: CameraResolution,
    val targetFps: Int,
    val targetBitrateKbps: Int? = null,
    val label: String = ""
) {
    LOW(
        resolution = CameraResolution.VGA,
        targetFps = 15,
        targetBitrateKbps = 500,
        label = "LOW"
    ),
    MEDIUM(
        resolution = CameraResolution.HD_720P,
        targetFps = 24,
        targetBitrateKbps = 1500,
        label = "MEDIUM"
    ),
    HIGH(
        resolution = CameraResolution.FULL_HD_1080P,
        targetFps = 30,
        targetBitrateKbps = 3000,
        label = "HIGH"
    ),
    FPS_60(
        resolution = CameraResolution.FULL_HD_1080P,
        targetFps = 60,
        targetBitrateKbps = 6000,
        label = "60 FPS"
    ),
    UHD_4K(
        resolution = CameraResolution.ULTRA_HD_4K,
        targetFps = 30,
        targetBitrateKbps = 12000,
        label = "4K UHD"
    ),
    AUTO(
        resolution = CameraResolution.HD_720P,
        targetFps = 30,
        targetBitrateKbps = null, // Adaptive
        label = "AUTO"
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
