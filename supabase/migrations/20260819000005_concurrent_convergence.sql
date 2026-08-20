-- Migration: Add expected_version to complete_task and uncomplete_task RPCs, and add realtime invalidation setup

-- 1. Updated api.complete_task supporting version CAS
DROP FUNCTION IF EXISTS api.complete_task(UUID, TIMESTAMPTZ);
DROP FUNCTION IF EXISTS api.complete_task(UUID, TIMESTAMPTZ, INTEGER);

CREATE OR REPLACE FUNCTION api.complete_task(
    id UUID,
    completed_at TIMESTAMPTZ DEFAULT now(),
    expected_version INTEGER DEFAULT NULL
)
RETURNS api.tasks
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
DECLARE
    v_task public.tasks%ROWTYPE;
    v_task_id UUID;
    v_result api.tasks;
BEGIN
    SELECT * INTO v_task
    FROM public.tasks
    WHERE public.tasks.id = complete_task.id
      AND public.tasks.operator_id = auth.uid()
    FOR UPDATE;

    IF v_task.id IS NULL THEN
        RAISE EXCEPTION 'Task not found or unauthorized';
    END IF;

    IF complete_task.expected_version IS NOT NULL AND v_task.version <> complete_task.expected_version THEN
        RAISE EXCEPTION 'Task version conflict: expected %, found %', complete_task.expected_version, v_task.version
            USING ERRCODE = 'P0003';
    END IF;

    UPDATE public.tasks
    SET
        completed_at = COALESCE(complete_task.completed_at, now()),
        updated_at = now(),
        version = v_task.version + 1
    WHERE public.tasks.id = complete_task.id
      AND public.tasks.operator_id = auth.uid()
    RETURNING public.tasks.id INTO v_task_id;

    SELECT * INTO v_result FROM api.tasks WHERE api.tasks.id = v_task_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.complete_task(UUID, TIMESTAMPTZ, INTEGER) TO authenticated;

-- 2. Updated api.uncomplete_task supporting version CAS
DROP FUNCTION IF EXISTS api.uncomplete_task(UUID);
DROP FUNCTION IF EXISTS api.uncomplete_task(UUID, INTEGER);

CREATE OR REPLACE FUNCTION api.uncomplete_task(
    id UUID,
    expected_version INTEGER DEFAULT NULL
)
RETURNS api.tasks
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
DECLARE
    v_task public.tasks%ROWTYPE;
    v_task_id UUID;
    v_result api.tasks;
BEGIN
    SELECT * INTO v_task
    FROM public.tasks
    WHERE public.tasks.id = uncomplete_task.id
      AND public.tasks.operator_id = auth.uid()
    FOR UPDATE;

    IF v_task.id IS NULL THEN
        RAISE EXCEPTION 'Task not found or unauthorized';
    END IF;

    IF uncomplete_task.expected_version IS NOT NULL AND v_task.version <> uncomplete_task.expected_version THEN
        RAISE EXCEPTION 'Task version conflict: expected %, found %', uncomplete_task.expected_version, v_task.version
            USING ERRCODE = 'P0003';
    END IF;

    UPDATE public.tasks
    SET
        completed_at = NULL,
        updated_at = now(),
        version = v_task.version + 1
    WHERE public.tasks.id = uncomplete_task.id
      AND public.tasks.operator_id = auth.uid()
    RETURNING public.tasks.id INTO v_task_id;

    SELECT * INTO v_result FROM api.tasks WHERE api.tasks.id = v_task_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.uncomplete_task(UUID, INTEGER) TO authenticated;

-- 3. RLS policy on realtime.messages for authenticated operator broadcast topics
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'realtime' AND tablename = 'messages') THEN
        DROP POLICY IF EXISTS "Authenticated users can listen to own operator channel" ON realtime.messages;
        CREATE POLICY "Authenticated users can listen to own operator channel"
        ON realtime.messages
        FOR SELECT
        TO authenticated
        USING (
            topic = 'operator:' || auth.uid()::text
            AND extension = 'broadcast'
        );
    END IF;
END;
$$;

-- 4. Database-triggered private Broadcast events for resource invalidation
-- Emits only resource identity, operation, and necessary parent identity without sensitive content.
CREATE OR REPLACE FUNCTION public.broadcast_resource_invalidation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, realtime
AS $$
DECLARE
    v_operator_id UUID;
    v_resource TEXT;
    v_id UUID;
    v_op TEXT;
    v_parent_id UUID := NULL;
    v_task_id UUID := NULL;
    v_payload JSONB;
BEGIN
    IF TG_TABLE_NAME = 'tasks' THEN
        v_resource := 'task';
        IF TG_OP = 'DELETE' THEN
            v_operator_id := OLD.operator_id;
            v_id := OLD.id;
            v_parent_id := OLD.parent_id;
        ELSE
            v_operator_id := NEW.operator_id;
            v_id := NEW.id;
            v_parent_id := NEW.parent_id;
        END IF;
    ELSIF TG_TABLE_NAME = 'labels' THEN
        v_resource := 'label';
        IF TG_OP = 'DELETE' THEN
            v_operator_id := OLD.operator_id;
            v_id := OLD.id;
        ELSE
            v_operator_id := NEW.operator_id;
            v_id := NEW.id;
        END IF;
    ELSIF TG_TABLE_NAME = 'comments' THEN
        v_resource := 'comment';
        IF TG_OP = 'DELETE' THEN
            v_operator_id := OLD.operator_id;
            v_id := OLD.id;
            v_task_id := OLD.task_id;
        ELSE
            v_operator_id := NEW.operator_id;
            v_id := NEW.id;
            v_task_id := NEW.task_id;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        v_op := 'created';
    ELSIF TG_OP = 'UPDATE' THEN
        v_op := 'updated';
    ELSIF TG_OP = 'DELETE' THEN
        v_op := 'deleted';
    END IF;

    v_payload := jsonb_build_object(
        'resource', v_resource,
        'id', v_id,
        'operation', v_op,
        'parentId', v_parent_id,
        'taskId', v_task_id
    );

    BEGIN
        PERFORM realtime.send(
            v_payload,
            'invalidate',
            'operator:' || v_operator_id::text,
            true
        );
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'Failed to send broadcast invalidation: %', SQLERRM;
    END;

    RETURN COALESCE(NEW, OLD);
END;
$$;

DROP TRIGGER IF EXISTS trg_tasks_broadcast_invalidation ON public.tasks;
CREATE TRIGGER trg_tasks_broadcast_invalidation
AFTER INSERT OR UPDATE OR DELETE ON public.tasks
FOR EACH ROW EXECUTE FUNCTION public.broadcast_resource_invalidation();

DROP TRIGGER IF EXISTS trg_labels_broadcast_invalidation ON public.labels;
CREATE TRIGGER trg_labels_broadcast_invalidation
AFTER INSERT OR UPDATE OR DELETE ON public.labels
FOR EACH ROW EXECUTE FUNCTION public.broadcast_resource_invalidation();

DROP TRIGGER IF EXISTS trg_comments_broadcast_invalidation ON public.comments;
CREATE TRIGGER trg_comments_broadcast_invalidation
AFTER INSERT OR UPDATE OR DELETE ON public.comments
FOR EACH ROW EXECUTE FUNCTION public.broadcast_resource_invalidation();
