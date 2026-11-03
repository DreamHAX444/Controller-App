package com.livetracker.controller.core.tracker.camera.domain

/**
 * Requested capture configuration sent to the tracker device.
 */
data class CameraCaptureConfig(
    val resolution: CameraResolution,
    val fps: Int,
    val bitrateKbps: Int? = null,
    val facing: CameraFacing = CameraFacing.BACK,
    val cameraId: String? = null
) {
    init {
        require(fps > 0) { "Requested FPS must be strictly positive, got: $fps" }
        if (bitrateKbps != null) {
            require(bitrateKbps > 0) { "Requested bitrate must be strictly positive if specified, got: $bitrateKbps" }
        }
    }

    companion object {
        val DEFAULT: CameraCaptureConfig = QualityProfile.MEDIUM.toCaptureConfig()
    }
}

/**
 * Actual capture configuration reported by the remote tracker stream.
 *
 * CRITICAL CONSTRAINT:
 * Values must reflect real telemetry or null. Telemetry, FPS, bitrate, or
 * capture resolution must NEVER be fabricated.
 */
data class ActualCaptureConfig(
    val resolution: CameraResolution,
    val fps: Double,
    val bitrateKbps: Int? = null,
    val facing: CameraFacing? = null,
    val cameraId: String? = null
) {
    init {
        require(fps >= 0.0) { "Actual FPS cannot be negative, got: $fps" }
        if (bitrateKbps != null) {
            require(bitrateKbps >= 0) { "Actual bitrate cannot be negative if specified, got: $bitrateKbps" }
        }
    }
}
