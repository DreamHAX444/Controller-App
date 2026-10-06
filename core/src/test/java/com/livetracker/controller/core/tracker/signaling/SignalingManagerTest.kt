package com.livetracker.controller.core.tracker.signaling

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalingManagerTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private lateinit var fakeTransport: FakeSignalingTransport
    private lateinit var manager: DefaultSignalingManager

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        fakeTransport = FakeSignalingTransport()
        manager = DefaultSignalingManager(fakeTransport, testScope)
    }

    @Test
    fun `Create signaling session`() {
        val session = manager.createSession(TrackerId("t1"), "sess1")
        assertNotNull(session)
        assertEquals("sess1", session.sessionId)
        assertEquals(TrackerId("t1"), session.trackerId)
    }

    @Test
    fun `Sessions remain isolated`() {
        val s1 = manager.createSession(TrackerId("t1"), "sess1")
        val s2 = manager.createSession(TrackerId("t2"), "sess2")
        val s3 = manager.createSession(TrackerId("t3"), "sess3")
        
        assertEquals(s1, manager.getSession(TrackerId("t1")))
        assertEquals(s2, manager.getSession(TrackerId("t2")))
        assertEquals(s3, manager.getSession(TrackerId("t3")))
    }
    
    @Test
    fun `Close A does not affect B`() = runTest(testDispatcher) {
        val s1 = manager.createSession(TrackerId("t1"), "sess1")
        val s2 = manager.createSession(TrackerId("t2"), "sess2")
        
        s1.start()
        s2.start()
        
        manager.closeSession(TrackerId("t1"))
        
        assertNull(manager.getSession(TrackerId("t1")))
        assertNotNull(manager.getSession(TrackerId("t2")))
        assertEquals(SignalingState.DISCONNECTED, s1.state.value)
        assertEquals(SignalingState.CONNECTED, s2.state.value)
    }
}
