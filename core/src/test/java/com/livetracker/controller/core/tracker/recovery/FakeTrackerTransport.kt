package com.livetracker.controller.core.tracker.recovery

import com.livetracker.controller.core.tracker.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeTrackerTransport(
    override val deviceId: TrackerId
) : TrackerTransport {

    val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.REGISTERED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState
    
    val _events = MutableSharedFlow<TrackerEvent>(extraBufferCapacity = 64)
    override val events: Flow<TrackerEvent> = _events
    
    var connectCount = 0
    var disconnectCount = 0
    
    var connectShouldFail = false
    var connectShouldSuspendForever = false

    override suspend fun connect() {
        connectCount++
        if (connectShouldSuspendForever) {
            kotlinx.coroutines.delay(Long.MAX_VALUE)
        }
        if (connectShouldFail) {
            _connectionState.value = ConnectionState.FAILED
            return
        }
        
        _connectionState.value = ConnectionState.CONNECTING
        kotlinx.coroutines.yield()
        _connectionState.value = ConnectionState.SIGNALING
        kotlinx.coroutines.yield()
        _connectionState.value = ConnectionState.WEBRTC_CONNECTING
        kotlinx.coroutines.yield()
        _connectionState.value = ConnectionState.DATA_CHANNEL_OPENING
        kotlinx.coroutines.yield()
        _connectionState.value = ConnectionState.CONNECTED
        kotlinx.coroutines.yield()
    }

    override suspend fun disconnect() {
        disconnectCount++
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    var commandHandler: suspend (TrackerCommand) -> CommandResult = { 
        CommandResult.Completed(it.commandId, deviceId) 
    }
    
    var pendingCommandCount = 0

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        pendingCommandCount++
        return commandHandler(command)
    }
    
    suspend fun simulateDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    suspend fun simulateFailure() {
        _connectionState.value = ConnectionState.FAILED
    }
}
