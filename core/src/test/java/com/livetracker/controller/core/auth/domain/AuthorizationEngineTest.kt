package com.livetracker.controller.core.auth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthorizationEngineTest {

    private lateinit var engine: AuthorizationEngine
    private val currentTime = 1000L

    @Before
    fun setup() {
        engine = DefaultAuthorizationEngine()
    }

    private fun createSession(
        user: User, 
        isAuthenticated: Boolean = true, 
        expiresAt: Long = 2000L,
        createdAt: Long = 1000L
    ): Session {
        return Session("session-123", user, isAuthenticated, createdAt, expiresAt)
    }

    private fun createUser(
        id: String,
        role: Role,
        devicePermissions: List<DevicePermission>,
        accountStatus: AccountStatus = AccountStatus.ACTIVE
    ): User {
        return User(id, "Test User", role, accountStatus, devicePermissions)
    }

    @Test
    fun `unauthenticated user yields NotAuthenticated`() {
        val user = createUser("u1", Role.USER, emptyList())
        val session = createSession(user, isAuthenticated = false)
        
        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.NotAuthenticated)
    }

    @Test
    fun `session invalidation (expired) yields NotAuthenticated`() {
        val user = createUser("u1", Role.USER, emptyList())
        val session = createSession(user, expiresAt = 500L) // Expired
        
        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.NotAuthenticated)
    }

    @Test
    fun `authenticated user without device access yields DeviceAccessDenied`() {
        val user = createUser("u1", Role.USER, emptyList())
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.DeviceAccessDenied)
    }

    @Test
    fun `user with device access but no service permission yields ServicePermissionDenied`() {
        val devicePermission = DevicePermission("device1", emptySet())
        val user = createUser("u1", Role.USER, listOf(devicePermission))
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.ServicePermissionDenied)
    }

    @Test
    fun `user with location permission yields location allowed`() {
        val devicePermission = DevicePermission("device1", setOf(ServicePermission.LOCATION))
        val user = createUser("u1", Role.USER, listOf(devicePermission))
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.Allowed)
    }

    @Test
    fun `user with location permission yields camera denied`() {
        val devicePermission = DevicePermission("device1", setOf(ServicePermission.LOCATION))
        val user = createUser("u1", Role.USER, listOf(devicePermission))
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.ServicePermissionDenied)
    }

    @Test
    fun `user with camera permission yields camera allowed`() {
        val devicePermission = DevicePermission("device1", setOf(ServicePermission.CAMERA))
        val user = createUser("u1", Role.USER, listOf(devicePermission))
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.Allowed)
    }

    @Test
    fun `user with multiple permissions yields correct services allowed`() {
        val devicePermission = DevicePermission("device1", setOf(ServicePermission.CAMERA, ServicePermission.FILE_READ))
        val user = createUser("u1", Role.USER, listOf(devicePermission))
        val session = createSession(user)
        
        val resultCamera = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertTrue(resultCamera is AuthorizationResult.Allowed)
        
        val resultFileRead = engine.authorize(session, "device1", ServicePermission.FILE_READ, Action.READ, currentTime)
        assertTrue(resultFileRead is AuthorizationResult.Allowed)
        
        val resultLocation = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(resultLocation is AuthorizationResult.ServicePermissionDenied)
    }

    @Test
    fun `admin authorization yields allowed for all services on authorized devices`() {
        // Admin must have explicit device access, but gains all services on that device
        val devicePermission = DevicePermission("device1", emptySet())
        val user = createUser("admin1", Role.ADMIN, listOf(devicePermission))
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.FILE_DELETE, Action.DELETE, currentTime)
        assertTrue(result is AuthorizationResult.Allowed)
    }
    
    @Test
    fun `admin without device access yields DeviceAccessDenied for sensitive operations`() {
        // Enforcing that admins cannot access sensitive operations without device assignment (Admin B2)
        val user = createUser("admin1", Role.ADMIN, emptyList())
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.DeviceAccessDenied)
    }

    @Test
    fun `invalid device yields InvalidRequest`() {
        val user = createUser("u1", Role.USER, emptyList())
        val session = createSession(user)
        
        val result = engine.authorize(session, null, ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.InvalidRequest)
        
        val resultEmpty = engine.authorize(session, "", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(resultEmpty is AuthorizationResult.InvalidRequest)
    }

    @Test
    fun `invalid service yields InvalidRequest`() {
        val user = createUser("u1", Role.USER, emptyList())
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", null, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.InvalidRequest)
    }

    @Test
    fun `deny by default behavior enforces rejection on unexpected conditions`() {
        val result = engine.authorize(null, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.NotAuthenticated)
    }
    
    @Test
    fun `deterministic permission precedence`() {
        // Precedence: NotAuthenticated -> InvalidRequest -> DeviceAccessDenied -> Allowed (Admin) -> Allowed/Denied (User)
        
        // 1. Unauthenticated beats InvalidRequest
        var result = engine.authorize(null, null, null, null, currentTime)
        assertTrue(result is AuthorizationResult.NotAuthenticated)
        
        // 2. InvalidRequest beats DeviceAccessDenied
        val session = createSession(createUser("u1", Role.USER, emptyList()))
        result = engine.authorize(session, null, ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.InvalidRequest)
        
        // 3. DeviceAccessDenied beats User/Admin checks on sensitive operations
        val adminSession = createSession(createUser("admin1", Role.ADMIN, emptyList()))
        result = engine.authorize(adminSession, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.DeviceAccessDenied)
    }

    @Test
    fun `owner authorization yields allowed for all services on authorized devices`() {
        val devicePermission = DevicePermission("device1", emptySet())
        val user = createUser("owner1", Role.OWNER, listOf(devicePermission))
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.Allowed)
    }

    @Test
    fun `owner without device access is allowed under Owner A1 policy`() {
        val user = createUser("owner1", Role.OWNER, emptyList())
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.Allowed)
    }

    @Test
    fun `visitor role unconditionally yields DeviceAccessDenied even if device permission is present`() {
        val devicePermission = DevicePermission("device1", setOf(ServicePermission.LOCATION, ServicePermission.CAMERA))
        val user = createUser("visitor1", Role.VISITOR, listOf(devicePermission))
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.DeviceAccessDenied)
    }

    // =========================================================================
    // PHASE 1: ROLE x ACCOUNT STATUS MATRIX & LIFECYCLE TESTS
    // =========================================================================

    @Test
    fun `pending account status yields AccountNotActive regardless of role or permissions`() {
        for (role in listOf(Role.OWNER, Role.ADMIN, Role.USER, Role.VISITOR)) {
            val devicePerm = DevicePermission("device1", setOf(ServicePermission.LOCATION), setOf(Action.READ))
            val user = createUser("u-pending", role, listOf(devicePerm), accountStatus = AccountStatus.PENDING)
            val session = createSession(user)

            val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
            assertTrue("Role $role with status PENDING must be rejected with AccountNotActive", 
                result is AuthorizationResult.AccountNotActive)
        }
    }

    @Test
    fun `disabled and suspended account statuses yield AccountNotActive across all roles`() {
        for (status in listOf(AccountStatus.DISABLED, AccountStatus.SUSPENDED, AccountStatus.UNKNOWN)) {
            for (role in Role.values()) {
                val devicePerm = DevicePermission("device1", setOf(ServicePermission.CAMERA), setOf(Action.READ))
                val user = createUser("u-blocked", role, listOf(devicePerm), accountStatus = status)
                val session = createSession(user)

                val result = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
                assertEquals("Role $role with status $status must yield AccountNotActive", 
                    AuthorizationResult.AccountNotActive, result)
            }
        }
    }

    // =========================================================================
    // PHASE 1: TEMPORAL PERMISSION EXPIRY & TIME BOUNDARY TESTS
    // =========================================================================

    @Test
    fun `device permission with future expiresAt is allowed`() {
        val futureExpiry = currentTime + 5000L
        val devicePerm = DevicePermission("device1", setOf(ServicePermission.LOCATION), setOf(Action.READ), expiresAt = futureExpiry)
        val user = createUser("u1", Role.USER, listOf(devicePerm))
        val session = createSession(user)

        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals(AuthorizationResult.Allowed, result)
    }

    @Test
    fun `device permission with exact currentTime boundary is expired and yields DeviceAccessDenied`() {
        val boundaryExpiry = currentTime // currentTime == expiresAt
        val devicePerm = DevicePermission("device1", setOf(ServicePermission.LOCATION), setOf(Action.READ), expiresAt = boundaryExpiry)
        val user = createUser("u1", Role.USER, listOf(devicePerm))
        val session = createSession(user)

        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals("Permission expiring at exact current time must be rejected as expired", 
            AuthorizationResult.DeviceAccessDenied, result)
    }

    @Test
    fun `device permission with past expiresAt is expired and yields DeviceAccessDenied`() {
        val pastExpiry = currentTime - 1L
        val devicePerm = DevicePermission("device1", setOf(ServicePermission.LOCATION), setOf(Action.READ), expiresAt = pastExpiry)
        val user = createUser("u1", Role.USER, listOf(devicePerm))
        val session = createSession(user)

        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals(AuthorizationResult.DeviceAccessDenied, result)
    }

    @Test
    fun `device permission with null expiresAt is unbounded and allowed`() {
        val devicePerm = DevicePermission("device1", setOf(ServicePermission.CAMERA), setOf(Action.READ), expiresAt = null)
        val user = createUser("u1", Role.USER, listOf(devicePerm))
        val session = createSession(user, expiresAt = Long.MAX_VALUE)

        val result = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime + 10000000L)
        assertEquals(AuthorizationResult.Allowed, result)
    }

    @Test
    fun `session expiry takes precedence over active device permission`() {
        val futurePermExpiry = currentTime + 5000L
        val devicePerm = DevicePermission("device1", setOf(ServicePermission.LOCATION), setOf(Action.READ), expiresAt = futurePermExpiry)
        val user = createUser("u1", Role.USER, listOf(devicePerm))
        val expiredSession = createSession(user, expiresAt = currentTime - 100L)

        val result = engine.authorize(expiredSession, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals("Session expiration must take precedence over valid device permission", 
            AuthorizationResult.NotAuthenticated, result)
    }

    // =========================================================================
    // PHASE 1: ACTION ENFORCEMENT & CAPABILITY MATRIX TESTS
    // =========================================================================

    @Test
    fun `user with service grant but lacking action grant yields ServicePermissionDenied`() {
        // User has CAMERA service, but only READ action granted
        val devicePerm = DevicePermission("device1", setOf(ServicePermission.CAMERA), allowedActions = setOf(Action.READ))
        val user = createUser("u1", Role.USER, listOf(devicePerm))
        val session = createSession(user)

        // READ is allowed
        val readResult = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertEquals(AuthorizationResult.Allowed, readResult)

        // WRITE is denied
        val writeResult = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.WRITE, currentTime)
        assertEquals(AuthorizationResult.ServicePermissionDenied, writeResult)

        // DELETE is denied
        val deleteResult = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.DELETE, currentTime)
        assertEquals(AuthorizationResult.ServicePermissionDenied, deleteResult)
    }

    @Test
    fun `user can execute delete on file only when both FILE_DELETE service and DELETE action are granted`() {
        val readOnlyPerm = DevicePermission("device1", setOf(ServicePermission.FILE_DELETE), allowedActions = setOf(Action.READ))
        val readOnlyUser = createUser("u1", Role.USER, listOf(readOnlyPerm))
        val session1 = createSession(readOnlyUser)

        val deniedResult = engine.authorize(session1, "device1", ServicePermission.FILE_DELETE, Action.DELETE, currentTime)
        assertEquals(AuthorizationResult.ServicePermissionDenied, deniedResult)

        val fullDeletePerm = DevicePermission("device1", setOf(ServicePermission.FILE_DELETE), allowedActions = setOf(Action.DELETE))
        val deleteUser = createUser("u2", Role.USER, listOf(fullDeletePerm))
        val session2 = createSession(deleteUser)

        val allowedResult = engine.authorize(session2, "device1", ServicePermission.FILE_DELETE, Action.DELETE, currentTime)
        assertEquals(AuthorizationResult.Allowed, allowedResult)
    }

    @Test
    fun `standard user is unconditionally denied Action MANAGE even if listed in permissions`() {
        // Adversarial case: database corruption attaches MANAGE action to standard USER
        val roguePerm = DevicePermission("device1", setOf(ServicePermission.LOCATION), allowedActions = setOf(Action.READ, Action.MANAGE))
        val user = createUser("u-rogue", Role.USER, listOf(roguePerm))
        val session = createSession(user)

        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.MANAGE, currentTime)
        assertEquals("Standard USER cannot perform MANAGE action under any circumstances", 
            AuthorizationResult.ServicePermissionDenied, result)
    }

    // =========================================================================
    // PHASE 2: OWNER A1 & ADMIN B2 POLICY ENFORCEMENT TESTS
    // =========================================================================

    @Test
    fun `owner has universal operational privileges across all services without device assignment (Owner A1)`() {
        // Owner with completely empty device permissions
        val unassignedOwner = createUser("owner1", Role.OWNER, emptyList())
        val session = createSession(unassignedOwner)

        for (service in ServicePermission.values()) {
            for (action in Action.values()) {
                val result = engine.authorize(session, "arbitrary-device-999", service, action, currentTime)
                assertEquals("Owner A1 must be allowed $action on $service unconditionally", 
                    AuthorizationResult.Allowed, result)
            }
        }
    }

    @Test
    fun `admin has fleet-wide location read privileges without device assignment (Admin B2)`() {
        // Admin with no device permissions
        val unassignedAdmin = createUser("admin1", Role.ADMIN, emptyList())
        val session = createSession(unassignedAdmin)

        val result = engine.authorize(session, "any-fleet-tracker-123", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals("Admin B2 must be allowed fleet-wide LOCATION:READ without device assignment", 
            AuthorizationResult.Allowed, result)
    }

    @Test
    fun `admin without device assignment is strictly denied sensitive operations (Admin B2)`() {
        val unassignedAdmin = createUser("admin1", Role.ADMIN, emptyList())
        val session = createSession(unassignedAdmin)

        // Non-READ action on LOCATION
        for (nonReadAction in listOf(Action.WRITE, Action.EXECUTE, Action.DELETE, Action.MANAGE)) {
            val result = engine.authorize(session, "device1", ServicePermission.LOCATION, nonReadAction, currentTime)
            assertEquals("Admin without assignment must be denied $nonReadAction on LOCATION", 
                AuthorizationResult.DeviceAccessDenied, result)
        }

        // Sensitive services (Camera, Mic, Audio, Screen, Files)
        val sensitiveServices = listOf(
            ServicePermission.CAMERA,
            ServicePermission.MICROPHONE_AUDIO,
            ServicePermission.DEVICE_AUDIO,
            ServicePermission.SCREEN_CAPTURE,
            ServicePermission.FILE_READ,
            ServicePermission.FILE_WRITE,
            ServicePermission.FILE_DELETE,
            ServicePermission.FILE_RENAME,
            ServicePermission.FILE_TRANSFER
        )

        for (service in sensitiveServices) {
            for (action in Action.values()) {
                val result = engine.authorize(session, "device1", service, action, currentTime)
                assertEquals("Admin without assignment must be denied $action on $service", 
                    AuthorizationResult.DeviceAccessDenied, result)
            }
        }
    }

    @Test
    fun `admin with active device assignment has full operational capabilities on assigned device`() {
        val devicePerm = DevicePermission("device1", emptySet())
        val assignedAdmin = createUser("admin1", Role.ADMIN, listOf(devicePerm))
        val session = createSession(assignedAdmin)

        for (service in ServicePermission.values()) {
            for (action in Action.values()) {
                val result = engine.authorize(session, "device1", service, action, currentTime)
                assertEquals("Admin with assignment must be allowed $action on $service", 
                    AuthorizationResult.Allowed, result)
            }
        }
    }

    @Test
    fun `admin with expired device assignment is denied sensitive operations`() {
        val expiredPerm = DevicePermission("device1", emptySet(), expiresAt = currentTime - 50L)
        val adminWithExpiredPerm = createUser("admin1", Role.ADMIN, listOf(expiredPerm))
        val session = createSession(adminWithExpiredPerm)

        // Sensitive operation on assigned device with expired assignment must fail
        val result = engine.authorize(session, "device1", ServicePermission.CAMERA, Action.READ, currentTime)
        assertEquals("Admin with expired assignment must be denied access", 
            AuthorizationResult.DeviceAccessDenied, result)

        // But fleet-wide LOCATION:READ still succeeds under Admin B2
        val locResult = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals("Admin B2 fleet-wide LOCATION:READ remains allowed regardless of expired device assignment", 
            AuthorizationResult.Allowed, locResult)
    }

    // =========================================================================
    // PHASE 1: NEGATIVE, MALFORMED & ADVERSARIAL EDGE CASES
    // =========================================================================

    @Test
    fun `whitespace-only deviceId yields InvalidRequest`() {
        val user = createUser("u1", Role.USER, emptyList())
        val session = createSession(user)

        val result = engine.authorize(session, "   ", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals(AuthorizationResult.InvalidRequest, result)
    }

    @Test
    fun `negative currentTime yields InvalidRequest`() {
        val devicePerm = DevicePermission("device1", setOf(ServicePermission.LOCATION), setOf(Action.READ))
        val user = createUser("u1", Role.USER, listOf(devicePerm))
        val session = createSession(user)

        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, -1L)
        assertEquals(AuthorizationResult.InvalidRequest, result)
    }

    @Test
    fun `null action yields InvalidRequest`() {
        val devicePerm = DevicePermission("device1", setOf(ServicePermission.LOCATION), setOf(Action.READ))
        val user = createUser("u1", Role.USER, listOf(devicePerm))
        val session = createSession(user)

        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, null, currentTime)
        assertEquals(AuthorizationResult.InvalidRequest, result)
    }

    @Test
    fun `visitor with complete service and action grants is strictly denied device access`() {
        // Adversarial injection: all services, all actions, infinite expiry
        val poisonedPerm = DevicePermission(
            deviceId = "device1",
            allowedServices = ServicePermission.values().toSet(),
            allowedActions = Action.values().toSet(),
            expiresAt = Long.MAX_VALUE
        )
        val visitor = createUser("visitor-injected", Role.VISITOR, listOf(poisonedPerm))
        val session = createSession(visitor)

        for (service in ServicePermission.values()) {
            for (action in Action.values()) {
                val result = engine.authorize(session, "device1", service, action, currentTime)
                assertEquals("Visitor role must unconditionally fail closed with DeviceAccessDenied", 
                    AuthorizationResult.DeviceAccessDenied, result)
            }
        }
    }

    @Test
    fun `complete precedence chain verification`() {
        // Precedence 1: Unauthenticated beats InvalidRequest and Inactive Status
        val inactiveUser = createUser("u1", Role.USER, emptyList(), accountStatus = AccountStatus.DISABLED)
        val unauthSession = createSession(inactiveUser, isAuthenticated = false)
        val result1 = engine.authorize(unauthSession, null, null, null, currentTime)
        assertEquals(AuthorizationResult.NotAuthenticated, result1)

        // Precedence 2: InvalidRequest beats AccountNotActive
        val activeSessionInactiveUser = createSession(inactiveUser, isAuthenticated = true)
        val result2 = engine.authorize(activeSessionInactiveUser, null, ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals(AuthorizationResult.InvalidRequest, result2)

        // Precedence 3: AccountNotActive beats Visitor Isolation
        val inactiveVisitor = createUser("v1", Role.VISITOR, emptyList(), accountStatus = AccountStatus.PENDING)
        val visitorSession = createSession(inactiveVisitor, isAuthenticated = true)
        val result3 = engine.authorize(visitorSession, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals(AuthorizationResult.AccountNotActive, result3)

        // Precedence 4: Visitor Isolation beats DeviceAccessDenied
        val activeVisitor = createUser("v2", Role.VISITOR, emptyList(), accountStatus = AccountStatus.ACTIVE)
        val activeVisitorSession = createSession(activeVisitor, isAuthenticated = true)
        val result4 = engine.authorize(activeVisitorSession, "unassigned-dev", ServicePermission.LOCATION, Action.READ, currentTime)
        assertEquals(AuthorizationResult.DeviceAccessDenied, result4)
    }
}
