-- Captured from live before 20261102000002_trace_permission_core.sql.

BEGIN;

CREATE OR REPLACE FUNCTION public.assign_device_to_user(p_device_id text, p_user_id uuid, p_expires_at timestamp with time zone DEFAULT NULL::timestamp with time zone)
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

CREATE OR REPLACE FUNCTION public.grant_device_feature_permission(p_user_id uuid, p_device_id text, p_feature text, p_expires_at timestamp with time zone DEFAULT NULL::timestamp with time zone)
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO 'public', 'private', 'extensions', 'pg_temp'
AS $function$
DECLARE
    v_clean_device_id TEXT;
    v_clean_feature TEXT;
    v_assignment_id BIGINT;
    v_actor_id UUID;
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

    SELECT id INTO v_assignment_id
    FROM public.user_device_assignments
    WHERE user_id = p_user_id
      AND device_id = v_clean_device_id
      AND status = 'ACTIVE'
      AND (expires_at IS NULL OR expires_at > NOW())
    LIMIT 1;

    IF v_assignment_id IS NULL THEN
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
            COALESCE(auth.uid(), (SELECT id FROM public.profiles WHERE role = 'OWNER' LIMIT 1))
        )
        ON CONFLICT (user_id, device_id) DO UPDATE SET
            status = 'ACTIVE',
            assigned_at = NOW(),
            expires_at = EXCLUDED.expires_at,
            assigned_by = EXCLUDED.assigned_by
        RETURNING id INTO v_assignment_id;
    END IF;

    v_actor_id := COALESCE(auth.uid(), (SELECT id FROM public.profiles WHERE role = 'OWNER' LIMIT 1));

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
        v_actor_id,
        NOW()
    )
    ON CONFLICT (assignment_id, feature) DO UPDATE SET
        is_allowed = TRUE,
        expires_at = EXCLUDED.expires_at,
        granted_by = v_actor_id,
        granted_at = NOW();

    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        v_actor_id,
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
$function$;

CREATE OR REPLACE FUNCTION public.has_command_permission(p_device_id text, p_command text)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
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
$function$;

CREATE OR REPLACE FUNCTION public.has_device_access(p_device_id text)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
  SELECT public.is_admin_or_owner()
  OR EXISTS (
    SELECT 1
    FROM public.user_device_assignments uda
    WHERE uda.user_id = (SELECT auth.uid())
      AND uda.device_id = p_device_id
      AND uda.status = 'ACTIVE'
      AND (uda.expires_at IS NULL OR uda.expires_at > now())
  );
$function$;

CREATE OR REPLACE FUNCTION public.has_device_permission(p_device_id text, p_feature text)
 RETURNS boolean
 LANGUAGE sql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
  SELECT public.is_admin_or_owner()
  OR EXISTS (
    SELECT 1
    FROM public.user_device_assignments uda
    JOIN public.device_feature_permissions dfp ON dfp.assignment_id = uda.id
    WHERE uda.user_id = (SELECT auth.uid())
      AND uda.device_id = p_device_id
      AND uda.status = 'ACTIVE'
      AND (uda.expires_at IS NULL OR uda.expires_at > now())
      AND dfp.feature = p_feature
      AND dfp.is_allowed = TRUE
      AND (dfp.expires_at IS NULL OR dfp.expires_at > now())
  );
$function$;

CREATE OR REPLACE FUNCTION public.is_admin_or_owner()
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
BEGIN
    IF auth.uid() IS NOT NULL THEN
        RETURN EXISTS (
            SELECT 1 FROM public.profiles 
            WHERE id = auth.uid() AND role IN ('OWNER', 'ADMIN')
        );
    END IF;
    RETURN FALSE;
END;
$function$;

CREATE OR REPLACE FUNCTION public.is_owner()
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
BEGIN
    IF auth.uid() IS NOT NULL THEN
        RETURN EXISTS (
            SELECT 1 FROM public.profiles 
            WHERE id = auth.uid() AND role = 'OWNER'
        );
    END IF;
    RETURN FALSE;
END;
$function$;

CREATE OR REPLACE FUNCTION public.revoke_device_feature_permission(p_user_id uuid, p_device_id text, p_feature text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO 'public', 'private', 'extensions', 'pg_temp'
AS $function$
DECLARE
    v_clean_device_id TEXT;
    v_clean_feature TEXT;
    v_assignment_id BIGINT;
    v_actor_id UUID;
    v_rows_affected INT;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can revoke feature permissions' USING ERRCODE = '42501';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_USER_ID: User ID cannot be null' USING ERRCODE = '22023';
    END IF;

    v_clean_device_id := TRIM(p_device_id);
    v_clean_feature := UPPER(TRIM(p_feature));
    v_actor_id := COALESCE(auth.uid(), (SELECT id FROM public.profiles WHERE role = 'OWNER' LIMIT 1));

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

    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        v_actor_id,
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
$function$;

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

-- Policies captured before replacement.

+DROP POLICY IF EXISTS app_signals_delete ON public.app_signals;
CREATE POLICY app_signals_delete ON public.app_signals FOR DELETE TO authenticated USING (is_admin_or_owner());

DROP POLICY IF EXISTS app_signals_insert ON public.app_signals;
CREATE POLICY app_signals_insert ON public.app_signals FOR INSERT TO authenticated WITH CHECK (is_active_member());

DROP POLICY IF EXISTS app_signals_select ON public.app_signals;
CREATE POLICY app_signals_select ON public.app_signals FOR SELECT TO authenticated USING (is_active_member());

DROP POLICY IF EXISTS app_signals_update ON public.app_signals;
CREATE POLICY app_signals_update ON public.app_signals FOR UPDATE TO authenticated USING (is_active_member()) WITH CHECK (is_active_member());

DROP POLICY IF EXISTS commands_anon_select ON public.commands;
CREATE POLICY commands_anon_select ON public.commands FOR SELECT TO anon USING (private.device_is_active(device_id));

DROP POLICY IF EXISTS commands_anon_update ON public.commands;
CREATE POLICY commands_anon_update ON public.commands FOR UPDATE TO anon USING (((status = 'pending'::text) AND private.device_is_active(device_id))) WITH CHECK ((status = ANY (ARRAY['executed'::text, 'failed'::text, 'expired'::text])));

DROP POLICY IF EXISTS commands_delete ON public.commands;
CREATE POLICY commands_delete ON public.commands FOR DELETE TO authenticated USING (is_admin_or_owner());

DROP POLICY IF EXISTS commands_insert ON public.commands;
CREATE POLICY commands_insert ON public.commands FOR INSERT TO authenticated WITH CHECK (has_command_permission(device_id, command));

DROP POLICY IF EXISTS commands_select ON public.commands;
CREATE POLICY commands_select ON public.commands FOR SELECT TO authenticated USING (has_device_access(device_id));

DROP POLICY IF EXISTS commands_update ON public.commands;
CREATE POLICY commands_update ON public.commands FOR UPDATE TO authenticated USING (has_device_access(device_id)) WITH CHECK (has_device_access(device_id));

DROP POLICY IF EXISTS media_delete ON public.media;
CREATE POLICY media_delete ON public.media FOR DELETE TO authenticated USING (is_admin_or_owner());

DROP POLICY IF EXISTS media_insert ON public.media;
CREATE POLICY media_insert ON public.media FOR INSERT TO authenticated WITH CHECK ((has_device_permission(device_id, 'CAMERA'::text) OR has_device_permission(device_id, 'MIC'::text)));

DROP POLICY IF EXISTS media_select ON public.media;
CREATE POLICY media_select ON public.media FOR SELECT TO authenticated USING ((has_device_permission(device_id, 'CAMERA'::text) OR has_device_permission(device_id, 'MIC'::text)));

DROP POLICY IF EXISTS media_update ON public.media;
CREATE POLICY media_update ON public.media FOR UPDATE TO authenticated USING (is_admin_or_owner()) WITH CHECK (is_admin_or_owner());

COMMIT;


