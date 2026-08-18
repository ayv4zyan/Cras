# JSON Schema contract, tagged plan, hex paths

Web and Android share a **contract**, not compiled types: JSON Schema in `contracts/` is canonical, golden examples live in `contracts/examples/`, and each client hand-writes its models (Effect Schema, kotlinx.serialization). CI validates the goldens; we do not generate types and we do not introduce Kotlin/JS to share them.

A Task file is `tasks/{aa}/{bb}/{id}.json` in the data repo (`aa`/`bb` are the first four hex characters of the Task id). Labels are `labels.json` at that repo’s root; a Task stores Label **ids**. Paths are stable — we do not shard by plan date or week, because clients already load the whole tree and a date edit must not move the file.

`plan` is a tagged union: omitted (Inbox), `{ date }` (Date-only, no mode), `{ type: "floating", date, time }`, or `{ type: "instant", at }` (UTC). New timed Tasks default to Instant; that default is Settings, not this file. Date-only is never an Instant (no fake midnight). Instants (`completedAt`, comment `createdAt`, `plan.at`) are RFC 3339 UTC.

Rejected: Effect Schema as the source of truth, OpenAPI, a single global clock mode, week-of-plan-date folders, `type` on a Date-only plan.

Details: [What is the shared task contract between web and Android?](https://github.com/ayv4zyan/Cras/issues/16).
