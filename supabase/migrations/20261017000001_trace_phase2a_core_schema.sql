-- ============================================================================
-- TRACE Phase 2A — Database Migration: Core Schema, RBAC & Hardened RLS
-- Target Schema: Core identity, hardware registry, device assignments,
-- feature permissions, audit logs, private secrets, and staging buffer.
--
-- Adheres strictly to:
-- - Owner A1 Policy (Universal operational authority)
-- - Admin B2 Policy (Fleet-wide location telemetry; assignment required for sensitive operations)
-- - Zero data loss: 105,930+ historical coordinates & 14 historical commands physically preserved
-- - Non-blocking foreign key protocol (NOT VALID + VALIDATE CONSTRAINT)
-- - Sensitive credential isolation (private.device_secrets)
-- ============================================================================

-- 1. Create Isolated Schemas and Extensions
CREATE SCHEMA IF NOT EXISTS private;
CREATE SCHEMA IF NOT EXISTS extensions;

DO $$ BEGIN
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA extensions;
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

DO $$ BEGIN
    CREATE EXTENSION IF NOT EXISTS "pgcrypto" WITH SCHEMA extensions;
EXCEPTION WHEN OTHERS THEN NULL;
END $$;

-- 2. Custom Role & Status Enums
DO $$ BEGIN
    CREATE TYPE user_role AS ENUM ('OWNER', 'ADMIN', 'USER', 'VISITOR');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE account_status AS ENUM ('PENDING', 'ACTIVE', 'SUSPENDED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE device_status AS ENUM ('PENDING', 'ACTIVE', 'SUSPENDED', 'REVOKED', 'DECOMMISSIONED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- 3. Enterprise Identity & User Profiles (public.profiles)
-- Tied to auth.users catalog
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    role user_role NOT NULL DEFAULT 'VISITOR'::user_role,
    status account_status NOT NULL DEFAULT 'PENDING'::account_status,
    display_name TEXT NOT NULL DEFAULT '',
    email TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_profiles_role ON public.profiles(role);
CREATE INDEX IF NOT EXISTS idx_profiles_status ON public.profiles(status);

-- Single-Owner Constraint: At most ONE active Owner identity in the system
CREATE UNIQUE INDEX IF NOT EXISTS idx_single_owner_constraint 
    ON public.profiles (role) 
    WHERE role = 'OWNER';

-- 4. Hardware Registry (public.devices)
-- Hardware device metadata. NOTE: api_key_hash and api_key_salt are isolated in private.device_secrets!
CREATE TABLE IF NOT EXISTS public.devices (
    device_id TEXT PRIMARY KEY,
    name TEXT NOT NULL DEFAULT '',
    model TEXT NOT NULL DEFAULT '',
    status device_status NOT NULL DEFAULT 'ACTIVE'::device_status,
    is_legacy BOOLEAN NOT NULL DEFAULT FALSE,
    hardware_uuid TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at TIMESTAMPTZ NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_status ON public.devices(status);

-- 5. User-to-Device Assignments (public.user_device_assignments)
CREATE TABLE IF NOT EXISTS public.user_device_assignments (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES public.devices(device_id) ON DELETE RESTRICT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NULL,
    assigned_by UUID NULL REFERENCES public.profiles(id) ON DELETE SET NULL,
    CONSTRAINT uq_user_device UNIQUE (user_id, device_id)
);

CREATE INDEX IF NOT EXISTS idx_uda_user ON public.user_device_assignments(user_id);
CREATE INDEX IF NOT EXISTS idx_uda_device ON public.user_device_assignments(device_id);
CREATE INDEX IF NOT EXISTS idx_uda_active ON public.user_device_assignments(user_id, device_id, status)
    WHERE status = 'ACTIVE';

-- 6. Granular Device Feature Permissions (public.device_feature_permissions)
CREATE TABLE IF NOT EXISTS public.device_feature_permissions (
    id BIGSERIAL PRIMARY KEY,
    assignment_id BIGINT NOT NULL REFERENCES public.user_device_assignments(id) ON DELETE CASCADE,
    feature TEXT NOT NULL CHECK (feature IN ('LOCATION', 'CAMERA', 'FILES', 'MIC', 'SCREEN', 'COMMANDS')),
    is_allowed BOOLEAN NOT NULL DEFAULT TRUE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NULL,
    granted_by UUID NULL REFERENCES public.profiles(id) ON DELETE SET NULL,
    CONSTRAINT uq_assignment_feature UNIQUE (assignment_id, feature)
);

CREATE INDEX IF NOT EXISTS idx_dfp_lookup 
    ON public.device_feature_permissions(assignment_id, feature, is_allowed);

-- 7. Immutable Audit Logs (public.audit_logs)
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID NULL REFERENCES public.profiles(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    payload JSONB DEFAULT '{}'::jsonb,
    ip_address TEXT NULL,
    user_agent TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_actor ON public.audit_logs(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON public.audit_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON public.audit_logs(resource_type, resource_id);

-- 8. Tracker Telemetry Staging Buffer (public.tracker_telemetry_staging)
-- Used for Option B telemetry ingestion buffer
CREATE TABLE IF NOT EXISTS public.tracker_telemetry_staging (
    id BIGSERIAL PRIMARY KEY,
    device_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_processed BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMPTZ NULL,
    error_message TEXT NULL
);

CREATE INDEX IF NOT EXISTS idx_staging_unprocessed 
    ON public.tracker_telemetry_staging(is_processed, created_at) 
    WHERE is_processed = FALSE;

-- 9. Private Schema Infrastructure (Credential & Secret Isolation)
CREATE TABLE IF NOT EXISTS private.system_bootstrap (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    token_hash TEXT NOT NULL,
    salt TEXT NOT NULL,
    is_claimed BOOLEAN NOT NULL DEFAULT FALSE,
    claimed_by UUID NULL REFERENCES auth.users(id),
    claimed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS private.device_secrets (
    device_id TEXT PRIMARY KEY REFERENCES public.devices(device_id) ON DELETE CASCADE,
    api_key_hash TEXT NOT NULL,
    api_key_salt TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS private.legacy_ingest_credentials (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    secret_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS private.staging_storage_counter (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    unprocessed_count INTEGER NOT NULL DEFAULT 0
);

INSERT INTO private.staging_storage_counter (id, unprocessed_count) 
VALUES (1, 0) 
ON CONFLICT (id) DO NOTHING;

-- Dynamic legacy ingest secret provisioning (zero hardcoded plaintext secrets in DDL)
CREATE OR REPLACE FUNCTION private.set_legacy_ingest_secret(p_secret TEXT)
RETURNS VOID AS $$
BEGIN
    IF p_secret IS NULL OR TRIM(p_secret) = '' THEN
        RAISE EXCEPTION 'INVALID_SECRET: Secret cannot be empty' USING ERRCODE = '22023';
    END IF;

    INSERT INTO private.legacy_ingest_credentials (id, secret_hash, created_at)
    VALUES (1, encode(sha256(p_secret::bytea), 'hex'), NOW())
    ON CONFLICT (id) DO UPDATE SET
        secret_hash = EXCLUDED.secret_hash,
        created_at = NOW();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = private, extensions, pg_temp;

-- Lockdown private schema completely
REVOKE ALL ON SCHEMA private FROM PUBLIC, anon, authenticated;
GRANT USAGE ON SCHEMA private TO service_role;
GRANT ALL ON ALL TABLES IN SCHEMA private TO service_role;
REVOKE ALL ON FUNCTION private.set_legacy_ingest_secret(TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION private.set_legacy_ingest_secret(TEXT) TO service_role;

-- 10. Multi-Table Legacy Device Backfill
-- Safely populate public.devices from 105k+ locations, historical commands, and media
DO $$
BEGIN
    -- Backfill from public.locations
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'locations') THEN
        INSERT INTO public.devices (device_id, name, model, status, is_legacy, created_at)
        SELECT DISTINCT 
            TRIM(device_id),
            'Legacy Tracker ' || SUBSTRING(TRIM(device_id) FROM 1 FOR 8),
            'Legacy Hardware',
            'ACTIVE'::device_status,
            TRUE,
            NOW()
        FROM public.locations
        WHERE device_id IS NOT NULL AND TRIM(device_id) != ''
        ON CONFLICT (device_id) DO NOTHING;
    END IF;

    -- Backfill from public.commands
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'commands') THEN
        INSERT INTO public.devices (device_id, name, model, status, is_legacy, created_at)
        SELECT DISTINCT 
            TRIM(device_id),
            'Legacy Tracker ' || SUBSTRING(TRIM(device_id) FROM 1 FOR 8),
            'Legacy Hardware',
            'ACTIVE'::device_status,
            TRUE,
            NOW()
        FROM public.commands
        WHERE device_id IS NOT NULL AND TRIM(device_id) != ''
        ON CONFLICT (device_id) DO NOTHING;
    END IF;

    -- Backfill from public.media
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'media') THEN
        INSERT INTO public.devices (device_id, name, model, status, is_legacy, created_at)
        SELECT DISTINCT 
            TRIM(device_id),
            'Legacy Tracker ' || SUBSTRING(TRIM(device_id) FROM 1 FOR 8),
            'Legacy Hardware',
            'ACTIVE'::device_status,
            TRUE,
            NOW()
        FROM public.media
        WHERE device_id IS NOT NULL AND TRIM(device_id) != ''
        ON CONFLICT (device_id) DO NOTHING;
    END IF;
END $$;

-- 11. Non-Blocking Referential Integrity (NOT VALID + VALIDATE CONSTRAINT)
-- Applies ON DELETE RESTRICT on 105k+ location rows without table locking
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'locations') THEN
        -- Clean whitespace on locations
        UPDATE public.locations SET device_id = TRIM(device_id) WHERE device_id != TRIM(device_id);

        -- Ensure monotonic default id sequence on locations if not present
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_schema = 'public' AND table_name = 'locations' AND column_name = 'id' AND column_default IS NOT NULL
        ) THEN
            CREATE SEQUENCE IF NOT EXISTS public.locations_id_seq AS BIGINT;
            ALTER SEQUENCE public.locations_id_seq OWNED BY public.locations.id;
            ALTER TABLE public.locations ALTER COLUMN id SET DEFAULT nextval('public.locations_id_seq');
            PERFORM setval('public.locations_id_seq', COALESCE((SELECT MAX(id) FROM public.locations), 0) + 1, false);
        END IF;

        -- Add constraint as NOT VALID (instant catalog update, zero lock)
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_locations_device_id') THEN
            ALTER TABLE public.locations 
                ADD CONSTRAINT fk_locations_device_id 
                FOREIGN KEY (device_id) REFERENCES public.devices(device_id) 
                ON DELETE RESTRICT NOT VALID;
        END IF;

        -- Validate constraint (SHARE UPDATE EXCLUSIVE lock)
        ALTER TABLE public.locations VALIDATE CONSTRAINT fk_locations_device_id;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'media') THEN
        -- Clean whitespace on media
        UPDATE public.media SET device_id = TRIM(device_id) WHERE device_id != TRIM(device_id);

        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_media_device_id') THEN
            ALTER TABLE public.media 
                ADD CONSTRAINT fk_media_device_id 
                FOREIGN KEY (device_id) REFERENCES public.devices(device_id) 
                ON DELETE RESTRICT NOT VALID;
        END IF;

        ALTER TABLE public.media VALIDATE CONSTRAINT fk_media_device_id;
    END IF;
END $$;

-- 12. Hardened Security Definer Helper Functions
-- Pinned search_path: public, private, extensions, pg_temp;

-- A. Resolve active role from public.profiles
CREATE OR REPLACE FUNCTION public.current_user_role()
RETURNS user_role AS $$
    SELECT role FROM public.profiles WHERE id = auth.uid() AND status = 'ACTIVE';
$$ LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- B. Owner A1 predicate
CREATE OR REPLACE FUNCTION public.is_owner()
RETURNS BOOLEAN AS $$
    SELECT COALESCE((public.current_user_role() = 'OWNER'), FALSE);
$$ LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- C. Admin or Owner predicate
CREATE OR REPLACE FUNCTION public.is_admin_or_owner()
RETURNS BOOLEAN AS $$
    SELECT COALESCE((public.current_user_role() IN ('OWNER', 'ADMIN')), FALSE);
$$ LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- D. Device Access Predicate (Owner A1 + Visitor Quarantine)
CREATE OR REPLACE FUNCTION public.has_device_access(p_device_id TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    IF p_device_id IS NULL OR TRIM(p_device_id) = '' THEN 
        RETURN FALSE; 
    END IF;
    
    -- Owner A1 Policy: Universal operational authority across all devices
    IF public.is_owner() THEN 
        RETURN TRUE; 
    END IF;

    -- Strict fail-closed for VISITOR, PENDING, SUSPENDED, or missing role
    IF public.current_user_role() IS NULL OR public.current_user_role() = 'VISITOR' THEN 
        RETURN FALSE; 
    END IF;

    RETURN EXISTS (
        SELECT 1 
        FROM public.user_device_assignments uda
        JOIN public.devices d ON d.device_id = uda.device_id
        WHERE uda.user_id = auth.uid()
          AND uda.device_id = p_device_id
          AND uda.status = 'ACTIVE'
          AND (uda.expires_at IS NULL OR uda.expires_at > NOW())
          AND d.status = 'ACTIVE'
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- E. Feature Permission Predicate (Owner A1 + Visitor Quarantine)
CREATE OR REPLACE FUNCTION public.has_feature_permission(p_device_id TEXT, p_feature TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    IF p_device_id IS NULL OR TRIM(p_device_id) = '' OR p_feature IS NULL OR TRIM(p_feature) = '' THEN
        RETURN FALSE;
    END IF;

    -- Owner A1 Policy: Universal privileges across all features
    IF public.is_owner() THEN 
        RETURN TRUE; 
    END IF;

    -- Strict fail-closed for VISITOR, PENDING, SUSPENDED
    IF public.current_user_role() IS NULL OR public.current_user_role() = 'VISITOR' THEN 
        RETURN FALSE; 
    END IF;

    RETURN EXISTS (
        SELECT 1 
        FROM public.user_device_assignments uda
        JOIN public.device_feature_permissions dfp ON dfp.assignment_id = uda.id
        JOIN public.devices d ON d.device_id = uda.device_id
        WHERE uda.user_id = auth.uid()
          AND uda.device_id = p_device_id
          AND uda.status = 'ACTIVE'
          AND (uda.expires_at IS NULL OR uda.expires_at > NOW())
          AND dfp.feature = UPPER(TRIM(p_feature))
          AND dfp.is_allowed = TRUE
          AND (dfp.expires_at IS NULL OR dfp.expires_at > NOW())
          AND d.status = 'ACTIVE'
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- F. Location Read Access Predicate (Owner A1 + Admin B2 Fleet Read)
CREATE OR REPLACE FUNCTION public.has_location_read_access(p_device_id TEXT)
RETURNS BOOLEAN AS $$
BEGIN
    IF p_device_id IS NULL OR TRIM(p_device_id) = '' THEN 
        RETURN FALSE; 
    END IF;

    -- Owner A1 Policy: Universal read access
    IF public.is_owner() THEN 
        RETURN TRUE; 
    END IF;

    -- Admin B2 Policy: Active Admins can read location telemetry fleet-wide without device assignment
    IF public.current_user_role() = 'ADMIN' THEN 
        RETURN TRUE; 
    END IF;

    -- Standard Users require active device assignment AND explicit LOCATION feature permission
    RETURN public.has_device_access(p_device_id) 
       AND public.has_feature_permission(p_device_id, 'LOCATION');
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

-- Revoke execute on helper functions from PUBLIC, grant to authenticated and service_role
REVOKE ALL ON FUNCTION public.current_user_role() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.is_owner() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.is_admin_or_owner() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.has_device_access(TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.has_feature_permission(TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.has_location_read_access(TEXT) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.current_user_role() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.is_owner() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.is_admin_or_owner() TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.has_device_access(TEXT) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.has_feature_permission(TEXT, TEXT) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.has_location_read_access(TEXT) TO authenticated, service_role;

-- 13. Secure Owner Bootstrap RPC
-- Single-use token claim with advisory transaction lock
CREATE OR REPLACE FUNCTION public.bootstrap_claim_owner(p_bootstrap_token TEXT)
RETURNS JSONB AS $$
DECLARE
    v_caller_id UUID;
    v_bootstrap RECORD;
    v_token_hash TEXT;
    v_existing_owner UUID;
BEGIN
    -- 1. Ensure caller is authenticated
    v_caller_id := auth.uid();
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'UNAUTHENTICATED: Caller must be an authenticated Supabase user'
            USING ERRCODE = '28000';
    END IF;

    -- 2. Acquire advisory lock to prevent concurrent bootstrap claims
    IF NOT pg_try_advisory_xact_lock(hashtext('trace_owner_bootstrap')) THEN
        RAISE EXCEPTION 'LOCK_BUSY: Bootstrap claim already in progress'
            USING ERRCODE = '55P03';
    END IF;

    -- 3. Check if an Owner already exists
    SELECT id INTO v_existing_owner FROM public.profiles WHERE role = 'OWNER' LIMIT 1;
    IF v_existing_owner IS NOT NULL THEN
        RAISE EXCEPTION 'OWNER_EXISTS: An Owner is already established (ID: %)', v_existing_owner
            USING ERRCODE = 'P0002';
    END IF;

    -- 4. Verify system bootstrap configuration
    SELECT * INTO v_bootstrap FROM private.system_bootstrap WHERE id = 1 FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'BOOTSTRAP_NOT_CONFIGURED: Bootstrap token not initialized'
            USING ERRCODE = 'P0003';
    END IF;

    IF v_bootstrap.is_claimed THEN
        RAISE EXCEPTION 'BOOTSTRAP_CONSUMED: Bootstrap token has already been claimed'
            USING ERRCODE = 'P0004';
    END IF;

    -- 5. Verify token hash using cryptographic hash comparison
    IF p_bootstrap_token IS NULL OR TRIM(p_bootstrap_token) = '' THEN
        RAISE EXCEPTION 'INVALID_TOKEN: Token cannot be empty'
            USING ERRCODE = '22023';
    END IF;

    -- Hash comparison with salt using native PostgreSQL sha256
    v_token_hash := encode(sha256((p_bootstrap_token || v_bootstrap.salt)::bytea), 'hex');
    IF v_token_hash != v_bootstrap.token_hash THEN
        RAISE EXCEPTION 'INVALID_TOKEN: Supplied bootstrap token does not match'
            USING ERRCODE = '28000';
    END IF;

    -- 6. Elevate caller profile to OWNER and ACTIVE
    INSERT INTO public.profiles (id, role, status, updated_at)
    VALUES (v_caller_id, 'OWNER'::user_role, 'ACTIVE'::account_status, NOW())
    ON CONFLICT (id) DO UPDATE SET 
        role = 'OWNER'::user_role,
        status = 'ACTIVE'::account_status,
        updated_at = NOW();

    -- 7. Mark bootstrap token consumed
    UPDATE private.system_bootstrap SET 
        is_claimed = TRUE,
        claimed_by = v_caller_id,
        claimed_at = NOW()
    WHERE id = 1;

    -- 8. Write immutable audit log
    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        v_caller_id, 
        'BOOTSTRAP_OWNER_CLAIM', 
        'profiles', 
        v_caller_id::text, 
        jsonb_build_object('method', 'token', 'timestamp', NOW())
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'user_id', v_caller_id,
        'role', 'OWNER',
        'status', 'ACTIVE'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.bootstrap_claim_owner(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.bootstrap_claim_owner(TEXT) TO authenticated, service_role;

-- 14. Automatic Tracker Registration RPC
CREATE OR REPLACE FUNCTION public.register_tracker_device(
    p_device_id TEXT,
    p_name TEXT DEFAULT '',
    p_model TEXT DEFAULT '',
    p_hardware_uuid TEXT DEFAULT NULL,
    p_api_key_hash TEXT DEFAULT NULL,
    p_api_key_salt TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_clean_device_id TEXT;
BEGIN
    v_clean_device_id := TRIM(p_device_id);
    IF v_clean_device_id IS NULL OR v_clean_device_id = '' THEN
        RAISE EXCEPTION 'INVALID_DEVICE_ID: Device ID cannot be empty' USING ERRCODE = '22023';
    END IF;

    -- Authorization check: Device registration requires Admin, Owner, or service_role authority
    IF NOT (public.is_admin_or_owner() OR current_user = 'service_role') THEN
        RAISE EXCEPTION 'FORBIDDEN: Device registration requires Admin or Owner privilege' USING ERRCODE = '42501';
    END IF;

    -- Upsert into public.devices
    INSERT INTO public.devices (
        device_id, name, model, status, is_legacy, hardware_uuid, updated_at
    ) VALUES (
        v_clean_device_id,
        COALESCE(NULLIF(p_name, ''), 'Tracker ' || SUBSTRING(v_clean_device_id FROM 1 FOR 8)),
        COALESCE(NULLIF(p_model, ''), 'Android Tracker'),
        'ACTIVE'::device_status,
        FALSE,
        p_hardware_uuid,
        NOW()
    )
    ON CONFLICT (device_id) DO UPDATE SET
        name = CASE WHEN EXCLUDED.name != '' THEN EXCLUDED.name ELSE public.devices.name END,
        model = CASE WHEN EXCLUDED.model != '' THEN EXCLUDED.model ELSE public.devices.model END,
        hardware_uuid = COALESCE(EXCLUDED.hardware_uuid, public.devices.hardware_uuid),
        status = 'ACTIVE'::device_status,
        updated_at = NOW();

    -- If credentials provided, store securely in private.device_secrets
    IF p_api_key_hash IS NOT NULL AND p_api_key_salt IS NOT NULL THEN
        INSERT INTO private.device_secrets (device_id, api_key_hash, api_key_salt, updated_at)
        VALUES (v_clean_device_id, p_api_key_hash, p_api_key_salt, NOW())
        ON CONFLICT (device_id) DO UPDATE SET
            api_key_hash = EXCLUDED.api_key_hash,
            api_key_salt = EXCLUDED.api_key_salt,
            updated_at = NOW();
    END IF;

    -- Audit log
    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'DEVICE_REGISTRATION',
        'devices',
        v_clean_device_id,
        jsonb_build_object('model', p_model, 'is_legacy', false)
    );

    RETURN jsonb_build_object('success', TRUE, 'device_id', v_clean_device_id, 'status', 'ACTIVE');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.register_tracker_device(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.register_tracker_device(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated, service_role;

-- 15. Telemetry Ingestion RPC — Approved Option B (Staging Buffer + Validation + Hardened Auth)
CREATE OR REPLACE FUNCTION public.submit_tracker_telemetry(
    p_device_id TEXT,
    p_payload JSONB,
    p_api_key TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_clean_id TEXT;
    v_unprocessed_count INT;
    v_lat FLOAT8;
    v_lng FLOAT8;
    v_alt FLOAT8;
    v_speed FLOAT4;
    v_accuracy FLOAT4;
    v_bearing FLOAT4;
    v_battery FLOAT4;
    v_charging BOOL;
    v_network TEXT;
    v_recorded_at TIMESTAMPTZ;
    v_staging_id BIGINT;
    v_device_record RECORD;
    v_secret_record RECORD;
    v_legacy_cred RECORD;
    v_is_authenticated BOOLEAN := FALSE;
    v_last_lat FLOAT8;
    v_last_lng FLOAT8;
    v_last_created_at TIMESTAMPTZ;
BEGIN
    v_clean_id := TRIM(p_device_id);
    IF v_clean_id IS NULL OR v_clean_id = '' THEN
        RAISE EXCEPTION 'INVALID_DEVICE_ID' USING ERRCODE = '22023';
    END IF;

    -- 1. Payload size boundary check (< 16KB)
    IF octet_length(p_payload::text) > 16384 THEN
        RAISE EXCEPTION 'PAYLOAD_TOO_LARGE: Telemetry payload exceeds 16KB limit' USING ERRCODE = '22023';
    END IF;

    -- 2. Device Identity Authentication Check (Pre-Staging Gate to Prevent Staging Buffer DoS)
    -- Caller authorization:
    -- If executed by service_role (e.g. verified gateway edge function) -> authenticated
    IF current_user = 'service_role' THEN
        v_is_authenticated := TRUE;
    ELSIF p_api_key IS NOT NULL AND TRIM(p_api_key) != '' THEN
        -- Check per-device credentials in private.device_secrets
        SELECT * INTO v_secret_record FROM private.device_secrets WHERE device_id = v_clean_id;
        IF FOUND THEN
            IF encode(sha256((p_api_key || v_secret_record.api_key_salt)::bytea), 'hex') = v_secret_record.api_key_hash THEN
                v_is_authenticated := TRUE;
            END IF;
        END IF;

        -- If not matched, check legacy ingest credentials in private.legacy_ingest_credentials
        IF NOT v_is_authenticated THEN
            SELECT * INTO v_legacy_cred FROM private.legacy_ingest_credentials WHERE id = 1;
            IF FOUND AND v_legacy_cred.secret_hash = encode(sha256(p_api_key::bytea), 'hex') THEN
                v_is_authenticated := TRUE;
            END IF;
        END IF;
    END IF;

    -- Unauthenticated submissions fail immediately without consuming staging table storage or counter
    IF NOT v_is_authenticated THEN
        RETURN jsonb_build_object(
            'success', FALSE, 
            'device_id', v_clean_id, 
            'staged', FALSE, 
            'error', 'UNAUTHENTICATED_DEVICE_CREDENTIALS'
        );
    END IF;

    -- 3. Device Status Check (Reject suspended/revoked hardware prior to staging)
    SELECT * INTO v_device_record FROM public.devices WHERE device_id = v_clean_id;
    IF v_device_record.device_id IS NOT NULL AND v_device_record.status != 'ACTIVE' THEN
        RETURN jsonb_build_object(
            'success', FALSE, 
            'device_id', v_clean_id, 
            'staged', FALSE, 
            'error', 'DEVICE_STATUS_NOT_ACTIVE'
        );
    END IF;

    -- 4. Storage ceiling enforcement: max 50,000 unprocessed staging rows
    -- FOR UPDATE row lock serializes counter access and prevents concurrent ceiling bypass
    SELECT unprocessed_count INTO v_unprocessed_count 
    FROM private.staging_storage_counter WHERE id = 1 FOR UPDATE;

    IF v_unprocessed_count >= 50000 THEN
        RAISE EXCEPTION 'STAGING_CAPACITY_EXCEEDED: Maximum staging buffer reached' USING ERRCODE = '54000';
    END IF;

    -- 5. Insert into staging buffer immediately (Approved Option B staging ledger)
    INSERT INTO public.tracker_telemetry_staging (device_id, payload, created_at, is_processed)
    VALUES (v_clean_id, p_payload, NOW(), FALSE)
    RETURNING id INTO v_staging_id;

    UPDATE private.staging_storage_counter 
    SET unprocessed_count = unprocessed_count + 1 
    WHERE id = 1;

    -- 6. Payload and Coordinate Validation
    IF NOT (
        (p_payload ? 'lat' OR p_payload ? 'latitude')
        AND (p_payload ? 'lng' OR p_payload ? 'longitude')
    ) THEN
        UPDATE public.tracker_telemetry_staging 
        SET is_processed = TRUE, processed_at = NOW(), error_message = 'MISSING_COORDINATES'
        WHERE id = v_staging_id;

        UPDATE private.staging_storage_counter 
        SET unprocessed_count = GREATEST(0, unprocessed_count - 1) 
        WHERE id = 1;

        RETURN jsonb_build_object('success', FALSE, 'device_id', v_clean_id, 'staged', TRUE, 'error', 'MISSING_COORDINATES');
    END IF;

    -- Parse coordinates safely (supports both short and full wire keys from Android clients)
    BEGIN
        v_lat := COALESCE((p_payload->>'lat')::FLOAT8, (p_payload->>'latitude')::FLOAT8);
        v_lng := COALESCE((p_payload->>'lng')::FLOAT8, (p_payload->>'longitude')::FLOAT8);
        v_alt := COALESCE((p_payload->>'alt')::FLOAT8, (p_payload->>'altitude')::FLOAT8, 0.0);
        v_speed := COALESCE((p_payload->>'speed')::FLOAT4, 0.0);
        v_accuracy := COALESCE((p_payload->>'accuracy')::FLOAT4, 0.0);
        v_bearing := COALESCE((p_payload->>'bearing')::FLOAT4, 0.0);
        v_battery := COALESCE((p_payload->>'battery_level')::FLOAT4, (p_payload->>'battery')::FLOAT4, 1.0);
        v_charging := COALESCE((p_payload->>'charging')::BOOL, FALSE);
        v_network := COALESCE(p_payload->>'network_type', 'UNKNOWN');
        v_recorded_at := COALESCE((p_payload->>'created_at')::TIMESTAMPTZ, (p_payload->>'timestamp')::TIMESTAMPTZ, NOW());
    EXCEPTION WHEN OTHERS THEN
        UPDATE public.tracker_telemetry_staging 
        SET is_processed = TRUE, processed_at = NOW(), error_message = 'INVALID_PAYLOAD_DATA_TYPES'
        WHERE id = v_staging_id;

        UPDATE private.staging_storage_counter 
        SET unprocessed_count = GREATEST(0, unprocessed_count - 1) 
        WHERE id = 1;

        RETURN jsonb_build_object('success', FALSE, 'device_id', v_clean_id, 'staged', TRUE, 'error', 'INVALID_PAYLOAD_DATA_TYPES');
    END;

    -- Geographic bounds check: lat [-90, 90], lng [-180, 180]
    IF v_lat IS NULL OR v_lng IS NULL OR v_lat < -90.0 OR v_lat > 90.0 OR v_lng < -180.0 OR v_lng > 180.0 THEN
        UPDATE public.tracker_telemetry_staging 
        SET is_processed = TRUE, processed_at = NOW(), error_message = 'INVALID_COORDINATES_OUT_OF_BOUNDS'
        WHERE id = v_staging_id;

        UPDATE private.staging_storage_counter 
        SET unprocessed_count = GREATEST(0, unprocessed_count - 1) 
        WHERE id = 1;

        RETURN jsonb_build_object('success', FALSE, 'device_id', v_clean_id, 'staged', TRUE, 'error', 'INVALID_COORDINATES_OUT_OF_BOUNDS');
    END IF;

    -- Freshness check: timestamp freshness bounds (no more than 5 minutes in future, no older than 7 days)
    IF v_recorded_at > (NOW() + INTERVAL '5 minutes') OR v_recorded_at < (NOW() - INTERVAL '7 days') THEN
        UPDATE public.tracker_telemetry_staging 
        SET is_processed = TRUE, processed_at = NOW(), error_message = 'TIMESTAMP_OUT_OF_BOUNDS'
        WHERE id = v_staging_id;

        UPDATE private.staging_storage_counter 
        SET unprocessed_count = GREATEST(0, unprocessed_count - 1) 
        WHERE id = 1;

        RETURN jsonb_build_object('success', FALSE, 'device_id', v_clean_id, 'staged', TRUE, 'error', 'TIMESTAMP_OUT_OF_BOUNDS');
    END IF;

    -- Duplicate-fix suppression: Check latest location fix for this device
    SELECT lat, lng, created_at 
    INTO v_last_lat, v_last_lng, v_last_created_at
    FROM public.locations
    WHERE device_id = v_clean_id
    ORDER BY created_at DESC, id DESC
    LIMIT 1;

    IF FOUND AND v_last_lat = v_lat AND v_last_lng = v_lng 
       AND (v_recorded_at <= v_last_created_at OR (v_recorded_at - v_last_created_at) < INTERVAL '2 seconds') THEN
        -- Duplicate fix suppressed
        UPDATE public.tracker_telemetry_staging 
        SET is_processed = TRUE, processed_at = NOW(), error_message = 'DUPLICATE_FIX_SUPPRESSED'
        WHERE id = v_staging_id;

        UPDATE private.staging_storage_counter 
        SET unprocessed_count = GREATEST(0, unprocessed_count - 1) 
        WHERE id = 1;

        RETURN jsonb_build_object('success', TRUE, 'device_id', v_clean_id, 'staged', TRUE, 'suppressed', TRUE);
    END IF;

    -- 7. Ensure device exists in registry if not already present
    IF v_device_record.device_id IS NULL THEN
        INSERT INTO public.devices (device_id, name, model, status, is_legacy)
        VALUES (v_clean_id, 'Tracker ' || SUBSTRING(v_clean_id FROM 1 FOR 8), 'Android Tracker', 'ACTIVE', FALSE)
        ON CONFLICT (device_id) DO NOTHING;
    END IF;

    -- 8. Insert authoritative location fix to public.locations
    INSERT INTO public.locations (
        device_id, lat, lng, alt, speed, accuracy, bearing, battery_level, charging, network_type, created_at
    ) VALUES (
        v_clean_id, v_lat, v_lng, v_alt, v_speed, v_accuracy, v_bearing, v_battery, v_charging, v_network, v_recorded_at
    );

    -- 9. Mark staging row processed
    UPDATE public.tracker_telemetry_staging 
    SET is_processed = TRUE, processed_at = NOW()
    WHERE id = v_staging_id;

    UPDATE private.staging_storage_counter 
    SET unprocessed_count = GREATEST(0, unprocessed_count - 1) 
    WHERE id = 1;

    RETURN jsonb_build_object('success', TRUE, 'device_id', v_clean_id, 'staged', TRUE, 'promoted', TRUE);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.submit_tracker_telemetry(TEXT, JSONB, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.submit_tracker_telemetry(TEXT, JSONB, TEXT) TO anon, authenticated, service_role;

-- 15B. Staging Buffer Retention & Cleanup RPC (Prunes processed/error records and re-synchronizes counter)
CREATE OR REPLACE FUNCTION private.purge_staging_records(
    p_retention_interval INTERVAL DEFAULT INTERVAL '24 hours'
)
RETURNS JSONB AS $$
DECLARE
    v_deleted_count INT;
    v_actual_unprocessed INT;
BEGIN
    -- 1. Delete processed or error records older than retention interval
    DELETE FROM public.tracker_telemetry_staging
    WHERE (is_processed = TRUE OR error_message IS NOT NULL)
      AND created_at < (NOW() - p_retention_interval);
    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;

    -- 2. Re-synchronize unprocessed_count with actual count of unprocessed rows in staging
    SELECT COUNT(*) INTO v_actual_unprocessed
    FROM public.tracker_telemetry_staging
    WHERE is_processed = FALSE;

    UPDATE private.staging_storage_counter
    SET unprocessed_count = v_actual_unprocessed
    WHERE id = 1;

    RETURN jsonb_build_object(
        'success', TRUE,
        'deleted_records', v_deleted_count,
        'unprocessed_count', v_actual_unprocessed
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION private.purge_staging_records(INTERVAL) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION private.purge_staging_records(INTERVAL) TO service_role;

-- 16. New User Registration Trigger on auth.users
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, role, status, display_name, email, created_at, updated_at)
    VALUES (
        NEW.id,
        'VISITOR'::user_role,
        'PENDING'::account_status,
        COALESCE(NEW.raw_user_meta_data->>'full_name', NEW.raw_user_meta_data->>'name', ''),
        NEW.email,
        NOW(),
        NOW()
    )
    ON CONFLICT (id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

-- 17. Default Feature Grant Trigger on user_device_assignments
-- Automatically grants default LOCATION feature permission upon assignment
CREATE OR REPLACE FUNCTION public.handle_new_device_assignment()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.device_feature_permissions (
        assignment_id, feature, is_allowed, granted_at
    ) VALUES (
        NEW.id, 'LOCATION', TRUE, NOW()
    )
    ON CONFLICT (assignment_id, feature) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

DROP TRIGGER IF EXISTS on_device_assignment_created ON public.user_device_assignments;
CREATE TRIGGER on_device_assignment_created
    AFTER INSERT ON public.user_device_assignments
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_device_assignment();

-- 17. Row Level Security Configuration & Table Hardening
-- Enable & force RLS across all public tables
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles FORCE ROW LEVEL SECURITY;

ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.devices FORCE ROW LEVEL SECURITY;

ALTER TABLE public.user_device_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_device_assignments FORCE ROW LEVEL SECURITY;

ALTER TABLE public.device_feature_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_feature_permissions FORCE ROW LEVEL SECURITY;

ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.audit_logs FORCE ROW LEVEL SECURITY;

ALTER TABLE public.tracker_telemetry_staging ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tracker_telemetry_staging FORCE ROW LEVEL SECURITY;

ALTER TABLE public.locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.locations FORCE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'media') THEN
        ALTER TABLE public.media ENABLE ROW LEVEL SECURITY;
        ALTER TABLE public.media FORCE ROW LEVEL SECURITY;
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- POLICIES: public.profiles
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "profiles_select_policy" ON public.profiles;
CREATE POLICY "profiles_select_policy" ON public.profiles
    FOR SELECT TO authenticated
    USING (
        public.is_admin_or_owner() OR id = auth.uid()
    );

DROP POLICY IF EXISTS "profiles_update_policy" ON public.profiles;
CREATE POLICY "profiles_update_policy" ON public.profiles
    FOR UPDATE TO authenticated
    USING (
        public.is_owner()
        OR (public.is_admin_or_owner() AND role NOT IN ('OWNER', 'ADMIN'))
        OR (id = auth.uid())
    )
    WITH CHECK (
        public.is_owner()
        OR (
            public.is_admin_or_owner() 
            AND role NOT IN ('OWNER', 'ADMIN')
        )
        OR (
            id = auth.uid() 
            -- Ordinary users cannot elevate their own role or un-suspend their status
            AND role = (SELECT p.role FROM public.profiles p WHERE p.id = auth.uid())
            AND status = (SELECT p.status FROM public.profiles p WHERE p.id = auth.uid())
        )
    );

-- Revoke profile deletions from clients
REVOKE DELETE, TRUNCATE ON TABLE public.profiles FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- POLICIES: public.devices
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "devices_select_policy" ON public.devices;
CREATE POLICY "devices_select_policy" ON public.devices
    FOR SELECT TO authenticated
    USING (
        public.is_admin_or_owner() OR public.has_device_access(device_id)
    );

DROP POLICY IF EXISTS "devices_mutate_policy" ON public.devices;
CREATE POLICY "devices_mutate_policy" ON public.devices
    FOR ALL TO authenticated
    USING (public.is_admin_or_owner())
    WITH CHECK (public.is_admin_or_owner());

REVOKE DELETE, TRUNCATE ON TABLE public.devices FROM PUBLIC, anon;

-- ----------------------------------------------------------------------------
-- POLICIES: public.user_device_assignments
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "uda_select_policy" ON public.user_device_assignments;
CREATE POLICY "uda_select_policy" ON public.user_device_assignments
    FOR SELECT TO authenticated
    USING (
        public.is_admin_or_owner() OR user_id = auth.uid()
    );

DROP POLICY IF EXISTS "uda_mutate_policy" ON public.user_device_assignments;
CREATE POLICY "uda_mutate_policy" ON public.user_device_assignments
    FOR ALL TO authenticated
    USING (public.is_admin_or_owner())
    WITH CHECK (public.is_admin_or_owner());

REVOKE DELETE, TRUNCATE ON TABLE public.user_device_assignments FROM PUBLIC, anon;

-- ----------------------------------------------------------------------------
-- POLICIES: public.device_feature_permissions
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "dfp_select_policy" ON public.device_feature_permissions;
CREATE POLICY "dfp_select_policy" ON public.device_feature_permissions
    FOR SELECT TO authenticated
    USING (
        public.is_admin_or_owner() 
        OR EXISTS (
            SELECT 1 FROM public.user_device_assignments uda
            WHERE uda.id = device_feature_permissions.assignment_id
              AND uda.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "dfp_mutate_policy" ON public.device_feature_permissions;
CREATE POLICY "dfp_mutate_policy" ON public.device_feature_permissions
    FOR ALL TO authenticated
    USING (public.is_admin_or_owner())
    WITH CHECK (public.is_admin_or_owner());

REVOKE DELETE, TRUNCATE ON TABLE public.device_feature_permissions FROM PUBLIC, anon;

-- ----------------------------------------------------------------------------
-- POLICIES: public.audit_logs (Strictly Append-Only via Triggers/Definer)
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "audit_select_policy" ON public.audit_logs;
CREATE POLICY "audit_select_policy" ON public.audit_logs
    FOR SELECT TO authenticated
    USING (public.is_admin_or_owner());

-- Revoke mutation privileges on audit_logs
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE public.audit_logs FROM PUBLIC, anon, authenticated;

-- ----------------------------------------------------------------------------
-- POLICIES: public.tracker_telemetry_staging (Admin/Owner Audit & Service Worker Only)
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "staging_select_policy" ON public.tracker_telemetry_staging;
CREATE POLICY "staging_select_policy" ON public.tracker_telemetry_staging
    FOR SELECT TO authenticated
    USING (public.is_admin_or_owner());

REVOKE ALL ON TABLE public.tracker_telemetry_staging FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.tracker_telemetry_staging TO authenticated;
GRANT ALL ON TABLE public.tracker_telemetry_staging TO service_role;

-- ----------------------------------------------------------------------------
-- POLICIES: public.locations
-- ----------------------------------------------------------------------------
DROP POLICY IF EXISTS "locations_select_policy" ON public.locations;
CREATE POLICY "locations_select_policy" ON public.locations
    FOR SELECT TO authenticated
    USING (
        public.has_location_read_access(device_id)
    );

-- Transitional policies with hardcoded static credentials dropped in Phase 2 Security Remediation.
-- All telemetry MUST enter via submit_tracker_telemetry() RPC (Option B).
DROP POLICY IF EXISTS "locations_transitional_select_policy" ON public.locations;
DROP POLICY IF EXISTS "locations_transitional_insert_policy" ON public.locations;

-- Revoke direct mutations on locations from unauthenticated clients
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE public.locations FROM PUBLIC, anon;
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE public.locations FROM authenticated;

-- ----------------------------------------------------------------------------
-- POLICIES: public.media
-- ----------------------------------------------------------------------------
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'media') THEN
        DROP POLICY IF EXISTS "media_select_policy" ON public.media;
        CREATE POLICY "media_select_policy" ON public.media
            FOR SELECT TO authenticated
            USING (
                public.is_owner()
                OR (
                    public.has_device_access(device_id)
                    AND public.has_feature_permission(device_id, 'CAMERA')
                )
            );
        REVOKE UPDATE, DELETE, TRUNCATE ON TABLE public.media FROM PUBLIC, anon, authenticated;
    END IF;
END $$;
