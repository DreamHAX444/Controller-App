package com.livetracker.controller.ui.screens.map

import android.content.Context
import android.content.SharedPreferences
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class MapPreferencesRepositoryTest {

    class FakeSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = TODO()
        override fun getString(key: String, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = TODO()
        override fun getInt(key: String, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = TODO()
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = TODO()
    }

    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value != null) pending[key] = value else pending.remove(key)
            return this
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = TODO()
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = TODO()
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = TODO()
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor = TODO()
        override fun clear(): SharedPreferences.Editor = TODO()
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            prefs.data.putAll(pending)
            pending.clear()
        }
    }

    class FakeContext(private val prefs: SharedPreferences) : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context { return this }

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefs
        }
    }

    @Test
    fun testDefaultConfig() {
        val prefs = FakeSharedPreferences()
        val repository = MapPreferencesRepository(FakeContext(prefs))

        val config = repository.mapConfiguration.value
        assertEquals(MapProvider.OPEN_STREET_MAP, config.provider)
        assertEquals(MapStyle.STANDARD, config.style)
    }

    @Test
    fun testInvalidStyleFallback() {
        val prefs = FakeSharedPreferences()
        prefs.data["pref_map_provider"] = MapProvider.OPEN_STREET_MAP.name
        prefs.data["pref_map_style"] = MapStyle.SATELLITE.name // Not supported by OSM

        val repository = MapPreferencesRepository(FakeContext(prefs))

        val config = repository.mapConfiguration.value
        assertEquals(MapProvider.OPEN_STREET_MAP, config.provider)
        assertEquals(MapStyle.STANDARD, config.style) // Fallback applied
    }

    @Test
    fun testSettingConfig() {
        val prefs = FakeSharedPreferences()
        val repository = MapPreferencesRepository(FakeContext(prefs))
        
        repository.setConfiguration(MapConfiguration(MapProvider.GOOGLE_MAPS, MapStyle.SATELLITE))

        assertEquals(MapProvider.GOOGLE_MAPS, repository.mapConfiguration.value.provider)
        assertEquals(MapStyle.SATELLITE, repository.mapConfiguration.value.style)

        assertEquals(MapProvider.GOOGLE_MAPS.name, prefs.data["pref_map_provider"])
        assertEquals(MapStyle.SATELLITE.name, prefs.data["pref_map_style"])
    }

    @Test
    fun testCameraPositionPersistence() {
        val prefs = FakeSharedPreferences()
        val repository = MapPreferencesRepository(FakeContext(prefs))

        // Initially null
        org.junit.Assert.assertNull(repository.getLastCameraPosition())

        // Save position
        repository.saveLastCameraPosition(40.7128, -74.0060, 15.5)

        val retrieved = repository.getLastCameraPosition()
        org.junit.Assert.assertNotNull(retrieved)
        assertEquals(40.7128, retrieved!!.first, 0.0001)
        assertEquals(-74.0060, retrieved.second, 0.0001)
        assertEquals(15.5, retrieved.third, 0.0001)
    }
}
