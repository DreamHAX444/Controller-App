package com.livetracker.controller.core.tracker.recovery

import com.livetracker.controller.core.tracker.domain.*
import com.livetracker.controller.core.tracker.session.DefaultTimeSource
import com.livetracker.controller.core.tracker.session.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TrackerRecoveryManager(
    override val deviceId: TrackerId,
    private val scope: CoroutineScope,
    private val transportFactory: () -> TrackerTransport,
    private val timeSource: TimeSource = DefaultTimeSource,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val networkStateProvider: NetworkStateProvider? = null
) : TrackerTransport {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.REGISTERED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<TrackerEvent>(extraBufferCapacity = 64)
    override val events: Flow<TrackerEvent> = _events.asSharedFlow()
    
    val recoveryEvents = MutableSharedFlow<RecoveryEvent>(extraBufferCapacity = 64)

    private var currentTransport: TrackerTransport? = null
    private var connectionGeneration = 0
    private var isIntentionallyDisconnected = false
    
    private var recoveryJob: Job? = null
    private var transportObserveJob: Job? = null
    private var currentAttempt = 0

    override suspend fun connect() {
        isIntentionallyDisconnected = false
        startNewGeneration()
    }

    override suspend fun disconnect() {
        isIntentionallyDisconnected = true
        cancelRecovery("Intentional disconnect")
        
        recoveryEvents.emit(RecoveryEvent.IntentionalDisconnect(deviceId, timeSource.currentTimeMillis(), currentAttempt))
        
        currentTransport?.disconnect()
        transportObserveJob?.cancel()
        currentTransport = null
        
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return CommandResult.Failed(command.commandId, deviceId, "Not connected")
        }
        val transport = currentTransport ?: return CommandResult.Failed(command.commandId, deviceId, "No transport")
        
        val gen = connectionGeneration
        val result = transport.sendCommand(command)
        
        if (connectionGeneration != gen || isIntentionallyDisconnected || _connectionState.value != ConnectionState.CONNECTED) {
             return CommandResult.Failed(command.commandId, deviceId, "Connection lost during command")
        }
        
        return result
    }

    private suspend fun startNewGeneration(isRecovery: Boolean = false) {
        val nextGen = ++connectionGeneration
        currentTransport?.disconnect()
        transportObserveJob?.cancel()
        
        val newTransport = transportFactory()
        currentTransport = newTransport
        
        transportObserveJob = scope.launch {
            launch {
                try {
                    newTransport.connectionState.collect { state ->
                        if (connectionGeneration != nextGen) return@collect
                        
                        if (!isRecovery) {
                            _connectionState.value = state
                        } else {
                            when (state) {
                                ConnectionState.REGISTERED,
                                ConnectionState.CONNECTING,
                                ConnectionState.SIGNALING -> _connectionState.value = ConnectionState.SIGNALING_RECOVERY
                                ConnectionState.WEBRTC_CONNECTING -> _connectionState.value = ConnectionState.WEBRTC_RECOVERY
                                ConnectionState.DATA_CHANNEL_OPENING -> _connectionState.value = ConnectionState.DATA_CHANNEL_RECOVERY
                                ConnectionState.CONNECTED -> _connectionState.value = ConnectionState.CONNECTED
                                ConnectionState.FAILED -> _connectionState.value = ConnectionState.FAILED
                                ConnectionState.DISCONNECTED -> _connectionState.value = ConnectionState.DISCONNECTED
                                else -> {}
                            }
                        }
                        
                        if ((state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) && !isIntentionallyDisconnected) {
                            triggerRecovery("Transport disconnected")
                        }
                    }
                } catch (e: Exception) {
                    // Suppress or log properly in production logging framework
                }
            }
            
            launch {
                newTransport.events.collect { event ->
                    if (connectionGeneration != nextGen) return@collect
                    
                    // Filter out stale commands completing if they somehow leaked, though sendCommand handles generation isolation
                    _events.emit(event)
                }
            }
        }
        
        // Yield to allow the observer job to start and subscribe before we connect
        kotlinx.coroutines.yield()
        newTransport.connect()
    }
    
    fun triggerRecovery(reason: String) {
        if (isIntentionallyDisconnected) return
        if (recoveryJob?.isActive == true) return 
        
        _connectionState.value = ConnectionState.RECONNECTING
        
        recoveryJob = scope.launch {
            recoveryEvents.emit(RecoveryEvent.RecoveryStarted(deviceId, timeSource.currentTimeMillis(), currentAttempt, reason))
            
            while (isActive) {
                currentAttempt++
                if (currentAttempt > policy.maxAttempts) {
                    recoveryEvents.emit(RecoveryEvent.RecoveryExhausted(deviceId, timeSource.currentTimeMillis(), currentAttempt))
                    _connectionState.value = ConnectionState.FAILED
                    break
                }
                
                val delayMs = policy.calculateDelay(currentAttempt)
                
                networkStateProvider?.let { provider ->
                    if (provider.networkState.value == NetworkState.LOST) {
                        provider.networkState.first { it == NetworkState.AVAILABLE }
                    }
                }
                
                delay(delayMs)
                if (!isActive) break
                
                recoveryEvents.emit(RecoveryEvent.RecoveryAttemptStarted(deviceId, timeSource.currentTimeMillis(), currentAttempt))
                
                _connectionState.value = ConnectionState.SIGNALING_RECOVERY
                
                try {
                    startNewGeneration(isRecovery = true)
                    
                    val finalState = currentTransport!!.connectionState.first { 
                        it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED || it == ConnectionState.FAILED 
                    }
                    
                    if (finalState == ConnectionState.CONNECTED) {
                        _connectionState.value = ConnectionState.CONNECTED
                        recoveryEvents.emit(RecoveryEvent.RecoverySucceeded(deviceId, timeSource.currentTimeMillis(), currentAttempt))
                        currentAttempt = 0
                        break
                    } else {
                        throw Exception("Transport reached terminal state before CONNECTED")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recoveryEvents.emit(RecoveryEvent.RecoveryAttemptFailed(deviceId, timeSource.currentTimeMillis(), currentAttempt, e))
                }
            }
        }
    }

    private fun cancelRecovery(reason: String) {
        if (recoveryJob?.isActive == true) {
            scope.launch { recoveryEvents.emit(RecoveryEvent.RecoveryCancelled(deviceId, timeSource.currentTimeMillis(), currentAttempt, reason)) }
            recoveryJob?.cancel()
        }
    }
}
