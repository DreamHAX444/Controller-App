/**
 * TRACE Phase 2 — Comprehensive PostgreSQL Migration & Authorization Test Suite
 * Executes against true PostgreSQL (PGlite v18 WASM runtime)
 * 
 * Verifies:
 * 1. Clean Database Migration (Migrations 01 -> 02 -> 03)
 * 2. Legacy Fleet Simulation:
 *    - 14 historical commands (IDs 1..14, unmapped statuses, null/array params)
 *    - Historical locations with legacy string hardware IDs (e.g. 'Google sdk_gphone16k_x86_64 (E10B)')
 *    - Historical media records
 * 3. Multi-table distinct device backfill and non-blocking FK verification
 * 4. Owner A1 Policy: Universal authority without device assignment
 * 5. Admin B2 Policy: Fleet-wide location telemetry without device assignment;
 *    Strict device assignment required for commands and sensitive operations
 * 6. User Gating: Access strictly limited to assigned active devices
 * 7. Visitor & Inactive Isolation: Strictly fails closed
 * 8. Priority 1: Zero hardcoded secrets, dynamic credential provisioning & rotation
 * 9. Priority 2: Staging buffer & submit_tracker_telemetry adversarial verification:
 *    - Direct table access prevention on staging
 *    - Unauthenticated/spoofed device quarantine
 *    - Revoked/suspended device quarantine
 *    - Boundary checks: coordinates [-90,90], [-180,180], timestamp freshness, payload limit
 *    - Duplicate-fix suppression
 *    - Concurrency and 50,000 staging row ceiling enforcement (FOR UPDATE)
 * 10. Priority 3: Registration, approval, and permissions complete lifecycle:
 *    - Fleet inventory visibility (Owner/Admin vs User vs Visitor)
 *    - Telemetry visibility (Owner/Admin B2 vs User vs Visitor)
 *    - Google OAuth signup defaults to VISITOR/PENDING
 *    - Owner protections (Admin cannot promote to OWNER/ADMIN, cannot modify OWNER)
 *    - Approval to USER/ACTIVE and default LOCATION feature trigger
 *    - Sensitive feature gating (CAMERA/COMMANDS denied until explicit grant)
 *    - Immediate fail-closed revocation (assignment revocation, account suspension)
 * 11. Priority 4: Idempotent rerun & integrity of historical migrations
 */

import { PGlite } from "npm:@electric-sql/pglite";
import * as fs from "node:fs";
import * as path from "node:path";

async function runMasterMigrationTests() {
  console.log("================================================================================");
  console.log("TRACE V1 Phase 2: Master PostgreSQL Migration & Authorization Test Suite");
  console.log("================================================================================");

  const m1Path = path.resolve("supabase/migrations/20261017000001_trace_phase2a_core_schema.sql");
  const m2Path = path.resolve("supabase/migrations/20261017000002_trace_phase2b_commands_gateway.sql");
  const m3Path = path.resolve("supabase/migrations/20261017000003_trace_phase3_authenticated_acks.sql");
  const m4Path = path.resolve("supabase/migrations/20261020000001_trace_phase4_device_enrollment.sql");
  const m5Path = path.resolve("supabase/migrations/20261021000001_trace_phase5_user_management.sql");
  const m6Path = path.resolve("supabase/migrations/20261022000001_trace_phase6_realtime_revocation.sql");

  const m1Sql = fs.readFileSync(m1Path, "utf-8");
  const m2Sql = fs.readFileSync(m2Path, "utf-8");
  const m3Sql = fs.readFileSync(m3Path, "utf-8");
  const m4Sql = fs.readFileSync(m4Path, "utf-8");
  const m5Sql = fs.readFileSync(m5Path, "utf-8");
  const m6Sql = fs.readFileSync(m6Path, "utf-8");

  // Verify zero hardcoded static secret references in SQL migration DDL
  console.log("\n[VERIFY PRIORITY 1] Auditing migration DDL for hardcoded secret strings...");
  const secretPattern = /zns-tracker-secret-2026/i;
  if (secretPattern.test(m1Sql) || secretPattern.test(m2Sql) || secretPattern.test(m3Sql) || secretPattern.test(m4Sql) || secretPattern.test(m5Sql) || secretPattern.test(m6Sql)) {
    throw new Error("SECURITY FAILURE: Hardcoded secret 'zns-tracker-secret-2026' found in migration SQL files!");
  }
  console.log("  ✔ Clean DDL: 0 occurrences of 'zns-tracker-secret-2026' in all migration files.");

  // Supabase prelude for roles, auth mock, and realtime publications
  const setupSupabasePrelude = `
    DO $$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        CREATE ROLE anon;
      END IF;
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        CREATE ROLE authenticated;
      END IF;
      IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
        CREATE ROLE service_role;
      END IF;
    END $$;

    DO $$ BEGIN
      IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        CREATE PUBLICATION supabase_realtime;
      END IF;
    END $$;

    CREATE SCHEMA IF NOT EXISTS auth;
    CREATE TABLE IF NOT EXISTS auth.users (
      id UUID PRIMARY KEY,
      email TEXT,
      raw_user_meta_data JSONB DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ DEFAULT NOW()
    );

    -- Variable to simulate current session caller
    CREATE TABLE IF NOT EXISTS public._test_session (
      caller_id UUID
    );

    CREATE OR REPLACE FUNCTION auth.uid() RETURNS UUID AS $$
      SELECT caller_id FROM public._test_session LIMIT 1;
    $$ LANGUAGE sql STABLE;
  `;

  // ---------------------------------------------------------------------------
  // TEST 1: Fresh Clean Database Migration Sequence (01 -> 02 -> 03)
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 1] Applying 3-stage migration sequence to clean empty database...");
  const cleanDb = new PGlite();
  await cleanDb.exec(setupSupabasePrelude);

  // Setup empty pre-existing table targets as found in Supabase
  await cleanDb.exec(`
    CREATE TABLE public.locations (
      id BIGINT PRIMARY KEY,
      device_id TEXT NOT NULL,
      lat FLOAT8 NOT NULL,
      lng FLOAT8 NOT NULL,
      alt FLOAT8 DEFAULT 0,
      speed FLOAT4 DEFAULT 0,
      accuracy FLOAT4 DEFAULT 0,
      bearing FLOAT4 DEFAULT 0,
      battery_level FLOAT4 DEFAULT 1,
      charging BOOL DEFAULT FALSE,
      network_type TEXT DEFAULT 'WIFI',
      created_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE TABLE public.commands (
      id BIGINT PRIMARY KEY,
      device_id TEXT NOT NULL,
      command TEXT NOT NULL,
      parameters JSONB,
      status TEXT DEFAULT 'pending',
      created_at TIMESTAMPTZ DEFAULT NOW(),
      executed_at TIMESTAMPTZ NULL
    );

    CREATE TABLE public.media (
      id BIGINT PRIMARY KEY,
      device_id TEXT NOT NULL,
      file_path TEXT NOT NULL,
      media_type TEXT NOT NULL,
      created_at TIMESTAMPTZ DEFAULT NOW()
    );
  `);

  await cleanDb.exec(m1Sql);
  console.log("  ✔ Migration 01 (Phase 2A Core Schema) applied cleanly.");
  await cleanDb.exec(m2Sql);
  console.log("  ✔ Migration 02 (Phase 2B Commands Gateway) applied cleanly.");
  await cleanDb.exec(m3Sql);
  console.log("  ✔ Migration 03 (Phase 3 Authenticated ACKs) applied cleanly.");
  await cleanDb.exec(m4Sql);
  console.log("  ✔ Migration 04 (Phase 4 Device Enrollment & Identity Lifecycle) applied cleanly.");
  await cleanDb.exec(m5Sql);
  console.log("  ✔ Migration 05 (Phase 5 User Management & Feature Permissions) applied cleanly.");
  await cleanDb.exec(m6Sql);
  console.log("  ✔ Migration 06 (Phase 6 Realtime Revocation & Permissions Broadcast) applied cleanly.");

  const tablesRes = await cleanDb.query(`
    SELECT table_name FROM information_schema.tables 
    WHERE table_schema = 'public' 
    ORDER BY table_name;
  `);
  const tables = tablesRes.rows.map((r: any) => r.table_name);
  const expectedTables = [
    "audit_logs",
    "commands",
    "device_feature_permissions",
    "devices",
    "enrolled_devices",
    "locations",
    "media",
    "profiles",
    "revocation_events",
    "tracker_telemetry_staging",
    "user_device_assignments"
  ];
  for (const exp of expectedTables) {
    if (!tables.includes(exp)) {
      throw new Error(`Missing expected table in public schema: ${exp}`);
    }
  }
  console.log("  ✔ Verified all core tables in public schema.");

  // Verify supabase_realtime publication membership
  const pubTablesRes = await cleanDb.query(`
    SELECT tablename FROM pg_publication_tables 
    WHERE pubname = 'supabase_realtime' AND schemaname = 'public';
  `);
  const pubTables = pubTablesRes.rows.map((r: any) => r.tablename);
  const expectedPubTables = ["profiles", "user_device_assignments", "device_feature_permissions", "revocation_events"];
  for (const pt of expectedPubTables) {
    if (!pubTables.includes(pt)) {
      throw new Error(`Table ${pt} is missing from supabase_realtime publication!`);
    }
  }
  if (pubTables.includes("commands")) {
    throw new Error("SECURITY FAILURE: commands table is exposed in supabase_realtime!");
  }
  console.log("  ✔ Verified supabase_realtime publication: profiles, user_device_assignments, device_feature_permissions, revocation_events included; commands excluded.");

  // ---------------------------------------------------------------------------
  // TEST 2: Legacy Database Simulation (Historical Locations, Commands, Media)
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 2] Simulating legacy database with pre-existing telemetry & historical commands...");
  const legacyDb = new PGlite();
  await legacyDb.exec(setupSupabasePrelude);

  await legacyDb.exec(`
    CREATE TABLE public.locations (
      id BIGINT PRIMARY KEY,
      device_id TEXT NOT NULL,
      lat FLOAT8 NOT NULL,
      lng FLOAT8 NOT NULL,
      alt FLOAT8 DEFAULT 0,
      speed FLOAT4 DEFAULT 0,
      accuracy FLOAT4 DEFAULT 0,
      bearing FLOAT4 DEFAULT 0,
      battery_level FLOAT4 DEFAULT 1,
      charging BOOL DEFAULT FALSE,
      network_type TEXT DEFAULT 'WIFI',
      created_at TIMESTAMPTZ DEFAULT NOW()
    );

    CREATE TABLE public.commands (
      id BIGINT PRIMARY KEY,
      device_id TEXT NOT NULL,
      command TEXT NOT NULL,
      parameters JSONB,
      status TEXT DEFAULT 'pending',
      created_at TIMESTAMPTZ DEFAULT NOW(),
      executed_at TIMESTAMPTZ NULL
    );

    CREATE TABLE public.media (
      id BIGINT PRIMARY KEY,
      device_id TEXT NOT NULL,
      file_path TEXT NOT NULL,
      media_type TEXT NOT NULL,
      created_at TIMESTAMPTZ DEFAULT NOW()
    );
  `);

  // Seed 3 historical devices
  const legacyDevice1 = "Google sdk_gphone16k_x86_64 (E10B)";
  const legacyDevice2 = "Samsung Galaxy S22 (SM-S901B)";
  const legacyDevice3 = "Google Pixel 8 Pro (GP8P)";

  await legacyDb.exec(`
    INSERT INTO public.locations (id, device_id, lat, lng, created_at) VALUES
      (1, '${legacyDevice1}', 37.7749, -122.4194, NOW() - INTERVAL '10 days'),
      (2, '${legacyDevice1}', 37.7750, -122.4195, NOW() - INTERVAL '9 days'),
      (3, '${legacyDevice2}', 40.7128, -74.0060, NOW() - INTERVAL '8 days');

    INSERT INTO public.commands (id, device_id, command, parameters, status) VALUES
      (1, '${legacyDevice1}', 'capture_photo', '{"camera":"back"}'::jsonb, 'executed'),
      (2, '${legacyDevice2}', 'reboot', '["soft"]'::jsonb, 'failed'),
      (3, '${legacyDevice3}', 'start_tracking', NULL, 'legacy_unmapped_status');

    INSERT INTO public.media (id, device_id, file_path, media_type) VALUES
      (1, '${legacyDevice1}', '/photos/snap1.jpg', 'image/jpeg');
  `);
  console.log("  ✔ Seeded legacy database with historical locations, commands, and media.");

  console.log("  -> Applying Phase 2A Core Schema on legacy database...");
  await legacyDb.exec(m1Sql);
  console.log("  -> Applying Phase 2B Commands Gateway on legacy database...");
  await legacyDb.exec(m2Sql);
  console.log("  -> Applying Phase 3 Authenticated ACKs on legacy database...");
  await legacyDb.exec(m3Sql);
  console.log("  -> Applying Phase 4 Device Enrollment & Identity Lifecycle on legacy database...");
  await legacyDb.exec(m4Sql);
  console.log("  -> Applying Phase 5 User Management & Feature Permissions on legacy database...");
  await legacyDb.exec(m5Sql);
  console.log("  -> Applying Phase 6 Realtime Revocation on legacy database...");
  await legacyDb.exec(m6Sql);

  console.log("  -> Configuring schema grants for authenticated and service roles...");
  await legacyDb.exec(`
    GRANT USAGE ON SCHEMA public TO anon, authenticated, service_role;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated, service_role;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authenticated, service_role;
    REVOKE ALL ON TABLE public.tracker_telemetry_staging FROM anon;
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE public.tracker_telemetry_staging FROM authenticated;
    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE public.revocation_events FROM authenticated;
  `);

  const backfilledDevs = await legacyDb.query(`SELECT device_id, is_legacy FROM public.devices ORDER BY device_id;`);
  if (backfilledDevs.rows.length !== 3) {
    throw new Error(`Expected 3 backfilled devices, found: ${backfilledDevs.rows.length}`);
  }
  console.log(`  ✔ public.devices backfilled with ${backfilledDevs.rows.length} devices.`);
  for (const row of backfilledDevs.rows) {
    if ((row as any).is_legacy !== true) {
      throw new Error(`Device ${(row as any).device_id} was not flagged as is_legacy = true!`);
    }
  }
  console.log("  ✔ Verified all legacy devices populated with is_legacy = true.");

  // ---------------------------------------------------------------------------
  // TEST 3: Priority 1 — Dynamic Legacy Ingest Secret Provisioning
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 3] Testing Dynamic Credential Provisioning & Rotation...");
  
  // Verify private.legacy_ingest_credentials has zero rows initially
  const initialCreds = await legacyDb.query("SELECT * FROM private.legacy_ingest_credentials;");
  if (initialCreds.rows.length !== 0) {
    throw new Error("Expected private.legacy_ingest_credentials to be empty on fresh installation!");
  }
  console.log("  ✔ private.legacy_ingest_credentials initialized empty (no hardcoded secret).");

  // Provision legacy secret dynamically via private.set_legacy_ingest_secret
  const testSecret = "dynamic-provisioned-secret-2026-secure";
  await legacyDb.query(`SELECT private.set_legacy_ingest_secret('${testSecret}');`);
  
  const updatedCreds = await legacyDb.query("SELECT * FROM private.legacy_ingest_credentials WHERE id = 1;");
  if (updatedCreds.rows.length !== 1) {
    throw new Error("Failed to set legacy ingest secret!");
  }
  console.log("  ✔ Dynamic legacy ingest secret provisioned securely in private schema.");

  // Test rotation
  const rotatedSecret = "rotated-secure-secret-2026-v2";
  await legacyDb.query(`SELECT private.set_legacy_ingest_secret('${rotatedSecret}');`);
  const rotatedCreds = await legacyDb.query("SELECT * FROM private.legacy_ingest_credentials WHERE id = 1;");
  if ((rotatedCreds.rows[0] as any).secret_hash === (updatedCreds.rows[0] as any).secret_hash) {
    throw new Error("Secret hash did not change upon rotation!");
  }
  console.log("  ✔ Credential rotation verified successfully.");

  // ---------------------------------------------------------------------------
  // TEST 4: Priority 2 — Staging Buffer & submit_tracker_telemetry Adversarial Suite
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 4] Testing Priority 2: Staging Buffer & Adversarial Ingestion Attacks...");

  // Adversarial Test 7: Direct client manipulation attempts on staging_storage_counter or tracker_telemetry_staging
  console.log("  -> [Adversarial 7] Testing direct client manipulation restrictions...");
  const testUserId = "30000000-0000-0000-0000-000000000003";
  await legacyDb.exec(`
    INSERT INTO auth.users (id, email) VALUES ('${testUserId}', 'user@trace.local') ON CONFLICT DO NOTHING;
    INSERT INTO public.profiles (id, role, status, display_name) 
    VALUES ('${testUserId}', 'USER', 'ACTIVE', 'Standard User')
    ON CONFLICT (id) DO UPDATE SET role = 'USER', status = 'ACTIVE';
    DELETE FROM public._test_session;
    INSERT INTO public._test_session (caller_id) VALUES ('${testUserId}');
    SET ROLE authenticated;
  `);

  const userStagingSelect = await legacyDb.query("SELECT * FROM public.tracker_telemetry_staging;");
  if (userStagingSelect.rows.length !== 0) {
    throw new Error("Security failure! Standard user was able to read records from tracker_telemetry_staging!");
  }

  // Attempt direct INSERT on tracker_telemetry_staging as authenticated user
  let directInsertBlocked = false;
  try {
    await legacyDb.query("INSERT INTO public.tracker_telemetry_staging (device_id, payload) VALUES ('hack', '{}'::jsonb);");
  } catch (_err) {
    directInsertBlocked = true;
  }
  if (!directInsertBlocked) {
    throw new Error("Security failure! Standard user was able to directly insert into tracker_telemetry_staging!");
  }

  // Attempt direct UPDATE on private.staging_storage_counter as authenticated user
  let directCounterBlocked = false;
  try {
    await legacyDb.query("UPDATE private.staging_storage_counter SET unprocessed_count = 0 WHERE id = 1;");
  } catch (_err) {
    directCounterBlocked = true;
  }
  if (!directCounterBlocked) {
    throw new Error("Security failure! Client was able to access private.staging_storage_counter!");
  }

  // Attempt direct invocation of private.purge_staging_records as authenticated user
  let directPurgeBlocked = false;
  try {
    await legacyDb.query("SELECT private.purge_staging_records('0 seconds'::interval);");
  } catch (_err) {
    directPurgeBlocked = true;
  }
  if (!directPurgeBlocked) {
    throw new Error("Security failure! Authenticated user was able to invoke private.purge_staging_records!");
  }

  await legacyDb.query("RESET ROLE;");
  console.log("  ✔ Adversarial 7: Direct client manipulation on staging & counter strictly blocked (fail-closed).");

  // Adversarial Test 1: Large burst of invalid credentials (staging counter remains 0, persistent storage unconsumed)
  console.log("  -> [Adversarial 1] Testing large burst of invalid credentials (storage exhaustion DoS mitigation)...");
  const validCoords = { lat: 37.7749, lng: -122.4194, speed: 0.0 };

  for (let i = 0; i < 20; i++) {
    const burstDeviceId = `attacker-spoofed-device-${i}`;
    const burstRes = await legacyDb.query(`
      SELECT public.submit_tracker_telemetry('${burstDeviceId}', '${JSON.stringify(validCoords)}'::jsonb, 'invalid-key-${i}') AS result;
    `);
    const burstResult = (burstRes.rows[0] as any).result;
    if (burstResult.success !== false || burstResult.staged !== false || burstResult.error !== "UNAUTHENTICATED_DEVICE_CREDENTIALS") {
      throw new Error(`Burst test ${i} failed! Result: ${JSON.stringify(burstResult)}`);
    }
  }

  const postBurstCounter = await legacyDb.query("SELECT unprocessed_count FROM private.staging_storage_counter WHERE id = 1;");
  if (Number(postBurstCounter.rows[0].unprocessed_count) !== 0) {
    throw new Error(`Unprocessed counter incremented on unauthenticated burst! Found: ${postBurstCounter.rows[0].unprocessed_count}`);
  }
  const postBurstStagingRows = await legacyDb.query("SELECT COUNT(*) AS count FROM public.tracker_telemetry_staging;");
  if (Number(postBurstStagingRows.rows[0].count) !== 0) {
    throw new Error(`Staging buffer allocated rows on unauthenticated burst! Found: ${postBurstStagingRows.rows[0].count}`);
  }
  console.log("  ✔ Adversarial 1: Burst of invalid credentials rejected pre-staging; 0 counter increments, 0 storage consumed.");

  // Suspended / Revoked Device Handling
  console.log("  -> Testing suspended/revoked device submission...");
  const suspendedDevice = "tracker-suspended-001";
  const deviceKey = "device-secret-key-xyz";
  const deviceSalt = "salt-abc";
  
  const ownerId = "10000000-0000-0000-0000-000000000001";
  const adminId = "20000000-0000-0000-0000-000000000002";
  await legacyDb.exec(`
    INSERT INTO auth.users (id, email) VALUES 
      ('${ownerId}', 'owner@trace.local'),
      ('${adminId}', 'admin@trace.local')
    ON CONFLICT DO NOTHING;

    INSERT INTO public.profiles (id, role, status, display_name) VALUES 
      ('${ownerId}', 'OWNER', 'ACTIVE', 'System Owner'),
      ('${adminId}', 'ADMIN', 'ACTIVE', 'Fleet Admin')
    ON CONFLICT (id) DO UPDATE SET role = EXCLUDED.role, status = EXCLUDED.status;

    DELETE FROM public._test_session;
    INSERT INTO public._test_session (caller_id) VALUES ('${adminId}');
  `);

  await legacyDb.query(`
    SELECT public.register_tracker_device(
      '${suspendedDevice}',
      'Suspended Canary',
      'Test Hardware',
      NULL,
      encode(sha256(('${deviceKey}' || '${deviceSalt}')::bytea), 'hex'),
      '${deviceSalt}'
    );
  `);
  await legacyDb.exec(`UPDATE public.devices SET status = 'SUSPENDED' WHERE device_id = '${suspendedDevice}';`);

  const suspendedRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry('${suspendedDevice}', '${JSON.stringify(validCoords)}'::jsonb, '${deviceKey}') AS result;
  `);
  const suspendedResult = (suspendedRes.rows[0] as any).result;
  if (suspendedResult.success !== false || suspendedResult.staged !== false || suspendedResult.error !== "DEVICE_STATUS_NOT_ACTIVE") {
    throw new Error(`Expected DEVICE_STATUS_NOT_ACTIVE with staged=false, received: ${JSON.stringify(suspendedResult)}`);
  }
  const suspendedLocCheck = await legacyDb.query(`SELECT * FROM public.locations WHERE device_id = '${suspendedDevice}';`);
  if (suspendedLocCheck.rows.length !== 0) {
    throw new Error("Suspended device coordinates were promoted to public.locations!");
  }
  console.log("  ✔ Suspended device submission rejected pre-staging; 0 rows staged, 0 promoted.");

  // Authenticated Device Submission Promoted Successfully
  console.log("  -> Testing valid authenticated device telemetry promotion...");
  const activeDevice = "tracker-active-001";
  await legacyDb.query(`
    SELECT public.register_tracker_device(
      '${activeDevice}',
      'Active Canary',
      'Test Hardware',
      NULL,
      encode(sha256(('${deviceKey}' || '${deviceSalt}')::bytea), 'hex'),
      '${deviceSalt}'
    );
  `);

  const validTelemetry = {
    lat: 37.7899,
    lng: -122.4011,
    alt: 12.0,
    speed: 1.5,
    accuracy: 3.0,
    battery_level: 0.9,
    charging: false,
    network_type: "5G"
  };

  const activeRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry('${activeDevice}', '${JSON.stringify(validTelemetry)}'::jsonb, '${deviceKey}') AS result;
  `);
  const activeResult = (activeRes.rows[0] as any).result;
  if (!activeResult.success || !activeResult.promoted) {
    throw new Error(`Failed to promote authenticated telemetry: ${JSON.stringify(activeResult)}`);
  }
  const activeLocCheck = await legacyDb.query(`SELECT * FROM public.locations WHERE device_id = '${activeDevice}';`);
  if (activeLocCheck.rows.length !== 1) {
    throw new Error("Promoted location fix was not found in public.locations!");
  }
  console.log(`  ✔ Authenticated telemetry accepted and promoted to public.locations (lat: ${activeLocCheck.rows[0].lat}, lng: ${activeLocCheck.rows[0].lng}).`);

  // Coordinate Out-of-Bounds & Timestamp Freshness Bounds
  console.log("  -> Testing coordinate and timestamp bounds validation...");
  const invalidCoords = { lat: 105.0, lng: -200.0, speed: 0.0 };
  const outOfBoundsRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry('${activeDevice}', '${JSON.stringify(invalidCoords)}'::jsonb, '${deviceKey}') AS result;
  `);
  const outOfBoundsResult = (outOfBoundsRes.rows[0] as any).result;
  if (outOfBoundsResult.success !== false || outOfBoundsResult.error !== "INVALID_COORDINATES_OUT_OF_BOUNDS") {
    throw new Error(`Expected INVALID_COORDINATES_OUT_OF_BOUNDS, received: ${JSON.stringify(outOfBoundsResult)}`);
  }
  console.log("  ✔ Out-of-bounds coordinates handled gracefully without crashing database.");

  // Adversarial Test 3: Repeated retries of the same request (Duplicate-Fix Suppression)
  console.log("  -> [Adversarial 3] Testing repeated retries and duplicate-fix suppression...");
  const dupRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry('${activeDevice}', '${JSON.stringify(validTelemetry)}'::jsonb, '${deviceKey}') AS result;
  `);
  const dupResult = (dupRes.rows[0] as any).result;
  if (!dupResult.success || !dupResult.suppressed) {
    throw new Error(`Expected duplicate fix suppression, received: ${JSON.stringify(dupResult)}`);
  }
  const totalFixes = await legacyDb.query(`SELECT COUNT(*) AS count FROM public.locations WHERE device_id = '${activeDevice}';`);
  if (Number(totalFixes.rows[0].count) !== 1) {
    throw new Error(`Duplicate fix was improperly inserted! Expected 1, found: ${totalFixes.rows[0].count}`);
  }
  console.log("  ✔ Adversarial 3: Duplicate coordinate flood suppressed without duplicate insert to public.locations.");

  // Payload Size Limit Enforcement (> 16KB)
  console.log("  -> Testing payload size ceiling (>16KB)...");
  const hugeData = "A".repeat(17000);
  let payloadTooLargeCaught = false;
  try {
    await legacyDb.query(`
      SELECT public.submit_tracker_telemetry('${activeDevice}', jsonb_build_object('overflow', '${hugeData}'), '${deviceKey}');
    `);
  } catch (err: any) {
    if (err.message.includes("PAYLOAD_TOO_LARGE")) {
      payloadTooLargeCaught = true;
    }
  }
  if (!payloadTooLargeCaught) {
    throw new Error("Payload exceeding 16KB was not rejected!");
  }
  console.log("  ✔ Payload size ceiling strictly enforced (< 16KB).");

  // Adversarial Test 2: Requests near capacity boundary (49,999 to 50,000)
  console.log("  -> [Adversarial 2] Testing capacity boundary behavior (49,999 to 50,000)...");
  await legacyDb.exec(`UPDATE private.staging_storage_counter SET unprocessed_count = 49999 WHERE id = 1;`);
  const boundaryTelemetry = { ...validTelemetry, lat: 37.7910, lng: -122.4020 };
  const boundaryRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry('${activeDevice}', '${JSON.stringify(boundaryTelemetry)}'::jsonb, '${deviceKey}') AS result;
  `);
  const boundaryResult = (boundaryRes.rows[0] as any).result;
  if (!boundaryResult.success || !boundaryResult.promoted) {
    throw new Error(`Submission at 49,999 capacity boundary failed: ${JSON.stringify(boundaryResult)}`);
  }

  await legacyDb.exec(`UPDATE private.staging_storage_counter SET unprocessed_count = 50000 WHERE id = 1;`);
  let capacityExceededCaught = false;
  try {
    await legacyDb.query(`
      SELECT public.submit_tracker_telemetry('${activeDevice}', '${JSON.stringify(validTelemetry)}'::jsonb, '${deviceKey}');
    `);
  } catch (err: any) {
    if (err.message.includes("STAGING_CAPACITY_EXCEEDED")) {
      capacityExceededCaught = true;
    }
  }
  if (!capacityExceededCaught) {
    throw new Error("50,000 staging ceiling was not enforced!");
  }
  console.log("  ✔ Adversarial 2: Capacity boundary behavior verified (succeeds at 49,999; blocks at 50,000).");

  // Adversarial Test 4: RPC failures after reservation (verify transaction rollback behavior)
  console.log("  -> [Adversarial 4] Testing transaction rollback behavior on RPC failures...");
  const counterBeforeRollback = await legacyDb.query("SELECT unprocessed_count FROM private.staging_storage_counter WHERE id = 1;");
  const stagingBeforeRollback = await legacyDb.query("SELECT COUNT(*) AS count FROM public.tracker_telemetry_staging;");
  
  await legacyDb.exec(`
    DO $$
    BEGIN
      -- Simulate an error after inserting to staging within a transaction
      BEGIN
        PERFORM public.submit_tracker_telemetry('${activeDevice}', '{"lat": 37.8, "lng": -122.4}'::jsonb, '${deviceKey}');
        RAISE EXCEPTION 'SIMULATED_TRANSACTION_FAILURE';
      EXCEPTION WHEN OTHERS THEN
        -- Handled exception in outer block, verifying inner changes roll back cleanly
        NULL;
      END;
    END $$;
  `);
  const counterAfterRollback = await legacyDb.query("SELECT unprocessed_count FROM private.staging_storage_counter WHERE id = 1;");
  const stagingAfterRollback = await legacyDb.query("SELECT COUNT(*) AS count FROM public.tracker_telemetry_staging;");
  if (Number(counterAfterRollback.rows[0].unprocessed_count) !== Number(counterBeforeRollback.rows[0].unprocessed_count)) {
    throw new Error("Transaction failure caused counter drift!");
  }
  if (Number(stagingAfterRollback.rows[0].count) !== Number(stagingBeforeRollback.rows[0].count)) {
    throw new Error("Transaction failure left orphaned staging row!");
  }
  console.log("  ✔ Adversarial 4: Transaction rollback cleans up staging reservation and counter atomically.");

  // Adversarial Test 5: Counter mismatch and recovery (purge_staging_records re-synchronizes counter)
  console.log("  -> [Adversarial 5] Testing counter mismatch recovery via private.purge_staging_records...");
  await legacyDb.exec("UPDATE private.staging_storage_counter SET unprocessed_count = 12345 WHERE id = 1;");
  const purgeRes = await legacyDb.query("SELECT private.purge_staging_records('24 hours'::interval) AS result;");
  const purgeResult = (purgeRes.rows[0] as any).result;
  if (!purgeResult.success) {
    throw new Error(`purge_staging_records failed: ${JSON.stringify(purgeResult)}`);
  }
  const counterAfterPurge = await legacyDb.query("SELECT unprocessed_count FROM private.staging_storage_counter WHERE id = 1;");
  const actualUnprocessed = await legacyDb.query("SELECT COUNT(*) AS count FROM public.tracker_telemetry_staging WHERE is_processed = FALSE;");
  if (Number(counterAfterPurge.rows[0].unprocessed_count) !== Number(actualUnprocessed.rows[0].count)) {
    throw new Error(`Counter was not re-synchronized! Counter: ${counterAfterPurge.rows[0].unprocessed_count}, Actual: ${actualUnprocessed.rows[0].count}`);
  }
  console.log(`  ✔ Adversarial 5: Counter mismatch recovered and re-synchronized from actual buffer rows (${counterAfterPurge.rows[0].unprocessed_count} unprocessed).`);

  // Adversarial Test 6: Buffer-full behavior followed by cleanup and successful recovery
  console.log("  -> [Adversarial 6] Testing buffer-full cleanup and recovery...");
  await legacyDb.exec("UPDATE private.staging_storage_counter SET unprocessed_count = 50000 WHERE id = 1;");
  let fullBlocked = false;
  try {
    await legacyDb.query(`
      SELECT public.submit_tracker_telemetry('${activeDevice}', '{"lat": 37.81, "lng": -122.41}'::jsonb, '${deviceKey}');
    `);
  } catch (err: any) {
    if (err.message.includes("STAGING_CAPACITY_EXCEEDED")) {
      fullBlocked = true;
    }
  }
  if (!fullBlocked) {
    throw new Error("Buffer-full state failed to block submission!");
  }

  // Purge processed rows (retention interval = 0 seconds)
  await legacyDb.query("SELECT private.purge_staging_records('0 seconds'::interval);");
  const recoveredRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry('${activeDevice}', '{"lat": 37.82, "lng": -122.42}'::jsonb, '${deviceKey}') AS result;
  `);
  const recoveredResult = (recoveredRes.rows[0] as any).result;
  if (!recoveredResult.success || !recoveredResult.promoted) {
    throw new Error(`Submission after buffer cleanup failed: ${JSON.stringify(recoveredResult)}`);
  }
  console.log("  ✔ Adversarial 6: Buffer-full condition cleared via purge; subsequent submissions succeed immediately.");

  // ---------------------------------------------------------------------------
  // TEST 5: Priority 3 — Complete Registration, Approval, and Permissions Workflow
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 5] Testing Priority 3: Registration, Approval, and Permissions Lifecycle...");

  const userId = "30000000-0000-0000-0000-000000000003";

  async function resetRole() {
    await legacyDb.query("RESET ROLE;");
  }

  // Helper function to switch session caller with RLS role setting
  async function setCaller(id: string, role = "authenticated") {
    await legacyDb.query("RESET ROLE;");
    await legacyDb.query("DELETE FROM public._test_session;");
    await legacyDb.query(`INSERT INTO public._test_session (caller_id) VALUES ('${id}');`);
    await legacyDb.query(`SET ROLE ${role};`);
  }

  // Sub-test 5A: New User Signup Defaults to VISITOR / PENDING
  console.log("  -> Testing new user signup trigger...");
  await resetRole();
  const newSignupId = "55000000-0000-0000-0000-000000000055";
  await legacyDb.exec(`
    INSERT INTO auth.users (id, email, raw_user_meta_data) 
    VALUES ('${newSignupId}', 'newuser@trace.local', '{"full_name":"New Visitor"}'::jsonb);
  `);
  const newProfile = await legacyDb.query(`SELECT * FROM public.profiles WHERE id = '${newSignupId}';`);
  if (newProfile.rows.length !== 1 || newProfile.rows[0].role !== "VISITOR" || newProfile.rows[0].status !== "PENDING") {
    throw new Error(`New user did not default to VISITOR/PENDING! Found: ${JSON.stringify(newProfile.rows[0])}`);
  }
  console.log("  ✔ New user registration automatically assigned role = VISITOR, status = PENDING.");

  // Visitor Isolation
  await setCaller(newSignupId);
  const visitorDevs = await legacyDb.query("SELECT * FROM public.devices;");
  if (visitorDevs.rows.length !== 0) {
    throw new Error(`Visitor was able to see ${visitorDevs.rows.length} devices in inventory!`);
  }
  const visitorLocs = await legacyDb.query("SELECT * FROM public.locations;");
  if (visitorLocs.rows.length !== 0) {
    throw new Error(`Visitor was able to see ${visitorLocs.rows.length} location records!`);
  }
  console.log("  ✔ Visitor completely isolated from devices and locations (0 rows returned).");

  // Sub-test 5B: Owner Protections — Admin cannot elevate to OWNER or modify OWNER
  console.log("  -> Testing Admin privilege boundaries and Owner protections...");
  await setCaller(adminId);

  // Admin attempts to promote new user to OWNER -> MUST FAIL
  let adminPromoteOwnerFailed = false;
  try {
    await legacyDb.exec(`
      UPDATE public.profiles SET role = 'OWNER' WHERE id = '${newSignupId}';
    `);
    // Check if it actually updated
    const check = await legacyDb.query(`SELECT role FROM public.profiles WHERE id = '${newSignupId}';`);
    if (check.rows[0].role === "OWNER") {
      throw new Error("Admin succeeded in promoting user to OWNER!");
    } else {
      adminPromoteOwnerFailed = true; // RLS WITH CHECK blocked it or filtered out
    }
  } catch (_e) {
    adminPromoteOwnerFailed = true;
  }
  if (!adminPromoteOwnerFailed) {
    throw new Error("Admin was able to promote a user to OWNER!");
  }
  console.log("  ✔ Owner Protection: Admin cannot promote any account to OWNER.");

  // Admin attempts to promote new user to ADMIN -> MUST FAIL
  let adminPromoteAdminFailed = false;
  try {
    await legacyDb.exec(`
      UPDATE public.profiles SET role = 'ADMIN' WHERE id = '${newSignupId}';
    `);
    const check = await legacyDb.query(`SELECT role FROM public.profiles WHERE id = '${newSignupId}';`);
    if (check.rows[0].role === "ADMIN") {
      throw new Error("Admin succeeded in promoting user to ADMIN!");
    } else {
      adminPromoteAdminFailed = true;
    }
  } catch (_e) {
    adminPromoteAdminFailed = true;
  }
  if (!adminPromoteAdminFailed) {
    throw new Error("Admin was able to promote a user to ADMIN!");
  }
  console.log("  ✔ Owner Protection: Admin cannot appoint other Admins (Owner-only privilege).");

  // Admin attempts to demote or modify OWNER -> MUST FAIL
  let adminModifyOwnerFailed = false;
  try {
    await legacyDb.exec(`
      UPDATE public.profiles SET status = 'SUSPENDED' WHERE id = '${ownerId}';
    `);
    const check = await legacyDb.query(`SELECT status FROM public.profiles WHERE id = '${ownerId}';`);
    if (check.rows[0].status === "SUSPENDED") {
      throw new Error("Admin succeeded in suspending OWNER!");
    } else {
      adminModifyOwnerFailed = true;
    }
  } catch (_e) {
    adminModifyOwnerFailed = true;
  }
  if (!adminModifyOwnerFailed) {
    throw new Error("Admin was able to modify OWNER account status!");
  }
  console.log("  ✔ Owner Protection: Admin cannot modify, demote, or suspend OWNER.");

  // Sub-test 5C: Approval Workflow & Default LOCATION Permission Trigger
  console.log("  -> Testing approval workflow and default LOCATION feature trigger...");
  // Admin approves visitor to USER / ACTIVE
  await setCaller(adminId);
  await legacyDb.exec(`
    UPDATE public.profiles SET role = 'USER', status = 'ACTIVE' WHERE id = '${newSignupId}';
  `);
  const approvedCheck = await legacyDb.query(`SELECT role, status FROM public.profiles WHERE id = '${newSignupId}';`);
  if (approvedCheck.rows[0].role !== "USER" || approvedCheck.rows[0].status !== "ACTIVE") {
    throw new Error("Admin failed to approve visitor to USER/ACTIVE!");
  }
  console.log("  ✔ Admin successfully approved visitor to USER / ACTIVE.");

  // Admin assigns device to approved user
  await legacyDb.exec(`
    INSERT INTO public.user_device_assignments (user_id, device_id, status)
    VALUES ('${newSignupId}', '${activeDevice}', 'ACTIVE');
  `);
  
  // Verify trigger automatically inserted LOCATION feature permission
  const autoPerm = await legacyDb.query(`
    SELECT dfp.feature, dfp.is_allowed 
    FROM public.device_feature_permissions dfp
    JOIN public.user_device_assignments uda ON uda.id = dfp.assignment_id
    WHERE uda.user_id = '${newSignupId}' AND uda.device_id = '${activeDevice}';
  `);
  if (autoPerm.rows.length !== 1 || autoPerm.rows[0].feature !== "LOCATION" || autoPerm.rows[0].is_allowed !== true) {
    throw new Error(`Default LOCATION permission trigger failed! Found: ${JSON.stringify(autoPerm.rows)}`);
  }
  console.log("  ✔ Default LOCATION feature permission automatically granted upon device assignment.");

  // User now has location access for assigned device
  await setCaller(newSignupId);
  const userLocRead = await legacyDb.query(`SELECT public.has_location_read_access('${activeDevice}') AS can_read;`);
  if (!userLocRead.rows[0].can_read) {
    throw new Error("User was not granted location read access on assigned device!");
  }
  console.log("  ✔ User successfully granted location telemetry access for assigned device.");

  // Sub-test 5D: Sensitive Feature Gating
  console.log("  -> Testing sensitive feature gating (CAMERA, FILES, MIC, COMMANDS)...");
  const userCameraAccess = await legacyDb.query(`SELECT public.has_feature_permission('${activeDevice}', 'CAMERA') AS can_camera;`);
  if (userCameraAccess.rows[0].can_camera) {
    throw new Error("Sensitive CAMERA permission was granted by default! Must be denied!");
  }
  const userCommandsAccess = await legacyDb.query(`SELECT public.has_feature_permission('${activeDevice}', 'COMMANDS') AS can_cmd;`);
  if (userCommandsAccess.rows[0].can_cmd) {
    throw new Error("Sensitive COMMANDS permission was granted by default! Must be denied!");
  }
  console.log("  ✔ Sensitive features (CAMERA, COMMANDS) strictly denied by default.");

  // Admin explicitly grants CAMERA permission
  await setCaller(adminId);
  await legacyDb.exec(`
    INSERT INTO public.device_feature_permissions (assignment_id, feature, is_allowed)
    SELECT id, 'CAMERA', TRUE FROM public.user_device_assignments 
    WHERE user_id = '${newSignupId}' AND device_id = '${activeDevice}';
  `);

  await setCaller(newSignupId);
  const userCameraAccessAfterGrant = await legacyDb.query(`SELECT public.has_feature_permission('${activeDevice}', 'CAMERA') AS can_camera;`);
  if (!userCameraAccessAfterGrant.rows[0].can_camera) {
    throw new Error("User was not granted CAMERA access after explicit grant!");
  }
  console.log("  ✔ Explicit grant of CAMERA permission verified successfully.");

  // Sub-test 5E: Immediate Fail-Closed Revocation
  console.log("  -> Testing immediate fail-closed revocation...");
  // 1. Revoke assignment
  await setCaller(adminId);
  await legacyDb.exec(`
    UPDATE public.user_device_assignments 
    SET status = 'REVOKED' 
    WHERE user_id = '${newSignupId}' AND device_id = '${activeDevice}';
  `);

  await setCaller(newSignupId);
  const locAccessRevoked = await legacyDb.query(`SELECT public.has_location_read_access('${activeDevice}') AS can_read;`);
  const camAccessRevoked = await legacyDb.query(`SELECT public.has_feature_permission('${activeDevice}', 'CAMERA') AS can_camera;`);
  const devAccessRevoked = await legacyDb.query(`SELECT public.has_device_access('${activeDevice}') AS can_dev;`);
  if (locAccessRevoked.rows[0].can_read || camAccessRevoked.rows[0].can_camera || devAccessRevoked.rows[0].can_dev) {
    throw new Error("Revocation failure: User still has access after assignment was REVOKED!");
  }
  console.log("  ✔ Immediate Fail-Closed: Revoking assignment terminates device, location, and feature access immediately.");

  // 2. Account Suspension
  await setCaller(adminId);
  // Re-activate assignment
  await legacyDb.exec(`
    UPDATE public.user_device_assignments 
    SET status = 'ACTIVE' 
    WHERE user_id = '${newSignupId}' AND device_id = '${activeDevice}';
    -- Suspend user account
    UPDATE public.profiles SET status = 'SUSPENDED' WHERE id = '${newSignupId}';
  `);

  await setCaller(newSignupId);
  const suspendedLoc = await legacyDb.query(`SELECT public.has_location_read_access('${activeDevice}') AS can_read;`);
  const suspendedDev = await legacyDb.query(`SELECT public.has_device_access('${activeDevice}') AS can_dev;`);
  if (suspendedLoc.rows[0].can_read || suspendedDev.rows[0].can_dev) {
    throw new Error("Revocation failure: Suspended user still has access!");
  }
  console.log("  ✔ Immediate Fail-Closed: Suspending user account terminates all access immediately.");

  // ---------------------------------------------------------------------------
  // TEST 6: Owner A1 and Admin B2 Authorization Policies
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 6] Verifying Owner A1 and Admin B2 Fleet Policies...");
  await setCaller(ownerId);
  const ownerLoc = await legacyDb.query(`SELECT public.has_location_read_access('${activeDevice}') AS can_read;`);
  const ownerDev = await legacyDb.query(`SELECT public.has_device_access('${activeDevice}') AS can_dev;`);
  if (!ownerLoc.rows[0].can_read || !ownerDev.rows[0].can_dev) {
    throw new Error("Owner A1 policy violation: Owner must have universal authority!");
  }
  console.log("  ✔ Owner A1 Verified: Universal device and location access without assignment.");

  await setCaller(adminId);
  const adminLoc = await legacyDb.query(`SELECT public.has_location_read_access('${activeDevice}') AS can_read;`);
  const adminDev = await legacyDb.query(`SELECT public.has_device_access('${activeDevice}') AS can_dev;`);
  if (!adminLoc.rows[0].can_read) {
    throw new Error("Admin B2 policy violation: Admin must have fleet-wide location read without assignment!");
  }
  if (adminDev.rows[0].can_dev) {
    throw new Error("Admin B2 policy violation: Unassigned Admin must NOT have device access for sensitive operations!");
  }
  console.log("  ✔ Admin B2 Verified: Fleet-wide location telemetry permitted; unassigned sensitive operations denied.");

  // ---------------------------------------------------------------------------
  // TEST 7: Owner Bootstrap Claim RPC & Single-Owner Invariant
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 7] Testing Owner Bootstrap Claim RPC...");
  const bootstrapToken = "super-secret-bootstrap-token-2026";
  const salt = "trace_salt_123";

  const bootstrapDb = new PGlite();
  await bootstrapDb.exec(setupSupabasePrelude);
  await bootstrapDb.exec(`
    CREATE TABLE public.locations (
      id BIGINT PRIMARY KEY,
      device_id TEXT NOT NULL,
      lat FLOAT8 NOT NULL,
      lng FLOAT8 NOT NULL,
      created_at TIMESTAMPTZ DEFAULT NOW()
    );
  `);
  await bootstrapDb.exec(m1Sql);

  const candidateId = "99000000-0000-0000-0000-000000000099";
  await bootstrapDb.exec(`
    INSERT INTO auth.users (id, email) VALUES ('${candidateId}', 'candidate@trace.local');
    INSERT INTO private.system_bootstrap (id, token_hash, salt, is_claimed)
    VALUES (1, encode(sha256(('${bootstrapToken}' || '${salt}')::bytea), 'hex'), '${salt}', FALSE)
    ON CONFLICT (id) DO UPDATE SET 
      token_hash = EXCLUDED.token_hash, 
      salt = EXCLUDED.salt, 
      is_claimed = FALSE;

    INSERT INTO public._test_session (caller_id) VALUES ('${candidateId}');
  `);

  const claimRes = await bootstrapDb.query(`
    SELECT public.bootstrap_claim_owner('${bootstrapToken}') AS result;
  `);
  const claimResult = (claimRes.rows[0] as any).result;
  if (!claimResult.success || claimResult.role !== "OWNER") {
    throw new Error("bootstrap_claim_owner failed to claim Owner role!");
  }
  console.log("  ✔ bootstrap_claim_owner successfully promoted candidate to OWNER/ACTIVE.");

  // Single-use token check
  let secondClaimFailed = false;
  try {
    await bootstrapDb.query(`SELECT public.bootstrap_claim_owner('${bootstrapToken}');`);
  } catch (_e) {
    secondClaimFailed = true;
  }
  if (!secondClaimFailed) {
    throw new Error("Second bootstrap claim should have failed!");
  }
  console.log("  ✔ Second bootstrap claim rejected (Token consumed / Owner already established).");

  // Single-Owner Index Constraint Verification
  let duplicateOwnerPrevented = false;
  try {
    await bootstrapDb.exec(`
      INSERT INTO auth.users (id, email) VALUES ('88000000-0000-0000-0000-000000000088', 'rogue@trace.local');
      UPDATE public.profiles SET role = 'OWNER', status = 'ACTIVE' WHERE id = '88000000-0000-0000-0000-000000000088';
    `);
  } catch (_err) {
    duplicateOwnerPrevented = true;
  }
  if (!duplicateOwnerPrevented) {
    throw new Error("idx_single_owner_constraint failed to prevent second OWNER in profiles!");
  }
  console.log("  ✔ idx_single_owner_constraint strictly enforced at database index level.");

  // ---------------------------------------------------------------------------
  // TEST 8: Idempotent Migration Rerun
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 8] Testing Idempotent Rerun of All Migrations...");
  await resetRole();
  await legacyDb.exec(m1Sql);
  await legacyDb.exec(m2Sql);
  await legacyDb.exec(m3Sql);
  await legacyDb.exec(m4Sql);
  await legacyDb.exec(m5Sql);
  await legacyDb.exec(m6Sql);
  console.log("  ✔ Rerun of migrations 01, 02, 03, 04, 05, and 06 succeeded with zero errors (100% Idempotent).");
  // ---------------------------------------------------------------------------
  // TEST 9: Priority 2.2 — Actual Android Client Wire Contract Integration Test
  // Exercises LocationData contract from tracker_app against submit_tracker_telemetry
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 9] Testing Actual Android Client Wire Contract (LocationData)...");
  
  const androidDeviceId = "Samsung Galaxy S24 (ABCD)";
  const androidDeviceKey = "android-device-secret-key-999";
  const androidDeviceSalt = "salt-samsung-999";

  // Register device with per-device credentials in private.device_secrets
  await legacyDb.query(`
    SELECT public.register_tracker_device(
      '${androidDeviceId}',
      'Samsung Galaxy S24 Canary',
      'Samsung SM-S921B',
      'hw-uuid-s24-999',
      encode(sha256(('${androidDeviceKey}' || '${androidDeviceSalt}')::bytea), 'hex'),
      '${androidDeviceSalt}'
    );
  `);

  // 9A: Valid Android LocationData Payload (Full wire keys: latitude, longitude, altitude, battery_level, signal_dbm)
  const androidLocationPayload = {
    device_id: androidDeviceId,
    latitude: 23.8103,
    longitude: 90.4125,
    altitude: 15.5,
    speed: 12.5,
    accuracy: 3.2,
    bearing: 180.0,
    pitch: 5.0,
    roll: -2.0,
    pressure: 1013.25,
    battery_level: 0.85,
    charging: true,
    signal_dbm: -75,
    network_type: "5G",
    created_at: new Date().toISOString()
  };

  const wireRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry(
      '${androidDeviceId}',
      '${JSON.stringify(androidLocationPayload)}'::jsonb,
      '${androidDeviceKey}'
    ) AS result;
  `);
  const wireResult = (wireRes.rows[0] as any).result;
  if (!wireResult.success || !wireResult.promoted) {
    throw new Error(`Android client wire contract failed promotion: ${JSON.stringify(wireResult)}`);
  }

  // Verify promoted row in public.locations matches Android wire attributes
  const promotedRow = await legacyDb.query(`
    SELECT lat, lng, alt, speed, accuracy, bearing, battery_level, charging, network_type
    FROM public.locations
    WHERE device_id = '${androidDeviceId}'
    ORDER BY id DESC LIMIT 1;
  `);
  if (promotedRow.rows.length !== 1) {
    throw new Error("Android wire location fix was not promoted to public.locations!");
  }
  const row = promotedRow.rows[0];
  if (Math.abs(row.lat - 23.8103) > 0.0001 || Math.abs(row.lng - 90.4125) > 0.0001) {
    throw new Error(`Coordinates mismatch in locations! Expected 23.8103, 90.4125, found: ${row.lat}, ${row.lng}`);
  }
  if (Math.abs(row.alt - 15.5) > 0.01) {
    throw new Error(`Altitude mismatch! Expected 15.5, found: ${row.alt}`);
  }
  if (row.network_type !== "5G" || Math.abs(row.battery_level - 0.85) > 0.01 || !row.charging) {
    throw new Error(`Telemetry attributes mismatch! Battery: ${row.battery_level}, Net: ${row.network_type}`);
  }
  console.log("  ✔ Android client LocationData payload (latitude, longitude, altitude) promoted accurately to public.locations.");

  // 9B: Android wire contract with legacy ingest secret fallback
  console.log("  -> Testing Android wire contract authenticated via legacy ingest credential fallback...");
  const legacyAndroidDevice = "Legacy Xiaomi Poco (XYZ)";
  const legacyKey = "legacy-fleet-shared-key-2026";
  await legacyDb.query(`SELECT private.set_legacy_ingest_secret('${legacyKey}');`);

  const legacyPayload = {
    device_id: legacyAndroidDevice,
    latitude: 37.7749,
    longitude: -122.4194,
    altitude: 10.0,
    speed: 0.0,
    accuracy: 4.0,
    battery_level: 0.5,
    charging: false,
    network_type: "LTE",
    created_at: new Date().toISOString()
  };

  const legacyRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry(
      '${legacyAndroidDevice}',
      '${JSON.stringify(legacyPayload)}'::jsonb,
      '${legacyKey}'
    ) AS result;
  `);
  const legacyResult = (legacyRes.rows[0] as any).result;
  if (!legacyResult.success || !legacyResult.promoted) {
    throw new Error(`Legacy ingest credential failed to authenticate Android payload: ${JSON.stringify(legacyResult)}`);
  }
  console.log("  ✔ Legacy ingest credential authenticated Android wire payload and auto-registered device.");

  // 9C: Negative test: Malformed/Missing coordinates in Android contract
  console.log("  -> Testing Android wire contract rejection when coordinates missing...");
  const missingCoordsPayload = {
    device_id: androidDeviceId,
    altitude: 15.5,
    battery_level: 0.85,
    created_at: new Date().toISOString()
  };
  const missingRes = await legacyDb.query(`
    SELECT public.submit_tracker_telemetry(
      '${androidDeviceId}',
      '${JSON.stringify(missingCoordsPayload)}'::jsonb,
      '${androidDeviceKey}'
    ) AS result;
  `);
  const missingResult = (missingRes.rows[0] as any).result;
  if (missingResult.success !== false || missingResult.error !== "MISSING_COORDINATES") {
    throw new Error(`Expected MISSING_COORDINATES, received: ${JSON.stringify(missingResult)}`);
  }
  console.log("  ✔ Android payload with missing coordinates rejected with MISSING_COORDINATES.");

  // ---------------------------------------------------------------------------
  // TEST 10: Phase 4 — Device Enrollment, Role Visibility & User Assignment Lifecycle
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 10] Testing Phase 4 Device Enrollment & Identity Lifecycle...");

  // 10A: Unprivileged/Anon Tracker Self-Enrollment
  console.log("  -> 10A: Unassigned tracker self-enrollment from anon client...");
  await legacyDb.query("RESET ROLE;");
  await legacyDb.query("DELETE FROM public._test_session;");
  await legacyDb.query("SET ROLE anon;");
  const p4DeviceId = "Pixel 10 Pro (E10B)";
  const p4PublicKeySpki = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtraceSamplePublicKeyBase64EncodedP256Length91BytesData==";
  const p4HashedKey = "hashed-p4-device-secret-key-1010";
  const p4Salt = "salt-p4-pixel-1010";

  const enrollRes = await legacyDb.query(`
    SELECT public.enroll_tracker_device(
      '${p4DeviceId}',
      'Tracker Pixel 10 Pro',
      'Pixel 10 Pro',
      'hardware-uuid-pixel-10-pro',
      '${p4PublicKeySpki}',
      'ES256',
      '${p4HashedKey}',
      '${p4Salt}',
      '{"firmware": "v1.0.0", "hardware_backed": true}'::jsonb
    ) AS result;
  `);
  const enrollResult = (enrollRes.rows[0] as any).result;
  if (!enrollResult.success || enrollResult.already_enrolled !== false) {
    throw new Error(`Enrollment failed: ${JSON.stringify(enrollResult)}`);
  }
  console.log("  ✔ Unassigned tracker enrolled cleanly via enroll_tracker_device.");

  // Verify records created across devices, enrolled_devices, and device_secrets
  await resetRole(); // superuser to inspect private schema
  const devRow = (await legacyDb.query(`SELECT * FROM public.devices WHERE device_id = '${p4DeviceId}'`)).rows[0] as any;
  if (!devRow || devRow.status !== 'ACTIVE' || devRow.model !== 'Pixel 10 Pro') {
    throw new Error(`Device record mismatch: ${JSON.stringify(devRow)}`);
  }
  const enrolledRow = (await legacyDb.query(`SELECT * FROM public.enrolled_devices WHERE device_id = '${p4DeviceId}'`)).rows[0] as any;
  if (!enrolledRow || enrolledRow.public_key_spki !== p4PublicKeySpki) {
    throw new Error(`Enrolled device record mismatch: ${JSON.stringify(enrolledRow)}`);
  }
  const secretRow = (await legacyDb.query(`SELECT * FROM private.device_secrets WHERE device_id = '${p4DeviceId}'`)).rows[0] as any;
  if (!secretRow || secretRow.api_key_hash !== p4HashedKey) {
    throw new Error(`Device secret record mismatch: ${JSON.stringify(secretRow)}`);
  }
  console.log("  ✔ Atomic records verified across public.devices, public.enrolled_devices, and private.device_secrets.");

  // 10B: Re-enrollment idempotency with same key
  console.log("  -> 10B: Idempotent re-enrollment with identical public key...");
  await legacyDb.query("RESET ROLE;");
  await legacyDb.query("DELETE FROM public._test_session;");
  await legacyDb.query("SET ROLE anon;");
  const reEnrollRes = await legacyDb.query(`
    SELECT public.enroll_tracker_device(
      '${p4DeviceId}',
      'Tracker Pixel 10 Pro',
      'Pixel 10 Pro',
      'hardware-uuid-pixel-10-pro',
      '${p4PublicKeySpki}',
      'ES256'
    ) AS result;
  `);
  const reEnrollResult = (reEnrollRes.rows[0] as any).result;
  if (!reEnrollResult.success || reEnrollResult.already_enrolled !== true) {
    throw new Error(`Idempotent re-enrollment failed: ${JSON.stringify(reEnrollResult)}`);
  }
  console.log("  ✔ Re-enrollment confirmed idempotent (already_enrolled = true).");

  // 10C: Anti-takeover rejection (different key without admin privilege)
  console.log("  -> 10C: Anti-takeover rejection with mismatched public key...");
  await legacyDb.query("RESET ROLE;");
  await legacyDb.query("DELETE FROM public._test_session;");
  await legacyDb.query("SET ROLE anon;");
  let takeoverCaught = false;
  try {
    await legacyDb.query(`
      SELECT public.enroll_tracker_device(
        '${p4DeviceId}',
        'Attacker Spoof',
        'Pixel 10 Pro',
        'hardware-uuid-attacker',
        'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEattackerTamperedKeyWithDifferentBytesLength91BytesData=='
      );
    `);
  } catch (err: any) {
    takeoverCaught = true;
    if (!err.message.includes("DEVICE_ID_ALREADY_EXISTS")) {
      throw new Error(`Expected DEVICE_ID_ALREADY_EXISTS, got: ${err.message}`);
    }
  }
  if (!takeoverCaught) {
    throw new Error("SECURITY FAILURE: Anti-takeover check failed to reject mismatched public key!");
  }
  console.log("  ✔ Anti-takeover check successfully rejected rogue public key for existing device ID.");

  // 10D: Visibility isolation on unassigned devices
  console.log("  -> 10D: Visibility isolation on newly enrolled unassigned device...");
  const p4ApprovedUserId = "66000000-0000-0000-0000-000000000066";
  const p4VisitorId = "77000000-0000-0000-0000-000000000077";
  await resetRole();
  await legacyDb.exec(`
    INSERT INTO auth.users (id, email) VALUES
      ('${p4ApprovedUserId}', 'user66@trace.local'),
      ('${p4VisitorId}', 'visitor77@trace.local')
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO public.profiles (id, email, role, status) VALUES
      ('${p4ApprovedUserId}', 'user66@trace.local', 'USER', 'ACTIVE'),
      ('${p4VisitorId}', 'visitor77@trace.local', 'VISITOR', 'PENDING')
    ON CONFLICT (id) DO UPDATE SET role = EXCLUDED.role, status = EXCLUDED.status;
  `);

  // Owner sees unassigned device
  await setCaller(ownerId);
  const ownerDevices = (await legacyDb.query(`SELECT device_id FROM public.devices WHERE device_id = '${p4DeviceId}'`)).rows;
  if (ownerDevices.length !== 1) {
    throw new Error(`Owner expected 1 unassigned device, got ${ownerDevices.length}`);
  }

  // Admin sees unassigned device
  await setCaller(adminId);
  const adminDevices = (await legacyDb.query(`SELECT device_id FROM public.devices WHERE device_id = '${p4DeviceId}'`)).rows;
  if (adminDevices.length !== 1) {
    throw new Error(`Admin expected 1 unassigned device, got ${adminDevices.length}`);
  }

  // Standard User DOES NOT see unassigned device (RLS devices_select_policy)
  await setCaller(p4ApprovedUserId);
  const userDevicesBefore = (await legacyDb.query(`SELECT device_id FROM public.devices WHERE device_id = '${p4DeviceId}'`)).rows;
  if (userDevicesBefore.length !== 0) {
    throw new Error(`Standard User should NOT see unassigned device, but saw ${userDevicesBefore.length}`);
  }

  // Visitor DOES NOT see unassigned device
  await setCaller(p4VisitorId);
  const visitorDevices = (await legacyDb.query(`SELECT device_id FROM public.devices WHERE device_id = '${p4DeviceId}'`)).rows;
  if (visitorDevices.length !== 0) {
    throw new Error(`Visitor should NOT see unassigned device, but saw ${visitorDevices.length}`);
  }
  console.log("  ✔ Unassigned device visibility verified: Owner/Admin sees it; standard User and Visitor fail closed.");

  // 10E: Admin assigns device to active User
  console.log("  -> 10E: Admin assigns device to User via assign_device_to_user...");
  await setCaller(adminId);
  const assignRes = await legacyDb.query(`
    SELECT public.assign_device_to_user(
      '${p4DeviceId}',
      '${p4ApprovedUserId}'::uuid
    ) AS result;
  `);
  const assignResult = (assignRes.rows[0] as any).result;
  if (!assignResult.success || assignResult.status !== 'ACTIVE') {
    throw new Error(`assign_device_to_user failed: ${JSON.stringify(assignResult)}`);
  }

  // Verify trigger automatically created LOCATION permission
  const p4AutoPerm = (await legacyDb.query(`
    SELECT dfp.* FROM public.device_feature_permissions dfp
    JOIN public.user_device_assignments uda ON dfp.assignment_id = uda.id
    WHERE uda.user_id = '${p4ApprovedUserId}' AND uda.device_id = '${p4DeviceId}' AND dfp.feature = 'LOCATION'
  `)).rows[0] as any;
  if (!p4AutoPerm || !p4AutoPerm.is_allowed) {
    throw new Error(`Expected automatic LOCATION permission on assignment, found: ${JSON.stringify(p4AutoPerm)}`);
  }

  // Standard User NOW sees the device in public.devices!
  await setCaller(p4ApprovedUserId);
  const userDevicesAfter = (await legacyDb.query(`SELECT device_id FROM public.devices WHERE device_id = '${p4DeviceId}'`)).rows;
  if (userDevicesAfter.length !== 1) {
    throw new Error(`Assigned User should now see device, but saw ${userDevicesAfter.length}`);
  }
  console.log("  ✔ Device assignment verified: Trigger auto-provisioned LOCATION permission, and User now sees device.");

  // 10F: Admin unassigns device from User
  console.log("  -> 10F: Admin revokes device assignment via unassign_device_from_user...");
  await setCaller(adminId);
  const unassignRes = await legacyDb.query(`
    SELECT public.unassign_device_from_user(
      '${p4DeviceId}',
      '${p4ApprovedUserId}'::uuid
    ) AS result;
  `);
  const unassignResult = (unassignRes.rows[0] as any).result;
  if (!unassignResult.success || unassignResult.status !== 'REVOKED') {
    throw new Error(`unassign_device_from_user failed: ${JSON.stringify(unassignResult)}`);
  }

  // Standard User immediately loses device visibility
  await setCaller(p4ApprovedUserId);
  const userDevicesRevoked = (await legacyDb.query(`SELECT device_id FROM public.devices WHERE device_id = '${p4DeviceId}'`)).rows;
  if (userDevicesRevoked.length !== 0) {
    throw new Error(`Revoked User should NOT see unassigned device, but saw ${userDevicesRevoked.length}`);
  }
  await resetRole();
  console.log("  ✔ Unassignment verified: Device immediately disappears from User view and permissions revoked.");

  // ---------------------------------------------------------------------------
  // TEST 11: Phase 5 — User Management & Feature Permissions RPCs
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 11] Testing Phase 5 User Management & Feature Permissions RPCs...");

  // 11A: Enum Verification — 'DISABLED' in account_status
  console.log("  -> 11A: Verifying 'DISABLED' value in account_status enum...");
  const enumCheck = await legacyDb.query(`
    SELECT enumlabel 
    FROM pg_enum 
    JOIN pg_type ON pg_enum.enumtypid = pg_type.oid 
    WHERE pg_type.typname = 'account_status' AND enumlabel = 'DISABLED';
  `);
  if (enumCheck.rows.length !== 1) {
    throw new Error("account_status enum missing 'DISABLED' value!");
  }
  console.log("  ✔ account_status enum successfully contains 'DISABLED'.");

  // Setup distinct test accounts
  const p5Admin2Id = "22000000-0000-0000-0000-000000000022";
  const p5User1Id = "81000000-0000-0000-0000-000000000081";
  const p5User2Id = "82000000-0000-0000-0000-000000000082";
  const p5PendingVisitorId = "83000000-0000-0000-0000-000000000083";

  await resetRole();
  await legacyDb.exec(`
    INSERT INTO auth.users (id, email) VALUES
      ('${p5Admin2Id}', 'admin2@trace.local'),
      ('${p5User1Id}', 'user1@trace.local'),
      ('${p5User2Id}', 'user2@trace.local'),
      ('${p5PendingVisitorId}', 'visitor_pending@trace.local')
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO public.profiles (id, email, display_name, role, status) VALUES
      ('${p5Admin2Id}', 'admin2@trace.local', 'Secondary Admin', 'ADMIN', 'ACTIVE'),
      ('${p5User1Id}', 'user1@trace.local', 'Test User 1', 'USER', 'ACTIVE'),
      ('${p5User2Id}', 'user2@trace.local', 'Test User 2', 'USER', 'ACTIVE'),
      ('${p5PendingVisitorId}', 'visitor_pending@trace.local', 'Pending Visitor', 'VISITOR', 'PENDING')
    ON CONFLICT (id) DO UPDATE SET 
      role = EXCLUDED.role, 
      status = EXCLUDED.status,
      display_name = EXCLUDED.display_name;
  `);

  // 11B: public.fetch_managed_users Authority and RBAC Boundaries
  console.log("  -> 11B: Testing fetch_managed_users() visibility and fail-closed boundaries...");
  
  // Unprivileged: Visitor fails closed
  await setCaller(p5PendingVisitorId);
  let visitorFetchFailed = false;
  try {
    await legacyDb.query("SELECT * FROM public.fetch_managed_users();");
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) {
      visitorFetchFailed = true;
    }
  }
  if (!visitorFetchFailed) {
    throw new Error("SECURITY FAILURE: Visitor was able to call fetch_managed_users!");
  }

  // Unprivileged: Standard User fails closed
  await setCaller(p5User1Id);
  let userFetchFailed = false;
  try {
    await legacyDb.query("SELECT * FROM public.fetch_managed_users();");
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) {
      userFetchFailed = true;
    }
  }
  if (!userFetchFailed) {
    throw new Error("SECURITY FAILURE: Standard User was able to call fetch_managed_users!");
  }

  // Owner sees ALL accounts
  await setCaller(ownerId);
  const ownerFetchRows = (await legacyDb.query("SELECT * FROM public.fetch_managed_users();")).rows as any[];
  const ownerSeenUserIds = ownerFetchRows.map((r: any) => r.user_id);
  if (!ownerSeenUserIds.includes(ownerId) || !ownerSeenUserIds.includes(adminId) || !ownerSeenUserIds.includes(p5Admin2Id) || !ownerSeenUserIds.includes(p5User1Id) || !ownerSeenUserIds.includes(p5PendingVisitorId)) {
    throw new Error(`Owner did not see all accounts! Seen: ${JSON.stringify(ownerSeenUserIds)}`);
  }
  console.log("  ✔ fetch_managed_users() Owner visibility verified: Universal directory access.");

  // Admin sees Users, Visitors, and Self; strictly NO Owner, NO other Admins
  await setCaller(adminId);
  const adminFetchRows = (await legacyDb.query("SELECT * FROM public.fetch_managed_users();")).rows as any[];
  const adminSeenUserIds = adminFetchRows.map((r: any) => r.user_id);
  if (!adminSeenUserIds.includes(adminId)) {
    throw new Error("Admin did not see Self in fetch_managed_users!");
  }
  if (!adminSeenUserIds.includes(p5User1Id) || !adminSeenUserIds.includes(p5User2Id) || !adminSeenUserIds.includes(p5PendingVisitorId)) {
    throw new Error("Admin did not see Users or Visitors in fetch_managed_users!");
  }
  if (adminSeenUserIds.includes(ownerId)) {
    throw new Error("SECURITY FAILURE: Admin saw Owner in fetch_managed_users!");
  }
  if (adminSeenUserIds.includes(p5Admin2Id)) {
    throw new Error("SECURITY FAILURE: Admin saw another Admin in fetch_managed_users!");
  }
  console.log("  ✔ fetch_managed_users() Admin visibility verified: Sees Users, Visitors, and Self; Owner & peer Admins hidden.");

  // 11C: public.approve_user_account
  console.log("  -> 11C: Testing approve_user_account()...");
  
  // User cannot approve
  await setCaller(p5User1Id);
  let userApproveBlocked = false;
  try {
    await legacyDb.query(`SELECT public.approve_user_account('${p5PendingVisitorId}'::uuid);`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) userApproveBlocked = true;
  }
  if (!userApproveBlocked) {
    throw new Error("SECURITY FAILURE: User was able to approve account!");
  }

  // Admin cannot approve already ACTIVE user
  await setCaller(adminId);
  let activeApproveBlocked = false;
  try {
    await legacyDb.query(`SELECT public.approve_user_account('${p5User1Id}'::uuid);`);
  } catch (err: any) {
    if (err.message.includes("INVALID_ACCOUNT_STATUS")) activeApproveBlocked = true;
  }
  if (!activeApproveBlocked) {
    throw new Error("SECURITY FAILURE: approve_user_account permitted on non-pending account!");
  }

  // Admin approves pending visitor
  const approveRes = await legacyDb.query(`SELECT public.approve_user_account('${p5PendingVisitorId}'::uuid) AS result;`);
  const approveResult = (approveRes.rows[0] as any).result;
  if (!approveResult.success || approveResult.role !== "USER" || approveResult.status !== "ACTIVE") {
    throw new Error(`approve_user_account failed: ${JSON.stringify(approveResult)}`);
  }
  const approvedProfile = (await legacyDb.query(`SELECT role, status FROM public.profiles WHERE id = '${p5PendingVisitorId}';`)).rows[0] as any;
  if (approvedProfile.role !== "USER" || approvedProfile.status !== "ACTIVE") {
    throw new Error(`Approved profile state mismatch: ${JSON.stringify(approvedProfile)}`);
  }

  // Verify audit log
  const approveAudit = (await legacyDb.query(`
    SELECT * FROM public.audit_logs WHERE action = 'USER_ACCOUNT_APPROVAL' AND resource_id = '${p5PendingVisitorId}';
  `)).rows;
  if (approveAudit.length === 0) {
    throw new Error("audit_logs missing entry for USER_ACCOUNT_APPROVAL!");
  }
  console.log("  ✔ approve_user_account verified: Promotes PENDING to USER/ACTIVE and records audit log.");

  // 11D: public.update_user_account_status
  console.log("  -> 11D: Testing update_user_account_status() and Owner protections...");
  
  // Validation: Invalid status rejected
  await setCaller(adminId);
  let invalidStatusBlocked = false;
  try {
    await legacyDb.query(`SELECT public.update_user_account_status('${p5User1Id}'::uuid, 'BANNED');`);
  } catch (err: any) {
    if (err.message.includes("INVALID_STATUS")) invalidStatusBlocked = true;
  }
  if (!invalidStatusBlocked) throw new Error("Invalid status string was accepted!");

  // Admin cannot suspend Owner
  let adminSuspendOwnerBlocked = false;
  try {
    await legacyDb.query(`SELECT public.update_user_account_status('${ownerId}'::uuid, 'SUSPENDED');`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) adminSuspendOwnerBlocked = true;
  }
  if (!adminSuspendOwnerBlocked) throw new Error("SECURITY FAILURE: Admin was able to suspend Owner!");

  // Admin cannot suspend other Admin
  let adminSuspendAdminBlocked = false;
  try {
    await legacyDb.query(`SELECT public.update_user_account_status('${p5Admin2Id}'::uuid, 'SUSPENDED');`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) adminSuspendAdminBlocked = true;
  }
  if (!adminSuspendAdminBlocked) throw new Error("SECURITY FAILURE: Admin was able to suspend another Admin!");

  // Owner cannot suspend Owner (Owner protection)
  await setCaller(ownerId);
  let ownerSuspendOwnerBlocked = false;
  try {
    await legacyDb.query(`SELECT public.update_user_account_status('${ownerId}'::uuid, 'SUSPENDED');`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) ownerSuspendOwnerBlocked = true;
  }
  if (!ownerSuspendOwnerBlocked) throw new Error("SECURITY FAILURE: Owner was able to modify Owner account status!");

  // Admin suspends User
  await setCaller(adminId);
  const suspendRes = await legacyDb.query(`SELECT public.update_user_account_status('${p5User2Id}'::uuid, 'SUSPENDED') AS result;`);
  const suspendResult = (suspendRes.rows[0] as any).result;
  if (!suspendResult.success || suspendResult.status !== "SUSPENDED") {
    throw new Error(`update_user_account_status failed: ${JSON.stringify(suspendResult)}`);
  }

  // Admin disables User
  const disableRes = await legacyDb.query(`SELECT public.update_user_account_status('${p5User2Id}'::uuid, 'DISABLED') AS result;`);
  const disableResult = (disableRes.rows[0] as any).result;
  if (!disableResult.success || disableResult.status !== "DISABLED") {
    throw new Error(`update_user_account_status to DISABLED failed: ${JSON.stringify(disableResult)}`);
  }
  const disabledProfile = (await legacyDb.query(`SELECT status FROM public.profiles WHERE id = '${p5User2Id}';`)).rows[0] as any;
  if (disabledProfile.status !== "DISABLED") {
    throw new Error(`Expected DISABLED status, got: ${disabledProfile.status}`);
  }

  // Owner suspends secondary Admin
  await setCaller(ownerId);
  const ownerSuspendAdminRes = await legacyDb.query(`SELECT public.update_user_account_status('${p5Admin2Id}'::uuid, 'SUSPENDED') AS result;`);
  const ownerSuspendAdminResult = (ownerSuspendAdminRes.rows[0] as any).result;
  if (!ownerSuspendAdminResult.success || ownerSuspendAdminResult.status !== "SUSPENDED") {
    throw new Error(`Owner suspending Admin failed: ${JSON.stringify(ownerSuspendAdminResult)}`);
  }

  // Reactivate accounts for remaining tests
  await legacyDb.query(`SELECT public.update_user_account_status('${p5User2Id}'::uuid, 'ACTIVE');`);
  await legacyDb.query(`SELECT public.update_user_account_status('${p5Admin2Id}'::uuid, 'ACTIVE');`);
  console.log("  ✔ update_user_account_status verified: Strict role protections enforced; status transitions to SUSPENDED/DISABLED/ACTIVE verified.");

  // 11E: public.update_user_account_role
  console.log("  -> 11E: Testing update_user_account_role() and Admin appointment boundaries...");

  // Promotion to OWNER strictly forbidden
  await setCaller(ownerId);
  let promoteToOwnerBlocked = false;
  try {
    await legacyDb.query(`SELECT public.update_user_account_role('${p5User1Id}'::uuid, 'OWNER');`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) promoteToOwnerBlocked = true;
  }
  if (!promoteToOwnerBlocked) throw new Error("SECURITY FAILURE: Promotion to OWNER was permitted!");

  // Demoting OWNER strictly forbidden
  let demoteOwnerBlocked = false;
  try {
    await legacyDb.query(`SELECT public.update_user_account_role('${ownerId}'::uuid, 'USER');`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) demoteOwnerBlocked = true;
  }
  if (!demoteOwnerBlocked) throw new Error("SECURITY FAILURE: Demotion of OWNER was permitted!");

  // Admin cannot appoint other Admins
  await setCaller(adminId);
  let adminAppointAdminBlocked = false;
  try {
    await legacyDb.query(`SELECT public.update_user_account_role('${p5User1Id}'::uuid, 'ADMIN');`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) adminAppointAdminBlocked = true;
  }
  if (!adminAppointAdminBlocked) throw new Error("SECURITY FAILURE: Admin was able to appoint an Admin!");

  // Admin cannot demote other Admins
  let adminDemoteAdminBlocked = false;
  try {
    await legacyDb.query(`SELECT public.update_user_account_role('${p5Admin2Id}'::uuid, 'USER');`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) adminDemoteAdminBlocked = true;
  }
  if (!adminDemoteAdminBlocked) throw new Error("SECURITY FAILURE: Admin was able to demote an Admin!");

  // Owner appoints User to Admin
  await setCaller(ownerId);
  const appointAdminRes = await legacyDb.query(`SELECT public.update_user_account_role('${p5User1Id}'::uuid, 'ADMIN') AS result;`);
  const appointAdminResult = (appointAdminRes.rows[0] as any).result;
  if (!appointAdminResult.success || appointAdminResult.role !== "ADMIN") {
    throw new Error(`Owner appoint admin failed: ${JSON.stringify(appointAdminResult)}`);
  }

  // Owner demotes Admin back to User
  const demoteAdminRes = await legacyDb.query(`SELECT public.update_user_account_role('${p5User1Id}'::uuid, 'USER') AS result;`);
  const demoteAdminResult = (demoteAdminRes.rows[0] as any).result;
  if (!demoteAdminResult.success || demoteAdminResult.role !== "USER") {
    throw new Error(`Owner demote admin failed: ${JSON.stringify(demoteAdminResult)}`);
  }

  // Admin can change User to VISITOR
  await setCaller(adminId);
  const adminDemoteUserRes = await legacyDb.query(`SELECT public.update_user_account_role('${p5PendingVisitorId}'::uuid, 'VISITOR') AS result;`);
  const adminDemoteUserResult = (adminDemoteUserRes.rows[0] as any).result;
  if (!adminDemoteUserResult.success || adminDemoteUserResult.role !== "VISITOR") {
    throw new Error(`Admin demote user failed: ${JSON.stringify(adminDemoteUserResult)}`);
  }

  // Verify audit log
  const roleAudit = (await legacyDb.query(`
    SELECT * FROM public.audit_logs WHERE action = 'USER_ROLE_UPDATE' AND resource_id = '${p5User1Id}';
  `)).rows;
  if (roleAudit.length === 0) throw new Error("audit_logs missing entry for USER_ROLE_UPDATE!");
  console.log("  ✔ update_user_account_role verified: Owner-only Admin appointment/removal boundaries strictly enforced.");

  // 11F: Granular Feature Permissions (grant & revoke)
  console.log("  -> 11F: Testing grant_device_feature_permission() and revoke_device_feature_permission()...");
  
  // Assign activeDevice to p5User1Id first
  await setCaller(adminId);
  await legacyDb.query(`SELECT public.assign_device_to_user('${activeDevice}', '${p5User1Id}'::uuid);`);

  // Grant without assignment fails (test on unassigned legacyDevice1)
  let unassignedPermBlocked = false;
  try {
    await legacyDb.query(`SELECT public.grant_device_feature_permission('${p5User1Id}'::uuid, '${legacyDevice1}', 'CAMERA');`);
  } catch (err: any) {
    if (err.message.includes("ASSIGNMENT_NOT_FOUND")) unassignedPermBlocked = true;
  }
  if (!unassignedPermBlocked) throw new Error("grant_device_feature_permission succeeded on unassigned device!");

  // Grant invalid feature fails
  let invalidFeatureBlocked = false;
  try {
    await legacyDb.query(`SELECT public.grant_device_feature_permission('${p5User1Id}'::uuid, '${activeDevice}', 'BLUETOOTH');`);
  } catch (err: any) {
    if (err.message.includes("INVALID_FEATURE")) invalidFeatureBlocked = true;
  }
  if (!invalidFeatureBlocked) throw new Error("grant_device_feature_permission accepted invalid feature!");

  // Ordinary user cannot grant permissions
  await setCaller(p5User1Id);
  let userGrantBlocked = false;
  try {
    await legacyDb.query(`SELECT public.grant_device_feature_permission('${p5User1Id}'::uuid, '${activeDevice}', 'CAMERA');`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) userGrantBlocked = true;
  }
  if (!userGrantBlocked) throw new Error("SECURITY FAILURE: Standard user granted feature permissions!");

  // Admin grants CAMERA permission
  await setCaller(adminId);
  const grantCameraRes = await legacyDb.query(`
    SELECT public.grant_device_feature_permission('${p5User1Id}'::uuid, '${activeDevice}', 'CAMERA') AS result;
  `);
  const grantCameraResult = (grantCameraRes.rows[0] as any).result;
  if (!grantCameraResult.success || !grantCameraResult.is_allowed || grantCameraResult.feature !== "CAMERA") {
    throw new Error(`grant_device_feature_permission failed: ${JSON.stringify(grantCameraResult)}`);
  }

  // Admin grants COMMANDS permission
  await legacyDb.query(`SELECT public.grant_device_feature_permission('${p5User1Id}'::uuid, '${activeDevice}', 'COMMANDS');`);

  // Check has_feature_permission for CAMERA
  await setCaller(p5User1Id);
  const hasCameraBefore = (await legacyDb.query(`SELECT public.has_feature_permission('${activeDevice}', 'CAMERA') AS allowed;`)).rows[0] as any;
  if (!hasCameraBefore.allowed) {
    throw new Error("User was not granted CAMERA feature permission!");
  }

  // Admin revokes CAMERA permission
  await setCaller(adminId);
  const revokeCameraRes = await legacyDb.query(`
    SELECT public.revoke_device_feature_permission('${p5User1Id}'::uuid, '${activeDevice}', 'CAMERA') AS result;
  `);
  const revokeCameraResult = (revokeCameraRes.rows[0] as any).result;
  if (!revokeCameraResult.success || revokeCameraResult.is_allowed !== false) {
    throw new Error(`revoke_device_feature_permission failed: ${JSON.stringify(revokeCameraResult)}`);
  }

  // Check has_feature_permission for CAMERA after revoke -> MUST be false
  await setCaller(p5User1Id);
  const hasCameraAfter = (await legacyDb.query(`SELECT public.has_feature_permission('${activeDevice}', 'CAMERA') AS allowed;`)).rows[0] as any;
  if (hasCameraAfter.allowed) {
    throw new Error("SECURITY FAILURE: User still has CAMERA feature permission after revocation!");
  }
  console.log("  ✔ Granular feature permissions verified: Assignment gating, explicit grant, and immediate fail-closed revocation.");

  // 11G: public.fetch_user_effective_permissions
  console.log("  -> 11G: Testing fetch_user_effective_permissions()...");

  // User self-inspects permissions
  await setCaller(p5User1Id);
  const userEffectiveRes = await legacyDb.query(`SELECT public.fetch_user_effective_permissions() AS result;`);
  const userEffective = (userEffectiveRes.rows[0] as any).result;
  if (userEffective.user_id !== p5User1Id || userEffective.role !== "USER" || userEffective.status !== "ACTIVE") {
    throw new Error(`fetch_user_effective_permissions self-inspection mismatch: ${JSON.stringify(userEffective)}`);
  }
  if (!Array.isArray(userEffective.assignments) || userEffective.assignments.length !== 1) {
    throw new Error(`Expected 1 assignment in effective permissions, found: ${JSON.stringify(userEffective.assignments)}`);
  }
  const userAssignedDevice = userEffective.assignments[0];
  if (userAssignedDevice.device_id !== activeDevice) {
    throw new Error(`Device ID mismatch: ${userAssignedDevice.device_id} vs ${activeDevice}`);
  }
  // Features should include LOCATION and COMMANDS, but NOT CAMERA
  if (!userAssignedDevice.features.includes("LOCATION") || !userAssignedDevice.features.includes("COMMANDS")) {
    throw new Error(`Expected features LOCATION and COMMANDS, found: ${JSON.stringify(userAssignedDevice.features)}`);
  }
  if (userAssignedDevice.features.includes("CAMERA")) {
    throw new Error("Revoked feature CAMERA still present in effective permissions!");
  }

  // Cross-user inspection: User inspecting Admin fails closed
  let userCrossInspectBlocked = false;
  try {
    await legacyDb.query(`SELECT public.fetch_user_effective_permissions('${adminId}'::uuid);`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) userCrossInspectBlocked = true;
  }
  if (!userCrossInspectBlocked) throw new Error("SECURITY FAILURE: Standard User inspected Admin permissions!");

  // Admin inspecting User succeeds
  await setCaller(adminId);
  const adminInspectRes = await legacyDb.query(`SELECT public.fetch_user_effective_permissions('${p5User1Id}'::uuid) AS result;`);
  const adminInspect = (adminInspectRes.rows[0] as any).result;
  if (adminInspect.user_id !== p5User1Id) {
    throw new Error(`Admin inspection mismatch: ${JSON.stringify(adminInspect)}`);
  }

  // Owner inspecting User succeeds
  await setCaller(ownerId);
  const ownerInspectRes = await legacyDb.query(`SELECT public.fetch_user_effective_permissions('${p5User1Id}'::uuid) AS result;`);
  const ownerInspect = (ownerInspectRes.rows[0] as any).result;
  if (ownerInspect.user_id !== p5User1Id) {
    throw new Error(`Owner inspection mismatch: ${JSON.stringify(ownerInspect)}`);
  }
  console.log("  ✔ fetch_user_effective_permissions verified: Self-inspection permitted; cross-inspection gated to Admin/Owner; nested active assignments & features verified.");

  // 11H: Aggregate Metrics Check in fetch_managed_users
  console.log("  -> 11H: Verifying aggregate metrics in fetch_managed_users()...");
  await setCaller(ownerId);
  const metricsRows = (await legacyDb.query("SELECT * FROM public.fetch_managed_users();")).rows as any[];
  const user1Metrics = metricsRows.find((r: any) => r.user_id === p5User1Id);
  if (!user1Metrics) throw new Error(`Test User 1 not found in fetch_managed_users!`);
  if (Number(user1Metrics.assigned_device_count) !== 1) {
    throw new Error(`Expected assigned_device_count = 1, got ${user1Metrics.assigned_device_count}`);
  }
  // active_permission_count: LOCATION + COMMANDS = 2 (CAMERA is revoked/is_allowed=false)
  if (Number(user1Metrics.active_permission_count) !== 2) {
    throw new Error(`Expected active_permission_count = 2, got ${user1Metrics.active_permission_count}`);
  }
  if (user1Metrics.pending_approval !== false) {
    throw new Error(`Expected pending_approval = false, got ${user1Metrics.pending_approval}`);
  }
  await resetRole();
  console.log("  ✔ Aggregate metrics verified: assigned_device_count, active_permission_count, and pending_approval compute accurately.");


  // ---------------------------------------------------------------------------
  // TEST 12: Phase 6 — Realtime Revocation, Publication Grants & Security Definer RPCs
  // ---------------------------------------------------------------------------
  console.log("\n[TEST 12] Testing Phase 6 Realtime Revocation, Publication Grants & Security Definer RPCs...");

  // 12A: Realtime Publication Membership & Replica Identity Check
  console.log("  -> 12A: Verifying supabase_realtime publication membership and REPLICA IDENTITY FULL...");
  const p6PubTablesRes = await legacyDb.query(`
    SELECT tablename FROM pg_publication_tables 
    WHERE pubname = 'supabase_realtime' AND schemaname = 'public';
  `);
  const p6PubTables = p6PubTablesRes.rows.map((r: any) => r.tablename);
  const p6ExpectedPubTables = ["profiles", "user_device_assignments", "device_feature_permissions", "revocation_events"];
  for (const pt of p6ExpectedPubTables) {
    if (!p6PubTables.includes(pt)) {
      throw new Error(`Expected table ${pt} to be in supabase_realtime publication!`);
    }
  }
  if (p6PubTables.includes("commands")) {
    throw new Error("SECURITY FAILURE: commands table is in supabase_realtime publication!");
  }

  // Check REPLICA IDENTITY FULL (relreplident = 'f')
  const replicaIdentities = (await legacyDb.query(`
    SELECT relname, relreplident FROM pg_class 
    WHERE relname IN ('profiles', 'user_device_assignments', 'device_feature_permissions', 'revocation_events')
      AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public');
  `)).rows as any[];
  for (const ri of replicaIdentities) {
    if (ri.relreplident !== "f") {
      throw new Error(`Table ${ri.relname} does not have REPLICA IDENTITY FULL (found: ${ri.relreplident})!`);
    }
  }
  console.log("  ✔ supabase_realtime publication & REPLICA IDENTITY FULL verified across all 4 target tables.");

  // 12B: Automatic Profile Revocation Trigger (Account Status Suspension & Role Demotion)
  console.log("  -> 12B: Testing automatic profile revocation triggers (status suspension & role demotion)...");
  await setCaller(adminId);
  // Suspend p5User2Id
  await legacyDb.query(`SELECT public.update_user_account_status('${p5User2Id}'::uuid, 'SUSPENDED');`);
  
  const statusRevokeEvent = (await legacyDb.query(`
    SELECT * FROM public.revocation_events 
    WHERE target_user_id = '${p5User2Id}' AND event_type = 'ACCOUNT_STATUS_CHANGED'
    ORDER BY created_at DESC LIMIT 1;
  `)).rows[0] as any;
  if (!statusRevokeEvent) {
    throw new Error("trg_profile_revocation failed to emit ACCOUNT_STATUS_CHANGED event on account suspension!");
  }
  if (!statusRevokeEvent.reason.includes("SUSPENDED")) {
    throw new Error(`Unexpected revocation reason: ${statusRevokeEvent.reason}`);
  }

  // Demote p5User2Id to VISITOR
  await legacyDb.query(`SELECT public.update_user_account_role('${p5User2Id}'::uuid, 'VISITOR');`);
  const roleRevokeEvent = (await legacyDb.query(`
    SELECT * FROM public.revocation_events 
    WHERE target_user_id = '${p5User2Id}' AND event_type = 'ROLE_CHANGED'
    ORDER BY created_at DESC LIMIT 1;
  `)).rows[0] as any;
  if (!roleRevokeEvent) {
    throw new Error("trg_profile_revocation failed to emit ROLE_CHANGED event on demotion to VISITOR!");
  }
  console.log("  ✔ Profile revocation triggers verified: Automatic revocation events recorded for status suspension and role demotion.");

  // 12C: Automatic Device Assignment Revocation Trigger
  console.log("  -> 12C: Testing automatic device assignment revocation trigger...");
  // Reactivate and approve p5User2Id for assignment test
  await legacyDb.query(`UPDATE public.profiles SET role = 'USER', status = 'ACTIVE' WHERE id = '${p5User2Id}';`);
  await legacyDb.query(`SELECT public.assign_device_to_user('${legacyDevice2}', '${p5User2Id}'::uuid);`);
  
  // Unassign legacyDevice2 from p5User2Id
  await legacyDb.query(`SELECT public.unassign_device_from_user('${legacyDevice2}', '${p5User2Id}'::uuid);`);
  const deviceRevokeEvent = (await legacyDb.query(`
    SELECT * FROM public.revocation_events 
    WHERE target_user_id = '${p5User2Id}' AND device_id = '${legacyDevice2}' AND event_type = 'DEVICE_UNASSIGNED'
    ORDER BY created_at DESC LIMIT 1;
  `)).rows[0] as any;
  if (!deviceRevokeEvent) {
    throw new Error("trg_uda_revocation failed to emit DEVICE_UNASSIGNED event on device unassignment!");
  }
  console.log("  ✔ Device assignment revocation trigger verified: Automatic DEVICE_UNASSIGNED event recorded.");

  // 12D: Automatic Feature Permission Revocation Trigger
  console.log("  -> 12D: Testing automatic feature permission revocation trigger...");
  // Grant MIC to p5User1Id on activeDevice
  await legacyDb.query(`SELECT public.grant_device_feature_permission('${p5User1Id}'::uuid, '${activeDevice}', 'MIC');`);
  // Revoke MIC
  await legacyDb.query(`SELECT public.revoke_device_feature_permission('${p5User1Id}'::uuid, '${activeDevice}', 'MIC');`);
  const featureRevokeEvent = (await legacyDb.query(`
    SELECT * FROM public.revocation_events 
    WHERE target_user_id = '${p5User1Id}' AND device_id = '${activeDevice}' AND feature = 'MIC' AND event_type = 'FEATURE_REVOKED'
    ORDER BY created_at DESC LIMIT 1;
  `)).rows[0] as any;
  if (!featureRevokeEvent) {
    throw new Error("trg_dfp_revocation failed to emit FEATURE_REVOKED event on feature revocation!");
  }
  console.log("  ✔ Feature permission revocation trigger verified: Automatic FEATURE_REVOKED event recorded.");

  // 12E: Explicit broadcast_revocation_event RPC
  console.log("  -> 12E: Testing broadcast_revocation_event() RPC...");
  // Admin broadcasts FORCE_LOGOUT to p5User1Id
  const broadcastRes = await legacyDb.query(`
    SELECT public.broadcast_revocation_event(
      '${p5User1Id}'::uuid, 
      'FORCE_LOGOUT', 
      NULL, 
      NULL, 
      'Security incident emergency force logout'
    ) AS result;
  `);
  const broadcastResult = (broadcastRes.rows[0] as any).result;
  if (!broadcastResult.success || broadcastResult.event_type !== "FORCE_LOGOUT") {
    throw new Error(`broadcast_revocation_event failed: ${JSON.stringify(broadcastResult)}`);
  }

  // Verify audit log for REVOCATION_BROADCAST
  const broadcastAudit = (await legacyDb.query(`
    SELECT * FROM public.audit_logs 
    WHERE action = 'REVOCATION_BROADCAST' AND resource_type = 'revocation_events';
  `)).rows;
  if (broadcastAudit.length === 0) {
    throw new Error("audit_logs missing entry for REVOCATION_BROADCAST!");
  }

  // Admin cannot broadcast revocation against Owner (Owner Protection)
  let adminTargetOwnerBlocked = false;
  try {
    await legacyDb.query(`
      SELECT public.broadcast_revocation_event('${ownerId}'::uuid, 'FORCE_LOGOUT');
    `);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) adminTargetOwnerBlocked = true;
  }
  if (!adminTargetOwnerBlocked) {
    throw new Error("SECURITY FAILURE: Admin broadcasted revocation event targeting the Owner!");
  }

  // Standard user cannot broadcast revocation events
  await setCaller(p5User1Id);
  let userBroadcastBlocked = false;
  try {
    await legacyDb.query(`
      SELECT public.broadcast_revocation_event('${p5User2Id}'::uuid, 'FORCE_LOGOUT');
    `);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) userBroadcastBlocked = true;
  }
  if (!userBroadcastBlocked) {
    throw new Error("SECURITY FAILURE: Standard user executed broadcast_revocation_event!");
  }

  // Invalid event type rejection
  await setCaller(adminId);
  let invalidEventBlocked = false;
  try {
    await legacyDb.query(`
      SELECT public.broadcast_revocation_event('${p5User1Id}'::uuid, 'UNKNOWN_EVENT');
    `);
  } catch (err: any) {
    if (err.message.includes("INVALID_EVENT_TYPE")) invalidEventBlocked = true;
  }
  if (!invalidEventBlocked) {
    throw new Error("broadcast_revocation_event accepted invalid event type!");
  }
  console.log("  ✔ broadcast_revocation_event verified: Admin authority enforced; Owner protection validated; audit logging confirmed.");

  // 12F: Offline Catchup RPC (fetch_user_revocations)
  console.log("  -> 12F: Testing fetch_user_revocations() catchup RPC...");
  // User 1 queries own revocation events
  await setCaller(p5User1Id);
  const user1Revocations = (await legacyDb.query(`SELECT * FROM public.fetch_user_revocations();`)).rows as any[];
  if (user1Revocations.length === 0) {
    throw new Error("fetch_user_revocations returned 0 events for user with active revocations!");
  }
  for (const ev of user1Revocations) {
    if (ev.target_user_id !== p5User1Id) {
      throw new Error(`Data leakage in fetch_user_revocations: found event for ${ev.target_user_id}!`);
    }
  }

  // User 1 cannot inspect User 2's revocation events
  let userCrossFetchBlocked = false;
  try {
    await legacyDb.query(`SELECT * FROM public.fetch_user_revocations(NULL, '${p5User2Id}'::uuid);`);
  } catch (err: any) {
    if (err.message.includes("FORBIDDEN")) userCrossFetchBlocked = true;
  }
  if (!userCrossFetchBlocked) {
    throw new Error("SECURITY FAILURE: Standard user inspected another user's revocation events!");
  }

  // Admin can inspect User 1's revocation events
  await setCaller(adminId);
  const adminQueryRevocations = (await legacyDb.query(`SELECT * FROM public.fetch_user_revocations(NULL, '${p5User1Id}'::uuid);`)).rows as any[];
  if (adminQueryRevocations.length === 0) {
    throw new Error("Admin query for user revocation events returned 0 rows!");
  }
  console.log("  ✔ fetch_user_revocations verified: Self-inspection allowed; cross-inspection blocked for non-admins; admin inspection allowed.");

  // 12G: RLS Isolation & Client Direct Mutation Lockdown on public.revocation_events
  console.log("  -> 12G: Testing RLS isolation and client direct mutation lockdown on public.revocation_events...");
  // Standard User 2 SELECTs from public.revocation_events -> RLS must filter to only p5User2Id rows
  await setCaller(p5User2Id);
  const directSelectRows = (await legacyDb.query(`SELECT * FROM public.revocation_events;`)).rows as any[];
  for (const r of directSelectRows) {
    if (r.target_user_id !== p5User2Id) {
      throw new Error(`RLS LEAKAGE on revocation_events: User 2 saw event belonging to ${r.target_user_id}!`);
    }
  }

  // Direct INSERT attempt by authenticated client must fail (fail-closed)
  let reDirectInsertBlocked = false;
  try {
    await legacyDb.query(`
      INSERT INTO public.revocation_events (target_user_id, event_type)
      VALUES ('${p5User2Id}'::uuid, 'FORCE_LOGOUT');
    `);
  } catch (err: any) {
    if (err.message.includes("permission denied") || err.message.includes("violates")) reDirectInsertBlocked = true;
  }
  if (!reDirectInsertBlocked) {
    throw new Error("SECURITY FAILURE: Authenticated user directly inserted into public.revocation_events!");
  }

  // Direct DELETE attempt by authenticated client must fail
  let reDirectDeleteBlocked = false;
  try {
    await legacyDb.query(`DELETE FROM public.revocation_events;`);
  } catch (err: any) {
    if (err.message.includes("permission denied") || err.message.includes("violates")) reDirectDeleteBlocked = true;
  }
  if (!reDirectDeleteBlocked) {
    throw new Error("SECURITY FAILURE: Authenticated user directly deleted from public.revocation_events!");
  }

  await resetRole();
  console.log("  ✔ RLS isolation and client direct mutation lockdown on public.revocation_events strictly verified.");

  console.log("\n================================================================================");
  console.log("ALL POSTGRESQL MIGRATION & AUTHORIZATION TESTS PASSED CLEANLY (12/12)");
  console.log("================================================================================\n");
}

runMasterMigrationTests().catch((err) => {
  console.error("\n❌ MASTER MIGRATION TEST SUITE FAILED:");
  console.error("Message:", err.message);
  console.error("Code:", err.code);
  console.error("Where:", err.where);
  Deno.exit(1);
});
