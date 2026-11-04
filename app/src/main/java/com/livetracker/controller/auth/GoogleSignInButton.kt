package com.livetracker.controller.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standard Google 'G' brand icon rendered via vector canvas geometry.
 * Complies with official Google Brand Guidelines:
 * Blue (#4285F4), Green (#34A853), Yellow (#FBBC05), Red (#EA4335).
 */
@Composable
fun GoogleLogo(modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier = modifier) {
        val size = this.size.minDimension
        val center = Offset(size / 2f, size / 2f)
        val radius = size / 2f
        val innerRadius = radius * 0.58f

        // Blue horizontal crossbar and right arc
        val bluePath = Path().apply {
            moveTo(center.x, center.y - innerRadius * 0.25f)
            lineTo(size * 0.96f, center.y - innerRadius * 0.25f)
            arcTo(
                rect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 45f,
                forceMoveTo = false
            )
            lineTo(center.x, center.y + innerRadius * 0.5f)
            close()
        }
        drawPath(bluePath, color = Color(0xFF4285F4), style = Fill)

        // Green bottom arc
        val greenPath = Path().apply {
            arcTo(
                rect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                startAngleDegrees = 45f,
                sweepAngleDegrees = 90f,
                forceMoveTo = true
            )
            arcTo(
                rect = Rect(center.x - innerRadius, center.y - innerRadius, center.x + innerRadius, center.y + innerRadius),
                startAngleDegrees = 135f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false
            )
            close()
        }
        drawPath(greenPath, color = Color(0xFF34A853), style = Fill)

        // Yellow left arc
        val yellowPath = Path().apply {
            arcTo(
                rect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                startAngleDegrees = 135f,
                sweepAngleDegrees = 90f,
                forceMoveTo = true
            )
            arcTo(
                rect = Rect(center.x - innerRadius, center.y - innerRadius, center.x + innerRadius, center.y + innerRadius),
                startAngleDegrees = 225f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false
            )
            close()
        }
        drawPath(yellowPath, color = Color(0xFFFBBC05), style = Fill)

        // Red top arc
        val redPath = Path().apply {
            arcTo(
                rect = Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                startAngleDegrees = 225f,
                sweepAngleDegrees = 90f,
                forceMoveTo = true
            )
            arcTo(
                rect = Rect(center.x - innerRadius, center.y - innerRadius, center.x + innerRadius, center.y + innerRadius),
                startAngleDegrees = 315f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false
            )
            close()
        }
        drawPath(redPath, color = Color(0xFFEA4335), style = Fill)
    }
}

/**
 * Production Google Sign-In button adhering to Google Brand Guidelines
 * and Trace dark tactical theme (UI/UX Pro Max).
 */
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Authenticating...",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                GoogleLogo(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Sign in with Google",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
