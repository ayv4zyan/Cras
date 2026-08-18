# Supabase account-lifecycle research for Cras

Verified against live first-party documentation on **2026-08-18**. This is a factual research note, not a product decision or implementation specification.

## Executive facts

- `auth.admin.deleteUser(userId)` is privileged, requires a service-role/secret credential, and must run on a trusted server. Hard deletion is the default; `shouldSoftDelete: true` hashes the user identifier and is documented as non-reversible. ([Admin `deleteUser`](https://supabase.com/docs/reference/javascript/auth-admin-deleteuser))
- Hard-deleting the Auth user removes `auth.users`, cascades to `auth.sessions`, and invalidates refresh tokens. It **does not retroactively invalidate already-issued access-token JWTs**; those remain usable until `exp` unless a sensitive operation additionally validates the token's `session_id` against `auth.sessions`. ([User Management — deleting users/removing account access](https://supabase.com/docs/guides/auth/managing-user-data), [User sessions](https://supabase.com/docs/guides/auth/sessions))
- Auth-user deletion fails while that user owns Supabase Storage objects. Those objects must first be deleted or their ownership reassigned. ([User Management — deleting users](https://supabase.com/docs/guides/auth/managing-user-data))
- A temporary Auth ban blocks later sign-in but does not revoke existing sessions. Marking only an application row as deleted likewise leaves the Auth identity able to authenticate and refresh. ([User Management — removing account access](https://supabase.com/docs/guides/auth/managing-user-data))
- `signOut({ scope: 'global' })` revokes refresh tokens/sessions, but access-token JWTs remain valid until expiration. The Admin sign-out API is documented as taking a valid logged-in JWT, not merely a user ID. ([Client `signOut`](https://supabase.com/docs/reference/javascript/auth-signout), [Admin `signOut`](https://supabase.com/docs/reference/javascript/auth-admin-signout))

## 1. Delete and session-revocation ordering

### What the platform guarantees

1. Storage ownership is a hard prerequisite: an Auth user who owns Storage objects cannot be deleted.
2. A hard Auth deletion cascades to Auth sessions and prevents any future refresh.
3. Any access JWT already issued may continue to authorize ordinary API calls until its `exp` time.
4. For operations that must notice deletion immediately, Supabase specifically documents checking the JWT's `session_id` against `auth.sessions`. Once the user is deleted, that session row is absent.

These facts mean that “revoke sessions” and “make every current bearer token unusable” are not equivalent operations in Supabase's stateless-JWT model. The documented default access-token lifetime is one hour; Supabase discourages most configurations below five minutes. ([User sessions](https://supabase.com/docs/guides/auth/sessions))

### Ordering constraints, without choosing a Cras flow

- Storage object deletion/reassignment has to precede `auth.admin.deleteUser`.
- Any database rows linked to `auth.users(id)` behave according to their foreign-key action. PostgreSQL's `ON DELETE CASCADE` removes them; `SET NULL` preserves them while clearing the reference; `RESTRICT`/`NO ACTION` can block deletion. ([PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html))
- Supabase advises referencing only primary keys in managed schemas such as `auth.users`, because other managed constraints and objects may change. ([User Management](https://supabase.com/docs/guides/auth/managing-user-data))
- Auth API operations and application-table mutations are different service calls, so the cited docs provide no cross-service atomic transaction spanning them. Any multi-step purge therefore needs resumable/idempotent treatment if Cras adopts one; that is an engineering inference, not a stated Supabase guarantee.

## 2. Reauthentication and identity verification

Supabase documents three different mechanisms; they should not be conflated:

| Mechanism | First-party documented meaning | Limitation relevant here |
|---|---|---|
| `auth.reauthenticate()` nonce | Used with `updateUser()` when Secure Password Change is enabled. A nonce is sent to confirmed email, falling back to confirmed phone. A session less than 24 hours old counts as recent for that password-change rule. ([Reauthentication API](https://supabase.com/docs/reference/python/auth-reauthentication)) | The documentation scopes this mechanism to password updates; it does **not** document it as a generic “confirm identity before account deletion” primitive. |
| MFA AAL2 | AAL2 means conventional sign-in plus a verified second factor. TOTP/phone challenge-and-verify upgrades the session to AAL2. ([MFA guide](https://supabase.com/docs/guides/auth/auth-mfa), [AAL API](https://supabase.com/docs/reference/javascript/auth-mfa-getauthenticatorassurancelevel)) | Only available to users who have an enrolled/verified second factor; AAL1 includes OAuth, OTP, magic-link, and password login. |
| Fresh OAuth/OIDC authentication | OpenID Connect defines `max_age`; `max_age=0` is equivalent to `prompt=login`, and use of `max_age` requires `auth_time` in the returned ID token. ([OpenID Connect Core 1.0](https://openid.net/specs/openid-connect-core-1_0.html)) | Google documents `prompt=consent` and `prompt=select_account`, but its current OAuth parameter table does not list `prompt=login`; consent or account selection is not necessarily fresh credential entry. ([Google OAuth web-server flow](https://developers.google.com/identity/protocols/oauth2/web-server), [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect)) |

**Uncertainty:** the reviewed Supabase docs do not expose a single provider-neutral, arbitrary-operation “recently reauthenticate this user” API for account deletion. A product requiring step-up verification must specify which of fresh login, MFA, OTP, or another proof is acceptable and then verify what its chosen provider/client SDK actually supports.

## 3. Edge Function authentication and JWT verification

The live documentation currently has a migration-sensitive inconsistency:

- The current **Securing Edge Functions** guide says that browser/client calls carrying a user JWT should keep gateway `verify_jwt = true` (the default), then use `withSupabase({ auth: 'user' })`; its user-scoped client follows the caller's RLS policies, while its admin client bypasses RLS. It says scheduled/service-to-service calls should use a secret API key, set `verify_jwt = false`, and validate with `auth: 'secret'`. ([Securing Edge Functions](https://supabase.com/docs/guides/functions/auth))
- The current **JWT Signing Keys** migration guide warns that Edge Functions using the Verify JWT setting may break when rotating away from the legacy JWT secret, instructs turning that setting off, and points implementations to `supabase.auth.getClaims()` or explicit JWKS verification. ([JWT Signing Keys](https://supabase.com/docs/guides/auth/signing-keys), [`getClaims`](https://supabase.com/docs/reference/javascript/auth-getclaims))

This appears tied to the active transition from legacy JWT-secret/API-key behavior to asymmetric signing keys plus publishable/secret keys. It should be resolved against the exact Supabase project key mode and deployed SDK version before Cras codifies a rule. Do not copy an older blanket statement such as “Edge Functions automatically verify every JWT” into a durable spec.

Regardless of verification location, an account-lifecycle Edge Function that invokes Admin Auth or bypasses RLS needs a server-only secret/service credential; Supabase says never expose it in a browser. ([Admin `deleteUser`](https://supabase.com/docs/reference/javascript/auth-admin-deleteuser), [API keys](https://supabase.com/docs/guides/getting-started/api-keys))

The newer `sb_publishable_...` and `sb_secret_...` API keys are not JWTs. Older examples that treat an anon/service key as an `Authorization: Bearer <JWT>` credential can therefore be stale; user JWTs belong in `Authorization`, while current service-to-service guidance places a secret key in `apikey`. ([Edge Function auth headers](https://supabase.com/docs/guides/functions/auth-headers), [Securing Edge Functions](https://supabase.com/docs/guides/functions/auth))

## 4. Postgres RPC and function security

- PostgreSQL functions are `SECURITY INVOKER` by default: they execute with the caller's privileges. `SECURITY DEFINER` executes with the owner's privileges. ([PostgreSQL `CREATE FUNCTION`](https://www.postgresql.org/docs/current/sql-createfunction.html))
- For `SECURITY DEFINER`, PostgreSQL explicitly requires a safe `search_path` excluding schemas writable by untrusted users, and recommends tightly controlling who can create/execute objects. ([PostgreSQL `CREATE FUNCTION`](https://www.postgresql.org/docs/current/sql-createfunction.html), [Function Security](https://www.postgresql.org/docs/current/perm-functions.html))
- Supabase's function guide additionally warns that database functions are executable by every role by default; it documents explicitly revoking `EXECUTE` and granting it only to intended roles. It recommends `SECURITY INVOKER`, and for any definer function shows an empty `search_path` with schema-qualified objects. ([Database Functions](https://supabase.com/docs/guides/database/functions))
- Supabase's Data API applies both grants and RLS. A secret/service role has `BYPASSRLS`; client-visible RPC privileges therefore need deliberate `GRANT`/`REVOKE`, and definer functions require special scrutiny. ([Securing the Data API](https://supabase.com/docs/guides/api/securing-your-api), [Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security))
- Database functions suit data-intensive work close to the database; Supabase positions Edge Functions for low-latency server-side TypeScript and integrations. ([Database Functions](https://supabase.com/docs/guides/database/functions))

A pure database state transition can be transactional inside an RPC. Admin Auth deletion and Storage API work are outside that database transaction boundary; the docs do not claim otherwise.

## 5. Scheduling and deferred purge

- Hosted Supabase supports `pg_cron`; together with `pg_net`, it can invoke Edge Functions periodically. Supabase recommends keeping invocation credentials in Vault. ([Scheduling Edge Functions](https://supabase.com/docs/guides/functions/schedule-functions))
- `pg_net` dispatches HTTP work only after the transaction commits, and its request queue is asynchronous. ([pg_net](https://supabase.com/docs/guides/database/extensions/pg_net))
- Supabase currently labels `pg_net` beta; its request/response tables are unlogged and responses are retained for six hours by default. It should not be treated as a durable job ledger. ([pg_net](https://supabase.com/docs/guides/database/extensions/pg_net))
- Supabase Queues/`pgmq` supports delayed visibility, visibility timeouts, repeat reads after timeout, deletion, and archival. `pop()` is explicitly at-most-once if the consumer cannot guarantee completion. ([PGMQ extension](https://supabase.com/docs/guides/queues/pgmq))
- Edge Functions are intended to be short-lived and idempotent; Supabase says heavy/long-running work belongs in background workers. Current hosted limits list 150 seconds wall-clock on Free, 400 seconds on paid plans, 2 seconds CPU time per request, and 150 seconds request idle timeout. ([Edge Functions](https://supabase.com/docs/guides/functions), [Limits](https://supabase.com/docs/guides/functions/limits))

### Idempotency implications (inference from the documented primitives)

Supabase does not document a built-in, exactly-once “purge this account” transaction spanning Auth, Database, Storage, and external systems. A retryable orchestrator can be made safe only if each step tolerates “already absent/already completed,” progress is durably recorded, concurrent workers are prevented from owning the same purge, and queue messages are acknowledged only after successful completion. `pgmq` visibility timeouts/read counts and Postgres uniqueness/transaction controls provide primitives, not an automatic end-to-end guarantee.

## 6. RLS while deletion is pending

RLS can express a pending-deletion gate because policies are SQL predicates applied to each table operation. Existing rows are filtered by `USING`; inserted/updated rows are checked by `WITH CHECK`. Enabling RLS without an applicable policy yields no client access. ([Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security), [PostgreSQL `CREATE POLICY`](https://www.postgresql.org/docs/current/sql-createpolicy.html))

Important enforcement boundaries:

- A pending-deletion predicate must be applied across every exposed object/path that should be blocked, not only in UI code.
- PostgreSQL restrictive policies combine with other applicable restrictive policies using `AND`, which is a primitive for layering an account-state gate over ordinary ownership policies. This is a generic PostgreSQL capability, not a Supabase account-deletion recipe. ([PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html))
- Grants and RLS are separate; both must be correct.
- Table owners, superusers, and `BYPASSRLS` roles normally bypass RLS; PostgreSQL offers `FORCE ROW LEVEL SECURITY` for table owners, but secret/service-role application code is still privileged by design. ([PostgreSQL row security](https://www.postgresql.org/docs/current/ddl-rowsecurity.html), [Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security))
- Supabase warns that when a client is initialized with a service key **and a user access token is supplied**, requests use that user's RLS context; without the user token, service access bypasses RLS. ([Supabase RLS — bypassing RLS](https://supabase.com/docs/guides/database/postgres/row-level-security))
- Because a deleted user's already-issued JWT can remain cryptographically valid, policies that rely only on `auth.uid()` will still see that subject until token expiry. Immediate rejection requires the additional session/state check documented by Supabase or an equivalent application-state predicate.
- Supabase warns that JWT claims are not always fresh until the token is refreshed and that `raw_user_meta_data` is user-editable. A pending-deletion control that must take effect immediately should therefore not rely only on JWT user metadata. ([Supabase RLS](https://supabase.com/docs/guides/database/postgres/row-level-security))

## 7. Export feasibility

- Supabase explicitly documents querying `auth.users` and `auth.identities` in SQL Editor and exporting results as CSV for an administrator-level bulk Auth export. ([User Management — exporting users](https://supabase.com/docs/guides/auth/managing-user-data))
- Application rows can be selected through the Data API under RLS, and Storage provides authenticated download APIs. ([Securing the Data API](https://supabase.com/docs/guides/api/securing-your-api), [Storage access control](https://supabase.com/docs/guides/storage/security/access-control))
- Free-plan database backups are not downloadable, but that is a project-backup limitation, not evidence that an application-level per-user export is impossible. ([Production checklist](https://supabase.com/docs/guides/deployment/going-into-prod))

**Uncertainty:** no reviewed first-party page specifies a turnkey per-user portability archive joining Auth metadata, Cras rows, and Storage. Such an archive is feasible from the available query/download primitives, but its format, consistency snapshot, size limits, and delivery mechanism are application responsibilities. Edge Function limits matter if the archive is large; streaming/chunking or a background worker may be required.

## 8. Preserving separate usage/security records

- PostgreSQL foreign-key behavior is the deciding mechanism: `CASCADE` deletes dependent records, while `SET NULL` preserves the row without the Auth reference; `RESTRICT`/`NO ACTION` blocks deletion. ([PostgreSQL constraints](https://www.postgresql.org/docs/current/ddl-constraints.html))
- Supabase Auth audit logs can optionally be written to the database, while general platform log retention depends on plan. ([Auth Audit Logs](https://supabase.com/docs/guides/auth/audit-logs), [Logging](https://supabase.com/docs/guides/monitoring-and-debugging/logs))
- Auth Audit Logs include events such as user deletion, token revocation/logout, refresh, and reauthentication requests. The reviewed docs do **not** state whether deleting an Auth user removes matching audit entries or guarantee retention by user; that behavior should not be asserted without a test or Supabase confirmation. ([Auth Audit Logs](https://supabase.com/docs/guides/auth/audit-logs))
- Supabase-managed Auth data and Cras-owned usage/security records are distinct datasets. The official docs do not define which application records Cras must retain or erase; that is a legal/product policy question. Any retained record should not have an `ON DELETE CASCADE` dependency on `auth.users` if preservation is intended, and any retained identifier must be intentionally anonymized/pseudonymized rather than accidentally preserved.

## Freshness warnings for issue/spec authors

1. **Edge authentication is actively changing.** The new publishable/secret key and asymmetric signing-key guidance can invalidate older `verify_jwt` instructions.
2. **Deletion guidance is more precise than older summaries.** Current docs explicitly say Auth deletion cascades sessions/refresh tokens but leaves extant access JWTs valid until expiry, and recommend `session_id` checks for sensitive operations.
3. **Do not treat `reauthenticate()` as generic account-deletion verification.** Its current documentation is specific to Secure Password Change.
4. **Do not promise atomic purge across services.** The official docs provide components for retries and scheduling, not an all-or-nothing transaction across Auth, Database, Storage, and external APIs.
5. Re-check these pages when implementation begins; the live Supabase docs are not a version-pinned contract for a future client/library release.
