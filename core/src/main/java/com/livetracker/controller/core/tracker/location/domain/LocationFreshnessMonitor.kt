package com.livetracker.controller.core.tracker.location.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LocationFreshnessMonitor(
    private val repository: TrackerLocationRepository,
    private val policy: LocationFreshnessPolicy = LocationFreshnessPolicy(),
    private val scope: CoroutineScope,
    private val checkIntervalMs: Long = 5000L
) {
    private var job: Job? = null
    
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(checkIntervalMs)
                val locations = repository.getAllLocations().firstOrNull() ?: emptyList()
                val now = System.currentTimeMillis()
                
                for (location in locations) {
                    val newState = policy.evaluate(location.timestamp, now)
                    if (newState != location.state) {
                        repository.updateLocationState(location.deviceId, newState)
                    }
                }
            }
        }
    }
    
    fun stop() {
        job?.cancel()
        job = null
    }
}
