package com.livetracker.controller.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.ObserveDeviceSummariesUseCase
import com.livetracker.controller.core.tracker.device.domain.TrackerDevice
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceSummary
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.geofence.domain.EvaluateGeoFencesUseCase
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceRepository
import com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D
import com.livetracker.controller.core.tracker.location.domain.Location
import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.location.domain.TrackerLocationRepository
import com.livetracker.controller.core.tracker.map.domain.MapMarker
import com.livetracker.controller.core.tracker.map.domain.MapState
import com.livetracker.controller.core.tracker.map.domain.ObserveMapStateUseCase
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import com.livetracker.controller.ui.screens.map.geofence.GEOFENCE_PRESET_COLORS
import com.livetracker.controller.ui.screens.map.geofence.GeofenceEditorState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Reactive ViewModel orchestrating live map telemetry, device selection filters,
 * the interactive polygon geofence state machine, and real-time developer debug controls.
 */
class MapViewModel(
    observeMapStateUseCase: ObserveMapStateUseCase,
    private val geoFenceRepository: GeoFenceRepository,
    private val mapPreferencesRepository: MapPreferencesRepository? = null,
    private val deviceSelectionManager: DeviceSelectionManager? = null,
    observeDeviceSummariesUseCase: ObserveDeviceSummariesUseCase? = null,
    private val trackerDeviceRepository: TrackerDeviceRepository? = null,
    private val trackerLocationRepository: TrackerLocationRepository? = null,
    private val evaluateGeoFencesUseCase: EvaluateGeoFencesUseCase? = null,
    private val alertRepository: AlertRepository? = null
) : ViewModel() {

    val mapState: StateFlow<MapState> = observeMapStateUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MapState.Initializing
        )

    val deviceSelection: StateFlow<DeviceSelection> = deviceSelectionManager?.getSelection()
        ?: MutableStateFlow<DeviceSelection>(DeviceSelection.All).asStateFlow()

    val deviceSummaries: StateFlow<List<TrackerDeviceSummary>> = (observeDeviceSummariesUseCase?.invoke() ?: flowOf(emptyList()))
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _geoFencesRefreshTrigger = MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val geoFences: StateFlow<List<GeoFence>> = _geoFencesRefreshTrigger.mapLatest {
        geoFenceRepository.getGeoFences()
    }.distinctUntilChanged()
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _showGeoFences = MutableStateFlow(true)
    val showGeoFences: StateFlow<Boolean> = _showGeoFences.asStateFlow()

    private val _fallbackConfiguration = MutableStateFlow(
        MapConfiguration(MapProvider.OPEN_STREET_MAP, MapStyle.STANDARD)
    )
    val mapConfiguration: StateFlow<MapConfiguration> =
        mapPreferencesRepository?.mapConfiguration ?: _fallbackConfiguration.asStateFlow()

    private val _fallbackCartoApiKey = MutableStateFlow("")
    val cartoApiKey: StateFlow<String> =
        mapPreferencesRepository?.cartoApiKey ?: _fallbackCartoApiKey.asStateFlow()

    private val _selectedMarker = MutableStateFlow<MapMarker?>(null)
    val selectedMarker: StateFlow<MapMarker?> = _selectedMarker.asStateFlow()

    private val _geofenceEditorState = MutableStateFlow<GeofenceEditorState>(GeofenceEditorState.Idle)
    val geofenceEditorState: StateFlow<GeofenceEditorState> = _geofenceEditorState.asStateFlow()

    // Configuration
    fun setConfiguration(configuration: MapConfiguration) {
        if (mapPreferencesRepository != null) {
            mapPreferencesRepository.setConfiguration(configuration)
        } else {
            _fallbackConfiguration.value = configuration
        }
    }

    // Camera Persistence
    fun saveCameraPosition(lat: Double, lon: Double, zoom: Double) {
        mapPreferencesRepository?.saveLastCameraPosition(lat, lon, zoom)
    }

    fun getLastCameraPosition(): Triple<Double, Double, Double>? {
        return mapPreferencesRepository?.getLastCameraPosition()
    }

    // Marker Focus
    fun selectMarker(marker: MapMarker?) {
        _selectedMarker.value = marker
    }

    // Layer Visibility
    fun toggleGeoFences() {
        _showGeoFences.value = !_showGeoFences.value
    }

    // Device Selection
    fun selectAllDevices() {
        deviceSelectionManager?.selectAll()
    }

    fun selectSingleDevice(deviceId: TrackerId) {
        deviceSelectionManager?.selectDevice(deviceId)
        val marker = (mapState.value as? MapState.Active)?.markers?.find { it.deviceId == deviceId }
        if (marker != null) {
            _selectedMarker.value = marker
        }
    }

    fun selectMultipleDevices(deviceIds: Set<TrackerId>) {
        deviceSelectionManager?.selectMultipleDevices(deviceIds)
    }

    fun toggleDeviceInMultipleSelection(deviceId: TrackerId) {
        val current = deviceSelection.value
        val allIds = deviceSummaries.value.map { it.device.deviceId }.toSet()
        val currentSelectedIds = when (current) {
            is DeviceSelection.Multiple -> current.deviceIds
            is DeviceSelection.Single -> setOf(current.deviceId)
            DeviceSelection.All -> allIds
            DeviceSelection.Empty -> emptySet()
        }

        val updated = if (deviceId in currentSelectedIds) {
            currentSelectedIds - deviceId
        } else {
            currentSelectedIds + deviceId
        }

        when {
            updated.isEmpty() -> deviceSelectionManager?.clearSelection()
            updated.size == allIds.size -> deviceSelectionManager?.selectAll()
            updated.size == 1 -> deviceSelectionManager?.selectDevice(updated.first())
            else -> deviceSelectionManager?.selectMultipleDevices(updated)
        }
    }

    // Geofence Workflows
    fun openGeofenceList() {
        _geofenceEditorState.value = GeofenceEditorState.ListMode
    }

    fun closeGeofenceEditor() {
        _geofenceEditorState.value = GeofenceEditorState.Idle
    }

    fun startCreatingGeofence() {
        _geofenceEditorState.value = GeofenceEditorState.Editing(
            id = null,
            name = "",
            vertices = emptyList(),
            colorArgb = GEOFENCE_PRESET_COLORS.first(),
            isEnabled = true
        )
    }

    fun startEditingGeofence(fence: GeoFence) {
        _geofenceEditorState.value = GeofenceEditorState.Editing(
            id = fence.id,
            name = fence.name,
            vertices = fence.vertices,
            colorArgb = fence.colorArgb,
            isEnabled = fence.isEnabled
        )
    }

    fun updateEditingName(name: String) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(name = name, validationError = null)
    }

    fun updateEditingColor(colorArgb: Int) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(colorArgb = colorArgb)
    }

    fun updateEditingEnabled(isEnabled: Boolean) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(isEnabled = isEnabled)
    }

    fun addVertex(latitude: Double, longitude: Double) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        val updatedVertices = current.vertices + GeoPoint2D(latitude, longitude)
        _geofenceEditorState.value = current.copy(vertices = updatedVertices, validationError = null)
    }

    fun updateVertex(index: Int, latitude: Double, longitude: Double) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        if (index in current.vertices.indices) {
            val updatedVertices = current.vertices.toMutableList()
            updatedVertices[index] = GeoPoint2D(latitude, longitude)
            _geofenceEditorState.value = current.copy(vertices = updatedVertices)
        }
    }

    fun undoLastVertex() {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        if (current.vertices.isNotEmpty()) {
            _geofenceEditorState.value = current.copy(vertices = current.vertices.dropLast(1))
        }
    }

    fun clearVertices() {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(vertices = emptyList())
    }

    fun onMapLongClick(latitude: Double, longitude: Double) {
        if (_geofenceEditorState.value is GeofenceEditorState.Editing) return

        viewModelScope.launch {
            val fences = if (geoFences.value.isNotEmpty()) {
                geoFences.value
            } else {
                geoFenceRepository.getGeoFences()
            }

            // Mathematical Ray-Casting Point-in-Polygon detection
            val hitFence = fences.find { fence ->
                fence.vertices.size >= 3 && isPointInPolygon(latitude, longitude, fence.vertices)
            }

            if (hitFence != null) {
                _geofenceEditorState.value = GeofenceEditorState.Editing(
                    id = hitFence.id,
                    name = hitFence.name,
                    vertices = hitFence.vertices,
                    colorArgb = hitFence.colorArgb,
                    isEnabled = hitFence.isEnabled
                )
            }
        }
    }

    private fun isPointInPolygon(lat: Double, lon: Double, polygon: List<GeoPoint2D>): Boolean {
        if (polygon.size < 3) return false
        var isInside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val vi = polygon[i]
            val vj = polygon[j]
            val intersect = ((vi.longitude > lon) != (vj.longitude > lon)) &&
                    (lat < (vj.latitude - vi.latitude) * (lon - vi.longitude) / (vj.longitude - vi.longitude) + vi.latitude)
            if (intersect) {
                isInside = !isInside
            }
            j = i
        }
        return isInside
    }

    fun saveGeofence() {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        if (current.name.isBlank()) {
            _geofenceEditorState.value = current.copy(validationError = "Please enter a zone name")
            return
        }
        if (current.vertices.size < 3) {
            _geofenceEditorState.value = current.copy(validationError = "A polygon requires at least 3 vertices")
            return
        }

        val fence = GeoFence(
            id = current.id ?: UUID.randomUUID().toString(),
            name = current.name.trim(),
            vertices = current.vertices,
            colorArgb = current.colorArgb,
            isEnabled = current.isEnabled
        )

        viewModelScope.launch {
            geoFenceRepository.addGeoFence(fence)
            _geoFencesRefreshTrigger.value++
            _geofenceEditorState.value = GeofenceEditorState.ListMode
        }
    }

    fun deleteGeofence(id: String) {
        viewModelScope.launch {
            geoFenceRepository.removeGeoFence(id)
            _geoFencesRefreshTrigger.value++
        }
    }

    fun toggleGeofenceEnabled(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            val fences = geoFenceRepository.getGeoFences()
            val fence = fences.find { it.id == id }
            if (fence != null) {
                geoFenceRepository.addGeoFence(fence.copy(isEnabled = isEnabled, updatedAt = System.currentTimeMillis()))
                _geoFencesRefreshTrigger.value++
            }
        }
    }

    // ==========================================
    // DEVELOPER DEBUG CONTROLS
    // ==========================================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val unacknowledgedAlerts: StateFlow<List<Alert>> = alertRepository?.let { repo ->
        flow {
            while (true) {
                emit(repo.getUnacknowledgedAlerts())
                delay(1500L)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    } ?: MutableStateFlow<List<Alert>>(emptyList()).asStateFlow()

    fun debugAddDevice(
        id: String,
        displayName: String,
        model: String,
        lat: Double,
        lon: Double,
        battery: Int = 90,
        speed: Float = 0f,
        bearing: Float? = 0f,
        connectionState: ConnectionState = ConnectionState.CONNECTED
    ) {
        viewModelScope.launch {
            val trackerId = TrackerId(id.trim().ifEmpty { "dev_${(System.currentTimeMillis() % 10000)}" })
            val now = System.currentTimeMillis()
            val device = TrackerDevice(
                deviceId = trackerId,
                displayName = displayName.trim().ifEmpty { "Tracker ${trackerId.value}" },
                deviceModel = model.trim().ifEmpty { "Virtual Tracker" },
                registrationTimestamp = now,
                lastSeenTimestamp = now,
                connectionState = connectionState,
                batteryLevel = battery,
                networkState = if (connectionState == ConnectionState.CONNECTED) "Virtual Link" else null
            )
            trackerDeviceRepository?.registerDevice(device)
            val location = Location(
                deviceId = trackerId,
                latitude = lat,
                longitude = lon,
                accuracy = 8f,
                bearing = bearing,
                speed = speed,
                timestamp = now,
                state = if (connectionState == ConnectionState.CONNECTED) LocationState.LIVE else LocationState.OFFLINE
            )
            trackerLocationRepository?.updateLocation(location)
            evaluateGeoFencesUseCase?.invoke(location)
        }
    }

    fun debugMoveDevice(
        deviceId: TrackerId,
        newLat: Double,
        newLon: Double,
        speed: Float = 15f,
        bearing: Float? = null
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val location = Location(
                deviceId = deviceId,
                latitude = newLat,
                longitude = newLon,
                accuracy = 6f,
                bearing = bearing,
                speed = speed,
                timestamp = now,
                state = LocationState.LIVE
            )
            trackerLocationRepository?.updateLocation(location)
            trackerDeviceRepository?.updateDevice(deviceId) {
                it.copy(lastSeenTimestamp = now, connectionState = ConnectionState.CONNECTED)
            }
            evaluateGeoFencesUseCase?.invoke(location)
        }
    }

    fun debugStepDevice(
        deviceId: TrackerId,
        deltaLat: Double,
        deltaLon: Double,
        stepSpeed: Float = 15f
    ) {
        viewModelScope.launch {
            val currentLocation = trackerLocationRepository?.getLocation(deviceId)?.first()
            val currentLat = currentLocation?.latitude ?: return@launch
            val currentLon = currentLocation?.longitude ?: return@launch
            val newLat = currentLat + deltaLat
            val newLon = currentLon + deltaLon
            val bearing = when {
                deltaLat > 0 && deltaLon == 0.0 -> 0f   // North
                deltaLat < 0 && deltaLon == 0.0 -> 180f // South
                deltaLon > 0 && deltaLat == 0.0 -> 90f  // East
                deltaLon < 0 && deltaLat == 0.0 -> 270f // West
                else -> currentLocation.bearing
            }
            debugMoveDevice(deviceId, newLat, newLon, stepSpeed, bearing)
        }
    }

    fun debugTeleportInsideGeofence(deviceId: TrackerId, geofence: GeoFence) {
        viewModelScope.launch {
            if (geofence.vertices.isEmpty()) return@launch
            val centerLat = geofence.vertices.map { it.latitude }.average()
            val centerLon = geofence.vertices.map { it.longitude }.average()
            debugMoveDevice(deviceId, centerLat, centerLon, speed = 20f, bearing = 45f)
        }
    }

    fun debugTeleportOutsideGeofence(deviceId: TrackerId, geofence: GeoFence) {
        viewModelScope.launch {
            if (geofence.vertices.isEmpty()) return@launch
            val maxLat = geofence.vertices.maxOf { it.latitude }
            val centerLon = geofence.vertices.map { it.longitude }.average()
            debugMoveDevice(deviceId, maxLat + 0.008, centerLon, speed = 35f, bearing = 0f)
        }
    }

    fun debugToggleDeviceConnection(deviceId: TrackerId) {
        viewModelScope.launch {
            val current = trackerDeviceRepository?.getDevice(deviceId) ?: return@launch
            val newState = if (current.connectionState == ConnectionState.CONNECTED) {
                ConnectionState.DISCONNECTED
            } else {
                ConnectionState.CONNECTED
            }
            trackerDeviceRepository?.updateDevice(deviceId) {
                it.copy(connectionState = newState, lastSeenTimestamp = System.currentTimeMillis())
            }
        }
    }

    fun debugRemoveDevice(deviceId: TrackerId) {
        viewModelScope.launch {
            trackerDeviceRepository?.unregisterDevice(deviceId)
            trackerLocationRepository?.clearLocation(deviceId)
            if (selectedMarker.value?.deviceId == deviceId) {
                selectMarker(null)
            }
        }
    }

    fun debugClearAllDevices() {
        viewModelScope.launch {
            val all = trackerDeviceRepository?.getAllDevices() ?: emptyList()
            all.forEach { dev ->
                trackerDeviceRepository?.unregisterDevice(dev.deviceId)
                trackerLocationRepository?.clearLocation(dev.deviceId)
            }
            selectMarker(null)
        }
    }

    fun debugSpawnPresetFleet() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val presets = listOf(
                Triple("alpha_van", "Alpha Van (Inside Safe Zone)", Pair(40.7125, -74.0060)),
                Triple("bravo_courier", "Bravo Courier (Inside NYU)", Pair(40.7300, -73.9870)),
                Triple("charlie_patrol", "Charlie Patrol (Outside Perimeter)", Pair(40.7600, -73.9750))
            )
            presets.forEachIndexed { index, (id, name, coords) ->
                val trackerId = TrackerId(id)
                val device = TrackerDevice(
                    deviceId = trackerId,
                    displayName = name,
                    deviceModel = if (index == 0) "Pixel 8 Pro" else if (index == 1) "Galaxy S24" else "OBD Tracker",
                    registrationTimestamp = now,
                    lastSeenTimestamp = now,
                    connectionState = ConnectionState.CONNECTED,
                    batteryLevel = 95 - (index * 15),
                    networkState = "Cellular 5G"
                )
                trackerDeviceRepository?.registerDevice(device)
                val location = Location(
                    deviceId = trackerId,
                    latitude = coords.first,
                    longitude = coords.second,
                    accuracy = 8f,
                    bearing = (index * 90f),
                    speed = (index * 12f),
                    timestamp = now,
                    state = LocationState.LIVE
                )
                trackerLocationRepository?.updateLocation(location)
                evaluateGeoFencesUseCase?.invoke(location)
            }
        }
    }
}
