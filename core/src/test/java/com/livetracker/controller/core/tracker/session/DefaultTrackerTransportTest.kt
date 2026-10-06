package com.livetracker.controller.core.tracker.session

import com.livetracker.controller.core.tracker.domain.*
import com.livetracker.controller.core.tracker.signaling.*
import com.livetracker.controller.core.tracker.webrtc.WebRtcSession
import com.livetracker.controller.core.tracker.webrtc.WebRtcState
import com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelTransport
import com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTrackerTransportTest {

    class FakeSignalingManager : SignalingManager {
        val sessions = mutableMapOf<TrackerId, FakeSignalingSession>()
        override fun createSession(trackerId: TrackerId, sessionId: String): SignalingSession {
            val session = FakeSignalingSession(trackerId, sessionId)
            sessions[trackerId] = session
            return session
        }
        override fun getSession(trackerId: TrackerId): SignalingSession? = sessions[trackerId]
        override suspend fun closeSession(trackerId: TrackerId) {
            sessions.remove(trackerId)
        }
        override fun closeAll() {
            sessions.clear()
        }
    }

    class FakeSignalingSession(
        override val trackerId: TrackerId,
        override val sessionId: String
    ) : SignalingSession {
        private val _state = MutableStateFlow(SignalingState.DISCONNECTED)
        override val state: StateFlow<SignalingState> = _state.asStateFlow()

        private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 10)
        override val incomingMessages = _incomingMessages.asSharedFlow()

        override val lastError = MutableStateFlow<SignalingError?>(null)

        val sentMessages = mutableListOf<SignalingMessage>()

        override suspend fun start() {
            _state.value = SignalingState.CONNECTED
        }

        override suspend fun stop() {
            _state.value = SignalingState.DISCONNECTED
        }

        override suspend fun sendMessage(type: SignalingMessageType, payload: SignalingPayload?) {
            sentMessages.add(SignalingMessage(
                sessionId = sessionId,
                trackerId = trackerId,
                type = type,
                timestamp = System.currentTimeMillis(),
                messageId = java.util.UUID.randomUUID().toString(),
                payload = payload
            ))
        }

        fun emitIncomingMessage(msg: SignalingMessage) {
            _incomingMessages.tryEmit(msg)
        }
    }

    class FakeWebRtcSession : WebRtcSession {
        val _state = MutableStateFlow(WebRtcState.NEW)
        override val state: StateFlow<WebRtcState> = _state.asStateFlow()

        private val _localIceCandidates = MutableSharedFlow<IceCandidatePayload>(extraBufferCapacity = 10)
        override val localIceCandidates = _localIceCandidates.asSharedFlow()

        private val _incomingDataChannels = MutableSharedFlow<TrackerDataChannel>(extraBufferCapacity = 1)
        override val incomingDataChannels: Flow<TrackerDataChannel> = _incomingDataChannels.asSharedFlow()

        var remoteOffer: OfferPayload? = null
        var answerToReturn: AnswerPayload = AnswerPayload("fake-answer", 0L)
        val addedIceCandidates = mutableListOf<IceCandidatePayload>()
        var stopped = false
        var createdDataChannelLabel: String? = null
        
        override suspend fun setRemoteOfferAndCreateAnswer(offer: OfferPayload): AnswerPayload {
            remoteOffer = offer
            return answerToReturn
        }

        override suspend fun addIceCandidate(candidate: IceCandidatePayload) {
            addedIceCandidates.add(candidate)
        }

        override suspend fun stop() {
            stopped = true
            _state.value = WebRtcState.CLOSED
        }

        override fun createDataChannel(label: String): TrackerDataChannel? {
            createdDataChannelLabel = label
            return FakeTrackerDataChannel()
        }

        fun emitLocalIceCandidate(candidate: IceCandidatePayload) {
            _localIceCandidates.tryEmit(candidate)
        }

        fun emitIncomingDataChannel(dc: TrackerDataChannel) {
            _incomingDataChannels.tryEmit(dc)
        }
    }

    class FakeDataChannelTransport : DataChannelTransport {
        override val deviceId: TrackerId = TrackerId("fake")
        var connectedChannel: TrackerDataChannel? = null
        var disconnected = false
        var commandToReturn: CommandResult = CommandResult.Completed("fake", TrackerId("fake"))

        private val _events = MutableSharedFlow<TrackerEvent>(extraBufferCapacity = 10)
        override val events: Flow<TrackerEvent> = _events.asSharedFlow()

        override suspend fun connect(channel: TrackerDataChannel) {
            connectedChannel = channel
        }

        override suspend fun disconnect() {
            disconnected = true
        }

        override suspend fun sendCommand(command: TrackerCommand): CommandResult {
            return commandToReturn
        }
    }

    class FakeTrackerDataChannel : TrackerDataChannel {
        override val label: String = "fake"
        override val state = MutableStateFlow(com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState.OPEN)
        override val incomingMessages = emptyFlow<String>()
        override suspend fun open() {}
        override suspend fun send(data: String): Boolean = true
        override suspend fun close() {}
    }

    @Test
    fun `connect initiates signaling and handles WebRTC state transitions`() = runTest(UnconfinedTestDispatcher()) {
        val signalingManager = FakeSignalingManager()
        val webRtcSession = FakeWebRtcSession()
        val dataChannelTransport = FakeDataChannelTransport()
        
        val transport = DefaultTrackerTransport(
            TrackerId("t1"),
            backgroundScope,
            signalingManager,
            webRtcSession
        ) { _, _ -> dataChannelTransport }

        transport.connect()
        advanceUntilIdle()

        assertEquals(ConnectionState.SIGNALING, transport.connectionState.value)
        val sigSession = signalingManager.getSession(TrackerId("t1")) as FakeSignalingSession
        
        // Initial CONNECT message sent
        assertTrue(sigSession.sentMessages.any { it.type == SignalingMessageType.START_REQUEST })

        // Simulate incoming OFFER
        sigSession.emitIncomingMessage(SignalingMessage(
            sessionId = "test-session",
            trackerId = TrackerId("t1"),
            type = SignalingMessageType.OFFER,
            timestamp = 0,
            messageId = "1",
            payload = OfferPayload("offer1", 0)
        ))
        advanceUntilIdle()
        
        // WebRTC session should receive the offer and generate an answer, which is sent via signaling
        assertEquals("offer1", webRtcSession.remoteOffer?.sdp)
        assertTrue(sigSession.sentMessages.any { it.type == SignalingMessageType.ANSWER && (it.payload as AnswerPayload).sdp == "fake-answer" })

        // Simulate ICE Candidate from remote
        sigSession.emitIncomingMessage(SignalingMessage(
            sessionId = "test-session",
            trackerId = TrackerId("t1"),
            type = SignalingMessageType.ICE_CANDIDATE,
            timestamp = 0,
            messageId = "2",
            payload = IceCandidatePayload("remote-ice", "mid", 0, 0)
        ))
        advanceUntilIdle()
        assertEquals(1, webRtcSession.addedIceCandidates.size)

        // Simulate local ICE Candidate
        webRtcSession.emitLocalIceCandidate(IceCandidatePayload("local-ice", "mid2", 1, 0))
        advanceUntilIdle()
        assertTrue(sigSession.sentMessages.any { it.type == SignalingMessageType.ICE_CANDIDATE && (it.payload as IceCandidatePayload).candidate == "local-ice" })

        // Simulate WebRTC connection success
        webRtcSession._state.value = WebRtcState.CONNECTED
        advanceUntilIdle()

        assertEquals(ConnectionState.DATA_CHANNEL_OPENING, transport.connectionState.value)

        // Simulate incoming DataChannel
        val fakeDc = FakeTrackerDataChannel()
        webRtcSession.emitIncomingDataChannel(fakeDc)
        advanceUntilIdle()

        // Transport should be CONNECTED and DataChannel should be setup
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)
        assertEquals(fakeDc, dataChannelTransport.connectedChannel)
        
        transport.disconnect()
    }
}
