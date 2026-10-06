# Controller App — Product Requirements Document

**Project:** Live Tracker Controller\
**Version:** 1.0\
**Status:** Implementation Baseline\
**Approach:** Complete rebuild from scratch

---

## 1. Product Vision

Build a secure, reliable, high-performance Android Controller App capable of managing multiple Tracker devices in real time.

The Controller must provide:

- Multi-user access
- Admin-controlled permissions
- Multi-device management
- Map-first live monitoring
- Geo-fencing and alerts
- Camera, audio, screen and file services
- Real-time events and state updates
- Strong connection/reconnection handling
- Device diagnostics
- In-app application updates
- Strong security
- Smooth, responsive UI

The new Controller must **not inherit architectural debt from the previous Controller implementation**.

---

## 2. Primary User Roles

### Admin

Can:

- Manage users
- Assign devices
- Assign service permissions
- Revoke permissions
- View audit logs
- Manage device configuration
- View diagnostics
- Manage application settings

### User

Can only access:

- Authorized devices
- Authorized services
- Authorized data

Permissions must be enforced server-side and at the Tracker command layer, not only by hiding UI elements.

---

## 3. Local App Security

On application launch:

```text
Open App
   ↓
4-Digit PIN
   ↓
Valid?
 ├── No → Retry / Lockout
 └── Yes → Authentication → Main App
```

Requirements:

- Four-digit PIN
- Secure local storage
- No plaintext PIN
- Failed-attempt tracking
- Temporary lockout
- Configurable background re-lock
- Secure session handling

The PIN is local app-entry protection and does not replace account authentication or authorization.

---

## 4. Main Dashboard

The primary screen is **Map-first**.

The map must provide:

- Live tracker markers
- Device direction/bearing
- Accuracy circles
- Speed
- Location timestamp
- Connection state
- Device selection
- Geo-fence visualization

### Device Selector

Support:

- All Devices
- Individual device
- Select Multiple Devices

When multiple devices are selected, only those devices appear on the map.

---

## 5. Live Location

Each tracker may provide:

- Latitude
- Longitude
- Accuracy
- Bearing/heading
- Speed
- Altitude
- Timestamp
- Provider/status
- Last update
- Connection state

Accuracy must be represented visually on the map using an accuracy radius/circle.

Bearing should be represented by a directional marker/arrow.

---

## 6. Geo-Fence

Support:

- Circle fences
- Polygon fences
- Active/disabled state
- Enter events
- Exit events
- Transition logging
- Alerts
- Multiple simultaneous fences
- Overlapping fences

Geo-fence functionality is part of the Location subsystem but remains independently manageable.

---

## 7. Tracker Services

### Camera

- Camera selection
- Front/back/available cameras
- Resolution
- FPS
- Quality profiles
- Live preview
- Start/stop
- Actual capture configuration

### Audio

- Capture state
- Transport state
- Session state
- Playback state
- Start/stop

### Screen Capture

- Start/stop
- Live screen
- Capture status
- Resolution/FPS information

### File System

- Browse
- Download
- Upload
- Rename
- Delete
- Transfer progress
- Transfer status
- Error recovery

---

## 8. Real-Time Event System

The Controller must be event-driven.

Example events:

```text
LOCATION_UPDATED
CAMERA_STARTED
CAMERA_STOPPED
AUDIO_STARTED
AUDIO_STOPPED
SCREEN_STARTED
SCREEN_STOPPED
FILE_TRANSFER_STARTED
FILE_TRANSFER_COMPLETED
CONNECTION_LOST
CONNECTION_RESTORED
PERMISSION_CHANGED
GEOFENCE_ENTERED
GEOFENCE_EXITED
```

The UI must react to state/event changes without unnecessary polling.

---

## 9. Connection Reliability

The system must support:

- Signaling recovery
- WebRTC reconnection
- Heartbeat
- Session management
- Network transition handling
- Temporary connectivity loss
- Exponential backoff
- Retry limits
- Session recovery
- Duplicate command protection
- Proper cleanup

Target behavior:

```text
Connection Lost
      ↓
Detect
      ↓
Preserve Safe State
      ↓
Reconnect
      ↓
Restore Session
      ↓
Resume Live State
```

---

## 10. Device Management

Every tracker should expose:

- Device identity
- Online/offline state
- Battery
- Network type
- Signal information where available
- Last heartbeat
- Last location
- Active services
- Capabilities
- Errors/warnings
- Configuration

Capability detection must prevent the Controller from assuming every Tracker supports every service.

---

## 11. Diagnostics

Provide a dedicated diagnostics interface containing:

- Connection state
- Signaling state
- WebRTC state
- Heartbeat
- Last successful event
- Last error
- Active sessions
- Service states
- Device health
- Reconnection attempts

Diagnostics must be useful for troubleshooting without exposing sensitive credentials or secrets.

---

## 12. Audit System

Admin-accessible audit history:

```text
Timestamp
Actor
Action
Target User
Target Device
Service
Result
```

Examples:

```text
User A → Location viewed → Tracker 01
User B → Camera started → Tracker 02
Admin → Camera permission revoked → User A
```

Sensitive credentials must never be stored in logs.

---

## 13. Permission System

Two levels:

### Device Permission

Determines which trackers a user can access.

### Service Permission

Determines which services are available for an authorized tracker.

Example:

```text
User A
├── Tracker 01 ✓
├── Tracker 03 ✓
│
├── Location ✓
├── Camera ✗
├── Audio ✗
├── Screen ✗
└── Files ✗
```

Permission changes should propagate to active sessions in real time.

---

## 14. Offline Behavior

When connectivity is unavailable:

- Preserve last known state
- Clearly mark stale data
- Do not pretend stale data is live
- Show connection status
- Queue only explicitly safe/retriable operations
- Reconcile state after reconnection

---

## 15. In-App Update System

The Controller must support a browser-free update experience.

Flow:

```text
Version Check
     ↓
Update Available
     ↓
Update Dialog
     ↓
Download APK
     ↓
Integrity Verification
     ↓
Android Package Installer
     ↓
Install
```

Support:

- Version code validation
- Version name
- Release notes
- Optional updates
- Mandatory updates
- APK integrity verification
- Resumable download where feasible
- Failed-download recovery
- Safe update handling

Android's own package-install confirmation remains authoritative where required by the platform.

---

## 16. Performance Requirements

Target:

- Smooth UI interaction
- Approximately 60 FPS where practical
- No unnecessary main-thread work
- Efficient Compose recomposition
- Lazy loading
- Efficient map rendering
- Batched marker updates
- Proper coroutine cancellation
- Memory leak prevention
- WebRTC resource cleanup
- Controlled network usage
- Battery-conscious background work

Performance regressions must be tested rather than assumed.

---

## 17. UX Requirements

The application must provide:

- Fast navigation
- Clear loading states
- Skeleton states where useful
- Empty states
- Clear error states
- Immediate action feedback
- Offline indicators
- Accessible touch targets
- Dark/light theme
- Accessibility support
- Consistent visual language

---

## 18. Quality Gates

A feature is not considered complete until:

1. Functional behavior works.
2. Permission enforcement works.
3. Lifecycle cleanup works.
4. Failure handling works.
5. Reconnection behavior works where applicable.
6. Memory/resource usage is acceptable.
7. Unit/integration tests pass.
8. Security review passes.
9. UI state handling is complete.
10. No known critical regression remains.

---

## 19. Navigation Model

```text
Controller
├── Map
├── Devices
├── Services
│   ├── Camera
│   ├── Audio
│   ├── Screen
│   └── Files
├── Alerts
├── Diagnostics
├── Admin
│   ├── Users
│   ├── Permissions
│   └── Audit Log
├── Settings
└── Updates
```

---

## 20. Product Success Criteria

The rebuilt Controller is successful when:

- Multiple trackers can be monitored reliably.
- Users only see authorized devices/services.
- Live location remains responsive.
- Geo-fence events are reliable.
- Tracker service state is synchronized in real time.
- Connection failures recover automatically.
- Diagnostics make failures understandable.
- UI remains responsive under normal multi-device load.
- Updates can be delivered safely.
- Security boundaries cannot be bypassed through UI manipulation.
