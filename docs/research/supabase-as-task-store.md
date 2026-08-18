# Can Supabase be the task store?

**Status (2026-08-18):** research for [Can Supabase be the task store?](https://github.com/ayv4zyan/Cras/issues/23). Clients are a TypeScript web app and a **Kotlin** Android app, not React Native ([Which client stack do we lock for web and Android?](https://github.com/ayv4zyan/Cras/issues/12)). [Where do tasks live?](https://github.com/ayv4zyan/Cras/issues/13) is being superseded; the new leaning is Supabase.

Question from that ticket: the Operator has withdrawn GitHub as the task store. What do primary sources say about using Supabase as the system of record for a personal task app with a TypeScript web client and a Kotlin Android client?

**Verdict.** Official Supabase docs describe a **two-tier** architecture: a hosted (or self-hosted) project is Postgres plus Auth, PostgREST, Realtime, Storage, and optional Edge Functions; TypeScript and Kotlin clients talk to those HTTPS APIs with a **publishable / `anon` key** and a **user JWT**. No Cras-owned server is required for CRUD, Auth, Storage, or Realtime. That is a database with RLS and last-writer-wins row updates, **not** an offline sync service. Concurrent device writes to the same Task row overwrite unless the client adds its own compare-and-swap (`UPDATE … WHERE version = $expected`). Unique constraints reject collisions with HTTP **409**. Offline queueing is entirely the client's job. The Kotlin library is **community-maintained**, documented and tutorialled by Supabase, and not claimed as first-party.

---

## 1. Products in play, and what a client can use without a Cras-owned server

Each hosted project is one Postgres instance plus the services in front of it: GoTrue (Auth), PostgREST (Data REST API), Realtime, Storage, Edge Functions (Deno), Studio, `postgres-meta`, Supavisor, Envoy.

Source: [Architecture](https://supabase.com/docs/guides/getting-started/architecture).

| Product | What official docs say it is | Client without a Cras server? |
| --- | --- | --- |
| **Postgres** | The core store. “We do not abstract the Postgres database.” | Not over the Postgres wire from a SPA/phone. Use the Data API. Direct connection strings are for “trusted servers, workers, or tools.” |
| **Data API (PostgREST)** | Auto-generated REST at `https://<project_ref>.supabase.co/rest/v1/`. “You can use them **directly from the browser** (two-tier architecture), or as a complement to your own API server (three-tier).” | **Yes.** Official two-tier path. |
| **Auth (GoTrue)** | JWT API; stores users in the project's Postgres; SDKs attach the user token to data requests; RLS uses it. | **Yes.** Password, magic link/OTP, social (including native Google on Android). |
| **Realtime** | WebSocket: Broadcast, Presence, Postgres Changes. | **Yes.** Clients subscribe with the same URL + key + user JWT. |
| **Storage** | S3-compatible objects; metadata in Postgres; RLS on `storage.objects`. For **files**, not rows. | **Yes** for attachments. Wrong primitive for Task/Label rows. |
| **Edge Functions** | Hosted Deno TypeScript. “Server-side” code **on Supabase's edge**, not on a machine you operate. Clients invoke with `functions.invoke`. | **Optional.** Not required for a personal list. Needed if you want a secret-holding step (service role, Stripe, custom SMTP logic). |

Sources: [Architecture](https://supabase.com/docs/guides/getting-started/architecture); [Data REST API](https://supabase.com/docs/guides/api); [Auth](https://supabase.com/docs/guides/auth); [Realtime](https://supabase.com/docs/guides/realtime); [Storage](https://supabase.com/docs/guides/storage); [Edge Functions](https://supabase.com/docs/guides/functions); [Securing your data](https://supabase.com/docs/guides/database/secure-data).

**Frontend access, official wording:** “For frontend apps, the Data API is the usual choice.” Use the **publishable key** and RLS. Secret / `service_role` keys “are **never** safe to expose.”

Source: [Securing your data](https://supabase.com/docs/guides/database/secure-data).

You **can** disable the Data API if the app only uses Edge Functions or a direct Postgres connection — that would *create* a need for something server-side. The leaning (no Cras-owned server) is the opposite: leave the Data API on.

Source: [Securing your API](https://supabase.com/docs/guides/api/securing-your-api#disable-the-data-api).

### Official JS vs Kotlin

- **`@supabase/supabase-js`** is the first-party isomorphic JS library: Postgres, Realtime, Edge Functions, Auth, Storage. Browser `createClient(url, publishableKey)` is the documented SPA init.

  Source: [JavaScript: Introduction](https://supabase.com/docs/reference/javascript/introduction); [Initializing](https://supabase.com/docs/reference/javascript/initializing).

- **`supabase-kt`** is documented on supabase.com and used in the official Android Kotlin quickstart and Jetpack Compose tutorial. The same reference page states: “The Kotlin client library is created and maintained by the **Supabase community**, and is **not an official library**.” Minimum Android SDK **26** (desugaring for lower). It covers PostgREST, Auth, Storage, Realtime, Edge Functions.

  Sources: [Kotlin: Introduction](https://supabase.com/docs/reference/kotlin/introduction); [Use Supabase with Android Kotlin](https://supabase.com/docs/guides/getting-started/quickstarts/kotlin); [Build a Product Management Android App](https://supabase.com/docs/guides/getting-started/tutorials/with-kotlin).

There is no first-party “official Kotlin SDK” claim. There is first-party product support for calling the same APIs from Kotlin Android.

---

## 2. Auth from a static webapp and from Kotlin Android

### Keys: what they are, and why `service_role` is not a client key

| Type | Format | Privileges | Where docs allow it |
| --- | --- | --- | --- |
| Publishable | `sb_publishable_…` | Low | “Safe to expose online: web page, mobile or desktop app, … source code.” |
| Secret | `sb_secret_…` | Elevated; **BYPASSRLS** as `service_role` | “**Only** use in backend components … Edge Functions, microservices.” Secret keys used in a browser match `User-Agent` and return **401**. |
| `anon` | long-lived JWT | Low | Legacy publishable. Deprecated **end of 2026**. |
| `service_role` | long-lived JWT | Elevated; **BYPASSRLS** | Legacy secret. Same deprecation. |

API keys answer **what** is calling (the app). Auth answers **who**. With a publishable key and **no** user session the Postgres role is `anon`. After `signIn*` the role is `authenticated`.

Sources: [Understanding API keys](https://supabase.com/docs/guides/getting-started/api-keys); [Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security).

**Why not `service_role` / secret in either client:** “By design, this role has full access to your project's data. It also uses the `BYPASSRLS` attribute, skipping any and all Row Level Security policies.” “These should **never** be used in the browser or exposed to customers.” Same rule for mobile packages: publishable keys are listed for “mobile or desktop app”; secret keys must not be “bundle[d] in executables or packages for mobile.”

A note that matters if someone later adds Edge Functions: a Service Key “bypasses RLS **only when the request carries no user access token**. If the request carries one, it runs under the RLS policies of that signed-in user.”

Source: [Row Level Security — Bypassing](https://supabase.com/docs/guides/database/postgres/row-level-security#bypassing-row-level-security).

### RLS is the actual permission layer

“A table in an exposed schema **without RLS is readable and writable by anyone with your publishable key**.” RLS is on by default for Table Editor tables; raw SQL tables must `ENABLE ROW LEVEL SECURITY` themselves. Until you write policies, a publishable-key client sees **no rows**.

Grants and policies are two checks: grants decide whether `anon` / `authenticated` / `service_role` can `SELECT`/`INSERT`/`UPDATE`/`DELETE` at all; policies decide **which rows**. New `public` tables historically get all four privileges for all three roles — policies do **not** revoke those grants. A missing grant is Postgres `42501`. A `USING` clause that filters the row out on `UPDATE`/`DELETE` raises **nothing** and matches **zero rows**.

The documented personal-todos policy is exactly `auth.uid() = user_id`.

Sources: [Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security); [Securing your API](https://supabase.com/docs/guides/api/securing-your-api).

### Sessions, JWTs, static SPA

Default: a session “lasts indefinitely” and a user “can have an **unlimited number** of active sessions on as many devices.” Access JWT: typically **5 minutes–1 hour** (default **1 hour**). Refresh token: single-use, does not expire, with a 10-second reuse window and parent-token recovery so a dropped response does not kill the session.

Two documented ways to get tokens:

1. **Implicit flow** — redirect fragment `#access_token=…&refresh_token=…`. “The implicit flow **only works on the client**.” Browsers do not send the fragment to a server. Client libraries extract tokens and “**Persist this information to local storage**.” This is the documented client-only SPA path.
2. **PKCE** — redirect `?code=…`, then `exchangeCodeForSession(code)`. Code lives **5 minutes**, one exchange. Verifier must stay on the **same browser and device** that started the flow. Official docs present PKCE as the server-side / SSR path; a static SPA can still run it entirely in the browser (`detectSessionInUrl: true`, `flowType: 'pkce'`) because the verifier is local. No `client_secret` is required (unlike GitHub’s web OAuth exchange).

`createClient` options: `auth.persistSession` “automatically save the user session into **local storage**”; `autoRefreshToken`; `detectSessionInUrl`. That is session persistence, not a task cache.

HTTP-only cookies: “only for apps that use the traditional server-only web app.” “If your app uses any client side JavaScript … HTTP-Only cookies is **not feasible**” because the browser would not hold the tokens. That is a **three-tier** pattern.

Sources: [User sessions](https://supabase.com/docs/guides/auth/sessions); [Implicit flow](https://supabase.com/docs/guides/auth/sessions/implicit-flow); [PKCE flow](https://supabase.com/docs/guides/auth/sessions/pkce-flow); [Initializing](https://supabase.com/docs/reference/javascript/initializing).

**What a static SPA can do (first-party):**

- Ship URL + publishable/`anon` key in the bundle.
- `signInWithPassword`, magic link / email OTP, `signInWithOAuth({ provider })`, `signInWithIdToken` (Google One Tap / GIS). Implicit OAuth completes in the browser. PKCE completes in the browser if the redirect lands on the same origin/device.
- Call PostgREST, Storage, Realtime, `functions.invoke` with the user JWT the SDK attaches.

**What a static SPA cannot do:**

- Hold a secret / `service_role` key.
- Complete **server-only** PKCE (cookie session the JS app cannot read).
- Rely on the built-in Auth email provider beyond **2 emails/hour** (custom SMTP required to raise that).
- Treat the publishable key as access control. Without RLS + grants, the key is the whole door.

Email/OTP rate limits (Auth, not Data API): built-in SMTP **2 emails/hour** project-wide; OTP default **30/hour** project-wide and **60 s** per user; `/token` refresh **1800/hour/IP**; verify **360/hour/IP**; over limit → **429**.

Sources: [Auth](https://supabase.com/docs/guides/auth); [Login with Google](https://supabase.com/docs/guides/auth/social-login/auth-google); [Rate limits](https://supabase.com/docs/guides/auth/rate-limits).

### Kotlin Android

Official Compose tutorial: `createSupabaseClient(url, publishableKey)` with `Postgrest`, `Auth` (`flowType = FlowType.PKCE`, custom `scheme`/`host`), `Storage`; email password `signInWith(Email)` / `signUpWith(Email)`; Google `signInWith(Google)` plus a `DeepLinkHandlerActivity` that calls `handleDeeplinks`. Keys from `local.properties` → `BuildConfig` (do not commit).

Native Google (first-party Auth guide, Kotlin Android section): Credential Manager + `GetGoogleIdOption` + `supabase.auth.signInWith(IDToken)` with the Google ID token. Needs **both** a Web OAuth client ID and an Android client ID (SHA-1). No app secret. Nonce recommended.

Sources: [with-kotlin tutorial](https://supabase.com/docs/guides/getting-started/tutorials/with-kotlin); [Login with Google — Kotlin (Android)](https://supabase.com/docs/guides/auth/social-login/auth-google).

Unlimited sessions per user is the default, so web + phone can stay signed in together unless the project enables Pro-only “Single session per user.”

---

## 3. Concurrent writes

Supabase does **not** document a GitHub-style blob SHA / `expectedHeadOid` on every row. PostgREST `PATCH`/`UPDATE` applies to every row matching the filter. `supabase-js` `.update({…}).eq('id', id)` is last-writer-wins on the columns you send.

Sources: [JavaScript: update](https://supabase.com/docs/reference/javascript/update); [Data REST API](https://supabase.com/docs/guides/api).

Postgres default isolation is **Read Committed**: each statement sees only committed data; two concurrent `UPDATE`s of the same row serialize on the row lock; the later commit remains.

Source: [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html).

| What two devices do | What the store does |
| --- | --- |
| Update **different** Task rows | Both commits succeed. Independent rows. |
| Update the **same** Task, each sending **only** the changed column (phone: `completed_at`; web: `title`) | Both commits succeed. Columns merge. |
| Update the same Task, each sending a **full row** (or overlapping columns) | Later commit **overwrites**. No merge, no 409. |
| `upsert` on the primary/unique key | Insert or overwrite. Official: “equivalent of `.insert()` if … doesn't exist, or if it does exist, perform an alternative action.” |
| Insert a second Label with the same unique `(owner, name)` | Postgres `23505` → PostgREST **HTTP 409** uniqueness violation. |
| `UPDATE … WHERE id = $id AND version = $seen` (app-owned column) and another device already incremented `version` | Zero rows updated. Client must treat empty `select()` as a conflict and re-GET. This compare-and-swap is **your schema**, not a Supabase product feature. |

Unique / PK / FK failures:

- `23505` uniqueness → **409**
- `23503` foreign key → **409**
- `42501` privilege → **401** (anon) or **403** (authenticated)

Sources: [JavaScript: upsert](https://supabase.com/docs/reference/javascript/upsert); [PostgREST Errors](https://docs.postgrest.org/en/v14/references/errors.html); [PostgreSQL unique constraints](https://www.postgresql.org/docs/current/ddl-constraints.html#DDL-CONSTRAINTS-UNIQUE-CONSTRAINTS).

There is **no** documented server-side CRDT, OT, or per-field merge for table rows. Realtime delivers the **resulting** row after the winner commits; it does not resolve the write.

RLS nuance: an `UPDATE` whose `USING` clause fails looks like “success, 0 rows,” not a 409. Always `.select()` after write if you need to know whether the row moved.

Source: [Row Level Security — Test your policies](https://supabase.com/docs/guides/database/postgres/row-level-security#test-your-policies).

---

## 4. Offline: what Supabase itself does not do

Nothing in the Auth, Data API, Realtime, Storage, or client-init docs offers an **offline replica, write queue, or sync engine**.

Documented facts that bound the gap:

- Data API and Auth are **HTTPS** to the project URL. A write that never reaches PostgREST is not in the store.
- Realtime is a **live WebSocket**. Offline clients do not receive Postgres Changes; there is no documented catch-up cursor for missed row events (Broadcast Replay is for Broadcast messages, 72 h / 25 messages per request — not a task table).
- `persistSession` / localStorage (web) and the Android client's session store keep **Auth tokens**, not Tasks.
- There is no first-party “Supabase Offline” product in the official guides comparable to a local SQLite replica with automatic merge.

**Implication (fact, not a product decision):** Outbox, local cache, and retry-on-reconnect are client work, same shape as they would be against GitHub, except the remote conflict primitive is a row `UPDATE` filter (or unique 409), not a git SHA.

Sources: [Initializing](https://supabase.com/docs/reference/javascript/initializing); [Realtime](https://supabase.com/docs/guides/realtime); [Realtime Limits](https://supabase.com/docs/guides/realtime/limits); [Data REST API](https://supabase.com/docs/guides/api).

---

## 5. Realtime vs request/response for a two-device personal list

**Request/response** is enough to be correct: each device `select`s (optionally with `.eq('user_id', …)` — official RLS performance advice) and `update`s on check-off. The other device sees changes on next fetch.

**Postgres Changes** is the first-party live path: add the table to publication `supabase_realtime`, subscribe with the user JWT. Events are authorized with the same RLS. Payload includes `new` (and `old` only if `REPLICA IDENTITY FULL`). Official example table is literally `todos`. Kotlin: `postgresChangeFlow<PostgresAction>`. Filters (`id=eq.…`) are evaluated **on the server**. `select: ['id', 'title']` can shrink payloads.

Documented limits that matter at two devices, not 10k users:

- Postgres Changes payload **1,024 KB**. Over that, `new`/`old` keep only fields ≤ **64 bytes**.
- Free plan: **200** concurrent connections, **100** messages/s, **100** channel joins/s. A personal pair of sockets is nowhere near this.
- Billing: Free **2 million** Realtime messages / **200** peak connections; Pro **5 million** / **500**.
- “When you make a single change to a table with 100 subscribed users, Realtime performs **100 authorization checks**.” Two subscribers ⇒ two checks. Throughput “scales with the number of subscribers, not the write rate,” and “larger compute add-ons don't meaningfully increase Postgres Changes throughput.” Irrelevant at n=2; relevant if the leaning later becomes many operators on one project, all listening to one table.
- `DELETE` events: filterable only with `REPLICA IDENTITY FULL`. “RLS policies are **not** applied to `DELETE` statements” for the deleted row (Postgres cannot re-check a gone row).

Broadcast / Presence are optional (typing, “other device online”). They are not the store.

Public Realtime connections without a user JWT are limited to **24 hours** unless upgraded with Auth.

Sources: [Postgres Changes](https://supabase.com/docs/guides/realtime/postgres-changes); [Realtime Limits](https://supabase.com/docs/guides/realtime/limits); [API keys — known limitations](https://supabase.com/docs/guides/getting-started/api-keys); [Billing](https://supabase.com/docs/guides/platform/billing-on-supabase).

**For two personal devices:** either poll/refetch or subscribe to `todos`/`tasks`. Realtime is convenience, not durability. The write is still a PostgREST `PATCH`.

---

## 6. Rate limits, payload size, check-off pattern

There is **no** published GitHub-style “5,000 REST req/hour + 80 content-creating commits/minute” cap on PostgREST. The Data API is “a very thin layer on top of Postgres” and “can serve thousands of simultaneous requests.” Abuse controls that *are* documented:

- Auth endpoints: table in §2 (emails, OTP, `/token`). Check-off does **not** hit these.
- Optional `pgrst.db_pre_request` function for **custom** IP/user quotas on Data API writes (`POST`/`PATCH`/`DELETE` only; `GET` is read-only and may hit replicas). Example in docs: >100 writes / 5 min / IP → HTTP 420. Off by default.
- Fair Use / overdue billing: **402** on **all** API requests; Free DB over **500 MB** → **read-only** (`cannot execute INSERT in a read-only transaction`).
- Realtime plan caps (§5).
- Edge Functions (if used): 256 MB RAM; wall clock 150 s Free / 400 s paid; 2 s CPU; Free **500,000** invocations.

Sources: [Data REST API](https://supabase.com/docs/guides/api); [Securing your API — pre-request](https://supabase.com/docs/guides/api/securing-your-api); [Auth rate limits](https://supabase.com/docs/guides/auth/rate-limits); [Database size](https://supabase.com/docs/guides/platform/database-size); [Billing FAQ](https://supabase.com/docs/guides/platform/billing-faq); [Edge Functions limits](https://supabase.com/docs/guides/functions/limits).

**Payload / row count**

- Default Data API page size on hosted projects is documented as a **maximum of 1,000 rows** per request (changeable in Project API Settings). Keep it low “to limit the payload size of accidental or malicious requests.”
- Storage files: Free **50 MB** max per file (global); Pro up to **500 GB** configurable. Irrelevant for Task rows.
- Realtime Postgres Changes: 1,024 KB as above.

Source: [C# Fetch data](https://supabase.com/docs/reference/csharp/select) (same platform default; wording is first-party); [Storage file limits](https://supabase.com/docs/guides/storage/uploads/file-limits).

**One check-off:** one `PATCH` (optionally `.select()` to confirm). That is one HTTP RTT + one row lock. Ten rapid check-offs are ten `PATCH`es; nothing in PostgREST docs asks you to serialize them or wait 1 s (contrast GitHub Contents). Two devices checking **different** tasks do not contend.

Egress (all services): Free **5 GB** uncached / **5 GB** cached per org; Pro **250 GB**. A personal task list will not see this first.

Source: [Billing](https://supabase.com/docs/guides/platform/billing-on-supabase).

---

## 7. Sensible shapes

Official modeling guidance is relational Postgres, not a JSON document store and not Storage objects.

**One row per Task.** Matches the documented `todos` example (`id`, `task`, plus whatever columns you add). Primary key: `uuid` or identity. Filter/index `user_id` if more than one Auth user will ever exist. Completing a Task is `UPDATE` of `completed_at` (or equivalent) on that row — not a rewrite of a blob.

Source: [Postgres Changes quick start](https://supabase.com/docs/guides/realtime/postgres-changes); [Tables and Data](https://supabase.com/docs/guides/database/tables).

**Labels.** First-party many-to-many is a **join table**:

```sql
-- same pattern as movies / actors / performances
create table labels ( id uuid primary key, name text not null, color text, … );
create table task_labels (
  task_id uuid references tasks,
  label_id uuid references labels,
  primary key (task_id, label_id)
);
```

Unique Label **names** are a unique constraint (per operator: `unique (user_id, name)` if many Auth users share a project). Rename keeps `id`. `supabase-js` can embed: `.from('tasks').select('*, labels(*)')` through the join.

Sources: [Tables — many-to-many](https://supabase.com/docs/guides/database/tables#joining-tables-with-foreign-keys); [JavaScript: select](https://supabase.com/docs/reference/javascript/select).

**JSONB documents.** Official: use `jsonb` for “unstructured or … variable schema” (webhook payloads). “**Don't go overboard.** … most of the benefits of a relational database come from the ability to query and join structured data, and the **referential integrity** that brings.” A single `tasks.json` column would throw away Label FKs, unique names, and per-row check-off isolation. `pg_jsonschema` can CHECK a document if you still want one.

Source: [Managing JSON](https://supabase.com/docs/guides/database/json).

**Storage objects.** “Store and serve images, videos, documents, and general-purpose **files**.” Metadata lives in `storage.objects`; there is no query/join/unique-constraint story for Task fields. Fine for voice-capture audio; not the Task table.

Source: [Storage](https://supabase.com/docs/guides/storage).

**Comments / Subtasks.** Comments: child table `task_id` + `created_at` + body (many rows per Task). Subtasks: either a `parent_id` on `tasks` with a CHECK that children are leaves, or a separate table — both are ordinary FK shapes. Docs do not prescribe Cras's one-level rule; they give you FKs and CHECKs.

---

## 8. Hosted project vs self-hosted; one project vs many operators

### Hosted

- An **organization** has one plan (Free / Pro / Team / Enterprise) and many **projects**.
- Each project is “a dedicated Supabase instance with all of its sub-services including Storage, Auth, Functions and Realtime” and “a dedicated Postgres instance running on its own server.”
- Free: **two active projects** (paused do not count). Free DB **500 MB/project** then read-only. **50k** Auth MAU. Projects on Free **pause**; Pro orgs do not pause.
- Quotas (egress, MAU, Realtime messages, Storage size, Edge invocations) are **org-wide sums**. Compute is **per project**. Extra projects on Pro start at ~$10/month each after $10 of compute credits.
- You cannot mix Free and Pro projects in one org.

Sources: [About billing](https://supabase.com/docs/guides/platform/billing-on-supabase); [Billing FAQ](https://supabase.com/docs/guides/platform/billing-faq).

### Self-hosted

Official path: Docker Compose from the Supabase repo. “Self-hosted Supabase runs as a **single project**” — Studio has no multi-org/multi-project. You own OS, hardening, backups, HA, monitoring. **Unavailable** vs hosted: branching, PITR/managed backups, advanced metrics, analytics/vector buckets, ETL, Management API. Community-supported. CLI local stack is **not** production self-host. No telemetry from the Compose stack.

Source: [Self-Hosting](https://supabase.com/docs/guides/self-hosting).

Architecture principle: “Our cloud offering is compatible with our self-hosted product” and they prefer `pg_dump` / CSV for portability.

Source: [Architecture — Everything is portable](https://supabase.com/docs/guides/getting-started/architecture).

### One project per Operator vs one project, many operators

Primary sources do **not** pick this. They only constrain it:

| | One hosted project per Operator | One project, many Auth users (RLS) |
| --- | --- | --- |
| Isolation | Separate VM, Auth DB, keys, RLS blast radius. Steal of one publishable key + broken RLS cannot see another Operator's project. | Shared Postgres. Safety is `auth.uid() = user_id` (and grants). A bad policy or leaked `service_role` is everyone's data. |
| Auth | One (or few) users in `auth.users`. | Many users; MAU counted per login/refresh. |
| Cost | Each extra project is another compute line. Free cap = 2 active. | One compute; org quotas shared. The intended multi-tenant shape in the RLS + SAML docs. |
| Realtime | Two sockets, one publication. | Postgres Changes authorizes **per subscriber**; cost scales with listener count. |
| Self-host | One Compose stack **is** one project. | Same stack; tenancy is RLS. |

SAML docs explicitly describe “multi-tenant SSO for multiple clients or organizations **within a single application**” via `sso_provider_id` in the JWT + RLS. That is a capability, not a requirement for a personal app.

Source: [SAML 2.0](https://supabase.com/docs/guides/auth/enterprise-sso/auth-sso-saml).

---

## 9. Precedents and constraints (official, not folklore)

- The **todo list** is Supabase's own RLS and Realtime tutorial object (`todos`, `auth.uid() = user_id`). A personal Task table is inside the documented grain.
- Two-tier (browser → PostgREST + Auth + RLS) is first-class, not a hack.
- Keys in the client **must** be publishable/`anon`. Service role is BYPASSRLS and is 401'd in browsers.
- Kotlin is supported in official guides; the library is labeled **community**.
- Writes are SQL `UPDATE`s. Optimistic concurrency is a `WHERE` you write. Unique names are a unique index. There is no merge.
- JSONB and Storage are documented as unstructured / files. Relational + join tables are the Label shape they teach.
- Hosted = one VM per project; two Free projects; org-level quotas. Self-host = one project, you run it.
- No official offline-first store. Session persistence ≠ Task persistence.

---

## 10. What this means for the new leaning

The leaning (Supabase as system of record; TypeScript web + Kotlin Android; no Cras-owned server) is **supported by primary sources**, with the following bounds a grilling ticket should not paper over:

- **Yes:** hosted (or self-hosted) Postgres via the Data API is a real multi-device store. Check-off is one `PATCH`. Different tasks do not race. Labels are a join table. Auth for a static SPA does **not** need a `client_secret` (implicit or in-browser PKCE). Android has a documented publishable-key + PKCE/deep-link + native Google ID-token path. Live two-device update is optional Postgres Changes.
- **Not provided by Supabase:** offline Outbox, field-level merge, or an official Kotlin guarantee. Same-row overlapping writes last-writer-win unless Cras adds `version`/`updated_at` CAS. RLS is mandatory; a missing policy on `public` is a public table. `service_role` in either binary is a total data leak. Edge Functions are optional hosted compute, not a substitute for deciding tenancy or Outbox.

This note does **not** choose tenancy, Auth UX, or Outbox. Those remain grilling.

---

## Sources

- https://supabase.com/docs/guides/getting-started/architecture — project services; Postgres core; portability
- https://supabase.com/docs/guides/api — PostgREST; two-tier from the browser
- https://supabase.com/docs/guides/api/securing-your-api — grants vs RLS; default privileges; pre-request; disable Data API
- https://supabase.com/docs/guides/database/secure-data — frontend + publishable key; never expose service role
- https://supabase.com/docs/guides/getting-started/api-keys — publishable / secret / anon / service_role; BYPASSRLS; browser 401 on secrets; Realtime 24 h
- https://supabase.com/docs/guides/database/postgres/row-level-security — exposed table without RLS; `auth.uid()`; grants; 0-row updates; bypass rules
- https://supabase.com/docs/guides/auth — JWT + RLS; methods; MAU pricing pointers
- https://supabase.com/docs/guides/auth/sessions — default unlimited multi-device sessions; JWT lifetime; refresh reuse
- https://supabase.com/docs/guides/auth/sessions/implicit-flow — client-only; tokens in fragment; localStorage
- https://supabase.com/docs/guides/auth/sessions/pkce-flow — `code` exchange; same device; no server required if the SPA holds the verifier
- https://supabase.com/docs/guides/auth/rate-limits — email/OTP/token 429s
- https://supabase.com/docs/guides/auth/social-login/auth-google — web OAuth; Kotlin/Android ID token + Credential Manager
- https://supabase.com/docs/reference/javascript/introduction — first-party JS client
- https://supabase.com/docs/reference/javascript/initializing — persistSession / localStorage / detectSessionInUrl
- https://supabase.com/docs/reference/javascript/update — filtered UPDATE
- https://supabase.com/docs/reference/javascript/upsert — onConflict overwrite
- https://supabase.com/docs/reference/javascript/select — embeds / join tables
- https://supabase.com/docs/reference/kotlin/introduction — community Kotlin client; min SDK 26
- https://supabase.com/docs/guides/getting-started/quickstarts/kotlin — official Android Kotlin quickstart
- https://supabase.com/docs/guides/getting-started/tutorials/with-kotlin — Compose + Postgrest/Auth/Storage; PKCE deep links
- https://supabase.com/docs/guides/realtime — Broadcast / Presence / Postgres Changes
- https://supabase.com/docs/guides/realtime/postgres-changes — todos example; RLS; replica identity; Kotlin API; scaling
- https://supabase.com/docs/guides/realtime/limits — connections, messages/s, 1,024 KB row payloads
- https://supabase.com/docs/guides/storage — files, not rows
- https://supabase.com/docs/guides/storage/uploads/file-limits — 50 MB Free / 500 GB Pro
- https://supabase.com/docs/guides/functions — hosted Deno; invoke from clients
- https://supabase.com/docs/guides/functions/limits — runtime / invocation caps
- https://supabase.com/docs/guides/database/tables — PK, FK, many-to-many join tables
- https://supabase.com/docs/guides/database/json — jsonb for unstructured data; don't replace relations
- https://supabase.com/docs/guides/platform/billing-on-supabase — plans, per-project compute, org quotas
- https://supabase.com/docs/guides/platform/billing-faq — two Free projects; one plan per org
- https://supabase.com/docs/guides/platform/database-size — 500 MB Free → read-only
- https://supabase.com/docs/guides/self-hosting — Docker; single project; what you operate
- https://docs.postgrest.org/en/v14/references/errors.html — 23505 → 409
- https://www.postgresql.org/docs/current/ddl-constraints.html — unique constraints
- https://www.postgresql.org/docs/current/transaction-iso.html — Read Committed; later UPDATE wins
- https://supabase.com/docs/reference/csharp/select — hosted max 1,000 rows per request
- https://supabase.com/docs/guides/auth/enterprise-sso/auth-sso-saml — multi-tenant-in-one-app is possible via RLS
