-- Voice allowance, circuit breaker, and usage security accounting

CREATE TABLE IF NOT EXISTS public.voice_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pseudonymous_key TEXT NOT NULL,
    audio_seconds NUMERIC(10, 2) NOT NULL DEFAULT 0,
    estimated_cost NUMERIC(10, 4) NOT NULL DEFAULT 0,
    actual_cost NUMERIC(10, 4),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'failed')),
    model_key TEXT,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '5 minutes')
);

ALTER TABLE public.voice_reservations ENABLE ROW LEVEL SECURITY;
-- voice_reservations is internal and accessed only by trusted Edge Functions via service_role

CREATE INDEX IF NOT EXISTS idx_voice_reservations_pseudo_created 
    ON public.voice_reservations (pseudonymous_key, created_at);

CREATE INDEX IF NOT EXISTS idx_voice_reservations_status_pseudo 
    ON public.voice_reservations (status, pseudonymous_key);

CREATE INDEX IF NOT EXISTS idx_voice_reservations_created 
    ON public.voice_reservations (created_at);

-- Reserve voice allowance atomically
CREATE OR REPLACE FUNCTION api.reserve_voice_allowance(
    p_pseudonymous_key TEXT,
    p_audio_seconds NUMERIC,
    p_estimated_cost NUMERIC
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_voice_enabled BOOLEAN;
    v_active_count INTEGER;
    v_min_count INTEGER;
    v_min_oldest TIMESTAMPTZ;
    v_day_count INTEGER;
    v_day_audio NUMERIC;
    v_day_oldest TIMESTAMPTZ;
    v_month_count INTEGER;
    v_month_audio NUMERIC;
    v_month_oldest TIMESTAMPTZ;
    v_deploy_24h_spend NUMERIC;
    v_deploy_30d_spend NUMERIC;
    v_now TIMESTAMPTZ := now();
    v_reservation_id UUID;
    v_retry_after TIMESTAMPTZ;
BEGIN
    -- 1. Check deployment config
    SELECT voice_enabled INTO v_voice_enabled
    FROM public.deployment_config
    WHERE id = 1;

    IF v_voice_enabled IS FALSE THEN
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'voice_disabled',
            'message', 'Voice capture is temporarily disabled.'
        );
    END IF;

    -- Clean up expired active reservations
    UPDATE public.voice_reservations
    SET status = 'failed', updated_at = v_now
    WHERE status = 'active' AND expires_at < v_now;

    -- 2. Check per-Operator active concurrent reservation (max 1)
    SELECT COUNT(*) INTO v_active_count
    FROM public.voice_reservations
    WHERE pseudonymous_key = p_pseudonymous_key
      AND status = 'active'
      AND expires_at >= v_now;

    IF v_active_count >= 1 THEN
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'concurrent_limit',
            'message', 'Another Voice capture is currently in progress.'
        );
    END IF;

    -- 3. Check per-Operator rolling 1-minute limit (max 3 requests)
    SELECT COUNT(*), MIN(created_at) INTO v_min_count, v_min_oldest
    FROM public.voice_reservations
    WHERE pseudonymous_key = p_pseudonymous_key
      AND created_at >= (v_now - interval '1 minute');

    IF v_min_count >= 3 THEN
        v_retry_after := v_min_oldest + interval '1 minute';
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'rate_limit_minute',
            'earliest_retry_at', v_retry_after,
            'retry_after_seconds', GREATEST(1, EXTRACT(EPOCH FROM (v_retry_after - v_now))::INTEGER),
            'message', 'Rate limit exceeded: maximum 3 requests per minute.'
        );
    END IF;

    -- 4. Check per-Operator rolling 24-hour limit (max 20 requests, max 10 audio minutes / 600s)
    SELECT COUNT(*), COALESCE(SUM(audio_seconds), 0), MIN(created_at)
    INTO v_day_count, v_day_audio, v_day_oldest
    FROM public.voice_reservations
    WHERE pseudonymous_key = p_pseudonymous_key
      AND created_at >= (v_now - interval '24 hours');

    IF v_day_count >= 20 OR (v_day_audio + p_audio_seconds) > 600 THEN
        v_retry_after := v_day_oldest + interval '24 hours';
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'daily_limit_exceeded',
            'earliest_retry_at', v_retry_after,
            'retry_after_seconds', GREATEST(1, EXTRACT(EPOCH FROM (v_retry_after - v_now))::INTEGER),
            'message', 'Daily voice allowance exceeded (20 requests or 10 audio minutes per 24 hours).'
        );
    END IF;

    -- 5. Check per-Operator rolling 30-day limit (max 300 requests, max 60 audio minutes / 3600s)
    SELECT COUNT(*), COALESCE(SUM(audio_seconds), 0), MIN(created_at)
    INTO v_month_count, v_month_audio, v_month_oldest
    FROM public.voice_reservations
    WHERE pseudonymous_key = p_pseudonymous_key
      AND created_at >= (v_now - interval '30 days');

    IF v_month_count >= 300 OR (v_month_audio + p_audio_seconds) > 3600 THEN
        v_retry_after := v_month_oldest + interval '30 days';
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'monthly_limit_exceeded',
            'earliest_retry_at', v_retry_after,
            'retry_after_seconds', GREATEST(1, EXTRACT(EPOCH FROM (v_retry_after - v_now))::INTEGER),
            'message', 'Monthly voice allowance exceeded (300 requests or 60 audio minutes per 30 days).'
        );
    END IF;

    -- 6. Check Deployment-wide circuit breaker ($1 / 24h, $5 / 30d)
    SELECT COALESCE(SUM(COALESCE(actual_cost, estimated_cost)), 0) INTO v_deploy_24h_spend
    FROM public.voice_reservations
    WHERE created_at >= (v_now - interval '24 hours');

    IF (v_deploy_24h_spend + p_estimated_cost) > 1.0000 THEN
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'circuit_breaker_daily',
            'message', 'Voice capture is temporarily unavailable. Please try again later.'
        );
    END IF;

    SELECT COALESCE(SUM(COALESCE(actual_cost, estimated_cost)), 0) INTO v_deploy_30d_spend
    FROM public.voice_reservations
    WHERE created_at >= (v_now - interval '30 days');

    IF (v_deploy_30d_spend + p_estimated_cost) > 5.0000 THEN
        RETURN jsonb_build_object(
            'allowed', false,
            'reason', 'circuit_breaker_monthly',
            'message', 'Voice capture is temporarily unavailable. Please try again later.'
        );
    END IF;

    -- 7. All checks passed: insert active reservation
    INSERT INTO public.voice_reservations (
        pseudonymous_key,
        audio_seconds,
        estimated_cost,
        status,
        created_at,
        updated_at,
        expires_at
    ) VALUES (
        p_pseudonymous_key,
        p_audio_seconds,
        p_estimated_cost,
        'active',
        v_now,
        v_now,
        v_now + interval '5 minutes'
    ) RETURNING id INTO v_reservation_id;

    RETURN jsonb_build_object(
        'allowed', true,
        'reservation_id', v_reservation_id
    );
END;
$$;

REVOKE ALL ON FUNCTION api.reserve_voice_allowance(TEXT, NUMERIC, NUMERIC) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.reserve_voice_allowance(TEXT, NUMERIC, NUMERIC) TO service_role;

-- Reconcile voice usage atomically
CREATE OR REPLACE FUNCTION api.reconcile_voice_usage(
    p_reservation_id UUID,
    p_status TEXT,
    p_actual_audio_seconds NUMERIC DEFAULT NULL,
    p_model_key TEXT DEFAULT NULL,
    p_prompt_tokens INTEGER DEFAULT NULL,
    p_completion_tokens INTEGER DEFAULT NULL,
    p_actual_cost NUMERIC DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    UPDATE public.voice_reservations
    SET status = p_status,
        audio_seconds = COALESCE(p_actual_audio_seconds, audio_seconds),
        actual_cost = COALESCE(p_actual_cost, actual_cost, estimated_cost),
        model_key = p_model_key,
        prompt_tokens = p_prompt_tokens,
        completion_tokens = p_completion_tokens,
        updated_at = now()
    WHERE id = p_reservation_id;

    -- Purge records older than 35 days (retention rule)
    DELETE FROM public.voice_reservations
    WHERE created_at < (now() - interval '35 days');

    DELETE FROM public.usage_security_records
    WHERE bucket_end < (now() - interval '35 days');
END;
$$;

REVOKE ALL ON FUNCTION api.reconcile_voice_usage(UUID, TEXT, NUMERIC, TEXT, INTEGER, INTEGER, NUMERIC) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION api.reconcile_voice_usage(UUID, TEXT, NUMERIC, TEXT, INTEGER, INTEGER, NUMERIC) TO service_role;

CREATE OR REPLACE VIEW api.voice_model_catalog
WITH (security_invoker = true) AS
SELECT key, type, name, is_default, is_enabled, created_at
FROM public.voice_model_catalog
WHERE is_enabled = true;

GRANT SELECT ON api.voice_model_catalog TO authenticated;

