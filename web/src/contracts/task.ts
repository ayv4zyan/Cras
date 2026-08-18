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

const decodeTask = Schema.decodeUnknownSync(TaskSchema, {
  onExcessProperty: "error",
});
const decodeComment = Schema.decodeUnknownSync(CommentSchema, {
  onExcessProperty: "error",
});

export function parseTask(raw: unknown): Task {
  return decodeTask(raw);
}

export function parseComment(raw: unknown): Comment {
  return decodeComment(raw);
}
