package com.livetracker.controller.ui.screens.alerts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import com.livetracker.controller.core.tracker.alert.domain.AlertSeverity
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SeverityFilter {
    ALL,
    CRITICAL,
    WARNING,
    INFO;

    fun matches(severity: AlertSeverity): Boolean = when (this) {
        ALL -> true
        CRITICAL -> severity == AlertSeverity.CRITICAL
        WARNING -> severity == AlertSeverity.WARNING
        INFO -> severity == AlertSeverity.INFO
    }
}

data class TrackerDeviceOption(
    val deviceId: TrackerId?,
    val displayName: String
)

data class AlertsUiState(
    val allAlerts: List<Alert> = emptyList(),
    val filteredAlerts: List<Alert> = emptyList(),
    val selectedSeverity: SeverityFilter = SeverityFilter.ALL,
    val selectedDeviceId: TrackerId? = null,
    val availableDevices: List<TrackerDeviceOption> = emptyList(),
    val deviceNames: Map<TrackerId, String> = emptyMap(),
    val totalUnacknowledgedCount: Int = 0,
    val criticalCount: Int = 0,
    val warningCount: Int = 0,
    val infoCount: Int = 0,
    val isFiltered: Boolean = false
)

class AlertsViewModel(
    private val alertRepository: AlertRepository,
    private val trackerDeviceRepository: TrackerDeviceRepository? = null,
    private val savedStateHandle: SavedStateHandle? = null
) : ViewModel() {

    companion object {
        const val KEY_SEVERITY_FILTER = "alerts_severity_filter"
        const val KEY_DEVICE_FILTER = "alerts_device_filter"
    }

    private val initialSeverity: SeverityFilter = savedStateHandle?.get<String>(KEY_SEVERITY_FILTER)
        ?.let { runCatching { SeverityFilter.valueOf(it) }.getOrNull() }
        ?: SeverityFilter.ALL

    private val initialDeviceId: TrackerId? = savedStateHandle?.get<String>(KEY_DEVICE_FILTER)
        ?.let { TrackerId(it) }

    private val _selectedSeverity = MutableStateFlow(initialSeverity)
    private val _selectedDeviceId = MutableStateFlow<TrackerId?>(initialDeviceId)

    private val rawAlertsFlow: Flow<List<Alert>> = alertRepository.observeUnacknowledgedAlerts().distinctUntilChanged()
    private val rawDevicesFlow: Flow<List<TrackerDevice>> =
        (trackerDeviceRepository?.observeAllDevices() ?: flowOf(emptyList())).distinctUntilChanged()

    val uiState: StateFlow<AlertsUiState> = combine(
        rawAlertsFlow,
        rawDevicesFlow,
        _selectedSeverity,
        _selectedDeviceId
    ) { allAlerts, devices, severityFilter, deviceFilter ->
        val deviceNameMap = mutableMapOf<TrackerId, String>()
        devices.forEach { device ->
            val name = com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(device.displayName, device.deviceId.value)
            deviceNameMap[device.deviceId] = name
        }
        allAlerts.forEach { alert ->
            if (!deviceNameMap.containsKey(alert.deviceId)) {
                deviceNameMap[alert.deviceId] = com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(null, alert.deviceId.value)
            }
        }

        val allDeviceIds = (devices.map { it.deviceId } + allAlerts.map { it.deviceId }).distinct()
        val deviceOptions = buildList {
            add(TrackerDeviceOption(deviceId = null, displayName = "All Devices"))
            val sortedOptions = allDeviceIds.map { id ->
                TrackerDeviceOption(
                    deviceId = id,
                    displayName = deviceNameMap[id] ?: id.value
                )
            }.sortedBy { it.displayName.lowercase() }
            addAll(sortedOptions)
        }

        val filtered = allAlerts.filter { alert ->
            severityFilter.matches(alert.severity) &&
                (deviceFilter == null || alert.deviceId == deviceFilter)
        }.sortedWith(
            compareByDescending<Alert> { it.timestamp }
                .thenByDescending { it.severity.ordinal }
                .thenByDescending { it.id }
        )

        val totalCount = allAlerts.size
        val critCount = allAlerts.count { it.severity == AlertSeverity.CRITICAL }
        val warnCount = allAlerts.count { it.severity == AlertSeverity.WARNING }
        val infCount = allAlerts.count { it.severity == AlertSeverity.INFO }
        val isFiltered = (severityFilter != SeverityFilter.ALL || deviceFilter != null)

        AlertsUiState(
            allAlerts = allAlerts,
            filteredAlerts = filtered,
            selectedSeverity = severityFilter,
            selectedDeviceId = deviceFilter,
            availableDevices = deviceOptions,
            deviceNames = deviceNameMap,
            totalUnacknowledgedCount = totalCount,
            criticalCount = critCount,
            warningCount = warnCount,
            infoCount = infCount,
            isFiltered = isFiltered
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AlertsUiState(
            selectedSeverity = initialSeverity,
            selectedDeviceId = initialDeviceId
        )
    )

    val alerts: StateFlow<List<Alert>> = uiState
        .map { it.filteredAlerts }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setSeverityFilter(filter: SeverityFilter) {
        _selectedSeverity.value = filter
        savedStateHandle?.set(KEY_SEVERITY_FILTER, filter.name)
    }

    fun setDeviceFilter(deviceId: TrackerId?) {
        _selectedDeviceId.value = deviceId
        savedStateHandle?.set(KEY_DEVICE_FILTER, deviceId?.value)
    }

    fun clearFilters() {
        setSeverityFilter(SeverityFilter.ALL)
        setDeviceFilter(null)
    }

    fun acknowledgeAlert(alertId: String) {
        viewModelScope.launch {
            alertRepository.acknowledgeAlert(alertId)
        }
    }

    fun acknowledgeAlerts(alertIds: Set<String>) {
        viewModelScope.launch {
            alertRepository.acknowledgeAlerts(alertIds)
        }
    }

    fun acknowledgeFiltered() {
        viewModelScope.launch {
            val idsToAck = uiState.value.filteredAlerts.map { it.id }.toSet()
            if (idsToAck.isNotEmpty()) {
                alertRepository.acknowledgeAlerts(idsToAck)
            }
        }
    }

    fun acknowledgeAll() {
        viewModelScope.launch {
            alertRepository.acknowledgeAllAlerts()
        }
    }
}

