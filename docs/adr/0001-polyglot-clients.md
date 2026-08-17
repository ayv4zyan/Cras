# Polyglot clients, not KMP and not React Native

Cras is two native clients that share a task **contract**, not a compiled domain and not one UI framework. Android is Kotlin + Jetpack Compose + Material 3 + Glance widgets. Web is React + TanStack + shadcn + Tailwind + Feature Sliced Design + Effect. The repo is Gradle + pnpm with **no** Moonrepo or Turborepo until a real cross-language task graph exists.

React Native was rejected because widgets and Material 3 are native work anyway, and TanStack Router does not target RN. True Kotlin Multiplatform (Kotlin/JS into the React app) and Compose Multiplatform on web were rejected as extra cost for a personal MVP: persistence and HTTP still need per-target adapters, and the web preference is React. Moonrepo only runs Gradle as a Tier 0 system task; Turborepo is a JS/TS build system.

Do not call this Kotlin Multiplatform unless a later decision compiles a Kotlin domain into the web client.
