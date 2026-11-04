package com.livetracker.controller.ui.screens.map.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceSummary
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.ui.theme.StatusActive
import com.livetracker.controller.ui.theme.StatusIdle
import com.livetracker.controller.ui.theme.StatusOffline
import com.livetracker.controller.ui.util.DeviceNameFormatter

@Composable
fun MapTopBar(
    deviceSelection: DeviceSelection,
    deviceSummaries: List<TrackerDeviceSummary>,
    geofenceCount: Int,
    onOpenDrawer: () -> Unit,
    onOpenDeviceSelector: () -> Unit,
    onOpenGeofences: () -> Unit,
    topPadding: Dp = 12.dp,
    startPadding: Dp = 16.dp,
    endPadding: Dp = 16.dp,
    focusedDeviceName: String? = null,
    onSwipeTracker: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val activeDevices = remember(deviceSelection, deviceSummaries) {
        when (deviceSelection) {
            is DeviceSelection.All -> deviceSummaries
            is DeviceSelection.Single -> deviceSummaries.filter { it.device.deviceId == deviceSelection.deviceId }
            is DeviceSelection.Multiple -> deviceSummaries.filter { it.device.deviceId in deviceSelection.deviceIds }
            is DeviceSelection.Empty -> emptyList()
        }
    }

    val liveCount = remember(activeDevices) {
        activeDevices.count { summary ->
            summary.device.connectionState == ConnectionState.CONNECTED
        }
    }

    val statusDotColor = when {
        activeDevices.isEmpty() -> StatusOffline
        liveCount > 0 -> StatusActive
        else -> StatusIdle
    }

    val selectorLabel = remember(deviceSelection, deviceSummaries) {
        when (deviceSelection) {
            is DeviceSelection.All -> "All Trackers (${deviceSummaries.size})"
            is DeviceSelection.Single -> {
                val matched = deviceSummaries.find { it.device.deviceId == deviceSelection.deviceId }
                if (matched != null) {
                    DeviceNameFormatter.formatFriendlyName(matched.device.displayName, matched.device.deviceId.value)
                } else {
                    DeviceNameFormatter.formatFriendlyName(deviceSelection.deviceId.value, deviceSelection.deviceId.value)
                }
            }
            is DeviceSelection.Multiple -> "${deviceSelection.deviceIds.size} Selected"
            is DeviceSelection.Empty -> "No Trackers Selected"
        }
    }

    val subtitleLabel = remember(deviceSelection, focusedDeviceName, activeDevices.size, liveCount) {
        if (!focusedDeviceName.isNullOrBlank() && deviceSelection !is DeviceSelection.Single) {
            "Focused: $focusedDeviceName"
        } else if (activeDevices.size > 1) {
            "$liveCount Live • Swipe ⇄ to switch"
        } else if (liveCount > 0) {
            "Live Telemetry Active"
        } else {
            "Tap to filter fleet"
        }
    }

    val glassSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    val glassBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    var cumulativeDrag by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = topPadding,
                start = startPadding,
                end = endPadding
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Floating Menu Button
        Surface(
            modifier = Modifier.size(46.dp),
            shape = RoundedCornerShape(14.dp),
            color = glassSurface,
            border = BorderStroke(1.dp, glassBorder),
            shadowElevation = 8.dp
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenDrawer() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open Menu",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Center: Dynamic Island Tracker Selector Capsule (Tap to open sheet, Swipe ⇄ to cycle trackers)
        Surface(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .pointerInput(activeDevices.size) {
                    if (onSwipeTracker != null && activeDevices.size > 1) {
                        detectHorizontalDragGestures(
                            onDragStart = { cumulativeDrag = 0f },
                            onDragEnd = {
                                if (cumulativeDrag > 52f) {
                                    onSwipeTracker(-1)
                                } else if (cumulativeDrag < -52f) {
                                    onSwipeTracker(1)
                                }
                                cumulativeDrag = 0f
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                cumulativeDrag += dragAmount
                            }
                        )
                    }
                },
            shape = RoundedCornerShape(16.dp),
            color = glassSurface,
            border = BorderStroke(1.dp, glassBorder),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onOpenDeviceSelector() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = selectorLabel,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitleLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Select Tracker",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Right: Floating Geofence Manager Button with active count badge
        Surface(
            modifier = Modifier.size(46.dp),
            shape = RoundedCornerShape(14.dp),
            color = if (geofenceCount > 0) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                glassSurface
            },
            border = BorderStroke(
                width = 1.dp,
                color = if (geofenceCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else glassBorder
            ),
            shadowElevation = 8.dp
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenGeofences() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Manage Geofences",
                    tint = if (geofenceCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp)
                )
                if (geofenceCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 5.dp, end = 5.dp)
                            .size(15.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = geofenceCount.coerceAtMost(9).toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
