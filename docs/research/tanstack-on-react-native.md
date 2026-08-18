# How well does TanStack work on React Native?

**Status (2026-08-18):** historical. Android is Kotlin, not React Native — [Which client stack do we lock for web and Android?](https://github.com/ayv4zyan/Cras/issues/12), `docs/adr/0001-polyglot-clients.md`. TanStack stays on the **web** client. This note is why we did not share a router or UI with mobile.

Researched 2026-08-17 from official TanStack docs, first-party GitHub READMEs, and package `package.json` / source in TanStack repos. No third-party roundups.

**Question at the time:** if the stack is “as good” on React Native as on web, use it on both.

**Short answer:** it is not one stack. Query and Form are official React Native products. Table is a headless engine with a React adapter and no official RN surface. Router and Start are web (ReactDOM) only. Native navigation chrome is not something TanStack provides; Query’s own RN docs assume React Navigation.

## Verdict for a web + React Native Android app (rejected path)

| Package | Official RN stance | Hard blocker? | Native navigation chrome? |
| --- | --- | --- | --- |
| **Query** (`@tanstack/react-query`) | Designed to work out of the box with RN | No | Still need React Navigation (or equivalent) for screens; Query wires into it |
| **Router** (`@tanstack/react-router`) | Not a supported framework; React + ReactDOM or Solid only | Yes — no RN target | N/A; use React Navigation / Expo Router on Android |
| **Form** (`@tanstack/react-form`) | Headless; official RN guide with `TextInput` | No (focus/DOM APIs are your problem) | N/A |
| **Table** (`@tanstack/react-table`) | Headless React adapter; **no RN docs or examples** | No dedicated RN adapter; you own all markup | N/A |
| **Start** | Full-stack web framework on Router + Vite/Rsbuild | Yes — web SSR/server stack | N/A |
| **DB** (beta) | Official RN UUID note + official RN/Expo SQLite persistence package | No, with setup | N/A |
| **AI** | Official RN/Expo quick start | No, with mobile transports | N/A |
| **Virtual** | Headless README; React adapter peers `react-dom`; **no RN docs** | Not an official RN target | N/A |

“As good on both” holds for **Query + Form** (and optionally **DB / AI**). It does **not** hold for **Router / Start**. Table is shareable *logic*, not a shareable table UI.

---

## Query

**Supported.** Installation docs: “React Query is compatible with React v18+ and works with ReactDOM and React Native.” ([Installation](https://tanstack.com/query/latest/docs/framework/react/installation))

The dedicated RN page opens: “React Query is designed to work out of the box with React Native.” ([React Native](https://tanstack.com/query/latest/docs/framework/react/react-native))

`@tanstack/react-query` peers only `react` (`^18 || ^19`) and ships a `"react-native": "src/index.ts"` entry. It does **not** peer `react-dom`. ([`packages/react-query/package.json`](https://github.com/TanStack/query/blob/main/packages/react-query/package.json))

There is an official Expo example (`@tanstack/query-example-react-react-native`) using Expo, `@react-navigation/native`, `@react-navigation/stack`, and `useQuery` against React Native `FlatList` / `RefreshControl`. ([`examples/react/react-native/package.json`](https://github.com/TanStack/query/blob/main/examples/react/react-native/package.json), [`MoviesListScreen.tsx`](https://github.com/TanStack/query/blob/main/examples/react/react-native/src/screens/MoviesListScreen.tsx); docs index: [React Native example](https://tanstack.com/query/latest/docs/framework/react/examples/react-native))

### Not automatic: browser-only signals

The library works, but web defaults for online/focus do not. Official RN docs tell you to wire:

- **Online / reconnect:** `onlineManager.setEventListener` with `@react-native-community/netinfo` **or** `expo-network`. Web already has this; RN does not until you add it. ([React Native](https://tanstack.com/query/latest/docs/framework/react/react-native))
- **App focus / refetch:** `focusManager.setFocused` from React Native `AppState`, not `window` listeners. ([same page](https://tanstack.com/query/latest/docs/framework/react/react-native))
- **Screen focus:** official snippets use `@react-navigation/native` `useFocusEffect` and `useIsFocused`, including `useQuery({ subscribed: isFocused })`. ([same page](https://tanstack.com/query/latest/docs/framework/react/react-native))

That last point is the navigation relationship: Query is **not** a navigator. Official RN guidance assumes **React Navigation** for “this screen is focused.” There is no official Expo Router snippet on that page.

### Persistence on RN

`createAsyncStoragePersister` docs say you can pass any `AsyncStorage` interface and **the example uses `@react-native-async-storage/async-storage`**, wrapped in `PersistQueryClientProvider`. ([createAsyncStoragePersister](https://tanstack.com/query/latest/docs/framework/react/plugins/createAsyncStoragePersister))

`@tanstack/react-query-persist-client` and `@tanstack/query-async-storage-persister` also declare `"react-native": "src/index.ts"`. Persist-client peers `react` only. ([persist-client package.json](https://github.com/TanStack/query/blob/main/packages/react-query-persist-client/package.json), [async-storage-persister package.json](https://github.com/TanStack/query/blob/main/packages/query-async-storage-persister/package.json))

### Devtools

The official RN page does **not** ship first-party RN DevTools. It lists third-party options (macOS app, Flipper, Reactotron). ([React Native](https://tanstack.com/query/latest/docs/framework/react/react-native))

The official Expo example still depends on `@tanstack/react-query-devtools`. That package peers `react` only; first-party TanStack Devtools architecture is a browser/DOM + Vite workbench (`window` CustomEvents, `localStorage`, `createPortal` / DOM mount). ([devtools architecture](https://tanstack.com/devtools/latest/docs/architecture); [react-query-devtools package.json](https://github.com/TanStack/query/blob/main/packages/react-query-devtools/package.json)) Treat first-party overlay DevTools as a web concern.

**Classification:** works on RN; not a hard blocker. You still add NetInfo/AppState adapters and a native navigator for screen-level refetch.

---

## Router (and React Navigation / Expo Router)

**Hard blocker for a shared router.** Quick Start requirements: React v18+ **with `createRoot`**, and **`react-dom` v18+**. Then: “TanStack Router is currently only compatible with React (with ReactDOM) and Solid. If you're interested in contributing to support other frameworks, such as React Native, Angular, or Vue, please reach out…” ([Quick Start](https://tanstack.com/router/latest/docs/quick-start))

`@tanstack/react-router` peers **both** `react` and `react-dom` (`>=18 || >=19`). ([`packages/react-router/package.json`](https://github.com/TanStack/router/blob/main/packages/react-router/package.json))

The Router README describes “type-safe routing, caching & URL state” for the web product; Start sits in the same repo as “full‑document SSR & streaming.” ([router README](https://github.com/TanStack/router/blob/main/README.md))

Scroll restoration is documented in terms of `history.pushState` and SPA/`<body>` scroll — web history APIs, not native stacks. ([Scroll Restoration](https://tanstack.com/router/latest/docs/guide/scroll-restoration))

### How this relates to React Navigation / Expo Router

Official TanStack Router docs **do not** document a React Navigation adapter, an Expo Router adapter, or a file-tree that compiles to both. React Native is named only as an *unsupported* future framework. ([Quick Start](https://tanstack.com/router/latest/docs/quick-start))

What *is* official:

- Query’s RN page and official Expo example use **`@react-navigation/native`** (stack) for screens and focus. ([Query RN](https://tanstack.com/query/latest/docs/framework/react/react-native), [example package.json](https://github.com/TanStack/query/blob/main/examples/react/react-native/package.json))
- Expo Router is **not** mentioned in those Query/Router pages.

For the rejected RN alternative, the honest split would have been **TanStack Router on web; React Navigation or Expo Router on Android**. Router is not a native navigator; RN chrome would have come from the RN ecosystem. Current Cras Android is Kotlin + Compose and uses neither React Navigation nor Expo Router.

---

## Form / Forms

**Supported, headless.** Dedicated guide: “TanStack Form is headless and it should support React Native out-of-the-box without needing any additional configuration,” with `Text` / `TextInput` / `onChangeText`. ([Usage with React Native](https://tanstack.com/form/latest/docs/framework/react/guides/react-native))

Philosophy lists **non-DOM support** as a reason forms are controlled: “You can use TanStack Form with React Native, Three.js renderers, or any other framework renderer.” ([Philosophy](https://tanstack.com/form/latest/docs/philosophy))

The Form README: “Framework‑agnostic & headless — bring your own UI.” ([form README](https://github.com/TanStack/form/blob/main/README.md))

`@tanstack/react-form` peers **only** `react` (`^17 || ^18 || ^19`), not `react-dom`. ([`packages/react-form/package.json`](https://github.com/TanStack/form/blob/main/packages/react-form/package.json))

### RN-specific caveat (not a missing adapter)

Focus-on-first-error cannot use `document.querySelector`. Official focus guide has a separate **React Native** section: no `querySelectorAll`; keep a `ref` list of `TextInput`s and focus the first invalid field yourself. ([Focus Management](https://tanstack.com/form/latest/docs/framework/react/guides/focus-management))

**Classification:** official RN target. You bring RN inputs and skip DOM helpers. Not “as good as a prebuilt RN form kit”; same as web, you own the widgets.

---

## Table

**Headless engine, no official RN product page.** Overview: Table is **not** a pre-built component; it “does not provide markup, styles, or pre-built implementations.” Official adapters: React, Preact, Octane, Vue, Solid, Svelte, Angular, Ember, Lit, Alpine, or `@tanstack/table-core`. React Native is **not** in that list. ([Overview](https://tanstack.com/table/latest/docs/overview))

README: “100% customizable — bring your own UI.” ([table README](https://github.com/TanStack/table/blob/main/README.md))

React Quick Start renders an HTML `<table>` / `<thead>` / `<tbody>` via `useTable` + `table.FlexRender`. All listed React examples pair with web component libraries (shadcn, MUI, Mantine, etc.). ([React Quick Start](https://tanstack.com/table/latest/docs/framework/react/quick-start), [Overview examples](https://tanstack.com/table/latest/docs/overview))

`@tanstack/react-table` peers **only** `react` (`>=18`), not `react-dom`. ([`packages/react-table/package.json`](https://github.com/TanStack/table/blob/main/packages/react-table/package.json))

**Classification:** not a hard “no RN target” the way Router is. There is also **no** official RN adapter, guide, or example. Sharing column defs / sorting / filtering *logic* is consistent with the headless design; a Material 3 list or datagrid on Android is **your** markup. Do not expect a drop-in table that matches web.

---

## Start

**Web full-stack only.** Overview: Start is a “full-stack React framework powered by TanStack Router” with “full-document SSR, streaming, server functions, client/server builds,” Vite or Rsbuild. It “relies 100% on TanStack Router for its routing system.” Extra features are SSR, streaming, server routes, server functions, middleware, full-stack builds, universal **hosting** deployment. ([Start Overview](https://tanstack.com/start/latest/docs/framework/react/overview))

Status at time of research: **Release Candidate**, API called stable, not claimed bug-free. ([same page](https://tanstack.com/start/latest/docs/framework/react/overview))

Because Start is Router + SSR/server, the Router RN gap applies. There is no official React Native or Expo Start target.

**Classification:** hard blocker as a mobile app framework. Fine as the **web** app (and API) next to an RN client.

---

## Other TanStack packages (relevant)

### DB (beta)

Docs have a **React Native** section: RN lacks `crypto.randomUUID()`; install `react-native-random-uuid` and import it at the app entry. ([DB Overview](https://tanstack.com/db/latest/docs/overview))

`@tanstack/react-db` peers only `react` (`>=16.8.0`). ([`packages/react-db/package.json`](https://github.com/TanStack/db/blob/main/packages/react-db/package.json))

v0.6 official blog: SQLite-backed persistence “across browser, React Native, Expo, Node, Electron, Capacitor, Tauri, and Cloudflare Durable Objects,” with a React Native shopping-list demo using `@op-engineering/op-sqlite` and `@tanstack/react-native-db-sqlite-persistence`. Persistence is described as a **first alpha**. ([TanStack DB 0.6 post](https://tanstack.com/blog/tanstack-db-0.6-app-ready-with-persistence-and-includes))

Package README: “Thin SQLite persistence for React Native apps (including Expo runtime).” Same API for RN and Expo; peers `@op-engineering/op-sqlite`. ([package README](https://github.com/TanStack/db/blob/main/packages/react-native-db-sqlite-persistence/README.md), [package.json](https://github.com/TanStack/db/blob/main/packages/react-native-db-sqlite-persistence/package.json))

DB README still marks the project **BETA**. ([db README](https://github.com/TanStack/db/blob/main/README.md))

### AI

Official “Quick Start: React Native” for Expo/RN chat: `useChat` from `@tanstack/ai-react`, server-owned keys, absolute backend URL (not `/api/chat`), start with `xhrHttpStream()` because phone runtimes often lack streaming `fetch`. Includes `examples/ts-react-native-chat`. ([Quick Start: React Native](https://tanstack.com/ai/latest/docs/getting-started/quick-start-react-native))

### Virtual

README: headless, framework-agnostic virtualization. ([virtual README](https://github.com/TanStack/virtual/blob/main/README.md))

`@tanstack/react-virtual` peers **`react` and `react-dom`**. There is no official RN guide or example in the docs nav. ([`packages/react-virtual/package.json`](https://github.com/TanStack/virtual/blob/main/packages/react-virtual/package.json))

**Classification:** do not treat Virtual as first-class RN the way Query/Form are.

### Store / Pacer

Used under Table/Form. `@tanstack/react-store` and `@tanstack/react-pacer` **declare `react-dom` peers** even though Store’s published React adapter source is React context/hooks (`createContext` / `useContext`), not `react-dom` imports. ([react-store package.json](https://github.com/TanStack/store/blob/main/packages/react-store/package.json), [createStoreContext.tsx](https://github.com/TanStack/store/blob/main/packages/react-store/src/createStoreContext.tsx), [react-pacer package.json](https://github.com/TanStack/pacer/blob/main/packages/react-pacer/package.json))

Pacer overview: framework-agnostic timing utilities; “currently mostly a client-side only library”; **beta**. No RN page. ([Pacer Overview](https://tanstack.com/pacer/latest/docs/overview))

Possible **install-time peer warnings** on RN; not an official RN claim either way.

### Devtools

Core shell mounts into a **DOM** element, persists to `localStorage`, uses `window` events / BroadcastChannel / Vite. React adapter uses `createPortal` from `react-dom`. ([Architecture](https://tanstack.com/devtools/latest/docs/architecture))

**Classification:** web-only first-party UI.

---

## Hard blockers vs “works, but…”

**Hard blockers (no RN target / web-only contract)**

1. **Router** — docs name ReactDOM only; RN is an unsupported framework; package peers `react-dom`. ([Quick Start](https://tanstack.com/router/latest/docs/quick-start), [package.json](https://github.com/TanStack/router/blob/main/packages/react-router/package.json))
2. **Start** — Router-powered SSR/server framework; no RN target. ([Start Overview](https://tanstack.com/start/latest/docs/framework/react/overview))
3. **First-party Devtools overlay** — DOM/Vite workbench. ([Architecture](https://tanstack.com/devtools/latest/docs/architecture))
4. **Virtual’s official React adapter** — `react-dom` peer, no RN docs. ([package.json](https://github.com/TanStack/virtual/blob/main/packages/react-virtual/package.json))

**Not blockers — you still own native chrome / adapters**

1. **Query** works OOTB as a cache, but reconnect/focus need NetInfo + `AppState`, and screen focus is documented against **React Navigation**. ([Query RN](https://tanstack.com/query/latest/docs/framework/react/react-native))
2. **Form** works OOTB as state; you bind `TextInput` and cannot use DOM focus helpers. ([Form RN](https://tanstack.com/form/latest/docs/framework/react/guides/react-native), [Focus](https://tanstack.com/form/latest/docs/framework/react/guides/focus-management))
3. **Table** can drive row models if you render RN views; there is no official RN table. ([Overview](https://tanstack.com/table/latest/docs/overview))
4. **DB / AI** have official RN paths with extra packages (UUID polyfill, op-sqlite, XHR transports). ([DB Overview](https://tanstack.com/db/latest/docs/overview), [DB 0.6](https://tanstack.com/blog/tanstack-db-0.6-app-ready-with-persistence-and-includes), [AI RN](https://tanstack.com/ai/latest/docs/getting-started/quick-start-react-native))

---

## Current consequence for Cras

TanStack stays on the **web** client. Android is Kotlin + Compose + Material 3, so TanStack Query, Form, Router, Start, Table, DB, React Navigation, and Expo Router are not part of the Android stack.

The RN findings above remain only the paper trail for rejecting a shared React-native client stack.

---

## Sources

- [TanStack Query — Installation](https://tanstack.com/query/latest/docs/framework/react/installation)
- [TanStack Query — React Native](https://tanstack.com/query/latest/docs/framework/react/react-native)
- [TanStack Query — persistQueryClient](https://tanstack.com/query/latest/docs/framework/react/plugins/persistQueryClient)
- [TanStack Query — createAsyncStoragePersister](https://tanstack.com/query/latest/docs/framework/react/plugins/createAsyncStoragePersister)
- [TanStack Query — React Native example](https://tanstack.com/query/latest/docs/framework/react/examples/react-native)
- [TanStack/query README](https://github.com/TanStack/query/blob/main/README.md)
- [TanStack/query `packages/react-query/package.json`](https://github.com/TanStack/query/blob/main/packages/react-query/package.json)
- [TanStack/query `examples/react/react-native`](https://github.com/TanStack/query/tree/main/examples/react/react-native)
- [TanStack Router — Quick Start](https://tanstack.com/router/latest/docs/quick-start)
- [TanStack Router — Scroll Restoration](https://tanstack.com/router/latest/docs/guide/scroll-restoration)
- [TanStack/router README](https://github.com/TanStack/router/blob/main/README.md)
- [TanStack/router `packages/react-router/package.json`](https://github.com/TanStack/router/blob/main/packages/react-router/package.json)
- [TanStack Form — Usage with React Native](https://tanstack.com/form/latest/docs/framework/react/guides/react-native)
- [TanStack Form — Focus Management](https://tanstack.com/form/latest/docs/framework/react/guides/focus-management)
- [TanStack Form — Philosophy](https://tanstack.com/form/latest/docs/philosophy)
- [TanStack/form README](https://github.com/TanStack/form/blob/main/README.md)
- [TanStack/form `packages/react-form/package.json`](https://github.com/TanStack/form/blob/main/packages/react-form/package.json)
- [TanStack Table — Overview](https://tanstack.com/table/latest/docs/overview)
- [TanStack Table — React Quick Start](https://tanstack.com/table/latest/docs/framework/react/quick-start)
- [TanStack/table README](https://github.com/TanStack/table/blob/main/README.md)
- [TanStack/table `packages/react-table/package.json`](https://github.com/TanStack/table/blob/main/packages/react-table/package.json)
- [TanStack Start — Overview](https://tanstack.com/start/latest/docs/framework/react/overview)
- [TanStack DB — Overview](https://tanstack.com/db/latest/docs/overview)
- [TanStack blog — DB 0.6 persistence](https://tanstack.com/blog/tanstack-db-0.6-app-ready-with-persistence-and-includes)
- [TanStack/db `react-native-db-sqlite-persistence` README](https://github.com/TanStack/db/blob/main/packages/react-native-db-sqlite-persistence/README.md)
- [TanStack AI — Quick Start: React Native](https://tanstack.com/ai/latest/docs/getting-started/quick-start-react-native)
- [TanStack Virtual README](https://github.com/TanStack/virtual/blob/main/README.md)
- [TanStack/virtual `packages/react-virtual/package.json`](https://github.com/TanStack/virtual/blob/main/packages/react-virtual/package.json)
- [TanStack Devtools — Architecture](https://tanstack.com/devtools/latest/docs/architecture)
- [TanStack Pacer — Overview](https://tanstack.com/pacer/latest/docs/overview)
