package com.livetracker.controller.ui.screens.map.components

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker as OsmMarker
import kotlin.math.abs

/**
 * High-performance, frame-smooth marker position and rotation interpolator.
 * Eliminates jagged GPS needle snapping by smoothly gliding markers along roads
 * and rotating needles along the shortest angular arc.
 */
class SmoothMarkerAnimator(
    private val onFrame: () -> Unit
) {
    private val activePositionAnimators = mutableMapOf<String, ValueAnimator>()
    private val activeRotationAnimators = mutableMapOf<String, ValueAnimator>()

    companion object {
        /**
         * Computes the shortest angular delta between two angles in degrees (-180..180).
         * Prevents spinning 350 degrees when crossing 0/360 degrees.
         */
        fun shortestAngleDelta(fromDegrees: Float, toDegrees: Float): Float {
            return ((toDegrees - fromDegrees + 540f) % 360f) - 180f
        }

        /**
         * Computes linear interpolation between two double values.
         */
        fun lerp(start: Double, end: Double, fraction: Double): Double {
            return start + (end - start) * fraction
        }
    }

    /**
     * Animates an OpenStreetMap marker to the new target coordinates and bearing.
     */
    fun animateOsmMarker(
        deviceId: String,
        marker: OsmMarker,
        targetLat: Double,
        targetLon: Double,
        targetBearing: Float?,
        durationMs: Long = 850L
    ) {
        val currentPos = marker.position
        val targetPos = GeoPoint(targetLat, targetLon)

        // 1. Position Interpolation
        if (currentPos == null || (currentPos.latitude == 0.0 && currentPos.longitude == 0.0)) {
            marker.position = targetPos
        } else {
            val distMeters = currentPos.distanceToAsDouble(targetPos)
            if (distMeters in 0.2..1000.0) {
                activePositionAnimators[deviceId]?.cancel()
                val startLat = currentPos.latitude
                val startLon = currentPos.longitude

                val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = durationMs
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { va ->
                        val fraction = va.animatedFraction.toDouble()
                        val interpLat = lerp(startLat, targetLat, fraction)
                        val interpLon = lerp(startLon, targetLon, fraction)
                        marker.position = GeoPoint(interpLat, interpLon)
                        onFrame()
                    }
                }
                activePositionAnimators[deviceId] = animator
                animator.start()
            } else if (distMeters > 0.0) {
                // Large jump or initial placement: snap immediately
                activePositionAnimators.remove(deviceId)?.cancel()
                marker.position = targetPos
            }
        }

        // 2. Shortest-Arc Rotation Interpolation
        if (targetBearing != null) {
            val targetRot = -targetBearing // OSM uses negative rotation for clockwise display
            val currentRot = marker.rotation
            val rotDelta = shortestAngleDelta(currentRot, targetRot)

            if (abs(rotDelta) in 1.5f..180f) {
                activeRotationAnimators[deviceId]?.cancel()
                val startRot = currentRot
                val endRot = startRot + rotDelta

                val rotAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = (durationMs * 0.65f).toLong().coerceAtLeast(300L)
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { va ->
                        val fraction = va.animatedFraction
                        marker.rotation = startRot + (endRot - startRot) * fraction
                        onFrame()
                    }
                }
                activeRotationAnimators[deviceId] = rotAnimator
                rotAnimator.start()
            } else if (abs(rotDelta) > 0f) {
                marker.rotation = targetRot
            }
        }
    }

    /**
     * Cancels any active animators and releases references.
     */
    fun clear() {
        activePositionAnimators.values.forEach { it.cancel() }
        activePositionAnimators.clear()
        activeRotationAnimators.values.forEach { it.cancel() }
        activeRotationAnimators.clear()
    }
}
