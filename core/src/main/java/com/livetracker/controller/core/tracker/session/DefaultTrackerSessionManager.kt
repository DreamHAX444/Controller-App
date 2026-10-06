package com.livetracker.controller.core.tracker.session

import com.livetracker.controller.core.tracker.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class DefaultTrackerSessionManager(
    private val scope: CoroutineScope,
    private val policy: HeartbeatPolicy = HeartbeatPolicy(),
    private val timeSource: TimeSource = DefaultTimeSource,
    private val transportFactory: (TrackerId) -> TrackerTransport
) : TrackerSessionManager {

    private val sessions = ConcurrentHashMap<TrackerId, SessionContext>()

    private inner class SessionContext(
        val deviceId: TrackerId,
        val displayName: String,
        val transport: TrackerTransport,
        val scope: CoroutineScope
    ) {
        val stateFlow = MutableStateFlow(
            TrackerSessionState(
                deviceId = deviceId,
                displayName = displayName
            )
        )
        val heartbeatMonitor = HeartbeatMonitor(scope, policy, stateFlow, timeSource)
        var observeJob: Job? = null
    }

    override fun registerTracker(deviceId: TrackerId, displayName: String) {
        if (sessions.containsKey(deviceId)) return
        
        val transport = transportFactory(deviceId)
        val context = SessionContext(deviceId, displayName, transport, scope)
        sessions[deviceId] = context
        context.heartbeatMonitor.start()
        
        context.observeJob = scope.launch {
            launch {
                transport.connectionState.collect { connState ->
                    // Handle valid/invalid transitions
                    context.stateFlow.update { currentState ->
                        val newState = validateTransition(currentState.connectionState, connState)
                        currentState.copy(connectionState = newState)
                    }
                }
            }
            
            launch {
                transport.events.collect { event ->
                    if (event.deviceId != deviceId) return@collect // Event isolation
                    
                    context.stateFlow.update { it.copy(lastEventTimestamp = event.timestamp) }
                    
                    when (event) {
                        is TrackerEvent.CapabilitiesUpdated -> {
                            context.stateFlow.update { it.copy(capabilities = event.capabilities) }
                        }
                        is TrackerEvent.HeartbeatReceived -> {
                            context.heartbeatMonitor.onHeartbeatReceived(event)
                        }
                        is TrackerEvent.Error -> {
                            context.stateFlow.update { it.copy(lastError = event.error) }
                        }
                        else -> {
                            // other events handled elsewhere or update state as needed
                        }
                    }
                }
            }
        }
    }

    private fun validateTransition(current: ConnectionState, next: ConnectionState): ConnectionState {
        // Enforce valid transitions: REGISTERED -> CONNECTING -> SIGNALING -> WEBRTC_CONNECTING -> DATA_CHANNEL_OPENING -> CONNECTED
        // Any active state can transition to DISCONNECTED or FAILED
        if (next == ConnectionState.DISCONNECTED || next == ConnectionState.FAILED) return next

        return when (current) {
            ConnectionState.REGISTERED -> if (next == ConnectionState.CONNECTING) next else current
            ConnectionState.DISCONNECTED, ConnectionState.FAILED -> if (next == ConnectionState.CONNECTING || next == ConnectionState.RECONNECTING) next else current
            ConnectionState.CONNECTING -> if (next == ConnectionState.SIGNALING) next else current
            ConnectionState.SIGNALING -> if (next == ConnectionState.WEBRTC_CONNECTING) next else current
            ConnectionState.WEBRTC_CONNECTING -> if (next == ConnectionState.DATA_CHANNEL_OPENING) next else current
            ConnectionState.DATA_CHANNEL_OPENING -> if (next == ConnectionState.CONNECTED) next else current
            ConnectionState.CONNECTED -> if (next == ConnectionState.DEGRADED) next else current
            ConnectionState.DEGRADED -> if (next == ConnectionState.CONNECTED || next == ConnectionState.RECONNECTING) next else current
            ConnectionState.RECONNECTING -> if (next == ConnectionState.CONNECTED) next else current
        }
    }

    override fun unregisterTracker(deviceId: TrackerId) {
        val session = sessions.remove(deviceId)
        session?.heartbeatMonitor?.stop()
        session?.observeJob?.cancel()
        scope.launch {
            session?.transport?.disconnect()
        }
    }

    override fun clearSessions() {
        val sessionKeys = sessions.keys().toList()
        sessionKeys.forEach { unregisterTracker(it) }
    }

    override fun getSession(deviceId: TrackerId): StateFlow<TrackerSessionState>? {
        return sessions[deviceId]?.stateFlow?.asStateFlow()
    }

    override fun getAllSessions(): List<StateFlow<TrackerSessionState>> {
        return sessions.values.map { it.stateFlow.asStateFlow() }
    }

    override fun hasSession(deviceId: TrackerId): Boolean {
        return sessions.containsKey(deviceId)
    }

    override fun listSessions(): List<TrackerId> {
        return sessions.keys().toList()
    }

    override suspend fun startConnection(deviceId: TrackerId) {
        val session = sessions[deviceId] ?: return
        session.transport.connect()
    }

    override suspend fun disconnect(deviceId: TrackerId) {
        val session = sessions[deviceId] ?: return
        session.transport.disconnect()
    }

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        val session = sessions[command.deviceId]
        if (session == null) {
            return CommandResult.Failed(command.commandId, command.deviceId, "Session not found")
        }

        if (session.stateFlow.value.connectionState != ConnectionState.CONNECTED) {
            return CommandResult.Failed(command.commandId, command.deviceId, "Device not connected")
        }
        
        // Note: Capabilities vs Permissions isolation
        // Check if command is supported by capability
        val caps = session.stateFlow.value.capabilities
        val isSupported = when (command) {
            is TrackerCommand.RequestLocation -> caps.location
            is TrackerCommand.StartCamera, is TrackerCommand.StopCamera -> caps.camera
            is TrackerCommand.StartAudio, is TrackerCommand.StopAudio -> caps.audio
            is TrackerCommand.StartScreen, is TrackerCommand.StopScreen -> caps.screen
            is TrackerCommand.ListFiles -> caps.fileTransfer
        }
        
        if (!isSupported) {
            return CommandResult.Unsupported(command.commandId, command.deviceId)
        }
        
        // In a real app, authorization engine runs before this. Here we assume command is authorized if it reaches here, 
        // OR we can explicitly fail if permission is missing in state.
        
        return session.transport.sendCommand(command)
    }
}
