-- Migration: Add api.create_task RPC and table permissions for authenticated operators

GRANT USAGE ON SCHEMA api TO authenticated, anon;
GRANT USAGE ON SCHEMA public TO authenticated, anon;

GRANT SELECT, INSERT, UPDATE, DELETE ON public.tasks TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.task_labels TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.labels TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.comments TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.settings TO authenticated;
GRANT SELECT ON public.deployment_config TO authenticated;
GRANT SELECT ON public.voice_model_catalog TO authenticated;

GRANT SELECT ON api.tasks TO authenticated;

-- Function: api.create_task
CREATE OR REPLACE FUNCTION api.create_task(
    title TEXT,
    id UUID DEFAULT gen_random_uuid(),
    description TEXT DEFAULT NULL,
    priority INTEGER DEFAULT 4,
    plan JSONB DEFAULT NULL,
    "parentId" UUID DEFAULT NULL,
    labels UUID[] DEFAULT '{}'::UUID[]
)
RETURNS api.tasks
LANGUAGE plpgsql
SECURITY INVOKER
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
        COALESCE(id, gen_random_uuid()),
        auth.uid(),
        trim(title),
        description,
        COALESCE(priority, 4),
        plan,
        "parentId"
    )
    RETURNING public.tasks.id INTO v_task_id;

    IF labels IS NOT NULL AND array_length(labels, 1) > 0 THEN
        FOREACH v_label_id IN ARRAY labels LOOP
            INSERT INTO public.task_labels (task_id, label_id, operator_id)
            VALUES (v_task_id, v_label_id, auth.uid());
        END LOOP;
    END IF;

    SELECT * INTO v_result FROM api.tasks WHERE api.tasks.id = v_task_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.create_task(TEXT, UUID, TEXT, INTEGER, JSONB, UUID, UUID[]) TO authenticated;
