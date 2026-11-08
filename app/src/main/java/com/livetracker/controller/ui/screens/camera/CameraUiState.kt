package com.livetracker.controller.ui.screens.camera

import com.livetracker.controller.core.tracker.camera.domain.ActualCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.DiscoveredCameraInfo
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.domain.TrackerId

/**
 * Diagnostic reason explaining why the camera operation is currently gated.
 */
enum class CameraGatingReason {
    NONE,
    NO_DEVICE_SELECTED,
    CAPABILITY_UNSUPPORTED,
    PERMISSION_DENIED,
    DEVICE_OFFLINE
}

/**
 * Device option for selecting active tracker on camera screen.
 */
data class CameraTrackerOption(
    val deviceId: TrackerId,
    val displayName: String,
    val isOnline: Boolean,
    val isCameraSupported: Boolean
)

/**
 * Immutable UI State for the Live Camera feature.
 *
 * CRITICAL CONSTRAINT:
 * [actualConfig] must strictly reflect telemetry verified from remote peer or null.
 * Never fabricates stream frames, FPS, bitrate, or latency.
 */
data class CameraUiState(
    // Tracker selection
    val selectedTrackerId: TrackerId? = null,
    val selectedTrackerDisplayName: String = "",
    val availableTrackers: List<CameraTrackerOption> = emptyList(),

    // Gating state
    val isCameraSupported: Boolean = false,
    val isDeviceOnline: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val gatingReason: CameraGatingReason = CameraGatingReason.NO_DEVICE_SELECTED,
    val gatingMessage: String? = null,

    // Stream lifecycle & telemetry
    val lifecycleState: StreamLifecycleState = StreamLifecycleState.IDLE,
    val isOperating: Boolean = false,
    val isSimulated: Boolean = false,
    val simulationLabel: String? = null,

    // Controls & configuration
    val selectedProfile: QualityProfile = QualityProfile.MEDIUM,
    val selectedFacing: CameraFacing = CameraFacing.BACK,
    val selectedCameraId: String? = null,
    val discoveredCameras: List<DiscoveredCameraInfo> = emptyList(),
    val isReloadingCameras: Boolean = false,
    val isFullScreen: Boolean = false,
    val requestedConfig: CameraCaptureConfig? = null,
    val actualConfig: ActualCaptureConfig? = null,
    val liveBitrateKbps: Int? = null,

    // Feedback
    val errorMessage: String? = null
) {
    val isStreaming: Boolean
        get() = lifecycleState == StreamLifecycleState.STREAMING

    val canStart: Boolean
        get() = selectedTrackerId != null &&
            isDeviceOnline &&
            isCameraSupported &&
            isPermissionGranted &&
            lifecycleState.canStart &&
            !isOperating

    val canStop: Boolean
        get() = lifecycleState.canStop && !isOperating

    val canSwitch: Boolean
        get() = selectedTrackerId != null &&
            isCameraSupported &&
            isPermissionGranted &&
            lifecycleState.canSwitch &&
            !isOperating
}
