package com.livetracker.controller.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Prominent tactical banner displayed when an authenticated user has the VISITOR role.
 * Enforces UX transparency that the environment is a simulated sandbox.
 * Includes an accessible "Check Access" action so promoted users can seamlessly transition
 * to real fleet streaming without requiring manual logout/re-login.
 */
@Composable
fun VisitorDemoBanner(
    onRefreshAccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF231C08),
        border = BorderStroke(1.dp, Color(0xFFFFB800))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Demo Mode",
                tint = Color(0xFFFFB800),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VISITOR SANDBOX — DEMO MODE",
                    color = Color(0xFFFFB800),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Simulated synthetic trackers only. Contact Admin for fleet assignment.",
                    color = Color(0xFFE5D5AA),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onRefreshAccess,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, Color(0xFFFFB800).copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFFB800)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh Access",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Check",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
