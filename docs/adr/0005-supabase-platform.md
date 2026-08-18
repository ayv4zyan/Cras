---
status: accepted
---

# Supabase is the sole backend platform

Cras MVP uses one hosted **Supabase** project as its entire backend platform and serves many Operators from one public Deployment. Postgres is the system of record, Auth plus RLS form the identity and authorization boundary, the Data API supports client CRUD, Realtime carries live changes, Storage is available for blobs, and Edge Functions host operations that need secrets or trusted server-side logic. Every Operator's data must be isolated from every other Operator. There is no separate Cloudflare deployment, Hono API, Hyperdrive connection, Durable Object, or custom Operator API token.

The web deployment and Android build contain the Supabase project URL and publishable key and act with the Operator's user JWT. Google is the only MVP authentication provider: web uses browser OAuth, Android uses native Google Sign-In, and any Google account may register. A secret / `service_role` key must never ship in web or Android. The exact boundary between direct Data API calls and Edge Functions remains on [Which operations use Supabase Data API vs Edge Functions?](https://github.com/ayv4zyan/Cras/issues/26).

The relational decisions remain: one row per Task; Labels plus a join table; Comments as child rows; Subtasks via `parent_id`; version compare-and-swap for overlapping writes; Android Outbox; web online-only; no merge UI.

Details: [Does Cras need a separate Cloudflare/Hono backend?](https://github.com/ayv4zyan/Cras/issues/25) and [How does an Operator start their own copy of Cras?](https://github.com/ayv4zyan/Cras/issues/17). Supersedes ADR 0004; ADR 0004 had superseded ADR 0002.
