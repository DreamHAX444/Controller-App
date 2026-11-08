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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        primaryDestinations + serviceDestinations + adminDestinations + systemDestinations + Destination.Profile
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

    val cameraViewModel: com.livetracker.controller.ui.screens.camera.CameraViewModel? = remember(owner, viewModelFactory) {
        if (owner != null) {
            if (viewModelFactory != null) {
                ViewModelProvider(owner, viewModelFactory)[com.livetracker.controller.ui.screens.camera.CameraViewModel::class.java]
            } else {
                ViewModelProvider(owner)[com.livetracker.controller.ui.screens.camera.CameraViewModel::class.java]
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
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = if (isMap) 188.dp else 84.dp)
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                AnimatedVisibility(
                    visible = !isMap && currentDestination?.route != Destination.Camera.route,
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
                            windowInsets = TopAppBarDefaults.windowInsets,
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
                                } else if (isAlertsScreen && alertsViewModel != null) {
                                    val alertsUiState by alertsViewModel.uiState.collectAsStateWithLifecycle()
                                    val activeAlarm by alertsViewModel.activeAlarm.collectAsStateWithLifecycle()
                                    val unackCount = alertsUiState.totalUnacknowledgedCount

                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Alert Center",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 17.sp,
                                                    letterSpacing = 0.3.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )

                                            if (unackCount > 0) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = com.livetracker.controller.ui.theme.StatusError.copy(alpha = 0.14f),
                                                    border = BorderStroke(0.85.dp, com.livetracker.controller.ui.theme.StatusError.copy(alpha = 0.35f))
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .clip(CircleShape)
                                                                .background(com.livetracker.controller.ui.theme.StatusError)
                                                        )
                                                        Text(
                                                            text = "$unackCount CRITICAL",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 9.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                letterSpacing = 0.5.sp
                                                            ),
                                                            color = com.livetracker.controller.ui.theme.StatusError
                                                        )
                                                    }
                                                }
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = com.livetracker.controller.ui.theme.StatusLive.copy(alpha = 0.14f),
                                                    border = BorderStroke(0.85.dp, com.livetracker.controller.ui.theme.StatusLive.copy(alpha = 0.35f))
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .clip(CircleShape)
                                                                .background(com.livetracker.controller.ui.theme.StatusLive)
                                                        )
                                                        Text(
                                                            text = "MONITORING ACTIVE",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 9.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                letterSpacing = 0.5.sp
                                                            ),
                                                            color = com.livetracker.controller.ui.theme.StatusLive
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Text(
                                            text = if (activeAlarm != null) "SIREN ACTIVE • AUDIBLE ALARM" else if (unackCount > 0) "$unackCount UNRESOLVED INCIDENT${if (unackCount == 1) "" else "S"}" else "ALL INCIDENTS RESOLVED",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (activeAlarm != null) com.livetracker.controller.ui.theme.StatusError else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                            )
                                        )
                                    }
                                } else if (isDiagnosticsScreen && diagnosticsViewModel != null) {
                                    val diagUiState by diagnosticsViewModel.uiState.collectAsStateWithLifecycle()
                                    val overallStatusColor = when {
                                        !diagUiState.isNetworkOnline -> com.livetracker.controller.ui.theme.StatusError
                                        diagUiState.subsystems.any { it.isWarning } -> com.livetracker.controller.ui.theme.StatusWarning
                                        diagUiState.subsystems.any { !it.isHealthy && !it.isWarning } -> com.livetracker.controller.ui.theme.StatusError
                                        else -> com.livetracker.controller.ui.theme.StatusLive
                                    }

                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Diagnostics",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 17.sp,
                                                    letterSpacing = 0.3.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = overallStatusColor.copy(alpha = 0.14f),
                                                border = BorderStroke(0.85.dp, overallStatusColor.copy(alpha = 0.35f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(overallStatusColor)
                                                    )
                                                    Text(
                                                        text = if (diagUiState.isNetworkOnline && diagUiState.subsystems.all { it.isHealthy }) "NOMINAL" else "ATTENTION",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 9.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            letterSpacing = 0.5.sp
                                                        ),
                                                        color = overallStatusColor
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = "PROBED ${diagUiState.lastCheckTime}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                            )
                                        )
                                    }
                                } else if (isCameraScreen && cameraViewModel != null) {
                                    val cameraUiState by cameraViewModel.uiState.collectAsStateWithLifecycle()
                                    var deviceDropdownOpen by remember { mutableStateOf(false) }

                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Live Camera",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 17.sp,
                                                    letterSpacing = 0.3.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )

                                            Box {
                                                Surface(
                                                    onClick = { deviceDropdownOpen = true },
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                                                    border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(6.dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                    if (cameraUiState.isDeviceOnline) com.livetracker.controller.ui.theme.StatusLive
                                                                    else com.livetracker.controller.ui.theme.StatusOffline
                                                                )
                                                        )
                                                        Text(
                                                            text = cameraUiState.selectedTrackerDisplayName.ifBlank { "SELECT ASSET" }.uppercase(),
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily.Monospace,
                                                                fontSize = 9.sp,
                                                                letterSpacing = 0.4.sp
                                                            ),
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowDropDown,
                                                            contentDescription = "Select Tracker Device",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }

                                                DropdownMenu(
                                                    expanded = deviceDropdownOpen,
                                                    onDismissRequest = { deviceDropdownOpen = false }
                                                ) {
                                                    if (cameraUiState.availableTrackers.isEmpty()) {
                                                        DropdownMenuItem(
                                                            text = { Text("No tracker devices available", fontSize = 12.sp) },
                                                            onClick = { deviceDropdownOpen = false }
                                                        )
                                                    } else {
                                                        cameraUiState.availableTrackers.forEach { tracker ->
                                                            val isSelected = tracker.deviceId == cameraUiState.selectedTrackerId
                                                            DropdownMenuItem(
                                                                leadingIcon = {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(8.dp)
                                                                            .clip(CircleShape)
                                                                            .background(if (tracker.isOnline) com.livetracker.controller.ui.theme.StatusLive else com.livetracker.controller.ui.theme.StatusOffline)
                                                                    )
                                                                },
                                                                text = {
                                                                    Column {
                                                                        Text(
                                                                            text = tracker.displayName,
                                                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                                            ),
                                                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                                        )
                                                                        Text(
                                                                            text = "${if (tracker.isOnline) "ONLINE" else "OFFLINE"} • ${if (tracker.isCameraSupported) "CAMERA READY" else "NO CAMERA"}",
                                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                                fontFamily = FontFamily.Monospace,
                                                                                fontSize = 9.sp
                                                                            ),
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                                                        )
                                                                    }
                                                                },
                                                                onClick = {
                                                                    deviceDropdownOpen = false
                                                                    cameraViewModel.selectTracker(tracker.deviceId)
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Text(
                                            text = when {
                                                cameraUiState.isStreaming -> "STREAMING • SENSOR ${cameraUiState.selectedCameraId ?: "0"} • ${cameraUiState.selectedProfile.name}"
                                                cameraUiState.canStart -> "READY TO STREAM • ${cameraUiState.discoveredCameras.size} SENSORS"
                                                else -> cameraUiState.gatingReason.name.replace("_", " ")
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (cameraUiState.isStreaming) com.livetracker.controller.ui.theme.StatusLive else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                            )
                                        )
                                    }
                                } else if (isSettingsScreen) {
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "System Settings",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 17.sp,
                                                    letterSpacing = 0.3.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                                border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    text = "PREFERENCES",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        letterSpacing = 0.5.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "CARTOGRAPHY & SYSTEM THEME",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                            )
                                        )
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
                                        .size(40.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                drawerState.open()
                                            }
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Menu,
                                            contentDescription = "Menu",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                            modifier = Modifier.size(20.dp)
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
                                            .size(40.dp)
                                    ) {
                                        IconButton(
                                            onClick = { devicesViewModel.refresh() },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Refresh Devices",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(19.dp)
                                            )
                                        }
                                    }
                                } else if (isAlertsScreen && alertsViewModel != null) {
                                    val alertsUiState by alertsViewModel.uiState.collectAsStateWithLifecycle()
                                    val unackCount = alertsUiState.totalUnacknowledgedCount

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(end = 12.dp)
                                    ) {
                                        if (unackCount > 0) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                                border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                                modifier = Modifier.size(38.dp)
                                            ) {
                                                IconButton(
                                                    onClick = { alertsViewModel.acknowledgeAll() },
                                                    modifier = Modifier.size(38.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DoneAll,
                                                        contentDescription = "Acknowledge All Alerts",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                            border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            IconButton(
                                                onClick = { alertsViewModel.openConfigSheet() },
                                                modifier = Modifier.size(38.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Settings,
                                                    contentDescription = "Alert & Siren Settings",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                        }
                                    }
                                } else if (isDiagnosticsScreen && diagnosticsViewModel != null) {
                                    val diagUiState by diagnosticsViewModel.uiState.collectAsStateWithLifecycle()
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(end = 12.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (diagUiState.isAutoRefreshActive) {
                                                com.livetracker.controller.ui.theme.StatusLive.copy(alpha = 0.12f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                                            },
                                            border = BorderStroke(
                                                0.85.dp,
                                                if (diagUiState.isAutoRefreshActive) {
                                                    com.livetracker.controller.ui.theme.StatusLive.copy(alpha = 0.35f)
                                                } else {
                                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                                }
                                            ),
                                            modifier = Modifier.clickable { diagnosticsViewModel.toggleAutoRefresh() }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (diagUiState.isAutoRefreshActive) com.livetracker.controller.ui.theme.StatusLive
                                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                        )
                                                )
                                                Text(
                                                    text = if (diagUiState.isAutoRefreshActive) "LIVE (4s)" else "PAUSED",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 10.sp,
                                                        letterSpacing = 0.4.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    ),
                                                    color = if (diagUiState.isAutoRefreshActive) com.livetracker.controller.ui.theme.StatusLive
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            IconButton(
                                                onClick = { diagnosticsViewModel.runDiagnostics() },
                                                enabled = !diagUiState.isChecking,
                                                modifier = Modifier.size(38.dp)
                                            ) {
                                                if (diagUiState.isChecking) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = "Run Diagnostics Probe",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(19.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (isCameraScreen && cameraViewModel != null) {
                                    val cameraUiState by cameraViewModel.uiState.collectAsStateWithLifecycle()
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(end = 12.dp)
                                    ) {
                                        if (cameraUiState.isStreaming) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                                border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                IconButton(
                                                    onClick = { cameraViewModel.switchCamera() },
                                                    modifier = Modifier.size(36.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.FlipCameraAndroid,
                                                        contentDescription = "Switch Camera Lens",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                            border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            IconButton(
                                                onClick = { cameraViewModel.reloadCameras() },
                                                enabled = !cameraUiState.isReloadingCameras,
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                if (cameraUiState.isReloadingCameras) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = "Scan Camera Sensors",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else if (isUpdatesScreen && updatesViewModel != null) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(40.dp)
                                    ) {
                                        IconButton(
                                            onClick = { updatesViewModel.checkForUpdates() },
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Check for Updates",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(19.dp)
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
                        if (isUsersScreen || isPermissionsScreen || isAuditLogScreen) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                adminDestinations.forEach { adminDest ->
                                    val selected = currentDestination?.route == adminDest.route
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 38.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                        },
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                        ),
                                        onClick = {
                                            if (!selected) {
                                                navController.navigate(adminDest.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp)
                                        ) {
                                            Text(
                                                text = adminDest.title,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                                ),
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                            thickness = 1.dp
                        )
                    }
                }
            },
            bottomBar = {
                if (!isMap && !isVisitor && currentDestination?.route != Destination.Camera.route) {
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
                    onLogout = onLogout,
                    onOpenDrawer = {
                        scope.launch {
                            drawerState.open()
                        }
                    }
                )

                if (isVisitor) {
                    com.livetracker.controller.ui.components.VisitorDemoBanner(
                        onRefreshAccess = onRefreshAccess,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = if (isMap) 76.dp else innerPadding.calculateTopPadding())
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
                            top = if (!isMap) innerPadding.calculateTopPadding() + 8.dp else 76.dp,
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
