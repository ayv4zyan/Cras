-- Migration: Enhance api.update_task RPC to support clear_plan and clear_description boolean parameters

DROP FUNCTION IF EXISTS api.update_task(UUID, TEXT, TEXT, INTEGER, JSONB, UUID, INTEGER);
DROP FUNCTION IF EXISTS api.update_task(UUID, TEXT, TEXT, INTEGER, JSONB, UUID, INTEGER, UUID[]);
DROP FUNCTION IF EXISTS api.update_task(UUID, TEXT, TEXT, INTEGER, JSONB, UUID, INTEGER, BOOLEAN);
DROP FUNCTION IF EXISTS api.update_task(UUID, TEXT, TEXT, INTEGER, JSONB, UUID, INTEGER, UUID[], BOOLEAN);

CREATE OR REPLACE FUNCTION api.update_task(
    id UUID,
    title TEXT DEFAULT NULL,
    description TEXT DEFAULT NULL,
    priority INTEGER DEFAULT NULL,
    plan JSONB DEFAULT NULL,
    parent_id UUID DEFAULT NULL,
    expected_version INTEGER DEFAULT NULL,
    labels UUID[] DEFAULT NULL,
    clear_plan BOOLEAN DEFAULT false,
    clear_description BOOLEAN DEFAULT false
)
RETURNS api.tasks
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
DECLARE
    v_task public.tasks%ROWTYPE;
    v_task_id UUID;
    v_label_id UUID;
    v_result api.tasks;
BEGIN
    SELECT * INTO v_task
    FROM public.tasks
    WHERE public.tasks.id = update_task.id
      AND public.tasks.operator_id = auth.uid()
    FOR UPDATE;

    IF v_task.id IS NULL THEN
        RAISE EXCEPTION 'Task not found or unauthorized';
    END IF;

    -- Acceptance criterion: A completed Task must be uncompleted before its fields can be edited.
    IF v_task.completed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Completed tasks cannot be edited. Uncomplete first.';
    END IF;

    IF update_task.expected_version IS NOT NULL AND v_task.version <> update_task.expected_version THEN
        RAISE EXCEPTION 'Task version conflict: expected %, found %', update_task.expected_version, v_task.version;
    END IF;

    IF update_task.title IS NOT NULL AND char_length(trim(update_task.title)) = 0 THEN
        RAISE EXCEPTION 'Task title cannot be empty';
    END IF;

    IF update_task.priority IS NOT NULL AND (update_task.priority < 1 OR update_task.priority > 4) THEN
        RAISE EXCEPTION 'Priority must be between 1 and 4';
    END IF;

    IF update_task.plan IS NOT NULL THEN
        IF jsonb_typeof(update_task.plan) <> 'object' THEN
            RAISE EXCEPTION 'Invalid plan format: expected JSON object';
        END IF;

        IF update_task.plan ? 'type' AND jsonb_typeof(update_task.plan->'type') <> 'null' THEN
            IF update_task.plan->>'type' NOT IN ('floating', 'instant') THEN
                RAISE EXCEPTION 'Invalid plan type: %', update_task.plan->>'type';
            END IF;
            IF update_task.plan->>'type' = 'floating' THEN
                IF (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(update_task.plan) k) <> ARRAY['date', 'time', 'type']::TEXT[] THEN
                    RAISE EXCEPTION 'Floating plan requires exactly type, date, and time';
                END IF;
                IF jsonb_typeof(update_task.plan->'date') <> 'string' OR NOT ((update_task.plan->>'date') ~ '^\d{4}-\d{2}-\d{2}$') THEN
                    RAISE EXCEPTION 'Floating plan date must match YYYY-MM-DD format';
                END IF;
                IF jsonb_typeof(update_task.plan->'time') <> 'string' OR NOT ((update_task.plan->>'time') ~ '^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$') THEN
                    RAISE EXCEPTION 'Floating plan time must match HH:MM or HH:MM:SS format';
                END IF;
            ELSIF update_task.plan->>'type' = 'instant' THEN
                IF (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(update_task.plan) k) <> ARRAY['at', 'type']::TEXT[] THEN
                    RAISE EXCEPTION 'Instant plan requires exactly type and at';
                END IF;
                IF jsonb_typeof(update_task.plan->'at') <> 'string' OR NOT ((update_task.plan->>'at') ~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$') THEN
                    RAISE EXCEPTION 'Instant plan at must match ISO date-time format';
                END IF;
            END IF;
        ELSIF (update_task.plan ? 'date') THEN
            IF update_task.plan ? 'type' THEN
                IF (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(update_task.plan) k) <> ARRAY['date', 'type']::TEXT[] THEN
                    RAISE EXCEPTION 'Date-only plan with null type requires exactly type and date';
                END IF;
            ELSE
                IF (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(update_task.plan) k) <> ARRAY['date']::TEXT[] THEN
                    RAISE EXCEPTION 'Date-only plan requires exactly date';
                END IF;
            END IF;
            IF jsonb_typeof(update_task.plan->'date') <> 'string' OR NOT ((update_task.plan->>'date') ~ '^\d{4}-\d{2}-\d{2}$') THEN
                RAISE EXCEPTION 'Date-only plan date must match YYYY-MM-DD format';
            END IF;
        ELSE
            RAISE EXCEPTION 'Plan must specify date or type';
        END IF;
    END IF;

    UPDATE public.tasks
    SET
        title = CASE WHEN update_task.title IS NOT NULL THEN trim(update_task.title) ELSE v_task.title END,
        description = CASE
            WHEN update_task.clear_description = true THEN NULL
            WHEN update_task.description IS NOT NULL THEN update_task.description
            ELSE v_task.description
        END,
        priority = CASE WHEN update_task.priority IS NOT NULL THEN update_task.priority ELSE v_task.priority END,
        plan = CASE
            WHEN update_task.clear_plan = true THEN NULL
            WHEN update_task.plan IS NOT NULL THEN update_task.plan
            ELSE v_task.plan
        END,
        parent_id = CASE WHEN update_task.parent_id IS NOT NULL THEN update_task.parent_id ELSE v_task.parent_id END,
        updated_at = now(),
        version = v_task.version + 1
    WHERE public.tasks.id = update_task.id
      AND public.tasks.operator_id = auth.uid()
    RETURNING public.tasks.id INTO v_task_id;

    IF update_task.labels IS NOT NULL THEN
        DELETE FROM public.task_labels
        WHERE task_id = v_task_id AND operator_id = auth.uid();

        IF array_length(update_task.labels, 1) > 0 THEN
            FOREACH v_label_id IN ARRAY update_task.labels LOOP
                INSERT INTO public.task_labels (task_id, label_id, operator_id)
                VALUES (v_task_id, v_label_id, auth.uid());
            END LOOP;
        END IF;
    END IF;

    SELECT * INTO v_result FROM api.tasks WHERE api.tasks.id = v_task_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.update_task(UUID, TEXT, TEXT, INTEGER, JSONB, UUID, INTEGER, UUID[], BOOLEAN, BOOLEAN) TO authenticated;
