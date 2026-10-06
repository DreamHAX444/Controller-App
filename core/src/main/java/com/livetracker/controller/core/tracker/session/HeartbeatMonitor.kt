package com.livetracker.controller.core.tracker.session

import com.livetracker.controller.core.tracker.domain.HeartbeatHealth
import com.livetracker.controller.core.tracker.domain.HeartbeatPolicy
import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.domain.TrackerSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface TimeSource {
    fun currentTimeMillis(): Long
}

object DefaultTimeSource : TimeSource {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

class HeartbeatMonitor(
    private val scope: CoroutineScope,
    private val policy: HeartbeatPolicy,
    private val stateFlow: MutableStateFlow<TrackerSessionState>,
    private val timeSource: TimeSource = DefaultTimeSource
) {
    private var monitorJob: Job? = null

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                delay(policy.expectedIntervalMs)
                checkHealth()
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun onHeartbeatReceived(event: TrackerEvent.HeartbeatReceived) {
        stateFlow.update { sessionState ->
            val current = sessionState.heartbeatState
            // Avoid old/out-of-order heartbeats from restoring state incorrectly.
            if (current.lastHeartbeatTimestamp != null && event.timestamp < current.lastHeartbeatTimestamp) {
                return@update sessionState
            }
            if (current.lastHeartbeatTimestamp != null && event.timestamp == current.lastHeartbeatTimestamp && event.sequence <= current.sequence) {
                return@update sessionState
            }
            
            sessionState.copy(
                heartbeatState = current.copy(
                    lastHeartbeatTimestamp = event.timestamp,
                    sequence = event.sequence,
                    missedHeartbeatCount = 0,
                    health = HeartbeatHealth.HEALTHY
                )
            )
        }
    }

    private fun checkHealth() {
        val now = timeSource.currentTimeMillis()
        stateFlow.update { sessionState ->
            val current = sessionState.heartbeatState
            val last = current.lastHeartbeatTimestamp ?: return@update sessionState
            
            if (now < last) return@update sessionState
            
            val elapsed = now - last
            
            val newHealth = when {
                elapsed >= policy.lostThresholdMs -> HeartbeatHealth.LOST
                elapsed >= policy.degradedThresholdMs -> HeartbeatHealth.DEGRADED
                else -> current.health
            }
            
            val missed = if (elapsed >= policy.expectedIntervalMs) (elapsed / policy.expectedIntervalMs).toInt() else 0

            sessionState.copy(
                heartbeatState = current.copy(
                    health = newHealth,
                    missedHeartbeatCount = missed
                )
            )
        }
    }
}
