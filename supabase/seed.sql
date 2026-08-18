-- Seed deployment configuration and voice catalog

INSERT INTO public.deployment_config (id, default_timed_plan_type, voice_enabled)
VALUES (1, 'instant', true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO public.voice_model_catalog (key, type, name, is_default, is_enabled)
VALUES
    ('voxtral-small', 'stt', 'Voxtral Small', true, true),
    ('gemma-4-26b-a4b-it', 'extractor', 'Gemma 4 26B-A4B-it', true, true)
ON CONFLICT (key) DO UPDATE SET
    name = EXCLUDED.name,
    is_default = EXCLUDED.is_default,
    is_enabled = EXCLUDED.is_enabled;
