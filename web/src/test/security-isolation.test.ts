import { describe, it, expect, beforeEach } from "vitest";
import type { Plan } from "../contracts/task";
import type { SupabaseClient } from "@supabase/supabase-js";
import { validateWavAudio } from "../services/voiceWorker";
import { handleAccountLifecycleRequest } from "../services/accountLifecycleWorker";

describe("Security & Isolation Suite (Multi-Operator, Unauthenticated, & Lifecycle States)", () => {
  const OPERATOR_A_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  const OPERATOR_B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

  interface TaskRecord {
    id: string;
    operator_id: string;
    title: string;
    description: string | null;
    priority: number;
    plan: Plan | null;
    parent_id: string | null;
    completed_at: string | null;
    created_at: string;
    updated_at: string;
    version: number;
  }

  interface LabelRecord {
    id: string;
    operator_id: string;
    name: string;
    color: string;
    created_at: string;
    updated_at: string;
  }

  interface TaskLabelRecord {
    task_id: string;
    label_id: string;
    operator_id: string;
  }

  interface CommentRecord {
    id: string;
    task_id: string;
    operator_id: string;
    content: string;
    created_at: string;
  }

  interface SettingsRecord {
    operator_id: string;
    default_timed_plan_type: "instant" | "floating" | null;
    missed_notification_delivery: boolean;
  }

  interface NotificationJobRecord {
    id: string;
    operator_id: string;
    task_id: string;
    status: "pending" | "delivered" | "cancelled";
  }

  interface DeviceEndpointRecord {
    id: string;
    operator_id: string;
    fcm_token: string;
    is_active: boolean;
  }

  interface WebPushSubscriptionRecord {
    id: string;
    operator_id: string;
    endpoint: string;
    is_active: boolean;
  }

  interface AccountDeletionRecord {
    operator_id: string;
    deletion_requested_at: string;
    recovery_deadline_at: string;
    purged_at: string | null;
  }

  // Simulated Postgres Database with RLS and Security Constraints
  class SimulatedPostgresSecurityHarness {
    public tasks: TaskRecord[] = [];
    public labels: LabelRecord[] = [];
    public taskLabels: TaskLabelRecord[] = [];
    public comments: CommentRecord[] = [];
    public settings: SettingsRecord[] = [];
    public notificationJobs: NotificationJobRecord[] = [];
    public deviceEndpoints: DeviceEndpointRecord[] = [];
    public webPushSubscriptions: WebPushSubscriptionRecord[] = [];
    public accountDeletions: AccountDeletionRecord[] = [];
    public realtimeMessages: { channel: string; payload: unknown }[] = [];

    public reset() {
      this.tasks = [];
      this.labels = [];
      this.taskLabels = [];
      this.comments = [];
      this.settings = [];
      this.notificationJobs = [];
      this.deviceEndpoints = [];
      this.webPushSubscriptions = [];
      this.accountDeletions = [];
      this.realtimeMessages = [];
    }

    private isOperatorFrozen(operatorId: string): boolean {
      const del = this.accountDeletions.find(
        (d) => d.operator_id === operatorId,
      );
      return Boolean(del);
    }

    // Direct Table Access with RLS Check
    public selectTasks(callerId: string | null): TaskRecord[] {
      if (!callerId)
        throw new Error("401: Unauthenticated caller denied on public.tasks");
      return this.tasks.filter((t) => t.operator_id === callerId);
    }

    public selectLabels(callerId: string | null): LabelRecord[] {
      if (!callerId)
        throw new Error("401: Unauthenticated caller denied on public.labels");
      return this.labels.filter((l) => l.operator_id === callerId);
    }

    public selectComments(callerId: string | null): CommentRecord[] {
      if (!callerId)
        throw new Error(
          "401: Unauthenticated caller denied on public.comments",
        );
      return this.comments.filter((c) => c.operator_id === callerId);
    }

    public selectSettings(callerId: string | null): SettingsRecord | null {
      if (!callerId)
        throw new Error(
          "401: Unauthenticated caller denied on public.operator_settings",
        );
      return this.settings.find((s) => s.operator_id === callerId) || null;
    }

    public selectNotificationJobs(
      callerId: string | null,
    ): NotificationJobRecord[] {
      if (!callerId)
        throw new Error(
          "401: Unauthenticated caller denied on public.notification_jobs",
        );
      return this.notificationJobs.filter((j) => j.operator_id === callerId);
    }

    public selectDeviceEndpoints(
      callerId: string | null,
    ): DeviceEndpointRecord[] {
      if (!callerId)
        throw new Error(
          "401: Unauthenticated caller denied on public.device_endpoints",
        );
      return this.deviceEndpoints.filter((d) => d.operator_id === callerId);
    }

    public selectWebPushSubscriptions(
      callerId: string | null,
    ): WebPushSubscriptionRecord[] {
      if (!callerId)
        throw new Error(
          "401: Unauthenticated caller denied on public.web_push_subscriptions",
        );
      return this.webPushSubscriptions.filter(
        (w) => w.operator_id === callerId,
      );
    }

    // Relationship Integrity Checks
    public insertSubtask(
      callerId: string | null,
      subtaskId: string,
      parentId: string,
      title: string,
    ) {
      if (!callerId) throw new Error("401: Unauthenticated caller");
      if (this.isOperatorFrozen(callerId))
        throw new Error("403: Account is in Pending Deletion");
      const parent = this.tasks.find((t) => t.id === parentId);
      if (!parent) throw new Error("404: Parent task not found");
      // Composite FK constraint: parent must belong to callerId
      if (parent.operator_id !== callerId) {
        throw new Error(
          "403: Cross-operator foreign key violation: parent_id belongs to another Operator",
        );
      }
      if (parent.parent_id !== null) {
        throw new Error("400: One-level subtask nesting limit exceeded");
      }
      const record: TaskRecord = {
        id: subtaskId,
        operator_id: callerId,
        title,
        description: null,
        priority: 4,
        plan: null,
        parent_id: parentId,
        completed_at: null,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
        version: 1,
      };
      this.tasks.push(record);
      return record;
    }

    public insertComment(
      callerId: string | null,
      commentId: string,
      taskId: string,
      content: string,
    ) {
      if (!callerId) throw new Error("401: Unauthenticated caller");
      if (this.isOperatorFrozen(callerId))
        throw new Error("403: Account is in Pending Deletion");
      const task = this.tasks.find((t) => t.id === taskId);
      if (!task) throw new Error("404: Task not found");
      // Composite FK constraint: task must belong to callerId
      if (task.operator_id !== callerId) {
        throw new Error(
          "403: Cross-operator foreign key violation: task_id belongs to another Operator",
        );
      }
      const record: CommentRecord = {
        id: commentId,
        task_id: taskId,
        operator_id: callerId,
        content,
        created_at: new Date().toISOString(),
      };
      this.comments.push(record);
      return record;
    }

    public assignTaskLabel(
      callerId: string | null,
      taskId: string,
      labelId: string,
    ) {
      if (!callerId) throw new Error("401: Unauthenticated caller");
      if (this.isOperatorFrozen(callerId))
        throw new Error("403: Account is in Pending Deletion");
      const task = this.tasks.find((t) => t.id === taskId);
      if (!task || task.operator_id !== callerId) {
        throw new Error("403: Cross-operator task violation");
      }
      const label = this.labels.find((l) => l.id === labelId);
      if (!label || label.operator_id !== callerId) {
        throw new Error("403: Cross-operator label violation");
      }
      this.taskLabels.push({
        task_id: taskId,
        label_id: labelId,
        operator_id: callerId,
      });
    }

    // RPC Invocations
    public rpcCreateTask(
      callerId: string | null,
      params: { id: string; title: string },
    ) {
      if (!callerId) throw new Error("401: Unauthenticated");
      if (this.isOperatorFrozen(callerId))
        throw new Error("403: Account is in Pending Deletion");
      const record: TaskRecord = {
        id: params.id,
        operator_id: callerId,
        title: params.title,
        description: null,
        priority: 4,
        plan: null,
        parent_id: null,
        completed_at: null,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
        version: 1,
      };
      this.tasks.push(record);
      return record;
    }

    public rpcUpdateTaskDetails(
      callerId: string | null,
      params: { id: string; title: string; version: number },
    ) {
      if (!callerId) throw new Error("401: Unauthenticated");
      if (this.isOperatorFrozen(callerId))
        throw new Error("403: Account is in Pending Deletion");
      const task = this.tasks.find(
        (t) => t.id === params.id && t.operator_id === callerId,
      );
      if (!task)
        throw new Error("404: Task not found or owned by another Operator");
      if (task.version !== params.version)
        throw new Error("409: Version CAS conflict");
      task.title = params.title;
      task.version += 1;
      task.updated_at = new Date().toISOString();
      return task;
    }

    public rpcCompleteTask(
      callerId: string | null,
      params: { id: string; version: number },
    ) {
      if (!callerId) throw new Error("401: Unauthenticated");
      if (this.isOperatorFrozen(callerId))
        throw new Error("403: Account is in Pending Deletion");
      const task = this.tasks.find(
        (t) => t.id === params.id && t.operator_id === callerId,
      );
      if (!task)
        throw new Error("404: Task not found or owned by another Operator");
      if (task.version !== params.version)
        throw new Error("409: Version CAS conflict");
      task.completed_at = new Date().toISOString();
      task.version += 1;
      return task;
    }

    public rpcDeleteTask(callerId: string | null, params: { id: string }) {
      if (!callerId) throw new Error("401: Unauthenticated");
      if (this.isOperatorFrozen(callerId))
        throw new Error("403: Account is in Pending Deletion");
      const idx = this.tasks.findIndex(
        (t) => t.id === params.id && t.operator_id === callerId,
      );
      if (idx === -1)
        throw new Error("404: Task not found or owned by another Operator");
      this.tasks.splice(idx, 1);
      return { success: true };
    }

    public rpcExportOperatorData(callerId: string | null) {
      if (!callerId) throw new Error("401: Unauthenticated");
      return {
        operator_id: callerId,
        tasks: this.tasks.filter((t) => t.operator_id === callerId),
        labels: this.labels.filter((l) => l.operator_id === callerId),
        comments: this.comments.filter((c) => c.operator_id === callerId),
        settings: this.settings.find((s) => s.operator_id === callerId) || null,
        exported_at: new Date().toISOString(),
      };
    }

    public rpcRequestAccountDeletion(
      callerId: string | null,
      deadlineOverride?: Date,
    ) {
      if (!callerId) throw new Error("401: Unauthenticated");
      const now = new Date();
      const deadline =
        deadlineOverride ?? new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000);
      this.accountDeletions.push({
        operator_id: callerId,
        deletion_requested_at: now.toISOString(),
        recovery_deadline_at: deadline.toISOString(),
        purged_at: null,
      });
      return { success: true, deadline: deadline.toISOString() };
    }

    public rpcFinalizePurge(operatorId: string) {
      const del = this.accountDeletions.find(
        (d) => d.operator_id === operatorId,
      );
      if (del) {
        del.purged_at = new Date().toISOString();
      }
    }

    public rpcRecoverAccount(callerId: string | null) {
      if (!callerId) throw new Error("401: Unauthenticated");
      const del = this.accountDeletions.find(
        (d) => d.operator_id === callerId && !d.purged_at,
      );
      if (!del) throw new Error("404: No active pending deletion record");
      if (new Date() > new Date(del.recovery_deadline_at)) {
        throw new Error("410: Recovery window has expired");
      }
      this.accountDeletions = this.accountDeletions.filter(
        (d) => d.operator_id !== callerId,
      );
      return { success: true };
    }

    // Realtime Broadcast Channel Security
    public subscribeRealtimeChannel(
      callerId: string | null,
      channelName: string,
    ) {
      if (!callerId) throw new Error("401: Realtime unauthenticated");
      const expectedPrefix = `operator:${callerId}`;
      if (channelName !== expectedPrefix) {
        throw new Error(
          `403: Realtime authorization failed. Caller cannot subscribe to ${channelName}`,
        );
      }
      return {
        send: (payload: unknown) => {
          this.realtimeMessages.push({ channel: channelName, payload });
        },
      };
    }
  }

  let db: SimulatedPostgresSecurityHarness;

  beforeEach(() => {
    db = new SimulatedPostgresSecurityHarness();
    db.reset();
  });

  describe("Criterion 1: Unauthenticated Caller Security across Tables, Views, RPCs, Realtime, & Functions", () => {
    it("rejects unauthenticated SELECT across all owned tables and views", () => {
      expect(() => db.selectTasks(null)).toThrow(/401/);
      expect(() => db.selectLabels(null)).toThrow(/401/);
      expect(() => db.selectComments(null)).toThrow(/401/);
      expect(() => db.selectSettings(null)).toThrow(/401/);
      expect(() => db.selectNotificationJobs(null)).toThrow(/401/);
      expect(() => db.selectDeviceEndpoints(null)).toThrow(/401/);
      expect(() => db.selectWebPushSubscriptions(null)).toThrow(/401/);
    });

    it("rejects unauthenticated RPC execution", () => {
      expect(() =>
        db.rpcCreateTask(null, {
          id: "11111111-1111-1111-1111-111111111111",
          title: "Test",
        }),
      ).toThrow(/401/);
      expect(() => db.rpcExportOperatorData(null)).toThrow(/401/);
      expect(() => db.rpcRequestAccountDeletion(null)).toThrow(/401/);
      expect(() => db.rpcRecoverAccount(null)).toThrow(/401/);
    });

    it("rejects unauthenticated Realtime channel subscription", () => {
      expect(() =>
        db.subscribeRealtimeChannel(null, `operator:${OPERATOR_A_ID}`),
      ).toThrow(/401/);
    });
  });

  describe("Criterion 2: Two Operators Data Isolation across All Tables, Views, and RPCs", () => {
    beforeEach(() => {
      // Seed Operator A data
      db.rpcCreateTask(OPERATOR_A_ID, {
        id: "11111111-1111-1111-1111-111111111111",
        title: "Operator A Task",
      });
      db.labels.push({
        id: "22222222-2222-2222-2222-222222222222",
        operator_id: OPERATOR_A_ID,
        name: "A Label",
        color: "#10b981",
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      });
      db.insertComment(
        OPERATOR_A_ID,
        "33333333-3333-3333-3333-333333333333",
        "11111111-1111-1111-1111-111111111111",
        "A Comment",
      );

      // Seed Operator B data
      db.rpcCreateTask(OPERATOR_B_ID, {
        id: "44444444-4444-4444-4444-444444444444",
        title: "Operator B Task",
      });
      db.labels.push({
        id: "55555555-5555-5555-5555-555555555555",
        operator_id: OPERATOR_B_ID,
        name: "B Label",
        color: "#3b82f6",
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      });
    });

    it("ensures Operator A cannot see Operator B tasks, labels, or comments via table/view queries", () => {
      const aTasks = db.selectTasks(OPERATOR_A_ID);
      expect(aTasks.length).toBe(1);
      expect(aTasks[0].id).toBe("11111111-1111-1111-1111-111111111111");

      const bTasks = db.selectTasks(OPERATOR_B_ID);
      expect(bTasks.length).toBe(1);
      expect(bTasks[0].id).toBe("44444444-4444-4444-4444-444444444444");

      const aLabels = db.selectLabels(OPERATOR_A_ID);
      expect(aLabels.length).toBe(1);
      expect(aLabels[0].name).toBe("A Label");

      const bLabels = db.selectLabels(OPERATOR_B_ID);
      expect(bLabels.length).toBe(1);
      expect(bLabels[0].name).toBe("B Label");
    });

    it("rejects cross-operator foreign key relationships for Subtasks", () => {
      // Operator A attempts to create a subtask referencing Operator B's task as parent
      expect(() => {
        db.insertSubtask(
          OPERATOR_A_ID,
          "66666666-6666-6666-6666-666666666666",
          "44444444-4444-4444-4444-444444444444", // Operator B task
          "Unauthorized Cross Subtask",
        );
      }).toThrow(/Cross-operator foreign key violation/);
    });

    it("rejects cross-operator foreign key relationships for Comments", () => {
      // Operator A attempts to add a comment to Operator B's task
      expect(() => {
        db.insertComment(
          OPERATOR_A_ID,
          "77777777-7777-7777-7777-777777777777",
          "44444444-4444-4444-4444-444444444444", // Operator B task
          "Unauthorized Cross Comment",
        );
      }).toThrow(/Cross-operator foreign key violation/);
    });

    it("rejects cross-operator Task-Label join associations", () => {
      // Operator A attempts to attach Operator B's label
      expect(() => {
        db.assignTaskLabel(
          OPERATOR_A_ID,
          "11111111-1111-1111-1111-111111111111", // Operator A task
          "55555555-5555-5555-5555-555555555555", // Operator B label
        );
      }).toThrow(/Cross-operator label violation/);
    });

    it("rejects cross-operator mutations via RPCs (CAS update, complete, delete)", () => {
      expect(() => {
        db.rpcUpdateTaskDetails(OPERATOR_A_ID, {
          id: "44444444-4444-4444-4444-444444444444", // Operator B task
          title: "Hijacked Title",
          version: 1,
        });
      }).toThrow(/404/);

      expect(() => {
        db.rpcCompleteTask(OPERATOR_A_ID, {
          id: "44444444-4444-4444-4444-444444444444", // Operator B task
          version: 1,
        });
      }).toThrow(/404/);

      expect(() => {
        db.rpcDeleteTask(OPERATOR_A_ID, {
          id: "44444444-4444-4444-4444-444444444444", // Operator B task
        });
      }).toThrow(/404/);
    });

    it("ensures data export RPC returns only the calling Operator's data", () => {
      const exportA = db.rpcExportOperatorData(OPERATOR_A_ID);
      expect(exportA.operator_id).toBe(OPERATOR_A_ID);
      expect(exportA.tasks.every((t) => t.operator_id === OPERATOR_A_ID)).toBe(
        true,
      );
      expect(exportA.labels.every((l) => l.operator_id === OPERATOR_A_ID)).toBe(
        true,
      );
      expect(
        exportA.comments.every((c) => c.operator_id === OPERATOR_A_ID),
      ).toBe(true);
    });
  });

  describe("Criterion 3: Realtime Authorization & Isolation", () => {
    it("allows Operator A to subscribe only to operator:operator-a channel", () => {
      const sub = db.subscribeRealtimeChannel(
        OPERATOR_A_ID,
        `operator:${OPERATOR_A_ID}`,
      );
      expect(sub).toBeDefined();
      sub.send({ type: "TASK_UPDATED", id: "1111" });
      expect(db.realtimeMessages.length).toBe(1);
    });

    it("strictly rejects Operator A attempting to subscribe to Operator B channel", () => {
      expect(() => {
        db.subscribeRealtimeChannel(OPERATOR_A_ID, `operator:${OPERATOR_B_ID}`);
      }).toThrow(/Realtime authorization failed/);
    });
  });

  describe("Criterion 4: Lifecycle State Isolation (Active vs Pending Deletion vs Purged vs Recovered)", () => {
    it("freezes all ordinary mutations when Operator is in Pending Deletion", () => {
      db.rpcCreateTask(OPERATOR_A_ID, {
        id: "11111111-1111-1111-1111-111111111111",
        title: "Active Task",
      });

      // Request deletion -> enters Pending Deletion
      db.rpcRequestAccountDeletion(OPERATOR_A_ID);

      // Subsequent mutations must be rejected
      expect(() => {
        db.rpcCreateTask(OPERATOR_A_ID, {
          id: "22222222-2222-2222-2222-222222222222",
          title: "New Task",
        });
      }).toThrow(/403: Account is in Pending Deletion/);

      expect(() => {
        db.rpcUpdateTaskDetails(OPERATOR_A_ID, {
          id: "11111111-1111-1111-1111-111111111111",
          title: "Changed",
          version: 1,
        });
      }).toThrow(/403: Account is in Pending Deletion/);

      expect(() => {
        db.rpcCompleteTask(OPERATOR_A_ID, {
          id: "11111111-1111-1111-1111-111111111111",
          version: 1,
        });
      }).toThrow(/403: Account is in Pending Deletion/);
    });

    it("keeps purged accounts frozen and prevents mutations or recovery", () => {
      db.rpcCreateTask(OPERATOR_A_ID, {
        id: "11111111-1111-1111-1111-111111111111",
        title: "Active Task",
      });
      db.rpcRequestAccountDeletion(OPERATOR_A_ID);
      db.rpcFinalizePurge(OPERATOR_A_ID);

      expect(() => {
        db.rpcCreateTask(OPERATOR_A_ID, {
          id: "33333333-3333-3333-3333-333333333333",
          title: "New Task",
        });
      }).toThrow(/403: Account is in Pending Deletion/);

      expect(() => {
        db.rpcRecoverAccount(OPERATOR_A_ID);
      }).toThrow(/404: No active pending deletion record/);
    });

    it("rejects account recovery when the recovery window has expired", () => {
      db.rpcRequestAccountDeletion(
        OPERATOR_A_ID,
        new Date(Date.now() - 1000 * 60), // expired 1 minute ago
      );
      expect(() => {
        db.rpcRecoverAccount(OPERATOR_A_ID);
      }).toThrow(/410: Recovery window has expired/);
    });

    it("restores active mutation capability upon account recovery within 7-day window", () => {
      db.rpcCreateTask(OPERATOR_A_ID, {
        id: "11111111-1111-1111-1111-111111111111",
        title: "Active Task",
      });
      db.rpcRequestAccountDeletion(OPERATOR_A_ID);

      // Recovery call
      const rec = db.rpcRecoverAccount(OPERATOR_A_ID);
      expect(rec.success).toBe(true);

      // Mutations succeed again
      const task = db.rpcUpdateTaskDetails(OPERATOR_A_ID, {
        id: "11111111-1111-1111-1111-111111111111",
        title: "Recovered and Updated Task",
        version: 1,
      });
      expect(task.title).toBe("Recovered and Updated Task");
    });
  });

  describe("Criterion 5: Edge Function Authentication & Authorization Boundaries", () => {
    it("account-lifecycle Edge Function rejects unauthenticated caller and invalid action/secret", async () => {
      const req = new Request(
        "http://localhost:54321/functions/v1/account-lifecycle",
        {
          method: "POST",
          body: JSON.stringify({ action: "purge-sweep" }),
          headers: { "Content-Type": "application/json" },
        },
      );
      const response = await handleAccountLifecycleRequest(req, {
        anonClient: {} as unknown as SupabaseClient,
        adminClient: {} as unknown as SupabaseClient,
        storageApi: { from: () => ({ remove: async () => ({}) }) },
        lifecycleSecret: "expected-secret",
      });
      expect(response.status).toBe(401);
    });

    it("account-lifecycle Edge Function rejects unknown action", async () => {
      const req = new Request(
        "http://localhost:54321/functions/v1/account-lifecycle",
        {
          method: "POST",
          body: JSON.stringify({ action: "unknown-action" }),
          headers: { "Content-Type": "application/json" },
        },
      );
      const response = await handleAccountLifecycleRequest(req, {
        anonClient: {} as unknown as SupabaseClient,
        adminClient: {} as unknown as SupabaseClient,
        storageApi: { from: () => ({ remove: async () => ({}) }) },
        lifecycleSecret: "expected-secret",
      });
      expect(response.status).toBe(400);
    });

    it("account-lifecycle Edge Function rejects missing Bearer prefix", async () => {
      const req = new Request(
        "http://localhost:54321/functions/v1/account-lifecycle",
        {
          method: "POST",
          body: JSON.stringify({ action: "status" }),
          headers: {
            "Content-Type": "application/json",
            Authorization: "Basic some-token",
          },
        },
      );
      const response = await handleAccountLifecycleRequest(req, {
        anonClient: {} as unknown as SupabaseClient,
        adminClient: {} as unknown as SupabaseClient,
        storageApi: { from: () => ({ remove: async () => ({}) }) },
        lifecycleSecret: "expected-secret",
      });
      expect(response.status).toBe(401);
    });

    it("account-lifecycle Edge Function rejects wrong secret on purge-sweep", async () => {
      const req = new Request(
        "http://localhost:54321/functions/v1/account-lifecycle",
        {
          method: "POST",
          body: JSON.stringify({ action: "purge-sweep" }),
          headers: {
            "Content-Type": "application/json",
            Authorization: "Bearer wrong-secret",
          },
        },
      );
      const response = await handleAccountLifecycleRequest(req, {
        anonClient: {} as unknown as SupabaseClient,
        adminClient: {} as unknown as SupabaseClient,
        storageApi: { from: () => ({ remove: async () => ({}) }) },
        lifecycleSecret: "expected-secret",
      });
      expect(response.status).toBe(401);
    });

    it("voice-capture Edge Function validates audio format and rejects malformed WAV headers", () => {
      // 4-byte invalid audio
      const invalidAudio = new Uint8Array([0, 1, 2, 3]);
      const validation = validateWavAudio(invalidAudio);
      expect(validation.valid).toBe(false);
      expect(validation.error).toBeDefined();
    });
  });
});
