package com.livetracker.controller.ui.screens.map.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.map.domain.MapMarker
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance, memory-efficient marker and accuracy rendering engine.
 * Generates minimal, professional vector-drawn radar beacon markers with
 * sub-pixel anti-aliasing and LRU memory caching.
 */
object MapMarkerRenderer {

    private val bitmapCache = LruCache<String, Bitmap>(64)
    private val drawableCache = LruCache<String, BitmapDrawable>(64)
    private val descriptorCache = LruCache<String, BitmapDescriptor>(64)
    private val circlePointsCache = LruCache<String, List<GeoPoint>>(128)
    private val vertexHandleCache = LruCache<Int, Bitmap>(16)
    private val vertexHandleDrawableCache = LruCache<Int, BitmapDrawable>(16)

    /**
     * Pre-warms marker bitmaps, handle graphics, and geometry caches
     * during the startup loading screen so runtime rendering is instantaneous.
     */
    fun warmup(
        context: Context,
        markers: List<MapMarker>,
        presetColors: List<Int> = emptyList()
    ) {
        // 1. Pre-warm marker bitmaps for known devices (selected & unselected, with & without bearing)
        markers.forEach { marker ->
            getMarkerBitmap(context, marker, isSelected = false)
            getMarkerBitmap(context, marker, isSelected = true)
            if (marker.bearing == null) {
                // Also pre-cache a variant with bearing
                val bearingMarker = marker.copy(bearing = 90f)
                getMarkerBitmap(context, bearingMarker, isSelected = false)
                getMarkerBitmap(context, bearingMarker, isSelected = true)
            }
        }

        // 2. Pre-warm accuracy circles for common radiuses (10m, 15m, 25m, 50m)
        markers.firstOrNull()?.let { first ->
            listOf(10.0, 15.0, 25.0, 50.0).forEach { r ->
                getAccuracyCirclePoints(first.latitude, first.longitude, r)
            }
        }

        // 3. Pre-warm geofence vertex handle bitmaps for preset palette colors
        presetColors.forEach { color ->
            getVertexHandleBitmap(context, color)
        }
    }

    /**
     * Retrieves or generates a cached circular vertex drag handle bitmap.
     * Prevents per-frame Bitmap/Canvas allocations during geofence drawing/dragging.
     */
    fun getVertexHandleBitmap(context: Context, colorArgb: Int): Bitmap {
        val cached = vertexHandleCache.get(colorArgb)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        val density = context.resources.displayMetrics.density
        val handleRadius = (8 * density).toInt()
        val handleDiameter = (handleRadius * 2).coerceAtLeast(16)

        val bitmap = Bitmap.createBitmap(handleDiameter, handleDiameter, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        val radiusFloat = handleRadius.toFloat()
        val innerRadius = radiusFloat - (2f * density)
        canvas.drawCircle(radiusFloat, radiusFloat, innerRadius, fillPaint)
        canvas.drawCircle(radiusFloat, radiusFloat, innerRadius, borderPaint)

        vertexHandleCache.put(colorArgb, bitmap)
        return bitmap
    }

    /**
     * Retrieves or generates a cached circular vertex drag handle BitmapDrawable.
     * Reuses single instance per color to eliminate allocations during geofence interaction.
     */
    fun getVertexHandleDrawable(context: Context, colorArgb: Int): BitmapDrawable {
        val cached = vertexHandleDrawableCache.get(colorArgb)
        if (cached != null) return cached

        val bitmap = getVertexHandleBitmap(context, colorArgb)
        val drawable = BitmapDrawable(context.resources, bitmap)
        vertexHandleDrawableCache.put(colorArgb, drawable)
        return drawable
    }

    /**
     * Retrieves or generates a cached beacon marker bitmap.
     */
    fun getMarkerBitmap(
        context: Context,
        marker: MapMarker,
        isSelected: Boolean
    ): Bitmap {
        val hasBearing = marker.bearing != null
        val cacheKey = "${marker.deviceId.value}_${isSelected}_${hasBearing}_${marker.connectionState}_${marker.locationState}"
        
        val cached = bitmapCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return cached
        }

        val bitmap = renderBeaconBitmap(context, marker, isSelected, hasBearing)
        bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    /**
     * Retrieves or generates a cached beacon marker BitmapDrawable for Osmdroid.
     * Prevents per-frame BitmapDrawable object allocations.
     */
    fun getMarkerDrawable(
        context: Context,
        marker: MapMarker,
        isSelected: Boolean
    ): BitmapDrawable {
        val hasBearing = marker.bearing != null
        val cacheKey = "${marker.deviceId.value}_${isSelected}_${hasBearing}_${marker.connectionState}_${marker.locationState}"

        val cached = drawableCache.get(cacheKey)
        if (cached != null) return cached

        val bitmap = getMarkerBitmap(context, marker, isSelected)
        val drawable = BitmapDrawable(context.resources, bitmap)
        drawableCache.put(cacheKey, drawable)
        return drawable
    }

    /**
     * Retrieves or generates a cached BitmapDescriptor for Google Maps.
     * Prevents per-frame native C++ JNI BitmapDescriptor allocations.
     */
    fun getMarkerBitmapDescriptor(
        context: Context,
        marker: MapMarker,
        isSelected: Boolean
    ): BitmapDescriptor {
        val hasBearing = marker.bearing != null
        val cacheKey = "${marker.deviceId.value}_${isSelected}_${hasBearing}_${marker.connectionState}_${marker.locationState}"

        val cached = descriptorCache.get(cacheKey)
        if (cached != null) return cached

        val bitmap = getMarkerBitmap(context, marker, isSelected)
        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        descriptorCache.put(cacheKey, descriptor)
        return descriptor
    }

    // High-visibility, distinct palette for individual devices
    private val DEVICE_PALETTE = intArrayOf(
        android.graphics.Color.parseColor("#0284C7"), // Electric Sky Blue
        android.graphics.Color.parseColor("#10B981"), // Emerald Green
        android.graphics.Color.parseColor("#F59E0B"), // Amber / Gold
        android.graphics.Color.parseColor("#8B5CF6"), // Vivid Violet
        android.graphics.Color.parseColor("#EF4444"), // Coral Red
        android.graphics.Color.parseColor("#EC4899"), // Neon Pink
        android.graphics.Color.parseColor("#06B6D4"), // Vibrant Cyan
        android.graphics.Color.parseColor("#F97316"), // Bright Orange
        android.graphics.Color.parseColor("#6366F1"), // Indigo
        android.graphics.Color.parseColor("#84CC16")  // Lime
    )

    private fun renderBeaconBitmap(
        context: Context,
        marker: MapMarker,
        isSelected: Boolean,
        hasBearing: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        // Arrow marker size: 44dp when selected, 36dp when normal
        val sizeDp = if (isSelected) 44f else 36f
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(32)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = sizePx / 2f
        val arrowColor = computeDeviceColor(marker.deviceId, marker.connectionState, marker.locationState)
        val isOffline = (marker.connectionState == ConnectionState.DISCONNECTED ||
                marker.connectionState == ConnectionState.FAILED ||
                marker.locationState == LocationState.OFFLINE ||
                marker.locationState == LocationState.UNKNOWN)

        // Geometric dimensions for the standalone chevron arrow (no circle background)
        val arrowHeight = sizePx * 0.74f
        val arrowWidth = sizePx * 0.58f
        val arrowTipY = center - (arrowHeight * 0.52f)
        val arrowBaseY = center + (arrowHeight * 0.48f)
        val arrowInnerY = center + (arrowHeight * 0.16f)
        val arrowWingX = arrowWidth * 0.50f

        val path = Path().apply {
            moveTo(center, arrowTipY)
            lineTo(center - arrowWingX, arrowBaseY)
            lineTo(center, arrowInnerY)
            lineTo(center + arrowWingX, arrowBaseY)
            close()
        }

        // 1. Subtle drop shadow for depth and elevation
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(80, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, shadowPaint)

        // 2. High-contrast border outline so arrow is razor-sharp on any map style
        if (isSelected) {
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 5f * density
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawPath(path, haloPaint)
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isOffline) {
                android.graphics.Color.parseColor("#64748B") // Slate outline for grey arrow
            } else {
                android.graphics.Color.argb(230, 15, 23, 42) // Deep navy/black border for contrast
            }
            style = Paint.Style.STROKE
            strokeWidth = if (isSelected) 3.5f * density else 2.2f * density
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(path, borderPaint)

        // 3. Solid colored arrow body (distinct per device, or muted white-grey when offline)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = arrowColor
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, fillPaint)

        return bitmap
    }

    /**
     * Determines marker accent color according to connection health and device palette.
     * When offline or disconnected, turns into muted grey/white.
     * When active, assigns a distinct vibrant color per device ID.
     */
    fun computeDeviceColor(
        deviceId: TrackerId,
        connectionState: ConnectionState,
        locationState: LocationState
    ): Int {
        if (connectionState == ConnectionState.DISCONNECTED ||
            connectionState == ConnectionState.FAILED ||
            locationState == LocationState.OFFLINE ||
            locationState == LocationState.UNKNOWN
        ) {
            return android.graphics.Color.parseColor("#CBD5E1") // Muted white-grey
        }
        if (connectionState != ConnectionState.CONNECTED || locationState == LocationState.STALE) {
            return android.graphics.Color.parseColor("#F59E0B") // Amber connecting / stale
        }

        // Active connected device: deterministic distinct palette color per device
        val hash = abs(deviceId.value.hashCode())
        return DEVICE_PALETTE[hash % DEVICE_PALETTE.size]
    }

    /**
     * Calculates circle polygon vertices in meters for horizontal accuracy visualization.
     */
    fun getAccuracyCirclePoints(
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        steps: Int = 36
    ): List<GeoPoint> {
        val key = "${latitude}_${longitude}_${radiusMeters}_$steps"
        val cached = circlePointsCache.get(key)
        if (cached != null) return cached

        val points = mutableListOf<GeoPoint>()
        val earthRadius = 6371000.0
        val latRad = Math.toRadians(latitude)
        val lonRad = Math.toRadians(longitude)
        val distRatio = radiusMeters / earthRadius

        for (i in 0 until steps) {
            val bearingRad = Math.toRadians(i * 360.0 / steps)
            val pointLat = asin(sin(latRad) * cos(distRatio) + cos(latRad) * sin(distRatio) * cos(bearingRad))
            val pointLon = lonRad + atan2(
                sin(bearingRad) * sin(distRatio) * cos(latRad),
                cos(distRatio) - sin(latRad) * sin(pointLat)
            )
            points.add(GeoPoint(Math.toDegrees(pointLat), Math.toDegrees(pointLon)))
        }

        circlePointsCache.put(key, points)
        return points
    }
}
