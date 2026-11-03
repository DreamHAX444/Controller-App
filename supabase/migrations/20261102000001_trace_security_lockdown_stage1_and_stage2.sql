-- ============================================================================
-- TRACE Security Hardening: Stage 1 Ingestion Lockdown & Assignment-Based RBAC
-- Replaces previous pairing-token / device-secret schema with assignment-based model.
-- Verified Live on: 2026-11-02
-- Schema: public, private, storage
-- ============================================================================

BEGIN;

-- ============================================================================
-- 1. Helper Predicates & Schema Isolation
-- ============================================================================
CREATE SCHEMA IF NOT EXISTS private;
GRANT USAGE ON SCHEMA private TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.is_active_member()
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.profiles p
    WHERE p.id = (SELECT auth.uid())
      AND p.status::text = 'ACTIVE'
      AND p.role::text IN ('OWNER','ADMIN','USER')
  );
$$;
REVOKE EXECUTE ON FUNCTION public.is_active_member() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_active_member() TO authenticated, service_role;

CREATE OR REPLACE FUNCTION private.device_is_active(p_device_id TEXT)
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.devices d
    WHERE d.device_id = p_device_id AND d.status::text = 'ACTIVE'
  );
$$;
REVOKE EXECUTE ON FUNCTION private.device_is_active(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION private.device_is_active(TEXT) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.has_device_access(p_device_id TEXT)
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp AS $$
  SELECT public.is_admin_or_owner()
  OR EXISTS (
    SELECT 1
    FROM public.user_device_assignments uda
    WHERE uda.user_id = (SELECT auth.uid())
      AND uda.device_id = p_device_id
      AND uda.status = 'ACTIVE'
      AND (uda.expires_at IS NULL OR uda.expires_at > NOW())
  );
$$;
REVOKE EXECUTE ON FUNCTION public.has_device_access(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.has_device_access(TEXT) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.has_device_permission(p_device_id TEXT, p_feature TEXT)
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp AS $$
  SELECT public.is_admin_or_owner()
  OR EXISTS (
    SELECT 1
    FROM public.user_device_assignments uda
    JOIN public.device_feature_permissions dfp ON dfp.assignment_id = uda.id
    WHERE uda.user_id = (SELECT auth.uid())
      AND uda.device_id = p_device_id
      AND uda.status = 'ACTIVE'
      AND (uda.expires_at IS NULL OR uda.expires_at > NOW())
      AND dfp.feature = p_feature
      AND dfp.is_allowed = TRUE
      AND (dfp.expires_at IS NULL OR dfp.expires_at > NOW())
  );
$$;
REVOKE EXECUTE ON FUNCTION public.has_device_permission(TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.has_device_permission(TEXT, TEXT) TO authenticated, service_role;

CREATE OR REPLACE FUNCTION public.has_command_permission(p_device_id TEXT, p_command TEXT)
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp AS $$
  SELECT public.is_admin_or_owner()
  OR (
    public.is_active_member()
    AND CASE
      WHEN p_command ILIKE '%camera%' THEN public.has_device_permission(p_device_id, 'CAMERA')
      WHEN p_command ILIKE '%mic%' OR p_command ILIKE '%audio%' THEN public.has_device_permission(p_device_id, 'MIC')
      WHEN p_command ILIKE '%screen%' THEN public.has_device_permission(p_device_id, 'SCREEN')
      ELSE public.has_device_permission(p_device_id, 'LOCATION')
    END
  );
$$;
REVOKE EXECUTE ON FUNCTION public.has_command_permission(TEXT, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.has_command_permission(TEXT, TEXT) TO authenticated, service_role;

-- ============================================================================
-- 2. Table Privilege Lockdown
-- ============================================================================
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA private FROM anon, authenticated, PUBLIC;
REVOKE TRUNCATE, REFERENCES, TRIGGER ON ALL TABLES IN SCHEMA public FROM authenticated;

-- Grant strictly necessary tracker permissions to anon
GRANT INSERT ON public.locations TO anon;
GRANT SELECT, UPDATE ON public.commands TO anon;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON TABLES FROM anon;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, anon;

-- ============================================================================
-- 3. Row-Level Security Policies (Assignment-Based RBAC)
-- ============================================================================

-- Devices
DROP POLICY IF EXISTS devices_select ON public.devices;
DROP POLICY IF EXISTS devices_insert ON public.devices;
DROP POLICY IF EXISTS devices_update ON public.devices;
DROP POLICY IF EXISTS devices_delete ON public.devices;

CREATE POLICY devices_select ON public.devices FOR SELECT TO authenticated
  USING (public.has_device_access(device_id));
CREATE POLICY devices_insert ON public.devices FOR INSERT TO authenticated
  WITH CHECK (public.is_admin_or_owner());
CREATE POLICY devices_update ON public.devices FOR UPDATE TO authenticated
  USING (public.is_admin_or_owner()) WITH CHECK (public.is_admin_or_owner());
CREATE POLICY devices_delete ON public.devices FOR DELETE TO authenticated
  USING (public.is_admin_or_owner());

-- Locations
DROP POLICY IF EXISTS locations_select ON public.locations;
DROP POLICY IF EXISTS locations_insert ON public.locations;
DROP POLICY IF EXISTS locations_update ON public.locations;
DROP POLICY IF EXISTS locations_delete ON public.locations;
DROP POLICY IF EXISTS locations_anon_insert ON public.locations;

CREATE POLICY locations_select ON public.locations FOR SELECT TO authenticated
  USING (public.has_device_permission(device_id, 'LOCATION'));
CREATE POLICY locations_anon_insert ON public.locations FOR INSERT TO anon
  WITH CHECK (device_id IS NOT NULL AND private.device_is_active(device_id));
CREATE POLICY locations_insert ON public.locations FOR INSERT TO authenticated
  WITH CHECK (public.is_admin_or_owner());
CREATE POLICY locations_update ON public.locations FOR UPDATE TO authenticated
  USING (public.is_admin_or_owner()) WITH CHECK (public.is_admin_or_owner());
CREATE POLICY locations_delete ON public.locations FOR DELETE TO authenticated
  USING (public.is_admin_or_owner());

-- Commands
DROP POLICY IF EXISTS commands_select ON public.commands;
DROP POLICY IF EXISTS commands_insert ON public.commands;
DROP POLICY IF EXISTS commands_update ON public.commands;
DROP POLICY IF EXISTS commands_delete ON public.commands;
DROP POLICY IF EXISTS commands_anon_select ON public.commands;
DROP POLICY IF EXISTS commands_anon_update ON public.commands;

CREATE POLICY commands_select ON public.commands FOR SELECT TO authenticated
  USING (public.has_device_access(device_id));
CREATE POLICY commands_anon_select ON public.commands FOR SELECT TO anon
  USING (private.device_is_active(device_id));
CREATE POLICY commands_insert ON public.commands FOR INSERT TO authenticated
  WITH CHECK (public.has_command_permission(device_id, command));
CREATE POLICY commands_anon_update ON public.commands FOR UPDATE TO anon
  USING (status = 'pending' AND private.device_is_active(device_id))
  WITH CHECK (status IN ('executed','failed','expired'));
CREATE POLICY commands_update ON public.commands FOR UPDATE TO authenticated
  USING (public.has_device_access(device_id)) WITH CHECK (public.has_device_access(device_id));
CREATE POLICY commands_delete ON public.commands FOR DELETE TO authenticated
  USING (public.is_admin_or_owner());

-- Media
DROP POLICY IF EXISTS media_select ON public.media;
DROP POLICY IF EXISTS media_insert ON public.media;
DROP POLICY IF EXISTS media_update ON public.media;
DROP POLICY IF EXISTS media_delete ON public.media;

CREATE POLICY media_select ON public.media FOR SELECT TO authenticated
  USING (public.has_device_permission(device_id, 'CAMERA') OR public.has_device_permission(device_id, 'MIC'));
CREATE POLICY media_insert ON public.media FOR INSERT TO authenticated
  WITH CHECK (public.has_device_permission(device_id, 'CAMERA') OR public.has_device_permission(device_id, 'MIC'));
CREATE POLICY media_update ON public.media FOR UPDATE TO authenticated
  USING (public.is_admin_or_owner()) WITH CHECK (public.is_admin_or_owner());
CREATE POLICY media_delete ON public.media FOR DELETE TO authenticated
  USING (public.is_admin_or_owner());

-- App Signals
DROP POLICY IF EXISTS app_signals_select ON public.app_signals;
DROP POLICY IF EXISTS app_signals_insert ON public.app_signals;
DROP POLICY IF EXISTS app_signals_update ON public.app_signals;
DROP POLICY IF EXISTS app_signals_delete ON public.app_signals;

CREATE POLICY app_signals_select ON public.app_signals FOR SELECT TO authenticated
  USING (public.is_active_member());
CREATE POLICY app_signals_insert ON public.app_signals FOR INSERT TO authenticated
  WITH CHECK (public.is_active_member());
CREATE POLICY app_signals_update ON public.app_signals FOR UPDATE TO authenticated
  USING (public.is_active_member()) WITH CHECK (public.is_active_member());
CREATE POLICY app_signals_delete ON public.app_signals FOR DELETE TO authenticated
  USING (public.is_admin_or_owner());

-- User Device Assignments
DROP POLICY IF EXISTS "Allow read assignments" ON public.user_device_assignments;
DROP POLICY IF EXISTS "Allow admin owner insert assignments" ON public.user_device_assignments;
DROP POLICY IF EXISTS "Allow admin owner update assignments" ON public.user_device_assignments;
DROP POLICY IF EXISTS "Allow admin owner remove assignments" ON public.user_device_assignments;
DROP POLICY IF EXISTS user_device_assignments_select ON public.user_device_assignments;
DROP POLICY IF EXISTS user_device_assignments_insert ON public.user_device_assignments;
DROP POLICY IF EXISTS user_device_assignments_update ON public.user_device_assignments;
DROP POLICY IF EXISTS user_device_assignments_delete ON public.user_device_assignments;

CREATE POLICY user_device_assignments_select ON public.user_device_assignments FOR SELECT TO authenticated
  USING (public.is_admin_or_owner() OR user_id = (SELECT auth.uid()));
CREATE POLICY user_device_assignments_insert ON public.user_device_assignments FOR INSERT TO authenticated
  WITH CHECK (public.is_admin_or_owner());
CREATE POLICY user_device_assignments_update ON public.user_device_assignments FOR UPDATE TO authenticated
  USING (public.is_admin_or_owner()) WITH CHECK (public.is_admin_or_owner());
CREATE POLICY user_device_assignments_delete ON public.user_device_assignments FOR DELETE TO authenticated
  USING (public.is_admin_or_owner());

-- Device Feature Permissions
DROP POLICY IF EXISTS "Allow read permissions" ON public.device_feature_permissions;
DROP POLICY IF EXISTS "Allow admin owner insert permissions" ON public.device_feature_permissions;
DROP POLICY IF EXISTS "Allow admin owner update permissions" ON public.device_feature_permissions;
DROP POLICY IF EXISTS "Allow admin owner remove permissions" ON public.device_feature_permissions;
DROP POLICY IF EXISTS device_feature_permissions_select ON public.device_feature_permissions;
DROP POLICY IF EXISTS device_feature_permissions_insert ON public.device_feature_permissions;
DROP POLICY IF EXISTS device_feature_permissions_update ON public.device_feature_permissions;
DROP POLICY IF EXISTS device_feature_permissions_delete ON public.device_feature_permissions;

CREATE POLICY device_feature_permissions_select ON public.device_feature_permissions FOR SELECT TO authenticated
  USING (public.is_admin_or_owner() OR EXISTS (
    SELECT 1 FROM public.user_device_assignments uda
    WHERE uda.id = device_feature_permissions.assignment_id AND uda.user_id = (SELECT auth.uid())
  ));
CREATE POLICY device_feature_permissions_insert ON public.device_feature_permissions FOR INSERT TO authenticated
  WITH CHECK (public.is_admin_or_owner());
CREATE POLICY device_feature_permissions_update ON public.device_feature_permissions FOR UPDATE TO authenticated
  USING (public.is_admin_or_owner()) WITH CHECK (public.is_admin_or_owner());
CREATE POLICY device_feature_permissions_delete ON public.device_feature_permissions FOR DELETE TO authenticated
  USING (public.is_admin_or_owner());

-- Profiles
DROP POLICY IF EXISTS "Allow authenticated read profiles" ON public.profiles;
DROP POLICY IF EXISTS "Allow authenticated insert own profile" ON public.profiles;
DROP POLICY IF EXISTS "Allow admin owner or self update profile" ON public.profiles;

CREATE POLICY "Allow authenticated read profiles" ON public.profiles FOR SELECT TO authenticated
  USING ((id = (SELECT auth.uid())) OR public.is_admin_or_owner());
CREATE POLICY "Allow authenticated insert own profile" ON public.profiles FOR INSERT TO authenticated
  WITH CHECK ((id = (SELECT auth.uid())) AND (role = 'VISITOR'::user_role));
CREATE POLICY "Allow admin owner or self update profile" ON public.profiles FOR UPDATE TO authenticated
  USING ((id = (SELECT auth.uid())) OR public.is_admin_or_owner())
  WITH CHECK ((id = (SELECT auth.uid())) OR public.is_admin_or_owner());

-- ============================================================================
-- 4. Core Security Definer Functions & Triggers
-- ============================================================================

-- Prune Trigger
CREATE OR REPLACE FUNCTION public.prune_old_locations()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER
SET search_path TO 'public', 'pg_temp' AS $$
BEGIN
  DELETE FROM public.locations WHERE device_id = NEW.device_id AND id <> NEW.id;
  RETURN NEW;
END $$;

-- Auto-Registration: Security Definer with Flood Guard (50 devices/hour limit)
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
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp AS $$
DECLARE
    v_clean_device_id TEXT;
    v_clean_name TEXT;
    v_clean_model TEXT;
    v_status TEXT;
    v_already_enrolled BOOLEAN;
    v_recent_enrollments INT;
BEGIN
    v_clean_device_id := TRIM(p_device_id);
    IF v_clean_device_id IS NULL OR v_clean_device_id = '' OR LENGTH(v_clean_device_id) > 200 THEN
        RAISE EXCEPTION 'INVALID_DEVICE_ID: Device ID cannot be null, empty, or exceed 200 characters' USING ERRCODE = '22023';
    END IF;

    v_clean_name := NULLIF(TRIM(p_name), '');
    v_clean_model := NULLIF(TRIM(p_model), '');

    SELECT status::TEXT INTO v_status FROM public.devices WHERE device_id = v_clean_device_id;
    IF FOUND THEN
        v_already_enrolled := TRUE;
        UPDATE public.devices
        SET updated_at = NOW(),
            name = COALESCE(v_clean_name, public.devices.name),
            model = COALESCE(v_clean_model, public.devices.model)
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

        INSERT INTO public.devices (device_id, name, model, status, created_at, updated_at)
        VALUES (
            v_clean_device_id,
            COALESCE(v_clean_name, v_clean_device_id),
            COALESCE(v_clean_model, v_clean_device_id),
            'ACTIVE',
            NOW(),
            NOW()
        );
    END IF;

    RETURN jsonb_build_object(
        'success', TRUE,
        'device_id', v_clean_device_id,
        'status', v_status,
        'already_enrolled', v_already_enrolled
    );
END;
$$;
REVOKE EXECUTE ON FUNCTION public.enroll_tracker_device(TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.enroll_tracker_device(TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,TEXT,JSONB) TO anon, authenticated, service_role;

-- Handle New User: Sets role VISITOR and status PENDING
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
        'PENDING'::account_status,
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

-- Approve Account Guard
CREATE OR REPLACE FUNCTION public.approve_user_account(p_user_id UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, private, extensions, pg_temp AS $$
DECLARE v_target public.profiles%ROWTYPE;
BEGIN
  IF NOT public.is_admin_or_owner() THEN
    RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can approve user accounts' USING ERRCODE='42501';
  END IF;
  IF p_user_id IS NULL THEN
    RAISE EXCEPTION 'INVALID_USER_ID: User ID cannot be null' USING ERRCODE='22023';
  END IF;
  PERFORM pg_advisory_xact_lock(hashtext('owner_guard'));
  SELECT * INTO v_target FROM public.profiles WHERE id = p_user_id FOR UPDATE;
  IF v_target.id IS NULL THEN
    RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE='22023';
  END IF;
  IF v_target.role::TEXT IN ('OWNER','ADMIN') THEN
    RAISE EXCEPTION 'FORBIDDEN: approval only applies to VISITOR/USER accounts' USING ERRCODE='42501';
  END IF;
  UPDATE public.profiles
     SET role = 'USER'::user_role, status = 'ACTIVE'::account_status, updated_at = NOW()
   WHERE id = p_user_id;
  INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
  VALUES (auth.uid(), 'USER_ACCOUNT_APPROVAL', 'profiles', p_user_id::TEXT,
          jsonb_build_object('previous_role', v_target.role::TEXT,
                             'previous_status', v_target.status::TEXT,
                             'new_role','USER','new_status','ACTIVE'));
  RETURN jsonb_build_object('success', TRUE, 'user_id', p_user_id, 'role','USER','status','ACTIVE');
END $$;
REVOKE EXECUTE ON FUNCTION public.approve_user_account(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.approve_user_account(UUID) TO authenticated, service_role;

-- Assign Device to User
CREATE OR REPLACE FUNCTION public.assign_device_to_user(
    p_device_id text,
    p_user_id uuid,
    p_expires_at timestamp with time zone DEFAULT NULL::timestamp with time zone
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'private', 'extensions', 'pg_temp'
AS $function$
DECLARE
    v_clean_device_id TEXT;
    v_user_record public.profiles%ROWTYPE;
    v_actor_id UUID;
    v_asgn_id BIGINT;
BEGIN
    v_clean_device_id := TRIM(p_device_id);
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can assign devices' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_user_record FROM public.profiles WHERE id = p_user_id;
    IF v_user_record.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.devices (device_id, name, model, status, created_at, updated_at)
    VALUES (v_clean_device_id, v_clean_device_id, v_clean_device_id, 'ACTIVE', NOW(), NOW())
    ON CONFLICT (device_id) DO UPDATE SET updated_at = NOW();

    v_actor_id := COALESCE(auth.uid(), (SELECT id FROM public.profiles WHERE role = 'OWNER' LIMIT 1));

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
        v_actor_id
    )
    ON CONFLICT (user_id, device_id) DO UPDATE SET
        status = 'ACTIVE',
        assigned_at = NOW(),
        expires_at = EXCLUDED.expires_at,
        assigned_by = v_actor_id
    RETURNING id INTO v_asgn_id;

    INSERT INTO public.device_feature_permissions (assignment_id, feature, is_allowed, granted_by, granted_at)
    VALUES (v_asgn_id, 'LOCATION', TRUE, v_actor_id, NOW())
    ON CONFLICT (assignment_id, feature) DO UPDATE SET is_allowed = TRUE;

    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        v_actor_id,
        'DEVICE_ASSIGNMENT',
        'user_device_assignments',
        v_clean_device_id,
        jsonb_build_object('target_user_id', p_user_id, 'expires_at', p_expires_at, 'assignment_id', v_asgn_id)
    );

    RETURN jsonb_build_object('success', TRUE, 'device_id', v_clean_device_id, 'user_id', p_user_id, 'status', 'ACTIVE', 'assignment_id', v_asgn_id);
END;
$function$;

-- Unassign Device from User
CREATE OR REPLACE FUNCTION public.unassign_device_from_user(p_device_id text, p_user_id uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'private', 'extensions', 'pg_temp'
AS $function$
DECLARE
    v_clean_device_id TEXT;
    v_assignment_id BIGINT;
    v_actor_id UUID;
BEGIN
    v_clean_device_id := TRIM(p_device_id);
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can revoke device assignments' USING ERRCODE = '42501';
    END IF;

    v_actor_id := COALESCE(auth.uid(), (SELECT id FROM public.profiles WHERE role = 'OWNER' LIMIT 1));

    UPDATE public.user_device_assignments
    SET status = 'REVOKED'
    WHERE device_id = v_clean_device_id AND user_id = p_user_id
    RETURNING id INTO v_assignment_id;

    IF v_assignment_id IS NOT NULL THEN
        UPDATE public.device_feature_permissions
        SET is_allowed = FALSE
        WHERE assignment_id = v_assignment_id;
    END IF;

    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        v_actor_id,
        'DEVICE_UNASSIGNMENT',
        'user_device_assignments',
        v_clean_device_id,
        jsonb_build_object('target_user_id', p_user_id, 'status', 'REVOKED')
    );

    RETURN jsonb_build_object('success', TRUE, 'device_id', v_clean_device_id, 'user_id', p_user_id, 'status', 'REVOKED');
END;
$function$;

-- Fetch User Effective Permissions
CREATE OR REPLACE FUNCTION public.fetch_user_effective_permissions(p_user_id uuid DEFAULT NULL::uuid)
RETURNS jsonb
LANGUAGE plpgsql
STABLE SECURITY DEFINER
SET search_path TO 'public', 'private', 'extensions', 'pg_temp'
AS $function$
DECLARE
    v_target_id UUID;
    v_user public.profiles%ROWTYPE;
    v_assignments JSONB;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        v_target_id := auth.uid();
    ELSE
        v_target_id := COALESCE(p_user_id, auth.uid());
    END IF;

    IF v_target_id IS NULL THEN
        SELECT id INTO v_target_id FROM public.profiles WHERE role = 'OWNER' LIMIT 1;
    END IF;

    IF v_target_id IS NULL THEN
        RAISE EXCEPTION 'UNAUTHENTICATED: Caller must be authenticated or specify user_id' USING ERRCODE = '28000';
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
                'device_name', COALESCE(d.name, uda.device_id),
                'device_status', COALESCE(d.status::text, 'ACTIVE'),
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
    LEFT JOIN public.devices d ON d.device_id = uda.device_id
    WHERE uda.user_id = v_target_id
      AND uda.status = 'ACTIVE'
      AND (uda.expires_at IS NULL OR uda.expires_at > NOW());

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
$function$;

-- Storage Policies
DROP POLICY IF EXISTS "Allow public access to file-transfers bucket" ON storage.objects;
DROP POLICY IF EXISTS file_transfers_anon_upload ON storage.objects;
DROP POLICY IF EXISTS file_transfers_member_all ON storage.objects;

CREATE POLICY file_transfers_anon_upload ON storage.objects FOR INSERT TO anon
  WITH CHECK (bucket_id = 'file-transfers');
CREATE POLICY file_transfers_member_all ON storage.objects FOR ALL TO authenticated
  USING      (bucket_id = 'file-transfers' AND public.is_active_member())
  WITH CHECK (bucket_id = 'file-transfers' AND public.is_active_member());

COMMIT;
