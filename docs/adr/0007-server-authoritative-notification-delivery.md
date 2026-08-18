---
status: accepted
---

# Server-authoritative Notification delivery through Web Push and FCM

Cras uses one server-authoritative Notification scheduling system for both clients. Web installations receive Web Push; Android installations receive Firebase Cloud Messaging (FCM) notification messages with attached routing data. Android does not schedule local alarms. This keeps cross-device edits, completion, deletion, timezone changes, Pending deletion, and endpoint state under one cancellation and deduplication authority. The accepted trade-off is that Android cannot deliver while fully offline at the planned moment; delivery remains best effort.

Canonical Task commands maintain durable, Operator-owned Notification jobs transactionally. Each open Floating or Instant Task has one job per eligible installation and plan occurrence. The job records the installation, Task, plan version, interpreted due moment, stable occurrence identity, state, attempt information, and claim lease. Clients never submit a trusted due moment. Task mutations atomically create, replace, or cancel jobs; installation activation, endpoint rotation, permission/setting changes, and Installation timezone changes atomically recalculate that installation's jobs. Immediately before sending, the worker rechecks that the Operator is active, the Task is open, the plan version still matches, and the installation remains eligible.

One recurring Supabase Cron job runs once per minute, atomically leases all due jobs, and invokes a secret-authenticated Edge Function to send Web Push or FCM. Cras does not create one Cron entry per Notification and does not use Supabase Queues in MVP. Stable occurrence identities, unique constraints, leases, and Android notification tags make retries idempotent and cause a duplicate Android display to replace the existing Notification. Provider acceptance does not prove display. Once a provider has accepted a message, Cras cannot guarantee retraction after a racing Task edit; that residual race is part of the best-effort contract.

A two-minute operational grace accommodates normal Cron and invocation latency. When shared Settings disables missed delivery, the sender uses zero provider retention and expires an unsent occurrence after that grace. When missed delivery is enabled, provider retention and Cras retries are bounded by the exact deadline one hour after the Task's interpreted plan time. Transient failures back off only inside the applicable deadline. Permanent provider rejection disables the endpoint and cancels its jobs.

Web requires HTTPS, a Service Worker, user-granted Notification permission, and an Operator-bound PushSubscription. Android uses an Operator-bound FCM registration and a system-rendered notification message; attached data contains only a stable occurrence identity and opaque Task/deep-link identifier. The displayed content is the Task title only—never Description, Comments, Labels, or other Task fields. MVP supports tap-to-open only: no inline Complete, Snooze, delivery history, or claim that a Notification was displayed.

Cras requests platform Notification permission in context when the Operator first saves a timed Task, after a short explanation. An installation whose local control remains enabled but whose platform permission is unavailable is shown as **Blocked by system permission**; Cras does not repeatedly prompt automatically, and Settings offers retry/open-system-settings. The current installation's Settings surface distinguishes Enabled, Disabled locally, Blocked by system permission, and Endpoint unavailable. MVP does not list or remotely manage every installation.

Sign-out immediately disables the Operator-bound installation and cancels its jobs. A different Operator on the same browser profile or Android installation receives a separate installation record and endpoint binding. Clients reconcile endpoint and permission state when they authenticate or resume; endpoint rotation invalidates the old version. An installation is not retired merely because it has not opened recently, but local disablement, permission loss, sign-out, permanent provider rejection, Pending deletion, or purge disables/removes it as already specified. Recovery reactivates only an authenticating installation and schedules only future Notifications; suppressed occurrences are never replayed.

The Deployment owns the VAPID key pair and FCM server credentials. Private keys live only in Edge Function secrets; clients receive only public configuration. Push subscriptions and Android registrations are sensitive Operator-owned rows protected by RLS and removed with the installation or permanent purge. Public Notification delivery requires a non-pausing Supabase plan; Free-project pausing is acceptable only during development.

Operator-facing reliability copy is exact:

> Notifications are best effort. They may be delayed or missed when your device is offline, system notifications are blocked, the app or browser is restricted, or Cras is unavailable.

Primary-source platform facts and transition-sensitive implementation checks are recorded in [Notification delivery research](../research/notification-delivery.md). Re-verify Android/FCM registration behavior, browser shutdown behavior, Supabase Cron and `pg_net` details, and provider limits immediately before implementation.

Details: [How are Notifications scheduled and delivered on Android and web?](https://github.com/ayv4zyan/Cras/issues/34).
