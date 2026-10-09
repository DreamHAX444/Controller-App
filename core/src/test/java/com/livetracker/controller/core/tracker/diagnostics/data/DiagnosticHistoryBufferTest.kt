package com.livetracker.controller.core.tracker.diagnostics.data

import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.diagnostics.domain.DiagnosticEvent
import com.livetracker.controller.core.tracker.diagnostics.domain.DiagnosticEventType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticHistoryBufferTest {

    private lateinit var buffer: DiagnosticHistoryBuffer
    private val deviceId = TrackerId("DEV1")

    @Before
    fun setup() {
        buffer = DiagnosticHistoryBuffer(maxEvents = 3)
    }

    @Test
    fun `addEvent adds event to history`() = runTest {
        val event = DiagnosticEvent(
            timestamp = 1000L,
            type = DiagnosticEventType.CONNECTED,
            message = "Test message"
        )
        
        buffer.addEvent(deviceId, event)
        
        val history = buffer.observeHistory(deviceId).first()
        assertEquals(1, history.size)
        assertEquals(event, history.first())
    }

    @Test
    fun `addEvent respects maxEvents limit`() = runTest {
        val events = (1..5).map {
            DiagnosticEvent(
                timestamp = it * 1000L,
                type = DiagnosticEventType.CONNECTED,
                message = "Test message $it"
            )
        }
        
        events.forEach { buffer.addEvent(deviceId, it) }
        
        val history = buffer.observeHistory(deviceId).first()
        
        // Max limit is 3, so it should contain 3, 4, 5
        assertEquals(3, history.size)
        assertEquals("Test message 3", history[0].message)
        assertEquals("Test message 4", history[1].message)
        assertEquals("Test message 5", history[2].message)
    }

    @Test
    fun `observeHistory returns empty list if no events`() = runTest {
        val history = buffer.observeHistory(deviceId).first()
        assertEquals(0, history.size)
    }

    @Test
    fun `history is isolated per device`() = runTest {
        val device2 = TrackerId("DEV2")
        val event1 = DiagnosticEvent(
            timestamp = 1000L,
            type = DiagnosticEventType.CONNECTED,
            message = "Device 1 event"
        )
        val event2 = DiagnosticEvent(
            timestamp = 2000L,
            type = DiagnosticEventType.CONNECTED,
            message = "Device 2 event"
        )
        
        buffer.addEvent(deviceId, event1)
        buffer.addEvent(device2, event2)
        
        val history1 = buffer.observeHistory(deviceId).first()
        val history2 = buffer.observeHistory(device2).first()
        
        assertEquals(1, history1.size)
        assertEquals("Device 1 event", history1.first().message)
        
        assertEquals(1, history2.size)
        assertEquals("Device 2 event", history2.first().message)
    }
}
