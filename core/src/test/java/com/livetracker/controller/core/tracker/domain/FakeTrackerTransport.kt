package com.livetracker.controller.core.tracker.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeTrackerTransport(
    override val deviceId: TrackerId
) : TrackerTransport {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<TrackerEvent>(extraBufferCapacity = 10)
    override val events = _events

    var connectCalled = false
    var disconnectCalled = false
    val sentCommands = mutableListOf<TrackerCommand>()
    
    var commandResultToReturn: CommandResult? = null

    override suspend fun connect() {
        connectCalled = true
        _connectionState.value = ConnectionState.CONNECTING
    }

    override suspend fun disconnect() {
        disconnectCalled = true
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        sentCommands.add(command)
        return commandResultToReturn ?: CommandResult.Completed(command.commandId, command.deviceId)
    }

    suspend fun emitEvent(event: TrackerEvent) {
        _events.emit(event)
    }
    
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
}
