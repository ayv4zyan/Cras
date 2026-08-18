# Desktop PWA packaging facts for Cras

Verified against current first-party browser documentation and web standards on **2026-08-18**. This note records platform facts and decision inputs; it does not choose Cras's desktop packaging.

## What “PWA packaging” means

A normal desktop PWA is still the hosted Cras web origin. The browser creates the OS launcher/app-window integration; Cras does not ship a separate executable containing the application. The web app manifest supplies install metadata and launch presentation, while a service worker supplies event-driven background capabilities such as Web Push and an optional offline cache. Microsoft explicitly describes PWAs as hosted HTML/CSS/JavaScript running in browser engines, either in a tab or installed; Chrome describes installation as adding the app to OS launch surfaces. Sources: [Microsoft Edge PWA overview](https://learn.microsoft.com/en-us/microsoft-edge/progressive-web-apps/), [Chrome install criteria](https://web.dev/articles/install-criteria), [Service Worker specification](https://www.w3.org/TR/service-workers/).

Installation and offline behavior are separate:

- Chrome desktop has allowed menu installation without a service-worker `fetch` handler since Chrome 112. Chrome's proactive install promotion/`beforeinstallprompt` still requires HTTPS and an installable manifest. A service worker is therefore **not a packaging prerequisite for a manual Chrome install**, but is required for Web Push and for Cras-controlled offline behavior. Sources: [Chrome's updated installability criteria](https://developer.chrome.com/blog/update-install-criteria), [current Chrome install criteria](https://web.dev/articles/install-criteria).
- Safari on macOS Sonoma or later can add **any** website to the Dock with no manifest or service worker. Both remain useful for a controlled app identity/presentation, Push, and offline behavior. Source: [WebKit: web apps on Mac](https://webkit.org/blog/14445/webkit-features-in-safari-17-0/).
- Firefox for Windows can likewise install any visited website as a web app; its feature is a Firefox-created app window/taskbar shortcut rather than manifest-driven PWA promotion. Source: [Mozilla: Use web apps in Firefox for Windows](https://support.mozilla.org/en-US/kb/web-apps-firefox-windows).

## Current desktop matrix

| Browser/platform | Install surface and behavior | Important boundary |
|---|---|---|
| Chrome / Chromium desktop | Supporting desktop Chrome presents an install flow for qualifying manifest-backed apps, producing a standalone app window and OS launch entry. Chrome can also let users manually install sites that miss promotion criteria. | The installed app remains browser-managed and tied to that browser/profile. Browser-specific capabilities must be feature-detected. [Chrome install criteria](https://web.dev/articles/install-criteria), [Chrome PWA architecture](https://web.dev/learn/pwa/architecture) |
| Microsoft Edge | Edge installs qualifying PWAs. On Windows they appear in Apps, Start, Taskbar and Alt+Tab; can expose manifest shortcuts and OS notifications; the Operator may opt into auto-start. Edge also permits Microsoft Store distribution. | Microsoft's strongest documented integration is Windows-specific; FSLogix profiles are a documented persistence exception. [Edge PWA UX](https://learn.microsoft.com/en-us/microsoft-edge/progressive-web-apps/ux) |
| Safari 17+ on macOS Sonoma+ | **File → Add to Dock** creates a standalone web app for any website. It appears in Dock, Launchpad, Spotlight, Mission Control/Stage Manager and Command+Tab. Manifest fields can customize name, icon, display, theme and start URL; Safari 17.4+ supports manifest shortcuts in File/Dock menus. | The installed web app has its own website-data container after creation. Safari initially copies cookies only; it does not copy other local storage, and subsequent website data is not shared. [WebKit Safari 17](https://webkit.org/blog/14445/webkit-features-in-safari-17-0/), [WebKit Safari 17.4](https://webkit.org/blog/15063/webkit-features-in-safari-17-4/) |
| Firefox desktop | Firefox 143+ can install any site as a dedicated web-app window on **Windows**, adding Taskbar and Start entries. It removes tab/new-tab UI, sidebars and menus, and keeps a read-only address bar. Microsoft Store Firefox gains the feature at version 150. | Mozilla currently documents no built-in web-app installation on macOS or Linux. The app depends on Firefox and is removed with it. [Mozilla Firefox web apps](https://support.mozilla.org/en-US/kb/web-apps-firefox-windows) |

## Manifest baseline

For consistent identity and presentation even where manual installation needs less, Cras should treat a manifest as the cross-browser metadata baseline. Chrome's current promotion criteria require:

- HTTPS;
- `name` or `short_name`;
- 192×192 and 512×512 icons;
- `start_url`;
- `display` set to `fullscreen`, `standalone`, `minimal-ui`, or `window-controls-overlay`;
- `prefer_related_applications` absent or `false`.

An explicit stable `id` is also useful: Chrome documents it as decoupling installed identity from `start_url` and manifest location, allowing those URLs to change without redefining the app. Define `scope` intentionally so Cras links inside that scope remain app navigations. Sources: [Chrome install criteria](https://web.dev/articles/install-criteria), [Chrome manifest `id`](https://developer.chrome.com/docs/capabilities/pwa-manifest-id), [Web App Manifest specification](https://www.w3.org/TR/appmanifest/).

Do not use “passes old Lighthouse PWA badge” as the gate. Chrome has deprecated those Lighthouse PWA audits and points to its updated installability criteria instead. Source: [Chrome Lighthouse installable-manifest audit](https://developer.chrome.com/docs/lighthouse/pwa/installable-manifest).

## Service worker, updates, and a minimal offline shell

A service worker registration is keyed by a storage key and scope URL. Browsers check for an updated worker on in-scope navigation and on functional events when the worker has not been checked recently; the standard defines a registration as stale after 86,400 seconds. If the script differs, the browser installs the new worker. By default it waits until the old worker controls no clients, preventing mixed versions. `skipWaiting()` and `clients.claim()` can force faster takeover, but can also put already-open UI onto a worker/assets version it was not built with. Sources: [Service Worker specification](https://www.w3.org/TR/service-workers/), [MDN Service Worker API](https://developer.mozilla.org/en-US/docs/Web/API/Service_Worker_API), [Chrome service-worker lifecycle](https://web.dev/articles/service-worker-lifecycle).

A small offline shell can precache only the versioned HTML entry/fallback, JavaScript, CSS, fonts and icons needed to render Cras's frame, then handle navigation with a network-first response and cached fallback. Chrome's Workbox guidance describes this app-shell pattern. Installation does not require full offline Task operation; Task data/outbox semantics are a separate product decision. Sources: [Workbox app-shell model](https://developer.chrome.com/docs/workbox/app-shell-model), [Workbox fallback responses](https://developer.chrome.com/docs/workbox/managing-fallback-responses).

Operationally, avoid an unconditional immediate takeover for every release. The safe default lifecycle keeps one version controlling a client; if Cras chooses immediate activation, it needs an explicit reload/version-compatibility path.

## Web Push identity: installed app versus tab

The Push API associates a push subscription with a **service-worker registration**, not with a window, tab, or installed icon. The registration itself is identified by storage key and scope. Consequently:

- **Chrome/Edge and Firefox, same browser profile/storage partition:** an installed Cras window and an ordinary Cras tab under the same origin and service-worker scope use the same registration and therefore the same push subscription. Installing the icon does not create another Cras eligible installation. This is an inference directly from the Push and Service Worker data models plus the same-origin model for installed PWA windows. Multiple browser profiles, browsers, storage partitions, origins, or service-worker scopes can produce distinct subscriptions and must remain distinct eligible installations. Sources: [W3C Push API relationship to service-worker registrations](https://www.w3.org/TR/push-api/#relationship-to-service-worker-registrations), [Service Worker registration model](https://www.w3.org/TR/service-workers/), [Chrome PWA origin model](https://web.dev/learn/pwa/architecture).
- **Safari web app on Mac:** do **not** merge it with Safari-tab identity. Safari copies only cookies when Add to Dock runs and shares no other website data afterward. The Dock app can therefore have its own service-worker registration, permission state and Push subscription; reconcile it as a separate eligible installation. Source: [WebKit Safari 17 web apps](https://webkit.org/blog/14445/webkit-features-in-safari-17-0/).
- Installation is not required for desktop Web Push. Safari 16.1+ on macOS can deliver standards-based Web Push even when Safari is not running. Mozilla currently states Firefox desktop notifications are delivered while Firefox is open. Chrome's service worker can start for a push event even when the PWA window is closed, but no browser documentation turns installation into an exact-delivery guarantee. Sources: [WebKit Web Push for macOS](https://webkit.org/blog/13399/webkit-features-in-safari-16-1/), [Mozilla Web Push notifications](https://support.mozilla.org/en-US/kb/push-notifications-firefox), [Chrome service-worker lifespan](https://web.dev/learn/pwa/service-workers).

Cras should therefore key Web Push destinations by the actual subscription/installation record, not by “installed PWA” versus “browser tab,” and should deduplicate only identities proven to share one registration.

## What the platform evidence does—and does not—justify about a native wrapper

The core desktop needs in scope here—dedicated window/launcher presence, Web Push, badge/shortcuts where supported, automatic hosted-code updates, and a minimal offline shell—are available without Electron or Tauri on Chrome/Edge and Safari, and largely on Firefox for Windows. None of those facts alone requires a native wrapper.

The evidence does identify wrapper decision triggers:

1. Cras requires one browser-independent downloadable executable/runtime and installer behavior across Windows, macOS and Linux.
2. “Install from Firefox” must work on macOS or Linux; Mozilla currently supports its built-in web-app surface only on Windows.
3. Cras requires uniform native integrations that the target browsers do not expose consistently, rather than progressive enhancement—for example a guaranteed cross-browser autostart/tray/global-shortcut contract or unrestricted filesystem/process access.
4. Product policy requires binary signing, a native updater/release channel, or app-store packaging whose artifact is not merely a browser-managed hosted PWA.

Those are requirement questions, not conclusions. If none is required for the MVP, the browser documentation shows no packaging gap that must be solved by Electron/Tauri. If one is required, compare wrappers against that exact requirement rather than treating “desktop app” itself as sufficient justification.

## Re-verify before implementation

These are stale-prone and should be checked again at implementation time:

1. Firefox's Windows-only limitation and Microsoft Store version threshold.
2. Chrome install-promotion criteria, especially `beforeinstallprompt` (non-standard and not universal).
3. Safari's separate website-data behavior for Mac web apps and the minimum supported macOS/Safari version.
4. Chrome/Edge link-capture defaults and optional manifest members.
5. Background Web Push behavior after explicit browser quit, OS power restrictions, profile removal, and permission changes.
6. Browser support for optional integration APIs; feature-detect instead of treating Chromium support as a web-platform guarantee.
