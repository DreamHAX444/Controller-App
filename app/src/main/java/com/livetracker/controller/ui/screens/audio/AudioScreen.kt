package com.livetracker.controller.ui.screens.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.tracker.audio.domain.AudioStatus
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusOffline
import com.livetracker.controller.ui.util.DeviceNameFormatter

object AudioTestTags {
    const val SCREEN = "audio_screen"
    const val DEVICE_HEADER = "audio_device_header"
    const val TRACKER_SELECTOR = "audio_tracker_selector"
    const val STATUS_BADGE = "audio_status_badge"
    const val TOGGLE_BUTTON = "audio_toggle_button"
    const val VOLUME_SLIDER = "audio_volume_slider"
    const val MUTE_BUTTON = "audio_mute_button"
    const val WAVEFORM_CARD = "audio_waveform_card"
    const val ERROR_BANNER = "audio_error_banner"
    const val QUALITY_CHIP_AUTO = "audio_quality_chip_auto"
    const val QUALITY_CHIP_HIGH = "audio_quality_chip_high"
    const val QUALITY_CHIP_MEDIUM = "audio_quality_chip_medium"
    const val QUALITY_CHIP_LOW = "audio_quality_chip_low"
}

private data class AudioColors(
    val screenBg: Color,
    val capsuleBg: Color,
    val capsuleBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textSubtle: Color,
    val waveActive: Color,
    val waveAmbient: Color,
    val deckBg: Color,
    val deckBorder: Color,
)

@Composable
fun AudioScreen(
    viewModel: AudioViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = LocalDarkTheme.current

    val colors = remember(isDark) {
        AudioColors(
            screenBg = if (isDark) Color(0xFF090C10) else Color(0xFFF8FAFC),
            capsuleBg = if (isDark) Color(0xFF161B22).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.92f),
            capsuleBorder = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f),
            textPrimary = if (isDark) Color.White else Color(0xFF0F172A),
            textSecondary = if (isDark) Color.White.copy(alpha = 0.75f) else Color(0xFF475569),
            textSubtle = if (isDark) Color.White.copy(alpha = 0.40f) else Color(0xFF64748B),
            waveActive = Color(0xFF10B981),
            waveAmbient = if (isDark) Color(0xFF1E293B) else Color(0xFFCBD5E1),
            deckBg = if (isDark) Color(0xFF111620).copy(alpha = 0.90f) else Color.White.copy(alpha = 0.95f),
            deckBorder = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBg)
            .testTag(AudioTestTags.SCREEN)
    ) {
        // -------------------------------------------------------------
        // 1. TOP FLOATING BAR (BEZEL & PUNCH-HOLE SAFE)
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
                AudioTrackerSelectorCapsule(
                    uiState = uiState,
                    colors = colors,
                    onSelectTracker = { viewModel.selectTracker(it) }
                )

                // Top Status Pill
                AudioStatusPill(
                    status = uiState.status,
                    colors = colors
                )
            }

            // Error Banner (Floats right below header if error occurs)
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
                        .testTag(AudioTestTags.ERROR_BANNER)
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
        // 2. CENTER ORGANIC VISUALIZER (NO HEAVY BOX / CLEAN ACOUSTIC CANVAS)
        // -------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .testTag(AudioTestTags.WAVEFORM_CARD),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isStreaming) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Codec & Level Telemetry Pill
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = colors.capsuleBg,
                            border = BorderStroke(0.75.dp, colors.capsuleBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(StatusLive, CircleShape)
                                )
                                Text(
                                    text = "PCM 8kHz MONO",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = colors.textPrimary
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = colors.capsuleBg,
                            border = BorderStroke(0.75.dp, colors.capsuleBorder)
                        ) {
                            Text(
                                text = if (uiState.isMuted) "MUTED" else "LVL ${(uiState.amplitude * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = if (uiState.isMuted) StatusOffline else StatusLive,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    // Dynamic Organic Audio Waves (Canvas)
                    AudioVisualizerOrganicWave(
                        waveformBars = uiState.waveformBars,
                        amplitude = uiState.amplitude,
                        isMuted = uiState.isMuted,
                        waveColor = colors.waveActive,
                        ambientColor = colors.waveAmbient
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = if (uiState.isMuted) "Local audio output muted • Microphone feed active"
                        else "Realtime low-latency stream • Direct PCM transport",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = colors.textSubtle,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Standby Acoustic View
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.capsuleBg,
                        border = BorderStroke(1.dp, colors.capsuleBorder),
                        modifier = Modifier.size(76.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF0F172A),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Realtime Audio Monitoring",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        ),
                        color = colors.textPrimary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Encrypted 8kHz PCM 16-Bit Mono Feed",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Helpful navigation hint
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
                AudioQualityChipRow(
                    selectedQuality = uiState.selectedQuality,
                    onQualitySelect = { viewModel.setQuality(it) },
                    colors = colors
                )

                // Volume slider row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.toggleMute() },
                        modifier = Modifier.testTag(AudioTestTags.MUTE_BUTTON)
                    ) {
                        Icon(
                            imageVector = if (uiState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (uiState.isMuted) "Unmute" else "Mute",
                            tint = if (uiState.isMuted) StatusError else colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Slider(
                        value = if (uiState.isMuted) 0f else uiState.volume,
                        onValueChange = { viewModel.setVolume(it) },
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(AudioTestTags.VOLUME_SLIDER),
                        colors = SliderDefaults.colors(
                            thumbColor = if (isDark) Color.White else Color(0xFF0F172A),
                            activeTrackColor = if (isDark) Color.White else Color(0xFF0F172A),
                            inactiveTrackColor = colors.waveAmbient
                        )
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "${((if (uiState.isMuted) 0f else uiState.volume) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = colors.textPrimary,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.End
                    )
                }

                // Tactile Pro Action Trigger (Start/Stop Audio Button)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    TactileAudioTrigger(
                        isStreaming = uiState.isStreaming,
                        isOperating = uiState.isActionPending,
                        enabled = uiState.deviceId.isNotEmpty() && !uiState.isActionPending,
                        ringColorBase = if (isDark) Color.White else Color(0xFF0F172A),
                        onClick = {
                            if (uiState.isStreaming) viewModel.stopAudio() else viewModel.startAudio()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Sleek floating device selector capsule with dropdown.
 */
@Composable
private fun AudioTrackerSelectorCapsule(
    uiState: AudioUiState,
    colors: AudioColors,
    onSelectTracker: (TrackerId) -> Unit
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
                .testTag(AudioTestTags.TRACKER_SELECTOR)
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
                    modifier = Modifier.testTag(AudioTestTags.DEVICE_HEADER)
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
                            onSelectTracker(option.deviceId)
                        }
                    )
                }
            }
        }
    }
}

/**
 * Top floating status pill with animated live pulse.
 */
@Composable
private fun AudioStatusPill(
    status: AudioStatus,
    colors: AudioColors
) {
    val livePulse = rememberInfiniteTransition(label = "audio_live_pulse")
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
        modifier = Modifier.testTag(AudioTestTags.STATUS_BADGE)
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
                            AudioStatus.STREAMING -> StatusLive.copy(alpha = liveDotAlpha)
                            AudioStatus.CONNECTING -> StatusConnecting
                            AudioStatus.ERROR -> StatusError
                            else -> StatusOffline
                        },
                        shape = CircleShape
                    )
            )
            Text(
                text = when (status) {
                    AudioStatus.STREAMING -> "LIVE MIC"
                    AudioStatus.CONNECTING -> "CONNECTING"
                    AudioStatus.ERROR -> "ERROR"
                    AudioStatus.STOPPED -> "STOPPED"
                    AudioStatus.IDLE -> "STANDBY"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = when (status) {
                    AudioStatus.STREAMING -> StatusLive
                    AudioStatus.CONNECTING -> StatusConnecting
                    AudioStatus.ERROR -> StatusError
                    else -> colors.textSecondary
                }
            )
        }
    }
}

/**
 * Real-time organic visualizer wave bars without border card.
 */
@Composable
private fun AudioVisualizerOrganicWave(
    waveformBars: List<Float>,
    amplitude: Float,
    isMuted: Boolean,
    waveColor: Color,
    ambientColor: Color,
    modifier: Modifier = Modifier
) {
    val barCount = 24
    val transition = rememberInfiniteTransition(label = "audio_organic_ambient")
    val ambientPulse by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambient_pulse"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(horizontal = 4.dp)
    ) {
        val totalWidth = size.width
        val canvasHeight = size.height
        val totalSpacing = totalWidth * 0.28f
        val spacing = totalSpacing / (barCount + 1)
        val barWidth = (totalWidth - totalSpacing) / barCount
        val cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)

        for (i in 0 until barCount) {
            val targetHeightFraction = if (isMuted) {
                0.08f
            } else if (waveformBars.isNotEmpty()) {
                waveformBars.getOrElse(i) { 0.08f }.coerceIn(0.08f, 1.0f)
            } else {
                ambientPulse
            }

            val barHeight = (canvasHeight * targetHeightFraction).coerceAtLeast(barWidth)
            val left = spacing + i * (barWidth + spacing)
            val top = (canvasHeight - barHeight) / 2f

            val brush = if (isMuted) {
                Brush.verticalGradient(
                    listOf(ambientColor.copy(alpha = 0.4f), ambientColor.copy(alpha = 0.15f)),
                    startY = top,
                    endY = top + barHeight
                )
            } else {
                val alpha = (0.50f + (targetHeightFraction * 0.50f)).coerceIn(0.50f, 1.0f)
                Brush.verticalGradient(
                    listOf(
                        waveColor.copy(alpha = alpha),
                        waveColor.copy(alpha = alpha * 0.70f)
                    ),
                    startY = top,
                    endY = top + barHeight
                )
            }

            drawRoundRect(
                brush = brush,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}

/**
 * Quality chip selector row with minimalist pills.
 */
@Composable
private fun AudioQualityChipRow(
    selectedQuality: String,
    onQualitySelect: (String) -> Unit,
    colors: AudioColors
) {
    val profiles = listOf(
        Triple("AUTO", "Auto", AudioTestTags.QUALITY_CHIP_AUTO),
        Triple("HIGH", "High (8k)", AudioTestTags.QUALITY_CHIP_HIGH),
        Triple("MEDIUM", "Med", AudioTestTags.QUALITY_CHIP_MEDIUM),
        Triple("LOW", "Low", AudioTestTags.QUALITY_CHIP_LOW)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "CODEC",
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
 * Tactile Pro Audio Trigger (Start / Stop action).
 */
@Composable
private fun TactileAudioTrigger(
    isStreaming: Boolean,
    isOperating: Boolean,
    enabled: Boolean,
    ringColorBase: Color,
    onClick: () -> Unit
) {
    val ringColor by animateColorAsState(
        targetValue = if (isStreaming) StatusError else ringColorBase.copy(alpha = 0.85f),
        animationSpec = tween(220),
        label = "audioRingColor"
    )
    val coreColor by animateColorAsState(
        targetValue = if (isStreaming) StatusError else Color(0xFF10B981),
        animationSpec = tween(220),
        label = "audioCoreColor"
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
            .testTag(AudioTestTags.TOGGLE_BUTTON)
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
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (isStreaming) 18.dp else 26.dp)
                    )
                }
            }
        }
    }
}
