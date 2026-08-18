---
status: accepted
---

# Recoverable account deletion

Cras MVP distinguishes sign-out from account deletion. After fresh Google reauthentication and explicit destructive confirmation, the Operator may download an optional JSON export and the account enters **Pending deletion**: every session is revoked, ordinary access is denied, and server data is retained only through an exact seven-day Recovery window. This balances immediate loss of access with protection from accidental deletion.

Recovery requires the same Google identity before the server-calculated deadline, restores all retained server data, and creates only the new session; previously revoked sessions remain revoked. At or after the deadline, recovery is refused and an idempotent trusted purge permanently deletes the Supabase Auth identity and all Operator-owned server data. Supabase Auth's irreversible soft-delete option is not the Recovery mechanism. After purge, the same Google identity may register as a new, empty Operator.

The optional export is one JSON download containing Tasks, completed Tasks, Subtasks, Labels, Task–Label relationships, Comments, and Settings, but no auth/provider internals, recordings, transcripts, or usage-security records. On confirmed deletion, the requesting client erases its Task cache, Outbox, unaccepted Drafts, and locally retained failed/retry recordings; other clients do the same when they next observe the frozen account, and their unsynced changes are discarded. An offline device cannot be remotely wiped.

Authorization must reject Pending deletion across the Data API, Realtime, RPCs, and Edge Functions even while an already-issued access JWT remains unexpired. Any privacy-preserving usage/security record needed to prevent delete-and-reregister limit evasion is decided separately by [How is shared AI usage bounded?](https://github.com/ayv4zyan/Cras/issues/29); server-side Voice recordings and transcripts are never retained.

Details: [What happens when an Operator deletes their Cras account?](https://github.com/ayv4zyan/Cras/issues/28).
