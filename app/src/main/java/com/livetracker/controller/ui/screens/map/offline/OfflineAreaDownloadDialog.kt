package com.livetracker.controller.ui.screens.map.offline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker as OsmMarker
import org.osmdroid.views.overlay.Polygon as OsmPolygon
import java.util.Locale

/**
 * Ultra-minimalist, map-first, viewfinder-centric Offline Area Download Mode.
 * Designed according to UI/UX Pro Max intelligence:
 * - Edge-to-edge full screen map canvas.
 * - Cinematic outer dark scrim (42% alpha) with transparent cutout spotlighting the selection box.
 * - Tactical camera-style corner reticle brackets with animated limit border.
 * - Zero center obstruction inside the box for 100% clear view of terrain and roads.
 * - Interactive Detail / Zoom Level Selector allowing user to choose high-res street/building detail (z15–z19).
 * - Compact floating HUD capsule at the top (46dp).
 * - Compact floating action bar at the bottom with inline naming and download trigger.
 * - Exact mathematical pixel-to-GeoPoint projection calculations.
 */
@Composable
fun OfflineAreaDownloadDialog(
    initialCenterLat: Double = 40.7128,
    initialCenterLon: Double = -74.0060,
    savedRegions: List<SavedOfflineRegion> = emptyList(),
    onDismiss: () -> Unit,
    onConfirmDownload: (OfflineRegion) -> Unit
) {
    val context = LocalContext.current
    var regionName by remember { mutableStateOf("Sector ${savedRegions.size + 1}") }
    var northLat by remember { mutableDoubleStateOf(initialCenterLat + 0.05) }
    var southLat by remember { mutableDoubleStateOf(initialCenterLat - 0.05) }
    var eastLon by remember { mutableDoubleStateOf(initialCenterLon + 0.06) }
    var westLon by remember { mutableDoubleStateOf(initialCenterLon - 0.06) }
    var estimatedTiles by remember { mutableIntStateOf(0) }
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    // User-selectable zoom detail levels (minZoom = 12 overview, maxZoom = 15..19 detail)
    var selectedMinZoom by remember { mutableIntStateOf(OfflineMapConstants.DEFAULT_MIN_ZOOM) }
    var selectedMaxZoom by remember { mutableIntStateOf(OfflineMapConstants.DEFAULT_MAX_ZOOM) }

    val zoomDetailDescription = when (selectedMaxZoom) {
        15 -> "Districts & Main Roads"
        16 -> "Streets & Blocks (Standard)"
        17 -> "Buildings & Alleys (High Detail)"
        18 -> "Footpaths & Numbers (Ultra Detail)"
        19 -> "Maximum Precision (OSM Max)"
        else -> "Custom Detail"
    }

    val handler = remember { Handler(Looper.getMainLooper()) }

    // Free device storage computation
    val freeStorageBytes = remember {
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            -1L
        }
    }
    val freeStorageGb = if (freeStorageBytes > 0) freeStorageBytes / (1024f * 1024f * 1024f) else null

    val isWithinLimit = estimatedTiles in 1..OfflineMapConstants.MAX_OFFLINE_TILES_LIMIT
    val isOverLimit = estimatedTiles > OfflineMapConstants.MAX_OFFLINE_TILES_LIMIT
    val estimatedMb = (estimatedTiles * OfflineMapConstants.ESTIMATED_BYTES_PER_TILE) / (1024f * 1024f)

    // Animated viewfinder border color: Emerald when ready, Crimson when over limit
    val currentBorderColor by animateColorAsState(
        targetValue = if (isOverLimit) Color(0xFFEF4444) else Color(0xFF10B981),
        animationSpec = tween(durationMillis = 250),
        label = "viewfinderBorderColor"
    )

    // Overlap detection with existing downloaded regions
    val overlappingRegion = remember(northLat, southLat, eastLon, westLon, savedRegions) {
        savedRegions.firstOrNull { saved ->
            northLat > saved.south &&
            southLat < saved.north &&
            eastLon > saved.west &&
            westLon < saved.east
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            mapViewInstance?.onDetach()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val density = LocalDensity.current
            val screenWidth = maxWidth
            val screenHeight = maxHeight

            // Viewfinder box dimensions: 84% width, 50% height
            val boxWidthDp = screenWidth * 0.84f
            val boxHeightDp = screenHeight * 0.50f

            // Center vertically raised (43% of height) to leave room for the bottom action card
            val boxCenterYDp = screenHeight * 0.43f
            val boxCenterXDp = screenWidth * 0.50f

            val boxLeftDp = boxCenterXDp - (boxWidthDp / 2f)
            val boxTopDp = boxCenterYDp - (boxHeightDp / 2f)
            val boxRightDp = boxCenterXDp + (boxWidthDp / 2f)
            val boxBottomDp = boxCenterYDp + (boxHeightDp / 2f)

            val boxLeftPx = with(density) { boxLeftDp.toPx() }
            val boxTopPx = with(density) { boxTopDp.toPx() }
            val boxRightPx = with(density) { boxRightDp.toPx() }
            val boxBottomPx = with(density) { boxBottomDp.toPx() }

            // Debounced geographic bounds calculation using exact on-screen pixel projection
            val updateDebounceRunnable = remember(boxLeftPx, boxTopPx, boxRightPx, boxBottomPx, selectedMinZoom, selectedMaxZoom) {
                Runnable {
                    val mapView = mapViewInstance ?: return@Runnable
                    val projection = mapView.projection ?: return@Runnable

                    try {
                        val nw = projection.fromPixels(boxLeftPx.toInt(), boxTopPx.toInt()) as? GeoPoint
                        val se = projection.fromPixels(boxRightPx.toInt(), boxBottomPx.toInt()) as? GeoPoint
                        if (nw != null && se != null) {
                            val n = maxOf(nw.latitude, se.latitude).coerceIn(-85.0, 85.0)
                            val s = minOf(nw.latitude, se.latitude).coerceIn(-85.0, 85.0)
                            val e = maxOf(nw.longitude, se.longitude).coerceIn(-180.0, 180.0)
                            val w = minOf(nw.longitude, se.longitude).coerceIn(-180.0, 180.0)

                            northLat = n
                            southLat = s
                            eastLon = e
                            westLon = w

                            estimatedTiles = calculateTileCountForBounds(
                                north = n,
                                east = e,
                                south = s,
                                west = w,
                                minZoom = selectedMinZoom,
                                maxZoom = selectedMaxZoom
                            )
                        }
                    } catch (_: Exception) {
                        // Fallback to bounding box ratio if projection is momentarily unavailable
                        val bb = mapView.boundingBox ?: return@Runnable
                        val latSpan = bb.latNorth - bb.latSouth
                        val lonSpan = bb.lonEast - bb.lonWest
                        val centerLat = (bb.latNorth + bb.latSouth) / 2.0
                        val centerLon = (bb.lonEast + bb.lonWest) / 2.0
                        val n = (centerLat + (latSpan * 0.25)).coerceIn(-85.0, 85.0)
                        val s = (centerLat - (latSpan * 0.25)).coerceIn(-85.0, 85.0)
                        val e = (centerLon + (lonSpan * 0.42)).coerceIn(-180.0, 180.0)
                        val w = (centerLon - (lonSpan * 0.42)).coerceIn(-180.0, 180.0)

                        northLat = n
                        southLat = s
                        eastLon = e
                        westLon = w

                        estimatedTiles = calculateTileCountForBounds(
                            north = n,
                            east = e,
                            south = s,
                            west = w,
                            minZoom = selectedMinZoom,
                            maxZoom = selectedMaxZoom
                        )
                    }
                }
            }

            // Instantly re-evaluate tile count when user changes zoom detail tier
            LaunchedEffect(selectedMaxZoom, selectedMinZoom) {
                updateDebounceRunnable.run()
            }

            // 1. Edge-to-Edge Base MapView (AndroidView)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(OfflineTileSources.OPEN_STREET_MAP)
                        setMultiTouchControls(true)
                        isTilesScaledToDpi = false
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        minZoomLevel = 5.0
                        maxZoomLevel = 19.0
                        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                        controller.setZoom(14.0)
                        controller.setCenter(GeoPoint(initialCenterLat, initialCenterLon))

                        // Render visual shaded polygons and badges for already downloaded regions
                        savedRegions.forEach { region ->
                            val polygon = OsmPolygon(this).apply {
                                infoWindow = null
                                points = listOf(
                                    GeoPoint(region.north, region.west),
                                    GeoPoint(region.north, region.east),
                                    GeoPoint(region.south, region.east),
                                    GeoPoint(region.south, region.west)
                                )
                                fillPaint.color = android.graphics.Color.argb(35, 16, 185, 129) // Emerald translucent
                                outlinePaint.color = android.graphics.Color.argb(210, 16, 185, 129) // Emerald border
                                outlinePaint.strokeWidth = 3f
                            }
                            overlays.add(polygon)

                            val centerPoint = GeoPoint((region.north + region.south) / 2.0, (region.east + region.west) / 2.0)
                            val badgeMarker = OsmMarker(this).apply {
                                position = centerPoint
                                setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_CENTER)
                                infoWindow = null
                                icon = createDownloadedAreaBadge(ctx, region.name, region.sizeMb)
                            }
                            overlays.add(badgeMarker)
                        }

                        addMapListener(object : MapListener {
                            override fun onScroll(event: ScrollEvent?): Boolean {
                                handler.removeCallbacks(updateDebounceRunnable)
                                handler.postDelayed(updateDebounceRunnable, 100)
                                return false
                            }

                            override fun onZoom(event: ZoomEvent?): Boolean {
                                handler.removeCallbacks(updateDebounceRunnable)
                                handler.postDelayed(updateDebounceRunnable, 100)
                                return false
                            }
                        })

                        post { updateDebounceRunnable.run() }
                        mapViewInstance = this
                    }
                }
            )

            // 2. Viewfinder Scrim & Corner Reticles Overlay (Hardware-accelerated Canvas)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cornerRadiusPx = 18.dp.toPx()

                // Dark translucent outer scrim with rounded rectangular cutout
                val scrimPath = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRoundRect(
                        RoundRect(
                            left = boxLeftPx,
                            top = boxTopPx,
                            right = boxRightPx,
                            bottom = boxBottomPx,
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                    )
                }
                drawPath(
                    path = scrimPath,
                    color = Color.Black.copy(alpha = 0.42f)
                )

                // Viewfinder main perimeter border
                val borderPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = boxLeftPx,
                            top = boxTopPx,
                            right = boxRightPx,
                            bottom = boxBottomPx,
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                    )
                }
                drawPath(
                    path = borderPath,
                    color = currentBorderColor,
                    style = Stroke(width = 1.8.dp.toPx())
                )

                // Tactical corner reticle brackets (camera viewfinder feel)
                val bracketLen = 20.dp.toPx()
                val bracketStroke = 3.5.dp.toPx()
                val bracketColor = currentBorderColor

                // Top-Left bracket
                drawLine(
                    color = bracketColor,
                    start = Offset(boxLeftPx + (cornerRadiusPx * 0.4f), boxTopPx),
                    end = Offset(boxLeftPx + bracketLen, boxTopPx),
                    strokeWidth = bracketStroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = bracketColor,
                    start = Offset(boxLeftPx, boxTopPx + (cornerRadiusPx * 0.4f)),
                    end = Offset(boxLeftPx, boxTopPx + bracketLen),
                    strokeWidth = bracketStroke,
                    cap = StrokeCap.Round
                )

                // Top-Right bracket
                drawLine(
                    color = bracketColor,
                    start = Offset(boxRightPx - bracketLen, boxTopPx),
                    end = Offset(boxRightPx - (cornerRadiusPx * 0.4f), boxTopPx),
                    strokeWidth = bracketStroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = bracketColor,
                    start = Offset(boxRightPx, boxTopPx + (cornerRadiusPx * 0.4f)),
                    end = Offset(boxRightPx, boxTopPx + bracketLen),
                    strokeWidth = bracketStroke,
                    cap = StrokeCap.Round
                )

                // Bottom-Left bracket
                drawLine(
                    color = bracketColor,
                    start = Offset(boxLeftPx + (cornerRadiusPx * 0.4f), boxBottomPx),
                    end = Offset(boxLeftPx + bracketLen, boxBottomPx),
                    strokeWidth = bracketStroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = bracketColor,
                    start = Offset(boxLeftPx, boxBottomPx - bracketLen),
                    end = Offset(boxLeftPx, boxBottomPx - (cornerRadiusPx * 0.4f)),
                    strokeWidth = bracketStroke,
                    cap = StrokeCap.Round
                )

                // Bottom-Right bracket
                drawLine(
                    color = bracketColor,
                    start = Offset(boxRightPx - bracketLen, boxBottomPx),
                    end = Offset(boxRightPx - (cornerRadiusPx * 0.4f), boxBottomPx),
                    strokeWidth = bracketStroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = bracketColor,
                    start = Offset(boxRightPx, boxBottomPx - bracketLen),
                    end = Offset(boxRightPx, boxBottomPx - (cornerRadiusPx * 0.4f)),
                    strokeWidth = bracketStroke,
                    cap = StrokeCap.Round
                )
            }

            // 3. Floating Viewfinder Status Badge (Anchored cleanly just below the box, never obscuring the map)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = boxBottomDp + 10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, currentBorderColor.copy(alpha = 0.6f)),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isOverLimit) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Area too large • Zoom in (${String.format(Locale.US, "%,d", estimatedTiles)} / 5,000 tiles)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp
                                ),
                                color = Color(0xFFEF4444)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFF10B981), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = String.format(Locale.US, "%.1f MB  •  %,d tiles (z%d–%d)", estimatedMb, estimatedTiles, selectedMinZoom, selectedMaxZoom),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp
                                ),
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // 4. Floating Minimal Top Bar (Capsule HUD at top center)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(23.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "Download Area",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (savedRegions.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(5.dp)
                                        .background(Color(0xFF10B981), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${savedRegions.size} Saved",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFF10B981)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = {
                            mapViewInstance?.controller?.animateTo(GeoPoint(initialCenterLat, initialCenterLon))
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Center",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // 5. Floating Zoom Controls (+ and - on middle right)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shadowElevation = 4.dp
                ) {
                    IconButton(
                        onClick = { mapViewInstance?.controller?.zoomIn() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Zoom In",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    shadowElevation = 4.dp
                ) {
                    IconButton(
                        onClick = { mapViewInstance?.controller?.zoomOut() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Zoom Out",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 6. Floating Bottom Action Bar with Detail Tier Selector & Tactical Download Button
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // Zoom Level / Detail Tier Selector Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Detail Level (Zoom 12–$selectedMaxZoom)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = zoomDetailDescription,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp
                            ),
                            color = if (isOverLimit) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Segmented Detail Tier Chips (z15 to z19)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        listOf(
                            15 to "15 Basic",
                            16 to "16 Std",
                            17 to "17 High",
                            18 to "18 Ultra",
                            19 to "19 Max"
                        ).forEach { (zoom, label) ->
                            val isSelected = selectedMaxZoom == zoom
                            val chipBg by animateColorAsState(
                                targetValue = if (isSelected) Color(0xFF10B981) else Color.Transparent,
                                label = "chipBg"
                            )
                            val chipTextColor by animateColorAsState(
                                targetValue = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "chipTextColor"
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(26.dp)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(chipBg)
                                    .clickable { selectedMaxZoom = zoom },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 10.sp
                                    ),
                                    color = chipTextColor,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 0.8.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Bottom Row: Editable Sector Name & Tactical Download Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left Column: Editable Sector Name & Storage Subtitle
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                BasicTextField(
                                    value = regionName,
                                    onValueChange = { regionName = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    decorationBox = { innerTextField ->
                                        if (regionName.isEmpty()) {
                                            Text(
                                                text = "Sector Name",
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            if (overlappingRegion != null) {
                                Text(
                                    text = "Overlaps '${overlappingRegion.name}'",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                                    color = Color(0xFFF59E0B),
                                    maxLines = 1
                                )
                            } else if (freeStorageGb != null) {
                                Text(
                                    text = String.format(Locale.US, "Free: %.1f GB", freeStorageGb),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            } else {
                                Text(
                                    text = "Offline cache ready",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        // Right Button: Tactical Download Trigger
                        Button(
                            onClick = {
                                if (isWithinLimit) {
                                    val region = OfflineRegion(
                                        name = regionName.trim().ifEmpty { "Sector ${savedRegions.size + 1}" },
                                        north = northLat,
                                        east = eastLon,
                                        south = southLat,
                                        west = westLon,
                                        minZoom = selectedMinZoom,
                                        maxZoom = selectedMaxZoom
                                    )
                                    onConfirmDownload(region)
                                }
                            },
                            enabled = isWithinLimit,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color.White,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isWithinLimit) "Download" else "Zoom In",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Generates a high-contrast, pre-rendered emerald pill badge displaying the downloaded region's name and size.
 */
private fun createDownloadedAreaBadge(context: Context, name: String, sizeMb: Float): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        color = android.graphics.Color.WHITE
    }
    val text = "✓ $name (${String.format(Locale.US, "%.1f MB", sizeMb)})"
    val textWidth = paint.measureText(text)
    val width = (textWidth + (20 * density)).toInt().coerceAtLeast((60 * density).toInt())
    val height = (24 * density).toInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(235, 6, 78, 59) // Deep Emerald (#064E3B)
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(255, 52, 211, 153) // Mint Green border (#34D399)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    val rect = android.graphics.RectF(2f, 2f, width - 2f, height - 2f)
    val radius = 12f * density
    canvas.drawRoundRect(rect, radius, radius, bgPaint)
    canvas.drawRoundRect(rect, radius, radius, borderPaint)

    val fontMetrics = paint.fontMetrics
    val baseline = (height - fontMetrics.bottom - fontMetrics.top) / 2f
    canvas.drawText(text, 10f * density, baseline, paint)

    return BitmapDrawable(context.resources, bitmap)
}
