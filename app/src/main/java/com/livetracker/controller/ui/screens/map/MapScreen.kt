package com.livetracker.controller.ui.screens.map

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.rememberCameraPositionState
import com.livetracker.controller.TraceApplication
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceType
import com.livetracker.controller.core.tracker.map.domain.MapMarker
import com.livetracker.controller.core.tracker.map.domain.MapState
import com.livetracker.controller.ui.screens.map.components.DeviceSelectionSheet
import com.livetracker.controller.ui.screens.map.components.DeviceTelemetryCard
import com.livetracker.controller.ui.screens.map.components.GestureOnboardingOverlay
import com.livetracker.controller.ui.screens.map.components.MapCanvas
import com.livetracker.controller.ui.screens.map.components.MapTopBar
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.geofence.GeofenceEditorHud
import com.livetracker.controller.ui.screens.map.geofence.GeofenceEditorState
import com.livetracker.controller.ui.screens.map.geofence.GeofenceListSheet
import com.livetracker.controller.ui.util.DeviceNameFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Master Map Dashboard Screen.
 * Fully animated, unified visual design system adhering to UI/UX Pro Max intelligence:
 * 100% immersive edge-to-edge canvas, fluid entrance/exit animations for all sheets,
 * seamless cross-device switching transitions, and real-time polygon preview editing.
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {}
) {
    val mapState by viewModel.mapState.collectAsStateWithLifecycle()
    val geoFences by viewModel.geoFences.collectAsStateWithLifecycle()
    val showGeoFences by viewModel.showGeoFences.collectAsStateWithLifecycle()
    val mapConfiguration by viewModel.mapConfiguration.collectAsStateWithLifecycle()
    val cartoApiKey by viewModel.cartoApiKey.collectAsStateWithLifecycle()
    val editorState by viewModel.geofenceEditorState.collectAsStateWithLifecycle()
    val selectedMarker by viewModel.selectedMarker.collectAsStateWithLifecycle()
    val deviceSelection by viewModel.deviceSelection.collectAsStateWithLifecycle()
    val deviceSummaries by viewModel.deviceSummaries.collectAsStateWithLifecycle()
    val unacknowledgedAlerts by viewModel.unacknowledgedAlerts.collectAsStateWithLifecycle()
    val hasSeenGestureCoach by viewModel.hasSeenGestureCoach.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val app = context.applicationContext as? TraceApplication
    val activeAlarmState by (app?.sirenAlertEngine?.activeAlarm?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) })
    val isTestingSound = activeAlarmState?.alertId?.startsWith("preview_") == true

    var showDeviceSelector by remember { mutableStateOf(false) }
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    var hasRunIntroCameraAnimation by rememberSaveable { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(120)
        hudVisible = true
    }

    // Safe Insets calculation for edge-to-edge camera cutout & gesture bars
    val layoutDirection = LocalLayoutDirection.current
    val cutoutInsets = WindowInsets.displayCutout.asPaddingValues()
    val statusBarInsets = WindowInsets.statusBars.asPaddingValues()
    val safeTop = maxOf(cutoutInsets.calculateTopPadding(), statusBarInsets.calculateTopPadding())
    val topPadding = if (safeTop > 0.dp) safeTop + 8.dp else 16.dp
    val startPadding = maxOf(16.dp, cutoutInsets.calculateStartPadding(layoutDirection) + 8.dp)
    val endPadding = maxOf(16.dp, cutoutInsets.calculateEndPadding(layoutDirection) + 8.dp)

    val handleEditorCancelOrBack = {
        val editingState = editorState as? GeofenceEditorState.Editing
        if (editingState != null && editingState.isDirty) {
            showDiscardConfirmDialog = true
        } else {
            viewModel.closeGeofenceEditor()
        }
    }

    // Hardware & Gesture Back Button Hierarchy
    BackHandler(enabled = showDeviceSelector) {
        showDeviceSelector = false
    }
    BackHandler(enabled = !showDeviceSelector && editorState is GeofenceEditorState.Editing) {
        handleEditorCancelOrBack()
    }
    BackHandler(enabled = !showDeviceSelector && editorState is GeofenceEditorState.ListMode) {
        viewModel.closeGeofenceEditor()
    }
    BackHandler(enabled = !showDeviceSelector && editorState is GeofenceEditorState.Idle && selectedMarker != null) {
        viewModel.selectMarker(null)
    }

    // All markers from current mapState
    val allMarkers = remember(mapState) {
        if (mapState is MapState.Active) {
            (mapState as MapState.Active).markers
        } else {
            emptyList()
        }
    }

    // Active markers filtered according to device selection
    val activeMarkers = remember(mapState) {
        if (mapState is MapState.Active) {
            (mapState as MapState.Active).markers.filter { it.isSelected }
        } else {
            emptyList()
        }
    }

    // Displayed geofences:
    // Only enabled geofences appear on the map in normal mode.
    // When in edit mode, the active edited zone is shown as a live preview polygon with real-time updates.
    val displayGeoFences = remember(geoFences, showGeoFences, editorState) {
        val list = if (showGeoFences) {
            geoFences.filter { it.isEnabled }.toMutableList()
        } else {
            mutableListOf()
        }

        if (editorState is GeofenceEditorState.Editing) {
            val editing = editorState as GeofenceEditorState.Editing
            // Remove the previous saved version of the fence being edited so the preview replaces it cleanly
            list.removeAll { it.id == editing.id }
            if (editing.shapeType == GeoFenceType.CIRCLE) {
                if (editing.center != null && editing.radiusMeters > 0.0) {
                    list.add(
                        GeoFence.createCircle(
                            id = editing.id ?: "draft_preview",
                            name = editing.name.ifBlank { "New Zone" },
                            center = editing.center!!,
                            radiusMeters = editing.radiusMeters,
                            colorArgb = editing.colorArgb,
                            isEnabled = true
                        )
                    )
                }
            } else {
                if (editing.vertices.isNotEmpty()) {
                    list.add(
                        GeoFence(
                            id = editing.id ?: "draft_preview",
                            name = editing.name.ifBlank { "New Zone" },
                            vertices = editing.vertices,
                            colorArgb = editing.colorArgb,
                            isEnabled = true
                        )
                    )
                }
            }
        }
        list
    }

    // Camera states & persistent memory cache
    val savedCamera = remember { viewModel.getLastCameraPosition() }
    val initialLatLng = remember(activeMarkers, savedCamera) {
        if (savedCamera != null) {
            LatLng(savedCamera.first, savedCamera.second)
        } else if (activeMarkers.isNotEmpty()) {
            LatLng(activeMarkers.first().latitude, activeMarkers.first().longitude)
        } else {
            LatLng(37.7749, -122.4194)
        }
    }
    val initialZoom = savedCamera?.third?.toFloat() ?: 14f
    val googleCameraState = rememberCameraPositionState {
        position = if (!hasRunIntroCameraAnimation) {
            val startZoom = if (savedCamera != null) {
                (savedCamera.third.toFloat() - 3.5f).coerceAtLeast(10f)
            } else 11.5f
            CameraPosition.fromLatLngZoom(initialLatLng, startZoom)
        } else {
            CameraPosition.fromLatLngZoom(initialLatLng, initialZoom)
        }
    }
    var osmMapView by remember { mutableStateOf<MapView?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Smooth camera refocus helper with state persistence
    fun focusOnCoordinate(lat: Double, lon: Double, zoom: Float = 15.5f) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || lat.isNaN() || lon.isNaN() || lat.isInfinite() || lon.isInfinite()) return
        viewModel.saveCameraPosition(lat, lon, zoom.toDouble())
        if (mapConfiguration.provider != MapProvider.GOOGLE_MAPS) {
            osmMapView?.controller?.animateTo(GeoPoint(lat, lon), zoom.toDouble(), 650L)
        } else {
            coroutineScope.launch {
                googleCameraState.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom),
                    650
                )
            }
        }
    }

    fun recenterOnAll() {
        val target = selectedMarker ?: activeMarkers.firstOrNull()
        if (target != null) {
            focusOnCoordinate(target.latitude, target.longitude)
        }
    }

    // Reactive starter fly-in when markers become available on first attempt
    LaunchedEffect(activeMarkers.isNotEmpty()) {
        if (activeMarkers.isNotEmpty() && !hasRunIntroCameraAnimation) {
            val target = selectedMarker ?: activeMarkers.first()
            if (selectedMarker == null) {
                viewModel.selectMarker(target)
            }
            if (savedCamera == null) {
                if (mapConfiguration.provider != MapProvider.GOOGLE_MAPS) {
                    osmMapView?.let { mapView ->
                        mapView.controller.setCenter(GeoPoint(target.latitude, target.longitude))
                        mapView.controller.setZoom(11.5)
                        mapView.postDelayed({
                            mapView.controller.animateTo(GeoPoint(target.latitude, target.longitude), 15.5, 1100L)
                            hasRunIntroCameraAnimation = true
                        }, 180L)
                    }
                } else {
                    googleCameraState.position = CameraPosition.fromLatLngZoom(LatLng(target.latitude, target.longitude), 11.5f)
                    delay(180)
                    googleCameraState.animate(
                        CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), 15.5f),
                        1100
                    )
                    hasRunIntroCameraAnimation = true
                }
            }
        }
    }

    // Google Maps initial fly-in when saved camera or focused marker exists
    LaunchedEffect(Unit) {
        if (!hasRunIntroCameraAnimation && mapConfiguration.provider == MapProvider.GOOGLE_MAPS) {
            val targetLat = selectedMarker?.latitude ?: savedCamera?.first ?: activeMarkers.firstOrNull()?.latitude ?: 37.7749
            val targetLon = selectedMarker?.longitude ?: savedCamera?.second ?: activeMarkers.firstOrNull()?.longitude ?: -122.4194
            val targetZoom = (savedCamera?.third ?: 15.5).toFloat()
            delay(180)
            googleCameraState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(targetLat, targetLon), targetZoom),
                1100
            )
            hasRunIntroCameraAnimation = true
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Master Map Canvas (OpenStreetMap / Google Maps / Esri / CARTO)
        MapCanvas(
            mapConfiguration = mapConfiguration,
            cartoApiKey = cartoApiKey,
            markers = activeMarkers,
            geoFences = displayGeoFences,
            selectedMarker = selectedMarker,
            googleCameraPositionState = googleCameraState,
            onSelectMarker = { marker ->
                viewModel.selectMarker(marker)
                focusOnCoordinate(marker.latitude, marker.longitude)
            },
            onMapTap = { lat, lon ->
                if (editorState is GeofenceEditorState.Editing) {
                    viewModel.addVertex(lat, lon)
                } else if (showDeviceSelector) {
                    showDeviceSelector = false
                }
            },
            onMapLongClick = { lat, lon ->
                viewModel.onMapLongClick(lat, lon)
            },
            editingFenceId = (editorState as? GeofenceEditorState.Editing)?.let { it.id ?: "draft_preview" },
            onVertexUpdate = { index, lat, lon ->
                viewModel.updateVertex(index, lat, lon)
            },
            onOsmMapReady = { mapView ->
                osmMapView = mapView
                val targetLat = selectedMarker?.latitude ?: savedCamera?.first ?: activeMarkers.firstOrNull()?.latitude ?: 37.7749
                val targetLon = selectedMarker?.longitude ?: savedCamera?.second ?: activeMarkers.firstOrNull()?.longitude ?: -122.4194
                val targetZoom = savedCamera?.third ?: 15.5

                if (!hasRunIntroCameraAnimation) {
                    val startZoom = if (savedCamera != null) {
                        (savedCamera.third - 3.5).coerceAtLeast(10.0)
                    } else 11.5
                    mapView.controller.setCenter(GeoPoint(targetLat, targetLon))
                    mapView.controller.setZoom(startZoom)
                    mapView.postDelayed({
                        mapView.controller.animateTo(GeoPoint(targetLat, targetLon), targetZoom, 1100L)
                        hasRunIntroCameraAnimation = true
                    }, 180L)
                } else {
                    mapView.controller.setCenter(GeoPoint(targetLat, targetLon))
                    mapView.controller.setZoom(targetZoom)
                }
            }
        )

        // 2. Dismiss scrim when device selector dropdown is active
        if (showDeviceSelector) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showDeviceSelector = false }
            )
        }

        // 3. Top Floating Navigation Bar (Menu, Dynamic Island Capsule, Geofences Launcher)
        AnimatedVisibility(
            visible = hudVisible && editorState !is GeofenceEditorState.Editing,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f)
            ) + fadeIn(tween(280)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)
            ) + fadeOut(tween(200))
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MapTopBar(
                    deviceSelection = deviceSelection,
                    deviceSummaries = deviceSummaries,
                    geofenceCount = geoFences.filter { it.isEnabled }.size,
                    onOpenDrawer = onOpenDrawer,
                    onOpenDeviceSelector = { showDeviceSelector = !showDeviceSelector },
                    onOpenGeofences = {
                        viewModel.openGeofenceList()
                        viewModel.selectMarker(null)
                    },
                    topPadding = topPadding,
                    startPadding = startPadding,
                    endPadding = endPadding,
                    focusedDeviceName = selectedMarker?.let {
                        DeviceNameFormatter.formatFriendlyName(it.displayName, it.deviceId.value)
                    },
                    onSwipeTracker = { direction ->
                        if (activeMarkers.size > 1) {
                            val currentIdx = activeMarkers.indexOfFirst { it.deviceId == selectedMarker?.deviceId }.coerceAtLeast(0)
                            val nextIdx = ((currentIdx + direction) % activeMarkers.size + activeMarkers.size) % activeMarkers.size
                            val nextMarker = activeMarkers[nextIdx]
                            viewModel.selectMarker(nextMarker)
                            focusOnCoordinate(nextMarker.latitude, nextMarker.longitude)
                        }
                    }
                )

                // Dropdown Device Selection Modal (with fluid top-down animation)
                DeviceSelectionSheet(
                    isVisible = showDeviceSelector,
                    deviceSelection = deviceSelection,
                    deviceSummaries = deviceSummaries,
                    onSelectAll = {
                        viewModel.selectAllDevices()
                        recenterOnAll()
                    },
                    onSelectSingle = { id ->
                        viewModel.selectSingleDevice(id)
                        val target = allMarkers.find { it.deviceId == id } ?: run {
                            val summary = deviceSummaries.find { it.device.deviceId == id }
                            val loc = summary?.latestLocation
                            if (loc != null && summary.locationState != com.livetracker.controller.core.tracker.location.domain.LocationState.UNKNOWN) {
                                MapMarker(
                                    deviceId = summary.device.deviceId,
                                    displayName = summary.device.displayName,
                                    latitude = loc.latitude,
                                    longitude = loc.longitude,
                                    locationState = summary.locationState,
                                    accuracy = loc.accuracy,
                                    bearing = loc.bearing,
                                    speed = loc.speed,
                                    timestamp = loc.timestamp,
                                    connectionState = summary.device.connectionState,
                                    healthState = summary.device.health,
                                    isSelected = true,
                                    batteryLevel = summary.device.batteryLevel,
                                    networkState = summary.device.networkState
                                )
                            } else null
                        }
                        viewModel.selectMarker(target)
                        if (target != null) {
                            focusOnCoordinate(target.latitude, target.longitude, 16.5f)
                        }
                    },
                    onToggleMultiple = { id -> viewModel.toggleDeviceInMultipleSelection(id) },
                    onDismiss = { showDeviceSelector = false },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // 4. Geofence Manager Bottom Sheet (with smooth bottom slide in/out animations)
        AnimatedVisibility(
            visible = editorState is GeofenceEditorState.ListMode,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f)
            ) + fadeIn(tween(240)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)
            ) + fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            GeofenceListSheet(
                geoFences = geoFences,
                onDismiss = { viewModel.closeGeofenceEditor() },
                onCreateNew = { shape -> viewModel.startCreatingGeofence(shape) },
                onEdit = { fence -> viewModel.startEditingGeofence(fence) },
                onDelete = { id -> viewModel.deleteGeofence(id) },
                onToggleEnabled = { id, enabled -> viewModel.toggleGeofenceEnabled(id, enabled) }
            )
        }

        // 5. Interactive Geofence Editor HUD (Drawing Mode with animated entry/exit)
        AnimatedVisibility(
            visible = editorState is GeofenceEditorState.Editing,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(200))
        ) {
            (editorState as? GeofenceEditorState.Editing)?.let { editingState ->
                GeofenceEditorHud(
                    state = editingState,
                    onNameChange = { viewModel.updateEditingName(it) },
                    onShapeChange = { viewModel.setEditingShapeType(it) },
                    onRadiusChange = { viewModel.setCircleRadius(it) },
                    onColorChange = { viewModel.updateEditingColor(it) },
                    onEnabledChange = { viewModel.updateEditingEnabled(it) },
                    onTriggerRuleChange = { viewModel.updateEditingTriggerRule(it) },
                    onSoundProfileChange = { viewModel.updateEditingSoundProfile(it) },
                    onDurationChange = { viewModel.updateEditingDurationSeconds(it) },
                    onVibrationChange = { viewModel.updateEditingVibrationEnabled(it) },
                    onTestSound = { profile ->
                        app?.sirenAlertEngine?.testSoundProfile(profile, durationSeconds = 5)
                    },
                    onStopSound = {
                        app?.sirenAlertEngine?.dismissAlarm()
                    },
                    isTestingSound = isTestingSound,
                    onUndo = { viewModel.undoLastVertex() },
                    onClear = { viewModel.clearVertices() },
                    onCancel = handleEditorCancelOrBack,
                    onSave = { viewModel.saveGeofence() },
                    topPadding = topPadding,
                    startPadding = startPadding,
                    endPadding = endPadding,
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }

        // 6. Selected Device Live Telemetry Pager
        // Strictly bound to activeMarkers (selected devices only) so unselected devices never appear in the swipe pager.
        val effectiveSwipeableMarkers: List<MapMarker> = remember(activeMarkers) {
            activeMarkers
        }

        // Retain last known markers during exit animation so card doesn't flicker when dismissed
        val cachedSwipeableMarkersRef = remember { object { var value: List<MapMarker> = emptyList() } }
        val displayMarkers: List<MapMarker> = if (effectiveSwipeableMarkers.isNotEmpty()) {
            cachedSwipeableMarkersRef.value = effectiveSwipeableMarkers
            effectiveSwipeableMarkers
        } else {
            cachedSwipeableMarkersRef.value
        }

        val pagerState = rememberPagerState(
            initialPage = 0
        ) {
            displayMarkers.size
        }

        // When a marker is selected from the map, top bar swipe, or multi-select auto-switch, scroll pager and focus camera
        val targetDeviceId = selectedMarker?.deviceId
        LaunchedEffect(targetDeviceId, displayMarkers.size) {
            if (targetDeviceId == null) return@LaunchedEffect
            val targetIndex = displayMarkers.indexOfFirst { it.deviceId == targetDeviceId }
            if (targetIndex >= 0) {
                if (targetIndex != pagerState.currentPage && !pagerState.isScrollInProgress) {
                    pagerState.animateScrollToPage(targetIndex)
                }
                val targetMarker = displayMarkers[targetIndex]
                focusOnCoordinate(targetMarker.latitude, targetMarker.longitude)
            }
        }

        // When the user swipes between cards, update the selected marker and refocus the map
        LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
            if (!pagerState.isScrollInProgress && displayMarkers.isNotEmpty()) {
                val current = displayMarkers.getOrNull(pagerState.currentPage)
                if (current != null && current.deviceId != selectedMarker?.deviceId) {
                    viewModel.selectMarker(current)
                    if (deviceSelection is DeviceSelection.Single) {
                        viewModel.selectSingleDevice(current.deviceId)
                    }
                    focusOnCoordinate(current.latitude, current.longitude)
                }
            }
        }

        AnimatedVisibility(
            visible = hudVisible && selectedMarker != null && editorState is GeofenceEditorState.Idle,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f)
            ) + fadeIn(tween(240)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f)
            ) + fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            if (displayMarkers.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    key = { index -> displayMarkers[index].deviceId.value },
                    pageSpacing = 12.dp,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    val marker = displayMarkers[page]
                    DeviceTelemetryCard(
                        marker = marker,
                        deviceIndex = page,
                        totalDeviceCount = displayMarkers.size,
                        onDismiss = { viewModel.selectMarker(null) },
                        onCenterOnDevice = { focusOnCoordinate(marker.latitude, marker.longitude, 16.5f) },
                        onDoubleTapZoom = { focusOnCoordinate(marker.latitude, marker.longitude, 17.8f) },
                        onPreviousDevice = if (page > 0) {
                            { coroutineScope.launch { pagerState.animateScrollToPage(page - 1) } }
                        } else null,
                        onNextDevice = if (page < displayMarkers.size - 1) {
                            { coroutineScope.launch { pagerState.animateScrollToPage(page + 1) } }
                        } else null
                    )
                }
            }
        }

        // 7. Minimal Empty / Loading State Indicator
        AnimatedVisibility(
            visible = hudVisible && activeMarkers.isEmpty() && editorState is GeofenceEditorState.Idle,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                shadowElevation = 4.dp,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                if (mapState is MapState.Initializing) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Loading tracker locations...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    val statusText = when {
                        deviceSummaries.isEmpty() -> "No registered trackers available"
                        deviceSelection is DeviceSelection.Empty -> "No trackers selected in current filter"
                        deviceSelection is DeviceSelection.Single -> {
                            val selectedId = (deviceSelection as DeviceSelection.Single).deviceId
                            val summary = deviceSummaries.find { it.device.deviceId == selectedId }
                            if (summary == null) {
                                "Selected tracker not found"
                            } else if (summary.device.connectionState != com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED) {
                                "${com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(summary.device.displayName, summary.device.deviceId.value)} is offline (no location fix)"
                            } else {
                                "Waiting for GPS fix from ${com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(summary.device.displayName, summary.device.deviceId.value)}..."
                            }
                        }
                        else -> "No location data available for selected trackers"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }
            }
        }

        // 8. Interactive First-Time Gesture Coach Overlay
        AnimatedVisibility(
            visible = hudVisible && !hasSeenGestureCoach && editorState is GeofenceEditorState.Idle,
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(220))
        ) {
            GestureOnboardingOverlay(
                onComplete = { viewModel.setHasSeenGestureCoach(true) }
            )
        }
    }

    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = {
                Text(
                    text = "Discard Unsaved Changes?",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = "You have unsaved changes to this geofence perimeter. If you leave now, all your edits will be lost.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmDialog = false
                        viewModel.closeGeofenceEditor()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Discard", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmDialog = false
                    }
                ) {
                    Text("Keep Editing", fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}
