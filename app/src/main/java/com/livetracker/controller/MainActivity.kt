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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.livetracker.controller.auth.AuthenticationViewModel
import com.livetracker.controller.auth.LoginScreen
import com.livetracker.controller.auth.StatusScreen
import com.livetracker.controller.auth.pin.PinScreen
import com.livetracker.controller.auth.pin.PinViewModel
import com.livetracker.controller.core.auth.presentation.AuthenticationState
import com.livetracker.controller.core.startup.StartupState
import com.livetracker.controller.main.MainShellScreen
import com.livetracker.controller.navigation.Destination
import com.livetracker.controller.ui.screens.startup.StartupFailureScreen
import com.livetracker.controller.ui.screens.startup.StartupFatalScreen
import com.livetracker.controller.ui.screens.startup.TraceLoadingScreen

class MainActivity : ComponentActivity() {

    private var isBackgrounded = false
    private val sessionPromptDismissedState = mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        val app = application as TraceApplication
        if (isBackgrounded) {
            sessionPromptDismissedState.value = false
            isBackgrounded = false
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(PinViewModel::class.java)) {
                    return PinViewModel(app.pinManager) as T
                }
                if (modelClass.isAssignableFrom(AuthenticationViewModel::class.java)) {
                    return AuthenticationViewModel(
                        app.restoreSessionUseCase,
                        app.authenticateUserUseCase,
                        app.validateSessionUseCase,
                        app.logoutUserUseCase
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
                if (modelClass.isAssignableFrom(com.livetracker.controller.ui.screens.alerts.AlertsViewModel::class.java)) {
                    return com.livetracker.controller.ui.screens.alerts.AlertsViewModel(app.alertRepository) as T
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
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }

        setContent {
            com.livetracker.controller.ui.theme.TraceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Observe startup orchestrator state
                    val startupState by app.startupOrchestrator.state.collectAsStateWithLifecycle()

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
                                    onLoginSubmit = { authViewModel.login(it) },
                                    isLoading = false
                                )

                                is AuthenticationState.Authenticating -> LoginScreen(
                                    onLoginSubmit = {},
                                    isLoading = true
                                )

                                is AuthenticationState.Error -> LoginScreen(
                                    onLoginSubmit = { authViewModel.login(it) },
                                    isLoading = false,
                                    errorMessage = state.message
                                )

                                is AuthenticationState.SessionExpired -> StatusScreen(
                                    title = "Session Expired",
                                    message = "Your session has expired. Please log in again.",
                                    actionText = "Log In",
                                    onAction = { authViewModel.logout() }
                                )

                                is AuthenticationState.AccountDisabled -> StatusScreen(
                                    title = "Account Disabled",
                                    message = "Your account has been disabled. Contact an administrator.",
                                    actionText = "Back",
                                    onAction = { authViewModel.logout() }
                                )

                                is AuthenticationState.AccountSuspended -> StatusScreen(
                                    title = "Account Suspended",
                                    message = "Your account has been suspended.",
                                    actionText = "Back",
                                    onAction = { authViewModel.logout() }
                                )

                                is AuthenticationState.AuthenticationUnavailable -> StatusScreen(
                                    title = "Service Unavailable",
                                    message = "Authentication services are currently unavailable.",
                                    actionText = "Retry",
                                    onAction = { authViewModel.logout() }
                                )

                                is AuthenticationState.Authenticated -> {
                                    // PIN Gate — security model preserved exactly with fluid spatial transition
                                    var pinPassed by rememberSaveable { mutableStateOf(false) }

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
                                            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

                                            val navBackStackEntry by mainNavController.currentBackStackEntryAsState()
                                            val currentRoute = navBackStackEntry?.destination?.route
                                            val isUpdatesScreen = currentRoute == Destination.Updates.route

                                            MainShellScreen(
                                                onLogout = {
                                                    pinPassed = false
                                                    authViewModel.logout()
                                                },
                                                viewModelFactory = factory,
                                                navController = mainNavController,
                                                updateState = updateState,
                                                sessionPromptDismissed = sessionPromptDismissedState.value,
                                                onNavigateToUpdates = {
                                                    mainNavController.navigate(Destination.Updates.route) {
                                                        launchSingleTop = true
                                                    }
                                                }
                                            )

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
