import { describe, it, expect, vi } from "vitest";
import {
  subscribeToInvalidations,
  parseInvalidationPayload,
} from "./realtimeService";
import type { SupabaseClient, RealtimeChannel } from "@supabase/supabase-js";

describe("Realtime Invalidation Service Seam", () => {
  describe("parseInvalidationPayload", () => {
    it("parses valid task invalidation payload revealing only resource, id, operation, and optional parentId", () => {
      const payload = {
        resource: "task",
        id: "550e8400-e29b-41d4-a716-446655440001",
        operation: "created",
        parentId: "550e8400-e29b-41d4-a716-446655440000",
      };

      const parsed = parseInvalidationPayload(payload);
      expect(parsed).toEqual({
        resource: "task",
        id: "550e8400-e29b-41d4-a716-446655440001",
        operation: "created",
        parentId: "550e8400-e29b-41d4-a716-446655440000",
        taskId: null,
      });
    });

    it("parses valid comment invalidation payload with necessary parent taskId", () => {
      const payload = {
        resource: "comment",
        id: "550e8400-e29b-41d4-a716-446655440021",
        operation: "created",
        taskId: "550e8400-e29b-41d4-a716-446655440001",
      };

      const parsed = parseInvalidationPayload(payload);
      expect(parsed).toEqual({
        resource: "comment",
        id: "550e8400-e29b-41d4-a716-446655440021",
        operation: "created",
        parentId: null,
        taskId: "550e8400-e29b-41d4-a716-446655440001",
      });
    });

    it("parses valid label invalidation payload", () => {
      const payload = {
        resource: "label",
        id: "550e8400-e29b-41d4-a716-446655440011",
        operation: "updated",
      };

      const parsed = parseInvalidationPayload(payload);
      expect(parsed).toEqual({
        resource: "label",
        id: "550e8400-e29b-41d4-a716-446655440011",
        operation: "updated",
        parentId: null,
        taskId: null,
      });
    });

    it("strips extraneous sensitive fields (e.g. title, notes, email) from event payload", () => {
      const payloadWithLeak = {
        resource: "task",
        id: "550e8400-e29b-41d4-a716-446655440001",
        operation: "updated",
        title: "Secret Task Title",
        description: "Confidential Notes",
        email: "operator@example.com",
      };

      const parsed = parseInvalidationPayload(payloadWithLeak);
      expect(parsed).toEqual({
        resource: "task",
        id: "550e8400-e29b-41d4-a716-446655440001",
        operation: "updated",
        parentId: null,
        taskId: null,
      });
      expect(parsed).not.toHaveProperty("title");
      expect(parsed).not.toHaveProperty("description");
      expect(parsed).not.toHaveProperty("email");
    });

    it("returns null for invalid or non-object payloads", () => {
      expect(parseInvalidationPayload(null)).toBeNull();
      expect(parseInvalidationPayload(undefined)).toBeNull();
      expect(parseInvalidationPayload("invalid string")).toBeNull();
      expect(
        parseInvalidationPayload({ resource: "unknown", id: "123" }),
      ).toBeNull();
      expect(parseInvalidationPayload({ resource: "task" })).toBeNull();
    });
  });

  describe("subscribeToInvalidations", () => {
    it("subscribes to the operator-authorized channel and routes broadcast invalidation events", () => {
      let broadcastCallback:
        ((event: { payload: unknown }) => void) | undefined;

      const mockChannel = {
        on: vi
          .fn()
          .mockImplementation(
            (
              type: string,
              filter: Record<string, unknown>,
              cb: (e: { payload: unknown }) => void,
            ) => {
              if (type === "broadcast" && filter.event === "invalidate") {
                broadcastCallback = cb;
              }
              return mockChannel;
            },
          ),
        subscribe: vi
          .fn()
          .mockImplementation((cb: (status: string) => void) => {
            cb("SUBSCRIBED");
            return mockChannel;
          }),
        unsubscribe: vi.fn().mockResolvedValue("ok"),
      } as unknown as RealtimeChannel;

      const mockClient = {
        channel: vi.fn().mockReturnValue(mockChannel),
        removeChannel: vi.fn().mockResolvedValue("ok"),
      } as unknown as SupabaseClient;

      const receivedEvents: unknown[] = [];
      const onInvalidate = vi.fn((event) => {
        receivedEvents.push(event);
      });

      const subscription = subscribeToInvalidations({
        client: mockClient,
        operatorId: "operator-1-uuid",
        onInvalidate,
        onReconnect: vi.fn(),
      });

      expect(mockClient.channel).toHaveBeenCalledWith(
        "operator:operator-1-uuid",
        {
          config: {
            private: true,
          },
        },
      );
      expect(mockChannel.on).toHaveBeenCalled();
      expect(mockChannel.subscribe).toHaveBeenCalled();

      // Trigger a task invalidation event
      if (broadcastCallback) {
        broadcastCallback({
          payload: {
            resource: "task",
            id: "550e8400-e29b-41d4-a716-446655440001",
            operation: "updated",
          },
        });
      }

      expect(onInvalidate).toHaveBeenCalledTimes(1);
      expect(receivedEvents[0]).toEqual({
        resource: "task",
        id: "550e8400-e29b-41d4-a716-446655440001",
        operation: "updated",
        parentId: null,
        taskId: null,
      });

      // Cleanup
      void subscription.unsubscribe();
      expect(mockClient.removeChannel).toHaveBeenCalledWith(mockChannel);
    });

    it("triggers onReconnect callback after reconnecting from a disconnected state", () => {
      let statusCallback: ((status: string) => void) | undefined;

      const mockChannel = {
        on: vi.fn().mockReturnThis(),
        subscribe: vi
          .fn()
          .mockImplementation((cb: (status: string) => void) => {
            statusCallback = cb;
            cb("SUBSCRIBED");
            return mockChannel;
          }),
        unsubscribe: vi.fn().mockResolvedValue("ok"),
      } as unknown as RealtimeChannel;

      const mockClient = {
        channel: vi.fn().mockReturnValue(mockChannel),
        removeChannel: vi.fn().mockResolvedValue("ok"),
      } as unknown as SupabaseClient;

      const onInvalidate = vi.fn();
      const onReconnect = vi.fn();

      subscribeToInvalidations({
        client: mockClient,
        operatorId: "operator-1-uuid",
        onInvalidate,
        onReconnect,
      });

      // Initial subscription does not trigger onReconnect
      expect(onReconnect).not.toHaveBeenCalled();

      // Channel disconnects
      if (statusCallback) {
        statusCallback("CLOSED");
      }
      expect(onReconnect).not.toHaveBeenCalled();

      // Channel reconnects
      if (statusCallback) {
        statusCallback("SUBSCRIBED");
      }
      expect(onReconnect).toHaveBeenCalledTimes(1);
    });
  });
});
