import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  resolveEffectiveTimedPlanType,
  getCachedEffectiveTimedPlanType,
  setCachedEffectiveTimedPlanType,
  clearCachedEffectiveTimedPlanType,
  fetchEffectiveTimedPlanType,
  updateOperatorTimedPlanType,
} from "./settingsService";
import type { SupabaseClient } from "@supabase/supabase-js";

describe("Settings & Deployment Configuration Seam", () => {
  beforeEach(() => {
    clearCachedEffectiveTimedPlanType();
    vi.restoreAllMocks();
  });

  describe("resolveEffectiveTimedPlanType", () => {
    it("returns Operator override when explicit 'floating' is set", () => {
      const type = resolveEffectiveTimedPlanType(
        { default_timed_plan_type: "floating" },
        { default_timed_plan_type: "instant" },
      );
      expect(type).toBe("floating");
    });

    it("returns Operator override when explicit 'instant' is set", () => {
      const type = resolveEffectiveTimedPlanType(
        { default_timed_plan_type: "instant" },
        { default_timed_plan_type: "floating" },
      );
      expect(type).toBe("instant");
    });

    it("inherits Deployment configuration when Operator override is null", () => {
      const type = resolveEffectiveTimedPlanType(
        { default_timed_plan_type: null },
        { default_timed_plan_type: "floating" },
      );
      expect(type).toBe("floating");
    });

    it("inherits Deployment configuration when Operator settings row is missing", () => {
      const type = resolveEffectiveTimedPlanType(null, {
        default_timed_plan_type: "floating",
      });
      expect(type).toBe("floating");
    });

    it("falls back to 'instant' when neither Operator nor DeploymentConfig has a value", () => {
      const type = resolveEffectiveTimedPlanType(null, null);
      expect(type).toBe("instant");
    });
  });

  describe("Caching & Offline Fallback", () => {
    it("falls back to 'instant' when cache is empty", () => {
      expect(getCachedEffectiveTimedPlanType()).toBe("instant");
    });

    it("caches and retrieves the effective default", () => {
      setCachedEffectiveTimedPlanType("floating");
      expect(getCachedEffectiveTimedPlanType()).toBe("floating");

      setCachedEffectiveTimedPlanType("instant");
      expect(getCachedEffectiveTimedPlanType()).toBe("instant");
    });
  });

  describe("fetchEffectiveTimedPlanType", () => {
    it("fetches settings and deployment config, caches effective default, and returns it", async () => {
      const mockSettingsSelect = vi.fn().mockReturnValue({
        maybeSingle: vi.fn().mockResolvedValue({
          data: { default_timed_plan_type: "floating" },
          error: null,
        }),
      });

      const mockDeployConfigSelect = vi.fn().mockReturnValue({
        maybeSingle: vi.fn().mockResolvedValue({
          data: { default_timed_plan_type: "instant" },
          error: null,
        }),
      });

      const mockFrom = vi.fn().mockImplementation((table: string) => {
        if (table === "settings") {
          return { select: mockSettingsSelect };
        }
        if (table === "deployment_config") {
          return { select: mockDeployConfigSelect };
        }
        return {};
      });

      const mockClient = {
        from: mockFrom,
      } as unknown as SupabaseClient;

      const result = await fetchEffectiveTimedPlanType(mockClient);
      expect(result).toBe("floating");
      expect(getCachedEffectiveTimedPlanType()).toBe("floating");
    });

    it("falls back to cached value when network fetch fails", async () => {
      setCachedEffectiveTimedPlanType("floating");

      const mockFrom = vi.fn().mockImplementation(() => {
        throw new Error("Network offline");
      });

      const mockClient = {
        from: mockFrom,
      } as unknown as SupabaseClient;

      const result = await fetchEffectiveTimedPlanType(mockClient);
      expect(result).toBe("floating");
    });
  });

  describe("updateOperatorTimedPlanType", () => {
    it("upserts operator settings and updates cache", async () => {
      const mockUpsert = vi.fn().mockResolvedValue({ error: null });
      const mockFrom = vi.fn().mockReturnValue({ upsert: mockUpsert });
      const mockClient = {
        from: mockFrom,
        auth: {
          getUser: vi.fn().mockResolvedValue({
            data: { user: { id: "user-123" } },
          }),
        },
      } as unknown as SupabaseClient;

      await updateOperatorTimedPlanType(mockClient, "floating");

      expect(mockFrom).toHaveBeenCalledWith("settings");
      expect(mockUpsert).toHaveBeenCalledWith({
        default_timed_plan_type: "floating",
      });
      expect(getCachedEffectiveTimedPlanType()).toBe("floating");
    });
  });
});
