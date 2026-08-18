# JSON Schema contract, tagged plan, Label ids

Web and Android share a **contract**, not compiled types: JSON Schema in `contracts/` is canonical, golden examples live in `contracts/examples/`, and each client hand-writes its models (Effect Schema, kotlinx.serialization). CI validates the goldens; we do not generate types and we do not introduce Kotlin/JS to share them.

Supabase table, RPC, and Edge Function payloads preserve this domain shape even when Postgres normalizes Tasks, Labels, joins, and Comments across rows. The exact Data API / Edge Function split is decided on [Which operations use Supabase Data API vs Edge Functions?](https://github.com/ayv4zyan/Cras/issues/26). Hex paths (`tasks/{aa}/{bb}/{id}.json`) were the withdrawn GitHub store layout — they are not part of the contract. A Task stores Label **ids**.

`plan` is a tagged union: omitted (Inbox), `{ date }` (Date-only, no mode), `{ type: "floating", date, time }`, or `{ type: "instant", at }` (UTC). New timed Tasks default to Instant; that default is Settings, not this file. Date-only is never an Instant (no fake midnight). Instants (`completedAt`, comment `createdAt`, `plan.at`) are RFC 3339 UTC.

Rejected: Effect Schema as the source of truth, OpenAPI, a single global clock mode, week-of-plan-date folders, `type` on a Date-only plan.

Details: [What is the shared task contract between web and Android?](https://github.com/ayv4zyan/Cras/issues/16).
