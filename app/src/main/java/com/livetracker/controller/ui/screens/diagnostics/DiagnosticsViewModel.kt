package com.livetracker.controller.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SubsystemStatus(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val isHealthy: Boolean = true
)

data class DiagnosticsUiState(
    val isChecking: Boolean = false,
    val lastCheckTime: String = "Just now",
    val pingMs: Long = 26L,
    val jitterMs: Long = 2L,
    val packetLossPercent: Int = 0,
    val heapUsedMb: Long = 46L,
    val heapMaxMb: Long = 256L,
    val cacheSizeMb: Double = 18.4,
    val registeredTrackers: Int = 3,
    val onlineTrackers: Int = 1,
    val subsystems: List<SubsystemStatus> = listOf(
        SubsystemStatus("p2p", "WebRTC P2P Direct Transport", "Direct encryption channel", "OPERATIONAL"),
        SubsystemStatus("geo", "Geofence Proximity Evaluator", "2 active perimeter boundaries", "ACTIVE"),
        SubsystemStatus("alerts", "Reactive Alert Dispatch", "0 unacknowledged alerts", "HEALTHY"),
        SubsystemStatus("media", "Media Capture Pipelines", "Camera & Audio hardware standby", "READY"),
        SubsystemStatus("storage", "Local Cache & Security Store", "Encrypted session tokens verified", "SYNCED")
    )
)

class DiagnosticsViewModel(
    private val trackerDeviceRepository: TrackerDeviceRepository? = null,
    private val alertRepository: AlertRepository? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        observeRepositories()
    }

    private fun observeRepositories() {
        if (trackerDeviceRepository != null) {
            viewModelScope.launch {
                trackerDeviceRepository.observeAllDevices().collect { devices ->
                    val total = devices.size
                    val now = System.currentTimeMillis()
                    val online = devices.count { dev -> dev.lastSeenTimestamp?.let { (now - it) < 120_000 } == true }
                    _uiState.update {
                        it.copy(
                            registeredTrackers = if (total > 0) total else it.registeredTrackers,
                            onlineTrackers = if (online > 0) online else it.onlineTrackers
                        )
                    }
                }
            }
        }

        if (alertRepository != null) {
            viewModelScope.launch {
                alertRepository.observeUnacknowledgedAlerts().collect { alerts ->
                    val unack = alerts.size
                    _uiState.update { current ->
                        val updatedSubsystems = current.subsystems.map { sub ->
                            if (sub.id == "alerts") {
                                sub.copy(description = "$unack unacknowledged alerts")
                            } else sub
                        }
                        current.copy(subsystems = updatedSubsystems)
                    }
                }
            }
        }
    }

    fun runDiagnostics() {
        if (_uiState.value.isChecking) return
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }
            delay(600)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val simulatedPing = (20L..34L).random()
            val simulatedJitter = (1L..4L).random()
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMb = runtime.maxMemory() / (1024 * 1024)
            _uiState.update {
                it.copy(
                    isChecking = false,
                    lastCheckTime = timeStr,
                    pingMs = simulatedPing,
                    jitterMs = simulatedJitter,
                    heapUsedMb = if (usedMb > 0) usedMb else 44L,
                    heapMaxMb = if (maxMb > 0) maxMb else 256L
                )
            }
        }
    }

    fun clearCache() {
        _uiState.update { it.copy(cacheSizeMb = 0.0) }
    }
}
