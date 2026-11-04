package com.livetracker.controller.ui.screens.map.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private data class GestureCoachStep(
    val badge: String,
    val title: String,
    val description: String,
    val gestureHint: String,
    val icon: ImageVector,
    val horizontalMotion: Boolean = true
)

@Composable
fun GestureOnboardingOverlay(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = remember {
        listOf(
            GestureCoachStep(
                badge = "GESTURE 1 OF 4",
                title = "Dynamic Island Tracker Bar",
                description = "Tap the top capsule to switch between All Trackers, Single, or Multi-Select modes. Swipe left or right directly on the capsule to cycle through active devices.",
                gestureHint = "Swipe ⇄ on Top Capsule",
                icon = Icons.Default.LocationOn,
                horizontalMotion = true
            ),
            GestureCoachStep(
                badge = "GESTURE 2 OF 4",
                title = "Swipeable Telemetry Deck",
                description = "Swipe left or right on the bottom telemetry card to glide the map camera between your selected trackers. Unselected devices are automatically filtered out.",
                gestureHint = "Swipe ⇄ on Bottom Card",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                horizontalMotion = true
            ),
            GestureCoachStep(
                badge = "GESTURE 3 OF 4",
                title = "Quick Zoom & Card Dismiss",
                description = "Double-tap the telemetry card to deep-zoom onto the focused tracker, or swipe down on the card to collapse it for a 100% unobstructed map view.",
                gestureHint = "Double-Tap or Swipe ↓",
                icon = Icons.Default.Refresh,
                horizontalMotion = false
            ),
            GestureCoachStep(
                badge = "GESTURE 4 OF 4",
                title = "Fast Alert & Drawer Gestures",
                description = "Swipe right on any unacknowledged alert in the Alerts tab to acknowledge it immediately, or swipe from the left edge to open the Command Sidebar.",
                gestureHint = "Swipe → to Acknowledge",
                icon = Icons.Default.Notifications,
                horizontalMotion = true
            )
        )
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    val step = steps[currentIndex]

    val infiniteTransition = rememberInfiniteTransition(label = "gestureCoachMotion")
    val motionOffset by infiniteTransition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gestureMotionOffset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* Intercept background taps */ },
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = step.badge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    IconButton(
                        onClick = onComplete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Skip Gesture Guide",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Animated Gesture Demonstration Stage
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .offset {
                                    if (step.horizontalMotion) {
                                        IntOffset(motionOffset.roundToInt(), 0)
                                    } else {
                                        IntOffset(0, (motionOffset * 0.6f).roundToInt())
                                    }
                                }
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = step.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = step.gestureHint,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedContent(targetState = step, label = "coachStepText") { targetStep ->
                    Column {
                        Text(
                            text = targetStep.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = targetStep.description,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Progress Indicators + Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in steps.indices) {
                            val active = i == currentIndex
                            Box(
                                modifier = Modifier
                                    .height(6.dp)
                                    .width(if (active) 20.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    )
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (currentIndex > 0) {
                            OutlinedButton(
                                onClick = { currentIndex-- },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Back")
                            }
                        } else {
                            OutlinedButton(
                                onClick = onComplete,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Skip")
                            }
                        }

                        Button(
                            onClick = {
                                if (currentIndex < steps.lastIndex) {
                                    currentIndex++
                                } else {
                                    onComplete()
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (currentIndex == steps.lastIndex) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Got It")
                            } else {
                                Text("Next")
                            }
                        }
                    }
                }
            }
        }
    }
}
