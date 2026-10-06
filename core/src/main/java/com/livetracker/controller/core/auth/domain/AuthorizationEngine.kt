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
        
        // Deny by default: if session is invalid or missing, deny.
        if (session == null || !session.isValid(currentTime)) {
            return AuthorizationResult.NotAuthenticated
        }

        val user = session.user

        // Deny by default: if inputs are ambiguous or missing, deny.
        if (deviceId.isNullOrBlank() || service == null || action == null) {
            return AuthorizationResult.InvalidRequest
        }

        // Account must be ACTIVE
        if (user.accountStatus != AccountStatus.ACTIVE) {
            return AuthorizationResult.AccountNotActive
        }

        // Find explicit device permission for this user
        val devicePermission = user.devicePermissions.find { it.deviceId == deviceId }
        
        if (devicePermission == null) {
            return AuthorizationResult.DeviceAccessDenied
        }

        // Evaluate permissions. 
        // ADMIN is a privileged role but still requires device authorization (handled above).
        // Once device access is verified, ADMINs are authorized for all services on that device.
        if (user.role == Role.ADMIN) {
            return AuthorizationResult.Allowed
        }

        // For standard USERs, check explicit service permissions.
        if (devicePermission.allowedServices.contains(service)) {
            return AuthorizationResult.Allowed
        }

        // Deny by default
        return AuthorizationResult.ServicePermissionDenied
    }
}
