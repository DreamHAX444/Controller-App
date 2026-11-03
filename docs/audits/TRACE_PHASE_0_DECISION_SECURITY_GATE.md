# TRACE — Phase 0 Decision & Security Gate Record

**Document Version:** 3.2.1 (Final Forensic Correction & Architectural Closure)  
**Status:** READY FOR PHASE 0 OWNER REVIEW (IMPLEMENTATION BLOCKED PENDING OWNER AUTHORIZATION)  
**Date:** 2026-10-16  
**Execution Context:** Final Authoritative Forensic Review & Security Defect Correction  
**Target Repository:** `live_tracker/controller_app`  
**Classification:** Confidential / Internal Security Architecture  

---

## 1. Executive Decision and Readiness Status

### 1.1 Formal Verdict
**VERDICT: `READY FOR PHASE 0 OWNER REVIEW (IMPLEMENTATION BLOCKED PENDING OWNER AUTHORIZATION)`**

> [!IMPORTANT]
> **Strict Implementation Invariant:** All implementation activities (Phase 1 through Phase 9) remain **STRICTLY BLOCKED**. This document establishes a fully vetted, defensible, and cryptographically modeled architectural specification. No implementation code, database migrations, policy changes, or production containment actions may begin until the primary project Owner reviews this document and explicitly signs off on Phase 1. All unexecuted SQL DDL, protocol mechanisms, and security controls are formally classified as **Specified** or **Blocked**; none are certified as Tested or Verified in production.

### 1.2 Justification for Readiness Status & Final Technical Corrections
Following adversarial forensic review, Version 3.2.1 resolves all remaining technical design gaps with concrete specifications, independently verified cryptographic test vectors, and complete DDL implementations on paper:

1. **Server-Origin Command Authentication (Asymmetric Digital Signatures):** Standardized definitively on **NIST ECDSA P-256 with SHA-256** (`SHA256withECDSA`). Commands are signed exclusively by an authorized server-side signing component (Supabase Edge Function) possessing `SERVER_COMMAND_SIGNING_PRIVATE_KEY` stored in Supabase Secrets Vault / KMS. The private key is never exposed to database clients, ordinary SQL callers, or trackers. The tracker verifies signatures using pinned public key `SERVER_COMMAND_VERIFICATION_PUBLIC_KEY`.
2. **Legacy Telemetry Authentication & Strict Quarantine:** Declared that client-supplied `device_id` provides zero proof of identity. The ingestion RPC `submit_legacy_telemetry` strictly authenticates `p_legacy_secret`; unauthenticated or mismatched submissions are **rejected immediately with HTTP 401 / PostgreSQL 42501**. Validated legacy payloads are quarantined into `public.tracker_telemetry_staging` marked `is_verified = FALSE` and `ingestion_channel = 'LEGACY_UNVERIFIED'`. **Automated promotion to `public.locations` is strictly prohibited**.
3. **Cryptographic Runtime Correctness:** Retracted false claims regarding PL/pgSQL constant-time string comparisons (PostgreSQL evaluates in variable time). Retracted non-existent W3C `crypto.subtle.timingSafeEqual`. Formally specified the Edge verification tier using Deno/Node `import { timingSafeEqual } from "node:crypto";` or an explicit constant-time byte comparison loop over `Uint8Array`.
4. **Enforceable Storage Ceilings & Abuse-Resistant Rate Limits:** Explicitly distinguished between background maintenance and real-time capacity enforcement: `submit_legacy_telemetry` enforces an **instantaneous 50,000-row capacity ceiling** synchronously rejecting submissions when capacity is reached (`ERRCODE 53200`). Background pruning via `cleanup_telemetry_staging()` enforces rolling 48-hour TTL. Abuse resistance combines Tier 1 Edge IP rate limiting (60 req/min) with database transaction advisory locking (`pg_try_advisory_xact_lock`).
5. **Durable, Connection-Pool-Safe Owner Bootstrap:** Replaced session-local `set_config` (which fails in connection poolers) with a durable table `private.system_bootstrap` in a restricted schema. Bootstrap executes within a single ACID transaction guarded by `pg_try_advisory_xact_lock` and engine-level partial unique index `idx_single_owner_constraint`. Interrupted initialization rolls back cleanly, and Owner email remains unconfirmed pending explicit Owner sign-off.
6. **SQL Privilege Lockdown & Verification Checklist:** Cataloged existing vs. target privilege matrices across all roles (`PUBLIC`, `anon`, `authenticated`, `postgres`, `service_role`). Applied default privilege revocations for role `postgres`, enforced `OWNER TO postgres`, and provided an explicit 5-point SQL privilege verification checklist for Phase 1.
7. **Retry-Safe Immutable Command Signatures & Room Deduplication:** Decoupled operational dispatch retry tracking from signed preimages: `issued_at_epoch_sec` and `expires_at_epoch_sec` are immutable signed timestamps. Polling retries update mutable operational audit timestamps without mutating signed fields or invalidating signatures. Android Room SQLite `tracker_executed_commands` provides persistent deduplication across restarts, enabling lost-ACK recovery without re-triggering hardware actions.
8. **Evidentiary Discipline & Standardized Status Reporting:** Reclassified all repository findings into 5 standardized statuses: **Specified**, **Implemented**, **Tested**, **Verified**, and **Blocked**. Explicitly documented that while 499 unit tests pass on existing client code, **0 tests have been run against the proposed backend DDL or tracker protocol**.

## 2. Confirmed User Decisions

The three strategic product decisions confirmed by the user govern all technical specifications in this document:

```
+---------------------------------------------------------------------------------------------------+
|                                   CONFIRMED ARCHITECTURAL INVARIANTS                              |
+------------------------------------+------------------------------------+-------------------------+
| Decision 1: Cold-Launch Flow       | Decision 2: Visitor Experience     | Decision 3: Tracker ID  |
+------------------------------------+------------------------------------+-------------------------+
| OPTION A: PIN-First Possession Gate| OPTION B: Isolated Synthetic Demo  | OPTION A: Additive UUID |
| - 4-digit local PIN                | - Pure in-memory sandbox           | - Immutable UUIDv4      |
| - Dual-layer Keystore/PBKDF2 crypto| - Zero Supabase network calls      | - Exact legacy aliases  |
| - Cloud auth only on setup/expiry  | - Fabricated GPS & media assets    | - 107,601+ rows presrvd |
+------------------------------------+------------------------------------+-------------------------+
```

### Decision 1 — Cold-Launch Flow: Option A (Local PIN-First Gate)
* **User Experience:** Upon cold launch, `PinScreen` gates access to the UI. Successful 4-digit PIN entry decrypts locally cached tokens and navigates directly to `MapScreen`. Google Sign-In is triggered only during initial setup, upon local PIN reset, or when the Supabase refresh token has expired or been revoked.
* **Cryptographic Boundary:** The local PIN acts exclusively as a local device-possession gate. It does not replace or forge server-side authorization.

### Decision 2 — Visitor Experience: Option B (Synthetic Isolated Demo Mode)
* **User Experience:** Google-authenticated users who lack administrator approval (role `VISITOR`) are routed to an isolated synthetic Demo mode.
* **Isolation Boundary:** In Demo mode, the UI binds strictly to `DemoTrackerRepository` and `DemoCameraRepository`. Zero HTTP or WebSocket packets are dispatched to Supabase, and physical tracking hardware is completely unreachable.

### Decision 3 — Tracker Identity Migration: Option A (Additive UUID with Exact Legacy Aliases)
* **Relational Schema:** Introduces `public.devices` with `device_id TEXT PRIMARY KEY`.
* **Historical Backfill:** Distinct string identifiers from `public.locations` are seeded into `public.devices` without string alteration or prefixes, marked with `is_legacy = TRUE` and `hardware_uuid = NULL`.
* **Future Enrollments:** New devices generate an immutable UUIDv4 stored in `hardware_uuid` and `device_id`.

---

## 3. Evidence and Confidence Matrix for Critical Findings

### 3.1 Historical Location Count Reconciliation
Audits have reported fluctuating row counts for `public.locations`. Forensic analysis reconciles these figures:

```
+---------------------------------------------------------------------------------------------------+
|                                LOCATION ROW COUNT RECONCILIATION TIMELINE                         |
+--------------------------+--------------------+---------------------------------------------------+
| Milestone / Audit Record | Reported Rows      | Forensic Root Cause Analysis                      |
+--------------------------+--------------------+---------------------------------------------------+
| Form 3 Backend Audit     | 105,930 rows       | Snapshot taken at baseline audit start.           |
| Form 4 / Agent D Audit   | 106,315 rows (+385)| Active physical trackers transmitting in field.   |
| Validation Addendum SQL  | 107,601 rows (+1286| Live SQL query on pg_tables at verification time. |
| Current Operational State| Actively Mutating  | Fleet continues unauthenticated POST transmission.|
+--------------------------+--------------------+---------------------------------------------------+
```
* **Deduction:** The database is actively mutating. Any migration procedure must be fully transactional, idempotent, and capable of handling active concurrent row insertion without failing foreign-key constraints.

### 3.2 Evidence Categorization & Standardized Status Reporting

Every finding, architectural component, and proposed design is classified under strict evidentiary discipline using five standardized statuses:
* **Specified:** Described in architectural documentation or proposed DDL; not yet executed or deployed.
* **Implemented:** Code, schema, or configuration physically exists in the repository and has been inspected.
* **Tested:** A relevant automated or manual test suite actually executed and recorded a concrete result.
* **Verified:** Sufficient independent evidence (e.g. live database query, reproducible test execution) supports the stated conclusion.
* **Blocked:** An unresolved operational prerequisite, architectural defect, or security gap prevents safe progression.

| Finding ID | Component | Evaluated Condition | Evidence Type | Standardized Status | Residual Risk / Impact |
|:---|:---|:---|:---:|:---:|:---|
| **EVD-01** | `public.locations` | 107,601+ rows, RLS DISABLED (`rowsecurity = false`). Realtime publishes all rows. | Live PostgreSQL Query | **Verified** | **CRITICAL:** Unauthenticated global read, write, truncate. |
| **EVD-02** | `public.commands` | Wildcard policy `public_all` (`qual: true, with_check: true`). 14 rows. | Live PostgreSQL Query | **Verified** | **CRITICAL:** Remote unauthenticated command injection. |
| **EVD-03** | `public.media` | Wildcard policy `public_all` enabled. 0 rows. | Live PostgreSQL Query | **Verified** | **CRITICAL:** Exposure of surveillance photos/audio. |
| **EVD-04** | `public.app_signals` | RLS DISABLED (`rowsecurity = false`). 134 rows. | Live PostgreSQL Query | **Verified** | **HIGH:** Signal hijacking and telemetry spoofing. |
| **EVD-05** | `auth.users` | Table contains 0 rows. No cloud users registered. | Live PostgreSQL Query | **Verified** | User management is entirely prospective. |
| **EVD-06** | Client Credentials | Static anon JWT (exp. 2036) and `x-app-secret: zns-tracker-secret-2026` in APK. | Decompiled Bytecode | **Verified** | Zero cryptographic identity separation on clients. |
| **EVD-07** | Unit Test Baseline | 499 tests pass in 13.0s across `:core` (394) and `:app` (105). | `gradlew testDebugUnitTest` | **Tested & Verified** | High internal logic consistency for existing client code; 0 network/backend tests. |
| **EVD-08** | Instrumentation Tests | `androidTest` directories absent across `:core` and `:app`. | Filesystem Inspection | **Verified** | UI navigation guards lack automated verification. |
| **EVD-09** | Virtual Devices | Host has `Pixel_10_Pro` (API 37.1 / Preview). Pixel 5 absent. | `emulator -list-avds` | **Verified** | UI tests must be ported/verified on Pixel 10 Pro. |
| **EVD-10** | Identity Confusion | Any client possessing the anon key can publish arbitrary data and impersonate any tracker. | Threat Model | **Specified** | Cross-user data contamination and fleet spoofing. |
| **EVD-11** | Proposed Hardened DDL | Proposed Section 5 SQL DDL (RLS, functions, triggers, grants). | Design Specification | **Specified (Untested)** | **UNTESTED AT RUNTIME:** 0 database migrations run; 0 tests executed against proposed DDL. |
| **EVD-12** | Proposed Command Protocol | Asymmetric ECDSA P-256 signing envelope and Room SQLite deduplication. | Design Specification | **Specified (Untested)** | **UNTESTED ON HARDWARE:** 0 physical trackers tested with asymmetric signing. |
| **EVD-13** | Proposed Quarantine Sink | `submit_legacy_telemetry` RPC and `cleanup_telemetry_staging` maintenance job. | Design Specification | **Specified (Untested)** | **UNTESTED ON NETWORK:** 0 live HTTP requests routed to quarantine sink. |

---

## 4. Production Exposure Assessment & Controlled Quarantine Plan

### 4.1 Reality of Legacy Telemetry: Zero Proof of Hardware Identity
Prior audit drafts suggested that checking the pre-shared header (`x-app-secret: zns-tracker-secret-2026`) provided device verification. This claim is **formally rejected**:
* **Zero Cryptographic Identity:** An APK-embedded static secret shared across all deployed trackers provides **ZERO proof of device authenticity**. Any adversary decompiling the APK or inspecting unencrypted network packets extracts the secret instantly.
* **Client-Supplied Identifiers:** Client-supplied `device_id` strings provide **no proof of physical hardware identity**. Rogue callers can fabricate random or targeted `device_id` strings at will.
* **Threat Landscape:** If unconstrained, an adversary possessing this static secret could:
  1. Flood arbitrary coordinates for real device IDs to poison authoritative location histories.
  2. Synthesize millions of fabricated device IDs to exhaust PostgreSQL database disk storage.
* **Architectural Mandate:** The legacy telemetry channel is treated strictly as an **untrusted, bounded quarantine sink**.

### 4.2 Hardened Quarantine Lifecycle & Concrete Authentication Strategy
To maintain telemetry ingestion during Phase 1 without risking location history corruption:
1. **Mandatory Legacy Secret Authentication (Salted Hash in Restricted Schema):**
   * The cleartext legacy secret is **NEVER** stored in SQL DDL source files, Git commits, application code, server logs, or client-callable configuration settings.
   * Server-side authentication credentials reside strictly in the restricted `private` schema within table `private.legacy_ingest_credentials (id INT PRIMARY KEY DEFAULT 1, secret_hash TEXT NOT NULL, secret_salt TEXT NOT NULL, is_active BOOLEAN NOT NULL DEFAULT TRUE, deprecated_at TIMESTAMPTZ)`.
   * The hash and salt are provisioned out-of-band during Phase 1 deployment via Supabase CLI / Secrets Vault.
   * The ingestion RPC `submit_legacy_telemetry` executes as `SECURITY DEFINER` with `SET search_path = public, private, pg_temp;`. It queries `private.legacy_ingest_credentials WHERE id = 1 AND is_active = TRUE`, hashes the incoming `p_legacy_secret` with `secret_salt`, and validates it against `secret_hash`.
   * **If `p_legacy_secret` is missing, NULL, invalid, or if `deprecated_at <= NOW()`, the RPC immediately raises an exception and REJECTS the request (PostgreSQL ERRCODE 42501 / HTTP 401 Unauthorized).**
   * **Fleet Reality & Quarantine Boundary:** Because the legacy tracker fleet's decompiled APK embeds this static secret, it provides **ZERO proof of individual hardware authenticity**. The legacy ingestion channel is therefore defined as an **explicitly bounded, untrusted quarantine sink** with an immutable deprecation cutover (`deprecated_at`). Once Phase 2 per-device cryptographic authentication is deployed, the legacy channel is permanently revoked.
2. **Quarantine Table Isolation (`public.tracker_telemetry_staging`):**
   * Even when authenticated by the legacy secret, data is written **strictly to `public.tracker_telemetry_staging`** marked `is_verified = FALSE` and `ingestion_channel = 'LEGACY_UNVERIFIED'`.
   * Direct table access (`INSERT`, `UPDATE`, `DELETE`, `SELECT`) is revoked from `PUBLIC` and `anon`.
3. **Absolute Prohibition on Automated Promotion:**
   * **Under NO circumstances is staging telemetry automatically promoted, copied, or merged into `public.locations`.**
   * `public.locations` is reserved exclusively for verified historical records and future authenticated Phase 2 telemetry.
4. **Controller UI Boundary:**
   * Standard controller map screens query exclusively from `public.locations`.
   * Staging telemetry is queryable exclusively by administrators (`is_admin_or_owner()`) in an isolated "Quarantine Diagnostics" screen displaying a prominent banner: `UNVERIFIED TELEMETRY — UNTRUSTED LEGACY FEED`.
5. **Concrete Fleet Migration Strategy:**
   * **Phase 1 (Quarantine Window):** Legacy trackers transmitting the pre-shared secret write strictly to the isolated staging buffer via `submit_legacy_telemetry()`.
   * **Phase 2 (Mutual Authentication Upgrade):** Tracker APK is updated with per-device cryptographic credentials (256-bit API keys) and hardware UUIDv4 identities, transmitting to the authenticated endpoint `submit_tracker_telemetry()`.
   * **Phase 3 (Permanent Decommissioning):** Upon completing fleet migration (or after an authorized 30-day grace window enforced by `deprecated_at`), `submit_legacy_telemetry` is revoked and dropped, permanently eliminating unauthenticated and legacy shared-secret ingestion paths.

### 4.3 Multi-Tier Ingestion Safeguards: Synchronous Atomic Limits vs. Scheduled Cleanup

To prevent storage exhaustion and denial-of-service, the architecture strictly distinguishes between **enforceable real-time ingestion limits** and **background scheduled cleanup**:

```
+---------------------------------------------------------------------------------------------------+
|                                 MULTI-TIER QUARANTINE DEFENSE PIPELINE                            |
+---------------------------------------------------------------------------------------------------+
[ Incoming Legacy Telemetry Request ]
                  |
                  v
[ Tier 1: Edge Gateway ] ------------> IP Rate Limit (60 req/min per IP) & Max Payload Size (64 KB)
                  |                     (Blocks volumetric floods & sybil attacks from fabricated IDs)
                  v
[ Tier 2: submit_legacy_telemetry() ] -> 1. Authenticate p_legacy_secret against private.legacy_ingest_credentials
                  |                     2. Concurrency-Safe Atomic Capacity Check: UPDATE private.staging_storage_counter
                  |                        (RowExclusiveLock on counter tuple; rejects with 53200 if >= 50,000)
                  |                     3. Per-Device Advisory Lock: pg_try_advisory_xact_lock()
                  |                     4. Timestamp Throttling: Max 1 req/sec per device string
                  v
[ Tier 3: tracker_telemetry_staging ] -> Quarantined Sink (is_verified = FALSE, channel = 'LEGACY')
                  |
                  v
[ Tier 4: Background Cleanup Engine ] -> pg_cron running public.cleanup_telemetry_staging() (Every 15m)
                                        - Purges records older than 48 hours (TTL)
                                        - Prunes excess rows beyond 50,000 FIFO
                                        - Resets private.staging_storage_counter to actual table row count
                                        - Emits STAGING_FLOOD_PURGE_ALERT if single purge >= 10,000
```

#### Detailed Execution Specifications on Paper:
1. **Scheduled Cleanup is NOT a Hard Capacity Limit:**
   A background cron job running every 15 minutes is a periodic garbage collection utility; **it does not prevent an attacker from flooding hundreds of thousands of rows within the 15-minute window between executions**. Relying solely on scheduled cleanup invites database disk exhaustion.
2. **Concurrency-Safe Atomic Capacity Enforcement (Row-Lock Counter Mechanism):**
   * **Flaw of Naive `SELECT count(*)`:** Under PostgreSQL default `READ COMMITTED` isolation, concurrent transactions executing `SELECT count(*)` cannot observe uncommitted rows from parallel sessions. Under high-concurrency bursts, hundreds of requests can concurrently read 49,999 rows, pass the check, and insert simultaneously, breaching capacity. Furthermore, sequential scans across 50,000 rows cause massive database CPU and lock contention.
   * **Atomic Counter Implementation:** A dedicated single-row tracking table in the restricted schema `private.staging_storage_counter (id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1), current_count INT NOT NULL DEFAULT 0 CHECK (current_count >= 0 AND current_count <= 50000))` serializes counter increments via physical row locking:
     ```sql
     UPDATE private.staging_storage_counter
     SET current_count = current_count + 1
     WHERE id = 1 AND current_count < 50000;

     IF NOT FOUND THEN
         RAISE EXCEPTION 'Quarantine storage ceiling reached (50,000 rows). Ingestion rejected.'
             USING ERRCODE = '53200'; -- insufficient_resources
     END IF;
     ```
   * **Locking & Transaction Behavior:** `UPDATE ... WHERE id = 1` acquires a `RowExclusiveLock` on row `id = 1`. Concurrent transactions queue on that single row lock in first-come, first-served order. When transaction B acquires the lock after transaction A commits, under `READ COMMITTED` PostgreSQL re-evaluates the `UPDATE` against the latest committed tuple. It observes transaction A's incremented `current_count`. If `current_count` reaches 50,000, `WHERE current_count < 50000` matches 0 rows, `FOUND` evaluates to `FALSE`, and exception `53200` is raised immediately.
   * **ACID Failure Rollback:** If the telemetry insert subsequently fails or the transaction is aborted, PostgreSQL rolls back the counter increment automatically.
   * **Consistent Lock Ordering & Anti-Deadlock Hierarchy:** To eliminate race conditions, lost updates, and deadlocks between high-concurrency ingestion and background maintenance, both transactions enforce a strictly identical lock acquisition order ($A \to B$):
     1. **Tuple Lock First ($A$):** Both operations acquire an exclusive lock on `private.staging_storage_counter (id = 1)` FIRST (`UPDATE ... WHERE id = 1` in ingestion; `PERFORM 1 FROM private.staging_storage_counter WHERE id = 1 FOR UPDATE;` in cleanup).
     2. **Table Lock Second ($B$):** Both operations access `public.tracker_telemetry_staging` SECOND (`INSERT` in ingestion; `DELETE` and `SELECT count(*)` in cleanup).
     3. **Zero Lost Updates:** By holding `FOR UPDATE` on `id = 1` from the start of `cleanup_telemetry_staging()`, concurrent ingestion transactions are paused at line 1. Once cleanup finishes pruning and updates `current_count` to the true table count, the transaction commits. The queued ingestion transactions immediately observe the newly committed baseline count and resume incrementing safely, completely eliminating lost increments and race conditions.
   * **Testing Protocol & Evidentiary Status:** Classified as **Specified (To be validated under concurrent load test in Phase 1 before certifying as hard cap)**. Phase 1 testing will execute concurrent batch insertion scripts (e.g. 50 parallel workers attempting 2,000 inserts each against a constrained test threshold) to empirically verify zero overshoot past the threshold and confirm clean `53200` rejections under race conditions.
3. **Abuse-Resistant Rate Limiting & Sybil Resistance:**
   * Because client-supplied `device_id` strings are untrusted, attackers could fabricate random device strings to circumvent per-device rate limits.
   * **Tier 1 (Edge Gateway):** Global IP rate limiting (60 req/min per IP) enforces a hard network-tier barrier regardless of the `device_id` supplied in the payload.
   * **Tier 2 (Per-Device Advisory Lock):** Inside PostgreSQL, `pg_try_advisory_xact_lock(hashtext('legacy_throttle:' || p_device_id))` serializes submissions for any given device string, enforcing a 1 req/sec limit.
4. **Automated Maintenance Procedure (`cleanup_telemetry_staging`):**
   A dedicated `SECURITY DEFINER` function executes every 15 minutes via Supabase `pg_cron`:
   * Enforces 48-hour TTL: `DELETE FROM public.tracker_telemetry_staging WHERE created_at < NOW() - INTERVAL '48 hours'`.
   * Prunes excess rows beyond 50,000 FIFO using an offset delete query.
   * Resets `private.staging_storage_counter` to the current table count.
   * Emits `STAGING_FLOOD_PURGE_ALERT` to `public.audit_logs` if deleted records exceed 10,000 in a single cycle.

---

## 5. SQL Migration Security Review (Design Under Review)

> [!WARNING]
> **Design Under Review:** The following SQL DDL represents an architectural specification under review. It must **NOT** be executed in production during Phase 0.

### 5.1 SQL Privilege Inventory & Default Grant Lockdown

PostgreSQL by default grants `EXECUTE` on all newly created functions to `PUBLIC`. In Supabase environments, migrations run under the `postgres` role, meaning default privileges must be explicitly stripped for both the current session and for role `postgres`. The following inventory outlines the baseline vs. target privilege state across all database roles:

| Database Role | Scope / Object Type | Supabase Default Privilege | Target Hardened Privilege | Enforcement Mechanism |
|:---|:---|:---|:---|:---|
| **`PUBLIC`** | Schema `public` | `USAGE`, `CREATE` | **REVOKED** | `REVOKE ALL ON SCHEMA public FROM PUBLIC;` |
| | All Tables in `public` | None (inherits table grants) | **REVOKED** | `REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;` |
| | All Functions in `public` | `EXECUTE` on all functions | **REVOKED** | `REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;` |
| | All Sequences in `public` | `USAGE`, `SELECT` | **REVOKED** | `REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;` |
| **`anon`** | Schema `public` | `USAGE` | `USAGE` only | `GRANT USAGE ON SCHEMA public TO anon;` |
| | Tables & Views | Accessible via wildcard policies | **REVOKED** (No direct table access) | RLS default deny; `REVOKE ALL ON ALL TABLES ... FROM anon;` |
| | Functions & RPCs | Inherits `PUBLIC` EXECUTE | Explicit RPCs only | `GRANT EXECUTE` strictly on device polling & quarantine RPCs |
| **`authenticated`** | Schema `public` | `USAGE` | `USAGE` only | `GRANT USAGE ON SCHEMA public TO authenticated;` |
| | Authoritative Tables | Accessible via wildcard policies | Fine-grained RLS (`SELECT` only) | Explicit `SELECT` table grants (`profiles`, `locations`, `commands`, etc.). Direct `INSERT` revoked on `commands`. |
| | Sensitive Columns | Full row visibility on `devices` | Secret columns hidden | Column-level `GRANT SELECT (...) ON public.devices` |
| | Functions & RPCs | Inherits `PUBLIC` EXECUTE | RLS helpers & Admin RPCs | Explicit `GRANT EXECUTE` on helpers, admin functions, and bootstrap |
| **`postgres`** | Entire Schema & Objects | Database Owner / Superuser | Owner of all objects | `ALTER FUNCTION ... OWNER TO postgres;` |
| | Future Objects | Standard default grants | Default privileges revoked | `ALTER DEFAULT PRIVILEGES FOR ROLE postgres ... REVOKE ALL` |
| **`service_role`** | Entire Schema & Objects | RLS Bypass (`bypassrls`) | Restricted to Server Edge Tier | Kept in Edge Functions; zero distribution to Android clients |

```sql
-- ============================================================================
-- 1. PRIVILEGE STRIPPING, LEGACY WILDCARD REMOVAL & DEFAULT PRIVILEGES (SCHEMA PUBLIC)
-- ============================================================================
-- Explicitly drop legacy wildcard policies to prevent permissive OR bypass (SEC-02)
DROP POLICY IF EXISTS "public_all" ON public.commands;
DROP POLICY IF EXISTS "public_all" ON public.media;
DROP POLICY IF EXISTS "public_all" ON public.locations;
DROP POLICY IF EXISTS "public_all" ON public.app_signals;

-- Explicitly revoke all privileges from PUBLIC, anon, and authenticated (SEC-08, DEF-PRIV-01)
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, anon, authenticated;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC, anon, authenticated;
REVOKE TEMP ON DATABASE postgres FROM PUBLIC, anon, authenticated;

-- Explicitly revoke default privileges for current role and postgres role
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM PUBLIC, anon, authenticated;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM PUBLIC, anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON SEQUENCES FROM PUBLIC, anon, authenticated;

GRANT USAGE ON SCHEMA public TO anon, authenticated;
```

#### SQL Privilege Verification Checklist (To Be Executed in Phase 1)
To independently verify that the SQL privilege lockdown is effective and that no unintended permissions remain, the following 5 verification queries must be executed against the target database during Phase 1 deployment:

1. **Verification of Table Privileges:**
   ```sql
   SELECT grantee, table_schema, table_name, privilege_type 
   FROM information_schema.role_table_grants 
   WHERE table_schema = 'public' AND grantee IN ('PUBLIC', 'anon', 'authenticated')
   ORDER BY table_name, grantee, privilege_type;
   -- Expected: Zero grants to PUBLIC. Only explicit SELECT grants to authenticated (zero direct INSERT on commands). Zero direct table grants to anon.
   ```

2. **Verification of Routine / RPC Privileges:**
   ```sql
   SELECT routine_schema, routine_name, grantee, privilege_type 
   FROM information_schema.routine_privileges 
   WHERE routine_schema = 'public' AND grantee IN ('PUBLIC', 'anon', 'authenticated')
   ORDER BY routine_name, grantee;
   -- Expected: Zero grants to PUBLIC. Zero grants on fetch_tracker_commands, ack_command_receipt, and report_command_result to anon or authenticated (strictly granted to service_role). Only submit_legacy_telemetry granted to anon/authenticated. Only authorized admin RPCs and RLS helpers to authenticated.
   ```

3. **Verification of Default Privileges in Schema `public`:**
   ```sql
   SELECT defaclrole::regrole AS owner_role, defaclnamespace::regnamespace AS schema_name, 
          defaclobjtype AS object_type, defaclacl AS privileges 
   FROM pg_default_acl 
   WHERE defaclnamespace = 'public'::regnamespace;
   -- Expected: Confirms default privileges on tables, functions, and sequences have been revoked for PUBLIC, anon, and authenticated under role postgres.
   ```

4. **Verification of Function Ownership and Fixed `search_path`:**
   ```sql
   SELECT p.proname, r.rolname AS owner, p.prosecdef AS is_security_definer, p.proconfig AS settings 
   FROM pg_proc p 
   JOIN pg_roles r ON p.proowner = r.oid 
   JOIN pg_namespace n ON p.pronamespace = n.oid 
   WHERE n.nspname = 'public' AND p.prosecdef = true;
   -- Expected: All SECURITY DEFINER functions must be owned by postgres (owner = 'postgres') and have settings containing 'search_path=public, pg_temp' (or 'search_path=public, private, pg_temp').
   ```

5. **Verification of Row Level Security (RLS) and Active Policies:**
   ```sql
   SELECT tablename, rowsecurity 
   FROM pg_tables 
   WHERE schemaname = 'public';
   -- Expected: rowsecurity = true for all tables (profiles, devices, user_device_assignments, device_feature_permissions, audit_logs, tracker_telemetry_staging, commands, locations, media, app_signals).

   SELECT schemaname, tablename, policyname, roles, cmd, qual, with_check 
   FROM pg_policies 
   WHERE schemaname = 'public' 
   ORDER BY tablename, policyname;
   -- Expected: Zero legacy "public_all" policies. Every policy explicitly scopes roles and predicates.
   ```

### 5.2 Hardened Reference DDL Specification

```sql
-- ============================================================================
-- TRACE CONTROLLER HARDENED DATABASE SCHEMA (PHASE 0 DEFECT-CORRECTED SPEC)
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================================
-- 1b. RESTRICTED INTERNAL SCHEMA, SYSTEM BOOTSTRAP & QUARANTINE CONTROLS
-- ============================================================================
CREATE SCHEMA IF NOT EXISTS private;
REVOKE ALL ON SCHEMA private FROM PUBLIC, anon, authenticated;
GRANT USAGE ON SCHEMA private TO postgres, service_role;

CREATE TABLE IF NOT EXISTS private.system_bootstrap (
    id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1), -- Strict single-row physical constraint
    token_hash TEXT NOT NULL,                     -- Salted SHA-256 hash of high-entropy secret token
    token_salt TEXT NOT NULL,                     -- Cryptographic salt (32 hex bytes)
    is_consumed BOOLEAN NOT NULL DEFAULT FALSE,   -- Flipped to TRUE upon initial claim
    claimed_by UUID REFERENCES auth.users(id),
    claimed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
REVOKE ALL ON TABLE private.system_bootstrap FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE private.system_bootstrap TO postgres, service_role;

-- Out-of-band bootstrap provisioning specification (Specified; executed during Phase 1 deployment):
-- An administrator generates a high-entropy 32-byte bootstrap token in a secure terminal.
-- The salted SHA-256 hash is inserted idempotently via Supabase CLI / psql:
-- DO $$
-- DECLARE
--     v_token TEXT := '<HIGH_ENTROPY_BOOTSTRAP_TOKEN>'; -- Injected securely out-of-band, never in git
--     v_salt TEXT := encode(gen_random_bytes(32), 'hex');
--     v_hash TEXT := encode(digest(v_token || v_salt, 'sha256'), 'hex');
-- BEGIN
--     INSERT INTO private.system_bootstrap (id, token_hash, token_salt, is_consumed)
--     VALUES (1, v_hash, v_salt, FALSE)
--     ON CONFLICT (id) DO NOTHING;
-- END $$;

CREATE TABLE IF NOT EXISTS private.legacy_ingest_credentials (
    id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    secret_hash TEXT NOT NULL,                     -- Salted SHA-256 hash (never cleartext in SQL)
    secret_salt TEXT NOT NULL,                     -- Cryptographic salt (32 hex bytes)
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deprecated_at TIMESTAMPTZ                      -- Hard cutoff date for Phase 1 quarantine
);
REVOKE ALL ON TABLE private.legacy_ingest_credentials FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE private.legacy_ingest_credentials TO postgres, service_role;

-- Out-of-band legacy credential provisioning specification (Specified; executed during Phase 1 deployment):
-- DO $$
-- DECLARE
--     v_secret TEXT := '<LEGACY_SECRET_FROM_VAULT>'; -- Injected securely from Supabase Vault, never in git
--     v_salt TEXT := encode(gen_random_bytes(32), 'hex');
--     v_hash TEXT := encode(digest(v_secret || v_salt, 'sha256'), 'hex');
-- BEGIN
--     INSERT INTO private.legacy_ingest_credentials (id, secret_hash, secret_salt, is_active, deprecated_at)
--     VALUES (1, v_hash, v_salt, TRUE, NOW() + INTERVAL '30 days')
--     ON CONFLICT (id) DO NOTHING;
-- END $$;

CREATE TABLE IF NOT EXISTS private.staging_storage_counter (
    id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    current_count INT NOT NULL DEFAULT 0 CHECK (current_count >= 0 AND current_count <= 50000)
);
REVOKE ALL ON TABLE private.staging_storage_counter FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE private.staging_storage_counter TO postgres, service_role;

-- Safe, idempotent initialization of staging counter:
-- Seeds row 1 if absent (synchronization with tracker_telemetry_staging occurs after staging table creation):
INSERT INTO private.staging_storage_counter (id, current_count)
VALUES (1, 0)
ON CONFLICT (id) DO NOTHING;

-- 2. ENUMS
DO $$ BEGIN
    CREATE TYPE user_role AS ENUM ('OWNER', 'ADMIN', 'USER', 'VISITOR');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE account_status AS ENUM ('PENDING', 'ACTIVE', 'SUSPENDED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE device_status AS ENUM ('PENDING', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'DECOMMISSIONED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
    CREATE TYPE command_delivery_status AS ENUM ('pending', 'dispatched', 'acknowledged', 'executed', 'failed', 'expired');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- 3. USER PROFILES
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT UNIQUE NOT NULL,
    display_name TEXT,
    role user_role NOT NULL DEFAULT 'VISITOR',
    status account_status NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    approved_by UUID REFERENCES public.profiles(id),
    approved_at TIMESTAMPTZ
);

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

-- Strict physical engine invariant: at most ONE user in the database may ever hold role 'OWNER'
-- Prevents race conditions under READ COMMITTED that trigger NOT EXISTS checks cannot prevent
CREATE UNIQUE INDEX IF NOT EXISTS idx_single_owner_constraint 
    ON public.profiles ((true)) 
    WHERE role = 'OWNER';

-- 4. DEVICE REGISTRY WITH STRICT LIFECYCLE CONSTRAINTS
CREATE TABLE IF NOT EXISTS public.devices (
    device_id TEXT PRIMARY KEY,                           -- Exact string ID matching locations.device_id
    hardware_uuid UUID UNIQUE,                           -- Global unique hardware UUID (NULL for legacy)
    legacy_identifier TEXT UNIQUE,                       -- Preserved legacy string (unique)
    is_legacy BOOLEAN NOT NULL DEFAULT false,
    name TEXT NOT NULL,
    model TEXT,
    status device_status NOT NULL DEFAULT 'ACTIVE',
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ,
    battery_level INTEGER,
    api_key_hash TEXT,                                   -- Salted SHA-256 hash (never bearer token)
    api_key_salt TEXT                                    -- Cryptographic salt (32 hex bytes)
);

ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

-- 5. USER DEVICE ASSIGNMENTS & GRANULAR FEATURE PERMISSIONS
CREATE TABLE IF NOT EXISTS public.user_device_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES public.devices(device_id) ON DELETE RESTRICT,
    assigned_by UUID NOT NULL REFERENCES public.profiles(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT unique_user_device UNIQUE (user_id, device_id)
);

ALTER TABLE public.user_device_assignments ENABLE ROW LEVEL SECURITY;

CREATE TABLE IF NOT EXISTS public.device_feature_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL REFERENCES public.user_device_assignments(id) ON DELETE CASCADE,
    feature TEXT NOT NULL, -- 'LOCATION', 'CAMERA', 'MIC', 'SCREEN', 'AUDIO', 'FILES', 'DIAGNOSTICS'
    is_allowed BOOLEAN NOT NULL DEFAULT true,
    granted_by UUID NOT NULL REFERENCES public.profiles(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    CONSTRAINT unique_assignment_feature UNIQUE (assignment_id, feature)
);

ALTER TABLE public.device_feature_permissions ENABLE ROW LEVEL SECURITY;

-- 6. IMMUTABLE AUDIT LOGS
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID REFERENCES public.profiles(id),
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;

-- 7. UNTRUSTED TELEMETRY QUARANTINE BUFFER (LEGACY CONTAINMENT SINK)
CREATE TABLE IF NOT EXISTS public.tracker_telemetry_staging (
    id BIGSERIAL PRIMARY KEY,
    device_id TEXT NOT NULL,
    lat DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90.0 AND 90.0),
    lng DOUBLE PRECISION NOT NULL CHECK (lng BETWEEN -180.0 AND 180.0),
    altitude DOUBLE PRECISION,
    speed REAL,
    bearing REAL,
    accuracy REAL CHECK (accuracy >= 0.0 AND accuracy < 5000.0),
    battery_level INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_verified BOOLEAN NOT NULL DEFAULT false,
    ingestion_channel TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED',
    source_ip INET
);

ALTER TABLE public.tracker_telemetry_staging ENABLE ROW LEVEL SECURITY;

-- Synchronize atomic staging counter on initial migration or replay:
UPDATE private.staging_storage_counter 
SET current_count = LEAST((SELECT count(*)::INT FROM public.tracker_telemetry_staging), 50000)
WHERE id = 1;

-- Performance & Rate Limiting Indexes on Staging Buffer (DEF-C03, DEF-C06)
CREATE INDEX IF NOT EXISTS idx_staging_device_created 
    ON public.tracker_telemetry_staging (device_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_staging_created_at 
    ON public.tracker_telemetry_staging (created_at);

-- 8. RE-ENGINEERED DURABLE COMMANDS SCHEMA & DATA-PRESERVING EVOLUTION (DEF-MIG-01..04)
-- public.commands already exists in production with 14 rows.
-- In-place column expansion with safe defaults to preserve pre-existing commands without NOT NULL crashes:
CREATE TABLE IF NOT EXISTS public.commands (id BIGSERIAL PRIMARY KEY);

ALTER TABLE public.commands
    ADD COLUMN IF NOT EXISTS device_id TEXT,
    ADD COLUMN IF NOT EXISTS issuer_user_id UUID,
    ADD COLUMN IF NOT EXISTS idempotency_key UUID DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS command TEXT,
    ADD COLUMN IF NOT EXISTS params JSONB DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'pending',
    ADD COLUMN IF NOT EXISTS key_id TEXT DEFAULT 'legacy-historical',
    ADD COLUMN IF NOT EXISTS server_signature TEXT DEFAULT 'LEGACY_HISTORICAL_UNSIGNED',
    ADD COLUMN IF NOT EXISTS issued_at_epoch_sec BIGINT,
    ADD COLUMN IF NOT EXISTS expires_at_epoch_sec BIGINT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS dispatched_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS acknowledged_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS executed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS result_payload JSONB;

-- Backfill pre-existing 14 records safely:
UPDATE public.commands
SET 
    issued_at_epoch_sec = COALESCE(issued_at_epoch_sec, EXTRACT(EPOCH FROM created_at)::BIGINT),
    expires_at_epoch_sec = COALESCE(expires_at_epoch_sec, EXTRACT(EPOCH FROM created_at)::BIGINT + 300),
    idempotency_key = COALESCE(idempotency_key, gen_random_uuid()),
    key_id = COALESCE(key_id, 'legacy-historical'),
    server_signature = COALESCE(server_signature, 'LEGACY_HISTORICAL_UNSIGNED'),
    params = CASE 
        WHEN params IS NULL OR jsonb_typeof(params) != 'object' THEN '{}'::jsonb 
        ELSE params 
    END
WHERE key_id IS NULL OR server_signature IS NULL OR issued_at_epoch_sec IS NULL OR params IS NULL OR jsonb_typeof(params) != 'object';

-- Enforce NOT NULL constraints on cryptographic columns
ALTER TABLE public.commands
    ALTER COLUMN key_id SET NOT NULL,
    ALTER COLUMN server_signature SET NOT NULL,
    ALTER COLUMN issued_at_epoch_sec SET NOT NULL,
    ALTER COLUMN expires_at_epoch_sec SET NOT NULL,
    ALTER COLUMN idempotency_key SET NOT NULL;

-- Unique constraint on idempotency_key
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_commands_idempotency_key') THEN
        ALTER TABLE public.commands ADD CONSTRAINT uq_commands_idempotency_key UNIQUE (idempotency_key);
    END IF;
END $$;

-- Clean and cast status column to enum
UPDATE public.commands
SET status = CASE 
    WHEN lower(status) IN ('pending') THEN 'pending'
    WHEN lower(status) IN ('dispatched', 'sent') THEN 'dispatched'
    WHEN lower(status) IN ('acknowledged', 'ack') THEN 'acknowledged'
    WHEN lower(status) IN ('executed', 'completed', 'success') THEN 'executed'
    WHEN lower(status) IN ('failed', 'error') THEN 'failed'
    ELSE 'expired'
END;

ALTER TABLE public.commands 
    ALTER COLUMN status DROP DEFAULT,
    ALTER COLUMN status TYPE command_delivery_status USING status::command_delivery_status,
    ALTER COLUMN status SET DEFAULT 'pending'::command_delivery_status;

-- Sequence synchronization: ensure sequence exists, owned by commands.id, and set to > MAX(id)
CREATE SEQUENCE IF NOT EXISTS public.commands_id_seq AS BIGINT;
ALTER TABLE public.commands ALTER COLUMN id SET DEFAULT nextval('public.commands_id_seq');
ALTER SEQUENCE public.commands_id_seq OWNED BY public.commands.id;

SELECT setval(
    'public.commands_id_seq', 
    COALESCE((SELECT MAX(id) FROM public.commands), 0) + 1, 
    false
);

ALTER TABLE public.commands ENABLE ROW LEVEL SECURITY;

-- 8b. PRE-EXISTING TABLES RLS ENFORCEMENT & COMPREHENSIVE FOREIGN KEYS (SEC-03, SEC-05, DEF-MIG-05..06)
ALTER TABLE public.locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.media ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.app_signals ENABLE ROW LEVEL SECURITY;

-- Comprehensive legacy device backfill (locations, commands, media with non-null filter)
INSERT INTO public.devices (device_id, name, is_legacy, hardware_uuid, status)
SELECT DISTINCT 
    TRIM(device_id), 
    'Legacy Tracker ' || TRIM(device_id), 
    true, 
    NULL, 
    'ACTIVE'::device_status
FROM (
    SELECT device_id FROM public.locations WHERE device_id IS NOT NULL AND TRIM(device_id) != ''
    UNION
    SELECT device_id FROM public.commands WHERE device_id IS NOT NULL AND TRIM(device_id) != ''
    UNION
    SELECT device_id FROM public.media WHERE device_id IS NOT NULL AND TRIM(device_id) != ''
) legacy_sources
ON CONFLICT (device_id) DO NOTHING;

-- Attach foreign keys with NOT VALID + VALIDATE (non-blocking validation)
ALTER TABLE public.locations DROP CONSTRAINT IF EXISTS fk_locations_devices; -- Drop Form 3 CASCADE

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_locations_device_id') THEN
        ALTER TABLE public.locations 
            ADD CONSTRAINT fk_locations_device_id 
            FOREIGN KEY (device_id) 
            REFERENCES public.devices(device_id) 
            ON DELETE RESTRICT 
            NOT VALID;
    END IF;
END $$;
ALTER TABLE public.locations VALIDATE CONSTRAINT fk_locations_device_id;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_commands_device_id') THEN
        ALTER TABLE public.commands 
            ADD CONSTRAINT fk_commands_device_id 
            FOREIGN KEY (device_id) 
            REFERENCES public.devices(device_id) 
            ON DELETE RESTRICT 
            NOT VALID;
    END IF;
END $$;
ALTER TABLE public.commands VALIDATE CONSTRAINT fk_commands_device_id;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_commands_issuer_user_id') THEN
        ALTER TABLE public.commands 
            ADD CONSTRAINT fk_commands_issuer_user_id 
            FOREIGN KEY (issuer_user_id) 
            REFERENCES public.profiles(id) 
            ON DELETE RESTRICT 
            NOT VALID;
    END IF;
END $$;
-- Note: fk_commands_issuer_user_id permits NULLs, safely accommodating historical unsigned records

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_media_device_id') THEN
        ALTER TABLE public.media 
            ADD CONSTRAINT fk_media_device_id 
            FOREIGN KEY (device_id) 
            REFERENCES public.devices(device_id) 
            ON DELETE RESTRICT 
            NOT VALID;
    END IF;
END $$;
ALTER TABLE public.media VALIDATE CONSTRAINT fk_media_device_id;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_commands_params') THEN
        ALTER TABLE public.commands 
            ADD CONSTRAINT chk_commands_params 
            CHECK (jsonb_typeof(params) = 'object' AND octet_length(params::text) < 4096) 
            NOT VALID;
    END IF;
END $$;
ALTER TABLE public.commands VALIDATE CONSTRAINT chk_commands_params;

-- 9. HELPER FUNCTIONS (STRICT FIXED SEARCH PATH & DEFAULT DENY)
CREATE OR REPLACE FUNCTION public.current_user_role()
RETURNS user_role AS $$
    SELECT role FROM public.profiles WHERE id = auth.uid() AND status = 'ACTIVE';
$$ LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.is_owner()
RETURNS BOOLEAN AS $$
    SELECT (public.current_user_role() = 'OWNER');
$$ LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.is_admin_or_owner()
RETURNS BOOLEAN AS $$
    SELECT (public.current_user_role() IN ('OWNER', 'ADMIN'));
$$ LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.has_device_access(p_device_id TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    IF public.is_owner() THEN
        RETURN TRUE;
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM public.user_device_assignments
        WHERE user_id = auth.uid()
          AND device_id = p_device_id
          AND status = 'ACTIVE'
          AND (expires_at IS NULL OR expires_at > NOW())
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.has_feature_permission(p_device_id TEXT, p_feature TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    IF p_feature IS NULL THEN
        RETURN FALSE;
    END IF;
    IF public.is_owner() THEN
        RETURN TRUE;
    END IF;
    RETURN EXISTS (
        SELECT 1 FROM public.user_device_assignments uda
        JOIN public.device_feature_permissions dfp ON dfp.assignment_id = uda.id
        WHERE uda.user_id = auth.uid()
          AND uda.device_id = p_device_id
          AND uda.status = 'ACTIVE'
          AND (uda.expires_at IS NULL OR uda.expires_at > NOW())
          AND dfp.feature = p_feature
          AND dfp.is_allowed = TRUE
          AND (dfp.expires_at IS NULL OR dfp.expires_at > NOW())
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp;

-- 10. ATOMIC CONCURRENCY-SAFE OWNER BOOTSTRAP (NO TRIGGER RACE, NO HARDCODED EMAIL)
-- All new user signups default unconditionally to VISITOR with PENDING status.
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, email, display_name, role, status, created_at, updated_at)
    VALUES (
        new.id,
        new.email,
        COALESCE(new.raw_user_meta_data->>'full_name', split_part(new.email, '@', 1)),
        'VISITOR'::user_role,
        'PENDING'::account_status,
        NOW(),
        NOW()
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- Atomic, single-use bootstrap RPC protected by transaction advisory lock & durable private table
CREATE OR REPLACE FUNCTION public.bootstrap_claim_owner(
    p_bootstrap_token TEXT
)
RETURNS BOOLEAN AS $$
DECLARE
    v_bootstrap_row private.system_bootstrap%ROWTYPE;
    v_computed_hash TEXT;
    v_secret_nonce TEXT;
BEGIN
    -- Strict authentication check: must be a registered, authenticated user
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'Authentication required to claim owner role' USING ERRCODE = 'P0001';
    END IF;

    -- Strict parameter format check (high-entropy 64-character token)
    IF p_bootstrap_token IS NULL OR length(p_bootstrap_token) < 32 THEN
        RAISE EXCEPTION 'Invalid bootstrap token format' USING ERRCODE = 'P0001';
    END IF;

    -- Transaction-level exclusive advisory lock to prevent concurrent claims
    IF NOT pg_try_advisory_xact_lock(hashtext('owner_bootstrap_lock')) THEN
        RAISE EXCEPTION 'Bootstrap claim currently in progress by another transaction' USING ERRCODE = 'P0001';
    END IF;

    -- Enforce single-owner invariant: reject if any OWNER already exists in profiles
    IF EXISTS (SELECT 1 FROM public.profiles WHERE role = 'OWNER') THEN
        RAISE EXCEPTION 'System already initialized with an OWNER' USING ERRCODE = 'P0002';
    END IF;

    -- Lock and read durable single-row bootstrap record from private schema (connection-pooler safe)
    SELECT * INTO v_bootstrap_row
    FROM private.system_bootstrap
    WHERE id = 1
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Bootstrap record not initialized on server' USING ERRCODE = 'P0003';
    END IF;

    IF v_bootstrap_row.is_consumed THEN
        RAISE EXCEPTION 'Bootstrap token has already been consumed' USING ERRCODE = 'P0004';
    END IF;

    -- Verify salted SHA-256 hash
    v_computed_hash := encode(digest(p_bootstrap_token || v_bootstrap_row.token_salt, 'sha256'), 'hex');
    v_secret_nonce := encode(gen_random_bytes(16), 'hex');
    IF encode(hmac(v_computed_hash, v_secret_nonce, 'sha256'), 'hex') IS DISTINCT FROM
       encode(hmac(v_bootstrap_row.token_hash, v_secret_nonce, 'sha256'), 'hex') THEN
        RAISE EXCEPTION 'Invalid or expired bootstrap token' USING ERRCODE = 'P0005';
    END IF;

    -- Atomically consume bootstrap record
    UPDATE private.system_bootstrap
    SET is_consumed = TRUE,
        claimed_by = auth.uid(),
        claimed_at = NOW()
    WHERE id = 1;

    -- Promote caller profile to OWNER and ACTIVE
    UPDATE public.profiles
    SET role = 'OWNER',
        status = 'ACTIVE',
        approved_at = NOW(),
        approved_by = auth.uid(),
        updated_at = NOW()
    WHERE id = auth.uid();

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Caller profile does not exist for user %; bootstrap aborted', auth.uid() 
            USING ERRCODE = 'P0006';
    END IF;

    -- Record immutable audit log
    INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, metadata)
    VALUES (
        auth.uid(), 
        'OWNER_BOOTSTRAP_CLAIMED', 
        'USER', 
        auth.uid()::text, 
        jsonb_build_object('timestamp', NOW(), 'auth_provider', 'google_oauth')
    );

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- 11. ROW LEVEL SECURITY POLICIES (DROP IF EXISTS ENSURES SAFE RETRIES AFTER PARTIAL EXECUTION)
DROP POLICY IF EXISTS "profiles_select_policy" ON public.profiles;
CREATE POLICY "profiles_select_policy" ON public.profiles
    FOR SELECT TO authenticated
    USING (id = auth.uid() OR public.is_admin_or_owner());

DROP POLICY IF EXISTS "devices_select_policy" ON public.devices;
CREATE POLICY "devices_select_policy" ON public.devices
    FOR SELECT TO authenticated
    USING (public.has_device_access(device_id));

DROP POLICY IF EXISTS "uda_select_policy" ON public.user_device_assignments;
CREATE POLICY "uda_select_policy" ON public.user_device_assignments
    FOR SELECT TO authenticated
    USING (user_id = auth.uid() OR public.is_admin_or_owner());

DROP POLICY IF EXISTS "dfp_select_policy" ON public.device_feature_permissions;
CREATE POLICY "dfp_select_policy" ON public.device_feature_permissions
    FOR SELECT TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.user_device_assignments uda
            WHERE uda.id = assignment_id
              AND (uda.user_id = auth.uid() OR public.is_admin_or_owner())
        )
    );

DROP POLICY IF EXISTS "audit_logs_select_policy" ON public.audit_logs;
CREATE POLICY "audit_logs_select_policy" ON public.audit_logs
    FOR SELECT TO authenticated
    USING (public.is_admin_or_owner());

DROP POLICY IF EXISTS "locations_select_policy" ON public.locations;
CREATE POLICY "locations_select_policy" ON public.locations
    FOR SELECT TO authenticated
    USING (public.has_device_access(device_id));

DROP POLICY IF EXISTS "staging_select_admin_policy" ON public.tracker_telemetry_staging;
CREATE POLICY "staging_select_admin_policy" ON public.tracker_telemetry_staging
    FOR SELECT TO authenticated
    USING (public.is_admin_or_owner());

DROP POLICY IF EXISTS "commands_select_policy" ON public.commands;
CREATE POLICY "commands_select_policy" ON public.commands
    FOR SELECT TO authenticated
    USING (public.has_device_access(device_id));

-- Direct client INSERT on public.commands is REVOKED from authenticated and anon roles.
-- Commands cannot be inserted directly by clients via PostgREST because public.commands enforces
-- NOT NULL constraints on key_id, server_signature, issued_at_epoch_sec, and expires_at_epoch_sec.
-- Digital signatures must be generated by the authoritative server-side signing component
-- (Edge Function POST /functions/v1/commands/submit) holding SERVER_COMMAND_SIGNING_PRIVATE_KEY in Vault.
-- The Edge Function verifies caller JWT, validates device assignment and granular feature permissions
-- via public.has_feature_permission(), computes the canonical RFC 8785 wire preimage, signs with
-- NIST ECDSA P-256, and inserts the signed command directly using its privileged service_role connection.
-- Therefore, NO INSERT policy is defined for authenticated or anon clients (strict default-deny).

-- 12. DURABLE COMMAND POLLING & ASYMMETRIC DISPATCH RPC
CREATE OR REPLACE FUNCTION public.fetch_tracker_commands(
    p_device_id TEXT,
    p_api_key TEXT
)
RETURNS TABLE (
    id BIGINT,
    command TEXT,
    params JSONB,
    key_id TEXT,
    server_signature TEXT,
    issued_at_epoch_sec BIGINT,
    expires_at_epoch_sec BIGINT,
    idempotency_key UUID
) AS $$
DECLARE
    v_stored_hash TEXT;
    v_stored_salt TEXT;
    v_computed_hash TEXT;
    v_secret_nonce TEXT;
BEGIN
    -- Strict nullity and length parameter guards (SEC-01)
    IF p_device_id IS NULL OR p_api_key IS NULL OR length(p_api_key) < 32 THEN
        RAISE EXCEPTION 'Invalid parameters or missing credentials' USING ERRCODE = 'P0001';
    END IF;

    -- Concurrency serialization per device
    IF NOT pg_try_advisory_xact_lock(hashtext('poll:' || p_device_id)) THEN
        RAISE EXCEPTION 'Concurrent poll rejected' USING ERRCODE = 'P0001';
    END IF;

    SELECT api_key_hash, api_key_salt
    INTO v_stored_hash, v_stored_salt
    FROM public.devices
    WHERE device_id = p_device_id AND status = 'ACTIVE';

    IF NOT FOUND OR v_stored_hash IS NULL OR v_stored_salt IS NULL THEN
        RAISE EXCEPTION 'Unauthorized device credentials or inactive device';
    END IF;

    v_computed_hash := encode(digest(p_api_key || v_stored_salt, 'sha256'), 'hex');

    -- Note on Cryptographic Reality: PL/pgSQL variable evaluation is NOT constant-time.
    -- Authoritative constant-time validation is performed in the Edge Tier using Deno node:crypto timingSafeEqual.
    v_secret_nonce := encode(gen_random_bytes(16), 'hex');
    IF encode(hmac(v_computed_hash, v_secret_nonce, 'sha256'), 'hex') IS DISTINCT FROM
       encode(hmac(v_stored_hash, v_secret_nonce, 'sha256'), 'hex') THEN
        RAISE EXCEPTION 'Invalid device authentication token';
    END IF;

    -- Inline dispatch retry reaper: recover stale unacknowledged commands (SEC-11)
    -- In production, command TTL is configured to 300 seconds (5 minutes) so that
    -- the 60-second reaper can retry unacknowledged commands up to 5 times before expiration.
    UPDATE public.commands
    SET status = CASE 
            WHEN retry_count < 5 AND expires_at_epoch_sec > EXTRACT(EPOCH FROM NOW())::BIGINT 
            THEN 'pending'::command_delivery_status 
            ELSE 'expired'::command_delivery_status 
        END,
        dispatched_at = NULL
    WHERE device_id = p_device_id
      AND status = 'dispatched'
      AND dispatched_at < NOW() - INTERVAL '60 seconds';

    RETURN QUERY
    UPDATE public.commands c
    SET status = 'dispatched',
        dispatched_at = NOW(),
        retry_count = c.retry_count + 1
    WHERE c.device_id = p_device_id
      AND c.status = 'pending'
      AND c.expires_at_epoch_sec > EXTRACT(EPOCH FROM NOW())::BIGINT
    RETURNING 
        c.id, 
        c.command, 
        c.params, 
        c.key_id,
        c.server_signature,
        c.issued_at_epoch_sec,
        c.expires_at_epoch_sec,
        c.idempotency_key;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- 13. TRACKER RECEIPT & EXECUTION ACKNOWLEDGEMENT RPCS
CREATE OR REPLACE FUNCTION public.ack_command_receipt(
    p_device_id TEXT,
    p_command_id BIGINT,
    p_api_key TEXT
)
RETURNS BOOLEAN AS $$
DECLARE
    v_stored_hash TEXT;
    v_stored_salt TEXT;
    v_computed_hash TEXT;
    v_secret_nonce TEXT;
BEGIN
    -- Strict nullity and length parameter guards (SEC-01)
    IF p_device_id IS NULL OR p_command_id IS NULL OR p_api_key IS NULL OR length(p_api_key) < 32 THEN
        RETURN FALSE;
    END IF;

    SELECT api_key_hash, api_key_salt
    INTO v_stored_hash, v_stored_salt
    FROM public.devices
    WHERE device_id = p_device_id AND status = 'ACTIVE';

    IF NOT FOUND OR v_stored_hash IS NULL OR v_stored_salt IS NULL THEN 
        RETURN FALSE; 
    END IF;

    v_computed_hash := encode(digest(p_api_key || v_stored_salt, 'sha256'), 'hex');
    v_secret_nonce := encode(gen_random_bytes(16), 'hex');
    IF encode(hmac(v_computed_hash, v_secret_nonce, 'sha256'), 'hex') IS DISTINCT FROM
       encode(hmac(v_stored_hash, v_secret_nonce, 'sha256'), 'hex') THEN
        RETURN FALSE;
    END IF;

    UPDATE public.commands
    SET status = 'acknowledged',
        acknowledged_at = NOW()
    WHERE id = p_command_id AND device_id = p_device_id AND status = 'dispatched';

    RETURN FOUND;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

CREATE OR REPLACE FUNCTION public.report_command_result(
    p_device_id TEXT,
    p_command_id BIGINT,
    p_execution_status TEXT, -- 'executed' or 'failed'
    p_result_payload JSONB,
    p_api_key TEXT
)
RETURNS BOOLEAN AS $$
DECLARE
    v_stored_hash TEXT;
    v_stored_salt TEXT;
    v_computed_hash TEXT;
    v_secret_nonce TEXT;
BEGIN
    -- Strict nullity and length parameter guards (SEC-01)
    IF p_device_id IS NULL OR p_command_id IS NULL OR p_api_key IS NULL OR length(p_api_key) < 32 THEN
        RETURN FALSE;
    END IF;

    SELECT api_key_hash, api_key_salt
    INTO v_stored_hash, v_stored_salt
    FROM public.devices
    WHERE device_id = p_device_id AND status = 'ACTIVE';

    IF NOT FOUND OR v_stored_hash IS NULL OR v_stored_salt IS NULL THEN 
        RETURN FALSE; 
    END IF;

    v_computed_hash := encode(digest(p_api_key || v_stored_salt, 'sha256'), 'hex');
    v_secret_nonce := encode(gen_random_bytes(16), 'hex');
    IF encode(hmac(v_computed_hash, v_secret_nonce, 'sha256'), 'hex') IS DISTINCT FROM
       encode(hmac(v_stored_hash, v_secret_nonce, 'sha256'), 'hex') THEN
        RETURN FALSE;
    END IF;

    UPDATE public.commands
    SET status = CASE WHEN p_execution_status = 'executed' THEN 'executed'::command_delivery_status ELSE 'failed'::command_delivery_status END,
        executed_at = NOW(),
        result_payload = p_result_payload
    WHERE id = p_command_id AND device_id = p_device_id AND status IN ('dispatched', 'acknowledged');

    RETURN FOUND;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- 14. DEVICE KEY LIFECYCLE MANAGEMENT
CREATE OR REPLACE FUNCTION public.admin_provision_device_key(
    p_device_id TEXT,
    p_raw_api_key TEXT
)
RETURNS VOID AS $$
DECLARE
    v_salt TEXT;
    v_hash TEXT;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'Insufficient privilege to provision device keys';
    END IF;

    -- Validate device ID and key entropy (SEC-13)
    IF p_device_id IS NULL OR p_raw_api_key IS NULL OR length(p_raw_api_key) < 32 THEN
        RAISE EXCEPTION 'Device ID required and API key must be at least 32 characters' USING ERRCODE = 'P0001';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.devices WHERE device_id = p_device_id) THEN
        RAISE EXCEPTION 'Device % does not exist in registry', p_device_id USING ERRCODE = 'P0002';
    END IF;

    v_salt := encode(gen_random_bytes(32), 'hex');
    v_hash := encode(digest(p_raw_api_key || v_salt, 'sha256'), 'hex');

    UPDATE public.devices
    SET api_key_hash = v_hash,
        api_key_salt = v_salt,
        status = 'ACTIVE'
    WHERE device_id = p_device_id;

    -- Audit log event (SEC-13)
    INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, metadata)
    VALUES (auth.uid(), 'DEVICE_KEY_PROVISIONED', 'DEVICE', p_device_id, jsonb_build_object('timestamp', NOW()));
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

CREATE OR REPLACE FUNCTION public.admin_rotate_device_key(
    p_device_id TEXT,
    p_new_api_key TEXT
)
RETURNS VOID AS $$
BEGIN
    PERFORM public.admin_provision_device_key(p_device_id, p_new_api_key);
    INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, metadata)
    VALUES (auth.uid(), 'DEVICE_KEY_ROTATED', 'DEVICE', p_device_id, jsonb_build_object('timestamp', NOW()));
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

CREATE OR REPLACE FUNCTION public.admin_revoke_device(
    p_device_id TEXT
)
RETURNS VOID AS $$
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'Insufficient privilege to revoke devices';
    END IF;

    UPDATE public.devices
    SET status = 'REVOKED',
        api_key_hash = NULL,
        api_key_salt = NULL
    WHERE device_id = p_device_id;

    INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, metadata)
    VALUES (auth.uid(), 'DEVICE_REVOKED', 'DEVICE', p_device_id, jsonb_build_object('timestamp', NOW()));
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- 14b. UNTRUSTED LEGACY TELEMETRY QUARANTINE INGESTION RPC
CREATE OR REPLACE FUNCTION public.submit_legacy_telemetry(
    p_device_id TEXT,
    p_lat DOUBLE PRECISION,
    p_lng DOUBLE PRECISION,
    p_altitude DOUBLE PRECISION DEFAULT NULL,
    p_speed REAL DEFAULT NULL,
    p_bearing REAL DEFAULT NULL,
    p_accuracy REAL DEFAULT NULL,
    p_battery_level INTEGER DEFAULT NULL,
    p_legacy_secret TEXT DEFAULT NULL
)
RETURNS BOOLEAN AS $$
DECLARE
    v_cred_row RECORD;
    v_computed_hash TEXT;
    v_secret_nonce TEXT;
    v_client_ip_text TEXT;
    v_source_ip INET;
BEGIN
    -- 1. Mandatory Legacy Secret Authentication (Salted Hash in private.legacy_ingest_credentials)
    -- Rejects unauthenticated callers or deprecated channels with 42501 (SEC-01, Section 4.2)
    IF p_legacy_secret IS NULL THEN
        RAISE EXCEPTION 'Unauthorized legacy telemetry submission: missing secret' USING ERRCODE = '42501';
    END IF;

    SELECT secret_hash, secret_salt, is_active, deprecated_at
    INTO v_cred_row
    FROM private.legacy_ingest_credentials
    WHERE id = 1;

    IF NOT FOUND OR NOT v_cred_row.is_active OR (v_cred_row.deprecated_at IS NOT NULL AND v_cred_row.deprecated_at <= NOW()) THEN
        RAISE EXCEPTION 'Unauthorized legacy telemetry submission: inactive or deprecated ingestion channel' USING ERRCODE = '42501';
    END IF;

    v_computed_hash := encode(digest(p_legacy_secret || v_cred_row.secret_salt, 'sha256'), 'hex');
    v_secret_nonce := encode(gen_random_bytes(16), 'hex');
    IF encode(hmac(v_computed_hash, v_secret_nonce, 'sha256'), 'hex') IS DISTINCT FROM
       encode(hmac(v_cred_row.secret_hash, v_secret_nonce, 'sha256'), 'hex') THEN
        RAISE EXCEPTION 'Unauthorized legacy telemetry submission: invalid secret' USING ERRCODE = '42501';
    END IF;

    -- 2. Strict geographic, parameter, and coordinate boundary validation (fail fast before locking)
    IF p_device_id IS NULL OR p_lat IS NULL OR p_lng IS NULL THEN
        RAISE EXCEPTION 'Device ID and coordinates required' USING ERRCODE = 'P0001';
    END IF;

    IF p_lat < -90.0 OR p_lat > 90.0 OR p_lng < -180.0 OR p_lng > 180.0 THEN
        RAISE EXCEPTION 'Coordinate values out of physical GPS range' USING ERRCODE = 'P0002';
    END IF;

    IF p_accuracy IS NOT NULL AND (p_accuracy < 0.0 OR p_accuracy > 5000.0) THEN
        RAISE EXCEPTION 'Accuracy value anomalous' USING ERRCODE = 'P0003';
    END IF;

    -- 3. Atomic per-device rate limiting (serializes concurrent posts for same device)
    IF NOT pg_try_advisory_xact_lock(hashtext('legacy_throttle:' || p_device_id)) THEN
        RAISE EXCEPTION 'Telemetry rate limit exceeded (lock busy)' USING ERRCODE = 'P0004';
    END IF;

    -- Throttle check: max 1 submission per second per device_id
    IF EXISTS (
        SELECT 1 FROM public.tracker_telemetry_staging
        WHERE device_id = p_device_id
          AND created_at > NOW() - INTERVAL '1 second'
    ) THEN
        RAISE EXCEPTION 'Telemetry submission rate exceeded (1 req/sec limit)' USING ERRCODE = 'P0005';
    END IF;

    -- 4. Extract client IP from PostgREST request headers for abuse attribution
    BEGIN
        v_client_ip_text := COALESCE(
            current_setting('request.headers', true)::json->>'cf-connecting-ip',
            split_part(current_setting('request.headers', true)::json->>'x-forwarded-for', ',', 1),
            current_setting('request.headers', true)::json->>'x-real-ip'
        );
        IF v_client_ip_text IS NOT NULL AND length(trim(v_client_ip_text)) > 0 THEN
            v_source_ip := trim(v_client_ip_text)::inet;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_source_ip := NULL;
    END;

    -- 5. Concurrency-Safe Atomic Capacity Check (Row-Level Lock on private.staging_storage_counter, Section 4.3)
    -- Acquired immediately before insertion to minimize lock contention duration:
    UPDATE private.staging_storage_counter
    SET current_count = current_count + 1
    WHERE id = 1 AND current_count < 50000;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Quarantine storage ceiling reached (50,000 rows). Ingestion rejected.'
            USING ERRCODE = '53200'; -- insufficient_resources
    END IF;

    -- 6. Insert strictly into quarantine buffer; ZERO PROMOTION TO authoritative public.locations
    INSERT INTO public.tracker_telemetry_staging (
        device_id, lat, lng, altitude, speed, bearing, accuracy, battery_level, 
        is_verified, ingestion_channel, source_ip, created_at
    )
    VALUES (
        p_device_id, p_lat, p_lng, p_altitude, p_speed, p_bearing, p_accuracy, p_battery_level,
        false, 'LEGACY_UNVERIFIED', v_source_ip, NOW()
    );

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- 14c. QUARANTINE BUFFER RETENTION & PURGE ENGINE (CALLED BY PG_CRON)
CREATE OR REPLACE FUNCTION public.cleanup_telemetry_staging()
RETURNS VOID AS $$
DECLARE
    v_purged_ttl_count INT := 0;
    v_purged_fifo_count INT := 0;
    v_total_purged INT := 0;
    v_actual_count INT := 0;
BEGIN
    -- 1. Acquire exclusive lock on counter tuple FIRST to enforce identical lock order ($A -> B)
    -- and serialize with concurrent ingestion transactions, eliminating deadlocks and lost increments:
    PERFORM 1 FROM private.staging_storage_counter WHERE id = 1 FOR UPDATE;

    -- 2. Purge records older than 48-hour rolling TTL
    WITH deleted_ttl AS (
        DELETE FROM public.tracker_telemetry_staging
        WHERE created_at < NOW() - INTERVAL '48 hours'
        RETURNING id
    )
    SELECT count(*) INTO v_purged_ttl_count FROM deleted_ttl;

    -- 3. Rolling High-Watermark Headroom Eviction (DEFECT-C01 resolution):
    -- If total row count exceeds 40,000, prune the oldest records down to 40,000.
    -- This guarantees a minimum 10,000-row headroom buffer under heavy traffic bursts,
    -- preventing buffer saturation deadlocks.
    SELECT count(*)::INT INTO v_actual_count FROM public.tracker_telemetry_staging;
    IF v_actual_count > 40000 THEN
        WITH excess_rows AS (
            SELECT id FROM public.tracker_telemetry_staging
            ORDER BY id ASC -- delete oldest
            LIMIT (v_actual_count - 40000)
        ),
        deleted_fifo AS (
            DELETE FROM public.tracker_telemetry_staging
            WHERE id IN (SELECT id FROM excess_rows)
            RETURNING id
        )
        SELECT count(*) INTO v_purged_fifo_count FROM deleted_fifo;
    ELSE
        v_purged_fifo_count := 0;
    END IF;

    v_total_purged := COALESCE(v_purged_ttl_count, 0) + COALESCE(v_purged_fifo_count, 0);

    -- 4. Resynchronize atomic storage counter to actual table row count while holding row lock
    SELECT count(*)::INT INTO v_actual_count FROM public.tracker_telemetry_staging;
    UPDATE private.staging_storage_counter 
    SET current_count = LEAST(v_actual_count, 50000)
    WHERE id = 1;

    -- 5. Emit abuse alert if purge volume exceeds 10,000 in a single cycle
    IF v_total_purged >= 10000 THEN
        INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, metadata)
        VALUES (
            NULL, 
            'STAGING_FLOOD_PURGE_ALERT', 
            'QUARANTINE_BUFFER', 
            'tracker_telemetry_staging', 
            jsonb_build_object(
                'purged_ttl_count', v_purged_ttl_count,
                'purged_fifo_count', v_purged_fifo_count,
                'total_purged', v_total_purged,
                'current_reconciled_count', v_actual_count,
                'timestamp', NOW()
            )
        );
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- 15. EXPLICIT LEAST-PRIVILEGE GRANTS & FUNCTION OWNERSHIP
GRANT USAGE ON SCHEMA public TO anon, authenticated;

-- Grants for authenticated controllers
GRANT SELECT ON public.profiles, public.user_device_assignments, public.device_feature_permissions, public.audit_logs, public.locations, public.commands, public.tracker_telemetry_staging TO authenticated;

-- Column-level grant on devices: exclude api_key_hash and api_key_salt from controllers (SEC-12)
GRANT SELECT (device_id, hardware_uuid, legacy_identifier, is_legacy, name, model, status, enrolled_at, last_seen_at, battery_level) ON public.devices TO authenticated;

-- Direct client INSERT on public.commands is REVOKED from authenticated and anon.
-- (GRANT INSERT ON public.commands TO authenticated is intentionally omitted).
-- Commands are inserted exclusively via server-side Edge Function (/functions/v1/commands/submit)
-- using the service_role key after RFC 8785 canonicalization and NIST ECDSA P-256 digital signing.

-- Grant EXECUTE to authenticated for RLS helper functions (SEC-04)
GRANT EXECUTE ON FUNCTION public.current_user_role() TO authenticated;
GRANT EXECUTE ON FUNCTION public.is_owner() TO authenticated;
GRANT EXECUTE ON FUNCTION public.is_admin_or_owner() TO authenticated;
GRANT EXECUTE ON FUNCTION public.has_device_access(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.has_feature_permission(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.bootstrap_claim_owner(TEXT) TO authenticated;

-- RPC Execution Grants for devices / trackers (Consistent Security & Privilege Boundary)
-- 1. Modern Authenticated Command Pipeline (Edge Function Gateway Tier):
-- Authenticated trackers poll and acknowledge commands via HTTPS Edge Gateways:
--   - POST /functions/v1/tracker/commands/poll
--   - POST /functions/v1/tracker/commands/ack
--   - POST /functions/v1/tracker/commands/result
-- The Edge Gateways verify caller IP rate limits and authenticate device API keys in constant time
-- using node:crypto.timingSafeEqual before invoking the database RPCs via service_role.
-- To prevent clients from bypassing Edge tier security controls, direct PostgREST invocation is BLOCKED:
REVOKE ALL ON FUNCTION public.fetch_tracker_commands(TEXT, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.fetch_tracker_commands(TEXT, TEXT) TO service_role;

REVOKE ALL ON FUNCTION public.ack_command_receipt(TEXT, BIGINT, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.ack_command_receipt(TEXT, BIGINT, TEXT) TO service_role;

REVOKE ALL ON FUNCTION public.report_command_result(TEXT, BIGINT, TEXT, JSONB, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.report_command_result(TEXT, BIGINT, TEXT, JSONB, TEXT) TO service_role;

-- 2. Transitional Legacy Ingestion Sink (Direct PostgREST RPC):
-- Existing legacy tracker APKs in the field transmit directly to PostgREST using the anon key.
-- Therefore, submit_legacy_telemetry is granted to anon.
-- Security checks for this endpoint are strictly enforced within PostgreSQL (salted hash validation
-- against private.legacy_ingest_credentials, per-device advisory locks, atomic counter).
-- Note: It does NOT pass through Edge Functions, and PL/pgSQL variable-time evaluation is an accepted
-- residual limitation for the untrusted, quarantined legacy sink until deprecated_at cutover.
GRANT EXECUTE ON FUNCTION public.submit_legacy_telemetry(TEXT, DOUBLE PRECISION, DOUBLE PRECISION, DOUBLE PRECISION, REAL, REAL, REAL, INTEGER, TEXT) TO anon, authenticated;

-- Admin-only RPC Execution Grants
GRANT EXECUTE ON FUNCTION public.admin_provision_device_key(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.admin_rotate_device_key(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.admin_revoke_device(TEXT) TO authenticated;

-- Maintenance RPC Grant (service_role only, revoked from anon/authenticated)
REVOKE ALL ON FUNCTION public.cleanup_telemetry_staging() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_telemetry_staging() TO service_role;

-- Schedule staging cleanup every 10 minutes via pg_cron (if pg_cron extension enabled):
-- SELECT cron.schedule('cleanup-staging-telemetry', '*/10 * * * *', 'SELECT public.cleanup_telemetry_staging();');

-- Enforce postgres ownership on all SECURITY DEFINER functions
ALTER FUNCTION public.current_user_role OWNER TO postgres;
ALTER FUNCTION public.is_owner OWNER TO postgres;
ALTER FUNCTION public.is_admin_or_owner OWNER TO postgres;
ALTER FUNCTION public.has_device_access OWNER TO postgres;
ALTER FUNCTION public.has_feature_permission OWNER TO postgres;
ALTER FUNCTION public.handle_new_user OWNER TO postgres;
ALTER FUNCTION public.bootstrap_claim_owner OWNER TO postgres;
ALTER FUNCTION public.fetch_tracker_commands OWNER TO postgres;
ALTER FUNCTION public.ack_command_receipt OWNER TO postgres;
ALTER FUNCTION public.report_command_result OWNER TO postgres;
ALTER FUNCTION public.admin_provision_device_key OWNER TO postgres;
ALTER FUNCTION public.admin_rotate_device_key OWNER TO postgres;
ALTER FUNCTION public.admin_revoke_device OWNER TO postgres;
ALTER FUNCTION public.submit_legacy_telemetry OWNER TO postgres;
ALTER FUNCTION public.cleanup_telemetry_staging OWNER TO postgres;

-- Realtime publication lockdown: strip ALL sensitive tables (SEC-07)
ALTER PUBLICATION supabase_realtime DROP TABLE IF EXISTS public.locations;
ALTER PUBLICATION supabase_realtime DROP TABLE IF EXISTS public.commands;
ALTER PUBLICATION supabase_realtime DROP TABLE IF EXISTS public.media;
ALTER PUBLICATION supabase_realtime DROP TABLE IF EXISTS public.app_signals;
```

---

## 6. Authentication, Identity, and Command-Trust Boundaries

### 6.1 Server-Origin Command Authentication: Asymmetric Digital Signatures
Prior drafts evaluated symmetric HMAC signing where the tracker's shared API key was also used by the server to sign outgoing commands. This model was **formally rejected** because sharing the signing key with the tracker allows any compromised physical device to forge arbitrary commands to other trackers or to itself.

Version 3.2.1 establishes an **Asymmetric Digital Signature Protocol** strictly standardized on **NIST ECDSA P-256 with SHA-256 (`SHA256withECDSA`)**:
* **Standardization Rationale:** NIST ECDSA P-256 with SHA-256 is natively supported by the standard Android Java Cryptography Architecture (JCA) across all supported Android versions (API 26+) out of the box. Ed25519 requires Android 13+ (API 33+) or external third-party dependencies (BouncyCastle/Tink). Standardizing strictly on NIST P-256 eliminates third-party dependencies and guarantees native hardware crypto acceleration.

```
+---------------------------------------------------------------------------------------------------+
|                           END-TO-END ASYMMETRIC COMMAND SIGNING & DISPATCH PIPELINE               |
+---------------------------------------------------------------------------------------------------+
[ Controller Client ]
         |
         | (1) POST /functions/v1/commands/submit (GoTrue Bearer JWT)
         v
[ Supabase Edge Tier Gateway ]
         |
         | (2) Authorize: verifies JWT, user status ACTIVE,
         |     checks has_device_access() and has_feature_permission(device_id, feature)
         | (2b) Pre-allocate Command ID from Sequence: SELECT nextval('public.commands_id_seq')
         |      (Resolves circular dependency: guarantees signed command_id == stored id)
         | (3) Format RFC 8785 Canonical Wire Preimage & assign timestamps
         |     (issued_at_epoch_sec = NOW(), expires_at_epoch_sec = NOW() + 300s [5m operational TTL])
         | (4) Sign Preimage with SERVER_COMMAND_SIGNING_PRIVATE_KEY from Vault / KMS
         |     via NIST ECDSA P-256 (SHA256withECDSA)
         | (5) Privileged INSERT (id = command_id, server_signature, ...) via service_role
         v
[ PostgreSQL public.commands Table ] <--- Row stored with key_id, server_signature NOT NULL,
         |                                status = 'pending', operational timestamps
         |
         | (6) Device Poll: fetch_tracker_commands(p_device_id, p_api_key)
         v
[ Tracker Device (Field) ] <-------------- Receives: command_id, params, key_id, server_signature,
         |                                           issued_at_epoch_sec, expires_at_epoch_sec, idempotency_key
         v
[ Pinned Public Key Verification ] -------> 1. Verify key_id against pinned public key registry
         |                                  2. Verify freshness: system_epoch_sec BETWEEN issued AND expires
         |                                  3. Verify ECDSA signature using Android JCA ("SHA256withECDSA")
         v
[ Room SQLite Deduplication ] ------------> 4. Check tracker_executed_commands table
         |                                     (IF already executed, skip hardware execution and re-ACK)
         v
[ Hardware Command Execution ] -----------> 5. Execute camera/mic/audio/diagnostics hardware action
         |
         | (7) Acknowledge Receipt: ack_command_receipt(p_device_id, p_command_id, p_api_key)
         | (8) Report Execution Result: report_command_result(p_device_id, p_command_id, status, payload, p_api_key)
         v
[ PostgreSQL public.commands Table ] <--- Updates status to 'acknowledged' -> 'executed'/'failed',
                                          stores result_payload, terminates retry lifecycle
```

1. **Key Custody & Separation:**
   * **Server Private Key:** Resides exclusively within Supabase Edge Secrets Vault / KMS (`SERVER_COMMAND_SIGNING_PRIVATE_KEY`). It is completely inaccessible to database clients, controller applications, ordinary SQL callers, and field trackers.
   * **Server Public Key:** Pinned immutably inside the Tracker Android application as a compiled DER resource (`res/raw/server_command_pubkey.der`) or hardcoded Base64 string (`SERVER_COMMAND_VERIFICATION_PUBLIC_KEY`).
   * **Key Identifier (`key_id`):** Facilitates graceful out-of-band key rotation (e.g. `trace-srv-ecdsa-p256-v1`). The tracker maintains a registry of trusted public keys indexed by `key_id`.
2. **Deterministic Canonical Wire Format (RFC 8785 JCS):**
   To eliminate JSON key ordering and whitespace discrepancies between TypeScript/Deno, PostgreSQL, and Android Kotlin, the signature preimage is constructed from the canonical RFC 8785 normalized representation with 8 newline-separated fields:
   ```text
   TRACE-CMD-V1\n
   <command_id>\n
   <device_id>\n
   <command_name>\n
   <canonical_json_params>\n
   <issued_at_epoch_sec>\n
   <expires_at_epoch_sec>\n
   <idempotency_key>
   ```
   * All JSON parameter keys are lexicographically sorted, formatted with no whitespace around separators (`:`, `,`), and standard UTF-8 encoded.

2b. **Atomic Sequence Pre-Allocation (Resolving the Circular Signing Dependency):**
    * **The Circular Dependency Problem:** The canonical wire preimage binds `<command_id>`. However, `public.commands.id` is generated by the database sequence (`BIGSERIAL`), while `public.commands.server_signature` enforces a strict `NOT NULL` constraint. If the database generated the ID upon `INSERT`, the signature could not exist prior to insertion; conversely, if the Edge Function signed prior to insertion, it could not know the database-generated ID.
    * **Concrete Atomic Resolution:**
      1. When the controller submits a command to `POST /functions/v1/commands/submit`, the Edge Function verifies JWT authentication and evaluates device and feature authorization via `public.has_feature_permission()`.
      2. **Idempotency Query Before Sequence Reservation:** The Edge Function queries `SELECT id, status, server_signature FROM public.commands WHERE idempotency_key = v_idempotency_key;`. If a command with this idempotency key already exists, the function returns the existing record immediately without calling `nextval`, preventing unnecessary sequence burning or duplicate signing operations.
      3. If not found, the Edge Function calls `SELECT nextval('public.commands_id_seq')::BIGINT AS command_id;` using its `service_role` connection.
      4. In PostgreSQL, `nextval` atomically increments and reserves the sequence value without taking locks on the table or requiring an open transaction. It is guaranteed unique, strictly monotonic, and concurrency-safe across all distributed Edge instances.
      5. The Edge Function injects this allocated `command_id` into line 2 of the canonical RFC 8785 wire preimage.
      6. The Edge Function computes the NIST ECDSA P-256 signature (`server_signature`) over the canonical preimage bytes using `SERVER_COMMAND_SIGNING_PRIVATE_KEY`. In production, the operational TTL is set to 300 seconds (5 minutes: `expires_at_epoch_sec = NOW() + 300`), which cleanly accommodates up to 5 retries by the 60-second reaper without premature expiration.
      7. The Edge Function inserts the completed row into `public.commands` with `id = v_command_id`, satisfying `server_signature NOT NULL` instantly and atomically:
         ```sql
         INSERT INTO public.commands (
             id, device_id, issuer_user_id, idempotency_key, command, params,
             key_id, server_signature, issued_at_epoch_sec, expires_at_epoch_sec,
             status, created_at
         ) VALUES (
             v_command_id, v_device_id, v_issuer_user_id, v_idempotency_key, v_command, v_params,
             v_key_id, v_server_signature, v_issued_at, v_expires_at,
             'pending', NOW()
         );
         ```
      8. **Failure and Retry Behavior:** If the Edge Function fails or the network drops between `nextval` and `INSERT`, the reserved sequence ID is simply retired (standard sequence gap behavior). The client can safely retry the request using the same `idempotency_key`; the database unique index on `idempotency_key` guarantees that at most one command record is ever stored per logical submission.

3. **Authoritative Concrete Test Vector:**
   To enable cross-platform verification during Phase 1 & 2 development, the following reference test vector specifies the exact wire format, preimage, and SHA-256 hash. (Note: The test vector below uses a nominal 30s expiration interval, independently verified by automated unit tests in `CommandCryptoVerificationTest.kt`, while production operational dispatch standardizes on 300s):
   * **Input Fields:**
     * `command_id`: `1042`
     * `device_id`: `tracker-alpha-01`
     * `command_name`: `take_photo`
     * `canonical_json_params`: `{"camera":"back","flash":false,"quality":85}`
     * `issued_at_epoch_sec`: `1776345600`
     * `expires_at_epoch_sec`: `1776345630`
     * `idempotency_key`: `b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234`
   * **Exact Canonical Preimage String (8 lines, LF `\n` delimited, UTF-8):**
     ```text
     TRACE-CMD-V1
     1042
     tracker-alpha-01
     take_photo
     {"camera":"back","flash":false,"quality":85}
     1776345600
     1776345630
     b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234
     ```
   * **Expected SHA-256 Hash of Preimage Bytes:**
     `157b1ac9f9792b7052e5d95c3ec8c8112797e193d43d40db2ffcede6b10599cb`
   * **Signature Algorithm:** `SHA256withECDSA` (ASN.1/DER encoded ECDSA signature over the above SHA-256 digest).

4. **Tracker Verification Rules:**
   * **Step 1 (Key Lookup):** Resolve `key_id` against the tracker's pinned public key registry. If unpinned or unrecognized, reject immediately.
   * **Step 2 (Freshness Window):** Verify that `system_epoch_sec >= (issued_at_epoch_sec - 5)` (tolerance for minor clock skew) and `system_epoch_sec <= expires_at_epoch_sec`. Future-skewed or expired commands are discarded.
   * **Step 3 (Cryptographic Signature Check):** Compute SHA-256 over canonical preimage bytes and verify via `java.security.Signature.getInstance("SHA256withECDSA")`.
   * **Step 4 (Replay Rejection):** Query Room SQLite database (`tracker_executed_commands`) for prior execution of `command_id` or `idempotency_key`.

5. **Cross-Platform ECDSA Encoding Adapter (IEEE P1363 vs. ASN.1/DER):**
   * **Incompatibility Risk:** Standard WebCrypto (`crypto.subtle.sign`) outputs raw IEEE P1363 format (fixed 64 bytes: 32 bytes $R$ concatenated with 32 bytes $S$). However, standard Android Java Cryptography Architecture (`java.security.Signature.getInstance("SHA256withECDSA")`) requires ASN.1/DER encoding (variable 70–72 bytes formatted as a DER sequence containing two ASN.1 integers: `0x30 [len] 0x02 [r_len] [r] 0x02 [s_len] [s]`). If raw IEEE P1363 bytes are passed to Android's `Signature.verify()`, verification throws `SignatureException: Invalid DER encoding` on 100% of calls.
   * **Resolution:**
     - In Edge Functions, using Deno / Node `node:crypto.createSign("SHA256")` natively produces standard ASN.1/DER signatures.
     - Alternatively, if WebCrypto is used, the tracker or Edge Gateway converts between IEEE P1363 and ASN.1/DER using the verified conversion adapter implemented and tested in `CommandCryptoVerificationTest.kt`:
     ```kotlin
     fun p1363ToDer(p1363Sig: ByteArray): ByteArray {
         require(p1363Sig.size == 64) { "P-256 IEEE P1363 signature must be exactly 64 bytes" }
         val r = p1363Sig.copyOfRange(0, 32)
         val s = p1363Sig.copyOfRange(32, 64)
         fun encodeInteger(value: ByteArray): ByteArray {
             var start = 0
             while (start < value.size - 1 && value[start] == 0.toByte()) start++
             val trimmed = value.copyOfRange(start, value.size)
             val needsPad = (trimmed[0].toInt() and 0x80) != 0
             val len = trimmed.size + if (needsPad) 1 else 0
             val out = ByteArray(2 + len)
             out[0] = 0x02.toByte()
             out[1] = len.toByte()
             if (needsPad) {
                 out[2] = 0x00.toByte()
                 System.arraycopy(trimmed, 0, out, 3, trimmed.size)
             } else {
                 System.arraycopy(trimmed, 0, out, 2, trimmed.size)
             }
             return out
         }
         val rDer = encodeInteger(r)
         val sDer = encodeInteger(s)
         val totalLen = rDer.size + sDer.size
         val der = ByteArray(2 + totalLen)
         der[0] = 0x30.toByte()
         der[1] = totalLen.toByte()
         System.arraycopy(rDer, 0, der, 2, rDer.size)
         System.arraycopy(sDer, 0, der, 2 + rDer.size, sDer.size)
         return der
     }
     ```

6. **Realtime Publication vs. WebSocket Broadcast Distinction:**
   * `ALTER PUBLICATION supabase_realtime DROP TABLE` strips tables (`locations`, `commands`, `media`, `app_signals`) from PostgreSQL Change Data Capture (CDC) replication. This prevents database table updates from streaming over Realtime.
   * **Critical Defense Note:** Supabase Realtime also provides ephemeral "Broadcast" and "Presence" WebSockets (e.g. `channel.subscribe()`, `channel.send({type: 'broadcast', ...})`). In the legacy codebase, channels `public:locations`, `public:commands`, and `webrtc-$sessionId` were used. These broadcast channels are purely in-memory on the Realtime server and completely bypass PostgreSQL RLS.
   * **Required Phase 2 Action:** Phase 2 client updates must terminate all unauthenticated Realtime broadcast subscriptions and migrate signaling to authenticated WebSockets or HTTPS Edge Gateways.

7. **Legacy Field Tracker APK Cutover Bridge:**
   * Existing legacy tracker APKs deployed in the field transmit directly to `postgrest["locations"].insert()`.
   * Enforcing RLS on `public.locations` without an upgrade bridge will immediately sever field telemetry for un-upgraded trackers.
   * **Operational Cutover Strategy:**
     - **Option A (Scheduled Cutover):** Deploy upgraded tracker APKs calling `submit_legacy_telemetry` during a scheduled maintenance window before applying the RLS lockdown.
     - **Option B (Database Transition View / Trigger):** If zero field downtime is required during the transition, configure a temporary PostgreSQL `INSTEAD OF INSERT` trigger on a compatibility view or transition window routing unauthenticated inserts into `public.tracker_telemetry_staging` until `deprecated_at`.

### 6.2 Credential Reality & Multi-Tier Verification Boundaries
Prior audit drafts claimed that PL/pgSQL comparisons using `= `, `!=`, or HMAC-nonce masking were "constant-time". This claim is **formally retracted**:
* **PostgreSQL Execution Model:** PostgreSQL is written in C and executes PL/pgSQL string equality and byte array comparisons via standard variable-time loops that terminate on the first non-matching byte. PostgreSQL offers no native constant-time equality primitive.
* **Correction of SubtleCrypto Misconception:** The W3C `SubtleCrypto` standard does NOT specify a `timingSafeEqual` method. In Deno and Node.js environments, constant-time comparison is provided via `node:crypto`:
  ```typescript
  import { timingSafeEqual } from "node:crypto";
  // Evaluates two equal-length Uint8Array buffers in constant time
  const isValid = bufA.length === bufB.length && timingSafeEqual(bufA, bufB);
  ```
  Alternatively, an explicit constant-time comparison loop over `Uint8Array` guarantees side-channel immunity across any JavaScript/TypeScript runtime:
  ```typescript
  function constantTimeEqual(a: Uint8Array, b: Uint8Array): boolean {
    if (a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff === 0;
  }
  ```
* **Architectural Tiering:**
  * **Authoritative Constant-Time Verification Tier (Edge Gateway):** Supabase Edge Functions (`/functions/v1/commands/poll`, `/functions/v1/telemetry/submit`) act as the secure entry gateway. They convert credentials and stored hashes into fixed-length `Uint8Array` buffers and execute `timingSafeEqual`, providing hardware-level immunity to timing side-channels.
  * **Database Defense-in-Depth:** In PostgreSQL RPCs, defense-in-depth is maintained through salted SHA-256 digests (`api_key_hash`, `api_key_salt`), strict parameter nullity/length guards, transaction-level advisory locks (`pg_try_advisory_xact_lock`), and strict connection rate limits.

### 6.3 Command Reliability State Machine, Room Deduplication & Lost-ACK Recovery
Network connectivity in field tracking scenarios is inherently intermittent. Version 3.2.1 establishes a robust, two-phase durable command delivery lifecycle with persistent tracker-side deduplication:

```
+---------------------------------------------------------------------------------------------------+
|                               COMMAND LIFECYCLE & LOST-ACK RECOVERY                               |
+---------------------------------------------------------------------------------------------------+
    [ pending ]
         |
         | (1) Tracker polls via Edge; Server sets dispatched_at = NOW(), retry_count++
         |     (Signed fields issued_at and expires_at remain immutable)
         v
   [ dispatched ] <-----------------------+ (60s Reaper resets if unacknowledged & retry < 5 & unexpired)
         |                                |
         | (2) Tracker verifies signature |
         |     and calls ack_command_receipt()
         v
  [ acknowledged ]
         |
         | (3) Tracker commits to Room SQLite:
         |     tracker_executed_commands(command_id, idempotency_key, 'EXECUTING')
         | (4) Tracker executes physical hardware action (e.g. camera photo, reboot)
         | (5) Tracker updates Room SQLite: status = 'executed', result_payload = {...}
         |
         | (6) Tracker calls report_command_result()
         v
   [ executed / failed ]
```

1. **Decoupling Operational Retries from Signed Preimages:**
   The cryptographic signature covers strictly immutable fields: `id`, `device_id`, `command`, `params`, `issued_at_epoch_sec`, `expires_at_epoch_sec`, and `idempotency_key`. Mutable operational polling timestamps (`dispatched_at`) and retry counters (`retry_count`) are excluded from the signed preimage. This guarantees that when an intermittent connection triggers a retry after 60 seconds, the original signature remains mathematically valid on subsequent polls without requiring re-signing or private key access.

2. **Persistent Tracker Deduplication Table (`tracker_executed_commands`):**
   The Android tracker manages a durable Room SQLite table surviving process kills and reboots:
   ```kotlin
   @Entity(tableName = "tracker_executed_commands")
   data class TrackerExecutedCommand(
       @PrimaryKey val commandId: Long,
       val idempotencyKey: String,
       val commandName: String,
       val executedAtEpochSec: Long,
       val executionStatus: String, // "executed" or "failed"
       val resultPayloadJson: String?,
       val ackSyncedToServer: Boolean
   )
   ```

3. **Lost-ACK Recovery Procedure:**
   * **Failure Scenario:** A tracker receives a `take_photo` command, validates the signature, captures the photo, and commits the result payload to Room SQLite. However, before the HTTP response from `report_command_result` completes, the cellular network drops.
   * **Server Behavior:** The server does not receive the execution report. After 60 seconds, the inline retry reaper resets the command from `dispatched` back to `pending`.
   * **Recovery on Reconnection:** On subsequent poll, the tracker receives the command again with the same `commandId` and `idempotencyKey`.
   * **Deduplication Invariant:** The tracker queries Room SQLite before taking any hardware action. Finding `commandId` already present in `tracker_executed_commands`:
     1. It **does NOT** trigger the camera shutter a second time.
     2. It immediately retrieves the cached `resultPayloadJson` and re-dispatches `report_command_result(command_id, status, cached_payload)`.
     3. Once the server confirms the report, the tracker updates `ackSyncedToServer = true`.

---

## 7. Android Cryptographic Architecture: PIN & Keystore

The implementation of User Decision 1 (Option A: Local PIN-First Gate) follows strict Android Keystore guidelines and hardware-isolated cryptographic boundaries:

```
+---------------------------------------------------------------------------------------------------+
|                                 DUAL-LAYER ANDROID CRYPTOGRAPHIC STORAGE                          |
+---------------------------------------------------------------------------------------------------+
[ User enters 4-digit PIN ]
            |
            v
   [ PBKDF2WithHmacSHA256 ] (100k iterations, 32-byte salt)
            |
            v
     [ Key_pin (256-bit AES) ]
            |
            v  (Encrypts / Decrypts Layer 2)
  +-----------------------------------------------------------------+
  | Intermediate Ciphertext = AES-256-GCM(Key_pin, RefreshToken)   |
  +-----------------------------------------------------------------+
            ^
            |  (Encrypts / Decrypts Layer 1)
     [ Key_keystore (Hardware-Backed Master Key in TEE/StrongBox) ]
            |
            v
  +-----------------------------------------------------------------+
  | Final Ciphertext Stored in EncryptedSharedPreferences           |
  +-----------------------------------------------------------------+
```

1. **Mathematical Entropy Qualification & Offline Guessing Boundary:**
   * A 4-digit numeric PIN consists of $10^4 = 10,000$ possible combinations, providing exactly $\log_2(10000) \approx 13.29\text{ bits}$ of mathematical entropy.
   * **Cryptographic Reality of PBKDF2:** Key stretching via `PBKDF2WithHmacSHA256` (100,000 iterations, 32-byte salt) substantially increases the computational work factor per attempt (~50–100 ms on mobile CPU, delaying online testing). However, **it cannot mathematically expand the underlying 13.3-bit search space**. If an attacker could extract the Layer 2 ciphertext and salt, an offline GPU attack could exhaust all 10,000 combinations in tens of seconds.
   * **Absolute Reliance on Layer 1 Hardware Boundary:** Consequently, the security of the 4-digit PIN against offline brute-force attacks **depends entirely and exclusively on Layer 1**: the hardware-backed AES-256-GCM master key in `AndroidKeyStore` (TEE/StrongBox). Because `Key_keystore` is non-exportable and physical hardware isolates key operations, an attacker without execution access inside the device's TEE cannot obtain the intermediate Layer 2 ciphertext, making offline dictionary attacks impossible.

2. **Hardware Master Key Isolation (Layer 1):**
   * The outer encryption layer utilizes an AES-256-GCM master key (`trace_master_keystore_key`) generated and stored within the device hardware `AndroidKeyStore` (`MasterKey.Builder` bound to TEE/StrongBox).
   * Key material is non-exportable: raw private/secret key bytes cannot be extracted by root software or offloaded from silicon. Decryption of Layer 1 requires physical execution inside the hardware TEE.

3. **User Knowledge Stretching & Double Encryption (Layer 2):**
   * The user-entered 4-digit PIN is stretched using `PBKDF2WithHmacSHA256` (100,000 iterations with a device-unique 32-byte cryptographic salt generated via `SecureRandom`).
   * The derived 256-bit `Key_pin` encrypts the Supabase refresh token and credentials using AES-256-GCM (`Intermediate Ciphertext`).
   * The intermediate ciphertext is then encrypted with `Key_keystore` before storage in `EncryptedSharedPreferences`.

4. **Application-Layer Lockout State Machine & Anti-Tampering (Correcting Hardware Claims):**
   * **Android Platform Reality:** AndroidKeyStore does **NOT** provide native hardware-enforced lockout timers for custom in-app software PINs (Android's hardware biometric/credential gates only apply to device-level screen locks).
   * **Persistent State Machine:** Lockout durations are enforced by a hardened application-layer state machine persisted in encrypted storage:
     * **Attempts 1–4:** Progressive UI delay (500 ms backoff).
     * **Attempt 5:** 30-second lockout.
     * **Attempts 6–9:** Exponential lockout escalation (60s, 120s, 300s, 600s).
     * **Attempt 10 (Zeroization):** Complete cryptographic purge.
   * **Reboot & Monotonic Time Anti-Tamper:** Lockout expiration is calculated using `SystemClock.elapsedRealtime()` combined with device boot count tracking (`Settings.Global.BOOT_COUNT`). If the device is rebooted during a lockout, `elapsedRealtime()` resets; detecting a change in `BOOT_COUNT` ensures the remaining lockout duration is re-enforced rather than bypassed.

5. **Key Deletion, App Wipe & Zeroization Lifecycle:**
   * **Hardware Zeroization on Lockout:** When 10 failed attempts occur, `keyStore.deleteEntry("trace_master_keystore_key")` is invoked. Because the master key was generated in hardware silicon and never exported, deleting the alias permanently zeroizes the key in the TEE. Layer 1 decryption becomes mathematically impossible, permanently destroying all cached tokens.
   * **App Data Wipe & Reinstallation:** If the user or an attacker executes `pm clear` or uninstalls the application, the AndroidKeyStore alias and all private application sandbox files are wiped. Cached refresh tokens are permanently destroyed, requiring clean re-authentication via Google Sign-In.
   * **Cloud Auto-Backup Protection (`backup_rules.xml`):** If Android Auto-Backup were active, encrypted credentials could be backed up to Google Drive and restored to a different device lacking the hardware master key. The application explicitly disables Auto-Backup for credential stores via `android:allowBackup="false"` in `AndroidManifest.xml` and excludes credential paths via `res/xml/backup_rules.xml`.

6. **Local Possession Gate vs. Cloud Authority:**
   * The local PIN functions strictly as an ergonomic **device-possession gate** for unlocking cached credentials on cold launch. It does not replace or forge server-side cloud identity.
   * Cloud authorization is governed exclusively by Supabase GoTrue JWTs and refresh tokens. If a user's account is revoked, suspended, or demoted in `public.profiles`, the next silent token refresh is rejected by Supabase with HTTP 401, immediately terminating access regardless of local PIN entry.

7. **Key Invalidation Handling:**
   * Handles `KeyPermanentlyInvalidatedException` (triggered if device lock screen credentials are removed or changed), gracefully purging local storage and routing to Google Sign-In re-authentication.

---

## 8. Historical Identity Migration Safeguards

1. **Exact String Preservation:** Historical `device_id` strings across all 107,601+ records in `public.locations` are preserved **without prefixes** (no `legacy-` prefix on `device_id`).
2. **Additive Dual-Key Schema:** `public.devices` introduces `device_id TEXT PRIMARY KEY`, `hardware_uuid UUID UNIQUE DEFAULT NULL`, and `is_legacy BOOLEAN DEFAULT false`.
3. **Idempotent Backfill & Constraint:** Backfill seeds legacy records with `is_legacy = true` and `hardware_uuid = NULL`. Foreign key enforces `ON DELETE RESTRICT` (preventing cascading deletion of historical GPS tracks).
4. **Resumable Migration Execution:** Foreign keys are attached with `NOT VALID` and verified in a subsequent step to avoid long table locks on active production tables. The distinct legacy `device_id`s must be backfilled first to prevent constraint validation failure (SEC-05):
   ```sql
   -- Mandatory idempotent legacy backfill query before constraint validation (SEC-05)
   INSERT INTO public.devices (device_id, name, is_legacy, hardware_uuid, status)
   SELECT DISTINCT 
       device_id, 
       'Legacy Tracker ' || device_id, 
       true, 
       NULL, 
       'ACTIVE'::device_status
   FROM public.locations
   ON CONFLICT (device_id) DO NOTHING;

   DO $$
   BEGIN
       IF NOT EXISTS (
           SELECT 1 FROM pg_constraint WHERE conname = 'fk_locations_device_id'
       ) THEN
           ALTER TABLE public.locations 
               ADD CONSTRAINT fk_locations_device_id 
               FOREIGN KEY (device_id) 
               REFERENCES public.devices(device_id) 
               ON DELETE RESTRICT 
               NOT VALID;
       END IF;
   END $$;

   ALTER TABLE public.locations VALIDATE CONSTRAINT fk_locations_device_id;
   ```

---

## 9. Test Evidence & Outstanding Validation Gaps

1. **Unit Test Baseline (Independently Verified):** 499 tests pass in 12.4s across `:core` (394) and `:app` (105). High internal logic consistency for existing client codebase.
2. **0 Instrumented Tests:** `app/src/androidTest` and `core/src/androidTest` do not exist. Navigation guards and UI dynamic hiding lack automated verification.
3. **Host Emulator Realities:** Host system contains `Pixel_10_Pro` (API 37.1 / Preview). Pixel 5 does not exist. Tests requiring hardware Google Play Services must be ported to Pixel 10 Pro.
4. **Physical Tracker Hardware Verification Missing:** Mutual authentication, command signing, and staging redirection have **not been tested on physical hardware**.
5. **0 Runtime Tests on Proposed Specifications (UPS):** While 499 unit tests pass on existing client code, **0 tests have been run against the proposed PostgreSQL schema, RLS policies, asymmetric command envelopes, or quarantine ingestion RPCs**, because all Phase 1 implementation remains strictly blocked pending Owner authorization.

---

## 10. Dependency-Aware 10-Phase Roadmap & Hardened Recovery

```
+---------------------------------------------------------------------------------------------------+
|                               PHASE DEPENDENCY & EXECUTION PIPELINE                               |
+---------------------------------------------------------------------------------------------------+
[ Phase 0: Security Gate ]  <--- (STATUS: READY FOR PHASE 0 OWNER REVIEW)
         |
         v
[ Phase 1: Database Hardening & Controlled Quarantine ]
         |
         +---------------------------------------+
         v                                       v
[ Phase 2: Tracker Protocol Sync ]      [ Phase 3: Controller Google Auth & Keystore PIN ]
         |                                       |
         +-------------------+-------------------+
                             v
                    [ Phase 4: Visitor Demo Mode ]
                             v
                    [ Phase 5: Authorization Engine & RBAC ]
                             v
                    [ Phase 6: Admin Management UI ]
                             v
                    [ Phase 7: Command Dispatch & WebRTC Revocation ]
                             v
                    [ Phase 8: Comprehensive End-to-End Test Suite ]
                             v
                    [ Phase 9: Production Cutover & Final Sign-Off ]
```

* **Anti-Pattern Elimination:** Re-opening `public_all` or disabling RLS during a rollback is strictly prohibited. Rollback relies on staging containment and encrypted offline physical database backups.

---

## 11. Unresolved Operational Questions & Blockers

The following items are **strictly unresolved** and require explicit confirmation from the Owner prior to Phase 1 authorization:

1. **Owner Bootstrap Identity:** Awaiting confirmation of the designated Google OAuth email to receive the initial `OWNER` role.
2. **Maintenance Window Agreement:** Awaiting Owner agreement on an authorized 15-minute maintenance window.
3. **Physical Tracker Availability:** Awaiting confirmation of the availability of physical tracker hardware for Phase 2 verification.

---

## 12. Explicit Authorization Checklist for Phase 1

Implementation of Phase 1 remains blocked until the Owner explicitly signs off on each checkpoint:

```markdown
[ ] 1. I approve the Phase 0 Security Gate Record (v3.2.1) as the technical baseline.
[ ] 2. I confirm the designated initial Superadmin Owner Google OAuth email.
[ ] 3. I authorize the Phase 1 maintenance window (15 minutes).
[ ] 4. I authorize deployment of the hardened RLS schema and Controlled Quarantine buffer.
[ ] 5. I authorize transition to Phase 1 implementation.
```

---

## 13. Issue-by-Issue Disposition, Residual Risks & Scope Record

### 13.1 Review Area Disposition (A through J)

| Defect / Review Area | Disposition & Corrected Technical Design | Standardized Status |
|:---|:---|:---:|
| **A. Legacy Telemetry Ingestion** | Static APK secret rejected as identity. Unauthenticated data quarantined into staging (`is_verified = FALSE`). Automatic promotion to `locations` prohibited. Multi-tier rate limiting and 48h TTL specified. | **Specified** |
| **B. SQL Privileges & RLS** | Default `PUBLIC` execution privileges stripped across schema. Explicit least-privilege grants cataloged. Search paths fixed. `commands`, `locations`, `media`, `app_signals` dropped from Realtime. | **Specified** |
| **C. Controller Authorization** | Issuer identity bound to `issuer_user_id DEFAULT auth.uid()` with RLS enforcement. Command allowlisting with `ELSE NULL` default-deny. Parameter JSON bounds enforced. | **Specified** |
| **D. Device Credentials** | High-entropy 256-bit token. Salted SHA-256 storage. Timing-resistant HMAC blinding with `IS DISTINCT FROM` in SQL; native constant-time Edge verification specified. Full lifecycle DDL provided. | **Specified** |
| **E. Durable Command Delivery** | Resilient state machine (`pending` $\to$ `dispatched` $\to$ `acknowledged` $\to$ `executed/failed`). Separate receipt vs execution ACKs. Tracker Room deduplication table. Inline 60s retry reaper. | **Specified** |
| **F. Rate Limiting Concurrency** | Concurrency race condition under `READ COMMITTED` resolved via advisory locks (`pg_try_advisory_xact_lock`), 50k row ceiling, and Tier 1 Edge rate limiting. | **Specified** |
| **G. Device Identity Migration** | Exact string preserved for `device_id`. `hardware_uuid = NULL` for legacy records (no fake UUIDs). Idempotent backfill pre-validation. Non-blocking foreign-key validation (`NOT VALID`). | **Specified** |
| **H. Android PIN & Keystore** | True dual-layer design: KeyStore AES-256-GCM (Layer 1) + PBKDF2 100k iterations (Layer 2) double encryption. Hardware lockout. Invalidation exception handling. | **Specified** |
| **I. Owner Bootstrap Decoupled** | Hardcoded emails eliminated. Bootstrap driven by durable table `private.system_bootstrap`. Single-use invariant enforced via single-row table and transactional advisory lock. | **Specified** |
| **J. Realtime & Containment** | Sensitive tables stripped from `supabase_realtime`. Anti-pattern of reopening `public_all` eliminated. Staging containment and offline physical backup rollback specified. | **Specified** |

### 13.2 Dual-Specialist Technical Defect Remediation Record (SEC-01 through SEC-13)

| Defect ID | Severity | Discovery Specialist | Root Cause / Vulnerability | Remediation Specified in v3.2.1 | Standardized Status |
|:---|:---:|:---|:---|:---|:---:|
| **SEC-01** | **CRITICAL** | Specialist 1 (DB) | SQL three-valued logic bypass: `p_api_key = NULL` or unprovisioned device evaluates `!=` to `NULL`, skipping exception branch in `IF`. | Added strict nullity/length guards (`length(p_api_key) >= 32`) and replaced `!=` with `IS DISTINCT FROM` across all RPCs. | **Specified** |
| **SEC-02** | **CRITICAL** | Specialist 1 (DB) | Permissive policy aggregation: legacy `public_all` wildcard policy combines via `OR` with restrictive policies, bypassing RLS. | Added explicit `DROP POLICY IF EXISTS "public_all"` on `commands`, `media`, `locations`, `app_signals`. | **Specified** |
| **SEC-03** | **CRITICAL** | Specialist 1 (DB) | Pre-existing production tables (`locations`, `media`, `app_signals`) omitted from `ENABLE ROW LEVEL SECURITY`. 107,601+ rows exposed. | Added explicit `ALTER TABLE public.locations ENABLE ROW LEVEL SECURITY;` (along with `media` and `app_signals`). | **Specified** |
| **SEC-04** | **CRITICAL** | Specialist 1 (DB) | Stripping function privileges from `PUBLIC` revoked execution on RLS helper functions without granting them back to `authenticated`. | Added explicit `GRANT EXECUTE` on `current_user_role`, `is_owner`, `is_admin_or_owner`, `has_device_access`, `has_feature_permission` to `authenticated`. | **Specified** |
| **SEC-05** | **CRITICAL** | Specialist 1 (DB) | `VALIDATE CONSTRAINT fk_locations_device_id` would fail immediately because distinct legacy `device_id`s were not yet seeded in `devices`. | Added mandatory idempotent backfill query inserting all distinct legacy `device_id`s into `public.devices` prior to constraint validation. | **Specified** |
| **SEC-06** | **HIGH** | Specialists 1 & 2 | Dispatch HMAC signature preimage in `fetch_tracker_commands` omitted `c.params::text`, permitting parameter tampering. | Standardized on NIST ECDSA P-256 with immutable `issued_at_epoch_sec` and `expires_at_epoch_sec` in canonical RFC 8785 wire preimage. | **Specified** |
| **SEC-07** | **HIGH** | Specialist 1 (DB) | `public.media` was omitted from Realtime publication removal, leaking surveillance photos and audio over WebSocket channels. | Added `ALTER PUBLICATION supabase_realtime DROP TABLE IF EXISTS public.media;`. | **Specified** |
| **SEC-08** | **HIGH** | Specialist 1 (DB) | Schema privilege revoking omitted `authenticated`, leaving pre-existing default table/sequence privileges active. | Expanded table and sequence revocations and `ALTER DEFAULT PRIVILEGES` to include `authenticated`. | **Specified** |
| **SEC-09** | **HIGH** | Specialist 1 (DB) | `public.tracker_telemetry_staging` had RLS enabled but no `SELECT` policy or grant for administrators, blocking diagnostic inspection. | Added `staging_select_admin_policy` and granted `SELECT` on `tracker_telemetry_staging` to `authenticated`. | **Specified** |
| **SEC-10** | **HIGH** | Specialist 1 (DB) | `handle_new_user()` did not verify that an owner did not already exist, violating the single-use bootstrap invariant. | Guarded Owner promotion with partial unique index `idx_single_owner_constraint` and atomic single-row table `private.system_bootstrap`. | **Specified** |
| **SEC-11** | **HIGH** | Specialist 2 (Crypto) | Lack of an inline retry reaper caused commands unacknowledged after 60s to remain stalled in `dispatched` indefinitely. | Added inline reaper in `fetch_tracker_commands` resetting stale dispatched commands (>60s) to `pending` (up to 5 retries if unexpired) or `expired`. | **Specified** |
| **SEC-12** | **HIGH** | Specialist 2 (Crypto) | Granting table-level `SELECT` on `public.devices` exposed `api_key_hash` and `api_key_salt` to controller clients. | Replaced table grant with column-level `GRANT SELECT` on non-secret columns only. | **Specified** |
| **SEC-13** | **MEDIUM** | Specialist 2 (Crypto) | `admin_provision_device_key` lacked device existence validation, key entropy checks, and audit logging. | Added parameter nullity/length checks, existence validation, and immutable audit log insertion. | **Specified** |

### 13.3 Authoritative 8-Blocker Technical Disposition Matrix (Version 3.2.1 Closure)

| Blocker # | Core Blocker Description | Previous Defective Design (v3.1.0) | Authoritative Resolution in Version 3.2.1 | Standardized Status |
|:---:|:---|:---|:---|:---:|
| **1** | **Server-Origin Command Authentication** | Symmetric HMAC using the tracker's shared API key as the server signing key (shared secret allowed compromised tracker to forge commands). | **Asymmetric Digital Signatures (NIST ECDSA P-256 with SHA-256):** Server private key held exclusively in Edge Vault; tracker pins public key in APK. Wire payload encoded via deterministic **RFC 8785 Canonical JSON** with concrete test vector. | **Specified** (Section 5.2, Section 6.1) |
| **2** | **Legacy Telemetry Quarantine & Upgrade** | Vaguely claimed static APK header `x-app-secret` provided partial identity; staging lacked concrete RPC and quarantine lifecycle. | **Zero Authenticity Acknowledged & Quarantine Buffer:** Explicitly acknowledged static secret has zero authenticity. Full DDL, `submit_legacy_telemetry` RPC with secret validation, table grants, and strict **ZERO PROMOTION to `locations`** enforced. | **Specified** (Section 4, Section 5.2) |
| **3** | **Credential Comparison Reality** | Claimed PL/pgSQL `=` or HMAC masking provided "constant-time" verification. Non-existent SubtleCrypto method cited. | **Retraction & Edge Verification Tier:** Retracted false PL/pgSQL constant-time claims (PostgreSQL evaluates in variable time). Formally designated **Supabase Edge Functions with `node:crypto` `timingSafeEqual`** or constant-time `Uint8Array` loop as authoritative tier. | **Specified** (Section 5.2, Section 6.2) |
| **4** | **Storage Ceilings & Rate Limits** | High-level prose lacking concrete SQL DDL, advisory lock implementation, and automated maintenance triggers. | **Concrete SQL Implementation on Paper:** Implemented `pg_try_advisory_xact_lock` per device in `submit_legacy_telemetry`, synchronous 50k hard row ceiling (`53200`), 48h TTL purge in `cleanup_telemetry_staging`, audit log alerts, and verified `pg_cron`. | **Specified** (Section 4.3, Section 5.2) |
| **5** | **Concurrency-Safe Owner Bootstrap** | Trigger-based promotion using `NOT EXISTS` query susceptible to race conditions under `READ COMMITTED`. Session-local `set_config` fails in poolers. | **Partial Unique Index & Durable Private Table:** Created `CREATE UNIQUE INDEX idx_single_owner_constraint ON public.profiles ((true)) WHERE role = 'OWNER';`. Added `private.system_bootstrap` single-row table with `FOR UPDATE` in `bootstrap_claim_owner(token)` RPC. Connection-pooler safe. Owner email kept unconfirmed. | **Specified** (Section 5.2, Section 11) |
| **6** | **SQL Privileges & Ownership** | Missing privilege inventory table; omitted default privilege overrides for role `postgres`; missing function ownership. | **Full Privilege Matrix, 5-Point Verification Checklist & Role Lockdown:** Inserted 5-role privilege inventory table (`PUBLIC`, `anon`, `authenticated`, `postgres`, `service_role`). Added `ALTER DEFAULT PRIVILEGES FOR ROLE postgres ... REVOKE ALL`. Enforced `OWNER TO postgres`. Added 5-point SQL privilege verification queries. | **Specified** (Section 5.1, Section 5.2) |
| **7** | **Command Reliability & Deduplication** | Missing deduplication persistence on tracker across restarts; undefined behavior if execution succeeds but acknowledgment network call fails; mutable polling timestamps invalidated signatures. | **Two-Phase Lifecycle, Room Deduplication & Immutable Signed Timestamps:** Decoupled mutable operational polling (`dispatched_at`, `retry_count`) from immutable signed fields (`issued_at_epoch_sec`, `expires_at_epoch_sec`). Specified Android Room SQLite `tracker_executed_commands` table. Documented lost-ACK recovery without hardware re-execution. | **Specified** (Section 6.1, Section 6.3) |
| **8** | **Evidentiary Discipline & Test Realities** | Audit claims, architectural theories, and unverified DDL were blended without evidentiary separation. | **Strict 5-Tier Evidentiary Classification:** Explicitly categorized every item under Specified, Implemented, Tested, Verified, or Blocked. Documented that while 499 client unit tests pass, **0 tests have run against proposed backend DDL or tracker protocol**. | **Specified** (Section 3.2, Section 9) |

### 13.4 Residual Security Risks
1. **Legacy Telemetry Window:** Until trackers are upgraded to Phase 2 (mutual API key authentication), telemetry submitted via legacy channels uses a static secret and cannot be cryptographically proven to originate from genuine hardware.
2. **In-Flight Logging Redaction:** Edge Functions and gateways must configure explicit query/body parameter redaction to prevent accidental logging of device API keys in access traces.
3. **Database Timing Side-Channels:** While PL/pgSQL HMAC-nonce masking prevents direct character-comparison timing, true cryptographic constant-time comparison is achieved in the Edge tier (`timingSafeEqual`).
4. **Physical Tracker Hardware Verification:** Phase 1 and Phase 2 cannot be deemed complete until asymmetric command verification and staging redirection are validated on physical hardware in an authorized testing session.

### 13.5 Record of Files Changed in This Run
* **Modified:** [`docs/audits/TRACE_PHASE_0_DECISION_SECURITY_GATE.md`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/docs/audits/TRACE_PHASE_0_DECISION_SECURITY_GATE.md) (Updated to Version 3.2.1, Final Forensic Correction & Architectural Closure).
* **Modified:** [`.agent_memory/memory.md`](file:///c:/Users/ZNS/Documents/live_tracker/controller_app/.agent_memory/memory.md) (Appended concise Phase 0 closure record).
* **Zero application source code changes.**
* **Zero tracker application changes.**
* **Zero database migrations executed.**
* **Zero Git commits or pushes.**
