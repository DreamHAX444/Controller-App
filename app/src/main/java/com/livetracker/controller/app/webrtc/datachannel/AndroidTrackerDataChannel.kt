package com.livetracker.controller.app.webrtc.datachannel

import com.livetracker.controller.core.tracker.webrtc.datachannel.DataChannelState
import com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerDataChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DataChannel
import java.nio.ByteBuffer

class AndroidTrackerDataChannel(
    private val dataChannel: DataChannel
) : TrackerDataChannel, DataChannel.Observer {

    override val label: String
        get() = dataChannel.label()
        
    private val _state = MutableStateFlow(DataChannelState.CLOSED)
    override val state: StateFlow<DataChannelState> = _state.asStateFlow()
    
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<String> = _incomingMessages.asSharedFlow()
    
    init {
        dataChannel.registerObserver(this)
        _state.value = mapState(dataChannel.state())
    }
    
    override suspend fun open() {
        // DataChannel is already opened by PeerConnection, but we can verify state
    }
    
    override suspend fun send(data: String): Boolean {
        if (state.value != DataChannelState.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data.toByteArray()), false)
        return dataChannel.send(buffer)
    }
    
    override suspend fun close() {
        dataChannel.unregisterObserver()
        dataChannel.dispose()
        _state.value = DataChannelState.CLOSED
    }

    override fun onBufferedAmountChange(previousAmount: Long) {
        // Not needed for text commands right now
    }

    override fun onStateChange() {
        _state.value = mapState(dataChannel.state())
    }

    override fun onMessage(buffer: DataChannel.Buffer) {
        if (!buffer.binary) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val message = String(bytes)
            _incomingMessages.tryEmit(message)
        }
    }
    
    private fun mapState(state: DataChannel.State): DataChannelState {
        return when (state) {
            DataChannel.State.CONNECTING -> DataChannelState.OPENING
            DataChannel.State.OPEN -> DataChannelState.OPEN
            DataChannel.State.CLOSING -> DataChannelState.CLOSING
            DataChannel.State.CLOSED -> DataChannelState.CLOSED
        }
    }
}
