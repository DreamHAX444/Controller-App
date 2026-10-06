package com.livetracker.controller.core.tracker.signaling

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SignalingSessionTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var fakeTransport: FakeSignalingTransport

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        fakeTransport = FakeSignalingTransport()
    }

    private fun createSession(trackerId: String, sessionId: String, onClosed: () -> Unit = {}): DefaultSignalingSession {
        return DefaultSignalingSession(
            sessionId = sessionId,
            trackerId = TrackerId(trackerId),
            transport = fakeTransport,
            scope = testScope,
            timeoutPolicy = SignalingTimeoutPolicy(),
            onClosed = onClosed
        )
    }

    @Test
    fun `Connects successfully`() = runTest(testDispatcher) {
        val session = createSession("t1", "sess1")
        assertEquals(SignalingState.DISCONNECTED, session.state.value)
        session.start()
        runCurrent()
        assertEquals(SignalingState.CONNECTED, session.state.value)
        assertTrue(fakeTransport.connectedSessions.contains("sess1"))
    }

    @Test
    fun `Failed connection is handled`() = runTest(testDispatcher) {
        val session = createSession("t1", "sess1")
        fakeTransport.connectError = RuntimeException("Network down")
        session.start()
        runCurrent()
        assertEquals(SignalingState.FAILED, session.state.value)
        assertTrue(session.lastError.value is SignalingError.TransportError)
    }

    @Test
    fun `Close successfully`() = runTest(testDispatcher) {
        val session = createSession("t1", "sess1")
        session.start()
        runCurrent()
        
        session.stop()
        runCurrent()
        
        assertEquals(SignalingState.DISCONNECTED, session.state.value)
        assertTrue("Expected SESSION_ENDED, but sent messages are: ${fakeTransport.sentMessages}", fakeTransport.sentMessages.any { it.type == SignalingMessageType.SESSION_ENDED })
    }

    @Test
    fun `Closed session cannot send messages`() = runTest(testDispatcher) {
        val session = createSession("t1", "sess1")
        var errorThrown = false
        try {
            session.sendMessage(SignalingMessageType.OFFER, OfferPayload("sdp", 1L))
        } catch (e: SignalingError.SessionClosed) {
            errorThrown = true
        }
        assertTrue(errorThrown)
    }

    @Test
    fun `Message for Tracker A reaches A`() = runTest(testDispatcher) {
        val session = createSession("t1", "sess1")
        session.start()
        runCurrent()

        val msg = SignalingMessage(
            sessionId = "sess1",
            trackerId = TrackerId("t1"),
            type = SignalingMessageType.READY,
            timestamp = 0L,
            messageId = UUID.randomUUID().toString(),
            payload = ReadyPayload(1L)
        )
        fakeTransport.simulateIncoming(msg)
        runCurrent()

        assertEquals(SignalingState.READY, session.state.value)
    }

    @Test
    fun `Unknown session is rejected`() = runTest(testDispatcher) {
        val session = createSession("t1", "sess1")
        session.start()
        runCurrent()

        val msg = SignalingMessage(
            sessionId = "sess2", // wrong session
            trackerId = TrackerId("t1"),
            type = SignalingMessageType.READY,
            timestamp = 0L,
            messageId = UUID.randomUUID().toString(),
            payload = ReadyPayload(1L)
        )
        fakeTransport.simulateIncoming(msg)
        runCurrent()

        assertEquals(SignalingState.CONNECTED, session.state.value) // remains connected
    }

    @Test
    fun `Duplicate message handling`() = runTest(testDispatcher) {
        val session = createSession("t1", "sess1")
        session.start()
        runCurrent()

        val msgId = UUID.randomUUID().toString()
        val msg = SignalingMessage(
            sessionId = "sess1",
            trackerId = TrackerId("t1"),
            type = SignalingMessageType.READY,
            timestamp = 0L,
            messageId = msgId,
            payload = ReadyPayload(1L)
        )
        fakeTransport.simulateIncoming(msg)
        runCurrent()
        assertEquals(SignalingState.READY, session.state.value)

        // Reset to connected forcefully for test, wait state transitions don't allow READY -> CONNECTED
        // But we can observe incoming messages
        var count = 0
        val job = testScope.launch {
            session.incomingMessages.collect { count++ }
        }
        
        fakeTransport.simulateIncoming(msg.copy(type = SignalingMessageType.OFFER))
        runCurrent()
        // Because msgId is identical, it's ignored
        assertEquals(0, count)
        job.cancel()
    }
}
