package com.livetracker.controller.ui.screens.map.offline

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentOfflineRegionRepositoryTest {

    class FakeSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data
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
            pending[key] = value
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
        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            prefs.data.clear()
            pending.clear()
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            for ((k, v) in pending) {
                if (v == null) prefs.data.remove(k) else prefs.data[k] = v
            }
            pending.clear()
        }
    }

    class FakeContext(private val prefs: SharedPreferences) : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context { return this }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefs
        }
    }

    @Test
    fun testSaveRetrieveAndRemoveOfflineRegions() = runTest {
        val prefs = FakeSharedPreferences()
        val context = FakeContext(prefs)
        val repo = PersistentOfflineRegionRepository(context)

        // Initially empty
        assertTrue(repo.regions.value.isEmpty())

        val region1 = SavedOfflineRegion(
            id = "reg_1",
            name = "Downtown Core",
            north = 40.75,
            east = -73.98,
            south = 40.72,
            west = -74.01,
            minZoom = 12,
            maxZoom = 16,
            tileCount = 450,
            sizeBytes = 450L * 25L * 1024L,
            downloadTimestamp = 1000L,
            isComplete = true
        )

        repo.saveRegion(region1)
        assertEquals(1, repo.regions.value.size)
        assertEquals("Downtown Core", repo.regions.value.first().name)
        assertEquals(450, repo.regions.value.first().tileCount)

        // Add a second region
        val region2 = SavedOfflineRegion(
            id = "reg_2",
            name = "Suburbs Safe Zone",
            north = 40.85,
            east = -73.88,
            south = 40.82,
            west = -73.91,
            minZoom = 12,
            maxZoom = 15,
            tileCount = 200,
            sizeBytes = 200L * 25L * 1024L,
            downloadTimestamp = 2000L,
            isComplete = true
        )

        repo.saveRegion(region2)
        assertEquals(2, repo.regions.value.size)

        // Remove first region
        val removed = repo.removeRegion("reg_1")
        assertTrue(removed)
        assertEquals(1, repo.regions.value.size)
        assertEquals("Suburbs Safe Zone", repo.regions.value.first().name)

        // Clear all
        repo.clearAll()
        assertTrue(repo.regions.value.isEmpty())
    }

    @Test
    fun testRegionPersistenceAcrossRepositoryInstances() = runTest {
        val prefs = FakeSharedPreferences()
        val context = FakeContext(prefs)

        val repo1 = PersistentOfflineRegionRepository(context)
        val testRegion = SavedOfflineRegion(
            id = "persist_test",
            name = "City Park",
            north = 37.77,
            east = -122.41,
            south = 37.75,
            west = -122.43,
            minZoom = 13,
            maxZoom = 16,
            tileCount = 312,
            sizeBytes = 312L * 25L * 1024L,
            downloadTimestamp = 5000L,
            isComplete = true
        )
        repo1.saveRegion(testRegion)

        // Instantiate second repository mimicking app restart
        val repo2 = PersistentOfflineRegionRepository(context)
        assertEquals(1, repo2.regions.value.size)
        assertEquals("City Park", repo2.regions.value.first().name)
        assertEquals("persist_test", repo2.regions.value.first().id)
        assertEquals(312, repo2.regions.value.first().tileCount)
    }
}
