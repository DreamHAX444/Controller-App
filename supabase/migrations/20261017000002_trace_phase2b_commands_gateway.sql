-- ============================================================================
-- TRACE Phase 2B — Database Migration: Command Gateway & Cryptographic Invariants
-- Target Schema: public.commands, public.commands_id_seq, constraints, and RLS
-- Adheres strictly to Section 5.2 of TRACE Phase 0 Security Gate Document v3.2.1
-- Corrected for Admin B2 Policy (Admins require device assignment for commands)
-- ============================================================================

-- 1. Ensure command_delivery_status enum type exists safely
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'command_delivery_status') THEN
        CREATE TYPE command_delivery_status AS ENUM (
            'pending',
            'dispatched',
            'acknowledged',
            'executed',
            'failed',
            'expired'
        );
    END IF;
END $$;

-- 2. Sequence for monotonic 64-bit integer command identifiers
CREATE SEQUENCE IF NOT EXISTS public.commands_id_seq
    AS BIGINT
    START WITH 1000
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- 3. Data-preserving table creation / evolution for public.commands
CREATE TABLE IF NOT EXISTS public.commands (
    id BIGINT PRIMARY KEY DEFAULT nextval('public.commands_id_seq'),
    device_id TEXT NOT NULL,
    command TEXT NOT NULL,
    params JSONB DEFAULT '{}'::jsonb,
    status command_delivery_status NOT NULL DEFAULT 'pending'::command_delivery_status,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Safely add Phase 2 cryptographic and lifecycle columns if missing
DO $$
BEGIN
    -- Standardize params column name from legacy 'parameters' if present
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'parameters') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'params') THEN
        ALTER TABLE public.commands RENAME COLUMN parameters TO params;
    ELSIF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'params') THEN
        ALTER TABLE public.commands ADD COLUMN params JSONB DEFAULT '{}'::jsonb;
    END IF;

    -- issuer_user_id (unconstrained column addition; FK added in Section 4 if profiles table exists)
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'issuer_user_id') THEN
        ALTER TABLE public.commands ADD COLUMN issuer_user_id UUID NULL;
    END IF;

    -- idempotency_key
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'idempotency_key') THEN
        ALTER TABLE public.commands ADD COLUMN idempotency_key UUID;
    END IF;

    -- key_id
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'key_id') THEN
        ALTER TABLE public.commands ADD COLUMN key_id TEXT DEFAULT 'legacy-compat';
    END IF;

    -- server_signature
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'server_signature') THEN
        ALTER TABLE public.commands ADD COLUMN server_signature TEXT DEFAULT 'LEGACY_UNSIGNED';
    END IF;

    -- issued_at_epoch_sec
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'issued_at_epoch_sec') THEN
        ALTER TABLE public.commands ADD COLUMN issued_at_epoch_sec BIGINT DEFAULT EXTRACT(EPOCH FROM NOW())::BIGINT;
    END IF;

    -- expires_at_epoch_sec
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'expires_at_epoch_sec') THEN
        ALTER TABLE public.commands ADD COLUMN expires_at_epoch_sec BIGINT DEFAULT (EXTRACT(EPOCH FROM NOW())::BIGINT + 300);
    END IF;

    -- operational lifecycle timestamps
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'dispatched_at') THEN
        ALTER TABLE public.commands ADD COLUMN dispatched_at TIMESTAMPTZ NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'acknowledged_at') THEN
        ALTER TABLE public.commands ADD COLUMN acknowledged_at TIMESTAMPTZ NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'executed_at') THEN
        ALTER TABLE public.commands ADD COLUMN executed_at TIMESTAMPTZ NULL;
    END IF;

    -- retry counter and execution results
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'retry_count') THEN
        ALTER TABLE public.commands ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'commands' AND column_name = 'result_payload') THEN
        ALTER TABLE public.commands ADD COLUMN result_payload JSONB NULL;
    END IF;
END $$;

-- 3b. Upgrade pre-existing status column to command_delivery_status enum if needed
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
          AND table_name = 'commands' 
          AND column_name = 'status' 
          AND udt_name != 'command_delivery_status'
    ) THEN
        ALTER TABLE public.commands ALTER COLUMN status DROP DEFAULT;
        ALTER TABLE public.commands ALTER COLUMN status TYPE command_delivery_status USING (
            CASE 
                WHEN LOWER(TRIM(status)) IN ('pending', 'dispatched', 'acknowledged', 'executed', 'failed') 
                    THEN LOWER(TRIM(status))::command_delivery_status
                WHEN LOWER(TRIM(status)) IN ('success', 'completed', 'done') 
                    THEN 'executed'::command_delivery_status
                ELSE 'failed'::command_delivery_status
            END
        );
        ALTER TABLE public.commands ALTER COLUMN status SET DEFAULT 'pending'::command_delivery_status;
    END IF;
END $$;

-- 4. Constraints and Referential Integrity
DO $$
BEGIN
    -- Idempotency unique constraint
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_commands_idempotency_key') THEN
        -- Backfill any null idempotency_key on legacy rows before adding constraint
        UPDATE public.commands SET idempotency_key = gen_random_uuid() WHERE idempotency_key IS NULL;
        ALTER TABLE public.commands ALTER COLUMN idempotency_key SET NOT NULL;
        ALTER TABLE public.commands ADD CONSTRAINT uq_commands_idempotency_key UNIQUE (idempotency_key);
    END IF;

    -- Device foreign key constraint
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_commands_device_id') THEN
        -- Only add if public.devices table exists
        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'devices') THEN
            ALTER TABLE public.commands 
                ADD CONSTRAINT fk_commands_device_id 
                FOREIGN KEY (device_id) REFERENCES public.devices(device_id) 
                ON DELETE RESTRICT;
        END IF;
    END IF;

    -- Issuer User foreign key constraint (conditional on profiles table existence)
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_commands_issuer_user_id') THEN
        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'profiles') THEN
            ALTER TABLE public.commands 
                ADD CONSTRAINT fk_commands_issuer_user_id 
                FOREIGN KEY (issuer_user_id) REFERENCES public.profiles(id) 
                ON DELETE RESTRICT;
        END IF;
    END IF;

    -- Params boundary check constraint (< 4KB, must be JSON object)
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_commands_params') THEN
        -- Sanitize historical rows: ensure params is a valid non-null JSON object
        UPDATE public.commands SET params = '{}'::jsonb WHERE params IS NULL OR jsonb_typeof(params) != 'object';
        ALTER TABLE public.commands 
            ADD CONSTRAINT chk_commands_params 
            CHECK (jsonb_typeof(params) = 'object' AND octet_length(params::text) < 4096);
    END IF;
END $$;

-- 5. Performance and Polling Indexes
CREATE INDEX IF NOT EXISTS idx_commands_device_poll 
    ON public.commands (device_id, status, expires_at_epoch_sec) 
    WHERE status IN ('pending', 'dispatched');

CREATE INDEX IF NOT EXISTS idx_commands_created_at 
    ON public.commands (created_at DESC);

-- 6. Atomic RPC for Idempotency Check & Sequence Allocation
CREATE OR REPLACE FUNCTION public.check_idempotency_or_allocate_id(
    p_idempotency_key UUID
)
RETURNS JSONB AS $$
DECLARE
    v_existing RECORD;
BEGIN
    IF p_idempotency_key IS NULL THEN
        RAISE EXCEPTION 'Idempotency key required' USING ERRCODE = 'P0001';
    END IF;

    -- Check for existing command submission
    SELECT id, device_id, command, params, status, key_id, server_signature,
           issued_at_epoch_sec, expires_at_epoch_sec, idempotency_key, created_at
    INTO v_existing
    FROM public.commands
    WHERE idempotency_key = p_idempotency_key;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'status', 'EXISTING',
            'command', to_jsonb(v_existing)
        );
    ELSE
        -- Atomically reserve next monotonic sequence ID
        RETURN jsonb_build_object(
            'status', 'ALLOCATED',
            'command_id', nextval('public.commands_id_seq')
        );
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.allocate_command_sequence()
RETURNS BIGINT AS $$
BEGIN
    RETURN nextval('public.commands_id_seq');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- 7. Privilege Lockdown: PostgREST Bypass Elimination
-- Revoke direct table mutation from public clients
REVOKE INSERT, UPDATE, DELETE ON public.commands FROM PUBLIC, anon, authenticated;
REVOKE ALL ON SEQUENCE public.commands_id_seq FROM PUBLIC, anon, authenticated;

-- Grant execution strictly to service_role (Edge Function)
GRANT ALL ON TABLE public.commands TO service_role;
GRANT USAGE, SELECT ON SEQUENCE public.commands_id_seq TO service_role;
REVOKE ALL ON FUNCTION public.check_idempotency_or_allocate_id(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.check_idempotency_or_allocate_id(UUID) TO service_role;
REVOKE ALL ON FUNCTION public.allocate_command_sequence() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.allocate_command_sequence() TO service_role;

-- 8. Row Level Security Configuration
ALTER TABLE public.commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.commands FORCE ROW LEVEL SECURITY;

-- Drop any legacy wildcard policies that grant unrestricted public access
DROP POLICY IF EXISTS "public_all" ON public.commands;

-- Controllers can only SELECT commands for devices they have access to.
-- Enforces Owner A1 (Owner has access to all devices) and Admin B2 (Admin requires device assignment for commands).
DROP POLICY IF EXISTS "commands_select_policy" ON public.commands;
CREATE POLICY "commands_select_policy" ON public.commands
    FOR SELECT TO authenticated
    USING (
        public.has_device_access(device_id)
    );

-- Transitional command bridge policy removed in Phase 2 Security Remediation.
-- Trackers fetch/acknowledge commands via authenticated RPCs, never raw SELECT on commands table.
DROP POLICY IF EXISTS "commands_transitional_select_policy" ON public.commands;

-- 9. Realtime Publication Lockdown
-- Drop commands table from supabase_realtime to prevent CDC broadcasts of commands
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
          AND schemaname = 'public' 
          AND tablename = 'commands'
    ) THEN
        ALTER PUBLICATION supabase_realtime DROP TABLE public.commands;
    END IF;
END $$;
