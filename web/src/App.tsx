import React, { useState, useEffect, useCallback, useMemo } from "react";
import {
  Inbox,
  Calendar,
  CalendarDays,
  CheckCircle2,
  LogOut,
  User as UserIcon,
  Loader2,
  Tag,
  Plus,
  Settings as SettingsIcon,
} from "lucide-react";
import { AuthProvider } from "./contexts/AuthContext";
import { useAuth } from "./contexts/useAuth";
import { SignInScreen } from "./components/SignInScreen";
import { InboxView } from "./components/InboxView";
import { TodayView } from "./components/TodayView";
import { UpcomingView } from "./components/UpcomingView";
import { CompletedView } from "./components/CompletedView";
import { TaskDetailModal } from "./components/TaskDetailModal";
import { LabelManagerModal } from "./components/LabelManagerModal";
import { SettingsModal } from "./components/SettingsModal";
import { NotificationPermissionModal } from "./components/NotificationPermissionModal";
import {
  hasExplainedPermission,
  getBrowserPermissionState,
  syncInstallationWithServer,
  deactivateInstallation,
  registerServiceWorker,
  getExistingPushSubscription,
  arrayBufferToBase64,
} from "./services/notificationService";
import {
  fetchTasks,
  fetchTaskById,
  updateTask,
  uncompleteTask,
  isVersionConflictError,
  filterInboxTasks,
  filterCompletedTasks,
  filterSubtasks,
  filterTodayTasks,
  filterUpcomingTasks,
  type CreateTaskParams,
  type UpdateTaskParams,
} from "./services/taskService";
import {
  fetchLabels,
  createLabel,
  updateLabel,
  deleteLabel,
  type CreateLabelParams,
  type UpdateLabelParams,
} from "./services/labelService";
import { fetchComments, createComment } from "./services/commentService";
import {
  fetchEffectiveTimedPlanType,
  getCachedEffectiveTimedPlanType,
} from "./services/settingsService";
import {
  getOutbox,
  enqueueOutboxItem,
  drainOutbox,
  applyOutboxToTasks,
  generateTaskId,
  type CreateOutboxItem,
  type CompleteOutboxItem,
  type OutboxItem,
} from "./services/outboxService";
import { subscribeToInvalidations } from "./services/realtimeService";
import type { TimedPlanType } from "./services/temporalService";
import type { Priority, Task, Label, Comment } from "./contracts/task";
import { supabase } from "./config/supabase";
import type { SupabaseClient, User } from "@supabase/supabase-js";

type ViewMode = "inbox" | "today" | "upcoming" | "completed";

export interface CrasAppProps {
  readonly client?: SupabaseClient;
}

export interface AuthenticatedAppProps {
  readonly client: SupabaseClient;
  readonly user: User;
  readonly onSignOut: () => Promise<void>;
}

interface NavItem {
  readonly id: ViewMode;
  readonly label: string;
  readonly icon: React.ComponentType<{ className?: string }>;
  readonly iconClassName?: string;
  readonly badge?: number;
}

export function AuthenticatedApp({
  client,
  user,
  onSignOut,
}: AuthenticatedAppProps): React.JSX.Element {
  const [activeView, setActiveView] = useState<ViewMode>("inbox");
  const [tasks, setTasks] = useState<Task[]>([]);
  const [labels, setLabels] = useState<Label[]>([]);
  const [comments, setComments] = useState<Comment[]>([]);
  const [effectiveTimedPlanType, setEffectiveTimedPlanType] =
    useState<TimedPlanType>(getCachedEffectiveTimedPlanType());
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [isDetailModalOpen, setIsDetailModalOpen] = useState(false);
  const [isLabelManagerOpen, setIsLabelManagerOpen] = useState(false);
  const [isSettingsModalOpen, setIsSettingsModalOpen] = useState(false);
  const [isPermissionModalOpen, setIsPermissionModalOpen] = useState(false);
  const [isTasksLoading, setIsTasksLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const userId = user.id;
  const selectedTaskRef = React.useRef(selectedTask);
  useEffect(() => {
    selectedTaskRef.current = selectedTask;
  }, [selectedTask]);

  // Initialize service worker and sync installation
  useEffect(() => {
    let isMounted = true;

    async function initInstallation() {
      try {
        const reg = await registerServiceWorker();
        let endpoint: string | null = null;
        let p256dh: string | null = null;
        let auth: string | null = null;

        if (reg) {
          const sub = await getExistingPushSubscription(reg);
          if (sub) {
            endpoint = sub.endpoint;
            p256dh = arrayBufferToBase64(sub.getKey("p256dh"));
            auth = arrayBufferToBase64(sub.getKey("auth"));
          }
        }

        if (isMounted) {
          await syncInstallationWithServer(client, {
            endpoint,
            p256dh,
            auth,
          });
        }
      } catch {
        // Best effort
      }
    }

    initInstallation();

    // Handle deep links from notifications
    if (typeof window !== "undefined") {
      const params = new URLSearchParams(window.location.search);
      const initialTaskId = params.get("taskId");
      if (initialTaskId) {
        fetchTaskById(client, initialTaskId)
          .then((t) => {
            if (t && isMounted) {
              setSelectedTask(t);
              setIsDetailModalOpen(true);
            }
          })
          .catch(() => {});
      }
    }

    // Listen for service worker messages
    const handleMessage = (event: MessageEvent) => {
      if (event.data?.type === "CRAS_OPEN_TASK" && event.data.taskId) {
        fetchTaskById(client, event.data.taskId)
          .then((t) => {
            if (t && isMounted) {
              setSelectedTask(t);
              setIsDetailModalOpen(true);
            }
          })
          .catch(() => {});
      } else if (event.data?.type === "CRAS_PUSH_SUBSCRIPTION_CHANGE") {
        syncInstallationWithServer(client).catch(() => {});
      }
    };

    if (
      typeof navigator !== "undefined" &&
      "serviceWorker" in navigator &&
      navigator.serviceWorker
    ) {
      navigator.serviceWorker.addEventListener("message", handleMessage);
    }

    return () => {
      isMounted = false;
      if (
        typeof navigator !== "undefined" &&
        "serviceWorker" in navigator &&
        navigator.serviceWorker
      ) {
        navigator.serviceWorker.removeEventListener("message", handleMessage);
      }
    };
  }, [client]);

  const applyTaskUpdate = useCallback((updated: Task) => {
    setTasks((prev) => {
      const exists = prev.some((t) => t.id === updated.id);
      if (!exists) {
        return [updated, ...prev];
      }
      return prev.map((t) => {
        if (t.id === updated.id) {
          return t.version > updated.version ? t : updated;
        }
        return t;
      });
    });
    setSelectedTask((prev) => {
      if (prev?.id === updated.id) {
        return prev.version > updated.version ? prev : updated;
      }
      return prev;
    });
  }, []);

  const reconcileFreshTasks = useCallback(
    (freshTasks: Task[]) => {
      const outbox = getOutbox(userId);
      const withOutbox = applyOutboxToTasks(freshTasks, outbox);

      setTasks((prev) => {
        const prevMap = new Map(prev.map((t) => [t.id, t]));
        return withOutbox.map((fresh) => {
          const existing = prevMap.get(fresh.id);
          return existing && existing.version > fresh.version
            ? existing
            : fresh;
        });
      });

      const currentSelected = selectedTaskRef.current;
      if (currentSelected) {
        const freshSelected = withOutbox.find(
          (t) => t.id === currentSelected.id,
        );
        if (freshSelected) {
          setSelectedTask((prev) =>
            prev &&
            prev.id === freshSelected.id &&
            prev.version > freshSelected.version
              ? prev
              : freshSelected,
          );
        } else {
          setSelectedTask(null);
          setComments([]);
          setIsDetailModalOpen(false);
        }
      }
    },
    [userId],
  );

  const handleVersionConflict = useCallback(
    async (taskId: string) => {
      const [freshTasks, freshTask] = await Promise.all([
        fetchTasks(client).catch(() => null),
        fetchTaskById(client, taskId).catch(() => null),
      ]);
      if (freshTasks !== null) {
        reconcileFreshTasks(freshTasks);
      }
      if (freshTask) {
        applyTaskUpdate(freshTask);
      } else if (freshTasks !== null) {
        const matching = freshTasks.find((t) => t.id === taskId);
        if (matching) {
          applyTaskUpdate(matching);
        } else {
          const currentSelected = selectedTaskRef.current;
          if (currentSelected?.id === taskId) {
            setSelectedTask(null);
            setComments([]);
            setIsDetailModalOpen(false);
          }
        }
      }
      const conflictMsg =
        "Task version conflict: modified in another session. Refetched latest state.";
      setErrorMessage(conflictMsg);
      return new Error(conflictMsg);
    },
    [client, applyTaskUpdate, reconcileFreshTasks],
  );

  const handleDrainConflict = useCallback(
    async (_err: unknown, item: OutboxItem) => {
      if (item.type === "complete") {
        await handleVersionConflict(item.taskId);
      }
    },
    [handleVersionConflict],
  );

  const handleDrainError = useCallback(
    async (err: unknown, item: OutboxItem) => {
      if (item.type === "create") {
        setTasks((prev) => prev.filter((t) => t.id !== item.task.id));
        setSelectedTask((prev) => (prev?.id === item.task.id ? null : prev));
      } else if (item.type === "complete") {
        await handleVersionConflict(item.taskId);
      }
      setErrorMessage(
        err instanceof Error
          ? err.message
          : item.type === "create"
            ? "Failed to create task"
            : "Failed to complete task",
      );
    },
    [handleVersionConflict],
  );

  useEffect(() => {
    let isCancelled = false;

    setTasks([]);
    setLabels([]);
    setSelectedTask(null);
    setIsDetailModalOpen(false);
    setComments([]);
    setErrorMessage(null);

    setIsTasksLoading(true);
    Promise.all([
      fetchTasks(client).catch((err: unknown) => {
        if (!isCancelled) {
          setErrorMessage(
            err instanceof Error
              ? `Failed to load tasks: ${err.message}`
              : "Failed to load tasks",
          );
        }
        return [] as Task[];
      }),
      fetchLabels(client).catch((err: unknown) => {
        if (!isCancelled) {
          setErrorMessage(
            err instanceof Error
              ? `Failed to load labels: ${err.message}`
              : "Failed to load labels",
          );
        }
        return [] as Label[];
      }),
      fetchEffectiveTimedPlanType(client).catch(() =>
        getCachedEffectiveTimedPlanType(),
      ),
    ])
      .then(async ([allTasks, allLabels, effectiveType]) => {
        if (isCancelled) return;
        const outbox = getOutbox(userId);
        const tasksWithOutbox = applyOutboxToTasks(allTasks, outbox);
        setTasks(tasksWithOutbox);
        setLabels(allLabels);
        setEffectiveTimedPlanType(effectiveType);
        setIsTasksLoading(false);

        // Serialize: startup draining runs after initial loading completes
        await drainOutbox({
          client,
          operatorId: userId,
          onTaskCreated: (created) => {
            if (!isCancelled) applyTaskUpdate(created);
          },
          onTaskCompleted: (completed) => {
            if (!isCancelled) applyTaskUpdate(completed);
          },
          onConflict: (_err, item) => {
            if (!isCancelled) handleDrainConflict(_err, item);
          },
          onError: (err, item) => {
            if (!isCancelled) handleDrainError(err, item);
          },
        }).catch(() => {});
      })
      .catch((err: unknown) => {
        if (!isCancelled) {
          setErrorMessage(
            err instanceof Error ? err.message : "Failed to load data",
          );
          setIsTasksLoading(false);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [userId, client, applyTaskUpdate, handleDrainConflict, handleDrainError]);

  // Realtime subscription for Operator domain invalidations
  useEffect(() => {
    if (!userId) return;

    const subscription = subscribeToInvalidations({
      client,
      operatorId: userId,
      onInvalidate: (event) => {
        if (event.resource === "task") {
          if (event.operation === "updated") {
            fetchTaskById(client, event.id)
              .then((freshTask) => {
                if (freshTask) {
                  applyTaskUpdate(freshTask);
                } else {
                  fetchTasks(client)
                    .then(reconcileFreshTasks)
                    .catch(() => {});
                }
              })
              .catch(() => {
                fetchTasks(client)
                  .then(reconcileFreshTasks)
                  .catch(() => {});
              });
          } else {
            fetchTasks(client)
              .then(reconcileFreshTasks)
              .catch(() => {});
          }
        } else if (event.resource === "label") {
          fetchLabels(client)
            .then((freshLabels) => {
              setLabels(freshLabels);
            })
            .catch(() => {});
        } else if (event.resource === "comment") {
          const currentSelected = selectedTaskRef.current;
          if (
            currentSelected &&
            (event.taskId === currentSelected.id ||
              event.id === currentSelected.id)
          ) {
            fetchComments(client, currentSelected.id)
              .then((freshComments) => {
                setComments(freshComments);
              })
              .catch(() => {});
          }
        }
      },
      onReconnect: () => {
        drainOutbox({
          client,
          operatorId: userId,
          onTaskCreated: (created) => applyTaskUpdate(created),
          onTaskCompleted: (completed) => applyTaskUpdate(completed),
          onConflict: handleDrainConflict,
          onError: handleDrainError,
        })
          .catch(() => {})
          .finally(() => {
            Promise.all([
              fetchTasks(client).catch(() => null),
              fetchLabels(client).catch(() => null),
            ])
              .then(([freshTasks, freshLabels]) => {
                if (freshTasks !== null) {
                  reconcileFreshTasks(freshTasks);
                  const currentSelected = selectedTaskRef.current;
                  if (
                    currentSelected &&
                    freshTasks.some((t) => t.id === currentSelected.id)
                  ) {
                    fetchComments(client, currentSelected.id)
                      .then((freshComments) => {
                        setComments(freshComments);
                      })
                      .catch(() => {});
                  }
                }
                if (freshLabels !== null) {
                  setLabels(freshLabels);
                }
              })
              .catch(() => {});
          });
      },
    });

    return () => {
      subscription.unsubscribe();
    };
  }, [
    client,
    userId,
    applyTaskUpdate,
    reconcileFreshTasks,
    handleDrainConflict,
    handleDrainError,
  ]);

  // Window online event listener to drain queued outbox work on reconnect
  useEffect(() => {
    const handleOnline = () => {
      drainOutbox({
        client,
        operatorId: userId,
        onTaskCreated: (created) => applyTaskUpdate(created),
        onTaskCompleted: (completed) => applyTaskUpdate(completed),
        onConflict: handleDrainConflict,
        onError: handleDrainError,
      })
        .catch(() => {})
        .finally(() => {
          fetchTasks(client)
            .then((freshTasks) => {
              reconcileFreshTasks(freshTasks);
            })
            .catch(() => {});
        });
    };

    window.addEventListener("online", handleOnline);
    return () => {
      window.removeEventListener("online", handleOnline);
    };
  }, [
    client,
    userId,
    applyTaskUpdate,
    handleDrainConflict,
    handleDrainError,
    reconcileFreshTasks,
  ]);

  const selectedTaskId = selectedTask?.id;
  useEffect(() => {
    if (!selectedTaskId) {
      setComments([]);
      return;
    }
    let isCancelled = false;
    fetchComments(client, selectedTaskId)
      .then((taskComments) => {
        if (!isCancelled) {
          setComments(taskComments);
        }
      })
      .catch((err: unknown) => {
        if (!isCancelled) {
          setComments([]);
          setErrorMessage(
            err instanceof Error
              ? `Failed to load comments: ${err.message}`
              : "Failed to load comments",
          );
        }
      });
    return () => {
      isCancelled = true;
    };
  }, [client, selectedTaskId]);

  const handleCreateTask = useCallback(
    async (
      params: CreateTaskParams | string,
      description?: string | null,
      priority?: Priority,
    ) => {
      setErrorMessage(null);
      const parsedParams: CreateTaskParams =
        typeof params === "string"
          ? { title: params, description, priority }
          : params;

      const trimmedTitle = parsedParams.title.trim();
      if (trimmedTitle.length === 0) {
        throw new Error("Task title cannot be empty");
      }

      const taskId = parsedParams.id || generateTaskId();
      const nowIso = new Date().toISOString();
      const optimisticTask: Task = {
        id: taskId,
        title: trimmedTitle,
        description: parsedParams.description ?? null,
        priority: parsedParams.priority ?? 4,
        plan: parsedParams.plan ?? null,
        parentId: parsedParams.parentId ?? null,
        labels: parsedParams.labels ?? [],
        completedAt: null,
        createdAt: nowIso,
        updatedAt: nowIso,
        version: 1,
      };

      const outboxParams: CreateTaskParams = {
        ...parsedParams,
        id: taskId,
        title: trimmedTitle,
      };

      const outboxItem: CreateOutboxItem = {
        id: taskId,
        type: "create",
        task: optimisticTask,
        params: outboxParams,
        createdAt: nowIso,
      };

      // 1. Enter persistent Outbox before network acknowledgement
      enqueueOutboxItem(userId, outboxItem);

      // 2. Optimistic UI update
      setTasks((prev) => {
        if (prev.some((t) => t.id === taskId)) {
          return prev;
        }
        return [optimisticTask, ...prev];
      });

      // 3. Attempt drain
      let createRejected = false;
      try {
        await drainOutbox({
          client,
          operatorId: userId,
          onTaskCreated: (created) => {
            applyTaskUpdate(created);
          },
          onTaskCompleted: (completed) => {
            applyTaskUpdate(completed);
          },
          onConflict: handleDrainConflict,
          onError: (err, item) => {
            if (item.type === "create" && item.task.id === taskId) {
              createRejected = true;
            }
            handleDrainError(err, item);
          },
        });
      } catch {
        // Retained in outbox on network error
      }

      // 4. In-context permission explanation for first timed task
      if (
        !createRejected &&
        optimisticTask.plan &&
        "type" in optimisticTask.plan &&
        (optimisticTask.plan.type === "instant" ||
          optimisticTask.plan.type === "floating")
      ) {
        if (
          !hasExplainedPermission() &&
          getBrowserPermissionState() !== "granted"
        ) {
          setIsPermissionModalOpen(true);
        }
      }
    },
    [userId, client, applyTaskUpdate, handleDrainConflict, handleDrainError],
  );

  const handleUpdateTask = useCallback(
    async (params: UpdateTaskParams) => {
      setErrorMessage(null);
      try {
        const updated = await updateTask(client, params);
        applyTaskUpdate(updated);

        if (
          params.plan &&
          "type" in params.plan &&
          (params.plan.type === "instant" || params.plan.type === "floating")
        ) {
          if (
            !hasExplainedPermission() &&
            getBrowserPermissionState() !== "granted"
          ) {
            setIsPermissionModalOpen(true);
          }
        }
      } catch (err) {
        if (isVersionConflictError(err)) {
          const conflictErr = await handleVersionConflict(params.id);
          throw conflictErr;
        }
        const msg =
          err instanceof Error ? err.message : "Failed to update task";
        setErrorMessage(msg);
        throw err;
      }
    },
    [client, applyTaskUpdate, handleVersionConflict],
  );

  const handleCompleteTask = useCallback(
    async (task: Task) => {
      setErrorMessage(null);
      const completedAt = new Date().toISOString();

      const outboxItem: CompleteOutboxItem = {
        id: generateTaskId(),
        type: "complete",
        taskId: task.id,
        expectedVersion: task.version,
        completedAt,
        createdAt: completedAt,
      };

      // 1. Enter persistent Outbox before network acknowledgement
      enqueueOutboxItem(userId, outboxItem);

      // 2. Optimistic local update
      const optimisticCompletedTask: Task = {
        ...task,
        completedAt,
        updatedAt: completedAt,
      };
      applyTaskUpdate(optimisticCompletedTask);

      // 3. Attempt drain
      try {
        await drainOutbox({
          client,
          operatorId: userId,
          onTaskCreated: (created) => {
            applyTaskUpdate(created);
          },
          onTaskCompleted: (completed) => {
            applyTaskUpdate(completed);
          },
          onConflict: handleDrainConflict,
          onError: handleDrainError,
        });
      } catch {
        // Retained in outbox on network error
      }
    },
    [userId, client, applyTaskUpdate, handleDrainConflict, handleDrainError],
  );

  const handleUncompleteTask = useCallback(
    async (task: Task) => {
      setErrorMessage(null);
      try {
        const uncompleted = await uncompleteTask(client, task.id, task.version);
        applyTaskUpdate(uncompleted);
      } catch (err) {
        if (isVersionConflictError(err)) {
          const conflictErr = await handleVersionConflict(task.id);
          throw conflictErr;
        }
        const msg =
          err instanceof Error ? err.message : "Failed to uncomplete task";
        setErrorMessage(msg);
        throw err;
      }
    },
    [client, applyTaskUpdate, handleVersionConflict],
  );

  const handleSelectTask = useCallback((task: Task) => {
    setSelectedTask(task);
    setIsDetailModalOpen(true);
  }, []);

  const handleCloseDetailModal = useCallback(() => {
    setIsDetailModalOpen(false);
    setSelectedTask(null);
  }, []);

  const handleToggleCompleteInModal = useCallback(
    async (task: Task) => {
      if (task.completedAt) {
        await handleUncompleteTask(task);
      } else {
        await handleCompleteTask(task);
      }
    },
    [handleCompleteTask, handleUncompleteTask],
  );

  const handleAddComment = useCallback(
    async (taskId: string, content: string) => {
      const newComment = await createComment(client, { taskId, content });
      setComments((prev) => [...prev, newComment]);
    },
    [client],
  );

  const handleCreateSubtask = useCallback(
    async (parentId: string, title: string) => {
      await handleCreateTask({
        title,
        parentId,
      });
    },
    [handleCreateTask],
  );

  const handleCreateLabel = useCallback(
    async (params: CreateLabelParams) => {
      setErrorMessage(null);
      const newLabel = await createLabel(client, params);
      setLabels((prev) => [...prev, newLabel]);
    },
    [client],
  );

  const handleUpdateLabel = useCallback(
    async (params: UpdateLabelParams) => {
      setErrorMessage(null);
      const updated = await updateLabel(client, params);
      setLabels((prev) => prev.map((l) => (l.id === updated.id ? updated : l)));
    },
    [client],
  );

  const handleDeleteLabel = useCallback(
    async (labelId: string) => {
      setErrorMessage(null);
      await deleteLabel(client, labelId);
      setLabels((prev) => prev.filter((l) => l.id !== labelId));
      // Remove deleted label from loaded tasks
      setTasks((prev) =>
        prev.map((t) =>
          t.labels.includes(labelId)
            ? { ...t, labels: t.labels.filter((id) => id !== labelId) }
            : t,
        ),
      );
      setSelectedTask((prev) =>
        prev && prev.labels.includes(labelId)
          ? { ...prev, labels: prev.labels.filter((id) => id !== labelId) }
          : prev,
      );
    },
    [client],
  );

  const inboxTasks = useMemo(() => filterInboxTasks(tasks), [tasks]);
  const completedTasks = useMemo(() => filterCompletedTasks(tasks), [tasks]);
  const todayTasks = useMemo(() => filterTodayTasks(tasks), [tasks]);
  const upcomingResult = useMemo(() => filterUpcomingTasks(tasks), [tasks]);
  const totalUpcomingCount = useMemo(
    () =>
      upcomingResult.overdue.length +
      upcomingResult.groups.reduce((acc, g) => acc + g.tasks.length, 0),
    [upcomingResult],
  );

  const selectedTaskComments = useMemo(
    () =>
      selectedTask ? comments.filter((c) => c.taskId === selectedTask.id) : [],
    [selectedTask, comments],
  );
  const selectedTaskSubtasks = useMemo(
    () => (selectedTask ? filterSubtasks(tasks, selectedTask.id) : []),
    [selectedTask, tasks],
  );

  const navItems: readonly NavItem[] = useMemo(
    () => [
      {
        id: "inbox",
        label: "Inbox",
        icon: Inbox,
        badge: inboxTasks.length > 0 ? inboxTasks.length : undefined,
      },
      {
        id: "today",
        label: "Today",
        icon: Calendar,
        iconClassName: "text-emerald-600 dark:text-emerald-400",
        badge: todayTasks.length > 0 ? todayTasks.length : undefined,
      },
      {
        id: "upcoming",
        label: "Upcoming",
        icon: CalendarDays,
        iconClassName: "text-blue-600 dark:text-blue-400",
        badge: totalUpcomingCount > 0 ? totalUpcomingCount : undefined,
      },
      {
        id: "completed",
        label: "Completed",
        icon: CheckCircle2,
        iconClassName: "text-muted-foreground",
        badge: completedTasks.length > 0 ? completedTasks.length : undefined,
      },
    ],
    [
      inboxTasks.length,
      todayTasks.length,
      totalUpcomingCount,
      completedTasks.length,
    ],
  );

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-background text-foreground">
      {/* Sidebar Navigation */}
      <aside className="w-64 border-r border-border flex flex-col justify-between p-4 bg-secondary/30">
        <div className="space-y-6">
          {/* Header */}
          <div className="flex items-center space-x-3 px-2 py-1">
            <div className="h-8 w-8 rounded-md bg-primary text-primary-foreground flex items-center justify-center font-bold text-lg shadow-sm">
              C
            </div>
            <div>
              <h1 className="text-base font-semibold tracking-tight">Cras</h1>
              <p className="text-xs text-muted-foreground">
                Operator task space
              </p>
            </div>
          </div>

          {/* Nav Items */}
          <nav className="space-y-1">
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive = activeView === item.id;
              return (
                <button
                  key={item.id}
                  onClick={() => setActiveView(item.id)}
                  className={`w-full flex items-center justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors cursor-pointer ${
                    isActive
                      ? "bg-secondary text-foreground font-semibold shadow-xs"
                      : "text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
                  }`}
                >
                  <div className="flex items-center space-x-2.5">
                    <Icon className={`h-4 w-4 ${item.iconClassName || ""}`} />
                    <span>{item.label}</span>
                  </div>
                  {item.badge !== undefined && (
                    <span className="text-xs text-muted-foreground font-semibold">
                      {item.badge}
                    </span>
                  )}
                </button>
              );
            })}
          </nav>

          {/* Labels Section */}
          <div className="space-y-2 pt-2 border-t border-border/60">
            <div className="flex items-center justify-between px-3">
              <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                Labels
              </span>
              <button
                type="button"
                onClick={() => setIsLabelManagerOpen(true)}
                aria-label="Add or manage labels"
                title="Manage labels"
                className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors cursor-pointer"
              >
                <Plus className="h-3.5 w-3.5" />
              </button>
            </div>

            <div className="space-y-0.5 max-h-40 overflow-y-auto px-1">
              {labels.length === 0 ? (
                <button
                  type="button"
                  onClick={() => setIsLabelManagerOpen(true)}
                  className="w-full text-left px-2 py-1.5 rounded-md text-xs text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors flex items-center space-x-2 cursor-pointer"
                >
                  <Tag className="h-3.5 w-3.5 text-muted-foreground" />
                  <span>Manage labels...</span>
                </button>
              ) : (
                labels.map((label) => (
                  <button
                    key={label.id}
                    type="button"
                    onClick={() => setIsLabelManagerOpen(true)}
                    className="w-full text-left px-2 py-1 rounded-md text-xs text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors flex items-center justify-between cursor-pointer"
                  >
                    <div className="flex items-center space-x-2 truncate">
                      <span
                        className="h-2 w-2 rounded-full shrink-0"
                        style={{ backgroundColor: label.color }}
                      />
                      <span className="truncate">{label.name}</span>
                    </div>
                  </button>
                ))
              )}
            </div>
          </div>
        </div>

        {/* Operator Profile & Sign Out */}
        <div className="space-y-3">
          {errorMessage && (
            <div className="p-2 text-xs bg-destructive/10 text-destructive rounded-md">
              {errorMessage}
            </div>
          )}

          <div className="px-3 py-2 rounded-lg border border-border/60 bg-card text-xs space-y-2">
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-2 min-w-0">
                <div className="h-6 w-6 rounded-full bg-secondary flex items-center justify-center text-muted-foreground shrink-0">
                  <UserIcon className="h-3.5 w-3.5" />
                </div>
                <div className="truncate">
                  <p className="font-medium text-foreground truncate">
                    {user.email || "Operator"}
                  </p>
                  <p className="text-[10px] text-muted-foreground">
                    Isolated Space
                  </p>
                </div>
              </div>

              <div className="flex items-center space-x-1">
                <button
                  type="button"
                  onClick={() => setIsSettingsModalOpen(true)}
                  aria-label="Settings"
                  title="Settings"
                  className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary/80 transition-colors cursor-pointer shrink-0"
                >
                  <SettingsIcon className="h-3.5 w-3.5" />
                </button>

                <button
                  type="button"
                  onClick={async () => {
                    try {
                      await deactivateInstallation(client);
                    } catch (deactivateErr) {
                      console.error(
                        "Failed to deactivate installation during sign out:",
                        deactivateErr,
                      );
                      setErrorMessage(
                        deactivateErr instanceof Error
                          ? deactivateErr.message
                          : "Failed to deactivate installation during sign out",
                      );
                    } finally {
                      try {
                        await onSignOut();
                      } catch (signOutErr) {
                        console.error("Failed to sign out:", signOutErr);
                        setErrorMessage(
                          signOutErr instanceof Error
                            ? signOutErr.message
                            : "Failed to sign out",
                        );
                      }
                    }
                  }}
                  aria-label="Sign out"
                  title="Sign out"
                  className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary/80 transition-colors cursor-pointer shrink-0"
                >
                  <LogOut className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          </div>
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col overflow-hidden">
        {activeView === "inbox" ? (
          <InboxView
            tasks={inboxTasks}
            labels={labels}
            onCreateTask={handleCreateTask}
            onCompleteTask={handleCompleteTask}
            onSelectTask={handleSelectTask}
            isLoading={isTasksLoading}
            effectiveDefault={effectiveTimedPlanType}
          />
        ) : activeView === "today" ? (
          <TodayView
            tasks={tasks}
            labels={labels}
            onCreateTask={handleCreateTask}
            onCompleteTask={handleCompleteTask}
            onSelectTask={handleSelectTask}
            isLoading={isTasksLoading}
            effectiveDefault={effectiveTimedPlanType}
          />
        ) : activeView === "upcoming" ? (
          <UpcomingView
            tasks={tasks}
            labels={labels}
            onCompleteTask={handleCompleteTask}
            onSelectTask={handleSelectTask}
            isLoading={isTasksLoading}
          />
        ) : (
          <CompletedView
            tasks={completedTasks}
            labels={labels}
            onUncompleteTask={handleUncompleteTask}
            onSelectTask={handleSelectTask}
            isLoading={isTasksLoading}
          />
        )}
      </main>

      {/* Task Detail Modal */}
      <TaskDetailModal
        task={selectedTask}
        availableLabels={labels}
        comments={selectedTaskComments}
        subtasks={selectedTaskSubtasks}
        effectiveDefault={effectiveTimedPlanType}
        isOpen={isDetailModalOpen}
        onClose={handleCloseDetailModal}
        onSave={handleUpdateTask}
        onToggleComplete={handleToggleCompleteInModal}
        onAddComment={handleAddComment}
        onCreateSubtask={handleCreateSubtask}
        onToggleSubtaskComplete={handleToggleCompleteInModal}
        onSelectSubtask={handleSelectTask}
      />

      {/* Label Manager Modal */}
      <LabelManagerModal
        isOpen={isLabelManagerOpen}
        labels={labels}
        onClose={() => setIsLabelManagerOpen(false)}
        onCreateLabel={handleCreateLabel}
        onUpdateLabel={handleUpdateLabel}
        onDeleteLabel={handleDeleteLabel}
      />

      {/* Settings Modal */}
      <SettingsModal
        isOpen={isSettingsModalOpen}
        onClose={() => setIsSettingsModalOpen(false)}
        client={client}
        effectiveDefaultTimedPlanType={effectiveTimedPlanType}
        onTimedPlanTypeChanged={(type) => setEffectiveTimedPlanType(type)}
      />

      {/* Notification Permission In-Context Modal */}
      <NotificationPermissionModal
        isOpen={isPermissionModalOpen}
        onClose={() => setIsPermissionModalOpen(false)}
        client={client}
      />
    </div>
  );
}

export function CrasApp({
  client = supabase,
}: CrasAppProps): React.JSX.Element {
  const {
    user,
    isLoading: isAuthLoading,
    signInWithGoogle,
    signOut,
  } = useAuth();

  if (isAuthLoading) {
    return (
      <div className="flex h-screen w-screen items-center justify-center bg-background text-foreground">
        <div className="flex flex-col items-center space-y-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary font-bold text-lg text-primary-foreground shadow-xs">
            C
          </div>
          <div className="flex items-center space-x-2 text-xs text-muted-foreground">
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
            <span>Restoring Operator session...</span>
          </div>
        </div>
      </div>
    );
  }

  if (!user) {
    return <SignInScreen onSignInWithGoogle={signInWithGoogle} />;
  }

  return (
    <AuthenticatedApp
      key={user.id}
      client={client}
      user={user}
      onSignOut={signOut}
    />
  );
}

export default function App(): React.JSX.Element {
  return (
    <AuthProvider>
      <CrasApp />
    </AuthProvider>
  );
}
