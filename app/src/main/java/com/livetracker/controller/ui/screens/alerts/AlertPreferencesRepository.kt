package com.livetracker.controller.ui.screens.alerts

import android.content.Context
import android.content.SharedPreferences
import com.livetracker.controller.core.tracker.geofence.domain.AlertSoundProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AlertConfiguration(
    val sirenEnabled: Boolean = true,
    val defaultSoundProfile: AlertSoundProfile = AlertSoundProfile.TACTICAL_SIREN,
    val sirenDurationSeconds: Int = 30,
    val vibrationEnabled: Boolean = true,
    val geofenceBreachAlarmEnabled: Boolean = true,
    val lowBatteryAlertEnabled: Boolean = true,
    val lowBatteryThresholdPercent: Int = 20,
    val deviceOfflineAlertEnabled: Boolean = true,
    val fullscreenBreachModalEnabled: Boolean = true
)

class AlertPreferencesRepository(private val sharedPrefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    companion object {
        private const val PREFS_NAME = "trace_alert_preferences"
        private const val KEY_SIREN_ENABLED = "key_siren_enabled"
        private const val KEY_SOUND_PROFILE = "key_sound_profile"
        private const val KEY_SIREN_DURATION = "key_siren_duration"
        private const val KEY_VIBRATION_ENABLED = "key_vibration_enabled"
        private const val KEY_GEOFENCE_ALARM = "key_geofence_alarm"
        private const val KEY_LOW_BATTERY_ALERT = "key_low_battery_alert"
        private const val KEY_LOW_BATTERY_THRESHOLD = "key_low_battery_threshold"
        private const val KEY_DEVICE_OFFLINE_ALERT = "key_device_offline_alert"
        private const val KEY_FULLSCREEN_MODAL = "key_fullscreen_modal"
    }

    private val _alertConfiguration = MutableStateFlow(loadAlertConfiguration())
    val alertConfiguration: StateFlow<AlertConfiguration> = _alertConfiguration.asStateFlow()

    private fun loadAlertConfiguration(): AlertConfiguration {
        val soundProfileStr = sharedPrefs.getString(KEY_SOUND_PROFILE, AlertSoundProfile.TACTICAL_SIREN.name)
            ?: AlertSoundProfile.TACTICAL_SIREN.name
        val profile = runCatching { AlertSoundProfile.valueOf(soundProfileStr) }
            .getOrDefault(AlertSoundProfile.TACTICAL_SIREN)

        return AlertConfiguration(
            sirenEnabled = sharedPrefs.getBoolean(KEY_SIREN_ENABLED, true),
            defaultSoundProfile = profile,
            sirenDurationSeconds = sharedPrefs.getInt(KEY_SIREN_DURATION, 30),
            vibrationEnabled = sharedPrefs.getBoolean(KEY_VIBRATION_ENABLED, true),
            geofenceBreachAlarmEnabled = sharedPrefs.getBoolean(KEY_GEOFENCE_ALARM, true),
            lowBatteryAlertEnabled = sharedPrefs.getBoolean(KEY_LOW_BATTERY_ALERT, true),
            lowBatteryThresholdPercent = sharedPrefs.getInt(KEY_LOW_BATTERY_THRESHOLD, 20),
            deviceOfflineAlertEnabled = sharedPrefs.getBoolean(KEY_DEVICE_OFFLINE_ALERT, true),
            fullscreenBreachModalEnabled = sharedPrefs.getBoolean(KEY_FULLSCREEN_MODAL, true)
        )
    }

    fun updateConfiguration(config: AlertConfiguration) {
        sharedPrefs.edit()
            .putBoolean(KEY_SIREN_ENABLED, config.sirenEnabled)
            .putString(KEY_SOUND_PROFILE, config.defaultSoundProfile.name)
            .putInt(KEY_SIREN_DURATION, config.sirenDurationSeconds)
            .putBoolean(KEY_VIBRATION_ENABLED, config.vibrationEnabled)
            .putBoolean(KEY_GEOFENCE_ALARM, config.geofenceBreachAlarmEnabled)
            .putBoolean(KEY_LOW_BATTERY_ALERT, config.lowBatteryAlertEnabled)
            .putInt(KEY_LOW_BATTERY_THRESHOLD, config.lowBatteryThresholdPercent)
            .putBoolean(KEY_DEVICE_OFFLINE_ALERT, config.deviceOfflineAlertEnabled)
            .putBoolean(KEY_FULLSCREEN_MODAL, config.fullscreenBreachModalEnabled)
            .apply()
        _alertConfiguration.value = config
    }

    fun setSirenEnabled(enabled: Boolean) {
        updateConfiguration(_alertConfiguration.value.copy(sirenEnabled = enabled))
    }

    fun setDefaultSoundProfile(profile: AlertSoundProfile) {
        updateConfiguration(_alertConfiguration.value.copy(defaultSoundProfile = profile))
    }

    fun setSirenDurationSeconds(seconds: Int) {
        updateConfiguration(_alertConfiguration.value.copy(sirenDurationSeconds = seconds))
    }

    fun setVibrationEnabled(enabled: Boolean) {
        updateConfiguration(_alertConfiguration.value.copy(vibrationEnabled = enabled))
    }

    fun setGeofenceBreachAlarmEnabled(enabled: Boolean) {
        updateConfiguration(_alertConfiguration.value.copy(geofenceBreachAlarmEnabled = enabled))
    }

    fun setLowBatteryAlertEnabled(enabled: Boolean) {
        updateConfiguration(_alertConfiguration.value.copy(lowBatteryAlertEnabled = enabled))
    }

    fun setLowBatteryThresholdPercent(percent: Int) {
        updateConfiguration(_alertConfiguration.value.copy(lowBatteryThresholdPercent = percent))
    }

    fun setDeviceOfflineAlertEnabled(enabled: Boolean) {
        updateConfiguration(_alertConfiguration.value.copy(deviceOfflineAlertEnabled = enabled))
    }

    fun setFullscreenBreachModalEnabled(enabled: Boolean) {
        updateConfiguration(_alertConfiguration.value.copy(fullscreenBreachModalEnabled = enabled))
    }
}
