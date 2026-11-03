package com.livetracker.controller.core.tracker.screen.data

import com.livetracker.controller.core.auth.domain.Action
import com.livetracker.controller.core.auth.domain.AuthorizationEngine
import com.livetracker.controller.core.auth.domain.AuthorizationResult
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureRepository
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureState
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureStatus
import com.livetracker.controller.core.tracker.signaling.SignalingManager
import com.livetracker.controller.core.tracker.signaling.SignalingSession
import com.livetracker.controller.core.tracker.webrtc.WebRtcConfiguration
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionManager
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionOrchestrator
import com.livetracker.controller.core.tracker.webrtc.WebRtcState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DefaultScreenCaptureRepository(
    private val sessionManager: TrackerSessionManager? = null,
    private val webRtcSessionManager: WebRtcSessionManager? = null,
    private val signalingManager: SignalingManager? = null,
    private val authEngine: AuthorizationEngine? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : ScreenCaptureRepository {

    private val statesFlow = MutableStateFlow<Map<TrackerId, ScreenCaptureState>>(emptyMap())
    private val mutex = Mutex()
    private val activeJobs = mutableMapOf<TrackerId, Job>()
    private val activeOrchestrators = mutableMapOf<TrackerId, Pair<WebRtcSessionOrchestrator, SignalingSession>>()

    override fun observeScreenState(deviceId: TrackerId): Flow<ScreenCaptureState> {
        return statesFlow.map { map ->
            map[deviceId] ?: ScreenCaptureState(deviceId = deviceId)
        }.distinctUntilChanged()
    }

    override suspend fun getScreenState(deviceId: TrackerId): ScreenCaptureState? {
        return mutex.withLock { statesFlow.value[deviceId] }
    }

    override suspend fun startScreenCapture(
        deviceId: TrackerId,
        qualityProfile: String,
        session: Session?
    ): Result<Unit> = mutex.withLock {
        // Step 1: Authorization Gate
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.SCREEN_CAPTURE,
                action = Action.READ,
                currentTime = timeProvider()
            )
            if (authResult !is AuthorizationResult.Allowed) {
                val err = "Screen capture access denied: ${authResult::class.simpleName}"
                updateState(deviceId) { it.copy(status = ScreenCaptureStatus.ERROR, errorMessage = err) }
                return Result.failure(SecurityException(err))
            }
        }

        val sessionId = UUID.randomUUID().toString()
        updateState(deviceId) {
            it.copy(
                sessionId = sessionId,
                status = ScreenCaptureStatus.AWAITING_CONSENT,
                qualityProfile = qualityProfile,
                errorMessage = null,
                lastUpdated = timeProvider()
            )
        }

        // Step 2: Establish WebRTC Session
        if (webRtcSessionManager != null) {
            val rtcConfig = WebRtcConfiguration()
            val rtcSession = webRtcSessionManager.createSession(deviceId, sessionId, rtcConfig)

            if (signalingManager != null) {
                val signalingSession = signalingManager.createSession(deviceId, sessionId)
                val orchestrator = WebRtcSessionOrchestrator(deviceId, rtcSession, signalingSession, scope)
                orchestrator.start()
                activeOrchestrators[deviceId] = Pair(orchestrator, signalingSession)
            }

            activeJobs[deviceId]?.cancel()
            val job = scope.launch {
                rtcSession.state.collect { rtcState ->
                    when (rtcState) {
                        WebRtcState.CONNECTED -> {
                            updateState(deviceId) {
                                it.copy(status = ScreenCaptureStatus.STREAMING, lastUpdated = timeProvider())
                            }
                        }
                        WebRtcState.FAILED, WebRtcState.CLOSED -> {
                            if (statesFlow.value[deviceId]?.status == ScreenCaptureStatus.STREAMING) {
                                updateState(deviceId) {
                                    it.copy(status = ScreenCaptureStatus.STOPPED, lastUpdated = timeProvider())
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
            activeJobs[deviceId] = job
        } else {
            // Emulated/Stub connected transition
            updateState(deviceId) {
                it.copy(status = ScreenCaptureStatus.STREAMING, lastUpdated = timeProvider())
            }
        }

        // Step 3: Dispatch Start Command to Tracker
        val command = TrackerCommand.StartScreen(
            deviceId = deviceId,
            sessionId = sessionId,
            qualityProfile = qualityProfile
        )
        sessionManager?.sendCommand(command)

        Result.success(Unit)
    }

    override suspend fun stopScreenCapture(deviceId: TrackerId, session: Session?): Result<Unit> = mutex.withLock {
        activeJobs.remove(deviceId)?.cancel()

        activeOrchestrators.remove(deviceId)?.let { (orch, sigSession) ->
            try { orch.stop() } catch (_: Exception) {}
            try { sigSession.stop() } catch (_: Exception) {}
        }

        webRtcSessionManager?.getSession(deviceId)?.let { session ->
            scope.launch {
                try { session.stop() } catch (_: Exception) {}
            }
        }
        webRtcSessionManager?.removeSession(deviceId)
        scope.launch {
            try { signalingManager?.closeSession(deviceId) } catch (_: Exception) {}
        }

        val command = TrackerCommand.StopScreen(deviceId = deviceId)
        sessionManager?.sendCommand(command)

        updateState(deviceId) {
            it.copy(
                status = ScreenCaptureStatus.STOPPED,
                sessionId = null,
                lastUpdated = timeProvider()
            )
        }

        Result.success(Unit)
    }

    private fun updateState(deviceId: TrackerId, transform: (ScreenCaptureState) -> ScreenCaptureState) {
        statesFlow.update { map ->
            val current = map[deviceId] ?: ScreenCaptureState(deviceId = deviceId)
            map + (deviceId to transform(current))
        }
    }
}
