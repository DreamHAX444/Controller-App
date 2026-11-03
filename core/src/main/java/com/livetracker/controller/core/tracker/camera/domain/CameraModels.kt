package com.livetracker.controller.core.tracker.camera.domain

/**
 * Camera hardware facing on the tracker device.
 */
enum class CameraFacing {
    FRONT,
    BACK
}

/**
 * Standard 2D pixel resolution for video capture.
 */
data class CameraResolution(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0) { "Width must be positive, got: $width" }
        require(height > 0) { "Height must be positive, got: $height" }
    }

    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()

    override fun toString(): String = "${width}x${height}"

    companion object {
        val QVGA = CameraResolution(320, 240)
        val VGA = CameraResolution(640, 480)
        val HD_720P = CameraResolution(1280, 720)
        val FULL_HD_1080P = CameraResolution(1920, 1080)

        val SUPPORTED_RESOLUTIONS: List<CameraResolution> = listOf(
            QVGA,
            VGA,
            HD_720P,
            FULL_HD_1080P
        )
    }
}

/**
 * Explicit lifecycle states of a tracker's camera stream.
 */
enum class StreamLifecycleState {
    /**
     * Camera is idle; no active capture or streaming session.
     */
    IDLE,

    /**
     * Stream initialization or negotiation is in progress.
     */
    STARTING,

    /**
     * Camera is actively capturing and streaming frames.
     */
    STREAMING,

    /**
     * Active camera switch (e.g. FRONT to BACK) is in progress.
     */
    SWITCHING,

    /**
     * Camera stop request is in progress.
     */
    STOPPING,

    /**
     * An unrecoverable error occurred in the camera stream or during command negotiation.
     */
    ERROR;

    val isActive: Boolean
        get() = this == STREAMING || this == SWITCHING

    val canStart: Boolean
        get() = this == IDLE || this == ERROR

    val canStop: Boolean
        get() = this == STARTING || this == STREAMING || this == SWITCHING

    val canSwitch: Boolean
        get() = this == STREAMING
}
