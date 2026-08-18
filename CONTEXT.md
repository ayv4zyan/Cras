# Cras

Personal task management for one operator and their devices. Todoist-inspired; no Projects in this effort.

## Language

**Operator**:
The person whose task list a copy of Cras is for. One operator per copy.
_Avoid_: User, customer, tenant, owner

**Voice capture**:
An in-app recording the Operator makes so Cras can propose or change Task fields.
_Avoid_: dictation, OpenWhispr (OS-level paste, not this product)

**Draft**:
A proposed Task, or a proposed change to a Task, shown after Voice capture. Not saved until the Operator accepts.
_Avoid_: pending task, suggestion

**Task**:
A single thing the Operator intends to do. Its identity is a unique id, not the title. Two Tasks may share a title.
_Avoid_: todo, item, notes

**Description**:
Optional body text on a Task. Not a Comment.
_Avoid_: notes, note

**Comment**:
A dated remark the Operator attaches to a Task. A Task may have many.
_Avoid_: notes, note

**Label**:
A named, colored tag the Operator applies to many Tasks.
_Avoid_: tag, category, project

**Priority**:
How urgent a Task is: one of four levels, default none.
_Avoid_: importance, severity

**Subtask**:
A Task that has a parent Task. A Subtask cannot itself have children.
_Avoid_: checklist item

**Inbox**:
The view of open top-level Tasks that have no date. Subtasks are not in Inbox.
_Avoid_: unprocessed, all tasks, default project

**Today**:
The view of open Tasks whose date is today or earlier, using the Operator's local calendar date. Completed Tasks are not shown.
_Avoid_: scheduled, due

**Upcoming**:
The view of open dated Tasks grouped by day, from today into the future, with overdue in a strip at the top. There is no 7-day window.
_Avoid_: next 7 days, calendar, agenda

**Completed**:
The view of Tasks that have a completed-at, newest first. Completing does not delete the Task. Field edits require uncompleting first.
_Avoid_: archive, history, reporting, activity log

**Outbox**:
Creates and completes the Operator accepted on a device that are not yet in the store.
_Avoid_: local replica, working copy, cache, pending task
