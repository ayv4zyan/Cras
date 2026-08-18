# Does Moonrepo orchestrate Kotlin plus TypeScript better than Turborepo?

**Status (2026-08-18):** locked — **neither** Moon nor Turbo. Repo is Gradle + pnpm until a real cross-language task graph exists — [Which client stack do we lock for web and Android?](https://github.com/ayv4zyan/Cras/issues/12), `docs/adr/0001-polyglot-clients.md`.

Question from [Does Moonrepo orchestrate Kotlin plus TypeScript better than Turborepo?](https://github.com/ayv4zyan/Cras/issues/15): the leaning was Moonrepo instead of Turborepo because Android may be Gradle/Kotlin and web may be Node/TypeScript.

**Verdict.** Moon can *run* Gradle/Kotlin and pnpm/TypeScript in one repo; it does **not** treat Kotlin, Java, or Gradle as a language toolchain. Official moon docs put “Other (Kotlin, Java, C#, …)” at **Tier 0 only**: a `system` command that must already exist on `PATH`. Turborepo officially is “a high-performance build system for JavaScript and TypeScript.” Non-JS is a `package.json` wrapper (Go is the documented example). Experimental first-class discovery exists for **uv** and **Cargo**, not Gradle. Both can cache and “affected”-filter a `gradle` task you declared yourself. Neither parses `build.gradle` / the Gradle project graph. For a personal two-app MVP with no compile-time edge between Android and web, Gradle + pnpm scripts is the smaller surface; add moon only when you want one VCS-aware graph and one CI command.

Docs quoted below are moon **v2** (`moonrepo.dev/docs`) and current Turborepo (`turborepo.dev`).

---

## 1. What Moon actually supports for Kotlin / Java / Gradle

### Language matrix: toolchain vs “run a task”

moon’s long-term vision is polyglot, delivered in four tiers ([Introduction — Supported languages](https://moonrepo.dev/docs#supported-languages)):

| Tier | Name | Meaning |
| --- | --- | --- |
| 0 | No direct integration | Not supported in moon; can still run via the **“system” task toolchain**, which **expects the tool to exist in the current environment**. |
| 1 | Project categorization | Primary `language` in `moon.*`; dedicated Rust crate for metadata. |
| 2 | Ecosystem platformization | Parses manifests, lockfiles, and semantic files to infer dependencies, tasks, etc. |
| 3 | Toolchain integration | Configured in `.moon/toolchains.*`; **automatically downloaded and installed**. |

Official matrix (same page):

| Language | T0 | T1 | T2 | T3 |
| --- | --- | --- | --- | --- |
| Node (JavaScript, TypeScript) | fully supported | fully supported | fully supported | fully supported |
| └─ npm, pnpm, yarn | fully supported | n/a | fully supported | fully supported |
| Go, Bun, Deno, Rust (+ Cargo) | fully supported through T3 | | | |
| Python / Ruby (and their managers) | partial / experimental at T2–T3 | | | |
| **Other (Kotlin, Java, C#, …)** | **fully supported at Tier 0 only** | empty | empty | empty |

So Kotlin/Java are documented as “other languages”: **run a task**, not a toolchain. Node/TypeScript/pnpm are the opposite: full inference + install.

FAQ “Will moon support other languages?”: they are “focusing right now on the web ecosystem (Node.js, Rust, Go, PHP, Python, etc)” while remaining “language agnostic and easily pluggable in the future” ([FAQ](https://moonrepo.dev/docs/faq#will-moon-support-other-languages)).

FAQ “Can we run other languages?”: yes, by setting `toolchain` / `toolchains` to `"system"`. System tasks “will use any command available on the current machine.” They **do not** get toolchain benefits: automatic language install, consistent versions, automatic dependency installs when the lockfile changes ([FAQ](https://moonrepo.dev/docs/faq#can-we-run-other-languages)).

```yaml
tasks:
  lint:
    command: 'rubocop'
    toolchain: 'system'
```

(Project config later renamed the field to `toolchains`; same `"system"` escape hatch: [Project config — toolchains](https://moonrepo.dev/docs/config/project#toolchains).)

### What is *not* a Java/Kotlin toolchain

`.moon/toolchains.*` documents first-class (or unstable) blocks for **go, javascript, bun, deno, node, npm, pnpm, yarn, typescript, unstable_python / pip / poetry / uv, unstable_ruby, rust**. There is **no** `java`, `kotlin`, or `gradle` toolchain block ([`.moon/toolchains`](https://moonrepo.dev/docs/config/toolchain)).

The project `language` enum is `bash`, `batch`, `go`, `javascript`, `php`, `python`, `ruby`, `rust`, `typescript`, `unknown`, or a **custom** string. Docs show `language: 'kotlin'` under “Custom” — categorization only, not Tier 2/3 integration ([Project config — language](https://moonrepo.dev/docs/config/project#language)).

### proto vs moon toolchain (Gradle as a *version*)

proto is “a version manager for your favorite programming languages” and “powers moon’s toolchain” ([Introduction — proto](https://moonrepo.dev/docs#proto); [proto product](https://moonrepo.dev/proto); [Toolchain concept](https://moonrepo.dev/docs/concepts/toolchain)). Installing a binary with proto is **not** the same as moon parsing that language’s graph.

The proto [Supported tools](https://moonrepo.dev/docs/proto/tools) catalog describes Gradle as “the open source build system of choice for Java, Android, and Kotlin developers.” That is proto (download/pin a Gradle version), **not** moon Tier 2/3. moon still will not infer Gradle modules, `settings.gradle` includes, or Android variants.

### Orchestration that *does* apply to a system `gradle` task

These features are language-agnostic once a project and task exist:

- **Project graph** from `.moon/workspace.*` `projects` map or globs (folder, `moon.yml`, or any manifest file). Kotlin can sit next to a Node app as a second project ([Workspace — projects](https://moonrepo.dev/docs/config/workspace#projects)).
- **Task graph** via `dependsOn` (projects) and `deps` (targets). `moon run` builds a DAG, runs in parallel/topo order, hashes, cache-hits or executes ([Run a task](https://moonrepo.dev/docs/run-task); [Project — dependsOn](https://moonrepo.dev/docs/config/project#dependson); [Project — deps](https://moonrepo.dev/docs/config/project#deps)).
- **Cache** for every target: command, args, inputs, outputs, env, project/task deps. Extra hash ingredients for **Deno / Bun / Node only** (runtime version, `package.json` deps, `tsconfig`). A Gradle task is cached like any other command: you must declare `inputs` / `outputs`. Default inputs are greedy `**/*`. ([Cache](https://moonrepo.dev/docs/concepts/cache).)
- **Affected**: `moon run <target> --affected` intersects VCS-changed files with the task’s `inputs`. `moon query projects --affected`. `moon ci` runs affected tasks with `runInCI` (default: all except long-running `dev`/`start`/`serve`). ([Run — affected](https://moonrepo.dev/docs/run-task#running-based-on-affected-files-only); [query projects](https://moonrepo.dev/docs/commands/query/projects); [CI](https://moonrepo.dev/docs/guides/ci).)
- **pnpm** is a real toolchain: `pnpm` requires `node`; `installDependencies` can auto-install when lockfile/manifest change ([`.moon/toolchains` — pnpm](https://moonrepo.dev/docs/config/toolchain#pnpm); [Shared — installDependencies](https://moonrepo.dev/docs/config/toolchain#installdependencies)).

**Implication for Cras:** moon will happily `command: './gradlew assembleDebug'` with `toolchains: system` and cache APKs if you list them as `outputs`. It will **not** install the JDK, Android SDK, or Gradle wrapper semantics, parse Gradle’s task graph, or invalidate on `libs.versions.toml` unless those files are `inputs`.

---

## 2. What Turborepo officially supports outside JS

Turborepo’s intro: “a high-performance build system for **JavaScript and TypeScript** codebases.” It “uses the `package.json` scripts you’ve already written, the dependencies you’ve already declared, and a single `turbo.json`.” It “leans on the conventions of the **npm ecosystem**” (`npm` / `yarn` / `pnpm`). ([Introduction](https://turborepo.dev/repo/docs).)

The Package Graph is “the structure of your monorepo created by your **package manager**.” Task Graph edges live in `turbo.json` (`dependsOn`, including `^build`). ([Package and Task Graphs](https://turborepo.dev/docs/core-concepts/package-and-task-graph).)

Lockfile / package-manager check: “Turborepo uses your repository’s lockfile to determine caching behavior, Package Graphs, and more.” ([`turbo run` — package manager check](https://turborepo.dev/docs/reference/run#--dangerously-disable-package-manager-check).)

### Documented non-JS path: wrap it in a workspace package

[Multi-language support](https://turborepo.dev/repo/docs/guides/multi-language) (Go example):

- Add the directory to `pnpm-workspace.yaml` / npm/yarn/bun workspaces.
- Put a `package.json` beside the foreign module; put `build` / `test` / `lint` scripts there (`go build`, `go test`, …).
- “A script can invoke **any toolchain**.”
- “Turborepo **does not interpret `go.mod` or Go imports**.” It hashes files in the package, runs scripts, and “lets Go resolve its own module graph.”
- Orchestration edges to JS apps are **package-manager dependencies** (`"@repo/go-api": "workspace:*"`), not language imports. `dependsOn: ["^build"]` then builds the Go package first. “This dependency is orchestration metadata … it does **not** make the Go module importable from JavaScript or teach Turborepo about Go package dependencies.”
- “Use one package boundary per … project that should be independently filtered, invalidated, or cached.”

That is the official Gradle story by substitution: `./gradlew …` inside `package.json` scripts, plus a dummy npm package name.

### Experimental native (still not Gradle)

| Guide | What turbo discovers | Flag |
| --- | --- | --- |
| [Python (Experimental)](https://turborepo.dev/docs/guides/tools/python) | uv workspace members, `uv.lock`, mapped `uv` tasks | `experimentalPythonWorkspaces` |
| [Rust (Experimental)](https://turborepo.dev/docs/guides/tools/rust) | Cargo workspace crates, `Cargo.lock`, mapped `cargo` tasks | `experimentalCargoWorkspaces` |

Both say turbo “can discover packages across languages and toolchains” **for those ecosystems**. There is **no** official Gradle / Kotlin / Android workspace guide.

### Cache and affected (language-agnostic *after* you wrap)

- Cache fingerprints global + package hashes (root `turbo.json`, lockfile, `package.json`, source files / `inputs`). Outputs restored from `.turbo/cache`; optional Vercel Remote Cache. ([Caching](https://turborepo.dev/docs/crafting-your-repository/caching).)
- `--filter` by package name, directory, dependents (`...ui`), dependencies (`web...`), git ranges (`[main...HEAD]`). `--affected` ≈ `--filter=...[main...HEAD]`. ([Running tasks](https://turborepo.dev/docs/crafting-your-repository/running-tasks); [`--affected`](https://turborepo.dev/docs/reference/run#--affected); [`--filter`](https://turborepo.dev/docs/reference/run#--filter-string).)
- Caching caveat that hits Gradle: “**Scripts that have their own caching**: Some tasks have their own internal caching behavior. In these cases, configuration can quickly become complicated to make Turborepo’s cache and the application cache work together.” Also: tiny tasks and huge artifacts can make turbo cache slower than just running the tool. ([Caching — troubleshooting](https://turborepo.dev/docs/crafting-your-repository/caching#caching-a-task-is-slower-than-executing-the-task).)

---

## 3. Can Moon or Turbo run `gradle` + `pnpm` graphs, cache, and affected in one repo?

| Capability | Moon (official) | Turborepo (official) |
| --- | --- | --- |
| One repo, two apps | Yes: two entries in `projects` | Yes: two workspace packages (Android needs a `package.json`) |
| pnpm as a first-class tool | Yes: `node` + `pnpm` toolchain, optional auto-install | Yes: native npm-ecosystem; lockfile is the package graph |
| `gradle` / `./gradlew` as a task | Yes: `toolchains: system`; JDK/Gradle **preinstalled** | Yes: script in `package.json`; JDK/Gradle **preinstalled** |
| Infer Gradle module graph | **No** (Tier 0; no `build.gradle` parser) | **No** (same as Go: does not interpret the foreign graph) |
| Cross-app task order | `dependsOn` / `deps` in `moon.*` | `workspace:*` + `dependsOn: ["^build"]` |
| Task cache | Hash + optional output archive; you declare `inputs`/`outputs` | Hash + restore `outputs`; you declare them (or accept defaults) |
| Affected / CI subset | `--affected`, `moon query projects --affected`, `moon ci` | `--affected`, `--filter=[git]`, package-level (task-level optional flag) |
| Remote cache | Documented moon remote cache (gRPC/HTTP RE API) | Vercel Remote Cache (zero-config default) |
| Install Android/JDK | Not moon’s job (system / proto-as-VM only) | Not turbo’s job |

**Together in one graph:** both can sequence “build Android then do something on web” only if *you* declare that edge. There is no official “Gradle workspace” mode in either product. Cras web and Android share a **JSON Schema contract**, not a compiled artifact, so there is no inherent Android-to-web build edge.

**Moon is “better” for this mix only in the narrow sense that** it does not force a fake `package.json` onto the Android tree, and it can query `language=kotlin` if you set the custom `language` field. It is **not** better at *understanding* Gradle.

**Turbo is “better” only if** the repo is already a pnpm workspace and you accept wrapping `./gradlew` as another package. Officially it remains a JS/TS build system.

---

## 4. When *no* orchestrator is the smaller choice

Official framing:

- moon sits “firmly in the middle between Bazel (high complexity, full structure), and make/just/etc scripts (low complexity, no structure)” and is meant to be adopted incrementally ([Introduction](https://moonrepo.dev/docs#moon)).
- Turborepo is aimed at monorepos that “struggle to scale” — “thousands of tasks” — while also advertising [single-package workspaces](https://turborepo.dev/repo/docs). Adoption is “a few minutes” *if* you already have `package.json` scripts ([Introduction](https://turborepo.dev/repo/docs)).
- Turbo’s own cache guide: if the underlying tool already caches, combining caches “can quickly become complicated”; skip turbo cache when the task is faster than a round-trip or artifacts are huge ([Caching](https://turborepo.dev/docs/crafting-your-repository/caching#caching-a-task-is-slower-than-executing-the-task)).
- moon system tasks explicitly **do not** install or version the foreign language ([FAQ](https://moonrepo.dev/docs/faq#can-we-run-other-languages)).

For a **two-client MVP** (one Gradle Android app and one pnpm TypeScript webapp):

**Skip moon and turbo when:**

- There is no shared build artifact (no “web `dependsOn` android:compile”).
- CI is “run `./gradlew test` in `android/` and `pnpm test` in `web/`” on two jobs or two steps.
- You would rather keep Gradle’s own incremental/configuration cache and pnpm’s own store than teach an orchestrator `inputs`/`outputs` that duplicate them.
- You do not need `moon ci` / `turbo run --affected` yet (two packages: change detection is a path filter).

**Add moon later if** you want one project graph, `moon ci` affected runs, and system `gradlew` next to a real `pnpm` toolchain **without** npm-wrapping Android.

**Add turbo later if** the web side grows into many JS packages and you already live in pnpm workspaces; Android remains a wrapped script, not a first-class language.

**Do not pick moon “because Kotlin.”** Official docs do not give Kotlin a toolchain. Pick it for repo-shaped orchestration, or pick neither.

---

## Sources

Moonrepo (v2 docs):

- [Introduction — supported languages, tiers, features](https://moonrepo.dev/docs)
- [FAQ — other languages, system toolchain](https://moonrepo.dev/docs/faq)
- [Toolchain concept](https://moonrepo.dev/docs/concepts/toolchain)
- [`.moon/toolchains` — no Java/Kotlin/Gradle](https://moonrepo.dev/docs/config/toolchain)
- [Project config — `language`, `dependsOn`, `deps`, `inputs`, `outputs`, `toolchains`](https://moonrepo.dev/docs/config/project)
- [Workspace — `projects`](https://moonrepo.dev/docs/config/workspace)
- [Cache / hashing](https://moonrepo.dev/docs/concepts/cache)
- [Run a task — DAG, `--affected`](https://moonrepo.dev/docs/run-task)
- [`moon ci` / CI guide](https://moonrepo.dev/docs/commands/ci), [CI guide](https://moonrepo.dev/docs/guides/ci)
- [`moon query projects --affected`](https://moonrepo.dev/docs/commands/query/projects)
- [proto](https://moonrepo.dev/proto), [proto supported tools](https://moonrepo.dev/docs/proto/tools)

Turborepo:

- [Introduction](https://turborepo.dev/repo/docs)
- [Multi-language support](https://turborepo.dev/repo/docs/guides/multi-language)
- [Python (Experimental)](https://turborepo.dev/docs/guides/tools/python)
- [Rust (Experimental)](https://turborepo.dev/docs/guides/tools/rust)
- [Package and Task Graphs](https://turborepo.dev/docs/core-concepts/package-and-task-graph)
- [Caching](https://turborepo.dev/docs/crafting-your-repository/caching)
- [Running tasks](https://turborepo.dev/docs/crafting-your-repository/running-tasks)
- [`turbo run` — `--affected`, `--filter`](https://turborepo.dev/docs/reference/run)
