package com.livetracker.controller.ui.screens.settings

import android.content.Context
import android.content.SharedPreferences
import com.livetracker.controller.ui.screens.map.MapPreferencesRepository
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {

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
    fun testChangingProviderKeepsStyleOrFallsBack() {
        val prefs = FakeSharedPreferences()
        val repository = MapPreferencesRepository(FakeContext(prefs))
        val viewModel = SettingsViewModel(repository)

        // Currently OSM / Standard
        assertEquals(MapProvider.OPEN_STREET_MAP, viewModel.mapConfiguration.value.provider)
        
        viewModel.setMapProvider(MapProvider.GOOGLE_MAPS)
        // Standard is valid for Google Maps
        assertEquals(MapProvider.GOOGLE_MAPS, viewModel.mapConfiguration.value.provider)
        assertEquals(MapStyle.STANDARD, viewModel.mapConfiguration.value.style)

        viewModel.setMapStyle(MapStyle.SATELLITE)
        assertEquals(MapStyle.SATELLITE, viewModel.mapConfiguration.value.style)

        viewModel.setMapProvider(MapProvider.OPEN_STREET_MAP)
        // Satellite is invalid for OSM, should fallback to Standard
        assertEquals(MapProvider.OPEN_STREET_MAP, viewModel.mapConfiguration.value.provider)
        assertEquals(MapStyle.STANDARD, viewModel.mapConfiguration.value.style)
    }

    @Test
    fun testOfflinePreferencesDefaultsAndUpdates() {
        val prefs = FakeSharedPreferences()
        val repository = MapPreferencesRepository(FakeContext(prefs))
        val viewModel = SettingsViewModel(repository)

        // Defaults
        assertEquals(true, viewModel.offlinePreferences.value.isOfflineDownloadAllowed)
        assertEquals(false, viewModel.offlinePreferences.value.autoCacheTrackerRegions)
        assertEquals(300, viewModel.offlinePreferences.value.maxCacheSizeMb)

        // Updates
        viewModel.setOfflineDownloadAllowed(false)
        assertEquals(false, viewModel.offlinePreferences.value.isOfflineDownloadAllowed)

        viewModel.setAutoCacheTrackerRegions(true)
        assertEquals(true, viewModel.offlinePreferences.value.autoCacheTrackerRegions)

        viewModel.setMaxCacheSizeMb(500)
        assertEquals(500, viewModel.offlinePreferences.value.maxCacheSizeMb)
    }

    @Test
    fun testAreaPickerOpenAndClose() {
        val prefs = FakeSharedPreferences()
        val repository = MapPreferencesRepository(FakeContext(prefs))
        val viewModel = SettingsViewModel(repository)

        assertEquals(false, viewModel.isAreaPickerOpen.value)

        viewModel.openAreaPicker()
        assertEquals(true, viewModel.isAreaPickerOpen.value)

        viewModel.closeAreaPicker()
        assertEquals(false, viewModel.isAreaPickerOpen.value)
    }

    @Test
    fun testOfflineRegionTileCalculationAndSafetyLimits() {
        // Small city center area: should be within safe limit (< 5000 tiles)
        val safeRegion = com.livetracker.controller.ui.screens.map.offline.OfflineRegion(
            name = "Safe Manhattan Area",
            north = 40.73,
            east = -73.98,
            south = 40.71,
            west = -74.01,
            minZoom = 12,
            maxZoom = 15
        )
        val safeCount = safeRegion.calculateTileCount()
        org.junit.Assert.assertTrue("Tile count should be > 0", safeCount > 0)
        org.junit.Assert.assertTrue("Tile count should be <= 5000", safeRegion.isWithinSafeLimit)

        // Massive continent area: should exceed safe limit
        val hugeRegion = com.livetracker.controller.ui.screens.map.offline.OfflineRegion(
            name = "Entire Continent",
            north = 60.0,
            east = 30.0,
            south = 20.0,
            west = -30.0,
            minZoom = 12,
            maxZoom = 16
        )
        val hugeCount = hugeRegion.calculateTileCount()
        org.junit.Assert.assertTrue("Tile count should be > 5000", hugeCount > com.livetracker.controller.ui.screens.map.offline.OfflineMapConstants.MAX_OFFLINE_TILES_LIMIT)
        org.junit.Assert.assertFalse("Should not be within safe limit", hugeRegion.isWithinSafeLimit)
    }

    @Test
    fun testSavedRegionMetadataAndOverlapLogic() {
        val saved = com.livetracker.controller.ui.screens.map.offline.SavedOfflineRegion(
            id = "region_test_1",
            name = "Brooklyn Sector",
            north = 40.70,
            east = -73.95,
            south = 40.65,
            west = -74.00,
            minZoom = 12,
            maxZoom = 16,
            tileCount = 350,
            sizeBytes = 350L * 25 * 1024,
            downloadTimestamp = System.currentTimeMillis()
        )

        assertEquals("Brooklyn Sector", saved.name)
        org.junit.Assert.assertTrue("Region size in MB should be > 0", saved.sizeMb > 0f)

        // Bounding box test: overlapping area
        val testNorth = 40.68
        val testSouth = 40.62
        val testEast = -73.97
        val testWest = -74.02

        val overlaps = (testNorth > saved.south && testSouth < saved.north && testEast > saved.west && testWest < saved.east)
        org.junit.Assert.assertTrue("Regions should overlap", overlaps)

        // Non-overlapping area (far away in California)
        val farNorth = 37.80
        val farSouth = 37.75
        val farEast = -122.40
        val farWest = -122.45

        val farOverlaps = (farNorth > saved.south && farSouth < saved.north && farEast > saved.west && farWest < saved.east)
        org.junit.Assert.assertFalse("Regions should not overlap", farOverlaps)
    }
}

