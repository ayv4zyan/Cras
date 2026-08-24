-- Migration: Recoverable account deletion (Pending deletion, Recovery window, purge)
-- Implements ADR 0006 and issue #59 server-side contracts.

-- 1. Operator account lifecycle state
CREATE TABLE IF NOT EXISTS public.operator_account_state (
    operator_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    deletion_state TEXT NOT NULL DEFAULT 'active' CHECK (deletion_state IN ('active', 'pending_deletion')),
    deletion_deadline TIMESTAMPTZ,
    recovered_at TIMESTAMPTZ,
    purge_started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT deletion_deadline_required_when_pending CHECK (
        deletion_state = 'active' OR deletion_deadline IS NOT NULL
    )
);

ALTER TABLE public.operator_account_state ENABLE ROW LEVEL SECURITY;

-- No policies: only SECURITY DEFINER functions and the service role reach this table.

CREATE OR REPLACE FUNCTION public.enforce_immutable_deletion_deadline()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    IF OLD.deletion_state = 'pending_deletion'
       AND OLD.deletion_deadline IS NOT NULL
       AND NEW.deletion_deadline IS DISTINCT FROM OLD.deletion_deadline THEN
        RAISE EXCEPTION 'deletion_deadline is immutable while Pending deletion';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_operator_account_state_immutable_deadline ON public.operator_account_state;
CREATE TRIGGER trg_operator_account_state_immutable_deadline
    BEFORE UPDATE ON public.operator_account_state
    FOR EACH ROW
    EXECUTE FUNCTION public.enforce_immutable_deletion_deadline();

-- 2. State helper used by RLS gating policies.
-- Missing row means an active Operator (pre-lifecycle accounts stay unaffected).
CREATE OR REPLACE FUNCTION public.operator_deletion_state(p_operator UUID)
RETURNS TEXT
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT COALESCE(
        (SELECT s.deletion_state FROM public.operator_account_state s WHERE s.operator_id = p_operator),
        'active'
    );
$$;

-- 3. Restrictive database-state gate on every Operator-owned surface.
-- Restrictive policies AND-combine with ownership policies, so a Pending deletion
-- Operator loses Data API access immediately even with an unexpired JWT.

CREATE POLICY "Pending deletion blocks task access"
    ON public.tasks
    AS RESTRICTIVE
    FOR ALL
    TO authenticated
    USING ((select public.operator_deletion_state(operator_id)) = 'active')
    WITH CHECK ((select public.operator_deletion_state(operator_id)) = 'active');

CREATE POLICY "Pending deletion blocks label access"
    ON public.labels
    AS RESTRICTIVE
    FOR ALL
    TO authenticated
    USING ((select public.operator_deletion_state(operator_id)) = 'active')
    WITH CHECK ((select public.operator_deletion_state(operator_id)) = 'active');

CREATE POLICY "Pending deletion blocks comment access"
    ON public.comments
    AS RESTRICTIVE
    FOR ALL
    TO authenticated
    USING ((select public.operator_deletion_state(operator_id)) = 'active')
    WITH CHECK ((select public.operator_deletion_state(operator_id)) = 'active');

CREATE POLICY "Pending deletion blocks settings access"
    ON public.settings
    AS RESTRICTIVE
    FOR ALL
    TO authenticated
    USING ((select public.operator_deletion_state(operator_id)) = 'active')
    WITH CHECK ((select public.operator_deletion_state(operator_id)) = 'active');

CREATE POLICY "Pending deletion blocks task-label access"
    ON public.task_labels
    AS RESTRICTIVE
    FOR ALL
    TO authenticated
    USING ((select public.operator_deletion_state(operator_id)) = 'active')
    WITH CHECK ((select public.operator_deletion_state(operator_id)) = 'active');

CREATE POLICY "Pending deletion blocks installation access"
    ON public.installations
    AS RESTRICTIVE
    FOR ALL
    TO authenticated
    USING ((select public.operator_deletion_state(operator_id)) = 'active')
    WITH CHECK ((select public.operator_deletion_state(operator_id)) = 'active');

CREATE POLICY "Pending deletion blocks notification job access"
    ON public.notification_jobs
    AS RESTRICTIVE
    FOR SELECT
    TO authenticated
    USING ((select public.operator_deletion_state(operator_id)) = 'active');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'realtime' AND tablename = 'messages') THEN
        CREATE POLICY "Pending deletion blocks realtime invalidations"
        ON realtime.messages
        AS RESTRICTIVE
        FOR SELECT
        TO authenticated
        USING ((select public.operator_deletion_state((select auth.uid()))) = 'active');
    END IF;
END;
$$;

-- 4. Narrow status/recovery surface

CREATE OR REPLACE FUNCTION api.get_lifecycle_status()
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_state public.operator_account_state;
BEGIN
    SELECT * INTO v_state
    FROM public.operator_account_state
    WHERE operator_id = (SELECT auth.uid());

    IF NOT FOUND THEN
        RETURN jsonb_build_object(
            'deletion_state', 'active',
            'deletion_deadline', NULL,
            'recovery_available', false
        );
    END IF;

    RETURN jsonb_build_object(
        'deletion_state', v_state.deletion_state,
        'deletion_deadline', v_state.deletion_deadline,
        'recovery_available',
            v_state.deletion_state = 'pending_deletion'
            AND v_state.purge_started_at IS NULL
            AND v_state.deletion_deadline > now()
    );
END;
$$;

REVOKE ALL ON FUNCTION api.get_lifecycle_status() FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.get_lifecycle_status() TO authenticated;

CREATE OR REPLACE FUNCTION api.assert_active_session(p_session_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM auth.sessions s
        WHERE s.id = p_session_id
          AND s.user_id = (SELECT auth.uid())
    );
$$;

REVOKE ALL ON FUNCTION api.assert_active_session(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.assert_active_session(UUID) TO authenticated;

-- 5. Transactional entry into Pending deletion (service-role only).
-- Records the immutable server-calculated 7-day deadline, cancels every pending
-- Notification job, disables every installation. Idempotent: repeating never
-- extends the deadline.

CREATE OR REPLACE FUNCTION api.enter_pending_deletion(p_operator UUID)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_existing public.operator_account_state;
    v_deadline TIMESTAMPTZ;
    v_already_pending BOOLEAN;
BEGIN
    SELECT * INTO v_existing
    FROM public.operator_account_state
    WHERE operator_id = p_operator
    FOR UPDATE;

    v_already_pending := FOUND AND v_existing.deletion_state = 'pending_deletion';

    IF v_already_pending THEN
        v_deadline := v_existing.deletion_deadline;
    ELSE
        -- A fresh deletion episode (including after Recovery) starts a new window.
        v_deadline := now() + interval '7 days';
    END IF;

    INSERT INTO public.operator_account_state (
        operator_id, deletion_state, deletion_deadline
    ) VALUES (
        p_operator, 'pending_deletion', v_deadline
    )
    ON CONFLICT (operator_id) DO UPDATE SET
        deletion_state = 'pending_deletion',
        deletion_deadline = CASE
            WHEN public.operator_account_state.deletion_state = 'pending_deletion'
                THEN public.operator_account_state.deletion_deadline
            ELSE EXCLUDED.deletion_deadline
        END,
        recovered_at = NULL,
        updated_at = now();

    UPDATE public.notification_jobs
    SET state = 'cancelled', updated_at = now()
    WHERE operator_id = p_operator AND state IN ('pending', 'leased');

    UPDATE public.installations
    SET is_active = false, updated_at = now()
    WHERE operator_id = p_operator AND is_active = true;

    RETURN jsonb_build_object(
        'operator_id', p_operator,
        'deletion_deadline', v_deadline,
        'already_pending', v_already_pending
    );
END;
$$;

REVOKE ALL ON FUNCTION api.enter_pending_deletion(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.enter_pending_deletion(UUID) TO service_role;

-- 6. Global session revocation attempt (service-role only).
-- Access JWTs already issued may remain valid until expiry; database-state
-- gating above keeps them powerless over ordinary paths.

CREATE OR REPLACE FUNCTION api.revoke_operator_sessions(p_operator UUID)
RETURNS INTEGER
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_revoked INTEGER;
BEGIN
    DELETE FROM auth.refresh_tokens WHERE user_id = p_operator;
    DELETE FROM auth.sessions WHERE user_id = p_operator;
    GET DIAGNOSTICS v_revoked = ROW_COUNT;
    RETURN v_revoked;
END;
$$;

REVOKE ALL ON FUNCTION api.revoke_operator_sessions(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.revoke_operator_sessions(UUID) TO service_role;

CREATE OR REPLACE FUNCTION api.operator_is_pending_deletion(p_operator UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT COALESCE(
        (
            SELECT s.deletion_state = 'pending_deletion'
            FROM public.operator_account_state s
            WHERE s.operator_id = p_operator
        ),
        false
    );
$$;

REVOKE ALL ON FUNCTION api.operator_is_pending_deletion(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.operator_is_pending_deletion(UUID) TO service_role;

-- 7. Recovery inside the window (service-role only).
-- Locks the same state as purge so only one can win at the boundary.

CREATE OR REPLACE FUNCTION api.recover_account(p_operator UUID)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_state public.operator_account_state;
BEGIN
    SELECT * INTO v_state
    FROM public.operator_account_state
    WHERE operator_id = p_operator
    FOR UPDATE;

    IF NOT FOUND OR v_state.deletion_state <> 'pending_deletion' THEN
        RETURN jsonb_build_object('recovered', false, 'error', 'not_pending');
    END IF;

    IF v_state.purge_started_at IS NOT NULL THEN
        RETURN jsonb_build_object('recovered', false, 'error', 'purge_in_progress');
    END IF;

    IF v_state.deletion_deadline <= now() THEN
        RETURN jsonb_build_object('recovered', false, 'error', 'recovery_window_closed');
    END IF;

    UPDATE public.operator_account_state
    SET deletion_state = 'active',
        recovered_at = now(),
        updated_at = now()
    WHERE operator_id = p_operator;

    RETURN jsonb_build_object('recovered', true);
END;
$$;

REVOKE ALL ON FUNCTION api.recover_account(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.recover_account(UUID) TO service_role;

-- 8. Idempotent purge (service-role only).
-- claim_due_purge_batch marks the work ledger; finalize deletes Storage-free
-- identities so FK cascades remove all Operator-owned rows. Retries treat
-- already-absent resources as success.

CREATE OR REPLACE FUNCTION api.claim_due_purge_batch(
    p_batch INTEGER DEFAULT 20
)
RETURNS TABLE (
    operator_id UUID,
    deletion_deadline TIMESTAMPTZ
)
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    RETURN QUERY
    UPDATE public.operator_account_state s
    SET purge_started_at = now(), updated_at = now()
    WHERE s.operator_id IN (
        SELECT c.operator_id
        FROM public.operator_account_state c
        WHERE c.deletion_state = 'pending_deletion'
          AND c.deletion_deadline <= now()
          AND c.purge_started_at IS NULL
        ORDER BY c.deletion_deadline ASC
        LIMIT GREATEST(COALESCE(p_batch, 20), 1)
        FOR UPDATE SKIP LOCKED
    )
    RETURNING s.operator_id, s.deletion_deadline;
END;
$$;

REVOKE ALL ON FUNCTION api.claim_due_purge_batch(INTEGER) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.claim_due_purge_batch(INTEGER) TO service_role;

CREATE OR REPLACE FUNCTION api.finalize_operator_purge(p_operator UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    PERFORM 1
    FROM public.operator_account_state
    WHERE operator_id = p_operator
    FOR UPDATE;

    IF NOT FOUND THEN
        -- Already purged (the ledger row cascades away with the identity).
        RETURN false;
    END IF;

    DELETE FROM auth.users WHERE id = p_operator;
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION api.finalize_operator_purge(UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.finalize_operator_purge(UUID) TO service_role;

-- 9. Canonical export: one transactionally consistent JSON snapshot.
-- Includes Tasks (with completed Tasks and Subtasks), Labels, Task-Label
-- relationships, Comments, and Settings. Excludes auth/provider internals,
-- recordings, transcripts, and Usage-security records. Not retained anywhere.

CREATE OR REPLACE FUNCTION api.export_operator_data()
RETURNS TEXT
LANGUAGE plpgsql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
DECLARE
    v_snapshot JSONB;
BEGIN
    IF (SELECT public.operator_deletion_state((SELECT auth.uid()))) <> 'active' THEN
        RAISE EXCEPTION 'account_pending_deletion';
    END IF;

    SELECT json_build_object(
        'exportedAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'tasks', COALESCE(
            (SELECT json_agg(task ORDER BY task."createdAt", task.id) FROM api.tasks task),
            '[]'::json
        ),
        'labels', COALESCE(
            (
                SELECT json_agg(
                    json_build_object(
                        'id', l.id,
                        'name', l.name,
                        'color', l.color,
                        'createdAt', l.created_at,
                        'updatedAt', l.updated_at
                    ) ORDER BY l.created_at, l.id
                )
                FROM public.labels l
                WHERE l.operator_id = (SELECT auth.uid())
            ),
            '[]'::json
        ),
        'taskLabels', COALESCE(
            (
                SELECT json_agg(
                    json_build_object(
                        'taskId', tl.task_id,
                        'labelId', tl.label_id
                    )
                )
                FROM public.task_labels tl
                WHERE tl.operator_id = (SELECT auth.uid())
            ),
            '[]'::json
        ),
        'comments', COALESCE(
            (SELECT json_agg(c ORDER BY c."createdAt", c.id) FROM api.comments c),
            '[]'::json
        ),
        'settings', (
            SELECT CASE
                WHEN s.operator_id IS NULL THEN NULL
                ELSE json_build_object(
                    'defaultTimedPlanType', s.default_timed_plan_type,
                    'missedDeliveryEnabled', s.missed_delivery_enabled,
                    'sttModelKey', s.stt_model_key,
                    'extractorModelKey', s.extractor_model_key,
                    'customExtractorPrompt', s.custom_extractor_prompt
                )
            END
            FROM public.settings s
            WHERE s.operator_id = (SELECT auth.uid())
        )
    )
    INTO v_snapshot;

    RETURN v_snapshot::TEXT;
END;
$$;

GRANT EXECUTE ON FUNCTION api.export_operator_data() TO authenticated;

-- 10. Suppressed Notifications are never replayed after recovery:
-- catch-up delivery only applies to occurrences that became due after recovery.

CREATE OR REPLACE FUNCTION public.sync_task_notification_jobs()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_occ_key TEXT;
    v_due_at TIMESTAMPTZ;
    v_inst RECORD;
    v_missed_enabled BOOLEAN := false;
    v_only_future BOOLEAN := false;
    v_recovered_at TIMESTAMPTZ;
BEGIN
    -- Check if task was completed or has no timed plan
    IF NEW.completed_at IS NOT NULL OR NEW.plan IS NULL OR NEW.plan->>'type' IS NULL OR NEW.plan->>'type' NOT IN ('instant', 'floating') THEN
        UPDATE public.notification_jobs
        SET state = 'cancelled', updated_at = now()
        WHERE task_id = NEW.id AND state IN ('pending', 'leased');
        RETURN NEW;
    END IF;

    -- Open timed task
    v_occ_key := public.calculate_occurrence_key(NEW.plan);
    IF v_occ_key IS NULL THEN
        UPDATE public.notification_jobs
        SET state = 'cancelled', updated_at = now()
        WHERE task_id = NEW.id AND state IN ('pending', 'leased');
        RETURN NEW;
    END IF;

    -- Cancel obsolete jobs for this task
    UPDATE public.notification_jobs
    SET state = 'cancelled', updated_at = now()
    WHERE task_id = NEW.id
      AND state IN ('pending', 'leased')
      AND (plan_version <> NEW.version OR occurrence_key <> v_occ_key);

    -- Check if task was uncompleted on UPDATE
    IF TG_OP = 'UPDATE' THEN
        IF OLD.completed_at IS NOT NULL AND NEW.completed_at IS NULL THEN
            v_only_future := true;
        END IF;
    END IF;

    -- Get Operator missed delivery setting and last recovery moment
    SELECT COALESCE(missed_delivery_enabled, false) INTO v_missed_enabled
    FROM public.settings
    WHERE operator_id = NEW.operator_id;

    SELECT recovered_at INTO v_recovered_at
    FROM public.operator_account_state
    WHERE operator_id = NEW.operator_id;

    -- Schedule job for every eligible installation
    FOR v_inst IN
        SELECT id, installation_timezone
        FROM public.installations
        WHERE operator_id = NEW.operator_id
          AND is_active = true
          AND local_enabled = true
          AND permission_state = 'granted'
          AND endpoint IS NOT NULL
    LOOP
        v_due_at := public.calculate_interpreted_due_at(NEW.plan, v_inst.installation_timezone);

        IF v_due_at IS NOT NULL THEN
            IF v_due_at > now()
               OR (
                   NOT v_only_future
                   AND v_missed_enabled
                   AND now() <= v_due_at + interval '1 hour'
                   AND (v_recovered_at IS NULL OR v_due_at > v_recovered_at)
               ) THEN
                INSERT INTO public.notification_jobs (
                    operator_id, task_id, installation_id, plan_version,
                    interpreted_due_at, occurrence_key, state
                ) VALUES (
                    NEW.operator_id, NEW.id, v_inst.id, NEW.version,
                    v_due_at, v_occ_key, 'pending'
                )
                ON CONFLICT (installation_id, task_id, occurrence_key)
                DO UPDATE SET
                    plan_version = EXCLUDED.plan_version,
                    interpreted_due_at = EXCLUDED.interpreted_due_at,
                    state = 'pending',
                    attempt_count = 0,
                    next_attempt_at = NULL,
                    updated_at = now()
                WHERE public.notification_jobs.state <> 'delivered';
            END IF;
        END IF;
    END LOOP;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_tasks_sync_notification_jobs ON public.tasks;
CREATE TRIGGER trg_tasks_sync_notification_jobs
    AFTER INSERT OR UPDATE ON public.tasks
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_task_notification_jobs();

CREATE OR REPLACE FUNCTION public.sync_installation_notification_jobs()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_task RECORD;
    v_due_at TIMESTAMPTZ;
    v_occ_key TEXT;
    v_missed_enabled BOOLEAN := false;
    v_should_sync BOOLEAN := false;
    v_recovered_at TIMESTAMPTZ;
BEGIN
    -- If installation became ineligible or inactive, cancel its pending/leased jobs
    IF NEW.is_active = false OR NEW.local_enabled = false OR NEW.permission_state <> 'granted' OR NEW.endpoint IS NULL THEN
        UPDATE public.notification_jobs
        SET state = 'cancelled', updated_at = now()
        WHERE installation_id = NEW.id AND state IN ('pending', 'leased');
        RETURN NEW;
    END IF;

    -- Check eligibility trigger conditions without evaluating OLD on INSERT
    IF TG_OP = 'INSERT' THEN
        v_should_sync := true;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.is_active = false
           OR OLD.local_enabled = false
           OR OLD.permission_state <> 'granted'
           OR OLD.endpoint IS NULL
           OR OLD.installation_timezone IS DISTINCT FROM NEW.installation_timezone
           OR OLD.endpoint IS DISTINCT FROM NEW.endpoint THEN
            v_should_sync := true;
        END IF;
    END IF;

    IF v_should_sync THEN
        SELECT COALESCE(missed_delivery_enabled, false) INTO v_missed_enabled
        FROM public.settings
        WHERE operator_id = NEW.operator_id;

        SELECT recovered_at INTO v_recovered_at
        FROM public.operator_account_state
        WHERE operator_id = NEW.operator_id;

        FOR v_task IN
            SELECT id, plan, version
            FROM public.tasks
            WHERE operator_id = NEW.operator_id
              AND completed_at IS NULL
              AND plan IS NOT NULL
              AND plan->>'type' IN ('instant', 'floating')
        LOOP
            v_due_at := public.calculate_interpreted_due_at(v_task.plan, NEW.installation_timezone);
            v_occ_key := public.calculate_occurrence_key(v_task.plan);

            IF v_due_at IS NOT NULL AND v_occ_key IS NOT NULL THEN
                IF v_due_at > now()
                   OR (
                       v_missed_enabled
                       AND now() <= v_due_at + interval '1 hour'
                       AND (v_recovered_at IS NULL OR v_due_at > v_recovered_at)
                   ) THEN
                    INSERT INTO public.notification_jobs (
                        operator_id, task_id, installation_id, plan_version,
                        interpreted_due_at, occurrence_key, state
                    ) VALUES (
                        NEW.operator_id, v_task.id, NEW.id, v_task.version,
                        v_due_at, v_occ_key, 'pending'
                    )
                    ON CONFLICT (installation_id, task_id, occurrence_key)
                    DO UPDATE SET
                        plan_version = EXCLUDED.plan_version,
                        interpreted_due_at = EXCLUDED.interpreted_due_at,
                        state = 'pending',
                        attempt_count = 0,
                        next_attempt_at = NULL,
                        updated_at = now()
                    WHERE public.notification_jobs.state <> 'delivered';
                END IF;
            END IF;
        END LOOP;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_installations_sync_notification_jobs ON public.installations;
CREATE TRIGGER trg_installations_sync_notification_jobs
    AFTER INSERT OR UPDATE ON public.installations
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_installation_notification_jobs();
