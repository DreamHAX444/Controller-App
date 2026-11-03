-- ============================================================================
-- TRACE Phase 4 — Database Migration: Device Enrollment & Identity Lifecycle
-- Target Schema: public.devices, public.enrolled_devices, private.device_secrets
-- Features:
-- 1. Idempotent device enrollment RPC: public.enroll_tracker_device
-- 2. Device assignment & unassignment RPCs: assign_device_to_user, unassign_device_from_user
-- 3. Strict audit logging and least privilege access
-- ============================================================================

-- 1. Atomic Device Enrollment RPC
-- Supports self-registration by new trackers (anon) with anti-takeover protection,
-- as well as administrative commissioning and credential rotation (Admin / Owner / service_role).
CREATE OR REPLACE FUNCTION public.enroll_tracker_device(
    p_device_id TEXT,
    p_name TEXT DEFAULT '',
    p_model TEXT DEFAULT '',
    p_hardware_uuid TEXT DEFAULT NULL,
    p_public_key_spki TEXT DEFAULT NULL,
    p_key_algorithm TEXT DEFAULT 'ES256',
    p_api_key_hash TEXT DEFAULT NULL,
    p_api_key_salt TEXT DEFAULT NULL,
    p_device_metadata JSONB DEFAULT '{}'::jsonb
)
RETURNS JSONB AS $$
DECLARE
    v_clean_device_id TEXT;
    v_clean_public_key TEXT;
    v_existing_device public.devices%ROWTYPE;
    v_existing_enrolled public.enrolled_devices%ROWTYPE;
    v_is_privileged BOOLEAN;
    v_metadata JSONB;
BEGIN
    v_clean_device_id := TRIM(p_device_id);
    IF v_clean_device_id IS NULL OR v_clean_device_id = '' THEN
        RAISE EXCEPTION 'INVALID_DEVICE_ID: Device ID cannot be empty' USING ERRCODE = '22023';
    END IF;

    v_clean_public_key := NULLIF(TRIM(p_public_key_spki), '');

    -- Determine privilege: is caller Admin, Owner, or service_role?
    v_is_privileged := (public.is_admin_or_owner() OR current_user = 'service_role');

    -- Check if device already exists
    SELECT * INTO v_existing_device FROM public.devices WHERE device_id = v_clean_device_id;
    SELECT * INTO v_existing_enrolled FROM public.enrolled_devices WHERE device_id = v_clean_device_id;

    -- If device already exists, execute idempotent update
    IF v_existing_device.device_id IS NOT NULL THEN
        -- Unprivileged caller (anon / regular user) anti-takeover checks:
        IF NOT v_is_privileged THEN
            IF v_existing_enrolled.device_id IS NOT NULL AND v_clean_public_key IS NOT NULL THEN
                IF v_existing_enrolled.public_key_spki != v_clean_public_key THEN
                    RAISE EXCEPTION 'DEVICE_ID_ALREADY_EXISTS: Cannot re-register device with differing public key'
                        USING ERRCODE = '23505';
                END IF;
            END IF;

            -- If device is revoked or suspended, report status without mutating
            IF v_existing_device.status IN ('REVOKED', 'SUSPENDED', 'DECOMMISSIONED') THEN
                RETURN jsonb_build_object(
                    'success', FALSE,
                    'device_id', v_clean_device_id,
                    'status', v_existing_device.status::text,
                    'error', 'DEVICE_NOT_ACTIVE'
                );
            END IF;
        END IF;

        -- Touch last seen and update metadata if privileged
        UPDATE public.devices
        SET last_seen_at = NOW(),
            name = CASE WHEN (v_is_privileged AND p_name != '') THEN p_name ELSE public.devices.name END,
            model = CASE WHEN (v_is_privileged AND p_model != '') THEN p_model ELSE public.devices.model END,
            hardware_uuid = CASE WHEN (v_is_privileged AND p_hardware_uuid IS NOT NULL) THEN p_hardware_uuid ELSE public.devices.hardware_uuid END,
            updated_at = NOW()
        WHERE device_id = v_clean_device_id;

        IF v_clean_public_key IS NOT NULL THEN
            IF v_existing_enrolled.device_id IS NOT NULL THEN
                UPDATE public.enrolled_devices
                SET last_seen_at = NOW(),
                    public_key_spki = CASE WHEN v_is_privileged THEN v_clean_public_key ELSE public.enrolled_devices.public_key_spki END
                WHERE device_id = v_clean_device_id;
            ELSE
                INSERT INTO public.enrolled_devices (
                    device_id,
                    public_key_spki,
                    enrolled_at,
                    is_revoked,
                    last_seen_at,
                    device_metadata
                ) VALUES (
                    v_clean_device_id,
                    v_clean_public_key,
                    NOW(),
                    FALSE,
                    NOW(),
                    COALESCE(p_device_metadata, '{}'::jsonb)
                );
            END IF;
        END IF;

        IF v_is_privileged AND p_api_key_hash IS NOT NULL AND p_api_key_salt IS NOT NULL THEN
            INSERT INTO private.device_secrets (
                device_id,
                api_key_hash,
                api_key_salt,
                created_at,
                updated_at
            ) VALUES (
                v_clean_device_id,
                p_api_key_hash,
                p_api_key_salt,
                NOW(),
                NOW()
            )
            ON CONFLICT (device_id) DO UPDATE SET
                api_key_hash = EXCLUDED.api_key_hash,
                api_key_salt = EXCLUDED.api_key_salt,
                updated_at = NOW();
        END IF;

        RETURN jsonb_build_object(
            'success', TRUE,
            'device_id', v_clean_device_id,
            'status', v_existing_device.status::text,
            'already_enrolled', TRUE
        );
    END IF;

    -- Upsert public.devices for new device
    INSERT INTO public.devices (
        device_id,
        name,
        model,
        status,
        is_legacy,
        hardware_uuid,
        created_at,
        updated_at,
        last_seen_at
    ) VALUES (
        v_clean_device_id,
        COALESCE(NULLIF(p_name, ''), 'Tracker ' || SUBSTRING(v_clean_device_id FROM 1 FOR 8)),
        COALESCE(NULLIF(p_model, ''), 'Android Tracker'),
        'ACTIVE'::device_status,
        FALSE,
        p_hardware_uuid,
        NOW(),
        NOW(),
        NOW()
    );

    -- Upsert public.enrolled_devices if public key is provided
    IF v_clean_public_key IS NOT NULL THEN
        v_metadata := COALESCE(p_device_metadata, '{}'::jsonb) || jsonb_build_object(
            'algorithm', COALESCE(p_key_algorithm, 'ES256'),
            'enrolled_by', COALESCE(auth.uid()::text, 'self_enrollment')
        );

        INSERT INTO public.enrolled_devices (
            device_id,
            public_key_spki,
            enrolled_at,
            is_revoked,
            last_seen_at,
            device_metadata
        ) VALUES (
            v_clean_device_id,
            v_clean_public_key,
            NOW(),
            FALSE,
            NOW(),
            v_metadata
        );
    END IF;

    -- Upsert private.device_secrets if hashed API credentials provided
    IF p_api_key_hash IS NOT NULL AND p_api_key_salt IS NOT NULL THEN
        INSERT INTO private.device_secrets (
            device_id,
            api_key_hash,
            api_key_salt,
            created_at,
            updated_at
        ) VALUES (
            v_clean_device_id,
            p_api_key_hash,
            p_api_key_salt,
            NOW(),
            NOW()
        );
    END IF;

    -- Emit structured audit log
    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'DEVICE_ENROLLMENT',
        'devices',
        v_clean_device_id,
        jsonb_build_object(
            'model', p_model,
            'has_public_key', (v_clean_public_key IS NOT NULL),
            'has_secrets', (p_api_key_hash IS NOT NULL),
            'privileged_enrollment', v_is_privileged
        )
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'device_id', v_clean_device_id,
        'status', 'ACTIVE',
        'already_enrolled', FALSE
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.enroll_tracker_device(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.enroll_tracker_device(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB) TO anon, authenticated, service_role;


-- 2. Device Assignment RPCs for Admin/Owner
CREATE OR REPLACE FUNCTION public.assign_device_to_user(
    p_device_id TEXT,
    p_user_id UUID,
    p_expires_at TIMESTAMPTZ DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_clean_device_id TEXT;
    v_user_record public.profiles%ROWTYPE;
BEGIN
    v_clean_device_id := TRIM(p_device_id);
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can assign devices' USING ERRCODE = '42501';
    END IF;

    -- Verify target user exists and is active
    SELECT * INTO v_user_record FROM public.profiles WHERE id = p_user_id;
    IF v_user_record.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE = '22023';
    END IF;

    IF v_user_record.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'USER_NOT_ACTIVE: Cannot assign devices to inactive users' USING ERRCODE = '22023';
    END IF;

    -- Verify device exists
    IF NOT EXISTS (SELECT 1 FROM public.devices WHERE device_id = v_clean_device_id) THEN
        RAISE EXCEPTION 'DEVICE_NOT_FOUND: Device does not exist' USING ERRCODE = '22023';
    END IF;

    -- Upsert assignment
    INSERT INTO public.user_device_assignments (
        user_id,
        device_id,
        status,
        assigned_at,
        expires_at,
        assigned_by
    ) VALUES (
        p_user_id,
        v_clean_device_id,
        'ACTIVE',
        NOW(),
        p_expires_at,
        auth.uid()
    )
    ON CONFLICT (user_id, device_id) DO UPDATE SET
        status = 'ACTIVE',
        assigned_at = NOW(),
        expires_at = EXCLUDED.expires_at,
        assigned_by = auth.uid();

    -- Audit log
    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'DEVICE_ASSIGNMENT',
        'user_device_assignments',
        v_clean_device_id,
        jsonb_build_object('target_user_id', p_user_id, 'expires_at', p_expires_at)
    );

    RETURN jsonb_build_object('success', TRUE, 'device_id', v_clean_device_id, 'user_id', p_user_id, 'status', 'ACTIVE');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.assign_device_to_user(TEXT, UUID, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.assign_device_to_user(TEXT, UUID, TIMESTAMPTZ) TO authenticated, service_role;


CREATE OR REPLACE FUNCTION public.unassign_device_from_user(
    p_device_id TEXT,
    p_user_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_clean_device_id TEXT;
    v_assignment_id BIGINT;
BEGIN
    v_clean_device_id := TRIM(p_device_id);
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can revoke device assignments' USING ERRCODE = '42501';
    END IF;

    UPDATE public.user_device_assignments
    SET status = 'REVOKED'
    WHERE device_id = v_clean_device_id AND user_id = p_user_id
    RETURNING id INTO v_assignment_id;

    -- Revoke associated feature permissions
    IF v_assignment_id IS NOT NULL THEN
        UPDATE public.device_feature_permissions
        SET is_allowed = FALSE
        WHERE assignment_id = v_assignment_id;
    END IF;

    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'DEVICE_UNASSIGNMENT',
        'user_device_assignments',
        v_clean_device_id,
        jsonb_build_object('target_user_id', p_user_id, 'status', 'REVOKED')
    );

    RETURN jsonb_build_object('success', TRUE, 'device_id', v_clean_device_id, 'user_id', p_user_id, 'status', 'REVOKED');
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.unassign_device_from_user(TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.unassign_device_from_user(TEXT, UUID) TO authenticated, service_role;
