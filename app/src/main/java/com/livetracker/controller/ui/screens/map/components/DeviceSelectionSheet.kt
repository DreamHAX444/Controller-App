package com.livetracker.controller.ui.screens.map.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.core.tracker.device.domain.DeviceSelection
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceSummary
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusOffline

/**
 * Ultra-minimalist, sleek tracker device selector modal adhering to UI/UX Pro Max intelligence:
 * - Highly compact footprint (max 315dp width, max 380dp height) for minimal map intrusion.
 * - Single-line "All Trackers" capsule without redundant descriptive text.
 * - Micro-segmented Single vs Multi mode capsule.
 * - Clean, unified typography and high-contrast status dot telemetry.
 */
@Composable
fun DeviceSelectionSheet(
    isVisible: Boolean,
    deviceSelection: DeviceSelection,
    deviceSummaries: List<TrackerDeviceSummary>,
    onSelectAll: () -> Unit,
    onSelectSingle: (TrackerId) -> Unit,
    onToggleMultiple: (TrackerId) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isMultiSelectMode by remember(deviceSelection) {
        mutableStateOf(deviceSelection is DeviceSelection.Multiple)
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { -it / 2 },
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)
        ) + fadeIn(tween(200)),
        exit = slideOutVertically(
            targetOffsetY = { -it / 2 },
            animationSpec = tween(160)
        ) + fadeOut(tween(140)),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 315.dp)
                .heightIn(max = 380.dp)
                .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Header Row: Title + Count Badge + Micro Mode Capsule + Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Trackers",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "${deviceSummaries.size}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Micro Segmented Mode Switch Capsule
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        ) {
                            Row(modifier = Modifier.padding(2.dp)) {
                                ModePill(
                                    label = "Single",
                                    isSelected = !isMultiSelectMode,
                                    onClick = { isMultiSelectMode = false }
                                )
                                ModePill(
                                    label = "Multi",
                                    isSelected = isMultiSelectMode,
                                    onClick = { isMultiSelectMode = true }
                                )
                            }
                        }

                        // Close Button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close tracker selector",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // "All Trackers" Single-Line Compact Option Tile
                val isAllSelected = deviceSelection == DeviceSelection.All
                val allTileBg by animateColorAsState(
                    targetValue = if (isAllSelected && !isMultiSelectMode) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    },
                    label = "AllTileBg"
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = allTileBg,
                    border = BorderStroke(
                        1.dp,
                        if (isAllSelected && !isMultiSelectMode) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onSelectAll()
                            if (!isMultiSelectMode) onDismiss()
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isMultiSelectMode) {
                            Checkbox(
                                checked = isAllSelected,
                                onCheckedChange = { onSelectAll() },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isAllSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Place,
                                        contentDescription = null,
                                        tint = if (isAllSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Text(
                            text = "All Trackers",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.5.sp
                            ),
                            color = if (isAllSelected && !isMultiSelectMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f)
                        )

                        AnimatedVisibility(
                            visible = !isMultiSelectMode && isAllSelected,
                            enter = scaleIn(animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) + fadeIn(),
                            exit = scaleOut() + fadeOut()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // List of Individual Devices (Compact, 5dp vertical rhythm)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(deviceSummaries, key = { it.device.deviceId.value }) { summary ->
                        val isSelected = when (deviceSelection) {
                            is DeviceSelection.Single -> deviceSelection.deviceId == summary.device.deviceId
                            is DeviceSelection.Multiple -> deviceSelection.deviceIds.contains(summary.device.deviceId)
                            DeviceSelection.All -> isMultiSelectMode
                            DeviceSelection.Empty -> false
                        }

                        val statusDotColor = when (summary.device.connectionState) {
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

                        val itemBg by animateColorAsState(
                            targetValue = if (isSelected && !isMultiSelectMode) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            },
                            label = "ItemBg"
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = itemBg,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected && !isMultiSelectMode) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (isMultiSelectMode) {
                                        onToggleMultiple(summary.device.deviceId)
                                    } else {
                                        onSelectSingle(summary.device.deviceId)
                                        onDismiss()
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isMultiSelectMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { onToggleMultiple(summary.device.deviceId) },
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else {
                                    // Status Dot with subtle glow
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(statusDotColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = summary.device.displayName,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                            fontSize = 12.5.sp
                                        ),
                                        color = if (isSelected && !isMultiSelectMode) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    // Compact Micro Telemetry Row
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(top = 1.dp)
                                    ) {
                                        val statusText = when (summary.device.connectionState) {
                                            ConnectionState.CONNECTED -> "Online"
                                            ConnectionState.CONNECTING,
                                            ConnectionState.SIGNALING,
                                            ConnectionState.WEBRTC_CONNECTING,
                                            ConnectionState.DATA_CHANNEL_OPENING,
                                            ConnectionState.RECONNECTING,
                                            ConnectionState.SIGNALING_RECOVERY,
                                            ConnectionState.WEBRTC_RECOVERY,
                                            ConnectionState.DATA_CHANNEL_RECOVERY -> "Connecting"
                                            ConnectionState.DEGRADED -> "Degraded"
                                            else -> "Offline"
                                        }

                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 9.5.sp
                                            ),
                                            color = statusDotColor
                                        )

                                        if (summary.device.batteryLevel != null) {
                                            Text(
                                                text = "• ${summary.device.batteryLevel}%",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 9.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        if (summary.device.networkState != null) {
                                            Text(
                                                text = "• ${summary.device.networkState}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 9.5.sp
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                AnimatedVisibility(
                                    visible = !isMultiSelectMode && isSelected,
                                    enter = scaleIn(animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)) + fadeIn(),
                                    exit = scaleOut() + fadeOut()
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "ModePillBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "ModePillText"
    )

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 10.sp
            ),
            color = textColor,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
