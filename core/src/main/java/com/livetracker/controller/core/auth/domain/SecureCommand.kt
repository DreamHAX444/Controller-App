package com.livetracker.controller.core.auth.domain

/**
 * A secure command represents an action that MUST be authorized before execution.
 * This contract prevents the UI from directly talking to the transport layer.
 */
abstract class SecureCommand<T> {
    abstract val deviceId: String
    abstract val service: ServicePermission
    abstract val action: Action
    
    // Protected so only the CommandExecutor can invoke it directly
    protected abstract suspend fun executeInternal(session: Session): T
    
    // Internal wrapper used by the executor
    internal suspend fun performExecution(session: Session): T {
        return executeInternal(session)
    }
}
