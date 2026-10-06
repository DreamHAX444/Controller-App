package com.livetracker.controller.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String, val title: String, val icon: ImageVector) {
    // Primary
    object Map : Destination("map", "Map", Icons.Default.Place)
    object Devices : Destination("devices", "Devices", Icons.Default.Phone)
    object Alerts : Destination("alerts", "Alerts", Icons.Default.Notifications)
    object Diagnostics : Destination("diagnostics", "Diagnostics", Icons.Default.Build)

    // Services
    object ServicesMenu : Destination("services", "Services", Icons.Default.PlayArrow)
    object Camera : Destination("services/camera", "Camera", Icons.Default.Face)
    object Audio : Destination("services/audio", "Audio", Icons.Default.Call)
    object ScreenCapture : Destination("services/screen", "Screen Capture", Icons.Default.Search)
    object Files : Destination("services/files", "Files", Icons.Default.List)

    // Administration
    object AdminMenu : Destination("admin", "Administration", Icons.Default.Lock)
    object Users : Destination("admin/users", "Users", Icons.Default.Person)
    object Permissions : Destination("admin/permissions", "Permissions", Icons.Default.Lock)
    object AuditLog : Destination("admin/audit", "Audit Log", Icons.Default.Info)

    // System
    object SettingsMenu : Destination("system", "System", Icons.Default.Settings)
    object Settings : Destination("system/settings", "Settings", Icons.Default.Settings)
    object Updates : Destination("system/updates", "Updates", Icons.Default.Refresh)
}

val primaryDestinations = listOf(
    Destination.Map,
    Destination.Devices,
    Destination.Alerts,
    Destination.Diagnostics
)

val serviceDestinations = listOf(
    Destination.Camera,
    Destination.Audio,
    Destination.ScreenCapture,
    Destination.Files
)

val adminDestinations = listOf(
    Destination.Users,
    Destination.Permissions,
    Destination.AuditLog
)

val systemDestinations = listOf(
    Destination.Settings,
    Destination.Updates
)
