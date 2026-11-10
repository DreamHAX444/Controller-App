package com.livetracker.controller.core.tracker.supabase

import android.util.Log
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.DevicePermission
import com.livetracker.controller.core.auth.domain.FeaturePermissionMapper
import com.livetracker.controller.core.auth.domain.RevocationEventRecord
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.command.CommandGateway
import com.livetracker.controller.core.tracker.command.DefaultCommandGateway
import com.livetracker.controller.core.tracker.command.model.CommandAckEnvelope
import com.livetracker.controller.core.tracker.command.persistence.CommandStateStore
import com.livetracker.controller.core.tracker.command.persistence.CommandStatus
import com.livetracker.controller.core.tracker.domain.CommandResult
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerCommand
import com.livetracker.controller.core.tracker.domain.TrackerEvent
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.location.data.LocationData
import com.livetracker.controller.core.tracker.webrtc.datachannel.TrackerCommandEnvelope
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Manages Supabase Realtime WebSocket connection, location telemetry,
 * and PostgREST initial state hydration across all trackers.
 *
 * Gated strictly by user session and role. Visitor mode is completely isolated
 * with ZERO Supabase network calls.
 * Insecure command broadcast fallback is eliminated; protected commands require
 * the secure [CommandGateway].
 */
@kotlinx.serialization.Serializable
data class ServerCommandStatusRecord(
    val id: Long? = null,
    @kotlinx.serialization.SerialName("device_id")
    val deviceId: String? = null,
    @kotlinx.serialization.SerialName("idempotency_key")
    val idempotencyKey: String? = null,
    val status: String,
    @kotlinx.serialization.SerialName("result_payload")
    val resultPayload: JsonElement? = null
) {
    constructor(
        id: Long? = null,
        deviceId: String? = null,
        idempotencyKey: String? = null,
        status: String,
        resultPayload: String?
    ) : this(
        id = id,
        deviceId = deviceId,
        idempotencyKey = idempotencyKey,
        status = status,
        resultPayload = resultPayload?.let {
            try {
                kotlinx.serialization.json.Json.parseToJsonElement(it)
            } catch (_: Exception) {
                JsonPrimitive(it)
            }
        }
    )
}

@kotlinx.serialization.Serializable
data class DeviceRecord(
    @kotlinx.serialization.SerialName("device_id")
    val deviceId: String,
    val name: String = "",
    val model: String = "",
    val status: String = "ACTIVE",
    @kotlinx.serialization.SerialName("is_legacy")
    val isLegacy: Boolean = false,
    @kotlinx.serialization.SerialName("hardware_uuid")
    val hardwareUuid: String? = null,
    @kotlinx.serialization.SerialName("created_at")
    val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at")
    val updatedAt: String? = null,
    @kotlinx.serialization.SerialName("last_seen_at")
    val lastSeenAt: String? = null
)

@kotlinx.serialization.Serializable
data class DeviceAssignmentRecord(
    val id: Long? = null,
    @kotlinx.serialization.SerialName("user_id")
    val userId: String,
    @kotlinx.serialization.SerialName("device_id")
    val deviceId: String,
    val status: String = "ACTIVE",
    @kotlinx.serialization.SerialName("assigned_at")
    val assignedAt: String? = null,
    @kotlinx.serialization.SerialName("expires_at")
    val expiresAt: String? = null
)

@kotlinx.serialization.Serializable
data class UserProfileRecord(
    val id: String,
    val email: String? = null,
    @kotlinx.serialization.SerialName("display_name")
    val displayName: String = "",
    @kotlinx.serialization.SerialName("avatar_url")
    val avatarUrl: String? = null,
    val role: String = "USER",
    val status: String = "ACTIVE"
)

@kotlinx.serialization.Serializable
data class AssignDeviceRpcParams(
    @kotlinx.serialization.SerialName("p_device_id")
    val deviceId: String,
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String,
    @kotlinx.serialization.SerialName("p_expires_at")
    val expiresAt: String? = null
)

@kotlinx.serialization.Serializable
data class UnassignDeviceRpcParams(
    @kotlinx.serialization.SerialName("p_device_id")
    val deviceId: String,
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String
)

@kotlinx.serialization.Serializable
data class DeviceFeaturePermissionRecord(
    val id: Long? = null,
    @kotlinx.serialization.SerialName("assignment_id")
    val assignmentId: Long,
    val feature: String,
    @kotlinx.serialization.SerialName("is_allowed")
    val isAllowed: Boolean = true,
    @kotlinx.serialization.SerialName("granted_at")
    val grantedAt: String? = null,
    @kotlinx.serialization.SerialName("expires_at")
    val expiresAt: String? = null,
    @kotlinx.serialization.SerialName("granted_by")
    val grantedBy: String? = null
)

@kotlinx.serialization.Serializable
data class ApproveUserAccountRpcParams(
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String
)

@kotlinx.serialization.Serializable
data class UpdateUserRoleRpcParams(
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String,
    @kotlinx.serialization.SerialName("p_new_role")
    val newRole: String
)

@kotlinx.serialization.Serializable
data class UpdateUserStatusRpcParams(
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String,
    @kotlinx.serialization.SerialName("p_new_status")
    val newStatus: String
)

@kotlinx.serialization.Serializable
data class DeleteUserAccountRpcParams(
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String
)

@kotlinx.serialization.Serializable
data class GrantFeaturePermissionRpcParams(
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String,
    @kotlinx.serialization.SerialName("p_device_id")
    val deviceId: String,
    @kotlinx.serialization.SerialName("p_feature")
    val feature: String,
    @kotlinx.serialization.SerialName("p_expires_at")
    val expiresAt: String? = null
)

@kotlinx.serialization.Serializable
data class RevokeFeaturePermissionRpcParams(
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String,
    @kotlinx.serialization.SerialName("p_device_id")
    val deviceId: String,
    @kotlinx.serialization.SerialName("p_feature")
    val feature: String
)

@kotlinx.serialization.Serializable
data class UpsertFeaturePermissionParams(
    @kotlinx.serialization.SerialName("assignment_id")
    val assignmentId: Long,
    val feature: String,
    @kotlinx.serialization.SerialName("is_allowed")
    val isAllowed: Boolean
)

@kotlinx.serialization.Serializable
data class FetchUserEffectivePermissionsParams(
    @kotlinx.serialization.SerialName("p_user_id")
    val userId: String? = null
)

@kotlinx.serialization.Serializable
data class EffectiveAssignmentRecord(
    @kotlinx.serialization.SerialName("assignment_id")
    val assignmentId: Long? = null,
    @kotlinx.serialization.SerialName("device_id")
    val deviceId: String,
    @kotlinx.serialization.SerialName("device_name")
    val deviceName: String = "",
    @kotlinx.serialization.SerialName("device_status")
    val deviceStatus: String = "ACTIVE",
    val status: String = "ACTIVE",
    @kotlinx.serialization.SerialName("assigned_at")
    val assignedAt: String? = null,
    @kotlinx.serialization.SerialName("expires_at")
    val expiresAt: String? = null,
    val features: List<String> = emptyList()
)

@kotlinx.serialization.Serializable
data class EffectivePermissionsResponse(
    @kotlinx.serialization.SerialName("user_id")
    val userId: String,
    val email: String? = null,
    @kotlinx.serialization.SerialName("display_name")
    val displayName: String = "",
    @kotlinx.serialization.SerialName("avatar_url")
    val avatarUrl: String? = null,
    val role: String = "USER",
    val status: String = "ACTIVE",
    @kotlinx.serialization.SerialName("is_owner")
    val isOwner: Boolean = false,
    @kotlinx.serialization.SerialName("created_at")
    val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at")
    val updatedAt: String? = null,
    val assignments: List<EffectiveAssignmentRecord> = emptyList()
)

open class SupabaseFleetManager(
    private val clientProvider: () -> SupabaseClient = { SupabaseClientProvider.client },
    val scope: CoroutineScope,
    private val commandGateway: CommandGateway? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val commandStateStore: CommandStateStore? = null,
    private val serverStatusVerifier: (suspend (deviceId: String, idempotencyKey: String, sequenceId: Long?) -> ServerCommandStatusRecord?)? = null,
    private val sessionRepository: SessionRepository? = null
) {
    private val tag = "SupabaseFleetManager"
    private val client get() = clientProvider()
    var onCommandAckReceived: ((CommandAckEnvelope) -> Unit)? = null

    protected val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    protected val _locationEvents = MutableSharedFlow<TrackerEvent.LocationUpdated>(extraBufferCapacity = 128)
    val locationEvents: SharedFlow<TrackerEvent.LocationUpdated> = _locationEvents.asSharedFlow()

    protected val _rawTelemetry = MutableSharedFlow<LocationData>(extraBufferCapacity = 128)
    val rawTelemetry: SharedFlow<LocationData> = _rawTelemetry.asSharedFlow()

    private var locationsChannel: RealtimeChannel? = null
    private var commandsChannel: RealtimeChannel? = null
    private var revocationsChannel: RealtimeChannel? = null

    private var locationListenJob: Job? = null
    private var commandListenJob: Job? = null
    private var revocationListenJob: Job? = null
    private var statusObserveJob: Job? = null
    private var reconnectJob: Job? = null
    private val channelMutex = Mutex()

    protected val authorizedDeviceIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    protected val latestDeviceTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    protected val _revocationEvents = MutableSharedFlow<RevocationEventRecord>(extraBufferCapacity = 64)
    val revocationEvents: SharedFlow<RevocationEventRecord> = _revocationEvents.asSharedFlow()

    private val pendingCommands = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<CommandResult>>()

    protected val inMemoryProfiles = java.util.concurrent.CopyOnWriteArrayList<UserProfileRecord>()
    protected val inMemoryAssignments = java.util.concurrent.CopyOnWriteArrayList<DeviceAssignmentRecord>()
    protected val inMemoryFeaturePermissions = java.util.concurrent.CopyOnWriteArrayList<DeviceFeaturePermissionRecord>()

    @Volatile
    protected var currentSession: Session? = null

    fun setCurrentSessionForTesting(session: Session?) {
        currentSession = session
    }

    fun setAuthorizedDevices(deviceIds: Collection<String>) {
        authorizedDeviceIds.clear()
        authorizedDeviceIds.addAll(deviceIds)
        Log.i(tag, "Updated authorized devices set: ${authorizedDeviceIds.size} devices")
    }

    fun addAuthorizedDevice(deviceId: String) {
        authorizedDeviceIds.add(deviceId)
        Log.i(tag, "Added authorized device: $deviceId")
    }

    fun removeAuthorizedDevice(deviceId: String) {
        authorizedDeviceIds.remove(deviceId)
        Log.i(tag, "Removed authorized device: $deviceId")
    }

    fun isDeviceAuthorized(deviceId: String): Boolean {
        val session = currentSession ?: return false
        if (session.user.accountStatus != AccountStatus.ACTIVE) return false
        return when (session.user.role) {
            Role.OWNER, Role.ADMIN -> true
            Role.USER -> {
                if (authorizedDeviceIds.contains(deviceId)) return true
                val cleanId = deviceId.substringBefore(" (").trim()
                authorizedDeviceIds.any { cleanId.equals(it.substringBefore(" (").trim(), ignoreCase = true) }
            }
            Role.VISITOR -> false
        }
    }

    fun clearMonotonicTimestamps() {
        latestDeviceTimestamps.clear()
    }

    val lastReceivedTimestamps: Map<String, Long> get() = latestDeviceTimestamps
    fun getLastReceivedTimestamp(deviceId: String): Long? = latestDeviceTimestamps[deviceId]
    fun getLastReceivedTimestamp(deviceId: TrackerId): Long? = latestDeviceTimestamps[deviceId.value]
    fun getCommandsChannel(): RealtimeChannel? = commandsChannel

    // NOTE: Eager `init { observeRealtimeStatus() }` removed to guarantee zero network activity on startup.

    protected open fun observeRealtimeStatus() {
        statusObserveJob?.cancel()
        statusObserveJob = scope.launch {
            try {
                client.realtime.status.collect { status ->
                    Log.d(tag, "Supabase Realtime status: $status")
                    val mapped = when (status) {
                        Realtime.Status.CONNECTED -> ConnectionState.CONNECTED
                        Realtime.Status.CONNECTING -> ConnectionState.CONNECTING
                        Realtime.Status.DISCONNECTED -> ConnectionState.DISCONNECTED
                    }
                    if (_connectionState.value != mapped) {
                        _connectionState.value = mapped
                    }
                    if (status == Realtime.Status.CONNECTED) {
                        reconnectJob?.cancel()
                        reconnectJob = scope.launch {
                            channelMutex.withLock {
                                try {
                                    val locStatus = locationsChannel?.status?.value
                                    if (currentSession != null &&
                                        locStatus != RealtimeChannel.Status.SUBSCRIBED &&
                                        locStatus != RealtimeChannel.Status.SUBSCRIBING
                                    ) {
                                        setupLocationsChannel()
                                    }
                                    val cmdStatus = commandsChannel?.status?.value
                                    if (currentSession != null &&
                                        cmdStatus != RealtimeChannel.Status.SUBSCRIBED &&
                                        cmdStatus != RealtimeChannel.Status.SUBSCRIBING
                                    ) {
                                        setupCommandsChannel()
                                    }
                                    val revStatus = revocationsChannel?.status?.value
                                    if (currentSession != null &&
                                        revStatus != RealtimeChannel.Status.SUBSCRIBED &&
                                        revStatus != RealtimeChannel.Status.SUBSCRIBING
                                    ) {
                                        setupRevocationsChannel()
                                    }
                                } catch (e: Exception) {
                                    Log.w(tag, "Failed to re-establish channels on reconnect: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Error in status observer: ${e.message}")
            }
        }
    }

    /**
     * Starts the Supabase fleet connection for an authenticated non-visitor session.
     * Guaranteed ZERO Supabase calls if role is VISITOR or session is invalid.
     */
    open suspend fun startFleet(
        session: Session,
        currentTime: Long = timeProvider()
    ): Boolean {
        if (!session.isValid(currentTime)) {
            Log.w(tag, "startFleet rejected: Session invalid or expired")
            return false
        }
        if (session.user.role == Role.VISITOR) {
            Log.i(tag, "startFleet rejected: Visitor role active. Supabase fleet transport disabled for isolation.")
            return false
        }
        if (session.user.accountStatus != AccountStatus.ACTIVE) {
            Log.w(tag, "startFleet rejected: Account status is ${session.user.accountStatus}")
            return false
        }

        channelMutex.withLock {
            if (currentSession?.sessionId == session.sessionId &&
                _connectionState.value == ConnectionState.CONNECTED &&
                locationsChannel != null &&
                commandsChannel != null &&
                revocationsChannel != null
            ) {
                return true
            }

            if (currentSession != null && currentSession?.sessionId != session.sessionId) {
                internalStop()
            }

            currentSession = session
            Log.i(tag, "Starting Supabase fleet for user '${session.user.id}' [Role: ${session.user.role}]")

            try {
                observeRealtimeStatus()
                client.realtime.connect()
                val locStatus = locationsChannel?.status?.value
                if (locStatus != RealtimeChannel.Status.SUBSCRIBED && locStatus != RealtimeChannel.Status.SUBSCRIBING) {
                    setupLocationsChannel()
                }
                val cmdStatus = commandsChannel?.status?.value
                if (cmdStatus != RealtimeChannel.Status.SUBSCRIBED && cmdStatus != RealtimeChannel.Status.SUBSCRIBING) {
                    setupCommandsChannel()
                }
                val revStatus = revocationsChannel?.status?.value
                if (revStatus != RealtimeChannel.Status.SUBSCRIBED && revStatus != RealtimeChannel.Status.SUBSCRIBING) {
                    setupRevocationsChannel()
                }
                return true
            } catch (e: Exception) {
                Log.e(tag, "Failed to connect Supabase fleet: ${e.message}", e)
                _connectionState.value = ConnectionState.FAILED
                return false
            }
        }
    }

    open suspend fun start() {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role == Role.VISITOR) {
            Log.w(tag, "start() rejected: No active non-visitor session")
            return
        }
        startFleet(session)
    }

    open suspend fun stopFleet() {
        channelMutex.withLock {
            internalStop()
        }
    }

    open suspend fun stop() = stopFleet()

    private suspend fun internalStop() {
        Log.i(tag, "Stopping Supabase fleet and cleaning up subscriptions/channels")
        val hadActiveSession = currentSession != null || locationsChannel != null || commandsChannel != null || revocationsChannel != null
        currentSession = null

        reconnectJob?.cancel()
        reconnectJob = null

        statusObserveJob?.cancel()
        statusObserveJob = null

        locationListenJob?.cancel()
        locationListenJob = null

        commandListenJob?.cancel()
        commandListenJob = null

        revocationListenJob?.cancel()
        revocationListenJob = null

        latestDeviceTimestamps.clear()

        pendingCommands.forEach { (cmdId, deferred) ->
            deferred.complete(CommandResult.Failed(cmdId, TrackerId("unknown"), "Fleet stopped"))
        }
        pendingCommands.clear()

        if (hadActiveSession) {
            cleanupSubscription("public:locations")
            cleanupSubscription("public:commands")
            cleanupSubscription("public:revocations")

            try {
                val activeChannels = client.realtime.subscriptions.values.toList()
                for (channel in activeChannels) {
                    try {
                        channel.unsubscribe()
                    } catch (_: Exception) {}
                    try {
                        client.realtime.removeChannel(channel)
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(tag, "Error cleaning up realtime subscriptions: ${e.message}")
            }

            locationsChannel = null
            commandsChannel = null
            revocationsChannel = null

            try {
                client.realtime.disconnect()
            } catch (e: Exception) {
                Log.w(tag, "Error disconnecting realtime: ${e.message}")
            }
        }
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private suspend fun setupLocationsChannel() {
        val currentStatus = locationsChannel?.status?.value
        if (currentStatus == RealtimeChannel.Status.SUBSCRIBED || currentStatus == RealtimeChannel.Status.SUBSCRIBING) return
        val topic = "public:locations"
        cleanupSubscription(topic)

        val channel = client.channel(topic)
        val broadcastFlow = channel.broadcastFlow<LocationData>(event = "location")
        val heartbeatFlow = channel.broadcastFlow<LocationData>(event = "heartbeat")
        locationListenJob?.cancel()
        locationListenJob = scope.launch {
            launch {
                broadcastFlow.collect { data ->
                    handleIncomingLocation(data)
                }
            }
            launch {
                heartbeatFlow.collect { data ->
                    handleIncomingHeartbeat(data)
                }
            }
        }

        try {
            channel.subscribe(blockUntilSubscribed = false)
            locationsChannel = channel
            Log.i(tag, "Subscribed to public:locations channel")
        } catch (e: Exception) {
            locationListenJob?.cancel()
            locationListenJob = null
            try { channel.unsubscribe() } catch (_: Exception) {}
            try { client.realtime.removeChannel(channel) } catch (_: Exception) {}
            throw e
        }
    }

    private suspend fun setupCommandsChannel() {
        val currentStatus = commandsChannel?.status?.value
        if (currentStatus == RealtimeChannel.Status.SUBSCRIBED || currentStatus == RealtimeChannel.Status.SUBSCRIBING) return
        val topic = "public:commands"
        cleanupSubscription(topic)

        val channel = client.channel(topic)
        val broadcastFlow = channel.broadcastFlow<TrackerCommandEnvelope>(event = "command")
        val ackFlow = channel.broadcastFlow<CommandAckEnvelope>(event = "command_ack")
        commandListenJob?.cancel()
        commandListenJob = scope.launch {
            launch {
                broadcastFlow.collect { envelope ->
                    handleCommandResponse(envelope)
                }
            }
            launch {
                ackFlow.collect { ack ->
                    handleCommandAck(ack)
                }
            }
        }

        try {
            channel.subscribe(blockUntilSubscribed = false)
            commandsChannel = channel
            (commandGateway as? DefaultCommandGateway)?.bindCommandsChannel(channel)
            Log.i(tag, "Subscribed to public:commands channel")
        } catch (e: Exception) {
            commandListenJob?.cancel()
            commandListenJob = null
            try { channel.unsubscribe() } catch (_: Exception) {}
            try { client.realtime.removeChannel(channel) } catch (_: Exception) {}
            throw e
        }
    }

    private suspend fun setupRevocationsChannel() {
        val currentStatus = revocationsChannel?.status?.value
        if (currentStatus == RealtimeChannel.Status.SUBSCRIBED || currentStatus == RealtimeChannel.Status.SUBSCRIBING) return
        val topic = "public:revocations"
        cleanupSubscription(topic)

        val channel = client.channel(topic)
        val broadcastFlow = channel.broadcastFlow<RevocationEventRecord>(event = "revocation")
        revocationListenJob?.cancel()
        revocationListenJob = scope.launch {
            broadcastFlow.collect { event ->
                handleIncomingRevocation(event)
            }
        }

        try {
            channel.subscribe(blockUntilSubscribed = false)
            revocationsChannel = channel
            Log.i(tag, "Subscribed to public:revocations channel")
        } catch (e: Exception) {
            revocationListenJob?.cancel()
            revocationListenJob = null
            try { channel.unsubscribe() } catch (_: Exception) {}
            try { client.realtime.removeChannel(channel) } catch (_: Exception) {}
            throw e
        }
    }

    private suspend fun handleIncomingRevocation(event: RevocationEventRecord) {
        val session = currentSession ?: return
        val currentUserId = session.user.id
        val isTarget = event.targetUserId == currentUserId ||
                session.user.role == Role.OWNER ||
                session.user.role == Role.ADMIN

        if (isTarget) {
            Log.i(tag, "Processing revocation event: type=${event.eventType}, target=${event.targetUserId}, device=${event.deviceId}, feature=${event.feature}")
            _revocationEvents.emit(event)
        }
    }

    private suspend fun cleanupSubscription(topic: String) {
        client.realtime.subscriptions.values.find { it.topic == topic || it.topic == "realtime:$topic" || it.topic.endsWith(topic) }?.let { stale ->
            try {
                stale.unsubscribe()
            } catch (_: Exception) {}
            try {
                client.realtime.removeChannel(stale)
            } catch (_: Exception) {}
        }
    }

    private val knownFleetDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal suspend fun handleIncomingHeartbeat(data: LocationData) {
        if (data.deviceId.isBlank() || data.createdAt.isNullOrBlank()) {
            return
        }

        if (!isDeviceAuthorized(data.deviceId)) {
            Log.w(tag, "Suppressed unauthorized heartbeat packet for device '${data.deviceId}'")
            return
        }

        val packetTimestamp = data.parseTimestamp()
        val prevTimestamp = latestDeviceTimestamps[data.deviceId]
        if (prevTimestamp != null && packetTimestamp <= prevTimestamp) {
            return
        }
        latestDeviceTimestamps[data.deviceId] = packetTimestamp

        if (knownFleetDevices.add(data.deviceId)) {
            scope.launch {
                registerDeviceInCloud(
                    DeviceRecord(
                        deviceId = data.deviceId,
                        name = data.deviceId,
                        model = data.deviceId,
                        status = "ACTIVE"
                    )
                )
            }
        }

        Log.i(tag, "Received valid presence heartbeat from '${data.deviceId}': bat=${data.batteryLevel}, net=${data.networkType}")
        _rawTelemetry.emit(data)
    }

    open suspend fun reconnectRealtime() {
        channelMutex.withLock {
            try {
                if (client.realtime.status.value != io.github.jan.supabase.realtime.Realtime.Status.CONNECTED) {
                    Log.i(tag, "Reconnecting Supabase fleet realtime transport...")
                    client.realtime.connect()
                }
                val locStatus = locationsChannel?.status?.value
                if (locStatus != RealtimeChannel.Status.SUBSCRIBED && locStatus != RealtimeChannel.Status.SUBSCRIBING) {
                    setupLocationsChannel()
                }
                val cmdStatus = commandsChannel?.status?.value
                if (cmdStatus != RealtimeChannel.Status.SUBSCRIBED && cmdStatus != RealtimeChannel.Status.SUBSCRIBING) {
                    setupCommandsChannel()
                }
                val revStatus = revocationsChannel?.status?.value
                if (revStatus != RealtimeChannel.Status.SUBSCRIBED && revStatus != RealtimeChannel.Status.SUBSCRIBING) {
                    setupRevocationsChannel()
                }
            } catch (e: Exception) {
                Log.w(tag, "reconnectRealtime error: ${e.message}")
            }
        }
    }

    internal suspend fun handleIncomingLocation(data: LocationData) {
        if (data.deviceId.isBlank() || data.createdAt.isNullOrBlank() || !data.isValidCoordinates()) {
            Log.w(tag, "Rejected invalid telemetry packet from '${data.deviceId}': coordinates invalid or blank")
            return
        }

        if (!isDeviceAuthorized(data.deviceId)) {
            Log.w(tag, "Suppressed unauthorized telemetry packet for device '${data.deviceId}'")
            return
        }

        val packetTimestamp = data.parseTimestamp()
        val prevTimestamp = latestDeviceTimestamps[data.deviceId]
        if (prevTimestamp != null && packetTimestamp <= prevTimestamp) {
            Log.d(tag, "Stale-Packet Shield: Dropping out-of-order packet for device '${data.deviceId}' (pkt=$packetTimestamp <= lastSeen=$prevTimestamp)")
            return
        }
        latestDeviceTimestamps[data.deviceId] = packetTimestamp

        if (knownFleetDevices.add(data.deviceId)) {
            scope.launch {
                registerDeviceInCloud(
                    DeviceRecord(
                        deviceId = data.deviceId,
                        name = data.deviceId,
                        model = data.deviceId,
                        status = "ACTIVE"
                    )
                )
            }
        }

        Log.i(tag, "Received valid location broadcast from '${data.deviceId}': acc=${data.accuracy}")
        _rawTelemetry.emit(data)
        val event = data.toLocationUpdatedEvent(parsedTimestamp = packetTimestamp)
        _locationEvents.emit(event)
    }

    internal fun registerPendingCommand(correlationId: String, deferred: CompletableDeferred<CommandResult>) {
        pendingCommands[correlationId] = deferred
    }

    protected open suspend fun queryServerCommandStatus(
        deviceId: String,
        idempotencyKey: String,
        sequenceId: Long?
    ): ServerCommandStatusRecord? {
        if (serverStatusVerifier != null) {
            return serverStatusVerifier.invoke(deviceId, idempotencyKey, sequenceId)
        }
        return try {
            val response = client.postgrest["commands"].select {
                filter {
                    eq("device_id", deviceId)
                    eq("idempotency_key", idempotencyKey)
                    if (sequenceId != null) {
                        eq("id", sequenceId)
                    }
                }
                limit(1)
            }
            val records = response.decodeList<ServerCommandStatusRecord>()
            records.firstOrNull()
        } catch (e: Exception) {
            Log.w(tag, "queryServerCommandStatus failed for device=$deviceId, key=$idempotencyKey: ${e.message}")
            null
        }
    }

    internal fun handleCommandResponse(envelope: TrackerCommandEnvelope) {
        val correlationId = envelope.effectiveCorrelationId ?: return
        val idKey = envelope.idempotencyKey ?: correlationId
        val seqId = envelope.id
        val targetDeviceId = envelope.deviceId

        scope.launch {
            try {
                // Verify server-side status via PostgREST before accepting transition
                var verifiedRecord = queryServerCommandStatus(targetDeviceId, idKey, seqId)
                if (verifiedRecord != null && verifiedRecord.status.lowercase() == "pending") {
                    for (retry in 1..4) {
                        kotlinx.coroutines.delay(250)
                        val retryRecord = queryServerCommandStatus(targetDeviceId, idKey, seqId)
                        if (retryRecord != null && retryRecord.status.lowercase() != "pending") {
                            verifiedRecord = retryRecord
                            break
                        }
                    }
                }
                if (verifiedRecord == null) {
                    Log.w(tag, "Ignoring unverified command broadcast for key '$idKey': not found on server")
                    return@launch
                }

                // Strict device, idempotency, and sequence binding check
                if (verifiedRecord.deviceId != null && verifiedRecord.deviceId != targetDeviceId) {
                    Log.w(tag, "Rejecting broadcast for key '$idKey': device mismatch (server: '${verifiedRecord.deviceId}', broadcast: '$targetDeviceId')")
                    return@launch
                }
                if (verifiedRecord.idempotencyKey != null && verifiedRecord.idempotencyKey != idKey) {
                    Log.w(tag, "Rejecting broadcast for key '$idKey': idempotency mismatch (server: '${verifiedRecord.idempotencyKey}')")
                    return@launch
                }
                if (seqId != null && verifiedRecord.id != null && verifiedRecord.id != seqId) {
                    Log.w(tag, "Rejecting broadcast for key '$idKey': sequence ID mismatch (server: '${verifiedRecord.id}', broadcast: '$seqId')")
                    return@launch
                }

                val statusLower = verifiedRecord.status.lowercase()
                if (statusLower == "executed" || statusLower == "failed" || statusLower == "error") {
                    val deferred = pendingCommands.remove(correlationId)
                        ?: pendingCommands.remove(idKey)

                    val resultPayloadStr = verifiedRecord.resultPayload?.let { elem ->
                        if (elem is JsonPrimitive && elem.isString) elem.content else elem.toString()
                    } ?: envelope.getParamsAsString()

                    val result = if (statusLower == "executed") {
                        CommandResult.Completed(correlationId, TrackerId(envelope.deviceId), resultPayloadStr)
                    } else {
                        CommandResult.Failed(correlationId, TrackerId(envelope.deviceId), resultPayloadStr ?: "Command failed")
                    }
                    deferred?.complete(result)

                    commandStateStore?.let { store ->
                        val newStatus = if (statusLower == "executed") {
                            CommandStatus.EXECUTED
                        } else {
                            CommandStatus.FAILED
                        }
                        store.updateStatus(idKey, newStatus, resultPayloadStr)
                        seqId?.let { sId ->
                            val existing = store.getCommandBySequenceId(sId)
                            if (existing != null && existing.idempotencyKey != idKey) {
                                store.updateStatus(existing.idempotencyKey, newStatus, resultPayloadStr)
                            }
                        }
                    }
                    Log.i(tag, "Command '$idKey' verified from server record: status='${verifiedRecord.status}'")
                } else {
                    Log.w(tag, "Ignoring premature or forged broadcast for key '$idKey': server status is '${verifiedRecord.status}'")
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to verify command status for '$idKey': ${e.message}")
            }
        }
    }

    internal suspend fun handleCommandAck(ack: CommandAckEnvelope) {
        onCommandAckReceived?.invoke(ack)
        val correlationId = ack.commandId ?: ack.idempotencyKey
        val deferred = pendingCommands.remove(ack.idempotencyKey)
            ?: (ack.commandId?.let { pendingCommands.remove(it) })
            ?: (ack.sequenceId?.let { pendingCommands.remove(it.toString()) })
            ?: pendingCommands.remove(correlationId)

        val isSuccess = ack.status.equals("COMPLETED", ignoreCase = true) ||
            ack.status.equals("EXECUTED", ignoreCase = true)

        val result = if (isSuccess) {
            CommandResult.Completed(
                commandId = ack.commandId ?: ack.idempotencyKey,
                deviceId = TrackerId("unknown"),
                payload = ack.resultPayload
            )
        } else {
            CommandResult.Failed(
                commandId = ack.commandId ?: ack.idempotencyKey,
                deviceId = TrackerId("unknown"),
                reason = ack.errorMessage ?: ack.resultPayload ?: "Command execution failed"
            )
        }
        deferred?.complete(result)

        commandStateStore?.let { store ->
            val newStatus = if (isSuccess) CommandStatus.EXECUTED else CommandStatus.FAILED
            store.updateStatus(ack.idempotencyKey, newStatus, ack.resultPayload ?: ack.errorMessage)
        }
        Log.i(tag, "Handled event-driven command_ack for key '${ack.idempotencyKey}': status='${ack.status}'")
    }

    /**
     * Reconciles effective permissions for the session or specified user from PostgreSQL via
     * the security definer RPC fetch_user_effective_permissions.
     * Updates local authorized device sets and triggers immediate revocation if status changed.
     */
    open suspend fun reconcileEffectivePermissions(userId: String? = null): EffectivePermissionsResponse? = withContext(Dispatchers.IO) {
        val session = currentSession ?: sessionRepository?.getActiveSession()?.getOrNull() ?: return@withContext null

        try {
            val targetUserId = userId ?: session.user.id
            if (targetUserId.length != 36 || !targetUserId.contains('-')) {
                // Non-UUID user (e.g. demo / visitor "u0"), skip remote Postgres RPC to avoid 42804 syntax error
                return@withContext null
            }
            val response = client.postgrest.rpc(
                function = "fetch_user_effective_permissions",
                parameters = FetchUserEffectivePermissionsParams(targetUserId)
            )
            val parsed = response.decodeAs<EffectivePermissionsResponse>()
            Log.i(tag, "Reconciled effective permissions for '${parsed.userId}': role=${parsed.role}, status=${parsed.status}, ${parsed.assignments.size} assignments")

            if (parsed.userId == session.user.id) {
                val assignedIds = parsed.assignments.map { it.deviceId }
                setAuthorizedDevices(assignedIds)

                sessionRepository?.let { repo ->
                    val current = repo.getActiveSession().getOrNull() ?: session
                    if (current.user.id == parsed.userId) {
                        val newRole = runCatching { Role.valueOf(parsed.role.trim().uppercase()) }.getOrDefault(current.user.role)
                        val newStatus = runCatching { AccountStatus.valueOf(parsed.status.trim().uppercase()) }.getOrDefault(current.user.accountStatus)

                        // Temporarily advance currentSession role/status if promoted so fetchFleetDevices() is not blocked
                        if (newRole != Role.VISITOR && newStatus == AccountStatus.ACTIVE) {
                            currentSession = current.copy(
                                user = current.user.copy(role = newRole, accountStatus = newStatus)
                            )
                        }

                        val newDevicePermissions = if (newRole == Role.ADMIN || newRole == Role.OWNER) {
                            val fleetDevs = fetchFleetDevices()
                            setAuthorizedDevices(fleetDevs.map { it.deviceId })
                            fleetDevs.map { dev ->
                                DevicePermission(
                                    deviceId = dev.deviceId,
                                    allowedServices = setOf(
                                        ServicePermission.LOCATION,
                                        ServicePermission.CAMERA,
                                        ServicePermission.MICROPHONE_AUDIO,
                                        ServicePermission.DEVICE_AUDIO,
                                        ServicePermission.SCREEN_CAPTURE,
                                        ServicePermission.FILE_READ,
                                        ServicePermission.FILE_WRITE,
                                        ServicePermission.FILE_DELETE,
                                        ServicePermission.FILE_RENAME,
                                        ServicePermission.FILE_TRANSFER
                                    )
                                )
                            }
                        } else if (newRole == Role.VISITOR) {
                            emptyList()
                        } else {
                            parsed.assignments.map { asgn ->
                                val services = FeaturePermissionMapper.toServicePermissions(asgn.features)
                                val effectiveServices = if (services.isEmpty() || asgn.features.any { it.equals("LOCATION", ignoreCase = true) }) {
                                    services + ServicePermission.LOCATION
                                } else {
                                    services
                                }
                                DevicePermission(
                                    deviceId = asgn.deviceId,
                                    allowedServices = effectiveServices
                                )
                            }
                        }

                        val updatedUser = current.user.copy(
                            role = newRole,
                            accountStatus = newStatus,
                            devicePermissions = newDevicePermissions,
                            displayName = parsed.displayName.ifBlank { current.user.displayName },
                            avatarUrl = parsed.avatarUrl ?: current.user.avatarUrl
                        )
                        currentSession = if (newRole == Role.VISITOR || newStatus != AccountStatus.ACTIVE) {
                            null
                        } else {
                            current.copy(user = updatedUser)
                        }
                        if (updatedUser != current.user) {
                            Log.i(tag, "Live session permissions/role updated for '${updatedUser.id}': role=${updatedUser.role}, status=${updatedUser.accountStatus}, devices=${updatedUser.devicePermissions.size}")
                            repo.updateSessionUser(updatedUser)
                        }
                    }
                }
            }

            if (parsed.userId == session.user.id && parsed.status != "ACTIVE") {
                Log.w(tag, "Account status changed to '${parsed.status}' during reconciliation! Emitting revocation event.")
                _revocationEvents.emit(
                    RevocationEventRecord(
                        targetUserId = parsed.userId,
                        eventType = "ACCOUNT_STATUS_CHANGED",
                        reason = parsed.status
                    )
                )
            }

            parsed
        } catch (e: Exception) {
            Log.w(tag, "Failed to reconcile effective permissions: ${e.message}")
            null
        }
    }

    /**
     * Fetches latest locations from PostgREST.
     * Gated behind valid non-visitor session. Returns emptyList() with ZERO network calls if Visitor.
     */
    open suspend fun fetchLatestLocations(): List<LocationData> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role == Role.VISITOR) {
            Log.w(tag, "fetchLatestLocations blocked: No active non-visitor session")
            return@withContext emptyList()
        }
        try {
            // Priority 1: Query optimized latest_device_locations view (1 row per device, never drops idle devices)
            try {
                val viewResult = client.postgrest["latest_device_locations"].select()
                val list = viewResult.decodeList<LocationData>()
                if (list.isNotEmpty()) {
                    return@withContext list.filter { it.isValidCoordinates() }
                }
            } catch (ve: Exception) {
                Log.w(tag, "latest_device_locations view query failed: ${ve.message}, falling back to locations table")
            }

            // Priority 2: Fallback query on locations table with limit 1000
            val result = client.postgrest["locations"].select {
                order("created_at", order = Order.DESCENDING)
                limit(1000)
            }
            val all = result.decodeList<LocationData>()
            // ⚡ Bolt: Replaced groupBy/map with distinctBy to reduce memory allocations and time complexity from O(N^2) to O(N)
            all.filter { it.isValidCoordinates() }
                .distinctBy { it.deviceId }
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch initial locations: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetches registered devices from PostgREST public.devices.
     * RLS strictly governs visibility:
     * - OWNER / ADMIN: All devices (including unassigned devices).
     * - USER: Only assigned devices where has_device_access(device_id) is true.
     * - VISITOR: Blocked, returns emptyList() with ZERO network calls.
     */
    open suspend fun fetchFleetDevices(): List<DeviceRecord> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role == Role.VISITOR) {
            Log.w(tag, "fetchFleetDevices blocked: No active non-visitor session")
            return@withContext emptyList()
        }
        try {
            val result = client.postgrest["devices"].select {
                order("created_at", order = Order.DESCENDING)
            }
            val list = result.decodeList<DeviceRecord>()
            list.forEach { knownFleetDevices.add(it.deviceId) }
            list
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch fleet devices: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Registers or updates a device record in public.devices.
     */
    open suspend fun registerDeviceInCloud(deviceRecord: DeviceRecord): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role == Role.VISITOR) {
            return@withContext Result.failure(IllegalStateException("No active non-visitor session"))
        }
        try {
            client.postgrest["devices"].upsert(deviceRecord)
            knownFleetDevices.add(deviceRecord.deviceId)
            Log.i(tag, "Upserted device '${deviceRecord.deviceId}' in public.devices")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(tag, "Failed to upsert device '${deviceRecord.deviceId}' in public.devices: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Permanently deletes a device and its associated records from Supabase.
     * Accessible by OWNER and ADMIN.
     */
    open suspend fun deleteDevice(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role == Role.VISITOR) {
            return@withContext Result.failure(IllegalStateException("No active non-visitor session"))
        }
        if (session.user.role != Role.OWNER && session.user.role != Role.ADMIN) {
            return@withContext Result.failure(SecurityException("Only Owner or Admin can delete devices"))
        }
        val targetId = deviceId.trim()
        try {
            try {
                client.postgrest["user_device_assignments"].delete {
                    filter { eq("device_id", targetId) }
                }
            } catch (e: Exception) {
                Log.w(tag, "Could not delete user_device_assignments for '$targetId': ${e.message}")
            }
            try {
                client.postgrest["commands"].delete {
                    filter { eq("device_id", targetId) }
                }
            } catch (e: Exception) {
                Log.w(tag, "Could not delete commands for '$targetId': ${e.message}")
            }
            try {
                client.postgrest["locations"].delete {
                    filter { eq("device_id", targetId) }
                }
            } catch (e: Exception) {
                Log.w(tag, "Could not delete locations for '$targetId': ${e.message}")
            }
            client.postgrest["devices"].delete {
                filter { eq("device_id", targetId) }
            }
            knownFleetDevices.remove(targetId)
            inMemoryAssignments.removeAll { it.deviceId == targetId }
            Log.i(tag, "Permanently deleted device '$targetId' from Supabase")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to delete device '$targetId': ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches active device assignments from public.user_device_assignments.
     * RLS allows Owner/Admin or self (user_id = auth.uid()).
     */
    open suspend fun fetchDeviceAssignments(): List<DeviceAssignmentRecord> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role == Role.VISITOR) {
            Log.w(tag, "fetchDeviceAssignments blocked: No active non-visitor session")
            return@withContext emptyList()
        }
        try {
            val result = client.postgrest["user_device_assignments"].select {
                filter {
                    eq("status", "ACTIVE")
                }
            }
            val list = result.decodeList<DeviceAssignmentRecord>()
            inMemoryAssignments.clear()
            inMemoryAssignments.addAll(list)
            list
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch device assignments: ${e.message}", e)
            inMemoryAssignments.toList()
        }
    }

    /**
     * Fetches active user profiles for device assignment picker (Owner/Admin only).
     */
    open suspend fun fetchAssignableUsers(): List<UserProfileRecord> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
            Log.w(tag, "fetchAssignableUsers blocked: Caller must be OWNER or ADMIN")
            return@withContext emptyList()
        }
        try {
            val result = client.postgrest["profiles"].select {
                filter {
                    eq("status", "ACTIVE")
                }
            }
            val list = result.decodeList<UserProfileRecord>()
            inMemoryProfiles.clear()
            inMemoryProfiles.addAll(list)
            list
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch assignable users: ${e.message}", e)
            inMemoryProfiles.filter { it.status.uppercase() == "ACTIVE" }
        }
    }

    /**
     * Assigns a device to a user via assign_device_to_user RPC.
     * Accessible only by OWNER and ADMIN.
     */
    open suspend fun assignDeviceToUser(
        deviceId: String,
        userId: String,
        expiresAt: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
            return@withContext Result.failure(SecurityException("Only Owner or Admin can assign devices"))
        }
        try {
            val params = AssignDeviceRpcParams(
                deviceId = deviceId.trim(),
                userId = userId.trim(),
                expiresAt = expiresAt
            )
            client.postgrest.rpc("assign_device_to_user", params)
            Log.i(tag, "Successfully assigned device '$deviceId' to user '$userId'")
            val newId = System.currentTimeMillis()
            inMemoryAssignments.removeAll { it.deviceId == deviceId.trim() && it.userId == userId.trim() }
            inMemoryAssignments.add(DeviceAssignmentRecord(id = newId, userId = userId.trim(), deviceId = deviceId.trim(), status = "ACTIVE"))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to assign device remotely: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Revokes device assignment from a user via unassign_device_from_user RPC.
     * Accessible only by OWNER and ADMIN.
     */
    open suspend fun unassignDeviceFromUser(
        deviceId: String,
        userId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
            return@withContext Result.failure(SecurityException("Only Owner or Admin can unassign devices"))
        }
        try {
            val params = UnassignDeviceRpcParams(
                deviceId = deviceId.trim(),
                userId = userId.trim()
            )
            client.postgrest.rpc("unassign_device_from_user", params)
            Log.i(tag, "Successfully unassigned device '$deviceId' from user '$userId'")
            inMemoryAssignments.removeAll { it.deviceId == deviceId.trim() && it.userId == userId.trim() }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to unassign device remotely: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches all user profiles from public.profiles (Owner/Admin only).
     * Guaranteed ZERO Supabase calls if role is VISITOR or session is invalid.
     */
    open suspend fun fetchAllProfiles(): List<UserProfileRecord> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
            Log.w(tag, "fetchAllProfiles blocked: Caller must be OWNER or ADMIN")
            return@withContext emptyList()
        }
        try {
            val result = client.postgrest["profiles"].select {
                order("created_at", order = Order.DESCENDING)
            }
            val profiles = result.decodeList<UserProfileRecord>()
            inMemoryProfiles.clear()
            inMemoryProfiles.addAll(profiles)
            profiles
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch all profiles: ${e.message}", e)
            inMemoryProfiles.toList()
        }
    }

    /**
     * Approves a user account (promoting VISITOR to USER and status to ACTIVE) via public.approve_user_account RPC.
     */
    open suspend fun approveUserAccount(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
            return@withContext Result.failure(SecurityException("Only Owner or Admin can approve user account"))
        }
        try {
            val params = ApproveUserAccountRpcParams(userId = userId.trim())
            client.postgrest.rpc("approve_user_account", params)
            Log.i(tag, "Successfully approved user '$userId' via RPC")
            val idx = inMemoryProfiles.indexOfFirst { it.id == userId.trim() }
            if (idx != -1) {
                inMemoryProfiles[idx] = inMemoryProfiles[idx].copy(role = "USER", status = "ACTIVE")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to approve user in Supabase: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Updates a user's role in public.profiles.
     */
    open suspend fun updateUserRole(userId: String, newRole: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
            return@withContext Result.failure(SecurityException("Only Owner or Admin can update user role"))
        }
        try {
            val cleanRole = newRole.trim().uppercase()
            val params = UpdateUserRoleRpcParams(
                userId = userId.trim(),
                newRole = cleanRole
            )
            client.postgrest.rpc("update_user_account_role", params)
            Log.i(tag, "Successfully updated role to '$cleanRole' for user '$userId' via RPC")
            val idx = inMemoryProfiles.indexOfFirst { it.id == userId.trim() }
            if (idx != -1) {
                inMemoryProfiles[idx] = inMemoryProfiles[idx].copy(role = cleanRole)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to update role in Supabase: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Updates a user's account status in public.profiles.
     * Allowed statuses: ACTIVE, SUSPENDED, PENDING.
     * Maps DISABLED to SUSPENDED for schema enum compatibility.
     */
    open suspend fun updateUserStatus(userId: String, newStatus: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
            return@withContext Result.failure(SecurityException("Only Owner or Admin can update user status"))
        }
        try {
            val dbStatus = when (newStatus.trim().uppercase()) {
                "DISABLED" -> "SUSPENDED"
                else -> newStatus.trim().uppercase()
            }
            val params = UpdateUserStatusRpcParams(
                userId = userId.trim(),
                newStatus = dbStatus
            )
            client.postgrest.rpc("update_user_account_status", params)
            Log.i(tag, "Successfully updated status to '$dbStatus' for user '$userId' via RPC")
            val idx = inMemoryProfiles.indexOfFirst { it.id == userId.trim() }
            if (idx != -1) {
                inMemoryProfiles[idx] = inMemoryProfiles[idx].copy(status = newStatus.trim().uppercase())
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to update status in Supabase: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Permanently deletes a user account via the `delete_user_account` RPC.
     * Strictly restricted to Fleet Owner. Admins, Users, and Visitors are blocked.
     * Prevents self-deletion and deletion of Owner accounts.
     */
    open suspend fun deleteUserAccount(userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role != Role.OWNER) {
            return@withContext Result.failure(SecurityException("Only Fleet Owner can delete user accounts"))
        }
        val targetId = userId.trim()
        if (targetId.equals(session.user.id.trim(), ignoreCase = true)) {
            return@withContext Result.failure(IllegalArgumentException("Cannot delete your own account"))
        }
        try {
            val params = DeleteUserAccountRpcParams(userId = targetId)
            client.postgrest.rpc("delete_user_account", params)
            Log.i(tag, "Successfully deleted user '$targetId' via RPC")
            inMemoryProfiles.removeAll { it.id == targetId }
            inMemoryAssignments.removeAll { it.userId == targetId }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to delete user account in Supabase: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches all device feature permissions from public.device_feature_permissions.
     */
    open suspend fun fetchDeviceFeaturePermissions(): List<DeviceFeaturePermissionRecord> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role == Role.VISITOR) {
            Log.w(tag, "fetchDeviceFeaturePermissions blocked: No active non-visitor session")
            return@withContext emptyList()
        }
        try {
            val result = client.postgrest["device_feature_permissions"].select()
            val list = result.decodeList<DeviceFeaturePermissionRecord>()
            inMemoryFeaturePermissions.clear()
            inMemoryFeaturePermissions.addAll(list)
            list
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch device feature permissions: ${e.message}", e)
            inMemoryFeaturePermissions.toList()
        }
    }

    /**
     * Updates or grants a feature permission for a device assignment.
     */
    open suspend fun updateDeviceFeaturePermission(
        assignmentId: Long,
        feature: String,
        isAllowed: Boolean,
        userId: String? = null,
        deviceId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
            return@withContext Result.failure(SecurityException("Only Owner or Admin can modify device feature permissions"))
        }
        try {
            val cleanFeature = feature.trim().uppercase()
            if (!userId.isNullOrBlank() && !deviceId.isNullOrBlank()) {
                if (isAllowed) {
                    val params = GrantFeaturePermissionRpcParams(
                        userId = userId.trim(),
                        deviceId = deviceId.trim(),
                        feature = cleanFeature
                    )
                    client.postgrest.rpc("grant_device_feature_permission", params)
                } else {
                    val params = RevokeFeaturePermissionRpcParams(
                        userId = userId.trim(),
                        deviceId = deviceId.trim(),
                        feature = cleanFeature
                    )
                    client.postgrest.rpc("revoke_device_feature_permission", params)
                }
            } else {
                val params = UpsertFeaturePermissionParams(
                    assignmentId = assignmentId,
                    feature = cleanFeature,
                    isAllowed = isAllowed
                )
                client.postgrest["device_feature_permissions"].upsert(params)
            }
            Log.i(tag, "Successfully updated permission '$feature'=$isAllowed for assignment $assignmentId")
            inMemoryFeaturePermissions.removeAll { it.assignmentId == assignmentId && it.feature == cleanFeature }
            inMemoryFeaturePermissions.add(
                DeviceFeaturePermissionRecord(
                    id = System.currentTimeMillis(),
                    assignmentId = assignmentId,
                    feature = cleanFeature,
                    isAllowed = isAllowed
                )
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(tag, "Failed to update feature permission remotely: ${e.message}", e)
            Result.failure(e)
        }
    }

    open suspend fun sendTrackerCommand(command: TrackerCommand): CommandResult {
        val session = currentSession
        if (session == null || !session.isValid(timeProvider()) || session.user.role == Role.VISITOR) {
            return CommandResult.Unauthorized(
                commandId = command.commandId,
                deviceId = command.deviceId
            )
        }

        // 1. Delegate to secure CommandGateway if configured
        if (commandGateway != null) {
            return commandGateway.submitCommand(command, session, command.idempotencyKey)
        }

        // 2. FAIL CLOSED: Insecure WebSocket command broadcast is permanently disabled
        return CommandResult.Failed(
            commandId = command.commandId,
            deviceId = command.deviceId,
            reason = "Secure command gateway unavailable: HTTPS Edge Function submission is mandatory"
        )
    }
}
