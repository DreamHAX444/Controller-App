package com.livetracker.controller.ui.screens.diagnostics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusWarning

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.onDismissStatusMessage()
        }
    }

    val statusColor = when {
        !uiState.isNetworkOnline -> StatusError
        uiState.subsystems.any { it.isWarning } -> StatusWarning
        uiState.subsystems.any { !it.isHealthy } -> StatusError
        else -> StatusLive
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 88.dp)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Live Status Banner
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = uiState.overallStatusText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }

                    Text(
                        text = "Checked: ${uiState.lastCheckTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Section 2: Bento Grid Telemetry Cards
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val pingValue = if (uiState.pingMs >= 0) "${uiState.pingMs} ms" else "Offline"
                    val netSubtext = if (uiState.isNetworkOnline) {
                        "${uiState.networkType} • ${if (uiState.packetLossPercent > 0) "${uiState.packetLossPercent}% loss" else "0% loss"}"
                    } else {
                        "No Connection"
                    }
                    BentoCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Speed,
                        label = "NETWORK RTT",
                        value = pingValue,
                        subtext = netSubtext,
                        iconTint = if (uiState.isNetworkOnline) StatusLive else StatusError
                    )
                    val standbyTrackers = (uiState.registeredTrackers - uiState.onlineTrackers).coerceAtLeast(0)
                    BentoCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Sensors,
                        label = "FLEET STATUS",
                        value = "${uiState.registeredTrackers} Registered",
                        subtext = "${uiState.onlineTrackers} Online • $standbyTrackers Standby",
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BentoCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Memory,
                        label = "APP HEAP",
                        value = "${uiState.heapUsedMb} MB",
                        subtext = "Allocated: ${uiState.heapAllocatedMb} MB • Max: ${uiState.heapMaxMb} MB",
                        iconTint = MaterialTheme.colorScheme.secondary
                    )
                    BentoCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Storage,
                        label = "CACHE & STORAGE",
                        value = uiState.cacheDisplaySize,
                        subtext = "${uiState.cacheFilesCount} cached files on disk",
                        iconTint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // Section 3: Subsystem Health Checks
            Text(
                text = "SUBSYSTEM HEALTH STATUS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 2.dp, top = 4.dp)
            )

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    uiState.subsystems.forEachIndexed { index, sub ->
                        SubsystemRowItem(subsystem = sub)
                        if (index < uiState.subsystems.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f),
                                thickness = 0.8.dp
                            )
                        }
                    }
                }
            }

            // Section 4: Tactical Control Actions
            Text(
                text = "DIAGNOSTIC CONTROLS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 2.dp, top = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { viewModel.runDiagnostics() },
                    enabled = !uiState.isChecking,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1.3f)
                        .heightIn(min = 48.dp)
                ) {
                    if (uiState.isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Probing System...",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Run Self-Check",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                OutlinedButton(
                    onClick = { viewModel.clearCache() },
                    enabled = !uiState.isChecking,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Flush Cache",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun BentoCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    subtext: String,
    iconTint: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.6.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtext,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun SubsystemRowItem(
    subsystem: SubsystemStatus
) {
    val badgeColor = when {
        subsystem.isWarning -> StatusWarning
        !subsystem.isHealthy -> StatusError
        else -> StatusLive
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subsystem.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subsystem.description,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            shape = RoundedCornerShape(6.dp),
            color = badgeColor.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Text(
                text = subsystem.status,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                ),
                color = badgeColor,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
