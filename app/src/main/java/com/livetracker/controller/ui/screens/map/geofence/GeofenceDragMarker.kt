package com.livetracker.controller.ui.screens.map.geofence

import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.sqrt

/**
 * High-performance, zero-latency draggable vertex marker for polygon editing.
 * Intercepts touch events within an expanded hitbox, preventing unwanted map pans.
 */
class GeofenceDragMarker(
    mapView: MapView,
    private val onPositionChanged: (GeoPoint) -> Unit
) : Marker(mapView) {

    private var isDragging = false

    /**
     * Density-aware hit test: checks if screen coordinates fall within the expanded touch target.
     * Uses a 40dp radius (80dp diameter), comfortably exceeding the Android 48dp touch minimum.
     */
    fun isWithinHitTarget(screenX: Float, screenY: Float, mapView: MapView): Boolean {
        if (!isEnabled) return false
        val point = mapView.projection.toPixels(this.position, null)
        val deltaX = point.x - screenX
        val deltaY = point.y - screenY
        val distance = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble())
        val density = mapView.context.resources.displayMetrics.density
        val hitThresholdPx = 40.0 * density
        return distance <= hitThresholdPx
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isWithinHitTarget(event.x, event.y, mapView)) {
                    isDragging = true
                    mapView.parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val geoPoint = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt()) as GeoPoint
                    this.position = geoPoint
                    onPositionChanged(geoPoint)
                    mapView.invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    mapView.parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }

        return super.onTouchEvent(event, mapView)
    }
}
