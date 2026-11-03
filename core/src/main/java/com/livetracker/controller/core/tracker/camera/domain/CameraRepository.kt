package com.livetracker.controller.core.tracker.camera.domain

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

/**
 * Reactive repository contract for managing and observing camera streams by [TrackerId].
 */
interface CameraRepository {

    /**
     * Observes the camera stream state for the specified tracker device reactively.
     * Always emits the current or initial state upon collection.
     */
    fun observeCameraState(deviceId: TrackerId): Flow<TrackerCameraState>

    /**
     * Retrieves the current camera state for the specified tracker device, if present.
     */
    suspend fun getCameraState(deviceId: TrackerId): TrackerCameraState?

    /**
     * Requests starting the camera stream for the given tracker device.
     * Enforces authorization, capabilities, and valid lifecycle state transitions.
     *
     * @param deviceId Target tracker device.
     * @param config Requested capture configuration (resolution, FPS, bitrate, facing).
     * @param session Current authenticated user session for permission evaluation.
     * @return [CameraResult] indicating deterministic outcome.
     */
    suspend fun startCamera(
        deviceId: TrackerId,
        config: CameraCaptureConfig = CameraCaptureConfig.DEFAULT,
        session: Session? = null
    ): CameraResult

    /**
     * Requests stopping the camera stream for the given tracker device.
     * Enforces authorization and valid lifecycle state transitions.
     *
     * @param deviceId Target tracker device.
     * @param session Current authenticated user session for permission evaluation.
     * @return [CameraResult] indicating deterministic outcome.
     */
    suspend fun stopCamera(
        deviceId: TrackerId,
        session: Session? = null
    ): CameraResult

    /**
     * Requests switching the active camera lens/facing (e.g. FRONT / BACK) on an active stream.
     *
     * @param deviceId Target tracker device.
     * @param facing Desired new camera facing.
     * @param session Current authenticated user session for permission evaluation.
     * @param cameraId Optional specific camera sensor ID to activate on the remote device.
     * @return [CameraResult] indicating deterministic outcome.
     */
    suspend fun switchCamera(
        deviceId: TrackerId,
        facing: CameraFacing,
        session: Session? = null,
        cameraId: String? = null
    ): CameraResult

    /**
     * Updates the actual capture configuration reported by the remote tracker stream telemetry.
     * Values must reflect real telemetry; NEVER fabricated.
     */
    suspend fun updateActualConfig(
        deviceId: TrackerId,
        actualConfig: ActualCaptureConfig
    )

    /**
     * Resets or clears the camera state for the specified tracker device.
     */
    suspend fun clearCameraState(deviceId: TrackerId)

    /**
     * Immediately and unconditionally halts any active or negotiating camera stream for [deviceId]
     * due to permission revocation or device unassignment. Bypasses regular EXECUTE authorization checks.
     */
    suspend fun emergencyRevokeStream(
        deviceId: TrackerId,
        reason: String = "Permission revoked"
    ): CameraResult

    /**
     * Discovers all hardware camera sensors supported by the specified tracker device.
     * Caches discovered cameras per device to eliminate redundant network roundtrips.
     *
     * @param deviceId Target tracker device.
     * @param session Current authenticated user session.
     * @param forceRefresh Whether to force re-querying the tracker device via remote command.
     * @return List of discovered cameras.
     */
    suspend fun discoverCameras(
        deviceId: TrackerId,
        session: Session? = null,
        forceRefresh: Boolean = false
    ): List<DiscoveredCameraInfo>

    /**
     * Observes the cached or discovered camera sensors for the given tracker device.
     */
    fun observeDiscoveredCameras(deviceId: TrackerId): Flow<List<DiscoveredCameraInfo>>
}
