package com.livetracker.controller.ui.screens.map.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
 * Minimalist, Sophisticated, and Premium Live Telemetry Card.
 * Designed strictly around UI/UX Pro Max and Ponytail guidelines:
 * - Pure telemetry intelligence: battery, velocity, precision, link, and coordinates.
 * - Zero empty/dead action states; RBAC-agnostic and clean.
 * - 1-tap copy coordinates to clipboard with feedback badge.
 * - Tactile spring drag-to-dismiss and double-tap zoom gestures.
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
            delay(2200L)
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
            .padding(horizontal = 16.dp)
            .pointerInput(marker.deviceId) {
                detectVerticalDragGestures(
                    onDragStart = { verticalDragDelta = 0f },
                    onDragEnd = {
                        if (verticalDragDelta > 64f) {
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
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp)
        ) {
            // Top Grab Handle / Pager Dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (totalDeviceCount > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (i in 0 until totalDeviceCount) {
                            val isCurrent = i == deviceIndex
                            Box(
                                modifier = Modifier
                                    .height(4.dp)
                                    .width(if (isCurrent) 16.dp else 5.dp)
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
                            .width(32.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f))
                    )
                }
            }

            // Header Row: Status Beacon + Friendly Name + Status Pill + Recenter & Dismiss
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
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                text = friendlyName,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.5.sp,
                                    letterSpacing = 0.2.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = statusColor.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.40f))
                            ) {
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.5.sp
                                    ),
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                )
                            }
                        }
                        Text(
                            text = "Updated $relativeTimeText",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (totalDeviceCount > 1) {
                        IconButton(
                            onClick = { onPreviousDevice?.invoke() },
                            enabled = onPreviousDevice != null,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous Tracker",
                                tint = if (onPreviousDevice != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = { onNextDevice?.invoke() },
                            enabled = onNextDevice != null,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next Tracker",
                                tint = if (onNextDevice != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Center Camera Button
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onCenterOnDevice() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Center on Map",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    // Dismiss Card Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Telemetry Card",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
            )

            // Telemetry Bento Metrics Row (Battery, Velocity, Precision, Link)
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
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                TelemetryMetricPill(
                    label = "BATTERY",
                    value = batteryText,
                    valueColor = batteryColor,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "VELOCITY",
                    value = speedText,
                    valueColor = if (speedKmh >= 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "PRECISION",
                    value = accuracyText,
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TelemetryMetricPill(
                    label = "LINK",
                    value = networkText,
                    valueColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Footer: Monospace Coordinates (1-Tap Copy) + Gesture Hint
            val formattedCoords = String.format(Locale.US, "%.5f, %.5f", marker.latitude, marker.longitude)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (copiedCoords) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val clip = android.content.ClipData.newPlainText("Coordinates", formattedCoords)
                            clipboardManager?.setPrimaryClip(clip)
                            copiedCoords = true
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formattedCoords,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                letterSpacing = 0.3.sp
                            ),
                            color = if (copiedCoords) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AnimatedVisibility(
                            visible = copiedCoords,
                            enter = fadeIn(tween(150)),
                            exit = fadeOut(tween(150))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Copied",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "Copied",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
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
                        "${deviceIndex + 1}/$totalDeviceCount • Swipe ⇄ or ↓ close"
                    } else {
                        "Double-tap zoom • Swipe ↓ close"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricPill(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp
                ),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
