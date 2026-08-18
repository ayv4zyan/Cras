# Current quality-gate capabilities relevant to Cras

Verified against primary/first-party sources on **2026-08-18**. This is factual input, not a decision about which gates Cras must adopt.

## Stale-document safeguards

- Derive web commands from the **current checked-in `package.json`**, because npm script names are project-defined; do not copy old `test` or `build` commands from planning docs ([npm scripts](https://docs.npmjs.com/cli/v11/using-npm/scripts/)).
- Derive Android module, flavor, and variant task names from the current build with `./gradlew tasks`, and run the checked-in Gradle wrapper ([Android command-line builds](https://developer.android.com/build/building-cmdline)).
- Pin CI tool/dependency versions in the repository. GitHub says a full-length commit SHA is the only immutable way to reference an external action ([GitHub secure-use reference](https://docs.github.com/en/actions/reference/security/secure-use)).

## React / TypeScript web

Candidate checks, using the repository's own scripts where present:

1. Reproducible install from the checked-in lockfile.
2. Repository-declared formatter/linter.
3. Type-check. TypeScript's `noEmit` option allows `tsc --noEmit` to check without generating output ([TypeScript `noEmit`](https://www.typescriptlang.org/tsconfig/noEmit.html)).
4. Repository-declared unit/component tests.
5. Repository-declared production build.
6. React Doctor as an additional React-specific static-analysis gate.

React Doctor is a third-party tool, not part of React/Meta. Its current first-party documentation says it scans correctness, security, performance, accessibility, architecture, and dead code. It supports changed-file/diff scanning and configurable blocking severity. Current CI options include `--scope changed --base <ref>` and `--blocking error|warning|none`; blocking findings produce a failing exit status ([CLI reference](https://www.react.doctor/docs/reference/cli-reference), [other CI providers](https://www.react.doctor/docs/ci-and-prs/other-ci-providers)). Its GitHub Action is currently documented as `millionco/react-doctor@v2`; correct PR-base diffs require checkout with `fetch-depth: 0`. The generated setup begins advisory (`blocking: none`) and can be tightened to `error` or `warning` ([GitHub Actions setup](https://www.react.doctor/docs/ci-and-prs/github-actions-setup)). The project README also documents `npx react-doctor@latest ci install`, configuration, and CI upgrade flows ([React Doctor repository](https://github.com/millionco/react-doctor)).

For React itself, the official project recommends the current `eslint-plugin-react-hooks` recommended rules, which now include compiler-powered checks for code that violates the Rules of React ([React Compiler 1.0 announcement](https://react.dev/blog/2025/10/07/react-compiler-1)).

## Kotlin / Android

- **Lint:** Android documents `./gradlew lint` and variant-specific `./gradlew lintRelease`. Lint is not automatically run as part of a build; Google recommends running it explicitly in CI ([Android lint](https://developer.android.com/studio/write/lint)).
- **Local JVM unit tests:** `./gradlew test`; variant task form is `testVariantNameUnitTest` ([Android command-line testing](https://developer.android.com/studio/test/command-line)).
- **Instrumented tests:** `./gradlew connectedAndroidTest`, or `connectedVariantNameAndroidTest`, requires a connected device or emulator. `connectedCheck` includes device tests plus other plugin-added checks ([Android command-line testing](https://developer.android.com/studio/test/command-line)).
- **Build/package smoke check:** `./gradlew assembleDebug` is the standard debug APK task; variants follow `assemble<Variant>`. Confirm actual project tasks with `./gradlew tasks` ([Android command-line builds](https://developer.android.com/build/building-cmdline)).

A technically valid split is a fast non-device job (`lint`, `test`, and an assemble task) plus a separate emulator/device job for `connectedAndroidTest`. Whether every PR or only protected/release branches require the device job is a policy choice.

## Shared JSON contract and cross-client conformance

JSON Schema Draft 2020-12 defines schemas for asserting the structure and constraints of JSON instances ([core specification](https://json-schema.org/draft/2020-12/json-schema-core)). The official, language-agnostic JSON Schema Test Suite verifies validator compliance with the specification and covers Draft 2020-12, but explicitly is not a schema-writing style guide ([official test suite](https://github.com/json-schema-org/JSON-Schema-Test-Suite)).

For Cras, schema validation and semantic parity are distinct checks:

- Validate the canonical task/command payload fixtures against one explicitly declared JSON Schema draft in both TypeScript and Kotlin.
- Run the **same checked-in golden fixtures** through both clients' parse/normalize/serialize implementations and compare canonical expected results.
- Include valid and invalid cases, omitted/defaulted fields, unknown fields, enum evolution, nullability, Unicode, date/time/time-zone boundaries, ordering rules, and round trips.
- Fail if either client accepts an invalid fixture, rejects a valid one, or produces a different normalized result.

The shared-golden-fixture procedure is an architectural inference for Cras: JSON Schema proves structural validity; it does not by itself prove that two independently written clients apply the same defaults or domain semantics.

## Supabase local backend checks

The Supabase CLI local stack needs Docker or a Docker-compatible runtime and provides local Postgres, Auth, Storage, Realtime, and the Edge Functions runtime ([local development](https://supabase.com/docs/guides/local-development), [Edge Functions environment](https://supabase.com/docs/guides/functions/development-environment)). Candidate CI checks are:

1. **Fresh migration replay:** `supabase db reset` recreates the local database and reapplies migrations and seed data, proving the current migration history can build a clean database ([database migrations](https://supabase.com/docs/guides/deployment/database-migrations)).
2. **Database lint:** `supabase db lint` checks the local database for schema/type errors and supports warning/error levels ([CLI testing and linting](https://supabase.com/docs/guides/local-development/cli/testing-and-linting)).
3. **Database/RLS/RPC tests:** `supabase test db` runs pgTAP tests. Supabase documents pgTAP coverage for tables/constraints, RLS policies, functions/procedures (therefore RPC implementation), and data integrity. Its RLS guidance calls for CRUD coverage across anonymous/authenticated roles and negative/bypass cases ([testing overview](https://supabase.com/docs/guides/local-development/testing/overview), [database testing](https://supabase.com/docs/guides/database/testing)).
4. **API-level integration tests:** Supabase also documents testing through a normal client instance. This is the appropriate layer for proving exposed RPC results, authenticated ownership boundaries, and client-visible error behavior rather than only internal SQL behavior ([database testing](https://supabase.com/docs/guides/database/testing)).
5. **Realtime integration tests:** start the local stack, subscribe with one client, mutate through another, and assert the expected event plus unauthorized/non-event cases. Supabase's local stack includes Realtime; Realtime access is controlled by RLS policies and JWT/topic context ([local development](https://supabase.com/docs/guides/local-development), [Realtime authorization](https://supabase.com/docs/guides/realtime/authorization)). This is a Cras test design, not a documented one-command Supabase Realtime test runner.
6. **Edge Functions:** unit-test extracted logic and handlers with `deno test`; Supabase explicitly supports Deno's `fmt`, `lint`, and `test`. For integration, serve against the local stack with `supabase functions serve` and invoke the HTTP endpoint ([unit testing Edge Functions](https://supabase.com/docs/guides/functions/unit-test), [local function quickstart](https://supabase.com/docs/guides/functions/quickstart)).

Do not use `supabase db reset --linked` in ordinary CI: Supabase documents it as destructive to the linked remote database and says never to run it on production ([local workflow](https://supabase.com/docs/guides/local-development/cli-workflows)).

## GitHub Actions: caching and credentials

- Caching is an optimization, not a correctness gate. GitHub supports package-manager caching through `setup-node` and custom caching through `actions/cache`; cache keys should incorporate OS/tool context and the dependency lockfile hash so dependency changes produce a new cache ([Node.js Actions guide](https://docs.github.com/en/actions/tutorials/build-and-test-code/nodejs), [dependency caching reference](https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching)). Keep the lockfile-enforcing install step even on cache hits.
- Grant the `GITHUB_TOKEN` only the permissions each job needs. Forked pull-request workflows normally receive a read-only token; Dependabot PR workflows are treated as forked and cannot access Actions secrets ([workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)).
- Except for `GITHUB_TOKEN`, repository secrets are not passed to workflows triggered from forks; they also are not automatically passed into reusable workflows or exposed to Dependabot events ([using secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)). Therefore PR quality gates should prefer the secret-free local Supabase stack.
- Avoid executing untrusted PR code with `pull_request_target`; GitHub specifically warns this can expose write privileges/secrets and enable cache poisoning ([workflow event security warning](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)).
- If a deployment target supports GitHub OIDC, GitHub recommends exchanging the workflow identity for short-lived credentials instead of storing long-lived cloud credentials ([GitHub OIDC](https://docs.github.com/en/actions/concepts/security/openid-connect)).

## Compact candidate matrix

| Area | Fast PR-capable checks | Heavier/separate checks |
|---|---|---|
| Web | lockfile install; lint; type-check; unit/component tests; production build; React Doctor changed-scope | browser end-to-end/accessibility/performance flows if defined |
| Android | `lint`; local unit tests; assemble chosen variant | instrumented tests on an emulator/device |
| Contract | schema validation and identical golden fixtures in TypeScript and Kotlin | compatibility fixtures across released schema versions if backward compatibility is promised |
| Supabase | clean local migration replay; DB lint; pgTAP RLS/RPC tests; Edge Function lint/unit tests | client-level RPC/Auth/Realtime/Edge Function integration against local stack |
| Workflow | least-privilege permissions; immutable external Action refs; lockfile-keyed caches; no secrets in fork PR jobs | protected deployment job/environment with OIDC or narrowly scoped secrets |

