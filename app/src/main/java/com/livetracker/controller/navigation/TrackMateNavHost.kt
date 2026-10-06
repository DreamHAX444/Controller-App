package com.livetracker.controller.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.livetracker.controller.ui.screens.PlaceholderScreen

@Composable
fun TrackMateNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Destination.Map.route,
        modifier = modifier
    ) {
        // Primary
        composable(Destination.Map.route) {
            PlaceholderScreen(
                title = Destination.Map.title,
                description = "Primary operational map and device tracking view."
            )
        }
        composable(Destination.Devices.route) {
            PlaceholderScreen(
                title = Destination.Devices.title,
                description = "List of all registered Tracker devices and their status."
            )
        }
        composable(Destination.Alerts.route) {
            PlaceholderScreen(
                title = Destination.Alerts.title,
                description = "System and geofence alerts."
            )
        }
        composable(Destination.Diagnostics.route) {
            PlaceholderScreen(
                title = Destination.Diagnostics.title,
                description = "Network and hardware diagnostic information."
            )
        }

        // Services
        composable(Destination.Camera.route) {
            PlaceholderScreen(
                title = Destination.Camera.title,
                description = "Remote camera access for selected device."
            )
        }
        composable(Destination.Audio.route) {
            PlaceholderScreen(
                title = Destination.Audio.title,
                description = "Remote microphone access for selected device."
            )
        }
        composable(Destination.ScreenCapture.route) {
            PlaceholderScreen(
                title = Destination.ScreenCapture.title,
                description = "Remote screen capture for selected device."
            )
        }
        composable(Destination.Files.route) {
            PlaceholderScreen(
                title = Destination.Files.title,
                description = "Remote file access and transfer."
            )
        }

        // Administration
        composable(Destination.Users.route) {
            PlaceholderScreen(
                title = Destination.Users.title,
                description = "Manage controller application users."
            )
        }
        composable(Destination.Permissions.route) {
            PlaceholderScreen(
                title = Destination.Permissions.title,
                description = "Configure user roles and device permissions."
            )
        }
        composable(Destination.AuditLog.route) {
            PlaceholderScreen(
                title = Destination.AuditLog.title,
                description = "Review command execution and access logs."
            )
        }

        // System
        composable(Destination.Settings.route) {
            PlaceholderScreen(
                title = Destination.Settings.title,
                description = "Controller application settings."
            )
        }
        composable(Destination.Updates.route) {
            PlaceholderScreen(
                title = Destination.Updates.title,
                description = "In-app update management."
            )
        }
    }
}
