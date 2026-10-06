package com.livetracker.controller.core.auth.domain

sealed class AuthenticationResult {
    data class Success(val session: Session) : AuthenticationResult()
    object InvalidCredentials : AuthenticationResult()
    object AccountDisabled : AuthenticationResult()
    object AccountSuspended : AuthenticationResult()
    object SessionExpired : AuthenticationResult()
    object AuthenticationUnavailable : AuthenticationResult()
    object InvalidRequest : AuthenticationResult()
}
