# Controller App — Technical Requirements Document

**Project:** Live Tracker Controller\
**Version:** 1.0\
**Status:** Implementation Baseline\
**Architecture:** Modular, event-driven, state-driven Android application

---

# 1. Technical Architecture

```text
┌─────────────────────────────────────────────────────────┐
│                     PRESENTATION                         │
│ Compose UI / Navigation / Screens / UI State            │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                  FEATURE / DOMAIN                        │
│ Map │ Devices │ Camera │ Audio │ Screen │ Files         │
│ GeoFence │ Alerts │ Users │ Permissions │ Diagnostics   │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                CENTRAL STATE + EVENTS                    │
│ State Store │ Event Bus │ Reducers/Processors            │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                     DOMAIN CORE                          │
│ Use Cases │ Authorization │ Command Routing │ Policies   │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                       DATA                               │
│ Repositories │ Local Cache │ Remote Data │ Persistence   │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│                TRANSPORT / SESSION                       │
│ Signaling │ WebRTC │ Data Channels │ Heartbeat           │
│ Reconnection │ Session Manager                           │
└──────────────────────────┬──────────────────────────────┘
                           │
                           ▼
                    TRACKER APP
```

---

# 2. Suggested Project Structure

```text
controller_app/
│
├── app/
│
├── core/
│   ├── common/
│   ├── logging/
│   ├── errors/
│   ├── security/
│   ├── lifecycle/
│   └── configuration/
│
├── auth/
│   ├── pin/
│   ├── authentication/
│   ├── session/
│   └── authorization/
│
├── data/
│   ├── local/
│   ├── remote/
│   └── repositories/
│
├── domain/
│   ├── models/
│   ├── usecases/
│   ├── commands/
│   └── policies/
│
├── transport/
│   ├── signaling/
│   ├── webrtc/
│   ├── datachannel/
│   └── heartbeat/
│
├── session/
│   ├── TrackerSessionManager
│   ├── ConnectionManager
│   └── ReconnectionManager
│
├── realtime/
│   ├── EventBus
│   ├── StateStore
│   └── StateProcessors
│
├── features/
│   ├── map/
│   ├── location/
│   ├── geofence/
│   ├── devices/
│   ├── camera/
│   ├── audio/
│   ├── screen/
│   ├── files/
│   ├── alerts/
│   ├── diagnostics/
│   ├── users/
│   ├── permissions/
│   ├── audit/
│   └── updates/
│
├── ui/
│   ├── navigation/
│   ├── theme/
│   ├── components/
│   └── design/
│
└── testing/
    ├── unit/
    ├── integration/
    └── reliability/
```

---

# 3. Technology Baseline

Use:

- Kotlin
- Jetpack Compose
- AndroidX
- Coroutines
- Flow/StateFlow
- ViewModel
- Dependency Injection
- Repository pattern
- Modular/domain-oriented feature boundaries
- WebRTC for real-time media/session transport
- Persistent local storage for appropriate non-sensitive state
- Android Keystore-backed secure storage for secrets

Exact library versions must be selected against the current Android/Gradle toolchain when implementation begins rather than hard-coded into this specification.

---

# 4. State Architecture

UI must not directly own transport state.

Recommended flow:

```text
Tracker Event
     ↓
Transport
     ↓
Event Processor
     ↓
Central State Store
     ↓
ViewModel
     ↓
Compose UI
```

Commands travel in the opposite direction:

```text
UI Action
   ↓
ViewModel
   ↓
Use Case
   ↓
Authorization
   ↓
Command Router
   ↓
Tracker Session
   ↓
Transport
```

---

# 5. Multi-Device Model

Each tracker gets an isolated session context.

```text
TrackerSession
├── deviceId
├── connectionState
├── signalingState
├── webrtcState
├── heartbeatState
├── capabilities
├── permissions
├── locationState
├── cameraState
├── audioState
├── screenState
└── fileTransferState
```

One tracker's failure must not corrupt another tracker's session.

---

# 6. Command Architecture

Commands must be typed and identifiable.

Example:

```text
StartCamera
StopCamera
StartAudio
StopAudio
StartScreen
StopScreen
RequestLocation
ListFiles
DownloadFile
UploadFile
```

Every command should have:

- Command ID
- Device ID
- Actor/session context
- Timestamp
- Permission requirement
- Idempotency strategy
- Timeout
- Result state

This prevents duplicate execution during retries.

---

# 7. Authorization Pipeline

```text
User
 ↓
Authenticated Session
 ↓
Device Access Check
 ↓
Service Permission Check
 ↓
Command Validation
 ↓
Command Execution
```

A UI restriction is never considered sufficient authorization.

---

# 8. Connection State Machine

```text
DISCONNECTED
     ↓
CONNECTING
     ↓
CONNECTED
     ↓
DEGRADED
     ↓
RECONNECTING
     ↓
CONNECTED
```

Failure handling must use bounded exponential backoff.

Connection state must be observable by both diagnostics and UI.

---

# 9. Real-Time Event Model

Events must contain enough context to identify their source.

Conceptually:

```text
Event {
    eventId
    deviceId
    eventType
    timestamp
    sequence
    payload
}
```

Events should support duplicate detection and ordering/reconciliation where required.

---

# 10. Location Architecture

Location state:

```text
LocationState {
    latitude
    longitude
    accuracy
    bearing
    speed
    altitude
    timestamp
    provider
    freshness
}
```

Map rendering must distinguish:

```text
LIVE
STALE
UNKNOWN
OFFLINE
```

Accuracy should render as a geographic circle.

Bearing should affect the marker/direction indicator.

---

# 11. Geo-Fence Architecture

```text
GeoFence
├── id
├── name
├── geometry
│   ├── Circle
│   └── Polygon
├── enabled
├── createdAt
└── configuration
```

Transitions:

```text
OUTSIDE → INSIDE = ENTER
INSIDE → OUTSIDE = EXIT
```

Transitions must be logged and deduplicated.

---

# 12. Capability System

Tracker capabilities must be explicitly advertised.

Example:

```text
Capabilities {
    location
    camera
    audio
    screen
    fileTransfer
}
```

Controller UI must derive available actions from capabilities plus permissions.

Therefore:

```text
Available UI Action =
Capability
AND
Permission
AND
Connection State
```

---

# 13. Local Security

Use Android Keystore-backed mechanisms for secrets.

Never store:

- Plaintext PIN
- Authentication secrets
- Private keys
- Session credentials

in ordinary unencrypted storage.

The PIN verification implementation must avoid reversible storage of the PIN.

---

# 14. Performance Architecture

Avoid heavy work on the main thread.

Use:

- Structured concurrency
- Lifecycle-aware collection
- Lazy lists
- Stable Compose state
- Marker update throttling/batching
- Background I/O
- Controlled image loading
- Resource pooling where appropriate
- Explicit WebRTC cleanup

Every long-running coroutine must have a defined owner and cancellation path.

---

# 15. Memory & Lifecycle Safety

Every resource must have a deterministic lifecycle.

Particularly:

- WebRTC PeerConnection
- Media tracks
- Data channels
- Surface/video resources
- Audio resources
- File-transfer jobs
- Coroutine scopes
- Map observers

No feature may retain an Activity/Context accidentally.

---

# 16. Offline Strategy

Local cache may retain:

- Last known device state
- Last known location
- Device metadata
- Safe UI preferences

Cached data must carry freshness metadata.

Never present stale cached location as live location.

---

# 17. Diagnostics

Expose structured diagnostic information:

```text
Connection
Signaling
WebRTC
Heartbeat
Events
Commands
Services
Errors
Reconnection
```

Use structured logs internally.

Do not log:

- PIN
- Tokens
- Passwords
- Private keys
- Sensitive file contents

---

# 18. Audit Architecture

Audit records should include:

```text
auditId
timestamp
actorId
action
deviceId
target
result
metadata
```

Retention policy should be configurable later.

---

# 19. Update Architecture

Update metadata:

```text
versionCode
versionName
downloadUrl
sha256
releaseNotes
mandatory
minimumSupportedVersion
```

Flow:

```text
Controller
   ↓
Update Manifest
   ↓
Validate Version
   ↓
Download
   ↓
Verify Integrity
   ↓
Invoke Android Installer
```

The update system must never install an APK merely because it was downloaded successfully.

---

# 20. Error Handling

Errors must be categorized:

```text
NETWORK_ERROR
AUTH_ERROR
PERMISSION_DENIED
DEVICE_OFFLINE
SESSION_ERROR
WEBRTC_ERROR
COMMAND_TIMEOUT
TRANSFER_ERROR
UPDATE_ERROR
UNKNOWN_ERROR
```

User-facing messages must be understandable.

Developer diagnostics should retain technical context.

---

# 21. Testing Strategy

### Unit Tests

Test:

- Authorization
- Permission rules
- State reducers/processors
- Command validation
- Geo-fence transitions
- Update validation
- Retry logic
- State reconciliation

### Integration Tests

Test:

- Controller ↔ Tracker connection
- WebRTC session
- Event delivery
- Commands
- File transfer
- Permission changes
- Reconnection

### Reliability Tests

Simulate:

- Wi-Fi loss
- Network switching
- Tracker restart
- Controller restart
- WebRTC failure
- Signaling failure
- Duplicate events
- Duplicate commands
- Long-running sessions

### UI Tests

Test:

- PIN flow
- Login
- Device selection
- Multi-device map
- Permission-dependent UI
- Service controls
- Offline state
- Error states
- Update flow

---

# 22. Development Phases

```text
PHASE 0
Foundation + Security
        ↓
PHASE 1
Authentication + Authorization
        ↓
PHASE 2
Connection + Session Layer
        ↓
PHASE 3
Central State + Event System
        ↓
PHASE 4
Device Management + Diagnostics
        ↓
PHASE 5
Map + Live Location
        ↓
PHASE 6
Geo-Fence + Alerts
        ↓
PHASE 7
Camera
        ↓
PHASE 8
Audio
        ↓
PHASE 9
Screen Capture
        ↓
PHASE 10
File Transfer
        ↓
PHASE 11
Admin + Users + Audit
        ↓
PHASE 12
In-App Updates
        ↓
PHASE 13
Performance / Security Hardening
        ↓
PHASE 14
Full Integration + Reliability Testing
        ↓
RELEASE
```

---

# 23. Definition of Done

A phase is complete only when:

```text
Implementation
      +
Unit Tests
      +
Integration Tests
      +
Lifecycle Review
      +
Security Review
      +
Performance Review
      +
Failure/Recovery Test
      +
Build Verification
      =
PHASE COMPLETE
```

No phase should be marked complete solely because the application compiles.

---

# 24. Final Architecture Principle

The Controller must remain:

**Modular → Event-driven → State-driven → Permission-aware → Session-isolated → Failure-resilient → Performance-conscious → Secure**

The UI is a consumer of application state, not the owner of the Tracker connection.

The transport layer is not allowed to bypass authorization.

The Tracker must not trust the UI simply because a command originated from the Controller.

Every critical operation must have a defined lifecycle, failure path, recovery path, and test.
