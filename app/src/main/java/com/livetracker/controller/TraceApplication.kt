package com.livetracker.controller

import android.app.Application
import com.livetracker.controller.core.auth.data.StubAuthenticationRepository
import com.livetracker.controller.core.auth.data.StubCredentialStore
import com.livetracker.controller.core.auth.data.StubSessionRepository
import com.livetracker.controller.core.auth.domain.usecase.AuthenticateUserUseCase
import com.livetracker.controller.core.auth.domain.usecase.LogoutUserUseCase
import com.livetracker.controller.core.auth.domain.usecase.RestoreSessionUseCase
import com.livetracker.controller.core.auth.domain.usecase.ValidateSessionUseCase
import com.livetracker.controller.core.security.EncryptedPreferencesStorage
import com.livetracker.controller.core.security.PinManager
import com.livetracker.controller.core.startup.StartupOrchestrator
import com.livetracker.controller.core.startup.StartupStep
import com.livetracker.controller.core.tracker.alert.data.InMemoryAlertRepository
import com.livetracker.controller.core.tracker.device.data.DefaultDeviceSelectionManager
import com.livetracker.controller.core.tracker.device.data.InMemoryTrackerDeviceRepository
import com.livetracker.controller.core.tracker.device.domain.ObserveDeviceSummariesUseCase
import com.livetracker.controller.core.tracker.geofence.data.InMemoryGeoFenceRepository
import com.livetracker.controller.core.tracker.location.data.InMemoryTrackerLocationRepository
import com.livetracker.controller.core.tracker.map.domain.ObserveMapStateUseCase
import com.livetracker.controller.ui.screens.map.MapPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.first

/**
 * Process-wide application class. Owns all singletons and the startup orchestrator.
 *
 * Initialization that previously happened synchronously in MainActivity.onCreate
 * is now orchestrated here with observable progress, concurrency, and error handling.
 *
 * This class survives Activity recreation (rotation, config changes) — expensive
 * dependencies are created exactly once per process lifecycle.
 */
class TraceApplication : Application() {

    // Process-scoped coroutine scope — lives for the entire process lifetime
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // === Security & Auth (Category A) ===
    lateinit var secureStorage: EncryptedPreferencesStorage
        private set
    lateinit var pinManager: PinManager
        private set

    // Auth stubs (Phase 1.3 — will be replaced by real implementations)
    lateinit var credentialStore: StubCredentialStore
        private set
    lateinit var sessionRepository: StubSessionRepository
        private set
    lateinit var authRepository: StubAuthenticationRepository
        private set

    // Auth use cases
    lateinit var restoreSessionUseCase: RestoreSessionUseCase
        private set
    lateinit var authenticateUserUseCase: AuthenticateUserUseCase
        private set
    lateinit var validateSessionUseCase: ValidateSessionUseCase
        private set
    lateinit var logoutUserUseCase: LogoutUserUseCase
        private set

    // === Tracker Repositories (Category A/B) ===
    lateinit var deviceSelectionManager: DefaultDeviceSelectionManager
        private set
    lateinit var trackerDeviceRepository: InMemoryTrackerDeviceRepository
        private set
    lateinit var trackerLocationRepository: InMemoryTrackerLocationRepository
        private set
    lateinit var alertRepository: InMemoryAlertRepository
        private set
    lateinit var geoFenceRepository: com.livetracker.controller.core.tracker.geofence.domain.GeoFenceRepository
        private set
    lateinit var geoFenceStateRepository: com.livetracker.controller.core.tracker.geofence.domain.GeoFenceStateRepository
        private set

    // === Map (Category A/B) ===
    lateinit var mapPreferencesRepository: MapPreferencesRepository
        private set
    lateinit var offlineMapManager: com.livetracker.controller.ui.screens.map.offline.OfflineMapManager
        private set

    // === Use Cases (Category B) ===
    lateinit var observeDeviceSummariesUseCase: ObserveDeviceSummariesUseCase
        private set
    lateinit var observeMapStateUseCase: ObserveMapStateUseCase
        private set
    lateinit var evaluateGeoFencesUseCase: com.livetracker.controller.core.tracker.geofence.domain.EvaluateGeoFencesUseCase
        private set
    lateinit var processLocationUpdateUseCase: com.livetracker.controller.core.tracker.location.domain.ProcessLocationUpdateUseCase
        private set

    // === Update System (Category A/B) ===
    lateinit var updatePreferencesRepository: com.livetracker.controller.core.update.storage.UpdatePreferencesRepository
        private set
    lateinit var updateInstaller: com.livetracker.controller.core.update.installer.UpdateInstaller
        private set
    lateinit var updateVerifier: com.livetracker.controller.core.update.verification.UpdateVerifier
        private set
    lateinit var updateRepository: com.livetracker.controller.core.update.domain.UpdateRepository
        private set
    lateinit var checkForUpdateUseCase: com.livetracker.controller.core.update.domain.usecase.CheckForUpdateUseCase
        private set
    lateinit var downloadUpdateUseCase: com.livetracker.controller.core.update.domain.usecase.DownloadUpdateUseCase
        private set
    lateinit var installUpdateUseCase: com.livetracker.controller.core.update.domain.usecase.InstallUpdateUseCase
        private set
    lateinit var dismissUpdateUseCase: com.livetracker.controller.core.update.domain.usecase.DismissUpdateUseCase
        private set
    lateinit var observeUpdateStateUseCase: com.livetracker.controller.core.update.domain.usecase.ObserveUpdateStateUseCase
        private set

    // === Startup ===
    lateinit var startupOrchestrator: StartupOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()

        val steps = listOf(
            StartupStep(
                label = "Preparing security",
                action = {
                    secureStorage = EncryptedPreferencesStorage(applicationContext)
                    pinManager = PinManager(secureStorage)
                }
            ),
            StartupStep(
                label = "Loading authentication",
                dependsOn = listOf("Preparing security"),
                action = {
                    credentialStore = StubCredentialStore()
                    sessionRepository = StubSessionRepository()
                    authRepository = StubAuthenticationRepository(sessionRepository, credentialStore)
                    restoreSessionUseCase = RestoreSessionUseCase(credentialStore, authRepository)
                    authenticateUserUseCase = AuthenticateUserUseCase(authRepository)
                    validateSessionUseCase = ValidateSessionUseCase(sessionRepository)
                    logoutUserUseCase = LogoutUserUseCase(authRepository, credentialStore)
                }
            ),
            StartupStep(
                label = "Restoring preferences",
                action = {
                    mapPreferencesRepository = MapPreferencesRepository(applicationContext)
                }
            ),
            StartupStep(
                label = "Preparing device state",
                action = {
                    deviceSelectionManager = DefaultDeviceSelectionManager()
                    trackerDeviceRepository = InMemoryTrackerDeviceRepository()
                    trackerLocationRepository = InMemoryTrackerLocationRepository()
                    alertRepository = InMemoryAlertRepository()
                    geoFenceRepository = com.livetracker.controller.core.tracker.geofence.data.PersistentGeoFenceRepository(applicationContext)
                    geoFenceStateRepository = com.livetracker.controller.core.tracker.geofence.data.InMemoryGeoFenceStateRepository()
                    evaluateGeoFencesUseCase = com.livetracker.controller.core.tracker.geofence.domain.EvaluateGeoFencesUseCase(
                        geoFenceRepository = geoFenceRepository,
                        geoFenceStateRepository = geoFenceStateRepository,
                        alertRepository = alertRepository
                    )
                    processLocationUpdateUseCase = com.livetracker.controller.core.tracker.location.domain.ProcessLocationUpdateUseCase(
                        repository = trackerLocationRepository,
                        evaluateGeoFencesUseCase = evaluateGeoFencesUseCase
                    )
                    deviceSelectionManager.selectAll()

                    val fence1 = com.livetracker.controller.core.tracker.geofence.domain.GeoFence(
                        id = "gf_1",
                        name = "Manhattan Safe Zone",
                        vertices = listOf(
                            com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D(40.7150, -74.0100),
                            com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D(40.7150, -74.0020),
                            com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D(40.7100, -74.0020),
                            com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D(40.7100, -74.0100)
                        ),
                        colorArgb = android.graphics.Color.parseColor("#4CAF50"),
                        isEnabled = true
                    )
                    val fence2 = com.livetracker.controller.core.tracker.geofence.domain.GeoFence(
                        id = "gf_2",
                        name = "NYU Campus",
                        vertices = listOf(
                            com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D(40.7320, -73.9900),
                            com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D(40.7320, -73.9840),
                            com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D(40.7280, -73.9840),
                            com.livetracker.controller.core.tracker.geofence.domain.GeoPoint2D(40.7280, -73.9900)
                        ),
                        colorArgb = android.graphics.Color.parseColor("#2196F3"),
                        isEnabled = true
                    )

                    if (geoFenceRepository.getGeoFences().isEmpty()) {
                        geoFenceRepository.addGeoFence(fence1)
                        geoFenceRepository.addGeoFence(fence2)
                    }
                }
            ),
            StartupStep(
                label = "Initializing map",
                dependsOn = listOf("Preparing device state", "Restoring preferences"),
                action = {
                    val storageBaseDir = java.io.File(applicationContext.filesDir, "osmdroid_storage")
                    val tilesStorageDir = java.io.File(storageBaseDir, "tiles")
                    if (!tilesStorageDir.exists()) {
                        tilesStorageDir.mkdirs()
                    }

                    org.osmdroid.config.Configuration.getInstance().apply {
                        load(applicationContext, applicationContext.getSharedPreferences("${applicationContext.packageName}_osm", android.content.Context.MODE_PRIVATE))
                        // Route map data permanently into persistent app storage (filesDir), NOT volatile system cache (cacheDir)
                        osmdroidBasePath = storageBaseDir
                        osmdroidTileCache = tilesStorageDir
                        userAgentValue = applicationContext.packageName

                        // High-performance balanced in-memory tile cache: 40 tiles + 12 overshoot (~13MB RAM)
                        // Fits full 1080x2408 viewport + margin, eliminating edge tile blink while keeping memory lean
                        cacheMapTileCount = 40
                        cacheMapTileOvershoot = 12

                        val maxBytes = (mapPreferencesRepository.offlinePreferences.value.maxCacheSizeMb.toLong()) * 1024 * 1024
                        tileFileSystemCacheMaxBytes = maxBytes
                        tileFileSystemCacheTrimBytes = (maxBytes * 0.75).toLong()
                        tileDownloadThreads = 4
                        tileFileSystemThreads = 2
                        // 30 days tile expiration override: guarantees Osmdroid will NOT re-download cached tiles from the internet
                        expirationOverrideDuration = 1000L * 60 * 60 * 24 * 30L
                        expirationExtendedDuration = 1000L * 60 * 60 * 24 * 30L
                    }

                    offlineMapManager = com.livetracker.controller.ui.screens.map.offline.OfflineMapManager(
                        context = applicationContext,
                        scope = applicationScope,
                        mapPreferencesRepository = mapPreferencesRepository
                    )
                    offlineMapManager.warmup()

                    // Pre-warm Google Maps SDK renderer on background thread
                    try {
                        com.google.android.gms.maps.MapsInitializer.initialize(
                            applicationContext,
                            com.google.android.gms.maps.MapsInitializer.Renderer.LATEST,
                            null
                        )
                    } catch (_: Throwable) {}

                    observeDeviceSummariesUseCase = ObserveDeviceSummariesUseCase(
                        trackerDeviceRepository,
                        trackerLocationRepository
                    )
                    observeMapStateUseCase = ObserveMapStateUseCase(
                        observeDeviceSummariesUseCase,
                        deviceSelectionManager
                    )
                }
            ),
            StartupStep(
                label = "Pre-warming UI shell & navigation",
                action = {
                    com.livetracker.controller.main.NavigationWarmup.warmup(applicationContext)
                }
            ),
            StartupStep(
                label = "Pre-warming map caches & vector graphics",
                dependsOn = listOf("Initializing map"),
                action = {
                    // Pre-warm geofences into in-memory cache
                    geoFenceRepository.getGeoFences()

                    // Pre-load initial markers from repository
                    val demoLocations = trackerLocationRepository.getAllLocations().first()
                    val devices = trackerDeviceRepository.getAllDevices()
                    val initialMarkers = devices.mapNotNull { dev ->
                        val loc = demoLocations.find { it.deviceId == dev.deviceId }
                        if (loc != null) {
                            com.livetracker.controller.core.tracker.map.domain.MapMarker(
                                deviceId = dev.deviceId,
                                displayName = dev.displayName,
                                latitude = loc.latitude,
                                longitude = loc.longitude,
                                locationState = loc.state,
                                accuracy = loc.accuracy,
                                bearing = loc.bearing,
                                speed = loc.speed,
                                timestamp = loc.timestamp,
                                connectionState = dev.connectionState,
                                healthState = dev.health,
                                isSelected = true,
                                batteryLevel = dev.batteryLevel,
                                networkState = dev.networkState
                            )
                        } else null
                    }

                    // Pre-warm vector marker bitmaps and geofence drag handle graphics
                    com.livetracker.controller.ui.screens.map.components.MapMarkerRenderer.warmup(
                        context = applicationContext,
                        markers = initialMarkers,
                        presetColors = com.livetracker.controller.ui.screens.map.geofence.GEOFENCE_PRESET_COLORS
                    )
                }
            ),
            StartupStep(
                label = "Preparing update system",
                action = {
                    val updatesDir = java.io.File(applicationContext.cacheDir, "updates")
                    if (!updatesDir.exists()) {
                        updatesDir.mkdirs()
                    }
                    val updateProvider = com.livetracker.controller.core.update.provider.HttpUpdateProvider(Dispatchers.IO)
                    val updateDownloader = com.livetracker.controller.core.update.downloader.HttpUpdateDownloader(Dispatchers.IO)
                    val packageInspector = com.livetracker.controller.core.update.verification.DefaultAndroidApkInspector(applicationContext)
                    updateVerifier = com.livetracker.controller.core.update.verification.DefaultUpdateVerifier(packageInspector, Dispatchers.IO)
                    updateInstaller = com.livetracker.controller.core.update.installer.AndroidPackageInstaller(applicationContext, Dispatchers.IO)
                    updatePreferencesRepository = com.livetracker.controller.core.update.storage.PersistentUpdatePreferencesRepository(applicationContext)

                    val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL
                    val packageInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
                    val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toLong()
                    }
                    val currentVersionName = packageInfo.versionName ?: "1.0.0"

                    updateRepository = com.livetracker.controller.core.update.domain.DefaultUpdateRepository(
                        appId = applicationContext.packageName,
                        currentVersionCode = currentVersionCode,
                        currentVersionName = currentVersionName,
                        manifestUrl = manifestUrl,
                        provider = updateProvider,
                        downloader = updateDownloader,
                        verifier = updateVerifier,
                        installer = updateInstaller,
                        preferences = updatePreferencesRepository,
                        updatesDirectory = updatesDir,
                        scope = applicationScope,
                        ioDispatcher = Dispatchers.IO
                    )

                    checkForUpdateUseCase = com.livetracker.controller.core.update.domain.usecase.CheckForUpdateUseCase(updateRepository)
                    downloadUpdateUseCase = com.livetracker.controller.core.update.domain.usecase.DownloadUpdateUseCase(updateRepository)
                    installUpdateUseCase = com.livetracker.controller.core.update.domain.usecase.InstallUpdateUseCase(updateRepository)
                    dismissUpdateUseCase = com.livetracker.controller.core.update.domain.usecase.DismissUpdateUseCase(updateRepository)
                    observeUpdateStateUseCase = com.livetracker.controller.core.update.domain.usecase.ObserveUpdateStateUseCase(updateRepository)
                }
            ),
            StartupStep(
                label = "Finalizing",
                dependsOn = listOf("Loading authentication", "Pre-warming map caches & vector graphics", "Pre-warming UI shell & navigation", "Preparing update system"),
                action = {
                    // All critical initialization complete.
                }
            )
        )

        startupOrchestrator = StartupOrchestrator(
            steps = steps,
            scope = applicationScope,
            defaultDispatcher = Dispatchers.Default,
            stepTimeoutMs = 15_000L
        )

        startupOrchestrator.start()

        // Non-blocking background update discovery: triggers after main shell becomes ready
        applicationScope.launch {
            startupOrchestrator.state.collect { state ->
                if (state is com.livetracker.controller.core.startup.StartupState.Ready) {
                    try {
                        kotlinx.coroutines.delay(2000L) // Settle initial composition
                        checkForUpdateUseCase(force = false)
                    } catch (_: Throwable) {
                        // Offline or background network errors are benign and never disrupt app startup
                    }
                }
            }
        }
    }
}
