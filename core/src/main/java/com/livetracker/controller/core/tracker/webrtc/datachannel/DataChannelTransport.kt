package com.livetracker.controller.core.tracker.webrtc.datachannel

import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.coroutines.flow.Flow

interface DataChannelTransport {
    val deviceId: TrackerId
    val events: Flow<TrackerEvent>
    
    suspend fun connect(dataChannel: TrackerDataChannel)
    suspend fun disconnect()
    suspend fun sendCommand(command: TrackerCommand): CommandResult
}
