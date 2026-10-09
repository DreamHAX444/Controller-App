package com.livetracker.controller.core.tracker.device.domain

import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.HeartbeatHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHealthClassifierTest {

    @Test
    fun `classify returns OFFLINE when connection is DISCONNECTED or FAILED`() {
        assertEquals(
            DeviceHealth.OFFLINE, 
            DeviceHealthClassifier.classify(ConnectionState.DISCONNECTED, HeartbeatHealth.HEALTHY, false)
        )
        assertEquals(
            DeviceHealth.OFFLINE, 
            DeviceHealthClassifier.classify(ConnectionState.FAILED, HeartbeatHealth.HEALTHY, false)
        )
    }

    @Test
    fun `classify returns UNKNOWN during connecting states`() {
        val connectingStates = listOf(
            ConnectionState.REGISTERED,
            ConnectionState.CONNECTING,
            ConnectionState.SIGNALING,
            ConnectionState.WEBRTC_CONNECTING,
            ConnectionState.DATA_CHANNEL_OPENING
        )
        for (state in connectingStates) {
            assertEquals(
                DeviceHealth.UNKNOWN,
                DeviceHealthClassifier.classify(state, HeartbeatHealth.HEALTHY, false)
            )
        }
    }

    @Test
    fun `classify returns DEGRADED during reconnecting states`() {
        val reconnectingStates = listOf(
            ConnectionState.RECONNECTING,
            ConnectionState.SIGNALING_RECOVERY,
            ConnectionState.WEBRTC_RECOVERY,
            ConnectionState.DATA_CHANNEL_RECOVERY
        )
        for (state in reconnectingStates) {
            assertEquals(
                DeviceHealth.DEGRADED,
                DeviceHealthClassifier.classify(state, HeartbeatHealth.HEALTHY, false)
            )
        }
    }

    @Test
    fun `classify returns DEGRADED when connection is CONNECTED but hasCriticalError is true`() {
        assertEquals(
            DeviceHealth.DEGRADED,
            DeviceHealthClassifier.classify(ConnectionState.CONNECTED, HeartbeatHealth.HEALTHY, true)
        )
    }

    @Test
    fun `classify handles HeartbeatHealth correctly when CONNECTED`() {
        assertEquals(
            DeviceHealth.HEALTHY,
            DeviceHealthClassifier.classify(ConnectionState.CONNECTED, HeartbeatHealth.HEALTHY, false)
        )
        assertEquals(
            DeviceHealth.DEGRADED,
            DeviceHealthClassifier.classify(ConnectionState.CONNECTED, HeartbeatHealth.DEGRADED, false)
        )
        assertEquals(
            DeviceHealth.OFFLINE,
            DeviceHealthClassifier.classify(ConnectionState.CONNECTED, HeartbeatHealth.LOST, false)
        )
        assertEquals(
            DeviceHealth.UNKNOWN,
            DeviceHealthClassifier.classify(ConnectionState.CONNECTED, HeartbeatHealth.UNKNOWN, false)
        )
    }

    @Test
    fun `classify returns DEGRADED when connection is DEGRADED`() {
        assertEquals(
            DeviceHealth.DEGRADED,
            DeviceHealthClassifier.classify(ConnectionState.DEGRADED, HeartbeatHealth.HEALTHY, false)
        )
    }
}
