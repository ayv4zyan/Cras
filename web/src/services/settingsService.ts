import type { SupabaseClient } from "@supabase/supabase-js";
import type { TimedPlanType } from "./temporalService";

export interface OperatorSettings {
  readonly operator_id?: string;
  readonly default_timed_plan_type?: TimedPlanType | null;
  readonly missed_delivery_enabled?: boolean;
}

export interface DeploymentConfig {
  readonly id?: number;
  readonly default_timed_plan_type?: TimedPlanType;
  readonly voice_enabled?: boolean;
}

const CACHED_TIMED_PLAN_TYPE_KEY = "cras_effective_default_timed_plan_type";

// In-memory fallback if localStorage is unavailable
let inMemoryCachedType: TimedPlanType = "instant";

/**
 * Resolves the effective default timed plan type:
 * 1. An explicit Operator override (Instant/Floating) wins.
 * 2. If Operator override is null or missing, inherit Deployment configuration.
 * 3. Fallback to 'instant'.
 */
export function resolveEffectiveTimedPlanType(
  settings?: OperatorSettings | null,
  deploymentConfig?: DeploymentConfig | null,
): TimedPlanType {
  if (
    settings?.default_timed_plan_type === "instant" ||
    settings?.default_timed_plan_type === "floating"
  ) {
    return settings.default_timed_plan_type;
  }

  if (
    deploymentConfig?.default_timed_plan_type === "instant" ||
    deploymentConfig?.default_timed_plan_type === "floating"
  ) {
    return deploymentConfig.default_timed_plan_type;
  }

  return "instant";
}

/**
 * Retrieves the cached effective default timed plan type from local storage.
 * Falls back to 'instant' if nothing was cached.
 */
export function getCachedEffectiveTimedPlanType(): TimedPlanType {
  try {
    if (typeof localStorage !== "undefined") {
      const cached = localStorage.getItem(CACHED_TIMED_PLAN_TYPE_KEY);
      if (cached === "instant" || cached === "floating") {
        return cached;
      }
    }
  } catch {
    // Local storage access error
  }
  return inMemoryCachedType;
}

/**
 * Caches the effective default timed plan type in local storage.
 */
export function setCachedEffectiveTimedPlanType(type: TimedPlanType): void {
  inMemoryCachedType = type;
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(CACHED_TIMED_PLAN_TYPE_KEY, type);
    }
  } catch {
    // Ignore storage write errors
  }
}

/**
 * Clears cached timed plan type (useful in tests).
 */
export function clearCachedEffectiveTimedPlanType(): void {
  inMemoryCachedType = "instant";
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.removeItem(CACHED_TIMED_PLAN_TYPE_KEY);
    }
  } catch {
    // Ignore
  }
}

/**
 * Fetches the operator's settings from Supabase.
 */
export async function fetchOperatorSettings(
  client: SupabaseClient,
): Promise<OperatorSettings | null> {
  const { data, error } = await client
    .from("settings")
    .select("operator_id, default_timed_plan_type, missed_delivery_enabled")
    .maybeSingle();

  if (error) {
    throw new Error(`Failed to fetch operator settings: ${error.message}`);
  }

  return data as OperatorSettings | null;
}

/**
 * Fetches deployment configuration from Supabase.
 */
export async function fetchDeploymentConfig(
  client: SupabaseClient,
): Promise<DeploymentConfig | null> {
  const { data, error } = await client
    .from("deployment_config")
    .select("id, default_timed_plan_type, voice_enabled")
    .maybeSingle();

  if (error) {
    throw new Error(
      `Failed to fetch deployment configuration: ${error.message}`,
    );
  }

  return data as DeploymentConfig | null;
}

/**
 * Fetches the effective timed plan type by combining operator settings and deployment config,
 * caches it locally, and falls back to cached value on network/offline failure.
 */
export async function fetchEffectiveTimedPlanType(
  client: SupabaseClient,
): Promise<TimedPlanType> {
  try {
    const [settingsResult, deployConfigResult] = await Promise.allSettled([
      fetchOperatorSettings(client),
      fetchDeploymentConfig(client),
    ]);

    const settings =
      settingsResult.status === "fulfilled" ? settingsResult.value : null;
    const deploymentConfig =
      deployConfigResult.status === "fulfilled"
        ? deployConfigResult.value
        : null;

    // Cache only a fully resolved default; otherwise keep the previous cache.
    if (
      settingsResult.status === "rejected" ||
      deployConfigResult.status === "rejected"
    ) {
      return getCachedEffectiveTimedPlanType();
    }

    const effective = resolveEffectiveTimedPlanType(settings, deploymentConfig);
    setCachedEffectiveTimedPlanType(effective);
    return effective;
  } catch {
    return getCachedEffectiveTimedPlanType();
  }
}

/**
 * Updates or sets the Operator's default timed plan type in settings.
 */
export async function updateOperatorTimedPlanType(
  client: SupabaseClient,
  type: TimedPlanType | null,
): Promise<void> {
  const { error } = await client.from("settings").upsert({
    default_timed_plan_type: type,
  });

  if (error) {
    throw new Error(
      `Failed to update operator settings: ${error.message} (${error.code})`,
    );
  }

  if (type) {
    setCachedEffectiveTimedPlanType(type);
  } else {
    clearCachedEffectiveTimedPlanType();
    await fetchEffectiveTimedPlanType(client);
  }
}
