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
        println("DEBUG-FAKE: connect() called")
        connectCount++
        if (connectShouldSuspendForever) {
            println("DEBUG-FAKE: suspending forever")
            kotlinx.coroutines.delay(Long.MAX_VALUE)
        }
        if (connectShouldFail) {
            println("DEBUG-FAKE: failing connection")
            _connectionState.value = ConnectionState.FAILED
            return
        }
        
        println("DEBUG-FAKE: emitting CONNECTING")
        _connectionState.value = ConnectionState.CONNECTING
        kotlinx.coroutines.yield()
        println("DEBUG-FAKE: emitting SIGNALING")
        _connectionState.value = ConnectionState.SIGNALING
        kotlinx.coroutines.yield()
        println("DEBUG-FAKE: emitting WEBRTC_CONNECTING")
        _connectionState.value = ConnectionState.WEBRTC_CONNECTING
        kotlinx.coroutines.yield()
        println("DEBUG-FAKE: emitting DATA_CHANNEL_OPENING")
        _connectionState.value = ConnectionState.DATA_CHANNEL_OPENING
        kotlinx.coroutines.yield()
        println("DEBUG-FAKE: emitting CONNECTED")
        _connectionState.value = ConnectionState.CONNECTED
        kotlinx.coroutines.yield()
        println("DEBUG-FAKE: connect() finished")
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
        println("DEBUG-FAKE: simulateDisconnect() called. Setting state to DISCONNECTED.")
        _connectionState.value = ConnectionState.DISCONNECTED
        println("DEBUG-FAKE: simulateDisconnect() finished.")
    }
    
    suspend fun simulateFailure() {
        _connectionState.value = ConnectionState.FAILED
    }
}
