package com.livetracker.controller.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.ui.components.UserProfileAvatar
import com.livetracker.controller.ui.theme.StatusActive
import com.livetracker.controller.ui.theme.StatusWarning
import com.livetracker.controller.ui.util.DeviceNameFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    currentUser: User?,
    onRefreshPermissions: (suspend () -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var uidCopied by remember { mutableStateOf(false) }
    var isSyncingPermissions by remember { mutableStateOf(false) }
    var syncStatusText by remember { mutableStateOf<String?>(null) }

    val role = currentUser?.role ?: Role.VISITOR
    val status = currentUser?.accountStatus ?: AccountStatus.ACTIVE
    val displayName = currentUser?.displayName?.ifBlank { "Operator" } ?: "Operator"
    val email = currentUser?.email?.takeIf { it.isNotBlank() } ?: "$displayName@trace.local"
    val userId = currentUser?.id ?: "local-session"

    val roleDescription = when (role) {
        Role.OWNER -> "Supreme Fleet Owner • Full access to all trackers, live streams, user roles, and system audit logs."
        Role.ADMIN -> "Fleet Administrator • Full access to trackers, live streams, device assignments, and user approvals."
        Role.USER -> "Field Operator • Real-time access to assigned trackers and explicitly granted remote services."
        Role.VISITOR -> "Sandbox Preview • Isolated demo environment. Request approval from an Administrator for live fleet access."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Operator Identity Hero Card
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    UserProfileAvatar(
                        avatarUrl = currentUser?.avatarUrl,
                        displayName = displayName,
                        role = role,
                        size = 64.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = role.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            val statusColor = if (status == AccountStatus.ACTIVE) StatusActive else StatusWarning
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = statusColor.copy(alpha = 0.16f),
                                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = status.name,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = statusColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )

                Text(
                    text = roleDescription,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Operator UID Copy Row
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OPERATOR UID",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = userId,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Operator UID", userId))
                                uidCopied = true
                                coroutineScope.launch {
                                    delay(2000L)
                                    uidCopied = false
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.heightIn(min = 36.dp)
                        ) {
                            Text(
                                text = if (uidCopied) "Copied" else "Copy UID",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }

        // 2. Live Effective Permissions & Assigned Fleet Matrix
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live Access & Clearance",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (onRefreshPermissions != null) {
                        OutlinedButton(
                            onClick = {
                                if (!isSyncingPermissions) {
                                    isSyncingPermissions = true
                                    syncStatusText = null
                                    coroutineScope.launch {
                                        onRefreshPermissions.invoke()
                                        isSyncingPermissions = false
                                        syncStatusText = "Permissions synced live with cloud"
                                        delay(3000L)
                                        syncStatusText = null
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.heightIn(min = 36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync Permissions",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isSyncingPermissions) "Syncing..." else "Sync Now",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }

                if (syncStatusText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = syncStatusText ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusActive
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val permissions = currentUser?.devicePermissions.orEmpty()
                if (role == Role.OWNER || role == Role.ADMIN) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Unrestricted Administrative Access",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Authorized for all fleet trackers (${permissions.size} active) and all remote telemetry services.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (permissions.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (role == Role.VISITOR) {
                                "Visitor mode active. No cloud trackers assigned."
                            } else {
                                "No trackers currently assigned to your account. Contact an Administrator to request device assignment."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        permissions.forEach { devPerm ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Phone,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = DeviceNameFormatter.formatFriendlyName(devPerm.deviceId, devPerm.deviceId),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        devPerm.allowedServices.forEach { svc ->
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    text = svc.name,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    ),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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

        // 3. Quick Actions Card (Settings & Sign Out)
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (onOpenSettings != null) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Theme, Map & System Settings", fontWeight = FontWeight.SemiBold)
                    }
                }

                if (onLogout != null) {
                    Button(
                        onClick = onLogout,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign Out of Session", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
