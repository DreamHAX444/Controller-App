package com.example.livelocationservice.webrtc

import android.util.Log
import com.example.livelocationservice.screen.CapturedScreenFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

interface WebRtcSessionListener {
    fun onIceCandidate(candidate: IceCandidate)
    fun onSdpOffer(sdp: SessionDescription)
    fun onSdpAnswer(sdp: SessionDescription)
    fun onConnectionStateChange(state: PeerConnection.PeerConnectionState)
}

open class WebRtcSession(
    private val config: WebRtcSessionConfig,
    private val factory: PeerConnectionFactory,
    private val frameFlow: Flow<CapturedScreenFrame>?,
    private val scope: CoroutineScope,
    private val listener: WebRtcSessionListener
) {
    private val tag = "WebRtcSession"
    val sessionId: String = config.sessionId

    private val _state = MutableStateFlow(WebRtcSessionState.IDLE)
    val state: StateFlow<WebRtcSessionState> = _state.asStateFlow()

    private var videoSource: WebRtcVideoSource? = null
    private var videoTrack: VideoTrack? = null
    var audioDataChannel: org.webrtc.DataChannel? = null
    var fileTransferDataChannel: org.webrtc.DataChannel? = null
    
    private var peerConnection: PeerConnection? = null
    fun getPeerConnection(): PeerConnection? = peerConnection

    @Volatile private var isClosed = false
    private val mutex = Mutex()
    
    private val pendingIceCandidates = java.util.concurrent.CopyOnWriteArrayList<IceCandidate>()

    suspend fun start() {
        mutex.withLock {
            if (isClosed) {
                Log.w(tag, "Cannot start a CLOSED session")
                return@withLock
            }

            try {
                if (frameFlow != null) {
                    videoSource = WebRtcVideoSource(factory, frameFlow, scope)
                    videoSource?.start()

                    videoTrack = factory.createVideoTrack("video_track_$sessionId", videoSource?.source)
                    Log.i(tag, "VideoTrack created")
                } else {
                    Log.i(tag, "No screen frame flow provided. Screen VideoTrack not created.")
                }

                // We do NOT create an AudioTrack here because factory.createAudioSource()
                // would natively activate the microphone, violating the constraints.
                // We will add an Audio Transceiver to ensure SDP negotiates m=audio.

                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                )
                val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
                val observer = object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                        Log.i(tag, "Signaling state: $state")
                    }
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        Log.i(tag, "ICE connection state: $state")
                    }
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        Log.i(tag, "Connection state: $newState")
                        if (newState != null) {
                            listener.onConnectionStateChange(newState)
                        }
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        Log.i(tag, "ICE gathering state: $state")
                    }
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let { listener.onIceCandidate(it) }
                    }
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: MediaStream?) {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(channel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
                }

                peerConnection = factory.createPeerConnection(rtcConfig, observer)
                videoTrack?.let { 
                    peerConnection?.addTrack(it, listOf("screen_stream"))
                }
                
                val init = org.webrtc.DataChannel.Init().apply { 
                    ordered = false
                    maxRetransmits = 0 
                }
                audioDataChannel = peerConnection?.createDataChannel("playback-audio", init)
                
                val fileInit = org.webrtc.DataChannel.Init().apply {
                    ordered = true
                    maxRetransmits = -1
                }
                fileTransferDataChannel = peerConnection?.createDataChannel("file-transfer", fileInit)
                
                Log.i(tag, "PeerConnection created and Video track attached. Audio and File DataChannels created.")

                _state.value = WebRtcSessionState.READY
            } catch (e: Exception) {
                Log.e(tag, "Error starting WebRtcSession", e)
                _state.value = WebRtcSessionState.ERROR
                cleanupResources()
            }
        }
    }

    private var cameraVideoSource: CameraWebRtcVideoSource? = null
    private var cameraVideoTrack: VideoTrack? = null
    private var cameraRtpSender: org.webrtc.RtpSender? = null

    suspend fun startCameraVideo(cameraFrameFlow: kotlinx.coroutines.flow.Flow<com.example.livelocationservice.camera.CapturedCameraFrame>) {
        mutex.withLock {
            if (isClosed || peerConnection == null) {
                Log.w(tag, "Cannot start camera video: session closed or PC null")
                return
            }
            if (cameraVideoTrack != null) {
                Log.w(tag, "Camera track already exists")
                return
            }
            try {
                cameraVideoSource = CameraWebRtcVideoSource(factory, cameraFrameFlow, scope)
                cameraVideoSource?.start()

                cameraVideoTrack = factory.createVideoTrack("camera_track_$sessionId", cameraVideoSource?.source)
                Log.i(tag, "Camera VideoTrack created")

                cameraRtpSender = peerConnection?.addTrack(cameraVideoTrack, listOf("camera_stream"))
                Log.i(tag, "Camera VideoTrack added to PeerConnection")
                
                // Trigger renegotiation so the Controller sees the new track
                createOffer()
            } catch (e: Exception) {
                Log.e(tag, "Failed to start camera video", e)
            }
        }
    }

    suspend fun stopCameraVideo() {
        mutex.withLock {
            try {
                if (cameraRtpSender != null) {
                    peerConnection?.removeTrack(cameraRtpSender)
                    cameraRtpSender = null
                }
                cameraVideoTrack?.dispose()
                cameraVideoTrack = null
                cameraVideoSource?.stop()
                cameraVideoSource = null
                
                Log.i(tag, "Camera VideoTrack removed from PeerConnection")
                createOffer()
            } catch (e: Exception) {
                Log.e(tag, "Failed to stop camera video cleanly", e)
            }
        }
    }

    fun createOffer() {
        if (isClosed) return
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.i(tag, "Local description set successfully (OFFER)")
                            listener.onSdpOffer(it)
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {
                            Log.e(tag, "Failed to set local description: $p0")
                        }
                    }, it)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                Log.e(tag, "Failed to create offer: $p0")
            }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        if (isClosed) return
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.i(tag, "Remote description set successfully")
                drainPendingIceCandidates()
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Log.e(tag, "Failed to set remote description: $p0")
            }
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (isClosed) return
        if (peerConnection?.remoteDescription != null) {
            peerConnection?.addIceCandidate(candidate)
            Log.i(tag, "Added ICE candidate")
        } else {
            Log.i(tag, "Buffered ICE candidate (remote description not set)")
            pendingIceCandidates.add(candidate)
        }
    }

    private fun drainPendingIceCandidates() {
        pendingIceCandidates.forEach {
            peerConnection?.addIceCandidate(it)
        }
        if (pendingIceCandidates.isNotEmpty()) {
            Log.i(tag, "Drained ${pendingIceCandidates.size} pending ICE candidates")
            pendingIceCandidates.clear()
        }
    }

    open fun setVideoBitrate(maxBitrateBps: Int) {
        val sender = peerConnection?.senders?.find { it.track()?.kind() == "video" }
        if (sender != null) {
            val parameters = sender.parameters
            if (parameters.encodings.isNotEmpty()) {
                parameters.encodings[0].maxBitrateBps = maxBitrateBps
                sender.parameters = parameters
                Log.i(tag, "VIDEO_BITRATE_CHANGED to $maxBitrateBps")
            }
        }
    }

    suspend fun stop() {
        mutex.withLock {
            if (isClosed) return@withLock
            isClosed = true
            
            Log.i(tag, "Session stopping")
            _state.value = WebRtcSessionState.STOPPING

            cleanupResources()

            Log.i(tag, "WebRTC resources released, PeerConnection closed")
            _state.value = WebRtcSessionState.CLOSED
        }
    }

    private fun cleanupResources() {
        try {
            audioDataChannel?.close()
            audioDataChannel?.dispose()
            audioDataChannel = null
        } catch (e: Exception) {}

        try {
            fileTransferDataChannel?.close()
            fileTransferDataChannel?.dispose()
            fileTransferDataChannel = null
        } catch (e: Exception) {}

        try {
            videoSource?.stop()
            videoSource = null
        } catch (e: Exception) {}

        try {
            cameraVideoTrack?.dispose()
            cameraVideoTrack = null
            cameraVideoSource?.stop()
            cameraVideoSource = null
        } catch (e: Exception) {}

        try {
            videoTrack?.dispose()
            videoTrack = null
        } catch (e: Exception) {}

        try {
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: Exception) {}
    }
}
