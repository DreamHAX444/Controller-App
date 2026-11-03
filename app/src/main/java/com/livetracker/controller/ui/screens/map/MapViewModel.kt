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
import com.livetracker.controller.core.tracker.geofence.domain.AlertSoundProfile
import com.livetracker.controller.core.tracker.geofence.domain.DistanceCalculator
import com.livetracker.controller.core.tracker.geofence.domain.EvaluateGeoFencesUseCase
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceRepository
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceType
import com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D
import com.livetracker.controller.core.tracker.geofence.domain.GeofenceTriggerRule
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
 * and the interactive geofence state machine.
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

    private var pendingSingleSelection: TrackerId? = null

    init {
        viewModelScope.launch {
            mapState.collect { state ->
                val current = _selectedMarker.value
                when (state) {
                    is MapState.Active -> {
                        if (current != null) {
                            val updated = state.markers.find { it.deviceId == current.deviceId }
                            _selectedMarker.value = updated
                        } else if (pendingSingleSelection != null) {
                            val target = state.markers.find { it.deviceId == pendingSingleSelection }
                            if (target != null) {
                                _selectedMarker.value = target
                                pendingSingleSelection = null
                            }
                        }
                    }
                    MapState.Empty -> {
                        _selectedMarker.value = null
                    }
                    MapState.Initializing -> {}
                }
            }
        }
    }

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
        if (marker == null) {
            pendingSingleSelection = null
        }
    }

    // Layer Visibility
    fun toggleGeoFences() {
        _showGeoFences.value = !_showGeoFences.value
    }

    // Device Selection
    fun selectAllDevices() {
        deviceSelectionManager?.selectAll()
        pendingSingleSelection = null
    }

    fun selectSingleDevice(deviceId: TrackerId) {
        deviceSelectionManager?.selectDevice(deviceId)
        val marker = (mapState.value as? MapState.Active)?.markers?.find { it.deviceId == deviceId }
        _selectedMarker.value = marker
        pendingSingleSelection = if (marker == null) deviceId else null
    }

    fun selectMultipleDevices(deviceIds: Set<TrackerId>) {
        deviceSelectionManager?.selectMultipleDevices(deviceIds)
        pendingSingleSelection = null
        val currentSelectedId = _selectedMarker.value?.deviceId
        if (currentSelectedId != null && currentSelectedId !in deviceIds) {
            _selectedMarker.value = null
        }
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

    fun startCreatingGeofence(initialShape: GeoFenceType = GeoFenceType.POLYGON) {
        _geofenceEditorState.value = GeofenceEditorState.Editing(
            id = null,
            name = "",
            shapeType = initialShape,
            vertices = emptyList(),
            center = null,
            radiusMeters = 200.0,
            colorArgb = GEOFENCE_PRESET_COLORS.first(),
            isEnabled = true,
            triggerRule = GeofenceTriggerRule.BOTH,
            soundProfile = AlertSoundProfile.TACTICAL_SIREN,
            durationSeconds = 30,
            targetDeviceId = null,
            isVibrationEnabled = true
        )
    }

    fun startEditingGeofence(fence: GeoFence) {
        _geofenceEditorState.value = GeofenceEditorState.Editing(
            id = fence.id,
            name = fence.name,
            shapeType = fence.type,
            vertices = fence.vertices,
            center = fence.center,
            radiusMeters = fence.radiusMeters ?: 200.0,
            colorArgb = fence.colorArgb,
            isEnabled = fence.isEnabled,
            triggerRule = fence.triggerRule,
            soundProfile = fence.soundProfile,
            durationSeconds = fence.durationSeconds,
            targetDeviceId = fence.targetDeviceId,
            isVibrationEnabled = fence.isVibrationEnabled,
            originalFence = fence
        )
    }

    fun setEditingShapeType(shapeType: GeoFenceType) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        if (current.shapeType == shapeType) return
        _geofenceEditorState.value = current.copy(
            shapeType = shapeType,
            validationError = null
        )
    }

    fun setCircleCenter(latitude: Double, longitude: Double) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(
            center = GeoPoint2D(latitude, longitude),
            validationError = null
        )
    }

    fun setCircleRadius(radiusMeters: Double) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        val clamped = radiusMeters.coerceIn(10.0, 50000.0)
        _geofenceEditorState.value = current.copy(
            radiusMeters = clamped,
            validationError = null
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

    fun updateEditingTriggerRule(rule: GeofenceTriggerRule) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(triggerRule = rule)
    }

    fun updateEditingSoundProfile(profile: AlertSoundProfile) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(soundProfile = profile)
    }

    fun updateEditingDurationSeconds(seconds: Int) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(durationSeconds = seconds.coerceIn(5, 300))
    }

    fun updateEditingTargetDeviceId(targetDeviceId: TrackerId?) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(targetDeviceId = targetDeviceId)
    }

    fun updateEditingVibrationEnabled(enabled: Boolean) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(isVibrationEnabled = enabled)
    }

    fun addVertex(latitude: Double, longitude: Double) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        if (current.shapeType == GeoFenceType.CIRCLE) {
            setCircleCenter(latitude, longitude)
            return
        }
        // Suppress accidental duplicate/stacked vertices tapped within 8 meters of an existing vertex
        val isNearExistingVertex = current.vertices.any { v ->
            DistanceCalculator.calculateDistanceMeters(latitude, longitude, v.latitude, v.longitude) < 8.0
        }
        if (isNearExistingVertex) return

        val updatedVertices = current.vertices + GeoPoint2D(latitude, longitude)
        _geofenceEditorState.value = current.copy(vertices = updatedVertices, validationError = null)
    }

    fun updateVertex(index: Int, latitude: Double, longitude: Double) {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        if (current.shapeType == GeoFenceType.CIRCLE) {
            setCircleCenter(latitude, longitude)
            return
        }
        if (index in current.vertices.indices) {
            val updatedVertices = current.vertices.toMutableList()
            updatedVertices[index] = GeoPoint2D(latitude, longitude)
            _geofenceEditorState.value = current.copy(vertices = updatedVertices)
        }
    }

    fun undoLastVertex() {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        if (current.shapeType == GeoFenceType.CIRCLE) {
            _geofenceEditorState.value = current.copy(center = null)
            return
        }
        if (current.vertices.isNotEmpty()) {
            _geofenceEditorState.value = current.copy(vertices = current.vertices.dropLast(1))
        }
    }

    fun clearVertices() {
        val current = _geofenceEditorState.value as? GeofenceEditorState.Editing ?: return
        _geofenceEditorState.value = current.copy(vertices = emptyList(), center = null)
    }

    fun onMapLongClick(latitude: Double, longitude: Double) {
        if (_geofenceEditorState.value is GeofenceEditorState.Editing) return

        viewModelScope.launch {
            val fences = if (geoFences.value.isNotEmpty()) {
                geoFences.value
            } else {
                geoFenceRepository.getGeoFences()
            }

            val hitFence = fences.find { fence ->
                when (fence.type) {
                    GeoFenceType.CIRCLE -> {
                        val center = fence.center
                        val radius = fence.radiusMeters
                        if (center != null && radius != null && radius > 0.0) {
                            DistanceCalculator.calculateDistanceMeters(
                                latitude, longitude,
                                center.latitude, center.longitude
                            ) <= radius
                        } else false
                    }
                    GeoFenceType.POLYGON -> {
                        fence.vertices.size >= 3 && isPointInPolygon(latitude, longitude, fence.vertices)
                    }
                }
            }

            if (hitFence != null) {
                startEditingGeofence(hitFence)
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

        val fence = when (current.shapeType) {
            GeoFenceType.POLYGON -> {
                if (current.vertices.size < 3) {
                    _geofenceEditorState.value = current.copy(validationError = "A polygon requires at least 3 vertices")
                    return
                }
                GeoFence.createPolygon(
                    id = current.id ?: UUID.randomUUID().toString(),
                    name = current.name.trim(),
                    vertices = current.vertices,
                    colorArgb = current.colorArgb,
                    isEnabled = current.isEnabled,
                    triggerRule = current.triggerRule,
                    soundProfile = current.soundProfile,
                    durationSeconds = current.durationSeconds,
                    targetDeviceId = current.targetDeviceId,
                    isVibrationEnabled = current.isVibrationEnabled
                )
            }
            GeoFenceType.CIRCLE -> {
                val center = current.center
                if (center == null) {
                    _geofenceEditorState.value = current.copy(validationError = "Please tap on the map to set the circle center")
                    return
                }
                if (current.radiusMeters <= 0.0 || !current.radiusMeters.isFinite()) {
                    _geofenceEditorState.value = current.copy(validationError = "Please specify a positive radius")
                    return
                }
                GeoFence.createCircle(
                    id = current.id ?: UUID.randomUUID().toString(),
                    name = current.name.trim(),
                    center = center,
                    radiusMeters = current.radiusMeters,
                    colorArgb = current.colorArgb,
                    isEnabled = current.isEnabled,
                    triggerRule = current.triggerRule,
                    soundProfile = current.soundProfile,
                    durationSeconds = current.durationSeconds,
                    targetDeviceId = current.targetDeviceId,
                    isVibrationEnabled = current.isVibrationEnabled
                )
            }
        }

        viewModelScope.launch {
            geoFenceRepository.addGeoFence(fence)
            _geoFencesRefreshTrigger.value++
            _geofenceEditorState.value = GeofenceEditorState.ListMode

            // Re-evaluate fence against all active device locations immediately
            try {
                val locRepo = trackerLocationRepository
                val evalUseCase = evaluateGeoFencesUseCase
                if (locRepo != null && evalUseCase != null) {
                    val currentLocations = locRepo.getAllLocations().first()
                    for (loc in currentLocations) {
                        evalUseCase(loc)
                    }
                }
            } catch (_: Exception) {}
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

    val unacknowledgedAlerts: StateFlow<List<Alert>> = alertRepository?.observeUnacknowledgedAlerts()
        ?.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        ) ?: MutableStateFlow<List<Alert>>(emptyList()).asStateFlow()
}
