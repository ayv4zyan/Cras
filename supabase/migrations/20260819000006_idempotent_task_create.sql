-- Migration: Make api.create_task idempotent when retried with an existing task id

CREATE OR REPLACE FUNCTION api.create_task(
    title TEXT,
    id UUID DEFAULT gen_random_uuid(),
    description TEXT DEFAULT NULL,
    priority INTEGER DEFAULT 4,
    plan JSONB DEFAULT NULL,
    parent_id UUID DEFAULT NULL,
    labels UUID[] DEFAULT '{}'::UUID[]
)
RETURNS api.tasks
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
DECLARE
    v_task_id UUID;
    v_label_id UUID;
    v_result api.tasks;
BEGIN
    IF title IS NULL OR char_length(trim(title)) = 0 THEN
        RAISE EXCEPTION 'Task title cannot be empty';
    END IF;

    INSERT INTO public.tasks (
        id,
        operator_id,
        title,
        description,
        priority,
        plan,
        parent_id
    ) VALUES (
        COALESCE(create_task.id, gen_random_uuid()),
        auth.uid(),
        trim(title),
        description,
        COALESCE(priority, 4),
        plan,
        parent_id
    )
    ON CONFLICT (id) DO UPDATE
        SET updated_at = public.tasks.updated_at
    RETURNING public.tasks.id INTO v_task_id;

    IF labels IS NOT NULL AND array_length(labels, 1) > 0 THEN
        FOREACH v_label_id IN ARRAY labels LOOP
            INSERT INTO public.task_labels (task_id, label_id, operator_id)
            VALUES (v_task_id, v_label_id, auth.uid())
            ON CONFLICT (task_id, label_id) DO NOTHING;
        END LOOP;
    END IF;

    SELECT * INTO v_result FROM api.tasks WHERE api.tasks.id = v_task_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.create_task(TEXT, UUID, TEXT, INTEGER, JSONB, UUID, UUID[]) TO authenticated;
