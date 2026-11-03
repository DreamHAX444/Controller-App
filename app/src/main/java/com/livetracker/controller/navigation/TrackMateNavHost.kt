package com.livetracker.controller.navigation

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.ui.components.AccessRestrictedScreen
import com.livetracker.controller.ui.screens.diagnostics.DiagnosticsScreen
import com.livetracker.controller.ui.screens.diagnostics.DiagnosticsViewModel
import com.livetracker.controller.ui.screens.alerts.AlertsScreen
import com.livetracker.controller.ui.screens.alerts.AlertsViewModel
import com.livetracker.controller.ui.screens.devices.DevicesScreen
import com.livetracker.controller.ui.screens.devices.DevicesViewModel
import com.livetracker.controller.ui.screens.camera.CameraScreen
import com.livetracker.controller.ui.screens.camera.CameraViewModel
import com.livetracker.controller.ui.screens.map.MapScreen
import com.livetracker.controller.ui.screens.map.MapViewModel
import com.livetracker.controller.ui.screens.settings.SettingsScreen
import com.livetracker.controller.ui.screens.settings.SettingsViewModel
import com.livetracker.controller.ui.screens.updates.UpdatesScreen
import com.livetracker.controller.ui.screens.updates.UpdatesViewModel
import com.livetracker.controller.ui.screens.users.UsersScreen
import com.livetracker.controller.ui.screens.users.UsersViewModel
import com.livetracker.controller.ui.screens.audio.AudioScreen
import com.livetracker.controller.ui.screens.audio.AudioViewModel
import com.livetracker.controller.ui.screens.screen.ScreenCaptureScreen
import com.livetracker.controller.ui.screens.screen.ScreenCaptureViewModel
import com.livetracker.controller.ui.screens.files.FilesScreen
import com.livetracker.controller.ui.screens.files.FilesViewModel
import com.livetracker.controller.ui.screens.permissions.PermissionsScreen
import com.livetracker.controller.ui.screens.audit.AuditLogScreen

/**
 * Master Navigation Host for Trace Controller.
 * Features unified, tactical page transition animations conforming to UI/UX Pro Max motion principles:
 * - Entering Map: Spatial zoom-in (0.95 -> 1.0) with fluid crossfade (tactical overview reveal)
 * - Exiting Map: Graceful dissolve with subtle shrink
 * - Entering Standard Screens from Map: Elegant upward slide & gentle scale-in between Scaffold bars
 * - Screen-to-Screen: Tactile directional horizontal crossfade
 */
@Composable
fun TrackMateNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModelFactory: ViewModelProvider.Factory? = null,
    onOpenDrawer: () -> Unit = {},
    currentUser: com.livetracker.controller.core.auth.domain.User? = null
) {
    NavHost(
        navController = navController,
        startDestination = Destination.Map.route,
        modifier = modifier,
        enterTransition = {
            val targetRoute = targetState.destination.route
            val initialRoute = initialState.destination.route
            if (targetRoute == Destination.Map.route) {
                // Entering Map: Fluid spatial zoom-in reveal
                fadeIn(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                ) + scaleIn(
                    initialScale = 0.95f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                )
            } else if (initialRoute == Destination.Map.route) {
                // Entering standard page from Map: smooth rise & fade
                fadeIn(
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                ) + slideInVertically(
                    initialOffsetY = { (it * 0.08f).toInt() },
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                ) + scaleIn(
                    initialScale = 0.98f,
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )
            } else {
                // Between standard screens: horizontal slide + fade
                fadeIn(
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                ) + slideInHorizontally(
                    initialOffsetX = { it / 14 },
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                )
            }
        },
        exitTransition = {
            val targetRoute = targetState.destination.route
            val initialRoute = initialState.destination.route
            if (initialRoute == Destination.Map.route) {
                // Exiting Map: soft dissolve + subtle shrink
                fadeOut(
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                ) + scaleOut(
                    targetScale = 0.96f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                )
            } else if (targetRoute == Destination.Map.route) {
                // Exiting standard page to Map: soft downward dissolve
                fadeOut(
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                ) + slideOutVertically(
                    targetOffsetY = { (it * 0.06f).toInt() },
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                )
            } else {
                // Between standard screens: horizontal exit slide + fade
                fadeOut(
                    animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
                ) + slideOutHorizontally(
                    targetOffsetX = { -it / 14 },
                    animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
                )
            }
        },
        popEnterTransition = {
            val targetRoute = targetState.destination.route
            val initialRoute = initialState.destination.route
            if (targetRoute == Destination.Map.route) {
                fadeIn(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                ) + scaleIn(
                    initialScale = 0.95f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                )
            } else if (initialRoute == Destination.Map.route) {
                fadeIn(
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                ) + slideInVertically(
                    initialOffsetY = { (it * 0.08f).toInt() },
                    animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                )
            } else {
                fadeIn(
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                ) + slideInHorizontally(
                    initialOffsetX = { -it / 14 },
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                )
            }
        },
        popExitTransition = {
            val targetRoute = targetState.destination.route
            val initialRoute = initialState.destination.route
            if (initialRoute == Destination.Map.route) {
                fadeOut(
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                ) + scaleOut(
                    targetScale = 0.96f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                )
            } else if (targetRoute == Destination.Map.route) {
                fadeOut(
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                ) + slideOutVertically(
                    targetOffsetY = { (it * 0.06f).toInt() },
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                )
            } else {
                fadeOut(
                    animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
                ) + slideOutHorizontally(
                    targetOffsetX = { it / 14 },
                    animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
                )
            }
        }
    ) {
        // Primary
        composable(Destination.Map.route) {
            val mapViewModel: MapViewModel = if (viewModelFactory != null) {
                viewModel(factory = viewModelFactory)
            } else {
                viewModel()
            }
            MapScreen(
                viewModel = mapViewModel,
                modifier = Modifier.fillMaxSize(),
                onOpenDrawer = onOpenDrawer
            )
        }
        composable(Destination.Devices.route) {
            if (currentUser?.role == Role.VISITOR) {
                AccessRestrictedScreen(
                    title = "Fleet Access Restricted",
                    message = "Device management is restricted to registered members. You are currently browsing in Visitor Sandbox mode.",
                    onBack = { navController.navigate(Destination.Map.route) { launchSingleTop = true } },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val context = LocalContext.current
                val owner = context as? androidx.lifecycle.ViewModelStoreOwner
                val devicesViewModel: DevicesViewModel = if (owner != null) {
                    remember(owner, viewModelFactory) {
                        if (viewModelFactory != null) {
                            ViewModelProvider(owner, viewModelFactory)[DevicesViewModel::class.java]
                        } else {
                            ViewModelProvider(owner)[DevicesViewModel::class.java]
                        }
                    }
                } else if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                DevicesScreen(
                    viewModel = devicesViewModel,
                    modifier = Modifier.padding(contentPadding),
                    onNavigateToMap = {
                        navController.navigate(Destination.Map.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToCamera = {
                        navController.navigate(Destination.Camera.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToAudio = {
                        navController.navigate(Destination.Audio.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToScreen = {
                        navController.navigate(Destination.ScreenCapture.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToFiles = {
                        navController.navigate(Destination.Files.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
        composable(Destination.Alerts.route) {
            if (currentUser?.role == Role.VISITOR) {
                AccessRestrictedScreen(
                    title = "Alerts Access Restricted",
                    message = "Operational security alerts are restricted to registered operators.",
                    onBack = { navController.navigate(Destination.Map.route) { launchSingleTop = true } },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val context = LocalContext.current
                val owner = context as? androidx.lifecycle.ViewModelStoreOwner
                val alertsViewModel: AlertsViewModel = if (owner != null) {
                    remember(owner, viewModelFactory) {
                        if (viewModelFactory != null) {
                            ViewModelProvider(owner, viewModelFactory)[AlertsViewModel::class.java]
                        } else {
                            ViewModelProvider(owner)[AlertsViewModel::class.java]
                        }
                    }
                } else if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                AlertsScreen(
                    viewModel = alertsViewModel,
                    modifier = Modifier.padding(contentPadding)
                )
            }
        }
        composable(Destination.Diagnostics.route) {
            if (currentUser?.role != Role.OWNER && currentUser?.role != Role.ADMIN) {
                AccessRestrictedScreen(
                    title = "Diagnostics Restricted",
                    message = "Hardware and system health diagnostics are restricted to Fleet Administrators and Owners.",
                    onBack = { navController.navigate(Destination.Map.route) { launchSingleTop = true } },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val context = LocalContext.current
                val owner = context as? androidx.lifecycle.ViewModelStoreOwner
                val diagnosticsViewModel: DiagnosticsViewModel = if (owner != null) {
                    remember(owner, viewModelFactory) {
                        if (viewModelFactory != null) {
                            ViewModelProvider(owner, viewModelFactory)[DiagnosticsViewModel::class.java]
                        } else {
                            ViewModelProvider(owner)[DiagnosticsViewModel::class.java]
                        }
                    }
                } else if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                DiagnosticsScreen(
                    viewModel = diagnosticsViewModel,
                    modifier = Modifier.padding(contentPadding)
                )
            }
        }

        // Services
        composable(Destination.Camera.route) {
            val allowedServices = currentUser?.devicePermissions?.flatMap { it.allowedServices }?.toSet().orEmpty()
            val isAllowed = currentUser?.role == Role.OWNER || currentUser?.role == Role.ADMIN ||
                (currentUser?.role == Role.USER && ServicePermission.CAMERA in allowedServices)

            if (!isAllowed) {
                AccessRestrictedScreen(
                    title = "Camera Access Restricted",
                    message = if (currentUser?.role == Role.VISITOR) "Visitors cannot access camera streams." else "You do not have Camera permissions for any assigned device. Contact an administrator to request access.",
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val cameraViewModel: CameraViewModel = if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                CameraScreen(
                    viewModel = cameraViewModel,
                    modifier = Modifier.padding(contentPadding),
                    onBack = { navController.popBackStack() }
                )
            }
        }
        composable(Destination.Audio.route) {
            val allowedServices = currentUser?.devicePermissions?.flatMap { it.allowedServices }?.toSet().orEmpty()
            val isAllowed = currentUser?.role == Role.OWNER || currentUser?.role == Role.ADMIN ||
                (currentUser?.role == Role.USER && (ServicePermission.MICROPHONE_AUDIO in allowedServices || ServicePermission.DEVICE_AUDIO in allowedServices))

            if (!isAllowed) {
                AccessRestrictedScreen(
                    title = "Audio Access Restricted",
                    message = if (currentUser?.role == Role.VISITOR) "Visitors cannot access audio monitoring." else "You do not have Audio permissions for any assigned device. Contact an administrator to request access.",
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val audioViewModel: AudioViewModel = if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                AudioScreen(
                    viewModel = audioViewModel,
                    modifier = Modifier.padding(contentPadding),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
        composable(Destination.ScreenCapture.route) {
            val allowedServices = currentUser?.devicePermissions?.flatMap { it.allowedServices }?.toSet().orEmpty()
            val isAllowed = currentUser?.role == Role.OWNER || currentUser?.role == Role.ADMIN ||
                (currentUser?.role == Role.USER && ServicePermission.SCREEN_CAPTURE in allowedServices)

            if (!isAllowed) {
                AccessRestrictedScreen(
                    title = "Screen Capture Restricted",
                    message = if (currentUser?.role == Role.VISITOR) "Visitors cannot access remote screen capture." else "You do not have Screen Capture permissions for any assigned device. Contact an administrator to request access.",
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val screenCaptureViewModel: ScreenCaptureViewModel = if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                ScreenCaptureScreen(
                    viewModel = screenCaptureViewModel,
                    modifier = Modifier.padding(contentPadding),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
        composable(Destination.Files.route) {
            val allowedServices = currentUser?.devicePermissions?.flatMap { it.allowedServices }?.toSet().orEmpty()
            val isAllowed = currentUser?.role == Role.OWNER || currentUser?.role == Role.ADMIN ||
                (currentUser?.role == Role.USER && (ServicePermission.FILE_READ in allowedServices || ServicePermission.FILE_TRANSFER in allowedServices))

            if (!isAllowed) {
                AccessRestrictedScreen(
                    title = "File Transfer Restricted",
                    message = if (currentUser?.role == Role.VISITOR) "Visitors cannot access device files." else "You do not have File management permissions for any assigned device. Contact an administrator to request access.",
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val filesViewModel: FilesViewModel = if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                FilesScreen(
                    viewModel = filesViewModel,
                    modifier = Modifier.padding(contentPadding),
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // Administration
        composable(Destination.Users.route) {
            if (currentUser?.role != Role.OWNER && currentUser?.role != Role.ADMIN) {
                AccessRestrictedScreen(
                    title = "Administration Restricted",
                    message = "User administration and access control are restricted to Fleet Administrators and Owners.",
                    onBack = { navController.navigate(Destination.Map.route) { launchSingleTop = true } },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val usersViewModel: UsersViewModel = if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                UsersScreen(
                    viewModel = usersViewModel,
                    modifier = Modifier.padding(contentPadding)
                )
            }
        }
        composable(Destination.Permissions.route) {
            if (currentUser?.role != Role.OWNER && currentUser?.role != Role.ADMIN) {
                AccessRestrictedScreen(
                    title = "Permissions Restricted",
                    message = "Permission configuration is restricted to Fleet Administrators and Owners.",
                    onBack = { navController.navigate(Destination.Map.route) { launchSingleTop = true } },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val usersViewModel: UsersViewModel = if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                PermissionsScreen(
                    viewModel = usersViewModel,
                    modifier = Modifier.padding(contentPadding)
                )
            }
        }
        composable(Destination.AuditLog.route) {
            if (currentUser?.role != Role.OWNER && currentUser?.role != Role.ADMIN) {
                AccessRestrictedScreen(
                    title = "Audit Log Restricted",
                    message = "System audit logs are restricted to Fleet Administrators and Owners.",
                    onBack = { navController.navigate(Destination.Map.route) { launchSingleTop = true } },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                AuditLogScreen(
                    modifier = Modifier.padding(contentPadding)
                )
            }
        }

        // System
        composable(Destination.Settings.route) {
            val settingsViewModel: SettingsViewModel = if (viewModelFactory != null) {
                viewModel(factory = viewModelFactory)
            } else {
                viewModel()
            }
            SettingsScreen(
                viewModel = settingsViewModel,
                currentUser = currentUser,
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.Updates.route) {
            if (currentUser?.role == Role.VISITOR) {
                AccessRestrictedScreen(
                    title = "Updates Restricted",
                    message = "System updates are disabled in Visitor Sandbox mode.",
                    onBack = { navController.navigate(Destination.Map.route) { launchSingleTop = true } },
                    modifier = Modifier.padding(contentPadding)
                )
            } else {
                val updatesViewModel: UpdatesViewModel = if (viewModelFactory != null) {
                    viewModel(factory = viewModelFactory)
                } else {
                    viewModel()
                }
                UpdatesScreen(
                    viewModel = updatesViewModel,
                    modifier = Modifier.padding(contentPadding)
                )
            }
        }
    }
}
