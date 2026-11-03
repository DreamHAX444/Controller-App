package com.livetracker.controller.core.tracker.geofence.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoFenceTest {

    @Test
    fun `createPolygon creates valid polygon geofence`() {
        val vertices = listOf(
            GeoPoint2D(37.7749, -122.4194),
            GeoPoint2D(37.7750, -122.4180),
            GeoPoint2D(37.7740, -122.4185)
        )
        val fence = GeoFence.createPolygon(
            id = "poly-1",
            name = "Mission Zone",
            vertices = vertices,
            colorArgb = 0xFF10B981.toInt()
        )

        assertEquals("poly-1", fence.id)
        assertEquals("Mission Zone", fence.name)
        assertEquals(GeoFenceType.POLYGON, fence.type)
        assertEquals(3, fence.vertices.size)
        assertTrue(fence.isPolygon)
        assertFalse(fence.isCircle)
        assertTrue(fence.isValid())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createPolygon throws when vertices less than 3`() {
        GeoFence.createPolygon(
            id = "poly-invalid",
            name = "Invalid Zone",
            vertices = listOf(GeoPoint2D(0.0, 0.0), GeoPoint2D(1.0, 1.0)),
            colorArgb = 0
        )
    }

    @Test
    fun `createCircle creates valid circular geofence`() {
        val center = GeoPoint2D(37.7749, -122.4194)
        val fence = GeoFence.createCircle(
            id = "circle-1",
            name = "Warehouse Safe Zone",
            center = center,
            radiusMeters = 250.0,
            colorArgb = 0xFF2563EB.toInt()
        )

        assertEquals("circle-1", fence.id)
        assertEquals("Warehouse Safe Zone", fence.name)
        assertEquals(GeoFenceType.CIRCLE, fence.type)
        assertEquals(center, fence.center)
        assertEquals(250.0, fence.radiusMeters!!, 0.001)
        assertTrue(fence.isCircle)
        assertFalse(fence.isPolygon)
        assertTrue(fence.isValid())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createCircle throws on negative radius`() {
        GeoFence.createCircle(
            id = "c-err-1",
            name = "Negative Radius",
            center = GeoPoint2D(10.0, 20.0),
            radiusMeters = -50.0,
            colorArgb = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createCircle throws on zero radius`() {
        GeoFence.createCircle(
            id = "c-err-2",
            name = "Zero Radius",
            center = GeoPoint2D(10.0, 20.0),
            radiusMeters = 0.0,
            colorArgb = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createCircle throws on infinite radius`() {
        GeoFence.createCircle(
            id = "c-err-3",
            name = "Infinite Radius",
            center = GeoPoint2D(10.0, 20.0),
            radiusMeters = Double.POSITIVE_INFINITY,
            colorArgb = 0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createCircle throws on invalid latitude`() {
        GeoFence.createCircle(
            id = "c-err-4",
            name = "Bad Latitude",
            center = GeoPoint2D(95.0, 20.0),
            radiusMeters = 100.0,
            colorArgb = 0
        )
    }

    @Test
    fun `backward compatibility - legacy JSON without type or circle fields deserializes as polygon`() {
        val legacyJson = """
            [
              {
                "id": "legacy-1",
                "name": "Old Polygon",
                "vertices": [
                  {"latitude": 10.0, "longitude": 20.0},
                  {"latitude": 10.0, "longitude": 21.0},
                  {"latitude": 11.0, "longitude": 20.5}
                ],
                "colorArgb": 12345,
                "isEnabled": true,
                "createdAt": 1000,
                "updatedAt": 2000
              }
            ]
        """.trimIndent()

        val decodedList = Json.decodeFromString<List<GeoFence>>(legacyJson)
        assertEquals(1, decodedList.size)
        val legacyFence = decodedList.first()
        assertEquals("legacy-1", legacyFence.id)
        assertEquals(GeoFenceType.POLYGON, legacyFence.type)
        assertEquals(3, legacyFence.vertices.size)
        assertNull(legacyFence.center)
        assertNull(legacyFence.radiusMeters)
        assertTrue(legacyFence.isPolygon)
        assertTrue(legacyFence.isValid())
    }

    @Test
    fun `roundtrip serialization preserves circle geofence`() {
        val circle = GeoFence.createCircle(
            id = "circle-persist",
            name = "Depot Zone",
            center = GeoPoint2D(51.5074, -0.1278),
            radiusMeters = 350.5,
            colorArgb = 0xFFF59E0B.toInt()
        )

        val json = Json.encodeToString(circle)
        val decoded = Json.decodeFromString<GeoFence>(json)

        assertEquals(circle.id, decoded.id)
        assertEquals(circle.name, decoded.name)
        assertEquals(GeoFenceType.CIRCLE, decoded.type)
        assertEquals(circle.center, decoded.center)
        assertEquals(350.5, decoded.radiusMeters!!, 0.001)
        assertTrue(decoded.isValid())
    }

    @Test
    fun `roundtrip serialization preserves mixed list of polygon and circle geofences`() {
        val polygon = GeoFence.createPolygon(
            id = "poly-mixed-1",
            name = "Harbor Polygon",
            vertices = listOf(
                GeoPoint2D(40.7000, -74.0100),
                GeoPoint2D(40.7050, -74.0050),
                GeoPoint2D(40.7000, -74.0000)
            ),
            colorArgb = 0xFF10B981.toInt()
        )
        val circle = GeoFence.createCircle(
            id = "circle-mixed-2",
            name = "Island Circle",
            center = GeoPoint2D(40.6892, -74.0445),
            radiusMeters = 750.0,
            colorArgb = 0xFF3B82F6.toInt()
        )

        val originalList = listOf(polygon, circle)
        val json = Json.encodeToString(originalList)
        val decodedList = Json.decodeFromString<List<GeoFence>>(json)

        assertEquals(2, decodedList.size)

        val decodedPoly = decodedList[0]
        assertEquals("poly-mixed-1", decodedPoly.id)
        assertEquals("Harbor Polygon", decodedPoly.name)
        assertEquals(GeoFenceType.POLYGON, decodedPoly.type)
        assertTrue(decodedPoly.isPolygon)
        assertFalse(decodedPoly.isCircle)
        assertEquals(3, decodedPoly.vertices.size)
        assertNull(decodedPoly.center)
        assertNull(decodedPoly.radiusMeters)

        val decodedCircle = decodedList[1]
        assertEquals("circle-mixed-2", decodedCircle.id)
        assertEquals("Island Circle", decodedCircle.name)
        assertEquals(GeoFenceType.CIRCLE, decodedCircle.type)
        assertTrue(decodedCircle.isCircle)
        assertFalse(decodedCircle.isPolygon)
        assertTrue(decodedCircle.vertices.isEmpty())
        assertNotNull(decodedCircle.center)
        assertEquals(40.6892, decodedCircle.center!!.latitude, 0.0001)
        assertEquals(-74.0445, decodedCircle.center!!.longitude, 0.0001)
        assertEquals(750.0, decodedCircle.radiusMeters!!, 0.001)
    }
}

