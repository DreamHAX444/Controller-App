# Controller App — UI/UX Design Specification

**Project:** Live Tracker Controller
**Version:** 1.0
**Status:** Design Baseline
**Source of Truth:** PRD 1.0 + TRD 1.0

---

## 1. Design Direction

### Product character

- Map-first
- Professional
- Clean
- Information-dense without feeling crowded
- Fast and responsive
- Security-conscious
- Mobile-first
- Consistent across every feature

The interface should feel like a serious real-time control application rather than a generic admin dashboard.

### Core principle

**The map is the primary workspace. Everything else should support monitoring, selecting, controlling, or diagnosing devices.**

---

## 2. Design Principles

1. **Map first:** The main dashboard prioritizes live geographic state.
2. **State is visible:** Live, stale, offline, connecting, and error states must never look identical.
3. **Progressive disclosure:** Show the important information first; expose advanced controls when requested.
4. **Permission-aware:** Unauthorized services should not appear as actionable controls.
5. **One-handed operation:** Primary mobile actions should be reachable and have comfortable touch targets.
6. **Low cognitive load:** Avoid unnecessary dialogs and nested navigation.
7. **Consistent feedback:** Every important action has loading, success, failure, or unavailable feedback.
8. **No fake live state:** Cached data must be visibly marked as stale.

---

# 3. Visual System

## 3.1 Color Tokens

Use semantic tokens rather than hard-coded colors.

### Light theme

- Background: near-white neutral
- Surface: white
- Elevated surface: slightly tinted neutral
- Primary: deep blue
- Primary container: pale blue
- Success: green
- Warning: amber
- Error: red
- Info: cyan/blue
- Text primary: near-black
- Text secondary: muted gray
- Divider: subtle gray
- Map overlay: translucent surface

### Dark theme

- Background: near-black blue/gray
- Surface: dark neutral
- Elevated surface: slightly lighter neutral
- Primary: bright blue
- Success: green
- Warning: amber
- Error: red
- Text primary: near-white
- Text secondary: muted light gray

Colors must pass accessibility contrast requirements.

---

# 4. Typography

Use a modern Android system typeface.

### Hierarchy

- Display: dashboard/major title
- Headline: screen title
- Title: card and section heading
- Body: standard content
- Label: controls
- Caption: secondary metadata

Prefer a small number of weights and sizes. Do not create excessive typography variants.

---

# 5. Spacing

Use an 8dp-based spacing system:

- 4dp: micro spacing
- 8dp: compact
- 12dp: standard internal spacing
- 16dp: default screen padding
- 24dp: section separation
- 32dp: major separation

Touch targets should generally be at least 48dp.

---

# 6. Shape & Elevation

Use:

- Medium rounded cards
- Larger radius for modal surfaces
- Minimal shadows
- Stronger elevation only for floating controls and dialogs
- Consistent corner radius across the application

Avoid excessive borders and decorative effects.

---

# 7. Navigation

Primary navigation:

```text
PIN
 ↓
Authentication
 ↓
Main Map
 ├── Devices
 ├── Alerts
 ├── Diagnostics
 ├── Services
 ├── Admin
 └── Settings
```

The map remains the default landing screen after authentication.

---

# 8. PIN Screen

## Layout

```text
┌─────────────────────────┐
│                         │
│       App Logo          │
│                         │
│     Enter your PIN      │
│                         │
│       • • • •           │
│                         │
│     1   2   3           │
│     4   5   6           │
│     7   8   9           │
│         0               │
│                         │
│     Locked / Error      │
└─────────────────────────┘
```

Requirements:

- Four-digit PIN
- Auto-advance
- Clear error feedback
- Lockout state
- Optional biometric unlock later
- No PIN displayed in plaintext

---

# 9. Main Map Dashboard

## Layout

```text
┌─────────────────────────────────┐
│ [Device Selector ▼]       [⋮]  │
│                                 │
│                                 │
│             MAP                 │
│                                 │
│        ● Tracker A              │
│                 ➜               │
│                                 │
│     ○ Accuracy radius           │
│                                 │
│                 ● Tracker B     │
│                                 │
│                                 │
│                           [+]   │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ Tracker A  • Online         │ │
│ │ 42 km/h • 12m accuracy      │ │
│ └─────────────────────────────┘ │
└─────────────────────────────────┘
```

The map occupies most of the viewport.

---

# 10. Dynamic Device Selector

The compact selector at the top opens a bottom sheet.

```text
Devices
────────────────────────
● All Devices
○ Tracker A
○ Tracker B
○ Tracker C

──────── Multiple ──────

☑ Select Multiple
```

When multiple selection is enabled:

```text
Select Devices

☑ Tracker A
☐ Tracker B
☑ Tracker C
☐ Tracker D

          [Apply]
```

After applying:

```text
2 Devices Selected
```

Only selected devices appear on the map.

---

# 11. Device Marker

Each marker communicates:

- Device identity
- Connection state
- Direction
- Selection state

Concept:

```text
       ↑ Bearing
       │
      ◉
   Tracker A
```

The marker should rotate according to bearing where valid.

If bearing is unavailable, use a neutral marker rather than inventing a direction.

---

# 12. Accuracy Visualization

Accuracy is primarily visualized on the map.

```text
       ┌─────────────┐
       │   accuracy  │
       │      ○      │
       │     ●→      │
       └─────────────┘
```

The radius represents reported horizontal accuracy.

Also show the numeric value in the selected-device details.

---

# 13. Device Detail Sheet

Tapping a marker opens a bottom sheet.

```text
Tracker A
● Online

Location
23.8103, 90.4125
Accuracy       12 m
Speed          42 km/h
Bearing        125°
Altitude       18 m
Updated        4 sec ago

[Open Device]
```

The sheet should be dismissible without losing map state.

---

# 14. Device Overview

```text
Tracker A
────────────────────
● Online

Battery       78%
Network       Wi-Fi
Heartbeat     2 sec ago
Location      Live

Capabilities
✓ Location
✓ Camera
✓ Audio
✓ Screen
✓ Files

Services
Camera     [Open]
Audio      [Open]
Screen     [Open]
Files      [Open]
```

Unavailable or unauthorized capabilities should be clearly distinguished.

---

# 15. Geo-Fence UI

Map view:

- Fence outline
- Fence name
- Active/disabled visual state
- Device transition indicator

Geo-fence management:

```text
Geo-Fences

● Home
  Circle • Active

● Office
  Polygon • Active

○ Warehouse
  Circle • Disabled

             [+]
```

Create flow:

```text
Add Geo-Fence
 ↓
Choose Circle / Polygon
 ↓
Draw on Map
 ↓
Name Fence
 ↓
Configure Alerts
 ↓
Save
```

---

# 16. Alerts

Alerts should be time-ordered.

```text
Alerts

● Tracker A entered Home
  10:42 AM

● Tracker C disconnected
  10:37 AM

● Tracker B exited Office
  10:21 AM
```

Use severity indicators:

- Info
- Warning
- Critical

Do not rely on color alone.

---

# 17. Camera Screen

```text
┌─────────────────────────┐
│ ← Tracker A     ● Live │
│                         │
│                         │
│       CAMERA VIEW       │
│                         │
│                         │
│                         │
│  1920×1080 • 20 FPS     │
│                         │
│       [ Stop ]          │
└─────────────────────────┘
```

Show actual capture configuration, not only requested configuration.

---

# 18. Audio Screen

```text
Tracker A
Audio

● Capturing
Transport: Connected
Session: Active

[ Stop Audio ]

Connection
████████████████
```

Keep the interface focused on state and control.

---

# 19. Screen Capture

```text
Tracker A
Screen

┌───────────────────────┐
│                       │
│   LIVE SCREEN VIEW    │
│                       │
└───────────────────────┘

● Capturing

[ Stop ]
```

---

# 20. File Manager

Use a familiar file-browser structure.

```text
Tracker A / Files

📁 Documents
📁 Pictures
📁 Videos

report.pdf
photo.jpg

[ Upload ]

Transfer status:
photo.jpg
████████████░░ 82%
```

Actions:

- Open
- Download
- Upload
- Rename
- Delete

Destructive operations require confirmation.

---

# 21. Admin Area

```text
Admin

Users
Permissions
Devices
Audit Log
Application Settings
Updates
```

Only authorized admin accounts should see this section.

---

# 22. User Management

```text
Users

Sabik
Admin
● Active

User A
User
● Active

User B
User
○ Disabled

                 [+]
```

User detail:

```text
User A

Assigned Devices
☑ Tracker A
☑ Tracker C
☐ Tracker D

Services
☑ Location
☐ Camera
☐ Audio
☐ Screen
☐ Files

[ Save Permissions ]
```

---

# 23. Permission UX

Permission changes should clearly distinguish:

- Granted
- Denied
- Not supported
- Device offline

Example:

```text
Tracker A

Location       ✓ Allowed
Camera         ✕ Denied
Audio          ✕ Denied
Screen         ✕ Denied
Files          ✕ Denied
```

Never imply that a denied service is available merely because the device supports it.

---

# 24. Diagnostics Screen

```text
Tracker A
Diagnostics

Connection
● Connected

Signaling
● Healthy

WebRTC
● Connected

Heartbeat
2 sec

Last Event
4 sec ago

Reconnect Attempts
0

Last Error
None
```

Use expandable technical sections for advanced diagnostics.

---

# 25. Audit Log

```text
Audit Log

10:42
Admin
Granted Location
User A
Tracker A

10:37
User A
Opened Location
Tracker A

10:20
Admin
Revoked Camera
User A
```

Support filtering by:

- User
- Device
- Action
- Date
- Result

---

# 26. Update UX

When an update is available:

```text
┌─────────────────────────────┐
│        Update Available     │
│                             │
│  Version 1.4.0              │
│                             │
│  • Improved live map        │
│  • Better reconnection      │
│  • Bug fixes                │
│                             │
│  [Later]     [Update]       │
└─────────────────────────────┘
```

Download state:

```text
Downloading Update

██████████████░░ 84%

42.6 MB / 50.8 MB
```

After verification:

```text
Update Ready

The update has been downloaded
and verified.

[Install]
```

The Android system installer may then display its required confirmation UI.

---

# 27. Global State Indicators

Every screen must consistently communicate:

### Live

Green indicator + live timestamp.

### Stale

Amber indicator + "Updated X ago".

### Offline

Gray/red indicator + offline state.

### Connecting

Progress indicator + connecting label.

### Error

Error indicator + actionable explanation.

---

# 28. Loading States

Avoid blank screens.

Use:

- Skeleton cards
- Map loading indicator
- Inline progress
- Button progress state

Do not block the entire application when only one device/service is loading.

---

# 29. Empty States

Example:

```text
No Devices Selected

Select one or more devices
to display them on the map.

[Select Devices]
```

Another:

```text
No Alerts

There are no recent alerts.
```

---

# 30. Error States

Every error should answer:

1. What happened?
2. Is anything affected?
3. What can the user do?

Example:

```text
Unable to connect to Tracker A

The tracker may be offline.

[Retry]
```

Technical details belong in Diagnostics.

---

# 31. Offline UX

When Controller connectivity is unavailable:

```text
⚠ Offline

Showing last known data
Updated 2 min ago
```

Do not show a green/live indicator for cached data.

---

# 32. Motion & Animation

Animations should be functional, not decorative.

Use:

- Short screen transitions
- Bottom-sheet transitions
- Marker state transitions
- Progress animations
- Subtle state-change feedback

Avoid heavy animations on the map because they can interfere with map performance.

---

# 33. Performance UX Rules

- Avoid unnecessary recomposition.
- Do not animate every location update.
- Batch rapid marker updates where appropriate.
- Do not reload the whole map for one device change.
- Keep dialogs lightweight.
- Use lazy rendering for long lists.
- Avoid blocking navigation while background data loads.

---

# 34. Accessibility

Required:

- Content descriptions
- Minimum touch target sizes
- Contrast compliance
- Text scaling support
- No color-only status indicators
- Screen-reader-friendly controls
- Keyboard/navigation support where applicable

---

# 35. Responsive Behavior

Primary target:

**375dp mobile viewport**

The design must scale upward without changing the information hierarchy.

At larger widths:

- Map remains primary.
- Detail sheets may become side panels.
- Device lists can become persistent panels.
- Multi-column admin layouts may be introduced.

---

# 36. Component Inventory

Core reusable components:

```text
AppTopBar
DeviceSelector
DeviceChip
DeviceMarker
DeviceStatusIndicator
AccuracyCircle
DeviceBottomSheet
ServiceCard
ServiceStatus
GeoFenceCard
AlertCard
PermissionToggle
ConnectionIndicator
OfflineBanner
LoadingState
ErrorState
EmptyState
ConfirmationDialog
UpdateDialog
ProgressDialog
DiagnosticCard
AuditLogRow
```

Components must be state-driven and reusable.

---

# 37. UI State Model

Each feature should explicitly model:

```text
Loading
Content
Empty
Error
Offline
Unauthorized
Unavailable
```

Do not represent all states with a nullable object.

---

# 38. Design Quality Gate

A screen is complete only when:

- All normal states exist.
- Loading state exists.
- Empty state exists where applicable.
- Error state exists.
- Offline state exists where applicable.
- Permission restrictions are represented.
- Accessibility is checked.
- Dark theme is checked.
- Performance is acceptable.
- Navigation/back behavior is correct.
- No destructive action lacks confirmation.

---

# 39. Final Design Rule

The Controller should feel like:

**A calm, fast, map-centric real-time control surface.**

Not:

**A collection of unrelated feature screens.**

The map, device state, permissions, services, alerts, diagnostics, and administration must all feel like parts of one coherent system.
