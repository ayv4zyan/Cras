import { Schema } from "@effect/schema";

const IsoDateTimeString = Schema.String.pipe(
  Schema.pattern(
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/,
  ),
);

export const DateOnlyPlanSchema = Schema.Struct({
  date: Schema.String.pipe(Schema.pattern(/^\d{4}-\d{2}-\d{2}$/)),
});

export const FloatingPlanSchema = Schema.Struct({
  type: Schema.Literal("floating"),
  date: Schema.String.pipe(Schema.pattern(/^\d{4}-\d{2}-\d{2}$/)),
  time: Schema.String.pipe(
    Schema.pattern(/^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/),
  ),
});

export const InstantPlanSchema = Schema.Struct({
  type: Schema.Literal("instant"),
  at: IsoDateTimeString,
});

export const PlanSchema = Schema.NullOr(
  Schema.Union(DateOnlyPlanSchema, FloatingPlanSchema, InstantPlanSchema),
);

export type Plan = Schema.Schema.Type<typeof PlanSchema>;

export const PrioritySchema = Schema.Literal(1, 2, 3, 4);
export type Priority = Schema.Schema.Type<typeof PrioritySchema>;

export interface PriorityOption {
  readonly value: Priority;
  readonly label: string;
}

export const PRIORITY_OPTIONS: readonly PriorityOption[] = [
  { value: 4, label: "Priority 4 (None)" },
  { value: 3, label: "Priority 3 (Medium)" },
  { value: 2, label: "Priority 2 (High)" },
  { value: 1, label: "Priority 1 (Urgent)" },
];

export const LabelsSchema = Schema.Array(Schema.UUID).pipe(
  Schema.filter((arr) => new Set(arr).size === arr.length, {
    message: () => "labels must contain unique items",
  }),
);

export const TaskSchema = Schema.Struct({
  id: Schema.UUID,
  title: Schema.NonEmptyTrimmedString,
  description: Schema.NullOr(Schema.String),
  priority: PrioritySchema,
  plan: PlanSchema,
  labels: LabelsSchema,
  parentId: Schema.NullOr(Schema.UUID),
  completedAt: Schema.NullOr(IsoDateTimeString),
  createdAt: IsoDateTimeString,
  updatedAt: IsoDateTimeString,
  version: Schema.Int.pipe(Schema.greaterThanOrEqualTo(1)),
});

export type Task = Schema.Schema.Type<typeof TaskSchema>;

export const CommentSchema = Schema.Struct({
  id: Schema.UUID,
  taskId: Schema.UUID,
  content: Schema.NonEmptyTrimmedString,
  createdAt: IsoDateTimeString,
});

export type Comment = Schema.Schema.Type<typeof CommentSchema>;

export const LabelSchema = Schema.Struct({
  id: Schema.UUID,
  name: Schema.NonEmptyTrimmedString,
  color: Schema.NonEmptyTrimmedString,
  createdAt: Schema.optional(IsoDateTimeString),
  updatedAt: Schema.optional(IsoDateTimeString),
});

export type Label = Schema.Schema.Type<typeof LabelSchema>;

export interface LabelColorOption {
  readonly name: string;
  readonly value: string;
}

export const LABEL_COLORS: readonly LabelColorOption[] = [
  { name: "Red", value: "#ef4444" },
  { name: "Orange", value: "#f97316" },
  { name: "Amber", value: "#f59e0b" },
  { name: "Green", value: "#10b981" },
  { name: "Teal", value: "#14b8a6" },
  { name: "Blue", value: "#3b82f6" },
  { name: "Indigo", value: "#6366f1" },
  { name: "Purple", value: "#a855f7" },
  { name: "Pink", value: "#ec4899" },
  { name: "Slate", value: "#64748b" },
];

export const parseTask = Schema.decodeUnknownSync(TaskSchema, {
  onExcessProperty: "error",
});

export const parseComment = Schema.decodeUnknownSync(CommentSchema, {
  onExcessProperty: "error",
});

export const parseLabel = Schema.decodeUnknownSync(LabelSchema, {
  onExcessProperty: "error",
});

