package com.livetracker.controller.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.livetracker.controller.core.update.model.UpdateState
import com.livetracker.controller.navigation.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import com.livetracker.controller.ui.screens.devices.DevicesViewModel
import com.livetracker.controller.ui.screens.alerts.AlertsViewModel
import com.livetracker.controller.ui.screens.diagnostics.DiagnosticsViewModel
import com.livetracker.controller.ui.screens.updates.UpdatesViewModel
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.DoneAll
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShellScreen(
    onLogout: () -> Unit,
    viewModelFactory: ViewModelProvider.Factory? = null,
    navController: NavHostController = rememberNavController(),
    updateState: UpdateState = UpdateState.Idle,
    sessionPromptDismissed: Boolean = false,
    isVisitor: Boolean = false,
    currentUser: com.livetracker.controller.core.auth.domain.User? = null,
    onNavigateToUpdates: () -> Unit = {
        navController.navigate(Destination.Updates.route) {
            launchSingleTop = true
        }
    },
    onRefreshAccess: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // When on Map, fullscreen map takes priority: hide top & bottom bars and disable drawer gestures
    val isMap = currentDestination?.route == Destination.Map.route
    val isUpdatesScreen = currentDestination?.route == Destination.Updates.route

    val allDestinations = remember {
        primaryDestinations + serviceDestinations + adminDestinations + systemDestinations
    }
    var currentHeaderTitle by remember { mutableStateOf("Trace") }
    LaunchedEffect(currentDestination?.route) {
        val route = currentDestination?.route
        if (route != null && route != Destination.Map.route) {
            allDestinations.find { it.route == route }?.let {
                currentHeaderTitle = it.title
            }
        }
    }

    val context = LocalContext.current
    val owner = context as? androidx.lifecycle.ViewModelStoreOwner
    val devicesViewModel: DevicesViewModel? = remember(owner, viewModelFactory) {
        if (owner != null) {
            if (viewModelFactory != null) {
                ViewModelProvider(owner, viewModelFactory)[DevicesViewModel::class.java]
            } else {
                ViewModelProvider(owner)[DevicesViewModel::class.java]
            }
        } else null
    }
    val devicesUiState by devicesViewModel?.uiState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val devicesCount = devicesUiState?.totalCount ?: 0

    val alertsViewModel: AlertsViewModel? = remember(owner, viewModelFactory) {
        if (owner != null) {
            if (viewModelFactory != null) {
                ViewModelProvider(owner, viewModelFactory)[AlertsViewModel::class.java]
            } else {
                ViewModelProvider(owner)[AlertsViewModel::class.java]
            }
        } else null
    }
    val alertsUiState by alertsViewModel?.uiState?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }
    val alertsCount = alertsUiState?.totalUnacknowledgedCount ?: 0

    val diagnosticsViewModel: DiagnosticsViewModel? = remember(owner, viewModelFactory) {
        if (owner != null) {
            if (viewModelFactory != null) {
                ViewModelProvider(owner, viewModelFactory)[DiagnosticsViewModel::class.java]
            } else {
                ViewModelProvider(owner)[DiagnosticsViewModel::class.java]
            }
        } else null
    }

    val updatesViewModel: UpdatesViewModel? = remember(owner, viewModelFactory) {
        if (owner != null) {
            if (viewModelFactory != null) {
                ViewModelProvider(owner, viewModelFactory)[UpdatesViewModel::class.java]
            } else {
                ViewModelProvider(owner)[UpdatesViewModel::class.java]
            }
        } else null
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // When on Map: do not intercept map panning/swiping when the drawer is closed.
    // Only enable gestures when the drawer is open so the user can fluidly swipe it closed.
    // On other screens, gestures are always enabled.
    val gesturesEnabled = if (isMap) {
        drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
    } else {
        true
    }

    // Automatically inform the user when the app is confirmed to be up to date
    var lastInformedCheckedEpochMs by rememberSaveable { mutableStateOf(0L) }
    LaunchedEffect(updateState) {
        if (updateState is UpdateState.UpToDate) {
            val upToDate = updateState
            if (upToDate.lastCheckedEpochMs > 0 && upToDate.lastCheckedEpochMs != lastInformedCheckedEpochMs) {
                lastInformedCheckedEpochMs = upToDate.lastCheckedEpochMs
                snackbarHostState.showSnackbar(
                    message = "Trace is up to date (v${upToDate.currentVersionName})",
                    duration = SnackbarDuration.Short,
                    withDismissAction = true
                )
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            TraceAppDrawer(
                currentRoute = currentDestination?.route,
                onNavigate = { dest ->
                    scope.launch { drawerState.close() }
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onLogout = {
                    scope.launch { drawerState.close() }
                    onLogout()
                },
                currentUser = currentUser,
                onCloseDrawer = {
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                AnimatedVisibility(
                    visible = !isMap,
                    enter = slideInVertically(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        initialOffsetY = { -it }
                    ) + fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing),
                        targetOffsetY = { -it }
                    ) + fadeOut(animationSpec = tween(180))
                ) {
                    val isDevicesScreen = currentDestination?.route == Destination.Devices.route
                    val isAlertsScreen = currentDestination?.route == Destination.Alerts.route
    val isDiagnosticsScreen = currentDestination?.route == Destination.Diagnostics.route
    val isSettingsScreen = currentDestination?.route == Destination.Settings.route
    val isCameraScreen = currentDestination?.route == Destination.Camera.route
    val isAudioScreen = currentDestination?.route == Destination.Audio.route
    val isScreenCaptureScreen = currentDestination?.route == Destination.ScreenCapture.route
    val isFilesScreen = currentDestination?.route == Destination.Files.route
    val isUsersScreen = currentDestination?.route == Destination.Users.route
    val isPermissionsScreen = currentDestination?.route == Destination.Permissions.route
    val isAuditLogScreen = currentDestination?.route == Destination.AuditLog.route
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TopAppBar(
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            title = {
                                if (isDevicesScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Device Fleet",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        if (devicesCount > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                            ) {
                                                Text(
                                                    text = "$devicesCount",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                } else if (isUsersScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Users & Access",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                        ) {
                                            Text(
                                                text = "ADMIN CONTROL",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    letterSpacing = 0.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                } else if (isFilesScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Remote Files",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                        ) {
                                            Text(
                                                text = "FILE MANAGER",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    letterSpacing = 0.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                } else if (isScreenCaptureScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Screen Capture",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                        ) {
                                            Text(
                                                text = "REMOTE MIRROR",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    letterSpacing = 0.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                } else if (isAudioScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Remote Audio",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                        ) {
                                            Text(
                                                text = "SURVEILLANCE",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    letterSpacing = 0.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                } else if (isCameraScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Live Camera",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                        ) {
                                            Text(
                                                text = "REMOTE VIDEO",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    letterSpacing = 0.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                } else if (isSettingsScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Settings",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                        ) {
                                            Text(
                                                text = "PREFERENCES",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    letterSpacing = 0.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                } else if (isDiagnosticsScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Diagnostics",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = com.livetracker.controller.ui.theme.StatusLive.copy(alpha = 0.12f),
                                            border = BorderStroke(1.dp, com.livetracker.controller.ui.theme.StatusLive.copy(alpha = 0.30f))
                                        ) {
                                            Text(
                                                text = "OPTIMAL",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    letterSpacing = 0.5.sp
                                                ),
                                                color = com.livetracker.controller.ui.theme.StatusLive,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                } else if (isAlertsScreen) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Alerts",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.5.sp,
                                                letterSpacing = 0.3.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        if (alertsCount > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = com.livetracker.controller.ui.theme.StatusError.copy(alpha = 0.12f),
                                                border = BorderStroke(1.dp, com.livetracker.controller.ui.theme.StatusError.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    text = "$alertsCount",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 11.sp
                                                    ),
                                                    color = com.livetracker.controller.ui.theme.StatusError,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = currentHeaderTitle,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.5.sp,
                                            letterSpacing = 0.3.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            },
                            navigationIcon = {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .size(36.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                drawerState.open()
                                                try {
                                                    (context.applicationContext as? com.livetracker.controller.TraceApplication)?.supabaseFleetManager?.reconcileEffectivePermissions()
                                                } catch (_: Exception) {}
                                            }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Menu,
                                            contentDescription = "Menu",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (isDevicesScreen && devicesViewModel != null) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(36.dp)
                                    ) {
                                        IconButton(
                                            onClick = { devicesViewModel.refresh() },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Refresh Devices",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                } else if (isAlertsScreen && alertsViewModel != null && alertsCount > 0) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(36.dp)
                                    ) {
                                        IconButton(
                                            onClick = { alertsViewModel.acknowledgeAll() },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DoneAll,
                                                contentDescription = "Acknowledge All Alerts",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                } else if (isDiagnosticsScreen && diagnosticsViewModel != null) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(36.dp)
                                    ) {
                                        IconButton(
                                            onClick = { diagnosticsViewModel.runDiagnostics() },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Run Diagnostics Probe",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                } else if (isUpdatesScreen && updatesViewModel != null) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(36.dp)
                                    ) {
                                        IconButton(
                                            onClick = { updatesViewModel.checkForUpdates() },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Check for Updates",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                            thickness = 1.dp
                        )
                    }
                }
            },
            bottomBar = {
                if (!isMap && !isVisitor) {
                    TraceBottomBar(
                        currentRoute = currentDestination?.route,
                        alertsCount = alertsCount,
                        devicesCount = devicesCount,
                        currentUser = currentUser,
                        onNavigate = { dest ->
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                TrackMateNavHost(
                    navController = navController,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                    viewModelFactory = viewModelFactory,
                    currentUser = currentUser,
                    onOpenDrawer = {
                        scope.launch {
                            drawerState.open()
                            try {
                                (context.applicationContext as? com.livetracker.controller.TraceApplication)?.supabaseFleetManager?.reconcileEffectivePermissions()
                            } catch (_: Exception) {}
                        }
                    }
                )

                if (isVisitor) {
                    com.livetracker.controller.ui.components.VisitorDemoBanner(
                        onRefreshAccess = onRefreshAccess,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = if (isMap) 16.dp else innerPadding.calculateTopPadding())
                    )
                }

                // Persistent in-app notice when an update is available but user dismissed modal prompt during current session
                val showBanner = updateState is UpdateState.UpdateAvailable && sessionPromptDismissed && !isUpdatesScreen
                AnimatedVisibility(
                    visible = showBanner,
                    enter = slideInVertically(
                        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        initialOffsetY = { -it }
                    ) + fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(
                        animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing),
                        targetOffsetY = { -it }
                    ) + fadeOut(animationSpec = tween(150)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(
                            top = if (!isMap) innerPadding.calculateTopPadding() + 8.dp else 40.dp,
                            start = 12.dp,
                            end = 12.dp
                        )
                ) {
                    val updateInfo = (updateState as? UpdateState.UpdateAvailable)?.updateInfo
                    if (updateInfo != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            tonalElevation = 4.dp,
                            shadowElevation = 6.dp,
                            onClick = onNavigateToUpdates,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Update available",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "App is not up to date",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = "v${updateInfo.latestVersionName} (Build ${updateInfo.latestVersionCode}) is available",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = onNavigateToUpdates,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Update", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
