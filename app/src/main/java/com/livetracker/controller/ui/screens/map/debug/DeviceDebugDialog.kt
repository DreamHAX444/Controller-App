package com.livetracker.controller.ui.screens.map.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.livetracker.controller.core.tracker.alert.domain.Alert
import com.livetracker.controller.core.tracker.device.domain.TrackerDeviceSummary
import com.livetracker.controller.core.tracker.domain.ConnectionState
import com.livetracker.controller.core.tracker.domain.TrackerId
import com.livetracker.controller.core.tracker.geofence.domain.GeoFence
import java.util.Locale

/**
 * Developer Debug Control Center.
 * Provides real-time emulator tools to add virtual trackers, configure device parameters,
 * move devices with a directional D-pad, and teleport across geofences to trigger alerts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceDebugDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    deviceSummaries: List<TrackerDeviceSummary>,
    geoFences: List<GeoFence>,
    unacknowledgedAlerts: List<Alert>,
    currentMapLat: Double,
    currentMapLon: Double,
    onAddDevice: (id: String, name: String, model: String, lat: Double, lon: Double, battery: Int, speed: Float, bearing: Float?, state: ConnectionState) -> Unit,
    onMoveDevice: (id: TrackerId, lat: Double, lon: Double, speed: Float, bearing: Float?) -> Unit,
    onStepDevice: (id: TrackerId, deltaLat: Double, deltaLon: Double, speed: Float) -> Unit,
    onTeleportInsideGeofence: (id: TrackerId, fence: GeoFence) -> Unit,
    onTeleportOutsideGeofence: (id: TrackerId, fence: GeoFence) -> Unit,
    onToggleConnection: (id: TrackerId) -> Unit,
    onRemoveDevice: (id: TrackerId) -> Unit,
    onClearAllDevices: () -> Unit,
    onSpawnPresetFleet: () -> Unit,
    onFocusDeviceOnMap: (lat: Double, lon: Double) -> Unit
) {
    if (!isOpen) return

    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedTargetDeviceId by remember(deviceSummaries) {
        mutableStateOf(deviceSummaries.firstOrNull()?.device?.deviceId)
    }

    // Step size for directional D-pad: Small (0.0005° ~ 55m), Medium (0.002° ~ 220m), Large (0.01° ~ 1.1km)
    var selectedStepIndex by remember { mutableIntStateOf(1) }
    val stepDeltas = listOf(0.0005, 0.002, 0.01)
    val stepLabels = listOf("50m", "220m", "1.1km")

    // State for Add Device form
    var newDeviceId by remember { mutableStateOf("dev_${(System.currentTimeMillis() % 10000)}") }
    var newDeviceName by remember { mutableStateOf("Virtual Tracker 1") }
    var newDeviceModel by remember { mutableStateOf("Pixel 8 Pro") }
    var newLatText by remember { mutableStateOf(String.format(Locale.US, "%.5f", currentMapLat)) }
    var newLonText by remember { mutableStateOf(String.format(Locale.US, "%.5f", currentMapLon)) }
    var newBattery by remember { mutableFloatStateOf(88f) }
    var newSpeed by remember { mutableFloatStateOf(20f) }
    var newIsOnline by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Build,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Developer Debug Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Virtual Tracker Engine & Geofence Triggers",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live Alerts Banner (if any)
                AnimatedVisibility(
                    visible = unacknowledgedAlerts.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val latest = unacknowledgedAlerts.firstOrNull()
                    if (latest != null) {
                        Surface(
                            color = Color(0xFFD32F2F).copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD32F2F).copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFD32F2F),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Alert Fired: ${latest.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Add Tracker", style = MaterialTheme.typography.labelMedium) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                "Move & Test (${deviceSummaries.size})",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Presets", style = MaterialTheme.typography.labelMedium) }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // Tab Content
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> AddTrackerTab(
                            deviceId = newDeviceId,
                            onDeviceIdChange = { newDeviceId = it },
                            onRegenerateId = { newDeviceId = "dev_${(System.currentTimeMillis() % 10000)}" },
                            displayName = newDeviceName,
                            onDisplayNameChange = { newDeviceName = it },
                            model = newDeviceModel,
                            onModelChange = { newDeviceModel = it },
                            latText = newLatText,
                            onLatTextChange = { newLatText = it },
                            lonText = newLonText,
                            onLonTextChange = { newLonText = it },
                            onUseMapCenter = {
                                newLatText = String.format(Locale.US, "%.5f", currentMapLat)
                                newLonText = String.format(Locale.US, "%.5f", currentMapLon)
                            },
                            battery = newBattery,
                            onBatteryChange = { newBattery = it },
                            speed = newSpeed,
                            onSpeedChange = { newSpeed = it },
                            isOnline = newIsOnline,
                            onIsOnlineChange = { newIsOnline = it },
                            onSubmit = {
                                val lat = newLatText.toDoubleOrNull() ?: currentMapLat
                                val lon = newLonText.toDoubleOrNull() ?: currentMapLon
                                onAddDevice(
                                    newDeviceId,
                                    newDeviceName,
                                    newDeviceModel,
                                    lat,
                                    lon,
                                    newBattery.toInt(),
                                    newSpeed,
                                    0f,
                                    if (newIsOnline) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
                                )
                                // Auto reset ID for next device
                                newDeviceId = "dev_${(System.currentTimeMillis() % 10000)}"
                                newDeviceName = "Virtual Tracker ${(deviceSummaries.size + 2)}"
                                selectedTab = 1
                            }
                        )

                        1 -> MoveAndTestTab(
                            deviceSummaries = deviceSummaries,
                            selectedDeviceId = selectedTargetDeviceId,
                            onSelectDevice = { selectedTargetDeviceId = it },
                            selectedStepIndex = selectedStepIndex,
                            stepLabels = stepLabels,
                            onSelectStepIndex = { selectedStepIndex = it },
                            onStep = { dLat, dLon ->
                                val devId = selectedTargetDeviceId ?: return@MoveAndTestTab
                                onStepDevice(devId, dLat, dLon, 15f)
                            },
                            stepDelta = stepDeltas[selectedStepIndex],
                            geoFences = geoFences,
                            onTeleportInside = { fence ->
                                val devId = selectedTargetDeviceId ?: return@MoveAndTestTab
                                onTeleportInsideGeofence(devId, fence)
                            },
                            onTeleportOutside = { fence ->
                                val devId = selectedTargetDeviceId ?: return@MoveAndTestTab
                                onTeleportOutsideGeofence(devId, fence)
                            },
                            onToggleConnection = {
                                val devId = selectedTargetDeviceId ?: return@MoveAndTestTab
                                onToggleConnection(devId)
                            },
                            onRemoveDevice = {
                                val devId = selectedTargetDeviceId ?: return@MoveAndTestTab
                                onRemoveDevice(devId)
                            },
                            onFocusOnMap = {
                                val summary = deviceSummaries.find { it.device.deviceId == selectedTargetDeviceId }
                                val loc = summary?.latestLocation
                                if (loc != null) {
                                    onFocusDeviceOnMap(loc.latitude, loc.longitude)
                                    onDismiss()
                                }
                            },
                            onSwitchToAdd = { selectedTab = 0 }
                        )

                        2 -> PresetsTab(
                            deviceCount = deviceSummaries.size,
                            onSpawnFleet = {
                                onSpawnPresetFleet()
                                selectedTab = 1
                            },
                            onClearAll = onClearAllDevices
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTrackerTab(
    deviceId: String,
    onDeviceIdChange: (String) -> Unit,
    onRegenerateId: () -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    latText: String,
    onLatTextChange: (String) -> Unit,
    lonText: String,
    onLonTextChange: (String) -> Unit,
    onUseMapCenter: () -> Unit,
    battery: Float,
    onBatteryChange: (Float) -> Unit,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    isOnline: Boolean,
    onIsOnlineChange: (Boolean) -> Unit,
    onSubmit: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = onDeviceIdChange,
                    label = { Text("Device ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors()
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(onClick = onRegenerateId) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "New ID", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Auto", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        item {
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("Display Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text("Hardware Model / Category:", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            val models = listOf("Pixel 8 Pro", "Galaxy S24", "OBD-II Car Tracker", "Pet Beacon", "Cargo Asset Tag")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(models) { m ->
                        FilterChip(
                            selected = model == m,
                            onClick = { onModelChange(m) },
                            label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors()
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Initial Coordinates:", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = onUseMapCenter) {
                    Icon(imageVector = Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Use Map Center", style = MaterialTheme.typography.labelSmall)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = onLatTextChange,
                    label = { Text("Latitude") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = onLonTextChange,
                    label = { Text("Longitude") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Battery Level: ${battery.toInt()}%", style = MaterialTheme.typography.labelMedium)
                Text("Speed: ${speed.toInt()} km/h", style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = battery,
                onValueChange = onBatteryChange,
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
            Slider(
                value = speed,
                onValueChange = onSpeedChange,
                valueRange = 0f..120f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Initial Status:", style = MaterialTheme.typography.labelMedium)
                FilterChip(
                    selected = isOnline,
                    onClick = { onIsOnlineChange(true) },
                    label = { Text("Online (Live)") }
                )
                FilterChip(
                    selected = !isOnline,
                    onClick = { onIsOnlineChange(false) },
                    label = { Text("Offline") }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Tracker to Map", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MoveAndTestTab(
    deviceSummaries: List<TrackerDeviceSummary>,
    selectedDeviceId: TrackerId?,
    onSelectDevice: (TrackerId) -> Unit,
    selectedStepIndex: Int,
    stepLabels: List<String>,
    onSelectStepIndex: (Int) -> Unit,
    onStep: (deltaLat: Double, deltaLon: Double) -> Unit,
    stepDelta: Double,
    geoFences: List<GeoFence>,
    onTeleportInside: (GeoFence) -> Unit,
    onTeleportOutside: (GeoFence) -> Unit,
    onToggleConnection: () -> Unit,
    onRemoveDevice: () -> Unit,
    onFocusOnMap: () -> Unit,
    onSwitchToAdd: () -> Unit
) {
    if (deviceSummaries.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No Virtual Trackers Active",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Add a new device using the 'Add Tracker' tab or spawn test presets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onSwitchToAdd) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Device Now")
            }
        }
        return
    }

    val activeSummary = deviceSummaries.find { it.device.deviceId == selectedDeviceId }
        ?: deviceSummaries.first()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Device Selector Row
        item {
            Text("Select Tracker to Control:", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(deviceSummaries) { summary ->
                    val isSelected = summary.device.deviceId == activeSummary.device.deviceId
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectDevice(summary.device.deviceId) },
                        label = {
                            Text(
                                summary.device.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }
        }

        // Active Telemetry Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeSummary.device.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (activeSummary.device.connectionState == ConnectionState.CONNECTED) {
                                Color(0xFF2E7D32).copy(alpha = 0.15f)
                            } else {
                                Color(0xFFC62828).copy(alpha = 0.15f)
                            }
                        ) {
                            Text(
                                text = activeSummary.device.connectionState.name,
                                color = if (activeSummary.device.connectionState == ConnectionState.CONNECTED) Color(0xFF2E7D32) else Color(0xFFC62828),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    val loc = activeSummary.latestLocation
                    Text(
                        text = "Lat: ${String.format(Locale.US, "%.5f", loc?.latitude ?: 0.0)} | Lon: ${String.format(Locale.US, "%.5f", loc?.longitude ?: 0.0)}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Speed: ${loc?.speed ?: 0f} km/h | Bearing: ${loc?.bearing ?: 0f}° | Battery: ${activeSummary.device.batteryLevel ?: 0}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = onFocusOnMap,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Focus on Map", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = onToggleConnection,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Toggle Status", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = onRemoveDevice) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }

        // Directional D-Pad
        item {
            Text("Directional Movement Controls (Live Step):", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))

            // Step size selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                stepLabels.forEachIndexed { index, label ->
                    FilterChip(
                        selected = selectedStepIndex == index,
                        onClick = { onSelectStepIndex(index) },
                        label = { Text("Step: $label", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // D-Pad Grid
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // North Button
                Button(
                    onClick = { onStep(stepDelta, 0.0) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(width = 110.dp, height = 40.dp)
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "North")
                    Text("North", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(6.dp))

                // West / Center / East Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onStep(0.0, -stepDelta) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(width = 100.dp, height = 40.dp)
                    ) {
                        Icon(imageVector = Icons.Default.KeyboardArrowLeft, contentDescription = "West")
                        Text("West", style = MaterialTheme.typography.labelSmall)
                    }

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("D-Pad", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                        }
                    }

                    Button(
                        onClick = { onStep(0.0, stepDelta) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(width = 100.dp, height = 40.dp)
                    ) {
                        Text("East", style = MaterialTheme.typography.labelSmall)
                        Icon(imageVector = Icons.Default.KeyboardArrowRight, contentDescription = "East")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // South Button
                Button(
                    onClick = { onStep(-stepDelta, 0.0) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(width = 110.dp, height = 40.dp)
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "South")
                    Text("South", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Geofence Trigger Testing
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(6.dp))
            Text("Geofence Trigger Testing (Teleport across boundaries):", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(6.dp))

            if (geoFences.isEmpty()) {
                Text(
                    text = "No geofences saved. Create a geofence in the Map Top Bar to test entry/exit alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    geoFences.forEach { fence ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(Color(fence.colorArgb), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(fence.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(if (fence.isEnabled) "ACTIVE" else "DISABLED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = { onTeleportInside(fence) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Move INSIDE (Trigger Enter)", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Button(
                                        onClick = { onTeleportOutside(fence) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Move OUTSIDE (Trigger Exit)", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetsTab(
    deviceCount: Int,
    onSpawnFleet: () -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Quick Test Fleet Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Quickly spawn a set of 3 realistic tracker devices positioned around known test zones to test multi-device paging, filtering, and geofence triggers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = onSpawnFleet,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Spawn 3 Test Trackers (Fleet Preset)")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Text("Active Devices in Repository: $deviceCount", style = MaterialTheme.typography.bodyMedium)

        OutlinedButton(
            onClick = onClearAll,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear All Virtual Trackers")
        }
    }
}
