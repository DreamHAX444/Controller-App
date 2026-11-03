package com.livetracker.controller.ui.screens.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

@Composable
fun AudioScreen(
    viewModel: AudioViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var trackerDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(AudioTestTags.SCREEN)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Target Tracker Dropdown Selector Row
        AudioTargetSelectorRow(
            uiState = uiState,
            dropdownExpanded = trackerDropdownExpanded,
            onDropdownToggle = { trackerDropdownExpanded = it },
            onSelectTracker = {
                trackerDropdownExpanded = false
                viewModel.selectTracker(it)
            }
        )

        // 2. Error banner
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

        // 3. Status Pill
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when (uiState.status) {
                AudioStatus.STREAMING -> StatusLive.copy(alpha = 0.12f)
                AudioStatus.CONNECTING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                AudioStatus.ERROR -> StatusError.copy(alpha = 0.12f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f)
            },
            border = BorderStroke(
                1.dp,
                when (uiState.status) {
                    AudioStatus.STREAMING -> StatusLive.copy(alpha = 0.35f)
                    AudioStatus.CONNECTING -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    AudioStatus.ERROR -> StatusError.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                }
            ),
            modifier = Modifier.testTag(AudioTestTags.STATUS_BADGE)
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
                                AudioStatus.STREAMING -> StatusLive
                                AudioStatus.CONNECTING -> MaterialTheme.colorScheme.primary
                                AudioStatus.ERROR -> StatusError
                                else -> StatusOffline
                            },
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (uiState.status) {
                        AudioStatus.STREAMING -> "LIVE AUDIO UPLINK ACTIVE"
                        AudioStatus.CONNECTING -> "ESTABLISHING AUDIO CHANNEL..."
                        AudioStatus.ERROR -> "AUDIO STREAM ERROR"
                        AudioStatus.STOPPED -> "AUDIO MONITORING STOPPED"
                        AudioStatus.IDLE -> "AUDIO STANDBY"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = when (uiState.status) {
                        AudioStatus.STREAMING -> StatusLive
                        AudioStatus.CONNECTING -> MaterialTheme.colorScheme.primary
                        AudioStatus.ERROR -> StatusError
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // 4. Center Visualizer Canvas Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(16.dp))
                .testTag(AudioTestTags.WAVEFORM_CARD),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isStreaming) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Live telemetry badge at top
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(StatusLive, CircleShape)
                                )
                                Text(
                                    text = "PCM 8kHz / 16-BIT MONO",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (uiState.isMuted) StatusOffline.copy(alpha = 0.2f) else StatusLive.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = if (uiState.isMuted) "MUTED" else "LVL: ${(uiState.amplitude * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (uiState.isMuted) StatusOffline else StatusLive,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        // Real-time sound-reactive waveform bars
                        AudioVisualizerWave(
                            waveformBars = uiState.waveformBars,
                            amplitude = uiState.amplitude,
                            isMuted = uiState.isMuted
                        )

                        // Realtime latency & codec note
                        Text(
                            text = if (uiState.isMuted) "Audio output muted locally • Microphone feed active"
                            else "Direct PCM low-latency buffer • Zero packet degradation",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "8kHz PCM 16-Bit Mono Audio Feed",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Encrypted Supabase Realtime Transport",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 5. Stream Controls Card (Volume, Quality & Actions)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Quality Selector Row
                AudioQualitySelectorRow(
                    selectedQuality = uiState.selectedQuality,
                    onQualitySelect = { viewModel.setQuality(it) }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    thickness = 0.8.dp
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
                            tint = if (uiState.isMuted) StatusError else MaterialTheme.colorScheme.primary
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
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${((if (uiState.isMuted) 0f else uiState.volume) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(44.dp),
                        textAlign = TextAlign.End
                    )
                }

                // Primary Start/Stop button
                Button(
                    onClick = {
                        if (uiState.isStreaming) viewModel.stopAudio() else viewModel.startAudio()
                    },
                    enabled = uiState.deviceId.isNotEmpty() && !uiState.isActionPending,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.isStreaming) StatusError else MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag(AudioTestTags.TOGGLE_BUTTON)
                ) {
                    if (uiState.isActionPending) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (uiState.isStreaming) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.isStreaming) "STOP AUDIO MONITORING" else "START AUDIO MONITORING",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioTargetSelectorRow(
    uiState: AudioUiState,
    dropdownExpanded: Boolean,
    onDropdownToggle: (Boolean) -> Unit,
    onSelectTracker: (TrackerId) -> Unit
) {
    val friendlyTarget = DeviceNameFormatter.formatFriendlyName(uiState.displayName, uiState.deviceId)

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
                    imageVector = Icons.Default.GraphicEq,
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
                    onClick = { onDropdownToggle(true) },
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .sizeIn(minHeight = 40.dp)
                        .testTag(AudioTestTags.TRACKER_SELECTOR)
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(AudioTestTags.DEVICE_HEADER)
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
                    onDismissRequest = { onDropdownToggle(false) },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                ) {
                    if (uiState.availableTrackers.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No trackers available", style = MaterialTheme.typography.bodyMedium) },
                            onClick = { onDropdownToggle(false) },
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
                                        if (option.isAudioSupported) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier.padding(start = 8.dp)
                                            ) {
                                                Text(
                                                    text = "MIC",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = { onSelectTracker(option.deviceId) },
                                modifier = Modifier.sizeIn(minHeight = 48.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioVisualizerWave(
    waveformBars: List<Float>,
    amplitude: Float,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val barCount = 20
    val transition = rememberInfiniteTransition(label = "audio_ambient")
    val ambientPulse by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ambient_pulse"
    )

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .padding(horizontal = 8.dp)
    ) {
        val totalWidth = size.width
        val canvasHeight = size.height
        val totalSpacing = totalWidth * 0.30f
        val spacing = totalSpacing / (barCount + 1)
        val barWidth = (totalWidth - totalSpacing) / barCount
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)

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
                    listOf(StatusOffline.copy(alpha = 0.4f), StatusOffline.copy(alpha = 0.2f)),
                    startY = top,
                    endY = top + barHeight
                )
            } else {
                val alpha = (0.55f + (targetHeightFraction * 0.45f)).coerceIn(0.55f, 1.0f)
                Brush.verticalGradient(
                    listOf(
                        StatusLive.copy(alpha = alpha),
                        StatusLive.copy(alpha = alpha * 0.75f)
                    ),
                    startY = top,
                    endY = top + barHeight
                )
            }

            drawRoundRect(
                brush = brush,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}

@Composable
private fun AudioQualitySelectorRow(
    selectedQuality: String,
    onQualitySelect: (String) -> Unit
) {
    val profiles = listOf(
        Triple("AUTO", "Auto (Zero-Lag)", AudioTestTags.QUALITY_CHIP_AUTO),
        Triple("HIGH", "High (Raw)", AudioTestTags.QUALITY_CHIP_HIGH),
        Triple("MEDIUM", "Med", AudioTestTags.QUALITY_CHIP_MEDIUM),
        Triple("LOW", "Low (Bandwidth)", AudioTestTags.QUALITY_CHIP_LOW)
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "STREAM QUALITY",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = when (selectedQuality) {
                    "AUTO" -> "Adaptive Low-Latency"
                    "HIGH" -> "8kHz 16-Bit PCM"
                    "MEDIUM" -> "Optimized Bandwidth"
                    else -> "Low-Bandwidth Mode"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            profiles.forEach { (key, label, tag) ->
                val isSelected = selectedQuality == key
                FilterChip(
                    selected = isSelected,
                    onClick = { onQualitySelect(key) },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.testTag(tag)
                )
            }
        }
    }
}
