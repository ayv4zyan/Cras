-- Migration: Add api.comments view, api.create_comment RPC, and enhanced subtask nesting check

-- 1. Canonical API View for Comments (security_invoker = true)
CREATE OR REPLACE VIEW api.comments
WITH (security_invoker = true) AS
SELECT
    c.id,
    c.task_id AS "taskId",
    c.content,
    c.created_at AS "createdAt"
FROM public.comments c;

GRANT SELECT ON api.comments TO authenticated;

-- 2. RPC to create a dated Comment under an Operator's Task
CREATE OR REPLACE FUNCTION api.create_comment(
    task_id UUID,
    content TEXT,
    id UUID DEFAULT gen_random_uuid()
)
RETURNS api.comments
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
DECLARE
    v_comment_id UUID;
    v_task_exists BOOLEAN;
    v_result api.comments;
BEGIN
    IF content IS NULL OR char_length(trim(content)) = 0 THEN
        RAISE EXCEPTION 'Comment content cannot be empty';
    END IF;

    -- Ensure target task belongs to authenticated operator
    SELECT EXISTS (
        SELECT 1 FROM public.tasks
        WHERE public.tasks.id = create_comment.task_id
          AND public.tasks.operator_id = auth.uid()
    ) INTO v_task_exists;

    IF NOT v_task_exists THEN
        RAISE EXCEPTION 'Task not found or unauthorized';
    END IF;

    INSERT INTO public.comments (
        id,
        task_id,
        operator_id,
        content
    ) VALUES (
        COALESCE(id, gen_random_uuid()),
        create_comment.task_id,
        auth.uid(),
        trim(content)
    )
    RETURNING public.comments.id INTO v_comment_id;

    SELECT * INTO v_result FROM api.comments WHERE api.comments.id = v_comment_id;
    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION api.create_comment(UUID, TEXT, UUID) TO authenticated;

-- 3. Enhance subtask nesting check trigger function
CREATE OR REPLACE FUNCTION public.check_subtask_nesting()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        IF NEW.parent_id = NEW.id THEN
            RAISE EXCEPTION 'A task cannot be its own parent';
        END IF;

        -- Check if target parent is itself a subtask
        IF EXISTS (
            SELECT 1 FROM public.tasks
            WHERE id = NEW.parent_id AND operator_id = NEW.operator_id AND parent_id IS NOT NULL
            FOR SHARE
        ) THEN
            RAISE EXCEPTION 'Subtasks cannot have children (one-level nesting only)';
        END IF;

        -- Check if task being updated already has subtasks
        IF EXISTS (
            SELECT 1 FROM public.tasks
            WHERE parent_id = NEW.id AND operator_id = NEW.operator_id
            FOR SHARE
        ) THEN
            RAISE EXCEPTION 'A task with children cannot become a subtask (one-level nesting only)';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
