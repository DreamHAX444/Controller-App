package com.livetracker.controller.core.tracker.supabase

import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.livetracker.controller.core.tracker.domain.TrackerCapabilities

/**
 * Concrete [TrackerTransport] representing an individual tracker connected via the
 * shared [SupabaseFleetManager] real-time pipeline.
 *
 * Tracks individual tracker connection state without false positives, avoiding stale
 * CONNECTED states when a device is registered or disconnected.
 */
class SupabaseTrackerTransport(
    override val deviceId: TrackerId,
    private val fleetManager: SupabaseFleetManager,
    private val scope: CoroutineScope? = null
) : TrackerTransport {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    @Volatile
    private var isExplicitlyDisconnected = false

    @Volatile
    private var isTrackerActive = false

    private var fleetObserveJob: Job? = null

    private val coroutineScope: CoroutineScope
        get() = scope ?: fleetManager.scope

    override val events: Flow<TrackerEvent> = fleetManager.locationEvents
        .filter { it.deviceId == deviceId }
        .onEach {
            if (!isExplicitlyDisconnected) {
                isTrackerActive = true
                if (_connectionState.value != ConnectionState.CONNECTED) {
                    _connectionState.value = ConnectionState.CONNECTED
                }
            }
        }

    init {
        startFleetObservation()
    }

    private fun startFleetObservation() {
        fleetObserveJob?.cancel()
        fleetObserveJob = coroutineScope.launch {
            fleetManager.connectionState.collect { fleetState ->
                if (isTrackerActive && !isExplicitlyDisconnected) {
                    when (fleetState) {
                        ConnectionState.CONNECTED -> {
                            _connectionState.value = ConnectionState.CONNECTED
                        }
                        ConnectionState.CONNECTING -> {
                            _connectionState.value = ConnectionState.CONNECTING
                        }
                        ConnectionState.RECONNECTING -> {
                            _connectionState.value = ConnectionState.RECONNECTING
                        }
                        ConnectionState.DISCONNECTED -> {
                            _connectionState.value = ConnectionState.DISCONNECTED
                        }
                        ConnectionState.FAILED -> {
                            _connectionState.value = ConnectionState.FAILED
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    override suspend fun connect() {
        isTrackerActive = true
        isExplicitlyDisconnected = false
        if (fleetObserveJob?.isActive != true) {
            startFleetObservation()
        }

        fleetManager.start()
        _connectionState.value = ConnectionState.CONNECTED
    }

    override suspend fun disconnect() {
        isTrackerActive = false
        isExplicitlyDisconnected = true
        _connectionState.value = ConnectionState.DISCONNECTED
        fleetObserveJob?.cancel()
        fleetObserveJob = null
    }

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        return fleetManager.sendTrackerCommand(command)
    }
}
