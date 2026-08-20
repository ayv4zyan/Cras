import type { SupabaseClient, RealtimeChannel } from "@supabase/supabase-js";

export type InvalidationResourceType = "task" | "label" | "comment";
export type InvalidationOperationType = "created" | "updated" | "deleted";

export interface InvalidationPayload {
  readonly resource: InvalidationResourceType;
  readonly id: string;
  readonly operation: InvalidationOperationType;
  readonly parentId: string | null;
  readonly taskId: string | null;
}

/**
 * Validates and sanitizes a raw event payload, ensuring only resource identity,
 * operation, and necessary parent identity are extracted without revealing
 * private domain content (titles, notes, descriptions, etc.).
 */
export function parseInvalidationPayload(
  data: unknown,
): InvalidationPayload | null {
  if (!data || typeof data !== "object") {
    return null;
  }

  const raw = data as Record<string, unknown>;

  const resource = raw.resource;
  if (resource !== "task" && resource !== "label" && resource !== "comment") {
    return null;
  }

  const id = raw.id;
  if (typeof id !== "string" || id.trim().length === 0) {
    return null;
  }

  const operation = raw.operation;
  if (
    operation !== "created" &&
    operation !== "updated" &&
    operation !== "deleted"
  ) {
    return null;
  }

  const parentId = typeof raw.parentId === "string" ? raw.parentId : null;
  const taskId = typeof raw.taskId === "string" ? raw.taskId : null;

  return {
    resource,
    id,
    operation,
    parentId,
    taskId,
  };
}

export interface RealtimeSubscriptionOptions {
  readonly client: SupabaseClient;
  readonly operatorId: string;
  readonly onInvalidate: (payload: InvalidationPayload) => void;
  readonly onReconnect?: () => void;
}

export interface RealtimeSubscription {
  readonly unsubscribe: () => Promise<void> | void;
}

/**
 * Subscribes to the Operator-authorized Realtime channel to receive
 * targeted domain invalidation events and trigger precise cache refetches.
 */
export function subscribeToInvalidations({
  client,
  operatorId,
  onInvalidate,
  onReconnect,
}: RealtimeSubscriptionOptions): RealtimeSubscription {
  if (!client || typeof client.channel !== "function") {
    return {
      unsubscribe: () => {},
    };
  }

  const channelName = `operator:${operatorId}`;
  let isInitialConnect = true;
  let wasDisconnected = false;

  const channel: RealtimeChannel = client.channel(channelName, {
    config: {
      private: true,
    },
  });
  if (!channel || typeof channel.on !== "function") {
    return {
      unsubscribe: () => {},
    };
  }

  channel
    .on(
      "broadcast",
      { event: "invalidate" },
      (event: { payload?: unknown }) => {
        const parsed = parseInvalidationPayload(event.payload);
        if (parsed) {
          onInvalidate(parsed);
        }
      },
    )
    .subscribe((status: string) => {
      if (status === "SUBSCRIBED") {
        if (!isInitialConnect && wasDisconnected) {
          onReconnect?.();
        }
        isInitialConnect = false;
        wasDisconnected = false;
      } else if (
        status === "CLOSED" ||
        status === "CHANNEL_ERROR" ||
        status === "TIMED_OUT"
      ) {
        wasDisconnected = true;
      }
    });

  return {
    unsubscribe: () => {
      if (typeof client.removeChannel === "function") {
        return client.removeChannel(channel);
      }
      if (typeof channel.unsubscribe === "function") {
        return channel.unsubscribe();
      }
    },
  };
}
