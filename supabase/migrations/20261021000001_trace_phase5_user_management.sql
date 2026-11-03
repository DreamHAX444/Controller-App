-- ============================================================================
-- TRACE Phase 5 — Database Migration: User Management & Feature Permissions RPCs
-- Target Schema: public.profiles, public.user_device_assignments,
--                public.device_feature_permissions, public.audit_logs
-- Features:
-- 1. Account Status Enum evolution ('DISABLED' state)
-- 2. fetch_managed_users: RBAC-filtered user directory with device & permission metrics
-- 3. approve_user_account: Explicit lifecycle approval from PENDING to USER/ACTIVE
-- 4. update_user_account_status: Account suspension/disabling with Owner protections
-- 5. update_user_account_role: Admin appointment & demotion with Owner-only boundaries
-- 6. grant_device_feature_permission: Assignment-bound feature permission upsert
-- 7. revoke_device_feature_permission: Granular feature permission revocation
-- 8. fetch_user_effective_permissions: Complete user profile, active assignments & features
-- ============================================================================

-- 1. Add 'DISABLED' to account_status enum safely
DO $$ 
BEGIN 
    ALTER TYPE account_status ADD VALUE IF NOT EXISTS 'DISABLED'; 
EXCEPTION 
    WHEN duplicate_object THEN NULL; 
END $$;


-- 2. RPC: fetch_managed_users
-- Authority: is_admin_or_owner()
-- Owner sees all accounts.
-- Admin sees Users, Visitors, and Self (cannot see other Admins or Owner).
-- Regular users / Visitors fail closed.
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
        (p.status = 'PENDING') AS pending_approval
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


-- 3. RPC: approve_user_account
-- Authority: is_admin_or_owner()
-- Target must have status='PENDING'. Sets role='USER', status='ACTIVE'. Logs to audit_logs.
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

    IF v_target.status != 'PENDING' THEN
        RAISE EXCEPTION 'INVALID_ACCOUNT_STATUS: User is not pending approval (status: %)', v_target.status
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


-- 4. RPC: update_user_account_status
-- Authority: is_admin_or_owner()
-- Status in ('ACTIVE', 'SUSPENDED', 'DISABLED')
-- Enforce: Admin cannot suspend/disable Owner. Admin cannot suspend/disable other Admins. Owner protected. Logs to audit_logs.
CREATE OR REPLACE FUNCTION public.update_user_account_status(
    p_user_id UUID,
    p_new_status TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_clean_status TEXT;
    v_target public.profiles%ROWTYPE;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can update user account status' USING ERRCODE = '42501';
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

    -- Owner Protection: The Owner account status cannot be modified (Owner is immutable)
    IF v_target.role = 'OWNER' THEN
        RAISE EXCEPTION 'FORBIDDEN: Owner account status is protected and cannot be modified' USING ERRCODE = '42501';
    END IF;

    -- Admin privilege boundary: Admin cannot suspend or disable other Admins
    IF NOT public.is_owner() AND v_target.role = 'ADMIN' THEN
        RAISE EXCEPTION 'FORBIDDEN: Admins cannot modify the status of Admin accounts' USING ERRCODE = '42501';
    END IF;

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


-- 5. RPC: update_user_account_role
-- Role in ('ADMIN', 'USER', 'VISITOR'). No promotion to OWNER (immutable).
-- Enforce: Only Owner can appoint or remove Admins. Admin cannot promote to Admin. Admin cannot demote Owner. Logs to audit_logs.
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

    -- Appointing an Admin: Only Owner can appoint Admins
    IF v_clean_role = 'ADMIN' AND NOT public.is_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only the Owner can appoint Admins' USING ERRCODE = '42501';
    END IF;

    -- Removing/demoting an Admin: Only Owner can remove or demote Admins
    IF v_target.role = 'ADMIN' AND NOT public.is_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only the Owner can demote or remove Admins' USING ERRCODE = '42501';
    END IF;

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


-- 6. RPC: grant_device_feature_permission
-- Feature in ('LOCATION', 'CAMERA', 'FILES', 'MIC', 'SCREEN', 'COMMANDS')
-- Verifies target user has active assignment in user_device_assignments.
-- Upserts into public.device_feature_permissions. Logs to audit_logs.
CREATE OR REPLACE FUNCTION public.grant_device_feature_permission(
    p_user_id UUID,
    p_device_id TEXT,
    p_feature TEXT,
    p_expires_at TIMESTAMPTZ DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_clean_device_id TEXT;
    v_clean_feature TEXT;
    v_assignment_id BIGINT;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can grant feature permissions' USING ERRCODE = '42501';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_USER_ID: User ID cannot be null' USING ERRCODE = '22023';
    END IF;

    v_clean_device_id := TRIM(p_device_id);
    IF v_clean_device_id IS NULL OR v_clean_device_id = '' THEN
        RAISE EXCEPTION 'INVALID_DEVICE_ID: Device ID cannot be empty' USING ERRCODE = '22023';
    END IF;

    v_clean_feature := UPPER(TRIM(p_feature));
    IF v_clean_feature NOT IN ('LOCATION', 'CAMERA', 'FILES', 'MIC', 'SCREEN', 'COMMANDS') THEN
        RAISE EXCEPTION 'INVALID_FEATURE: Feature % is not supported', p_feature USING ERRCODE = '22023';
    END IF;

    -- Verify target user has an active assignment for this device
    SELECT id INTO v_assignment_id
    FROM public.user_device_assignments
    WHERE user_id = p_user_id
      AND device_id = v_clean_device_id
      AND status = 'ACTIVE'
      AND (expires_at IS NULL OR expires_at > NOW())
    LIMIT 1;

    IF v_assignment_id IS NULL THEN
        RAISE EXCEPTION 'ASSIGNMENT_NOT_FOUND: User does not have an active assignment for device %', v_clean_device_id
            USING ERRCODE = '22023';
    END IF;

    -- Upsert feature permission
    INSERT INTO public.device_feature_permissions (
        assignment_id,
        feature,
        is_allowed,
        expires_at,
        granted_by,
        granted_at
    ) VALUES (
        v_assignment_id,
        v_clean_feature,
        TRUE,
        p_expires_at,
        auth.uid(),
        NOW()
    )
    ON CONFLICT (assignment_id, feature) DO UPDATE SET
        is_allowed = TRUE,
        expires_at = EXCLUDED.expires_at,
        granted_by = auth.uid(),
        granted_at = NOW();

    -- Audit log
    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'FEATURE_PERMISSION_GRANT',
        'device_feature_permissions',
        v_clean_device_id,
        jsonb_build_object(
            'target_user_id', p_user_id,
            'device_id', v_clean_device_id,
            'feature', v_clean_feature,
            'assignment_id', v_assignment_id,
            'expires_at', p_expires_at
        )
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'user_id', p_user_id,
        'device_id', v_clean_device_id,
        'feature', v_clean_feature,
        'is_allowed', TRUE,
        'expires_at', p_expires_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.grant_device_feature_permission(UUID, TEXT, TEXT, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.grant_device_feature_permission(UUID, TEXT, TEXT, TIMESTAMPTZ) TO authenticated, service_role;


-- 7. RPC: revoke_device_feature_permission
-- Updates is_allowed = FALSE for target assignment feature. Logs to audit_logs.
CREATE OR REPLACE FUNCTION public.revoke_device_feature_permission(
    p_user_id UUID,
    p_device_id TEXT,
    p_feature TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_clean_device_id TEXT;
    v_clean_feature TEXT;
    v_assignment_id BIGINT;
    v_rows_affected INT;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can revoke feature permissions' USING ERRCODE = '42501';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_USER_ID: User ID cannot be null' USING ERRCODE = '22023';
    END IF;

    v_clean_device_id := TRIM(p_device_id);
    IF v_clean_device_id IS NULL OR v_clean_device_id = '' THEN
        RAISE EXCEPTION 'INVALID_DEVICE_ID: Device ID cannot be empty' USING ERRCODE = '22023';
    END IF;

    v_clean_feature := UPPER(TRIM(p_feature));
    IF v_clean_feature NOT IN ('LOCATION', 'CAMERA', 'FILES', 'MIC', 'SCREEN', 'COMMANDS') THEN
        RAISE EXCEPTION 'INVALID_FEATURE: Feature % is not supported', p_feature USING ERRCODE = '22023';
    END IF;

    SELECT id INTO v_assignment_id
    FROM public.user_device_assignments
    WHERE user_id = p_user_id
      AND device_id = v_clean_device_id
    LIMIT 1;

    IF v_assignment_id IS NOT NULL THEN
        UPDATE public.device_feature_permissions
        SET is_allowed = FALSE
        WHERE assignment_id = v_assignment_id
          AND feature = v_clean_feature;
        GET DIAGNOSTICS v_rows_affected = ROW_COUNT;
    ELSE
        v_rows_affected := 0;
    END IF;

    -- Audit log
    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'FEATURE_PERMISSION_REVOKE',
        'device_feature_permissions',
        v_clean_device_id,
        jsonb_build_object(
            'target_user_id', p_user_id,
            'device_id', v_clean_device_id,
            'feature', v_clean_feature,
            'assignment_id', v_assignment_id,
            'rows_affected', v_rows_affected
        )
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'user_id', p_user_id,
        'device_id', v_clean_device_id,
        'feature', v_clean_feature,
        'is_allowed', FALSE
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.revoke_device_feature_permission(UUID, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.revoke_device_feature_permission(UUID, TEXT, TEXT) TO authenticated, service_role;


-- 8. RPC: fetch_user_effective_permissions
-- Returns user profile, active assignments, and granted features.
-- Self-inspection permitted for authenticated caller; cross-user inspection requires Admin or Owner.
CREATE OR REPLACE FUNCTION public.fetch_user_effective_permissions(p_user_id UUID DEFAULT NULL)
RETURNS JSONB AS $$
DECLARE
    v_target_id UUID;
    v_user public.profiles%ROWTYPE;
    v_assignments JSONB;
BEGIN
    v_target_id := COALESCE(p_user_id, auth.uid());
    IF v_target_id IS NULL THEN
        RAISE EXCEPTION 'UNAUTHENTICATED: Caller must be authenticated or specify user_id' USING ERRCODE = '28000';
    END IF;

    -- Authority check: Only Admin/Owner can inspect other users
    IF v_target_id != auth.uid() AND NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: You do not have permission to inspect other users permissions' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_user FROM public.profiles WHERE id = v_target_id;
    IF v_user.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE = '22023';
    END IF;

    SELECT COALESCE(
        jsonb_agg(
            jsonb_build_object(
                'assignment_id', uda.id,
                'device_id', uda.device_id,
                'device_name', d.name,
                'device_status', d.status::text,
                'status', uda.status,
                'assigned_at', uda.assigned_at,
                'expires_at', uda.expires_at,
                'features', COALESCE(
                    (
                        SELECT jsonb_agg(dfp.feature ORDER BY dfp.feature)
                        FROM public.device_feature_permissions dfp
                        WHERE dfp.assignment_id = uda.id
                          AND dfp.is_allowed = TRUE
                          AND (dfp.expires_at IS NULL OR dfp.expires_at > NOW())
                    ),
                    '[]'::jsonb
                )
            )
            ORDER BY uda.assigned_at DESC
        ),
        '[]'::jsonb
    ) INTO v_assignments
    FROM public.user_device_assignments uda
    JOIN public.devices d ON d.device_id = uda.device_id
    WHERE uda.user_id = v_target_id
      AND uda.status = 'ACTIVE'
      AND (uda.expires_at IS NULL OR uda.expires_at > NOW())
      AND d.status = 'ACTIVE';

    RETURN jsonb_build_object(
        'user_id', v_user.id,
        'email', v_user.email,
        'display_name', v_user.display_name,
        'role', v_user.role::text,
        'status', v_user.status::text,
        'is_owner', (v_user.role = 'OWNER'),
        'created_at', v_user.created_at,
        'updated_at', v_user.updated_at,
        'assignments', v_assignments
    );
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.fetch_user_effective_permissions(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fetch_user_effective_permissions(UUID) TO authenticated, service_role;
