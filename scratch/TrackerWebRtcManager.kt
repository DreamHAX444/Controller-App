package com.example.livelocationservice.webrtc

import android.content.Context
import android.util.Log
import com.example.livelocationservice.screen.CapturedScreenFrame
import com.example.livelocationservice.webrtc.signaling.OfferPayload
import com.example.livelocationservice.webrtc.signaling.AnswerPayload
import com.example.livelocationservice.webrtc.signaling.IceCandidatePayload
import com.example.livelocationservice.webrtc.signaling.SignalingMessageGuard
import com.example.livelocationservice.webrtc.signaling.SignalType
import com.example.livelocationservice.webrtc.signaling.TrackerWebRtcSignalingSession
import com.example.livelocationservice.webrtc.signaling.WebRtcSignalingTransport
import com.example.livelocationservice.webrtc.adaptation.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

enum class TrackerSessionState {
    IDLE, STARTING, SIGNALING, CONNECTING, CONNECTED, DEGRADED, RECOVERING, STOPPING, ENDED, FAILED
}

class TrackerWebRtcManager(
    private val context: Context,
    private val sessionId: String,
    private val deviceId: String,
    private val transport: WebRtcSignalingTransport,
    private val telemetryTransport: com.example.livelocationservice.webrtc.adaptation.AdaptationTelemetryTransport,
    private val frameFlow: Flow<CapturedScreenFrame>?,
    private val audioFrameFlow: Flow<com.example.livelocationservice.screen.audio.CapturedAudioFrame>?,
    private val fileTransferManager: com.example.livelocationservice.fileaccess.transfer.FileTransferManager?,
    private val videoQualityManager: com.example.livelocationservice.screen.VideoQualityManager?,
    private val initialQualityProfile: com.example.livelocationservice.screen.VideoQualityProfile,
    private val scope: CoroutineScope
) {
    private val tag = "TrackerWebRtcManager"

    private val _state = MutableStateFlow(TrackerSessionState.IDLE)
    val state: StateFlow<TrackerSessionState> = _state.asStateFlow()

    private var signalingSession: TrackerWebRtcSignalingSession? = null
    private var webRtcSession: WebRtcSession? = null
    private var audioSender: com.example.livelocationservice.screen.audio.transport.PlaybackAudioDataChannelSender? = null
    private var audioCollectionJob: kotlinx.coroutines.Job? = null
    
    private var adaptationController: AdaptiveQualityController? = null
    private var telemetryJob: kotlinx.coroutines.Job? = null
    
    private val recoveryMutex = Mutex()

    private var generation = System.currentTimeMillis()
    private val messageGuard = SignalingMessageGuard()
        
    private var authoritativeMode = VideoControlMode.AUTO
    private var authoritativeProfile = initialQualityProfile

    suspend fun start() {
        if (_state.value != TrackerSessionState.IDLE) return
        _state.value = TrackerSessionState.STARTING
        
        WebRtcPeerConnectionFactory.init(context.applicationContext)
        signalingSession = TrackerWebRtcSignalingSession(sessionId, deviceId, transport, scope)
        
        scope.launch {
            signalingSession?.incomingSignals?.collect { msg ->
                handleSignal(msg)
            }
        }
        
        signalingSession?.start()
        telemetryTransport.connect(sessionId)
        _state.value = TrackerSessionState.SIGNALING

        scope.launch {
            recoveryMutex.withLock {
                if (_state.value == TrackerSessionState.STOPPING || _state.value == TrackerSessionState.ENDED) return@withLock
                generation++
                val readyPayload = com.example.livelocationservice.webrtc.signaling.ReadyPayload(generation)
                signalingSession?.sendSignal(SignalType.READY, Json.encodeToJsonElement(readyPayload))
                startSessionGeneration(generation)
            }
        }
    }
    
    private suspend fun handleSignal(msg: com.example.livelocationservice.webrtc.signaling.WebRtcSignalMessage) {
        when (msg.type) {
            SignalType.START_REQUEST -> {
                recoveryMutex.withLock {
                    if (_state.value == TrackerSessionState.STOPPING || _state.value == TrackerSessionState.ENDED) return@withLock
                    if (_state.value == TrackerSessionState.CONNECTED || (_state.value == TrackerSessionState.CONNECTING && webRtcSession != null)) {
                        val readyPayload = com.example.livelocationservice.webrtc.signaling.ReadyPayload(generation)
                        signalingSession?.sendSignal(SignalType.READY, Json.encodeToJsonElement(readyPayload))
                        return@withLock
                    }
                    generation++
                    val readyPayload = com.example.livelocationservice.webrtc.signaling.ReadyPayload(generation)
                    signalingSession?.sendSignal(SignalType.READY, Json.encodeToJsonElement(readyPayload))
                    startSessionGeneration(generation)
                }
            }
            SignalType.ANSWER -> {
                msg.payload?.let {
                    val payload = Json.decodeFromJsonElement<AnswerPayload>(it)
                    if (!messageGuard.shouldAcceptAnswer(payload.generation)) return@let
                    webRtcSession?.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, payload.sdp))
                }
            }
            SignalType.ICE_CANDIDATE -> {
                msg.payload?.let {
                    val payload = Json.decodeFromJsonElement<IceCandidatePayload>(it)
                    val iceId = payload.candidate
                    if (!messageGuard.shouldAcceptIceCandidate(payload.generation, iceId)) return@let
                    val candidate = IceCandidate(payload.sdpMid, payload.sdpMLineIndex, payload.candidate)
                    webRtcSession?.addIceCandidate(candidate)
                }
            }
            SignalType.STOP_REQUEST -> {
                stop()
            }
            SignalType.VIDEO_PROFILE -> {
                msg.payload?.let { payloadElement ->
                    recoveryMutex.withLock {
                        try {
                            val payload = Json.decodeFromJsonElement<com.example.livelocationservice.webrtc.signaling.VideoProfilePayload>(payloadElement)
                            val mode = VideoControlMode.valueOf(payload.mode)
                            val profile = payload.profileName?.let { com.example.livelocationservice.screen.VideoQualityProfile.valueOf(it) }
                            
                            authoritativeMode = mode
                            if (profile != null) authoritativeProfile = profile

                            adaptationController?.setMode(mode, profile)
                            
                            if (adaptationController == null && profile != null) {
                                videoQualityManager?.applyProfile(profile)
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to apply VIDEO_PROFILE", e)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private suspend fun startSessionGeneration(currentGeneration: Long) {
        messageGuard.startNewGeneration(currentGeneration)
        Log.i(tag, "Starting session generation $currentGeneration")
        _state.value = TrackerSessionState.CONNECTING
        
        fileTransferManager?.detachTransport()
        
        webRtcSession?.stop()
        webRtcSession = null
        
        audioCollectionJob?.cancel()
        audioSender = null
        
        adaptationController?.stop()
        telemetryJob?.cancel()
        
        val factory = WebRtcPeerConnectionFactory.getFactory(null)
        val rtcConfig = WebRtcSessionConfig(sessionId)
        
        val webrtcListener = object : WebRtcSessionListener {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (generation != currentGeneration) return
                scope.launch {
                    val payload = IceCandidatePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex, generation = currentGeneration, candidateId = java.util.UUID.randomUUID().toString())
                    signalingSession?.sendSignal(SignalType.ICE_CANDIDATE, kotlinx.serialization.json.Json.encodeToJsonElement(payload))
                }
            }
            override fun onSdpOffer(sdp: SessionDescription) {
                if (generation != currentGeneration) return
                scope.launch {
                    val payload = OfferPayload(sdp.description, generation = currentGeneration, offerId = java.util.UUID.randomUUID().toString())
                    signalingSession?.sendSignal(SignalType.OFFER, kotlinx.serialization.json.Json.encodeToJsonElement(payload))
                }
            }
            override fun onSdpAnswer(sdp: SessionDescription) {}
            override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
                if (generation != currentGeneration) return
                
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        _state.value = TrackerSessionState.CONNECTED
                        if (audioSender == null) {
                            val dataChannel = webRtcSession?.audioDataChannel
                            if (dataChannel != null && audioFrameFlow != null) {
                                audioSender = com.example.livelocationservice.screen.audio.transport.PlaybackAudioDataChannelSender(dataChannel, sessionId, deviceId)
                                audioCollectionJob = scope.launch {
                                    audioFrameFlow.collect { frame ->
                                        audioSender?.sendFrame(frame)
                                    }
                                }
                            }
                        }
                        
                        val fileDataChannel = webRtcSession?.fileTransferDataChannel
                        if (fileDataChannel != null && fileTransferManager != null) {
                            val fileTransport = com.example.livelocationservice.fileaccess.transfer.WebRtcFileTransferTransport(fileDataChannel, scope)
                            fileTransport.startListening(fileTransferManager)
                            fileTransferManager.attachTransport(fileTransport)
                        }
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        fileTransferManager?.detachTransport()
                        _state.value = TrackerSessionState.DEGRADED
                        triggerRecovery(currentGeneration)
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        fileTransferManager?.detachTransport()
                        _state.value = TrackerSessionState.FAILED
                        triggerRecovery(currentGeneration)
                    }
                    else -> {}
                }
            }
        }
        
        webRtcSession = WebRtcSession(rtcConfig, factory, frameFlow, scope, webrtcListener)
        webRtcSession?.start()
        webRtcSession?.createOffer()
        
        val pc = webRtcSession?.getPeerConnection()
        if (pc != null) {
            val statsCollector = NetworkStatsCollector(pc, webRtcSession?.audioDataChannel, AdaptiveQualityConfig())
            val profileApplier = object : com.example.livelocationservice.webrtc.adaptation.ProfileApplier {
                override suspend fun applyProfile(profile: com.example.livelocationservice.screen.VideoQualityProfile) {
                    videoQualityManager?.applyProfile(profile)
                }
            }
            
            adaptationController = AdaptiveQualityController(
                statsCollector,
                NetworkQualityAnalyzer(),
                profileApplier,
                AdaptiveQualityConfig(),
                scope
            )
            adaptationController?.setMode(authoritativeMode, authoritativeProfile)
            adaptationController?.start()
            
            telemetryJob = scope.launch {
                adaptationController?.telemetryFlow?.collect { decision ->
                    val msg = com.example.livelocationservice.webrtc.adaptation.AdaptationTelemetryMessage(
                        sessionId = sessionId,
                        deviceId = deviceId,
                        timestamp = decision.timestampMs,
                        networkQuality = decision.networkQuality.name,
                        requestedProfile = decision.targetProfile.name,
                        actualProfile = decision.currentProfile.name,
                        mode = adaptationController?.currentMode?.name ?: "UNKNOWN",
                        reason = decision.reason,
                        isStale = decision.metrics?.isStale ?: false,
                        audioTransportPressure = decision.metrics?.audioTransportPressure?.name ?: "NORMAL",
                        audioPlaybackHealth = "UNKNOWN"
                    )
                    telemetryTransport.sendTelemetry(msg)
                }
            }
        }
        
        videoQualityManager?.webRtcSession = webRtcSession
    }
    
    private fun triggerRecovery(failedGeneration: Long) {
        scope.launch {
            recoveryMutex.withLock {
                if (generation != failedGeneration) return@withLock
                if (_state.value == TrackerSessionState.STOPPING || _state.value == TrackerSessionState.ENDED) return@withLock
                
                Log.w(tag, "Triggering recovery for generation $failedGeneration")
                _state.value = TrackerSessionState.RECOVERING
                
                delay(2000) // Debounce transient failures
                
                // If stopped during debounce, abort
                if (_state.value == TrackerSessionState.STOPPING || _state.value == TrackerSessionState.ENDED) return@withLock
                
                if (_state.value == TrackerSessionState.CONNECTED) {
                    Log.i(tag, "Aborting recovery: Session recovered naturally")
                    return@withLock
                }
                
                generation++
                
                // Re-send READY to controller so it knows to drop its PC and wait for a new OFFER
                
                val readyPayload = com.example.livelocationservice.webrtc.signaling.ReadyPayload(generation)
                signalingSession?.sendSignal(SignalType.READY, kotlinx.serialization.json.Json.encodeToJsonElement(readyPayload))
                
                startSessionGeneration(generation)
            }
        }
    }

    
    suspend fun handleRealtimeReconnect() {
        Log.i(tag, "Handling Realtime reconnect")
        signalingSession?.reconnect()
        try {
            telemetryTransport.invalidate()
            telemetryTransport.connect(sessionId)
        } catch (e: Exception) {
            Log.e(tag, "Error reconnecting telemetry transport", e)
        }
    }
    suspend fun startCameraVideo(cameraFrameFlow: kotlinx.coroutines.flow.Flow<com.example.livelocationservice.camera.CapturedCameraFrame>) {
        webRtcSession?.startCameraVideo(cameraFrameFlow)
    }

    suspend fun stopCameraVideo() {
        webRtcSession?.stopCameraVideo()
    }

    suspend fun stop() {
        Log.i(tag, "Stopping WebRTC Manager")
        _state.value = TrackerSessionState.STOPPING
        fileTransferManager?.detachTransport()
        
        recoveryMutex.withLock {
            generation++ // Invalidate any ongoing callbacks
            
            scope.launch {
                signalingSession?.sendSignal(SignalType.SESSION_ENDED, kotlinx.serialization.json.JsonObject(emptyMap()))
                signalingSession?.stop()
            }
            
            adaptationController?.stop()
            adaptationController = null
            telemetryJob?.cancel()
            telemetryJob = null
            audioCollectionJob?.cancel()
            audioCollectionJob = null
            audioSender = null
            webRtcSession?.stop()
            
            try { telemetryTransport.disconnect() } catch (e: Exception) {}
            
            _state.value = TrackerSessionState.ENDED
        }
    }
}
