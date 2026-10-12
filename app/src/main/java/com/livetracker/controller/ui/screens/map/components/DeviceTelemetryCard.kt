package com.livetracker.controller.ui.screens.map.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.location.domain.LocationState
import com.livetracker.controller.core.tracker.map.domain.MapMarker
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusOffline
import java.util.Locale

/**
 * Ultra-sophisticated, minimal live telemetry card for the focused device.
 * Displays real-time battery gauge, movement velocity, compass bearing, accuracy,
 * and 1-tap copyable coordinates.
 * Supports horizontal swipe switching between devices with paging indicators.
 */
@Composable
fun DeviceTelemetryCard(
    marker: MapMarker,
    onDismiss: () -> Unit,
    onCenterOnDevice: () -> Unit,
    modifier: Modifier = Modifier,
    deviceIndex: Int = 0,
    totalDeviceCount: Int = 1,
    onPreviousDevice: (() -> Unit)? = null,
    onNextDevice: (() -> Unit)? = null
) {
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Subtle top drag handle for swipe affordance (when multiple devices exist)
            if (totalDeviceCount > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
            }

            // Header: Device Identity + Live Badge + Paging Indicator + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Pulsating status dot
                    val statusColor = when (marker.connectionState) {
                        ConnectionState.CONNECTED -> StatusLive
                        ConnectionState.CONNECTING,
                        ConnectionState.SIGNALING,
                        ConnectionState.WEBRTC_CONNECTING,
                        ConnectionState.DATA_CHANNEL_OPENING,
                        ConnectionState.RECONNECTING,
                        ConnectionState.SIGNALING_RECOVERY,
                        ConnectionState.WEBRTC_RECOVERY,
                        ConnectionState.DATA_CHANNEL_RECOVERY -> StatusConnecting
                        else -> StatusOffline
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = marker.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    // Location State Tag
                    val (tagBg, tagText) = when (marker.locationState) {
                        LocationState.LIVE -> Pair(StatusLive.copy(alpha = 0.15f), StatusLive)
                        LocationState.STALE -> Pair(StatusConnecting.copy(alpha = 0.15f), StatusConnecting)
                        LocationState.OFFLINE -> Pair(StatusOffline.copy(alpha = 0.15f), StatusOffline)
                        LocationState.UNKNOWN -> Pair(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tagBg
                    ) {
                        Text(
                            text = marker.locationState.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = tagText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Paging counter badge (e.g. "1 / 3") when multiple devices are registered
                    if (totalDeviceCount > 1) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                        ) {
                            Text(
                                text = "${deviceIndex + 1} / $totalDeviceCount",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4-Tile Telemetry Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Battery Gauge
                val battery = marker.batteryLevel
                val (batteryVal, batteryColor) = when {
                    battery == null -> Pair("N/A", MaterialTheme.colorScheme.onSurfaceVariant)
                    battery > 50 -> Pair("$battery%", StatusLive)
                    battery > 20 -> Pair("$battery%", StatusConnecting)
                    else -> Pair("$battery%", StatusError)
                }
                TelemetryTile(
                    label = "BATTERY",
                    value = batteryVal,
                    valueColor = batteryColor,
                    modifier = Modifier.weight(1f)
                )

                // 2. Velocity & Motion
                val speed = marker.speed ?: 0f
                val isMoving = speed > 0.5f
                TelemetryTile(
                    label = "MOVEMENT",
                    value = if (isMoving) String.format(Locale.US, "%.1f km/h", speed) else "Stationary",
                    valueColor = if (isMoving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.2f)
                )

                // 3. Heading & Compass
                val bearing = marker.bearing
                val compassText = if (bearing != null) {
                    "${bearing.toInt()}° ${computeCardinalDirection(bearing)}"
                } else {
                    "None"
                }
                TelemetryTile(
                    label = "HEADING",
                    value = compassText,
                    modifier = Modifier.weight(1.1f)
                )

                // 4. Accuracy Radius
                val accuracy = marker.accuracy
                TelemetryTile(
                    label = "ACCURACY",
                    value = if (accuracy != null) "±${accuracy.toInt()}m" else "±10m",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            Spacer(modifier = Modifier.height(10.dp))

            // Footer Coordinates & Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Coordinates with 1-tap copy
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val coords = "${String.format(Locale.US, "%.5f", marker.latitude)}, ${String.format(Locale.US, "%.5f", marker.longitude)}"
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Coordinates", coords))
                            Toast.makeText(context, "Coordinates copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = String.format(Locale.US, "%.5f, %.5f", marker.latitude, marker.longitude),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${marker.networkState ?: "Cellular"} • Updated ${formatRelativeTime(marker.timestamp)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Actions Row: Previous / Next Navigation + Focus Action Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (totalDeviceCount > 1) {
                        IconButton(
                            onClick = { onPreviousDevice?.invoke() },
                            enabled = onPreviousDevice != null,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous device",
                                tint = if (onPreviousDevice != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        IconButton(
                            onClick = { onNextDevice?.invoke() },
                            enabled = onNextDevice != null,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next device",
                                tint = if (onNextDevice != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Focus Action Button
                    Button(
                        onClick = onCenterOnDevice,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Center on device",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Focus",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun computeCardinalDirection(bearing: Float): String {
    val normalized = (bearing % 360f + 360f) % 360f
    return when {
        normalized >= 337.5f || normalized < 22.5f -> "N"
        normalized < 67.5f -> "NE"
        normalized < 112.5f -> "E"
        normalized < 157.5f -> "SE"
        normalized < 202.5f -> "S"
        normalized < 247.5f -> "SW"
        normalized < 292.5f -> "W"
        else -> "NW"
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val elapsedSeconds = (System.currentTimeMillis() - timestamp) / 1000
    return when {
        elapsedSeconds < 5 -> "Just now"
        elapsedSeconds < 60 -> "${elapsedSeconds}s ago"
        elapsedSeconds < 3600 -> "${elapsedSeconds / 60}m ago"
        else -> "${elapsedSeconds / 3600}h ago"
    }
}
