export type Plan =
  | { readonly date: string }
  | { readonly type: 'floating'; readonly date: string; readonly time: string }
  | { readonly type: 'instant'; readonly at: string };

export type Priority = 1 | 2 | 3 | 4;

export interface Task {
  readonly id: string;
  readonly title: string;
  readonly description: string | null;
  readonly priority: Priority;
  readonly plan: Plan | null;
  readonly labels: readonly string[];
  readonly parentId: string | null;
  readonly completedAt: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly version: number;
}

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const DATE_REGEX = /^\d{4}-\d{2}-\d{2}$/;
const TIME_REGEX = /^\d{2}:\d{2}(:\d{2})?$/;

export function parsePlan(raw: unknown): Plan | null {
  if (raw === null || raw === undefined) {
    return null;
  }
  if (typeof raw !== 'object') {
    throw new Error('Plan must be an object or null');
  }
  const obj = raw as Record<string, unknown>;
  if (!('type' in obj)) {
    if (typeof obj.date === 'string' && DATE_REGEX.test(obj.date)) {
      return { date: obj.date };
    }
    throw new Error('Date-only plan requires valid date string (YYYY-MM-DD)');
  }
  if (obj.type === 'floating') {
    if (
      typeof obj.date === 'string' &&
      DATE_REGEX.test(obj.date) &&
      typeof obj.time === 'string' &&
      TIME_REGEX.test(obj.time)
    ) {
      return { type: 'floating', date: obj.date, time: obj.time };
    }
    throw new Error('Floating plan requires valid date and time');
  }
  if (obj.type === 'instant') {
    if (typeof obj.at === 'string' && !isNaN(Date.parse(obj.at))) {
      return { type: 'instant', at: obj.at };
    }
    throw new Error('Instant plan requires valid RFC 3339 UTC date-time string');
  }
  throw new Error(`Unknown plan type: ${String(obj.type)}`);
}

export function parseTask(raw: unknown): Task {
  if (!raw || typeof raw !== 'object') {
    throw new Error('Task must be a non-null object');
  }
  const t = raw as Record<string, unknown>;

  if (typeof t.id !== 'string' || !UUID_REGEX.test(t.id)) {
    throw new Error(`Invalid or missing task id: ${String(t.id)}`);
  }
  if (typeof t.title !== 'string' || t.title.trim().length === 0) {
    throw new Error('Task title must be a non-empty string');
  }
  if (t.description !== null && t.description !== undefined && typeof t.description !== 'string') {
    throw new Error('Task description must be a string or null');
  }
  if (typeof t.priority !== 'number' || t.priority < 1 || t.priority > 4) {
    throw new Error(`Task priority must be an integer between 1 and 4, got ${String(t.priority)}`);
  }
  if (!Array.isArray(t.labels) || t.labels.some((l) => typeof l !== 'string' || !UUID_REGEX.test(l))) {
    throw new Error('Task labels must be an array of UUIDs');
  }
  if (t.parentId !== null && t.parentId !== undefined && (typeof t.parentId !== 'string' || !UUID_REGEX.test(t.parentId))) {
    throw new Error('Task parentId must be a UUID or null');
  }
  if (t.completedAt !== null && t.completedAt !== undefined && (typeof t.completedAt !== 'string' || isNaN(Date.parse(t.completedAt)))) {
    throw new Error('Task completedAt must be an ISO date-time string or null');
  }
  if (typeof t.createdAt !== 'string' || isNaN(Date.parse(t.createdAt))) {
    throw new Error('Task createdAt must be an ISO date-time string');
  }
  if (typeof t.updatedAt !== 'string' || isNaN(Date.parse(t.updatedAt))) {
    throw new Error('Task updatedAt must be an ISO date-time string');
  }
  if (typeof t.version !== 'number' || t.version < 1) {
    throw new Error('Task version must be an integer >= 1');
  }

  return {
    id: t.id,
    title: t.title,
    description: (t.description as string) ?? null,
    priority: t.priority as Priority,
    plan: parsePlan(t.plan),
    labels: t.labels as string[],
    parentId: (t.parentId as string) ?? null,
    completedAt: (t.completedAt as string) ?? null,
    createdAt: t.createdAt,
    updatedAt: t.updatedAt,
    version: t.version,
  };
}
