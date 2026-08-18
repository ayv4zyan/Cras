# JSON Schema contract, tagged plan, Label ids

Web and Android share a **contract**, not compiled types: JSON Schema in `contracts/` is canonical, golden examples live in `contracts/examples/`, and each client hand-writes its models (Effect Schema, kotlinx.serialization). CI validates the goldens; we do not generate types and we do not introduce Kotlin/JS to share them.

Supabase table, RPC, and Edge Function payloads preserve this domain shape even when Postgres normalizes Tasks, Labels, joins, and Comments across rows. The exact Data API / Edge Function split remains open on [Which operations use Supabase Data API vs Edge Functions?](https://github.com/ayv4zyan/Cras/issues/26). Hex paths (`tasks/{aa}/{bb}/{id}.json`) were the withdrawn GitHub store layout — they are not part of the contract. A Task stores Label **ids**.

`plan` is a tagged union: omitted (Inbox), `{ date }` (Date-only, no mode), `{ type: "floating", date, time }`, or `{ type: "instant", at }` (UTC). For a new timed Task, clients resolve the Operator’s nullable `Settings.default_timed_plan_type`: an explicit Instant/Floating override, or `null` / missing Settings to inherit Deployment configuration seeded as Instant. A later default change never rewrites existing Tasks. Android uses the cached effective default while offline and falls back to Instant if no cache exists. Date-only is never an Instant (no fake midnight). Instants (`completedAt`, comment `createdAt`, `plan.at`) are RFC 3339 UTC. Details: [Where does the Instant vs Floating default live?](https://github.com/ayv4zyan/Cras/issues/21).

Each client derives **Today** from the viewing device’s local calendar date; MVP has no home timezone. Selecting a relative date resolves it immediately using that device’s clock. Voice resolves relative dates from the recording-start time and the recording device’s timezone. The Draft stores the resulting exact Date-only, Floating, or Instant plan—not a relative token—so midnight never silently changes it; the Operator can edit the visible exact date before acceptance. An Instant remains one UTC moment, although its displayed day and Today membership may differ by device.

Rejected: Effect Schema as the source of truth, OpenAPI, a single global clock mode, week-of-plan-date folders, `type` on a Date-only plan.

Details: [What is the shared task contract between web and Android?](https://github.com/ayv4zyan/Cras/issues/16).
