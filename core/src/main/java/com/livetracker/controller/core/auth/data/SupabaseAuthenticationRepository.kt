package com.livetracker.controller.core.auth.data

import com.livetracker.controller.core.auth.data.model.ProfileUpsert
import com.livetracker.controller.core.auth.data.model.UserProfile
import com.livetracker.controller.core.auth.domain.*
import com.livetracker.controller.core.auth.domain.repository.AuthenticationRepository
import com.livetracker.controller.core.auth.domain.repository.CredentialStore
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord
import com.livetracker.controller.core.tracker.supabase.DeviceFeaturePermissionRecord
import com.livetracker.controller.core.tracker.supabase.DeviceRecord
import com.livetracker.controller.core.tracker.supabase.SupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RegisterUserAccountRpcParams(
    val p_email: String,
    val p_password: String,
    val p_display_name: String
)

/**
 * Production-ready [AuthenticationRepository] combining Supabase GoTrue Google ID Token
 * exchange, email/password authentication, and public.profiles role/status verification.
 */
class SupabaseAuthenticationRepository(
    private val sessionRepository: SessionRepository,
    private val credentialStore: CredentialStore,
    private val clientProvider: () -> SupabaseClient = { SupabaseClientProvider.client }
) : AuthenticationRepository {

    override suspend fun authenticate(credentialIdentifier: String): AuthenticationResult {
        val trimmed = credentialIdentifier.trim()

        // 1. Explicitly reject all legacy developer preset bypasses
        val lower = trimmed.lowercase()
        if (lower in setOf("owner", "admin", "user", "visitor", "pending", "disabled", "suspended", "unavailable")) {
            return AuthenticationResult.InvalidCredentials
        }

        // 2. Reject legacy synthetic Google format immediately (force fresh login)
        if (trimmed.startsWith("google:") && trimmed.contains("|")) {
            val parts = trimmed.removePrefix("google:").split("|")
            val possibleToken = parts.getOrNull(3)?.trim().orEmpty()
            if (!possibleToken.startsWith("ey")) {
                credentialStore.clearSecureToken()
                return AuthenticationResult.Error("Google Sign-In requires a verified ID token. Please sign in again.")
            }
        }

        val tokenToUse = if (trimmed.startsWith("id_token:")) {
            trimmed.removePrefix("id_token:").trim()
        } else if (trimmed.startsWith("google:")) {
            val after = trimmed.removePrefix("google:").trim()
            if (after.contains("|")) {
                after.split("|").getOrNull(3)?.trim().orEmpty()
            } else {
                after
            }
        } else {
            trimmed
        }

        // 3. Email Sign Up ("signup:email|password|displayName")
        if (trimmed.startsWith("signup:")) {
            val payload = trimmed.removePrefix("signup:").trim()
            val parts = payload.split("|")
            val email = parts.getOrNull(0)?.trim().orEmpty()
            val password = parts.getOrNull(1)?.trim().orEmpty()
            val displayName = parts.getOrNull(2)?.trim()?.ifBlank { null }
                ?: email.substringBefore("@").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

            if (email.isBlank() || password.isBlank()) {
                return AuthenticationResult.InvalidRequest
            }

            return withContext(Dispatchers.IO) {
                val client = clientProvider()
                val registerResult = runCatching {
                    val params = RegisterUserAccountRpcParams(
                        p_email = email,
                        p_password = password,
                        p_display_name = displayName
                    )
                    client.postgrest.rpc("register_user_account", params)
                }

                if (registerResult.isFailure) {
                    val err = registerResult.exceptionOrNull()?.message?.lowercase() ?: ""
                    return@withContext if (err.contains("already exists")) {
                        AuthenticationResult.Error("An account with this email already exists. Please sign in.")
                    } else if (err.contains("password must be at least")) {
                        AuthenticationResult.Error("Password must be at least 6 characters.")
                    } else if (err.contains("invalid email address format")) {
                        AuthenticationResult.Error("Invalid email address format.")
                    } else {
                        AuthenticationResult.Error("Registration failed: ${registerResult.exceptionOrNull()?.localizedMessage ?: "Please try again."}")
                    }
                }

                // Auto-login upon successful registration
                try {
                    client.auth.signInWith(Email) {
                        this.email = email
                        this.password = password
                    }
                    val currentSession = client.auth.currentSessionOrNull()
                        ?: return@withContext AuthenticationResult.InvalidCredentials
                    val authUser = currentSession.user
                        ?: return@withContext AuthenticationResult.InvalidCredentials

                    val storedToken = currentSession.accessToken
                    resolveAuthenticatedUser(
                        client = client,
                        authUser = authUser,
                        tokenToStore = storedToken
                    )
                } catch (e: Exception) {
                    AuthenticationResult.InvalidCredentials
                }
            }
        }

        // 4. Check if the credentialIdentifier is an Email/Password pair ("email:password")
        val isEmailPassword = trimmed.contains(":") &&
            !trimmed.startsWith("id_token:") &&
            !trimmed.startsWith("google:") &&
            trimmed.substringBefore(":").contains("@")

        val isExplicitIdToken = trimmed.startsWith("id_token:") || (trimmed.startsWith("google:") && tokenToUse.startsWith("ey"))

        if (!isEmailPassword && tokenToUse.isBlank()) {
            return AuthenticationResult.InvalidRequest
        }

        return withContext(Dispatchers.IO) {
            try {
                val client = clientProvider()

                if (isEmailPassword) {
                    val email = credentialIdentifier.substringBefore(":").trim()
                    val password = credentialIdentifier.substringAfter(":").trim()
                    client.auth.signInWith(Email) {
                        this.email = email
                        this.password = password
                    }
                    val currentSession = client.auth.currentSessionOrNull()
                        ?: return@withContext AuthenticationResult.InvalidCredentials
                    val authUser = currentSession.user
                        ?: return@withContext AuthenticationResult.InvalidCredentials

                    val storedToken = currentSession.accessToken.ifBlank { tokenToUse }
                    resolveAuthenticatedUser(
                        client = client,
                        authUser = authUser,
                        tokenToStore = storedToken
                    )
                } else if (isExplicitIdToken) {
                    // Exchange verified Google ID token with Supabase GoTrue
                    client.auth.signInWith(IDToken) {
                        this.idToken = tokenToUse
                        this.provider = Google
                    }
                    val currentSession = client.auth.currentSessionOrNull()
                        ?: return@withContext AuthenticationResult.InvalidCredentials
                    val authUser = currentSession.user
                        ?: return@withContext AuthenticationResult.InvalidCredentials

                    val storedToken = currentSession.accessToken.ifBlank { tokenToUse }
                    resolveAuthenticatedUser(
                        client = client,
                        authUser = authUser,
                        tokenToStore = storedToken
                    )
                } else {
                    // Restoring from a saved token (JWT access token or existing session)
                    runCatching { client.auth.awaitInitialization() }
                    val existingSession = client.auth.currentSessionOrNull()
                    if (existingSession != null && existingSession.user != null && (existingSession.accessToken == tokenToUse || tokenToUse.isBlank())) {
                        val token = existingSession.accessToken
                        credentialStore.storeSecureToken(token)
                        resolveAuthenticatedUser(
                            client = client,
                            authUser = existingSession.user!!,
                            tokenToStore = token
                        )
                    } else {
                        val userResult = runCatching { client.auth.retrieveUser(tokenToUse) }
                        if (userResult.isSuccess) {
                            val authUser = userResult.getOrThrow()
                            credentialStore.storeSecureToken(tokenToUse)
                            resolveAuthenticatedUser(
                                client = client,
                                authUser = authUser,
                                tokenToStore = tokenToUse
                            )
                        } else {
                            // Try session refresh before giving up
                            val refreshResult = runCatching { client.auth.refreshCurrentSession() }
                            val refreshedSession = client.auth.currentSessionOrNull()
                            if (refreshResult.isSuccess && refreshedSession != null && refreshedSession.user != null) {
                                credentialStore.storeSecureToken(refreshedSession.accessToken)
                                resolveAuthenticatedUser(
                                    client = client,
                                    authUser = refreshedSession.user!!,
                                    tokenToStore = refreshedSession.accessToken
                                )
                            } else {
                                val errorMsg = userResult.exceptionOrNull()?.message?.lowercase() ?: ""
                                if (errorMsg.contains("expired")) {
                                    AuthenticationResult.SessionExpired
                                } else if (errorMsg.contains("invalid") || errorMsg.contains("unauthorized") || errorMsg.contains("jwt")) {
                                    AuthenticationResult.InvalidCredentials
                                } else {
                                    AuthenticationResult.AuthenticationUnavailable
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message?.lowercase() ?: ""
                if (errorMsg.contains("expired")) {
                    AuthenticationResult.SessionExpired
                } else if (errorMsg.contains("invalid") || errorMsg.contains("unauthorized") || errorMsg.contains("jwt")) {
                    AuthenticationResult.InvalidCredentials
                } else {
                    AuthenticationResult.AuthenticationUnavailable
                }
            }
        }
    }

    private suspend fun resolveAuthenticatedUser(
        client: SupabaseClient,
        authUser: UserInfo,
        tokenToStore: String
    ): AuthenticationResult {
        val userEmail = authUser.email ?: "user@trace.local"
        val userName = authUser.userMetadata?.get("full_name")?.toString()?.trim('"')
            ?: authUser.userMetadata?.get("name")?.toString()?.trim('"')
            ?: userEmail.substringBefore("@")
        val avatar = authUser.userMetadata?.get("avatar_url")?.toString()?.trim('"')
            ?: authUser.userMetadata?.get("picture")?.toString()?.trim('"')

        return syncProfileAndResolveUser(
            client = client,
            authUserId = authUser.id,
            email = userEmail,
            displayName = userName,
            avatarUrl = avatar,
            tokenToStore = tokenToStore
        )
    }

    private suspend fun syncProfileAndResolveUser(
        client: SupabaseClient,
        authUserId: String?,
        email: String,
        displayName: String,
        avatarUrl: String?,
        tokenToStore: String
    ): AuthenticationResult {
        // 1. Fetch existing profile from public.profiles by authUserId or email
        var profile = runCatching {
            if (!authUserId.isNullOrBlank()) {
                client.from("profiles").select {
                    filter { eq("id", authUserId) }
                }.decodeList<UserProfile>().firstOrNull()
            } else null
        }.getOrNull()

        if (profile == null && email.isNotBlank()) {
            profile = runCatching {
                client.from("profiles").select {
                    filter { eq("email", email) }
                }.decodeList<UserProfile>().firstOrNull()
            }.getOrNull()
        }

        if (profile == null) {
            val targetId = authUserId ?: UUID.nameUUIDFromBytes(email.lowercase().toByteArray()).toString()
            val initialRole = Role.VISITOR
            val initialStatus = AccountStatus.ACTIVE

            val upsertRecord = ProfileUpsert(
                id = targetId,
                email = email,
                displayName = displayName,
                avatarUrl = avatarUrl,
                role = initialRole.name,
                status = initialStatus.name
            )
            runCatching {
                client.from("profiles").upsert(upsertRecord)
            }
            profile = UserProfile(
                id = targetId,
                email = email,
                displayName = displayName,
                avatarUrl = avatarUrl,
                roleStr = initialRole.name,
                statusStr = initialStatus.name
            )
        } else {
            // Update displayName or avatarUrl in Supabase if missing or updated from Google
            val effectiveDisplayName = if (profile.displayName.isNullOrBlank()) displayName else profile.displayName
            val effectiveAvatarUrl = if (profile.avatarUrl.isNullOrBlank()) avatarUrl else profile.avatarUrl

            if ((profile.displayName != effectiveDisplayName && !effectiveDisplayName.isNullOrBlank()) ||
                (profile.avatarUrl != effectiveAvatarUrl && !effectiveAvatarUrl.isNullOrBlank())) {
                runCatching {
                    val updateRecord = ProfileUpsert(
                        id = profile.id,
                        email = profile.email ?: email,
                        displayName = effectiveDisplayName ?: "",
                        avatarUrl = effectiveAvatarUrl,
                        role = profile.role.name,
                        status = profile.status.name
                    )
                    client.from("profiles").update(updateRecord) {
                        filter { eq("id", profile.id) }
                    }
                }
            }
            profile = profile.copy(
                displayName = effectiveDisplayName,
                avatarUrl = effectiveAvatarUrl
            )
        }

        val userRole = profile.role
        val userStatus = profile.status

        // Fetch assigned devices & permissions
        val assignments = if (userRole != Role.VISITOR && userStatus == AccountStatus.ACTIVE) {
            if (userRole == Role.OWNER || userRole == Role.ADMIN) {
                runCatching {
                    val records = client.from("devices").select().decodeList<DeviceRecord>()
                    records.map { r ->
                        DevicePermission(
                            deviceId = r.deviceId,
                            allowedServices = setOf(
                                ServicePermission.LOCATION,
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
                        )
                    }
                }.getOrDefault(emptyList())
            } else {
                runCatching {
                    val userAssignments = client.from("user_device_assignments").select {
                        filter {
                            eq("user_id", profile.id)
                            eq("status", "ACTIVE")
                        }
                    }.decodeList<DeviceAssignmentRecord>()

                    if (userAssignments.isEmpty()) {
                        emptyList()
                    } else {
                        val assignmentIds = userAssignments.mapNotNull { it.id }
                        val permissions = client.from("device_feature_permissions").select {
                            filter {
                                isIn("assignment_id", assignmentIds)
                                eq("is_allowed", true)
                            }
                        }.decodeList<DeviceFeaturePermissionRecord>()

                        val permsByAssignment = permissions.groupBy { it.assignmentId }
                        userAssignments.map { asgn ->
                            val asgnPerms = permsByAssignment[asgn.id].orEmpty()
                            val services = FeaturePermissionMapper.toServicePermissions(asgnPerms.map { it.feature })
                            val effectiveServices = if (services.isEmpty() || asgnPerms.any { it.feature.equals("LOCATION", ignoreCase = true) && it.isAllowed }) {
                                services + ServicePermission.LOCATION
                            } else {
                                services
                            }
                            DevicePermission(
                                deviceId = asgn.deviceId,
                                allowedServices = effectiveServices
                            )
                        }
                    }
                }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }

        val verifiedUser = User(
            id = profile.id,
            displayName = profile.displayName?.ifBlank { null } ?: email.substringBefore("@"),
            role = userRole,
            accountStatus = userStatus,
            devicePermissions = assignments,
            email = email,
            avatarUrl = profile.avatarUrl
        )

        return handleAccountStatus(verifiedUser, tokenToStore)
    }

    private suspend fun handleAccountStatus(user: User, secureToken: String): AuthenticationResult {
        return when (user.accountStatus) {
            AccountStatus.DISABLED -> AuthenticationResult.AccountDisabled
            AccountStatus.SUSPENDED -> AuthenticationResult.AccountSuspended
            AccountStatus.UNKNOWN -> AuthenticationResult.AuthenticationUnavailable
            AccountStatus.PENDING -> AuthenticationResult.AccountPending
            AccountStatus.ACTIVE -> {
                val now = System.currentTimeMillis()
                val currentSession = runCatching { clientProvider().auth.currentSessionOrNull() }.getOrNull()
                val sessionExpiryMillis = currentSession?.expiresAt?.toEpochMilliseconds()
                    ?: currentSession?.expiresIn?.let { now + it * 1000L }
                    ?: (now + 24L * 3600_000L) // Default to 24 hours for offline presets/JWTs if not provided

                val session = Session(
                    sessionId = "sess_${UUID.randomUUID()}",
                    user = user,
                    isAuthenticated = true,
                    createdAt = now,
                    expiresAt = sessionExpiryMillis
                )
                sessionRepository.createSession(session)
                credentialStore.storeSecureToken(secureToken)
                AuthenticationResult.Success(session)
            }
        }
    }

    override suspend fun logout(): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                runCatching {
                    clientProvider().auth.signOut()
                }
                runCatching {
                    clientProvider().auth.clearSession()
                }
            }
            sessionRepository.invalidateSession()
            credentialStore.clearSecureToken()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
