package com.livetracker.controller.core.auth.domain

sealed class AuthorizationResult {
    object Allowed : AuthorizationResult()
    object NotAuthenticated : AuthorizationResult()
    object AccountNotActive : AuthorizationResult()
    object UserNotFound : AuthorizationResult()
    object DeviceAccessDenied : AuthorizationResult()
    object ServicePermissionDenied : AuthorizationResult()
    object InvalidRequest : AuthorizationResult()
    object AuthorizationUnavailable : AuthorizationResult()
}
