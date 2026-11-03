# Trace Controller — Design System Specification

## 1. Design Philosophy & Vision

The Trace Controller App is a mobile-first, real-time tactical asset and location tracking dashboard.
The interface is engineered around precision, high clarity, and zero visual noise:
- **Map-First Experience**: The map canvas occupies 100% of the screen. All UI components float gracefully with elevation, subtle translucent borders, and responsive edge-to-edge insets.
- **Unified Visual Identity**: Every floating card, bottom sheet, HUD, badge, and selector shares the exact same curvature hierarchy, surface palette, border stroke, elevation, and 8-point spacing grid.
- **Honest Real-Time Telemetry**: Never fake status indicators. Live items pulse emerald; connecting items glow amber; offline items remain calm slate. Batteries, network state, and speed are accurately qualified.

---

## 2. Color Palette & Surface Tokens

### Surfaces & Backgrounds
| Token | Light Theme | Dark Theme | Purpose |
|:---|:---|:---|:---|
| `Surface Container` (Cards) | `#FFFFFF` | `#111827` (`TraceSurfaceDark`) | Base floating card background for all dialogs, sheets, and HUDs |
| `Surface Elevated` | `#F8FAFC` | `#192134` | Elevated capsules and floating action buttons |
| `Tile Neutral` | `#F1F5F9` (alpha 0.65) | `#1E293B` (alpha 0.35) | Unselected list items, device rows, and geofence rows |
| `Tile Active / Selected` | `#DBEAFE` (alpha 0.40) | `#1E3A8A` (alpha 0.30) | Selected device rows, active filters |
| `Card Border Stroke` | `#E2E8F0` (alpha 0.50) | `#334155` (alpha 0.35) | Outer card boundary (1.dp) |
| `Tile Border Stroke` | `#E2E8F0` (alpha 0.35) | `#334155` (alpha 0.25) | Inner row boundary (1.dp) |
| `Divider Color` | `#E2E8F0` (alpha 0.35) | `#334155` (alpha 0.25) | Subtle horizontal section divider (1.dp) |

### Brand & Accents
| Token | Hex Value | Purpose |
|:---|:---|:---|
| `Primary` | `#1E40AF` (Light) / `#60A5FA` (Dark) | Primary actions, checkmarks, selected highlights, active focus |
| `PrimaryContainer` | `#DBEAFE` (Light) / `#1E3A8A` (Dark) | Badge backgrounds, action buttons |
| `OnPrimaryContainer` | `#1E3A8A` (Light) / `#DBEAFE` (Dark) | Primary button text & icons |

### Semantic Status Tokens
| State | Color Token | Hex | Badge Container | Purpose |
|:---|:---|:---|:---|:---|
| **Live / Online** | `StatusLive` | `#16A34A` / `#10B981` | `#DCFCE7` (15% alpha) | Connected devices, active geofences, moving assets |
| **Connecting / Stale** | `StatusConnecting` | `#D97706` / `#F59E0B` | `#FEF3C7` (15% alpha) | Reconnecting, WebRTC negotiating, location stale |
| **Offline / Degraded** | `StatusOffline` | `#64748B` | `#F1F5F9` (15% alpha) | Disconnected devices, disabled geofences |
| **Critical / Error** | `StatusError` | `#DC2626` / `#EF4444` | `#FEE2E2` (15% alpha) | Battery < 20%, delete confirmation, geofence breaches |

---

## 3. Curvature & Geometry Hierarchy (Corner Radii)

To prevent visual discordance, all UI elements must use the canonical curvature scale:

| Level | Radius | Applied To |
|:---|:---|:---|
| **Outer Floating Card** | **`20.dp`** | `DeviceSelectionSheet`, `DeviceTelemetryCard`, `GeofenceListSheet`, `GeofenceEditorHud` (Bottom Card) |
| **Inner Tile / Row** | **`12.dp`** | Device item rows, "All Trackers" tile, Geofence item rows, Mode switch capsule |
| **Action Button** | **`10.dp` - `12.dp`** | "Focus" button, "Update Perimeter" button, "Save" button |
| **Micro Badge / Tag** | **`6.dp`** | Device count chips, Location state badges (`LIVE`, `STALE`), Telemetry units |
| **Capsule / Pill** | **`24.dp`** | Center top bar device selector, Drawing HUD status pill, Mode switch pills |
| **Icon / Circle Action** | `CircleShape` | Close buttons, status dots, color picker chips, checkmark badges |

---

## 4. Elevation & Shadow Tokens

- **Floating Overlay Sheets**: `elevation = 8.dp` - `10.dp` (provides high contrast separation against map tiles).
- **Top Bar & Action Buttons**: `shadowElevation = 4.dp` - `6.dp`.
- **Inner Tiles**: `0.dp` (flat surface embedded within the card container).

---

## 5. Spacing & Padding System (8-Point Rhythm)

- **Card Outer Horizontal Margin**: `12.dp`
- **Card Inner Padding**: `16.dp`
- **Header-to-Content Spacing**: `12.dp`
- **Section Spacing**: `14.dp`
- **Vertical Spacing Between List Items**: `8.dp`
- **Tile Inner Padding**: `horizontal = 14.dp, vertical = 10.dp`
- **Icon-to-Text Gap**: `8.dp`
- **Minimum Touch Target**: `48.dp` for primary actions; `32.dp` icon buttons nested within padded touch containers.

---

## 6. Typography Scale

- **Card Header Title**: `MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)` (16sp)
- **Item Primary Name**: `MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)` (14sp)
- **Item Secondary / Subtitle**: `MaterialTheme.typography.bodySmall` / `labelSmall` (11-12sp, `onSurfaceVariant`)
- **Telemetry Value**: `MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp)`
- **Telemetry Label (Overline)**: `MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp)`
- **Coordinates (GPS)**: `MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)`
- **Micro Badges**: `MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp - 11.sp)`

---

## 7. Component Structure & Templates

### A. Floating Outer Card Template
```kotlin
Card(
    modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
    ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
) {
    Column(modifier = Modifier.padding(16.dp)) {
        // Card Content
    }
}
```

### B. Inner Interactive Tile Template
```kotlin
val tileBg by animateColorAsState(
    targetValue = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    },
    label = "TileBg"
)

Surface(
    shape = RoundedCornerShape(12.dp),
    color = tileBg,
    border = BorderStroke(
        1.dp,
        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    ),
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tile Content
    }
}
```

### C. Standard Micro Status Badge
```kotlin
Surface(
    shape = RoundedCornerShape(6.dp),
    color = statusColor.copy(alpha = 0.15f)
) {
    Text(
        text = statusLabel,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        ),
        color = statusColor,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
```

---

## 8. Anti-Patterns to Avoid
1. **Never use ad-hoc corner radii**: Avoid mixing `14.dp`, `18.dp`, and `22.dp` randomly. All cards are `20.dp`; all tiles are `12.dp`; all badges are `6.dp`.
2. **Never use pure black or pure white un-themed surfaces**: Always bind to `MaterialTheme.colorScheme.surface` and `surfaceVariant`.
3. **Never hardcode raw hex values in components**: Use defined color tokens from `Color.kt` and `Theme.kt`.
4. **Never omit exit animations**: All cards must transition smoothly with `AnimatedVisibility(slideIn + fadeIn / slideOut + fadeOut)`.
5. **Never use emojis as icons**: Always use Google Material Icons or Android Vector Drawables.
