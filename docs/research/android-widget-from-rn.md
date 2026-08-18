# What does an Android home-screen widget take from React Native?

**Status (2026-08-18):** the RN question is closed; the **Glance facts still apply**. Android is Kotlin + Glance — [Which client stack do we lock for web and Android?](https://github.com/ayv4zyan/Cras/issues/12), `docs/adr/0001-polyglot-clients.md`. There is no JS snapshot layer. Product shape is locked on [What should the Android widget do?](https://github.com/ayv4zyan/Cras/issues/11): Launchpad, Shortcuts, and a Today glance with complete-on-tile. The “tap-to-open is the honest MVP” line below was the RN-era recommendation and is **superseded**.

Ticket: [What does an Android home-screen widget take from React Native?](https://github.com/ayv4zyan/Cras/issues/10)

Primary sources only: Android developer docs, React Native official docs, Expo official docs. Last fetched 2026-08-17.

## Verdict

A React Native (or Expo) app can **ship** an Android home-screen widget **without rewriting the app**. The widget itself is **not** React Native UI. Under the polyglot lock, skip RN entirely: the same Glance/`AppWidget` surface is the Android app.

- The widget is a native `AppWidget` hosted in the launcher process. Jetpack Glance is the Kotlin / Compose-style way to build it; it still compiles to `RemoteViews`.
- Official Expo `expo-widgets` is **iOS only**. React Native's official app-extensions page is **iOS only** and warns that embedding RN in an iOS Today widget is already memory-unreliable.
- Kotlin (or Java) owns: provider, metadata, widget layout, data load when the process is not in the foreground, and the tap `PendingIntent`.
- On the rejected RN path, JS would own writing a snapshot into shared native storage when the app is awake, and routing after the widget launches `MainActivity`. On the locked Kotlin path, the app and the widget both read the same native store — no JS snapshot.
- A widget **can** deep-link into compose-task or Today (Android intents; Expo Router was the RN equivalent).
- A **live Today list that stays current while the UI process is dead** is native work (read persisted data in `provideGlance` / a `RemoteViewsFactory`, then refresh on a 15–30 minute cadence). The locked Kotlin MVP accepts that native work: the Today glance lists Tasks and supports complete-on-tile.

## Can an RN / Expo app ship Glance / AppWidget without a full native rewrite?

**Yes for the product. No for the widget surface.**

Android defines app widgets as miniature views you embed on the home screen and update periodically. You publish one with an app widget provider. The host (usually the launcher) holds the views. Two official implementation paths exist:

1. Traditional: `AppWidgetProviderInfo` XML + `AppWidgetProvider` + a `RemoteViews` layout. `RemoteViews` does not support custom views or subclasses of the views it allows.
2. Recommended: Jetpack Glance — Kotlin APIs on the Compose *runtime* that still translate into `RemoteViews` and send them to `AppWidgetManager`. Glance composables are **not** interoperable with regular Jetpack Compose UI.

Required native pieces (identical for Views and Glance metadata):

- A `<receiver>` in `AndroidManifest.xml` that accepts `android.appwidget.action.APPWIDGET_UPDATE`.
- An `<appwidget-provider>` XML resource (`minWidth` / `minHeight`, `updatePeriodMillis`, `initialLayout`, `widgetCategory`, …).
- A `GlanceAppWidget` + `GlanceAppWidgetReceiver` (or an `AppWidgetProvider`) in Kotlin/Java.

None of that is a React Native component tree. Under the lock, the rest of Android is Kotlin too; only the web client is TypeScript.

### What Expo and React Native officially give you

| Official surface | Android home-screen widget? |
| --- | --- |
| [Expo Widgets (`expo-widgets`)](https://docs.expo.dev/versions/latest/sdk/widgets/) | **No.** Page title: "A library to build **iOS** home screen widgets and Live Activities." `platforms: ['ios']`. Config is WidgetKit families (`systemSmall`, Lock Screen accessories). |
| [React Native App Extensions](https://reactnative.dev/docs/app-extensions) | **No.** iOS only. Today-widget memory cap is 16 MB; "Today widget implementations using React Native may work unreliably." |
| [Expo Router introduction](https://docs.expo.dev/router/introduction/) | Integration *pattern* only: "notifications and **home screen widgets** are easier to integrate as you can simply intercept the launch and deep link, with query parameters, anywhere in the app." That assumes a native widget already exists and launches the RN activity. |
| [Expo CNG](https://docs.expo.dev/workflow/continuous-native-generation/) | Lists a **community** config plugin, "[Cross-platform home screen widgets](https://github.com/gaishimo/eas-widget-example)", as an example of reducing a native feature to config. That is not a first-party Expo Android widget SDK. |
| Expo Go | Custom native code (a widget receiver) is not in the Expo Go runtime. Development builds / prebuild are required. |

Expo's official story for adding this kind of native capability without rewriting the JS app:

- Write Kotlin in a **local Expo module** (`npx create-expo-module@latest --local`) and/or apply a **config plugin** so `npx expo prebuild` injects the `<receiver>`, XML metadata, and Kotlin sources.
- Do **not** hand-edit generated `android/` if you stay on Continuous Native Generation — those edits are wiped by `prebuild --clean`.
- Custom native libraries need a **development build**, not Expo Go.

So: ship Glance beside RN. Do not expect JS to paint the home-screen tile.

## What must be native Kotlin vs JS?

### Must be native (Kotlin / Java + XML)

Android and Glance are explicit that the widget is a **different process**, **stateless/passive**, and rendered as `RemoteViews`.

| Piece | Why native |
| --- | --- |
| `GlanceAppWidget` / `GlanceAppWidgetReceiver` or `AppWidgetProvider` | Platform entry. Receiver handles `APPWIDGET_UPDATE` / deleted / enabled / disabled / options-changed. |
| `@xml/…` `AppWidgetProviderInfo` | Size, resize, `updatePeriodMillis`, preview, `widgetCategory="home_screen"`. |
| Manifest `<receiver>` + `android.appwidget.provider` meta-data | How the launcher discovers the widget. |
| Widget UI | Glance composables (`Text`, `Button`, `LazyColumn`, …) or `RemoteViews` XML. **Not** RN `View`s. Glance is "not directly interoperable" with Compose UI, let alone RN. |
| Load data in `provideGlance` (or `onUpdate` / `RemoteViewsFactory`) | The launcher asks for an update when JS may not be running. Glance runs `provideGlance` as a `WorkManager` `CoroutineWorker`. |
| Persist widget-readable state | In-memory / JS-heap state "can be destroyed at any time." Glance examples use DataStore / a repository. Collection widgets say: do not store non-static data on the `RemoteViewsService`; use a `ContentProvider` (or other durable store). |
| Tap actions | Glance `Action` → `PendingIntent`. `actionStartActivity`, `actionStartService`, `actionSendBroadcast`, `ActionCallback`. Lambda clicks run in a `WorkManager` worker / `Service`, not in the JS runtime. |
| Collection list plumbing (if you show Today as a list) | `RemoteViewsService` + `RemoteViewsFactory`, or Android 12+ `RemoteCollectionItems`. |

Glance constraints you inherit:

- `provideGlance` is subject to the WorkManager time limit (**currently ten minutes**) *before* `provideContent`. After `provideContent`, composition runs ~**45 seconds**.
- Receivers still have a **~10 second** "don't block the main thread" rule. Long work belongs in WorkManager. Glance warns: if you override `onUpdate` / `onReceive`, call `super`; never call `goAsync` yourself.
- Gestures on the home screen: **touch** and **vertical swipe** only.

### Historical: what could stay JS on the rejected RN path

| Piece | Why JS is enough |
| --- | --- |
| Today, Inbox, compose-task screens | Normal RN / Expo Router routes. |
| Snapshot writer | When the app is foreground (or otherwise awake), persist the Today list / counts to DataStore, SharedPreferences, SQLite, or a `ContentProvider` the widget can read. Then call native `GlanceAppWidget().updateAll(context)` (via a small Expo/RN native module). |
| Incoming deep link | Expo Router enables a deep link per screen. `expo-linking` (`useLinkingURL` / `getInitialURL`) or RN `Linking.getInitialURL` / `'url'` events. |
| "Fast run" destination UX | Compose-task and Today are JS screens once `MainActivity` is up. |

### Historical: optional RN native bridge

A local Expo module method such as `Widgets.refresh()` that calls `MyAppWidget().updateAll(context)` is the documented "update immediately when the app is awake" path. The JS bundle does not talk to `AppWidgetManager` by itself.

## Historical RN deep-link integration (superseded)

**Yes.** This is the well-supported join between a native widget and RN.

### From the widget (native)

Glance: `actionStartActivity` accepts the activity class, a `ComponentName`, or an `Intent`. Glance wraps that in a `PendingIntent`. You can pass `ActionParameters` (typed key/value); they land in the activity `Intent` extras.

```kotlin
Button(
    text = "Today",
    onClick = actionStartActivity<MainActivity>(
        actionParametersOf(destinationKey to "today")
    )
)
```

Alternatively put a custom-scheme URI on the `Intent` (`cras://today`, `cras://compose`) so RN's linker sees a normal deep link.

Do **not** start the activity from a Glance lambda / `ActionCallback`. On Android 12+ you cannot start activities from services or broadcast receivers that act as trampolines. Use `actionStartActivity` from the widget UI.

Collection items cannot each have their own `setOnClickPendingIntent`. The collection sets a pending-intent template; each row sets a fill-in intent (`setOnClickFillInIntent`) so Today-row taps can carry a task id.

### In the RN app

- **Expo:** set `scheme` in app config (`"scheme": "cras"`). After a development/production build, `cras://today` opens the app. Expo Router: every file in `app/` is a route and is automatically deep-linkable. Expo's own rationale for file-based routing includes home-screen widgets: intercept launch + deep link + query params.
- **RN Linking:** if the app is closed, `Linking.getInitialURL()` is the launch URL; if it is already open, a `'url'` event fires. Android needs an intent-filter on `MainActivity` (Expo Prebuild adds the scheme; bare RN you add it yourself). `singleTask` keeps the existing activity instance.

So "Open compose task" and "Open Today" are native buttons whose `PendingIntent` launches `MainActivity` with a path or extra. JS routing does the rest. The widget does not mount those RN screens on the home screen.

## Historical RN cost comparison: live Today list vs tap-to-open

Both are legal Android widgets. They are not equally cheap from RN.

### What Android thinks a widget is

Widgets are "at-a-glance" views of the most important data and functions. Types that match Cras:

- **Control** — frequent functions without opening the app. Android's example is a remote control. "Compose task" / "Open Today" is this.
- **Information** — a few crucial facts; tap typically **launches the app** on a detail view. A "3 due today" chip is this.
- **Collection** — many same-type rows, vertical scroll, tap a row to open detail; Android 12+ can mark items done with compound buttons. A Today list is this.
- **Hybrid** — control + a bit of information.

Android's own navigation guidance: put **generative** actions (create new content) and a top-level open on the widget; treat the widget as the snack and the app as the meal.

Quality bar (if you *do* show content): stale/untimely content is **low quality** (WT-3), including "doesn't update after an action on the widget" and "doesn't update after the related action in the app." Empty/logged-out states should still show value or a CTA (WT-1).

### Why a live list is not free

1. The widget process does not run the JS bundle. `GlanceAppWidget` "should be stateless and passive." Application state lives in **your** data layer; the widget only reflects it.
2. A Today list is a **collection widget**: native `RemoteViewsFactory` (or Glance scrollable) reading a **durable** store. "You can't rely on a single instance of your service, or any data it contains, to persist."
3. Freshness is a native schedule (see next section), plus an immediate `updateAll` whenever JS (or a widget action) mutates tasks.
4. Completing a task *from the widget* is native: Glance `CheckBox` / `ActionCallback` or Android 12 compound buttons, then write the store and `update`. That duplicates domain writes you would rather keep in JS — or you accept "tap row → open Today" and complete there.

### Historical RN-era MVP estimate (superseded)

For the rejected RN path, the minimum was **tap-to-open (control widget), optionally with a last-written snapshot**.

Minimum useful widget:

1. Button / whole-tile tap → `actionStartActivity` → `cras://today` or `cras://compose`.
2. Optional static line: "N due today" or the first task title, written by JS into DataStore when the app is awake, read by `provideGlance`.
3. If nobody has opened the app since install, show a CTA empty state (WT-1), not a broken list.

A scrolling live Today list, check-off-in-place, and sub-30-minute freshness are a **second** native slice: persisted task projection + WorkManager + collection adapter + fill-in intents. Do not block "fast run" on that.

## Constraints: update frequency and data when JS is not running

### The JS bundle is not there

- App widgets live in a **different process**. The system restores `RemoteViews`; anything only in the app's in-memory (or JS) scope can be destroyed at any time.
- Glance: "Each application is responsible for managing the data layer." When state changes, **the app** must notify and `update` the widget.
- `provideGlance` / `onUpdate` / `RemoteViewsFactory.onDataSetChanged` run in native workers/receivers. They can read files, DataStore, SQLite, a `ContentProvider`, or the network. They cannot assume a Metro bundle is loaded.

General widget implication: whatever Today the widget shows must be a **native-readable projection**. On the rejected RN path, JS-only memory would have made the tile empty or stale. Current Cras Android is Kotlin throughout, so the app and Glance widget can share native persistence.

### How often it can refresh

| Mechanism | Official limit | When to use |
| --- | --- | --- |
| `AppWidgetProviderInfo.updatePeriodMillis` | **Not more than once every 30 minutes.** `0` disables periodic updates. | Cheap, system-driven. Glance sample comments the same 30-minute floor. |
| WorkManager periodic work | Glance's own sample uses **15 minutes**. Android says use WorkManager if you want user-configurable frequency and set `updatePeriodMillis` to 0. Standby buckets still apply. | Native refresh of Today projection while the app is asleep. |
| Immediate `GlanceAppWidget.update` / `updateAll` / `updateIf` | Whenever the **app is awake**: user hit the widget, user is in the app, FCM, or a broadcast you already handle. | After Quick Add, complete, or Today edits in JS. |
| `notifyAppWidgetViewDataChanged` | Invalidates a collection view; `onDataSetChanged` may do expensive work synchronously. | List widgets. |
| Every minute while asleep | **Don't.** "Avoid updating your widget every minute when the app isn't awake" — battery. | — |

Receiver / worker budgets:

- `BroadcastReceiver` / `AppWidgetProvider.onUpdate`: treat **10 seconds** as the ANR ceiling; use `goAsync` or (preferred) WorkManager.
- Glance `provideGlance` before `provideContent`: WorkManager limit, **currently 10 minutes**.
- After `provideContent`: ~**45 seconds** of composition unless new interactions/updates extend it.
- `update` / `updateAll` do **not** restart `provideGlance` if it is already running — load first, then `collectAsState` inside composition.

### What "live" therefore means

Not a running RN root on the home screen. It means:

1. JS (when awake) or a native worker writes Today into durable storage.
2. Native `provideGlance` reads that storage and emits new `RemoteViews`.
3. The launcher displays those views until the next update.

On the rejected RN path, a tap-to-open-only tile could remain a branded control without loading the JS bundle. That historical fallback is not the locked Cras widget shape.

## Current consequence for Cras

The research-time RN split is superseded. Android is Kotlin throughout, so the app and its Glance surfaces share native models and persistence rather than a JS-generated snapshot.

```
Home screen (launcher process)
  Launchpad                          Today / Upcoming / Voice capture / Create task
  Shortcuts                          one per Launchpad action
  Today glance                      titles + complete-on-tile + open Task + create

Android app
  Kotlin + Compose + Material 3
  Glance actions deep-link into the same Kotlin app
```

Periodic background refresh details remain implementation work. The product shape itself is locked on [What should the Android widget do?](https://github.com/ayv4zyan/Cras/issues/11).

## Sources

### Android

- [App widgets overview](https://developer.android.com/develop/ui/views/appwidgets/overview) — widget types (information / collection / control / hybrid); tap-to-open detail; generative actions; gesture limits (touch + vertical swipe).
- [Create a simple widget](https://developer.android.com/develop/ui/views/appwidgets) — `AppWidgetProviderInfo`, `AppWidgetProvider`, `RemoteViews` (no custom views).
- [Jetpack Glance](https://developer.android.com/develop/ui/compose/glance) — Kotlin / Compose-runtime widgets; not interoperable with Compose UI.
- [Create an app widget with Glance](https://developer.android.com/develop/ui/compose/glance/create-app-widget) — receiver, XML metadata, `provideGlance`, `actionStartActivity`.
- [Manage and update GlanceAppWidget](https://developer.android.com/develop/ui/compose/glance/glance-app-widget) — different process; passive widget; `update` / `updateAll`; 30-minute `updatePeriodMillis`; WorkManager ~15 minutes; do not update every minute.
- [GlanceAppWidget](https://developer.android.com/reference/kotlin/androidx/glance/appwidget/GlanceAppWidget) — `provideGlance` as `CoroutineWorker`; 10-minute then ~45-second windows; DataStore + WorkManager sample; 30-minute floor comment.
- [Handle user interaction (Glance)](https://developer.android.com/develop/ui/compose/glance/user-interaction) — `actionStartActivity` / service / broadcast / `ActionCallback`; no activity trampolines from lambdas on Android 12+; `ActionParameters` extras.
- [Create an advanced widget](https://developer.android.com/develop/ui/views/appwidgets/advanced) — update types; `updatePeriodMillis` ≥ 30 minutes or 0; WorkManager; 10-second receiver budget.
- [AppWidgetProviderInfo.updatePeriodMillis](https://developer.android.com/reference/android/appwidget/AppWidgetProviderInfo#updatePeriodMillis) — "will not be delivered more than once every 30 minutes."
- [Use collection widgets](https://developer.android.com/develop/ui/views/appwidgets/collections) — lists; persist via `ContentProvider`; pending-intent template + fill-in intent; `notifyAppWidgetViewDataChanged`.
- [Widget quality](https://developer.android.com/docs/quality-guidelines/widget-quality) — WT-3 stale content; WT-1 empty/CTA states.

### React Native

- [App Extensions](https://reactnative.dev/docs/app-extensions) — iOS only; RN Today widgets often exceed 16 MB.
- [Linking](https://reactnative.dev/docs/linking) — `getInitialURL` / `'url'`; Android intent-filters; `singleTask`.

### Expo

- [Expo Widgets](https://docs.expo.dev/versions/latest/sdk/widgets/) — iOS only (`platforms: ['ios']`).
- [Continuous Native Generation](https://docs.expo.dev/workflow/continuous-native-generation/) — config plugins; community Android+iOS widget example (not first-party SDK).
- [Add custom native code](https://docs.expo.dev/workflow/customizing/) — local Expo modules (Kotlin); config plugins; development builds; do not hand-edit CNG `android/`.
- [Expo Modules API: Get started](https://docs.expo.dev/modules/get-started/) — `create-expo-module --local`.
- [Linking overview](https://docs.expo.dev/linking/overview/) — custom schemes; Expo Router auto deep links.
- [Linking into your app](https://docs.expo.dev/linking/into-your-app/) — `scheme` in app config; `useLinkingURL` / `getInitialURL`.
- [Introduction to Expo Router](https://docs.expo.dev/router/introduction/) — every screen deep-linkable; home-screen widgets via intercept launch + query params.
