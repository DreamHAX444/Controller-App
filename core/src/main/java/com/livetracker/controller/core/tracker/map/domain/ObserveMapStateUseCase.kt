package com.livetracker.controller.core.tracker.map.domain

import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.DeviceSelectionManager
import com.livetracker.controller.core.tracker.device.domain.ObserveDeviceSummariesUseCase
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceSummary
import com.livetracker.controller.core.tracker.location.domain.LocationState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

import com.livetracker.controller.core.tracker.session.TimeSource
import com.livetracker.controller.core.tracker.session.DefaultTimeSource

class ObserveMapStateUseCase(
    private val observeDeviceSummaries: ObserveDeviceSummariesUseCase,
    private val selectionManager: DeviceSelectionManager,
    private val timeSource: TimeSource = DefaultTimeSource
) {
    operator fun invoke(): Flow<MapState> {
        return combine(
            observeDeviceSummaries(),
            selectionManager.getSelection()
        ) { summaries, selection ->
            
            if (summaries.isEmpty()) {
                return@combine MapState.Empty
            }
            
            val visibleSummaries = when (selection) {
                DeviceSelection.All -> summaries
                DeviceSelection.Empty -> emptyList()
                is DeviceSelection.Single -> summaries.filter { it.device.deviceId == selection.deviceId }
                is DeviceSelection.Multiple -> summaries.filter { it.device.deviceId in selection.deviceIds }
            }
            
            if (visibleSummaries.isEmpty()) {
                return@combine MapState.Active(
                    selectionMode = selection,
                    markers = emptyList(),
                    lastUpdateTimestamp = timeSource.currentTimeMillis()
                )
            }
            
            val markers = visibleSummaries.mapNotNull { summary ->
                // Map only if there's a valid location that we can plot
                val loc = summary.latestLocation
                if (loc != null && summary.locationState != LocationState.UNKNOWN) {
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
                        isSelected = isSelected(summary, selection)
                    )
                } else {
                    null
                }
            }
            
            MapState.Active(
                selectionMode = selection,
                markers = markers,
                lastUpdateTimestamp = timeSource.currentTimeMillis()
            )
        }
    }
    
    private fun isSelected(summary: TrackerDeviceSummary, selection: DeviceSelection): Boolean {
        return when (selection) {
            DeviceSelection.All -> true
            DeviceSelection.Empty -> false
            is DeviceSelection.Single -> summary.device.deviceId == selection.deviceId
            is DeviceSelection.Multiple -> summary.device.deviceId in selection.deviceIds
        }
    }
}
