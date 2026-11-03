package com.livetracker.controller.core.tracker.location.data

import com.livetracker.controller.core.tracker.domain.TrackerId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocationDataSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize valid json matching tracker schema`() {
        val jsonString = """
            {
                "device_id": "Pixel_7_Tracker",
                "latitude": 37.7749,
                "longitude": -122.4194,
                "altitude": 15.5,
                "speed": 12.3,
                "accuracy": 4.2,
                "bearing": 185.0,
                "pitch": -2.1,
                "roll": 0.5,
                "pressure": 1013.25,
                "battery_level": 0.85,
                "charging": true,
                "signal_dbm": -75,
                "network_type": "5G",
                "created_at": "2026-10-15T12:00:00.000Z"
            }
        """.trimIndent()

        val data = json.decodeFromString<LocationData>(jsonString)
        assertEquals("Pixel_7_Tracker", data.deviceId)
        assertEquals(37.7749, data.latitude, 0.0001)
        assertEquals(-122.4194, data.longitude, 0.0001)
        assertEquals(15.5, data.altitude!!, 0.001)
        assertEquals(12.3f, data.speed!!, 0.001f)
        assertEquals(4.2f, data.accuracy!!, 0.001f)
        assertEquals(185.0f, data.bearing!!, 0.001f)
        assertEquals(0.85f, data.batteryLevel!!, 0.001f)
        assertEquals(true, data.charging)
        assertEquals("5G", data.networkType)
        assertTrue(data.isValidCoordinates())
    }

    @Test
    fun `deserialize minimal json with optional fields null`() {
        val jsonString = """
            {
                "device_id": "Tracker_Min",
                "latitude": 40.7128,
                "longitude": -74.0060,
                "accuracy": 8.0,
                "bearing": 0.0,
                "created_at": "2026-10-15T12:00:00.000Z"
            }
        """.trimIndent()

        val data = json.decodeFromString<LocationData>(jsonString)
        assertEquals("Tracker_Min", data.deviceId)
        assertEquals(40.7128, data.latitude, 0.0001)
        assertEquals(-74.0060, data.longitude, 0.0001)
        assertNull(data.altitude)
        assertNull(data.speed)
        assertNull(data.batteryLevel)
        assertTrue(data.isValidCoordinates())
    }

    @Test
    fun `isValidCoordinates rejects out of bound coordinates`() {
        val valid = LocationData("dev", 45.0, 90.0, accuracy = 5f, createdAt = "2026-10-15T00:00:00Z")
        assertTrue(valid.isValidCoordinates())

        val highLat = valid.copy(latitude = 90.1)
        assertFalse(highLat.isValidCoordinates())

        val lowLat = valid.copy(latitude = -90.1)
        assertFalse(lowLat.isValidCoordinates())

        val highLon = valid.copy(longitude = 180.1)
        assertFalse(highLon.isValidCoordinates())

        val lowLon = valid.copy(longitude = -180.1)
        assertFalse(lowLon.isValidCoordinates())

        val negAcc = valid.copy(accuracy = -0.5f)
        assertFalse(negAcc.isValidCoordinates())

        val nanLat = valid.copy(latitude = Double.NaN)
        assertFalse(nanLat.isValidCoordinates())

        val nanLon = valid.copy(longitude = Double.NaN)
        assertFalse(nanLon.isValidCoordinates())
    }

    @Test
    fun `parseTimestamp correctly converts ISO-8601 UTC to epoch millis`() {
        val data = LocationData("dev", 10.0, 10.0, createdAt = "2026-10-15T12:30:00.000Z")
        val expected = Instant.parse("2026-10-15T12:30:00.000Z").toEpochMilli()
        assertEquals(expected, data.parseTimestamp())
    }

    @Test
    fun `parseTimestamp falls back to current time on malformed string`() {
        val data = LocationData("dev", 10.0, 10.0, createdAt = "not-a-valid-date")
        val now = System.currentTimeMillis()
        val parsed = data.parseTimestamp()
        assertTrue(parsed in (now - 1000)..(now + 1000))
    }

    @Test
    fun `toLocationUpdatedEvent maps attributes accurately`() {
        val data = LocationData(
            deviceId = "dev_123",
            latitude = 51.5074,
            longitude = -0.1278,
            accuracy = 3.5f,
            bearing = 90f,
            speed = 5.0f,
            createdAt = "2026-10-15T10:00:00.000Z"
        )
        val event = data.toLocationUpdatedEvent(eventId = "test_event")

        assertEquals("test_event", event.eventId)
        assertEquals(TrackerId("dev_123"), event.deviceId)
        assertEquals(51.5074, event.latitude, 0.0001)
        assertEquals(-0.1278, event.longitude, 0.0001)
        assertEquals(3.5f, event.accuracy, 0.001f)
        assertEquals(90f, event.bearing)
        assertEquals(5.0f, event.speed)
        assertEquals(Instant.parse("2026-10-15T10:00:00.000Z").toEpochMilli(), event.timestamp)
    }

    @Test
    fun `roundtrip serialization preserves essential fields`() {
        val original = LocationData(
            deviceId = "test-tracker",
            latitude = 12.3456,
            longitude = 78.9101,
            altitude = 100.0,
            speed = 22.0f,
            accuracy = 4.5f,
            bearing = 45.0f,
            batteryLevel = 0.92f,
            charging = false,
            signalDbm = -60,
            networkType = "WIFI",
            createdAt = "2026-10-15T14:20:00.000Z"
        )
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<LocationData>(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun `deserialize throws on malformed json syntax`() {
        val malformedStrings = listOf(
            "not a json object",
            "{",
            """{"device_id": "dev1", "latitude": }""",
            """{"device_id": "dev1", "latitude": "not_a_number", "longitude": 10.0, "created_at": "2026-10-15T00:00:00Z"}""",
            ""
        )
        for (badJson in malformedStrings) {
            try {
                json.decodeFromString<LocationData>(badJson)
                fail("Expected SerializationException or IllegalArgumentException for: $badJson")
            } catch (e: Exception) {
                assertTrue(
                    "Expected SerializationException or IllegalArgumentException, got ${e.javaClass.simpleName}",
                    e is kotlinx.serialization.SerializationException || e is IllegalArgumentException
                )
            }
        }
    }

    @Test
    fun `deserialize throws on missing required fields`() {
        // Missing device_id
        val missingDeviceId = """{"latitude": 10.0, "longitude": 20.0, "created_at": "2026-10-15T00:00:00Z"}"""
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<LocationData>(missingDeviceId)
        }

        // Missing latitude
        val missingLat = """{"device_id": "dev1", "longitude": 20.0, "created_at": "2026-10-15T00:00:00Z"}"""
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<LocationData>(missingLat)
        }

        // Missing longitude
        val missingLon = """{"device_id": "dev1", "latitude": 10.0, "created_at": "2026-10-15T00:00:00Z"}"""
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<LocationData>(missingLon)
        }

        // Missing created_at
        val missingCreatedAt = """{"device_id": "dev1", "latitude": 10.0, "longitude": 20.0}"""
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            json.decodeFromString<LocationData>(missingCreatedAt)
        }
    }

    @Test
    fun `deserialize safely ignores unknown extra keys`() {
        val jsonWithExtras = """
            {
                "device_id": "Tracker_Future",
                "latitude": 45.0,
                "longitude": 9.0,
                "accuracy": 5.0,
                "created_at": "2026-10-15T12:00:00.000Z",
                "unsupported_metric_xyz": 42,
                "future_firmware_version": "v9.9.9",
                "nested_debug": { "sub_key": "val" }
            }
        """.trimIndent()

        val data = json.decodeFromString<LocationData>(jsonWithExtras)
        assertEquals("Tracker_Future", data.deviceId)
        assertEquals(45.0, data.latitude, 0.0001)
        assertEquals(9.0, data.longitude, 0.0001)
        assertEquals(5.0f, data.accuracy!!, 0.001f)
        assertTrue(data.isValidCoordinates())
    }

    @Test
    fun `isValidCoordinates accepts valid boundary coordinates`() {
        // Exact lat/lon limits and positive accuracy
        assertTrue(LocationData("dev", 90.0, 180.0, accuracy = 0.001f, createdAt = "2026-10-15T00:00:00Z").isValidCoordinates())
        assertTrue(LocationData("dev", -90.0, -180.0, accuracy = 0.001f, createdAt = "2026-10-15T00:00:00Z").isValidCoordinates())
        assertTrue(LocationData("dev", 0.0, 0.0, accuracy = 5.0f, createdAt = "2026-10-15T00:00:00Z").isValidCoordinates())

        // Accuracy of 0 or negative is rejected
        assertFalse(LocationData("dev", 0.0, 0.0, accuracy = 0f, createdAt = "2026-10-15T00:00:00Z").isValidCoordinates())
        assertFalse(LocationData("dev", 0.0, 0.0, accuracy = -0.5f, createdAt = "2026-10-15T00:00:00Z").isValidCoordinates())
    }

    @Test
    fun `isValidCoordinates rejects infinite and extreme values`() {
        val base = LocationData("dev", 0.0, 0.0, accuracy = 5f, createdAt = "2026-10-15T00:00:00Z")
        assertFalse(base.copy(latitude = Double.POSITIVE_INFINITY).isValidCoordinates())
        assertFalse(base.copy(latitude = Double.NEGATIVE_INFINITY).isValidCoordinates())
        assertFalse(base.copy(longitude = Double.POSITIVE_INFINITY).isValidCoordinates())
        assertFalse(base.copy(longitude = Double.NEGATIVE_INFINITY).isValidCoordinates())

        // Just outside boundary
        assertFalse(base.copy(latitude = 90.000001).isValidCoordinates())
        assertFalse(base.copy(latitude = -90.000001).isValidCoordinates())
        assertFalse(base.copy(longitude = 180.000001).isValidCoordinates())
        assertFalse(base.copy(longitude = -180.000001).isValidCoordinates())
        assertFalse(base.copy(accuracy = -0.0001f).isValidCoordinates())
        assertFalse(base.copy(accuracy = Float.NaN).isValidCoordinates())
        assertFalse(base.copy(accuracy = Float.POSITIVE_INFINITY).isValidCoordinates())
        assertFalse(base.copy(accuracy = Float.NEGATIVE_INFINITY).isValidCoordinates())
    }

    @Test
    fun `parseTimestamp handles varied ISO-8601 UTC formats and trimmed inputs`() {
        val expected = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()

        // Standard with Z
        assertEquals(expected, LocationData(deviceId = "dev", latitude = 0.0, longitude = 0.0, createdAt = "2026-10-15T12:00:00Z").parseTimestamp())
        assertEquals(expected, LocationData(deviceId = "dev", latitude = 0.0, longitude = 0.0, createdAt = "2026-10-15T12:00:00.000Z").parseTimestamp())

        // Offset format
        assertEquals(expected, LocationData(deviceId = "dev", latitude = 0.0, longitude = 0.0, createdAt = "2026-10-15T12:00:00+00:00").parseTimestamp())

        // Without timezone, assumes UTC
        assertEquals(expected, LocationData(deviceId = "dev", latitude = 0.0, longitude = 0.0, createdAt = "2026-10-15T12:00:00").parseTimestamp())

        // Trimmed whitespace
        assertEquals(expected, LocationData(deviceId = "dev", latitude = 0.0, longitude = 0.0, createdAt = "  2026-10-15T12:00:00Z  ").parseTimestamp())

        // Epoch millis string fallback
        assertEquals(expected, LocationData(deviceId = "dev", latitude = 0.0, longitude = 0.0, createdAt = "$expected").parseTimestamp())
    }
}

