import React, { useState, useEffect, useCallback } from "react";
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
} from "lucide-react";
import { AuthProvider } from "./contexts/AuthContext";
import { useAuth } from "./contexts/useAuth";
import { SignInScreen } from "./components/SignInScreen";
import { InboxView } from "./components/InboxView";
import { CompletedView } from "./components/CompletedView";
import { TaskDetailModal } from "./components/TaskDetailModal";
import { LabelManagerModal } from "./components/LabelManagerModal";
import {
  fetchTasks,
  createTask,
  updateTask,
  completeTask,
  uncompleteTask,
  filterInboxTasks,
  filterCompletedTasks,
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
import type { Priority, Task, Label } from "./contracts/task";
import { supabase } from "./config/supabase";
import type { SupabaseClient } from "@supabase/supabase-js";

type ViewMode = "inbox" | "today" | "upcoming" | "completed";

export interface CrasAppProps {
  readonly client?: SupabaseClient;
}

interface NavItem {
  readonly id: ViewMode;
  readonly label: string;
  readonly icon: React.ComponentType<{ className?: string }>;
  readonly iconClassName?: string;
  readonly badge?: number;
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
  const [activeView, setActiveView] = useState<ViewMode>("inbox");
  const [tasks, setTasks] = useState<Task[]>([]);
  const [labels, setLabels] = useState<Label[]>([]);
  const [selectedTask, setSelectedTask] = useState<Task | null>(null);
  const [isDetailModalOpen, setIsDetailModalOpen] = useState(false);
  const [isLabelManagerOpen, setIsLabelManagerOpen] = useState(false);
  const [isTasksLoading, setIsTasksLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    if (!user) {
      setTasks([]);
      setLabels([]);
      return;
    }
    setIsTasksLoading(true);
    setErrorMessage(null);
    try {
      const [allTasks, allLabels] = await Promise.all([
        fetchTasks(client),
        fetchLabels(client).catch(() => [] as Label[]),
      ]);
      setTasks(allTasks);
      setLabels(allLabels);
    } catch (err) {
      setErrorMessage(
        err instanceof Error ? err.message : "Failed to load data",
      );
    } finally {
      setIsTasksLoading(false);
    }
  }, [user, client]);

  useEffect(() => {
    if (user) {
      loadData();
    }
  }, [user, loadData]);

  const applyTaskUpdate = useCallback((updated: Task) => {
    setTasks((prev) => prev.map((t) => (t.id === updated.id ? updated : t)));
    setSelectedTask((prev) => (prev?.id === updated.id ? updated : prev));
  }, []);

  const handleCreateTask = useCallback(
    async (
      params: CreateTaskParams | string,
      description?: string | null,
      priority?: Priority,
    ) => {
      setErrorMessage(null);
      const createPayload: CreateTaskParams =
        typeof params === "string"
          ? { title: params, description, priority }
          : params;
      const newTask = await createTask(client, createPayload);
      setTasks((prev) => [newTask, ...prev]);
    },
    [client],
  );

  const handleUpdateTask = useCallback(
    async (params: UpdateTaskParams) => {
      setErrorMessage(null);
      const updated = await updateTask(client, params);
      applyTaskUpdate(updated);
    },
    [client, applyTaskUpdate],
  );

  const handleCompleteTask = useCallback(
    async (task: Task) => {
      setErrorMessage(null);
      const completed = await completeTask(client, task.id);
      applyTaskUpdate(completed);
    },
    [client, applyTaskUpdate],
  );

  const handleUncompleteTask = useCallback(
    async (task: Task) => {
      setErrorMessage(null);
      const uncompleted = await uncompleteTask(client, task.id);
      applyTaskUpdate(uncompleted);
    },
    [client, applyTaskUpdate],
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

  const handleCreateLabel = useCallback(
    async (params: CreateLabelParams) => {
      const newLabel = await createLabel(client, params);
      setLabels((prev) => [...prev, newLabel]);
    },
    [client],
  );

  const handleUpdateLabel = useCallback(
    async (params: UpdateLabelParams) => {
      const updated = await updateLabel(client, params);
      setLabels((prev) => prev.map((l) => (l.id === updated.id ? updated : l)));
    },
    [client],
  );

  const handleDeleteLabel = useCallback(
    async (labelId: string) => {
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

  const inboxTasks = filterInboxTasks(tasks);
  const completedTasks = filterCompletedTasks(tasks);

  const navItems: readonly NavItem[] = [
    {
      id: "inbox",
      label: "Inbox",
      icon: Inbox,
      badge: inboxTasks.length,
    },
    {
      id: "today",
      label: "Today",
      icon: Calendar,
      iconClassName: "text-emerald-600 dark:text-emerald-400",
      badge: 0,
    },
    {
      id: "upcoming",
      label: "Upcoming",
      icon: CalendarDays,
      iconClassName: "text-blue-600 dark:text-blue-400",
    },
    {
      id: "completed",
      label: "Completed",
      icon: CheckCircle2,
      iconClassName: "text-muted-foreground",
      badge: completedTasks.length,
    },
  ];

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

              <button
                type="button"
                onClick={() => signOut()}
                aria-label="Sign out"
                title="Sign out"
                className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-secondary/80 transition-colors cursor-pointer shrink-0"
              >
                <LogOut className="h-3.5 w-3.5" />
              </button>
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
          />
        ) : activeView === "completed" ? (
          <CompletedView
            tasks={completedTasks}
            labels={labels}
            onUncompleteTask={handleUncompleteTask}
            onSelectTask={handleSelectTask}
            isLoading={isTasksLoading}
          />
        ) : (
          <div className="flex-1 flex flex-col">
            <header className="h-14 border-b border-border/70 flex items-center justify-between px-8 bg-background/50 backdrop-blur-xs">
              <div className="flex items-center space-x-2">
                <h2 className="text-lg font-semibold capitalize tracking-tight">
                  {activeView}
                </h2>
              </div>
            </header>

            <div className="flex-1 flex items-center justify-center p-8">
              <div className="max-w-md w-full text-center space-y-4">
                <div className="space-y-1.5">
                  <h3 className="text-base font-medium tracking-tight">
                    {activeView === "today" && "No tasks for Today"}
                    {activeView === "upcoming" && "No upcoming tasks"}
                  </h3>
                  <p className="text-sm text-muted-foreground">
                    Your task space is clear.
                  </p>
                </div>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Task Detail Modal */}
      <TaskDetailModal
        task={selectedTask}
        availableLabels={labels}
        isOpen={isDetailModalOpen}
        onClose={handleCloseDetailModal}
        onSave={handleUpdateTask}
        onToggleComplete={handleToggleCompleteInModal}
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
    </div>
  );
}

export default function App(): React.JSX.Element {
  return (
    <AuthProvider>
      <CrasApp />
    </AuthProvider>
  );
}
