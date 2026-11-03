-- ============================================================================
-- TRACE Phase 6 — Database Migration: Realtime Revocation & Permissions Broadcast
-- Target Schema: public.profiles, public.user_device_assignments,
--                public.device_feature_permissions, public.revocation_events,
--                publication: supabase_realtime
-- Features:
-- 1. Configure REPLICA IDENTITY FULL on core identity & assignment relations
-- 2. Idempotently add profiles, user_device_assignments, and device_feature_permissions
--    to supabase_realtime publication
-- 3. Dedicated public.revocation_events ledger for single-channel client push & catchup
-- 4. Automatic triggers on status/role/assignment/feature changes to emit revocation events
-- 5. RPC public.broadcast_revocation_event for immediate administrative force-logout / revocation
-- 6. RPC public.fetch_user_revocations for offline reconnection catchup
-- ============================================================================

-- 1. Configure REPLICA IDENTITY FULL
-- Guarantees complete old-row values in WAL for CDC subscribers and RLS filtering
ALTER TABLE public.profiles REPLICA IDENTITY FULL;
ALTER TABLE public.user_device_assignments REPLICA IDENTITY FULL;
ALTER TABLE public.device_feature_permissions REPLICA IDENTITY FULL;


-- 2. Publication Management: Idempotently add tables to supabase_realtime
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
          AND schemaname = 'public' 
          AND tablename = 'profiles'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.profiles;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
          AND schemaname = 'public' 
          AND tablename = 'user_device_assignments'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.user_device_assignments;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
          AND schemaname = 'public' 
          AND tablename = 'device_feature_permissions'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.device_feature_permissions;
    END IF;
END $$;


-- 3. Dedicated Revocation Events Ledger (public.revocation_events)
CREATE TABLE IF NOT EXISTS public.revocation_events (
    id BIGSERIAL PRIMARY KEY,
    target_user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL CHECK (
        event_type IN (
            'ACCOUNT_STATUS_CHANGED',
            'ROLE_CHANGED',
            'DEVICE_UNASSIGNED',
            'FEATURE_REVOKED',
            'FORCE_LOGOUT'
        )
    ),
    device_id TEXT NULL REFERENCES public.devices(device_id) ON DELETE SET NULL,
    feature TEXT NULL CHECK (
        feature IS NULL OR 
        feature IN ('LOCATION', 'CAMERA', 'FILES', 'MIC', 'SCREEN', 'COMMANDS')
    ),
    reason TEXT NULL,
    revoked_by UUID NULL REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_revocation_events_target 
    ON public.revocation_events(target_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_revocation_events_device 
    ON public.revocation_events(device_id) 
    WHERE device_id IS NOT NULL;

ALTER TABLE public.revocation_events REPLICA IDENTITY FULL;

-- Add revocation_events to supabase_realtime publication
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
          AND schemaname = 'public' 
          AND tablename = 'revocation_events'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.revocation_events;
    END IF;
END $$;


-- 4. Row Level Security for public.revocation_events
ALTER TABLE public.revocation_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "revocation_events_select_policy" ON public.revocation_events;
CREATE POLICY "revocation_events_select_policy" ON public.revocation_events
    FOR SELECT TO authenticated
    USING (
        public.is_admin_or_owner() OR target_user_id = auth.uid()
    );

-- Client mutation lockdown: Events can ONLY be inserted via Security Definer triggers/RPCs
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE public.revocation_events FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.revocation_events TO authenticated, service_role;


-- 5. Trigger Functions: Automatic Revocation Event Generation

-- 5A: Profiles Trigger (Account Status or Role Revocation)
CREATE OR REPLACE FUNCTION public.handle_profile_revocation_trigger()
RETURNS TRIGGER AS $$
DECLARE
    v_actor UUID;
BEGIN
    v_actor := auth.uid();

    -- Detect account suspension or disabling
    IF (OLD.status IS DISTINCT FROM NEW.status) AND (NEW.status IN ('SUSPENDED', 'DISABLED')) THEN
        INSERT INTO public.revocation_events (
            target_user_id,
            event_type,
            reason,
            revoked_by,
            created_at
        ) VALUES (
            NEW.id,
            'ACCOUNT_STATUS_CHANGED',
            'Account status transitioned from ' || OLD.status::TEXT || ' to ' || NEW.status::TEXT,
            v_actor,
            NOW()
        );

        -- Optional Postgres LISTEN/NOTIFY for backend listeners
        PERFORM pg_notify(
            'trace_realtime_revocation',
            jsonb_build_object(
                'event_type', 'ACCOUNT_STATUS_CHANGED',
                'target_user_id', NEW.id,
                'status', NEW.status::TEXT
            )::text
        );
    END IF;

    -- Detect role demotion to VISITOR
    IF (OLD.role IS DISTINCT FROM NEW.role) AND (NEW.role = 'VISITOR') THEN
        INSERT INTO public.revocation_events (
            target_user_id,
            event_type,
            reason,
            revoked_by,
            created_at
        ) VALUES (
            NEW.id,
            'ROLE_CHANGED',
            'Account role demoted from ' || OLD.role::TEXT || ' to VISITOR',
            v_actor,
            NOW()
        );

        PERFORM pg_notify(
            'trace_realtime_revocation',
            jsonb_build_object(
                'event_type', 'ROLE_CHANGED',
                'target_user_id', NEW.id,
                'role', NEW.role::TEXT
            )::text
        );
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

DROP TRIGGER IF EXISTS trg_profile_revocation ON public.profiles;
CREATE TRIGGER trg_profile_revocation
    AFTER UPDATE OF status, role ON public.profiles
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_profile_revocation_trigger();


-- 5B: User Device Assignments Trigger (Assignment Revocation)
CREATE OR REPLACE FUNCTION public.handle_uda_revocation_trigger()
RETURNS TRIGGER AS $$
DECLARE
    v_actor UUID;
BEGIN
    v_actor := auth.uid();

    -- Detect transition to REVOKED or SUSPENDED
    IF (OLD.status IS DISTINCT FROM NEW.status) AND (NEW.status IN ('REVOKED', 'SUSPENDED')) THEN
        INSERT INTO public.revocation_events (
            target_user_id,
            event_type,
            device_id,
            reason,
            revoked_by,
            created_at
        ) VALUES (
            NEW.user_id,
            'DEVICE_UNASSIGNED',
            NEW.device_id,
            'Device assignment status changed to ' || NEW.status,
            v_actor,
            NOW()
        );

        PERFORM pg_notify(
            'trace_realtime_revocation',
            jsonb_build_object(
                'event_type', 'DEVICE_UNASSIGNED',
                'target_user_id', NEW.user_id,
                'device_id', NEW.device_id,
                'status', NEW.status
            )::text
        );
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

DROP TRIGGER IF EXISTS trg_uda_revocation ON public.user_device_assignments;
CREATE TRIGGER trg_uda_revocation
    AFTER UPDATE OF status ON public.user_device_assignments
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_uda_revocation_trigger();


-- 5C: Device Feature Permissions Trigger (Feature Revocation)
CREATE OR REPLACE FUNCTION public.handle_dfp_revocation_trigger()
RETURNS TRIGGER AS $$
DECLARE
    v_actor UUID;
    v_target_user_id UUID;
    v_device_id TEXT;
BEGIN
    v_actor := auth.uid();

    -- Detect transition from allowed (TRUE) to disallowed (FALSE)
    IF (OLD.is_allowed = TRUE) AND (NEW.is_allowed = FALSE) THEN
        -- Resolve target user_id and device_id from assignment
        SELECT uda.user_id, uda.device_id INTO v_target_user_id, v_device_id
        FROM public.user_device_assignments uda
        WHERE uda.id = NEW.assignment_id;

        IF v_target_user_id IS NOT NULL THEN
            INSERT INTO public.revocation_events (
                target_user_id,
                event_type,
                device_id,
                feature,
                reason,
                revoked_by,
                created_at
            ) VALUES (
                v_target_user_id,
                'FEATURE_REVOKED',
                v_device_id,
                NEW.feature,
                'Feature permission revoked: ' || NEW.feature,
                v_actor,
                NOW()
            );

            PERFORM pg_notify(
                'trace_realtime_revocation',
                jsonb_build_object(
                    'event_type', 'FEATURE_REVOKED',
                    'target_user_id', v_target_user_id,
                    'device_id', v_device_id,
                    'feature', NEW.feature
                )::text
            );
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

DROP TRIGGER IF EXISTS trg_dfp_revocation ON public.device_feature_permissions;
CREATE TRIGGER trg_dfp_revocation
    AFTER UPDATE OF is_allowed ON public.device_feature_permissions
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_dfp_revocation_trigger();


-- 6. RPC: public.broadcast_revocation_event
-- Explicit administrative revocation broadcast (e.g. emergency force-logout, instant push)
-- Authority: is_admin_or_owner()
-- Owner Protection: Admin cannot broadcast revocation targeting Owner.
CREATE OR REPLACE FUNCTION public.broadcast_revocation_event(
    p_target_user_id UUID,
    p_event_type TEXT,
    p_device_id TEXT DEFAULT NULL,
    p_feature TEXT DEFAULT NULL,
    p_reason TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_clean_event_type TEXT;
    v_clean_device_id TEXT;
    v_clean_feature TEXT;
    v_target public.profiles%ROWTYPE;
    v_event_id BIGINT;
BEGIN
    IF NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Only Admins and Owners can broadcast revocation events' USING ERRCODE = '42501';
    END IF;

    IF p_target_user_id IS NULL THEN
        RAISE EXCEPTION 'INVALID_USER_ID: Target user ID cannot be null' USING ERRCODE = '22023';
    END IF;

    v_clean_event_type := UPPER(TRIM(p_event_type));
    IF v_clean_event_type NOT IN ('ACCOUNT_STATUS_CHANGED', 'ROLE_CHANGED', 'DEVICE_UNASSIGNED', 'FEATURE_REVOKED', 'FORCE_LOGOUT') THEN
        RAISE EXCEPTION 'INVALID_EVENT_TYPE: % is not a valid revocation event type', p_event_type USING ERRCODE = '22023';
    END IF;

    v_clean_device_id := NULLIF(TRIM(p_device_id), '');
    v_clean_feature := NULLIF(UPPER(TRIM(p_feature)), '');

    IF v_clean_feature IS NOT NULL AND v_clean_feature NOT IN ('LOCATION', 'CAMERA', 'FILES', 'MIC', 'SCREEN', 'COMMANDS') THEN
        RAISE EXCEPTION 'INVALID_FEATURE: % is not a recognized feature', p_feature USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_target FROM public.profiles WHERE id = p_target_user_id;
    IF v_target.id IS NULL THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: Target profile does not exist' USING ERRCODE = '22023';
    END IF;

    -- Owner Protection: Admin cannot broadcast revocation against the Owner
    IF v_target.role = 'OWNER' AND NOT public.is_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: Admins cannot broadcast revocation events targeting the Owner' USING ERRCODE = '42501';
    END IF;

    -- Insert into public.revocation_events
    INSERT INTO public.revocation_events (
        target_user_id,
        event_type,
        device_id,
        feature,
        reason,
        revoked_by,
        created_at
    ) VALUES (
        p_target_user_id,
        v_clean_event_type,
        v_clean_device_id,
        v_clean_feature,
        COALESCE(p_reason, 'Administrative broadcast: ' || v_clean_event_type),
        auth.uid(),
        NOW()
    )
    RETURNING id INTO v_event_id;

    -- Emit Postgres LISTEN/NOTIFY
    PERFORM pg_notify(
        'trace_realtime_revocation',
        jsonb_build_object(
            'event_id', v_event_id,
            'event_type', v_clean_event_type,
            'target_user_id', p_target_user_id,
            'device_id', v_clean_device_id,
            'feature', v_clean_feature,
            'reason', p_reason
        )::text
    );

    -- Log to immutable audit_logs
    INSERT INTO public.audit_logs (actor_id, action, resource_type, resource_id, payload)
    VALUES (
        auth.uid(),
        'REVOCATION_BROADCAST',
        'revocation_events',
        v_event_id::TEXT,
        jsonb_build_object(
            'target_user_id', p_target_user_id,
            'event_type', v_clean_event_type,
            'device_id', v_clean_device_id,
            'feature', v_clean_feature,
            'reason', p_reason
        )
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'event_id', v_event_id,
        'event_type', v_clean_event_type,
        'target_user_id', p_target_user_id,
        'device_id', v_clean_device_id,
        'feature', v_clean_feature,
        'created_at', NOW()
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.broadcast_revocation_event(UUID, TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.broadcast_revocation_event(UUID, TEXT, TEXT, TEXT, TEXT) TO authenticated, service_role;


-- 7. RPC: public.fetch_user_revocations
-- Reconnection & offline catchup query for mobile clients
-- Allows target user to query their own events since a specific timestamp;
-- Admins/Owner can query for any user.
CREATE OR REPLACE FUNCTION public.fetch_user_revocations(
    p_since TIMESTAMPTZ DEFAULT NULL,
    p_target_user_id UUID DEFAULT NULL,
    p_limit INT DEFAULT 50
)
RETURNS TABLE (
    event_id BIGINT,
    target_user_id UUID,
    event_type TEXT,
    device_id TEXT,
    feature TEXT,
    reason TEXT,
    created_at TIMESTAMPTZ
) AS $$
DECLARE
    v_user_to_query UUID;
    v_clean_limit INT;
BEGIN
    v_user_to_query := COALESCE(p_target_user_id, auth.uid());
    IF v_user_to_query IS NULL THEN
        RAISE EXCEPTION 'UNAUTHENTICATED: Caller must be authenticated or specify user_id' USING ERRCODE = '28000';
    END IF;

    -- Cross-inspection boundary
    IF v_user_to_query != auth.uid() AND NOT public.is_admin_or_owner() THEN
        RAISE EXCEPTION 'FORBIDDEN: You do not have permission to inspect other users revocation events' USING ERRCODE = '42501';
    END IF;

    v_clean_limit := LEAST(GREATEST(COALESCE(p_limit, 50), 1), 200);

    RETURN QUERY
    SELECT
        re.id AS event_id,
        re.target_user_id,
        re.event_type,
        re.device_id,
        re.feature,
        re.reason,
        re.created_at
    FROM public.revocation_events re
    WHERE re.target_user_id = v_user_to_query
      AND (p_since IS NULL OR re.created_at > p_since)
    ORDER BY re.created_at DESC
    LIMIT v_clean_limit;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, private, extensions, pg_temp;

REVOKE ALL ON FUNCTION public.fetch_user_revocations(TIMESTAMPTZ, UUID, INT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.fetch_user_revocations(TIMESTAMPTZ, UUID, INT) TO authenticated, service_role;
