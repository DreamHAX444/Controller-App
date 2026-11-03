package com.livetracker.controller.core.tracker.supabase

import android.util.Log
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.RevocationEventRecord
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.Session
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.camera.domain.CameraRepository
import com.livetracker.controller.core.tracker.device.domain.DeviceManager
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.domain.TrackerSessionManager
import com.livetracker.controller.core.tracker.location.domain.ProcessLocationUpdateUseCase
import com.livetracker.controller.core.tracker.location.domain.TrackerLocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates Supabase fleet transport connection, telemetry pipelines, device hydration,
 * and repository teardown bound strictly to the active user session lifecycle.
 *
 * Implements strict 4-stage hydration precedence and instant reactive revocation enforcement.
 */
class FleetLifecycleManager(
    private val supabaseFleetManager: SupabaseFleetManager,
    private val deviceManager: DeviceManager,
    private val trackerDeviceRepository: TrackerDeviceRepository,
    private val trackerLocationRepository: TrackerLocationRepository,
    private val trackerSessionManager: TrackerSessionManager,
    private val processLocationUpdateUseCase: ProcessLocationUpdateUseCase,
    private val scope: CoroutineScope,
    private val cameraRepository: CameraRepository? = null,
    private val sessionRepository: SessionRepository? = null,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) {
    companion object {
        private const val OFFLINE_TIMEOUT_MS = 60_000L
    }

    private val tag = "FleetLifecycleManager"
    private val mutex = Mutex()
    private var activePipelineJob: Job? = null

    private fun normalizeBattery(raw: Float?): Int? = raw?.let {
        if (it > 1.0f) it.toInt().coerceIn(0, 100) else (it * 100).toInt().coerceIn(0, 100)
    }

    private fun parseIso(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        val trimmed = iso.trim().trim('"')
        return try {
            java.time.Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDateTime.parse(trimmed).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    suspend fun onSessionChanged(session: Session?) {
        mutex.withLock {
            if (session == null || !session.isValid() || session.user.role == Role.VISITOR || session.user.accountStatus != AccountStatus.ACTIVE) {
                teardownFleet(clearCache = true)
            } else {
                startFleetPipeline(session)
            }
        }
    }

    private suspend fun startFleetPipeline(session: Session) {
        teardownFleet(clearCache = false)

        activePipelineJob = scope.launch(dispatcher) {
            try {
                // Stage 1: Start Realtime subscriptions & initialize currentSession FIRST
                val started = try {
                    supabaseFleetManager.startFleet(session)
                } catch (e: Exception) {
                    Log.w(tag, "Exception in startFleet: ${e.message}")
                    false
                }
                if (!started) {
                    Log.w(tag, "Supabase fleet channels still establishing for session: ${session.sessionId}")
                }

                // Stage 2: Authoritative registered devices hydration from public.devices
                val fleetDevices = supabaseFleetManager.fetchFleetDevices()
                val authorizedIds = fleetDevices.map { it.deviceId }
                supabaseFleetManager.setAuthorizedDevices(authorizedIds)

                val nowInit = System.currentTimeMillis()
                for (devRecord in fleetDevices) {
                    if (!isActive) break
                    val trackerId = TrackerId(devRecord.deviceId)
                    if (trackerDeviceRepository.getDevice(trackerId) == null || !trackerSessionManager.hasSession(trackerId)) {
                        deviceManager.registerDevice(trackerId, devRecord.name.ifBlank { devRecord.deviceId })
                    }
                    val registryTs = parseIso(devRecord.updatedAt).takeIf { it > 0 } ?: parseIso(devRecord.lastSeenAt)
                    val existingDev = trackerDeviceRepository.getDevice(trackerId)
                    val effectiveLastSeen = maxOf(existingDev?.lastSeenTimestamp ?: 0L, registryTs)
                    val isConnected = effectiveLastSeen > 0 && (nowInit - effectiveLastSeen) < 300_000L
                    if (isConnected || devRecord.status.equals("ACTIVE", ignoreCase = true)) {
                        deviceManager.connectDevice(trackerId)
                    }
                    trackerDeviceRepository.updateDevice(trackerId) { current ->
                        current.copy(
                            displayName = devRecord.name.ifBlank { current.displayName },
                            deviceModel = devRecord.model.ifBlank { current.deviceModel },
                            lifecycle = if (devRecord.status.equals("ACTIVE", ignoreCase = true)) {
                                com.livetracker.controller.core.tracker.device.domain.DeviceLifecycle.ACTIVE
                            } else {
                                com.livetracker.controller.core.tracker.device.domain.DeviceLifecycle.REGISTERED
                            },
                            lastSeenTimestamp = if (effectiveLastSeen > 0) effectiveLastSeen else current.lastSeenTimestamp,
                            connectionState = if (isConnected) {
                                com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED
                            } else {
                                current.connectionState
                            },
                            capabilities = com.livetracker.controller.core.tracker.domain.TrackerCapabilities(
                                location = true,
                                camera = true,
                                audio = true,
                                screen = true,
                                fileTransfer = true
                            )
                        )
                    }
                }


                // Stage 3: Initial locations hydration from public.locations / latest_device_locations
                val initialLocations = supabaseFleetManager.fetchLatestLocations()
                val now = System.currentTimeMillis()
                for (locData in initialLocations) {
                    if (!isActive) break
                    val trackerId = TrackerId(locData.deviceId)
                    if (session.user.role == Role.USER && !supabaseFleetManager.isDeviceAuthorized(locData.deviceId)) {
                        continue
                    }
                    if (trackerDeviceRepository.getDevice(trackerId) == null) {
                        deviceManager.registerDevice(trackerId, locData.deviceId)
                    }
                    val ts = locData.parseTimestamp()
                    val existingDev = trackerDeviceRepository.getDevice(trackerId)
                    val effectiveLastSeen = maxOf(existingDev?.lastSeenTimestamp ?: 0L, ts)
                    val isConnected = effectiveLastSeen > 0 && (now - effectiveLastSeen) < 60_000L
                    if (isConnected) {
                        deviceManager.connectDevice(trackerId)
                    }
                    val batteryInt = normalizeBattery(locData.batteryLevel)
                    trackerDeviceRepository.updateDevice(trackerId) { dev ->
                        dev.copy(
                            batteryLevel = batteryInt ?: dev.batteryLevel,
                            networkState = locData.networkType ?: dev.networkState,
                            lastSeenTimestamp = effectiveLastSeen,
                            connectionState = if (isConnected) com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED else com.livetracker.controller.core.tracker.domain.ConnectionState.DISCONNECTED
                        )
                    }
                    val event = locData.toLocationUpdatedEvent()
                    processLocationUpdateUseCase(event)
                }

                // Stage 4A: Continuous telemetry collection
                launch {
                    supabaseFleetManager.rawTelemetry.collect { telemetry ->
                        val trackerId = TrackerId(telemetry.deviceId)
                        if (session.user.role == Role.USER && !supabaseFleetManager.isDeviceAuthorized(telemetry.deviceId)) {
                            return@collect
                        }
                        if (trackerDeviceRepository.getDevice(trackerId) == null) {
                            deviceManager.registerDevice(trackerId, telemetry.deviceId)
                        }
                        deviceManager.connectDevice(trackerId)
                        val batteryInt = normalizeBattery(telemetry.batteryLevel)
                        trackerDeviceRepository.updateDevice(trackerId) { dev ->
                            dev.copy(
                                batteryLevel = batteryInt ?: dev.batteryLevel,
                                networkState = telemetry.networkType ?: dev.networkState,
                                lastSeenTimestamp = telemetry.parseTimestamp(),
                                connectionState = com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED
                            )
                        }
                    }
                }

                // Stage 4B: Continuous location events collection
                launch {
                    supabaseFleetManager.locationEvents.collect { event ->
                        val trackerId = event.deviceId
                        if (session.user.role == Role.USER && !supabaseFleetManager.isDeviceAuthorized(trackerId.value)) {
                            return@collect
                        }
                        if (trackerDeviceRepository.getDevice(trackerId) == null) {
                            deviceManager.registerDevice(trackerId, trackerId.value)
                        }
                        deviceManager.connectDevice(trackerId)
                        trackerDeviceRepository.updateDevice(trackerId) { dev ->
                            dev.copy(
                                connectionState = com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED,
                                lastSeenTimestamp = event.timestamp
                            )
                        }
                        processLocationUpdateUseCase(event)
                    }
                }

                // Stage 4C: In-Memory Presence Watchdog (10s local scan, 0 network I/O)
                launch {
                    while (isActive) {
                        kotlinx.coroutines.delay(10_000L)
                        try {
                            val now = System.currentTimeMillis()
                            // Audit existing devices in-memory: if inactive for > OFFLINE_TIMEOUT_MS, mark DISCONNECTED (0 network I/O)
                            for (dev in trackerDeviceRepository.getAllDevices()) {
                                val lastSeen = dev.lastSeenTimestamp
                                if (lastSeen != null && (now - lastSeen) > OFFLINE_TIMEOUT_MS &&
                                    dev.connectionState == com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED
                                ) {
                                    trackerDeviceRepository.updateDevice(dev.deviceId) {
                                        it.copy(connectionState = com.livetracker.controller.core.tracker.domain.ConnectionState.DISCONNECTED)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(tag, "In-memory presence watchdog error: ${e.message}")
                        }
                    }
                }

                // Stage 4C: Continuous revocation events collection
                launch {
                    supabaseFleetManager.revocationEvents.collect { revEvent ->
                        handleRevocationEvent(revEvent, session)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in fleet pipeline: ${e.message}", e)
            }
        }
    }

    private suspend fun handleRevocationEvent(revEvent: RevocationEventRecord, session: Session) {
        Log.i(tag, "Received revocation event: ${revEvent.eventType} for target ${revEvent.targetUserId}")
        when (revEvent.eventType) {
            "ACCOUNT_STATUS_CHANGED" -> {
                if (revEvent.targetUserId == session.user.id) {
                    Log.w(tag, "Account status changed to ${revEvent.reason} for current session. Initiating immediate teardown.")
                    val newStatus = when (revEvent.reason?.uppercase()) {
                        "SUSPENDED" -> AccountStatus.SUSPENDED
                        "DISABLED" -> AccountStatus.DISABLED
                        "PENDING" -> AccountStatus.PENDING
                        else -> AccountStatus.SUSPENDED
                    }
                    sessionRepository?.updateSessionUser(session.user.copy(accountStatus = newStatus))
                    teardownFleet()
                }
            }
            "ROLE_CHANGED" -> {
                if (revEvent.targetUserId == session.user.id) {
                    Log.i(tag, "Role changed for current session to ${revEvent.reason}. Reconciling fleet permissions.")
                    val newRole = when (revEvent.reason?.uppercase()) {
                        "ADMIN" -> Role.ADMIN
                        "USER" -> Role.USER
                        "VISITOR" -> Role.VISITOR
                        else -> session.user.role
                    }
                    sessionRepository?.updateSessionUser(session.user.copy(role = newRole))
                    if (newRole == Role.VISITOR) {
                        teardownFleet()
                    } else {
                        supabaseFleetManager.reconcileEffectivePermissions()
                    }
                }
            }
            "DEVICE_UNASSIGNED" -> {
                if (revEvent.targetUserId == session.user.id || session.user.role == Role.USER) {
                    val deviceId = revEvent.deviceId
                    if (deviceId != null) {
                        Log.i(tag, "Device $deviceId unassigned from user ${revEvent.targetUserId}. Revoking stream and device registration.")
                        val trackerId = TrackerId(deviceId)
                        cameraRepository?.emergencyRevokeStream(trackerId, "Device unassigned")
                        deviceManager.unregisterDevice(trackerId)
                        trackerLocationRepository.clearLocation(trackerId)
                        supabaseFleetManager.removeAuthorizedDevice(deviceId)

                        val updatedPermissions = session.user.devicePermissions.filter { it.deviceId != deviceId }
                        sessionRepository?.updateSessionUser(session.user.copy(devicePermissions = updatedPermissions))
                    }
                }
            }
            "FEATURE_REVOKED" -> {
                if (revEvent.targetUserId == session.user.id || session.user.role == Role.USER) {
                    val deviceId = revEvent.deviceId
                    val feature = revEvent.feature
                    Log.i(tag, "Feature $feature revoked on device $deviceId for user ${revEvent.targetUserId}.")
                    if (deviceId != null && feature.equals("CAMERA", ignoreCase = true)) {
                        cameraRepository?.emergencyRevokeStream(TrackerId(deviceId), "Camera feature revoked")
                    }
                    if (deviceId != null) {
                        val updatedPermissions = session.user.devicePermissions.map { perm ->
                            if (perm.deviceId == deviceId) {
                                val updatedServices = perm.allowedServices.filterNot {
                                    when (feature?.uppercase()) {
                                        "CAMERA" -> it == ServicePermission.CAMERA
                                        "MIC" -> it == ServicePermission.MICROPHONE_AUDIO || it == ServicePermission.DEVICE_AUDIO
                                        "SCREEN" -> it == ServicePermission.SCREEN_CAPTURE
                                        "FILES" -> it == ServicePermission.FILE_READ ||
                                                it == ServicePermission.FILE_WRITE ||
                                                it == ServicePermission.FILE_DELETE ||
                                                it == ServicePermission.FILE_RENAME ||
                                                it == ServicePermission.FILE_TRANSFER
                                        "LOCATION" -> it == ServicePermission.LOCATION
                                        else -> false
                                    }
                                }.toSet()
                                perm.copy(allowedServices = updatedServices)
                            } else {
                                perm
                            }
                        }
                        sessionRepository?.updateSessionUser(session.user.copy(devicePermissions = updatedPermissions))
                    }
                }
            }
        }
    }

    private suspend fun teardownFleet(clearCache: Boolean = false) {
        Log.i(tag, "Tearing down fleet: cancelling pipeline coroutines, disconnecting Supabase (clearCache=$clearCache)")
        activePipelineJob?.cancel()
        activePipelineJob = null

        supabaseFleetManager.stopFleet()
        trackerSessionManager.clearSessions()

        if (clearCache) {
            val devices = trackerDeviceRepository.getAllDevices()
            for (dev in devices) {
                trackerDeviceRepository.unregisterDevice(dev.deviceId)
                trackerLocationRepository.clearLocation(dev.deviceId)
            }
        }
    }

    suspend fun refresh() {
        mutex.withLock {
            try {
                val fleetDevices = supabaseFleetManager.fetchFleetDevices()
                val authorizedIds = fleetDevices.map { it.deviceId }
                supabaseFleetManager.setAuthorizedDevices(authorizedIds)

                for (devRecord in fleetDevices) {
                    val trackerId = TrackerId(devRecord.deviceId)
                    if (trackerDeviceRepository.getDevice(trackerId) == null) {
                        deviceManager.registerDevice(trackerId, devRecord.name.ifBlank { devRecord.deviceId })
                    }
                    trackerDeviceRepository.updateDevice(trackerId) { current ->
                        current.copy(
                            displayName = devRecord.name.ifBlank { current.displayName },
                            deviceModel = devRecord.model.ifBlank { current.deviceModel },
                            lifecycle = if (devRecord.status.equals("ACTIVE", ignoreCase = true)) {
                                com.livetracker.controller.core.tracker.device.domain.DeviceLifecycle.ACTIVE
                            } else {
                                com.livetracker.controller.core.tracker.device.domain.DeviceLifecycle.REGISTERED
                            }
                        )
                    }
                }

                val now = System.currentTimeMillis()
                for (devRecord in fleetDevices) {
                    val trackerId = TrackerId(devRecord.deviceId)
                    val registryTs = parseIso(devRecord.updatedAt).takeIf { it > 0 } ?: parseIso(devRecord.lastSeenAt)
                    if (registryTs > 0) {
                        val existingDev = trackerDeviceRepository.getDevice(trackerId)
                        val effectiveLastSeen = maxOf(existingDev?.lastSeenTimestamp ?: 0L, registryTs)
                        val isConnected = effectiveLastSeen > 0 && (now - effectiveLastSeen) < 60_000L
                        if (isConnected) {
                            deviceManager.connectDevice(trackerId)
                        }
                        trackerDeviceRepository.updateDevice(trackerId) { current ->
                            current.copy(
                                lastSeenTimestamp = if (effectiveLastSeen > 0) effectiveLastSeen else current.lastSeenTimestamp,
                                connectionState = if (isConnected) com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED else current.connectionState
                            )
                        }
                    }
                }

                val initialLocations = supabaseFleetManager.fetchLatestLocations()
                for (locData in initialLocations) {
                    val trackerId = TrackerId(locData.deviceId)
                    if (trackerDeviceRepository.getDevice(trackerId) == null) {
                        deviceManager.registerDevice(trackerId, locData.deviceId)
                    }
                    val ts = locData.parseTimestamp()
                    val existingDev = trackerDeviceRepository.getDevice(trackerId)
                    val effectiveLastSeen = maxOf(existingDev?.lastSeenTimestamp ?: 0L, ts)
                    val isConnected = effectiveLastSeen > 0 && (now - effectiveLastSeen) < 60_000L
                    if (isConnected) {
                        deviceManager.connectDevice(trackerId)
                    }
                    val batteryInt = normalizeBattery(locData.batteryLevel)
                    trackerDeviceRepository.updateDevice(trackerId) { dev ->
                        dev.copy(
                            batteryLevel = batteryInt ?: dev.batteryLevel,
                            networkState = locData.networkType ?: dev.networkState,
                            lastSeenTimestamp = effectiveLastSeen,
                            connectionState = if (isConnected) com.livetracker.controller.core.tracker.domain.ConnectionState.CONNECTED else com.livetracker.controller.core.tracker.domain.ConnectionState.DISCONNECTED
                        )
                    }
                    val event = locData.toLocationUpdatedEvent()
                    processLocationUpdateUseCase(event)
                }
            } catch (e: Exception) {
                Log.w(tag, "refresh error: ${e.message}")
            }
        }
    }
}
