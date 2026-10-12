package com.livetracker.controller.core.tracker.geofence.data

import android.content.Context
import android.content.SharedPreferences
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PersistentGeoFenceRepository(context: Context) : GeoFenceRepository {
    private val prefs: SharedPreferences = context.getSharedPreferences("geofences_prefs", Context.MODE_PRIVATE)
    private val KEY_GEOFENCES = "geofences_json"

    private val mutex = Mutex()
    private var memoryCache: List<GeoFence>? = null

    override suspend fun addGeoFence(geoFence: GeoFence) {
        mutex.withLock {
            val currentFences = (memoryCache ?: loadFromDisk()).toMutableList()
            val existingIndex = currentFences.indexOfFirst { it.id == geoFence.id }
            if (existingIndex >= 0) {
                currentFences[existingIndex] = geoFence
            } else {
                currentFences.add(geoFence)
            }
            memoryCache = currentFences.toList()
            withContext(Dispatchers.IO) {
                saveList(currentFences)
            }
        }
    }

    override suspend fun removeGeoFence(id: String) {
        mutex.withLock {
            val currentFences = (memoryCache ?: loadFromDisk()).toMutableList()
            val removed = currentFences.removeAll { it.id == id }
            if (removed) {
                memoryCache = currentFences.toList()
                withContext(Dispatchers.IO) {
                    saveList(currentFences)
                }
            }
        }
    }

    override suspend fun getGeoFences(): List<GeoFence> {
        mutex.withLock {
            memoryCache?.let { return it }
            val loaded = withContext(Dispatchers.IO) { loadFromDisk() }
            memoryCache = loaded
            return loaded
        }
    }

    private fun loadFromDisk(): List<GeoFence> {
        val json = prefs.getString(KEY_GEOFENCES, null)
        return if (json.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                Json.decodeFromString(json)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun saveList(list: List<GeoFence>) {
        val json = Json.encodeToString(list)
        prefs.edit().putString(KEY_GEOFENCES, json).apply()
    }
}
