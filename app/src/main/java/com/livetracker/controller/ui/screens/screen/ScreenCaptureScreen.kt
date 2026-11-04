package com.livetracker.controller.ui.screens.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureStatus
import com.livetracker.controller.webrtc.AndroidWebRtcSessionFactory
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusOffline
import com.livetracker.controller.ui.util.DeviceNameFormatter
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

object ScreenCaptureTestTags {
    const val SCREEN = "screen_capture_screen"
    const val DEVICE_HEADER = "screen_capture_device_header"
    const val STATUS_BADGE = "screen_capture_status_badge"
    const val TOGGLE_BUTTON = "screen_capture_toggle_button"
    const val VIEWPORT = "screen_capture_viewport"
    const val ERROR_BANNER = "screen_capture_error_banner"
    const val QUALITY_CHIP_BALANCED = "quality_chip_balanced"
    const val QUALITY_CHIP_HIGH = "quality_chip_high"
    const val QUALITY_CHIP_SAVER = "quality_chip_saver"
}

@Composable
fun ScreenCaptureScreen(
    viewModel: ScreenCaptureViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedQuality by remember { mutableStateOf("BALANCED") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val friendlyTarget = DeviceNameFormatter.formatFriendlyName(uiState.displayName, uiState.deviceId)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(ScreenCaptureTestTags.SCREEN)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Target Tracker Status Bar
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Screenshot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "TARGET TRACKER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box {
                    OutlinedButton(
                        onClick = { dropdownExpanded = true },
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.sizeIn(minHeight = 40.dp)
                    ) {
                        val isOnline = uiState.availableTrackers.find { it.deviceId.value == uiState.deviceId }?.isOnline ?: (uiState.deviceId.isNotEmpty())
                        val dotColor = if (isOnline) StatusLive else StatusOffline
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(dotColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.deviceId.isNotEmpty()) friendlyTarget else "Select device",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(ScreenCaptureTestTags.DEVICE_HEADER)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select tracker",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        if (uiState.availableTrackers.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No trackers available", style = MaterialTheme.typography.bodyMedium) },
                                onClick = { dropdownExpanded = false },
                                enabled = false
                            )
                        } else {
                            uiState.availableTrackers.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            val itemDotColor = if (option.isOnline) StatusLive else StatusOffline
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(itemDotColor, CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = option.displayName,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (option.deviceId.value == uiState.deviceId) FontWeight.Bold else FontWeight.Normal
                                                )
                                            )
                                            Spacer(modifier = Modifier.weight(1f))
                                            if (option.isScreenSupported) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    modifier = Modifier.padding(start = 8.dp)
                                                ) {
                                                    Text(
                                                        text = "SCR",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        dropdownExpanded = false
                                        viewModel.selectTracker(option.deviceId.value)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Error banner
        AnimatedVisibility(
            visible = uiState.errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = StatusError.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, StatusError.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ScreenCaptureTestTags.ERROR_BANNER)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = StatusError,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusError,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = StatusError
                        )
                    }
                }
            }
        }

        // Top Status Pill
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when (uiState.status) {
                ScreenCaptureStatus.STREAMING -> StatusLive.copy(alpha = 0.12f)
                ScreenCaptureStatus.CONNECTING, ScreenCaptureStatus.AWAITING_CONSENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ScreenCaptureStatus.ERROR -> StatusError.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
            },
            border = BorderStroke(
                1.dp,
                when (uiState.status) {
                    ScreenCaptureStatus.STREAMING -> StatusLive.copy(alpha = 0.35f)
                    ScreenCaptureStatus.CONNECTING, ScreenCaptureStatus.AWAITING_CONSENT -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    ScreenCaptureStatus.ERROR -> StatusError.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                }
            ),
            modifier = Modifier.testTag(ScreenCaptureTestTags.STATUS_BADGE)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = when (uiState.status) {
                                ScreenCaptureStatus.STREAMING -> StatusLive
                                ScreenCaptureStatus.CONNECTING, ScreenCaptureStatus.AWAITING_CONSENT -> MaterialTheme.colorScheme.primary
                                ScreenCaptureStatus.ERROR -> StatusError
                                else -> StatusOffline
                            },
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (uiState.status) {
                        ScreenCaptureStatus.STREAMING -> "LIVE SCREEN STREAM ACTIVE"
                        ScreenCaptureStatus.AWAITING_CONSENT -> "AWAITING TRACKER CONSENT..."
                        ScreenCaptureStatus.CONNECTING -> "ESTABLISHING WEBRTC PIPELINE..."
                        ScreenCaptureStatus.ERROR -> "SCREEN CAPTURE ERROR"
                        ScreenCaptureStatus.STOPPED -> "SCREEN CAPTURE STOPPED"
                        ScreenCaptureStatus.IDLE -> "SCREEN STANDBY"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = when (uiState.status) {
                        ScreenCaptureStatus.STREAMING -> StatusLive
                        ScreenCaptureStatus.CONNECTING, ScreenCaptureStatus.AWAITING_CONSENT -> MaterialTheme.colorScheme.primary
                        ScreenCaptureStatus.ERROR -> StatusError
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // Center Viewport Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .testTag(ScreenCaptureTestTags.VIEWPORT),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0F172A)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.videoTrack != null) {
                    WebRtcScreenRenderer(videoTrack = uiState.videoTrack!!)
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Screenshot,
                            contentDescription = null,
                            tint = if (uiState.status == ScreenCaptureStatus.AWAITING_CONSENT) StatusConnecting else Color(0xFF94A3B8),
                            modifier = Modifier.size(60.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = when (uiState.status) {
                                ScreenCaptureStatus.AWAITING_CONSENT -> "User Prompt Displayed on Tracker"
                                ScreenCaptureStatus.CONNECTING -> "Negotiating WebRTC Video Track..."
                                else -> "Remote Screen Capture Stream"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when (uiState.status) {
                                ScreenCaptureStatus.AWAITING_CONSENT -> "Prompt requires user tap on physical tracker device"
                                ScreenCaptureStatus.CONNECTING -> "P2P Data & MediaChannel synchronization in progress"
                                else -> "Encrypted WebRTC H.264 / VP8 hardware video pipeline"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        // Bottom Quality & Control Panel Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Quality Profile Selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quality:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilterChip(
                        selected = selectedQuality == "BALANCED",
                        onClick = { selectedQuality = "BALANCED" },
                        label = { Text("Balanced") },
                        modifier = Modifier.testTag(ScreenCaptureTestTags.QUALITY_CHIP_BALANCED)
                    )

                    FilterChip(
                        selected = selectedQuality == "HIGH_QUALITY",
                        onClick = { selectedQuality = "HIGH_QUALITY" },
                        label = { Text("High") },
                        modifier = Modifier.testTag(ScreenCaptureTestTags.QUALITY_CHIP_HIGH)
                    )

                    FilterChip(
                        selected = selectedQuality == "BANDWIDTH_SAVER",
                        onClick = { selectedQuality = "BANDWIDTH_SAVER" },
                        label = { Text("Saver") },
                        modifier = Modifier.testTag(ScreenCaptureTestTags.QUALITY_CHIP_SAVER)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary Start/Stop button
                Button(
                    onClick = {
                        if (uiState.isStreaming || uiState.status == ScreenCaptureStatus.AWAITING_CONSENT || uiState.status == ScreenCaptureStatus.CONNECTING) {
                            viewModel.stopScreenCapture()
                        } else {
                            viewModel.startScreenCapture(selectedQuality)
                        }
                    },
                    enabled = uiState.deviceId.isNotEmpty() && !uiState.isActionPending,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isStreaming || uiState.status == ScreenCaptureStatus.AWAITING_CONSENT || uiState.status == ScreenCaptureStatus.CONNECTING) {
                            StatusError
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(ScreenCaptureTestTags.TOGGLE_BUTTON)
                ) {
                    if (uiState.isActionPending) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (uiState.isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.isStreaming || uiState.status == ScreenCaptureStatus.AWAITING_CONSENT || uiState.status == ScreenCaptureStatus.CONNECTING) {
                                "STOP SCREEN CAPTURE"
                            } else {
                                "START SCREEN CAPTURE"
                            },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WebRtcScreenRenderer(
    videoTrack: VideoTrack,
    modifier: Modifier = Modifier
) {
    key(videoTrack) {
        AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(AndroidWebRtcSessionFactory.sharedEglBase.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    videoTrack.addSink(this)
                }
            },
            update = { renderer ->
                // Update sink if needed
            },
            onRelease = { renderer ->
                try {
                    videoTrack.removeSink(renderer)
                    renderer.release()
                } catch (_: Exception) {}
            }
        )
    }
}
