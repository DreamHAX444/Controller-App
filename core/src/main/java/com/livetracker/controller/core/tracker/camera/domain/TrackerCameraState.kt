package com.livetracker.controller.core.tracker.camera.domain

import com.livetracker.controller.core.tracker.domain.TrackerId

/**
 * State representing a tracker device's camera stream.
 *
 * CRITICAL CONSTRAINT:
 * If [isSimulated] is true, [simulationLabel] must be set and never
 * disguised as a real camera stream. [actualConfig] must never be fabricated.
 */
data class TrackerCameraState(
    val deviceId: TrackerId,
    val lifecycleState: StreamLifecycleState = StreamLifecycleState.IDLE,
    val requestedConfig: CameraCaptureConfig? = null,
    val actualConfig: ActualCaptureConfig? = null,
    val isSimulated: Boolean = false,
    val simulationLabel: String? = null,
    val lastError: CameraError? = null,
    val lastUpdatedTimestamp: Long = 0L
) {
    val isStreaming: Boolean
        get() = lifecycleState == StreamLifecycleState.STREAMING

    companion object {
        fun initial(deviceId: TrackerId, timestamp: Long = 0L): TrackerCameraState {
            return TrackerCameraState(
                deviceId = deviceId,
                lifecycleState = StreamLifecycleState.IDLE,
                lastUpdatedTimestamp = timestamp
            )
        }
    }
}
