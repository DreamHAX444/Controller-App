package com.livetracker.controller.ui.screens.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.audio.domain.AudioRepository
import com.livetracker.controller.core.tracker.audio.domain.AudioState
import com.livetracker.controller.core.tracker.audio.domain.AudioStatus
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AudioTrackerOption(
    val deviceId: TrackerId,
    val displayName: String,
    val isOnline: Boolean,
    val isAudioSupported: Boolean
)

data class AudioUiState(
    val deviceId: String = "",
    val displayName: String = "",
    val availableTrackers: List<AudioTrackerOption> = emptyList(),
    val isStreaming: Boolean = false,
    val status: AudioStatus = AudioStatus.IDLE,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val amplitude: Float = 0f,
    val waveformBars: List<Float> = emptyList(),
    val selectedQuality: String = "AUTO",
    val errorMessage: String? = null,
    val isActionPending: Boolean = false
)

class AudioViewModel(
    private val audioRepository: AudioRepository,
    private val deviceSelectionManager: DeviceSelectionManager,
    private val sessionRepository: SessionRepository,
    private val trackerDeviceRepository: TrackerDeviceRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioUiState())
    val uiState: StateFlow<AudioUiState> = _uiState.asStateFlow()

    private var audioObserveJob: Job? = null

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
                    observeDeviceAudio(primaryId)
                }
            }
        }
        if (trackerDeviceRepository != null) {
            viewModelScope.launch {
                trackerDeviceRepository.observeAllDevices().collect { devices ->
                    val options = devices.map { dev ->
                        AudioTrackerOption(
                            deviceId = dev.deviceId,
                            displayName = com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(dev.displayName, dev.deviceId.value),
                            isOnline = dev.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED ||
                                dev.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTING,
                            isAudioSupported = dev.capabilities.audio
                        )
                    }
                    val currentId = _uiState.value.deviceId
                    val currentDevice = devices.find { it.deviceId.value == currentId }
                    val hasConnectedDevice = devices.any { it.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED }
                    val shouldAutoSwitch = currentId.isEmpty() || (currentDevice?.connectionState != com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED && hasConnectedDevice)
                    if (devices.isNotEmpty() && shouldAutoSwitch) {
                        val preferred = devices.firstOrNull { it.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED } ?: devices.first()
                        _uiState.update {
                            it.copy(
                                deviceId = preferred.deviceId.value,
                                displayName = preferred.displayName,
                                availableTrackers = options
                            )
                        }
                        observeDeviceAudio(preferred.deviceId)
                    } else if (currentId.isNotEmpty()) {
                        val matched = devices.find { it.deviceId.value == currentId }
                        _uiState.update {
                            it.copy(
                                displayName = matched?.displayName ?: it.displayName,
                                availableTrackers = options
                            )
                        }
                    } else {
                        _uiState.update { it.copy(availableTrackers = options) }
                    }
                }
            }
        }
    }

    fun selectTracker(newDeviceId: TrackerId) {
        val currentId = _uiState.value.deviceId
        if (currentId == newDeviceId.value) return

        viewModelScope.launch {
            if (_uiState.value.isStreaming && currentId.isNotEmpty()) {
                val session = sessionRepository.getActiveSession().getOrNull()
                audioRepository.stopAudio(TrackerId(currentId), session)
            }
            val devName = trackerDeviceRepository?.getDevice(newDeviceId)?.displayName ?: newDeviceId.value
            _uiState.update {
                it.copy(
                    deviceId = newDeviceId.value,
                    displayName = devName,
                    status = AudioStatus.IDLE,
                    isStreaming = false,
                    amplitude = 0f,
                    waveformBars = emptyList(),
                    errorMessage = null
                )
            }
            observeDeviceAudio(newDeviceId)
        }
    }

    fun setQuality(quality: String) {
        _uiState.update { it.copy(selectedQuality = quality) }
    }

    private fun observeDeviceAudio(deviceId: TrackerId) {
        audioObserveJob?.cancel()
        audioObserveJob = viewModelScope.launch {
            audioRepository.observeAudioState(deviceId).collect { state ->
                _uiState.update {
                    it.copy(
                        status = state.status,
                        isStreaming = state.isStreaming,
                        volume = state.volume,
                        isMuted = state.isMuted,
                        amplitude = state.amplitude,
                        waveformBars = state.waveformBars,
                        errorMessage = state.errorMessage
                    )
                }
            }
        }
    }

    fun startAudio() {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty() || _uiState.value.isActionPending) return

        _uiState.update { it.copy(isActionPending = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                val result = audioRepository.startAudio(TrackerId(devId), session)
                if (result.isFailure) {
                    _uiState.update {
                        it.copy(
                            isActionPending = false,
                            errorMessage = result.exceptionOrNull()?.message ?: "Failed to start audio"
                        )
                    }
                } else {
                    _uiState.update { it.copy(isActionPending = false) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isActionPending = false,
                        errorMessage = e.message ?: "Failed to start audio"
                    )
                }
            }
        }
    }

    fun stopAudio() {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty() || _uiState.value.isActionPending) return

        _uiState.update { it.copy(isActionPending = true) }
        viewModelScope.launch {
            try {
                val session = sessionRepository.getActiveSession().getOrNull()
                audioRepository.stopAudio(TrackerId(devId), session)
            } finally {
                _uiState.update { it.copy(isActionPending = false) }
            }
        }
    }

    fun setVolume(volume: Float) {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty()) return
        viewModelScope.launch {
            audioRepository.setVolume(TrackerId(devId), volume)
        }
    }

    fun toggleMute() {
        val devId = _uiState.value.deviceId
        if (devId.isEmpty()) return
        val currentMute = _uiState.value.isMuted
        viewModelScope.launch {
            audioRepository.setMuted(TrackerId(devId), !currentMute)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
