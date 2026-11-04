package com.livetracker.controller.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError

/**
 * Dedicated tactical screen displayed when an authenticated user has status PENDING.
 */
@Composable
fun AccountPendingApprovalScreen(
    onCheckStatus: () -> Unit,
    onSignOut: () -> Unit,
    userEmail: String? = null
) {
    LifecycleStatusScreen(
        icon = Icons.Default.HourglassTop,
        accentColor = StatusConnecting,
        title = "APPROVAL PENDING",
        subtitle = "ACCESS REQUEST SUBMITTED",
        description = "Your account has been registered with the TRACE fleet backend. Access requires approval and device assignment from a fleet administrator.",
        detailLabel = userEmail?.let { "Authenticated Account: $it" },
        primaryActionText = "Check Status",
        onPrimaryAction = onCheckStatus,
        secondaryActionText = "Sign Out",
        onSecondaryAction = onSignOut
    )
}

/**
 * Dedicated tactical screen displayed when an authenticated user has status DISABLED.
 */
@Composable
fun AccountDisabledScreen(
    onSignOut: () -> Unit
) {
    LifecycleStatusScreen(
        icon = Icons.Default.Lock,
        accentColor = StatusError,
        title = "ACCOUNT DISABLED",
        subtitle = "AUTHORIZATION REVOKED",
        description = "This account has been deactivated by a system administrator. Fleet telemetry and hardware commands are inaccessible.",
        primaryActionText = "Sign Out",
        onPrimaryAction = onSignOut
    )
}

/**
 * Dedicated tactical screen displayed when an authenticated user has status SUSPENDED.
 */
@Composable
fun AccountSuspendedScreen(
    onSignOut: () -> Unit
) {
    LifecycleStatusScreen(
        icon = Icons.Default.Warning,
        accentColor = StatusConnecting,
        title = "ACCOUNT SUSPENDED",
        subtitle = "TEMPORARY SECURITY HOLD",
        description = "Access to the TRACE operational network has been temporarily suspended. Please verify your credentials or contact fleet administration.",
        primaryActionText = "Sign Out",
        onPrimaryAction = onSignOut
    )
}

@Composable
private fun LifecycleStatusScreen(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    subtitle: String,
    description: String,
    detailLabel: String? = null,
    primaryActionText: String,
    onPrimaryAction: () -> Unit,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = accentColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                tint = accentColor,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = 1.2.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        letterSpacing = 1.5.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )

                    if (detailLabel != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = detailLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = onPrimaryAction,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = primaryActionText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }

                    if (secondaryActionText != null && onSecondaryAction != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(
                                text = secondaryActionText,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
