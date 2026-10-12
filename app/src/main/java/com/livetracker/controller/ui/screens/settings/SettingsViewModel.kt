package com.livetracker.controller.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.MapPreferencesRepository
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import com.livetracker.controller.ui.screens.map.offline.OfflineCacheInfo
import com.livetracker.controller.ui.screens.map.offline.OfflineDownloadState
import com.livetracker.controller.ui.screens.map.offline.OfflineMapManager
import com.livetracker.controller.ui.screens.map.offline.OfflineMapPreferences
import com.livetracker.controller.ui.screens.map.offline.OfflineRegion
import com.livetracker.controller.ui.screens.map.offline.SavedOfflineRegion
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
    val lastCameraPosition: Triple<Double, Double, Double>?
        get() = mapPreferencesRepository.getLastCameraPosition()
    val offlinePreferences: StateFlow<OfflineMapPreferences> = mapPreferencesRepository.offlinePreferences
    val downloadState: StateFlow<OfflineDownloadState> = offlineMapManager?.downloadState ?: MutableStateFlow(OfflineDownloadState.Idle)
    val cacheInfo: StateFlow<OfflineCacheInfo> = offlineMapManager?.cacheInfo ?: MutableStateFlow(OfflineCacheInfo())
    val savedRegions: StateFlow<List<SavedOfflineRegion>> = offlineMapManager?.savedRegions ?: MutableStateFlow(emptyList())

    fun setCartoApiKey(key: String) {
        mapPreferencesRepository.setCartoApiKey(key)
    }

    private val _isAreaPickerOpen = MutableStateFlow(false)
    val isAreaPickerOpen: StateFlow<Boolean> = _isAreaPickerOpen.asStateFlow()

    fun openAreaPicker() {
        _isAreaPickerOpen.value = true
    }

    fun closeAreaPicker() {
        _isAreaPickerOpen.value = false
    }

    fun downloadSelectedRegion(region: OfflineRegion) {
        closeAreaPicker()
        offlineMapManager?.downloadRegion(region)
    }

    fun setMapProvider(provider: MapProvider) {
        val currentStyle = mapConfiguration.value.style
        mapPreferencesRepository.setConfiguration(MapConfiguration(provider, currentStyle))
    }

    fun setMapStyle(style: MapStyle) {
        val currentProvider = mapConfiguration.value.provider
        mapPreferencesRepository.setConfiguration(MapConfiguration(currentProvider, style))
    }

    fun setOfflineDownloadAllowed(allowed: Boolean) {
        mapPreferencesRepository.setOfflineDownloadAllowed(allowed)
    }

    fun setAutoCacheTrackerRegions(autoCache: Boolean) {
        mapPreferencesRepository.setAutoCacheTrackerRegions(autoCache)
    }

    fun setMaxCacheSizeMb(sizeMb: Int) {
        mapPreferencesRepository.setMaxCacheSizeMb(sizeMb)
    }

    fun downloadActiveArea(region: OfflineRegion? = null) {
        if (!offlinePreferences.value.isOfflineDownloadAllowed) return
        val targetRegion = region ?: OfflineRegion(
            name = "Active Operations Area",
            north = 40.80,
            east = -73.90,
            south = 40.68,
            west = -74.05,
            minZoom = 12,
            maxZoom = 16
        )
        offlineMapManager?.downloadRegion(targetRegion)
    }

    fun cancelDownload() {
        offlineMapManager?.cancelDownload()
    }

    fun deleteSavedRegion(id: String) {
        viewModelScope.launch {
            offlineMapManager?.deleteRegion(id)
        }
    }

    fun clearOfflineCache() {
        viewModelScope.launch {
            offlineMapManager?.clearCache()
        }
    }

    fun refreshCacheInfo() {
        offlineMapManager?.refreshCacheInfo()
    }
}

