# Notification scheduling and delivery facts for Cras

Verified against first-party documentation and standards on **2026-08-18**. This note distinguishes what the platforms guarantee from architectural recommendations.

## Platform implications and accepted decision

- **Web:** schedule on the server, then send Web Push when due. A push can activate the site's service worker when no page is open. This is not a promise that a notification will appear after the user explicitly quits/force-stops the browser, disables background activity, revokes permission, or remains offline beyond the push TTL.
- **Android:** local `AlarmManager` is the only path here that can fire from already-synced device data with no network. FCM is a server-to-device delivery channel, not a future-time scheduler and not an exact alarm. These are researched alternatives, not the Cras decision.
- **Supabase:** the simplest supported server orchestration is one recurring Supabase Cron job that claims due outbox rows and invokes an Edge Function to send Web Push/FCM. Supabase Queues can add delayed visibility and retry-friendly pull processing, but still needs a consumer (commonly an Edge Function invoked by Cron).
- **Accepted Cras decision:** ADR 0007 chooses server-authoritative Web Push and FCM with no Android local alarms. This accepts offline-at-plan-time loss in exchange for one cross-platform scheduling, cancellation, and deduplication authority.

## Web Push when the web app is closed

### Required lifecycle

1. The app runs in a secure context (HTTPS), registers a service worker, requests notification permission in a user-initiated flow, and creates a `PushSubscription`.
2. The subscription contains an opaque push-service endpoint plus encryption keys. Store that subscription server-side and treat the endpoint as a secret/capability URL.
3. At the reminder's due time, the application server sends an encrypted Web Push request to that endpoint.
4. The browser/user agent receives it, activates the service worker if needed, and dispatches `push`; the service worker calls `ServiceWorkerRegistration.showNotification()`.

The W3C Push API explicitly defines the user agent activating the intended service worker as necessary. Chrome's first-party documentation explicitly says this works **without a page being open**. Therefore, "tab closed" is a supported Web Push use case; keeping a Cras tab alive is not required.

Sources: [W3C Push API](https://www.w3.org/TR/push-api/), [Chrome: Push Notifications on the Open Web](https://developer.chrome.com/blog/push-notifications-on-the-open-web), [MDN Push API](https://developer.mozilla.org/en-US/docs/Web/API/Push_API), [MDN `showNotification()`](https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerRegistration/showNotification)

### Boundaries that matter

- The standards promise activation through the user agent/push service, not operation after every form of explicit browser shutdown, OS background restriction, force-stop, profile deletion, permission revocation, or device outage. Product copy should say reminders are **best effort**, not exact-delivery guarantees.
- FCM's current Web/Android TTL is retention after a message has already been sent: FCM normally attempts immediate delivery, then may store an undeliverable message for up to 28 days. **TTL is expiration, not “deliver at this future timestamp.”**
- Push subscriptions can be refreshed, revoked, or lost outside the app's control; the service worker has a `pushsubscriptionchange` event, but support varies, so the server also needs invalid-endpoint cleanup and the client should reconcile its current subscription when opened.
- On iOS/iPadOS, standards-based Web Push is limited to web apps added to the Home Screen, and permission must follow direct user interaction. Do not generalize desktop/Android-browser behavior to an ordinary iOS browser tab.

Sources: [Firebase: set message lifespan](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan), [MDN `pushsubscriptionchange`](https://developer.mozilla.org/en-US/docs/Web/API/ServiceWorkerGlobalScope/pushsubscriptionchange_event), [WebKit: Web Push on iOS/iPadOS](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)

## Android: local alarms versus FCM

| Concern | Local `AlarmManager` | Server send through FCM |
|---|---|---|
| Network at due time | Not needed after reminder is synced locally | Needed for delivery; FCM may retain until reconnect, within TTL |
| Timing | Inexact alarms can be deferred/batched; exact APIs are for genuinely precise user-facing actions | FCM attempts delivery after the server sends; normal priority may wait through Doze, high priority attempts immediate wake-up but is not an exact-time guarantee |
| Scheduler location | Android device | Cras backend/Supabase; FCM itself is the transport |
| App not foregrounded | Alarm `PendingIntent`/receiver can run subject to Android background rules | Background notification messages go to the system tray; data messages reach `onMessageReceived()` with a short processing window |
| Main permissions/state | Android 13+ `POST_NOTIFICATIONS`; exact APIs additionally require appropriate Alarms & reminders access | Android 13+ `POST_NOTIFICATIONS`; valid FCM app-instance registration; user/system background state |
| Reboot/force-stop | Alarms are canceled at shutdown and must be rebuilt after `BOOT_COMPLETED`; Android 15 cancels pending intents on force-stop and keeps the package stopped until user action | Push should not be represented as bypassing force-stop; registration can become stale/invalid and sends need cleanup |

### Exact/inexact alarm constraints

- Android recommends inexact alarms for most cases. `set()` is for a user action after a time; `setAndAllowWhileIdle()` is the inexact Doze-capable option. `setWindow()` has a minimum 10-minute window for apps targeting Android 12+.
- For exact timing, `setExactAndAllowWhileIdle()` can run despite battery-saving modes. Exact alarms are intentionally restricted because of battery cost.
- Apps targeting Android 12+ need an Alarms & reminders permission for exact `PendingIntent` alarm APIs. On Android 13+, `SCHEDULE_EXACT_ALARM` is user-granted/revocable; it is not pre-granted on fresh installs targeting API 33+. `USE_EXACT_ALARM` is auto-granted but limited to qualifying use cases and Google Play policy. Check `canScheduleExactAlarms()` and rebuild alarms after access is granted.
- Android 13+ notifications are off by default for fresh installs until `POST_NOTIFICATIONS` is granted.
- All alarms are canceled on shutdown. Declare `RECEIVE_BOOT_COMPLETED`, receive `ACTION_BOOT_COMPLETED`, and rebuild from durable task/reminder state. Also reconsider schedules after time/time-zone changes when Cras semantics require it.

Sources: [Android: Schedule alarms](https://developer.android.com/develop/background-work/services/alarms), [Android: notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [Android 15 force-stop behavior](https://developer.android.com/about/versions/15/behavior-changes-all)

### FCM constraints

- FCM has normal and high downstream priority. Normal delivery may be delayed in Doze. High priority attempts immediate delivery and may wake a sleeping device, but it must be reserved for time-sensitive, user-visible notifications; repeated high-priority messages that do not produce visible notifications may be deprioritized.
- Receiving a message ID means FCM accepted the message, not that the device displayed it. TTL controls how long FCM may retain a message if delivery is unavailable; it does not schedule the initial send.
- A background **notification message** is put in the system tray by the SDK/OS path. A **data message** runs app code through `onMessageReceived()` and only has a short processing window; longer work belongs in WorkManager.
- Registration lifecycle advice is currently changing: Firebase's 2026-08-13 guide says FCM is transitioning from registration tokens to Firebase Installation ID–based registrations, with both co-supported. Follow the current SDK callbacks/docs, timestamp registrations, refresh on app starts/changes, and delete invalid/stale targets.

Sources: [Firebase: Android message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority), [Firebase: receive Android messages](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), [Firebase: message lifespan](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan), [Firebase: registration management](https://firebase.google.com/docs/cloud-messaging/manage-tokens)

## Supabase-supported delayed-job orchestration

### Supported building blocks

- **Supabase Cron / `pg_cron`:** recurring jobs can currently run from every second to once a year. A job can execute SQL/database functions or make HTTP requests, including invoking an Edge Function. Runs are recorded in `cron.job_run_details`. Second intervals require Postgres `15.1.1.61` or later.
- **`pg_net`:** asynchronous HTTP from Postgres, used by the official Cron-to-Edge-Function pattern. Supabase currently labels its API **beta**, so function signatures are transition-sensitive. Keep it behind a small database function/migration boundary and verify before implementation.
- **Supabase Queues / `pgmq`:** `send(..., sleep_seconds)` delays message visibility to consumers. Queues are pull-based; visibility does not execute the notification send. Supabase's official pattern is an Edge Function consumer, optionally run every N seconds/minutes by Cron. A failed message remains available for a later read, enabling retry handling.
- **Edge Functions:** supported place to call FCM or a Web Push provider/protocol endpoint and keep credentials server-side. Do not keep an Edge Function asleep until the due time: current wall-clock limits are 150 seconds on Free and 400 seconds on paid plans.
- **Free-project pausing:** Supabase says low-activity Free projects may be paused after a 7-day period; paid projects are not auto-paused. A paused project cannot run its notification scheduler, so this is a release/cost constraint, not merely an operations footnote.

Sources: [Supabase Cron overview](https://supabase.com/docs/guides/cron), [Supabase Cron quickstart](https://supabase.com/docs/guides/cron/quickstart), [Scheduling Edge Functions](https://supabase.com/docs/guides/functions/schedule-functions), [Supabase Queues API](https://supabase.com/docs/guides/queues/api), [Consuming Queues with Edge Functions](https://supabase.com/docs/guides/queues/consuming-messages-with-edge-functions), [Edge Function limits](https://supabase.com/docs/guides/functions/limits), [`pg_net`](https://supabase.com/docs/guides/database/extensions/pg_net), [Supabase push example](https://supabase.com/docs/guides/functions/examples/push-notifications), [Free-project pausing](https://supabase.com/docs/guides/platform/free-project-pausing)

### Recommended Cras server shape (inference from the supported primitives)

Use a durable `notification_delivery`/outbox row per reminder × destination, with `due_at`, destination ID, payload/version, status, attempt count, next-attempt time, lease/claim fields, and a stable idempotency key. Run one recurring Cron worker (for example every 30–60 seconds), atomically claim due rows, invoke an Edge Function to send Web Push or FCM, and record provider acceptance/failure. Retry transient failures with backoff; retire invalid subscriptions/registrations; suppress rows made obsolete by task edits/completion.

Queues are optional for the MVP. They become useful for isolating delivery attempts and retries, but a due-row outbox remains valuable because task edits/cancellations and timezone recomputation are easier to audit and invalidate there. Do not create one cron job per reminder.

Cras ultimately chose the server delivery path for Android as well as web: FCM is Android's sole display authority and Android schedules no local alarms. Stable occurrence identities still bound worker retries and replace duplicate Android displays. See [ADR 0007](../adr/0007-server-authoritative-notification-delivery.md).

## Explicitly stale-prone / transition-sensitive items

Re-verify these immediately before implementation:

1. Android exact-alarm permissions and Google Play eligibility/policy (`USE_EXACT_ALARM` versus `SCHEDULE_EXACT_ALARM`).
2. Android force-stop and background-execution changes for the target SDK/version.
3. Firebase's active transition from legacy registration-token terminology/APIs toward FID-based registration.
4. `pg_net`, currently documented by Supabase as beta.
5. Supabase Cron second-level scheduling's minimum Postgres version and current project plan/limits.
6. Browser-specific Web Push behavior after explicit full browser quit, OS power saving, and iOS Home Screen requirements. Test supported browsers/devices; do not encode a cross-browser guarantee in the spec.
7. Supabase Free-project pausing behavior and plan limits; scheduled delivery cannot run while the project is paused.
