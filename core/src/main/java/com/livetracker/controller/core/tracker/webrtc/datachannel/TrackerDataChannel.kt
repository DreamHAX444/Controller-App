package com.livetracker.controller.core.tracker.webrtc.datachannel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface TrackerDataChannel {
    val label: String
    val state: StateFlow<DataChannelState>
    
    val incomingMessages: Flow<String>
    
    suspend fun open()
    suspend fun send(data: String): Boolean
    suspend fun close()
}
