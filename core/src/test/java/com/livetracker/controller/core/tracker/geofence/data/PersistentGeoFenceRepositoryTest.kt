package com.livetracker.controller.core.tracker.geofence.data

import android.content.SharedPreferences
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceType
import com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentGeoFenceRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
    }

    private fun createRepository(prefs: SharedPreferences = fakePrefs): PersistentGeoFenceRepository {
        return PersistentGeoFenceRepository(prefs, testDispatcher)
    }

    @Test
    fun `persistence across repository instances using the same backing preferences`() = runTest(testDispatcher) {
        val repo1 = createRepository()

        val polygon = GeoFence.createPolygon(
            id = "p-1",
            name = "Zone Alpha",
            vertices = listOf(
                GeoPoint2D(10.0, 10.0),
                GeoPoint2D(10.0, 20.0),
                GeoPoint2D(20.0, 10.0)
            ),
            colorArgb = 0xFF10B981.toInt()
        )
        val circle = GeoFence.createCircle(
            id = "c-1",
            name = "Zone Beta",
            center = GeoPoint2D(35.0, 45.0),
            radiusMeters = 500.0,
            colorArgb = 0xFF2563EB.toInt()
        )

        repo1.addGeoFence(polygon)
        repo1.addGeoFence(circle)

        // Verify JSON string was written to backing preferences
        val storedJson = fakePrefs.getString(PersistentGeoFenceRepository.KEY_GEOFENCES, null)
        assertNotNull(storedJson)
        assertTrue(storedJson!!.contains("Zone Alpha"))
        assertTrue(storedJson.contains("Zone Beta"))

        // Simulate app restart: create completely new repository instance with same preferences
        val repo2 = createRepository()
        val loaded = repo2.getGeoFences()

        assertEquals(2, loaded.size)
        val loadedPoly = loaded.first { it.id == "p-1" }
        assertEquals("Zone Alpha", loadedPoly.name)
        assertEquals(GeoFenceType.POLYGON, loadedPoly.type)
        assertEquals(3, loadedPoly.vertices.size)

        val loadedCircle = loaded.first { it.id == "c-1" }
        assertEquals("Zone Beta", loadedCircle.name)
        assertEquals(GeoFenceType.CIRCLE, loadedCircle.type)
        assertEquals(GeoPoint2D(35.0, 45.0), loadedCircle.center)
        assertEquals(500.0, loadedCircle.radiusMeters!!, 0.001)
    }

    @Test
    fun `mixed polygon and circle list serialization and deserialization`() = runTest(testDispatcher) {
        val repo = createRepository()

        val poly = GeoFence.createPolygon(
            id = "poly-depot",
            name = "Depot Polygon",
            vertices = listOf(
                GeoPoint2D(50.0, 10.0),
                GeoPoint2D(50.0, 11.0),
                GeoPoint2D(51.0, 10.5)
            ),
            colorArgb = 0xFF123456.toInt()
        )
        val circle = GeoFence.createCircle(
            id = "circle-silo",
            name = "Silo Perimeter",
            center = GeoPoint2D(52.0, 12.0),
            radiusMeters = 300.0,
            colorArgb = 0xFF654321.toInt()
        )

        repo.addGeoFence(poly)
        repo.addGeoFence(circle)

        val readRepo = createRepository()
        val fences = readRepo.getGeoFences()
        assertEquals(2, fences.size)

        val polyResult = fences.first { it.id == "poly-depot" }
        assertTrue(polyResult.isPolygon)
        assertFalse(polyResult.isCircle)
        assertEquals(3, polyResult.vertices.size)

        val circleResult = fences.first { it.id == "circle-silo" }
        assertTrue(circleResult.isCircle)
        assertFalse(circleResult.isPolygon)
        assertEquals(GeoPoint2D(52.0, 12.0), circleResult.center)
        assertEquals(300.0, circleResult.radiusMeters!!, 0.001)
    }

    @Test
    fun `corrupted JSON followed by attempted add does not silently overwrite original payload`() = runTest(testDispatcher) {
        val corruptPayload = """{"invalid_key": "not a list", [malformed json}"""
        fakePrefs.edit()
            .putString(PersistentGeoFenceRepository.KEY_GEOFENCES, corruptPayload)
            .apply()

        val repo = createRepository()

        val newFence = GeoFence.createCircle(
            id = "recovery-attempt-1",
            name = "Should Not Overwrite",
            center = GeoPoint2D(0.0, 0.0),
            radiusMeters = 100.0,
            colorArgb = 0
        )

        // Attempting to add must throw IllegalStateException and block the write
        assertThrows(IllegalStateException::class.java) {
            runTest(testDispatcher) {
                repo.addGeoFence(newFence)
            }
        }

        // Verify the original stored payload in KEY_GEOFENCES was NOT overwritten
        val actualStored = fakePrefs.getString(PersistentGeoFenceRepository.KEY_GEOFENCES, null)
        assertEquals(corruptPayload, actualStored)

        // Verify corrupted payload was backed up to KEY_GEOFENCES_BACKUP for disaster recovery
        val backupStored = fakePrefs.getString(PersistentGeoFenceRepository.KEY_GEOFENCES_BACKUP, null)
        assertEquals(corruptPayload, backupStored)

        // Verify repository flag records corruption state
        assertTrue(repo.isStorageCorrupted())
    }

    @Test
    fun `corrupted JSON followed by attempted remove does not silently overwrite original payload`() = runTest(testDispatcher) {
        val corruptPayload = "corrupted-raw-bytes-here"
        fakePrefs.edit()
            .putString(PersistentGeoFenceRepository.KEY_GEOFENCES, corruptPayload)
            .apply()

        val repo = createRepository()

        // Attempting to remove must throw IllegalStateException and block the write
        assertThrows(IllegalStateException::class.java) {
            runTest(testDispatcher) {
                repo.removeGeoFence("any-id")
            }
        }

        // Verify the original stored payload is completely intact
        val actualStored = fakePrefs.getString(PersistentGeoFenceRepository.KEY_GEOFENCES, null)
        assertEquals(corruptPayload, actualStored)
        assertTrue(repo.isStorageCorrupted())
    }

    @Test
    fun `corrupted JSON causes getGeoFences to fail instead of silently returning empty list`() = runTest(testDispatcher) {
        val corruptPayload = "[{\"id\": \"unclosed"
        fakePrefs.edit()
            .putString(PersistentGeoFenceRepository.KEY_GEOFENCES, corruptPayload)
            .apply()

        val repo = createRepository()

        // Must throw IllegalStateException rather than returning emptyList()
        assertThrows(IllegalStateException::class.java) {
            runTest(testDispatcher) {
                repo.getGeoFences()
            }
        }

        // Verify data was NOT overwritten
        assertEquals(corruptPayload, fakePrefs.getString(PersistentGeoFenceRepository.KEY_GEOFENCES, null))
    }

    @Test
    fun `legacy polygon data compatibility`() = runTest(testDispatcher) {
        val legacyJson = """
            [
              {
                "id": "legacy-poly-10",
                "name": "Old Saved Polygon",
                "vertices": [
                  {"latitude": 40.7128, "longitude": -74.0060},
                  {"latitude": 40.7150, "longitude": -74.0040},
                  {"latitude": 40.7100, "longitude": -74.0030}
                ],
                "colorArgb": -16711936,
                "isEnabled": true,
                "createdAt": 1700000000,
                "updatedAt": 1700001000
              }
            ]
        """.trimIndent()

        fakePrefs.edit()
            .putString(PersistentGeoFenceRepository.KEY_GEOFENCES, legacyJson)
            .apply()

        val repo = createRepository()
        val loaded = repo.getGeoFences()

        assertEquals(1, loaded.size)
        val legacyFence = loaded.first()
        assertEquals("legacy-poly-10", legacyFence.id)
        assertEquals("Old Saved Polygon", legacyFence.name)
        assertEquals(GeoFenceType.POLYGON, legacyFence.type)
        assertTrue(legacyFence.isPolygon)
        assertFalse(legacyFence.isCircle)
        assertNull(legacyFence.center)
        assertNull(legacyFence.radiusMeters)
        assertEquals(3, legacyFence.vertices.size)

        // Adding a new circular geofence to legacy storage persists both legacy polygon and new circle
        val newCircle = GeoFence.createCircle(
            id = "new-circle-20",
            name = "New Circular Zone",
            center = GeoPoint2D(40.7200, -74.0000),
            radiusMeters = 400.0,
            colorArgb = 0xFF2563EB.toInt()
        )
        repo.addGeoFence(newCircle)

        // Re-read from a fresh repository instance
        val reloaded = createRepository().getGeoFences()
        assertEquals(2, reloaded.size)
        assertTrue(reloaded.any { it.id == "legacy-poly-10" && it.isPolygon })
        assertTrue(reloaded.any { it.id == "new-circle-20" && it.isCircle })
    }

    @Test
    fun `empty preferences returns empty list without error`() = runTest(testDispatcher) {
        val repo = createRepository()
        val fences = repo.getGeoFences()
        assertTrue(fences.isEmpty())
        assertFalse(repo.isStorageCorrupted())

        // Can add to empty storage
        val fence = GeoFence.createCircle(
            id = "first-fence",
            name = "Initial Zone",
            center = GeoPoint2D(1.0, 1.0),
            radiusMeters = 100.0,
            colorArgb = 0
        )
        repo.addGeoFence(fence)

        assertEquals(1, createRepository().getGeoFences().size)
    }

    @Test
    fun `removeGeoFence persists removals across repository instances`() = runTest(testDispatcher) {
        val repo1 = createRepository()
        val fence1 = GeoFence.createCircle("f-1", "Zone 1", GeoPoint2D(0.0, 0.0), 100.0, 0)
        val fence2 = GeoFence.createCircle("f-2", "Zone 2", GeoPoint2D(1.0, 1.0), 200.0, 0)

        repo1.addGeoFence(fence1)
        repo1.addGeoFence(fence2)
        assertEquals(2, repo1.getGeoFences().size)

        repo1.removeGeoFence("f-1")
        assertEquals(1, repo1.getGeoFences().size)

        val repo2 = createRepository()
        val remaining = repo2.getGeoFences()
        assertEquals(1, remaining.size)
        assertEquals("f-2", remaining.first().id)
    }

    @Test
    fun `updating existing geofence updates in place across repository instances`() = runTest(testDispatcher) {
        val repo1 = createRepository()
        val initialCircle = GeoFence.createCircle("f-edit", "Initial Name", GeoPoint2D(10.0, 10.0), 100.0, 0)
        repo1.addGeoFence(initialCircle)

        val updatedCircle = GeoFence.createCircle("f-edit", "Updated Name", GeoPoint2D(10.0, 10.0), 350.0, 0)
        repo1.addGeoFence(updatedCircle)

        val repo2 = createRepository()
        val loaded = repo2.getGeoFences()
        assertEquals(1, loaded.size)
        assertEquals("Updated Name", loaded.first().name)
        assertEquals(350.0, loaded.first().radiusMeters!!, 0.001)
    }
}

/**
 * Lightweight, fully thread-safe in-memory [SharedPreferences] implementation for JVM unit tests.
 */
private class FakeSharedPreferences : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? {
        return (data[key] as? String) ?: defValue
    }

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return (data[key] as? MutableSet<String>) ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class FakeEditor : SharedPreferences.Editor {
        private val staging = mutableMapOf<String, Any?>()
        private val toRemove = mutableSetOf<String>()
        private var clearFlag = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) staging[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) staging[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) toRemove.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearFlag = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearFlag) {
                data.clear()
            }
            toRemove.forEach { data.remove(it) }
            data.putAll(staging)
        }
    }
}
