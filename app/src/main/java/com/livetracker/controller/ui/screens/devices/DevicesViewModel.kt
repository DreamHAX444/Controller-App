package com.livetracker.controller.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.ObserveDeviceSummariesUseCase
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceSummary
import com.livetracker.controller.core.tracker.location.domain.TrackerLocationRepository
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord
import com.livetracker.controller.core.tracker.supabase.SupabaseFleetManager
import com.livetracker.controller.core.tracker.supabase.UserProfileRecord
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.livetracker.controller.core.tracker.location.domain.formatElapsedSwift
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DeviceFilterTab {
    ALL,
    ASSIGNED,
    UNASSIGNED
}

data class DeviceItemUiState(
    val deviceId: String,
    val displayName: String,
    val model: String,
    val status: String,
    val connectionState: ConnectionState,
    val batteryLevel: Int?,
    val networkState: String?,
    val lastSeenFormatted: String,
    val lastSeenTimestamp: Long? = null,
    val hasLocation: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val isAssigned: Boolean,
    val assignedUserId: String?,
    val isSelected: Boolean
)

data class DevicesUiState(
    val isLoading: Boolean = false,
    val userRole: Role = Role.USER,
    val isVisitor: Boolean = false,
    val selectedTab: DeviceFilterTab = DeviceFilterTab.ALL,
    val searchQuery: String = "",
    val devices: List<DeviceItemUiState> = emptyList(),
    val totalCount: Int = 0,
    val assignedCount: Int = 0,
    val unassignedCount: Int = 0,
    val assignableUsers: List<UserProfileRecord> = emptyList(),
    val isAssigning: Boolean = false,
    val selectedDeviceIdForAssignment: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val devicePendingDeletion: DeviceItemUiState? = null
)

class DevicesViewModel(
    private val observeDeviceSummariesUseCase: ObserveDeviceSummariesUseCase,
    private val deviceSelectionManager: DeviceSelectionManager,
    private val sessionRepository: SessionRepository,
    private val supabaseFleetManager: SupabaseFleetManager,
    private val trackerDeviceRepository: TrackerDeviceRepository? = null,
    private val trackerLocationRepository: TrackerLocationRepository? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedTab = MutableStateFlow(DeviceFilterTab.ALL)
    private val _assignments = MutableStateFlow<List<DeviceAssignmentRecord>>(emptyList())
    private val _assignableUsers = MutableStateFlow<List<UserProfileRecord>>(emptyList())
    private val _isAssigning = MutableStateFlow(false)
    private val _selectedDeviceIdForAssignment = MutableStateFlow<String?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _devicePendingDeletion = MutableStateFlow<DeviceItemUiState?>(null)

    // Intermediate flow combining core device data
    private val baseDevicesFlow = combine(
        observeDeviceSummariesUseCase(),
        deviceSelectionManager.getSelection(),
        _assignments
    ) { summaries: List<TrackerDeviceSummary>, selection: DeviceSelection, assignments: List<DeviceAssignmentRecord> ->
        val assignmentMap = assignments.associateBy { it.deviceId }
        summaries.map { summary ->
            val devId = summary.device.deviceId.value
            val isSelected = when (selection) {
                is DeviceSelection.Single -> selection.deviceId.value == devId
                is DeviceSelection.Multiple -> selection.deviceIds.any { it.value == devId }
                is DeviceSelection.All -> true
                is DeviceSelection.Empty -> false
            }
            val assignment = assignmentMap[devId]
            val isAssigned = assignment != null
            val loc = summary.latestLocation
            val effectiveFreshnessTimestamp = loc?.timestamp ?: summary.device.lastSeenTimestamp

            DeviceItemUiState(
                deviceId = devId,
                displayName = com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(summary.device.displayName, devId),
                model = summary.device.deviceModel ?: "Android Tracker",
                status = summary.device.lifecycle.name,
                connectionState = summary.device.connectionState,
                batteryLevel = summary.device.batteryLevel,
                networkState = summary.device.networkState,
                lastSeenFormatted = formatLastSeen(effectiveFreshnessTimestamp),
                lastSeenTimestamp = effectiveFreshnessTimestamp,
                hasLocation = loc != null,
                latitude = loc?.latitude,
                longitude = loc?.longitude,
                isAssigned = isAssigned,
                assignedUserId = assignment?.userId,
                isSelected = isSelected
            )
        }
    }

    // Combine filters and devices into filtered devices
    private val filteredDevicesFlow = combine(
        baseDevicesFlow,
        _searchQuery,
        _selectedTab
    ) { allItems: List<DeviceItemUiState>, query: String, tab: DeviceFilterTab ->
        val totalCount = allItems.size
        val assignedCount = allItems.count { it.isAssigned }
        val unassignedCount = allItems.count { !it.isAssigned }

        val filteredByQuery = if (query.isBlank()) {
            allItems
        } else {
            val q = query.trim().lowercase()
            allItems.filter {
                it.displayName.lowercase().contains(q) ||
                it.deviceId.lowercase().contains(q) ||
                it.model.lowercase().contains(q)
            }
        }

        val filteredByTab = when (tab) {
            DeviceFilterTab.ALL -> filteredByQuery
            DeviceFilterTab.ASSIGNED -> filteredByQuery.filter { it.isAssigned }
            DeviceFilterTab.UNASSIGNED -> filteredByQuery.filter { !it.isAssigned }
        }

        Triple(filteredByTab, Triple(totalCount, assignedCount, unassignedCount), Pair(query, tab))
    }

    val uiState: StateFlow<DevicesUiState> = combine(
        filteredDevicesFlow,
        sessionRepository.observeActiveSession(),
        _assignableUsers,
        _isAssigning,
        combine(_selectedDeviceIdForAssignment, _statusMessage, _errorMessage, _isLoading, _devicePendingDeletion) { targetDev, status, err, loading, deleteDev ->
            Tuple5(targetDev, status, err, loading, deleteDev)
        }
    ) { (devices, counts, filterPair), activeSession, users, isAssigning, tuple ->
        val userRole = activeSession?.user?.role ?: Role.VISITOR
        val isVisitor = userRole == Role.VISITOR
        val (totalCount, assignedCount, unassignedCount) = counts
        val (query, tab) = filterPair

        val allowedDeviceIds = activeSession?.user?.devicePermissions?.map { it.deviceId }?.toSet() ?: emptySet()
        val roleFilteredDevices = when {
            isVisitor -> emptyList()
            userRole == Role.USER -> devices.filter { it.deviceId in allowedDeviceIds }
            else -> devices
        }
        val visibleTotal = when {
            isVisitor -> 0
            userRole == Role.USER -> roleFilteredDevices.size
            else -> totalCount
        }
        val visibleAssigned = when {
            isVisitor -> 0
            userRole == Role.USER -> roleFilteredDevices.count { it.isAssigned }
            else -> assignedCount
        }
        val visibleUnassigned = when {
            isVisitor -> 0
            userRole == Role.USER -> 0
            else -> unassignedCount
        }

        DevicesUiState(
            isLoading = tuple.d,
            userRole = userRole,
            isVisitor = isVisitor,
            selectedTab = tab,
            searchQuery = query,
            devices = roleFilteredDevices,
            totalCount = visibleTotal,
            assignedCount = visibleAssigned,
            unassignedCount = visibleUnassigned,
            assignableUsers = users,
            isAssigning = isAssigning,
            selectedDeviceIdForAssignment = tuple.a,
            statusMessage = tuple.b,
            errorMessage = tuple.c,
            devicePendingDeletion = tuple.e
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DevicesUiState(isLoading = true)
    )

    private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    init {
        refresh()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onTabSelected(tab: DeviceFilterTab) {
        _selectedTab.value = tab
    }

    fun onSelectDevice(deviceId: String) {
        deviceSelectionManager.selectDevice(TrackerId(deviceId))
    }

    fun onOpenAssignmentDialog(deviceId: String) {
        _selectedDeviceIdForAssignment.value = deviceId
        _statusMessage.value = null
        _errorMessage.value = null
    }

    fun onDismissAssignmentDialog() {
        _selectedDeviceIdForAssignment.value = null
    }

    fun onRequestDeleteDevice(device: DeviceItemUiState) {
        _devicePendingDeletion.value = device
    }

    fun onDismissDeleteDialog() {
        _devicePendingDeletion.value = null
    }

    fun deleteDevice(deviceId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Only Admins and Owners can delete devices"
                return@launch
            }

            _isLoading.value = true
            _statusMessage.value = "Deleting device $deviceId..."
            try {
                val result = supabaseFleetManager.deleteDevice(deviceId)
                if (result.isSuccess) {
                    val trackerId = TrackerId(deviceId)
                    trackerDeviceRepository?.unregisterDevice(trackerId)
                    trackerLocationRepository?.clearLocation(trackerId)
                    val currentSelection = deviceSelectionManager.getSelection().first()
                    if (currentSelection is DeviceSelection.Single && currentSelection.deviceId == trackerId) {
                        deviceSelectionManager.clearSelection()
                    }
                    _devicePendingDeletion.value = null
                    _statusMessage.value = "Device '$deviceId' deleted successfully"
                    refresh()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to delete device"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error deleting device: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onDismissMessage() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    fun assignDevice(deviceId: String, targetUserId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Only Admins and Owners can assign devices"
                return@launch
            }

            _isAssigning.value = true
            _statusMessage.value = null
            _errorMessage.value = null
            try {
                val result = supabaseFleetManager.assignDeviceToUser(deviceId, targetUserId)
                if (result.isSuccess) {
                    _statusMessage.value = "Device '$deviceId' assigned successfully"
                    _selectedDeviceIdForAssignment.value = null
                    loadAssignments()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to assign device"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Assignment failed"
            } finally {
                _isAssigning.value = false
            }
        }
    }

    fun unassignDevice(deviceId: String, targetUserId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Only Admins and Owners can revoke assignments"
                return@launch
            }

            _isAssigning.value = true
            _statusMessage.value = null
            _errorMessage.value = null
            try {
                val result = supabaseFleetManager.unassignDeviceFromUser(deviceId, targetUserId)
                if (result.isSuccess) {
                    _statusMessage.value = "Device '$deviceId' unassigned"
                    loadAssignments()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to unassign device"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unassignment failed"
            } finally {
                _isAssigning.value = false
            }
        }
    }

    fun wakeDevice(deviceId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || !session.isValid(timeProvider())) {
                android.util.Log.w("DevicesViewModel", "Wake command blocked: session invalid or expired for device $deviceId")
                _errorMessage.value = "Session expired. Please log in again to control devices."
                return@launch
            }
            if (session.user.role == Role.VISITOR) {
                android.util.Log.w("DevicesViewModel", "Wake command blocked: visitor cannot control device $deviceId")
                _errorMessage.value = "Unauthorized: visitors cannot control devices"
                return@launch
            }
            android.util.Log.i("DevicesViewModel", "Dispatching Wake command to $deviceId...")
            _statusMessage.value = "Sending Wake command to $deviceId..."
            try {
                val command = TrackerCommand.Wake(deviceId = TrackerId(deviceId))
                val result = supabaseFleetManager.sendTrackerCommand(command)
                android.util.Log.i("DevicesViewModel", "Wake command result for $deviceId: $result")
                when (result) {
                    is CommandResult.Accepted, is CommandResult.Completed -> {
                        _statusMessage.value = "Wake command sent to $deviceId"
                    }
                    is CommandResult.Unauthorized -> {
                        _errorMessage.value = "Unauthorized to wake device $deviceId"
                    }
                    is CommandResult.Failed -> {
                        _errorMessage.value = "Failed to wake $deviceId: ${result.reason}"
                    }
                    else -> {
                        _statusMessage.value = "Wake command dispatched ($deviceId)"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DevicesViewModel", "Error sending wake command to $deviceId: ${e.message}", e)
                _errorMessage.value = "Error sending wake command: ${e.message}"
            }
        }
    }

    fun sleepDevice(deviceId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || !session.isValid(timeProvider())) {
                android.util.Log.w("DevicesViewModel", "Sleep command blocked: session invalid or expired for device $deviceId")
                _errorMessage.value = "Session expired. Please log in again to control devices."
                return@launch
            }
            if (session.user.role == Role.VISITOR) {
                android.util.Log.w("DevicesViewModel", "Sleep command blocked: visitor cannot control device $deviceId")
                _errorMessage.value = "Unauthorized: visitors cannot control devices"
                return@launch
            }
            android.util.Log.i("DevicesViewModel", "Dispatching Sleep command to $deviceId...")
            _statusMessage.value = "Sending Sleep command to $deviceId..."
            try {
                val command = TrackerCommand.Sleep(deviceId = TrackerId(deviceId))
                val result = supabaseFleetManager.sendTrackerCommand(command)
                android.util.Log.i("DevicesViewModel", "Sleep command result for $deviceId: $result")
                when (result) {
                    is CommandResult.Accepted, is CommandResult.Completed -> {
                        _statusMessage.value = "Sleep command sent to $deviceId"
                    }
                    is CommandResult.Unauthorized -> {
                        _errorMessage.value = "Unauthorized to sleep device $deviceId"
                    }
                    is CommandResult.Failed -> {
                        _errorMessage.value = "Failed to sleep $deviceId: ${result.reason}"
                    }
                    else -> {
                        _statusMessage.value = "Sleep command dispatched ($deviceId)"
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DevicesViewModel", "Error sending sleep command to $deviceId: ${e.message}", e)
                _errorMessage.value = "Error sending sleep command: ${e.message}"
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            loadAssignments()
            loadAssignableUsers()
            _isLoading.value = false
        }
    }

    private suspend fun loadAssignments() {
        val session = sessionRepository.getActiveSession().getOrNull() ?: return
        if (session.user.role == Role.VISITOR) return
        try {
            val list = supabaseFleetManager.fetchDeviceAssignments()
            _assignments.value = list
        } catch (_: Exception) {}
    }

    private suspend fun loadAssignableUsers() {
        val session = sessionRepository.getActiveSession().getOrNull() ?: return
        if (session.user.role != Role.OWNER && session.user.role != Role.ADMIN) return
        try {
            val users = supabaseFleetManager.fetchAssignableUsers()
            _assignableUsers.value = users
        } catch (_: Exception) {}
    }

    private fun formatLastSeen(timestamp: Long?): String {
        return formatElapsedSwift(timestamp, timeProvider())
    }
}
