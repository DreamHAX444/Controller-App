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
        accentColor = Color(0xFFFFB800),
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
        accentColor = Color(0xFFCF6679),
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
        accentColor = Color(0xFFFF9800),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = accentColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.2.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
                letterSpacing = 1.5.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0B0B0),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            if (detailLabel != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF1E1E1E),
                    border = BorderStroke(1.dp, Color(0xFF333333))
                ) {
                    Text(
                        text = detailLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF888888),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onPrimaryAction,
                shape = RoundedCornerShape(8.dp),
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
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF555555)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = secondaryActionText,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
