package com.livetracker.controller.ui.screens.map.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
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

/**
 * Minimalist Aerospace Dynamic Island Top HUD.
 * Recreated with UI/UX Pro Max Minimalism guidelines:
 * - Ultra-compact 42dp vertical footprint maximizing visible map canvas.
 * - Frosted micro-glassmorphism (alpha 0.88) with 0.75dp hairline border.
 * - Live radar pulse beacon on active telemetry.
 * - Horizontal swipe on island to cycle trackers.
 * - Geofence perimeter launcher with active count badge.
 */
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
            "$liveCount Live • Swipe ⇄"
        } else if (liveCount > 0) {
            "Live Telemetry Active"
        } else {
            "Tap to select"
        }
    }

    val glassSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
    val glassBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
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
        // 1. Navigation Drawer Launcher Button
        TacticalIconButton(
            icon = Icons.Default.Menu,
            contentDescription = "Open Menu",
            onClick = onOpenDrawer,
            surfaceColor = glassSurface,
            borderColor = glassBorder
        )

        Spacer(modifier = Modifier.width(8.dp))

        // 2. Center Dynamic Island Tracker Capsule
        val islandInteractionSource = remember { MutableInteractionSource() }
        val isIslandPressed by islandInteractionSource.collectIsPressedAsState()
        val islandScale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isIslandPressed) 0.97f else 1.0f,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = 450f),
            label = "islandScale"
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .scale(islandScale)
                .pointerInput(activeDevices.size) {
                    if (onSwipeTracker != null && activeDevices.size > 1) {
                        detectHorizontalDragGestures(
                            onDragStart = { cumulativeDrag = 0f },
                            onDragEnd = {
                                if (cumulativeDrag > 48f) {
                                    onSwipeTracker(-1)
                                } else if (cumulativeDrag < -48f) {
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
            shape = RoundedCornerShape(21.dp),
            color = glassSurface,
            border = BorderStroke(0.85.dp, glassBorder),
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(21.dp))
                    .clickable(
                        interactionSource = islandInteractionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onOpenDeviceSelector
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Pulsing Status Dot
                    val infiniteTransition = rememberInfiniteTransition(label = "TopLivePulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1.0f,
                        targetValue = 1.7f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1300, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "topPulseScale"
                    )
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.65f,
                        targetValue = 0.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1300, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "topPulseAlpha"
                    )

                    Box(contentAlignment = Alignment.Center) {
                        if (liveCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(statusDotColor.copy(alpha = pulseAlpha))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                    }

                    Spacer(modifier = Modifier.width(9.dp))

                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = selectorLabel,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 0.2.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitleLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Select Tracker",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 3. Geofence Perimeter Manager Launcher Button
        TacticalIconButton(
            icon = Icons.Default.LocationOn,
            contentDescription = "Manage Geofences",
            onClick = onOpenGeofences,
            surfaceColor = if (geofenceCount > 0) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                glassSurface
            },
            borderColor = if (geofenceCount > 0) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
            } else {
                glassBorder
            },
            iconTint = if (geofenceCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            badgeCount = if (geofenceCount > 0) geofenceCount else null
        )
    }
}

@Composable
private fun TacticalIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    badgeCount: Int? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "btnScale"
    )

    Surface(
        modifier = modifier
            .size(42.dp)
            .scale(scale),
        shape = RoundedCornerShape(13.dp),
        color = surfaceColor,
        border = BorderStroke(0.85.dp, borderColor),
        shadowElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(13.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )

            if (badgeCount != null && badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = badgeCount.coerceAtMost(9).toString(),
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
