package com.livetracker.controller.core.auth.presentation

import com.livetracker.controller.core.auth.domain.Session

sealed class AuthenticationState {
    object Initializing : AuthenticationState()
    object Unauthenticated : AuthenticationState()
    object Authenticating : AuthenticationState()
    data class Authenticated(val session: Session) : AuthenticationState()
    object SessionExpired : AuthenticationState()
    object AccountDisabled : AuthenticationState()
    object AccountSuspended : AuthenticationState()
    object AuthenticationUnavailable : AuthenticationState()
    data class Error(val message: String) : AuthenticationState()
}
