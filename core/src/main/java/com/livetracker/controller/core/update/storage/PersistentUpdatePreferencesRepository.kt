package com.livetracker.controller.core.update.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed persistence for update preferences and dismissal states.
 */
class PersistentUpdatePreferencesRepository(
    context: Context,
    prefsName: String = "trace_update_preferences"
) : UpdatePreferencesRepository {

    private val prefs: SharedPreferences = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val _lastCheckedEpochMs = MutableStateFlow(prefs.getLong(KEY_LAST_CHECKED, 0L))
    override val lastCheckedEpochMs: StateFlow<Long> = _lastCheckedEpochMs.asStateFlow()

    private val _dismissedVersionCode = MutableStateFlow(prefs.getLong(KEY_DISMISSED_VERSION, 0L))
    override val dismissedVersionCode: StateFlow<Long> = _dismissedVersionCode.asStateFlow()

    private val _selectedChannel = MutableStateFlow(prefs.getString(KEY_CHANNEL, "stable") ?: "stable")
    override val selectedChannel: StateFlow<String> = _selectedChannel.asStateFlow()

    override suspend fun setLastCheckedEpochMs(timestampMs: Long) {
        prefs.edit().putLong(KEY_LAST_CHECKED, timestampMs).apply()
        _lastCheckedEpochMs.value = timestampMs
    }

    override suspend fun setDismissedVersionCode(versionCode: Long) {
        prefs.edit().putLong(KEY_DISMISSED_VERSION, versionCode).apply()
        _dismissedVersionCode.value = versionCode
    }

    override suspend fun setSelectedChannel(channel: String) {
        prefs.edit().putString(KEY_CHANNEL, channel).apply()
        _selectedChannel.value = channel
    }

    companion object {
        private const val KEY_LAST_CHECKED = "update_last_checked_ms"
        private const val KEY_DISMISSED_VERSION = "update_dismissed_version_code"
        private const val KEY_CHANNEL = "update_selected_channel"
    }
}

/**
 * In-memory test implementation for JVM unit tests.
 */
class InMemoryUpdatePreferencesRepository(
    initialLastCheckedMs: Long = 0L,
    initialDismissedVersionCode: Long = 0L,
    initialChannel: String = "stable"
) : UpdatePreferencesRepository {

    private val _lastCheckedEpochMs = MutableStateFlow(initialLastCheckedMs)
    override val lastCheckedEpochMs: StateFlow<Long> = _lastCheckedEpochMs.asStateFlow()

    private val _dismissedVersionCode = MutableStateFlow(initialDismissedVersionCode)
    override val dismissedVersionCode: StateFlow<Long> = _dismissedVersionCode.asStateFlow()

    private val _selectedChannel = MutableStateFlow(initialChannel)
    override val selectedChannel: StateFlow<String> = _selectedChannel.asStateFlow()

    override suspend fun setLastCheckedEpochMs(timestampMs: Long) {
        _lastCheckedEpochMs.value = timestampMs
    }

    override suspend fun setDismissedVersionCode(versionCode: Long) {
        _dismissedVersionCode.value = versionCode
    }

    override suspend fun setSelectedChannel(channel: String) {
        _selectedChannel.value = channel
    }
}
