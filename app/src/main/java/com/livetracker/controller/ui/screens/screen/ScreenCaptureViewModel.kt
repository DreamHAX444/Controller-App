package com.livetracker.controller.ui.screens.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureRepository
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureStatus
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionManager
import com.livetracker.controller.webrtc.AndroidWebRtcSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack

data class ScreenTrackerOption(
    val deviceId: TrackerId,
    val displayName: String,
    val isOnline: Boolean,
    val isScreenSupported: Boolean
)

data class ScreenCaptureUiState(
    val deviceId: String = "",
    val displayName: String = "",
    val isStreaming: Boolean = false,
    val status: ScreenCaptureStatus = ScreenCaptureStatus.IDLE,
    val qualityProfile: String = "BALANCED",
    val videoTrack: VideoTrack? = null,
    val errorMessage: String? = null,
    val isActionPending: Boolean = false,
    val availableTrackers: List<ScreenTrackerOption> = emptyList()
)

class ScreenCaptureViewModel(
    private val screenCaptureRepository: ScreenCaptureRepository,
    private val webRtcSessionManager: WebRtcSessionManager,
    private val deviceSelectionManager: DeviceSelectionManager,
    private val sessionRepository: SessionRepository,
    private val trackerDeviceRepository: TrackerDeviceRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScreenCaptureUiState())
    val uiState: StateFlow<ScreenCaptureUiState> = _uiState.asStateFlow()

    private var screenObserveJob: Job? = null
    private var videoTrackObserveJob: Job? = null

    init {
        viewModelScope.launch {
            deviceSelectionManager.getSelection().collectLatest { selection ->
                val primaryId = when (selection) {
                    is DeviceSelection.Single -> selection.deviceId
                    is DeviceSelection.Multiple -> selection.deviceIds.firstOrNull()
                    else -> null
                }
                if (primaryId != null) {
                    val initialName = trackerDeviceRepository?.getDevice(primaryId)?.displayName ?: primaryId.value
                    _uiState.update { it.copy(deviceId = primaryId.value, displayName = initialName) }
                    observeDeviceScreen(primaryId)
                }
            }
        }
        if (trackerDeviceRepository != null) {
            viewModelScope.launch {
                trackerDeviceRepository.observeAllDevices().collect { devices ->
                    val options = devices.map { dev ->
                        ScreenTrackerOption(
                            deviceId = dev.deviceId,
                            displayName = com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(dev.displayName, dev.deviceId.value),
                            isOnline = dev.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED ||
                                dev.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTING,
                            isScreenSupported = dev.capabilities.screen
                        )
                    }
                    val currentId = _uiState.value.deviceId
                    val currentDevice = devices.find { it.deviceId.value == currentId }
                    val hasConnectedDevice = devices.any { it.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED }
                    val shouldAutoSwitch = currentId.isEmpty() || (currentDevice?.connectionState != com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED && hasConnectedDevice)
                    if (devices.isNotEmpty() && shouldAutoSwitch) {
                        val preferred = devices.firstOrNull { it.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED } ?: devices.first()
                        _uiState.update { it.copy(deviceId = preferred.deviceId.value, displayName = preferred.displayName, availableTrackers = options) }
                        observeDeviceScreen(preferred.deviceId)
                    } else if (currentId.isNotEmpty()) {
                        val matched = devices.find { it.deviceId.value == currentId }
                        if (matched != null && matched.displayName != _uiState.value.displayName) {
                            _uiState.update { it.copy(displayName = matched.displayName, availableTrackers = options) }
                        } else {
                            _uiState.update { it.copy(availableTrackers = options) }
                        }
                    } else {
                        _uiState.update { it.copy(availableTrackers = options) }
                    }
                }
            }
        }
    }

    fun selectTracker(deviceId: String) {
        val trackerId = TrackerId(deviceId)
        viewModelScope.launch {
            val name = trackerDeviceRepository?.getDevice(trackerId)?.displayName ?: deviceId
            _uiState.update { it.copy(deviceId = deviceId, displayName = name) }
            observeDeviceScreen(trackerId)
        }
    }

    private fun observeDeviceScreen(deviceId: TrackerId) {
        screenObserveJob?.cancel()
        screenObserveJob = viewModelScope.launch {
            screenCaptureRepository.observeScreenState(deviceId).collect { state ->
                _uiState.update {
                    it.copy(
                        status = state.status,
                        isStreaming = state.isStreaming,
                        qualityProfile = state.qualityProfile,
                        errorMessage = state.errorMessage
                    )
                }

                if (state.isStreaming) {
                    if (videoTrackObserveJob?.isActive != true) {
                        observeVideoTrack(deviceId)
                    }
                } else {
                    videoTrackObserveJob?.cancel()
                    videoTrackObserveJob = null
                    _uiState.update { it.copy(videoTrack = null) }
                }
            }
        }
    }

    private fun observeVideoTrack(deviceId: TrackerId) {
        videoTrackObserveJob?.cancel()
        videoTrackObserveJob = viewModelScope.launch {
            var attempts = 0
            while (isActive && attempts < 50) {
                attempts++
                val session = webRtcSessionManager.getSession(deviceId) as? AndroidWebRtcSession
                if (session != null) {
                    session.remoteVideoTrack.collect { track ->
                        _uiState.update { it.copy(videoTrack = track) }
                    }
                    return@launch
                }
                delay(200)
            }
        }
    }

    fun startScreenCapture(qualityProfile: String = "BALANCED") {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty() || _uiState.value.isActionPending) return

        _uiState.update { it.copy(isActionPending = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                val result = screenCaptureRepository.startScreenCapture(TrackerId(devId), qualityProfile, session)
                if (result.isFailure) {
                    _uiState.update {
                        it.copy(
                            isActionPending = false,
                            errorMessage = result.exceptionOrNull()?.message ?: "Failed to start screen capture"
                        )
                    }
                } else {
                    _uiState.update { it.copy(isActionPending = false) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionPending = false,
                        errorMessage = e.message ?: "Failed to start screen capture"
                    )
                }
            }
        }
    }

    fun stopScreenCapture() {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty() || _uiState.value.isActionPending) return

        _uiState.update { it.copy(isActionPending = true) }
        viewModelScope.launch {
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                screenCaptureRepository.stopScreenCapture(TrackerId(devId), session)
            } finally {
                _uiState.update { it.copy(isActionPending = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
