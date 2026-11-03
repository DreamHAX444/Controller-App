package com.livetracker.controller.core.auth.domain

interface AuthorizationEngine {
    fun authorize(
        session: Session?,
        deviceId: String?,
        service: ServicePermission?,
        action: Action?,
        currentTime: Long
    ): AuthorizationResult
}

class DefaultAuthorizationEngine : AuthorizationEngine {
    override fun authorize(
        session: Session?,
        deviceId: String?,
        service: ServicePermission?,
        action: Action?,
        currentTime: Long
    ): AuthorizationResult {
        // Step 1: Deny by default: if session is invalid, expired, or missing, deny.
        if (session == null || !session.isValid(currentTime)) {
            return AuthorizationResult.NotAuthenticated
        }

        val user = session.user

        // Step 2: Deny by default: if inputs are ambiguous, missing, or malformed, deny.
        if (deviceId.isNullOrBlank() || service == null || action == null || currentTime < 0) {
            return AuthorizationResult.InvalidRequest
        }

        // Step 3: Account lifecycle gating: account must be strictly ACTIVE.
        // Fails closed for PENDING, DISABLED, SUSPENDED, and UNKNOWN.
        if (user.accountStatus != AccountStatus.ACTIVE) {
            return AuthorizationResult.AccountNotActive
        }

        // Step 4: Visitor sandbox isolation: reject physical device access unconditionally.
        if (user.role == Role.VISITOR) {
            return AuthorizationResult.DeviceAccessDenied
        }

        // Step 5: Owner A1 Policy: Universal authority across all devices, services, and actions.
        if (user.role == Role.OWNER) {
            return AuthorizationResult.Allowed
        }

        // Step 6: Admin B2 Policy: Fleet-wide location telemetry read without explicit device assignment.
        if (user.role == Role.ADMIN && service == ServicePermission.LOCATION && action == Action.READ) {
            return AuthorizationResult.Allowed
        }

        // Step 7: Device assignment & temporal expiry for Admin (sensitive operations) and Standard User.
        val devicePermission = user.devicePermissions.find { it.deviceId == deviceId }
            ?: return AuthorizationResult.DeviceAccessDenied

        // Reject if temporal permission has expired (currentTime >= expiresAt)
        if (devicePermission.expiresAt != null && currentTime >= devicePermission.expiresAt) {
            return AuthorizationResult.DeviceAccessDenied
        }

        // Step 8: Admin operational capabilities on assigned devices.
        if (user.role == Role.ADMIN) {
            return AuthorizationResult.Allowed
        }

        // Step 9: Standard USER capability & action evaluation.
        if (user.role == Role.USER) {
            // Role ceiling: Standard users can never perform Action.MANAGE
            if (action == Action.MANAGE) {
                return AuthorizationResult.ServicePermissionDenied
            }

            // Verify specific service grant
            if (!devicePermission.allowedServices.contains(service)) {
                return AuthorizationResult.ServicePermissionDenied
            }

            // Verify specific action capability grant
            if (!devicePermission.allowedActions.contains(action)) {
                return AuthorizationResult.ServicePermissionDenied
            }

            return AuthorizationResult.Allowed
        }

        // Fail closed for any unrecognized role
        return AuthorizationResult.ServicePermissionDenied
    }
}
