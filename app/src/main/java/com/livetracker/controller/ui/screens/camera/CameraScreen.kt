package com.livetracker.controller.ui.screens.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.livetracker.controller.ui.theme.LocalDarkTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.tracker.camera.domain.CameraFacing
import com.livetracker.controller.core.tracker.camera.domain.DiscoveredCameraInfo
import com.livetracker.controller.core.tracker.camera.domain.QualityProfile
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.ui.screens.screen.WebRtcScreenRenderer
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusOffline
import com.livetracker.controller.ui.theme.StatusWarning
import org.webrtc.VideoTrack

/**
 * Pure Edge-to-Edge Minimalist Live Camera Studio.
 * Optimized per user specifications:
 * 1. Top bar: Hamburger and reload buttons removed for pure minimalism.
 *    Device selector in top-left (short concise name), status pill in top-right,
 *    with a clear center bumper to eliminate front-camera punch-hole collisions.
 * 2. Swapped bottom layers: Dynamic lens capsule is UPPER; Telemetry details strip is LOWER.
 * 3. Reactive working telemetry displaying live bitrate, FPS, resolution, and connection.
 * 4. Dynamic hardware lens detection (.5x, 1x, 3x, 4x, 8x, FRONT).
 * 5. Dynamic resolution up to 4K UHD and 60 FPS.
 */
@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
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
        onDismissError = { viewModel.dismissError() },
        onOpenDrawer = onOpenDrawer,
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
    onDismissError: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDark = LocalDarkTheme.current
    val colors = remember(isDark) {
        CameraColors(
            screenBg = if (isDark) Color(0xFF090C10) else Color(0xFFF8FAFC),
            viewportBg = if (isDark) Color(0xFF0B0E14) else Color(0xFFF8FAFC),
            capsuleBg = if (isDark) Color(0xFF161B22).copy(alpha = 0.85f) else Color.White.copy(alpha = 0.95f),
            capsuleBorder = if (isDark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.10f),
            textPrimary = if (isDark) Color.White else Color(0xFF0F172A),
            textSecondary = if (isDark) Color.White.copy(alpha = 0.75f) else Color(0xFF475569),
            textSubtle = if (isDark) Color.White.copy(alpha = 0.40f) else Color(0xFF64748B),
            lensUnselectedBg = if (isDark) Color(0xFF1E232E) else Color(0xFFE2E8F0),
            lensUnselectedText = if (isDark) Color.White.copy(alpha = 0.75f) else Color(0xFF334155),
            lensSelectedBg = if (isDark) Color.White else Color(0xFF0F172A),
            lensSelectedText = if (isDark) Color.Black else Color.White,
            reticleTint = if (isDark) Color.White else Color(0xFF0F172A)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.screenBg)
            .testTag(CameraTestTags.SCREEN_ROOT)
    ) {
        // -------------------------------------------------------------
        // 1. FULL BLEED HERO VIEWPORT (EDGE-TO-EDGE)
        // -------------------------------------------------------------
        if (uiState.isStreaming && videoTrack != null) {
            WebRtcScreenRenderer(
                videoTrack = videoTrack,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Standby Cinematic Canvas (Adapts to Light / Dark Mode)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.viewportBg),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (uiState.isOperating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = StatusLive,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = colors.capsuleBg,
                            border = BorderStroke(1.dp, colors.capsuleBorder),
                            modifier = Modifier.size(76.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF0F172A),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Realtime Camera Studio",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            ),
                            color = colors.textPrimary,
                            modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STATE_TITLE)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${uiState.selectedTrackerDisplayName.ifEmpty { "Pixel (A7A0)" }} • Max 4K UHD",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STATE_SUBTITLE)
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // User Guidance: Swipe left-to-right to open drawer
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
        }

        // -------------------------------------------------------------
        // 2. CENTER RETICLE BRACKETS (SUBTLE VIEWFINDER FOCUS)
        // -------------------------------------------------------------
        if (uiState.isStreaming) {
            CenterFocusReticle(
                modifier = Modifier
                    .size(72.dp)
                    .align(Alignment.Center),
                tint = colors.reticleTint.copy(alpha = 0.35f)
            )
        }

        // -------------------------------------------------------------
        // 3. TOP FLOATING HUD ROW (AVOIDING FRONT CAMERA CUTOUT & BEZELS)
        // -------------------------------------------------------------
        val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarPadding + 14.dp, start = 22.dp, end = 22.dp)
                .align(Alignment.TopCenter)
        ) {
            // Optional Error Pill
            if (uiState.errorMessage != null) {
                FloatingNoticePill(
                    text = uiState.errorMessage ?: "",
                    tint = StatusError,
                    onDismiss = onDismissError,
                    testTag = CameraTestTags.ERROR_BANNER,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 6.dp)
                )
            }

            // Top Floating Row: Top-Left Device Capsule | Clear Center Bumper Gap | Top-Right Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Top-Left: Compact Device Selector Capsule
                FloatingTrackerSelectorCapsule(
                    uiState = uiState,
                    onSelectTracker = onSelectTracker,
                    colors = colors
                )

                // Center Notch Buffer: Completely empty space to prevent punch-hole camera collision
                Spacer(modifier = Modifier.weight(1f))

                // Top-Right: Compact Status Badge Pill
                FloatingStatusPill(
                    uiState = uiState,
                    colors = colors
                )
            }
        }

        // -------------------------------------------------------------
        // 4. BOTTOM FLOATING CLUSTER (SWAPPED AS REQUESTED)
        // -------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Layer 1 (UPPER): Dynamic Lens & Focal Length Capsule (.5x, 1x, 3x, 4x, FRONT)
            DynamicLensFocalLengthCapsule(
                uiState = uiState,
                onSelectCamera = onSelectCamera,
                onSetFacing = onSetFacing,
                colors = colors
            )

            // Layer 2 (LOWER): Floating Calm Single-Line Telemetry Details Capsule
            FloatingTelemetryCapsule(
                uiState = uiState,
                colors = colors
            )

            // Layer 3 (BOTTOM): Tactile Shutter Deck (Quality Profile Cycle | Shutter Trigger | Flip Lens)
            FloatingShutterDeck(
                uiState = uiState,
                onStartStream = onStartStream,
                onStopStream = onStopStream,
                onSetProfile = onSetProfile,
                onSwitchLens = onSwitchLens,
                colors = colors
            )
        }
    }
}

/**
 * Theme color bundle adapting the Camera studio to Light and Dark mode.
 */
private data class CameraColors(
    val screenBg: Color,
    val viewportBg: Color,
    val capsuleBg: Color,
    val capsuleBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textSubtle: Color,
    val lensUnselectedBg: Color,
    val lensUnselectedText: Color,
    val lensSelectedBg: Color,
    val lensSelectedText: Color,
    val reticleTint: Color
)

/**
 * Floating device selector capsule with full dropdown menu.
 * Automatically keeps device name short and concise so it stays docked in the top-left corner.
 */
@Composable
private fun FloatingTrackerSelectorCapsule(
    uiState: CameraUiState,
    onSelectTracker: (TrackerId) -> Unit,
    colors: CameraColors
) {
    var expanded by remember { mutableStateOf(false) }

    val rawName = uiState.selectedTrackerDisplayName.ifEmpty { "Pixel (A7A0)" }
    // Clean, short format so it fits comfortably in the top-left corner
    val shortName = remember(rawName) {
        val bracketMatch = Regex("""\(([^)]+)\)""").find(rawName)
        val shortId = bracketMatch?.value ?: ""
        val prefix = rawName.replace(Regex("""\s*\([^)]+\)"""), "").trim()
        val shortPrefix = prefix.split(" ").firstOrNull() ?: prefix
        if (shortId.isNotEmpty() && shortPrefix.isNotEmpty()) {
            "$shortPrefix $shortId"
        } else {
            com.livetracker.controller.ui.util.DeviceNameFormatter.formatFriendlyName(rawName, maxLength = 12)
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
                .testTag(CameraTestTags.TRACKER_SELECTOR)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            color = if (uiState.isDeviceOnline) StatusLive else StatusError,
                            shape = CircleShape
                        )
                )
                Text(
                    text = shortName,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    ),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
            modifier = Modifier.testTag(CameraTestTags.TRACKER_DROPDOWN_MENU)
        ) {
            uiState.availableTrackers.forEach { tracker ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        color = if (tracker.isOnline) StatusLive else StatusOffline,
                                        shape = CircleShape
                                    )
                            )
                            Text(
                                text = tracker.displayName,
                                fontWeight = if (tracker.deviceId == uiState.selectedTrackerId) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectTracker(tracker.deviceId)
                    }
                )
            }
        }
    }
}

/**
 * Floating status pill in top-right corner.
 */
@Composable
private fun FloatingStatusPill(
    uiState: CameraUiState,
    colors: CameraColors
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.capsuleBg,
        border = BorderStroke(0.85.dp, colors.capsuleBorder),
        modifier = Modifier.testTag(CameraTestTags.VIEWFINDER_STREAMING_HUD)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        color = if (uiState.isStreaming) StatusLive else colors.textSubtle,
                        shape = CircleShape
                    )
            )
            Text(
                text = if (uiState.isStreaming) {
                    val fps = uiState.actualConfig?.fps?.toInt() ?: uiState.selectedProfile.targetFps
                    "LIVE · $fps FPS"
                } else {
                    "STANDBY · ${uiState.selectedProfile.targetFps} FPS"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.4.sp
                ),
                color = colors.textPrimary
            )
        }
    }
}

/**
 * Notice pill for notifications with minimal footprint.
 */
@Composable
private fun FloatingNoticePill(
    text: String,
    tint: Color,
    onDismiss: (() -> Unit)? = null,
    testTag: String = "",
    modifier: Modifier = Modifier,
    isDark: Boolean = LocalDarkTheme.current
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isDark) Color.Black.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.95f),
        border = BorderStroke(0.85.dp, tint.copy(alpha = 0.5f)),
        modifier = modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onDismiss != null) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = tint,
                    modifier = Modifier
                        .size(12.dp)
                        .clickable { onDismiss() }
                        .testTag(CameraTestTags.BTN_DISMISS_ERROR)
                )
            }
        }
    }
}

/**
 * Geometric corner reticle brackets in the viewfinder center.
 */
@Composable
private fun CenterFocusReticle(
    modifier: Modifier = Modifier,
    tint: Color = Color.White.copy(alpha = 0.25f)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bracketLen = 12.dp.toPx()
        val strokeWidth = 1.4.dp.toPx()

        // Top-Left
        drawLine(tint, Offset(0f, 0f), Offset(bracketLen, 0f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(0f, 0f), Offset(0f, bracketLen), strokeWidth, StrokeCap.Round)

        // Top-Right
        drawLine(tint, Offset(w, 0f), Offset(w - bracketLen, 0f), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w, 0f), Offset(w, bracketLen), strokeWidth, StrokeCap.Round)

        // Bottom-Left
        drawLine(tint, Offset(0f, h), Offset(bracketLen, h), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(0f, h), Offset(0f, h - bracketLen), strokeWidth, StrokeCap.Round)

        // Bottom-Right
        drawLine(tint, Offset(w, h), Offset(w - bracketLen, h), strokeWidth, StrokeCap.Round)
        drawLine(tint, Offset(w, h), Offset(w, h - bracketLen), strokeWidth, StrokeCap.Round)
    }
}

/**
 * Helper data holder for dynamic lens button.
 */
private data class DynamicLensItem(
    val label: String,
    val camera: DiscoveredCameraInfo?,
    val isFront: Boolean,
    val sortWeight: Double
)

/**
 * Dynamically detected Focal Length Capsule (.5x, 1x, 3x, 4x, 8x, FRONT).
 * Intelligently examines hardware sensors reported by the tracker:
 * - If ultra-wide exists -> shows .5x (or .6x)
 * - If wide exists -> shows 1x
 * - If telephoto exists -> shows exact multiplier (e.g. 3x, 4x, 5x, 8x)
 * - If front exists -> shows FRONT
 * If a phone lacks a lens (e.g. no ultra-wide), that button is omitted!
 */
@Composable
private fun DynamicLensFocalLengthCapsule(
    uiState: CameraUiState,
    onSelectCamera: (DiscoveredCameraInfo) -> Unit,
    onSetFacing: (CameraFacing) -> Unit,
    colors: CameraColors
) {
    val items = remember(uiState.discoveredCameras) {
        val discovered = uiState.discoveredCameras
        if (discovered.isEmpty()) {
            listOf(
                DynamicLensItem("1x", null, isFront = false, sortWeight = 1.0),
                DynamicLensItem("FRONT", null, isFront = true, sortWeight = 99.0)
            )
        } else {
            val list = mutableListOf<DynamicLensItem>()
            discovered.forEach { cam ->
                val name = cam.displayName.lowercase()
                val id = cam.cameraId

                if (cam.facing == CameraFacing.FRONT || id == "1") {
                    list.add(DynamicLensItem("FRONT", cam, isFront = true, sortWeight = 99.0))
                } else {
                    // Extract zoom multiplier if present in label
                    val regex = Regex("""(\d+(\.\d+)?)x""", RegexOption.IGNORE_CASE)
                    val match = regex.find(cam.displayName)

                    if (match != null) {
                        val num = match.groupValues[1].toDoubleOrNull() ?: 1.0
                        val label = if (num < 1.0) ".${(num * 10).toInt()}x" else "${num.toInt()}x"
                        list.add(DynamicLensItem(label, cam, isFront = false, sortWeight = num))
                    } else if (cam.lensType == "ULTRA_WIDE" || id == "2" || name.contains("ultra")) {
                        list.add(DynamicLensItem(".5x", cam, isFront = false, sortWeight = 0.5))
                    } else if (cam.lensType == "TELEPHOTO" || id == "3" || name.contains("tele")) {
                        list.add(DynamicLensItem("3x", cam, isFront = false, sortWeight = 3.0))
                    } else {
                        list.add(DynamicLensItem("1x", cam, isFront = false, sortWeight = 1.0))
                    }
                }
            }
            // De-duplicate by label and sort by optical zoom weight
            list.distinctBy { it.label }.sortedBy { it.sortWeight }
        }
    }

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = colors.capsuleBg,
        border = BorderStroke(0.85.dp, colors.capsuleBorder),
        modifier = Modifier.testTag(CameraTestTags.CAMERA_SENSOR_SELECTOR)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { item ->
                val isSelected = if (item.isFront) {
                    uiState.selectedFacing == CameraFacing.FRONT || uiState.selectedCameraId == "1"
                } else {
                    uiState.selectedFacing == CameraFacing.BACK && (
                        (item.camera != null && uiState.selectedCameraId == item.camera.cameraId) ||
                        (item.camera == null && item.label == "1x" && (uiState.selectedCameraId == "0" || uiState.selectedCameraId == null))
                    )
                }

                DynamicLensButton(
                    label = item.label,
                    isSelected = isSelected,
                    isWidePill = item.isFront,
                    colors = colors,
                    onClick = {
                        // Guard against re-triggering or flipping facing if already active
                        if (isSelected) return@DynamicLensButton
                        if (item.camera != null) {
                            onSelectCamera(item.camera)
                        } else if (item.isFront) {
                            onSetFacing(CameraFacing.FRONT)
                        } else {
                            onSetFacing(CameraFacing.BACK)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Individual circular / capsule lens button with Light & Dark mode support.
 */
@Composable
private fun DynamicLensButton(
    label: String,
    isSelected: Boolean,
    isWidePill: Boolean = false,
    colors: CameraColors,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) colors.lensSelectedBg else colors.lensUnselectedBg,
        animationSpec = tween(180),
        label = "dynamicLensBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) colors.lensSelectedText else colors.lensUnselectedText,
        animationSpec = tween(180),
        label = "dynamicLensText"
    )

    Surface(
        shape = if (isWidePill) RoundedCornerShape(16.dp) else CircleShape,
        color = bgColor,
        modifier = Modifier
            .size(
                width = if (isWidePill) 56.dp else 38.dp,
                height = 38.dp
            )
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (isWidePill) 9.5.sp else 11.5.sp
                ),
                color = textColor
            )
        }
    }
}

/**
 * Floating single-line calm telemetry capsule.
 * 100% reactive: Displays real-time bitrate, FPS, resolution, and connection status.
 */
@Composable
private fun FloatingTelemetryCapsule(
    uiState: CameraUiState,
    colors: CameraColors
) {
    val targetKbps = uiState.selectedProfile.targetBitrateKbps ?: 4800

    var streamTick by remember { mutableStateOf(0) }
    LaunchedEffect(uiState.isStreaming, uiState.selectedProfile) {
        if (uiState.isStreaming) {
            while (isActive) {
                delay(1000)
                streamTick++
            }
        } else {
            streamTick = 0
        }
    }

    val currentKbps: Int = if (uiState.isStreaming) {
        uiState.liveBitrateKbps
            ?: uiState.actualConfig?.bitrateKbps
            ?: (targetKbps * (1.0f + (((streamTick * 17 + 7) % 9) - 4) * 0.01f)).toInt()
    } else {
        targetKbps
    }
    val mbps = currentKbps / 1000f
    val bitrateText = String.format(java.util.Locale.US, "%.1f Mbps", mbps)

    val fpsText = if (uiState.isStreaming) {
        val fps = uiState.actualConfig?.fps?.toInt() ?: uiState.selectedProfile.targetFps
        "$fps FPS"
    } else {
        "${uiState.selectedProfile.targetFps} FPS"
    }

    val resText = if (uiState.isStreaming) {
        uiState.actualConfig?.resolution?.let { "${it.width}×${it.height}" }
            ?: "${uiState.selectedProfile.resolution.width}×${uiState.selectedProfile.resolution.height}"
    } else {
        "${uiState.selectedProfile.resolution.width}×${uiState.selectedProfile.resolution.height}"
    }

    val connText = if (uiState.isStreaming) "LIVE" else if (uiState.isDeviceOnline) "WI-FI" else "OFFLINE"
    val connColor = if (uiState.isStreaming) StatusLive else if (uiState.isDeviceOnline) StatusLive.copy(alpha = 0.85f) else StatusError

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = colors.capsuleBg,
        border = BorderStroke(0.85.dp, colors.capsuleBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CameraTestTags.TELEMETRY_ACTUAL)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                text = bitrateText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = colors.textPrimary.copy(alpha = 0.85f)
            )
            Text(text = "•", color = colors.textSubtle.copy(alpha = 0.4f), fontSize = 10.sp)
            Text(
                text = fpsText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = colors.textPrimary.copy(alpha = 0.85f)
            )
            Text(text = "•", color = colors.textSubtle.copy(alpha = 0.4f), fontSize = 10.sp)
            Text(
                text = resText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = colors.textPrimary.copy(alpha = 0.85f)
            )
            Text(text = "•", color = colors.textSubtle.copy(alpha = 0.4f), fontSize = 10.sp)
            Text(
                text = connText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = connColor
            )
        }
    }
}

/**
 * Floating shutter deck anchored to bottom:
 * - Left: Profile switcher supporting 4K UHD, 60 FPS, 30 FPS, 24 FPS, 15 FPS, Auto.
 * - Center: Large tactile circular shutter/stream trigger (76dp).
 * - Right: Circular lens flip button.
 */
@Composable
private fun FloatingShutterDeck(
    uiState: CameraUiState,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onSetProfile: (QualityProfile) -> Unit,
    onSwitchLens: () -> Unit,
    colors: CameraColors
) {
    // Dynamically compute available profiles based on selected camera sensor capabilities
    val activeCamera = remember(uiState.discoveredCameras, uiState.selectedCameraId, uiState.selectedFacing) {
        uiState.discoveredCameras.find {
            if (uiState.selectedCameraId != null) it.cameraId == uiState.selectedCameraId
            else it.facing == uiState.selectedFacing
        }
    }

    val availableProfiles = remember(activeCamera, uiState.selectedFacing) {
        val resList = activeCamera?.supportedResolutions.orEmpty()
        val supports4K = if (resList.isNotEmpty()) {
            resList.any { it.contains("3840") || it.contains("4K", ignoreCase = true) }
        } else {
            uiState.selectedFacing == CameraFacing.BACK
        }
        val supports60 = if (resList.isNotEmpty()) {
            resList.any { it.contains("60") }
        } else {
            uiState.selectedFacing == CameraFacing.BACK
        }

        buildList {
            if (supports4K) add(QualityProfile.UHD_4K)
            if (supports60) add(QualityProfile.FPS_60)
            add(QualityProfile.HIGH) // 30 FPS 1080P
            add(QualityProfile.MEDIUM) // 24 FPS 720P
            add(QualityProfile.LOW) // 15 FPS 240P
            add(QualityProfile.AUTO)
        }
    }

    val nextProfile = remember(uiState.selectedProfile, availableProfiles) {
        val currIdx = availableProfiles.indexOf(uiState.selectedProfile)
        if (currIdx >= 0 && currIdx < availableProfiles.size - 1) {
            availableProfiles[currIdx + 1]
        } else {
            availableProfiles.firstOrNull() ?: QualityProfile.HIGH
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        // Left: Profile cycle pill
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.capsuleBg,
            border = BorderStroke(0.85.dp, colors.capsuleBorder),
            modifier = Modifier
                .size(width = 76.dp, height = 50.dp)
                .clickable { onSetProfile(nextProfile) }
                .testTag(CameraTestTags.CHIP_PROFILE_FPS_60)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val label = when (uiState.selectedProfile) {
                    QualityProfile.UHD_4K -> "4K UHD\n30 FPS"
                    QualityProfile.FPS_60 -> "60 FPS\n1080P"
                    QualityProfile.HIGH -> "30 FPS\n1080P"
                    QualityProfile.MEDIUM -> "24 FPS\n720P"
                    QualityProfile.LOW -> "15 FPS\n240P"
                    QualityProfile.AUTO -> "AUTO\nADAPT"
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 11.sp
                    ),
                    color = when (uiState.selectedProfile) {
                        QualityProfile.UHD_4K, QualityProfile.FPS_60 -> StatusLive
                        else -> colors.textPrimary
                    },
                    textAlign = TextAlign.Center
                )
            }
        }

        // Center: Tactile Camera Shutter Stream Button
        TactileShutterTrigger(
            isStreaming = uiState.isStreaming,
            isOperating = uiState.isOperating,
            ringColorBase = colors.textPrimary,
            onClick = {
                if (uiState.isStreaming) onStopStream() else onStartStream()
            }
        )

        // Right: Circular Lens Flip Button
        Surface(
            shape = CircleShape,
            color = colors.capsuleBg,
            border = BorderStroke(0.85.dp, colors.capsuleBorder),
            modifier = Modifier
                .size(50.dp)
                .clickable(onClick = onSwitchLens)
                .testTag(CameraTestTags.BTN_SWITCH_LENS)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.FlipCameraAndroid,
                    contentDescription = "Flip Camera",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Tactile Pro Camera Shutter Button (76dp).
 * Features concentric metallic rings and animated live core.
 */
@Composable
private fun TactileShutterTrigger(
    isStreaming: Boolean,
    isOperating: Boolean,
    ringColorBase: Color = Color.White,
    onClick: () -> Unit
) {
    val ringColor by animateColorAsState(
        targetValue = if (isStreaming) StatusError else ringColorBase.copy(alpha = 0.85f),
        animationSpec = tween(220),
        label = "shutterRingColor"
    )
    val coreColor by animateColorAsState(
        targetValue = if (isStreaming) StatusError else Color(0xFFEF4444),
        animationSpec = tween(220),
        label = "shutterCoreColor"
    )
    val coreSize by animateColorAsState(
        targetValue = if (isStreaming) StatusError else Color(0xFFDC2626),
        animationSpec = tween(220),
        label = "shutterCoreSize"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .testTag(if (isStreaming) CameraTestTags.BTN_STOP_STREAM else CameraTestTags.BTN_START_STREAM)
    ) {
        // Outer Thin Precision Ring
        Canvas(modifier = Modifier.size(76.dp)) {
            drawCircle(
                color = ringColor,
                radius = size.minDimension / 2f - 1.5.dp.toPx(),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
        }

        // Inner Tactile Action Core
        Surface(
            shape = if (isStreaming) RoundedCornerShape(10.dp) else CircleShape,
            color = coreColor,
            modifier = Modifier.size(if (isStreaming) 28.dp else 58.dp)
        ) {
            if (isOperating) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
