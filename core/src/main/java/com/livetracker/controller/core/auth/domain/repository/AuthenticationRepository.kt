package com.livetracker.controller.core.auth.domain.repository

import com.livetracker.controller.core.auth.domain.AuthenticationResult

interface AuthenticationRepository {
    // Note: This does not take raw passwords directly in the domain layer.
    // In a real implementation, credentials would be handled securely and this method
    // might take a secure token or an opaque credential object, or the repository
    // itself internally manages the handshake.
    // For now, we establish the boundary.
    suspend fun authenticate(credentialIdentifier: String): AuthenticationResult
    
    suspend fun logout(): Result<Unit>
}
