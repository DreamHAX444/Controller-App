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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.livetracker.controller.ui.screens.PlaceholderScreen
import com.livetracker.controller.ui.screens.alerts.AlertsScreen
import com.livetracker.controller.ui.screens.alerts.AlertsViewModel
import com.livetracker.controller.ui.screens.map.MapScreen
import com.livetracker.controller.ui.screens.map.MapViewModel
import com.livetracker.controller.ui.screens.settings.SettingsScreen
import com.livetracker.controller.ui.screens.settings.SettingsViewModel
import com.livetracker.controller.ui.screens.updates.UpdatesScreen
import com.livetracker.controller.ui.screens.updates.UpdatesViewModel

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
    onOpenDrawer: () -> Unit = {}
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
            PlaceholderScreen(
                title = Destination.Devices.title,
                description = "List of all registered Tracker devices and their status.",
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.Alerts.route) {
            val alertsViewModel: AlertsViewModel = if (viewModelFactory != null) {
                viewModel(factory = viewModelFactory)
            } else {
                viewModel()
            }
            AlertsScreen(
                viewModel = alertsViewModel,
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.Diagnostics.route) {
            PlaceholderScreen(
                title = Destination.Diagnostics.title,
                description = "Network and hardware diagnostic information.",
                modifier = Modifier.padding(contentPadding)
            )
        }

        // Services
        composable(Destination.Camera.route) {
            PlaceholderScreen(
                title = Destination.Camera.title,
                description = "Remote camera access for selected device.",
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.Audio.route) {
            PlaceholderScreen(
                title = Destination.Audio.title,
                description = "Remote microphone access for selected device.",
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.ScreenCapture.route) {
            PlaceholderScreen(
                title = Destination.ScreenCapture.title,
                description = "Remote screen capture for selected device.",
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.Files.route) {
            PlaceholderScreen(
                title = Destination.Files.title,
                description = "Remote file access and transfer.",
                modifier = Modifier.padding(contentPadding)
            )
        }

        // Administration
        composable(Destination.Users.route) {
            PlaceholderScreen(
                title = Destination.Users.title,
                description = "Manage controller application users.",
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.Permissions.route) {
            PlaceholderScreen(
                title = Destination.Permissions.title,
                description = "Configure user roles and device permissions.",
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.AuditLog.route) {
            PlaceholderScreen(
                title = Destination.AuditLog.title,
                description = "Review command execution and access logs.",
                modifier = Modifier.padding(contentPadding)
            )
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
                modifier = Modifier.padding(contentPadding)
            )
        }
        composable(Destination.Updates.route) {
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
