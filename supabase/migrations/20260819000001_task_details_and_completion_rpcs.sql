-- Migration: Add api.update_task, api.complete_task, and api.uncomplete_task RPCs

-- Function: api.complete_task
CREATE OR REPLACE FUNCTION api.complete_task(
    id UUID,
    completed_at TIMESTAMPTZ DEFAULT now()
)
RETURNS api.tasks
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_task_id UUID;
    v_result api.tasks;
BEGIN
    UPDATE public.tasks
    SET
        completed_at = COALESCE(complete_task.completed_at, now()),
        updated_at = now(),
        version = version + 1
    WHERE public.tasks.id = complete_task.id
      AND public.tasks.operator_id = auth.uid()
    RETURNING public.tasks.id INTO v_task_id;

    IF v_task_id IS NULL THEN
        RAISE EXCEPTION 'Task not found or unauthorized';
    END IF;

    SELECT * INTO v_result FROM api.tasks WHERE api.tasks.id = v_task_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.complete_task(UUID, TIMESTAMPTZ) TO authenticated;

-- Function: api.uncomplete_task
CREATE OR REPLACE FUNCTION api.uncomplete_task(
    id UUID
)
RETURNS api.tasks
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_task_id UUID;
    v_result api.tasks;
BEGIN
    UPDATE public.tasks
    SET
        completed_at = NULL,
        updated_at = now(),
        version = version + 1
    WHERE public.tasks.id = uncomplete_task.id
      AND public.tasks.operator_id = auth.uid()
    RETURNING public.tasks.id INTO v_task_id;

    IF v_task_id IS NULL THEN
        RAISE EXCEPTION 'Task not found or unauthorized';
    END IF;

    SELECT * INTO v_result FROM api.tasks WHERE api.tasks.id = v_task_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.uncomplete_task(UUID) TO authenticated;

-- Function: api.update_task
CREATE OR REPLACE FUNCTION api.update_task(
    id UUID,
    title TEXT DEFAULT NULL,
    description TEXT DEFAULT NULL,
    priority INTEGER DEFAULT NULL,
    plan JSONB DEFAULT NULL,
    parent_id UUID DEFAULT NULL,
    expected_version INTEGER DEFAULT NULL,
    clear_description BOOLEAN DEFAULT false
)
RETURNS api.tasks
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_task public.tasks%ROWTYPE;
    v_task_id UUID;
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

    UPDATE public.tasks
    SET
        title = CASE WHEN update_task.title IS NOT NULL THEN trim(update_task.title) ELSE v_task.title END,
        description = CASE
            WHEN update_task.clear_description = true THEN NULL
            WHEN update_task.description IS NOT NULL THEN update_task.description
            ELSE v_task.description
        END,
        priority = CASE WHEN update_task.priority IS NOT NULL THEN update_task.priority ELSE v_task.priority END,
        plan = CASE WHEN update_task.plan IS NOT NULL THEN update_task.plan ELSE v_task.plan END,
        parent_id = CASE WHEN update_task.parent_id IS NOT NULL THEN update_task.parent_id ELSE v_task.parent_id END,
        updated_at = now(),
        version = v_task.version + 1
    WHERE public.tasks.id = update_task.id
      AND public.tasks.operator_id = auth.uid()
    RETURNING public.tasks.id INTO v_task_id;

    SELECT * INTO v_result FROM api.tasks WHERE api.tasks.id = v_task_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.update_task(UUID, TEXT, TEXT, INTEGER, JSONB, UUID, INTEGER, BOOLEAN) TO authenticated;
