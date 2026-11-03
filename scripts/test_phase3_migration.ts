/**
 * TRACE Phase 3.4.3 — PostgreSQL Migration & Security Privilege Test Suite
 * Executes against true PostgreSQL (PGlite v18 WASM runtime)
 */

import { PGlite } from "npm:@electric-sql/pglite";
import * as fs from "node:fs";
import * as path from "node:path";

async function runPhase3MigrationTests() {
  console.log("=== TRACE Phase 3.4.3: PostgreSQL Migration & Privilege Verification ===");

  const phase2aSqlPath = path.resolve("supabase/migrations/20261017000001_trace_phase2a_core_schema.sql");
  const phase2bSqlPath = path.resolve("supabase/migrations/20261017000002_trace_phase2b_commands_gateway.sql");
  const phase3SqlPath = path.resolve("supabase/migrations/20261017000003_trace_phase3_authenticated_acks.sql");

  const phase2aSql = fs.readFileSync(phase2aSqlPath, "utf-8");
  const phase2bSql = fs.readFileSync(phase2bSqlPath, "utf-8");
  const phase3Sql = fs.readFileSync(phase3SqlPath, "utf-8");

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

    CREATE OR REPLACE FUNCTION auth.uid() RETURNS UUID AS $$
      SELECT '00000000-0000-0000-0000-000000000001'::uuid;
    $$ LANGUAGE sql;
  `;

  const db = new PGlite();
  await db.query("CREATE SCHEMA IF NOT EXISTS auth;");
  await db.exec(setupSupabasePrelude);

  await db.exec(`
    CREATE TABLE public.locations (
      id BIGINT PRIMARY KEY,
      device_id TEXT NOT NULL,
      lat FLOAT8 NOT NULL,
      lng FLOAT8 NOT NULL,
      created_at TIMESTAMPTZ DEFAULT NOW()
    );
  `);

  // Apply Phase 2A migration
  console.log("\n[STEP 1] Applying Phase 2A core schema migration...");
  await db.exec(phase2aSql);
  console.log("  ✔ Phase 2A migration applied successfully.");

  // Apply Phase 2B migration
  console.log("\n[STEP 2] Applying Phase 2B schema migration...");
  await db.exec(phase2bSql);
  console.log("  ✔ Phase 2B migration applied successfully.");

  // Apply Phase 3 migration
  console.log("\n[STEP 3] Applying Phase 3 authenticated ACKs migration...");
  await db.exec(phase3Sql);
  console.log("  ✔ Phase 3 migration applied successfully.");

  // Test 1: Verify Table Privileges on public.commands
  console.log("\n[TEST 1] Verifying public.commands table privileges...");
  const tablePrivs = await db.query(`
    SELECT 
      has_table_privilege('anon', 'public.commands', 'UPDATE') as anon_update,
      has_table_privilege('anon', 'public.commands', 'INSERT') as anon_insert,
      has_table_privilege('anon', 'public.commands', 'DELETE') as anon_delete,
      has_table_privilege('authenticated', 'public.commands', 'UPDATE') as auth_update,
      has_table_privilege('authenticated', 'public.commands', 'INSERT') as auth_insert,
      has_table_privilege('authenticated', 'public.commands', 'DELETE') as auth_delete,
      has_table_privilege('service_role', 'public.commands', 'UPDATE') as sr_update
  `);
  const row = (tablePrivs.rows as any[])[0];
  if (row.anon_update || row.anon_insert || row.anon_delete) {
    throw new Error(`FAIL: anon has direct mutation privileges on commands! ${JSON.stringify(row)}`);
  }
  if (row.auth_update || row.auth_insert || row.auth_delete) {
    throw new Error(`FAIL: authenticated has direct mutation privileges on commands! ${JSON.stringify(row)}`);
  }
  if (!row.sr_update) {
    throw new Error(`FAIL: service_role lacks UPDATE on commands! ${JSON.stringify(row)}`);
  }
  console.log("  ✔ Direct table mutations on public.commands strictly revoked from anon and authenticated.");

  // Test 2: Verify RPC EXECUTE Privileges on acknowledge_command
  console.log("\n[TEST 2] Verifying RPC EXECUTE privileges across all acknowledge_command overloads...");
  const rpcPrivs = await db.query(`
    SELECT p.oid::regprocedure AS proc_name,
      has_function_privilege('anon', p.oid, 'EXECUTE') as anon_exec,
      has_function_privilege('authenticated', p.oid, 'EXECUTE') as auth_exec,
      has_function_privilege('public', p.oid, 'EXECUTE') as pub_exec,
      has_function_privilege('service_role', p.oid, 'EXECUTE') as sr_exec
    FROM pg_proc p
    JOIN pg_namespace n ON p.pronamespace = n.oid
    WHERE p.proname = 'acknowledge_command' AND n.nspname = 'public'
  `);
  
  if (rpcPrivs.rows.length === 0) {
    throw new Error("FAIL: No acknowledge_command function found in public schema!");
  }

  for (const proc of (rpcPrivs.rows as any[])) {
    console.log(`  Checking ${proc.proc_name}: anon=${proc.anon_exec}, auth=${proc.auth_exec}, public=${proc.pub_exec}, service_role=${proc.sr_exec}`);
    if (proc.anon_exec || proc.auth_exec || proc.pub_exec) {
      throw new Error(`FAIL: ${proc.proc_name} is executable by untrusted role!`);
    }
    if (!proc.sr_exec) {
      throw new Error(`FAIL: ${proc.proc_name} is not executable by service_role!`);
    }
  }
  console.log("  ✔ EXECUTE privilege strictly revoked from PUBLIC/anon/authenticated and granted ONLY to service_role.");

  // Test 3: Seed enrolled device and command for functional testing
  console.log("\n[TEST 3] Testing acknowledge_command invariants under service_role...");
  await db.exec(`
    INSERT INTO public.devices (device_id, name, model, status) VALUES
      ('pixel-7-001', 'Pixel 7', 'Google Pixel', 'ACTIVE'),
      ('revoked-dev-999', 'Revoked Pixel', 'Google Pixel', 'REVOKED')
    ON CONFLICT (device_id) DO NOTHING;

    INSERT INTO public.enrolled_devices (device_id, public_key_spki, is_revoked)
    VALUES ('pixel-7-001', 'MHYwEAYHKoZIzj0CAQYFK4EEACIDYgA...', false);
    
    INSERT INTO public.enrolled_devices (device_id, public_key_spki, is_revoked)
    VALUES ('revoked-dev-999', 'MHYwEAYHKoZIzj0CAQYFK4EEACIDYgA...', true);

    INSERT INTO public.commands (id, device_id, command, status, idempotency_key)
    VALUES (1001, 'pixel-7-001', 'start_camera', 'pending'::command_delivery_status, 'b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid);
  `);

  const nowSec = Math.floor(Date.now() / 1000);

  // 3a. Unenrolled device rejection
  try {
    await db.query(`SELECT public.acknowledge_command(1001, 'unknown-device', 'b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid, 'executed', $1)`, [nowSec]);
    throw new Error("FAIL: Unenrolled device was not rejected!");
  } catch (e: any) {
    if (!e.message.includes("UNAUTHORIZED_DEVICE")) throw e;
    console.log("  ✔ Unenrolled device rejected with UNAUTHORIZED_DEVICE.");
  }

  // 3b. Revoked device rejection
  try {
    await db.query(`SELECT public.acknowledge_command(1001, 'revoked-dev-999', 'b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid, 'executed', $1)`, [nowSec]);
    throw new Error("FAIL: Revoked device was not rejected!");
  } catch (e: any) {
    if (!e.message.includes("DEVICE_REVOKED")) throw e;
    console.log("  ✔ Revoked device rejected with DEVICE_REVOKED.");
  }

  // 3c. Stale timestamp rejection (> 300s)
  try {
    await db.query(`SELECT public.acknowledge_command(1001, 'pixel-7-001', 'b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid, 'executed', $1)`, [nowSec - 500]);
    throw new Error("FAIL: Stale timestamp was not rejected!");
  } catch (e: any) {
    if (!e.message.includes("ACK_EXPIRED")) throw e;
    console.log("  ✔ Stale timestamp rejected with ACK_EXPIRED.");
  }

  // 3d. Cross-device spoofing rejection
  try {
    await db.exec(`
      INSERT INTO public.devices (device_id, name, model, status) VALUES ('pixel-7-002', 'Pixel 7 #2', 'Pixel', 'ACTIVE') ON CONFLICT (device_id) DO NOTHING;
      INSERT INTO public.enrolled_devices (device_id, public_key_spki) VALUES ('pixel-7-002', 'dummy');
      INSERT INTO public.commands (id, device_id, command, status, idempotency_key) VALUES (1002, 'pixel-7-001', 'start_camera', 'pending', 'c3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid);
    `);

    await db.query(`SELECT public.acknowledge_command(1002, 'pixel-7-002', 'c3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid, 'executed', $1)`, [nowSec]);
    throw new Error("FAIL: Cross-device ACK was not rejected!");
  } catch (e: any) {
    if (!e.message.includes("CROSS_DEVICE_ACK_DENIED")) throw e;
    console.log("  ✔ Cross-device ACK rejected with CROSS_DEVICE_ACK_DENIED.");
  }

  // 3e. Valid state transition
  const validRes = await db.query(`
    SELECT public.acknowledge_command(1001, 'pixel-7-001', 'b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid, 'executed', $1, 'result-digest-123') as result
  `, [nowSec]);
  const resObj = (validRes.rows as any[])[0].result;
  if (resObj.status !== "SUCCESS") {
    throw new Error(`FAIL: Valid ACK failed: ${JSON.stringify(resObj)}`);
  }
  console.log("  ✔ Valid state transition pending -> executed succeeded.");

  // 3f. Idempotent re-ACK returns ALREADY_ACKNOWLEDGED
  const replayRes = await db.query(`
    SELECT public.acknowledge_command(1001, 'pixel-7-001', 'b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid, 'executed', $1) as result
  `, [nowSec]);
  const replayObj = (replayRes.rows as any[])[0].result;
  if (replayObj.status !== "ALREADY_ACKNOWLEDGED") {
    throw new Error(`FAIL: Idempotent replay failed: ${JSON.stringify(replayObj)}`);
  }
  console.log("  ✔ Idempotent duplicate ACK returns ALREADY_ACKNOWLEDGED.");

  // 3g. Terminal state regression rejection (cannot transition executed -> failed)
  try {
    await db.query(`SELECT public.acknowledge_command(1001, 'pixel-7-001', 'b3c0e12a-4df1-4a7b-891a-7b2c8e5f1234'::uuid, 'failed', $1)`, [nowSec]);
    throw new Error("FAIL: Terminal state regression was not rejected!");
  } catch (e: any) {
    if (!e.message.includes("TERMINAL_STATE_REGRESSION")) throw e;
    console.log("  ✔ Terminal state regression executed -> failed strictly rejected.");
  }

  console.log("\nALL POSTGRESQL INVARIANTS & PRIVILEGE LOCKDOWNS VERIFIED 100% PASS!");
}

runPhase3MigrationTests().catch((err) => {
  console.error("FATAL TEST FAILURE:");
  console.error("Message:", err.message);
  console.error("Code:", err.code);
  console.error("Where:", err.where);
  process.exit(1);
});
