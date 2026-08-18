---
status: accepted
---

# Recoverable account deletion

Cras MVP distinguishes sign-out from account deletion. After fresh Google reauthentication and explicit destructive confirmation, the Operator may download an optional JSON export and the account enters **Pending deletion**: every session is revoked, ordinary access is denied, and server data is retained only through an exact seven-day Recovery window. This balances immediate loss of access with protection from accidental deletion.

Recovery requires the same Google identity before the server-calculated deadline, restores all retained server data, and creates only the new session; previously revoked sessions remain revoked. At or after the deadline, recovery is refused and an idempotent trusted purge permanently deletes the Supabase Auth identity and all Operator-owned server data. Supabase Auth's irreversible soft-delete option is not the Recovery mechanism. After purge, the same Google identity may register as a new, empty Operator.

The optional export is one JSON download containing Tasks, completed Tasks, Subtasks, Labels, Task–Label relationships, Comments, and Settings, but no auth/provider internals, recordings, transcripts, or usage-security records. On confirmed deletion, the requesting client erases its Task cache, Outbox, unaccepted Drafts, and locally retained failed/retry recordings; other clients do the same when they next observe the frozen account, and their unsynced changes are discarded. An offline device cannot be remotely wiped.

Authorization must reject Pending deletion across the Data API, Realtime, RPCs, and Edge Functions even while an already-issued access JWT remains unexpired. Voice usage survives account purge only as content-free Usage-security records keyed by an irreversible, server-keyed pseudonym derived from the Google identity. Records may contain time buckets, aggregate request/audio usage, provider-attempt categories, model keys, token counts, cost, and reservation state, but never email, raw Google identity, Tasks, recordings, transcripts, prompts, Drafts, or provider responses. Buckets expire 35 days after their end, and the pseudonymous key remains after purge only while an associated unexpired bucket exists. A later registration with the same Google identity is still a new empty Operator, but cannot reset an active Voice allowance. Details: [How is shared AI usage bounded?](https://github.com/ayv4zyan/Cras/issues/29).

## Lifecycle operations

Fresh Google reauthentication means a new interactive Google OAuth flow for the same Google identity and a newly issued server session within the deletion flow. It proves deliberate identity selection but does not claim Google requested the password. The optional export is generated before confirmation by one Operator-scoped, read-only Postgres RPC, producing a transactionally consistent canonical JSON snapshot that the client downloads directly; Cras does not retain an export file.

Beginning deletion, recovery, session revocation, and permanent-purge orchestration use narrowly scoped Edge Functions. Atomic multi-row database state transitions remain internal Postgres functions. User-facing lifecycle functions verify the current user JWT, confirm that its server-side session remains active, derive the Operator identity from that proof, and read current Operator state. They never trust a client-supplied Operator identity. A separate privileged client may use the secret / `service_role` credential only for the exact Auth Admin or trusted-orchestration step. That credential never reaches web, Android, ordinary Data API operations, or a generic Operator-data path.

Supabase's Edge Function authentication and API-key guidance is transition-sensitive. The implementation must follow the project's then-current key mode and SDK rather than pinning an old `verify_jwt` example. Current factual constraints and sources are captured in `docs/research/supabase-account-lifecycle.md`.

## Entering Pending deletion

The authenticated Edge Function first calls one transactional internal Postgres function that records the immutable server-calculated deadline, enters Pending deletion, suppresses every pending Notification, and disables every installation. Only after that transaction commits does it globally revoke sessions. If revocation fails, deletion is still confirmed: database-state checks already block every ordinary path, the client clears local data, and the server records and retries revocation. Repeating deletion returns the existing Pending-deletion state and never extends its deadline.

Every client-facing Data API table/view, RPC, Realtime authorization path, and authenticated Edge Function checks current database state. Pending deletion cannot be enforced only through JWT metadata or session revocation because an already-issued access JWT may remain cryptographically valid until expiry. A pending Operator may access only a narrow account-status/recovery surface that returns the deletion deadline and recovery availability and accepts the recovery command. Tasks, Settings, export, Realtime, Voice capture, and all other ordinary operations remain unavailable.

## Recovery and Notifications

Recovery before the deadline uses the same Google identity and a current server-side session. It locks the same Operator state as purge and atomically restores active state. Server/database time decides the boundary; at or after the deadline recovery is refused. Repeating a successful recovery is harmless.

Every old installation remains disabled. The currently authenticated recovering installation may reactivate when its local Notifications control remains enabled and platform permission is available; every other installation reactivates only as it authenticates again. After recovery, Cras schedules only Notifications whose plan times are after recovery. Notifications intentionally suppressed during Pending deletion are never replayed, even when their plan times fall inside the ordinary one-hour missed-delivery window.

## Permanent purge

One recurring, secret-authenticated Edge Function periodically claims due Pending-deletion Operators. It removes Operator-owned Supabase Storage objects first, because Auth deletion can fail while the Auth identity owns Storage objects, then hard-deletes the Auth identity. Database foreign-key cascades remove the Operator row and every Operator-owned row, including Notification jobs, browser push subscriptions, Android endpoints, and installation records. Usage-security records remain deliberately independent and expire under their existing 35-day rule.

Purge is retryable and idempotent across Supabase's non-atomic Storage, Auth, and Database boundaries: each step treats “already absent” as success and durable database state—not `pg_net` response history—is the work ledger. Recovery and purge lock the same Operator state so only one can win at the deadline.

[How are Notifications scheduled and delivered on Android and web?](https://github.com/ayv4zyan/Cras/issues/34) must implement these lifecycle semantics without reopening them.

Details: [What happens when an Operator deletes their Cras account?](https://github.com/ayv4zyan/Cras/issues/28), [Which account-lifecycle operations use Edge Functions?](https://github.com/ayv4zyan/Cras/issues/31), and `docs/research/supabase-account-lifecycle.md`.
