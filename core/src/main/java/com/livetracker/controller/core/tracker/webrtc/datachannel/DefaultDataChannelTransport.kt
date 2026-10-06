package com.livetracker.controller.core.tracker.webrtc.datachannel

import com.livetracker.controller.core.tracker.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class DefaultDataChannelTransport(
    override val deviceId: TrackerId,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : DataChannelTransport {

    private val _events = MutableSharedFlow<TrackerEvent>(extraBufferCapacity = 64)
    override val events: Flow<TrackerEvent> = _events.asSharedFlow()
    
    private var dataChannel: TrackerDataChannel? = null
    private var observeJob: Job? = null
    private val mutex = Mutex()
    
    private val pendingCommands = mutableMapOf<String, CompletableDeferred<CommandResult>>()
    
    override suspend fun connect(channel: TrackerDataChannel) {
        mutex.withLock {
            if (dataChannel != null) {
                disconnectInternal()
            }
            dataChannel = channel
            
            observeJob = scope.launch {
                channel.incomingMessages.collect { message ->
                    handleIncomingMessage(message)
                }
            }
        }
    }
    
    override suspend fun disconnect() {
        mutex.withLock {
            disconnectInternal()
        }
    }
    
    private fun disconnectInternal() {
        observeJob?.cancel()
        observeJob = null
        dataChannel = null
        
        val exception = CancellationException("DataChannel disconnected")
        pendingCommands.values.forEach { it.cancel(exception) }
        pendingCommands.clear()
    }
    
    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        val channel = dataChannel
        if (channel == null || channel.state.value != DataChannelState.OPEN) {
            return CommandResult.Failed(command.commandId, command.deviceId, "DataChannel unavailable")
        }
        
        val deferred = CompletableDeferred<CommandResult>()
        mutex.withLock {
            pendingCommands[command.commandId] = deferred
        }
        
        val envelope = TrackerCommandEnvelope(
            id = command.commandId.toLongOrNull(),
            deviceId = command.deviceId.value,
            command = getCommandType(command)
        )
        
        val jsonString = try {
             json.encodeToString(TrackerCommandEnvelope.serializer(), envelope)
        } catch (e: Exception) {
            mutex.withLock {
                pendingCommands.remove(command.commandId)
            }
            return CommandResult.Failed(command.commandId, command.deviceId, "Serialization failed")
        }
        
        val success = channel.send(jsonString)
        if (!success) {
            mutex.withLock {
                pendingCommands.remove(command.commandId)
            }
            return CommandResult.Failed(command.commandId, command.deviceId, "Send failed")
        }
        
        return try {
            withTimeout(command.timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            mutex.withLock {
                pendingCommands.remove(command.commandId)
            }
            CommandResult.Timeout(command.commandId, command.deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult.Failed(command.commandId, command.deviceId, e.message ?: "Unknown error")
        }
    }
    
    private fun getCommandType(command: TrackerCommand): String {
        return when (command) {
            is TrackerCommand.RequestLocation -> "request_location"
            is TrackerCommand.StartCamera -> "start_camera"
            is TrackerCommand.StopCamera -> "stop_camera"
            is TrackerCommand.StartAudio -> "start_audio"
            is TrackerCommand.StopAudio -> "stop_audio"
            is TrackerCommand.StartScreen -> "start_screen"
            is TrackerCommand.StopScreen -> "stop_screen"
            is TrackerCommand.ListFiles -> "list_files"
        }
    }
    
    private suspend fun handleIncomingMessage(message: String) {
        try {
            val element = json.parseToJsonElement(message)
            if (element !is JsonObject) return
            
            if (element.containsKey("command") && element.containsKey("device_id")) {
                val envelope = json.decodeFromJsonElement(TrackerCommandEnvelope.serializer(), element)
                val commandId = envelope.id?.toString() ?: return
                
                val result = when (envelope.status) {
                    "executed" -> CommandResult.Completed(commandId, deviceId)
                    "error" -> CommandResult.Failed(commandId, deviceId, envelope.params ?: "Command failed")
                    else -> CommandResult.Completed(commandId, deviceId, envelope.params)
                }
                
                mutex.withLock {
                    pendingCommands.remove(commandId)?.complete(result)
                }
                return
            }
            
        } catch (e: Exception) {
            // Ignore malformed messages without crashing
        }
    }
}
