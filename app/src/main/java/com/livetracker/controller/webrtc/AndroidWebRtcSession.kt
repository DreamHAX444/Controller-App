package com.livetracker.controller.webrtc

import android.util.Log
import com.livetracker.controller.core.tracker.signaling.AnswerPayload
import com.livetracker.controller.core.tracker.signaling.IceCandidatePayload
import com.livetracker.controller.core.tracker.signaling.OfferPayload
import com.livetracker.controller.core.tracker.webrtc.WebRtcConfiguration
import com.livetracker.controller.core.tracker.webrtc.WebRtcSession
import com.livetracker.controller.core.tracker.webrtc.WebRtcState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel
import com.livetracker.controller.app.webrtc.datachannel.AndroidTrackerDataChannel
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidWebRtcSession(
    private val sessionId: String,
    private val configuration: WebRtcConfiguration,
    private val factory: PeerConnectionFactory,
    private val scope: CoroutineScope
) : WebRtcSession {
    private val tag = "AndroidWebRtcSession"

    private val _state = MutableStateFlow(WebRtcState.NEW)
    override val state: StateFlow<WebRtcState> = _state.asStateFlow()

    private val _localIceCandidates = MutableSharedFlow<IceCandidatePayload>(extraBufferCapacity = 100)
    override val localIceCandidates: Flow<IceCandidatePayload> = _localIceCandidates.asSharedFlow()

    private val _incomingDataChannels = MutableSharedFlow<TrackerDataChannel>(extraBufferCapacity = 10)
    override val incomingDataChannels: Flow<TrackerDataChannel> = _incomingDataChannels.asSharedFlow()
    
    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _liveBitrateKbps = MutableStateFlow<Int?>(null)
    val liveBitrateKbps: StateFlow<Int?> = _liveBitrateKbps.asStateFlow()

    private var statsJob: Job? = null
    private var lastBytesReceived: Long = 0L
    private var lastTimestampMs: Long = 0L
    
    private val activeDataChannels = mutableListOf<AndroidTrackerDataChannel>()
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private var peerConnection: PeerConnection? = null
    private val sessionMutex = Mutex()
    private var currentGeneration: Long = -1L

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    _state.value = WebRtcState.CONNECTED
                    startStatsPolling()
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    _state.value = WebRtcState.DISCONNECTED
                    stopStatsPolling()
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    _state.value = WebRtcState.FAILED
                    stopStatsPolling()
                }
                PeerConnection.IceConnectionState.CLOSED -> {
                    _state.value = WebRtcState.CLOSED
                    stopStatsPolling()
                }
                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            val payload = IceCandidatePayload(
                candidate = candidate.sdp,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex,
                generation = currentGeneration
            )
            _localIceCandidates.tryEmit(payload)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(dataChannel: DataChannel?) {
            if (dataChannel != null) {
                Log.d(tag, "DataChannel received: ${dataChannel.label()}")
                val trackerChannel = AndroidTrackerDataChannel(dataChannel)
                activeDataChannels.add(trackerChannel)
                _incomingDataChannels.tryEmit(trackerChannel)
            }
        }

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            val track = receiver?.track()
            Log.d(tag, "Track received: ${track?.kind()}")
            if (track is VideoTrack) {
                _remoteVideoTrack.value = track
            }
        }
    }

    init {
        createPeerConnection()
    }

    companion object {
        fun buildRtcConfiguration(
            configuration: WebRtcConfiguration
        ): PeerConnection.RTCConfiguration {
            val iceServers = configuration.iceServers.map {
                PeerConnection.IceServer.builder(it.url)
                    .setUsername(it.username ?: "")
                    .setPassword(it.password ?: "")
                    .createIceServer()
            }
            return PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = if (configuration.continualGathering) {
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                } else {
                    PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                }
                candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
                tcpCandidatePolicy = if (configuration.tcpCandidatesEnabled) {
                    PeerConnection.TcpCandidatePolicy.ENABLED
                } else {
                    PeerConnection.TcpCandidatePolicy.DISABLED
                }
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                iceCandidatePoolSize = configuration.iceCandidatePoolSize
            }
        }
    }

    private fun createPeerConnection() {
        val rtcConfig = buildRtcConfiguration(configuration)
        peerConnection = factory.createPeerConnection(rtcConfig, observer)
        _state.value = WebRtcState.CONNECTING
    }

    override suspend fun setRemoteOfferAndCreateAnswer(offer: OfferPayload): AnswerPayload = sessionMutex.withLock {
        val pc = peerConnection ?: throw IllegalStateException("PeerConnection is closed")
        
        currentGeneration = offer.generation

        // 1. Set Remote Description
        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, offer.sdp)
        pc.setRemoteDescriptionSuspend(remoteSdp)

        // Drain any ICE candidates that arrived before remote description was ready
        pendingRemoteCandidates.forEach { pc.addIceCandidate(it) }
        pendingRemoteCandidates.clear()

        // 2. Create Answer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        val localSdp = pc.createAnswerSuspend(constraints)

        // 3. Set Local Description
        pc.setLocalDescriptionSuspend(localSdp)

        return AnswerPayload(
            sdp = localSdp.description,
            generation = offer.generation
        )
    }

    override suspend fun addIceCandidate(candidate: IceCandidatePayload): Unit = sessionMutex.withLock {
        val pc = peerConnection ?: return@withLock
        
        // Ignore stale ICE candidates
        if (currentGeneration != -1L && candidate.generation != 0L && candidate.generation != currentGeneration) {
            Log.d(tag, "Ignoring stale ICE candidate. Expected $currentGeneration, got ${candidate.generation}")
            return@withLock
        }

        val iceCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        if (pc.remoteDescription == null) {
            pendingRemoteCandidates.add(iceCandidate)
            Log.d(tag, "Buffered early remote ICE candidate before remote description set")
        } else {
            pc.addIceCandidate(iceCandidate)
        }
    }

    override suspend fun stop() = sessionMutex.withLock {
        stopStatsPolling()
        peerConnection?.close()
        peerConnection = null
        pendingRemoteCandidates.clear()
        _remoteVideoTrack.value = null
        activeDataChannels.forEach {
            try { it.close() } catch (e: Exception) {}
        }
        activeDataChannels.clear()
        _state.value = WebRtcState.CLOSED
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                val pc = peerConnection ?: break
                if (_state.value != WebRtcState.CONNECTED) continue
                try {
                    pc.getStats { report ->
                        for (stat in report.statsMap.values) {
                            if (stat.type == "inbound-rtp") {
                                val kind = stat.members["kind"] as? String
                                if (kind == "video" || kind == null) {
                                    val bytes = (stat.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                                    val now = System.currentTimeMillis()
                                    if (lastBytesReceived > 0L && now > lastTimestampMs && bytes >= lastBytesReceived) {
                                        val deltaBytes = bytes - lastBytesReceived
                                        val deltaTimeMs = now - lastTimestampMs
                                        if (deltaTimeMs > 0) {
                                            val kbps = ((deltaBytes * 8L * 1000L) / (deltaTimeMs * 1000L)).toInt()
                                            if (kbps > 0) {
                                                _liveBitrateKbps.value = kbps
                                            }
                                        }
                                    }
                                    lastBytesReceived = bytes
                                    lastTimestampMs = now
                                    break
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore transient stats delivery failures
                }
            }
        }
    }

    private fun stopStatsPolling() {
        statsJob?.cancel()
        statsJob = null
        _liveBitrateKbps.value = null
        lastBytesReceived = 0L
        lastTimestampMs = 0L
    }
    
    override fun createDataChannel(label: String): TrackerDataChannel? {
        val init = DataChannel.Init()
        val dataChannel = peerConnection?.createDataChannel(label, init)
        return if (dataChannel != null) {
            val trackerChannel = AndroidTrackerDataChannel(dataChannel)
            activeDataChannels.add(trackerChannel)
            trackerChannel
        } else {
            null
        }
    }

    override fun getDataChannel(label: String): TrackerDataChannel? {
        return activeDataChannels.find { it.label == label }
    }
}

// Suspending extension functions for SdpObserver callbacks

private suspend fun PeerConnection.setRemoteDescriptionSuspend(description: SessionDescription) = suspendCoroutine<Unit> { cont ->
    setRemoteDescription(object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) { cont.resumeWithException(Exception("Failed to set remote description: $error")) }
    }, description)
}

private suspend fun PeerConnection.createAnswerSuspend(constraints: MediaConstraints) = suspendCoroutine<SessionDescription> { cont ->
    createAnswer(object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {
            if (desc != null) {
                cont.resume(desc)
            } else {
                cont.resumeWithException(Exception("Created answer is null"))
            }
        }
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { cont.resumeWithException(Exception("Failed to create answer: $error")) }
        override fun onSetFailure(error: String?) {}
    }, constraints)
}

private suspend fun PeerConnection.setLocalDescriptionSuspend(description: SessionDescription) = suspendCoroutine<Unit> { cont ->
    setLocalDescription(object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) { cont.resumeWithException(Exception("Failed to set local description: $error")) }
    }, description)
}
