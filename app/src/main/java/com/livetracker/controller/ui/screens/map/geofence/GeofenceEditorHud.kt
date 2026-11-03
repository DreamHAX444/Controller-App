package com.livetracker.controller.ui.screens.map.geofence

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.livetracker.controller.core.tracker.geofence.domain.AlertSoundProfile
import com.livetracker.controller.core.tracker.geofence.domain.GeoFenceType
import com.livetracker.controller.core.tracker.geofence.domain.GeofenceTriggerRule

/** Curated, accessible high-contrast palette for geofence perimeters */
val GEOFENCE_PRESET_COLORS = listOf(
    0xFF10B981.toInt(), // Emerald Green
    0xFF2563EB.toInt(), // Sapphire Blue
    0xFFF43F5E.toInt(), // Sunset Coral
    0xFFF59E0B.toInt(), // Amber Gold
    0xFF8B5CF6.toInt(), // Royal Violet
    0xFF06B6D4.toInt()  // Cyan Teal
)

/** Common tactical presets for circle perimeter radius in meters */
val RADIUS_PRESETS_METERS = listOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2000.0)

/** Alarm duration presets in seconds */
val DURATION_PRESETS_SECONDS = listOf(10, 15, 30, 60)

/**
 * Interactive Geofence HUD for real-time vertex drawing and parameter configuration.
 * Supports polygon/circle geometry, selectable alert sound profiles, duration, trigger rules, and vibration.
 */
@Composable
fun GeofenceEditorHud(
    state: GeofenceEditorState.Editing,
    onNameChange: (String) -> Unit,
    onShapeChange: (GeoFenceType) -> Unit,
    onRadiusChange: (Double) -> Unit,
    onColorChange: (Int) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onTriggerRuleChange: (GeofenceTriggerRule) -> Unit = {},
    onSoundProfileChange: (AlertSoundProfile) -> Unit = {},
    onDurationChange: (Int) -> Unit = {},
    onVibrationChange: (Boolean) -> Unit = {},
    onTestSound: (AlertSoundProfile) -> Unit = {},
    onStopSound: () -> Unit = {},
    isTestingSound: Boolean = false,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    topPadding: Dp,
    startPadding: Dp,
    endPadding: Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Top HUD Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPadding, start = startPadding, end = endPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drawing Status Capsule with Undo/Reset actions
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusText = when (state.shapeType) {
                        GeoFenceType.POLYGON -> when {
                            state.vertices.isEmpty() -> "Tap map to add points"
                            state.vertices.size < 3 -> "${state.vertices.size} points • Need at least 3"
                            else -> "${state.vertices.size} vertices • Polygon closed"
                        }
                        GeoFenceType.CIRCLE -> when {
                            state.center == null -> "Tap map to place center"
                            else -> "Center placed • Radius: ${state.radiusMeters.toInt()}m"
                        }
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val hasGeometry = when (state.shapeType) {
                        GeoFenceType.POLYGON -> state.vertices.isNotEmpty()
                        GeoFenceType.CIRCLE -> state.center != null
                    }

                    if (hasGeometry) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(16.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )
                        Spacer(modifier = Modifier.width(4.dp))

                        if (state.shapeType == GeoFenceType.POLYGON) {
                            IconButton(
                                onClick = onUndo,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Undo last point",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = if (state.shapeType == GeoFenceType.CIRCLE) "Clear center" else "Clear all points",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Cancel / Exit Button
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                shadowElevation = 6.dp
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel geofence editor",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 2. Bottom Configuration Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (state.validationError != null) {
                    Text(
                        text = state.validationError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Shape Type Segmented Control (Polygon vs Circle)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val isPolygon = state.shapeType == GeoFenceType.POLYGON
                        val isCircle = state.shapeType == GeoFenceType.CIRCLE
                        val isExistingFence = state.id != null

                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (isPolygon) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable(enabled = !isExistingFence) {
                                    if (!isPolygon) onShapeChange(GeoFenceType.POLYGON)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Polygon ⬡",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isPolygon) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (isCircle) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .clickable(enabled = !isExistingFence) {
                                    if (!isCircle) onShapeChange(GeoFenceType.CIRCLE)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Circle ◯",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isCircle) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Name Input Field
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("Zone Name") },
                    placeholder = { Text("e.g. Warehouse, HQ, Restricted Perimeter") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Circle Radius Adjustment Section
                AnimatedVisibility(
                    visible = state.shapeType == GeoFenceType.CIRCLE,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Perimeter Radius",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val radiusDisplay = if (state.radiusMeters >= 1000.0) {
                                String.format("%.2f km", state.radiusMeters / 1000.0)
                            } else {
                                "${state.radiusMeters.toInt()} meters"
                            }
                            Text(
                                text = radiusDisplay,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(RADIUS_PRESETS_METERS) { preset ->
                                val isSelected = Math.abs(state.radiusMeters - preset) < 1.0
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onRadiusChange(preset) },
                                    label = {
                                        Text(
                                            text = if (preset >= 1000.0) "${(preset / 1000).toInt()}km" else "${preset.toInt()}m",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onRadiusChange((state.radiusMeters - 25.0).coerceAtLeast(10.0)) },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Decrease radius",
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Slider(
                                value = state.radiusMeters.toFloat().coerceIn(10f, 3000f),
                                onValueChange = { onRadiusChange(it.toDouble()) },
                                valueRange = 10f..3000f,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )

                            IconButton(
                                onClick = { onRadiusChange((state.radiusMeters + 25.0).coerceAtMost(50000.0)) },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Increase radius",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // --- 3. TRIGGER RULES ---
                Text(
                    text = "Alarm Trigger Condition",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val rules = listOf(
                        GeofenceTriggerRule.ENTRY to "Entry 🟢",
                        GeofenceTriggerRule.EXIT to "Exit 🔴",
                        GeofenceTriggerRule.BOTH to "Both 🔄"
                    )
                    rules.forEach { (rule, label) ->
                        val isSelected = state.triggerRule == rule
                        FilterChip(
                            selected = isSelected,
                            onClick = { onTriggerRuleChange(rule) },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // --- 4. SIREN SOUND PROFILE & TEST PREVIEW ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Alarm Siren Sound",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedButton(
                        onClick = {
                            if (isTestingSound) onStopSound() else onTestSound(state.soundProfile)
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (isTestingSound) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isTestingSound) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isTestingSound) "Stop preview" else "Test sound",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isTestingSound) "Stop" else "Test",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(AlertSoundProfile.entries) { profile ->
                        val isSelected = state.soundProfile == profile
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSoundProfileChange(profile) },
                            label = {
                                Text(
                                    text = profile.displayName,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // --- 5. ALARM DURATION & VIBRATION ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Playback Duration",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DURATION_PRESETS_SECONDS.forEach { sec ->
                                val isSelected = state.durationSeconds == sec
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onDurationChange(sec) },
                                    label = {
                                        Text(
                                            text = "${sec}s",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Vibration,
                                contentDescription = "Vibration toggle",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Vibrate",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        Switch(
                            checked = state.isVibrationEnabled,
                            onCheckedChange = onVibrationChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Curated Color Palette Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Accent Color",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        GEOFENCE_PRESET_COLORS.forEach { colorInt ->
                            val isSelected = state.colorArgb == colorInt
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable { onColorChange(colorInt) },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorInt)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected color",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Save Geofence Action
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = if (state.id != null) "Update Perimeter" else "Save Perimeter",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    )
                }
            }
        }
    }
}
