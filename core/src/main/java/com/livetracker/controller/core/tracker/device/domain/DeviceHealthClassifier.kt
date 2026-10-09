package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.HeartbeatHealth

object DeviceHealthClassifier {
    fun classify(
        connectionState: ConnectionState,
        heartbeatHealth: HeartbeatHealth,
        hasCriticalError: Boolean
    ): DeviceHealth {
        if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.FAILED) {
            return DeviceHealth.OFFLINE
        }
        
        if (connectionState == ConnectionState.REGISTERED || connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.SIGNALING || connectionState == ConnectionState.WEBRTC_CONNECTING || connectionState == ConnectionState.DATA_CHANNEL_OPENING) {
            return DeviceHealth.UNKNOWN
        }

        if (connectionState == ConnectionState.RECONNECTING || connectionState == ConnectionState.SIGNALING_RECOVERY || connectionState == ConnectionState.WEBRTC_RECOVERY || connectionState == ConnectionState.DATA_CHANNEL_RECOVERY) {
            return DeviceHealth.DEGRADED
        }

        if (connectionState == ConnectionState.CONNECTED) {
            if (hasCriticalError) return DeviceHealth.DEGRADED
            return when (heartbeatHealth) {
                HeartbeatHealth.HEALTHY -> DeviceHealth.HEALTHY
                HeartbeatHealth.DEGRADED -> DeviceHealth.DEGRADED
                HeartbeatHealth.LOST -> DeviceHealth.OFFLINE
                HeartbeatHealth.UNKNOWN -> DeviceHealth.UNKNOWN
            }
        }
        
        if (connectionState == ConnectionState.DEGRADED) {
            return DeviceHealth.DEGRADED
        }

        return DeviceHealth.UNKNOWN
    }
}
