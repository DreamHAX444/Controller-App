package com.livetracker.controller.ui.screens.permissions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.ui.components.UserProfileAvatar
import com.livetracker.controller.ui.screens.users.UserDeviceAssignmentUiState
import com.livetracker.controller.ui.screens.users.UserItemUiState
import com.livetracker.controller.ui.screens.users.UsersViewModel
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.util.DeviceNameFormatter

private val TacticalBg @Composable get() = MaterialTheme.colorScheme.background
private val TacticalSurface @Composable get() = MaterialTheme.colorScheme.surface
private val TacticalCard @Composable get() = MaterialTheme.colorScheme.surface
private val TacticalCardSecondary @Composable get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
private val TacticalBorder @Composable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
private val TacticalBorderSubtle @Composable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)

data class FeatureMetadata(
    val featureKey: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val isCritical: Boolean = false
)

private val FLEET_FEATURES = listOf(
    FeatureMetadata(
        featureKey = "LOCATION",
        title = "Realtime GPS Tracking",
        description = "Stream high-frequency fused location & heading updates (Default on assignment)",
        icon = Icons.Default.GpsFixed,
        isCritical = false
    ),
    FeatureMetadata(
        featureKey = "CAMERA",
        title = "Remote Camera Snapshot",
        description = "Request discreet remote camera snapshots and preview feeds",
        icon = Icons.Default.Videocam,
        isCritical = true
    ),
    FeatureMetadata(
        featureKey = "MIC",
        title = "Live Audio & Microphone",
        description = "Live acoustic monitoring and audio uplink stream",
        icon = Icons.Default.Mic,
        isCritical = true
    ),
    FeatureMetadata(
        featureKey = "SCREEN",
        title = "Screen Capture Feed",
        description = "Live display framebuffer capture via MediaProjection",
        icon = Icons.Default.Screenshot,
        isCritical = true
    ),
    FeatureMetadata(
        featureKey = "FILES",
        title = "Remote File Browser",
        description = "Browse tracker filesystem and download telemetry artifacts",
        icon = Icons.Default.FolderShared,
        isCritical = false
    ),
    FeatureMetadata(
        featureKey = "COMMANDS",
        title = "Remote Command Execution & Ping",
        description = "Dispatch remote control commands, ping signals, and device diagnostics",
        icon = Icons.Default.Terminal,
        isCritical = false
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    modifier: Modifier = Modifier,
    viewModel: UsersViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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

    var selectedUserId by remember { mutableStateOf<String?>(null) }
    var selectedDeviceId by remember { mutableStateOf<String?>(null) }
    var isAssignPickerOpen by remember { mutableStateOf(false) }

    // Resolve target user: prioritize selected, or fallback to first USER account, or first user
    val targetUser: UserItemUiState? = remember(uiState.users, selectedUserId) {
        val userList = uiState.users
        if (userList.isEmpty()) null
        else if (selectedUserId != null) userList.find { it.userId == selectedUserId } ?: userList.first()
        else userList.find { it.role == Role.USER } ?: userList.first()
    }

    // Synchronize selectedUserId
    LaunchedEffect(targetUser?.userId) {
        if (targetUser != null && selectedUserId != targetUser.userId) {
            selectedUserId = targetUser.userId
        }
    }

    // Resolve active assignment for selected targetUser
    val activeAssignments = targetUser?.assignedDevices.orEmpty()
    val activeAssignment: UserDeviceAssignmentUiState? = remember(activeAssignments, selectedDeviceId) {
        if (activeAssignments.isEmpty()) null
        else if (selectedDeviceId != null) activeAssignments.find { it.deviceId == selectedDeviceId } ?: activeAssignments.first()
        else activeAssignments.first()
    }

    // Synchronize selectedDeviceId
    LaunchedEffect(activeAssignment?.deviceId) {
        if (activeAssignment != null && selectedDeviceId != activeAssignment.deviceId) {
            selectedDeviceId = activeAssignment.deviceId
        }
    }

    // Unassigned fleet devices for assignment picker
    val assignedDeviceIds = remember(activeAssignments) { activeAssignments.map { it.deviceId }.toSet() }
    val unassignedFleetDevices = remember(uiState.fleetDevices, assignedDeviceIds) {
        uiState.fleetDevices.filter { it.deviceId !in assignedDeviceIds }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = TacticalBg
    ) { paddingValues ->
        if (uiState.isLoading && uiState.users.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (uiState.users.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No users registered in fleet database.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Device Permissions Matrix",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Granular hardware access rules per user & assigned tracker",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // STEP A: Target User Selector
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = TacticalCard),
                    border = BorderStroke(1.dp, TacticalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SELECT OPERATOR / ACCOUNT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (targetUser != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when (targetUser.role) {
                                        Role.OWNER -> Color(0xFF78350F).copy(alpha = 0.4f)
                                        Role.ADMIN -> Color(0xFF4C1D95).copy(alpha = 0.4f)
                                        Role.USER -> Color(0xFF1E3A8A).copy(alpha = 0.4f)
                                        Role.VISITOR -> Color(0xFF334155).copy(alpha = 0.4f)
                                    },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        text = targetUser.role.name,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = when (targetUser.role) {
                                            Role.OWNER -> Color(0xFFFBBF24)
                                            Role.ADMIN -> Color(0xFFA78BFA)
                                            Role.USER -> Color(0xFF60A5FA)
                                            Role.VISITOR -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(uiState.users, key = { it.userId }) { user ->
                                val isSelected = targetUser?.userId == user.userId
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedUserId = user.userId
                                        selectedDeviceId = null
                                    },
                                    leadingIcon = {
                                        UserProfileAvatar(
                                            avatarUrl = user.avatarUrl,
                                            displayName = user.displayName,
                                            role = user.role,
                                            size = 24.dp
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = user.displayName.take(18),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                        containerColor = TacticalSurface,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = TacticalBorder,
                                        selectedBorderColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }
                }

                // Authority & Sandbox Explanations for non-USER accounts
                if (targetUser != null && (targetUser.role == Role.OWNER || targetUser.role == Role.ADMIN)) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TacticalCardSecondary),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Full Fleet Access (${targetUser.role.name})",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Fleet Administrators and Owners possess unrestricted access to all connected devices with all 6 features (Location, Camera, Mic, Screen, Files, Commands) enabled automatically by system architecture.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (targetUser != null && targetUser.role == Role.VISITOR) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TacticalCardSecondary),
                        border = BorderStroke(1.dp, StatusConnecting.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(StatusConnecting.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = StatusConnecting,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Visitor Sandbox Mode",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Visitors operate within an isolated demo map sandbox with zero real device access. To assign devices and grant hardware permissions, promote this account to User in the Users tab.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (targetUser != null && targetUser.role == Role.USER) {
                    // STEP B: Assigned Device Selector
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TacticalCard),
                        border = BorderStroke(1.dp, TacticalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ASSIGNED DEVICES (${activeAssignments.size})",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (unassignedFleetDevices.isNotEmpty()) {
                                    TextButton(
                                        onClick = { isAssignPickerOpen = !isAssignPickerOpen },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isAssignPickerOpen) Icons.Default.Close else Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isAssignPickerOpen) "Close" else "Assign Device", fontSize = 12.sp)
                                    }
                                }
                            }

                            // Device Assignment Dropdown Picker
                            AnimatedVisibility(visible = isAssignPickerOpen) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .background(TacticalSurface, RoundedCornerShape(10.dp))
                                        .border(1.dp, TacticalBorder, RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "Select Device to Assign:",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    unassignedFleetDevices.forEach { dev ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = TacticalCard,
                                            border = BorderStroke(1.dp, TacticalBorderSubtle),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp)
                                                .clickable {
                                                    viewModel.onAssignDevice(targetUser.userId, dev.deviceId)
                                                    selectedDeviceId = dev.deviceId
                                                    isAssignPickerOpen = false
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text(
                                                        text = DeviceNameFormatter.formatFriendlyName(dev.name, dev.deviceId),
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = "${dev.model} • ${DeviceNameFormatter.formatShortId(dev.deviceId)}",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = "Assign",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (activeAssignments.isEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = TacticalSurface,
                                    border = BorderStroke(1.dp, TacticalBorderSubtle),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PhoneAndroid,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "No devices assigned to ${targetUser.displayName}.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Assign an active fleet tracker device to configure feature permissions.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.height(8.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(activeAssignments, key = { it.deviceId }) { asgn ->
                                        val isSelected = activeAssignment?.deviceId == asgn.deviceId
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedDeviceId = asgn.deviceId },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.PhoneAndroid,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = DeviceNameFormatter.formatFriendlyName(asgn.deviceName, asgn.deviceId),
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = StatusLive.copy(alpha = 0.20f),
                                                selectedLabelColor = StatusLive,
                                                containerColor = TacticalSurface,
                                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            border = FilterChipDefaults.filterChipBorder(
                                                enabled = true,
                                                selected = isSelected,
                                                borderColor = TacticalBorder,
                                                selectedBorderColor = StatusLive
                                            ),
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        )
                                    }
                                }

                                if (activeAssignment != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Device ID: ${activeAssignment.deviceId}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        TextButton(
                                            onClick = { viewModel.onUnassignDevice(targetUser.userId, activeAssignment.deviceId) },
                                            colors = ButtonDefaults.textButtonColors(contentColor = StatusError),
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Unassign", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // STEP C: Live 6-Feature Permission Matrix
                    if (activeAssignment != null) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = TacticalCard),
                            border = BorderStroke(1.dp, TacticalBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "FEATURE PERMISSIONS MATRIX",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Active controls for ${targetUser.displayName} on ${DeviceNameFormatter.formatFriendlyName(activeAssignment.deviceName, activeAssignment.deviceId)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                FLEET_FEATURES.forEachIndexed { index, feature ->
                                    val isAllowed = activeAssignment.features[feature.featureKey] ?: (feature.featureKey == "LOCATION")
                                    PermissionToggleCard(
                                        feature = feature,
                                        isAllowed = isAllowed,
                                        onToggle = { newAllowed ->
                                            viewModel.onToggleFeaturePermission(
                                                activeAssignment.assignmentId,
                                                feature.featureKey,
                                                newAllowed,
                                                targetUser.userId,
                                                activeAssignment.deviceId
                                            )
                                        }
                                    )
                                    if (index < FLEET_FEATURES.lastIndex) {
                                        HorizontalDivider(
                                            color = TacticalBorderSubtle,
                                            thickness = 0.8.dp,
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PermissionToggleCard(
    feature: FeatureMetadata,
    isAllowed: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isAllowed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = feature.icon,
                        contentDescription = null,
                        tint = if (isAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = feature.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (feature.isCritical) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "CRITICAL",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = feature.description,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = isAllowed,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.heightIn(min = 48.dp)
        )
    }
}
