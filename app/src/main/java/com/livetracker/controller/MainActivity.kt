package com.livetracker.controller

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.livetracker.controller.auth.AccountDisabledScreen
import com.livetracker.controller.auth.AccountPendingApprovalScreen
import com.livetracker.controller.auth.AccountSuspendedScreen
import com.livetracker.controller.auth.AuthenticationViewModel
import com.livetracker.controller.auth.GoogleSignInManager
import com.livetracker.controller.auth.GoogleSignInResult
import com.livetracker.controller.auth.LoginScreen
import com.livetracker.controller.auth.StatusScreen
import com.livetracker.controller.auth.pin.PinScreen
import com.livetracker.controller.auth.pin.PinViewModel
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.presentation.AuthenticationState
import com.livetracker.controller.core.startup.StartupState
import com.livetracker.controller.main.MainShellScreen
import com.livetracker.controller.navigation.Destination
import com.livetracker.controller.ui.screens.startup.StartupFailureScreen
import com.livetracker.controller.ui.screens.startup.StartupFatalScreen
import com.livetracker.controller.ui.screens.startup.TraceLoadingScreen

class MainActivity : ComponentActivity() {

    private val googleSignInManager by lazy { GoogleSignInManager(this) }
    private var isBackgrounded = false
    private val sessionPromptDismissedState = mutableStateOf(false)

    private fun performLogout(authViewModel: AuthenticationViewModel) {
        lifecycleScope.launch {
            googleSignInManager.signOut()
            authViewModel.logout()
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as TraceApplication
        if (isBackgrounded) {
            sessionPromptDismissedState.value = false
            isBackgrounded = false
        }
        // Reconcile effective permissions on app resume
        lifecycleScope.launch {
            try {
                app.supabaseFleetManager.reconcileEffectivePermissions()
            } catch (_: Throwable) {
                // Background permission reconciliation errors are handled gracefully
            }
        }
        // Whenever the user tries to open the app, automatically check for updates
        lifecycleScope.launch {
            try {
                if (app.startupOrchestrator.state.value !is StartupState.Ready) {
                    app.startupOrchestrator.state.first { it is StartupState.Ready }
                }
                app.checkForUpdateUseCase(force = true)
            } catch (_: Throwable) {
                // Background update check errors are handled gracefully
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            isBackgrounded = true
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            (application as? TraceApplication)?.refreshFleetIfReady()
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable fullscreen immersive edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        // Get process-wide dependencies from TraceApplication — NO re-construction on Activity recreation
        val app = application as TraceApplication

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                if (modelClass.isAssignableFrom(PinViewModel::class.java)) {
                    return PinViewModel(app.pinManager) as T
                }
                if (modelClass.isAssignableFrom(AuthenticationViewModel::class.java)) {
                    return AuthenticationViewModel(
                        app.restoreSessionUseCase,
                        app.authenticateUserUseCase,
                        app.validateSessionUseCase,
                        app.logoutUserUseCase,
                        app.sessionRepository
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.map.MapViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.map.MapViewModel(
                        app.observeMapStateUseCase,
                        app.geoFenceRepository,
                        app.mapPreferencesRepository,
                        app.deviceSelectionManager,
                        app.observeDeviceSummariesUseCase,
                        app.trackerDeviceRepository,
                        app.trackerLocationRepository,
                        app.evaluateGeoFencesUseCase,
                        app.alertRepository
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.devices.DevicesViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.devices.DevicesViewModel(
                        observeDeviceSummariesUseCase = app.observeDeviceSummariesUseCase,
                        deviceSelectionManager = app.deviceSelectionManager,
                        sessionRepository = app.sessionRepository,
                        supabaseFleetManager = app.supabaseFleetManager,
                        trackerDeviceRepository = app.trackerDeviceRepository,
                        trackerLocationRepository = app.trackerLocationRepository
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.alerts.AlertsViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.alerts.AlertsViewModel(
                        alertRepository = app.alertRepository,
                        trackerDeviceRepository = app.trackerDeviceRepository
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.settings.SettingsViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.settings.SettingsViewModel(
                        app.mapPreferencesRepository,
                        app.offlineMapManager
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.updates.UpdatesViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.updates.UpdatesViewModel(
                        currentVersionCode = app.updateRepository.currentVersionCode,
                        currentVersionName = app.updateRepository.currentVersionName,
                        checkForUpdateUseCase = app.checkForUpdateUseCase,
                        downloadUpdateUseCase = app.downloadUpdateUseCase,
                        installUpdateUseCase = app.installUpdateUseCase,
                        dismissUpdateUseCase = app.dismissUpdateUseCase,
                        observeUpdateStateUseCase = app.observeUpdateStateUseCase,
                        preferencesRepository = app.updatePreferencesRepository,
                        updateInstaller = app.updateInstaller
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.camera.CameraViewModel::class.java)) {
                    val savedStateHandle = runCatching { extras.createSavedStateHandle() }.getOrNull()
                    return com.livetracker.controller.ui.screens.camera.CameraViewModel(
                        cameraRepository = app.cameraRepository,
                        trackerDeviceRepository = app.trackerDeviceRepository,
                        deviceSelectionManager = app.deviceSelectionManager,
                        sessionRepository = app.sessionRepository,
                        authEngine = app.authorizationEngine,
                        savedStateHandle = savedStateHandle,
                        webRtcSessionManager = app.webRtcSessionManager,
                        timeProvider = { System.currentTimeMillis() }
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.users.UsersViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.users.UsersViewModel(
                        sessionRepository = app.sessionRepository,
                        supabaseFleetManager = app.supabaseFleetManager
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.audio.AudioViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.audio.AudioViewModel(
                        audioRepository = app.audioRepository,
                        deviceSelectionManager = app.deviceSelectionManager,
                        sessionRepository = app.sessionRepository,
                        trackerDeviceRepository = app.trackerDeviceRepository
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.screen.ScreenCaptureViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.screen.ScreenCaptureViewModel(
                        screenCaptureRepository = app.screenCaptureRepository,
                        webRtcSessionManager = app.webRtcSessionManager,
                        deviceSelectionManager = app.deviceSelectionManager,
                        sessionRepository = app.sessionRepository,
                        trackerDeviceRepository = app.trackerDeviceRepository
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.files.FilesViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.files.FilesViewModel(
                        fileTransferRepository = app.fileTransferRepository,
                        deviceSelectionManager = app.deviceSelectionManager,
                        sessionRepository = app.sessionRepository,
                        trackerDeviceRepository = app.trackerDeviceRepository
                    ) as T
                }
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.diagnostics.DiagnosticsViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.diagnostics.DiagnosticsViewModel(
                        trackerDeviceRepository = app.trackerDeviceRepository,
                        alertRepository = app.alertRepository
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }

            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                create(modelClass, CreationExtras.Empty)
        }

        setContent {
            val startupState by app.startupOrchestrator.state.collectAsStateWithLifecycle()
            val themeConfig = if (startupState is StartupState.Ready) {
                val observedTheme by app.mapPreferencesRepository.themeConfiguration.collectAsStateWithLifecycle()
                observedTheme
            } else {
                com.livetracker.controller.ui.theme.ThemeConfiguration()
            }

            com.livetracker.controller.ui.theme.TraceTheme(themeConfig = themeConfig) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val startup = startupState) {
                        // Phase 1: Show Trace loading screen during initialization
                        is StartupState.Idle,
                        is StartupState.Loading -> {
                            TraceLoadingScreen(startupState = startup)
                        }

                        // Phase 2: Startup failed — show recovery screen
                        is StartupState.RecoverableFailure -> {
                            StartupFailureScreen(
                                failedStep = startup.failedStep,
                                message = startup.message,
                                onRetry = { app.startupOrchestrator.retry() }
                            )
                        }

                        is StartupState.FatalFailure -> {
                            StartupFatalScreen(message = startup.message)
                        }

                        // Phase 3: Startup complete — proceed to auth/PIN/main flow
                        is StartupState.Ready -> {
                            val authViewModel: AuthenticationViewModel = viewModel(factory = factory)
                            val authState by authViewModel.state.collectAsStateWithLifecycle()
                            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                            var localAuthError by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }

                            val googleSignInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
                            ) { result ->
                                val parsed = googleSignInManager.parseSignInResult(result.resultCode, result.data)
                                when (parsed) {
                                    is GoogleSignInResult.Success -> {
                                        localAuthError = null
                                        android.util.Log.i("MainActivity", "Google sign in successful for ${parsed.email}, exchanging ID token with GoTrue")
                                        val payload = "id_token:${parsed.idToken}"
                                        authViewModel.login(payload)
                                    }
                                    is GoogleSignInResult.Error -> {
                                        android.util.Log.e("MainActivity", "Google Sign-In Error: ${parsed.message}", parsed.cause)
                                        localAuthError = parsed.message
                                    }
                                    is GoogleSignInResult.Cancelled -> {
                                        android.util.Log.d("MainActivity", "Google Sign-In dismissed by user")
                                    }
                                }
                            }

                            val handleGoogleSignIn: () -> Unit = {
                                localAuthError = null
                                val serverClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
                                coroutineScope.launch {
                                    googleSignInManager.startSignIn(
                                        activity = this@MainActivity,
                                        serverClientId = serverClientId,
                                        fallbackLauncher = googleSignInLauncher,
                                        onResult = { result ->
                                            when (result) {
                                                is GoogleSignInResult.Success -> {
                                                    localAuthError = null
                                                    android.util.Log.i("MainActivity", "CredentialManager Google sign in successful for ${result.email}")
                                                    val payload = "id_token:${result.idToken}"
                                                    authViewModel.login(payload)
                                                }
                                                is GoogleSignInResult.Error -> {
                                                    android.util.Log.e("MainActivity", "Google Sign-In Error: ${result.message}", result.cause)
                                                    localAuthError = result.message
                                                }
                                                is GoogleSignInResult.Cancelled -> {
                                                    android.util.Log.d("MainActivity", "Google Sign-In dismissed by user")
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            when (val state = authState) {
                                is AuthenticationState.Initializing -> {
                                    // Auth is still restoring session — show loading
                                    // (brief — just awaiting the session restore coroutine)
                                    TraceLoadingScreen(
                                        startupState = StartupState.Loading(
                                            completedSteps = 6,
                                            totalSteps = 7,
                                            currentStepLabel = "Restoring session"
                                        )
                                    )
                                }

                                is AuthenticationState.Unauthenticated -> LoginScreen(
                                    onLoginSubmit = {
                                        localAuthError = null
                                        authViewModel.login(it)
                                    },
                                    onGoogleSignIn = handleGoogleSignIn,
                                    isLoading = false,
                                    errorMessage = localAuthError
                                )

                                is AuthenticationState.Authenticating -> LoginScreen(
                                    onLoginSubmit = {},
                                    onGoogleSignIn = {},
                                    isLoading = true
                                )

                                is AuthenticationState.Error -> LoginScreen(
                                    onLoginSubmit = {
                                        localAuthError = null
                                        authViewModel.login(it)
                                    },
                                    onGoogleSignIn = handleGoogleSignIn,
                                    isLoading = false,
                                    errorMessage = state.message
                                )

                                is AuthenticationState.SessionExpired -> StatusScreen(
                                    title = "Session Expired",
                                    message = "Your session has expired. Please log in again.",
                                    actionText = "Log In",
                                    onAction = { performLogout(authViewModel) }
                                )

                                is AuthenticationState.AccountDisabled -> AccountDisabledScreen(
                                    onSignOut = { performLogout(authViewModel) }
                                )

                                is AuthenticationState.AccountSuspended -> AccountSuspendedScreen(
                                    onSignOut = { performLogout(authViewModel) }
                                )

                                is AuthenticationState.AccountPending -> AccountPendingApprovalScreen(
                                    onCheckStatus = { authViewModel.restoreSession() },
                                    onSignOut = { performLogout(authViewModel) }
                                )

                                is AuthenticationState.AuthenticationUnavailable -> StatusScreen(
                                    title = "Service Unavailable",
                                    message = "Authentication services are currently unavailable.",
                                    actionText = "Retry",
                                    onAction = { performLogout(authViewModel) }
                                )

                                is AuthenticationState.Authenticated -> {
                                    val isVisitor = state.session.user.role == Role.VISITOR
                                    // Visitor accounts bypass operational PIN gate directly into demo sandbox
                                    var pinPassed by rememberSaveable(state.session.sessionId) { mutableStateOf(isVisitor) }

                                    AnimatedContent(
                                        targetState = pinPassed,
                                        transitionSpec = {
                                            (fadeIn(animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing)) +
                                             scaleIn(initialScale = 0.93f, animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)))
                                                .togetherWith(
                                                    fadeOut(animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)) +
                                                    scaleOut(targetScale = 1.05f, animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing))
                                                )
                                        },
                                        label = "PinToMainTransition"
                                    ) { passed ->
                                        if (passed) {
                                            val mainNavController = rememberNavController()
                                            val updateState by app.updateRepository.updateState.collectAsStateWithLifecycle()

                                            val navBackStackEntry by mainNavController.currentBackStackEntryAsState()
                                            val currentRoute = navBackStackEntry?.destination?.route
                                            val isUpdatesScreen = currentRoute == Destination.Updates.route

                                            MainShellScreen(
                                                onLogout = {
                                                    pinPassed = false
                                                    performLogout(authViewModel)
                                                },
                                                viewModelFactory = factory,
                                                navController = mainNavController,
                                                updateState = updateState,
                                                sessionPromptDismissed = sessionPromptDismissedState.value,
                                                isVisitor = isVisitor,
                                                currentUser = state.session.user,
                                                onNavigateToUpdates = {
                                                    mainNavController.navigate(Destination.Updates.route) {
                                                        launchSingleTop = true
                                                    }
                                                },
                                                onRefreshAccess = {
                                                    authViewModel.restoreSession()
                                                }
                                            )

                                            val activeAlarm by app.sirenAlertEngine.activeAlarm.collectAsStateWithLifecycle()
                                            val alarmToDisplay = activeAlarm
                                            if (alarmToDisplay != null && !alarmToDisplay.alertId.startsWith("preview_")) {
                                                com.livetracker.controller.ui.screens.map.geofence.PerimeterBreachDialog(
                                                    alarm = alarmToDisplay,
                                                    onDismiss = { app.sirenAlertEngine.dismissAlarm() }
                                                )
                                            }

                                            val showUpdatePrompt = updateState is com.livetracker.controller.core.update.model.UpdateState.UpdateAvailable &&
                                                !sessionPromptDismissedState.value &&
                                                !isUpdatesScreen

                                            if (showUpdatePrompt) {
                                                val updateInfo = (updateState as com.livetracker.controller.core.update.model.UpdateState.UpdateAvailable).updateInfo
                                                com.livetracker.controller.ui.screens.updates.UpdatePromptDialog(
                                                    updateInfo = updateInfo,
                                                    onUpdateNow = {
                                                        sessionPromptDismissedState.value = true
                                                        coroutineScope.launch {
                                                            app.downloadUpdateUseCase()
                                                        }
                                                        mainNavController.navigate(Destination.Updates.route) {
                                                            launchSingleTop = true
                                                        }
                                                    },
                                                    onLater = {
                                                        sessionPromptDismissedState.value = true
                                                    }
                                                )
                                            }
                                        } else {
                                            val pinViewModel: PinViewModel = viewModel(factory = factory)
                                            PinScreen(
                                                viewModel = pinViewModel,
                                                onAuthenticated = { pinPassed = true }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
