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

    private fun renderBeaconBitmap(
        context: Context,
        marker: MapMarker,
        isSelected: Boolean,
        hasBearing: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        // Selected marker size: 48dp, unselected: 38dp
        val sizeDp = if (isSelected) 48f else 38f
        val sizePx = (sizeDp * density).toInt().coerceAtLeast(32)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = sizePx / 2f
        val radius = center - (4f * density)

        // Generate consistent, sophisticated golden-angle color per device ID
        val baseColor = computeDeviceColor(marker.deviceId, marker.connectionState, marker.locationState)

        // 1. Soft ambient shadow under beacon
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(70, 0, 0, 0)
            style = Paint.Style.FILL
            setShadowLayer(4f * density, 0f, 2f * density, android.graphics.Color.argb(90, 0, 0, 0))
        }
        canvas.drawCircle(center, center, radius, shadowPaint)

        // 2. Solid color core
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, corePaint)

        // 3. Crisp white outer ring for maximum contrast against any map style
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = if (isSelected) 3.5f * density else 2.5f * density
        }
        canvas.drawCircle(center, center, radius, strokePaint)

        // 4. Directional heading glyph or concentric resting dot
        val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }

        if (hasBearing) {
            // Sleek aerodynamic chevron arrow pointing upwards (marker rotation handles orientation)
            val path = Path().apply {
                val arrowTipY = center - (radius * 0.55f)
                val arrowBaseY = center + (radius * 0.45f)
                val arrowInnerY = center + (radius * 0.15f)
                val arrowWingX = radius * 0.45f

                moveTo(center, arrowTipY)
                lineTo(center - arrowWingX, arrowBaseY)
                lineTo(center, arrowInnerY)
                lineTo(center + arrowWingX, arrowBaseY)
                close()
            }
            canvas.drawPath(path, glyphPaint)
        } else {
            // Minimalist centered pulse dot
            canvas.drawCircle(center, center, radius * 0.35f, glyphPaint)
        }

        return bitmap
    }

    /**
     * Determines marker accent color according to connection health and golden-angle distribution.
     */
    fun computeDeviceColor(
        deviceId: TrackerId,
        connectionState: ConnectionState,
        locationState: LocationState
    ): Int {
        if (connectionState == ConnectionState.DISCONNECTED || locationState == LocationState.OFFLINE) {
            return android.graphics.Color.parseColor("#64748B") // Slate offline
        }
        if (connectionState == ConnectionState.CONNECTING) {
            return android.graphics.Color.parseColor("#F59E0B") // Amber connecting
        }

        // Active connected device: golden angle hue distribution for instant distinction
        val hash = abs(deviceId.value.hashCode())
        val hue = (hash * 137.5f) % 360f
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.82f, 0.88f))
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
