package com.livetracker.controller.main

import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.DevicePermission
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.navigation.Destination
import com.livetracker.controller.navigation.adminDestinations
import com.livetracker.controller.navigation.primaryDestinations
import com.livetracker.controller.navigation.serviceDestinations
import com.livetracker.controller.navigation.systemDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceAppDrawerDestinationsTest {

    @Test
    fun primaryDestinations_containCoreOperationalViews() {
        val expected = listOf(
            Destination.Map,
            Destination.Devices,
            Destination.Alerts,
            Destination.Diagnostics
        )
        assertEquals(expected, primaryDestinations)
    }

    @Test
    fun secondaryDestinations_haveNoDuplicatesAndUniqueRoutes() {
        val secondary = serviceDestinations + adminDestinations + (systemDestinations.filter { it != Destination.Settings })
        val routes = secondary.map { it.route }
        assertEquals(routes.distinct().size, routes.size)
        assertFalse(secondary.contains(Destination.Settings))
    }

    @Test
    fun allNavigationDestinations_haveValidTitlesAndNonBlankRoutes() {
        val all = primaryDestinations + serviceDestinations + adminDestinations + systemDestinations
        for (dest in all) {
            assertTrue("Route must not be blank for ${dest.title}", dest.route.isNotBlank())
            assertTrue("Title must not be blank for ${dest.route}", dest.title.isNotBlank())
        }
    }

    @Test
    fun userWithLocationAndMic_onlySeesAudioAndUpdatesInSecondaryDestinations() {
        val user = User(
            id = "u_test_1",
            displayName = "Field Operative",
            role = Role.USER,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = listOf(
                DevicePermission(
                    deviceId = "tracker-1",
                    allowedServices = setOf(
                        ServicePermission.LOCATION,
                        ServicePermission.MICROPHONE_AUDIO,
                        ServicePermission.DEVICE_AUDIO
                    )
                )
            )
        )

        val secondary = computeSecondaryDestinations(user)
        val expected = listOf(Destination.Audio, Destination.Updates)
        assertEquals(expected, secondary)
        assertFalse("Standard user must NEVER see Destination.Users", secondary.contains(Destination.Users))
        assertFalse("Camera must not be visible without camera permission", secondary.contains(Destination.Camera))
        assertFalse("Files must not be visible without file permission", secondary.contains(Destination.Files))
        assertFalse("ScreenCapture must not be visible without screen permission", secondary.contains(Destination.ScreenCapture))

        val pillars = computePrimaryPillars(user.role)
        val expectedPillars = listOf(
            Destination.Map,
            Destination.Devices,
            Destination.Alerts,
            Destination.Settings
        )
        assertEquals(expectedPillars, pillars)
        assertFalse("Diagnostics must NOT be available to Role.USER", pillars.contains(Destination.Diagnostics))
    }

    @Test
    fun userWithZeroServicePermissions_onlySeesUpdatesInSecondaryDestinations() {
        val user = User(
            id = "u_test_empty",
            displayName = "Observer",
            role = Role.USER,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = listOf(
                DevicePermission(
                    deviceId = "tracker-1",
                    allowedServices = setOf(ServicePermission.LOCATION)
                )
            )
        )

        val secondary = computeSecondaryDestinations(user)
        assertEquals(listOf(Destination.Updates), secondary)
        assertFalse(secondary.contains(Destination.Users))
    }

    @Test
    fun visitorOrNullSession_failsClosedWithMinimalPillarsAndEmptySecondaryTools() {
        // Visitor
        val visitorUser = User(
            id = "v1",
            displayName = "Guest",
            role = Role.VISITOR,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        assertEquals(emptyList<Destination>(), computeSecondaryDestinations(visitorUser))
        assertEquals(listOf(Destination.Map, Destination.Settings), computePrimaryPillars(Role.VISITOR))

        // Null session (pre-auth / state transitions)
        assertEquals(emptyList<Destination>(), computeSecondaryDestinations(null))
        assertEquals(listOf(Destination.Map, Destination.Settings), computePrimaryPillars(null))
    }

    @Test
    fun adminAndOwner_seeFullFleetDestinationsAndUsersSection() {
        val adminUser = User(
            id = "admin_1",
            displayName = "Admin",
            role = Role.ADMIN,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        val adminSecondary = computeSecondaryDestinations(adminUser)
        assertTrue("Admin must have Destination.Users", adminSecondary.contains(Destination.Users))
        assertTrue("Admin must have Destination.Camera", adminSecondary.contains(Destination.Camera))
        assertTrue("Admin must have Destination.Audio", adminSecondary.contains(Destination.Audio))
        assertTrue("Admin must have Destination.ScreenCapture", adminSecondary.contains(Destination.ScreenCapture))
        assertTrue("Admin must have Destination.Files", adminSecondary.contains(Destination.Files))
        assertTrue("Admin must have Destination.Updates", adminSecondary.contains(Destination.Updates))

        val adminPillars = computePrimaryPillars(Role.ADMIN)
        assertTrue("Admin must have Destination.Diagnostics", adminPillars.contains(Destination.Diagnostics))

        val ownerUser = User(
            id = "owner_1",
            displayName = "Owner",
            role = Role.OWNER,
            accountStatus = AccountStatus.ACTIVE,
            devicePermissions = emptyList()
        )
        val ownerSecondary = computeSecondaryDestinations(ownerUser)
        assertTrue("Owner must have Destination.Users", ownerSecondary.contains(Destination.Users))
    }
}
