package com.livetracker.controller.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleSignInManagerTest {

    @Test
    fun formatDisplayNameFromEmail_formatsCorrectly() {
        assertEquals("Sabik", GoogleSignInManager.formatDisplayNameFromEmail("sabik123344@gmail.com"))
        assertEquals("John Doe", GoogleSignInManager.formatDisplayNameFromEmail("john.doe@gmail.com"))
        assertEquals("Alice Smith", GoogleSignInManager.formatDisplayNameFromEmail("alice_smith@domain.com"))
        assertEquals("Bob Jones", GoogleSignInManager.formatDisplayNameFromEmail("bob-jones@domain.org"))
        assertEquals("12345", GoogleSignInManager.formatDisplayNameFromEmail("12345@gmail.com"))
    }

    @Test
    fun formatDisplayNameFromEmail_withComplexName() {
        assertEquals("Admin Super User", GoogleSignInManager.formatDisplayNameFromEmail("admin.super_user@live.com"))
        assertEquals("Jane", GoogleSignInManager.formatDisplayNameFromEmail("jane999@yahoo.com"))
    }
}
