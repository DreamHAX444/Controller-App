# Trace Controller — Master UI/UX Design & Architecture Blueprint

## 1. Executive Design Philosophy
Trace Controller is designed around a **Minimalist, Sophisticated, High-Precision Operational Aesthetic**. Every screen prioritizes clarity, zero visual clutter, instantaneous state feedback, and fluid 60–120 FPS spring-physics motion.

### Core Principles
1. **Two Distinct Spatial Modes**:
   - **Mode A — Immersive Map HUD (`MapScreen`)**: 100% edge-to-edge spatial canvas with floating glassmorphic controls:
     - **Top Left**: Navigation Drawer Launcher (`48.dp` circular floating button).
     - **Top Center**: Dynamic Island Tracker Capsule (displays active selection state, live connection pulse, and supports **tap** to open the tracker selector dropdown and **horizontal swipe** to cycle through active trackers).
     - **Top Right**: Geofence Perimeter Manager Launcher (`48.dp` circular floating button with active zone badge).
     - **Bottom**: Synchronized Live Device Telemetry Pager (`HorizontalPager` with scale/alpha physics, **horizontal swipe** to switch active trackers, **vertical swipe-down** to dismiss, **double-tap** to deep-zoom, and 1-tap quick service launchers).
   - **Mode B — Structured Operational Shell (`MainShellScreen`)**: Clean, distraction-free operational workspace with a unified `TopAppBar`, quick contextual actions, and floating bottom navigation dock (`TraceBottomBar`).

2. **Strict Component Placement & Information Architecture**:
   - **No Misplaced Components**: Each screen has a single, well-defined operational responsibility:
     - **`MapScreen` (`map`)**: Live spatial tracking, multi-device filtering, geofence drawing/editing, and real-time device telemetry HUD.
     - **`ProfileScreen` (`system/profile`)**: Dedicated **User Account & Security Profile** page (separated from `SettingsScreen`). Accessible directly by tapping the operator profile card in the Sidebar (`TraceAppDrawer`) or from the Settings overview header. Displays operator identity, UID copy, Role & Status badges, Local PIN Security status, Assigned Fleet & Effective Permissions summary, and Session Sign-Out.
     - **`SettingsScreen` (`system/settings`)**: Dedicated exclusively to **Application Configuration**:
       1. **Theme & Color Studio**: Theme Mode (`System`, `Dark`, `Light`), Material You **System Dynamic Colors** toggle, **Curated Accent Presets** (`Sapphire`, `Emerald`, `Violet`, `Amber`, `Rose`, `Cyan`), and an interactive **Custom Hue Color Slider** (`0°–360°`).
       2. **Map Engine & Basemap Appearance**: Provider selection (`OpenStreetMap`, `Esri World Imagery`, `CARTO`, `Google Maps`) and style themes.
       3. **Offline Maps & Tile Cache**: Offline tile storage controls, auto-cache toggle, storage quota bar, region downloader, and saved region manager.
       4. **Touch Gestures & Onboarding**: Interactive Gesture Coach replay trigger and system version link to `UpdatesScreen`.
     - **`DevicesScreen` (`devices`)**: Fleet directory, search, status filtering, Wake/Sleep command dispatch, and 1-tap service handoff (`Map`, `Cam`, `Audio`, `Screen`, `Files`) that automatically locks `DeviceSelectionManager` onto that device.
     - **`AlertsScreen` (`alerts`)**: Perimeter breach & security alert feed with severity filtering, **swipe-to-acknowledge** gesture on individual alert cards, and batch `"Ack All"` confirmation modal.
     - **`DiagnosticsScreen` (`diagnostics`)**: Real-time subsystem health probes, active vs. standby fleet counters, and security posture audit.
     - **`CameraScreen`, `AudioScreen`, `ScreenCaptureScreen`, `FilesScreen`**: Dedicated remote WebRTC/command service consoles with unified device switcher headers and connection state banners.
     - **`UsersScreen`, `PermissionsScreen`, `AuditLogScreen`**: Integrated Administration Suite with a unified top navigation switcher allowing seamless switching between **Users**, **RBAC Matrix (Permissions)**, and **Security Audit Trail** without dead-end screens.
     - **`UpdatesScreen` (`system/updates`)**: Release channel verification, SHA-256 package verification, and OTA installation progress.

---

## 2. Map Focus & Multi-Selection State Machine Rules

### Rule 1: Returning to Map from Another Screen
- `MapViewModel` is scoped at the Activity / host level and persists `lastFocusedDeviceId` and `lastCameraPosition` in `MapPreferencesRepository`.
- When the user navigates to another page (`Devices`, `Settings`, `Alerts`, etc.) and returns to `MapScreen`:
  - If the user was focused on a specific device (`selectedMarker != null`), the map **retains focus on that exact device** and keeps its telemetry card synchronized—never snapping back to `activeMarkers.first()`.
  - If the user explicitly dismissed the telemetry card (`selectedMarker == null`) while viewing `All Trackers`, returning to the map preserves their last camera viewport without forcing an unwanted device card open.

### Rule 2: Strict Multi-Selection Filtering & Auto-Switching
- When `DeviceSelection` is `All`: `activeMarkers` contains all devices; the bottom telemetry pager swipes across all devices.
- When `DeviceSelection` is `Multiple(selectedIds)` (e.g., 2 out of 3 devices selected):
  - Only the selected devices appear on the map and in the bottom telemetry pager (`displayMarkers = activeMarkers`). Unselected devices (e.g., Device 3) are **completely excluded** from the telemetry pager.
  - If the user is currently viewing Device 3's telemetry card and deselects Device 3 in Multi-select mode, the state machine **immediately switches `selectedMarker` to the first remaining selected device** (e.g., Device 1 or Device 2) and smoothly animates the map camera to the newly focused device.

---

## 3. Dynamic Theme & Color Studio Specification

| Token / Setting | Options | Persistence Key |
| :--- | :--- | :--- |
| **Theme Mode** | `SYSTEM`, `DARK`, `LIGHT` | `pref_theme_mode` |
| **Dynamic System Colors** | `Boolean` (Android 12+ Material You Wallpaper extraction) | `pref_theme_dynamic_color` |
| **Accent Preset** | `SAPPHIRE`, `EMERALD`, `VIOLET`, `AMBER`, `ROSE`, `CYAN`, `CUSTOM` | `pref_theme_accent_preset` |
| **Custom Accent Hue** | `Float` (`0f..360f` HSV Hue for full user-controlled color customization) | `pref_theme_custom_hue` |

All color scheme transitions use `animateColorAsState(tween(280))` inside `TraceTheme` so switching themes or dragging the custom color slider updates the entire UI at 60 FPS without recreation lag.

---

## 4. Synchronized Motion & Mobile Gesture System

### Choreographed Animations
1. **Dynamic Island Morphing**: `AnimatedContent` with spring scale + fade when tracker selection state changes.
2. **Telemetry Card Pager Physics**: `graphicsLayer` page offset interpolation (subtle `0.94f -> 1.0f` scale and `0.7f -> 1.0f` alpha during horizontal swipe) + synchronized camera fly-to (`650ms`).
3. **Interactive First-Time Gesture Coach (`GestureOnboardingOverlay`)**:
   - Launches automatically on first visit to `MapScreen` (`pref_has_seen_gesture_coach = false`) and can be replayed anytime via the `?` help button in `MapTopBar` or from `SettingsScreen`.
   - Guides the user through 3 interactive mobile gesture cards with animated vector motion indicators:
     1. **Top Dynamic Island Gestures**: Tap to select Single/Multi trackers, or swipe left/right on the capsule to quick-switch devices.
     2. **Bottom Telemetry Pager Gestures**: Swipe left/right between selected trackers, swipe down to dismiss the card, or double-tap to zoom in close.
     3. **Map & Alert Gestures**: Tap markers to inspect, long-press geofence zones to edit boundaries, and swipe alerts to acknowledge.

### Curvature & Touch Ergonomics Contract
- **Outer Cards / Modals / Sheets**: `20.dp` rounded corners (`RoundedCornerShape(20.dp)`), `1.dp` hairline border (`outlineVariant.copy(alpha = 0.35f)`).
- **Inner Interactive Tiles / Buttons / Inputs**: `12.dp` rounded corners (`RoundedCornerShape(12.dp)`).
- **Status Badges**: `6.dp` rounded corners (`RoundedCornerShape(6.dp)`).
- **Floating HUD Capsules**: `24.dp`–`26.dp` pill geometry.
- **Touch Targets**: Minimum `40.dp`–`48.dp` on all interactive buttons, icons, and gesture handles.
