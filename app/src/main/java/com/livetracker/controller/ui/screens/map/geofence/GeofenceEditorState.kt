package com.livetracker.controller.ui.screens.map.geofence

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.geofence.domain.AlertSoundProfile
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceType
import com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D
import com.livetracker.controller.core.tracker.geofence.domain.GeofenceTriggerRule

/**
 * Represents the state machine for geofence viewing, management, and editing on the map.
 */
sealed interface GeofenceEditorState {
    /** Map is in standard tracking mode; geofence controls are idle. */
    data object Idle : GeofenceEditorState

    /** Geofence management panel/sheet is open, listing all configured perimeters. */
    data object ListMode : GeofenceEditorState

    /** Interactive drawing/editing mode on the map canvas. */
    data class Editing(
        val id: String?,
        val name: String,
        val shapeType: GeoFenceType = GeoFenceType.POLYGON,
        val vertices: List<GeoPoint2D> = emptyList(),
        val center: GeoPoint2D? = null,
        val radiusMeters: Double = 200.0,
        val colorArgb: Int,
        val isEnabled: Boolean = true,
        val triggerRule: GeofenceTriggerRule = GeofenceTriggerRule.BOTH,
        val soundProfile: AlertSoundProfile = AlertSoundProfile.TACTICAL_SIREN,
        val durationSeconds: Int = 30,
        val targetDeviceId: TrackerId? = null,
        val isVibrationEnabled: Boolean = true,
        val validationError: String? = null,
        val originalFence: GeoFence? = null
    ) : GeofenceEditorState {
        val hasMinVertices: Boolean get() = vertices.size >= 3
        val vertexCount: Int get() = vertices.size
        val isCircleConfigured: Boolean get() = center != null && radiusMeters > 0.0
        val isValidShape: Boolean get() = when (shapeType) {
            GeoFenceType.POLYGON -> hasMinVertices
            GeoFenceType.CIRCLE -> isCircleConfigured
        }

        /**
         * Tracks whether the user has unpersisted changes relative to baseline state.
         * For new geofences: true if name is entered, vertices are added, or center is placed.
         * For existing geofences: true if any editable property differs from originalFence.
         */
        val isDirty: Boolean get() {
            val original = originalFence
            return if (original == null) {
                name.isNotBlank() || vertices.isNotEmpty() || center != null
            } else {
                name != original.name ||
                    shapeType != original.type ||
                    vertices != original.vertices ||
                    center != original.center ||
                    radiusMeters != (original.radiusMeters ?: 200.0) ||
                    colorArgb != original.colorArgb ||
                    isEnabled != original.isEnabled ||
                    triggerRule != original.triggerRule ||
                    soundProfile != original.soundProfile ||
                    durationSeconds != original.durationSeconds ||
                    targetDeviceId != original.targetDeviceId ||
                    isVibrationEnabled != original.isVibrationEnabled
            }
        }
    }
}
