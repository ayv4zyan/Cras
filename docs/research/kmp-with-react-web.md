# Can Kotlin Multiplatform share a domain with a React web app?

**Status (2026-08-18):** locked **against** compiling a Kotlin domain into the web client. Clients share a contract, not Kotlin/JS — [Which client stack do we lock for web and Android?](https://github.com/ayv4zyan/Cras/issues/12), `docs/adr/0001-polyglot-clients.md`. Contract details: [What is the shared task contract between web and Android?](https://github.com/ayv4zyan/Cras/issues/16). Keep this note as the rejected-KMP paper trail.

Ticket: [Can Kotlin Multiplatform share a domain with a React web app?](https://github.com/ayv4zyan/Cras/issues/14)

Primary sources only: JetBrains Kotlin docs, Compose Multiplatform docs, official Kotlin/JS and Kotlin/Wasm pages, official Kotlin sample, JetBrains Ktor docs, official SQLDelight docs (for the persistence question). Last fetched 2026-08-17.

**Question at the time:** replacing React Native with Kotlin on Android while keeping React on web is only “Kotlin Multiplatform” if a Kotlin domain compiles to something the React app can call.

## Verdict

**Yes — through Kotlin/JS, not through Kotlin/Wasm and not through expect/actual alone.**

JetBrains’ own decision table is explicit:

| Use case | Recommended target |
| --- | --- |
| Sharing business logic, but using **web-native UI** | **Kotlin/JS** — “straightforward interop with JS and minimal overhead” |
| Sharing **both UI and business logic** | **Kotlin/Wasm** + Compose Multiplatform |
| Non-shareable UI written in Kotlin | Kotlin/JS + HTML/React wrappers |

([Web overview — Choose your web approach](https://kotlinlang.org/docs/web-overview.html))

The Kotlin Multiplatform product page says the same split: “Share business logic with your web application via Kotlin/JS or take full advantage of code sharing by bringing your app’s UX to the browser using Compose Multiplatform and Kotlin/Wasm.” ([Kotlin Multiplatform](https://www.jetbrains.com/kotlin-multiplatform/))

For Cras (React stays the web UI):

- Models, validation, and other **pure Kotlin** in `commonMain` **can** compile to a JS library the React app imports.
- That is a **stable** KMP target ([platform stability](https://kotlinlang.org/docs/multiplatform/supported-platforms.html): “Web based on Kotlin/JS — Stable”).
- Kotlin/Wasm is **not** the interop path for a TypeScript app. Official docs point Wasm at Compose Multiplatform web (Beta) and restrict what you can export to JS.
- expect/actual is **not a target**. It is how common code calls **platform** APIs (Android vs browser).
- Persistence adapters and HTTP engines are **not** one binary. Drivers and engines are per target.
- Compose Multiplatform for web is a **different product decision** (replace React), not a way to keep React.

Honest MVP: a thin Kotlin/JS **library** of models + validation is officially supported and the official sample is small. Sharing **live** persistence/HTTP as one domain that both Android and a Vite/TanStack React app execute is real extra Gradle + export + adapter work. Two clients plus a JSON schema is the cheaper MVP unless you already want Kotlin as the single source of truth for rules.

---

## Targets for a non-UI domain

### Kotlin Multiplatform itself

KMP lets you share “whichever part of your codebase you want, including business logic and/or UI.” Sharing business logic while keeping the UI native is a first-class strategy. ([KMP vs React Native](https://kotlinlang.org/docs/multiplatform/kotlin-multiplatform-react-native.html); [Share code on platforms](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html))

Core KMP stability ([supported platforms](https://kotlinlang.org/docs/multiplatform/supported-platforms.html)):

| Platform | Stability |
| --- | --- |
| Android | Stable |
| Web based on Kotlin/JS | **Stable** |
| Web based on Kotlin/Wasm | **Beta** |

### Kotlin/JS (the React-interop target)

Kotlin/JS transpiles Kotlin, the stdlib, and compatible dependencies to JavaScript. You configure it with the `kotlin.multiplatform` Gradle plugin. Targets: **browser** or **Node.js**. ([Kotlin/JS overview](https://kotlinlang.org/docs/js-overview.html); [Set up a Kotlin/JS project](https://kotlinlang.org/docs/js-project-setup.html))

Documented use cases that match Cras:

- “Share common logic between Android, iOS, and web clients … REST API abstractions, user authentication, form validation, and **domain models**,” while keeping native UIs. ([Kotlin/JS use cases](https://kotlinlang.org/docs/js-overview.html#use-cases-for-kotlin-js))
- “If you need to share Kotlin code (**such as domain or data logic**) with a native JavaScript/TypeScript app,” Kotlin/JS gives “direct interoperability” and “minimal overhead … avoiding unnecessary data copying.” ([Web overview](https://kotlinlang.org/docs/web-overview.html))

**IR:** today’s Kotlin/JS compiler **is** the IR compiler. It “transforms Kotlin code into an intermediate representation (IR) before generating the JavaScript code.” Development incremental compilation is toggled with `kotlin.incremental.js.ir`. Production DCE/minification runs on `compileProductionLibraryKotlinJs` / `jsBrowserProductionWebpack`. `@JsExport` declarations are DCE roots. ([Kotlin/JS compiler features](https://kotlinlang.org/docs/js-ir-compiler.html))

There is no separate “legacy” backend in current setup docs. You write `js { browser(); binaries.library() }`, not a special IR flag. ([JS project setup](https://kotlinlang.org/docs/js-project-setup.html); official sample [`sharedLogic/build.gradle.kts`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/sharedLogic/build.gradle.kts))

`binaries.executable()` emits runnable `.js`. Omit it (or use `binaries.library()`) to produce a **library** other projects consume, not a standalone app. ([Execution environments](https://kotlinlang.org/docs/js-project-setup.html#execution-environments))

### Kotlin/Wasm (not the TS-library target)

Kotlin/Wasm compiles to WebAssembly. Browser apps use `wasmJs`; out-of-browser uses `wasmWasi`. ([Kotlin/Wasm](https://kotlinlang.org/docs/wasm-overview.html); [Web overview](https://kotlinlang.org/docs/web-overview.html))

Official use case: “Use Kotlin/Wasm if you want to **share both logic and UI**.” In browsers, “Kotlin/Wasm lets you build web apps with Compose Multiplatform.” Compose for web uses a `wasm-js` target. Kotlin/Wasm is **Beta**. ([Web overview](https://kotlinlang.org/docs/web-overview.html); [Wasm overview](https://kotlinlang.org/docs/wasm-overview.html); [supported platforms](https://kotlinlang.org/docs/multiplatform/supported-platforms.html))

You **can** call Kotlin/Wasm from JavaScript (`@JsExport` on **top-level functions**, generated `.mjs` default export, optional `.d.ts`). Interop signatures are restricted to primitives, `String`, `Boolean`, function types, `JsAny` / subtypes, and `JsReference` (opaque Kotlin objects). “Other types | Not supported.” Kotlin classes are not exported as JS classes; you pass `JsReference` and more exported functions. Arrays copy through adapters. ([Wasm JS interop](https://kotlinlang.org/docs/wasm-js-interop.html))

That is a **bridge API**, not a comfortable domain model for a TypeScript React tree. Official docs do **not** recommend Wasm for “share domain, keep React.”

Compatibility mode (Compose web) cross-compiles `js` + `wasmJs` so old browsers fall back to JS. That is a **Compose UI** fallback, not a React strategy. ([Web overview](https://kotlinlang.org/docs/web-overview.html#compatibility-mode-for-web-targets))

### expect/actual (not a target)

Expected and actual declarations let common code call **platform-specific** APIs. `expect` in `commonMain` has no implementation; each platform `actual` implements it. The compiler merges them per target. ([Expected and actual declarations](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html); [Share code on platforms](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html))

Rules that matter for a domain:

- Every `expect` must have an `actual` on **every** target (including `js`).
- `expect` must not contain an implementation.
- expect/actual **classes** are Beta (`-Xexpect-actual-classes`). Docs recommend interfaces + factory functions (or DI) instead. ([expect/actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html))

`@JsExport` works in common code and “only has an effect when compiling for the JavaScript target.” `@JsNoRuntime` can map expect/actual **interfaces** to TypeScript interfaces. ([Use Kotlin from JavaScript](https://kotlinlang.org/docs/js-to-kotlin-interop.html))

The official logic-sharing sample is the textbook shape: `expect fun getPlatform()` in common, `actual` on Android / iOS / JS (`"Web with Kotlin/JS"`), and a `@JsExport class Greeting` the React app instantiates. ([sample `Platform.kt`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/sharedLogic/src/commonMain/kotlin/org/example/project/Platform.kt), [`Platform.js.kt`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/sharedLogic/src/jsMain/kotlin/org/example/project/Platform.js.kt), [`Greeting.kt`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/sharedLogic/src/commonMain/kotlin/org/example/project/Greeting.kt))

**Android APIs do not belong in `commonMain`.** Moving Android-only code into a shared module “stop[s] compiling”; you rewrite onto multiplatform libraries or hide them behind interfaces / expect/actual. ([KMP FAQ — migrate Android](https://kotlinlang.org/docs/multiplatform/faq.html))

---

## Interop with a TypeScript React app

### What the compiler will emit

1. Mark the surface with **`@JsExport`** (top-level class / interface / function, or `@file:JsExport`). Nested declarations export under the Kotlin name. Combine with `@JsName` for overloads. ([`@JsExport`](https://kotlinlang.org/docs/js-to-kotlin-interop.html#jsexport-annotation))
2. Call **`generateTypeScriptDefinitions()`** inside `js { }`. The compiler collects `@JsExport` declarations into a **`.d.ts`**. Output: `build/js/packages/<package_name>/kotlin` next to the **un-webpacked** JS. Docs call this “especially valuable for the business logic sharing use cases.” ([`.d.ts` generation](https://kotlinlang.org/docs/js-project-setup.html#generation-of-typescript-declaration-files-d-ts))
3. For a **library** (React owns the app): `binaries.library()`, `outputModuleName`, `compilerOptions { target = "es2015" }`, `generateTypeScriptDefinitions()`. Official sample. ([`sharedLogic/build.gradle.kts`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/sharedLogic/build.gradle.kts))
4. Produce an npm-shaped folder with **`jsBrowserDevelopmentLibraryDistribution`** / production counterpart. Sample README: Gradle first, then `npm install` / `npm run start`. ([sample README](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/README.md))

The plugin **generates `package.json`** (name, version, license, dependencies) because “popular package registries such as npm require” it. You can add fields with `compilations["main"].packageJson { customField(...) }`. Docs describe generating the file; they do **not** document a first-party “publish this library to the npm registry” task. ([package.json customization](https://kotlinlang.org/docs/js-project-setup.html#package-json-customization))

The official sample wires consumption with an **npm workspace** pointing at the Gradle dist, not `npm publish`:

```json
"workspaces": ["webApp", "sharedLogic/build/dist/js/developmentLibrary"]
```

`webApp` depends on `"sharedLogic": "0.0.0-unspecified"`. ([root `package.json`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/package.json), [`webApp/package.json`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/webApp/package.json))

TypeScript then does a normal import:

```ts
import { Greeting as KotlinGreeting } from 'sharedLogic';
const greeting = new KotlinGreeting();
greeting.greet();
```

([`Greeting.tsx`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/webApp/src/components/Greeting/Greeting.tsx))

Module systems: ESM (default if target is `es2015` — what you want for Vite), UMD (default for `browser`/`nodejs` otherwise), CommonJS, AMD, plain. ESM **drops package names** so you `import { foo } from 'myModule'`. UMD/CommonJS keep `my.qualified.packagename.foo`. `useEsModules()` / `useCommonJs()` shortcuts exist. ([JS modules](https://kotlinlang.org/docs/js-modules.html); [package structure](https://kotlinlang.org/docs/js-to-kotlin-interop.html#package-structure))

### Type mapping (the real export tax)

From [Kotlin types in JavaScript](https://kotlinlang.org/docs/js-to-kotlin-interop.html#kotlin-types-in-javascript):

| Kotlin | JavaScript / TS | Notes |
| --- | --- | --- |
| `Byte`/`Short`/`Int`/`Float`/`Double` | `Number` | Overflow semantics kept for integer types |
| `Char` | `Number` | Character code |
| `Long` | `BigInt` | Needs `-Xes-long-as-bigint`; export needs `+JsAllowLongInExportedDeclarations` (Experimental) |
| `String`/`Boolean` | same | |
| `List`/`Map`/`Set` | `KtList`/`KtMap`/`KtSet` | Not JS `Array`/`Map`/`Set` unless you use `.asJsReadonlyArrayView` etc. |
| `Unit` | `undefined` | Exportable as **return** type, not as a parameter |
| `enum class` | class + static entries | |
| `Type?` | `Type \| null \| undefined` | |
| Unsigned integers, anything not `@JsExport` | **Not supported** | |

Value classes can export as TS classes (`@JsExport @JvmInline value class Email`). The official snippet exports `suspend fun login` and TypeScript `await`s it. ([value class export](https://kotlinlang.org/docs/js-to-kotlin-interop.html#support-for-value-class-export))

### webpack vs Vite vs TanStack Start

**Gradle Kotlin/JS (when Kotlin is the web app):** the Multiplatform plugin “builds a JavaScript bundle … using [webpack](https://webpack.js.org/).” Browser run = `webpack-dev-server`. Tasks: `jsBrowserDevelopmentWebpack`, `jsBrowserProductionWebpack`, `jsBrowserDistribution`. Extra config in `webpack.config.d`. Default webpack `libraryTarget` is `umd`. ([JS project setup](https://kotlinlang.org/docs/js-project-setup.html) — webpack bundling / run task)

**TypeScript React app (Cras leaning):** official Kotlin docs do **not** document Vite or TanStack Start. They **do** ship a first-party sample whose `webApp` is React 18 + TypeScript + **Vite 7** (`"build": "tsc && vite build"`, `"start": "vite"`) consuming the Gradle **library** output. Vite is the **React** bundler; webpack stays inside Gradle if you run Kotlin-side browser tasks. ([sample `webApp/package.json`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/webApp/package.json), [`vite.config.ts`](https://github.com/Kotlin/kmp-logic-sharing-simple-example/blob/main/webApp/vite.config.ts))

TanStack Start is **absent** from Kotlin/JS, Kotlin/Wasm, and Compose Multiplatform docs. Treat it as the existing web stack. The Kotlin library is an npm workspace dependency; Start/Vite would import it the same way as the official Vite sample.

Two other official web-UI options that are **not** “keep React as TS”:

- **Kotlin wrappers for React** — write React **in Kotlin** (`kotlin-react`, `kotlin-react-dom`). Tutorial uses webpack-dev-server, not a separate TS app. ([React + Kotlin/JS tutorial](https://kotlinlang.org/docs/js-react.html); [JS overview](https://kotlinlang.org/docs/js-overview.html))
- **Compose HTML / Kobweb / Kilua** — Kotlin-only HTML UIs. FAQ: Compose HTML “is an additional library designed for working with the DOM in Kotlin/JS, and **it's not intended for sharing UIs across platforms**.” ([Web overview](https://kotlinlang.org/docs/web-overview.html); [KMP FAQ](https://kotlinlang.org/docs/multiplatform/faq.html))

---

## What cannot be shared (or not as-is)

### Coroutines

Coroutines **are** supported on JS and wasmJs. They are **not** invisible to TypeScript.

- `CoroutineScope.promise { }` (js / wasmJs / web) starts a coroutine and returns a JS `Promise`. ([`promise`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/promise.html))
- `Deferred.asPromise()` converts to `Promise`. ([`asPromise`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/as-promise.html))
- Exported `suspend` functions can be `await`ed from TypeScript (official `AuthService.login` example). ([JS interop](https://kotlinlang.org/docs/js-to-kotlin-interop.html))
- `runBlocking` is documented as a **concurrent** (JVM/Native) bridge that “block[s] the current thread.” It is **not** listed as a JS/wasm API. Hierarchical coroutines docs place `runBlocking` on the JVM+Native `concurrent` source set. ([`runBlocking`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html); [Share code — kotlinx.coroutines hierarchy](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html))
- FAQ concurrency section is about calling suspend/Flow from **Swift**, not from React. No official “export Flow to a TS Observable” page. ([KMP FAQ](https://kotlinlang.org/docs/multiplatform/faq.html))

**Share:** suspend validation, in-Kotlin use of Ktor/SQLDelight. **Do not share:** `runBlocking`, Android `Dispatchers.Main`, assuming Flow is a first-class TS type without a wrapper.

### Android APIs

Not in `commonMain`. Android-only libraries fail to compile when moved to shared modules. Hide them behind interfaces / expect/actual; implement with Android on `androidMain` and browser APIs (or a multiplatform library) on `jsMain`. ([FAQ](https://kotlinlang.org/docs/multiplatform/faq.html); [expect/actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html))

Examples that are Android-only unless wrapped: Jetpack Room as the Android driver, `SharedPreferences`, `WorkManager`, `Context`, Glance widgets.

### SQLDelight / browser

SQLDelight (Cash App — they own the browser driver) **can** share `.sq` schema and generated queries. The **driver is not shared**.

Kotlin/JS official path ([SQLDelight JS](https://sqldelight.github.io/sqldelight/2.1.0/js_sqlite/)):

- Pre-2.0 **synchronous `sqljs-driver` is gone**. Replaced by async **`web-worker-driver`**.
- `generateAsync.set(true)` — queries use `awaitAs*()`, not blocking `executeAs*()`.
- “The web worker driver is **only compatible with browser targets**” (not Node).
- Worker talks to **SQL.js** (SQLite compiled to Wasm) via `@cashapp/sqldelight-sqljs-worker` + `sql.js`.
- Worker URL **must** be built inside `js("""new URL(..., import.meta.url)""")` so **Webpack** rewrites it.
- Extra `webpack.config.d` + `copy-webpack-plugin` to copy `sql-wasm.wasm`. Karma needs a matching proxy for tests. ([SQL.js worker](https://sqldelight.github.io/sqldelight/2.1.0/js_sqlite/sqljs_worker/))

So: **schema/queries can be common**. Android uses the Android SQLite driver (sync). Browser uses a **worker + Wasm + async API**. That is an expect/actual (or interface) **adapter**, not one persistence binary. Official SQLDelight JS docs assume **Kotlin/JS webpack**, not Vite/TanStack. A Vite React host would have to load that worker/`sql-wasm.wasm` itself; SQLDelight does not document that.

### HTTP engines

Ktor client is multiplatform. **Each platform needs an engine.** ([Ktor client engines](https://ktor.io/docs/client-engines.html), Ktor 3.5.2)

| Engine | Platforms |
| --- | --- |
| OkHttp, Android | JVM / Android only |
| Apache5, Java, Jetty | JVM only |
| Darwin / WinHttp / Curl | Native only |
| **Js** | **JavaScript only** (`fetch` in the browser, `node-fetch` on Node) |
| **CIO** | JVM, Android, Native, **JS, WasmJs** — HTTP/1.x only |

Default `HttpClient()` picks an engine from the classpath — useful in KMP if `androidMain` depends on OkHttp/Android and `jsMain` depends on `ktor-client-js` or CIO. Ktor also shows `expect fun httpClient(...)` for per-platform engine config. ([engines](https://ktor.io/docs/client-engines.html) — default engine, JS section, multiplatform example)

Limitations: CIO has no HTTP/2. Android engine has neither HTTP/2 nor WebSockets. Js has both. Feature parity is **not** automatic. ([Limitations](https://ktor.io/docs/client-engines.html#limitations))

**Share:** Ktor `HttpClient` call sites in common if you inject the engine. **Do not share:** OkHttp (or the Android engine) into the React bundle.

### Other export holes

- Unsigned types cannot be `@JsExport`ed. ([type table](https://kotlinlang.org/docs/js-to-kotlin-interop.html#kotlin-types-in-javascript))
- Kotlin/Wasm cannot export arbitrary domain classes as TS classes. ([Wasm interop types](https://kotlinlang.org/docs/wasm-js-interop.html#type-correspondence))
- Production JS DCE will drop unexported API; only `@JsExport` is a documented root. ([IR compiler / DCE](https://kotlinlang.org/docs/js-ir-compiler.html))

---

## Compose Multiplatform for web vs “React is the web UI”

Official stance is a **fork**, not a blend.

**Compose Multiplatform** = share the **UI** (and logic) with Jetpack Compose APIs. Platforms: Android (via Jetpack Compose), iOS, desktop — **Stable**; **Web — Beta**, compiled with **Kotlin/Wasm**. Product page lists Web as Beta and links a Wasm example. ([Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/); [supported platforms](https://kotlinlang.org/docs/multiplatform/supported-platforms.html); [KMP FAQ](https://kotlinlang.org/docs/multiplatform/faq.html))

FAQ, verbatim enough: Compose Multiplatform for web based on WebAssembly is Beta — “you can use it, but migration issues may still occur. It has the same UI as Compose Multiplatform for iOS, Android, and desktop.” Future web work is **Wasm**. “In Compose Multiplatform, due to resource constraints, we've shifted our focus from JS Canvas to Wasm.” Compose HTML is DOM-only and **not** for sharing UI across platforms. ([FAQ — future web](https://kotlinlang.org/docs/multiplatform/faq.html))

**React as the web UI** is the **other** official row: “Sharing business logic, but using web-native UI → Kotlin/JS.” ([Web overview](https://kotlinlang.org/docs/web-overview.html))

The official [logic-sharing sample](https://github.com/Kotlin/kmp-logic-sharing-simple-example) is exactly Cras’s shape: Android Jetpack Compose + iOS SwiftUI + **Web React**, with `sharedLogic` as a Kotlin/JS library. `sharedUI` is Compose for the **native** apps, not for React.

There is **no** official “mount a Compose canvas inside a TanStack route” or “generate React components from composables” story. Keeping React means **not** using Compose for web.

---

## Honest MVP cost

Official docs do not price the two options. They do state **recommended targets** and ship a **minimal** sample. Cost below is inferred only from that documented surface.

### A. One Kotlin domain + React (KMP in the map’s sense)

**What JetBrains actually documents as the happy path**

- `commonMain` models + validation + (optional) expect/actual factories.
- Android target + `js { browser(); binaries.library(); generateTypeScriptDefinitions() }`.
- `@JsExport` on a **small** API (sample is one class + `greet()`).
- `./gradlew :sharedLogic:jsBrowserDevelopmentLibraryDistribution` then npm workspace / Vite import.
- Coroutines exported as `Promise` / `await`.
- HTTP: Ktor core in common + OkHttp (or Android) on Android + `ktor-client-js` or CIO on JS.
- Persistence: shared `.sq` **or** skip SQLDelight on web; Android driver vs `web-worker-driver` + SQL.js.

**What you pay that two-clients-plus-schema does not**

| Extra | Why it is real |
| --- | --- |
| Second build graph | Gradle (Kotlin/JS + Yarn/npm for Kotlin’s JS deps) **and** Vite/TanStack. Sample requires Gradle **before** `npm install`. |
| Export design | Only `@JsExport` is visible; Lists are `KtList`; unsigned/`Long` are special; DCE drops the rest. |
| Rebuild discipline | React does not compile Kotlin. Change domain → rerun `jsBrowser*LibraryDistribution`. |
| Adapter layer | Android APIs, SQLDelight driver, HTTP engine, Main dispatcher — all expect/actual or interfaces. Browser SQLDelight is async + worker + webpack-oriented. |
| Runtime weight | JS output includes Kotlin stdlib (DCE docs: unused stdlib can be ~1.3 MB before DCE). Production DCE helps; dev does not. ([IR compiler](https://kotlinlang.org/docs/js-ir-compiler.html)) |
| Tooling split | Official Kotlin/JS browser loop is webpack; Cras web loop is Vite/TanStack. Official sample proves Vite **can** consume the library; it does not integrate Kotlin into TanStack Start SSR/routing. |
| Wasm dead end | Using Wasm “because it’s the future of Compose web” does **not** give a nice TS domain. Official recommendation for React is JS. |

**When this is worth it:** validation/invariants you refuse to write twice; you already live in Gradle for Android; exported surface stays small (parse, validate, sort Today, not a Room clone in JS).

### B. Two clients + a JSON schema contract

No Kotlin/JS target, no `@JsExport`, no Gradle-to-npm workspace. Android Kotlin and web TypeScript each implement the same **document**. Official Kotlin/JS pages even list “DTOs, validation … abstractions for REST API endpoints” as something you **could** share with a Kotlin **backend** — the inverse of this option. ([JS use cases](https://kotlinlang.org/docs/js-overview.html))

**What you pay:** two implementations of models/validation; drift unless the schema is generated/tested (OpenAPI, JSON Schema, kotlinx.serialization on a server, etc.). The map already has “Shared task *contract* shape if web and Android are different languages” as unspecified.

**When this is cheaper:** MVP, one operator, TanStack web already chosen, Android is Compose/widgets, persistence is “GitHub or a small API” rather than one in-process Kotlin database both UIs call.

### Recommendation for this map

Call it **KMP** only if you add a `js` target and React **imports the compiled library** (sample-shaped). Kotlin on Android + React on web **without** that library is two clients.

For Cras MVP: **B is the cheaper lock** unless issue 12 chooses Kotlin Android *and* you have a concrete list of rules you will not duplicate (task field validation, Today/Inbox predicates). If you do share, share **pure functions + types** via Kotlin/JS; keep SQLDelight/HTTP/UI on each side (or Ktor with two engines). Do **not** plan Compose Multiplatform web while React remains the web UI. Do **not** plan Kotlin/Wasm as the React domain runtime.

---

## Sources

- [Kotlin web overview](https://kotlinlang.org/docs/web-overview.html) (12 Aug 2026)
- [Kotlin/JS overview](https://kotlinlang.org/docs/js-overview.html) (1 Oct 2025)
- [Set up a Kotlin/JS project](https://kotlinlang.org/docs/js-project-setup.html) (12 Aug 2026)
- [Kotlin/JS compiler features (IR)](https://kotlinlang.org/docs/js-ir-compiler.html) (4 Sep 2025)
- [Use Kotlin code from JavaScript](https://kotlinlang.org/docs/js-to-kotlin-interop.html) (12 Aug 2026)
- [JavaScript modules](https://kotlinlang.org/docs/js-modules.html) (12 Aug 2026)
- [Kotlin/Wasm overview](https://kotlinlang.org/docs/wasm-overview.html) (18 May 2026)
- [Kotlin/Wasm JS interop](https://kotlinlang.org/docs/wasm-js-interop.html) (16 Mar 2026)
- [Expected and actual declarations](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html) (13 May 2026)
- [Share code on platforms](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html) (16 Mar 2026)
- [Stability of supported platforms](https://kotlinlang.org/docs/multiplatform/supported-platforms.html) (10 Sep 2025)
- [KMP FAQ](https://kotlinlang.org/docs/multiplatform/faq.html) (15 May 2026)
- [KMP vs React Native](https://kotlinlang.org/docs/multiplatform/kotlin-multiplatform-react-native.html) (21 Jul 2026)
- [Compose Multiplatform product](https://kotlinlang.org/compose-multiplatform/)
- [Kotlin Multiplatform product](https://www.jetbrains.com/kotlin-multiplatform/)
- [React + Kotlin/JS tutorial](https://kotlinlang.org/docs/js-react.html) (12 Aug 2026)
- [Official logic-sharing sample (Android Compose + iOS SwiftUI + React)](https://github.com/Kotlin/kmp-logic-sharing-simple-example)
- [kotlinx.coroutines `promise`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/promise.html) / [`asPromise`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/as-promise.html) / [`runBlocking`](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html)
- [Ktor client engines](https://ktor.io/docs/client-engines.html) (24 Apr 2026, Ktor 3.5.2)
- [SQLDelight on Kotlin/JS](https://sqldelight.github.io/sqldelight/2.1.0/js_sqlite/) / [SQL.js worker](https://sqldelight.github.io/sqldelight/2.1.0/js_sqlite/sqljs_worker/) (SQLDelight 2.1.0)
