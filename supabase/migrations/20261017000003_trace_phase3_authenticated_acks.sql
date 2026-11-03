-- ============================================================================
-- TRACE Phase 3.4 — Database Migration: Authenticated Acknowledgements & Device Registry
-- Target Schema: public.enrolled_devices, narrow state transitions, and RPC
-- Adheres strictly to Section 5.2 of TRACE Phase 0 Security Gate
-- Resequenced to Migration 03, following Phase 2A and Phase 2B
-- ============================================================================

-- 1. Create enrolled_devices table for tracking authentic tracker device public keys
CREATE TABLE IF NOT EXISTS public.enrolled_devices (
    device_id TEXT PRIMARY KEY,
    public_key_spki TEXT NOT NULL,
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at TIMESTAMPTZ NULL,
    device_metadata JSONB DEFAULT '{}'::jsonb
);

-- Foreign Key to public.devices (from Phase 2A)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_enrolled_devices_device_id') THEN
        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'devices') THEN
            ALTER TABLE public.enrolled_devices
                ADD CONSTRAINT fk_enrolled_devices_device_id
                FOREIGN KEY (device_id) REFERENCES public.devices(device_id)
                ON DELETE RESTRICT;
        END IF;
    END IF;
END $$;

-- RLS on enrolled_devices: readable by service_role and authenticated admins/owners only
ALTER TABLE public.enrolled_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.enrolled_devices FORCE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE public.enrolled_devices FROM PUBLIC, anon, authenticated;
GRANT ALL ON TABLE public.enrolled_devices TO service_role;

-- 2. Narrow Cryptographically Authenticated Command Acknowledgement Function
-- Enforces:
-- 1. Device identity binding (p_device_id must match command and enrolled_devices).
-- 2. Timestamp freshness (|now - p_timestamp_epoch_sec| <= 300).
-- 3. Idempotency key exact match.
-- 4. Terminal state immutability (cannot regress from executed/failed).
-- 5. Zero broad table grants to anon or authenticated.
CREATE OR REPLACE FUNCTION public.acknowledge_command(
    p_command_id BIGINT,
    p_device_id TEXT,
    p_idempotency_key UUID,
    p_status TEXT,
    p_timestamp_epoch_sec BIGINT,
    p_result_digest TEXT DEFAULT '',
    p_device_signature TEXT DEFAULT ''
)
RETURNS JSONB AS $$
DECLARE
    v_command RECORD;
    v_device RECORD;
    v_now_epoch BIGINT;
    v_target_status command_delivery_status;
BEGIN
    -- A. Validate status argument
    IF p_status NOT IN ('executed', 'failed') THEN
        RAISE EXCEPTION 'INVALID_STATUS: Status must be executed or failed, received: %', p_status
            USING ERRCODE = '22023';
    END IF;
    v_target_status := p_status::command_delivery_status;

    -- B. Validate timestamp freshness (5-minute maximum tolerance)
    v_now_epoch := EXTRACT(EPOCH FROM NOW())::BIGINT;
    IF ABS(v_now_epoch - p_timestamp_epoch_sec) > 300 THEN
        RAISE EXCEPTION 'ACK_EXPIRED: Acknowledgement timestamp % is outside the 300s freshness window (now: %)',
            p_timestamp_epoch_sec, v_now_epoch
            USING ERRCODE = '22003';
    END IF;

    -- C. Validate Device Enrollment & Revocation Status
    SELECT * INTO v_device
    FROM public.enrolled_devices
    WHERE device_id = p_device_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'UNAUTHORIZED_DEVICE: Device % is not enrolled in fleet', p_device_id
            USING ERRCODE = '28000';
    END IF;

    IF v_device.is_revoked THEN
        RAISE EXCEPTION 'DEVICE_REVOKED: Device % is revoked from fleet', p_device_id
            USING ERRCODE = '28000';
    END IF;

    -- D. Locate and lock command row for update
    SELECT * INTO v_command
    FROM public.commands
    WHERE id = p_command_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'COMMAND_NOT_FOUND: Command with id % does not exist', p_command_id
            USING ERRCODE = 'P0002';
    END IF;

    -- E. Enforce device ID binding (anti-spoofing)
    IF v_command.device_id <> p_device_id THEN
        RAISE EXCEPTION 'CROSS_DEVICE_ACK_DENIED: Command % is for device %, not caller %',
            p_command_id, v_command.device_id, p_device_id
            USING ERRCODE = '42501';
    END IF;

    -- F. Enforce idempotency key match
    IF v_command.idempotency_key IS NOT NULL AND v_command.idempotency_key <> p_idempotency_key THEN
        RAISE EXCEPTION 'IDEMPOTENCY_MISMATCH: Idempotency key does not match command record'
            USING ERRCODE = '23505';
    END IF;

    -- G. Enforce terminal state immutability & anti-regression
    IF v_command.status IN ('executed', 'failed', 'expired') THEN
        -- If already in the exact same target state, treat as idempotent retry
        IF v_command.status = v_target_status THEN
            RETURN jsonb_build_object(
                'status', 'ALREADY_ACKNOWLEDGED',
                'command_id', p_command_id,
                'current_status', v_command.status
            );
        ELSE
            RAISE EXCEPTION 'TERMINAL_STATE_REGRESSION: Cannot transition command % from % to %',
                p_command_id, v_command.status, p_status
                USING ERRCODE = '22000';
        END IF;
    END IF;

    -- H. Apply state transition atomically
    UPDATE public.commands
    SET status = v_target_status,
        acknowledged_at = COALESCE(acknowledged_at, NOW()),
        executed_at = CASE WHEN p_status = 'executed' THEN NOW() ELSE executed_at END,
        result_payload = CASE 
            WHEN p_result_digest IS NOT NULL AND p_result_digest <> '' THEN 
                jsonb_build_object('digest', p_result_digest, 'device_signature', p_device_signature)
            ELSE result_payload 
        END
    WHERE id = p_command_id;

    -- Update device last_seen_at if enrolled
    IF FOUND THEN
        UPDATE public.enrolled_devices
        SET last_seen_at = NOW()
        WHERE device_id = p_device_id;
    END IF;

    RETURN jsonb_build_object(
        'status', 'SUCCESS',
        'command_id', p_command_id,
        'new_status', p_status
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- 3. Access Control: Drop legacy overloads and revoke execution from PUBLIC, anon, authenticated across all overloads
-- Explicitly drop legacy proposed 4-parameter and 5-parameter overloads if they exist in pre-migrated environments
DROP FUNCTION IF EXISTS public.acknowledge_command(BIGINT, TEXT, UUID, TEXT);
DROP FUNCTION IF EXISTS public.acknowledge_command(BIGINT, TEXT, UUID, TEXT, BIGINT);

-- Revoke on current canonical overload
REVOKE ALL ON FUNCTION public.acknowledge_command(BIGINT, TEXT, UUID, TEXT, BIGINT, TEXT, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.acknowledge_command(BIGINT, TEXT, UUID, TEXT, BIGINT, TEXT, TEXT) TO service_role;

-- Defense in depth: dynamically revoke privileges across ANY and ALL overloads of acknowledge_command in public schema
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT p.oid::regprocedure AS proc_name
        FROM pg_proc p
        JOIN pg_namespace n ON p.pronamespace = n.oid
        WHERE p.proname = 'acknowledge_command'
          AND n.nspname = 'public'
    ) LOOP
        EXECUTE 'REVOKE ALL ON FUNCTION ' || r.proc_name || ' FROM PUBLIC, anon, authenticated';
        EXECUTE 'GRANT EXECUTE ON FUNCTION ' || r.proc_name || ' TO service_role';
    END LOOP;
END $$;
