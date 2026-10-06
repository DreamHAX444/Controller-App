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
    fun `admin without device access yields DeviceAccessDenied`() {
        // Enforcing that admins cannot bypass device-level constraints entirely
        val user = createUser("admin1", Role.ADMIN, emptyList())
        val session = createSession(user)
        
        val result = engine.authorize(session, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
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
        
        // 3. DeviceAccessDenied beats User/Admin checks
        val adminSession = createSession(createUser("admin1", Role.ADMIN, emptyList()))
        result = engine.authorize(adminSession, "device1", ServicePermission.LOCATION, Action.READ, currentTime)
        assertTrue(result is AuthorizationResult.DeviceAccessDenied)
    }
}
