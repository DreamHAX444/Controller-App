package com.livetracker.controller.ui.screens.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.livetracker.controller.ui.theme.LocalDarkTheme
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.tracker.screen.domain.ScreenCaptureStatus
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusOffline
import com.livetracker.controller.ui.util.DeviceNameFormatter
import com.livetracker.controller.webrtc.AndroidWebRtcSessionFactory
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

private data class ScreenColors(
    val screenBg: Color,
    val viewportBg: Color,
    val capsuleBg: Color,
    val capsuleBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textSubtle: Color,
    val deckBg: Color,
    val deckBorder: Color,
)

@Composable
fun ScreenCaptureScreen(
    viewModel: ScreenCaptureViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedQuality by remember { mutableStateOf("BALANCED") }
    val isDark = LocalDarkTheme.current

    val colors = remember(isDark) {
        ScreenColors(
            screenBg = if (isDark) Color(0xFF090C10) else Color(0xFFF8FAFC),
            viewportBg = if (isDark) Color(0xFF0B0E14) else Color(0xFFEDF2F7),
            capsuleBg = if (isDark) Color(0xFF161B22).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.92f),
            capsuleBorder = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f),
            textPrimary = if (isDark) Color.White else Color(0xFF0F172A),
            textSecondary = if (isDark) Color.White.copy(alpha = 0.75f) else Color(0xFF475569),
            textSubtle = if (isDark) Color.White.copy(alpha = 0.40f) else Color(0xFF64748B),
            deckBg = if (isDark) Color(0xFF111620).copy(alpha = 0.90f) else Color.White.copy(alpha = 0.95f),
            deckBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBg)
            .testTag(ScreenCaptureTestTags.SCREEN)
    ) {
        // -------------------------------------------------------------
        // 1. FULL BLEED HERO VIEWPORT
        // -------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.viewportBg)
                .testTag(ScreenCaptureTestTags.VIEWPORT),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.videoTrack != null) {
                WebRtcScreenRenderer(
                    videoTrack = uiState.videoTrack!!,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(28.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.capsuleBg,
                        border = BorderStroke(1.dp, colors.capsuleBorder),
                        modifier = Modifier.size(76.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Screenshot,
                                contentDescription = null,
                                tint = when (uiState.status) {
                                    ScreenCaptureStatus.AWAITING_CONSENT, ScreenCaptureStatus.CONNECTING -> StatusConnecting
                                    ScreenCaptureStatus.ERROR -> StatusError
                                    else -> if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF0F172A)
                                },
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = when (uiState.status) {
                            ScreenCaptureStatus.AWAITING_CONSENT -> "Awaiting Tracker Consent"
                            ScreenCaptureStatus.CONNECTING -> "Connecting WebRTC Stream..."
                            ScreenCaptureStatus.ERROR -> "Screen Capture Error"
                            else -> "Remote Screen Capture"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        ),
                        color = colors.textPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = when (uiState.status) {
                            ScreenCaptureStatus.AWAITING_CONSENT -> "Prompt requires user confirmation on device"
                            ScreenCaptureStatus.CONNECTING -> "Synchronizing peer media channels"
                            ScreenCaptureStatus.ERROR -> (uiState.errorMessage ?: "Hardware encoder unavailable")
                            else -> "Encrypted WebRTC H.264 / VP8 Stream"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Left-swipe guidance pill
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colors.capsuleBg,
                        border = BorderStroke(0.75.dp, colors.capsuleBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = null,
                                tint = colors.textSubtle,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Swipe from left edge to open menu",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = colors.textSubtle
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // 2. TOP FLOATING BAR (BEZEL & PUNCH-HOLE SAFE)
        // -------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 14.dp, start = 22.dp, end = 22.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tracker Capsule with Dropdown
                ScreenTrackerSelectorCapsule(
                    uiState = uiState,
                    colors = colors,
                    onSelectTracker = { viewModel.selectTracker(it) }
                )

                // Top Status Pill
                ScreenStatusPill(
                    status = uiState.status,
                    colors = colors
                )
            }

            // Error Banner (Floats right below top row if present)
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
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusError,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearError() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = StatusError,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // 3. FLOATING BOTTOM CONTROLS DECK
        // -------------------------------------------------------------
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = colors.deckBg,
            border = BorderStroke(1.dp, colors.deckBorder),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Quality Selector Row
                ScreenQualityChipRow(
                    selectedQuality = selectedQuality,
                    onQualitySelect = { selectedQuality = it },
                    colors = colors
                )

                // Tactile Pro Action Trigger (Start / Stop button)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val isRunning = uiState.isStreaming ||
                        uiState.status == ScreenCaptureStatus.AWAITING_CONSENT ||
                        uiState.status == ScreenCaptureStatus.CONNECTING

                    TactileScreenTrigger(
                        isStreaming = isRunning,
                        isOperating = uiState.isActionPending,
                        enabled = uiState.deviceId.isNotEmpty() && !uiState.isActionPending,
                        ringColorBase = if (isDark) Color.White else Color(0xFF0F172A),
                        onClick = {
                            if (isRunning) {
                                viewModel.stopScreenCapture()
                            } else {
                                viewModel.startScreenCapture(selectedQuality)
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Floating device selector capsule for Screen Capture.
 */
@Composable
private fun ScreenTrackerSelectorCapsule(
    uiState: ScreenCaptureUiState,
    colors: ScreenColors,
    onSelectTracker: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val friendlyTarget = DeviceNameFormatter.formatFriendlyName(uiState.displayName, uiState.deviceId)

    val shortName = remember(friendlyTarget) {
        val bracketMatch = Regex("""\(([^)]+)\)""").find(friendlyTarget)
        val shortId = bracketMatch?.value ?: ""
        val prefix = friendlyTarget.replace(Regex("""\s*\([^)]+\)"""), "").trim()
        val shortPrefix = prefix.split(" ").firstOrNull() ?: prefix
        if (shortId.isNotEmpty() && shortPrefix.isNotEmpty()) {
            "$shortPrefix $shortId"
        } else {
            DeviceNameFormatter.formatFriendlyName(friendlyTarget, maxLength = 12)
        }
    }

    Box {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = colors.capsuleBg,
            border = BorderStroke(0.85.dp, colors.capsuleBorder),
            modifier = Modifier
                .widthIn(max = 160.dp)
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val isOnline = uiState.availableTrackers.find { it.deviceId.value == uiState.deviceId }?.isOnline ?: (uiState.deviceId.isNotEmpty())
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            color = if (isOnline) StatusLive else StatusOffline,
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (uiState.deviceId.isNotEmpty()) shortName else "Select device",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(ScreenCaptureTestTags.DEVICE_HEADER)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select Device",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.capsuleBg)
        ) {
            if (uiState.availableTrackers.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No trackers available", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { expanded = false },
                    enabled = false
                )
            } else {
                uiState.availableTrackers.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(if (option.isOnline) StatusLive else StatusOffline, CircleShape)
                                )
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (option.deviceId.value == uiState.deviceId) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = colors.textPrimary
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelectTracker(option.deviceId.value)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Status pill for screen streaming.
 */
@Composable
private fun ScreenStatusPill(
    status: ScreenCaptureStatus,
    colors: ScreenColors
) {
    val livePulse = rememberInfiniteTransition(label = "screen_live_pulse")
    val liveDotAlpha by livePulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotAlpha"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.capsuleBg,
        border = BorderStroke(0.85.dp, colors.capsuleBorder),
        modifier = Modifier.testTag(ScreenCaptureTestTags.STATUS_BADGE)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = when (status) {
                            ScreenCaptureStatus.STREAMING -> StatusLive.copy(alpha = liveDotAlpha)
                            ScreenCaptureStatus.CONNECTING, ScreenCaptureStatus.AWAITING_CONSENT -> StatusConnecting
                            ScreenCaptureStatus.ERROR -> StatusError
                            else -> StatusOffline
                        },
                        shape = CircleShape
                    )
            )
            Text(
                text = when (status) {
                    ScreenCaptureStatus.STREAMING -> "LIVE SCREEN"
                    ScreenCaptureStatus.AWAITING_CONSENT -> "CONSENT PENDING"
                    ScreenCaptureStatus.CONNECTING -> "CONNECTING"
                    ScreenCaptureStatus.ERROR -> "ERROR"
                    ScreenCaptureStatus.STOPPED -> "STOPPED"
                    ScreenCaptureStatus.IDLE -> "STANDBY"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = when (status) {
                    ScreenCaptureStatus.STREAMING -> StatusLive
                    ScreenCaptureStatus.CONNECTING, ScreenCaptureStatus.AWAITING_CONSENT -> StatusConnecting
                    ScreenCaptureStatus.ERROR -> StatusError
                    else -> colors.textSecondary
                }
            )
        }
    }
}

/**
 * Quality selection chip row for screen capture.
 */
@Composable
private fun ScreenQualityChipRow(
    selectedQuality: String,
    onQualitySelect: (String) -> Unit,
    colors: ScreenColors
) {
    val profiles = listOf(
        Triple("BALANCED", "Balanced", ScreenCaptureTestTags.QUALITY_CHIP_BALANCED),
        Triple("HIGH_QUALITY", "High", ScreenCaptureTestTags.QUALITY_CHIP_HIGH),
        Triple("BANDWIDTH_SAVER", "Saver", ScreenCaptureTestTags.QUALITY_CHIP_SAVER)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "PROFILE",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            ),
            color = colors.textSubtle
        )

        profiles.forEach { (key, label, tag) ->
            val isSelected = selectedQuality == key
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) colors.textPrimary else colors.capsuleBg,
                border = BorderStroke(0.75.dp, if (isSelected) colors.textPrimary else colors.capsuleBorder),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onQualitySelect(key) }
                    .testTag(tag)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 10.sp
                    ),
                    color = if (isSelected) colors.screenBg else colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/**
 * Tactile Pro Screen Trigger (Start / Stop action).
 */
@Composable
private fun TactileScreenTrigger(
    isStreaming: Boolean,
    isOperating: Boolean,
    enabled: Boolean,
    ringColorBase: Color,
    onClick: () -> Unit
) {
    val ringColor by animateColorAsState(
        targetValue = if (isStreaming) StatusError else ringColorBase.copy(alpha = 0.85f),
        animationSpec = tween(220),
        label = "screenRingColor"
    )
    val coreColor by animateColorAsState(
        targetValue = if (isStreaming) StatusError else Color(0xFF2563EB),
        animationSpec = tween(220),
        label = "screenCoreColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .testTag(ScreenCaptureTestTags.TOGGLE_BUTTON)
    ) {
        // Outer concentric ring
        Canvas(modifier = Modifier.size(72.dp)) {
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - 1.5.dp.toPx(),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Inner solid tactile core
        Surface(
            shape = if (isStreaming) RoundedCornerShape(10.dp) else CircleShape,
            color = coreColor,
            modifier = Modifier.size(if (isStreaming) 26.dp else 54.dp)
        ) {
            if (isOperating) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (isStreaming) 18.dp else 26.dp)
                    )
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
            update = { _ -> },
            onRelease = { renderer ->
                try {
                    videoTrack.removeSink(renderer)
                    renderer.release()
                } catch (_: Exception) {}
            }
        )
    }
}
