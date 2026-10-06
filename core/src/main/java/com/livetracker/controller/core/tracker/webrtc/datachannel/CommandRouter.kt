package com.livetracker.controller.core.tracker.webrtc.datachannel

import com.livetracker.controller.core.auth.domain.Action
import com.livetracker.controller.core.auth.domain.AuthorizationEngine
import com.livetracker.controller.core.auth.domain.AuthorizationResult
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import java.time.Clock

interface CommandRouter {
    suspend fun routeCommand(
        session: Session,
        command: TrackerCommand,
        service: ServicePermission,
        action: Action
    ): CommandResult
}

class DefaultCommandRouter(
    private val authorizationEngine: AuthorizationEngine,
    private val trackerSessionManager: TrackerSessionManager,
    private val clock: Clock = Clock.systemUTC()
) : CommandRouter {

    override suspend fun routeCommand(
        session: Session,
        command: TrackerCommand,
        service: ServicePermission,
        action: Action
    ): CommandResult {
        val authResult = authorizationEngine.authorize(
            session = session,
            deviceId = command.deviceId.value,
            service = service,
            action = action,
            currentTime = clock.millis()
        )

        if (authResult != AuthorizationResult.Allowed) {
            return CommandResult.Unauthorized(command.commandId, command.deviceId)
        }

        if (!trackerSessionManager.hasSession(command.deviceId)) {
            return CommandResult.Failed(command.commandId, command.deviceId, "SESSION_NOT_FOUND")
        }

        return trackerSessionManager.sendCommand(command)
    }
}
