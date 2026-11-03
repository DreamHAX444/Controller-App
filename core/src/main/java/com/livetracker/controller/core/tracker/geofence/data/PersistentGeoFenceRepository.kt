package com.livetracker.controller.core.tracker.geofence.data

import android.content.Context
import android.content.SharedPreferences
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PersistentGeoFenceRepository(
    private val prefs: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GeoFenceRepository {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        Dispatchers.IO
    )

    private val mutex = Mutex()
    private var memoryCache: List<GeoFence>? = null

    @Volatile
    private var storageCorrupted: Boolean = false

    fun isStorageCorrupted(): Boolean = storageCorrupted

    override suspend fun addGeoFence(geoFence: GeoFence) {
        mutex.withLock {
            if (storageCorrupted) {
                throw IllegalStateException(
                    "Cannot add geofence: persistent storage in key '$KEY_GEOFENCES' is corrupted. Writes are blocked to prevent data loss."
                )
            }
            val currentFences = (memoryCache ?: withContext(ioDispatcher) { loadFromDisk() }).toMutableList()
            val existingIndex = currentFences.indexOfFirst { it.id == geoFence.id }
            if (existingIndex >= 0) {
                currentFences[existingIndex] = geoFence
            } else {
                currentFences.add(geoFence)
            }
            withContext(ioDispatcher) {
                saveList(currentFences)
            }
            memoryCache = currentFences.toList()
        }
    }

    override suspend fun removeGeoFence(id: String) {
        mutex.withLock {
            if (storageCorrupted) {
                throw IllegalStateException(
                    "Cannot remove geofence: persistent storage in key '$KEY_GEOFENCES' is corrupted. Writes are blocked to prevent data loss."
                )
            }
            val currentFences = (memoryCache ?: withContext(ioDispatcher) { loadFromDisk() }).toMutableList()
            val removed = currentFences.removeAll { it.id == id }
            if (removed) {
                withContext(ioDispatcher) {
                    saveList(currentFences)
                }
                memoryCache = currentFences.toList()
            }
        }
    }

    override suspend fun getGeoFences(): List<GeoFence> {
        mutex.withLock {
            if (storageCorrupted) {
                throw IllegalStateException(
                    "Cannot get geofences: persistent storage in key '$KEY_GEOFENCES' is corrupted and cannot be deserialized."
                )
            }
            memoryCache?.let { return it }
            val loaded = withContext(ioDispatcher) { loadFromDisk() }
            memoryCache = loaded
            return loaded
        }
    }

    private fun loadFromDisk(): List<GeoFence> {
        val json = prefs.getString(KEY_GEOFENCES, null)
        if (json.isNullOrBlank()) {
            storageCorrupted = false
            return emptyList()
        }
        return try {
            val decoded = jsonFormat.decodeFromString<List<GeoFence>>(json)
            storageCorrupted = false
            decoded
        } catch (e: Exception) {
            storageCorrupted = true
            // Quarantine/backup the raw corrupted payload so it is preserved
            try {
                prefs.edit().putString(KEY_GEOFENCES_BACKUP, json).apply()
            } catch (_: Exception) {
                // Secondary backup failure ignored
            }
            throw IllegalStateException(
                "Persistent geofence storage in key '$KEY_GEOFENCES' is corrupted and cannot be deserialized. Writes are blocked to prevent data loss.",
                e
            )
        }
    }

    private fun saveList(list: List<GeoFence>) {
        if (storageCorrupted) {
            throw IllegalStateException(
                "Cannot save geofences: storage is marked as corrupted. Write blocked."
            )
        }
        val json = jsonFormat.encodeToString(list)
        prefs.edit().putString(KEY_GEOFENCES, json).commit()
    }

    companion object {
        const val PREFS_NAME = "geofences_prefs"
        const val KEY_GEOFENCES = "geofences_json"
        const val KEY_GEOFENCES_BACKUP = "geofences_json_corrupted_backup"

        private val jsonFormat = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}

