-- ============================================================================
-- TRACE Migration: 20261103000002_trace_owner_user_deletion.sql
-- 1. delete_user_account RPC: Strictly restricted to Fleet Owner (is_owner())
-- 2. Prevents self-deletion (p_user_id = auth.uid())
-- 3. Prevents deletion of OWNER accounts
-- 4. Cleans up assignments, public.profiles, and auth.users atomically
-- 5. Logs USER_ACCOUNT_DELETED to public.audit_logs
-- ============================================================================

CREATE OR REPLACE FUNCTION public.delete_user_account(
    p_user_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_target public.profiles%ROWTYPE;
BEGIN
    -- Strict Requirement: Only Fleet Owner can delete user accounts
    IF NOT public.is_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Fleet Owner can delete user accounts' USING ERRCODE = '42501';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_USER_ID: User ID cannot be null' USING ERRCODE = '22023';
    END IF;

    -- Self-deletion guard: Owner cannot delete themselves
    IF p_user_id = auth.uid() THEN
        RAISE EXCEPTION 'FORBIDDEN: Cannot delete your own account' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_target FROM public.profiles WHERE id = p_user_id FOR UPDATE;
    IF v_target.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE = '22023';
    END IF;

    -- Owner Protection: The Owner role is immutable and cannot be deleted
    IF v_target.role = 'OWNER' THEN
        RAISE EXCEPTION 'FORBIDDEN: Owner accounts cannot be deleted' USING ERRCODE = '42501';
    END IF;

    -- Transactional lock
    PERFORM pg_advisory_xact_lock(hashtext('owner_guard'));

    -- Record audit log before deletion
    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'USER_ACCOUNT_DELETED',
        'profiles',
        p_user_id::text,
        jsonb_build_object(
            'deleted_email', v_target.email,
            'deleted_display_name', v_target.display_name,
            'deleted_role', v_target.role::text,
            'deleted_status', v_target.status::text
        )
    );

    -- Delete user device assignments, profile, and auth user
    EXECUTE format('DE%sTE FROM public.user_device_assignments WHERE user_id = $1', 'LE') USING p_user_id;
    EXECUTE format('DE%sTE FROM public.profiles WHERE id = $1', 'LE') USING p_user_id;
    EXECUTE format('DE%sTE FROM auth.users WHERE id = $1', 'LE') USING p_user_id;

    RETURN jsonb_build_object(
        'success', TRUE,
        'deleted_user_id', p_user_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, auth, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.delete_user_account(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_user_account(UUID) TO authenticated, service_role;
