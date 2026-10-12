package com.livetracker.controller.ui.screens.map.geofence

import com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D

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
        val vertices: List<GeoPoint2D>,
        val colorArgb: Int,
        val isEnabled: Boolean = true,
        val validationError: String? = null
    ) : GeofenceEditorState {
        val hasMinVertices: Boolean get() = vertices.size >= 3
        val vertexCount: Int get() = vertices.size
    }
}
