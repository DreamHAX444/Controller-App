package com.livetracker.controller.core.update.storage

import kotlinx.coroutines.flow.StateFlow

/**
 * Storage interface for persisting update preferences and check telemetry.
 */
interface UpdatePreferencesRepository {
    val lastCheckedEpochMs: StateFlow<Long>
    val dismissedVersionCode: StateFlow<Long>
    val selectedChannel: StateFlow<String>

    suspend fun setLastCheckedEpochMs(timestampMs: Long)
    suspend fun setDismissedVersionCode(versionCode: Long)
    suspend fun setSelectedChannel(channel: String)
}
