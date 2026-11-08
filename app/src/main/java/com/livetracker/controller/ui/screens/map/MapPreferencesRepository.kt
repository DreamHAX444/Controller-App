package com.livetracker.controller.ui.screens.map

import android.content.Context
import android.content.SharedPreferences
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import com.livetracker.controller.ui.screens.map.config.getSupportedStyles
import com.livetracker.controller.ui.screens.map.offline.OfflineMapPreferences
import com.livetracker.controller.ui.theme.ThemeAccentPreset
import com.livetracker.controller.ui.theme.ThemeConfiguration
import com.livetracker.controller.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MapPreferencesRepository(private val sharedPrefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _mapConfiguration = MutableStateFlow(loadMapConfiguration())
    val mapConfiguration: StateFlow<MapConfiguration> = _mapConfiguration.asStateFlow()

    private val _cartoApiKey = MutableStateFlow(loadCartoApiKey())
    val cartoApiKey: StateFlow<String> = _cartoApiKey.asStateFlow()

    private val _offlinePreferences = MutableStateFlow(loadOfflinePreferences())
    val offlinePreferences: StateFlow<OfflineMapPreferences> = _offlinePreferences.asStateFlow()

    private val _themeConfiguration = MutableStateFlow(loadThemeConfiguration())
    val themeConfiguration: StateFlow<ThemeConfiguration> = _themeConfiguration.asStateFlow()

    private val _hasSeenGestureCoach = MutableStateFlow(loadHasSeenGestureCoach())
    val hasSeenGestureCoach: StateFlow<Boolean> = _hasSeenGestureCoach.asStateFlow()

    private val _lastFocusedDeviceId = MutableStateFlow(loadLastFocusedDeviceId())
    val lastFocusedDeviceId: StateFlow<String?> = _lastFocusedDeviceId.asStateFlow()

    private fun loadMapConfiguration(): MapConfiguration {
        val providerName = sharedPrefs.getString(KEY_PROVIDER, MapProvider.OPEN_STREET_MAP.name)
            ?: MapProvider.OPEN_STREET_MAP.name
        val styleName = sharedPrefs.getString(KEY_STYLE, MapStyle.STANDARD.name)
            ?: MapStyle.STANDARD.name

        val provider = runCatching { MapProvider.valueOf(providerName) }
            .getOrDefault(MapProvider.OPEN_STREET_MAP)
        val rawStyle = runCatching { MapStyle.valueOf(styleName) }
            .getOrDefault(MapStyle.STANDARD)

        val supported = provider.getSupportedStyles()
        val effectiveStyle = if (rawStyle in supported) rawStyle else supported.firstOrNull() ?: MapStyle.STANDARD

        return MapConfiguration(provider = provider, style = effectiveStyle)
    }

    private fun loadCartoApiKey(): String {
        return sharedPrefs.getString(KEY_CARTO_API_KEY, "") ?: ""
    }

    private fun loadOfflinePreferences(): OfflineMapPreferences {
        val allowed = sharedPrefs.getBoolean(KEY_OFFLINE_ALLOWED, true)
        val autoCache = sharedPrefs.getBoolean(KEY_AUTO_CACHE_REGIONS, false)
        val maxCacheMb = sharedPrefs.getInt(KEY_MAX_CACHE_SIZE_MB, 300)
        return OfflineMapPreferences(
            isOfflineDownloadAllowed = allowed,
            autoCacheTrackerRegions = autoCache,
            maxCacheSizeMb = maxCacheMb
        )
    }

    private fun loadThemeConfiguration(): ThemeConfiguration {
        val modeStr = sharedPrefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val presetStr = sharedPrefs.getString(KEY_ACCENT_PRESET, ThemeAccentPreset.SAPPHIRE.name) ?: ThemeAccentPreset.SAPPHIRE.name
        val customHue = sharedPrefs.getInt(KEY_CUSTOM_HUE_DEGREES, 215).coerceIn(0, 360)
        val dynamicColor = sharedPrefs.getBoolean(KEY_USE_DYNAMIC_COLOR, false)

        val mode = runCatching { ThemeMode.valueOf(modeStr) }.getOrDefault(ThemeMode.SYSTEM)
        val preset = runCatching { ThemeAccentPreset.valueOf(presetStr) }.getOrDefault(ThemeAccentPreset.SAPPHIRE)

        return ThemeConfiguration(
            themeMode = mode,
            accentPreset = preset,
            customHueDegrees = customHue,
            useDynamicSystemColor = dynamicColor
        )
    }

    private fun loadHasSeenGestureCoach(): Boolean {
        return sharedPrefs.getBoolean(KEY_HAS_SEEN_GESTURE_COACH, false)
    }

    private fun loadLastFocusedDeviceId(): String? {
        return sharedPrefs.getString(KEY_LAST_FOCUSED_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
    }

    fun setConfiguration(configuration: MapConfiguration) {
        val supported = configuration.provider.getSupportedStyles()
        val validStyle = if (configuration.style in supported) {
            configuration.style
        } else {
            supported.firstOrNull() ?: MapStyle.STANDARD
        }
        val finalConfig = configuration.copy(style = validStyle)
        sharedPrefs.edit()
            .putString(KEY_PROVIDER, finalConfig.provider.name)
            .putString(KEY_STYLE, finalConfig.style.name)
            .apply()
        _mapConfiguration.value = finalConfig
    }

    fun setCartoApiKey(apiKey: String) {
        val clean = apiKey.trim()
        sharedPrefs.edit().putString(KEY_CARTO_API_KEY, clean).apply()
        _cartoApiKey.value = clean
    }

    fun setOfflineDownloadAllowed(allowed: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_OFFLINE_ALLOWED, allowed).apply()
        _offlinePreferences.value = _offlinePreferences.value.copy(isOfflineDownloadAllowed = allowed)
    }

    fun setAutoCacheTrackerRegions(autoCache: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_AUTO_CACHE_REGIONS, autoCache).apply()
        _offlinePreferences.value = _offlinePreferences.value.copy(autoCacheTrackerRegions = autoCache)
    }

    fun setMaxCacheSizeMb(maxCacheSizeMb: Int) {
        val clamped = maxCacheSizeMb.coerceIn(50, 4096)
        sharedPrefs.edit().putInt(KEY_MAX_CACHE_SIZE_MB, clamped).apply()
        _offlinePreferences.value = _offlinePreferences.value.copy(maxCacheSizeMb = clamped)
    }

    fun saveLastCameraPosition(lat: Double, lon: Double, zoom: Double) {
        if (lat.isNaN() || lon.isNaN() || zoom.isNaN()) return
        sharedPrefs.edit()
            .putString(KEY_LAST_LAT, lat.toString())
            .putString(KEY_LAST_LON, lon.toString())
            .putString(KEY_LAST_ZOOM, zoom.toString())
            .apply()
    }

    fun getLastCameraPosition(): Triple<Double, Double, Double>? {
        val latStr = sharedPrefs.getString(KEY_LAST_LAT, null) ?: return null
        val lonStr = sharedPrefs.getString(KEY_LAST_LON, null) ?: return null
        val zoomStr = sharedPrefs.getString(KEY_LAST_ZOOM, null) ?: return null

        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null
        val zoom = zoomStr.toDoubleOrNull() ?: return null
        if (lat.isNaN() || lon.isNaN() || zoom.isNaN()) return null
        return Triple(lat, lon, zoom)
    }

    fun setThemeMode(mode: ThemeMode) {
        sharedPrefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeConfiguration.value = _themeConfiguration.value.copy(themeMode = mode)
    }

    fun setAccentPreset(preset: ThemeAccentPreset) {
        sharedPrefs.edit().putString(KEY_ACCENT_PRESET, preset.name).apply()
        _themeConfiguration.value = _themeConfiguration.value.copy(accentPreset = preset)
    }

    fun setCustomHueDegrees(hueDegrees: Int) {
        val clamped = hueDegrees.coerceIn(0, 360)
        sharedPrefs.edit()
            .putInt(KEY_CUSTOM_HUE_DEGREES, clamped)
            .putString(KEY_ACCENT_PRESET, ThemeAccentPreset.CUSTOM.name)
            .apply()
        _themeConfiguration.value = _themeConfiguration.value.copy(
            customHueDegrees = clamped,
            accentPreset = ThemeAccentPreset.CUSTOM
        )
    }

    fun setUseDynamicSystemColor(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_USE_DYNAMIC_COLOR, enabled).apply()
        _themeConfiguration.value = _themeConfiguration.value.copy(useDynamicSystemColor = enabled)
    }

    fun setHasSeenGestureCoach(seen: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_HAS_SEEN_GESTURE_COACH, seen).apply()
        _hasSeenGestureCoach.value = seen
    }

    fun setLastFocusedDeviceId(deviceId: String?) {
        sharedPrefs.edit().putString(KEY_LAST_FOCUSED_DEVICE_ID, deviceId ?: "").apply()
        _lastFocusedDeviceId.value = deviceId?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val PREFS_NAME = "trace_map_preferences"
        private const val KEY_PROVIDER = "pref_map_provider"
        private const val KEY_STYLE = "pref_map_style"
        private const val KEY_CARTO_API_KEY = "pref_carto_api_key"
        private const val KEY_OFFLINE_ALLOWED = "pref_offline_allowed"
        private const val KEY_AUTO_CACHE_REGIONS = "pref_auto_cache_regions"
        private const val KEY_MAX_CACHE_SIZE_MB = "pref_max_cache_size_mb"
        private const val KEY_LAST_LAT = "pref_last_camera_lat"
        private const val KEY_LAST_LON = "pref_last_camera_lon"
        private const val KEY_LAST_ZOOM = "pref_last_camera_zoom"

        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_ACCENT_PRESET = "pref_theme_accent_preset"
        private const val KEY_CUSTOM_HUE_DEGREES = "pref_theme_custom_hue_deg"
        private const val KEY_USE_DYNAMIC_COLOR = "pref_theme_use_dynamic_color"
        private const val KEY_HAS_SEEN_GESTURE_COACH = "pref_has_seen_gesture_coach"
        private const val KEY_LAST_FOCUSED_DEVICE_ID = "pref_last_focused_device_id"
    }
}
