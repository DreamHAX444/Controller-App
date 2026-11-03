package com.livetracker.controller.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceNameFormatterTest {

    @Test
    fun `formats emulator model string into short pixel name with suffix`() {
        val name = "Google sdk_gphone16k_x86_64 (A7A0)"
        val formatted = DeviceNameFormatter.formatFriendlyName(name, name)
        assertEquals("Pixel (A7A0)", formatted)
    }

    @Test
    fun `formats emulator without suffix into pixel emulator`() {
        val name = "Google sdk_gphone_x86"
        val formatted = DeviceNameFormatter.formatFriendlyName(name, name)
        assertEquals("Pixel Emulator", formatted)
    }

    @Test
    fun `formats samsung model into short marketing name with suffix`() {
        val name = "Samsung SM-A156M (497E)"
        val formatted = DeviceNameFormatter.formatFriendlyName(name, "dev497e")
        assertEquals("Samsung A15 (497E)", formatted)
    }

    @Test
    fun `formats tecno model deduplicating repeated brand words`() {
        val name = "TECNO TECNO KL4 (86D6)"
        val formatted = DeviceNameFormatter.formatFriendlyName(name, "dev86d6")
        assertEquals("Tecno KL4 (86D6)", formatted)
    }

    @Test
    fun `formats uuid deviceId into short uppercase id`() {
        val uuid = "c3d4e5f6-7890-4123-abcd-ef0123456789"
        val formatted = DeviceNameFormatter.formatFriendlyName("", uuid)
        assertEquals("C3D4E5F6", formatted)
    }

    @Test
    fun `preserves clean custom friendly name`() {
        val name = "Alpha Patrol"
        val formatted = DeviceNameFormatter.formatFriendlyName(name, "dev123")
        assertEquals("Alpha Patrol", formatted)
    }

    @Test
    fun `truncates oversized names gracefully with ellipsis`() {
        val name = "Very Long Device Name Exceeding Limit"
        val formatted = DeviceNameFormatter.formatFriendlyName(name, "dev123", maxLength = 15)
        assertEquals("Very Long Devi…", formatted)
    }

    @Test
    fun `formatShortId extracts tag from parentheses if present`() {
        assertEquals("A7A0", DeviceNameFormatter.formatShortId("Google sdk_gphone16k_x86_64 (A7A0)"))
    }

    @Test
    fun `formatShortModel cleans kernel strings and strips tags`() {
        assertEquals("Pixel Emulator", DeviceNameFormatter.formatShortModel("Google sdk_gphone16k_x86_64 (A7A0)"))
        assertEquals("SM-A156M", DeviceNameFormatter.formatShortModel("Samsung SM-A156M (497E)"))
        assertEquals("Tecno KL4", DeviceNameFormatter.formatShortModel("TECNO TECNO KL4 (86D6)"))
    }
}
