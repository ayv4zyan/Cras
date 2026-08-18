# Hosted Postgres behind Hono; clients never touch Supabase

Cras MVP keeps Tasks in **hosted Supabase Postgres**. Web and Android talk only to a **Hono** API on **Cloudflare Workers**. The Worker reaches Postgres through **Hyperdrive + SQL** (Supabase’s direct connection string, not the pooler). Clients send an **Operator API token**; they do not ship a publishable key, do not use Supabase Auth, and do not use PostgREST or Realtime.

One row per Task; Labels and a join table; Comments as child rows; Subtasks are Tasks with `parent_id` (still one level). Hono request/response bodies are the JSON Schema contract from [What is the shared task contract between web and Android?](https://github.com/ayv4zyan/Cras/issues/16) — not raw SQL rows and not hex file paths. Same-Task overlapping saves use a `version` compare-and-swap (zero rows → client reloads). Two creates stay two Tasks. Android may hold an **Outbox**; web is online-only. There is no merge UI.

Live updates while both devices are open use **SSE** to a Cloudflare Durable Object: Hono writes the row, then notifies the object. Not a webhook to the device, not client Realtime.

Audience is unchanged: one **Operator**, one Worker, one project, personal MVP — not multi-tenant hosting. First-run (Hono URL + token) stays on [How does an Operator start their own copy of Cras?](https://github.com/ayv4zyan/Cras/issues/17).

Rejected: GitHub as system of record; two-tier PostgREST from the client; `service_role` in a client; Supabase Auth / Realtime on the client; last-writer-wins silent overwrite; local-first on every client; one deployment serving many Operators.

Details: [Where do tasks live if the store is Supabase?](https://github.com/ayv4zyan/Cras/issues/24). Supersedes [Where do tasks live?](https://github.com/ayv4zyan/Cras/issues/13) and ADR 0002.
