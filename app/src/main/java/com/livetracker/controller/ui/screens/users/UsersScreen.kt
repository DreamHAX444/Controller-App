package com.livetracker.controller.ui.screens.users

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.tracker.supabase.DeviceRecord
import com.livetracker.controller.ui.components.UserProfileAvatar
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive

// UI/UX Pro Max Dynamic Semantic Theme Tokens (100% Light/Dark Adaptive)
private val TacticalBg @Composable get() = MaterialTheme.colorScheme.background
private val TacticalSurface @Composable get() = MaterialTheme.colorScheme.surface
private val TacticalCard @Composable get() = MaterialTheme.colorScheme.surface
private val TacticalCardSecondary @Composable get() = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
private val TacticalBorder @Composable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
private val TacticalBorderSubtle @Composable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(
    viewModel: UsersViewModel,
    modifier: Modifier = Modifier
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = TacticalBg
    ) { paddingValues ->
        if (uiState.isAccessDenied) {
            AccessDeniedView(
                isVisitor = uiState.isVisitor,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
// 1. Unified header in MainShell TopBar

                // 2. Pending Approval Alert Banner (when pending accounts exist)
                if (uiState.pendingCount > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = StatusConnecting.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, StatusConnecting.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Pending Approval Alert",
                                tint = StatusConnecting,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "${uiState.pendingCount} account(s) pending approval. Immediate action required.",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = StatusConnecting
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Search Bar
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = {
                        Text(
                            "Search users by email, name, or ID...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    },
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
                                Icon(Icons.Default.Close, contentDescription = "Clear search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = TacticalSurface,
                        unfocusedContainerColor = TacticalSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = TacticalBorder,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 4. Tactical Filter Chips
                FilterChipsRow(
                    selectedFilter = uiState.selectedFilter,
                    totalCount = uiState.totalCount,
                    visitorsCount = uiState.visitorsCount,
                    activeCount = uiState.activeCount,
                    suspendedDisabledCount = uiState.suspendedDisabledCount,
                    onSelectFilter = { viewModel.onFilterSelected(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 5. Users List or Empty / Loading State
                if (uiState.isLoading && uiState.users.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (uiState.users.isEmpty()) {
                    EmptyUsersView(
                        searchQuery = uiState.searchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(uiState.users, key = { it.userId }) { user ->
                            UserCard(
                                user = user,
                                currentUserRole = uiState.currentUserRole,
                                onApprove = { viewModel.onApproveUser(user.userId) },
                                onPromote = { viewModel.onPromoteVisitorToUser(user) },
                                onManage = { viewModel.onOpenManageUser(user) }
                            )
                        }
                    }
                }
            }
        }

        // 6. User Management Modal Dialog
        if (uiState.selectedUserForManagement != null) {
            val targetUser = uiState.selectedUserForManagement!!
            UserManagementDialog(
                user = targetUser,
                currentUserRole = uiState.currentUserRole,
                currentUserId = uiState.currentUserId,
                fleetDevices = uiState.fleetDevices,
                isAssigning = uiState.isAssigningDevice,
                onDismiss = { viewModel.onDismissManageUser() },
                onChangeRole = { newRole -> viewModel.onChangeRole(targetUser.userId, newRole) },
                onRequestStatusChange = { newStatus -> viewModel.onRequestStatusChange(targetUser, newStatus) },
                onAssignDevice = { devId -> viewModel.onAssignDevice(targetUser.userId, devId) },
                onUnassignDevice = { devId -> viewModel.onUnassignDevice(targetUser.userId, devId) },
                onToggleFeaturePermission = { asgnId, feature, allowed, userId, devId ->
                    viewModel.onToggleFeaturePermission(asgnId, feature, allowed, userId, devId)
                },
                onRequestDelete = { user -> viewModel.onRequestDeleteUser(user) }
            )
        }

        // 7. Status Change Confirmation Alert Dialog
        if (uiState.userToConfirmStatusChange != null) {
            val tuple = uiState.userToConfirmStatusChange!!
            val targetUser = tuple.first
            val targetStatus = tuple.second
            AlertDialog(
                onDismissRequest = { viewModel.onCancelStatusChange() },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Alert",
                        tint = StatusError,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Confirm ${targetStatus.name} Status",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to change '${targetUser.displayName}' (${targetUser.email}) to ${targetStatus.name}? The account will immediately be restricted from accessing the operational fleet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.onConfirmStatusChange() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusError,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Confirm ${targetStatus.name}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onCancelStatusChange() }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = TacticalCard,
                tonalElevation = 6.dp,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // 8. User Account Deletion Confirmation Alert Dialog
        if (uiState.userToConfirmDelete != null) {
            val userToDelete = uiState.userToConfirmDelete!!
            AlertDialog(
                onDismissRequest = { viewModel.onDismissDeleteUser() },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = StatusError,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = "Delete User Account",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        text = "Are you sure you want to permanently delete '${userToDelete.displayName}' (${userToDelete.email})?\n\nThis will remove all associated permissions, device assignments, and account data. This action cannot be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.onConfirmDeleteUser() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusError,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "Delete Account",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.onDismissDeleteUser() },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = TacticalCard,
                tonalElevation = 6.dp,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Subcomponents
// -------------------------------------------------------------------------------------------------

@Composable
private fun UsersHeader(
    totalCount: Int,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "User Administration",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    letterSpacing = 0.3.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Text(
                    text = "$totalCount Users",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh Users",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun FilterChipsRow(
    selectedFilter: UserFilter,
    totalCount: Int,
    visitorsCount: Int,
    activeCount: Int,
    suspendedDisabledCount: Int,
    onSelectFilter: (UserFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedFilter == UserFilter.ALL,
            onClick = { onSelectFilter(UserFilter.ALL) },
            label = { Text("All ($totalCount)") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                selectedLabelColor = MaterialTheme.colorScheme.primary,
                containerColor = TacticalSurface,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selectedFilter == UserFilter.ALL,
                borderColor = TacticalBorder,
                selectedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        FilterChip(
            selected = selectedFilter == UserFilter.VISITORS,
            onClick = { onSelectFilter(UserFilter.VISITORS) },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Visitors ($visitorsCount)")
                    if (visitorsCount > 0) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                selectedLabelColor = MaterialTheme.colorScheme.primary,
                containerColor = TacticalSurface,
                labelColor = if (visitorsCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selectedFilter == UserFilter.VISITORS,
                borderColor = if (visitorsCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else TacticalBorder,
                selectedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        FilterChip(
            selected = selectedFilter == UserFilter.ACTIVE,
            onClick = { onSelectFilter(UserFilter.ACTIVE) },
            label = { Text("Active ($activeCount)") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = StatusLive.copy(alpha = 0.2f),
                selectedLabelColor = StatusLive,
                containerColor = TacticalSurface,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selectedFilter == UserFilter.ACTIVE,
                borderColor = TacticalBorder,
                selectedBorderColor = StatusLive
            )
        )

        FilterChip(
            selected = selectedFilter == UserFilter.SUSPENDED_DISABLED,
            onClick = { onSelectFilter(UserFilter.SUSPENDED_DISABLED) },
            label = { Text("Suspended ($suspendedDisabledCount)") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = StatusError.copy(alpha = 0.2f),
                selectedLabelColor = StatusError,
                containerColor = TacticalSurface,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selectedFilter == UserFilter.SUSPENDED_DISABLED,
                borderColor = TacticalBorder,
                selectedBorderColor = StatusError
            )
        )
    }
}

@Composable
private fun UserCard(
    user: UserItemUiState,
    currentUserRole: Role = Role.USER,
    onApprove: () -> Unit,
    onPromote: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = TacticalCard,
        border = BorderStroke(1.dp, TacticalBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Avatar, Info, Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserProfileAvatar(
                    avatarUrl = user.avatarUrl,
                    displayName = user.displayName,
                    role = user.role,
                    size = 42.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (user.email.isNotBlank()) {
                        Text(
                            text = user.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "UID: ${user.userId}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp
                        ),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    RoleBadge(role = user.role)
                    StatusBadge(status = user.status)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metrics Row: Devices count & Active permissions count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricChip(
                    icon = Icons.Default.Phone,
                    label = when {
                        user.isAdmin -> "All Fleet Devices (${user.assignedDevicesCount})"
                        user.isVisitor -> "0 Assigned Devices"
                        else -> "${user.assignedDevicesCount} Assigned ${if (user.assignedDevicesCount == 1) "Device" else "Devices"}"
                    },
                    containerColor = TacticalSurface
                )
                MetricChip(
                    icon = Icons.Default.Lock,
                    label = when {
                        user.isAdmin -> "Full Permissions"
                        user.isVisitor -> "Demo Mode (Visitor)"
                        else -> "${user.activePermissionsCount} Permissions"
                    },
                    containerColor = TacticalSurface
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Actions Row: Quick Action Promote / Approve + Manage Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (user.role == Role.VISITOR) {
                    Button(
                        onClick = onPromote,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Promote to User",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                } else if (user.isPending) {
                    Button(
                        onClick = onApprove,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusLive,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Approve",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                val canManage = currentUserRole != Role.ADMIN || (user.role != Role.OWNER && user.role != Role.ADMIN)
                if (canManage) {
                    OutlinedButton(
                        onClick = onManage,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        ),
                        border = BorderStroke(1.dp, TacticalBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = if (user.role == Role.VISITOR || user.isPending) Modifier.weight(1f) else Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Manage User",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TacticalSurface,
                        border = BorderStroke(1.dp, TacticalBorderSubtle),
                        modifier = if (user.role == Role.VISITOR || user.isPending) Modifier.weight(1f) else Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Protected (${user.role.name})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserAvatarWithRole(
    role: Role,
    name: String,
    email: String
) {
    val (roleCode, roleColor, roleBg) = when (role) {
        Role.OWNER -> Triple("OW", Color(0xFFFBBF24), Color(0xFF78350F))
        Role.ADMIN -> Triple("AD", Color(0xFFA78BFA), Color(0xFF4C1D95))
        Role.USER -> Triple("US", Color(0xFF60A5FA), Color(0xFF1E3A8A))
        Role.VISITOR -> Triple("VI", MaterialTheme.colorScheme.onSurfaceVariant, Color(0xFF334155))
    }

    val initials = remember(name, email) {
        val src = name.ifBlank { email }
        val parts = src.split(" ", "@", ".", "_").filter { it.isNotBlank() }
        if (parts.size >= 2) {
            "${parts[0].first().uppercase()}${parts[1].first().uppercase()}"
        } else if (parts.isNotEmpty()) {
            parts[0].take(2).uppercase()
        } else {
            "US"
        }
    }

    Box(modifier = Modifier.size(44.dp)) {
        // Main Avatar Circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(roleBg.copy(alpha = 0.5f))
                .border(1.dp, roleColor.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Role Letter Badge in Corner
        Surface(
            shape = CircleShape,
            color = roleBg,
            border = BorderStroke(1.dp, roleColor),
            modifier = Modifier
                .size(16.dp)
                .align(Alignment.BottomEnd)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = roleCode,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 7.5.sp
                    ),
                    color = roleColor
                )
            }
        }
    }
}

@Composable
private fun RoleBadge(role: Role) {
    val (bgColor, textColor) = when (role) {
        Role.OWNER -> Pair(Color(0xFF78350F).copy(alpha = 0.4f), Color(0xFFFBBF24))
        Role.ADMIN -> Pair(Color(0xFF4C1D95).copy(alpha = 0.4f), Color(0xFFA78BFA))
        Role.USER -> Pair(Color(0xFF1E3A8A).copy(alpha = 0.4f), Color(0xFF60A5FA))
        Role.VISITOR -> Pair(Color(0xFF334155).copy(alpha = 0.4f), MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f))
    ) {
        Text(
            text = role.name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.5.sp),
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StatusBadge(status: AccountStatus) {
    val (bgColor, textColor) = when (status) {
        AccountStatus.ACTIVE -> Pair(StatusLive.copy(alpha = 0.15f), StatusLive)
        AccountStatus.PENDING -> Pair(StatusConnecting.copy(alpha = 0.15f), StatusConnecting)
        AccountStatus.SUSPENDED -> Pair(StatusError.copy(alpha = 0.15f), StatusError)
        AccountStatus.DISABLED -> Pair(Color(0xFF334155).copy(alpha = 0.4f), MaterialTheme.colorScheme.onSurfaceVariant)
        AccountStatus.UNKNOWN -> Pair(Color(0xFF334155).copy(alpha = 0.3f), MaterialTheme.colorScheme.outline)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f))
    ) {
        Text(
            text = status.name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.5.sp),
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun MetricChip(
    icon: ImageVector,
    label: String,
    containerColor: Color
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(1.dp, TacticalBorderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyUsersView(
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = Color(0xFF475569),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (searchQuery.isNotBlank()) "No users matching '$searchQuery'" else "No users match selected filter",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Try adjusting your search criteria or filter tabs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun AccessDeniedView(
    isVisitor: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = TacticalCard,
            border = BorderStroke(1.dp, TacticalBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(StatusError.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Access Restricted",
                        tint = StatusError,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Access Restricted",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isVisitor) {
                        "Visitor Sandbox: User administration and RBAC privilege configuration are restricted in demo mode."
                    } else {
                        "User account administration and permission assignment require Fleet Owner or Administrator credentials."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// User Management Bottom Sheet / Dialog
// -------------------------------------------------------------------------------------------------

@Composable
private fun UserManagementDialog(
    user: UserItemUiState,
    currentUserRole: Role,
    currentUserId: String?,
    fleetDevices: List<DeviceRecord>,
    isAssigning: Boolean,
    onDismiss: () -> Unit,
    onChangeRole: (Role) -> Unit,
    onRequestStatusChange: (AccountStatus) -> Unit,
    onAssignDevice: (String) -> Unit,
    onUnassignDevice: (String) -> Unit,
    onToggleFeaturePermission: (Long, String, Boolean, String, String) -> Unit,
    onRequestDelete: (UserItemUiState) -> Unit
) {
    var isAssignPickerOpen by remember { mutableStateOf(false) }
    val assignedDeviceIds = remember(user.assignedDevices) {
        user.assignedDevices.map { it.deviceId }.toSet()
    }
    val unassignedFleetDevices = remember(fleetDevices, assignedDeviceIds) {
        fleetDevices.filter { it.deviceId !in assignedDeviceIds }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = TacticalSurface,
            border = BorderStroke(1.dp, TacticalBorder),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Top: Header with User Summary & Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        UserProfileAvatar(
                            avatarUrl = user.avatarUrl,
                            displayName = user.displayName,
                            role = user.role,
                            size = 44.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = user.displayName,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = user.email.ifBlank { user.userId },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                HorizontalDivider(
                    color = TacticalBorder,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                // Scrollable Configuration Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // SECTION 1: ROLE CONFIGURATION
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TacticalCard),
                        border = BorderStroke(1.dp, TacticalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Account Role",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Defines authority boundaries across the controller platform.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Owner constraint notices
                            val isOwnerTarget = user.isOwner
                            val isSelf = currentUserId == user.userId
                            val canModifyRole = when (currentUserRole) {
                                Role.OWNER -> !isSelf
                                Role.ADMIN -> user.role == Role.VISITOR && !isSelf
                                else -> false
                            }

                            val roleNotice = when {
                                isSelf -> "🔒 You cannot modify your own role."
                                currentUserRole == Role.ADMIN && user.role != Role.VISITOR -> "🔒 Administrators cannot alter established User roles. Only the Fleet Owner may manage role assignments."
                                currentUserRole == Role.ADMIN && user.role == Role.VISITOR -> "ℹ️ Administrators may promote Visitor accounts to User."
                                else -> null
                            }

                            if (roleNotice != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = roleNotice,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (canModifyRole) MaterialTheme.colorScheme.primary else StatusConnecting
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Available roles
                            val availableRoles = when (currentUserRole) {
                                Role.OWNER -> listOf(Role.ADMIN, Role.USER, Role.VISITOR)
                                Role.ADMIN -> if (user.role == Role.VISITOR) listOf(Role.USER) else emptyList()
                                else -> emptyList()
                            }

                            if (availableRoles.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    availableRoles.forEach { roleOption ->
                                        val isSelected = user.role == roleOption
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                if (canModifyRole && user.role != roleOption) {
                                                    onChangeRole(roleOption)
                                                }
                                            },
                                            enabled = canModifyRole,
                                            label = { Text(if (currentUserRole == Role.ADMIN && roleOption == Role.USER) "Promote to User" else roleOption.name) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                                containerColor = TacticalSurface,
                                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                disabledContainerColor = TacticalSurface.copy(alpha = 0.5f),
                                                disabledLabelColor = MaterialTheme.colorScheme.outline
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 2: ACCOUNT STATUS
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TacticalCard),
                        border = BorderStroke(1.dp, TacticalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Account Status",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            if (currentUserRole == Role.ADMIN) {
                                // Strictly hide status modification controls for Administrator
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusBadge(status = user.status)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Account status management (Suspend, Disable, Reactivate) is restricted to the Fleet Owner.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    text = "Controls whether this account can access live telemetry.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                val statusOptions = listOf(AccountStatus.ACTIVE, AccountStatus.SUSPENDED, AccountStatus.DISABLED)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    statusOptions.forEach { statusOption ->
                                        val isSelected = user.status == statusOption
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                if (user.status != statusOption) {
                                                    onRequestStatusChange(statusOption)
                                                }
                                            },
                                            label = { Text(statusOption.name) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = when (statusOption) {
                                                    AccountStatus.ACTIVE -> StatusLive.copy(alpha = 0.25f)
                                                    AccountStatus.SUSPENDED -> StatusError.copy(alpha = 0.25f)
                                                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                                },
                                                selectedLabelColor = when (statusOption) {
                                                    AccountStatus.ACTIVE -> StatusLive
                                                    AccountStatus.SUSPENDED -> StatusError
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                },
                                                containerColor = TacticalSurface,
                                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // SECTION 3: DEVICE ASSIGNMENTS & FEATURE PERMISSIONS
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = TacticalCard),
                        border = BorderStroke(1.dp, TacticalBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (user.isAdmin) {
                                // Admin Authority Banner - No manual assignment required
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Full Fleet Access (Admin)",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "All fleet devices and service features automatically granted",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = TacticalSurface,
                                    border = BorderStroke(1.dp, TacticalBorderSubtle),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Administrators inherently possess unrestricted access to all connected fleet devices with all permissions active (Location, Camera, Audio, Screen, Files, Commands). Manual device assignment is not required.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }

                                if (user.assignedDevices.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Fleet Devices (${user.assignedDevices.size}):",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        user.assignedDevices.forEach { asgn ->
                                            AdminDeviceItem(assignment = asgn)
                                        }
                                    }
                                }
                            } else if (user.isVisitor) {
                                // Visitor Sandbox Notice
                                Text(
                                    text = "Device Assignments",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = TacticalSurface,
                                    border = BorderStroke(1.dp, TacticalBorderSubtle),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Visitor Sandbox: Visitors only have access to simulated demo trackers on the Map. To assign real fleet devices and configure permissions, change this user's role to 'USER' or 'ADMIN' above.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            } else {
                                // Standard User: Manual Device Assignment & Granular Permissions
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Device Assignments & Permissions",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "${user.assignedDevices.size} active device assignments",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    TextButton(
                                        onClick = { isAssignPickerOpen = !isAssignPickerOpen },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Icon(
                                            imageVector = if (isAssignPickerOpen) Icons.Default.Close else Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isAssignPickerOpen) "Cancel" else "Assign Device")
                                    }
                                }

                                // Device Assignment Picker dropdown/list
                                AnimatedVisibility(visible = isAssignPickerOpen) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
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

                                        if (unassignedFleetDevices.isEmpty()) {
                                            Text(
                                                text = "No unassigned devices available in fleet.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        } else {
                                            unassignedFleetDevices.forEach { dev ->
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = TacticalCard,
                                                    border = BorderStroke(1.dp, TacticalBorderSubtle),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 3.dp)
                                                        .clickable {
                                                            onAssignDevice(dev.deviceId)
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
                                                                text = com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(dev.name, dev.deviceId),
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                            Text(
                                                                text = "${dev.model} • ${com.livetracker.controller.ui.util.DeviceNameFormatter.formatShortId(dev.deviceId)}",
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
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // List of Assigned Devices with Granular Permission Toggles
                                if (user.assignedDevices.isEmpty()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = TacticalSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "No devices currently assigned to this user.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        user.assignedDevices.forEach { asgn ->
                                            AssignedDeviceItem(
                                                assignment = asgn,
                                                onUnassign = { onUnassignDevice(asgn.deviceId) },
                                                onTogglePermission = { feature, allowed ->
                                                    onToggleFeaturePermission(asgn.assignmentId, feature, allowed, user.userId, asgn.deviceId)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Danger Zone: Delete User Account (Strictly Owner-Only, Not Self, Not Owner)
                if (currentUserRole == Role.OWNER && user.userId != currentUserId && user.role != Role.OWNER) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { onRequestDelete(user) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = StatusError
                        ),
                        border = BorderStroke(1.dp, StatusError.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete User",
                            tint = StatusError,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete User Account",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = StatusError
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Close Button
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalCard,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    border = BorderStroke(1.dp, TacticalBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun AdminDeviceItem(assignment: UserDeviceAssignmentUiState) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = TacticalCardSecondary,
        border = BorderStroke(1.dp, TacticalBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(assignment.deviceName, assignment.deviceId),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = com.livetracker.controller.ui.util.DeviceNameFormatter.formatShortId(assignment.deviceId),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StatusLive.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, StatusLive.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "ALL ACTIVE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                        color = StatusLive,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("LOC", "CAM", "MIC", "SCR", "FILE", "CMD").forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = TacticalSurface,
                        border = BorderStroke(0.5.dp, StatusLive.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "✓ $tag",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                            color = StatusLive,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssignedDeviceItem(
    assignment: UserDeviceAssignmentUiState,
    onUnassign: () -> Unit,
    onTogglePermission: (String, Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = TacticalCardSecondary,
        border = BorderStroke(1.dp, TacticalBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Device header + Unassign Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(assignment.deviceName, assignment.deviceId),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = com.livetracker.controller.ui.util.DeviceNameFormatter.formatShortId(assignment.deviceId),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onUnassign,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Unassign device",
                        tint = StatusError.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 6 Granular Feature Permission Toggles (LOCATION, CAMERA, FILES, MIC, SCREEN, COMMANDS)
            Text(
                text = "Feature Permissions",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                UsersViewModel.ALL_FEATURES.chunked(2).forEach { rowFeatures ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowFeatures.forEach { feature ->
                            val isAllowed = assignment.features[feature] == true
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = TacticalSurface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isAllowed) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else TacticalBorderSubtle
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onTogglePermission(feature, !isAllowed) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = feature,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = if (isAllowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                    )
                                    Switch(
                                        checked = isAllowed,
                                        onCheckedChange = { onTogglePermission(feature, it) },
                                        modifier = Modifier.size(28.dp)
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
