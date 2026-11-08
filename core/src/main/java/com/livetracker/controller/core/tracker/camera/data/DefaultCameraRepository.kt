package com.livetracker.controller.core.tracker.camera.data

import com.livetracker.controller.core.auth.domain.Action
import com.livetracker.controller.core.auth.domain.AuthorizationEngine
import com.livetracker.controller.core.auth.domain.AuthorizationResult
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.tracker.camera.domain.ActualCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraError
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraResult
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.camera.domain.TrackerCameraState
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import com.livetracker.controller.core.tracker.signaling.SignalingManager
import com.livetracker.controller.core.tracker.signaling.SignalingSession
import com.livetracker.controller.core.tracker.webrtc.WebRtcConfiguration
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionManager
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionOrchestrator
import com.livetracker.controller.core.tracker.webrtc.WebRtcState
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.livetracker.controller.core.tracker.camera.domain.DiscoveredCameraInfo
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/**
 * Production implementation of [CameraRepository] integrating with [TrackerSessionManager],
 * [AuthorizationEngine], and [TrackerDeviceRepository].
 *
 * CRITICAL CONSTRAINT:
 * Does not fabricate latency, FPS, bitrate, or capture resolution. Telemetry is populated
 * only via verified [updateActualConfig] reports.
 */
class DefaultCameraRepository(
    private val sessionManager: TrackerSessionManager? = null,
    private val authEngine: AuthorizationEngine? = null,
    private val deviceRepository: TrackerDeviceRepository? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val commandIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val allowStandaloneFallback: Boolean = false,
    private val webRtcSessionManager: WebRtcSessionManager? = null,
    private val signalingManager: SignalingManager? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : CameraRepository {

    private val statesFlow = MutableStateFlow<Map<TrackerId, TrackerCameraState>>(emptyMap())
    private val discoveredCamerasFlow = MutableStateFlow<Map<TrackerId, List<DiscoveredCameraInfo>>>(emptyMap())
    private val mutex = Mutex()
    private val pendingOperations = mutableMapOf<TrackerId, PendingOperation>()
    private val activeOrchestrators = mutableMapOf<TrackerId, Pair<WebRtcSessionOrchestrator, SignalingSession>>()

    override fun observeDiscoveredCameras(deviceId: TrackerId): Flow<List<DiscoveredCameraInfo>> {
        return discoveredCamerasFlow.map { map ->
            map[deviceId] ?: DiscoveredCameraInfo.DEFAULT_CAMERAS
        }.distinctUntilChanged()
    }

    override suspend fun discoverCameras(
        deviceId: TrackerId,
        session: Session?,
        forceRefresh: Boolean
    ): List<DiscoveredCameraInfo> {
        val cached = mutex.withLock { discoveredCamerasFlow.value[deviceId] }
        if (!forceRefresh && !cached.isNullOrEmpty()) {
            return cached
        }

        if (sessionManager != null) {
            val command = TrackerCommand.GetCameraCapabilities(
                commandId = commandIdGenerator(),
                deviceId = deviceId,
                timestamp = timeProvider(),
                actorId = session?.user?.id ?: "controller"
            )
            try {
                val result = sessionManager.sendCommand(command)
                if (result is CommandResult.Completed && !result.payload.isNullOrBlank()) {
                    val parsed = parseDiscoveredCameras(result.payload)
                    if (parsed.isNotEmpty()) {
                        mutex.withLock {
                            discoveredCamerasFlow.update { it + (deviceId to parsed) }
                        }
                        return parsed
                    }
                }
            } catch (e: Exception) {
                Log.w("DefaultCameraRepository", "Failed to query camera capabilities for ${deviceId.value}: ${e.message}")
            }
        }

        val fallback = cached ?: DiscoveredCameraInfo.DEFAULT_CAMERAS
        mutex.withLock {
            discoveredCamerasFlow.update { it + (deviceId to fallback) }
        }
        return fallback
    }

    private fun parseDiscoveredCameras(payload: String): List<DiscoveredCameraInfo> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            var element = json.parseToJsonElement(payload)
            if (element is JsonObject && element.containsKey("params")) {
                val paramsStr = element["params"]?.jsonPrimitive?.contentOrNull
                if (!paramsStr.isNullOrBlank()) {
                    runCatching { element = json.parseToJsonElement(paramsStr) }
                }
            }

            val camerasArray = when (element) {
                is JsonObject -> {
                    element["cameras"]?.jsonArray
                        ?: (element["data"] as? JsonObject)?.get("cameras")?.jsonArray
                }
                is kotlinx.serialization.json.JsonArray -> element
                else -> null
            }

            camerasArray?.mapNotNull { item ->
                if (item is JsonObject) {
                    val id = item["cameraId"]?.jsonPrimitive?.content ?: "0"
                    val displayName = item["displayName"]?.jsonPrimitive?.content ?: "Camera $id"
                    val facingStr = item["facing"]?.jsonPrimitive?.content ?: "BACK"
                    val facing = runCatching { CameraFacing.valueOf(facingStr) }.getOrDefault(CameraFacing.BACK)
                    val lensType = item["lensType"]?.jsonPrimitive?.content ?: "PRIMARY"
                    val isLogical = item["isLogicalMultiCamera"]?.jsonPrimitive?.booleanOrNull ?: false

                    val physIds = item["physicalCameraIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

                    val configs = item["configurations"]?.jsonArray
                    val resolutions = configs?.mapNotNull { cfg ->
                        if (cfg is JsonObject) {
                            val w = cfg["width"]?.jsonPrimitive?.intOrNull
                            val h = cfg["height"]?.jsonPrimitive?.intOrNull
                            if (w != null && h != null) "${w}x${h}" else null
                        } else null
                    } ?: emptyList()

                    DiscoveredCameraInfo(
                        cameraId = id,
                        displayName = displayName,
                        facing = facing,
                        lensType = lensType,
                        isLogicalMultiCamera = isLogical,
                        physicalCameraIds = physIds,
                        supportedResolutions = resolutions
                    )
                } else null
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun observeCameraState(deviceId: TrackerId): Flow<TrackerCameraState> {
        return statesFlow.map { map ->
            map[deviceId] ?: TrackerCameraState.initial(deviceId, timeProvider())
        }.distinctUntilChanged()
    }

    override suspend fun getCameraState(deviceId: TrackerId): TrackerCameraState? {
        return mutex.withLock { statesFlow.value[deviceId] }
    }

    override suspend fun startCamera(
        deviceId: TrackerId,
        config: CameraCaptureConfig,
        session: Session?
    ): CameraResult {
        // 0. Coalesce concurrent in-flight START calls for the same device
        val deferredToAwait: CompletableDeferred<CameraResult>? = mutex.withLock {
            val existing = pendingOperations[deviceId]
            if (existing?.inFlightDeferred != null && existing.action == "START") {
                existing.inFlightDeferred
            } else {
                null
            }
        }
        if (deferredToAwait != null) {
            return deferredToAwait.await()
        }

        // 1. Authorization evaluation
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.CAMERA,
                action = Action.EXECUTE,
                currentTime = timeProvider()
            )
            if (authResult !is AuthorizationResult.Allowed) {
                val errorMsg = "Camera service permission denied ($authResult)"
                val failure = CameraResult.PermissionDenied(deviceId, errorMsg)
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.PermissionDenied(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                return failure
            }
        }

        // 2. Capabilities evaluation
        val device = deviceRepository?.getDevice(deviceId)
        val sessionState = sessionManager?.getSession(deviceId)?.value
        val isUnsupported = (device != null && !device.capabilities.camera) ||
            (device == null && sessionState != null && !sessionState.capabilities.camera)

        if (isUnsupported) {
            val errorMsg = "Tracker device '${deviceId.value}' does not support camera capability"
            val failure = CameraResult.UnsupportedDevice(deviceId, errorMsg)
            updateState(deviceId) { current ->
                current.copy(
                    lifecycleState = StreamLifecycleState.ERROR,
                    lastError = CameraError.UnsupportedCapability(errorMsg),
                    lastUpdatedTimestamp = timeProvider()
                )
            }
            return failure
        }

        // 3. Lifecycle state transition validation and commandId selection
        val (currentState, commandId, inFlightDeferred) = mutex.withLock {
            val state = statesFlow.value[deviceId] ?: TrackerCameraState.initial(deviceId, timeProvider())
            if (!state.lifecycleState.canStart) {
                return CameraResult.InvalidStateTransition(
                    currentState = state.lifecycleState,
                    attemptedAction = "START",
                    reason = "Cannot start camera while in state '${state.lifecycleState}'"
                )
            }

            val pendingOp = pendingOperations[deviceId]
            val isRetry = state.lifecycleState == StreamLifecycleState.ERROR &&
                pendingOp?.action == "START" &&
                pendingOp.config == config
            val chosenCommandId = if (isRetry) {
                pendingOp!!.commandId
            } else {
                commandIdGenerator()
            }

            val newDeferred = CompletableDeferred<CameraResult>()
            pendingOperations[deviceId] = PendingOperation(
                action = "START",
                commandId = chosenCommandId,
                config = config,
                inFlightDeferred = newDeferred
            )

            // 4. Update state to STARTING
            val updatedState = state.copy(
                lifecycleState = StreamLifecycleState.STARTING,
                requestedConfig = config,
                actualConfig = null,
                lastError = null,
                lastUpdatedTimestamp = timeProvider()
            )
            statesFlow.update { it + (deviceId to updatedState) }

            Triple(state, chosenCommandId, newDeferred)
        }

        // Setup WebRTC and Signaling if available
        if (webRtcSessionManager != null && signalingManager != null) {
            try {
                val rtcConfig = WebRtcConfiguration()
                val rtcSession = webRtcSessionManager.createSession(deviceId, commandId, rtcConfig)
                val signalingSession = signalingManager.createSession(deviceId, commandId)
                val orchestrator = WebRtcSessionOrchestrator(deviceId, rtcSession, signalingSession, scope)
                orchestrator.start()
                activeOrchestrators[deviceId] = Pair(orchestrator, signalingSession)

                scope.launch {
                    rtcSession.state.collect { rtcState ->
                        when (rtcState) {
                            WebRtcState.CONNECTED -> {
                                updateState(deviceId) { current ->
                                    if (current.lifecycleState == StreamLifecycleState.STARTING) {
                                        current.copy(
                                            lifecycleState = StreamLifecycleState.STREAMING,
                                            lastUpdatedTimestamp = timeProvider()
                                        )
                                    } else current
                                }
                            }
                            WebRtcState.FAILED -> {
                                updateState(deviceId) { current ->
                                    current.copy(
                                        lifecycleState = StreamLifecycleState.ERROR,
                                        lastError = CameraError.CommandFailure("WebRTC connection failed"),
                                        lastUpdatedTimestamp = timeProvider()
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("DefaultCameraRepository", "Failed to initialize WebRTC orchestrator: ${e.message}")
            }
        }

        val finalResult: CameraResult = try {
            // 5. Dispatch StartCamera command if sessionManager is present
            if (sessionManager != null) {
                val discovered = discoveredCamerasFlow.value[deviceId]
                val resolvedCameraId = config.cameraId
                    ?: discovered?.firstOrNull { it.facing == config.facing }?.cameraId
                    ?: discovered?.firstOrNull()?.cameraId

                val command = TrackerCommand.StartCamera(
                    commandId = commandId,
                    deviceId = deviceId,
                    timestamp = timeProvider(),
                    actorId = session?.user?.id ?: "controller",
                    cameraId = resolvedCameraId,
                    width = config.resolution.width,
                    height = config.resolution.height,
                    fps = config.fps
                )
                val result = try {
                    sessionManager.sendCommand(command)
                } catch (e: CancellationException) {
                    updateState(deviceId) { current ->
                        if (current.lifecycleState == StreamLifecycleState.STARTING) {
                            currentState.copy(lastUpdatedTimestamp = timeProvider())
                        } else {
                            current
                        }
                    }
                    throw e
                }
                handleStartCommandResult(deviceId, result)
            } else if (allowStandaloneFallback) {
                val newState = updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.STREAMING,
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.Success(newState)
            } else {
                // Fail closed by default if sessionManager is null (no command dispatch possible)
                val errorMsg = "TrackerSessionManager not configured; command cannot be dispatched"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.CommandFailure(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
        } catch (e: Throwable) {
            mutex.withLock {
                val cur = pendingOperations[deviceId]
                if (cur?.inFlightDeferred === inFlightDeferred) {
                    pendingOperations[deviceId] = cur.copy(inFlightDeferred = null)
                }
            }
            inFlightDeferred.completeExceptionally(e)
            throw e
        }

        mutex.withLock {
            if (finalResult is CameraResult.Success) {
                pendingOperations.remove(deviceId)
            } else {
                val cur = pendingOperations[deviceId]
                if (cur?.inFlightDeferred === inFlightDeferred) {
                    pendingOperations[deviceId] = cur.copy(inFlightDeferred = null)
                }
            }
        }
        inFlightDeferred.complete(finalResult)
        return finalResult
    }

    private suspend fun handleStartCommandResult(
        deviceId: TrackerId,
        result: CommandResult
    ): CameraResult {
        return when (result) {
            is CommandResult.Accepted, is CommandResult.Completed -> {
                val newState = updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.STREAMING,
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.Success(newState)
            }
            is CommandResult.Unsupported -> {
                val errorMsg = "Tracker device rejected command: Camera unsupported"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.UnsupportedCapability(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.UnsupportedDevice(deviceId, errorMsg)
            }
            is CommandResult.Unauthorized -> {
                val errorMsg = "Tracker device rejected command: Unauthorized"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.PermissionDenied(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.PermissionDenied(deviceId, errorMsg)
            }
            is CommandResult.Rejected -> {
                val errorMsg = "Start camera command rejected: ${result.reason}"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.CommandFailure(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
            is CommandResult.Timeout -> {
                val errorMsg = "Start camera command timed out"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.Timeout(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
            is CommandResult.Failed -> {
                val errorMsg = "Start camera command failed: ${result.reason}"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.CommandFailure(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
        }
    }

    override suspend fun stopCamera(
        deviceId: TrackerId,
        session: Session?
    ): CameraResult {
        // 0. Coalesce concurrent in-flight STOP calls for the same device
        val deferredToAwait: CompletableDeferred<CameraResult>? = mutex.withLock {
            val existing = pendingOperations[deviceId]
            if (existing?.inFlightDeferred != null && existing.action == "STOP") {
                existing.inFlightDeferred
            } else {
                null
            }
        }
        if (deferredToAwait != null) {
            return deferredToAwait.await()
        }

        // 1. Authorization evaluation
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.CAMERA,
                action = Action.EXECUTE,
                currentTime = timeProvider()
            )
            if (authResult !is AuthorizationResult.Allowed) {
                return CameraResult.PermissionDenied(
                    deviceId = deviceId,
                    reason = "Camera service permission denied ($authResult)"
                )
            }
        }

        // 2. Lifecycle state transition validation & commandId resolution
        val (currentState, commandId, inFlightDeferred) = mutex.withLock {
            val state = statesFlow.value[deviceId] ?: TrackerCameraState.initial(deviceId, timeProvider())
            val pendingOp = pendingOperations[deviceId]
            val canStopOperation = state.lifecycleState.canStop ||
                (state.lifecycleState == StreamLifecycleState.ERROR && pendingOp?.action == "STOP")
            if (!canStopOperation) {
                return CameraResult.InvalidStateTransition(
                    currentState = state.lifecycleState,
                    attemptedAction = "STOP",
                    reason = "Cannot stop camera while in state '${state.lifecycleState}'"
                )
            }

            val isRetry = state.lifecycleState == StreamLifecycleState.ERROR && pendingOp?.action == "STOP"
            val chosenCommandId = if (isRetry) {
                pendingOp!!.commandId
            } else {
                commandIdGenerator()
            }

            val newDeferred = CompletableDeferred<CameraResult>()
            pendingOperations[deviceId] = PendingOperation(
                action = "STOP",
                commandId = chosenCommandId,
                config = null,
                inFlightDeferred = newDeferred
            )

            // 3. Update state to STOPPING
            val updatedState = state.copy(
                lifecycleState = StreamLifecycleState.STOPPING,
                lastUpdatedTimestamp = timeProvider()
            )
            statesFlow.update { it + (deviceId to updatedState) }

            Triple(state, chosenCommandId, newDeferred)
        }

        // Teardown WebRTC and Signaling for stopped device
        activeOrchestrators.remove(deviceId)?.let { (orch, sigSession) ->
            try { orch.stop() } catch (_: Exception) {}
            try { sigSession.stop() } catch (_: Exception) {}
        }
        webRtcSessionManager?.getSession(deviceId)?.let { rtcSession ->
            scope.launch {
                try { rtcSession.stop() } catch (_: Exception) {}
            }
        }
        webRtcSessionManager?.removeSession(deviceId)
        scope.launch {
            try { signalingManager?.closeSession(deviceId) } catch (_: Exception) {}
        }

        val finalResult: CameraResult = try {
            // 4. Dispatch StopCamera command if sessionManager is present
            if (sessionManager != null) {
                val command = TrackerCommand.StopCamera(
                    commandId = commandId,
                    deviceId = deviceId,
                    timestamp = timeProvider(),
                    actorId = session?.user?.id ?: "controller"
                )
                val result = try {
                    sessionManager.sendCommand(command)
                } catch (e: CancellationException) {
                    updateState(deviceId) { current ->
                        if (current.lifecycleState == StreamLifecycleState.STOPPING) {
                            currentState.copy(lastUpdatedTimestamp = timeProvider())
                        } else {
                            current
                        }
                    }
                    throw e
                }
                handleStopCommandResult(deviceId, result)
            } else if (allowStandaloneFallback) {
                val newState = updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.IDLE,
                        requestedConfig = null,
                        actualConfig = null,
                        lastError = null,
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.Success(newState)
            } else {
                val errorMsg = "TrackerSessionManager not configured; command cannot be dispatched"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.CommandFailure(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
        } catch (e: Throwable) {
            mutex.withLock {
                val cur = pendingOperations[deviceId]
                if (cur?.inFlightDeferred === inFlightDeferred) {
                    pendingOperations[deviceId] = cur.copy(inFlightDeferred = null)
                }
            }
            inFlightDeferred.completeExceptionally(e)
            throw e
        }

        mutex.withLock {
            if (finalResult is CameraResult.Success) {
                pendingOperations.remove(deviceId)
            } else {
                val cur = pendingOperations[deviceId]
                if (cur?.inFlightDeferred === inFlightDeferred) {
                    pendingOperations[deviceId] = cur.copy(inFlightDeferred = null)
                }
            }
        }
        inFlightDeferred.complete(finalResult)
        return finalResult
    }

    private suspend fun handleStopCommandResult(
        deviceId: TrackerId,
        result: CommandResult
    ): CameraResult {
        return when (result) {
            is CommandResult.Accepted, is CommandResult.Completed -> {
                val newState = updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.IDLE,
                        requestedConfig = null,
                        actualConfig = null,
                        lastError = null,
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.Success(newState)
            }
            is CommandResult.Rejected -> {
                val errorMsg = "Stop camera command rejected: ${result.reason}"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.CommandFailure(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
            is CommandResult.Timeout -> {
                val errorMsg = "Stop camera command timed out"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.Timeout(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
            is CommandResult.Failed -> {
                val errorMsg = "Stop camera command failed: ${result.reason}"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.CommandFailure(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
            else -> {
                val errorMsg = "Stop camera encountered unexpected result: $result"
                updateState(deviceId) { current ->
                    current.copy(
                        lifecycleState = StreamLifecycleState.ERROR,
                        lastError = CameraError.CommandFailure(errorMsg),
                        lastUpdatedTimestamp = timeProvider()
                    )
                }
                CameraResult.CommandFailed(deviceId, errorMsg)
            }
        }
    }

    override suspend fun switchCamera(
        deviceId: TrackerId,
        facing: CameraFacing,
        session: Session?,
        cameraId: String?
    ): CameraResult {
        // 1. Authorization evaluation
        if (authEngine != null) {
            val authResult = authEngine.authorize(
                session = session,
                deviceId = deviceId.value,
                service = ServicePermission.CAMERA,
                action = Action.EXECUTE,
                currentTime = timeProvider()
            )
            if (authResult !is AuthorizationResult.Allowed) {
                return CameraResult.PermissionDenied(
                    deviceId = deviceId,
                    reason = "Camera service permission denied ($authResult)"
                )
            }
        }

        // 2. Lifecycle state transition validation
        val currentState = mutex.withLock {
            statesFlow.value[deviceId] ?: TrackerCameraState.initial(deviceId, timeProvider())
        }
        if (!currentState.lifecycleState.canSwitch) {
            return CameraResult.InvalidStateTransition(
                currentState = currentState.lifecycleState,
                attemptedAction = "SWITCH",
                reason = "Cannot switch camera while in state '${currentState.lifecycleState}' (must be STREAMING)"
            )
        }

        // 3. Mark SWITCHING
        updateState(deviceId) { current ->
            current.copy(
                lifecycleState = StreamLifecycleState.SWITCHING,
                lastUpdatedTimestamp = timeProvider()
            )
        }

        val currentRequested = currentState.requestedConfig ?: CameraCaptureConfig.DEFAULT
        val updatedRequestedConfig = currentRequested.copy(
            facing = facing,
            cameraId = cameraId ?: currentRequested.cameraId
        )

        // 4. Dispatch SwitchCamera command with new facing/cameraId
        if (sessionManager != null) {
            val command = TrackerCommand.SwitchCamera(
                commandId = commandIdGenerator(),
                deviceId = deviceId,
                timestamp = timeProvider(),
                actorId = session?.user?.id ?: "controller",
                cameraId = updatedRequestedConfig.cameraId,
                width = updatedRequestedConfig.resolution.width,
                height = updatedRequestedConfig.resolution.height,
                fps = updatedRequestedConfig.fps
            )
            val result = try {
                sessionManager.sendCommand(command)
            } catch (e: CancellationException) {
                updateState(deviceId) { current ->
                    if (current.lifecycleState == StreamLifecycleState.SWITCHING) {
                        currentState.copy(lastUpdatedTimestamp = timeProvider())
                    } else {
                        current
                    }
                }
                throw e
            }
            return when (result) {
                is CommandResult.Accepted, is CommandResult.Completed -> {
                    val newState = updateState(deviceId) { current ->
                        current.copy(
                            lifecycleState = StreamLifecycleState.STREAMING,
                            requestedConfig = updatedRequestedConfig,
                            lastUpdatedTimestamp = timeProvider()
                        )
                    }
                    CameraResult.Success(newState)
                }
                else -> {
                    val errorMsg = "Camera switch failed on dispatch: $result"
                    updateState(deviceId) { current ->
                        current.copy(
                            lifecycleState = StreamLifecycleState.ERROR,
                            lastError = CameraError.CommandFailure(errorMsg),
                            lastUpdatedTimestamp = timeProvider()
                        )
                    }
                    CameraResult.CommandFailed(deviceId, errorMsg)
                }
            }
        }

        if (allowStandaloneFallback) {
            val newState = updateState(deviceId) { current ->
                current.copy(
                    lifecycleState = StreamLifecycleState.STREAMING,
                    requestedConfig = updatedRequestedConfig,
                    lastUpdatedTimestamp = timeProvider()
                )
            }
            return CameraResult.Success(newState)
        }

        val errorMsg = "TrackerSessionManager not configured; command cannot be dispatched"
        updateState(deviceId) { current ->
            current.copy(
                lifecycleState = StreamLifecycleState.ERROR,
                lastError = CameraError.CommandFailure(errorMsg),
                lastUpdatedTimestamp = timeProvider()
            )
        }
        return CameraResult.CommandFailed(deviceId, errorMsg)
    }

    override suspend fun updateActualConfig(
        deviceId: TrackerId,
        actualConfig: ActualCaptureConfig
    ) {
        updateState(deviceId) { current ->
            current.copy(
                actualConfig = actualConfig,
                lastUpdatedTimestamp = timeProvider()
            )
        }
    }

    override suspend fun clearCameraState(deviceId: TrackerId) {
        mutex.withLock {
            statesFlow.update { it - deviceId }
            pendingOperations.remove(deviceId)
        }
    }

    override suspend fun emergencyRevokeStream(
        deviceId: TrackerId,
        reason: String
    ): CameraResult {
        val (state, commandId) = mutex.withLock {
            val current = statesFlow.value[deviceId] ?: TrackerCameraState.initial(deviceId, timeProvider())
            if (current.lifecycleState == StreamLifecycleState.IDLE) {
                return CameraResult.Success(current)
            }
            val cid = commandIdGenerator()
            val stoppingState = current.copy(
                lifecycleState = StreamLifecycleState.STOPPING,
                lastUpdatedTimestamp = timeProvider()
            )
            statesFlow.update { it + (deviceId to stoppingState) }
            Pair(current, cid)
        }

        return try {
            if (sessionManager != null) {
                val command = TrackerCommand.StopCamera(
                    commandId = commandId,
                    deviceId = deviceId,
                    timestamp = timeProvider(),
                    actorId = "system:revocation"
                )
                try {
                    sessionManager.sendCommand(command)
                } catch (e: Exception) {
                    // best effort dispatch
                }
            }
            val finalState = updateState(deviceId) { current ->
                current.copy(
                    lifecycleState = StreamLifecycleState.IDLE,
                    requestedConfig = null,
                    actualConfig = null,
                    lastError = CameraError.PermissionDenied("Stream terminated by revocation: $reason"),
                    lastUpdatedTimestamp = timeProvider()
                )
            }
            mutex.withLock {
                pendingOperations.remove(deviceId)
            }
            CameraResult.Success(finalState)
        } catch (e: Exception) {
            val errorState = updateState(deviceId) { current ->
                current.copy(
                    lifecycleState = StreamLifecycleState.IDLE,
                    lastError = CameraError.CommandFailure("Emergency revocation failed: ${e.message}"),
                    lastUpdatedTimestamp = timeProvider()
                )
            }
            CameraResult.Success(errorState)
        }
    }

    private suspend fun updateState(
        deviceId: TrackerId,
        transform: (TrackerCameraState) -> TrackerCameraState
    ): TrackerCameraState {
        return mutex.withLock {
            val current = statesFlow.value[deviceId] ?: TrackerCameraState.initial(deviceId, timeProvider())
            val updated = transform(current)
            statesFlow.update { it + (deviceId to updated) }
            updated
        }
    }
}

/**
 * Tracks in-flight and prior operations to maintain stable command identity across retries
 * and coalesce concurrent dispatches for a tracker device.
 */
private data class PendingOperation(
    val action: String,
    val commandId: String,
    val config: CameraCaptureConfig? = null,
    val inFlightDeferred: CompletableDeferred<CameraResult>? = null
)

