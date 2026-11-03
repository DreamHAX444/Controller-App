# TRACE Controller App — User System Master Forensic Audit & Requirements Discovery Report

**Document Reference**: `TRACE-AUDIT-2026-MASTER`  
**Target Repository**: `controller_app` (`c:\Users\ZNS\Documents\live_tracker\controller_app`)  
**Target System**: TRACE Android Controller App, Background Tracker Fleet Service, and Supabase Cloud Backend  
**Audit Classification**: Pre-Implementation Forensic Discovery & Security Baseline  
**Audit Status**: **AUDIT COMPLETE — READ-ONLY DISCOVERY ONLY (Zero Code/Schema Changes)**  
**Target Application Version**: `0.1.0` (Build `1`) — Prototype Baseline  
**Verified Unit Test Suite**: **499 / 499 passing unit tests (100% pass rate, 0 failures, 0 errors)**  
**Coordinating Agent**: Master Audit Orchestrator  

---

## 1. Executive Summary

A comprehensive, multi-agent forensic audit was conducted on the TRACE Android Controller project to establish a definitive, evidence-backed baseline before implementing its user, authentication, device-assignment, role, permission, administration, and update-management systems.

The investigation was executed by nine specialized subagents (Agents A through I) and coordinated under strict read-only constraints. The audit spanned the Android Controller client codebase, background Tracker service contracts, the live Supabase PostgreSQL database (`jyiqhqxjoahlxflaated.supabase.co`), real-time WebSocket replication streams, native update pipelines, and host testing infrastructure.

### Critical Discoveries & Root Vulnerabilities:
1. **Critical Backend Authorization Deficit**:
   - The live PostgreSQL table `public.locations` (105,930 historical rows) has **Row-Level Security (RLS) DISABLED**.
   - `public.commands` (14 rows) and `public.media` (0 rows) have RLS enabled, but are neutralized by a wildcard policy `public_all` (`qual: true, with_check: true` for role `{public}`).
   - Anyone possessing the public Supabase `anon` key (Base64-encoded in the APK) can read the entire historical movement of all devices, delete records, or broadcast arbitrary remote surveillance commands (`start_camera`, `start_mic`, `start_screen`).
2. **Complete Identity Void & Mocked Authentication**:
   - Supabase Auth (`auth.users`) contains **0 registered users**.
   - The Controller application runs purely on in-memory mock repositories (`StubAuthenticationRepository`). Google Sign-In, Android Credential Manager, and Supabase GoTrue Auth are entirely absent from Gradle dependencies and runtime code.
3. **Colliding & Unstable Tracker Hardware Identifiers**:
   - The tracker derives its identity in `LiveLocationService.kt:2081` from `Build.MODEL` combined with `Settings.Secure.ANDROID_ID.takeLast(4)`.
   - This truncated 16-bit identifier ($16^4 = 65,536$ combinations) produces an **>85% collision probability in a fleet of 500 identical devices**, rotates upon app reinstallation on Android 8.0+, and is wiped upon factory reset.
4. **Command Protocol Divergences**:
   - Controller dispatches `"start_audio"`, but the tracker listens only for `"start_mic"` (`LiveLocationService.kt:674`), causing remote audio capture to fail silently.
   - Controller sends `"list_files"` over Realtime broadcast, but the tracker only accepts file transfers via WebRTC DataChannels.
   - Camera UI in the controller is wired to `SimulatedCameraRepository`, leaving `DefaultCameraRepository` and WebRTC video rendering orphaned.
5. **Absence of Active Session Revocation**:
   - Revoking a device assignment or user permission in the controller only cancels local UI coroutines.
   - No wire message is dispatched to the physical tracker; active WebRTC media streams, ambient microphone listening, and background GPS broadcasts continue indefinitely.
6. **Ghost Device Resurrection & Data Retention**:
   - Unregistering a device in the controller purges local memory only. The device is not removed from PostgreSQL. On the very next incoming GPS broadcast, `TraceApplication.kt` automatically resurrects and re-registers the deleted tracker.

---

## 2. Audit Scope and Methodology

### 2.1 Target Systems
- **Controller Application**: Android application module `:app` and shared library `:core` located at `c:\Users\ZNS\Documents\live_tracker\controller_app`.
- **Tracker Application**: Background surveillance and location service contracts located at `c:\Users\ZNS\Documents\live_tracker\tracker_app` (read-only inspection; zero modifications).
- **Cloud Backend**: Live Supabase instance `https://jyiqhqxjoahlxflaated.supabase.co` running PostgreSQL 15, PostgREST, and Realtime WebSocket replication.
- **Testing & Host Infrastructure**: Android SDK, AVD manager, AEHD hypervisor, wireless ADB bridge, and local test runners.

### 2.2 Forensic Methodology
The audit adhered strictly to the following protocols:
- **Strict Read-Only Enforcement**: Zero code changes, zero database migrations, zero permission escalations, and zero git commits/pushes.
- **Source-Backed Evidence**: Every finding requires exact file paths, line numbers, schema objects, or live database metadata citations.
- **Ponytail Senior Dev Principles**: Minimal viable solutions, reuse of existing utilities, zero speculative abstractions, boring over clever, and deletion over addition.
- **UI/UX Pro Max Standards**: Adherence to unified design systems, 3-layer token architecture, accessibility (WCAG), and responsive navigation layout.
- **Multi-Agent Decomposition**: Independent investigation workstreams executed in parallel, followed by adversarial cross-verification and contradiction reconciliation by the coordinating agent.

---

## 3. Sub-Agent Inventory and Original Report References

| Workstream / Agent | Role & Domain Scope | Conversation ID / Transcript URI | Status | Key Deliverable / Primary Findings |
| :--- | :--- | :--- | :---: | :--- |
| **Agent A** | Project Structure & Implementation Auditor | [`d858612c-99e9-4466-800f-7ee9f388301e`](conversation://d858612c-99e9-4466-800f-7ee9f388301e) | **COMPLETE** | Form 1 Inventory; 18 structural findings (F-STRUCT through F-SPEC); 5 of 13 screens implemented; WebRTC orphaned; command envelope ID type mismatch. |
| **Agent B** | Authentication & Google Sign-In Auditor | [`9fcf075a-b5ae-4c0d-ab5e-c5f3739dd04d`](conversation://9fcf075a-b5ae-4c0d-ab5e-c5f3739dd04d) | **COMPLETE** | Form 2 Auth Audit; confirmed zero Google/Supabase Auth SDKs; in-memory credential storage; target Google-only OIDC architecture designed. |
| **Agent C** | Supabase Backend & Database Auditor | [`845ec5fb-9981-47cf-98e5-84873c6a116c`](conversation://845ec5fb-9981-47cf-98e5-84873c6a116c) | **COMPLETE** | Form 3 Backend Audit; live RLS inspection (`locations` open, wildcard `public_all`); DDL for 6 core tables; RLS policies; 6-phase zero-downtime cutover plan. |
| **Agent D** | Tracker Identity & Device Sessions Auditor | [`b3e8fb68-3476-4ca2-8cfb-b8056dd73591`](conversation://b3e8fb68-3476-4ca2-8cfb-b8056dd73591) | **COMPLETE** | Form 4 Device Session Audit; 16-bit hardware identifier collision risk; protocol command divergences; ghost device resurrection; open signaling. |
| **Agent E** | Authorization & Security Threat Model Auditor | [`61829394-f7f2-4a38-92fe-51fa93073c59`](conversation://61829394-f7f2-4a38-92fe-51fa93073c59) | **COMPLETE** | Form 6 Threat Model; TM-01 through TM-07 threat catalog; unauthenticated Realtime broadcast interception; client privilege escalation vectors. |
| **Agent F** | Role & Permission Model Auditor | [`3a193b01-46b5-43a2-9df9-5e04d1b63107`](conversation://3a193b01-46b5-43a2-9df9-5e04d1b63107) | **COMPLETE** | Form 5 Role Matrix; 4-tier hierarchy (Owner, Admin, User, Visitor); backend Owner identity binding (`shabik163344@gmail.com`); time-windowed historical RLS. |
| **Agent G** | User Management & Admin UI Auditor | [`6e69d338-76f7-4760-8c7a-ed78d846ffd0`](conversation://6e69d338-76f7-4760-8c7a-ed78d846ffd0) | **COMPLETE** | Form 7 UI Audit; consolidation of split routes into `admin/users_and_permissions`; drawer hardcoded watermark fixes; dynamic navigation filters; route guards. |
| **Agent H** | Update & Versioning System Auditor | [`3e4b9cd8-4809-43f0-ad69-66b978c7527f`](conversation://3e4b9cd8-4809-43f0-ad69-66b978c7527f) | **COMPLETE** | Form 8 Updates Audit; verified version `0.1.0`; OTA verification engine mature; version centralization gap; role-aware update notifications designed. |
| **Agent I** | Testing & Verification Infrastructure Auditor | [`e37be030-374d-4b99-bd46-a646e5235a3d`](conversation://e37be030-374d-4b99-bd46-a646e5235a3d) | **COMPLETE** | Form 9 Verification Matrix; 19 core test scenarios; AVD `Pixel_10_Pro` verified (Pixel 5 absent); 499 unit tests verified; zero `androidTest` suites. |

---

## 4. Audit Completeness Matrix

| # | Workstream Focus | Output Found | Evidence Sufficient | Independently Checked | Status | Remaining Work Before Implementation |
|---|:--- |:---:|:---:|:---:|:---:|:--- |
| **1** | Project structure & existing implementation | Yes (Agent A) | Yes | Yes (Verified in code) | **COMPLETE** | None (Architecture cataloged). |
| **2** | Google authentication & session lifecycle | Yes (Agent B) | Yes | Yes (Verified in code) | **COMPLETE** | None (Target architecture defined). |
| **3** | Supabase Auth, schema, RLS, RPCs, Realtime | Yes (Agent C) | Yes | Yes (Live DB queried) | **COMPLETE** | None (Target DDL & migration plan drafted). |
| **4** | Tracker identity, device ownership, sessions | Yes (Agent D) | Yes | Yes (Verified contracts) | **COMPLETE** | None (Protocol divergences mapped). |
| **5** | Authorization boundaries & threat model | Yes (Agent E) | Yes | Yes (Trace analysis) | **COMPLETE** | None (Threat catalog TM-01 to TM-07 complete). |
| **6** | Owner, Admin, User, and Visitor roles | Yes (Agent F) | Yes | Yes (Domain analysis) | **COMPLETE** | None (Role & capability matrix formalized). |
| **7** | User-management UI and navigation | Yes (Agent G) | Yes | Yes (Screen audit) | **COMPLETE** | None (Consolidated screen spec produced). |
| **8** | Versioning, GitHub distribution, updates | Yes (Agent H) | Yes | Yes (Build audit) | **COMPLETE** | None (Version 0.1.0 roadmap established). |
| **9** | Emulator, test infra & verification | Yes (Agent I) | Yes | Yes (AVD/test run) | **COMPLETE** | None (19-scenario matrix categorized). |
| **10**| Cross-workstream integration & gap analysis | Yes (Coord) | Yes | Yes (Reconciled) | **COMPLETE** | None (Consolidated in this master report). |

---

## 5. Existing Architecture Inventory

### 5.1 Clean Architecture Decomposition
The project is organized into two Gradle modules:
- `:app`: Android application layer containing Jetpack Compose UI screens, ViewModels, navigation hosts, themes, and Android-specific implementations.
- `:core`: Business logic library bundling domain entities, use cases, repository interfaces, in-memory repository implementations, Ktor/Supabase transports, and WebRTC contracts.

### 5.2 Navigation & Screen Implementation Realization
Of the 13 navigation destinations declared in [`Destination.kt:15-55`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/navigation/Destination.kt#L15-L55) and [`TrackMateNavHost.kt:180-270`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/navigation/TrackMateNavHost.kt#L180-L270):
- **Implemented Screens (5)**:
  1. `Destination.Map` (`map`) -> [`MapScreen.kt`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/ui/screens/map/MapScreen.kt)
  2. `Destination.Alerts` (`alerts`) -> [`AlertsScreen.kt`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/ui/screens/alerts/AlertsScreen.kt)
  3. `Destination.Camera` (`camera/{trackerId}`) -> [`CameraScreen.kt`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/ui/screens/camera/CameraScreen.kt)
  4. `Destination.Settings` (`settings`) -> [`SettingsScreen.kt`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/ui/screens/settings/SettingsScreen.kt)
  5. `Destination.Updates` (`updates`) -> [`UpdatesScreen.kt`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/ui/screens/updates/UpdatesScreen.kt)
- **Placeholder Screens (8)**:
  `Destination.Devices`, `Destination.Diagnostics`, `Destination.Audio`, `Destination.ScreenCapture`, `Destination.Files`, `Destination.Users`, `Destination.Permissions`, and `Destination.AuditLog` all route directly to [`PlaceholderScreen.kt`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/ui/screens/PlaceholderScreen.kt#L41) displaying `"Coming in a later phase"`.

### 5.3 ViewModels & State Flow Pipeline
Seven ViewModels exist in `:app`:
1. `AuthenticationViewModel`: Backed by mock stubs; exposes `StateFlow<AuthenticationState>`.
2. `PinViewModel`: Backed by `PinManager`; drives 4-digit PBKDF2 entry and 30s lockouts.
3. `MapViewModel`: Reactive map state, zero coordinate fabrication, multi-device selection.
4. `AlertsViewModel`: Alert acknowledgment, severity filtering, device isolation.
5. `CameraViewModel`: Enforces fail-closed permission gates; cancellation-safe camera controls.
6. `SettingsViewModel`: Offline region map downloads, tile cache metrics, provider toggles.
7. `UpdatesViewModel`: Manages in-app OTA updates, downloads, verification, and package installation.

---

## 6. Authentication and Google OAuth Audit

### 6.1 Current State: Mock Stubbing
- In [`TraceApplication.kt:50-56`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/TraceApplication.kt#L50-L56), authentication is initialized with:
  ```kotlin
  credentialStore = StubCredentialStore()
  sessionRepository = StubSessionRepository()
  authRepository = StubAuthenticationRepository(sessionRepository, credentialStore)
  ```
- [`StubAuthenticationRepository.kt:32-37`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/core/src/main/java/com/livetracker/controller/core/auth/data/StubAuthRepositories.kt#L32-L37) contains an in-memory map of 4 static users (`admin`, `user`, `disabled`, `suspended`).
- [`LoginScreen.kt:70`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/app/src/main/java/com/livetracker/controller/auth/LoginScreen.kt#L70) accepts a plain-text username string with no password or token.

### 6.2 Missing OAuth & Credential Infrastructure
- **SDK Absence**: No Google Credential Manager or Google ID dependencies are included in `gradle/libs.versions.toml` or `app/build.gradle.kts`.
- **Supabase Auth Absence**: `io.github.jan-tennert.supabase:auth-kt` is not installed.
- **Intent Filters**: `AndroidManifest.xml` lacks deep link intent filters or custom redirect schemes.
- **Token Persistence**: `StubCredentialStore` holds tokens in an in-memory variable `private var token: String? = null`. Process death discards user credentials immediately.

### 6.3 Target Google-Only Architecture
1. **Frontend**: Integrate `androidx.credentials:credentials` and `googleid`. User triggers Google Sign-In button, obtaining an OIDC ID Token from Google Play Services.
2. **Backend**: Pass Google ID Token to `supabase.auth.signInWith(IdToken)`. Supabase GoTrue validates the token against Google's public JWKS.
3. **Role Minting**: A PostgreSQL database trigger on `auth.users` maps `shabik163344@gmail.com` to `Role.OWNER` and all other newly registered accounts to `Role.VISITOR` with status `PENDING`.
4. **Local Keystore**: Persist the Supabase session refresh token in Android Keystore via `EncryptedPreferencesStorage`.

---

## 7. Supabase Schema and Backend Security Audit

### 7.1 Live Supabase Security Posture (`jyiqhqxjoahlxflaated`)
- **`public.locations` (105,930 rows)**: RLS is **DISABLED**. Full read/write/delete access for anyone with the public anon key.
- **`public.commands` (14 rows)**: RLS is ENABLED, but neutralized by policy `public_all` (`roles: {public}, cmd: ALL, qual: true, with_check: true`).
- **`public.media` (0 rows)**: RLS is ENABLED, but neutralized by policy `public_all` (`roles: {public}, cmd: ALL, qual: true, with_check: true`).
- **`public.app_signals` (134 rows)**: RLS is **DISABLED**.
- **`auth.users`**: **0 registered users**.
- **Database Migrations**: **0 migrations**. Schema is unversioned.

### 7.2 Static Header Obscurity
In [`SupabaseClientProvider.kt:24-37`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/core/src/main/java/com/livetracker/controller/core/tracker/supabase/SupabaseClientProvider.kt#L24-L37), an OkHttp interceptor injects:
```
x-app-secret: zns-tracker-secret-2026
```
This header is hardcoded in the client, easily reverse-engineered via `jadx`, and is completely uninspected and unenforced by PostgreSQL.

### 7.3 Target Enterprise Data Model (Summary DDL)
The full DDL is documented in [`docs/FORM_3_DATABASE_BACKEND_AUTH_AUDIT.md`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/docs/FORM_3_DATABASE_BACKEND_AUTH_AUDIT.md):
- `public.profiles`: Extends `auth.users(id)` with `role (OWNER, ADMIN, USER, VISITOR)` and `status (PENDING, ACTIVE, SUSPENDED, DISABLED)`.
- `public.devices`: Hardware fleet registry storing `device_id`, display name, hardware model, and SHA-256 `api_key_hash`.
- `public.user_device_assignments`: Many-to-many join table with `user_id`, `device_id`, `assigned_by`, `assigned_at`, and `expires_at`.
- `public.device_feature_permissions`: Granular feature toggles (`LOCATION_TRACKING`, `CAMERA_STREAM`, `AUDIO_STREAM`, `SCREEN_CAPTURE`, `FILE_EXPLORER`, `DIAGNOSTICS`).
- `public.audit_logs`: Tamper-evident append-only log capturing `actor_id`, `action`, `target_id`, `metadata`, and `timestamp`.
- `public.active_sessions`: Tracks active controller logins and supports remote session revocation.

---

## 8. Tracker Identity, Device Assignment, and Session Audit

### 8.1 Device Identifier Vulnerability
In `LiveLocationService.kt:2081-2088` (`tracker_app`):
```kotlin
val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN"
val shortId = androidId.takeLast(4).uppercase(Locale.US)
cachedDeviceName = "$manufacturer ${Build.MODEL} ($shortId)"
```
- **16-bit Entropy**: `takeLast(4)` produces only 65,536 possibilities. In a commercial fleet, collision risk exceeds 85% at 500 units.
- **Instability**: Reinstalling the app or factory resetting the device generates a new identifier, creating orphan records in Supabase.

### 8.2 Protocol & Command Mismatches
| Subsystem | Controller Wire Dispatch | Tracker Parser (`applyCommand`) | Protocol Status |
| :--- | :--- | :--- | :---: |
| **Camera** | `"start_camera"`, `"stop_camera"` | `"start_camera"`, `"stop_camera"`, `"switch_camera"` | **MOCKED IN CONTROLLER** |
| **Microphone** | `"start_audio"`, `"stop_audio"` | `"start_mic"`, `"stop_mic"` | **FATAL MISMATCH (Dropped)** |
| **Screen** | `"start_screen"`, `"stop_screen"` | `"start_screen"`, `"stop_screen"` | **MATCHING** |
| **Files** | `"list_files"` (Realtime broadcast) | WebRTC DataChannel `"file-transfer"` only | **FATAL MISMATCH (Dropped)** |
| **Diagnostics**| Local synthesis (No wire packet) | `"check_status"` | **DISCONNECTED** |

### 8.3 In-Memory Ephemerality & Ghost Device Resurrection
- Deleting a device in `DefaultDeviceManager.kt:69` purges only local in-memory collections.
- `TraceApplication.kt:248` continuously listens to `public:locations`. The moment the physical tracker emits its next GPS fix, the controller automatically resurrects and re-registers the deleted device.

---

## 9. Authorization Data-Flow Map

The following architectural flow maps identity, role verification, database queries, and command dispatches from client to tracker:

```
[ Android Controller Client ]
      │
      ├── 1. Google Credential Manager (OIDC ID Token)
      ▼
[ Supabase GoTrue Auth ] ──(Validates Google JWKS)──▶ [ Mint Supabase JWT ]
      │                                                     │
      │ (Bearer JWT with app_role claim)                   │ (User ID = auth.uid())
      ▼                                                     ▼
[ PostgreSQL Row-Level Security (RLS) ]
      ├── profiles: Read own profile; Owner/Admin manage profiles
      ├── devices: Owner sees full inventory; Users see assigned devices only
      ├── user_device_assignments: Verified against auth.uid()
      ├── locations: SELECT allowed ONLY if has_device_feature_permission('LOCATION')
      └── commands: INSERT allowed ONLY if user assigned to target device
            │
            ▼ (pg_notify / Realtime Replication)
[ Supabase Private Realtime Channel ] (`device:{deviceId}:commands`)
      │
      ├── Authenticated WebSocket Subscription (Tracker verifies JWT / API key)
      ▼
[ Background Tracker Service (LiveLocationService) ]
      │
      ├── Cryptographic Token Verification (Ensures issuer is authorized)
      ▼
[ Hardware Subsystem Execution ] (CameraCaptureEngine / MediaProjection / GPS)
```

---

## 10. Role and Capability Matrix

| Operational Capability | Tier 0: OWNER | Tier 1: ADMIN | Tier 2: USER | Tier 3: VISITOR | Enforcement Mechanism |
| :--- | :---: | :---: | :---: | :---: | :--- |
| **View Full Device Catalog (Unassigned/Spares)** | ✅ Full | ❌ Blocked | ❌ Blocked | ❌ Blocked | PostgreSQL RLS on `devices` table |
| **View Assigned Fleet Trackers** | ✅ Full | ✅ Assigned | ✅ Assigned | ❌ Blocked | PostgreSQL RLS on `user_device_assignments` |
| **Register / Provision New Tracker Hardware** | ✅ Allowed | ❌ Blocked | ❌ Blocked | ❌ Blocked | PostgreSQL RPC (`register_tracker_hardware`) |
| **Factory Wipe / Retire Tracker Hardware** | ✅ Allowed | ❌ Blocked | ❌ Blocked | ❌ Blocked | PostgreSQL RPC (`retire_tracker_hardware`) |
| **Appoint / Demote Administrators** | ✅ Allowed | ❌ Blocked | ❌ Blocked | ❌ Blocked | PostgreSQL RPC (`appoint_admin`, `demote_admin`) |
| **Approve Visitor -> User Promotion** | ✅ Allowed | ✅ Allowed | ❌ Blocked | ❌ Blocked | PostgreSQL RPC (`approve_user_access`) |
| **Assign / Unassign Devices to Users** | ✅ Allowed | ✅ Allowed | ❌ Blocked | ❌ Blocked | PostgreSQL RLS on `user_device_assignments` |
| **Grant / Revoke Feature Permissions** | ✅ Allowed | ✅ Allowed | ❌ Blocked | ❌ Blocked | PostgreSQL RLS on `device_feature_permissions` |
| **Set Time-Based Permission Expirations** | ✅ Allowed | ✅ Allowed | ❌ Blocked | ❌ Blocked | PostgreSQL `expires_at` column constraint |
| **Deactivate / Suspend User Accounts** | ✅ Allowed | ❌ Blocked | ❌ Blocked | ❌ Blocked | PostgreSQL RPC (`set_user_status`) |
| **Access Master Security Audit Logs** | ✅ Full | ❌ Blocked | ❌ Blocked | ❌ Blocked | PostgreSQL RLS on `audit_logs` |
| **View Live Location on Map** | ✅ Full | ✅ Assigned | 🔓 If Granted | ❌ Blocked | Realtime private channel + RLS |
| **View Historical Location Breadcrumbs** | ✅ Full | ✅ Assigned | 🔓 Windowed | ❌ Blocked | Time-windowed RLS on `locations` |
| **Stream Live Camera Video (`start_camera`)** | ✅ Allowed | ✅ Allowed | 🔓 If Granted | ❌ Blocked | `CommandRouter.kt` + RLS on `commands` |
| **Stream Live Microphone Audio (`start_mic`)** | ✅ Allowed | ✅ Allowed | 🔓 If Granted | ❌ Blocked | `CommandRouter.kt` + RLS on `commands` |
| **Stream Live Screen Capture (`start_screen`)** | ✅ Allowed | ✅ Allowed | 🔓 If Granted | ❌ Blocked | `CommandRouter.kt` + RLS on `commands` |
| **Browse / Transfer Files (`list_files`)** | ✅ Allowed | ✅ Allowed | 🔓 If Granted | ❌ Blocked | WebRTC DataChannel auth handshake |
| **Observe Device Diagnostics (Battery/RSSI)** | ✅ Full | ✅ Assigned | 🔓 If Granted | ❌ Blocked | `TrackerDiagnosticsProvider` + RLS |
| **Trigger Remote Reboot / Diagnostics** | ✅ Allowed | ❌ Blocked | ❌ Blocked | ❌ Blocked | `CommandRouter.kt` (`DIAGNOSTICS:EXECUTE`) |
| **Manage Geofences (Create/Edit/Delete)** | ✅ Allowed | ✅ Allowed | ❌ Blocked | ❌ Blocked | `PersistentGeoFenceRepository` |

---

## 11. Security Threat Model

| Threat ID | Threat Scenario | Impact | Severity | Current Mitigation | Remediation |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **TM-01** | **Client Privilege Escalation**<br>Attacker decompiles APK, patches `User.role` to `OWNER`, and connects to backend. | Universal administrative takeover; access to all devices. | **CRITICAL** | None (client uses mock stubs). | Server-enforced roles via Supabase Auth custom claims hook; PostgreSQL RLS checks `auth.jwt() ->> 'app_role'`. |
| **TM-02** | **Unauthorized Fleet Enumeration**<br>Attacker subscribes to `public:locations` with anon key to harvest tracker IDs. | Complete disclosure of physical fleet inventory and locations. | **CRITICAL** | None (Realtime channel is global and public). | Migrate to private presence channels (`device:{deviceId}:telemetry`) authenticated by user JWT. |
| **TM-03** | **Historical Telemetry Siphoning**<br>Attacker queries PostgREST `public.locations` without authentication. | Full leakage of 105,930 historical GPS coordinates and timestamps. | **CRITICAL** | None (`public.locations` has RLS disabled). | Enable and force RLS on `locations`. Restrict SELECT to users with active assignment. |
| **TM-04** | **Unauthenticated Surveillance Command Injection**<br>Attacker broadcasts `start_camera` on `public:commands`. | Unauthorized live video/audio wiretapping of physical tracker. | **CRITICAL** | None (`commands` has wildcard policy `public_all`). | Drop `public_all`. Restrict INSERT on `commands` to authorized users; tracker verifies issuer token. |
| **TM-05** | **Man-in-the-Middle WebRTC Signaling Eavesdropping**<br>Attacker intercepts `webrtc-$sessionId` topic. | Video feed interception or rogue media injection. | **CRITICAL** | None (session IDs broadcasted in cleartext). | Restrict Realtime channels via server-signed ephemeral tokens. |
| **TM-06** | **Telemetry Spoofing & Trajectory Poisoning**<br>Attacker inserts fabricated GPS fixes into `locations`. | Operator deceived by false tracker locations; alert fatigue. | **HIGH** | Client checks `isValidCoordinates()`. | Authenticate tracker inserts via per-device API key hash verification (`devices.api_key_hash`). |
| **TM-07** | **Zombie Sessions After Access Revocation**<br>Admin revokes access, but tracker continues streaming over WebRTC. | Former user retains surveillance access after access termination. | **CRITICAL** | None (revocation only cancels local UI flows). | Deploy `SecurityRevocationManager`: emit revocation broadcast to terminate WebRTC and clear client cache. |

---

## 12. User-Management UI and Navigation Audit

### 12.1 Sidebar Consolidation
- **Finding**: Currently, user management is split into two placeholder routes in `Destination.kt`: `Destination.Users` (`admin/users`) and `Destination.Permissions` (`admin/permissions`).
- **Remediation**: Consolidate into a single entry point **"User and Permissions"** (`admin/users_and_permissions`), eliminating redundant navigation items in compliance with Ponytail principles.

### 12.2 Screen Architecture & UI/UX Pro Max Specs
The consolidated screen will feature:
1. **PrimaryTabRow Navigation**:
   - Tab 1: **Pending Approvals** (Badged with count of unapproved Visitors).
   - Tab 2: **User Directory** (List of users with role badges: Owner, Admin, User, Visitor).
   - Tab 3: **Device Assignments** (Matrix of assigned trackers, active feature toggles, and expiration dates).
   - Tab 4: **Audit Trail** (Tamper-evident log of administrative actions).
2. **Device Assignment & Feature Sheet**:
   - Bottom sheet displaying device checklist.
   - Granular feature toggles: Location Visibility, Camera, Microphone, Screen Capture, Audio Playback, Files, Diagnostics.
   - Expiration picker: `1 Hour`, `24 Hours`, `7 Days`, `30 Days`, `Custom`, `Permanent`.
3. **Owner-Only Section**:
   - Admin management (Appoint User -> Admin / Demote Admin -> User).
   - Unassigned device inventory.
   - Emergency system freeze button.

### 12.3 Route Guards & Drawer Dynamic Filtering
- In `MainShellScreen.kt`, dynamically filter navigation items based on `currentUser.role`.
- In `TrackMateNavHost.kt`, wrap routes in composable route guards that redirect unauthorized navigation back to `Destination.Map` with an error snackbar.

---

## 13. Versioning and Update-System Audit

### 13.1 Version Baseline
- **Official Version**: `0.1.0` (versionCode `1`) declared in `app/build.gradle.kts:19-20`.
- **Status**: Development prototype. No version `1.0.0` release authorized.
- **Centralization Gap**: Version is scraped via regex in `trace.ps1` and shell grep in `release.yml`. Stale fallbacks to `"1.0.0"` exist in `TraceApplication.kt:438` and `scripts/generate_manifest.py:10`.

### 13.2 Update Engine Maturity
- Fully implemented production-grade OTA update engine in `:core` (`DefaultUpdateRepository`, `HttpUpdateDownloader`, `DefaultUpdateVerifier`, `AndroidPackageInstaller`).
- Supports HTTPS downloads, constant-time SHA-256 validation (`MessageDigest.isEqual`), signing cert pinning (`b1c5128cd81bd989c0dd05d5cd5eb8f332b7283428e28dfe81100d5a0ba606c2`), and native Android 8.0–15 `PackageInstaller.Session` installation.

### 13.3 Release Governance
- Progression roadmap: `0.1.x` -> `0.2.x` -> `0.3.x` -> `1.0.0`.
- Owner release authorization gate: Publishing releases must enforce `-OwnerAuthorized` flag in tooling and manual reviewer approval in GitHub Actions.

---

## 14. Testing and Emulator Verification Capabilities

### 14.1 Host Infrastructure
- **Virtual Device**: AVD `Pixel_10_Pro` (Android API 37.1 / Preview, x86_64, 4GB RAM) with official Google Play Store and active AEHD hypervisor. **Pixel 5 AVD is not installed**.
- **Physical Device**: Xiaomi Redmi 11 Prime (`22071219AI`, `rock_in`) recognized in `trace.ps1:280-284`, currently disconnected.
- **Automated Unit Tests**: **499 tests execute and pass cleanly (100% pass rate, 0 failures, 0 errors)** in ~22 seconds across `:core` (394) and `:app` (105).
- **Instrumentation Gap**: Zero `androidTest` directories exist. Real on-device UI rendering, navigation animations, and Keystore crypto are validated only via JVM simulations.

### 14.2 Hermetic Auth Testing Strategy
To test authentication without relying on real Google accounts in automated CI:
1. `FakeGoogleCredentialClient`: Returns deterministic mock Google ID Tokens signed with a test RSA key.
2. Ktor `MockEngine`: Intercepts Supabase GoTrue token exchange and returns mock session JWTs.
3. `FakeCredentialStore`: In-memory storage eliminating Android Keystore dependencies during JVM unit tests.

---

## 15. Cross-Agent Contradictions and Their Resolution

### Contradiction 1: Cold App Launch Gating Order
- **Conflict**: PRD Section 3 specifies: `App Open -> 4-Digit PIN -> Valid? Yes -> Authentication -> Main App`. However, `MainActivity.kt:217-332` enforces `AuthenticationState (LoginScreen)` first, and only presents `PinScreen` after login.
- **Resolution**: This is documented in **Form 10 (Question 1)** for the Owner's explicit decision. The recommended architecture is **Option A (Device Security Gate)**: prompt for local PIN on cold launch to unlock cached credentials, and only prompt for Google OAuth on first install or session expiration.

### Contradiction 2: Drawer Version Watermark Desynchronization
- **Conflict**: `TraceAppDrawer.kt:410` hardcodes text `"Trace Controller • v1.0.0"`, whereas `app/build.gradle.kts:20` declares `versionName = "0.1.0"`.
- **Resolution**: Refactor `TraceAppDrawer.kt` to bind dynamically to `BuildConfig.VERSION_NAME`, ensuring single-source consistency.

### Contradiction 3: Command Envelope ID Type Mismatch
- **Conflict**: `DefaultCameraRepository.kt:44` generates string UUIDs (`UUID.randomUUID().toString()`), but `SupabaseFleetManager.kt:261` encodes `id = command.commandId.toLongOrNull()`. Because UUIDs are non-numeric, `id` evaluates to `null`, causing command responses to be dropped and timing out.
- **Resolution**: Update `TrackerCommandEnvelope` to use `val id: String? = null` across all transport layers.

### Contradiction 4: Admin Access Inversion in Authorization Engine
- **Conflict**: `DefaultAuthorizationEngine.kt:40` checks `user.devicePermissions.find { it.deviceId == deviceId } == null` before checking `user.role == Role.ADMIN` at line 49. Because stub admin users have an empty permission list, all device commands fail with `DeviceAccessDenied`.
- **Resolution**: Invert the evaluation logic to grant `Role.ADMIN` and `Role.OWNER` universal device access prior to evaluating specific permission entries.

---

## 16. Confirmed Gaps and Risks (Prioritized by Severity)

| Finding ID | Severity | Category | Risk Description | Remediation Phase |
| :--- | :---: | :--- | :--- | :---: |
| **GAP-01** | **CRITICAL** | Backend Security | `public.locations` RLS disabled; 105,930 historical records publicly readable/deletable. | Phase 2 |
| **GAP-02** | **CRITICAL** | Backend Security | Wildcard `public_all` policy on `commands` allows arbitrary unauthenticated surveillance commands. | Phase 2 |
| **GAP-03** | **CRITICAL** | Identity / Auth | Controller uses mock stubs; real Google Sign-In and Supabase Auth are completely missing. | Phase 1 & 5 |
| **GAP-04** | **CRITICAL** | Device Security | Tracker hardware ID has 16-bit entropy (>85% collision risk on 500 units); rotates on reinstall. | Phase 4 |
| **GAP-05** | **CRITICAL** | Signaling Security| `webrtc-$sessionId` signaling topics are public; anyone can intercept SDP offers or inject media. | Phase 6 |
| **GAP-06** | **CRITICAL** | Session Lifecycle| Revoking permissions cancels local UI flows only; tracker continues streaming media indefinitely. | Phase 7 |
| **GAP-07** | **HIGH** | Protocol Mismatch| Controller sends `"start_audio"`, tracker listens for `"start_mic"`; audio capture fails. | Phase 6 |
| **GAP-08** | **HIGH** | Protocol Mismatch| Controller sends `"list_files"` over broadcast; tracker accepts file commands via DataChannel only. | Phase 6 |
| **GAP-09** | **HIGH** | Lifecycle State | Deleting a device purges local memory only; tracker auto-resurrects on next GPS broadcast. | Phase 2 & 4 |
| **GAP-10** | **HIGH** | Quality / Testing | Zero instrumentation tests (`androidTest`) exist; no automated tests run on device/emulator. | Phase 9 |
| **GAP-11** | **MEDIUM** | Architecture | Versioning scraped via regex in scripts; stale "1.0.0" fallbacks exist in code and manifest. | Phase 8 |
| **GAP-12** | **MEDIUM** | Domain Model | `Role.kt` lacks `OWNER` and `VISITOR`; `Action.kt` is ignored by authorization engine. | Phase 1 |

---

## 17. Unresolved Product Decisions (Owner Questionnaire)

The following three questions require product decisions from the Owner:

### Question 1: Initial App-Open Flow (PIN vs Google Sign-In)
- **Option A (Device Security Gate - Recommended)**: The 4-digit PIN acts as the initial local device lock on cold launch to decrypt stored credentials. Google Sign-In is only prompted on initial setup or session expiry.
- **Option B (Post-Login Session Lock)**: User must log in with Google OAuth first, and the 4-digit PIN acts solely as a screen lock while authenticated.

### Question 2: Visitor Sandbox Experience
- **Option A (Zero-Access Pending Screen - Recommended)**: Authenticated Visitor accounts display a clean `PendingApprovalScreen` ("Awaiting administrator approval") with zero device access, zero map markers, and zero telemetry.
- **Option B (Synthetic Demo Tracker)**: Render a simulated sandbox tracker with synthetic demo GPS coordinates (e.g. simulated walking loop) so visitors can explore the UI without accessing real hardware.

### Question 3: Tracker Identity Migration Strategy
- **Option A (Clean UUID Migration with Legacy Aliases - Recommended)**: Introduce immutable hardware UUIDs for all new enrollments, while mapping existing names (`"Google sdk_gphone16k_x86_64 (E10B)"`) as immutable aliases to preserve the 105,930 historical location rows.
- **Option B (Clean Slate Archive)**: Archive existing location data into `locations_archive` and require all trackers to re-enroll with fresh UUIDs and cryptographic API keys.

---

## 18. Proposed Target Architecture

```
                                  [ Android Controller Client ]
                                                │
                          ┌─────────────────────┴─────────────────────┐
                          ▼                                           ▼
             [ Google Credential Manager ]               [ EncryptedPreferencesStorage ]
             (OIDC ID Token from Play Services)          (MasterKey Keystore Session Cache)
                          │                                           ▲
                          ▼                                           │
             [ Supabase GoTrue Auth Module ] ─────────────────────────┘
             (signInWith(IdToken) / Token Refresh)
                          │
                          │ Validated JWT (sub = user_id, app_role, account_status)
                          ▼
             [ Supabase PostgreSQL 15 Backend ]
             ├── public.profiles (Auth User Extension & Roles)
             ├── public.devices (Hardware Registry & API Key Hashes)
             ├── public.user_device_assignments (Many-to-Many RBAC Mapping)
             ├── public.device_feature_permissions (Granular Feature Grants)
             ├── public.audit_logs (Immutable Tamper-Evident Trail)
             └── RLS Policies (Enforces auth.uid() & has_device_feature_permission())
                          │
                          ├── pg_notify / Realtime Replication
                          ▼
             [ Supabase Private Realtime Channels ] (`device:{deviceId}:commands`)
                          │
                          ├── Authenticated WebSocket Subscription (x-tracker-api-key)
                          ▼
             [ Background Tracker Fleet Service (tracker_app) ]
             ├── LiveLocationService (GPS Fused Location Provider)
             ├── CameraCaptureEngine (Front/Back Camera Video Streams)
             ├── AudioUplinkSession (Ambient Microphone Stream)
             └── WebRtcSession (P2P Media & Ordered DataChannels)
```

---

## 19. Proposed Phased Implementation Roadmap

```
Phase 0: Architecture & Requirements Approval (Resolve Form 10 Decisions)
   │
   ▼
Phase 1: Domain & Security Refactoring (Role, User, Action models)
   │
   ▼
Phase 2: Database Schema & Supabase OIDC Integration (DDL, Custom Claims Hook)
   │
   ▼
Phase 3: User Approval & Administration Backend (RPCs, Grants, Audit Logs)
   │
   ▼
Phase 4: User and Permissions UI (Consolidated Route, Assignment Sheet)
   │
   ▼
Phase 5: Google Login & Visitor Experience (Credential Manager, GoTrue Auth)
   │
   ▼
Phase 6: Feature Gating & Secure Data Delivery (Private Realtime, Command Fixes)
   │
   ▼
Phase 7: Revocation, Expiration, & Session Handling (SecurityRevocationManager)
   │
   ▼
Phase 8: Update Integration & Release Governance (Version Centralization)
   │
   ▼
Phase 9: Comprehensive Verification & Release Readiness (Matrix Execution)
```

---

## 20. Phase-by-Phase Acceptance Criteria

### Phase 0 — Architecture and Requirements Approval
- **Scope**: Resolve Form 10 product decisions and obtain Owner sign-off on target architecture.
- **Acceptance Criteria**: Owner resolves Questions 1, 2, and 3; architecture document approved.

### Phase 1 — Domain & Security Model Refactoring
- **Scope**: Refactor `Role.kt` (`OWNER`, `ADMIN`, `USER`, `VISITOR`), `AccountStatus.kt` (`PENDING`), `DevicePermission.kt` (`expiresAt`), and fix `DefaultAuthorizationEngine.kt` admin logic inversion.
- **Acceptance Criteria**: Unit tests assert unassigned `ADMIN` and `OWNER` pass authorization; `Action` evaluation verified; all 499 tests pass.

### Phase 2 — Database Model and Device Assignment
- **Scope**: Deploy Form 3 DDL on Supabase; backfill `devices` from `locations`; deploy RLS policies and helper functions.
- **Acceptance Criteria**: Schema applied cleanly; unassigned users receive 0 rows from `locations`; authenticated trackers insert via API key hash.

### Phase 3 — User Approval and Administration Backend
- **Scope**: Implement PostgreSQL RPCs: `approve_user_access`, `appoint_admin`, `demote_admin`, `set_user_status`.
- **Acceptance Criteria**: Non-owner cannot appoint admins; visitor promotion creates assignment and feature rows atomically.

### Phase 4 — User and Permissions UI
- **Scope**: Consolidate `Destination.Users` and `Destination.Permissions` into `Destination.UsersAndPermissions`; implement tabs, assignment sheet, and owner controls.
- **Acceptance Criteria**: Admin can approve visitors, toggle feature permissions, set expirations; UI dynamically reflects updates.

### Phase 5 — Google Login and Visitor Experience
- **Scope**: Add Credential Manager and Supabase Auth SDK; implement `GoogleCredentialClient`; render `PendingApprovalScreen` for Visitors.
- **Acceptance Criteria**: Google sign-in mints valid Supabase JWT; visitor accounts cannot access device screens or telemetry.

### Phase 6 — Feature Gating and Secure Data Delivery
- **Scope**: Migrate to private Realtime channels; fix audio (`start_mic`) and file command protocol mismatches; wire `DefaultCameraRepository`.
- **Acceptance Criteria**: Audio and camera commands execute on physical tracker; unauthorized feature routes redirect to map.

### Phase 7 — Revocation, Expiration, and Session Handling
- **Scope**: Implement `SecurityRevocationManager`; handle real-time revocation events; teardown WebRTC and purge local caches.
- **Acceptance Criteria**: Revoking access during active camera stream terminates stream within 1,000ms and evicts cached coordinates.

### Phase 8 — Update Integration and Release Preparation
- **Scope**: Centralize versioning in `gradle.properties`; gate updates behind `Role.ADMIN`/`OWNER`; suppress prompts for Visitors.
- **Acceptance Criteria**: Version code and name single-sourced; `trace release` enforces `-OwnerAuthorized` gate.

### Phase 9 — End-to-End Verification and Release Readiness
- **Scope**: Execute all 19 scenarios in Form 9 Verification Matrix across `Pixel_10_Pro` AVD and physical `Xiaomi Redmi 11 Prime`.
- **Acceptance Criteria**: 100% test pass rate across unit, integration, and manual hardware verification; final Owner sign-off.

---

## 21. Migration, Rollback, and Release Considerations

### 21.1 Backward Compatibility with Existing Physical Trackers
- Live Supabase holds 105,930 rows from active devices (`Xiaomi 22071219AI`, `Samsung SM-A156M`, `TECNO KL4`, `Google sdk_gphone16k_x86_64`).
- **Hazard**: Enabling RLS immediately will cause `LiveLocationService.flushTelemetryQueue()` on physical trackers to fail with HTTP 401/403, overflowing queues and dropping GPS fixes.
- **Mitigation**: Deploy Phase 1 and 2 with dual-auth fallback (permitting inserts with either legacy `x-app-secret` or new `x-tracker-api-key`) until all trackers are updated.

### 21.2 Rollback Strategy
- Every SQL migration must include a corresponding down migration script (`down.sql`).
- Client releases must maintain backward compatibility with previous PostgREST endpoints.

---

## 22. Final Readiness Assessment & Recommendation

| Assessment Dimension | Readiness Status | Forensic Justification |
| :--- | :---: | :--- |
| **Telemetry & Map Pipeline** | **READY** | Validated monotonic coordinates, zero coordinate fabrication, dual-map providers. |
| **Authentication & Identity** | **NOT READY** | Entirely mocked; zero real OAuth or GoTrue integration. |
| **Backend & Row-Level Security** | **NOT READY** | Telemetry and control signals have RLS disabled; commands have wildcard public policies. |
| **Tracker Protocols & Hardware ID** | **NOT READY** | 16-bit identifier collision risk; command name protocol mismatches (`start_audio` vs `start_mic`). |
| **Administration & UI Screens** | **NOT READY** | 8 destinations route to placeholder screens; no consolidated management UI. |
| **Update Subsystem (OTA)** | **READY** | Mature package installer, constant-time SHA-256 verifier, cert pinning. |
| **Automated Test Infrastructure** | **PARTIAL** | 499 unit tests passing cleanly; zero on-device instrumentation tests exist. |

### Final Recommendation: **PROCEED TO PHASED IMPLEMENTATION**
The system architecture, failure modes, protocol divergences, and database security gaps are fully mapped. All 10 audit workstreams are complete. 

**Next Steps**:
1. Review and resolve the 3 targeted product decisions in **Section 17 (Form 10)**.
2. Authorize proceeding to **Phase 0 & Phase 1** of the implementation roadmap.

---

## 23. Evidence References and Limitations

### 23.1 Inspected File References
- `controller_app/app/build.gradle.kts`: Lines 12-22, 62-68, 70-135
- `controller_app/core/build.gradle.kts`: Lines 1-16, 46-55
- `controller_app/app/src/main/AndroidManifest.xml`: Lines 1-50
- `controller_app/app/src/main/java/com/livetracker/controller/TraceApplication.kt`: Lines 44-138, 151-164, 194-196, 228-277, 438, 481-492
- `controller_app/app/src/main/java/com/livetracker/controller/MainActivity.kt`: Lines 99-168, 217-332
- `controller_app/app/src/main/java/com/livetracker/controller/navigation/TrackMateNavHost.kt`: Lines 47, 180-270
- `controller_app/app/src/main/java/com/livetracker/controller/navigation/Destination.kt`: Lines 15-55
- `controller_app/app/src/main/java/com/livetracker/controller/main/TraceAppDrawer.kt`: Lines 364-386, 410
- `controller_app/app/src/main/java/com/livetracker/controller/auth/LoginScreen.kt`: Lines 37-75
- `controller_app/core/src/main/java/com/livetracker/controller/core/auth/data/StubAuthRepositories.kt`: Lines 9-25, 27-69
- `controller_app/core/src/main/java/com/livetracker/controller/core/auth/domain/AuthorizationEngine.kt`: Lines 13-61
- `controller_app/core/src/main/java/com/livetracker/controller/core/auth/domain/Role.kt`: Lines 3-6
- `controller_app/core/src/main/java/com/livetracker/controller/core/tracker/supabase/SupabaseClientProvider.kt`: Lines 24-45
- `controller_app/core/src/main/java/com/livetracker/controller/core/tracker/supabase/SupabaseFleetManager.kt`: Lines 137-187, 212-215, 224-238, 241-282
- `controller_app/core/src/main/java/com/livetracker/controller/core/tracker/supabase/SupabaseTrackerTransport.kt`: Lines 46-58, 107-113
- `controller_app/core/src/main/java/com/livetracker/controller/core/tracker/signaling/SupabaseSignalingTransport.kt`: Lines 25-99, 133
- `tracker_app/app/src/main/java/com/example/livelocationservice/LiveLocationService.kt`: Lines 144-166, 441-447, 556, 651-732, 2076-2088
- `tracker_app/app/src/main/java/com/example/livelocationservice/tracker/SupabaseConnectionManager.kt`: Lines 115-155
- `release/update-manifest.json`: Lines 1-15
- `scripts/trace.ps1`: Lines 280-284, 870-980, 990-1110, 1379-1480, 1658-1669

### 23.2 Access Limitations
- Physical test device (`Xiaomi Redmi 11 Prime`) is currently disconnected from wireless debugging; live hardware verification was established via previous session logs (`rock_in` / `22071219AI`).
- Supabase database was audited via read-only SQL queries and policy enumeration; no write tests, schema migrations, or user creations were executed.

---
*Certified by Coordinating Agent and recorded into `.agent_memory/memory.md`.*
