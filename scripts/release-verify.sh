#!/usr/bin/env bash
set -euo pipefail

echo "========================================="
echo " Cras MVP Release Readiness Verification "
echo "========================================="

export PATH="$HOME/.bun/bin:$PATH"

echo "--> 1. Verifying Web formatting, lint, typecheck, all test suites, and production build..."
bun run --cwd web format:check
bun run --cwd web lint
bun run --cwd web typecheck
bun run --cwd web test
bun run --cwd web build

echo "--> 2. Verifying Android native client lint, unit tests, contract tests, security tests, and build..."
cd android
./gradlew lint
./gradlew test
./gradlew assembleDebug
cd ..

echo "--> 3. Verifying Migration & Client Compatibility..."
bun run --cwd web test src/test/migration-lifecycle.test.ts
bun run --cwd web test src/contracts/contract.test.ts

echo "--> 4. Verifying Security & Multi-Operator Isolation..."
bun run --cwd web test src/test/security-isolation.test.ts

echo "--> 5. Verifying Accessibility & Keyboard Semantics..."
bun run --cwd web test src/test/accessibility.test.tsx

echo "--> 6. Verifying Protected Real-Service Smoke Tests..."
bun run --cwd web test src/test/protected-smoke.test.ts

echo "--> 7. Generating Release Artifacts Traceability Manifest..."
mkdir -p dist/release
COMMIT_SHA=$(git rev-parse HEAD || echo "unknown")
BUILD_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat <<EOF > dist/release/release-manifest.json
{
  "project": "cras",
  "version": "0.1.0",
  "commit": "$COMMIT_SHA",
  "builtAt": "$BUILD_TIME",
  "gate": "Release Gate",
  "status": "READY_FOR_RELEASE",
  "artifacts": [
    {
      "name": "web-dist",
      "path": "web/dist",
      "sha256": "$(find web/dist -type f -exec sha256sum {} + | sha256sum | awk '{print $1}')"
    },
    {
      "name": "android-debug-apk",
      "path": "android/app/build/outputs/apk/debug/app-debug.apk",
      "sha256": "$(sha256sum android/app/build/outputs/apk/debug/app-debug.apk 2>/dev/null | awk '{print $1}' || echo "n/a")"
    }
  ]
}
EOF

echo "Release Manifest created at dist/release/release-manifest.json"
cat dist/release/release-manifest.json

echo "========================================="
echo " ✅ MVP RELEASE READINESS PROVEN         "
echo "========================================="
