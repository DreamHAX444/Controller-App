package com.livetracker.controller.core.tracker.device.data

import com.livetracker.controller.core.tracker.device.domain.*
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCapabilities
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class DefaultDeviceManager(
    private val scope: CoroutineScope,
    private val repository: TrackerDeviceRepository,
    private val sessionManager: TrackerSessionManager,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : DeviceManager {

    private val syncJobs = ConcurrentHashMap<TrackerId, Job>()

    override suspend fun registerDevice(deviceId: TrackerId, displayName: String) {
        val device = TrackerDevice(
            deviceId = deviceId,
            displayName = displayName,
            registrationTimestamp = timeProvider(),
            lifecycle = DeviceLifecycle.REGISTERED,
            connectionState = ConnectionState.CONNECTED,
            capabilities = TrackerCapabilities(
                location = true,
                camera = true,
                audio = true,
                screen = true,
                fileTransfer = true
            )
        )
        
        repository.registerDevice(device)
        startSessionSync(deviceId, displayName)
    }
    
    private fun startSessionSync(deviceId: TrackerId, displayName: String) {
        syncJobs.remove(deviceId)?.cancel()
        sessionManager.registerTracker(deviceId, displayName)
        
        val job = scope.launch {
            val sessionFlow = sessionManager.getSession(deviceId) ?: return@launch
            sessionFlow.collect { sessionState ->
                repository.updateDevice(deviceId) { current ->
                    val newHealth = DeviceHealthClassifier.classify(
                        connectionState = sessionState.connectionState,
                        heartbeatHealth = sessionState.heartbeatState.health,
                        hasCriticalError = sessionState.lastError != null
                    )
                    
                    val isActive = sessionState.connectionState == ConnectionState.CONNECTED ||
                                   sessionState.connectionState == ConnectionState.DEGRADED ||
                                   sessionState.connectionState == ConnectionState.RECONNECTING ||
                                   sessionState.connectionState == ConnectionState.DATA_CHANNEL_RECOVERY
                    
                    val effectiveCapabilities = if (sessionState.capabilities == com.livetracker.controller.core.tracker.domain.TrackerCapabilities()) {
                        current.capabilities
                    } else {
                        sessionState.capabilities
                    }

                    current.copy(
                        connectionState = if (isActive || current.connectionState == ConnectionState.CONNECTED) ConnectionState.CONNECTED else sessionState.connectionState,
                        lastHeartbeatTimestamp = sessionState.heartbeatState.lastHeartbeatTimestamp ?: current.lastHeartbeatTimestamp,
                        health = newHealth,
                        capabilities = effectiveCapabilities,
                        lastSeenTimestamp = if (isActive || sessionState.lastEventTimestamp != null) 
                            maxOf(current.lastSeenTimestamp ?: 0, sessionState.lastEventTimestamp ?: timeProvider()) 
                            else current.lastSeenTimestamp,
                        warningIndicator = sessionState.lastError != null || newHealth == DeviceHealth.DEGRADED
                    )
                }
            }
        }
        syncJobs[deviceId] = job
    }

    override suspend fun unregisterDevice(deviceId: TrackerId) {
        syncJobs.remove(deviceId)?.cancel()
        sessionManager.unregisterTracker(deviceId)
        repository.unregisterDevice(deviceId)
    }

    override suspend fun enableDevice(deviceId: TrackerId) {
        repository.updateDevice(deviceId) { current ->
            current.copy(lifecycle = DeviceLifecycle.ACTIVE)
        }
    }

    override suspend fun disableDevice(deviceId: TrackerId) {
        repository.updateDevice(deviceId) { current ->
            current.copy(lifecycle = DeviceLifecycle.DISABLED)
        }
        sessionManager.disconnect(deviceId)
    }

    override suspend fun connectDevice(deviceId: TrackerId) {
        val device = repository.getDevice(deviceId) ?: return
        if (device.lifecycle == DeviceLifecycle.DISABLED || device.lifecycle == DeviceLifecycle.UNREGISTERED) return
        startSessionSync(deviceId, device.displayName)
        repository.updateDevice(deviceId) { current ->
            current.copy(
                connectionState = ConnectionState.CONNECTED,
                lastSeenTimestamp = timeProvider()
            )
        }
        sessionManager.startConnection(deviceId)
    }

    override suspend fun disconnectDevice(deviceId: TrackerId) {
        sessionManager.disconnect(deviceId)
    }
}
