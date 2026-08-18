# How does Todoist treat two similar tasks?

**Status (2026-08-18):** informs “two creates stay two Tasks.” That rule survived both persistence changes and remains current under the Supabase-only backend decision — [Does Cras need a separate Cloudflare/Hono backend?](https://github.com/ayv4zyan/Cras/issues/25), `docs/adr/0005-supabase-platform.md`. Originally written for Q12 on the superseded [Where do tasks live?](https://github.com/ayv4zyan/Cras/issues/13).

Question: if the Operator creates “Go to the pool” for Monday and later “Go to the pool” for Friday — or forgets and creates “the same” task again on the phone — what does Todoist officially do? Warn, merge, block, or keep both?

**Verdict.** Todoist treats a Task as an **id**, not as a title. Two tasks with the same name are allowed and sometimes **intentional** (`Duplicate`). Official help documents no create-time duplicate warning and no “merge two tasks” action. Same title + different dates is two tasks. Search is how you find them later. Recurring dates are how you avoid creating a *repeating* thing twice — not how you collapse two one-off creates. Multi-device *same-id* edit conflicts are **SILENT** in help (sync docs cover accounts, cache, and a manual Sync button).

---

## 1. Identity is not the title

Adding a task is: name + optional date, then confirm. Nothing in that flow says the name must be unique.

Source: [Introduction to tasks](https://www.todoist.com/help/articles/introduction-to-tasks-080OAXric).

The Sync API’s `item_add` takes `content` (the title) as ordinary text. Example in the docs: `"content": "Buy Milk"`. There is no uniqueness constraint on `content` in that command object.

Command `uuid` is **idempotency for retrying the same command**, not “this title already exists”:

> “Todoist will not execute a command that has same UUID as a previously executed command. This will allow clients to safely retry each command without accidentally performing the action twice.”

A second `item_add` with a **new** `uuid` and the same `content` is a second task.

Source: [Todoist API — Sync / Command UUID](https://developer.todoist.com/api/v1/).

---

## 2. Duplicate is a feature, not a problem

Official help has a first-class **Duplicate** action on a single task and on a multi-select:

> “If you need to create copies of a task, you can save time and effort by duplicating the task and any of its active sub-tasks. The copies will appear right below it in the task list.”

Copies omit comments, custom reminders, and completed sub-tasks.

Team help tells you to duplicate when several people must each complete “the same” work:

> “If several people need to complete the same task, duplicate the task and assign each copy to the responsible member.”

Bulk toolbar actions (web): Date, Move to, Labels, Priority, Assign to, Deadline, Complete, **Duplicate**, Open in new window, Delete. There is **no Merge**.

Sources:

- [Introduction to tasks — Duplicate a task](https://www.todoist.com/help/articles/introduction-to-tasks-080OAXric)
- [Add or manage multiple tasks](https://www.todoist.com/help/articles/add-or-manage-multiple-tasks-in-todoist-PcPoskdUp)
- [Manage team tasks](https://www.todoist.com/help/articles/manage-team-tasks-in-todoist-S99543QzY)

---

## 3. No documented merge-two-tasks

The only official “merge” article is **accounts**:

> “It’s not possible to merge separate Todoist accounts at this point.”

Workaround: export/import projects as CSV.

Help search for merging *tasks* does not surface a task-merge feature. Bulk manage lists Duplicate and Delete, not Merge.

**SILENT:** create-time “this title already exists” warning. **SILENT:** merge-two-tasks. Do not infer a hidden matcher from third-party blogs.

Source: [Can I merge several Todoist accounts?](https://www.todoist.com/help/articles/can-i-merge-several-todoist-accounts-ybfXqH)

---

## 4. Monday pool vs Friday pool

Nothing in add-task, dates, or search says two tasks that share a name are one Task. A date is a field on a task, not its identity.

The official way to *not* create a new task every time for something that **repeats** is a recurring date:

> “Instead of creating a new task every single time, add a recurring date!”

That is one task whose date shifts on complete — not two dated rows that the app collapses. Recurrence and reminders are explicitly excluded from the current MVP Task model by [What is a Task, and what are Inbox, Today, and Upcoming?](https://github.com/ayv4zyan/Cras/issues/6). That decision does not answer the forgotten second *one-off* create.

If a recurring pattern cannot be expressed as one rule, help says create **separate** tasks (e.g. “every mon at 8pm” and “every tue 9pm”).

Sources:

- [Introduction to recurring dates](https://www.todoist.com/help/articles/introduction-to-recurring-dates-YUYVJJAV)
- [Introduction to tasks](https://www.todoist.com/help/articles/introduction-to-tasks-080OAXric)

---

## 5. Finding a forgotten create

Search matches keywords in titles (and descriptions, comments). `search: report` “Shows all tasks that contain the word report.” Wildcards exist (`search: sky*`). You can also search completed tasks.

That is **after-the-fact lookup**, not an interrupt at create time. Quick Add / Dynamic Add docs do not mention existing-task suggestions.

Sources:

- [Introduction to search](https://www.todoist.com/help/articles/introduction-to-search-fAfiDSAp)
- [Introduction to tasks](https://www.todoist.com/help/articles/introduction-to-tasks-080OAXric)

---

## 6. Two devices: what sync docs actually say

[Troubleshoot syncing issues](https://www.todoist.com/help/articles/troubleshoot-syncing-issues-in-todoist-d6dDzzpF) covers:

- Don’t clear cache before sync finishes (you can lose unsynced local changes)
- Extensions / VPN / wrong account / outdated app / plan limits
- A **Sync** button (web: avatar → Sync)
- A test: create a task on device A, see it on B, create on B, see it on A

**SILENT:** two clients editing the **same** task at once (field merge, last-write-wins, a conflict dialog). Do not invent Todoist’s internal merge from this page.

First-party clients “use optimistic updates”; new resources have a client-side `tmp-` placeholder until the server assigns an id. That is create latency, not duplicate detection.

Source: [Todoist API](https://developer.todoist.com/api/v1/)

---

## 7. What this means for Cras

Todoist’s documented product is: **two creates → two tasks**. Title match is not identity. They *add* copies on purpose. Cleanup is delete/complete (or search to find the extra one). They do not ship “merge these two tasks” or a create-time matcher.

Cras identifies Tasks by id, so the forgotten second create is the Todoist-shaped case: leave both. An overlapping write to the **same id** is a separate Supabase row/version conflict handled by compare-and-swap; it does not merge two independently created Tasks.

---

## Sources

- https://www.todoist.com/help/articles/introduction-to-tasks-080OAXric
- https://www.todoist.com/help/articles/add-or-manage-multiple-tasks-in-todoist-PcPoskdUp
- https://www.todoist.com/help/articles/manage-team-tasks-in-todoist-S99543QzY
- https://www.todoist.com/help/articles/can-i-merge-several-todoist-accounts-ybfXqH
- https://www.todoist.com/help/articles/introduction-to-recurring-dates-YUYVJJAV
- https://www.todoist.com/help/articles/introduction-to-search-fAfiDSAp
- https://www.todoist.com/help/articles/troubleshoot-syncing-issues-in-todoist-d6dDzzpF
- https://developer.todoist.com/api/v1/
