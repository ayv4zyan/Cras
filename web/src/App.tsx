import React, { useState } from "react";
import {
  Inbox,
  Calendar,
  CalendarDays,
  CheckCircle2,
  Plus,
  Layers,
  Sparkles,
} from "lucide-react";

type ViewMode = "inbox" | "today" | "upcoming" | "completed";

export default function App(): React.JSX.Element {
  const [activeView, setActiveView] = useState<ViewMode>("inbox");

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
            <button
              onClick={() => setActiveView("inbox")}
              className={`w-full flex items-center justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                activeView === "inbox"
                  ? "bg-secondary text-foreground font-semibold shadow-xs"
                  : "text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
              }`}
            >
              <div className="flex items-center space-x-2.5">
                <Inbox className="h-4 w-4" />
                <span>Inbox</span>
              </div>
              <span className="text-xs text-muted-foreground">0</span>
            </button>

            <button
              onClick={() => setActiveView("today")}
              className={`w-full flex items-center justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                activeView === "today"
                  ? "bg-secondary text-foreground font-semibold shadow-xs"
                  : "text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
              }`}
            >
              <div className="flex items-center space-x-2.5">
                <Calendar className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                <span>Today</span>
              </div>
              <span className="text-xs text-muted-foreground">0</span>
            </button>

            <button
              onClick={() => setActiveView("upcoming")}
              className={`w-full flex items-center justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                activeView === "upcoming"
                  ? "bg-secondary text-foreground font-semibold shadow-xs"
                  : "text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
              }`}
            >
              <div className="flex items-center space-x-2.5">
                <CalendarDays className="h-4 w-4 text-blue-600 dark:text-blue-400" />
                <span>Upcoming</span>
              </div>
            </button>

            <button
              onClick={() => setActiveView("completed")}
              className={`w-full flex items-center justify-between px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                activeView === "completed"
                  ? "bg-secondary text-foreground font-semibold shadow-xs"
                  : "text-muted-foreground hover:bg-secondary/60 hover:text-foreground"
              }`}
            >
              <div className="flex items-center space-x-2.5">
                <CheckCircle2 className="h-4 w-4 text-muted-foreground" />
                <span>Completed</span>
              </div>
            </button>
          </nav>
        </div>

        {/* Deployment Status */}
        <div className="px-3 py-2 rounded-lg border border-border/60 bg-card text-xs text-muted-foreground space-y-1">
          <div className="flex items-center space-x-1.5 font-medium text-foreground">
            <span className="h-2 w-2 rounded-full bg-emerald-500"></span>
            <span>Local Deployment</span>
          </div>
          <p className="text-[11px] leading-relaxed">
            Connected to local Supabase store
          </p>
        </div>
      </aside>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col overflow-y-auto">
        {/* Top bar */}
        <header className="h-14 border-b border-border/70 flex items-center justify-between px-8 bg-background/50 backdrop-blur-xs">
          <div className="flex items-center space-x-2">
            <h2 className="text-lg font-semibold capitalize tracking-tight">
              {activeView}
            </h2>
          </div>
          <button
            className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-md text-xs font-medium bg-primary text-primary-foreground hover:opacity-90 transition-opacity shadow-xs cursor-pointer"
            title="Create Task"
          >
            <Plus className="h-3.5 w-3.5" />
            <span>New Task</span>
          </button>
        </header>

        {/* View Surface */}
        <div className="flex-1 flex items-center justify-center p-8">
          <div className="max-w-md w-full text-center space-y-4">
            <div className="mx-auto w-12 h-12 rounded-full bg-secondary/80 flex items-center justify-center text-muted-foreground">
              {activeView === "inbox" && <Layers className="h-6 w-6" />}
              {activeView === "today" && <Calendar className="h-6 w-6" />}
              {activeView === "upcoming" && (
                <CalendarDays className="h-6 w-6" />
              )}
              {activeView === "completed" && (
                <CheckCircle2 className="h-6 w-6" />
              )}
            </div>

            <div className="space-y-1.5">
              <h3 className="text-base font-medium tracking-tight">
                {activeView === "inbox" && "No tasks in Inbox"}
                {activeView === "today" && "No tasks scheduled for Today"}
                {activeView === "upcoming" && "No upcoming tasks"}
                {activeView === "completed" && "No completed tasks yet"}
              </h3>
              <p className="text-sm text-muted-foreground">
                Your task space is clear. Capture a new task or use Voice
                capture to propose drafts.
              </p>
            </div>

            <div className="pt-2">
              <div className="inline-flex items-center space-x-2 text-xs text-muted-foreground/80 px-3 py-1 rounded-full bg-secondary/40 border border-border/40">
                <Sparkles className="h-3.5 w-3.5 text-amber-500" />
                <span>Polyglot web client &middot; React 19</span>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
