package com.livetracker.controller.ui.screens.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.livetracker.controller.core.tracker.camera.domain.DiscoveredCameraInfo
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.tracker.camera.domain.ActualCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraCaptureConfig
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.CameraResolution
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.camera.domain.StreamLifecycleState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusConnectingContainer
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusLiveContainer
import com.livetracker.controller.ui.theme.StatusOffline
import com.livetracker.controller.ui.theme.StatusOnConnectingContainer
import com.livetracker.controller.ui.theme.StatusOnLiveContainer
import com.livetracker.controller.ui.screens.screen.WebRtcScreenRenderer
import org.webrtc.VideoTrack

private val CameraResolution.label: String
    get() = when (this) {
        CameraResolution.QVGA -> "QVGA (240p)"
        CameraResolution.VGA -> "VGA (480p)"
        CameraResolution.HD_720P -> "HD (720p)"
        CameraResolution.FULL_HD_1080P -> "FHD (1080p)"
        else -> "${width}×${height}"
    }

/**
 * Tactical Remote Camera Screen for Trace Controller.
 *
 * Conforms strictly to:
 * - UI/UX Pro Max mobile design principles (WCAG AA, 48dp touch targets, clear hierarchies)
 * - Trace Design System (Curvatures: 20dp cards, 12dp tiles, 24dp pills)
 * - Telemetry Honesty: Separates requested settings from verified actual telemetry;
 *   never fabricates frames, FPS, bitrate, or latency; explicitly labels simulated streams.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val videoTrack by viewModel.videoTrack.collectAsStateWithLifecycle()

    CameraScreenContent(
        uiState = uiState,
        videoTrack = videoTrack,
        onSelectTracker = { viewModel.selectTracker(it) },
        onStartStream = { viewModel.startCamera() },
        onStopStream = { viewModel.stopCamera() },
        onSwitchLens = { viewModel.switchCamera() },
        onSetFacing = { viewModel.setCameraFacing(it) },
        onSetProfile = { viewModel.setQualityProfile(it) },
        onReloadCameras = { viewModel.reloadCameras() },
        onSelectCamera = { viewModel.selectCamera(it) },
        onToggleFullScreen = { viewModel.toggleFullScreen() },
        onDismissError = { viewModel.dismissError() },
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
fun CameraScreenContent(
    uiState: CameraUiState,
    videoTrack: VideoTrack? = null,
    onSelectTracker: (TrackerId) -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onSwitchLens: () -> Unit,
    onSetFacing: (CameraFacing) -> Unit,
    onSetProfile: (QualityProfile) -> Unit,
    onReloadCameras: () -> Unit = {},
    onSelectCamera: (DiscoveredCameraInfo) -> Unit = {},
    onToggleFullScreen: () -> Unit = {},
    onDismissError: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var trackerDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(CameraTestTags.SCREEN_ROOT)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. Target Tracker Selector Bar ---
        CameraTargetSelectorRow(
            uiState = uiState,
            dropdownExpanded = trackerDropdownExpanded,
            onDropdownToggle = { trackerDropdownExpanded = it },
            onSelectTracker = {
                trackerDropdownExpanded = false
                onSelectTracker(it)
            }
        )

        // --- 2. Simulation Banner (if active stream is simulated) ---
        if (uiState.isSimulated) {
            SimulationWarningBanner(
                simulationLabel = uiState.simulationLabel ?: "Simulation Mode"
            )
        }

        // --- 3. Dismissible Error Banner (if any error present) ---
        AnimatedVisibility(
            visible = uiState.errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            uiState.errorMessage?.let { message ->
                CameraErrorBanner(
                    message = message,
                    onDismiss = onDismissError
                )
            }
        }

        // --- 4. Responsive Viewfinder Canvas ---
        CameraViewfinder(
            uiState = uiState,
            videoTrack = videoTrack,
            onToggleFullScreen = onToggleFullScreen
        )

        // --- 5. Discovered Camera Sensors Selection Card ---
        CameraSensorSelectorCard(
            uiState = uiState,
            onSelectCamera = onSelectCamera,
            onReloadCameras = onReloadCameras
        )

        // --- 6. Controls Panel Card ---
        CameraControlsCard(
            uiState = uiState,
            onStartStream = onStartStream,
            onStopStream = onStopStream,
            onSwitchLens = onSwitchLens,
            onSetFacing = onSetFacing,
            onSetProfile = onSetProfile
        )

        // --- 7. Telemetry & Configuration Inspector Card ---
        CameraTelemetryInspectorCard(
            uiState = uiState
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // --- 8. Immersive Full Screen Viewer Dialog ---
    if (uiState.isFullScreen) {
        CameraFullScreenViewer(
            uiState = uiState,
            videoTrack = videoTrack,
            onDismiss = onToggleFullScreen,
            onSwitchLens = onSwitchLens
        )
    }
}

@Composable
private fun CameraTargetSelectorRow(
    uiState: CameraUiState,
    dropdownExpanded: Boolean,
    onDropdownToggle: (Boolean) -> Unit,
    onSelectTracker: (TrackerId) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
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
                        .testTag(CameraTestTags.TRACKER_SELECTOR)
                ) {
                    val dotColor = if (uiState.isDeviceOnline) StatusLive else StatusOffline
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.selectedTrackerDisplayName,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
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
                                            fontWeight = if (option.deviceId == uiState.selectedTrackerId) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    if (option.isCameraSupported) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = "CAM",
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

/**
 * Honest simulation banner explicitly highlighting simulated status.
 */
@Composable
private fun SimulationWarningBanner(simulationLabel: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = StatusConnectingContainer,
        border = BorderStroke(1.dp, StatusConnecting.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CameraTestTags.SIMULATION_BANNER)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Simulation indicator",
                tint = StatusOnConnectingContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "SIMULATION ACTIVE: $simulationLabel",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = StatusOnConnectingContainer
                )
                Text(
                    text = "Operating in deterministic simulation mode. No live video frames are transmitted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusOnConnectingContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * User-dismissible error banner.
 */
@Composable
private fun CameraErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CameraTestTags.ERROR_BANNER)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Error icon",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(CameraTestTags.BTN_DISMISS_ERROR)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss error",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Responsive Viewfinder canvas rendering gating states, lifecycle states,
 * and an honest video placeholder.
 */
@Composable
private fun CameraViewfinder(
    uiState: CameraUiState,
    videoTrack: VideoTrack? = null,
    onToggleFullScreen: () -> Unit = {}
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0F172A) // Dark tactical canvas across both themes
        ),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .testTag(CameraTestTags.VIEWFINDER)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                // 1. Gating State: No Device Selected
                uiState.gatingReason == CameraGatingReason.NO_DEVICE_SELECTED -> {
                    ViewfinderPlaceholderContent(
                        icon = Icons.Default.VideocamOff,
                        iconTint = StatusOffline,
                        title = "No Tracker Selected",
                        subtitle = uiState.gatingMessage ?: "Select a tracker device to view remote camera feed."
                    )
                }

                // 2. Gating State: Capability Unsupported
                uiState.gatingReason == CameraGatingReason.CAPABILITY_UNSUPPORTED -> {
                    ViewfinderPlaceholderContent(
                        icon = Icons.Default.VideocamOff,
                        iconTint = StatusOffline,
                        title = "Camera Unsupported",
                        subtitle = uiState.gatingMessage ?: "Camera hardware is not supported by this device."
                    )
                }

                // 3. Gating State: Permission Denied
                uiState.gatingReason == CameraGatingReason.PERMISSION_DENIED -> {
                    ViewfinderPlaceholderContent(
                        icon = Icons.Default.Lock,
                        iconTint = StatusError,
                        title = "Permission Denied",
                        subtitle = uiState.gatingMessage ?: "Your session does not have permission to execute camera services."
                    )
                }

                // 4. Gating State: Device Offline
                uiState.gatingReason == CameraGatingReason.DEVICE_OFFLINE -> {
                    ViewfinderPlaceholderContent(
                        icon = Icons.Default.CloudOff,
                        iconTint = StatusOffline,
                        title = "Device Offline",
                        subtitle = uiState.gatingMessage ?: "Tracker is disconnected. Reconnect device to start camera."
                    )
                }

                // 5. Lifecycle State: Starting
                uiState.lifecycleState == StreamLifecycleState.STARTING -> {
                    ViewfinderLoadingContent(
                        title = "Connecting to Camera...",
                        subtitle = "Dispatching StartCamera command to ${uiState.selectedTrackerDisplayName}."
                    )
                }

                // 6. Lifecycle State: Stopping
                uiState.lifecycleState == StreamLifecycleState.STOPPING -> {
                    ViewfinderLoadingContent(
                        title = "Stopping Camera Stream...",
                        subtitle = "Closing camera capture session on tracker."
                    )
                }

                // 7. Lifecycle State: Switching
                uiState.lifecycleState == StreamLifecycleState.SWITCHING -> {
                    ViewfinderLoadingContent(
                        title = "Switching Camera Lens...",
                        subtitle = "Transitioning to ${uiState.selectedFacing.name} camera."
                    )
                }

                // 8. Lifecycle State: Error
                uiState.lifecycleState == StreamLifecycleState.ERROR -> {
                    ViewfinderPlaceholderContent(
                        icon = Icons.Default.Warning,
                        iconTint = StatusError,
                        title = "Stream Error",
                        subtitle = uiState.errorMessage ?: "Failed to maintain camera stream."
                    )
                }

                // 9. Lifecycle State: Streaming
                uiState.lifecycleState == StreamLifecycleState.STREAMING -> {
                    ViewfinderStreamingContent(
                        uiState = uiState,
                        videoTrack = videoTrack,
                        onToggleFullScreen = onToggleFullScreen
                    )
                }

                // 10. Lifecycle State: Idle (Default ready state)
                else -> {
                    ViewfinderPlaceholderContent(
                        icon = Icons.Default.Videocam,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Camera Ready",
                        subtitle = "Tap 'Start Stream' below to request remote camera feed from ${uiState.selectedTrackerDisplayName}."
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewfinderPlaceholderContent(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(iconTint.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STATE_TITLE)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STATE_SUBTITLE)
        )
    }
}

@Composable
private fun ViewfinderLoadingContent(
    title: String,
    subtitle: String
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STATE_TITLE)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF94A3B8),
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STATE_SUBTITLE)
        )
    }
}

/**
 * Tactical HUD overlay rendered when camera is actively in STREAMING state.
 * Honestly discloses simulation / pending physical transport.
 */
@Composable
private fun ViewfinderStreamingContent(
    uiState: CameraUiState,
    videoTrack: VideoTrack? = null,
    onToggleFullScreen: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(CameraTestTags.VIEWFINDER_STREAMING_HUD)
    ) {
        if (videoTrack != null) {
            WebRtcScreenRenderer(
                videoTrack = videoTrack,
                modifier = Modifier.fillMaxSize()
            )
        }

        // --- Top HUD Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Live Status Pill
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (uiState.isSimulated) StatusConnectingContainer else StatusLiveContainer
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(if (uiState.isSimulated) StatusConnecting else StatusLive, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (uiState.isSimulated) "SIMULATED" else "LIVE STREAM",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                        color = if (uiState.isSimulated) StatusOnConnectingContainer else StatusOnLiveContainer
                    )
                }
            }

            // Lens & Profile Badges and Fullscreen Button
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${uiState.selectedFacing.name} LENS",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = uiState.selectedProfile.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.40f)
                ) {
                    IconButton(
                        onClick = onToggleFullScreen,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag(CameraTestTags.BTN_FULLSCREEN)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Full Screen",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // --- Center Viewfinder Honest Status Notice ---
        if (videoTrack == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = if (uiState.isSimulated) StatusConnecting else StatusLive,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (uiState.isSimulated) "SIMULATED STREAM SESSION" else "REMOTE STREAM ACTIVE",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STATE_TITLE)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (uiState.isSimulated) {
                        "(${uiState.simulationLabel})\nDeterministic simulation — no real frames transmitted"
                    } else {
                        "Awaiting incoming video frames\n(WebRTC transport layer pending)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STATE_SUBTITLE)
                )
            }
        }

        // --- Bottom Telemetry Strip ---
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val actual = uiState.actualConfig
            val telemetrySummary = if (actual != null) {
                "Verified: ${actual.resolution.label} • ${actual.fps} FPS • ${actual.bitrateKbps} kbps"
            } else {
                "Requested: ${uiState.requestedConfig?.resolution?.label ?: "N/A"} (${uiState.requestedConfig?.fps ?: 0} FPS) • Actual: Pending"
            }

            Text(
                text = telemetrySummary,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                color = Color(0xFFE2E8F0),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Camera Controls Card with Start/Stop, Lens Switch, and Quality Profile controls.
 */
@Composable
private fun CameraControlsCard(
    uiState: CameraUiState,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onSwitchLens: () -> Unit,
    onSetFacing: (CameraFacing) -> Unit,
    onSetProfile: (QualityProfile) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Stream Controls",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            // --- Row 1: Start / Stop Primary Button ---
            if (uiState.isStreaming || uiState.lifecycleState == StreamLifecycleState.STARTING || uiState.lifecycleState == StreamLifecycleState.SWITCHING) {
                Button(
                    onClick = onStopStream,
                    enabled = uiState.canStop && !uiState.isOperating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag(CameraTestTags.BTN_STOP_STREAM)
                ) {
                    if (uiState.isOperating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "Stop Camera Stream",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            } else {
                Button(
                    onClick = onStartStream,
                    enabled = uiState.canStart && !uiState.isOperating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 48.dp)
                        .testTag(CameraTestTags.BTN_START_STREAM)
                ) {
                    if (uiState.isOperating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "Start Camera Stream",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            // --- Row 2: Camera Lens Switch ---
            val targetFacing = if (uiState.selectedFacing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
            OutlinedButton(
                onClick = {
                    if (uiState.isStreaming) {
                        onSwitchLens()
                    } else {
                        onSetFacing(targetFacing)
                    }
                },
                enabled = (uiState.canSwitch || uiState.canStart) && !uiState.isOperating,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .testTag(CameraTestTags.BTN_SWITCH_LENS)
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch camera lens",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Facing: ${uiState.selectedFacing.name} (Switch to ${targetFacing.name})",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            // --- Row 3: Quality Profile Selection ---
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Quality Profile",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QualityProfile.values().forEach { profile ->
                        val isSelected = uiState.selectedProfile == profile
                        val profileTag = when (profile) {
                            QualityProfile.LOW -> CameraTestTags.CHIP_PROFILE_LOW
                            QualityProfile.MEDIUM -> CameraTestTags.CHIP_PROFILE_MEDIUM
                            QualityProfile.HIGH -> CameraTestTags.CHIP_PROFILE_HIGH
                            QualityProfile.AUTO -> CameraTestTags.CHIP_PROFILE_AUTO
                        }

                        FilterChip(
                            selected = isSelected,
                            onClick = { onSetProfile(profile) },
                            enabled = !uiState.isOperating,
                            label = {
                                Text(
                                    text = if (profile == QualityProfile.AUTO) "AUTO" else "${profile.name} (${profile.resolution.label})",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1
                                )
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .sizeIn(minHeight = 48.dp)
                                .testTag(profileTag)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Explicit comparison inspector showing Requested Configuration vs Verified Actual Telemetry.
 */
@Composable
private fun CameraTelemetryInspectorCard(
    uiState: CameraUiState
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Stream Configuration & Telemetry",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Column 1: Requested Configuration Tile
                TelemetryTile(
                    title = "Requested Settings",
                    tag = CameraTestTags.TELEMETRY_REQUESTED,
                    lines = listOf(
                        "Resolution" to (uiState.requestedConfig?.resolution?.let { "${it.width}×${it.height}" } ?: "Default (720p)"),
                        "Target FPS" to (uiState.requestedConfig?.fps?.let { "$it FPS" } ?: "30 FPS"),
                        "Bitrate" to (uiState.requestedConfig?.bitrateKbps?.let { "$it kbps" } ?: "1500 kbps"),
                        "Facing" to (uiState.requestedConfig?.facing?.name ?: uiState.selectedFacing.name)
                    ),
                    modifier = Modifier.weight(1f)
                )

                // Column 2: Verified Actual Telemetry Tile
                val actual = uiState.actualConfig
                TelemetryTile(
                    title = "Verified Peer Telemetry",
                    tag = CameraTestTags.TELEMETRY_ACTUAL,
                    lines = listOf(
                        "Resolution" to (actual?.resolution?.let { "${it.width}×${it.height}" } ?: "Pending"),
                        "Actual FPS" to (actual?.fps?.let { "%.1f FPS".format(it) } ?: "Pending"),
                        "Actual Bitrate" to (actual?.bitrateKbps?.let { "$it kbps" } ?: "Pending"),
                        "Source" to if (uiState.isSimulated) "Simulated" else if (actual != null) "Verified Peer" else "None"
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    title: String,
    tag: String,
    lines: List<Pair<String, String>>,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier.testTag(tag)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 1.dp
            )

            lines.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Dedicated Card for hardware camera sensor selection and remote discovery reload.
 */
@Composable
private fun CameraSensorSelectorCard(
    uiState: CameraUiState,
    onSelectCamera: (DiscoveredCameraInfo) -> Unit,
    onReloadCameras: () -> Unit
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "reload_rotate")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    val currentCameraId = uiState.selectedCameraId ?: "0"
    val activeCamera = uiState.discoveredCameras.find { it.cameraId == currentCameraId }
        ?: uiState.discoveredCameras.find { it.facing == uiState.selectedFacing }
        ?: uiState.discoveredCameras.firstOrNull()

    Card(
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CameraTestTags.CAMERA_SENSOR_SELECTOR)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Hardware Camera Sensors",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = onReloadCameras,
                    enabled = !uiState.isReloadingCameras && uiState.selectedTrackerId != null,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag(CameraTestTags.BTN_RELOAD_CAMERAS)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan / Reload Cameras",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .then(if (uiState.isReloadingCameras) Modifier.rotate(rotation) else Modifier)
                    )
                }
            }

            // Dropdown Selector Button
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { dropdownExpanded = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (activeCamera?.facing == CameraFacing.FRONT) Icons.Default.CameraFront else Icons.Default.CameraRear,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = activeCamera?.displayName ?: "Camera $currentCameraId",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (activeCamera?.supportedResolutions.isNullOrEmpty()) {
                                        "Facing: ${activeCamera?.facing?.name ?: uiState.selectedFacing.name}"
                                    } else {
                                        "Resolutions: ${activeCamera?.supportedResolutions?.take(2)?.joinToString(", ")}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select Camera",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    uiState.discoveredCameras.forEach { cam ->
                        val isSelected = cam.cameraId == currentCameraId
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = cam.displayName,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = "ACTIVE",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    if (cam.supportedResolutions.isNotEmpty()) {
                                        Text(
                                            text = "Max: ${cam.supportedResolutions.firstOrNull() ?: ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (cam.facing == CameraFacing.FRONT) Icons.Default.CameraFront else Icons.Default.CameraRear,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                dropdownExpanded = false
                                onSelectCamera(cam)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fullscreen immersive camera stream viewer.
 */
@Composable
private fun CameraFullScreenViewer(
    uiState: CameraUiState,
    videoTrack: VideoTrack? = null,
    onDismiss: () -> Unit,
    onSwitchLens: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (videoTrack != null) {
                WebRtcScreenRenderer(
                    videoTrack = videoTrack,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                ViewfinderPlaceholderContent(
                    icon = Icons.Default.Videocam,
                    iconTint = StatusLive,
                    title = if (uiState.isSimulated) "SIMULATED STREAM SESSION" else "REMOTE STREAM ACTIVE",
                    subtitle = "Remote stream rendering in Fullscreen"
                )
            }

            // Top HUD Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(if (uiState.isSimulated) StatusConnecting else StatusLive, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (uiState.isSimulated) "SIMULATED" else "LIVE • ${uiState.selectedFacing.name} LENS (${uiState.selectedCameraId ?: "0"})",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Floating Switch Lens Action Button in Fullscreen
            FloatingActionButton(
                onClick = onSwitchLens,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera Lens"
                )
            }
        }
    }
}
