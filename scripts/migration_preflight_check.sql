-- ============================================================================
-- TRACE V1 — Phase 7 Read-Only Migration Preflight Diagnostic
-- ============================================================================
--
-- Objective:
--   Inspect existing production database tables (public.locations, public.commands,
--   public.media, types, publications, migration history, existing RPCs) BEFORE
--   applying migrations 01 through 06.
--
-- Safety Guarantee:
--   STRICTLY READ-ONLY. No DDL, no INSERT, no UPDATE, no DELETE, no ALTER.
--   Zero table locks. Zero state changes.
--
-- DATA REMEDIATION POLICY:
--   NO AUTOMATIC DATA REMEDIATION. Automatic deletion, rewriting, quarantine,
--   or reassignment of existing records is strictly PROHIBITED.
--   Any data remediation action requires explicit operator review and manual approval.
--
-- Execution:
--   Run against the target database via Supabase Dashboard SQL Editor, psql,
--   or supabase db remote query.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- SECTION 1: public.locations Inspection
-- ----------------------------------------------------------------------------

-- 1A: Overall locations row counts and device_id health
SELECT 
    'locations_summary' AS check_name,
    COUNT(*) AS total_rows,
    COUNT(DISTINCT TRIM(device_id)) AS distinct_non_blank_devices,
    COUNT(*) FILTER (WHERE device_id IS NULL) AS null_device_id_count,
    COUNT(*) FILTER (WHERE device_id IS NOT NULL AND TRIM(device_id) = '') AS blank_device_id_count,
    COUNT(*) FILTER (WHERE device_id IS NOT NULL AND device_id != TRIM(device_id)) AS unpadded_whitespace_count,
    COUNT(*) FILTER (WHERE device_id IS NOT NULL AND TRIM(device_id) != '') AS valid_backfillable_rows
FROM public.locations;

-- 1B: Sample of problematic locations rows (if any exist)
SELECT 
    'locations_problematic_sample' AS check_name,
    id,
    created_at,
    device_id,
    latitude,
    longitude
FROM public.locations
WHERE device_id IS NULL OR TRIM(device_id) = ''
ORDER BY created_at DESC
LIMIT 10;

-- 1C: Device ID distribution in locations
SELECT 
    'locations_device_distribution' AS check_name,
    COALESCE(TRIM(device_id), '<NULL_OR_BLANK>') AS normalized_device_id,
    COUNT(*) AS location_count,
    MIN(created_at) AS first_seen,
    MAX(created_at) AS last_seen
FROM public.locations
GROUP BY TRIM(device_id)
ORDER BY location_count DESC;

-- ----------------------------------------------------------------------------
-- SECTION 2: public.commands Inspection
-- ----------------------------------------------------------------------------

-- 2A: Overall commands row counts and column health
SELECT 
    'commands_summary' AS check_name,
    COUNT(*) AS total_rows,
    COUNT(DISTINCT TRIM(device_id)) AS distinct_non_blank_devices,
    COUNT(*) FILTER (WHERE device_id IS NULL) AS null_device_id_count,
    COUNT(*) FILTER (WHERE device_id IS NOT NULL AND TRIM(device_id) = '') AS blank_device_id_count,
    COUNT(*) FILTER (WHERE params IS NULL) AS null_params_count,
    COUNT(*) FILTER (WHERE params IS NOT NULL AND jsonb_typeof(params) != 'object') AS non_object_params_count,
    COUNT(*) FILTER (WHERE status IS NULL) AS null_status_count
FROM public.commands;

-- 2B: Sample of problematic commands rows (if any exist)
SELECT 
    'commands_problematic_sample' AS check_name,
    id,
    command,
    device_id,
    status,
    created_at
FROM public.commands
WHERE device_id IS NULL 
   OR TRIM(device_id) = '' 
   OR params IS NULL 
   OR jsonb_typeof(params) != 'object'
ORDER BY created_at DESC
LIMIT 10;

-- 2C: Device ID distribution in commands
SELECT 
    'commands_device_distribution' AS check_name,
    COALESCE(TRIM(device_id), '<NULL_OR_BLANK>') AS normalized_device_id,
    COUNT(*) AS command_count,
    MIN(created_at) AS first_command,
    MAX(created_at) AS last_command
FROM public.commands
GROUP BY TRIM(device_id)
ORDER BY command_count DESC;

-- ----------------------------------------------------------------------------
-- SECTION 3: public.media Inspection (if table exists)
-- ----------------------------------------------------------------------------
SELECT 
    'media_summary' AS check_name,
    COUNT(*) AS total_rows,
    COUNT(DISTINCT TRIM(device_id)) AS distinct_non_blank_devices,
    COUNT(*) FILTER (WHERE device_id IS NULL) AS null_device_id_count,
    COUNT(*) FILTER (WHERE device_id IS NOT NULL AND TRIM(device_id) = '') AS blank_device_id_count
FROM public.media
WHERE EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'media');

-- ----------------------------------------------------------------------------
-- SECTION 4: Cross-Table Orphan & Blank Validation (locations & commands)
-- ----------------------------------------------------------------------------
-- Evaluates whether any record in public.locations or public.commands would
-- fail foreign key constraint validation if public.devices is populated.

WITH candidate_devices AS (
    SELECT DISTINCT TRIM(device_id) AS device_id
    FROM public.locations
    WHERE device_id IS NOT NULL AND TRIM(device_id) != ''
    UNION
    SELECT DISTINCT TRIM(device_id) AS device_id
    FROM public.commands
    WHERE device_id IS NOT NULL AND TRIM(device_id) != ''
)
SELECT 
    'orphan_and_blank_validation' AS check_name,
    (SELECT COUNT(*) FROM public.locations l 
     WHERE l.device_id IS NOT NULL 
       AND TRIM(l.device_id) != '' 
       AND TRIM(l.device_id) NOT IN (SELECT device_id FROM candidate_devices)
    ) AS locations_orphans_count,
    (SELECT COUNT(*) FROM public.commands c 
     WHERE c.device_id IS NOT NULL 
       AND TRIM(c.device_id) != '' 
       AND TRIM(c.device_id) NOT IN (SELECT device_id FROM candidate_devices)
    ) AS commands_orphans_count,
    (SELECT COUNT(*) FROM public.locations WHERE device_id IS NULL OR TRIM(device_id) = '') AS locations_blank_or_null_count,
    (SELECT COUNT(*) FROM public.commands WHERE device_id IS NULL OR TRIM(device_id) = '') AS commands_blank_or_null_count;

-- ----------------------------------------------------------------------------
-- SECTION 5: Schema Environment & Types Preflight
-- ----------------------------------------------------------------------------

-- 5A: Check existing enums
SELECT 
    'enum_check' AS check_name,
    t.typname AS enum_name,
    e.enumlabel AS enum_value
FROM pg_type t
JOIN pg_enum e ON t.oid = e.enumtypid
WHERE t.typname IN ('account_status', 'user_role', 'device_status', 'command_status')
ORDER BY t.typname, e.enumsortorder;

-- 5B: Check Realtime Publication configuration
SELECT 
    'realtime_publication_tables' AS check_name,
    pubname,
    schemaname,
    tablename
FROM pg_publication_tables
WHERE pubname = 'supabase_realtime'
ORDER BY tablename;

-- 5C: Check existing sequences
SELECT 
    'sequence_check' AS check_name,
    sequence_schema,
    sequence_name,
    data_type
FROM information_schema.sequences
WHERE sequence_schema = 'public'
ORDER BY sequence_name;

-- ----------------------------------------------------------------------------
-- SECTION 6: Current Remote Migration History Verification
-- ----------------------------------------------------------------------------
-- Inspects existing recorded migrations in supabase_migrations.schema_migrations
SELECT 
    'applied_migrations_table_status' AS check_name,
    to_regclass('supabase_migrations.schema_migrations') IS NOT NULL AS schema_migrations_table_exists;

DO $$
DECLARE
    r RECORD;
BEGIN
    IF to_regclass('supabase_migrations.schema_migrations') IS NOT NULL THEN
        FOR r IN EXECUTE 'SELECT version, inserted_at FROM supabase_migrations.schema_migrations ORDER BY version ASC' LOOP
            RAISE NOTICE 'Recorded migration: % at %', r.version, r.inserted_at;
        END LOOP;
    ELSE
        RAISE NOTICE 'supabase_migrations.schema_migrations does not exist (no remote migrations recorded).';
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- SECTION 7: Existing Schema Compatibility & Required RPCs Audit
-- ----------------------------------------------------------------------------

-- 7A: Audit existing target tables
SELECT 
    'existing_target_tables' AS check_name,
    table_schema,
    table_name
FROM information_schema.tables
WHERE table_schema IN ('public', 'private')
  AND table_name IN (
    'profiles', 'devices', 'user_device_assignments', 'device_feature_permissions',
    'audit_logs', 'enrolled_devices', 'device_secrets', 'system_bootstrap'
  )
ORDER BY table_schema, table_name;

-- 7B: Audit existing RPCs / functions in public schema
SELECT 
    'existing_rpcs_audit' AS check_name,
    routine_name,
    routine_type,
    data_type AS return_type
FROM information_schema.routines
WHERE routine_schema = 'public'
  AND routine_name IN (
    'submit_tracker_telemetry', 'acknowledge_command', 'check_idempotency_or_allocate_id',
    'enroll_tracker_device', 'fetch_managed_users', 'approve_user_account',
    'update_user_account_status', 'update_user_account_role',
    'grant_device_feature_permission', 'revoke_device_feature_permission',
    'broadcast_revocation_event'
  )
ORDER BY routine_name;

-- ----------------------------------------------------------------------------
-- SECTION 8: Database Backup & Rollback Plan Pre-check
-- ----------------------------------------------------------------------------
SELECT 
    'backup_readiness_info' AS check_name,
    current_database() AS database_name,
    pg_size_pretty(pg_database_size(current_database())) AS database_size,
    current_setting('wal_level') AS wal_level,
    current_setting('archive_mode', true) AS archive_mode;

-- ============================================================================
-- OPERATOR REMEDIATION DECISION MATRIX (REMEDIAL ACTION REQUIRES OPERATOR APPROVAL)
-- ============================================================================
--
-- STRICT POLICY:
--   DO NOT delete, rewrite, quarantine, or reassign existing records automatically.
--   Any remediation must be explicitly reviewed and authorized by the operator.
--
-- Scenario 1: locations_blank_or_null_count > 0
--   RISK: Migration 01 validates constraint 'fk_locations_device_id'.
--         Rows with TRIM(device_id) = '' will cause VALIDATE CONSTRAINT to FAIL.
--   OPERATOR DECISION REQUIRED:
--     Option 1A (Operator-Approved Quarantine):
--       Operator reviews quarantined records before moving:
--       CREATE TABLE public.locations_legacy_unattributed AS 
--         SELECT * FROM public.locations WHERE device_id IS NULL OR TRIM(device_id) = '';
--       DELETE FROM public.locations WHERE device_id IS NULL OR TRIM(device_id) = '';
--     Option 1B (Manual Attribution by Operator):
--       Operator determines device identity:
--       UPDATE public.locations SET device_id = '<operator_verified_id>' WHERE ...;
--     Option 1C (Operator-Approved Sentinel Legacy Device):
--       INSERT INTO public.devices (device_id, name, model, status, is_legacy)
--       VALUES ('legacy-unattributed-device', 'Legacy Unattributed Records', 'Unknown', 'REVOKED', TRUE);
--       UPDATE public.locations SET device_id = 'legacy-unattributed-device' WHERE device_id IS NULL OR TRIM(device_id) = '';
--
-- Scenario 2: commands_blank_or_null_count > 0
--   RISK: Migration 02 adds constraint 'fk_commands_device_id'.
--         Blank device_id will cause ADD CONSTRAINT to FAIL.
--   OPERATOR DECISION REQUIRED:
--     Option 2A: Operator-approved quarantine to public.commands_legacy_unattributed.
--     Option 2B: Manual attribution to operator-verified device ID.
-- ============================================================================
