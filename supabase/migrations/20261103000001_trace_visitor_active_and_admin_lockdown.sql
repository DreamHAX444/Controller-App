-- ============================================================================
-- TRACE Migration: 20261103000001_trace_visitor_active_and_admin_lockdown.sql
-- 1. Default & Existing Visitor Status: ACTIVE (Instant demo onboarding)
-- 2. handle_new_user() & register_user_account(): VISITOR + ACTIVE
-- 3. approve_user_account(): Permits promotion of VISITOR to USER/ACTIVE
-- 4. update_user_account_role(): Restricts ADMIN to VISITOR -> USER only
-- 5. update_user_account_status(): Restricted strictly to OWNER
-- 6. fetch_managed_users(): pending_approval includes VISITOR accounts
-- ============================================================================

-- 1. Alter public.profiles default status to 'ACTIVE'
ALTER TABLE public.profiles ALTER COLUMN status SET DEFAULT 'ACTIVE'::account_status;

-- 2. Update existing pending visitors to active
UPDATE public.profiles
SET status = 'ACTIVE'::account_status,
    updated_at = NOW()
WHERE role = 'VISITOR' AND status = 'PENDING';

-- 3. handle_new_user() Trigger Function: Sets role 'VISITOR' and status 'ACTIVE'
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
BEGIN
    INSERT INTO public.profiles (
        id,
        email,
        display_name,
        avatar_url,
        role,
        status,
        created_at,
        updated_at
    ) VALUES (
        NEW.id,
        NEW.email,
        COALESCE(
            NEW.raw_user_meta_data->>'full_name',
            NEW.raw_user_meta_data->>'name',
            split_part(NEW.email, '@', 1)
        ),
        COALESCE(
            NEW.raw_user_meta_data->>'avatar_url',
            NEW.raw_user_meta_data->>'picture',
            ''
        ),
        'VISITOR'::user_role,
        'ACTIVE'::account_status,
        NOW(),
        NOW()
    )
    ON CONFLICT (id) DO UPDATE SET
        email = EXCLUDED.email,
        display_name = CASE WHEN public.profiles.display_name = '' THEN EXCLUDED.display_name ELSE public.profiles.display_name END,
        avatar_url = CASE WHEN public.profiles.avatar_url IS NULL OR public.profiles.avatar_url = '' THEN EXCLUDED.avatar_url ELSE public.profiles.avatar_url END,
        updated_at = NOW();
    RETURN NEW;
END;
$function$;

-- 4. register_user_account RPC: Sets role 'VISITOR' and status 'ACTIVE'
CREATE OR REPLACE FUNCTION public.register_user_account(
    p_email TEXT,
    p_password TEXT,
    p_display_name TEXT DEFAULT ''
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'auth', 'extensions', 'pg_temp'
AS $$
DECLARE
    v_clean_email TEXT := LOWER(TRIM(p_email));
    v_clean_display TEXT := TRIM(p_display_name);
    v_user_id UUID;
BEGIN
    IF v_clean_email IS NULL OR v_clean_email = '' OR v_clean_email NOT LIKE '%@%.%' THEN
        RAISE EXCEPTION 'INVALID_EMAIL: Invalid email address format' USING ERRCODE = '22023';
    END IF;

    IF p_password IS NULL OR LENGTH(p_password) < 6 THEN
        RAISE EXCEPTION 'INVALID_PASSWORD: Password must be at least 6 characters' USING ERRCODE = '22023';
    END IF;

    IF EXISTS (SELECT 1 FROM auth.users WHERE email = v_clean_email) THEN
        RAISE EXCEPTION 'USER_EXISTS: An account with this email already exists' USING ERRCODE = '23505';
    END IF;

    v_user_id := gen_random_uuid();

    IF v_clean_display = '' THEN
        v_clean_display := split_part(v_clean_email, '@', 1);
    END IF;

    INSERT INTO auth.users (
        id,
        instance_id,
        email,
        encrypted_password,
        email_confirmed_at,
        raw_app_meta_data,
        raw_user_meta_data,
        created_at,
        updated_at,
        role,
        aud
    ) VALUES (
        v_user_id,
        '00000000-0000-0000-0000-000000000000',
        v_clean_email,
        crypt(p_password, gen_salt('bf')),
        NOW(),
        '{"provider":"email","providers":["email"]}'::JSONB,
        jsonb_build_object('full_name', v_clean_display),
        NOW(),
        NOW(),
        'authenticated',
        'authenticated'
    );

    INSERT INTO public.profiles (
        id,
        email,
        display_name,
        role,
        status,
        created_at,
        updated_at
    ) VALUES (
        v_user_id,
        v_clean_email,
        v_clean_display,
        'VISITOR'::user_role,
        'ACTIVE'::account_status,
        NOW(),
        NOW()
    )
    ON CONFLICT (id) DO UPDATE SET
        email = EXCLUDED.email,
        display_name = EXCLUDED.display_name,
        updated_at = NOW();

    RETURN jsonb_build_object(
        'success', TRUE,
        'user_id', v_user_id,
        'email', v_clean_email,
        'role', 'VISITOR',
        'status', 'ACTIVE'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.register_user_account(TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.register_user_account(TEXT, TEXT, TEXT) TO anon, authenticated, service_role;

-- 5. approve_user_account RPC: Approves/Promotes VISITOR (or PENDING) to USER / ACTIVE
CREATE OR REPLACE FUNCTION public.approve_user_account(p_user_id UUID)
RETURNS JSONB AS $$
DECLARE
    v_target public.profiles%ROWTYPE;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can approve user accounts' USING ERRCODE = '42501';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_USER_ID: User ID cannot be null' USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_target FROM public.profiles WHERE id = p_user_id FOR UPDATE;
    IF v_target.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE = '22023';
    END IF;

    -- Target must be a VISITOR or PENDING account to be approved/promoted to USER
    IF v_target.role != 'VISITOR' AND v_target.status != 'PENDING' THEN
        RAISE EXCEPTION 'INVALID_TARGET: Target account is already a verified member (role: %, status: %)', v_target.role, v_target.status
            USING ERRCODE = '22023';
    END IF;

    UPDATE public.profiles
    SET role = 'USER'::user_role,
        status = 'ACTIVE'::account_status,
        updated_at = NOW()
    WHERE id = p_user_id;

    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'USER_ACCOUNT_APPROVAL',
        'profiles',
        p_user_id::text,
        jsonb_build_object(
            'previous_role', v_target.role::text,
            'previous_status', v_target.status::text,
            'new_role', 'USER',
            'new_status', 'ACTIVE'
        )
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'user_id', p_user_id,
        'role', 'USER',
        'status', 'ACTIVE'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.approve_user_account(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.approve_user_account(UUID) TO authenticated, service_role;

-- 6. update_user_account_role RPC: Strict Admin boundary (Admin can ONLY promote VISITOR -> USER)
CREATE OR REPLACE FUNCTION public.update_user_account_role(
    p_user_id UUID,
    p_new_role TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_clean_role TEXT;
    v_target public.profiles%ROWTYPE;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can update user roles' USING ERRCODE = '42501';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_USER_ID: User ID cannot be null' USING ERRCODE = '22023';
    END IF;

    v_clean_role := UPPER(TRIM(p_new_role));

    -- Single-Owner & Invariant: No promotion to OWNER via this RPC
    IF v_clean_role = 'OWNER' THEN
        RAISE EXCEPTION 'FORBIDDEN: Promotion to OWNER is not permitted via this RPC' USING ERRCODE = '42501';
    END IF;

    IF v_clean_role NOT IN ('ADMIN', 'USER', 'VISITOR') THEN
        RAISE EXCEPTION 'INVALID_ROLE: Role must be ADMIN, USER, or VISITOR' USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_target FROM public.profiles WHERE id = p_user_id FOR UPDATE;
    IF v_target.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE = '22023';
    END IF;

    -- Owner Protection: The Owner role is immutable and cannot be demoted or changed
    IF v_target.role = 'OWNER' THEN
        RAISE EXCEPTION 'FORBIDDEN: Owner role is immutable and cannot be modified' USING ERRCODE = '42501';
    END IF;

    -- Enforce strict Admin boundary:
    -- If caller is NOT an Owner (i.e. caller is an ADMIN), the caller is ONLY allowed when:
    -- target is VISITOR AND new role is USER.
    IF NOT public.is_owner() THEN
        IF NOT (v_target.role = 'VISITOR' AND v_clean_role = 'USER') THEN
            RAISE EXCEPTION 'FORBIDDEN: Admins can only promote VISITOR to USER' USING ERRCODE = '42501';
        END IF;
    END IF;

    -- Transactional lock
    PERFORM pg_advisory_xact_lock(hashtext('owner_guard'));

    UPDATE public.profiles
    SET role = v_clean_role::user_role,
        updated_at = NOW()
    WHERE id = p_user_id;

    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'USER_ROLE_UPDATE',
        'profiles',
        p_user_id::text,
        jsonb_build_object(
            'previous_role', v_target.role::text,
            'new_role', v_clean_role
        )
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'user_id', p_user_id,
        'role', v_clean_role
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.update_user_account_role(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.update_user_account_role(UUID, TEXT) TO authenticated, service_role;

-- 7. update_user_account_status RPC: Restricted STRICTLY to OWNER
CREATE OR REPLACE FUNCTION public.update_user_account_status(
    p_user_id UUID,
    p_new_status TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_clean_status TEXT;
    v_target public.profiles%ROWTYPE;
BEGIN
    -- Strict Requirement: Only the Owner can update user account status
    IF NOT public.is_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only the Owner can update user account status' USING ERRCODE = '42501';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_USER_ID: User ID cannot be null' USING ERRCODE = '22023';
    END IF;

    v_clean_status := UPPER(TRIM(p_new_status));
    IF v_clean_status NOT IN ('ACTIVE', 'SUSPENDED', 'DISABLED') THEN
        RAISE EXCEPTION 'INVALID_STATUS: Status must be ACTIVE, SUSPENDED, or DISABLED' USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_target FROM public.profiles WHERE id = p_user_id FOR UPDATE;
    IF v_target.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE = '22023';
    END IF;

    -- Owner Protection: The Owner account status cannot be modified
    IF v_target.role = 'OWNER' THEN
        RAISE EXCEPTION 'FORBIDDEN: Owner account status is protected and cannot be modified' USING ERRCODE = '42501';
    END IF;

    -- Transactional lock
    PERFORM pg_advisory_xact_lock(hashtext('owner_guard'));

    UPDATE public.profiles
    SET status = v_clean_status::account_status,
        updated_at = NOW()
    WHERE id = p_user_id;

    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'USER_STATUS_UPDATE',
        'profiles',
        p_user_id::text,
        jsonb_build_object(
            'previous_status', v_target.status::text,
            'new_status', v_clean_status
        )
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'user_id', p_user_id,
        'status', v_clean_status
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.update_user_account_status(UUID, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.update_user_account_status(UUID, TEXT) TO authenticated, service_role;

-- 8. fetch_managed_users RPC: pending_approval includes VISITOR accounts
CREATE OR REPLACE FUNCTION public.fetch_managed_users()
RETURNS TABLE (
    user_id UUID,
    email TEXT,
    display_name TEXT,
    role TEXT,
    status TEXT,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    assigned_device_count BIGINT,
    active_permission_count BIGINT,
    pending_approval BOOLEAN
) AS $$
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can fetch managed users' USING ERRCODE = '42501';
    END IF;

    RETURN QUERY
    SELECT
        p.id AS user_id,
        p.email,
        p.display_name,
        p.role::TEXT,
        p.status::TEXT,
        p.created_at,
        p.updated_at,
        COUNT(DISTINCT uda.device_id) FILTER (
            WHERE uda.status = 'ACTIVE' 
              AND (uda.expires_at IS NULL OR uda.expires_at > NOW())
        )::BIGINT AS assigned_device_count,
        COUNT(DISTINCT dfp.id) FILTER (
            WHERE dfp.is_allowed = TRUE 
              AND (dfp.expires_at IS NULL OR dfp.expires_at > NOW())
              AND uda.status = 'ACTIVE'
              AND (uda.expires_at IS NULL OR uda.expires_at > NOW())
        )::BIGINT AS active_permission_count,
        (p.status = 'PENDING' OR p.role = 'VISITOR') AS pending_approval
    FROM public.profiles p
    LEFT JOIN public.user_device_assignments uda ON uda.user_id = p.id
    LEFT JOIN public.device_feature_permissions dfp ON dfp.assignment_id = uda.id
    WHERE public.is_owner()
       OR (p.role IN ('USER', 'VISITOR') OR p.id = auth.uid())
    GROUP BY p.id, p.email, p.display_name, p.role, p.status, p.created_at, p.updated_at
    ORDER BY p.created_at DESC;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.fetch_managed_users() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fetch_managed_users() TO authenticated, service_role;

-- ============================================================================
-- 9. Device Identity: Persist public_key_spki and Anti-Takeover Verification
-- ============================================================================

-- Ensure public.devices has public_key_spki column
ALTER TABLE public.devices ADD COLUMN IF NOT EXISTS public_key_spki TEXT NULL;

-- Updated enroll_tracker_device RPC: Persists public_key_spki & enforces anti-takeover
CREATE OR REPLACE FUNCTION public.enroll_tracker_device(
    p_device_id TEXT,
    p_name TEXT DEFAULT ''::TEXT,
    p_model TEXT DEFAULT ''::TEXT,
    p_hardware_uuid TEXT DEFAULT NULL::TEXT,
    p_public_key_spki TEXT DEFAULT NULL::TEXT,
    p_key_algorithm TEXT DEFAULT 'ES256'::TEXT,
    p_api_key_hash TEXT DEFAULT NULL::TEXT,
    p_api_key_salt TEXT DEFAULT NULL::TEXT,
    p_device_metadata JSONB DEFAULT '{}'::JSONB
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_clean_device_id TEXT;
    v_clean_name TEXT;
    v_clean_model TEXT;
    v_clean_public_key TEXT;
    v_status TEXT;
    v_already_enrolled BOOLEAN;
    v_recent_enrollments INT;
    v_existing_public_key TEXT;
BEGIN
    v_clean_device_id := TRIM(p_device_id);
    IF v_clean_device_id IS NULL OR v_clean_device_id = '' OR LENGTH(v_clean_device_id) > 200 THEN
        RAISE EXCEPTION 'INVALID_DEVICE_ID: Device ID cannot be null, empty, or exceed 200 characters' USING ERRCODE = '22023';
    END IF;

    v_clean_name := NULLIF(TRIM(p_name), '');
    v_clean_model := NULLIF(TRIM(p_model), '');
    v_clean_public_key := NULLIF(TRIM(p_public_key_spki), '');

    -- Check if device is already registered in public.devices
    SELECT status::TEXT, public_key_spki
    INTO v_status, v_existing_public_key
    FROM public.devices
    WHERE device_id = v_clean_device_id;

    IF FOUND THEN
        -- Anti-takeover check: If already enrolled with a public key and caller presents a differing non-null key
        IF v_existing_public_key IS NOT NULL 
           AND v_clean_public_key IS NOT NULL 
           AND v_existing_public_key != v_clean_public_key THEN
            RAISE EXCEPTION 'PUBLIC_KEY_MISMATCH: Cannot re-register device with differing public key'
                USING ERRCODE = '42501';
        END IF;

        v_already_enrolled := TRUE;

        UPDATE public.devices
        SET updated_at = NOW(),
            last_seen_at = NOW(),
            name = COALESCE(v_clean_name, public.devices.name),
            model = COALESCE(v_clean_model, public.devices.model),
            hardware_uuid = COALESCE(p_hardware_uuid, public.devices.hardware_uuid),
            public_key_spki = COALESCE(v_clean_public_key, public.devices.public_key_spki)
        WHERE device_id = v_clean_device_id;
    ELSE
        -- Rate Limiting: Max 50 new device registrations per hour
        SELECT COUNT(*) INTO v_recent_enrollments
        FROM public.devices
        WHERE created_at > NOW() - INTERVAL '1 hour';

        IF v_recent_enrollments >= 50 THEN
            RAISE EXCEPTION 'RATE_LIMITED: Device enrollment threshold exceeded' USING ERRCODE = '54000';
        END IF;

        v_already_enrolled := FALSE;
        v_status := 'ACTIVE';

        INSERT INTO public.devices (
            device_id,
            name,
            model,
            hardware_uuid,
            public_key_spki,
            status,
            created_at,
            updated_at,
            last_seen_at
        ) VALUES (
            v_clean_device_id,
            COALESCE(v_clean_name, v_clean_device_id),
            COALESCE(v_clean_model, v_clean_device_id),
            p_hardware_uuid,
            v_clean_public_key,
            'ACTIVE',
            NOW(),
            NOW(),
            NOW()
        );
    END IF;

    -- Sync to public.enrolled_devices if table exists
    IF v_clean_public_key IS NOT NULL THEN
        BEGIN
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
            )
            ON CONFLICT (device_id) DO UPDATE SET
                last_seen_at = NOW(),
                public_key_spki = EXCLUDED.public_key_spki,
                device_metadata = CASE 
                    WHEN p_device_metadata IS NOT NULL AND p_device_metadata != '{}'::jsonb 
                    THEN EXCLUDED.device_metadata 
                    ELSE public.enrolled_devices.device_metadata 
                END;
        EXCEPTION WHEN undefined_table THEN
            NULL;
        END;
    END IF;

    -- Sync to private.device_secrets if API key hash and salt are provided
    IF p_api_key_hash IS NOT NULL AND p_api_key_salt IS NOT NULL THEN
        BEGIN
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
        EXCEPTION WHEN undefined_table THEN
            NULL;
        END;
    END IF;

    RETURN jsonb_build_object(
        'success', TRUE,
        'device_id', v_clean_device_id,
        'status', v_status,
        'already_enrolled', v_already_enrolled
    );
END;
$$;

REVOKE ALL ON FUNCTION public.enroll_tracker_device(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.enroll_tracker_device(TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, JSONB) TO anon, authenticated, service_role;
