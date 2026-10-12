package com.livetracker.controller.ui.screens.map.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberMarkerState
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.map.domain.MapMarker
import com.livetracker.controller.ui.screens.map.EsriTileSource
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import com.livetracker.controller.ui.screens.map.geofence.GeofenceDragMarker
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker as OsmMarker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon as OsmPolygon
import org.osmdroid.views.overlay.Polyline as OsmPolyline

/**
 * Universal, high-performance map canvas hosting OpenStreetMap, Google Maps,
 * Esri ArcGIS, and CARTO engines with hardware acceleration and differential rendering.
 */
@Composable
fun MapCanvas(
    mapConfiguration: MapConfiguration,
    markers: List<MapMarker>,
    geoFences: List<GeoFence>,
    selectedMarker: MapMarker?,
    googleCameraPositionState: CameraPositionState,
    onSelectMarker: (MapMarker) -> Unit,
    onMapTap: (latitude: Double, longitude: Double) -> Unit,
    onMapLongClick: (latitude: Double, longitude: Double) -> Unit,
    cartoApiKey: String = "",
    editingFenceId: String? = null,
    onVertexUpdate: (index: Int, lat: Double, lon: Double) -> Unit = { _, _, _ -> },
    onOsmMapReady: (MapView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    when (mapConfiguration.provider) {
        MapProvider.OPEN_STREET_MAP,
        MapProvider.ESRI_ARCGIS,
        MapProvider.CARTO -> {
            OsmMapCanvasView(
                context = context,
                provider = mapConfiguration.provider,
                style = mapConfiguration.style,
                cartoApiKey = cartoApiKey,
                markers = markers,
                geoFences = geoFences,
                selectedMarker = selectedMarker,
                editingFenceId = editingFenceId,
                onSelectMarker = onSelectMarker,
                onMapTap = onMapTap,
                onMapLongClick = onMapLongClick,
                onVertexUpdate = onVertexUpdate,
                onMapReady = onOsmMapReady,
                modifier = modifier
            )
        }
        MapProvider.GOOGLE_MAPS -> {
            GoogleMapCanvasView(
                cameraPositionState = googleCameraPositionState,
                style = mapConfiguration.style,
                markers = markers,
                geoFences = geoFences,
                selectedMarker = selectedMarker,
                editingFenceId = editingFenceId,
                onSelectMarker = onSelectMarker,
                onMapTap = onMapTap,
                onMapLongClick = onMapLongClick,
                onVertexUpdate = onVertexUpdate,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun OsmMapCanvasView(
    context: Context,
    provider: MapProvider,
    style: MapStyle,
    cartoApiKey: String,
    markers: List<MapMarker>,
    geoFences: List<GeoFence>,
    selectedMarker: MapMarker?,
    editingFenceId: String?,
    onSelectMarker: (MapMarker) -> Unit,
    onMapTap: (Double, Double) -> Unit,
    onMapLongClick: (Double, Double) -> Unit,
    onVertexUpdate: (Int, Double, Double) -> Unit,
    onMapReady: (MapView) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isInitialCentered by remember { mutableStateOf(false) }
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    // Resolve tile provider
    val tileSource = remember(provider, style, cartoApiKey) {
        com.livetracker.controller.ui.screens.map.offline.OfflineTileSources.resolveTileSource(provider, style, cartoApiKey)
    }

    // Overlay caches to prevent recreation churn
    val geoFenceOverlays = remember { mutableMapOf<String, OsmPolygon>() }
    val polylineOverlays = remember { mutableMapOf<String, OsmPolyline>() }
    val editMarkerOverlays = remember { mutableMapOf<Int, GeofenceDragMarker>() }
    val accuracyOverlays = remember { mutableMapOf<String, OsmPolygon>() }
    val markerOverlays = remember { mutableMapOf<String, OsmMarker>() }
    val accuracyStateCache = remember { mutableMapOf<String, Triple<Double, Double, Float>>() }

    val mapEventsOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onMapTap(p.latitude, p.longitude)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                onMapLongClick(p.latitude, p.longitude)
                return true
            }
        })
    }

    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(tileSource)
                setMultiTouchControls(true)
                isTilesScaledToDpi = false // Crisp 1:1 pixel tiles
                val maxZoom = tileSource.maximumZoomLevel.toDouble()
                val minZoom = tileSource.minimumZoomLevel.toDouble().coerceAtLeast(3.0)
                minZoomLevel = minZoom
                maxZoomLevel = maxZoom
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                isFlingEnabled = true
                controller.setZoom(14.0)

                if (markers.isNotEmpty()) {
                    val first = markers.first()
                    controller.setCenter(GeoPoint(first.latitude, first.longitude))
                } else {
                    controller.setCenter(GeoPoint(37.7749, -122.4194))
                }

                mapViewInstance = this
                onMapReady(this)
            }
        },
        update = { mapView ->
            var hasChanges = false

            // Synchronize zoom constraints to prevent "No data available" server errors
            val maxZoom = tileSource.maximumZoomLevel.toDouble()
            val minZoom = tileSource.minimumZoomLevel.toDouble().coerceAtLeast(3.0)
            if (mapView.maxZoomLevel != maxZoom) {
                mapView.maxZoomLevel = maxZoom
            }
            if (mapView.minZoomLevel != minZoom) {
                mapView.minZoomLevel = minZoom
            }
            if (mapView.zoomLevelDouble > maxZoom) {
                mapView.controller.setZoom(maxZoom)
            }

            if (mapView.tileProvider.tileSource != tileSource) {
                mapView.setTileSource(tileSource)
                hasChanges = true
            }

            // Tactical Dark Mode filter for OpenStreetMap DARK style
            if (provider == MapProvider.OPEN_STREET_MAP && style == MapStyle.DARK) {
                val darkMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    -0.78f,  0.00f,  0.00f, 0f, 215f,
                     0.00f, -0.78f,  0.00f, 0f, 220f,
                     0.00f,  0.00f, -0.78f, 0f, 230f,
                     0.00f,  0.00f,  0.00f, 1f,   0f
                ))
                mapView.overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(darkMatrix))
            } else {
                mapView.overlayManager.tilesOverlay.setColorFilter(null)
            }

            val overlayList = mutableListOf<Overlay>()
            overlayList.add(mapEventsOverlay)

            val activePolygons = mutableSetOf<String>()
            val activePolylines = mutableSetOf<String>()
            val activeEditMarkers = mutableSetOf<Int>()

            // Render Geofences
            geoFences.forEach { fence ->
                val points = fence.vertices.map { GeoPoint(it.latitude, it.longitude) }
                if (points.size >= 3) {
                    activePolygons.add(fence.id)
                    val polygon = geoFenceOverlays.getOrPut(fence.id) {
                        OsmPolygon(mapView).apply { infoWindow = null }
                    }
                    if (fence.id == editingFenceId || polygon.actualPoints.size != points.size) {
                        polygon.points = points
                        val baseColor = fence.colorArgb
                        val r = android.graphics.Color.red(baseColor)
                        val g = android.graphics.Color.green(baseColor)
                        val b = android.graphics.Color.blue(baseColor)
                        val isPreview = fence.id == editingFenceId
                        polygon.fillPaint.color = if (isPreview) {
                            android.graphics.Color.argb(70, r, g, b)
                        } else {
                            android.graphics.Color.argb(45, r, g, b)
                        }
                        polygon.outlinePaint.color = if (isPreview) {
                            baseColor
                        } else {
                            android.graphics.Color.argb(220, r, g, b)
                        }
                        polygon.outlinePaint.strokeWidth = if (isPreview) 7f else 4.5f
                    }
                    overlayList.add(polygon)
                } else if (points.size == 2) {
                    activePolylines.add(fence.id)
                    val polyline = polylineOverlays.getOrPut(fence.id) {
                        OsmPolyline(mapView)
                    }
                    if (fence.id == editingFenceId || polyline.actualPoints.size != points.size) {
                        polyline.setPoints(points)
                        polyline.outlinePaint.color = fence.colorArgb
                        polyline.outlinePaint.strokeWidth = 5f
                    }
                    overlayList.add(polyline)
                }

                // If actively editing this fence, render interactive draggable vertex nodes
                if (fence.id == editingFenceId) {
                    val density = context.resources.displayMetrics.density
                    val handleRadius = (8 * density).toInt()
                    val handleDiameter = handleRadius * 2

                    points.forEachIndexed { index, point ->
                        activeEditMarkers.add(index)
                        val dragMarker = editMarkerOverlays.getOrPut(index) {
                            GeofenceDragMarker(mapView) { newPos ->
                                onVertexUpdate(index, newPos.latitude, newPos.longitude)
                            }.apply {
                                setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_CENTER)
                            }
                        }

                        // Use cached / pre-warmed circular vertex handle drawable (zero per-frame allocation)
                        val handleDrawable = MapMarkerRenderer.getVertexHandleDrawable(context, fence.colorArgb)
                        if (dragMarker.icon != handleDrawable) {
                            dragMarker.icon = handleDrawable
                            hasChanges = true
                        }
                        if (dragMarker.position != point) {
                            dragMarker.position = point
                            hasChanges = true
                        }
                        overlayList.add(dragMarker)
                    }
                }
            }

            geoFenceOverlays.keys.retainAll(activePolygons)
            polylineOverlays.keys.retainAll(activePolylines)
            editMarkerOverlays.keys.retainAll(activeEditMarkers)

            // Render Accuracy Rings
            val activeAccuracyIds = mutableSetOf<String>()

            markers.forEach { marker ->
                val acc = marker.accuracy
                if (acc != null && acc > 0f) {
                    activeAccuracyIds.add(marker.deviceId.value)
                    val accuracyPolygon = accuracyOverlays.getOrPut(marker.deviceId.value) {
                        hasChanges = true
                        OsmPolygon(mapView).apply {
                            infoWindow = null
                            fillPaint.color = android.graphics.Color.argb(30, 16, 185, 129) // Emerald aura
                            outlinePaint.color = android.graphics.Color.argb(90, 16, 185, 129)
                            outlinePaint.strokeWidth = 2f
                        }
                    }
                    val prevState = accuracyStateCache[marker.deviceId.value]
                    val currState = Triple(marker.latitude, marker.longitude, acc)
                    if (prevState != currState) {
                        accuracyStateCache[marker.deviceId.value] = currState
                        accuracyPolygon.points = MapMarkerRenderer.getAccuracyCirclePoints(
                            marker.latitude,
                            marker.longitude,
                            acc.toDouble()
                        )
                        hasChanges = true
                    }
                    overlayList.add(accuracyPolygon)
                }
            }
            if (accuracyOverlays.keys.retainAll(activeAccuracyIds)) {
                hasChanges = true
            }
            accuracyStateCache.keys.retainAll(activeAccuracyIds)

            // Render Tracker Markers
            val activeMarkerIds = mutableSetOf<String>()
            markers.forEach { marker ->
                activeMarkerIds.add(marker.deviceId.value)
                val isSelected = marker.deviceId == selectedMarker?.deviceId
                val osmMarker = markerOverlays.getOrPut(marker.deviceId.value) {
                    hasChanges = true
                    OsmMarker(mapView).apply {
                        setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ ->
                            onSelectMarker(marker)
                            true
                        }
                    }
                }

                val newPos = GeoPoint(marker.latitude, marker.longitude)
                if (osmMarker.position != newPos) {
                    osmMarker.position = newPos
                    hasChanges = true
                }

                val newDrawable = MapMarkerRenderer.getMarkerDrawable(context, marker, isSelected)
                if (osmMarker.icon != newDrawable) {
                    osmMarker.icon = newDrawable
                    hasChanges = true
                }

                val targetRotation = marker.bearing?.let { -it } ?: 0f
                if (osmMarker.rotation != targetRotation) {
                    osmMarker.rotation = targetRotation
                    hasChanges = true
                }

                overlayList.add(osmMarker)
            }
            if (markerOverlays.keys.retainAll(activeMarkerIds)) {
                hasChanges = true
            }

            // High-performance incremental overlay reconciliation:
            // Avoids clearing and re-adding all overlays on every frame
            val currentOverlays = mapView.overlays
            val toRemove = currentOverlays.filter { it !in overlayList }
            if (toRemove.isNotEmpty()) {
                currentOverlays.removeAll(toRemove)
                hasChanges = true
            }
            overlayList.forEach { overlay ->
                if (overlay !in currentOverlays) {
                    currentOverlays.add(overlay)
                    hasChanges = true
                }
            }

            // Initial auto-centering on first load
            if (!isInitialCentered && markers.isNotEmpty()) {
                val first = markers.first()
                mapView.controller.setCenter(GeoPoint(first.latitude, first.longitude))
                isInitialCentered = true
                hasChanges = true
            }

            if (hasChanges) {
                mapView.invalidate()
            }
        },
        onRelease = { mapView ->
            mapView.onDetach()
        },
        modifier = modifier.fillMaxSize()
    )

    // OS Lifecycle observation for leak-free backgrounding
    DisposableEffect(lifecycleOwner, mapViewInstance) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewInstance?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewInstance?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
private fun GoogleMapCanvasView(
    cameraPositionState: CameraPositionState,
    style: MapStyle,
    markers: List<MapMarker>,
    geoFences: List<GeoFence>,
    selectedMarker: MapMarker?,
    editingFenceId: String?,
    onSelectMarker: (MapMarker) -> Unit,
    onMapTap: (Double, Double) -> Unit,
    onMapLongClick: (Double, Double) -> Unit,
    onVertexUpdate: (Int, Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val mapProperties = remember(style) {
        MapProperties(
            mapType = when (style) {
                MapStyle.SATELLITE -> MapType.SATELLITE
                MapStyle.TERRAIN -> MapType.TERRAIN
                MapStyle.HYBRID -> MapType.HYBRID
                else -> MapType.NORMAL
            }
        )
    }

    GoogleMap(
        onMapClick = { latLng -> onMapTap(latLng.latitude, latLng.longitude) },
        onMapLongClick = { latLng -> onMapLongClick(latLng.latitude, latLng.longitude) },
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = mapProperties
    ) {
        // Render Accuracy circles
        markers.forEach { marker ->
            val acc = marker.accuracy
            if (acc != null && acc > 0f) {
                Circle(
                    center = LatLng(marker.latitude, marker.longitude),
                    radius = acc.toDouble(),
                    fillColor = Color(0x1F10B981), // Emerald 12%
                    strokeColor = Color(0x6610B981),
                    strokeWidth = 2f
                )
            }
        }

        // Render Geofence polygons & lines
        geoFences.forEach { fence ->
            val points = fence.vertices.map { LatLng(it.latitude, it.longitude) }
            val fenceColor = Color(fence.colorArgb)

            if (points.size >= 3) {
                Polygon(
                    points = points,
                    fillColor = fenceColor.copy(alpha = 0.18f),
                    strokeColor = fenceColor.copy(alpha = 0.85f),
                    strokeWidth = 4f
                )
            } else if (points.size == 2) {
                Polyline(
                    points = points,
                    color = fenceColor.copy(alpha = 0.85f),
                    width = 4f
                )
            }

            // Draggable vertex handles when in active editing mode
            if (fence.id == editingFenceId) {
                points.forEachIndexed { index, point ->
                    val markerState = rememberMarkerState(key = "${fence.id}_$index", position = point)

                    LaunchedEffect(markerState.position) {
                        if (markerState.position.latitude != point.latitude ||
                            markerState.position.longitude != point.longitude
                        ) {
                            onVertexUpdate(index, markerState.position.latitude, markerState.position.longitude)
                        }
                    }

                    LaunchedEffect(point) {
                        if (markerState.position.latitude != point.latitude ||
                            markerState.position.longitude != point.longitude
                        ) {
                            markerState.position = point
                        }
                    }

                    Marker(
                        state = markerState,
                        draggable = true,
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                    )
                }
            }
        }

        // Render Markers
        markers.forEach { marker ->
            val isSelected = marker.deviceId == selectedMarker?.deviceId
            val iconDescriptor = MapMarkerRenderer.getMarkerBitmapDescriptor(context, marker, isSelected)
            val markerState = rememberMarkerState(
                key = marker.deviceId.value,
                position = LatLng(marker.latitude, marker.longitude)
            )
            val targetLatLng = LatLng(marker.latitude, marker.longitude)
            if (markerState.position != targetLatLng) {
                markerState.position = targetLatLng
            }

            Marker(
                state = markerState,
                icon = iconDescriptor,
                anchor = Offset(0.5f, 0.5f),
                rotation = marker.bearing ?: 0f,
                onClick = {
                    onSelectMarker(marker)
                    true
                }
            )
        }
    }
}
