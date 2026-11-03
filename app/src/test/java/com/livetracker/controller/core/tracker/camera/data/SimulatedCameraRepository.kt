package com.livetracker.controller.core.tracker.camera.data

import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.camera.domain.ActualCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraError
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.camera.domain.DiscoveredCameraInfo
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.camera.domain.TrackerCameraState
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic simulation repository for unit tests and offline previews.
 *
 * CRITICAL CONSTRAINTS:
 * 1. [TrackerCameraState.isSimulated] is ALWAYS true.
 * 2. [TrackerCameraState.simulationLabel] explicitly identifies this as simulated.
 * 3. Never labels simulated telemetry as a real camera stream.
 * 4. Never fabricates latency, FPS, bitrate, or capture resolution. [TrackerCameraState.actualConfig]
 *    remains null unless explicitly supplied via [updateActualConfig].
 */
class SimulatedCameraRepository(
    private val simulationLabel: String = "Deterministic Simulation Engine",
    private val supportedDevices: Set<TrackerId>? = null,
    private val authorizedDevices: Set<TrackerId>? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : CameraRepository {

    private val statesFlow = MutableStateFlow<Map<TrackerId, TrackerCameraState>>(emptyMap())
    private val mutex = Mutex()

    var simulatedCommandFailure: String? = null

    override fun observeCameraState(deviceId: TrackerId): Flow<TrackerCameraState> {
        return statesFlow.map { map ->
            map[deviceId] ?: TrackerCameraState(
                deviceId = deviceId,
                lifecycleState = StreamLifecycleState.IDLE,
                isSimulated = true,
                simulationLabel = simulationLabel,
                lastUpdatedTimestamp = timeProvider()
            )
        }.distinctUntilChanged()
    }

    override suspend fun getCameraState(deviceId: TrackerId): TrackerCameraState? {
        return mutex.withLock { statesFlow.value[deviceId] }
    }

    override suspend fun startCamera(
        deviceId: TrackerId,
        config: CameraCaptureConfig,
        session: Session?
    ): CameraResult {
        // 1. Authorization check
        if (authorizedDevices != null && !authorizedDevices.contains(deviceId)) {
            val errorMsg = "Simulated device access denied: Device '$deviceId' not authorized"
            val failure = CameraResult.PermissionDenied(deviceId, errorMsg)
            updateState(deviceId) { current ->
                current.copy(
                    lifecycleState = StreamLifecycleState.ERROR,
                    lastError = CameraError.PermissionDenied(errorMsg),
                    lastUpdatedTimestamp = timeProvider()
                )
            }
            return failure
        }

        // 2. Capability check
        if (supportedDevices != null && !supportedDevices.contains(deviceId)) {
            val errorMsg = "Simulated device does not support camera: '$deviceId'"
            val failure = CameraResult.UnsupportedDevice(deviceId, errorMsg)
            updateState(deviceId) { current ->
                current.copy(
                    lifecycleState = StreamLifecycleState.ERROR,
                    lastError = CameraError.UnsupportedCapability(errorMsg),
                    lastUpdatedTimestamp = timeProvider()
                )
            }
            return failure
        }

        // 3. State transition check
        val currentState = mutex.withLock {
            statesFlow.value[deviceId] ?: TrackerCameraState(
                deviceId = deviceId,
                lifecycleState = StreamLifecycleState.IDLE,
                isSimulated = true,
                simulationLabel = simulationLabel,
                lastUpdatedTimestamp = timeProvider()
            )
        }
        if (!currentState.lifecycleState.canStart) {
            return CameraResult.InvalidStateTransition(
                currentState = currentState.lifecycleState,
                attemptedAction = "START",
                reason = "Cannot start camera while in state '${currentState.lifecycleState}'"
            )
        }

        // 4. Injected simulated failure check
        val injectedFailure = simulatedCommandFailure
        if (injectedFailure != null) {
            val failure = CameraResult.CommandFailed(deviceId, injectedFailure)
            updateState(deviceId) { current ->
                current.copy(
                    lifecycleState = StreamLifecycleState.ERROR,
                    lastError = CameraError.CommandFailure(injectedFailure),
                    lastUpdatedTimestamp = timeProvider()
                )
            }
            return failure
        }

        // 5. Successful transition to STREAMING (never fabricates actualConfig)
        val newState = updateState(deviceId) { current ->
            current.copy(
                lifecycleState = StreamLifecycleState.STREAMING,
                requestedConfig = config,
                actualConfig = null, // Strictly null; not fabricated
                lastError = null,
                isSimulated = true,
                simulationLabel = simulationLabel,
                lastUpdatedTimestamp = timeProvider()
            )
        }
        return CameraResult.Success(newState)
    }

    override suspend fun stopCamera(
        deviceId: TrackerId,
        session: Session?
    ): CameraResult {
        // 1. Authorization check
        if (authorizedDevices != null && !authorizedDevices.contains(deviceId)) {
            return CameraResult.PermissionDenied(
                deviceId = deviceId,
                reason = "Simulated device access denied: Device '$deviceId' not authorized"
            )
        }

        // 2. State transition check
        val currentState = mutex.withLock {
            statesFlow.value[deviceId] ?: TrackerCameraState(
                deviceId = deviceId,
                lifecycleState = StreamLifecycleState.IDLE,
                isSimulated = true,
                simulationLabel = simulationLabel,
                lastUpdatedTimestamp = timeProvider()
            )
        }
        if (!currentState.lifecycleState.canStop) {
            return CameraResult.InvalidStateTransition(
                currentState = currentState.lifecycleState,
                attemptedAction = "STOP",
                reason = "Cannot stop camera while in state '${currentState.lifecycleState}'"
            )
        }

        // 3. Injected simulated failure check
        val injectedFailure = simulatedCommandFailure
        if (injectedFailure != null) {
            val failure = CameraResult.CommandFailed(deviceId, injectedFailure)
            updateState(deviceId) { current ->
                current.copy(
                    lifecycleState = StreamLifecycleState.ERROR,
                    lastError = CameraError.CommandFailure(injectedFailure),
                    lastUpdatedTimestamp = timeProvider()
                )
            }
            return failure
        }

        // 4. Successful transition to IDLE
        val newState = updateState(deviceId) { current ->
            current.copy(
                lifecycleState = StreamLifecycleState.IDLE,
                requestedConfig = null,
                actualConfig = null,
                lastError = null,
                isSimulated = true,
                simulationLabel = simulationLabel,
                lastUpdatedTimestamp = timeProvider()
            )
        }
        return CameraResult.Success(newState)
    }

    override suspend fun switchCamera(
        deviceId: TrackerId,
        facing: CameraFacing,
        session: Session?,
        cameraId: String?
    ): CameraResult {
        // 1. Authorization check
        if (authorizedDevices != null && !authorizedDevices.contains(deviceId)) {
            return CameraResult.PermissionDenied(
                deviceId = deviceId,
                reason = "Simulated device access denied: Device '$deviceId' not authorized"
            )
        }

        // 2. State transition check
        val currentState = mutex.withLock {
            statesFlow.value[deviceId] ?: TrackerCameraState(
                deviceId = deviceId,
                lifecycleState = StreamLifecycleState.IDLE,
                isSimulated = true,
                simulationLabel = simulationLabel,
                lastUpdatedTimestamp = timeProvider()
            )
        }
        if (!currentState.lifecycleState.canSwitch) {
            return CameraResult.InvalidStateTransition(
                currentState = currentState.lifecycleState,
                attemptedAction = "SWITCH",
                reason = "Cannot switch camera while in state '${currentState.lifecycleState}' (must be STREAMING)"
            )
        }

        // 3. Update facing and optional cameraId in requested config
        val currentRequested = currentState.requestedConfig ?: CameraCaptureConfig.DEFAULT
        val updatedRequestedConfig = currentRequested.copy(
            facing = facing,
            cameraId = cameraId ?: currentRequested.cameraId
        )

        val newState = updateState(deviceId) { current ->
            current.copy(
                lifecycleState = StreamLifecycleState.STREAMING,
                requestedConfig = updatedRequestedConfig,
                isSimulated = true,
                simulationLabel = simulationLabel,
                lastUpdatedTimestamp = timeProvider()
            )
        }
        return CameraResult.Success(newState)
    }

    override suspend fun updateActualConfig(
        deviceId: TrackerId,
        actualConfig: ActualCaptureConfig
    ) {
        updateState(deviceId) { current ->
            current.copy(
                actualConfig = actualConfig,
                lastUpdatedTimestamp = timeProvider()
            )
        }
    }

    override suspend fun clearCameraState(deviceId: TrackerId) {
        mutex.withLock {
            statesFlow.update { it - deviceId }
        }
    }

    override suspend fun emergencyRevokeStream(
        deviceId: TrackerId,
        reason: String
    ): CameraResult {
        val newState = updateState(deviceId) { current ->
            current.copy(
                lifecycleState = StreamLifecycleState.IDLE,
                requestedConfig = null,
                actualConfig = null,
                lastError = CameraError.PermissionDenied("Stream terminated by revocation: $reason"),
                lastUpdatedTimestamp = timeProvider()
            )
        }
        return CameraResult.Success(newState)
    }

    private val discoveredCamerasFlow = MutableStateFlow<Map<TrackerId, List<DiscoveredCameraInfo>>>(emptyMap())

    override fun observeDiscoveredCameras(deviceId: TrackerId): Flow<List<DiscoveredCameraInfo>> {
        return discoveredCamerasFlow.map { map ->
            map[deviceId] ?: DiscoveredCameraInfo.DEFAULT_CAMERAS
        }.distinctUntilChanged()
    }

    override suspend fun discoverCameras(
        deviceId: TrackerId,
        session: Session?,
        forceRefresh: Boolean
    ): List<DiscoveredCameraInfo> {
        return mutex.withLock {
            val list = discoveredCamerasFlow.value[deviceId] ?: DiscoveredCameraInfo.DEFAULT_CAMERAS
            discoveredCamerasFlow.update { it + (deviceId to list) }
            list
        }
    }

    private suspend fun updateState(
        deviceId: TrackerId,
        transform: (TrackerCameraState) -> TrackerCameraState
    ): TrackerCameraState {
        return mutex.withLock {
            val current = statesFlow.value[deviceId] ?: TrackerCameraState(
                deviceId = deviceId,
                lifecycleState = StreamLifecycleState.IDLE,
                isSimulated = true,
                simulationLabel = simulationLabel,
                lastUpdatedTimestamp = timeProvider()
            )
            val updated = transform(current)
            statesFlow.update { it + (deviceId to updated) }
            updated
        }
    }
}
