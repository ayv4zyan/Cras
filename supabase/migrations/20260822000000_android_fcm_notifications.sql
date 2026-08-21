-- Migration: Deliver Android Notifications through FCM (issue #56)
--
-- Android installations bind an Operator-bound FCM registration token as
-- their endpoint (ADR 0007). Registration tokens are opaque strings, not
-- HTTPS URLs, so register_or_update_installation accepts them without the
-- Web Push origin policy applied to browser subscription URLs. Server
-- credentials stay in Edge Function secrets; clients only ever hold public
-- configuration.

CREATE OR REPLACE FUNCTION api.register_or_update_installation(
    p_id UUID DEFAULT NULL,
    p_platform TEXT DEFAULT 'web',
    p_local_enabled BOOLEAN DEFAULT true,
    p_permission_state TEXT DEFAULT 'prompt',
    p_endpoint TEXT DEFAULT NULL,
    p_p256dh TEXT DEFAULT NULL,
    p_auth TEXT DEFAULT NULL,
    p_installation_timezone TEXT DEFAULT 'UTC',
    p_clear_subscription BOOLEAN DEFAULT false
)
RETURNS public.installations
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $$
#variable_conflict error
DECLARE
    v_inst public.installations;
    v_inst_id UUID;
    v_endpoint_host TEXT;
BEGIN
    IF p_platform NOT IN ('web', 'android') THEN
        RAISE EXCEPTION 'Invalid platform: %', p_platform;
    END IF;

    -- Web Push endpoints must be HTTPS capability URLs on a supported push
    -- service origin; Android endpoints are opaque FCM registration tokens.
    -- Pinning the origin narrows the destinations the Notification worker can
    -- be pointed at, but it is not a complete SSRF defense: DNS resolution
    -- and redirect targets remain the outbound client's responsibility.
    IF p_platform = 'web' AND p_endpoint IS NOT NULL THEN
        v_endpoint_host := substring(p_endpoint FROM '^https://([^/?#]+)');
        IF v_endpoint_host IS NULL
           OR position('@' IN v_endpoint_host) > 0
           OR v_endpoint_host NOT IN (
               'fcm.googleapis.com',
               'updates.push.services.mozilla.com',
               'web.push.apple.com'
           ) THEN
            RAISE EXCEPTION 'Push endpoint origin is not a supported Web Push service';
        END IF;
    END IF;

    IF p_endpoint IS NOT NULL AND btrim(p_endpoint) = '' THEN
        RAISE EXCEPTION 'Push endpoint must not be blank';
    END IF;

    v_inst_id := COALESCE(p_id, gen_random_uuid());

    -- If another installation of this operator holds this endpoint, rotate/reassign cleanly
    IF p_endpoint IS NOT NULL THEN
        UPDATE public.installations
        SET endpoint = NULL, p256dh = NULL, auth = NULL, is_active = false, updated_at = now()
        WHERE operator_id = auth.uid()
          AND endpoint = p_endpoint
          AND id <> v_inst_id;
    END IF;

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
        p_platform,
        COALESCE(p_local_enabled, true),
        COALESCE(p_permission_state, 'prompt'),
        CASE WHEN p_clear_subscription THEN NULL ELSE p_endpoint END,
        CASE WHEN p_clear_subscription THEN NULL ELSE p_p256dh END,
        CASE WHEN p_clear_subscription THEN NULL ELSE p_auth END,
        COALESCE(NULLIF(trim(p_installation_timezone), ''), 'UTC'),
        now(),
        true,
        now()
    )
    ON CONFLICT (id, operator_id) DO UPDATE SET
        platform = EXCLUDED.platform,
        local_enabled = EXCLUDED.local_enabled,
        permission_state = EXCLUDED.permission_state,
        endpoint = CASE
            WHEN p_clear_subscription THEN NULL
            WHEN EXCLUDED.endpoint IS NOT NULL THEN EXCLUDED.endpoint
            ELSE public.installations.endpoint
        END,
        p256dh = CASE
            WHEN p_clear_subscription THEN NULL
            WHEN EXCLUDED.endpoint IS NOT NULL THEN EXCLUDED.p256dh
            ELSE public.installations.p256dh
        END,
        auth = CASE
            WHEN p_clear_subscription THEN NULL
            WHEN EXCLUDED.endpoint IS NOT NULL THEN EXCLUDED.auth
            ELSE public.installations.auth
        END,
        installation_timezone = EXCLUDED.installation_timezone,
        timezone_observed_at = now(),
        is_active = true,
        updated_at = now()
    RETURNING * INTO v_inst;

    RETURN v_inst;
END;
$$;
