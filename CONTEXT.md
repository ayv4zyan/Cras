# Cras

Personal task management for independent operators and their devices. Each Operator has an isolated task space. Todoist-inspired; no Projects in this effort.

## Language

**Operator**:
A person authenticated to Cras with their own isolated task space. Many Operators may use one Deployment.
_Avoid_: User, customer, tenant, owner

**Deployment**:
One configured release of the Cras web and Android clients connected to a shared backend. A Deployment serves many Operators.
_Avoid_: copy, instance, workspace

**Settings**:
An Operator's shared optional overrides of Deployment defaults. Settings follow the Operator across devices.
_Avoid_: preferences, device settings, config

**Voice model catalog**:
The Deployment-maintained set of enabled Voice capture models and defaults.
_Avoid_: model allow-list, model list, Operator models

**Voice capture**:
An in-app recording the Operator makes so Cras can propose or change Task fields.
_Avoid_: dictation, Dictate, Ramble, OpenWhispr (OS-level paste, not this product)

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
A named, colored tag the Operator applies to many Tasks. Its identity is a unique id, not the name. Names are unique; rename keeps the id.
_Avoid_: tag, category, project

**Priority**:
How urgent a Task is: one of four levels, default none.
_Avoid_: importance, severity

**Instant**:
A timed plan that is one moment on Earth. Stored as UTC. The clock face on a device is derived.
_Avoid_: absolute, zoned, UTC task

**Floating**:
A timed plan that is a clock time on a calendar day, the same face in every city. No timezone.
_Avoid_: local time, wall clock, unzoned

**Date-only**:
A plan that is a calendar day and nothing else. Not an Instant. Has no Instant/Floating mode.
_Avoid_: all-day, midnight, date with type

**Subtask**:
A Task that has a parent Task. A Subtask cannot itself have children.
_Avoid_: checklist item

**Inbox**:
The view of open top-level Tasks that have no date. Subtasks are not in Inbox.
_Avoid_: unprocessed, all tasks, default project

**Today**:
The view of open Tasks whose plan day is today or earlier, using the Operator's local calendar date. For an Instant that day is the device-local date of the moment. Completed Tasks are not shown.
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

**Launchpad**:
A home-screen widget of four action buttons: Today, Upcoming, Voice capture, and Create task.
_Avoid_: dock, control center

**Shortcut**:
A single-action home-screen button. One of the Launchpad actions, placed alone.
_Avoid_: action button, humble button

**Today glance**:
A home-screen widget that lists Today's Task titles.
_Avoid_: task list widget, agenda widget
