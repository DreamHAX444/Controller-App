package com.livetracker.controller.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.ui.screens.map.MapPreferencesRepository
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import com.livetracker.controller.ui.screens.map.offline.OfflineCacheInfo
import com.livetracker.controller.ui.screens.map.offline.OfflineDownloadState
import com.livetracker.controller.ui.screens.map.offline.OfflineMapManager
import com.livetracker.controller.ui.screens.map.offline.OfflineMapPreferences
import com.livetracker.controller.ui.screens.map.offline.OfflineRegion
import com.livetracker.controller.ui.screens.map.offline.SavedOfflineRegion
import com.livetracker.controller.ui.theme.ThemeAccentPreset
import com.livetracker.controller.ui.theme.ThemeConfiguration
import com.livetracker.controller.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val mapPreferencesRepository: MapPreferencesRepository,
    private val offlineMapManager: OfflineMapManager? = null
) : ViewModel() {

    val mapConfiguration: StateFlow<MapConfiguration> = mapPreferencesRepository.mapConfiguration
    val cartoApiKey: StateFlow<String> = mapPreferencesRepository.cartoApiKey
    val offlinePreferences: StateFlow<OfflineMapPreferences> = mapPreferencesRepository.offlinePreferences
    val themeConfiguration: StateFlow<ThemeConfiguration> = mapPreferencesRepository.themeConfiguration
    val hasSeenGestureCoach: StateFlow<Boolean> = mapPreferencesRepository.hasSeenGestureCoach

    private val _fallbackDownloadState = MutableStateFlow<OfflineDownloadState>(OfflineDownloadState.Idle)
    val downloadState: StateFlow<OfflineDownloadState> =
        offlineMapManager?.downloadState ?: _fallbackDownloadState.asStateFlow()

    private val _fallbackCacheInfo = MutableStateFlow(OfflineCacheInfo())
    val cacheInfo: StateFlow<OfflineCacheInfo> =
        offlineMapManager?.cacheInfo ?: _fallbackCacheInfo.asStateFlow()

    private val _fallbackSavedRegions = MutableStateFlow<List<SavedOfflineRegion>>(emptyList())
    val savedRegions: StateFlow<List<SavedOfflineRegion>> =
        offlineMapManager?.savedRegions ?: _fallbackSavedRegions.asStateFlow()

    private val _isAreaPickerOpen = MutableStateFlow(false)
    val isAreaPickerOpen: StateFlow<Boolean> = _isAreaPickerOpen.asStateFlow()

    val lastCameraPosition: Triple<Double, Double, Double>?
        get() = mapPreferencesRepository.getLastCameraPosition()

    fun setThemeMode(mode: ThemeMode) {
        mapPreferencesRepository.setThemeMode(mode)
    }

    fun setAccentPreset(preset: ThemeAccentPreset) {
        mapPreferencesRepository.setAccentPreset(preset)
    }

    fun setCustomHueDegrees(hueDegrees: Int) {
        mapPreferencesRepository.setCustomHueDegrees(hueDegrees)
    }

    fun setUseDynamicSystemColor(enabled: Boolean) {
        mapPreferencesRepository.setUseDynamicSystemColor(enabled)
    }

    fun resetGestureCoach() {
        mapPreferencesRepository.setHasSeenGestureCoach(false)
    }

    fun setMapProvider(provider: MapProvider) {
        val current = mapConfiguration.value
        mapPreferencesRepository.setConfiguration(current.copy(provider = provider))
    }

    fun setMapStyle(style: MapStyle) {
        val current = mapConfiguration.value
        mapPreferencesRepository.setConfiguration(current.copy(style = style))
    }

    fun setCartoApiKey(apiKey: String) {
        mapPreferencesRepository.setCartoApiKey(apiKey)
    }

    fun setOfflineDownloadAllowed(allowed: Boolean) {
        mapPreferencesRepository.setOfflineDownloadAllowed(allowed)
        if (!allowed) {
            offlineMapManager?.cancelDownload()
        }
    }

    fun setAutoCacheTrackerRegions(autoCache: Boolean) {
        mapPreferencesRepository.setAutoCacheTrackerRegions(autoCache)
    }

    fun setMaxCacheSizeMb(maxCacheSizeMb: Int) {
        mapPreferencesRepository.setMaxCacheSizeMb(maxCacheSizeMb)
        offlineMapManager?.refreshCacheInfo()
    }

    fun openAreaPicker() {
        _isAreaPickerOpen.value = true
    }

    fun closeAreaPicker() {
        _isAreaPickerOpen.value = false
    }

    fun downloadSelectedRegion(region: OfflineRegion) {
        _isAreaPickerOpen.value = false
        if (!offlinePreferences.value.isOfflineDownloadAllowed) return
        offlineMapManager?.downloadRegion(region)
    }

    fun cancelDownload() {
        offlineMapManager?.cancelDownload()
    }

    fun clearOfflineCache() {
        viewModelScope.launch {
            offlineMapManager?.clearCache()
        }
    }

    fun deleteSavedRegion(regionId: String) {
        viewModelScope.launch {
            offlineMapManager?.deleteRegion(regionId)
        }
    }
}
