package com.livetracker.controller.core.auth.domain

import com.livetracker.controller.core.auth.data.model.UserFeaturePermissionRecord
import org.junit.Assert.*
import org.junit.Test

class FeaturePermissionMapperTest {

    @Test
    fun `toDatabaseFeature maps LOCATION to LOCATION`() {
        assertEquals("LOCATION", ServicePermission.LOCATION.toDatabaseFeature())
    }

    @Test
    fun `toDatabaseFeature maps CAMERA to CAMERA`() {
        assertEquals("CAMERA", ServicePermission.CAMERA.toDatabaseFeature())
    }

    @Test
    fun `toDatabaseFeature maps audio permissions to MIC`() {
        assertEquals("MIC", ServicePermission.MICROPHONE_AUDIO.toDatabaseFeature())
        assertEquals("MIC", ServicePermission.DEVICE_AUDIO.toDatabaseFeature())
    }

    @Test
    fun `toDatabaseFeature maps SCREEN_CAPTURE to SCREEN`() {
        assertEquals("SCREEN", ServicePermission.SCREEN_CAPTURE.toDatabaseFeature())
    }

    @Test
    fun `toDatabaseFeature maps file permissions to FILES`() {
        assertEquals("FILES", ServicePermission.FILE_READ.toDatabaseFeature())
        assertEquals("FILES", ServicePermission.FILE_WRITE.toDatabaseFeature())
        assertEquals("FILES", ServicePermission.FILE_DELETE.toDatabaseFeature())
        assertEquals("FILES", ServicePermission.FILE_RENAME.toDatabaseFeature())
        assertEquals("FILES", ServicePermission.FILE_TRANSFER.toDatabaseFeature())
    }

    @Test
    fun `toServicePermissions maps database features to expected sets`() {
        assertEquals(setOf(ServicePermission.LOCATION), "LOCATION".toServicePermissions())
        assertEquals(setOf(ServicePermission.CAMERA), "CAMERA".toServicePermissions())
        assertEquals(
            setOf(ServicePermission.MICROPHONE_AUDIO, ServicePermission.DEVICE_AUDIO),
            "MIC".toServicePermissions()
        )
        assertEquals(setOf(ServicePermission.SCREEN_CAPTURE), "SCREEN".toServicePermissions())
        assertEquals(
            setOf(
                ServicePermission.FILE_READ,
                ServicePermission.FILE_WRITE,
                ServicePermission.FILE_DELETE,
                ServicePermission.FILE_RENAME,
                ServicePermission.FILE_TRANSFER
            ),
            "FILES".toServicePermissions()
        )
        assertEquals(emptySet<ServicePermission>(), "COMMANDS".toServicePermissions())
    }

    @Test
    fun `toServicePermissions handles whitespace and case insensitivity`() {
        assertEquals(setOf(ServicePermission.LOCATION), "  location  ".toServicePermissions())
        assertEquals(setOf(ServicePermission.CAMERA), "Camera".toServicePermissions())
        assertEquals(
            setOf(ServicePermission.MICROPHONE_AUDIO, ServicePermission.DEVICE_AUDIO),
            "mic".toServicePermissions()
        )
    }

    @Test
    fun `isValidDatabaseFeature accurately validates supported features`() {
        assertTrue(FeaturePermissionMapper.isValidDatabaseFeature("LOCATION"))
        assertTrue(FeaturePermissionMapper.isValidDatabaseFeature("CAMERA"))
        assertTrue(FeaturePermissionMapper.isValidDatabaseFeature("FILES"))
        assertTrue(FeaturePermissionMapper.isValidDatabaseFeature("MIC"))
        assertTrue(FeaturePermissionMapper.isValidDatabaseFeature("SCREEN"))
        assertTrue(FeaturePermissionMapper.isValidDatabaseFeature("COMMANDS"))

        assertFalse(FeaturePermissionMapper.isValidDatabaseFeature("UNKNOWN"))
        assertFalse(FeaturePermissionMapper.isValidDatabaseFeature(""))
        assertFalse(FeaturePermissionMapper.isValidDatabaseFeature(null))
    }

    @Test
    fun `UserFeaturePermissionRecord servicePermissions helper unfolds correctly`() {
        val record = UserFeaturePermissionRecord(
            id = 1L,
            assignmentId = 100L,
            deviceId = "dev-1",
            feature = "MIC",
            isAllowed = true
        )
        assertEquals(
            setOf(ServicePermission.MICROPHONE_AUDIO, ServicePermission.DEVICE_AUDIO),
            record.servicePermissions
        )
    }
}
