-- Cras Initial Database Schema
-- Dedicated api schema for client contract views and invoker RPCs
CREATE SCHEMA IF NOT EXISTS api;

-- Grant usage to authenticated and anon roles
GRANT USAGE ON SCHEMA api TO anon, authenticated;
GRANT USAGE ON SCHEMA public TO anon, authenticated;

--------------------------------------------------
-- 1. Deployment Configuration & Voice Catalog
--------------------------------------------------
CREATE TABLE IF NOT EXISTS public.deployment_config (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    default_timed_plan_type TEXT NOT NULL DEFAULT 'instant' CHECK (default_timed_plan_type IN ('instant', 'floating')),
    voice_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.deployment_config ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow read deployment configuration"
    ON public.deployment_config
    FOR SELECT
    TO authenticated, anon
    USING (true);

CREATE TABLE IF NOT EXISTS public.voice_model_catalog (
    key TEXT PRIMARY KEY,
    type TEXT NOT NULL CHECK (type IN ('stt', 'extractor')),
    name TEXT NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT false,
    is_enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.voice_model_catalog ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Allow read enabled voice models"
    ON public.voice_model_catalog
    FOR SELECT
    TO authenticated, anon
    USING (is_enabled = true);

--------------------------------------------------
-- 2. Operator Settings
--------------------------------------------------
CREATE TABLE IF NOT EXISTS public.settings (
    operator_id UUID PRIMARY KEY DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    default_timed_plan_type TEXT CHECK (default_timed_plan_type IN ('instant', 'floating')),
    missed_delivery_enabled BOOLEAN NOT NULL DEFAULT false,
    stt_model_key TEXT REFERENCES public.voice_model_catalog(key),
    extractor_model_key TEXT REFERENCES public.voice_model_catalog(key),
    custom_extractor_prompt TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.settings ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Operators manage their own settings"
    ON public.settings
    FOR ALL
    TO authenticated
    USING (operator_id = auth.uid())
    WITH CHECK (operator_id = auth.uid());

--------------------------------------------------
-- 3. Tasks, Labels, Comments, and Joins
--------------------------------------------------
CREATE TABLE IF NOT EXISTS public.labels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (char_length(trim(name)) > 0),
    color TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_labels_id_operator UNIQUE (id, operator_id),
    CONSTRAINT uq_labels_name_operator UNIQUE (name, operator_id)
);

ALTER TABLE public.labels ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Operators manage their own labels"
    ON public.labels
    FOR ALL
    TO authenticated
    USING (operator_id = auth.uid())
    WITH CHECK (operator_id = auth.uid());

CREATE TABLE IF NOT EXISTS public.tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    title TEXT NOT NULL CHECK (char_length(trim(title)) > 0),
    description TEXT,
    priority INTEGER NOT NULL DEFAULT 4 CHECK (priority BETWEEN 1 AND 4),
    plan JSONB,
    parent_id UUID,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 1,
    CONSTRAINT uq_tasks_id_operator UNIQUE (id, operator_id),
    CONSTRAINT fk_tasks_parent FOREIGN KEY (parent_id, operator_id)
        REFERENCES public.tasks(id, operator_id) ON DELETE CASCADE
);

ALTER TABLE public.tasks ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Operators manage their own tasks"
    ON public.tasks
    FOR ALL
    TO authenticated
    USING (operator_id = auth.uid())
    WITH CHECK (operator_id = auth.uid());

CREATE TABLE IF NOT EXISTS public.task_labels (
    task_id UUID NOT NULL,
    label_id UUID NOT NULL,
    operator_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, label_id),
    CONSTRAINT fk_task_labels_task FOREIGN KEY (task_id, operator_id)
        REFERENCES public.tasks(id, operator_id) ON DELETE CASCADE,
    CONSTRAINT fk_task_labels_label FOREIGN KEY (label_id, operator_id)
        REFERENCES public.labels(id, operator_id) ON DELETE CASCADE
);

ALTER TABLE public.task_labels ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Operators manage their own task labels"
    ON public.task_labels
    FOR ALL
    TO authenticated
    USING (operator_id = auth.uid())
    WITH CHECK (operator_id = auth.uid());

CREATE TABLE IF NOT EXISTS public.comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL,
    operator_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id) ON DELETE CASCADE,
    content TEXT NOT NULL CHECK (char_length(trim(content)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_comments_task FOREIGN KEY (task_id, operator_id)
        REFERENCES public.tasks(id, operator_id) ON DELETE CASCADE
);

ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Operators manage their own comments"
    ON public.comments
    FOR ALL
    TO authenticated
    USING (operator_id = auth.uid())
    WITH CHECK (operator_id = auth.uid());

--------------------------------------------------
-- 4. Usage-Security Records
--------------------------------------------------
CREATE TABLE IF NOT EXISTS public.usage_security_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pseudonymous_key TEXT NOT NULL,
    bucket_start TIMESTAMPTZ NOT NULL,
    bucket_end TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    audio_seconds NUMERIC(10, 2) NOT NULL DEFAULT 0,
    estimated_cost NUMERIC(10, 4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.usage_security_records ENABLE ROW LEVEL SECURITY;
-- usage_security_records is accessed only by trusted Edge Functions via service_role

--------------------------------------------------
-- 5. Canonical API Views (security_invoker)
--------------------------------------------------
CREATE OR REPLACE VIEW api.tasks
WITH (security_invoker = true) AS
SELECT
    t.id,
    t.title,
    t.description,
    t.priority,
    t.plan,
    COALESCE(
        (SELECT jsonb_agg(tl.label_id) FROM public.task_labels tl WHERE tl.task_id = t.id),
        '[]'::jsonb
    ) AS labels,
    t.parent_id AS "parentId",
    t.completed_at AS "completedAt",
    t.created_at AS "createdAt",
    t.updated_at AS "updatedAt",
    t.version
FROM public.tasks t;

GRANT SELECT ON api.tasks TO authenticated;
