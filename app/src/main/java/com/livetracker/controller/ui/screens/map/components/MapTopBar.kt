package com.livetracker.controller.ui.screens.map.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceSummary
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusOffline

/**
 * Minimalist, floating Top Bar for the map dashboard.
 * Houses the drawer menu toggle, dynamic island device selector capsule, and geofence launcher.
 */
@Composable
fun MapTopBar(
    deviceSelection: DeviceSelection,
    deviceSummaries: List<TrackerDeviceSummary>,
    geofenceCount: Int,
    onOpenDrawer: () -> Unit,
    onOpenDeviceSelector: () -> Unit,
    onOpenGeofences: () -> Unit,
    topPadding: Dp,
    startPadding: Dp,
    endPadding: Dp,
    modifier: Modifier = Modifier
) {
    // Aggregate connection state determination
    val isAnyConnected = deviceSummaries.any { summary ->
        val isSelected = when (deviceSelection) {
            DeviceSelection.All -> true
            is DeviceSelection.Single -> summary.device.deviceId == deviceSelection.deviceId
            is DeviceSelection.Multiple -> deviceSelection.deviceIds.contains(summary.device.deviceId)
            DeviceSelection.Empty -> false
        }
        isSelected && summary.device.connectionState == ConnectionState.CONNECTED
    }

    val isAnyConnecting = deviceSummaries.any { summary ->
        val isSelected = when (deviceSelection) {
            DeviceSelection.All -> true
            is DeviceSelection.Single -> summary.device.deviceId == deviceSelection.deviceId
            is DeviceSelection.Multiple -> deviceSelection.deviceIds.contains(summary.device.deviceId)
            DeviceSelection.Empty -> false
        }
        isSelected && summary.device.connectionState == ConnectionState.CONNECTING
    }

    val statusDotColor = when {
        isAnyConnected -> StatusLive
        isAnyConnecting -> StatusConnecting
        else -> StatusOffline
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPadding, start = startPadding, end = endPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Menu Action Button
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            shadowElevation = 4.dp
        ) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open navigation menu",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 2. Center Dynamic Capsule Device Selector
        Surface(
            modifier = Modifier.widthIn(min = 140.dp, max = 220.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onOpenDeviceSelector)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Pulsating status dot isolated to drawing phase
                TopBarPulseDot(
                    color = statusDotColor,
                    isPulsing = isAnyConnected || isAnyConnecting
                )
                Spacer(modifier = Modifier.width(8.dp))

                val labelText = when (deviceSelection) {
                    DeviceSelection.All -> "All Trackers (${deviceSummaries.size})"
                    is DeviceSelection.Single -> {
                        val dev = deviceSummaries.find { it.device.deviceId == deviceSelection.deviceId }
                        dev?.device?.displayName ?: "1 Tracker"
                    }
                    is DeviceSelection.Multiple -> "${deviceSelection.deviceIds.size} Selected"
                    DeviceSelection.Empty -> "No Trackers"
                }

                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select tracker",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 3. Geofences Action Button
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            shadowElevation = 4.dp
        ) {
            IconButton(
                onClick = onOpenGeofences,
                modifier = Modifier.size(44.dp)
            ) {
                if (geofenceCount > 0) {
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text("$geofenceCount", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Manage geofences",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Manage geofences",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * Isolated live connectivity pulse dot.
 * Alpha is applied in the RenderThread drawing phase via graphicsLayer,
 * guaranteeing zero CPU recompositions in the parent Top Bar.
 */
@Composable
private fun TopBarPulseDot(
    color: Color,
    isPulsing: Boolean,
    modifier: Modifier = Modifier
) {
    if (isPulsing) {
        val infiniteTransition = rememberInfiniteTransition(label = "topBarPulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        Box(
            modifier = modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulseAlpha }
                .clip(CircleShape)
                .background(color)
        )
    } else {
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

