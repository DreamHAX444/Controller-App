package com.livetracker.controller.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.ServicePermission
import com.livetracker.controller.core.auth.domain.User
import com.livetracker.controller.ui.components.UserProfileAvatar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.navigation.Destination
import com.livetracker.controller.navigation.adminDestinations
import com.livetracker.controller.navigation.serviceDestinations
import com.livetracker.controller.navigation.systemDestinations
import com.livetracker.controller.ui.theme.StatusLive

// Hoisted static pillars list: zero allocations during recompositions
private val PrimaryPillars = listOf(
    Destination.Map,
    Destination.Devices,
    Destination.Alerts,
    Destination.Diagnostics,
    Destination.Settings
)

// Hoisted secondary destinations list
private val SecondaryDestinations = serviceDestinations + adminDestinations + (systemDestinations.filter { it != Destination.Settings })

/**
 * Pure RBAC decision function determining the visible primary navigation pillars.
 */
fun computePrimaryPillars(role: Role?): List<Destination> {
    return when (role) {
        Role.VISITOR, null -> listOf(
            Destination.Map,
            Destination.Settings
        )
        Role.USER -> listOf(
            Destination.Map,
            Destination.Devices,
            Destination.Alerts,
            Destination.Settings
        )
        Role.ADMIN, Role.OWNER -> listOf(
            Destination.Map,
            Destination.Devices,
            Destination.Alerts,
            Destination.Diagnostics,
            Destination.Settings
        )
    }
}

/**
 * Pure RBAC decision function determining secondary advanced tools.
 * - Visitors & null sessions receive an empty list (fail-closed).
 * - Standard users only receive tools corresponding to explicitly granted permissions + Updates.
 * - Destination.Users is STRICTLY reserved for Fleet Admins and Owners.
 */
fun computeSecondaryDestinations(user: User?): List<Destination> {
    return when (user?.role) {
        Role.VISITOR, null -> emptyList()
        Role.USER -> {
            val allowedServices = user.devicePermissions.flatMap { it.allowedServices }.toSet()
            val tools = mutableListOf<Destination>()
            if (ServicePermission.CAMERA in allowedServices) {
                tools.add(Destination.Camera)
            }
            if (ServicePermission.MICROPHONE_AUDIO in allowedServices || ServicePermission.DEVICE_AUDIO in allowedServices) {
                tools.add(Destination.Audio)
            }
            if (ServicePermission.SCREEN_CAPTURE in allowedServices) {
                tools.add(Destination.ScreenCapture)
            }
            if (ServicePermission.FILE_READ in allowedServices || ServicePermission.FILE_TRANSFER in allowedServices) {
                tools.add(Destination.Files)
            }
            // Updates is default for all operational users
            tools.add(Destination.Updates)
            tools
        }
        Role.ADMIN, Role.OWNER -> listOf(
            Destination.Camera,
            Destination.Audio,
            Destination.ScreenCapture,
            Destination.Files,
            Destination.Users,
            Destination.Updates
        )
    }
}

/**
 * Ultra-high-performance, buttery smooth Navigation Drawer for Trace Controller.
 * Built with rock-solid 60 FPS optimizations adhering to UI/UX Pro Max intelligence:
 * - GPU Hardware Layer isolation (`graphicsLayer { clip = true }`) for zero-jank translation.
 * - Draw-phase isolated live status pulse (zero main-thread CPU recompositions).
 * - Allocation-free item styling with fluid color transitions (`animateColorAsState`).
 * - Zero nested scroll conflicts on horizontal swipe gestures.
 * - Precision component alignment, tactical branding, and balanced vertical rhythm.
 */
@Composable
fun TraceAppDrawer(
    currentRoute: String?,
    onNavigate: (Destination) -> Unit,
    onLogout: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    currentUser: User? = null
) {
    // State for collapsible secondary tools
    var areToolsExpanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (areToolsExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "chevronRotation"
    )

    val primaryPillars = remember(currentUser?.role) { computePrimaryPillars(currentUser?.role) }
    val secondaryDestinations = remember(currentUser) { computeSecondaryDestinations(currentUser) }

    val scrollState = rememberScrollState()

    ModalDrawerSheet(
        modifier = modifier
            .width(300.dp)
            .graphicsLayer {
                // Promotes entire drawer sheet to an isolated GPU hardware layer.
                // When dragging/translating, the GPU renders the cached texture without child repainting.
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                clip = true
            },
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 0.dp,
        windowInsets = DrawerDefaults.windowInsets
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // -------------------------------------------------------------
            // 1. BRAND HEADER & INLINE LIVE STATUS
            // -------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tactical Rounded Shield Emblem
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Trace Emblem",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Trace",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.5.sp,
                                letterSpacing = 0.4.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Soft, isolated Live Indicator Pill (Draw-phase alpha, zero recomposition)
                        Surface(
                            shape = CircleShape,
                            color = StatusLive.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, StatusLive.copy(alpha = 0.28f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SoftLivePulseDot()
                                Spacer(modifier = Modifier.width(4.5.dp))
                                Text(
                                    text = "Online",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.3.sp
                                    ),
                                    color = StatusLive
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(1.dp))

                    Text(
                        text = "Fleet Command",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.5.sp,
                            letterSpacing = 0.2.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                    )
                }

                // Close Button with accessible touch bounds
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(40.dp)
                ) {
                    IconButton(
                        onClick = onCloseDrawer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close navigation drawer",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                thickness = 1.dp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // -------------------------------------------------------------
            // 2. NAVIGATION CONTENT (Zero nested scroll conflict when collapsed)
            // -------------------------------------------------------------
            val contentModifier = if (areToolsExpanded) {
                Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
            } else {
                Modifier.weight(1f)
            }

            Column(modifier = contentModifier) {
                // Section Label: perfectly aligned with item start margin
                Text(
                    text = "NAVIGATION",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        letterSpacing = 1.2.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f),
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 6.dp)
                )

                // The Core Pillars
                primaryPillars.forEach { destination ->
                    val isSelected = currentRoute == destination.route
                    MinimalDrawerItem(
                        destination = destination,
                        isSelected = isSelected,
                        onClick = { onNavigate(destination) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (secondaryDestinations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                        modifier = Modifier.padding(horizontal = 4.dp),
                        thickness = 1.dp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // -------------------------------------------------------------
                    // 3. COLLAPSIBLE ADVANCED TOOLS & SERVICES
                    // -------------------------------------------------------------
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { areToolsExpanded = !areToolsExpanded }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(18.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = "Advanced Tools",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                            ) {
                                Text(
                                    text = "${secondaryDestinations.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = if (areToolsExpanded) "Collapse tools" else "Expand tools",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(chevronRotation)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = areToolsExpanded,
                        enter = expandVertically(animationSpec = spring(stiffness = 500f, dampingRatio = 0.8f)) + fadeIn(tween(180)),
                        exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(tween(120))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            secondaryDestinations.forEach { dest ->
                                val isSelected = currentRoute == dest.route
                                SecondaryToolItem(
                                    destination = dest,
                                    isSelected = isSelected,
                                    onClick = { onNavigate(dest) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // -------------------------------------------------------------
            // 4. OPERATOR IDENTITY & LOCK SESSION FOOTER (Tap to open Account Profile)
            // -------------------------------------------------------------
            val isProfileSelected = currentRoute == Destination.Profile.route
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isProfileSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isProfileSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onNavigate(Destination.Profile) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Operator / User Avatar
                    UserProfileAvatar(
                        avatarUrl = currentUser?.avatarUrl,
                        displayName = currentUser?.displayName ?: "Visitor",
                        role = currentUser?.role ?: Role.VISITOR,
                        size = 36.dp
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentUser?.displayName ?: "Operator",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5.sp
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(StatusLive)
                            )
                            Spacer(modifier = Modifier.width(4.5.dp))
                            Text(
                                text = "${currentUser?.role?.name ?: "VISITOR"} • View Profile",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f)
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        IconButton(
                            onClick = onLogout,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Lock session / Log out",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Version Watermark
            Text(
                text = "Trace Controller • v1.0.0",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 2.dp)
            )
        }
    }
}

/**
 * Isolated live pulse dot running purely on the RenderThread drawing phase.
 * Zero CPU recompositions triggered.
 */
@Composable
private fun SoftLivePulseDot(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer { alpha = pulseAlpha }
            .clip(CircleShape)
            .background(StatusLive)
    )
}

/**
 * Minimalist, modern navigation item for primary destinations.
 * Adheres to UI/UX Pro Max tactical dark aesthetic with zero-shift active indicator.
 */
@Composable
private fun MinimalDrawerItem(
    destination: Destination,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(180),
        label = "itemBgColor"
    )

    val animatedIconTint by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
        },
        animationSpec = tween(180),
        label = "itemIconTint"
    )

    val animatedTextColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        },
        animationSpec = tween(180),
        label = "itemTextColor"
    )

    val borderStroke = if (isSelected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    } else {
        null
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = animatedBgColor,
        border = borderStroke,
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left active indicator: keeps icon alignment identical whether selected or unselected!
            // When selected: 3.5dp bar + 8.5dp spacing = 12dp
            // When unselected: 12dp spacing
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.5.dp))
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }

            Icon(
                imageVector = destination.icon,
                contentDescription = destination.title,
                tint = animatedIconTint,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = destination.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 13.5.sp,
                    letterSpacing = 0.2.sp
                ),
                color = animatedTextColor,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                )
            }
        }
    }
}

/**
 * Compact, lightweight row for secondary tools and admin views.
 * Indented slightly to demonstrate clear hierarchical relationship under Advanced Tools.
 */
@Composable
private fun SecondaryToolItem(
    destination: Destination,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedBgColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(180),
        label = "secToolBgColor"
    )

    val animatedContentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        },
        animationSpec = tween(180),
        label = "secToolContentColor"
    )

    val borderStroke = if (isSelected) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
    } else {
        null
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = animatedBgColor,
        border = borderStroke,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zero-shift left alignment: 3dp bar + 7dp spacing = 10dp
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(7.dp))
            } else {
                Spacer(modifier = Modifier.width(10.dp))
            }

            Icon(
                imageVector = destination.icon,
                contentDescription = destination.title,
                tint = animatedContentColor,
                modifier = Modifier.size(17.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = destination.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 12.5.sp
                ),
                color = animatedContentColor,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                )
            }
        }
    }
}
