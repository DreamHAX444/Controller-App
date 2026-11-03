package com.livetracker.controller.core.auth.domain

sealed class AuthenticationResult {
    data class Success(val session: Session) : AuthenticationResult()
    object InvalidCredentials : AuthenticationResult()
    object AccountPending : AuthenticationResult()
    object AccountDisabled : AuthenticationResult()
    object AccountSuspended : AuthenticationResult()
    object SessionExpired : AuthenticationResult()
    object AuthenticationUnavailable : AuthenticationResult()
    object InvalidRequest : AuthenticationResult()
    data class Error(val message: String) : AuthenticationResult()
}
