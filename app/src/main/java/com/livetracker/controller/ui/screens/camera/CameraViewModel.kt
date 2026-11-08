package com.livetracker.controller.ui.screens.camera

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.auth.domain.Action
import com.livetracker.controller.core.auth.domain.AuthorizationEngine
import com.livetracker.controller.core.auth.domain.AuthorizationResult
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.camera.domain.DiscoveredCameraInfo
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.camera.domain.TrackerCameraState
import com.livetracker.controller.core.tracker.camera.domain.usecase.ObserveCameraStreamUseCase
import com.livetracker.controller.core.tracker.camera.domain.usecase.StartCameraStreamUseCase
import com.livetracker.controller.core.tracker.camera.domain.usecase.StopCameraStreamUseCase
import com.livetracker.controller.core.tracker.camera.domain.usecase.SwitchCameraFacingUseCase
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.ui.util.DeviceNameFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reactive ViewModel for the Live Camera feature.
 *
 * Enforces:
 * 1. Capability gating (camera must be supported by tracker).
 * 2. Online-state gating (tracker must be connected).
 * 3. Permission gating (user must have CAMERA permission on tracker).
 * 4. Safe tracker switching (stops active stream before switching).
 * 5. Distinction between requested settings and verified actual telemetry.
 * 6. Active stream cleanup on ViewModel disposal / screen exit.
 */
class CameraViewModel(
    private val cameraRepository: CameraRepository,
    private val trackerDeviceRepository: TrackerDeviceRepository,
    private val deviceSelectionManager: DeviceSelectionManager? = null,
    private val sessionRepository: SessionRepository? = null,
    private val authEngine: AuthorizationEngine? = null,
    private val savedStateHandle: SavedStateHandle? = null,
    private val sessionProvider: (suspend () -> Session?)? = null,
    private val webRtcSessionManager: com.livetracker.controller.core.tracker.webrtc.WebRtcSessionManager? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val observeCameraStreamUseCase: ObserveCameraStreamUseCase = ObserveCameraStreamUseCase(cameraRepository),
    private val startCameraStreamUseCase: StartCameraStreamUseCase = StartCameraStreamUseCase(cameraRepository),
    private val stopCameraStreamUseCase: StopCameraStreamUseCase = StopCameraStreamUseCase(cameraRepository),
    private val switchCameraFacingUseCase: SwitchCameraFacingUseCase = SwitchCameraFacingUseCase(cameraRepository),
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : ViewModel() {

    private val _videoTrack = MutableStateFlow<org.webrtc.VideoTrack?>(null)
    val videoTrack: StateFlow<org.webrtc.VideoTrack?> = _videoTrack.asStateFlow()

    companion object {
        const val KEY_SELECTED_TRACKER_ID = "camera_selected_tracker_id"
        const val KEY_SELECTED_PROFILE = "camera_selected_profile"
        const val KEY_SELECTED_FACING = "camera_selected_facing"
        const val KEY_SELECTED_CAMERA_ID = "camera_selected_camera_id"
    }

    private val initialTrackerId: TrackerId? = savedStateHandle?.get<String>(KEY_SELECTED_TRACKER_ID)
        ?.let { TrackerId(it) }

    private val initialProfile: QualityProfile = savedStateHandle?.get<String>(KEY_SELECTED_PROFILE)
        ?.let { runCatching { QualityProfile.valueOf(it) }.getOrNull() }
        ?: QualityProfile.MEDIUM

    private val initialFacing: CameraFacing = savedStateHandle?.get<String>(KEY_SELECTED_FACING)
        ?.let { runCatching { CameraFacing.valueOf(it) }.getOrNull() }
        ?: CameraFacing.BACK

    private val initialCameraId: String? = savedStateHandle?.get<String>(KEY_SELECTED_CAMERA_ID)

    private val _selectedTrackerId = MutableStateFlow<TrackerId?>(initialTrackerId)
    private val _selectedProfile = MutableStateFlow(initialProfile)
    private val _selectedFacing = MutableStateFlow(initialFacing)
    private val _selectedCameraId = MutableStateFlow<String?>(initialCameraId)
    private val _isReloadingCameras = MutableStateFlow(false)
    private val _isFullScreen = MutableStateFlow(false)
    private val _isOperating = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _sessionState = MutableStateFlow<Session?>(null)
    private val _liveBitrateKbps = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch {
            _sessionState.value = fetchSession()
        }
        if (deviceSelectionManager != null) {
            viewModelScope.launch {
                deviceSelectionManager.getSelection().collect { selection ->
                    val primaryId = when (selection) {
                        is DeviceSelection.Single -> selection.deviceId
                        is DeviceSelection.Multiple -> selection.deviceIds.firstOrNull()
                        else -> null
                    }
                    if (primaryId != null) {
                        selectTracker(primaryId)
                    }
                }
            }
        }
        viewModelScope.launch {
            trackerDeviceRepository.observeAllDevices().collect { devices ->
                if (devices.isNotEmpty()) {
                    val currentSelected = _selectedTrackerId.value
                    val currentDevice = devices.find { it.deviceId == currentSelected }
                    val hasConnectedDevice = devices.any { it.connectionState == ConnectionState.CONNECTED }
                    val shouldAutoSwitch = currentSelected == null || (currentDevice?.connectionState != ConnectionState.CONNECTED && hasConnectedDevice)
                    if (shouldAutoSwitch) {
                        val preferred = devices.firstOrNull { it.connectionState == ConnectionState.CONNECTED } ?: devices.first()
                        val targetDevice = preferred.deviceId
                        if (_selectedTrackerId.value != targetDevice) {
                            _selectedTrackerId.value = targetDevice
                            savedStateHandle?.set(KEY_SELECTED_TRACKER_ID, targetDevice.value)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            _selectedTrackerId.collect { trackerId ->
                if (trackerId != null) {
                    val session = fetchSession()
                    cameraRepository.discoverCameras(trackerId, session, forceRefresh = false)
                }
            }
        }
    }

    private suspend fun fetchSession(): Session? {
        return sessionProvider?.invoke() ?: sessionRepository?.getActiveSession()?.getOrNull()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawCameraStreamFlow: Flow<TrackerCameraState?> = _selectedTrackerId
        .flatMapLatest { trackerId ->
            if (trackerId != null) {
                observeCameraStreamUseCase(trackerId)
            } else {
                flowOf(null)
            }
        }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val rawDiscoveredCamerasFlow: Flow<List<DiscoveredCameraInfo>> = _selectedTrackerId
        .flatMapLatest { trackerId ->
            if (trackerId != null) {
                cameraRepository.observeDiscoveredCameras(trackerId)
            } else {
                flowOf(DiscoveredCameraInfo.DEFAULT_CAMERAS)
            }
        }
        .distinctUntilChanged()

    private val rawDevicesFlow: Flow<List<TrackerDevice>> = trackerDeviceRepository
        .observeAllDevices()
        .distinctUntilChanged()

    private data class ControlsState(
        val profile: QualityProfile,
        val facing: CameraFacing,
        val cameraId: String?,
        val discoveredCameras: List<DiscoveredCameraInfo>,
        val isReloadingCameras: Boolean,
        val isFullScreen: Boolean,
        val isOperating: Boolean,
        val errorMessage: String?,
        val session: Session?
    )

    private val controlsFlow: Flow<ControlsState> = combine(
        _selectedProfile,
        _selectedFacing,
        _selectedCameraId,
        rawDiscoveredCamerasFlow,
        _isReloadingCameras,
        _isFullScreen,
        _isOperating,
        _errorMessage,
        _sessionState
    ) { args: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        ControlsState(
            profile = args[0] as QualityProfile,
            facing = args[1] as CameraFacing,
            cameraId = args[2] as? String,
            discoveredCameras = (args[3] as? List<DiscoveredCameraInfo>).orEmpty(),
            isReloadingCameras = args[4] as Boolean,
            isFullScreen = args[5] as Boolean,
            isOperating = args[6] as Boolean,
            errorMessage = args[7] as? String,
            session = args[8] as? Session
        )
    }.distinctUntilChanged()

    val uiState: StateFlow<CameraUiState> = combine(
        rawDevicesFlow,
        _selectedTrackerId,
        rawCameraStreamFlow,
        controlsFlow,
        _liveBitrateKbps
    ) { devices, selectedId, cameraState, controls, liveBitrate ->
        // Effective device if none selected and devices are available
        val effectiveTrackerId = selectedId ?: devices.firstOrNull()?.deviceId
        val selectedDevice = devices.find { it.deviceId == effectiveTrackerId }

        val trackerOptions = devices.map { device ->
            CameraTrackerOption(
                deviceId = device.deviceId,
                displayName = DeviceNameFormatter.formatFriendlyName(device.displayName, device.deviceId.value),
                isOnline = device.connectionState == ConnectionState.CONNECTED || device.connectionState == ConnectionState.CONNECTING,
                isCameraSupported = device.capabilities.camera
            )
        }

        val isCameraSupported = selectedDevice?.capabilities?.camera == true
        val isDeviceOnline = selectedDevice?.connectionState == ConnectionState.CONNECTED ||
            selectedDevice?.connectionState == ConnectionState.CONNECTING

        // Permission evaluation (Strictly fail-closed: requires non-null authEngine and allowed result)
        val isPermissionGranted = if (selectedDevice != null && authEngine != null) {
            authEngine.authorize(
                session = controls.session,
                deviceId = selectedDevice.deviceId.value,
                service = ServicePermission.CAMERA,
                action = Action.EXECUTE,
                currentTime = timeProvider()
            ) is AuthorizationResult.Allowed
        } else {
            false
        }

        // Diagnostic gating determination
        val gatingReason = when {
            selectedDevice == null -> CameraGatingReason.NO_DEVICE_SELECTED
            !isCameraSupported -> CameraGatingReason.CAPABILITY_UNSUPPORTED
            !isPermissionGranted -> CameraGatingReason.PERMISSION_DENIED
            !isDeviceOnline -> CameraGatingReason.DEVICE_OFFLINE
            else -> CameraGatingReason.NONE
        }

        val friendlyDevName = selectedDevice?.let { DeviceNameFormatter.formatFriendlyName(it.displayName, it.deviceId.value) } ?: "Selected device"
        val gatingMessage = when (gatingReason) {
            CameraGatingReason.NO_DEVICE_SELECTED -> "No tracker selected. Choose a device to view camera stream."
            CameraGatingReason.CAPABILITY_UNSUPPORTED -> "Camera capture is not supported by $friendlyDevName."
            CameraGatingReason.PERMISSION_DENIED -> "Camera permission denied for $friendlyDevName."
            CameraGatingReason.DEVICE_OFFLINE -> "$friendlyDevName is offline. Camera is unavailable."
            CameraGatingReason.NONE -> null
        }

        val streamLifecycle = cameraState?.lifecycleState ?: StreamLifecycleState.IDLE
        val requestedConfig = cameraState?.requestedConfig
            ?: controls.profile.toCaptureConfig(facing = controls.facing, cameraId = controls.cameraId)
        val actualConfig = cameraState?.actualConfig

        CameraUiState(
            selectedTrackerId = effectiveTrackerId,
            selectedTrackerDisplayName = selectedDevice?.let { DeviceNameFormatter.formatFriendlyName(it.displayName, it.deviceId.value) }
                ?: effectiveTrackerId?.let { DeviceNameFormatter.formatFriendlyName(null, it.value) } ?: "",
            availableTrackers = trackerOptions,
            isCameraSupported = isCameraSupported,
            isDeviceOnline = isDeviceOnline,
            isPermissionGranted = isPermissionGranted,
            gatingReason = gatingReason,
            gatingMessage = gatingMessage,
            lifecycleState = streamLifecycle,
            isOperating = controls.isOperating,
            isSimulated = cameraState?.isSimulated == true,
            simulationLabel = cameraState?.simulationLabel,
            selectedProfile = controls.profile,
            selectedFacing = cameraState?.requestedConfig?.facing ?: controls.facing,
            selectedCameraId = controls.cameraId ?: cameraState?.requestedConfig?.cameraId,
            discoveredCameras = controls.discoveredCameras.ifEmpty { DiscoveredCameraInfo.DEFAULT_CAMERAS },
            isReloadingCameras = controls.isReloadingCameras,
            isFullScreen = controls.isFullScreen,
            requestedConfig = requestedConfig,
            actualConfig = actualConfig,
            liveBitrateKbps = liveBitrate,
            errorMessage = controls.errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CameraUiState(
            selectedTrackerId = initialTrackerId,
            selectedProfile = initialProfile,
            selectedFacing = initialFacing
        )
    )

    private var videoTrackObserveJob: Job? = null
    private var bitrateSamplerJob: Job? = null

    init {
        viewModelScope.launch {
            uiState.collect { state ->
                val trackerId = state.selectedTrackerId
                if (trackerId != null && (state.lifecycleState == StreamLifecycleState.STREAMING || state.lifecycleState == StreamLifecycleState.STARTING)) {
                    if (videoTrackObserveJob?.isActive != true) {
                        observeVideoTrack(trackerId)
                    }
                    if (state.lifecycleState == StreamLifecycleState.STREAMING) {
                        if (bitrateSamplerJob?.isActive != true) {
                            observeBitrateStats(trackerId)
                        }
                    } else {
                        bitrateSamplerJob?.cancel()
                        bitrateSamplerJob = null
                        _liveBitrateKbps.value = null
                    }
                } else {
                    videoTrackObserveJob?.cancel()
                    videoTrackObserveJob = null
                    _videoTrack.value = null
                    bitrateSamplerJob?.cancel()
                    bitrateSamplerJob = null
                    _liveBitrateKbps.value = null
                }
            }
        }
    }

    private fun observeBitrateStats(trackerId: TrackerId) {
        val manager = webRtcSessionManager ?: return
        bitrateSamplerJob?.cancel()
        bitrateSamplerJob = viewModelScope.launch {
            val session = manager.getSession(trackerId) as? com.livetracker.controller.webrtc.AndroidWebRtcSession
            session?.liveBitrateKbps?.collect { kbps ->
                _liveBitrateKbps.value = kbps
            }
        }
    }

    private fun observeVideoTrack(trackerId: TrackerId) {
        val manager = webRtcSessionManager ?: return
        videoTrackObserveJob?.cancel()
        videoTrackObserveJob = viewModelScope.launch {
            var attempts = 0
            while (isActive && attempts < 50) {
                attempts++
                val session = manager.getSession(trackerId) as? com.livetracker.controller.webrtc.AndroidWebRtcSession
                if (session != null) {
                    session.remoteVideoTrack.collect { track ->
                        _videoTrack.value = track
                    }
                    return@launch
                }
                delay(200)
            }
        }
    }

    /**
     * Safely switches to observing a new tracker device.
     * If the current tracker is actively streaming, it safely stops the stream first.
     */
    fun selectTracker(newDeviceId: TrackerId) {
        val currentId = _selectedTrackerId.value
        if (currentId == newDeviceId) return

        viewModelScope.launch {
            _isOperating.value = true
            try {
                if (currentId != null) {
                    val currentState = cameraRepository.getCameraState(currentId)
                    if (currentState != null && currentState.lifecycleState.canStop) {
                        val session = fetchSession()
                        stopCameraStreamUseCase(currentId, session)
                    }
                }
                _selectedTrackerId.value = newDeviceId
                savedStateHandle?.set(KEY_SELECTED_TRACKER_ID, newDeviceId.value)
                _errorMessage.value = null
            } finally {
                _isOperating.value = false
            }
        }
    }

    /**
     * Requests starting the camera stream for the selected tracker device.
     */
    fun startCamera() {
        val currentState = uiState.value
        if (!currentState.canStart) {
            _errorMessage.value = currentState.gatingMessage ?: "Cannot start camera in current state"
            return
        }
        val deviceId = currentState.selectedTrackerId ?: return

        viewModelScope.launch {
            _isOperating.value = true
            _errorMessage.value = null
            try {
                val session = fetchSession()
                _sessionState.value = session
                val config = _selectedProfile.value.toCaptureConfig(
                    facing = _selectedFacing.value,
                    cameraId = _selectedCameraId.value
                )
                val result = startCameraStreamUseCase(deviceId, config, session)
                if (!result.isSuccess) {
                    _errorMessage.value = when (result) {
                        is CameraResult.UnsupportedDevice -> result.reason
                        is CameraResult.PermissionDenied -> result.reason
                        is CameraResult.InvalidStateTransition -> result.reason
                        is CameraResult.CommandFailed -> result.reason
                        is CameraResult.Success -> null
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to start camera: ${e.message}"
            } finally {
                _isOperating.value = false
            }
        }
    }

    /**
     * Requests stopping the active camera stream for the selected tracker device.
     */
    fun stopCamera() {
        val currentState = uiState.value
        if (!currentState.canStop) return
        val deviceId = currentState.selectedTrackerId ?: return

        viewModelScope.launch {
            _isOperating.value = true
            _errorMessage.value = null
            try {
                val session = fetchSession()
                _sessionState.value = session
                val result = stopCameraStreamUseCase(deviceId, session)
                if (!result.isSuccess) {
                    _errorMessage.value = when (result) {
                        is CameraResult.CommandFailed -> result.reason
                        is CameraResult.InvalidStateTransition -> result.reason
                        is CameraResult.PermissionDenied -> result.reason
                        is CameraResult.UnsupportedDevice -> result.reason
                        is CameraResult.Success -> null
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to stop camera: ${e.message}"
            } finally {
                _isOperating.value = false
            }
        }
    }

    /**
     * Switches the camera lens/facing (FRONT vs BACK) on an active stream.
     */
    fun switchCamera() {
        val currentState = uiState.value
        if (!currentState.canSwitch) return
        val deviceId = currentState.selectedTrackerId ?: return
        val targetFacing = if (_selectedFacing.value == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK

        viewModelScope.launch {
            _isOperating.value = true
            _errorMessage.value = null
            try {
                val session = fetchSession()
                _sessionState.value = session
                val result = switchCameraFacingUseCase(deviceId, targetFacing, session)
                if (result.isSuccess) {
                    _selectedFacing.value = targetFacing
                    savedStateHandle?.set(KEY_SELECTED_FACING, targetFacing.name)
                } else {
                    _errorMessage.value = when (result) {
                        is CameraResult.CommandFailed -> result.reason
                        is CameraResult.InvalidStateTransition -> result.reason
                        is CameraResult.PermissionDenied -> result.reason
                        is CameraResult.UnsupportedDevice -> result.reason
                        is CameraResult.Success -> null
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to switch camera: ${e.message}"
            } finally {
                _isOperating.value = false
            }
        }
    }

    /**
     * Updates requested quality profile. If stream is active, restarts with new configuration.
     */
    fun setQualityProfile(profile: QualityProfile) {
        _selectedProfile.value = profile
        savedStateHandle?.set(KEY_SELECTED_PROFILE, profile.name)

        val currentState = uiState.value
        if (currentState.isStreaming && currentState.selectedTrackerId != null) {
            viewModelScope.launch {
                _isOperating.value = true
                _errorMessage.value = null
                try {
                    val session = fetchSession()
                    val deviceId = currentState.selectedTrackerId
                    val stopResult = stopCameraStreamUseCase(deviceId, session)
                    if (!stopResult.isSuccess) {
                        _errorMessage.value = when (stopResult) {
                            is CameraResult.CommandFailed -> stopResult.reason
                            is CameraResult.InvalidStateTransition -> stopResult.reason
                            is CameraResult.PermissionDenied -> stopResult.reason
                            is CameraResult.UnsupportedDevice -> stopResult.reason
                            is CameraResult.Success -> null
                        }
                        return@launch
                    }
                    val newConfig = profile.toCaptureConfig(
                        facing = _selectedFacing.value,
                        cameraId = _selectedCameraId.value
                    )
                    val startResult = startCameraStreamUseCase(deviceId, newConfig, session)
                    if (!startResult.isSuccess) {
                        _errorMessage.value = when (startResult) {
                            is CameraResult.CommandFailed -> startResult.reason
                            is CameraResult.InvalidStateTransition -> startResult.reason
                            is CameraResult.PermissionDenied -> startResult.reason
                            is CameraResult.UnsupportedDevice -> startResult.reason
                            is CameraResult.Success -> null
                        }
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to update quality profile: ${e.message}"
                } finally {
                    _isOperating.value = false
                }
            }
        }
    }

    /**
     * Updates requested camera facing before stream is started.
     */
    fun setCameraFacing(facing: CameraFacing) {
        if (uiState.value.isStreaming) {
            if (_selectedFacing.value != facing) {
                switchCamera()
            }
        } else {
            _selectedFacing.value = facing
            savedStateHandle?.set(KEY_SELECTED_FACING, facing.name)
        }
    }

    /**
     * Sends GetCameraCapabilities command to remote tracker to discover available hardware camera sensors.
     */
    fun reloadCameras() {
        val deviceId = _selectedTrackerId.value ?: uiState.value.selectedTrackerId ?: return
        viewModelScope.launch {
            _isReloadingCameras.value = true
            try {
                val session = fetchSession()
                val list = cameraRepository.discoverCameras(deviceId, session, forceRefresh = true)
                if (list.isNotEmpty()) {
                    val currentId = _selectedCameraId.value
                    val exists = list.any { it.cameraId == currentId }
                    if (!exists) {
                        val defaultCam = list.find { it.facing == _selectedFacing.value } ?: list.first()
                        _selectedCameraId.value = defaultCam.cameraId
                        _selectedFacing.value = defaultCam.facing
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to scan cameras: ${e.message}"
            } finally {
                _isReloadingCameras.value = false
            }
        }
    }

    /**
     * Selects a specific discovered hardware camera sensor (e.g. Back Primary, Ultra-Wide, Telephoto, Front).
     */
    fun selectCamera(camera: DiscoveredCameraInfo) {
        if (_selectedCameraId.value == camera.cameraId && _selectedFacing.value == camera.facing) {
            return // Already active; ignore redundant selection
        }
        _selectedCameraId.value = camera.cameraId
        _selectedFacing.value = camera.facing
        savedStateHandle?.set(KEY_SELECTED_FACING, camera.facing.name)
        savedStateHandle?.set(KEY_SELECTED_CAMERA_ID, camera.cameraId)

        val currentState = uiState.value
        if (currentState.isStreaming && currentState.selectedTrackerId != null) {
            viewModelScope.launch {
                _isOperating.value = true
                _errorMessage.value = null
                try {
                    val session = fetchSession()
                    val result = cameraRepository.switchCamera(
                        deviceId = currentState.selectedTrackerId,
                        facing = camera.facing,
                        session = session,
                        cameraId = camera.cameraId
                    )
                    if (!result.isSuccess) {
                        _errorMessage.value = when (result) {
                            is CameraResult.CommandFailed -> result.reason
                            is CameraResult.InvalidStateTransition -> result.reason
                            is CameraResult.PermissionDenied -> result.reason
                            is CameraResult.UnsupportedDevice -> result.reason
                            is CameraResult.Success -> null
                        }
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to switch camera: ${e.message}"
                } finally {
                    _isOperating.value = false
                }
            }
        }
    }

    fun toggleFullScreen() {
        _isFullScreen.value = !_isFullScreen.value
    }

    fun setFullScreen(enabled: Boolean) {
        _isFullScreen.value = enabled
    }

    /**
     * Dismisses any active user-facing error message.
     */
    fun dismissError() {
        _errorMessage.value = null
    }

    private var cleanupJob: Job? = null

    /**
     * Ensures an active camera stream is stopped when the ViewModel is destroyed
     * or the camera session ends.
     * Prevents duplicate stop requests if cleanup is already running or completed.
     * Returns the cleanup [Job] for deterministic observation and testing.
     */
    fun cleanupActiveStream(): Job {
        cleanupJob?.let { if (it.isActive) return it }
        val deviceId = _selectedTrackerId.value ?: uiState.value.selectedTrackerId ?: return Job().apply { complete() }
        val job = cleanupScope.launch {
            try {
                val currentState = cameraRepository.getCameraState(deviceId)
                if (currentState != null && currentState.lifecycleState.canStop) {
                    val session = fetchSession()
                    val result = stopCameraStreamUseCase(deviceId, session)
                    if (!result.isSuccess) {
                        _errorMessage.value = when (result) {
                            is CameraResult.CommandFailed -> result.reason
                            is CameraResult.InvalidStateTransition -> result.reason
                            is CameraResult.PermissionDenied -> result.reason
                            is CameraResult.UnsupportedDevice -> result.reason
                            is CameraResult.Success -> null
                        }
                    }
                }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to cleanup camera stream: ${e.message}"
            }
        }
        cleanupJob = job
        return job
    }

    public override fun onCleared() {
        bitrateSamplerJob?.cancel()
        bitrateSamplerJob = null
        _liveBitrateKbps.value = null
        cleanupActiveStream()
        super.onCleared()
    }
}
