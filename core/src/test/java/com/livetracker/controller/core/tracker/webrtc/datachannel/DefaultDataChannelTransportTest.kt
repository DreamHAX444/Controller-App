package com.livetracker.controller.core.tracker.webrtc.datachannel

import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTrackerDataChannel : TrackerDataChannel {
    override val label: String = "commands"
    
    private val _state = MutableStateFlow(DataChannelState.CLOSED)
    override val state: StateFlow<DataChannelState> = _state.asStateFlow()
    
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<String> = _incomingMessages.asSharedFlow()
    
    val sentMessages = mutableListOf<String>()
    var sendSuccess = true
    
    override suspend fun open() {
        _state.value = DataChannelState.OPEN
    }
    
    override suspend fun send(data: String): Boolean {
        if (state.value != DataChannelState.OPEN) return false
        if (!sendSuccess) return false
        sentMessages.add(data)
        return true
    }
    
    override suspend fun close() {
        _state.value = DataChannelState.CLOSED
    }
    
    suspend fun emitIncomingMessage(message: String) {
        _incomingMessages.emit(message)
    }
    
    fun setState(newState: DataChannelState) {
        _state.value = newState
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDataChannelTransportTest {

    @Test
    fun `send fails if channel is closed`() = runTest {
        val transport = DefaultDataChannelTransport(TrackerId("dev1"), this)
        val channel = FakeTrackerDataChannel()
        transport.connect(channel)
        
        val command = TrackerCommand.StartCamera("123", TrackerId("dev1"), 0L, "actor")
        val result = transport.sendCommand(command)
        
        assertTrue(result is CommandResult.Failed && (result as CommandResult.Failed).reason == "DataChannel unavailable")
        transport.disconnect()
    }

    @Test
    fun `send success completes with response`() = runTest {
        val transport = DefaultDataChannelTransport(TrackerId("dev1"), this)
        val channel = FakeTrackerDataChannel()
        channel.open()
        transport.connect(channel)
        
        val command = TrackerCommand.StartCamera("123", TrackerId("dev1"), 0L, "actor")
        
        var result: CommandResult? = null
        val job = launch {
            result = transport.sendCommand(command)
        }
        
        // Wait for it to be sent
        advanceTimeBy(100)
        
        assertEquals(1, channel.sentMessages.size)
        assertTrue(channel.sentMessages[0].contains("start_camera"))
        
        // Simulate tracker response
        channel.emitIncomingMessage("""{"id":123,"device_id":"dev1","command":"start_camera_response","status":"executed"}""")
        
        advanceTimeBy(100)
        
        assertTrue(result is CommandResult.Completed)
        assertEquals("123", result?.commandId)
        
        job.cancel()
        transport.disconnect()
    }
    
    @Test
    fun `send times out if no response`() = runTest {
        val transport = DefaultDataChannelTransport(TrackerId("dev1"), this)
        val channel = FakeTrackerDataChannel()
        channel.open()
        transport.connect(channel)
        
        val command = TrackerCommand.StartCamera("123", TrackerId("dev1"), 0L, "actor", timeoutMs = 1000)
        
        var result: CommandResult? = null
        val job = launch {
            result = transport.sendCommand(command)
        }
        
        advanceTimeBy(1500)
        
        assertTrue(result is CommandResult.Timeout)
        job.cancel()
        transport.disconnect()
    }

    @Test
    fun `malformed incoming message does not crash transport`() = runTest {
        val transport = DefaultDataChannelTransport(TrackerId("dev1"), this)
        val channel = FakeTrackerDataChannel()
        channel.open()
        transport.connect(channel)
        
        val command = TrackerCommand.StartCamera("123", TrackerId("dev1"), 0L, "actor")
        
        var result: CommandResult? = null
        val job = launch {
            result = transport.sendCommand(command)
        }
        
        advanceTimeBy(100)
        
        // Emit malformed JSON
        channel.emitIncomingMessage("""{ "bad json """)
        channel.emitIncomingMessage("""{"id":123,"device_id":"dev1","command":"start_camera_response","status":"executed"}""")
        
        advanceTimeBy(100)
        
        assertTrue(result is CommandResult.Completed)
        job.cancel()
        transport.disconnect()
    }
    
    @Test
    fun `disconnect cancels pending commands`() = runTest {
        val transport = DefaultDataChannelTransport(TrackerId("dev1"), this)
        val channel = FakeTrackerDataChannel()
        channel.open()
        transport.connect(channel)
        
        val command = TrackerCommand.StartCamera("123", TrackerId("dev1"), 0L, "actor")
        
        var exceptionOccurred = false
        val job = launch {
            try {
                transport.sendCommand(command)
            } catch (e: kotlinx.coroutines.CancellationException) {
                exceptionOccurred = true
            }
        }
        
        advanceTimeBy(100)
        transport.disconnect()
        advanceTimeBy(100)
        
        assertTrue(exceptionOccurred)
        transport.disconnect() // Already disconnected, but safe
    }
}
