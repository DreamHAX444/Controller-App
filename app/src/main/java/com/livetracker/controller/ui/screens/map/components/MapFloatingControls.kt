package com.livetracker.controller.ui.screens.map.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.ui.screens.map.config.MapConfiguration
import com.livetracker.controller.ui.screens.map.config.MapProvider
import com.livetracker.controller.ui.screens.map.config.MapStyle

/**
 * Ultra-Compact, Minimalist Tactical Map Action Dock.
 * Designed for one-handed operational ergonomics:
 * - 40dp capsule geometry elevated smoothly above map clutter.
 * - Hairline border with frosted glass surface.
 * - Tactile spring-physics press feedback.
 * - 4 core operational actions: Layer Switcher, Recenter on Target, Zoom In, Zoom Out.
 */
@Composable
fun MapFloatingControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onRecenter: () -> Unit,
    onOpenLayerPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glassSurface = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    val glassBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = glassSurface,
        border = BorderStroke(1.dp, glassBorder),
        shadowElevation = 6.dp,
        tonalElevation = 2.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp)
        ) {
            // 1. Layer Quick Switcher
            FloatingControlButton(
                icon = Icons.Default.Layers,
                contentDescription = "Map Layers",
                onClick = onOpenLayerPicker
            )

            HorizontalDivider(
                modifier = Modifier
                    .width(22.dp)
                    .padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                thickness = 0.5.dp
            )

            // 2. Recenter Camera on Selected Tracker / Target
            FloatingControlButton(
                icon = Icons.Default.MyLocation,
                contentDescription = "Recenter on Target",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onRecenter
            )

            HorizontalDivider(
                modifier = Modifier
                    .width(22.dp)
                    .padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                thickness = 0.5.dp
            )

            // 3. Zoom In (+)
            FloatingControlButton(
                icon = Icons.Default.Add,
                contentDescription = "Zoom In",
                onClick = onZoomIn
            )

            HorizontalDivider(
                modifier = Modifier
                    .width(22.dp)
                    .padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                thickness = 0.5.dp
            )

            // 4. Zoom Out (-)
            FloatingControlButton(
                icon = Icons.Default.Remove,
                contentDescription = "Zoom Out",
                onClick = onZoomOut
            )
        }
    }
}

@Composable
private fun FloatingControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "controlPressScale"
    )

    Box(
        modifier = modifier
            .size(38.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * Minimalist, Sophisticated Basemap Appearance Modal.
 * High-precision 2x2 tactical layout for instant 1-tap layer switching.
 */
@Composable
fun MapLayersSheet(
    isVisible: Boolean,
    currentConfiguration: MapConfiguration,
    onSelectConfiguration: (MapConfiguration) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(160)) + slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f)
        ) + scaleIn(initialScale = 0.94f),
        exit = fadeOut(tween(140)) + slideOutVertically(
            targetOffsetY = { it / 3 },
            animationSpec = tween(160)
        ) + scaleOut(targetScale = 0.94f),
        modifier = modifier
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.40f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Map Basemap",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.5.sp,
                                letterSpacing = 0.2.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "4 operational appearance presets",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                    thickness = 0.5.dp
                )

                // 2x2 Tactical Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LayerTile(
                        title = "Streets",
                        subtitle = "OSM Network",
                        icon = Icons.Default.Place,
                        isSelected = currentConfiguration.provider == MapProvider.OPEN_STREET_MAP &&
                                currentConfiguration.style == MapStyle.STANDARD,
                        onClick = {
                            onSelectConfiguration(MapConfiguration(MapProvider.OPEN_STREET_MAP, MapStyle.STANDARD))
                        },
                        modifier = Modifier.weight(1f)
                    )

                    LayerTile(
                        title = "Satellite",
                        subtitle = "Esri Orbital",
                        icon = Icons.Default.Public,
                        isSelected = currentConfiguration.provider == MapProvider.ESRI_ARCGIS &&
                                currentConfiguration.style == MapStyle.SATELLITE,
                        onClick = {
                            onSelectConfiguration(MapConfiguration(MapProvider.ESRI_ARCGIS, MapStyle.SATELLITE))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LayerTile(
                        title = "Tactical Night",
                        subtitle = "Low-Light Dark",
                        icon = Icons.Default.DarkMode,
                        isSelected = currentConfiguration.provider == MapProvider.OPEN_STREET_MAP &&
                                currentConfiguration.style == MapStyle.DARK,
                        onClick = {
                            onSelectConfiguration(MapConfiguration(MapProvider.OPEN_STREET_MAP, MapStyle.DARK))
                        },
                        modifier = Modifier.weight(1f)
                    )

                    LayerTile(
                        title = "Topographic",
                        subtitle = "Elevation Relief",
                        icon = Icons.Default.Terrain,
                        isSelected = currentConfiguration.provider == MapProvider.OPEN_STREET_MAP &&
                                currentConfiguration.style == MapStyle.TERRAIN,
                        onClick = {
                            onSelectConfiguration(MapConfiguration(MapProvider.OPEN_STREET_MAP, MapStyle.TERRAIN))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
    }

    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f),
                modifier = Modifier.size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(9.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 12.sp
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Active",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(9.dp)
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
