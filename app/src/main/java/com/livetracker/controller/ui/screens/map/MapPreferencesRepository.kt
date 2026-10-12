package com.livetracker.controller.ui.screens.map

import android.content.Context
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import com.livetracker.controller.ui.screens.map.config.getDefaultStyle
import com.livetracker.controller.ui.screens.map.config.getSupportedStyles
import com.livetracker.controller.ui.screens.map.offline.OfflineMapPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MapPreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("trackmate_map_settings", Context.MODE_PRIVATE)

    private val _mapConfiguration = MutableStateFlow(loadConfiguration())
    val mapConfiguration: StateFlow<MapConfiguration> = _mapConfiguration.asStateFlow()

    private val _offlinePreferences = MutableStateFlow(loadOfflinePreferences())
    val offlinePreferences: StateFlow<OfflineMapPreferences> = _offlinePreferences.asStateFlow()

    private val _cartoApiKey = MutableStateFlow(loadCartoApiKey())
    val cartoApiKey: StateFlow<String> = _cartoApiKey.asStateFlow()

    private fun loadCartoApiKey(): String {
        return prefs.getString(KEY_CARTO_API_KEY, "") ?: ""
    }

    fun setCartoApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString(KEY_CARTO_API_KEY, trimmed).apply()
        _cartoApiKey.value = trimmed
    }

    private fun loadConfiguration(): MapConfiguration {
        val storedProviderName = prefs.getString(KEY_MAP_PROVIDER, MapProvider.OPEN_STREET_MAP.name)
        val provider = try {
            MapProvider.valueOf(storedProviderName ?: MapProvider.OPEN_STREET_MAP.name)
        } catch (_: Exception) {
            MapProvider.OPEN_STREET_MAP
        }

        val storedStyleName = prefs.getString(KEY_MAP_STYLE, provider.getDefaultStyle().name)
        val parsedStyle = try {
            MapStyle.valueOf(storedStyleName ?: provider.getDefaultStyle().name)
        } catch (_: Exception) {
            provider.getDefaultStyle()
        }

        // Validate style compatibility with provider
        val style = if (provider.getSupportedStyles().contains(parsedStyle)) {
            parsedStyle
        } else {
            provider.getDefaultStyle()
        }

        return MapConfiguration(provider, style)
    }

    private fun loadOfflinePreferences(): OfflineMapPreferences {
        val allowed = try { prefs.getBoolean(KEY_OFFLINE_DOWNLOAD_ALLOWED, true) } catch (_: Exception) { true }
        val autoCache = try { prefs.getBoolean(KEY_AUTO_CACHE_TRACKER_REGIONS, false) } catch (_: Exception) { false }
        val maxSize = try { prefs.getInt(KEY_MAX_CACHE_SIZE_MB, 300) } catch (_: Exception) { 300 }
        return OfflineMapPreferences(
            isOfflineDownloadAllowed = allowed,
            autoCacheTrackerRegions = autoCache,
            maxCacheSizeMb = maxSize
        )
    }

    fun setConfiguration(configuration: MapConfiguration) {
        val validStyle = if (configuration.provider.getSupportedStyles().contains(configuration.style)) {
            configuration.style
        } else {
            configuration.provider.getDefaultStyle()
        }

        val validConfig = MapConfiguration(configuration.provider, validStyle)

        prefs.edit()
            .putString(KEY_MAP_PROVIDER, validConfig.provider.name)
            .putString(KEY_MAP_STYLE, validConfig.style.name)
            .apply()

        _mapConfiguration.value = validConfig
    }

    fun setOfflineDownloadAllowed(allowed: Boolean) {
        prefs.edit()
            .putBoolean(KEY_OFFLINE_DOWNLOAD_ALLOWED, allowed)
            .apply()
        _offlinePreferences.value = _offlinePreferences.value.copy(isOfflineDownloadAllowed = allowed)
    }

    fun setAutoCacheTrackerRegions(autoCache: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO_CACHE_TRACKER_REGIONS, autoCache)
            .apply()
        _offlinePreferences.value = _offlinePreferences.value.copy(autoCacheTrackerRegions = autoCache)
    }

    fun setMaxCacheSizeMb(sizeMb: Int) {
        prefs.edit()
            .putInt(KEY_MAX_CACHE_SIZE_MB, sizeMb)
            .apply()
        _offlinePreferences.value = _offlinePreferences.value.copy(maxCacheSizeMb = sizeMb)
    }

    /**
     * Remembers the last camera position and zoom across screen switches and app restarts,
     * ensuring the map view restores immediately without snapping back or blinking.
     */
    fun saveLastCameraPosition(latitude: Double, longitude: Double, zoom: Double) {
        prefs.edit()
            .putString(KEY_LAST_CAM_LAT, latitude.toString())
            .putString(KEY_LAST_CAM_LON, longitude.toString())
            .putString(KEY_LAST_CAM_ZOOM, zoom.toString())
            .apply()
    }

    fun getLastCameraPosition(): Triple<Double, Double, Double>? {
        val latStr = prefs.getString(KEY_LAST_CAM_LAT, null) ?: return null
        val lonStr = prefs.getString(KEY_LAST_CAM_LON, null) ?: return null
        val zoomStr = prefs.getString(KEY_LAST_CAM_ZOOM, null) ?: return null
        return try {
            Triple(latStr.toDouble(), lonStr.toDouble(), zoomStr.toDouble())
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        // We migrate away from old key pref_map_engine seamlessly if we want, or just start fresh.
        private const val KEY_MAP_PROVIDER = "pref_map_provider"
        private const val KEY_MAP_STYLE = "pref_map_style"
        private const val KEY_CARTO_API_KEY = "pref_carto_api_key"
        private const val KEY_OFFLINE_DOWNLOAD_ALLOWED = "pref_offline_download_allowed"
        private const val KEY_AUTO_CACHE_TRACKER_REGIONS = "pref_auto_cache_tracker_regions"
        private const val KEY_MAX_CACHE_SIZE_MB = "pref_max_cache_size_mb"
        private const val KEY_LAST_CAM_LAT = "pref_last_cam_lat"
        private const val KEY_LAST_CAM_LON = "pref_last_cam_lon"
        private const val KEY_LAST_CAM_ZOOM = "pref_last_cam_zoom"
    }
}
