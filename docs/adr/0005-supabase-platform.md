---
status: accepted
---

# Supabase is the sole backend platform

Cras MVP uses one hosted **Supabase** project as its entire backend platform and serves many Operators from one public Deployment. Postgres is the system of record, Auth plus RLS form the identity and authorization boundary, the Data API supports client CRUD, Realtime carries live changes, Storage is available for blobs, and Edge Functions host operations that need secrets or trusted server-side logic. Every Operator's data must be isolated from every other Operator. There is no separate Cloudflare deployment, Hono API, Hyperdrive connection, Durable Object, or custom Operator API token.

The web deployment and Android build contain the Supabase project URL and publishable key and act with the Operator's user JWT. Google is the only MVP authentication provider: web uses browser OAuth, Android uses native Google Sign-In, and any Google account may register. A secret / `service_role` key must never ship in web or Android. The exact boundary between direct Data API calls and Edge Functions remains on [Which operations use Supabase Data API vs Edge Functions?](https://github.com/ayv4zyan/Cras/issues/26).

Voice capture uses one authenticated Edge Function as its provider boundary. One Deployment-wide DeepInfra credential lives only as an Edge Function secret. The function orchestrates speech-to-text plus structured extraction; clients upload mono 16 kHz PCM WAV, and the function validates rather than transcodes. Supabase also holds a Deployment-managed **Voice model catalog**, seeded by Cras migrations; Operators may read enabled choices, but only the Deployment maintainer may change them. Shared Operator **Settings** stores nullable stable catalog keys and an optional custom extractor prompt. Null inherits the current Deployment default: Voxtral Small for speech-to-text and Gemma 4 26B-A4B-it for extraction. The Edge Function resolves Settings against the current catalog and validates the effective models before calling DeepInfra; a disabled stored choice falls back to the current default. Server-side audio is never retained. Public launch requires the limits and circuit breaker tracked by [How is shared AI usage bounded?](https://github.com/ayv4zyan/Cras/issues/29).

The relational decisions remain: one row per Task; Labels plus a join table; Comments as child rows; Subtasks via `parent_id`; version compare-and-swap for overlapping writes; Android Outbox; web online-only; no merge UI.

Details: [Does Cras need a separate Cloudflare/Hono backend?](https://github.com/ayv4zyan/Cras/issues/25), [How does an Operator start their own copy of Cras?](https://github.com/ayv4zyan/Cras/issues/17), [How do DeepInfra credentials live on web and Android?](https://github.com/ayv4zyan/Cras/issues/18), and [Where does Operator voice config live?](https://github.com/ayv4zyan/Cras/issues/19). Supersedes ADR 0004; ADR 0004 had superseded ADR 0002.
