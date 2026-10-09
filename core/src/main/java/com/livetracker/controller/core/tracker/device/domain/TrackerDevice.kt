package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCapabilities
import com.livetracker.controller.core.tracker.domain.TrackerId

data class TrackerDevice(
    val deviceId: TrackerId,
    val displayName: String,
    val deviceModel: String? = null,
    val manufacturer: String? = null,
    val platformVersion: String? = null,
    val appVersion: String? = null,
    
    // Lifecycle
    val lifecycle: DeviceLifecycle = DeviceLifecycle.REGISTERED,
    
    // Persistent stats
    val registrationTimestamp: Long,
    val lastSeenTimestamp: Long? = null,
    
    // Derived Runtime/Diagnostic states (to be updated by sync layer)
    val lastHeartbeatTimestamp: Long? = null,
    val batteryLevel: Int? = null,
    val networkState: String? = null, // e.g. "WIFI", "CELLULAR", "LOST"
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val health: DeviceHealth = DeviceHealth.UNKNOWN,
    
    val capabilities: TrackerCapabilities = TrackerCapabilities(),
    val activeServices: Set<String> = emptySet(),
    
    val lastKnownLocationTimestamp: Long? = null,
    val warningIndicator: Boolean = false
)
