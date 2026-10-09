package com.livetracker.controller.core.tracker.session

import com.livetracker.controller.core.tracker.domain.*
import com.livetracker.controller.core.tracker.signaling.*
import com.livetracker.controller.core.tracker.webrtc.WebRtcSession
import com.livetracker.controller.core.tracker.webrtc.WebRtcState
import com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*


class DefaultTrackerTransport(
    override val deviceId: TrackerId,
    private val scope: CoroutineScope,
    private val signalingManager: SignalingManager,
    private val webRtcSession: WebRtcSession,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val dataChannelTransportFactory: (TrackerId, CoroutineScope) -> DataChannelTransport
) : TrackerTransport {


    private val _connectionState = MutableStateFlow(ConnectionState.REGISTERED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var signalingSession: SignalingSession? = null
    private var dataChannelTransport: DataChannelTransport? = null
    private var observeJob: Job? = null

    // For now, only emit events from DataChannelTransport when it's available
    private val _dataChannelTransportState = MutableStateFlow<DataChannelTransport?>(null)
    private val _internalEvents = MutableSharedFlow<TrackerEvent>(extraBufferCapacity = 64)
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val events: Flow<TrackerEvent> = merge(
        _internalEvents,
        _dataChannelTransportState.filterNotNull().flatMapLatest { it.events }
    )

    override suspend fun connect() {
        if (_connectionState.value != ConnectionState.REGISTERED && _connectionState.value != ConnectionState.DISCONNECTED && _connectionState.value != ConnectionState.FAILED) return
        _connectionState.value = ConnectionState.CONNECTING

        try {
            signalingSession = signalingManager.createSession(deviceId, "session-${System.currentTimeMillis()}")
            
            observeJob = scope.launch {
                launch {
                    signalingSession?.incomingMessages?.collect { msg ->
                        when (msg.type) {
                            SignalingMessageType.OFFER -> {
                                val offer = msg.payload as? OfferPayload
                                if (offer != null) {
                                    val answer = webRtcSession.setRemoteOfferAndCreateAnswer(offer)
                                    signalingSession?.sendMessage(SignalingMessageType.ANSWER, answer)
                                }
                            }
                            SignalingMessageType.ICE_CANDIDATE -> {
                                val candidate = msg.payload as? IceCandidatePayload
                                if (candidate != null) {
                                    webRtcSession.addIceCandidate(candidate)
                                }
                            }
                            else -> {}
                        }
                    }
                }

                launch {
                    webRtcSession.localIceCandidates.collect { candidate ->
                        signalingSession?.sendMessage(SignalingMessageType.ICE_CANDIDATE, candidate)
                    }
                }

                launch {
                    signalingSession?.state?.collect { sigState ->
                        when (sigState) {
                            SignalingState.CONNECTING, SignalingState.CONNECTED, SignalingState.NEGOTIATING -> {
                                if (_connectionState.value == ConnectionState.CONNECTING) {
                                    _connectionState.value = ConnectionState.SIGNALING
                                }
                            }
                            SignalingState.FAILED -> {
                                _connectionState.value = ConnectionState.FAILED
                            }
                            SignalingState.DISCONNECTED -> {
                                if (_connectionState.value != ConnectionState.CONNECTING) {
                                    _connectionState.value = ConnectionState.DISCONNECTED
                                }
                            }
                            else -> {}
                        }
                    }
                }

                launch {
                    webRtcSession.state.collect { rtcState ->
                        when (rtcState) {
                            WebRtcState.CONNECTING -> {
                                if (_connectionState.value == ConnectionState.SIGNALING || _connectionState.value == ConnectionState.CONNECTING) {
                                    _connectionState.value = ConnectionState.WEBRTC_CONNECTING
                                }
                            }
                            WebRtcState.CONNECTED -> {
                                if (_connectionState.value == ConnectionState.WEBRTC_CONNECTING) {
                                    _connectionState.value = ConnectionState.DATA_CHANNEL_OPENING
                                }
                            }
                            WebRtcState.FAILED -> {
                                _connectionState.value = ConnectionState.FAILED
                            }
                            WebRtcState.DISCONNECTED, WebRtcState.CLOSED -> {
                                _connectionState.value = ConnectionState.DISCONNECTED
                            }
                            else -> {}
                        }
                    }
                }

                launch {
                    webRtcSession.incomingDataChannels.collect { dc ->
                        when (dc.label) {
                            "commands" -> {
                                setupDataChannel(dc)
                                
                                launch {
                                    dc.state.collect { dcState ->
                                        when (dcState) {
                                            com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState.OPENING -> {
                                                _connectionState.value = ConnectionState.DATA_CHANNEL_OPENING
                                            }
                                            com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState.OPEN -> {
                                                _connectionState.value = ConnectionState.CONNECTED
                                            }
                                            com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState.FAILED, 
                                            com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState.CLOSED -> {
                                                _connectionState.value = ConnectionState.DISCONNECTED
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                            }
                            "locations" -> {
                                setupLocationsChannel(dc)
                            }
                            else -> {
                                dc.close()
                            }
                        }
                    }
                }
            }
            
            signalingSession?.start()
            signalingSession?.sendMessage(SignalingMessageType.START_REQUEST, null)

        } catch (e: Exception) {
            e.printStackTrace()
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private suspend fun setupLocationsChannel(dc: com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel) {
        scope.launch {
            dc.incomingMessages.collect { msg ->
                try {
                    val element = json.parseToJsonElement(msg)
                    if (element is JsonObject) {
                        val eventType = element["event"]?.jsonPrimitive?.contentOrNull
                        val payload = element["message"] ?: element["payload"] ?: element
                        
                        if (eventType == "location" || payload.jsonObject.containsKey("latitude")) {
                            val data = payload.jsonObject
                            val devId = data["device_id"]?.jsonPrimitive?.contentOrNull ?: deviceId.value
                            val lat = data["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@collect
                            val lon = data["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@collect
                            val acc = data["accuracy"]?.jsonPrimitive?.floatOrNull ?: 0f
                            
                            val timestampStr = data["created_at"]?.jsonPrimitive?.contentOrNull
                            val timestamp = timestampStr?.let { 
                                try {
                                    java.time.Instant.parse(it).toEpochMilli()
                                } catch (e: Exception) {
                                    System.currentTimeMillis()
                                }
                            } ?: System.currentTimeMillis()
                            
                            val bearing = data["bearing"]?.jsonPrimitive?.floatOrNull
                            val speed = data["speed"]?.jsonPrimitive?.floatOrNull

                            val locationEvent = TrackerEvent.LocationUpdated(
                                eventId = "loc-${java.util.UUID.randomUUID()}",
                                deviceId = TrackerId(devId),
                                timestamp = timestamp,
                                latitude = lat,
                                longitude = lon,
                                accuracy = acc,
                                bearing = bearing,
                                speed = speed
                            )
                            _internalEvents.emit(locationEvent)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore parse errors for robust connection
                }
            }
        }
    }

    private suspend fun setupDataChannel(dc: com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel) {
        val transport = dataChannelTransportFactory(deviceId, scope)
        transport.connect(dc)
        dataChannelTransport = transport
        _dataChannelTransportState.value = transport
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        observeJob?.cancel()
        dataChannelTransport?.disconnect()
        dataChannelTransport = null
        _dataChannelTransportState.value = null
        
        webRtcSession.stop()
        signalingSession?.stop()
        signalingManager.closeSession(deviceId)
        signalingSession = null
    }

    override suspend fun sendCommand(command: TrackerCommand): CommandResult {
        val transport = dataChannelTransport
        if (transport == null) {
            return CommandResult.Failed(command.commandId, command.deviceId, "DataChannel unavailable")
        }
        return transport.sendCommand(command)
    }
}
