+BEGIN;

CREATE OR REPLACE FUNCTION public.is_admin_or_owner() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO public, pg_temp AS $$
  SELECT EXISTS (SELECT 1 FROM public.profiles WHERE id = (SELECT auth.uid())
    AND status = 'ACTIVE' AND role IN ('OWNER','ADMIN'));
$$;

CREATE OR REPLACE FUNCTION public.is_owner() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO public, pg_temp AS $$
  SELECT EXISTS (SELECT 1 FROM public.profiles WHERE id = (SELECT auth.uid())
    AND status = 'ACTIVE' AND role = 'OWNER');
$$;

CREATE OR REPLACE FUNCTION public.has_device_access(p_device_id text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO public, pg_temp AS $$
  SELECT public.is_admin_or_owner() OR EXISTS (
    SELECT 1 FROM public.profiles p JOIN public.user_device_assignments uda ON uda.user_id=p.id
    WHERE p.id=(SELECT auth.uid()) AND p.status='ACTIVE' AND p.role='USER'
      AND uda.device_id=p_device_id AND uda.status='ACTIVE'
      AND (uda.expires_at IS NULL OR uda.expires_at > now()));
$$;

CREATE OR REPLACE FUNCTION public.has_feature(p_device_id text, p_feature text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO public, pg_temp AS $$
  SELECT public.is_admin_or_owner() OR EXISTS (
    SELECT 1 FROM public.profiles p
    JOIN public.user_device_assignments uda ON uda.user_id=p.id
    JOIN public.device_feature_permissions dfp ON dfp.assignment_id=uda.id
    WHERE p.id=(SELECT auth.uid()) AND p.status='ACTIVE' AND p.role='USER'
      AND uda.device_id=p_device_id AND uda.status='ACTIVE'
      AND (uda.expires_at IS NULL OR uda.expires_at > now())
      AND dfp.feature=upper(trim(p_feature)) AND dfp.is_allowed
      AND (dfp.expires_at IS NULL OR dfp.expires_at > now()));
$$;

CREATE OR REPLACE FUNCTION public.has_device_permission(p_device_id text, p_feature text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO public, pg_temp AS $$
  SELECT public.has_feature(p_device_id,p_feature);
$$;

CREATE OR REPLACE FUNCTION public.has_command_permission(p_device_id text, p_command text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path TO public, pg_temp AS $$
  SELECT public.has_feature(p_device_id,'COMMANDS') AND CASE
    WHEN p_command ILIKE '%camera%' THEN public.has_feature(p_device_id,'CAMERA')
    WHEN p_command ILIKE '%mic%' OR p_command ILIKE '%audio%' THEN public.has_feature(p_device_id,'MIC')
    WHEN p_command ILIKE '%screen%' THEN public.has_feature(p_device_id,'SCREEN')
    WHEN p_command ILIKE '%file%' THEN public.has_feature(p_device_id,'FILES')
    ELSE public.has_device_access(p_device_id)
  END;
$$;

CREATE OR REPLACE FUNCTION public.assign_device_to_user(p_device_id text, p_user_id uuid, p_expires_at timestamptz DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO public, private, extensions, pg_temp AS $$
DECLARE v_device_id text:=trim(p_device_id); v_assignment_id bigint; v_actor uuid:=auth.uid();
BEGIN
 IF NOT public.is_admin_or_owner() THEN RAISE EXCEPTION 'FORBIDDEN' USING ERRCODE='42501'; END IF;
 IF v_actor IS NULL OR v_device_id IS NULL OR v_device_id='' OR length(v_device_id)>200 THEN RAISE EXCEPTION 'INVALID_INPUT' USING ERRCODE='22023'; END IF;
 IF p_expires_at IS NOT NULL AND p_expires_at<=now() THEN RAISE EXCEPTION 'INVALID_EXPIRY' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id=p_user_id AND role='USER' AND status='ACTIVE') THEN RAISE EXCEPTION 'TARGET_NOT_ACTIVE_USER' USING ERRCODE='42501'; END IF;
 IF NOT EXISTS (SELECT 1 FROM public.devices WHERE device_id=v_device_id AND status='ACTIVE') THEN RAISE EXCEPTION 'DEVICE_NOT_FOUND_OR_INACTIVE' USING ERRCODE='22023'; END IF;
 INSERT INTO public.user_device_assignments(user_id,device_id,status,assigned_at,expires_at,assigned_by)
 VALUES(p_user_id,v_device_id,'ACTIVE',now(),p_expires_at,v_actor)
 ON CONFLICT(user_id,device_id) DO UPDATE SET status='ACTIVE',assigned_at=now(),expires_at=excluded.expires_at,assigned_by=v_actor
 RETURNING id INTO v_assignment_id;
 INSERT INTO public.device_feature_permissions(assignment_id,feature,is_allowed,granted_by,granted_at)
 VALUES(v_assignment_id,'LOCATION',true,v_actor,now()) ON CONFLICT(assignment_id,feature) DO UPDATE SET is_allowed=true,granted_by=v_actor,granted_at=now();
 INSERT INTO public.audit_logs(actor_id,action,resource_type,resource_id,payload) VALUES(v_actor,'DEVICE_ASSIGNMENT','user_device_assignments',v_device_id,jsonb_build_object('target_user_id',p_user_id,'assignment_id',v_assignment_id,'expires_at',p_expires_at));
 RETURN jsonb_build_object('success',true,'device_id',v_device_id,'user_id',p_user_id,'assignment_id',v_assignment_id,'status','ACTIVE');
END; $$;

CREATE OR REPLACE FUNCTION public.unassign_device_from_user(p_device_id text, p_user_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO public, private, extensions, pg_temp AS $$
DECLARE v_device_id text:=trim(p_device_id); v_assignment_id bigint; v_actor uuid:=auth.uid();
BEGIN
 IF NOT public.is_admin_or_owner() THEN RAISE EXCEPTION 'FORBIDDEN' USING ERRCODE='42501'; END IF;
 IF v_actor IS NULL OR v_device_id IS NULL OR v_device_id='' OR NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=p_user_id AND role='USER') THEN RAISE EXCEPTION 'INVALID_TARGET' USING ERRCODE='22023'; END IF;
 UPDATE public.user_device_assignments SET status='REVOKED' WHERE user_id=p_user_id AND device_id=v_device_id RETURNING id INTO v_assignment_id;
 IF v_assignment_id IS NOT NULL THEN UPDATE public.device_feature_permissions SET is_allowed=false WHERE assignment_id=v_assignment_id; END IF;
 INSERT INTO public.audit_logs(actor_id,action,resource_type,resource_id,payload) VALUES(v_actor,'DEVICE_UNASSIGNMENT','user_device_assignments',v_device_id,jsonb_build_object('target_user_id',p_user_id,'assignment_id',v_assignment_id));
 RETURN jsonb_build_object('success',true,'device_id',v_device_id,'user_id',p_user_id,'status','REVOKED');
END; $$;

CREATE OR REPLACE FUNCTION public.grant_device_feature_permission(p_user_id uuid,p_device_id text,p_feature text,p_expires_at timestamptz DEFAULT NULL)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO public, private, extensions, pg_temp AS $$
DECLARE v_device_id text:=trim(p_device_id); v_feature text:=upper(trim(p_feature)); v_assignment_id bigint; v_actor uuid:=auth.uid();
BEGIN
 IF NOT public.is_admin_or_owner() THEN RAISE EXCEPTION 'FORBIDDEN' USING ERRCODE='42501'; END IF;
 IF v_actor IS NULL OR v_feature NOT IN ('LOCATION','CAMERA','FILES','MIC','SCREEN','COMMANDS') OR p_expires_at IS NOT NULL AND p_expires_at<=now() THEN RAISE EXCEPTION 'INVALID_INPUT' USING ERRCODE='22023'; END IF;
 IF NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=p_user_id AND role='USER' AND status='ACTIVE') THEN RAISE EXCEPTION 'TARGET_NOT_ACTIVE_USER' USING ERRCODE='42501'; END IF;
 SELECT id INTO v_assignment_id FROM public.user_device_assignments WHERE user_id=p_user_id AND device_id=v_device_id AND status='ACTIVE' AND (expires_at IS NULL OR expires_at>now());
 IF v_assignment_id IS NULL THEN RAISE EXCEPTION 'ACTIVE_ASSIGNMENT_REQUIRED' USING ERRCODE='22023'; END IF;
 INSERT INTO public.device_feature_permissions(assignment_id,feature,is_allowed,expires_at,granted_by,granted_at) VALUES(v_assignment_id,v_feature,true,p_expires_at,v_actor,now())
 ON CONFLICT(assignment_id,feature) DO UPDATE SET is_allowed=true,expires_at=excluded.expires_at,granted_by=v_actor,granted_at=now();
 INSERT INTO public.audit_logs(actor_id,action,resource_type,resource_id,payload) VALUES(v_actor,'FEATURE_PERMISSION_GRANT','device_feature_permissions',v_device_id,jsonb_build_object('target_user_id',p_user_id,'feature',v_feature,'assignment_id',v_assignment_id,'expires_at',p_expires_at));
 RETURN jsonb_build_object('success',true,'user_id',p_user_id,'device_id',v_device_id,'feature',v_feature,'is_allowed',true);
END; $$;

CREATE OR REPLACE FUNCTION public.revoke_device_feature_permission(p_user_id uuid,p_device_id text,p_feature text)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path TO public, private, extensions, pg_temp AS $$
DECLARE v_device_id text:=trim(p_device_id); v_feature text:=upper(trim(p_feature)); v_assignment_id bigint; v_actor uuid:=auth.uid(); v_rows int:=0;
BEGIN
 IF NOT public.is_admin_or_owner() THEN RAISE EXCEPTION 'FORBIDDEN' USING ERRCODE='42501'; END IF;
 IF v_actor IS NULL OR v_feature NOT IN ('LOCATION','CAMERA','FILES','MIC','SCREEN','COMMANDS') OR NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=p_user_id AND role='USER') THEN RAISE EXCEPTION 'INVALID_INPUT' USING ERRCODE='22023'; END IF;
 SELECT id INTO v_assignment_id FROM public.user_device_assignments WHERE user_id=p_user_id AND device_id=v_device_id;
 IF v_assignment_id IS NOT NULL THEN UPDATE public.device_feature_permissions SET is_allowed=false WHERE assignment_id=v_assignment_id AND feature=v_feature; GET DIAGNOSTICS v_rows=ROW_COUNT; END IF;
 INSERT INTO public.audit_logs(actor_id,action,resource_type,resource_id,payload) VALUES(v_actor,'FEATURE_PERMISSION_REVOKE','device_feature_permissions',v_device_id,jsonb_build_object('target_user_id',p_user_id,'feature',v_feature,'assignment_id',v_assignment_id,'rows_affected',v_rows));
 RETURN jsonb_build_object('success',true,'user_id',p_user_id,'device_id',v_device_id,'feature',v_feature,'is_allowed',false);
END; $$;

DROP POLICY IF EXISTS app_signals_select ON public.app_signals;
DROP POLICY IF EXISTS app_signals_insert ON public.app_signals;
DROP POLICY IF EXISTS app_signals_update ON public.app_signals;
DROP POLICY IF EXISTS app_signals_delete ON public.app_signals;
CREATE POLICY app_signals_select ON public.app_signals FOR SELECT TO authenticated USING (public.is_admin_or_owner());
CREATE POLICY app_signals_insert ON public.app_signals FOR INSERT TO authenticated WITH CHECK (public.is_admin_or_owner());
CREATE POLICY app_signals_update ON public.app_signals FOR UPDATE TO authenticated USING (public.is_admin_or_owner()) WITH CHECK (public.is_admin_or_owner());
CREATE POLICY app_signals_delete ON public.app_signals FOR DELETE TO authenticated USING (public.is_admin_or_owner());

REVOKE EXECUTE ON FUNCTION public.is_admin_or_owner(),public.is_owner(),public.has_device_access(text),public.has_feature(text,text),public.has_device_permission(text,text),public.has_command_permission(text,text),public.assign_device_to_user(text,uuid,timestamptz),public.unassign_device_from_user(text,uuid),public.grant_device_feature_permission(uuid,text,text,timestamptz),public.revoke_device_feature_permission(uuid,text,text) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_admin_or_owner(),public.is_owner(),public.has_device_access(text),public.has_feature(text,text),public.has_device_permission(text,text),public.has_command_permission(text,text),public.assign_device_to_user(text,uuid,timestamptz),public.unassign_device_from_user(text,uuid),public.grant_device_feature_permission(uuid,text,text,timestamptz),public.revoke_device_feature_permission(uuid,text,text) TO authenticated, service_role;
COMMIT;
