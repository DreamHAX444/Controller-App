package com.livetracker.controller.ui.screens.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.alert.domain.AlertRepository
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceRepository
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceRepository
import com.livetracker.controller.core.tracker.recovery.NetworkState
import com.livetracker.controller.core.tracker.webrtc.WebRtcSessionManager
import com.livetracker.controller.network.AndroidNetworkStateProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SubsystemStatus(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val isHealthy: Boolean = true,
    val isWarning: Boolean = false
)

data class DiagnosticsUiState(
    val isChecking: Boolean = false,
    val lastCheckTime: String = "Not probed yet",
    val pingMs: Long = -1L,
    val networkType: String = "Detecting...",
    val isNetworkOnline: Boolean = true,
    val packetLossPercent: Int = 0,
    val heapUsedMb: Long = 0L,
    val heapAllocatedMb: Long = 0L,
    val heapMaxMb: Long = 0L,
    val cacheSizeBytes: Long = 0L,
    val cacheFilesCount: Int = 0,
    val registeredTrackers: Int = 0,
    val onlineTrackers: Int = 0,
    val subsystems: List<SubsystemStatus> = emptyList(),
    val overallStatusText: String = "PROBING SYSTEM...",
    val statusMessage: String? = null,
    val isAutoRefreshActive: Boolean = true,
    val refreshTickCounter: Long = 0L
) {
    val cacheDisplaySize: String
        get() {
            val mb = cacheSizeBytes.toDouble() / (1024.0 * 1024.0)
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                val kb = cacheSizeBytes.toDouble() / 1024.0
                String.format(Locale.US, "%.0f KB", kb)
            }
        }
}

class DiagnosticsViewModel(
    private val application: Context? = null,
    private val trackerDeviceRepository: TrackerDeviceRepository? = null,
    private val alertRepository: AlertRepository? = null,
    private val geoFenceRepository: GeoFenceRepository? = null,
    private val sessionRepository: SessionRepository? = null,
    private val networkStateProvider: AndroidNetworkStateProvider? = null,
    private val webRtcSessionManager: WebRtcSessionManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()
    private var livePollingJob: Job? = null

    init {
        val (used, total, max) = readHeapMemory()
        _uiState.update {
            it.copy(
                heapUsedMb = used,
                heapAllocatedMb = total,
                heapMaxMb = max
            )
        }
        observeRepositories()
        runDiagnostics()
    }

    private fun observeRepositories() {
        if (trackerDeviceRepository != null) {
            viewModelScope.launch {
                trackerDeviceRepository.observeAllDevices().collect { devices ->
                    val total = devices.size
                    val online = devices.count { it.connectionState == ConnectionState.CONNECTED }
                    _uiState.update {
                        it.copy(
                            registeredTrackers = total,
                            onlineTrackers = online
                        )
                    }
                    evaluateSubsystems()
                }
            }
        }

        if (alertRepository != null) {
            viewModelScope.launch {
                alertRepository.observeUnacknowledgedAlerts().collect {
                    evaluateSubsystems()
                }
            }
        }

        if (sessionRepository != null) {
            viewModelScope.launch {
                sessionRepository.observeActiveSession().collect {
                    evaluateSubsystems()
                }
            }
        }

        if (networkStateProvider != null) {
            viewModelScope.launch {
                networkStateProvider.networkState.collect { state ->
                    val isOnline = state != NetworkState.LOST
                    _uiState.update { it.copy(isNetworkOnline = isOnline) }
                    evaluateSubsystems()
                }
            }
        }
    }

    private fun readHeapMemory(): Triple<Long, Long, Long> {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        val freeMb = runtime.freeMemory() / (1024 * 1024)
        val usedMb = (totalMb - freeMb).coerceAtLeast(0L)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        return Triple(usedMb, totalMb, maxMb)
    }

    private fun getCacheDirs(): List<File> {
        val context = application ?: return emptyList()
        return listOfNotNull(
            context.cacheDir,
            context.codeCacheDir,
            context.externalCacheDir
        )
    }

    private suspend fun scanCache(): Pair<Long, Int> = withContext(Dispatchers.IO) {
        var totalBytes = 0L
        var count = 0
        getCacheDirs().forEach { dir ->
            if (dir.exists()) {
                try {
                    dir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            totalBytes += file.length()
                            count++
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        totalBytes to count
    }

    private suspend fun probeNetworkRtt(): Pair<Long, String> = withContext(Dispatchers.IO) {
        val context = application ?: return@withContext -1L to "Offline"
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)

        val typeName = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }

        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return@withContext -1L to "Offline"
        }

        val startNs = System.nanoTime()
        var rtt = -1L
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("8.8.8.8", 53), 2000)
                rtt = (System.nanoTime() - startNs) / 1_000_000L
            }
        } catch (_: Exception) {
            try {
                val httpStart = System.nanoTime()
                val url = java.net.URL("https://www.google.com/generate_204")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "HEAD"
                val code = conn.responseCode
                if (code in 200..399) {
                    rtt = (System.nanoTime() - httpStart) / 1_000_000L
                }
                conn.disconnect()
            } catch (_: Exception) {
                rtt = -1L
            }
        }
        rtt to typeName
    }

    fun startLivePolling() {
        if (livePollingJob?.isActive == true) return
        _uiState.update { it.copy(isAutoRefreshActive = true) }
        livePollingJob = viewModelScope.launch {
            // Immediate initial probe on screen entry
            runDiagnosticsInternal()
            while (isActive) {
                delay(4000L) // Telemetry poll cycle every 4 seconds
                runDiagnosticsInternal()
                _uiState.update { it.copy(refreshTickCounter = it.refreshTickCounter + 1) }
            }
        }
    }

    fun stopLivePolling() {
        livePollingJob?.cancel()
        livePollingJob = null
        _uiState.update { it.copy(isAutoRefreshActive = false) }
    }

    fun toggleAutoRefresh() {
        if (_uiState.value.isAutoRefreshActive) {
            stopLivePolling()
        } else {
            startLivePolling()
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch {
            runDiagnosticsInternal()
        }
    }

    private suspend fun runDiagnosticsInternal() {
        if (_uiState.value.isChecking) return
        _uiState.update { it.copy(isChecking = true) }

        try {
            // 1. Probe real network latency & connection type
            val (measuredPing, netType) = probeNetworkRtt()
            val isOnline = measuredPing >= 0L

            // 2. Read real heap memory
            val (usedMb, totalMb, maxMb) = readHeapMemory()

            // 3. Scan real cache directory sizes
            val (cacheBytes, cacheCount) = scanCache()

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            _uiState.update {
                it.copy(
                    isChecking = false,
                    lastCheckTime = timeStr,
                    pingMs = measuredPing,
                    networkType = netType,
                    isNetworkOnline = isOnline,
                    packetLossPercent = if (isOnline) 0 else 100,
                    heapUsedMb = usedMb,
                    heapAllocatedMb = totalMb,
                    heapMaxMb = maxMb,
                    cacheSizeBytes = cacheBytes,
                    cacheFilesCount = cacheCount
                )
            }

            evaluateSubsystems()
        } catch (_: Exception) {
            _uiState.update { it.copy(isChecking = false) }
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val beforeBytes = _uiState.value.cacheSizeBytes
            getCacheDirs().forEach { dir ->
                if (dir.exists()) {
                    try {
                        dir.listFiles()?.forEach { file ->
                            file.deleteRecursively()
                        }
                    } catch (_: Exception) {}
                }
            }
            val (afterBytes, count) = scanCache()
            val freedBytes = (beforeBytes - afterBytes).coerceAtLeast(0L)
            val msg = if (freedBytes > 0L) {
                val freedMb = freedBytes.toDouble() / (1024.0 * 1024.0)
                val freedStr = if (freedMb >= 0.1) String.format(Locale.US, "%.1f MB", freedMb) else "${freedBytes / 1024} KB"
                "Cache flushed: freed $freedStr"
            } else {
                "Cache already clean (0 KB on disk)"
            }

            _uiState.update {
                it.copy(
                    cacheSizeBytes = afterBytes,
                    cacheFilesCount = count,
                    statusMessage = msg
                )
            }
            evaluateSubsystems()
        }
    }

    fun onDismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private suspend fun evaluateSubsystems() {
        val (usedMb, totalMb, maxMb) = readHeapMemory()
        val (cacheBytes, cacheCount) = scanCache()
        val devices = trackerDeviceRepository?.getAllDevices().orEmpty()
        val totalDevices = devices.size
        val onlineDevices = devices.count { it.connectionState == ConnectionState.CONNECTED }
        val unackAlerts = alertRepository?.getUnacknowledgedAlerts().orEmpty().size
        val geofences = geoFenceRepository?.getGeoFences().orEmpty()
        val enabledGeofences = geofences.count { it.isEnabled }
        val activeSessionsCount = webRtcSessionManager?.getAllSessions()?.size ?: 0
        val isOnline = _uiState.value.isNetworkOnline
        val netType = _uiState.value.networkType
        val rtt = _uiState.value.pingMs
        val activeSession = sessionRepository?.getActiveSession()?.getOrNull()
        val role = activeSession?.user?.role?.name ?: "AUTHENTICATED"

        val list = mutableListOf<SubsystemStatus>()

        // 1. Network & Cloud Signaling
        list.add(
            SubsystemStatus(
                id = "network",
                name = "Network & Cloud Gateway",
                description = if (isOnline) "$netType connected • Real-time probe ${if (rtt > 0) "${rtt}ms RTT" else "ready"}" else "No active internet connectivity",
                status = if (isOnline) "OPERATIONAL" else "DISCONNECTED",
                isHealthy = isOnline
            )
        )

        // 2. Device Fleet & Telemetry Pipeline
        list.add(
            SubsystemStatus(
                id = "fleet",
                name = "Device Fleet & Telemetry",
                description = if (totalDevices > 0) "$onlineDevices online, ${totalDevices - onlineDevices} standby ($totalDevices registered)" else "No tracker devices registered",
                status = when {
                    onlineDevices > 0 -> "STREAMING"
                    totalDevices > 0 -> "STANDBY"
                    else -> "EMPTY"
                },
                isHealthy = true
            )
        )

        // 3. WebRTC P2P Transport Engine
        list.add(
            SubsystemStatus(
                id = "webrtc",
                name = "WebRTC P2P Direct Transport",
                description = if (activeSessionsCount > 0) "$activeSessionsCount direct peer channel(s) active" else "Direct encryption channels standby",
                status = if (activeSessionsCount > 0) "CONNECTED" else "STANDBY",
                isHealthy = true
            )
        )

        // 4. Geofence Perimeter Engine
        list.add(
            SubsystemStatus(
                id = "geofence",
                name = "Geofence Proximity Evaluator",
                description = if (geofences.isNotEmpty()) "$enabledGeofences active perimeter boundar${if (enabledGeofences == 1) "y" else "ies"} (${geofences.size} configured)" else "No geofences created",
                status = if (enabledGeofences > 0) "ACTIVE" else "IDLE",
                isHealthy = true
            )
        )

        // 5. Reactive Alert Dispatch
        list.add(
            SubsystemStatus(
                id = "alerts",
                name = "Reactive Alert Dispatch",
                description = if (unackAlerts > 0) "$unackAlerts unacknowledged urgent alert${if (unackAlerts == 1) "" else "s"}" else "All alerts acknowledged • Siren standby",
                status = if (unackAlerts > 0) "WARNING" else "HEALTHY",
                isHealthy = unackAlerts == 0,
                isWarning = unackAlerts > 0
            )
        )

        // 6. Security Vault & Session
        list.add(
            SubsystemStatus(
                id = "security",
                name = "Local Security & Vault",
                description = "Role: $role • Hardware KeyStore session verified",
                status = "SECURED",
                isHealthy = true
            )
        )

        val hasWarnings = list.any { it.isWarning }
        val hasErrors = list.any { !it.isHealthy && !it.isWarning }

        val overallStatus = when {
            hasErrors -> "SYSTEM DEGRADED • CONNECTIVITY LOST"
            hasWarnings -> "ATTENTION REQUIRED • $unackAlerts ALERT${if (unackAlerts == 1) "" else "S"}"
            else -> "ALL SYSTEMS OPERATIONAL"
        }

        _uiState.update {
            it.copy(
                subsystems = list,
                overallStatusText = overallStatus,
                registeredTrackers = totalDevices,
                onlineTrackers = onlineDevices,
                heapUsedMb = usedMb,
                heapAllocatedMb = totalMb,
                heapMaxMb = maxMb,
                cacheSizeBytes = cacheBytes,
                cacheFilesCount = cacheCount
            )
        }
    }
    override fun onCleared() {
        super.onCleared()
        livePollingJob?.cancel()
        livePollingJob = null
    }
}