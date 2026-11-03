package com.livetracker.controller.ui.screens.alerts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.alert.domain.AlertSeverity
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertsScreen(
    viewModel: AlertsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAcknowledgeDialog by remember { mutableStateOf(false) }
    var deviceDropdownExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (uiState.isFiltered) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filtered: Showing ${uiState.filteredAlerts.size} of ${uiState.totalUnacknowledgedCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { viewModel.clearFilters() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.sizeIn(minHeight = 32.dp)
                    ) {
                        Text(
                            text = "Reset",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // --- Filter Controls ---
        // Severity Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = uiState.selectedSeverity == SeverityFilter.ALL,
                onClick = { viewModel.setSeverityFilter(SeverityFilter.ALL) },
                label = { Text("All (${uiState.totalUnacknowledgedCount})") },
                shape = RoundedCornerShape(12.dp)
            )

            FilterChip(
                selected = uiState.selectedSeverity == SeverityFilter.CRITICAL,
                onClick = { viewModel.setSeverityFilter(SeverityFilter.CRITICAL) },
                label = { Text("Critical (${uiState.criticalCount})") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = StatusError,
                        modifier = Modifier.size(16.dp)
                    )
                },
                shape = RoundedCornerShape(12.dp)
            )

            FilterChip(
                selected = uiState.selectedSeverity == SeverityFilter.WARNING,
                onClick = { viewModel.setSeverityFilter(SeverityFilter.WARNING) },
                label = { Text("Warning (${uiState.warningCount})") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusConnecting,
                        modifier = Modifier.size(16.dp)
                    )
                },
                shape = RoundedCornerShape(12.dp)
            )

            FilterChip(
                selected = uiState.selectedSeverity == SeverityFilter.INFO,
                onClick = { viewModel.setSeverityFilter(SeverityFilter.INFO) },
                label = { Text("Info (${uiState.infoCount})") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = StatusLive,
                        modifier = Modifier.size(16.dp)
                    )
                },
                shape = RoundedCornerShape(12.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Device Dropdown + Reset Filter Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                val currentDeviceName = if (uiState.selectedDeviceId == null) {
                    "All Devices"
                } else {
                    uiState.deviceNames[uiState.selectedDeviceId] ?: com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(null, uiState.selectedDeviceId!!.value)
                }

                Surface(
                    onClick = { deviceDropdownExpanded = true },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.sizeIn(minHeight = 44.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentDeviceName,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select tracker",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = deviceDropdownExpanded,
                    onDismissRequest = { deviceDropdownExpanded = false }
                ) {
                    uiState.availableDevices.forEach { option ->
                        val isSelected = option.deviceId == uiState.selectedDeviceId
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.displayName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            onClick = {
                                viewModel.setDeviceFilter(option.deviceId)
                                deviceDropdownExpanded = false
                            },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null,
                            modifier = Modifier.sizeIn(minHeight = 48.dp)
                        )
                    }
                }
            }

            if (uiState.isFiltered) {
                TextButton(
                    onClick = { viewModel.clearFilters() },
                    modifier = Modifier.sizeIn(minHeight = 44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reset Filters",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Content Section: Empty States or List ---
        if (uiState.totalUnacknowledgedCount == 0) {
            // Empty State 1: No alerts in system at all
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Alerts",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "There are no unacknowledged alerts.\nGeofence events and connectivity warnings will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        } else if (uiState.filteredAlerts.isEmpty()) {
            // Empty State 2: Filters active but none match
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Matching Alerts",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No alerts match the selected filters.\nTry choosing a different severity or device, or reset all filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.clearFilters() },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.sizeIn(minHeight = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset Filters")
                    }
                }
            }
        } else {
            // List of alerts
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(uiState.filteredAlerts, key = { it.id }) { alert ->
                    val displayName = uiState.deviceNames[alert.deviceId] ?: com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(null, alert.deviceId.value)
                    AlertItem(
                        alert = alert,
                        deviceDisplayName = displayName,
                        onAcknowledge = { viewModel.acknowledgeAlert(it) }
                    )
                }
            }
        }
    }

    // --- Confirmation Dialog ---
    if (showAcknowledgeDialog) {
        val isFiltered = uiState.isFiltered
        val countToAck = if (isFiltered) uiState.filteredAlerts.size else uiState.totalUnacknowledgedCount
        val remainingCount = uiState.totalUnacknowledgedCount - countToAck

        AlertDialog(
            onDismissRequest = { showAcknowledgeDialog = false },
            title = {
                Text(
                    text = if (isFiltered) "Acknowledge Filtered Alerts?" else "Acknowledge All Alerts?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isFiltered) {
                        Text(
                            text = "Are you sure you want to acknowledge $countToAck filtered alert(s)?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (remainingCount > 0) {
                            Text(
                                text = "$remainingCount other alert(s) outside current filters will remain unacknowledged.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "Are you sure you want to acknowledge all $countToAck unacknowledged alert(s)?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAcknowledgeDialog = false
                        if (isFiltered) {
                            viewModel.acknowledgeFiltered()
                        } else {
                            viewModel.acknowledgeAll()
                        }
                    }
                ) {
                    Text(
                        text = if (isFiltered) "Acknowledge Filtered ($countToAck)" else "Acknowledge All",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAcknowledgeDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private data class SeveritySpec(
    val label: String,
    val color: Color,
    val containerColor: Color,
    val icon: ImageVector
)

private val alertTimeFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("h:mm a", Locale.getDefault())
}

@Composable
fun AlertItem(
    alert: Alert,
    onAcknowledge: (String) -> Unit,
    deviceDisplayName: String = alert.deviceId.value
) {
    val spec = when (alert.severity) {
        AlertSeverity.CRITICAL -> SeveritySpec(
            label = "CRITICAL",
            color = StatusError,
            containerColor = StatusError.copy(alpha = 0.14f),
            icon = Icons.Default.Error
        )
        AlertSeverity.WARNING -> SeveritySpec(
            label = "WARNING",
            color = StatusConnecting,
            containerColor = StatusConnecting.copy(alpha = 0.14f),
            icon = Icons.Default.Warning
        )
        AlertSeverity.INFO -> SeveritySpec(
            label = "INFO",
            color = StatusLive,
            containerColor = StatusLive.copy(alpha = 0.14f),
            icon = Icons.Default.Info
        )
    }

    val timeString = remember(alert.timestamp) {
        alertTimeFormatter.get()?.format(Date(alert.timestamp)).orEmpty()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(spec.containerColor, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = spec.label,
                    tint = spec.color,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Non-color dependent severity tag
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = spec.containerColor
                    ) {
                        Text(
                            text = spec.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = spec.color,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Device identifier tag showing friendly name
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = deviceDisplayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 10.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = timeString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = alert.message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { onAcknowledge(alert.id) },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Acknowledge alert",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

