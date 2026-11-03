package com.livetracker.controller.ui.screens.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAssignmentValidationTest {

    private val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private fun isValidUuid(input: String): Boolean {
        return input.trim().matches(uuidRegex)
    }

    @Test
    fun `valid lowercase UUID passes validation`() {
        assertTrue(isValidUuid("c3d4e5f6-7890-4123-abcd-ef0123456789"))
    }

    @Test
    fun `valid uppercase UUID passes validation`() {
        assertTrue(isValidUuid("C3D4E5F6-7890-4123-ABCD-EF0123456789"))
    }

    @Test
    fun `valid UUID with leading and trailing whitespace passes after trim`() {
        assertTrue(isValidUuid("  c3d4e5f6-7890-4123-abcd-ef0123456789  "))
    }

    @Test
    fun `empty and blank string fails validation`() {
        assertFalse(isValidUuid(""))
        assertFalse(isValidUuid("   "))
    }

    @Test
    fun `malformed UUIDs fail validation`() {
        // Missing hyphens
        assertFalse(isValidUuid("c3d4e5f678904123abcdef0123456789"))
        // Invalid hex character 'g'
        assertFalse(isValidUuid("g3d4e5f6-7890-4123-abcd-ef0123456789"))
        // Too short
        assertFalse(isValidUuid("c3d4e5f6-7890-4123-abcd"))
        // Too long
        assertFalse(isValidUuid("c3d4e5f6-7890-4123-abcd-ef01234567890000"))
        // Wrong group lengths
        assertFalse(isValidUuid("c3d4e5f-67890-4123-abcd-ef0123456789"))
    }

    @Test
    fun `confirm button enablement logic with manual and dropdown selection`() {
        val selectedUserId = "user-123"
        val validManualUuid = "00000000-0000-0000-0000-000000000000"
        val invalidManualUuid = "not-a-uuid"

        // Manual mode enabled only if UUID is valid
        var useManualInput = true
        var isManualUuidValid = isValidUuid(validManualUuid)
        var isEnabled = (useManualInput && isManualUuidValid) || (!useManualInput && selectedUserId.isNotBlank())
        assertTrue(isEnabled)

        isManualUuidValid = isValidUuid(invalidManualUuid)
        isEnabled = (useManualInput && isManualUuidValid) || (!useManualInput && selectedUserId.isNotBlank())
        assertFalse(isEnabled)

        // Dropdown mode enabled if selectedUserId is not blank
        useManualInput = false
        isEnabled = (useManualInput && isManualUuidValid) || (!useManualInput && selectedUserId.isNotBlank())
        assertTrue(isEnabled)

        val blankUserId = ""
        isEnabled = (useManualInput && isManualUuidValid) || (!useManualInput && blankUserId.isNotBlank())
        assertFalse(isEnabled)
    }
}
