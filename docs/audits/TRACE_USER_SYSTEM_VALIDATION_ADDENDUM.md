# TRACE — Master Audit Validation Addendum (Read-Only)

**Document Reference**: `TRACE-AUDIT-2026-ADDENDUM`  
**Target Repository**: `controller_app` (`c:\Users\ZNS\Documents\live_tracker\controller_app`) & `tracker_app` contracts  
**Audited Backend**: Supabase Cloud Production Instance `jyiqhqxjoahlxflaated.supabase.co` (PostgreSQL 15.8)  
**Execution Timestamp**: October 16, 2026 — 14:45 UTC  
**Validation Classification**: STRICT READ-ONLY EVIDENCE VALIDATION  
**Coordinating Verifier**: Master Audit Validation Orchestrator  

---

## 1. Executive Verdict

This addendum independently validates the claims, findings, proposed SQL migrations, protocol contracts, and test claims documented in `docs/audits/TRACE_USER_SYSTEM_MASTER_AUDIT_DRAFT.md`. 

### Summary Verdict:
- **Live Supabase Security Posture**: **CRITICAL EXPOSURE CONFIRMED**. `public.locations` (107,601 rows) has Row-Level Security **DISABLED**. `public.commands` and `public.media` have RLS enabled, but have an open wildcard policy `public_all` (`qual: true, with_check: true` for role `{public}`). Anyone with the hardcoded `anon` key can read, modify, or truncate all fleet data.
- **Proposed SQL Review (Form 3 DDL)**: **CRITICAL FLAWS IDENTIFIED**. The proposed migration contains severe security defects:
  1. A race condition in `handle_new_user()` allowing any first registrant to capture permanent `OWNER` status.
  2. Missing `search_path = public, pg_temp` across all `SECURITY DEFINER` functions (privilege escalation hazard).
  3. `commands_insert_user` checks only device assignment, permitting standard users with location-only access to execute camera/microphone surveillance commands.
  4. `audit_logs` permits arbitrary unauthenticated `INSERT` by any authenticated client, allowing log spoofing.
  5. `ON DELETE CASCADE` from `devices` to `locations` risks accidental catastrophic erasure of historical telemetry.
- **Tracker Authentication & Command Trust**: **CRITICAL EXPOSURE CONFIRMED**. The background tracker service (`tracker_app`) trusts unauthenticated commands from `public:commands` based solely on matching string `cmd.deviceId == deviceId`. There is zero replay protection, zero command expiry, and zero sender signature verification.
- **Test Evidence**: **VERIFIED**. 499 passing unit tests confirmed via `.\gradlew.bat testDebugUnitTest` (exit code 0 in 12s; 394 in `:core`, 105 in `:app`). Instrumentation tests confirmed **0% coverage (0 `androidTest` files exist)**.
- **Readiness Determination**: **`READY FOR PHASE 0 REVIEW (CONDITIONAL)`**. Implementation must NOT begin until the critical SQL defects are corrected, the legacy authentication migration bypass is eliminated, and the Owner resolves the three pending product decisions.

---

## 2. Findings Confirmed by Independent Inspection

| Finding / Claim | Verification Method | Live Evidence / Artifact | Verification Status | Confidence |
| :--- | :--- | :--- | :---: | :---: |
| **`public.locations` RLS Disabled** | Direct query to `pg_tables` on `jyiqhqxjoahlxflaated` | `tablename: "locations", rowsecurity: false`. 107,601 rows active. | **VERIFIED** | 100% |
| **`public.commands` Wildcard Policy** | Query to `pg_policies` on `jyiqhqxjoahlxflaated` | `policyname: "public_all", roles: "{public}", cmd: "ALL", qual: "true", with_check: "true"`. | **VERIFIED** | 100% |
| **`public.media` Wildcard Policy** | Query to `pg_policies` on `jyiqhqxjoahlxflaated` | `policyname: "public_all", roles: "{public}", cmd: "ALL", qual: "true", with_check: "true"`. | **VERIFIED** | 100% |
| **`public.app_signals` RLS Disabled** | Query to `pg_tables` on `jyiqhqxjoahlxflaated` | `tablename: "app_signals", rowsecurity: false`. 134 rows active. | **VERIFIED** | 100% |
| **Realtime Public Replication** | Query to `pg_publication_tables` | Publication `supabase_realtime` publishes all 4 public tables (`locations`, `commands`, `media`, `app_signals`). | **VERIFIED** | 100% |
| **Zero Supabase Auth Users** | Query to `auth.users` | `SELECT count(*) FROM auth.users` returns `0`. | **VERIFIED** | 100% |
| **No Database Migrations** | Query to `information_schema.tables` | No `public.schema_migrations` or `supabase_migrations` table exists. | **VERIFIED** | 100% |
| **Static Header Obscurity** | Code inspection `SupabaseClientProvider.kt:24-37` | Injects `x-app-secret: zns-tracker-secret-2026`. No Postgres policy or trigger inspects this header. | **VERIFIED** | 100% |
| **Colliding Tracker Hardware ID** | Code inspection `LiveLocationService.kt:2081` | Concatenates `Build.MODEL` + `ANDROID_ID.takeLast(4)`. 16-bit entropy. | **VERIFIED** | 100% |
| **Audio Command Protocol Divergence** | Code inspection `SupabaseFleetManager.kt:254` vs `LiveLocationService.kt:674` | Controller dispatches `"start_audio"`; tracker only listens for `"start_mic"`. Commands are silently dropped. | **VERIFIED** | 100% |
| **File Command Protocol Divergence** | Code inspection `SupabaseFleetManager.kt:258` vs `WebRtcFileTransferTransport.kt` | Controller broadcasts `"list_files"`; tracker accepts file operations strictly over WebRTC DataChannel. | **VERIFIED** | 100% |
| **Simulated Camera Wiring** | Code inspection `TraceApplication.kt:194` | Wires `SimulatedCameraRepository`. `DefaultCameraRepository` is orphaned. | **VERIFIED** | 100% |
| **Admin Authorization Inversion** | Code inspection `DefaultAuthorizationEngine.kt:40-51` | Checks empty `devicePermissions` before checking `Role.ADMIN`, blocking admins. | **VERIFIED** | 100% |
| **499 Passing Unit Tests** | Direct CLI execution `.\gradlew.bat testDebugUnitTest --no-daemon` | Exit Code 0. XML reports: `:core` (394 tests, 0 failures), `:app` (105 tests, 0 failures). | **VERIFIED** | 100% |
| **Zero Instrumentation Tests** | Filesystem inspection | `Test-Path core/src/androidTest` -> `False`. `Test-Path app/src/androidTest` -> `False`. | **VERIFIED** | 100% |
| **Host AVD Hardware Profile** | CLI execution `emulator -list-avds` & config inspection | Installed AVD: `Pixel_10_Pro` (API 37.1, Play Store enabled). Pixel 5 AVD **does not exist**. | **VERIFIED** | 100% |

---

## 3. Findings Only Partially Verified

1. **Tracker WebRTC P2P Media Streaming**:
   - *Status*: **PARTIALLY VERIFIED**.
   - *Evidence*: Code inspection confirms `tracker_app` implements `TrackerWebRtcManager` and `CameraCaptureEngine`. Controller implements `AndroidWebRtcSession`. However, because `TraceApplication.kt` wires `SimulatedCameraRepository` and does not instantiate `AndroidWebRtcSessionFactory`, true P2P video stream negotiation could not be verified in live execution.
2. **Offline Telemetry Queue FIFO Eviction**:
   - *Status*: **PARTIALLY VERIFIED**.
   - *Evidence*: `tracker_app` unit test `TelemetryQueueTest.kt` passes with 1000-item FIFO cap. However, actual flash-memory behavior under sustained offline GPS collection on physical hardware was not tested during this read-only audit.

---

## 4. Findings That Remain Unverified

1. **Physical Xiaomi Redmi 11 Prime ADB Behavior**:
   - *Status*: **UNVERIFIED AT RUNTIME**.
   - *Limitation*: The physical test phone (`Xiaomi Redmi 11 Prime`, `22071219AI`) is currently disconnected from wireless debugging (0 devices attached in `adb devices`). While previous logcat captures corroborate its operation, live on-device behavior could not be exercised during this session.
2. **Cellular Carrier Network NAT Traversal (STUN/TURN)**:
   - *Status*: **UNVERIFIED**.
   - *Limitation*: No TURN server configuration exists in `controller_app` or `tracker_app` (both use Google public STUN `stun:stun.l.google.com:19302`). Symmetric NAT traversal across real mobile carrier networks remains unverified.

---

## 5. Production Security Exposure and Containment Recommendations

### 5.1 Active Production Security Exposure
The live Supabase database `jyiqhqxjoahlxflaated.supabase.co` is currently accessible to anyone on the internet holding the public `anon` key (which is compiled into every distributed APK):
- **107,601 Location Records Exposed**: Real-time and historical whereabouts of all active trackers can be downloaded or purged by any unauthenticated third party.
- **Rogue Command Injection**: Any actor can broadcast commands causing physical phones to secretly record audio (`start_mic`) or stream screens (`start_screen`).
- **Telemetry Poisoning**: Fraudulent GPS tracks can be inserted to forge device locations.

### 5.2 Immediate Containment Recommendation (Pre-Implementation)

> [!IMPORTANT]
> **DO NOT EXECUTE WITHOUT EXPLICIT OWNER AUTHORIZATION**  
> The following containment actions will close the critical data exposure immediately, but require synchronized coordination:

1. **Containment Action 1 (Drop Wildcard Command & Media Backdoors)**:
   ```sql
   DROP POLICY IF EXISTS "public_all" ON public.commands;
   DROP POLICY IF EXISTS "public_all" ON public.media;
   ```
   *Impact*: Immediately stops unauthorized third-party surveillance commands via PostgREST. Does not break tracker GPS location broadcast.
2. **Containment Action 2 (Temporary Read Restriction on Locations)**:
   ```sql
   ALTER TABLE public.locations ENABLE ROW LEVEL SECURITY;
   ALTER TABLE public.locations FORCE ROW LEVEL SECURITY;
   -- Allow INSERT from public (preserving live tracker ingestion)
   CREATE POLICY "allow_tracker_insert_temp" ON public.locations FOR INSERT TO public WITH CHECK (true);
   -- Block unauthenticated SELECT
   CREATE POLICY "block_public_select_temp" ON public.locations FOR SELECT TO authenticated USING (false);
   ```
   *Impact*: Preserves ongoing tracker location collection in PostgreSQL while immediately blinding unauthorized internet scrapers from reading historical coordinates. Note: Controller app will receive 0 historical locations on app launch until Controller Auth is deployed.

---

## 6. SQL and RLS Review Findings (`docs/FORM_3_DATABASE_BACKEND_AUTH_AUDIT.md`)

An exhaustive security and architectural review of the proposed DDL and policies was conducted. **Multiple critical defects were identified**:

| SQL Object / Policy | Intended Authorization | Code Location in Form 3 | Identified Vulnerability / Defect | Required Correction |
| :--- | :--- | :--- | :--- | :--- |
| **`handle_new_user()` Trigger** | Make designated Owner `shabik163344@gmail.com` Tier 0; make others Visitors. | Form 3: Lines 789–796 | **First-Registrant Race Condition**: Checks `(SELECT count(*) FROM public.profiles) = 0 THEN 'OWNER'`. If any arbitrary Google user logs in before the Owner, they become permanent Owner! | Remove count check. Bind explicitly to email: `WHEN LOWER(new.email) = 'shabik163344@gmail.com' THEN 'OWNER'::user_role ELSE 'VISITOR'::user_role`. |
| **All `SECURITY DEFINER` Functions** | Provide trusted privilege escalation for auth helpers without caller spoofing. | Form 3: Lines 498–615, 781–835 | **Missing Search Path (CVE Hazard)**: Functions omit `SET search_path = public, pg_temp;`. In PostgreSQL, omitting search_path on `SECURITY DEFINER` allows search-path hijacking. | Add `SET search_path = public, pg_temp` to `is_owner`, `is_admin`, `has_device_access`, `has_device_feature_permission`, `is_authenticated_tracker`, `handle_new_user`, and `audit_command_dispatch`. |
| **`commands_insert_user` Policy** | Restrict command dispatch to authorized users holding device & feature grant. | Form 3: Lines 725–731 | **Privilege Escalation**: Policy checks `public.has_device_access(device_id)`, but **fails to check feature permission**. A user with location-only access can insert `start_camera` or `start_mic` commands! | Update policy to check feature grant: verify that `command` maps to an allowed feature via `has_device_feature_permission()`. |
| **`audit_logs_insert_system` Policy** | Allow system triggers to write audit log entries. | Form 3: Lines 762–764 | **Audit Forgery**: Policy is `FOR INSERT TO authenticated WITH CHECK (true)`. Any authenticated user can insert fraudulent audit records, impersonating admins or framing other users. | Drop policy. Revoke direct `INSERT` on `public.audit_logs` from `authenticated`. All audit logging must occur strictly via `SECURITY DEFINER` triggers. |
| **`profiles_update_self` Policy** | Allow users to update their own display name without escalating role/status. | Form 3: Lines 630–637 | **Subquery Recursion & Inefficacy**: Policy checks `role = (SELECT role FROM public.profiles WHERE id = auth.uid())`. Self-referencing subqueries in RLS policies can trigger recursion or performance collapse. | Implement a `BEFORE UPDATE` trigger (`enforce_profile_immutability`) that forces `NEW.role = OLD.role` and `NEW.status = OLD.status` unless caller is Owner. |
| **`is_authenticated_tracker()` Helper** | Authenticate tracker HTTP header `x-tracker-api-key`. | Form 3: Line 600 | **Malformed JSON Cast Exception**: `current_setting('request.headers', true)::json` throws a fatal database exception if the header is not valid JSON. | Use `current_setting('request.headers', true)::jsonb` inside a `BEGIN ... EXCEPTION` block returning `false` on parsing errors. |
| **`fk_locations_devices` Constraint** | Ensure location records reference valid registered devices. | Form 3: Lines 457–460 | **Catastrophic Deletion Cascade**: Specifies `ON DELETE CASCADE`. If a device is retired or deleted from `devices`, **all historical GPS breadcrumbs are permanently deleted**! | Change constraint to `ON DELETE RESTRICT` or `ON DELETE NO ACTION`. Enforce soft-deletion via `status = 'DECOMMISSIONED'` in `devices`. |
| **`devices_tracker_update_heartbeat` Policy** | Allow tracker to update its own `last_seen_at`. | Form 3: Lines 657–660 | **Unbounded Column Update**: Allows tracker to update **all columns** of `devices`, including `name`, `status`, and `api_key_hash`. | Replace with a dedicated `SECURITY DEFINER` RPC `record_tracker_heartbeat(target_device_id)` that updates only `last_seen_at` and `metadata`. |

---

## 7. Tracker Authentication and Command-Trust Review

### 7.1 Tracker Enrollment & Identity
- **Current State**: The tracker has no enrollment handshake. It computes `deviceName(context)` as `"$manufacturer ${Build.MODEL} ($shortId)"` (`LiveLocationService.kt:2081`) and self-declares this identity on `public:locations`.
- **Verdict**: Insecure. Device identity must be an immutable UUIDv4 generated during initial provisioning and stored in `EncryptedSharedPreferences`.

### 7.2 Shared APK-Embedded Secret
- **Current State**: `SupabaseClientProvider.kt` in `tracker_app` injects `x-app-secret: zns-tracker-secret-2026`.
- **Verdict**: Zero security value. The string is compiled into the APK and shared by all trackers and controllers. Decompiling any copy of the app compromises the secret across the entire fleet.

### 7.3 Command Publisher & Origin Trust
- **Current State**: In `LiveLocationService.kt:556`, the tracker receives commands from `public:commands` and executes:
  ```kotlin
  if (cmd.deviceId == deviceId) {
      applyCommand(cmd)
  }
  ```
- **Critical Flaw**:
  1. **Zero Origin Authentication**: Tracker does not verify who published the command.
  2. **Zero Cryptographic Signature**: Tracker does not verify a cryptographic signature or HMAC.
  3. **Zero Expiration or Replay Protection**: `TrackerCommand` payloads contain no `timestamp`, `nonce`, or `ttl`. An attacker can capture a legitimate command envelope and replay it days later.
- **Required Tracker Fix**: Tracker must require a server-signed command token containing `issuer_id`, `command`, `target_device_id`, `timestamp`, and `expires_at` (e.g. valid for 60 seconds).

### 7.4 WebRTC Signaling & Active Stream Revocation
- **Signaling Vulnerability**: Signaling topic `webrtc-$sessionId` is public on Realtime broadcast.
- **Active Capture Revocation**: When access is revoked in `controller_app`, the tracker receives no termination event. Active WebRTC camera/audio capture continues indefinitely until the local socket disconnects or phone sleeps.
- **Required Tracker Fix**: Tracker must listen to an authenticated control topic `device:{deviceId}:control` and immediately invoke `trackerWebRtcManager?.stop()` and `cameraCaptureEngine?.stop()` upon receiving `REVOKE_SESSION`.

---

## 8. Authentication and Owner-Bootstrap Review

### 8.1 Google OIDC & Supabase GoTrue
- **Current State**: Completely mocked in controller (`StubAuthRepositories.kt`).
- **Target Flow**:
  1. Android Controller invokes `CredentialManager.getCredential()` requesting Google ID Token.
  2. ID Token passed to `supabase.auth.signInWith(IdToken)`.
  3. GoTrue verifies token against Google's public JWKS endpoint (`https://www.googleapis.com/oauth2/v3/certs`).
  4. User record provisioned in `auth.users`.

### 8.2 Owner Identity Binding & Escalation Prevention
- **Flaw in Master Report**: Form 3 proposed counting profiles (`count(*) = 0`). This is a critical security vulnerability.
- **Corrected Architecture**:
  The database trigger on `auth.users` must bind exclusively to the verified email address:
  ```sql
  CREATE OR REPLACE FUNCTION public.handle_new_user()
  RETURNS trigger AS $$
  BEGIN
      INSERT INTO public.profiles (id, email, display_name, role, status)
      VALUES (
          new.id,
          new.email,
          COALESCE(new.raw_user_meta_data->>'full_name', new.email),
          CASE 
              WHEN LOWER(new.email) = 'shabik163344@gmail.com' THEN 'OWNER'::user_role
              ELSE 'VISITOR'::user_role
          END,
          CASE 
              WHEN LOWER(new.email) = 'shabik163344@gmail.com' THEN 'ACTIVE'::account_status
              ELSE 'PENDING'::account_status
          END
      );
      RETURN new;
  END;
  $$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;
  ```
- **Custom Claims Hook**: Supabase Auth Hook injects `app_role` into the signed access token JWT. Client-side bytecode modifications cannot escalate permissions because PostgreSQL RLS verifies the cryptographic JWT signature.

### 8.3 PIN Responsibilities & Cold-Launch Behavior
- The 4-digit PIN (`PinManager.kt`) utilizes PBKDF2WithHmacSHA256 (100,000 iterations) with salt stored in `EncryptedPreferencesStorage`.
- **Verdict**: The PIN is a **device possession gate**, not a cloud identity provider. Its correct role is to unlock the Android Keystore on cold launch, decrypting the Supabase refresh token without prompting the user for an interactive Google sign-in every time the app opens.

---

## 9. Migration Safety Review: Legacy Auth Hazard

### 9.1 Evaluation of Master Report Proposal
The master report (`TRACE_USER_SYSTEM_MASTER_AUDIT_DRAFT.md` Section 21.1) proposed a "dual-auth fallback" allowing database inserts with either the legacy `x-app-secret` header or the new `x-tracker-api-key`.

### 9.2 The Threat of Retaining `x-app-secret`
> [!CAUTION]
> Retaining a fallback to `x-app-secret: zns-tracker-secret-2026` in production RLS policies **completely invalidates the security boundary**. Because that secret is hardcoded in every existing APK and disclosed in audit documentation, any attacker can simply include `x-app-secret` in their requests to bypass RLS, inject false telemetry, or harvest fleet data.

### 9.3 Safe Staged Migration Protocol (Zero-Bypass Cutover)
To preserve tracker availability during migration without leaving a global backdoor:

```
[ Step 1: Pre-Migration Registry Backfill ]
Populate public.devices with all existing device_id strings from public.locations.
Generate unique, random 256-bit API keys for each device.
                         │
                         ▼
[ Step 2: Quarantine Ingestion for Legacy Trackers ]
Do NOT allow x-app-secret into public.locations under RLS!
Create public.legacy_telemetry_quarantine with RLS open to x-app-secret.
Production controller app queries ONLY public.locations.
Legacy un-upgraded trackers write ONLY to quarantine table.
                         │
                         ▼
[ Step 3: Coordinated Tracker Upgrade ]
Deploy tracker_app update containing per-device API key and x-tracker-api-key header.
Once upgraded, tracker writes directly to public.locations authenticated by key hash.
                         │
                         ▼
[ Step 4: Deprecation & Quarantine Purge ]
Monitor public.legacy_telemetry_quarantine row count.
When old tracker traffic drops to zero, DROP public.legacy_telemetry_quarantine.
Full zero-trust RLS achieved.
```

---

## 10. Reproducible Test Evidence

### 10.1 Command Execution
- **Command**: `.\gradlew.bat testDebugUnitTest --no-daemon`
- **Working Directory**: `c:\Users\ZNS\Documents\live_tracker\controller_app`
- **Execution Timestamp**: 2026-10-16 14:44:16 UTC
- **Exit Code**: `0` (BUILD SUCCESSFUL in 12s)
- **Output Artifacts**:
  - `core/build/test-results/testDebugUnitTest/`
  - `app/build/test-results/testDebugUnitTest/`

### 10.2 Exact Test Metrics Verified
- **Module `:core`**: **39 test suites, 394 tests, 0 failures, 0 errors, 0 skipped**.
- **Module `:app`**: **12 test suites, 105 tests, 0 failures, 0 errors, 0 skipped**.
- **Total Tested**: **51 test suites, 499 tests passed, 0 failures, 0 errors, 0 skipped (100% pass rate)**.

### 10.3 Test Coverage Scope vs Gaps
- **Verified Under Unit Tests**: Monotonic coordinate ordering, GPS bounds validation (-90..90, -180..180), geofence polygon math, in-memory alert acknowledgments, OTA package verifier constant-time SHA-256 checks, and simulated camera controls.
- **Critical Testing Gap**: **0 instrumentation tests exist**. Jetpack Compose UI rendering, real Android Keystore crypto, WebSocket reconnection under cellular drops, and on-device WebRTC media pipelines have zero automated test coverage.

---

## 11. Contradictions or Corrections to the Master Report

1. **Correction to Owner Bootstrap (`handle_new_user`)**:
   - *Master Report Claim*: First user in database becomes Owner via `count(*) = 0`.
   - *Correction*: This is a severe race condition vulnerability. Owner identity must be hard-bound to `LOWER(new.email) = 'shabik163344@gmail.com'` in the database trigger.
2. **Correction to Migration Fallback**:
   - *Master Report Claim*: Allow dual-auth fallback with `x-app-secret` during migration.
   - *Correction*: Dual-auth fallback leaves the production database 100% vulnerable. Migration must use a segregated quarantine table for un-upgraded trackers.
3. **Correction to `commands` RLS Insertion**:
   - *Master Report Claim*: `commands_insert_user` checks `has_device_access()`.
   - *Correction*: Access alone is insufficient. The policy must check granular `device_feature_permissions` matching the specific command type (`start_camera` requires `CAMERA_STREAM`).
4. **Correction to `locations` Foreign Key Cascade**:
   - *Master Report Claim*: `locations` table references `devices` with `ON DELETE CASCADE`.
   - *Correction*: `CASCADE` risks catastrophic telemetry loss if a device record is deleted. Must be `ON DELETE RESTRICT`.

---

## 12. Revised Dependency-Aware Phase Roadmap

The ten-phase roadmap is restructured to reflect true dependencies and eliminate security exposures during deployment:

```
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 0 — Architecture, Threat & Owner Sign-Off                        │
│ Dependencies: Owner approval of Form 10 decisions & Addendum SQL fixes │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1 — Domain & Security Refactoring (:core)                        │
│ Dependencies: Phase 0. Changes: Role.kt, DevicePermission.kt, Engine  │
│ Deliverable: 4-tier hierarchy, Action evaluation, admin logic inversion│
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 2 — Hardened Database Schema & OIDC Hook (Backend)               │
│ Dependencies: Phase 1. Changes: Supabase DDL, RLS, Helper Functions    │
│ Deliverable: Corrected DDL, Owner trigger, devices backfill            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 3 — Tracker Device Enrollment & Cryptographic API Keys (Backend) │
│ Dependencies: Phase 2. Changes: API key generation, quarantine table   │
│ Deliverable: Per-device credentials issued, tracker auth RPCs deployed │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 4 — Controller Google Auth & Credential Manager (:app)           │
│ Dependencies: Phase 2. Changes: CredentialManager, Supabase Auth SDK   │
│ Deliverable: Real Google Sign-In, Keystore refresh token persistence   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 5 — User Management & Admin UI (:app)                            │
│ Dependencies: Phase 4. Changes: Destination.UsersAndPermissions        │
│ Deliverable: Consolidated management route, assignment sheet, approvals│
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 6 — Protocol Synchronization & Feature Gating (:core & tracker)  │
│ Dependencies: Phase 5. Changes: Audio command (start_mic), WebRTC wiring│
│ Deliverable: Protocol parity, DefaultCameraRepository wired, RLS lock  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 7 — Active Revocation & Push Invalidation (:core & tracker)      │
│ Dependencies: Phase 6. Changes: SecurityRevocationManager              │
│ Deliverable: Instant WebRTC teardown upon permission revocation        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 8 — Version Centralization & Update Governance (Build toolchain) │
│ Dependencies: Phase 7. Changes: gradle.properties, trace.ps1           │
│ Deliverable: Single-source 0.1.0 versioning, -OwnerAuthorized release  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 9 — Comprehensive Matrix Verification & Release Readiness (QA)   │
│ Dependencies: All previous phases. Changes: androidTest test suites    │
│ Deliverable: Full 19-scenario matrix verified on AVD & physical Redmi  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 13. Explicit Blockers Before Implementation

The following items strictly block code execution or migration deployment:
1. **Uncorrected SQL DDL**: Deploying the SQL as currently drafted in Form 3 will introduce privilege escalation vulnerabilities and audit forgery risks.
2. **Tracker Protocol Disconnect**: Modifying controller command paths without a synchronized update to `tracker_app` will break remote audio capture.
3. **Pending Owner Product Decisions**: Navigation structure, cold-launch gating sequence, and visitor sandbox experience depend on Owner decisions.

---

## 14. Required Owner Decisions

The three decisions required from the Owner prior to Phase 1 authorization:

1. **Cold App-Open Flow**:
   - **Recommendation: Option A**. 4-digit PIN unlocks local Keystore session cache. Google OAuth only prompted on initial setup or session expiry.
2. **Visitor Sandbox Experience**:
   - **Recommendation: Option A**. Clean `PendingApprovalScreen` ("Awaiting administrator approval") with zero device access and zero telemetry.
3. **Tracker Identity Migration**:
   - **Recommendation: Option A**. Introduce immutable hardware UUIDs for all new enrollments, while preserving existing names as immutable aliases in `devices.device_id` to retain the 107,601 historical records.

---

## 15. Final Recommendation

### Status: **`READY FOR PHASE 0 REVIEW (CONDITIONAL)`**

The forensic audit and validation addendum are complete. The critical backend vulnerabilities, SQL flaws, protocol divergences, and test metrics are 100% verified with live source and database evidence. 

**Conditions to proceed to Phase 1**:
1. Owner reviews and approves this Validation Addendum.
2. Owner resolves the three questions in Section 14.
3. SQL corrections in Section 6 are integrated into the Phase 2 migration specification.

---
*Validation Addendum certified by Master Audit Orchestrator. Zero code, schema, or credential modifications were made during this audit.*
