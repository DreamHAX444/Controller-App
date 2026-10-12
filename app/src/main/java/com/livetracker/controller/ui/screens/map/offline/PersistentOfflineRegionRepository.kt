package com.livetracker.controller.ui.screens.map.offline

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thread-safe persistent repository managing explicitly downloaded offline map regions.
 * Persists region metadata in internal app storage (surviving application restarts and cache purges)
 * and provides reactive StateFlow observations for UI screens.
 * Uses high-speed pure Kotlin serialization to avoid Android framework unit test stub overhead.
 */
class PersistentOfflineRegionRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("offline_regions_prefs", Context.MODE_PRIVATE)
    private val KEY_REGIONS = "saved_regions_data"

    private val mutex = Mutex()
    private val _regions = MutableStateFlow<List<SavedOfflineRegion>>(emptyList())
    val regions: StateFlow<List<SavedOfflineRegion>> = _regions.asStateFlow()

    init {
        _regions.value = loadFromDisk()
    }

    suspend fun saveRegion(region: SavedOfflineRegion) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _regions.value.toMutableList()
            val existingIndex = list.indexOfFirst { it.id == region.id }
            if (existingIndex >= 0) {
                list[existingIndex] = region
            } else {
                list.add(0, region) // Latest on top
            }
            writeToDisk(list)
            _regions.value = list
        }
    }

    suspend fun removeRegion(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _regions.value.toMutableList()
            val removed = list.removeAll { it.id == id }
            if (removed) {
                writeToDisk(list)
                _regions.value = list
            }
            removed
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().remove(KEY_REGIONS).apply()
            _regions.value = emptyList()
        }
    }

    private fun loadFromDisk(): List<SavedOfflineRegion> {
        val raw = prefs.getString(KEY_REGIONS, null) ?: return emptyList()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val parts = line.split("|")
                if (parts.size >= 12) {
                    SavedOfflineRegion(
                        id = parts[0],
                        name = parts[1],
                        north = parts[2].toDouble(),
                        east = parts[3].toDouble(),
                        south = parts[4].toDouble(),
                        west = parts[5].toDouble(),
                        minZoom = parts[6].toInt(),
                        maxZoom = parts[7].toInt(),
                        tileCount = parts[8].toInt(),
                        sizeBytes = parts[9].toLong(),
                        downloadTimestamp = parts[10].toLong(),
                        isComplete = parts[11].toBoolean()
                    )
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun writeToDisk(list: List<SavedOfflineRegion>) {
        val raw = list.joinToString("\n") { r ->
            val safeName = r.name.replace("|", "/")
            "${r.id}|$safeName|${r.north}|${r.east}|${r.south}|${r.west}|${r.minZoom}|${r.maxZoom}|${r.tileCount}|${r.sizeBytes}|${r.downloadTimestamp}|${r.isComplete}"
        }
        prefs.edit().putString(KEY_REGIONS, raw).apply()
    }
}
