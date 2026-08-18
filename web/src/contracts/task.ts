import { Schema } from "@effect/schema";

export const DateOnlyPlanSchema = Schema.Struct({
  date: Schema.String.pipe(Schema.pattern(/^\d{4}-\d{2}-\d{2}$/)),
});

export const FloatingPlanSchema = Schema.Struct({
  type: Schema.Literal("floating"),
  date: Schema.String.pipe(Schema.pattern(/^\d{4}-\d{2}-\d{2}$/)),
  time: Schema.String.pipe(Schema.pattern(/^\d{2}:\d{2}(:\d{2})?$/)),
});

export const InstantPlanSchema = Schema.Struct({
  type: Schema.Literal("instant"),
  at: Schema.String,
});

export const PlanSchema = Schema.NullOr(
  Schema.Union(DateOnlyPlanSchema, FloatingPlanSchema, InstantPlanSchema),
);

export type Plan = Schema.Schema.Type<typeof PlanSchema>;

export const PrioritySchema = Schema.Literal(1, 2, 3, 4);
export type Priority = Schema.Schema.Type<typeof PrioritySchema>;

export const TaskSchema = Schema.Struct({
  id: Schema.UUID,
  title: Schema.NonEmptyTrimmedString,
  description: Schema.NullOr(Schema.String),
  priority: PrioritySchema,
  plan: PlanSchema,
  labels: Schema.Array(Schema.UUID),
  parentId: Schema.NullOr(Schema.UUID),
  completedAt: Schema.NullOr(Schema.String),
  createdAt: Schema.String,
  updatedAt: Schema.String,
  version: Schema.Int.pipe(Schema.greaterThanOrEqualTo(1)),
});

export type Task = Schema.Schema.Type<typeof TaskSchema>;

const decodeTask = Schema.decodeUnknownSync(TaskSchema);

export function parseTask(raw: unknown): Task {
  return decodeTask(raw);
}
