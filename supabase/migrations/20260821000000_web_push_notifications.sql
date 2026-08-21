-- Migration: Server-authoritative Web Push Notifications and Installations

-- 1. Installations Table
CREATE TABLE IF NOT EXISTS public.installations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    platform TEXT NOT NULL CHECK (platform IN ('web', 'android')),
    local_enabled BOOLEAN NOT NULL DEFAULT true,
    permission_state TEXT NOT NULL DEFAULT 'prompt' CHECK (permission_state IN ('granted', 'denied', 'prompt', 'default')),
    endpoint TEXT,
    p256dh TEXT,
    auth TEXT,
    installation_timezone TEXT NOT NULL DEFAULT 'UTC',
    timezone_observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_installations_id_operator UNIQUE (id, operator_id),
    CONSTRAINT uq_installations_endpoint_operator UNIQUE (operator_id, endpoint)
);

CREATE INDEX IF NOT EXISTS idx_installations_operator ON public.installations (operator_id);
CREATE INDEX IF NOT EXISTS idx_installations_eligible ON public.installations (operator_id, is_active, local_enabled, permission_state);

ALTER TABLE public.installations ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Operators manage their own installations"
    ON public.installations
    FOR ALL
    TO authenticated
    USING (operator_id = (select auth.uid()))
    WITH CHECK (operator_id = (select auth.uid()));

-- 2. Notification Jobs Table
CREATE TABLE IF NOT EXISTS public.notification_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    task_id UUID NOT NULL,
    installation_id UUID NOT NULL,
    plan_version INTEGER NOT NULL,
    interpreted_due_at TIMESTAMPTZ NOT NULL,
    occurrence_key TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'leased', 'delivered', 'failed', 'cancelled', 'expired')),
    leased_at TIMESTAMPTZ,
    lease_token UUID,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_jobs_id_operator UNIQUE (id, operator_id),
    CONSTRAINT uq_notification_jobs_target UNIQUE (installation_id, task_id, occurrence_key),
    CONSTRAINT fk_notification_jobs_task FOREIGN KEY (task_id, operator_id)
        REFERENCES public.tasks(id, operator_id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_jobs_installation FOREIGN KEY (installation_id, operator_id)
        REFERENCES public.installations(id, operator_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_notification_jobs_due ON public.notification_jobs (state, interpreted_due_at, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_notification_jobs_task ON public.notification_jobs (task_id, operator_id);
CREATE INDEX IF NOT EXISTS idx_notification_jobs_installation ON public.notification_jobs (installation_id, operator_id);

ALTER TABLE public.notification_jobs ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Operators view their own notification jobs"
    ON public.notification_jobs
    FOR SELECT
    TO authenticated
    USING (operator_id = (select auth.uid()));

-- 3. Helper: Calculate Interpreted Due Moment
CREATE OR REPLACE FUNCTION public.calculate_interpreted_due_at(
    plan JSONB,
    tz TEXT
)
RETURNS TIMESTAMPTZ
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_type TEXT;
    v_date TEXT;
    v_time TEXT;
    v_at TEXT;
    v_tz TEXT;
    v_result TIMESTAMPTZ;
BEGIN
    IF plan IS NULL THEN
        RETURN NULL;
    END IF;

    v_type := plan->>'type';
    IF v_type IS NULL THEN
        RETURN NULL;
    END IF;

    IF v_type = 'instant' THEN
        v_at := plan->>'at';
        IF v_at IS NULL THEN
            RETURN NULL;
        END IF;
        RETURN v_at::TIMESTAMPTZ;
    ELSIF v_type = 'floating' THEN
        v_date := plan->>'date';
        v_time := plan->>'time';
        IF v_date IS NULL OR v_time IS NULL THEN
            RETURN NULL;
        END IF;
        v_tz := COALESCE(NULLIF(trim(tz), ''), 'UTC');
        BEGIN
            v_result := (v_date || ' ' || v_time)::timestamp AT TIME ZONE v_tz;
            RETURN v_result;
        EXCEPTION WHEN OTHERS THEN
            RETURN (v_date || ' ' || v_time)::timestamp AT TIME ZONE 'UTC';
        END;
    END IF;

    RETURN NULL;
END;
$$;

-- 4. Helper: Calculate Occurrence Key
CREATE OR REPLACE FUNCTION public.calculate_occurrence_key(plan JSONB)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF plan IS NULL OR plan->>'type' IS NULL THEN
        RETURN NULL;
    END IF;
    IF plan->>'type' = 'instant' THEN
        RETURN 'instant:' || (plan->>'at');
    ELSIF plan->>'type' = 'floating' THEN
        RETURN 'floating:' || (plan->>'date') || 'T' || (plan->>'time');
    END IF;
    RETURN NULL;
END;
$$;

-- 5. Trigger Function: Synchronize Notification Jobs on Task Changes
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

    -- Check if task was uncompleted
    IF TG_OP = 'UPDATE' AND OLD.completed_at IS NOT NULL AND NEW.completed_at IS NULL THEN
        v_only_future := true;
    END IF;

    -- Get Operator missed delivery setting
    SELECT COALESCE(missed_delivery_enabled, false) INTO v_missed_enabled
    FROM public.settings
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
            IF v_only_future THEN
                IF v_due_at > now() THEN
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
            ELSE
                IF v_due_at > now() OR (v_missed_enabled AND now() <= v_due_at + interval '1 hour') THEN
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

-- 6. Trigger Function: Synchronize Notification Jobs on Installation Changes
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
BEGIN
    -- If installation became ineligible or inactive, cancel its pending/leased jobs
    IF NEW.is_active = false OR NEW.local_enabled = false OR NEW.permission_state <> 'granted' OR NEW.endpoint IS NULL THEN
        UPDATE public.notification_jobs
        SET state = 'cancelled', updated_at = now()
        WHERE installation_id = NEW.id AND state IN ('pending', 'leased');
        RETURN NEW;
    END IF;

    -- If installation became eligible or its timezone/endpoint changed, recalculate
    IF TG_OP = 'INSERT'
       OR OLD.is_active = false
       OR OLD.local_enabled = false
       OR OLD.permission_state <> 'granted'
       OR OLD.endpoint IS NULL
       OR OLD.installation_timezone <> NEW.installation_timezone
       OR OLD.endpoint <> NEW.endpoint THEN

        SELECT COALESCE(missed_delivery_enabled, false) INTO v_missed_enabled
        FROM public.settings
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
                IF v_due_at > now() OR (v_missed_enabled AND now() <= v_due_at + interval '1 hour') THEN
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

-- 7. Client RPCs for Installation Management
CREATE OR REPLACE FUNCTION api.register_or_update_installation(
    id UUID,
    platform TEXT,
    local_enabled BOOLEAN DEFAULT true,
    permission_state TEXT DEFAULT 'prompt',
    endpoint TEXT DEFAULT NULL,
    p256dh TEXT DEFAULT NULL,
    auth TEXT DEFAULT NULL,
    installation_timezone TEXT DEFAULT 'UTC'
)
RETURNS public.installations
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
DECLARE
    v_inst public.installations;
    v_inst_id UUID;
BEGIN
    IF register_or_update_installation.platform NOT IN ('web', 'android') THEN
        RAISE EXCEPTION 'Invalid platform: %', register_or_update_installation.platform;
    END IF;

    v_inst_id := COALESCE(register_or_update_installation.id, gen_random_uuid());

    INSERT INTO public.installations (
        id,
        operator_id,
        platform,
        local_enabled,
        permission_state,
        endpoint,
        p256dh,
        auth,
        installation_timezone,
        timezone_observed_at,
        is_active,
        updated_at
    ) VALUES (
        v_inst_id,
        auth.uid(),
        register_or_update_installation.platform,
        COALESCE(register_or_update_installation.local_enabled, true),
        COALESCE(register_or_update_installation.permission_state, 'prompt'),
        register_or_update_installation.endpoint,
        register_or_update_installation.p256dh,
        register_or_update_installation.auth,
        COALESCE(NULLIF(trim(register_or_update_installation.installation_timezone), ''), 'UTC'),
        now(),
        true,
        now()
    )
    ON CONFLICT (id, operator_id) DO UPDATE SET
        platform = EXCLUDED.platform,
        local_enabled = EXCLUDED.local_enabled,
        permission_state = EXCLUDED.permission_state,
        endpoint = EXCLUDED.endpoint,
        p256dh = EXCLUDED.p256dh,
        auth = EXCLUDED.auth,
        installation_timezone = EXCLUDED.installation_timezone,
        timezone_observed_at = now(),
        is_active = true,
        updated_at = now()
    RETURNING * INTO v_inst;

    RETURN v_inst;
END;
$$;

GRANT EXECUTE ON FUNCTION api.register_or_update_installation(UUID, TEXT, BOOLEAN, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION api.deactivate_installation(
    id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
BEGIN
    UPDATE public.installations
    SET is_active = false, updated_at = now()
    WHERE public.installations.id = deactivate_installation.id
      AND public.installations.operator_id = auth.uid();

    RETURN FOUND;
END;
$$;

GRANT EXECUTE ON FUNCTION api.deactivate_installation(UUID) TO authenticated;

-- 8. Internal Leasing and Result Recording Functions for Worker / Edge Function
CREATE OR REPLACE FUNCTION api.lease_due_notification_jobs(
    batch_size INTEGER DEFAULT 50,
    lease_seconds INTEGER DEFAULT 60
)
RETURNS TABLE (
    job_id UUID,
    lease_token UUID,
    task_id UUID,
    task_title TEXT,
    task_version INTEGER,
    task_completed_at TIMESTAMPTZ,
    operator_id UUID,
    occurrence_key TEXT,
    interpreted_due_at TIMESTAMPTZ,
    missed_delivery_enabled BOOLEAN,
    platform TEXT,
    endpoint TEXT,
    p256dh TEXT,
    auth TEXT,
    is_active BOOLEAN,
    local_enabled BOOLEAN,
    permission_state TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_token UUID := gen_random_uuid();
BEGIN
    -- 1. Expire jobs that exceeded deadline before leasing
    -- Missed delivery disabled: expire after 2 minutes grace
    UPDATE public.notification_jobs j
    SET state = 'expired', updated_at = now()
    FROM public.settings s
    WHERE j.operator_id = s.operator_id
      AND s.missed_delivery_enabled = false
      AND j.state IN ('pending', 'failed')
      AND now() > j.interpreted_due_at + interval '2 minutes';

    -- Missed delivery enabled: expire after 1 hour deadline
    UPDATE public.notification_jobs j
    SET state = 'expired', updated_at = now()
    FROM public.settings s
    WHERE j.operator_id = s.operator_id
      AND s.missed_delivery_enabled = true
      AND j.state IN ('pending', 'failed')
      AND now() > j.interpreted_due_at + interval '1 hour';

    -- Also expire jobs where settings record is missing (default missed_delivery_enabled = false)
    UPDATE public.notification_jobs j
    SET state = 'expired', updated_at = now()
    WHERE j.state IN ('pending', 'failed')
      AND NOT EXISTS (SELECT 1 FROM public.settings s WHERE s.operator_id = j.operator_id)
      AND now() > j.interpreted_due_at + interval '2 minutes';

    -- 2. Claim due jobs with SKIP LOCKED
    RETURN QUERY
    WITH due_jobs AS (
        SELECT j.id
        FROM public.notification_jobs j
        WHERE j.state IN ('pending', 'failed')
          AND (j.next_attempt_at IS NULL OR j.next_attempt_at <= now())
          AND j.interpreted_due_at <= now()
        ORDER BY j.interpreted_due_at ASC
        LIMIT batch_size
        FOR UPDATE SKIP LOCKED
    ),
    leased AS (
        UPDATE public.notification_jobs j
        SET state = 'leased',
            leased_at = now(),
            lease_token = v_token,
            attempt_count = j.attempt_count + 1,
            updated_at = now()
        FROM due_jobs
        WHERE j.id = due_jobs.id
        RETURNING j.*
    )
    SELECT
        l.id AS job_id,
        l.lease_token,
        t.id AS task_id,
        t.title AS task_title,
        t.version AS task_version,
        t.completed_at AS task_completed_at,
        l.operator_id,
        l.occurrence_key,
        l.interpreted_due_at,
        COALESCE(s.missed_delivery_enabled, false) AS missed_delivery_enabled,
        i.platform,
        i.endpoint,
        i.p256dh,
        i.auth,
        i.is_active,
        i.local_enabled,
        i.permission_state
    FROM leased l
    JOIN public.tasks t ON t.id = l.task_id AND t.operator_id = l.operator_id
    JOIN public.installations i ON i.id = l.installation_id AND i.operator_id = l.operator_id
    LEFT JOIN public.settings s ON s.operator_id = l.operator_id;
END;
$$;

GRANT EXECUTE ON FUNCTION api.lease_due_notification_jobs(INTEGER, INTEGER) TO authenticated;

CREATE OR REPLACE FUNCTION api.record_notification_result(
    p_job_id UUID,
    p_lease_token UUID,
    p_result TEXT,
    p_status_code INTEGER DEFAULT NULL
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_job public.notification_jobs%ROWTYPE;
    v_missed_enabled BOOLEAN := false;
    v_deadline TIMESTAMPTZ;
BEGIN
    SELECT * INTO v_job
    FROM public.notification_jobs
    WHERE id = p_job_id AND lease_token = p_lease_token;

    IF v_job.id IS NULL THEN
        RETURN false;
    END IF;

    IF p_result = 'delivered' THEN
        UPDATE public.notification_jobs
        SET state = 'delivered', updated_at = now()
        WHERE id = p_job_id;
        RETURN true;
    ELSIF p_result = 'permanent_failure' THEN
        UPDATE public.notification_jobs
        SET state = 'failed', updated_at = now()
        WHERE id = p_job_id;

        -- Permanent rejection disables endpoint and cancels jobs
        UPDATE public.installations
        SET is_active = false, endpoint = NULL, updated_at = now()
        WHERE id = v_job.installation_id;

        UPDATE public.notification_jobs
        SET state = 'cancelled', updated_at = now()
        WHERE installation_id = v_job.installation_id AND state IN ('pending', 'leased');

        RETURN true;
    ELSIF p_result = 'cancelled' OR p_result = 'expired' THEN
        UPDATE public.notification_jobs
        SET state = p_result, updated_at = now()
        WHERE id = p_job_id;
        RETURN true;
    ELSE
        -- Transient failure: check deadline
        SELECT COALESCE(missed_delivery_enabled, false) INTO v_missed_enabled
        FROM public.settings
        WHERE operator_id = v_job.operator_id;

        IF v_missed_enabled THEN
            v_deadline := v_job.interpreted_due_at + interval '1 hour';
        ELSE
            v_deadline := v_job.interpreted_due_at + interval '2 minutes';
        END IF;

        IF now() > v_deadline THEN
            UPDATE public.notification_jobs
            SET state = 'expired', updated_at = now()
            WHERE id = p_job_id;
        ELSE
            UPDATE public.notification_jobs
            SET state = 'failed',
                next_attempt_at = now() + (interval '10 seconds' * power(2, LEAST(v_job.attempt_count, 6))),
                updated_at = now()
            WHERE id = p_job_id;
        END IF;

        RETURN true;
    END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION api.record_notification_result(UUID, UUID, TEXT, INTEGER) TO authenticated;

-- 9. Canonical Views for Installations and Jobs
CREATE OR REPLACE VIEW api.installations
WITH (security_invoker = true) AS
SELECT
    i.id,
    i.platform,
    i.local_enabled AS "localEnabled",
    i.permission_state AS "permissionState",
    i.endpoint,
    i.installation_timezone AS "installationTimezone",
    i.timezone_observed_at AS "timezoneObservedAt",
    i.is_active AS "isActive",
    i.created_at AS "createdAt",
    i.updated_at AS "updatedAt"
FROM public.installations i;

GRANT SELECT ON api.installations TO authenticated;

CREATE OR REPLACE VIEW api.notification_jobs
WITH (security_invoker = true) AS
SELECT
    j.id,
    j.task_id AS "taskId",
    j.installation_id AS "installationId",
    j.plan_version AS "planVersion",
    j.interpreted_due_at AS "interpretedDueAt",
    j.occurrence_key AS "occurrenceKey",
    j.state,
    j.attempt_count AS "attemptCount",
    j.next_attempt_at AS "nextAttemptAt",
    j.created_at AS "createdAt",
    j.updated_at AS "updatedAt"
FROM public.notification_jobs j;

GRANT SELECT ON api.notification_jobs TO authenticated;
