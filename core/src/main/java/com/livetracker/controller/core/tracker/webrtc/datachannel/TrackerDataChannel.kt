package com.livetracker.controller.core.tracker.webrtc.datachannel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TrackerDataChannel {
    val label: String
    val state: StateFlow<DataChannelState>
    
    val incomingMessages: Flow<String>
    val incomingBinaryMessages: Flow<ByteArray>
        get() = kotlinx.coroutines.flow.emptyFlow()
    
    suspend fun open()
    suspend fun send(data: String): Boolean
    suspend fun sendBinary(data: ByteArray): Boolean = false
    suspend fun close()
}
