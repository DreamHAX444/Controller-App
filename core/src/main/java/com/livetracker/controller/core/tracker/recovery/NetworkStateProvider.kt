package com.livetracker.controller.core.tracker.recovery

import kotlinx.coroutines.flow.StateFlow

enum class NetworkState {
    AVAILABLE, LOST, UNKNOWN
}

interface NetworkStateProvider {
    val networkState: StateFlow<NetworkState>
}
