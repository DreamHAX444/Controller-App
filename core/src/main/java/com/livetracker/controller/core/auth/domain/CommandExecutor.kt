package com.livetracker.controller.core.auth.domain

/**
 * CommandExecutor enforces the architectural boundary.
 * All SecureCommands must pass through this executor, which validates authorization
 * before delegating to the command's internal execution logic.
 */
class CommandExecutor(
    private val authEngine: AuthorizationEngine
) {
    suspend fun <T> executeCommand(
        session: Session?, 
        command: SecureCommand<T>,
        currentTime: Long
    ): Result<T> {
        val authResult = authEngine.authorize(
            session = session,
            deviceId = command.deviceId,
            service = command.service,
            action = command.action,
            currentTime = currentTime
        )

        return when (authResult) {
            is AuthorizationResult.Allowed -> {
                try {
                    // Safe execution since it was allowed
                    Result.success(command.performExecution(session!!))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            is AuthorizationResult.AccountNotActive -> Result.failure(SecurityException("Account is not active"))
            is AuthorizationResult.NotAuthenticated -> Result.failure(SecurityException("Not authenticated"))
            is AuthorizationResult.DeviceAccessDenied -> Result.failure(SecurityException("Device access denied"))
            is AuthorizationResult.ServicePermissionDenied -> Result.failure(SecurityException("Service permission denied"))
            is AuthorizationResult.InvalidRequest -> Result.failure(SecurityException("Invalid request parameters"))
            is AuthorizationResult.AuthorizationUnavailable -> Result.failure(SecurityException("Authorization state unavailable"))
            is AuthorizationResult.UserNotFound -> Result.failure(SecurityException("User not found"))
        }
    }
}
