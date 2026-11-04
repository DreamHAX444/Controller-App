package com.livetracker.controller.ui.screens.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.location.domain.formatElapsedSwift
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    modifier: Modifier = Modifier,
    onNavigateToMap: () -> Unit = {},
    onNavigateToCamera: () -> Unit = {},
    onNavigateToAudio: () -> Unit = {},
    onNavigateToScreen: () -> Unit = {},
    onNavigateToFiles: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface status or error messages via Snackbar
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.onDismissMessage()
        }
    }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.onDismissMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // --- Visitor Demo Sandbox Warning ---
            if (uiState.isVisitor) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Visitor Sandbox",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Visitor Mode: Physical tracker fleet is protected. Synthetic demo devices are isolated.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // --- Search Field ---
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                placeholder = { Text("Search by name, ID, or model...", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            // --- Filter Tabs (Owner & Admin: All, Assigned, Unassigned) ---
            if (uiState.userRole == Role.OWNER || uiState.userRole == Role.ADMIN) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.selectedTab == DeviceFilterTab.ALL,
                        onClick = { viewModel.onTabSelected(DeviceFilterTab.ALL) },
                        label = { Text("All (${uiState.totalCount})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = uiState.selectedTab == DeviceFilterTab.ASSIGNED,
                        onClick = { viewModel.onTabSelected(DeviceFilterTab.ASSIGNED) },
                        label = { Text("Assigned (${uiState.assignedCount})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = uiState.selectedTab == DeviceFilterTab.UNASSIGNED,
                        onClick = { viewModel.onTabSelected(DeviceFilterTab.UNASSIGNED) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Unassigned (${uiState.unassignedCount})")
                                if (uiState.unassignedCount > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }
            }

            // --- Device Cards List ---
            if (uiState.devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (uiState.searchQuery.isNotBlank()) "No devices matching '${uiState.searchQuery}'" else "No devices registered",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.devices, key = { it.deviceId }) { device ->
                        DeviceCard(
                            device = device,
                            userRole = uiState.userRole,
                            onSelect = {
                                viewModel.onSelectDevice(device.deviceId)
                            },
                            onFocusOnMap = {
                                viewModel.onSelectDevice(device.deviceId)
                                onNavigateToMap()
                            },
                            onNavigateToCamera = {
                                viewModel.onSelectDevice(device.deviceId)
                                onNavigateToCamera()
                            },
                            onNavigateToAudio = {
                                viewModel.onSelectDevice(device.deviceId)
                                onNavigateToAudio()
                            },
                            onNavigateToScreen = {
                                viewModel.onSelectDevice(device.deviceId)
                                onNavigateToScreen()
                            },
                            onNavigateToFiles = {
                                viewModel.onSelectDevice(device.deviceId)
                                onNavigateToFiles()
                            },
                            onAssign = {
                                viewModel.onOpenAssignmentDialog(device.deviceId)
                            },
                            onUnassign = {
                                device.assignedUserId?.let { userId ->
                                    viewModel.unassignDevice(device.deviceId, userId)
                                }
                            },
                            onWake = {
                                viewModel.wakeDevice(device.deviceId)
                            },
                            onSleep = {
                                viewModel.sleepDevice(device.deviceId)
                            },
                            onDelete = {
                                viewModel.onRequestDeleteDevice(device)
                            }
                        )
                    }
                }
            }
        }

        // --- Delete Confirmation Modal Dialog ---
        if (uiState.devicePendingDeletion != null) {
            val targetDev = uiState.devicePendingDeletion!!
            AlertDialog(
                onDismissRequest = { viewModel.onDismissDeleteDialog() },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                title = {
                    Text("Delete Device", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                },
                text = {
                    Text("Are you sure you want to permanently delete '${targetDev.displayName}' (${com.livetracker.controller.ui.util.DeviceNameFormatter.formatShortId(targetDev.deviceId)}) from the fleet? All associated telemetry and assignments will be removed.")
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.deleteDevice(targetDev.deviceId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onDismissDeleteDialog() }) {
                        Text("Cancel")
                    }
                }
            )
        }
        // --- Assignment Modal Dialog ---
        if (uiState.selectedDeviceIdForAssignment != null) {
            val targetDevId = uiState.selectedDeviceIdForAssignment!!
            DeviceAssignmentDialog(
                deviceId = targetDevId,
                users = uiState.assignableUsers,
                isAssigning = uiState.isAssigning,
                onDismiss = { viewModel.onDismissAssignmentDialog() },
                onConfirm = { targetUserId ->
                    viewModel.assignDevice(targetDevId, targetUserId)
                }
            )
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceItemUiState,
    userRole: Role,
    onFocusOnMap: () -> Unit,
    onAssign: () -> Unit,
    onUnassign: () -> Unit,
    onSelect: () -> Unit = {},
    onNavigateToCamera: () -> Unit = {},
    onNavigateToAudio: () -> Unit = {},
    onNavigateToScreen: () -> Unit = {},
    onNavigateToFiles: () -> Unit = {},
    onWake: () -> Unit = {},
    onSleep: () -> Unit = {},
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectionDotColor = when (device.connectionState) {
        ConnectionState.CONNECTED -> StatusLive
        ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.DATA_CHANNEL_RECOVERY -> StatusConnecting
        ConnectionState.DISCONNECTED, ConnectionState.FAILED -> StatusError
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (device.isSelected) 2.dp else 1.dp,
            if (device.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = if (device.isSelected) 4.dp else 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header: Icon + Device Name + Connection Dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Device",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = device.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${com.livetracker.controller.ui.util.DeviceNameFormatter.formatShortModel(device.model)} • ID: ${com.livetracker.controller.ui.util.DeviceNameFormatter.formatShortId(device.deviceId)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(connectionDotColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = device.connectionState.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Badges Row: Lifecycle Status, Assignment, Battery, Network
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (device.status) {
                        "ACTIVE" -> MaterialTheme.colorScheme.primaryContainer
                        "PENDING" -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Text(
                        text = device.status,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = when (device.status) {
                            "ACTIVE" -> MaterialTheme.colorScheme.onPrimaryContainer
                            "PENDING" -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Assignment Badge
                if (userRole == Role.OWNER || userRole == Role.ADMIN) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (device.isAssigned) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = if (device.isAssigned) "Assigned" else "Unassigned",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (device.isAssigned) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Battery Badge
                if (device.batteryLevel != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${device.batteryLevel}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Network Badge
                if (device.networkState != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = device.networkState,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Last Seen (Live ticking every second)
                var currentTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(device.lastSeenTimestamp) {
                    while (true) {
                        delay(1000L)
                        currentTick = System.currentTimeMillis()
                    }
                }
                val liveSeen = if (device.lastSeenTimestamp != null && device.lastSeenTimestamp > 0) {
                    formatElapsedSwift(device.lastSeenTimestamp, currentTick)
                } else {
                    device.lastSeenFormatted
                }

                Text(
                    text = liveSeen,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Quick Service Launch Row
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onNavigateToCamera,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Icon(imageVector = Icons.Default.Videocam, contentDescription = "Camera", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cam", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                }

                FilledTonalButton(
                    onClick = onNavigateToAudio,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Icon(imageVector = Icons.Default.Call, contentDescription = "Audio", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Audio", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                }

                FilledTonalButton(
                    onClick = onNavigateToScreen,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Icon(imageVector = Icons.Default.Screenshot, contentDescription = "Screen", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Screen", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                }

                FilledTonalButton(
                    onClick = onNavigateToFiles,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Icon(imageVector = Icons.Default.FolderShared, contentDescription = "Files", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Files", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 1.dp
            )
            Spacer(modifier = Modifier.height(10.dp))

            // Actions Row: Wake/Sleep + Focus on Map + Assign/Unassign
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Wake & Sleep Quick Controls (Available for non-visitors)
                if (userRole != Role.VISITOR) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = onWake,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = StatusLive.copy(alpha = 0.16f),
                                contentColor = StatusLive
                            ),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Wake",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Wake", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }

                        OutlinedButton(
                            onClick = onSleep,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Sleep",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sleep", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onFocusOnMap,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Map", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    }

                    if (userRole == Role.OWNER || userRole == Role.ADMIN) {
                        if (device.isAssigned) {
                            OutlinedButton(
                                onClick = onUnassign,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Unassign", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Button(
                                onClick = onAssign,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Assign", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Device",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAssignmentDialog(
    deviceId: String,
    users: List<com.livetracker.controller.core.tracker.supabase.UserProfileRecord>,
    isAssigning: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (userId: String) -> Unit
) {
    var selectedUserId by remember { mutableStateOf(users.firstOrNull()?.id ?: "") }
    var manualUserId by remember { mutableStateOf("") }
    var useManualInput by remember { mutableStateOf(users.isEmpty()) }
    val isManualUuidValid = manualUserId.trim().matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Assign Tracker Device",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Assign device '$deviceId' to an active user.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                if (users.isNotEmpty() && !useManualInput) {
                    Text(
                        text = "Select User Profile:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        users.forEach { user ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selectedUserId == user.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedUserId = user.id }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedUserId == user.id,
                                        onClick = { selectedUserId = user.id }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = user.displayName.ifBlank { user.email ?: user.id },
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                        )
                                        Text(
                                            text = "Role: ${user.role} • ID: ${user.id.take(8)}...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { useManualInput = true }) {
                        Text("Enter UUID manually", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    OutlinedTextField(
                        value = manualUserId,
                        onValueChange = { manualUserId = it },
                        label = { Text("User UUID") },
                        placeholder = { Text("00000000-0000-0000-0000-000000000000") },
                        singleLine = true,
                        isError = manualUserId.isNotBlank() && !isManualUuidValid,
                        supportingText = {
                            if (manualUserId.isNotBlank() && !isManualUuidValid) {
                                Text("Format: 8-4-4-4-12 hex characters (e.g. 00000000-0000-0000-0000-000000000000)")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (users.isNotEmpty()) {
                        TextButton(onClick = { useManualInput = false }) {
                            Text("Choose from active users", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = if (useManualInput) manualUserId.trim() else selectedUserId
                    if (target.isNotBlank()) {
                        onConfirm(target)
                    }
                },
                enabled = !isAssigning && ((useManualInput && isManualUuidValid) || (!useManualInput && selectedUserId.isNotBlank()))
            ) {
                if (isAssigning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Assign")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isAssigning) {
                Text("Cancel")
            }
        }
    )
}
