package com.livetracker.controller.ui.screens.map.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.map.domain.MapMarker
import com.livetracker.controller.ui.theme.StatusActive
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusIdle
import com.livetracker.controller.ui.theme.StatusOffline
import com.livetracker.controller.ui.theme.StatusWarning
import com.livetracker.controller.ui.util.DeviceNameFormatter
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Ultra-Sleek Aerospace Live Telemetry HUD.
 * Redesigned with UI/UX Pro Max Minimalism guidelines:
 * - Slim ~118dp height profile giving >90% unobstructed screen canvas to the map.
 * - Translucent frosted micro-glass (alpha 0.88) with 0.85dp hairline border.
 * - Live status beacon with high-contrast state badge (LIVE / IDLE / OFFLINE).
 * - Fast tactical actions: Previous, Next, Recenter on Target, Dismiss.
 * - Single-row 4-pill Bento Telemetry Strip (Battery, Velocity, Precision, Link).
 * - 1-Tap Monospace Coordinates copy with animated checkmark feedback.
 * - Vertical drag-down to dismiss + double-tap deep zoom gestures.
 */
@Composable
fun DeviceTelemetryCard(
    marker: MapMarker,
    deviceIndex: Int = 0,
    totalDeviceCount: Int = 1,
    onDismiss: () -> Unit,
    onCenterOnDevice: () -> Unit,
    onDoubleTapZoom: (() -> Unit)? = null,
    onPreviousDevice: (() -> Unit)? = null,
    onNextDevice: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboardManager = remember(context) {
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }
    var copiedCoords by remember { mutableStateOf(false) }
    LaunchedEffect(copiedCoords) {
        if (copiedCoords) {
            delay(2000L)
            copiedCoords = false
        }
    }

    val effectiveTimestamp = marker.timestamp
    val ageMs = (nowMillis - effectiveTimestamp).coerceAtLeast(0L)
    val isLive = marker.connectionState == ConnectionState.CONNECTED && ageMs < 60_000L
    val isIdle = !isLive && (marker.connectionState == ConnectionState.CONNECTED || ageMs < 600_000L)

    val statusColor by animateColorAsState(
        targetValue = when {
            isLive -> StatusActive
            isIdle -> StatusIdle
            else -> StatusOffline
        },
        animationSpec = tween(250),
        label = "statusColor"
    )

    val statusLabel = when {
        isLive -> "LIVE"
        isIdle -> "IDLE"
        else -> "OFFLINE"
    }

    val relativeTimeText = remember(ageMs, effectiveTimestamp) {
        if (effectiveTimestamp <= 0L) {
            "Awaiting sync"
        } else {
            val seconds = ageMs / 1000L
            when {
                seconds < 5L -> "Just now"
                seconds < 60L -> "${seconds}s ago"
                seconds < 3600L -> "${seconds / 60L}m ago"
                seconds < 86400L -> "${seconds / 3600L}h ago"
                else -> "${seconds / 86400L}d ago"
            }
        }
    }

    val friendlyName = remember(marker.displayName, marker.deviceId) {
        DeviceNameFormatter.formatFriendlyName(marker.displayName, marker.deviceId.value)
    }

    var verticalDragDelta by remember { mutableFloatStateOf(0f) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .pointerInput(marker.deviceId) {
                detectVerticalDragGestures(
                    onDragStart = { verticalDragDelta = 0f },
                    onDragEnd = {
                        if (verticalDragDelta > 50f) {
                            onDismiss()
                        }
                        verticalDragDelta = 0f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (dragAmount > 0f) {
                            verticalDragDelta += dragAmount
                        }
                    }
                )
            }
            .pointerInput(marker.deviceId) {
                detectTapGestures(
                    onDoubleTap = {
                        onDoubleTapZoom?.invoke() ?: onCenterOnDevice()
                    }
                )
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        ),
        border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            // Micro Grab Handle / Multi-device indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (totalDeviceCount > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (i in 0 until totalDeviceCount) {
                            val isCurrent = i == deviceIndex
                            Box(
                                modifier = Modifier
                                    .height(3.dp)
                                    .width(if (isCurrent) 14.dp else 4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    )
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                    )
                }
            }

            // Header Row: Beacon + Friendly Name + Status Pill + Relative Time + Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = friendlyName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    letterSpacing = 0.15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Surface(
                                shape = RoundedCornerShape(5.dp),
                                color = statusColor.copy(alpha = 0.14f),
                                border = BorderStroke(0.75.dp, statusColor.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    ),
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 4.5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "Updated $relativeTimeText",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Quick Action Controls (Previous, Next, Recenter, Dismiss)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (totalDeviceCount > 1) {
                        MicroIconButton(
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous Tracker",
                            enabled = onPreviousDevice != null,
                            onClick = { onPreviousDevice?.invoke() }
                        )
                        MicroIconButton(
                            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next Tracker",
                            enabled = onNextDevice != null,
                            onClick = { onNextDevice?.invoke() }
                        )
                    }

                    // Recenter on Target Button
                    MicroIconButton(
                        icon = Icons.Default.MyLocation,
                        contentDescription = "Center on Map",
                        tint = MaterialTheme.colorScheme.primary,
                        surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        onClick = onCenterOnDevice
                    )

                    // Dismiss Card Button
                    MicroIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = "Close Telemetry Card",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        onClick = onDismiss
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f),
                thickness = 0.6.dp
            )

            // Telemetry 1-Row Bento Strip (Battery, Velocity, Precision, Link)
            val batteryVal = marker.batteryLevel
            val batteryText = if (batteryVal != null && batteryVal in 0..100) "$batteryVal%" else "Standby"
            val batteryColor = when {
                batteryVal == null -> MaterialTheme.colorScheme.onSurface
                batteryVal <= 15 -> StatusError
                batteryVal <= 30 -> StatusWarning
                else -> StatusActive
            }

            val speedMps = marker.speed ?: 0f
            val speedKmh = (speedMps * 3.6f).coerceAtLeast(0f)
            val speedText = if (speedKmh >= 0.5f) {
                String.format(Locale.US, "%.1f km/h", speedKmh)
            } else {
                "Stationary"
            }

            val accMeters = marker.accuracy
            val accuracyText = if (accMeters != null && accMeters > 0f) {
                String.format(Locale.US, "±%.0f m", accMeters)
            } else {
                "Calibrating"
            }

            val networkText = marker.networkState?.takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("unknown", ignoreCase = true)
            } ?: if (isLive) "Online" else "Standby"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MicroTelemetryPill(
                    label = "BATTERY",
                    value = batteryText,
                    valueColor = batteryColor,
                    modifier = Modifier.weight(1f)
                )
                MicroTelemetryPill(
                    label = "VELOCITY",
                    value = speedText,
                    valueColor = if (speedKmh >= 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                MicroTelemetryPill(
                    label = "PRECISION",
                    value = accuracyText,
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                MicroTelemetryPill(
                    label = "LINK",
                    value = networkText,
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(5.dp))

            // Footer: Monospace Coordinates (1-Tap Copy) + Gestures Hint
            val formattedCoords = String.format(Locale.US, "%.5f, %.5f", marker.latitude, marker.longitude)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (copiedCoords) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable {
                            val clip = android.content.ClipData.newPlainText("Coordinates", formattedCoords)
                            clipboardManager?.setPrimaryClip(clip)
                            copiedCoords = true
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = formattedCoords,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                letterSpacing = 0.25.sp
                            ),
                            color = if (copiedCoords) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AnimatedVisibility(
                            visible = copiedCoords,
                            enter = fadeIn(tween(120)),
                            exit = fadeOut(tween(120))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Copied",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "Copied",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Text(
                    text = if (totalDeviceCount > 1) {
                        "${deviceIndex + 1}/$totalDeviceCount • Swipe ⇄"
                    } else {
                        "Double-tap zoom • ↓ close"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MicroTelemetryPill(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(0.65.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MicroIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    surfaceColor: Color = Color.Transparent,
    borderColor: Color = Color.Transparent
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.88f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "microBtnScale"
    )

    Surface(
        modifier = modifier
            .size(28.dp)
            .scale(scale),
        shape = RoundedCornerShape(8.dp),
        color = surfaceColor,
        border = if (borderColor != Color.Transparent) BorderStroke(0.75.dp, borderColor) else null
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
