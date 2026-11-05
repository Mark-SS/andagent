# Contributing to AndAgent

Thanks for your interest in contributing! This document covers the essentials.

## Project structure

```
web/     React 19 + assistant-ui frontend (Vite, bundled into the APK)
app/     Kotlin Android shell: WebView host, tool registry, system bridges
app/src/main/java/com/dc/andagent/tools/   One file per tool domain
.github/workflows/   CI (push/PR) and Release (tag-triggered)
```

## Development setup

Requirements: JDK 17, Android SDK (API 34), Node.js 20, any Android device or emulator on API 26+.

```bash
# Frontend (unit tests + build web bundles into app assets)
cd web && npm ci && npm test && npm run build

# Android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The app reads endpoint/model/key from `Download/config.json` on the device, or from the in-app Settings panel. No credentials are compiled into the source — never commit API keys.

## Pull requests

1. Keep PRs focused: one feature or fix per PR.
2. Run the full web test suite (`npm test`) before pushing — CI enforces it.
3. New tools must:
   - implement `Tool` (JSON Schema parameters, `destructive` marker where appropriate),
   - be registered in `MainActivity.kt`,
   - have their schema mirrored in `web/src/extraTools.ts` (agent-facing docs),
   - degrade gracefully when the underlying permission is missing.
4. Destructive operations must ask before acting; the confirmation flow lives in the bridge layer.
5. Update `ROADMAP-TODO.md` (status + commit) for anything user-visible.

## Adding a tool

1. Implement the Kotlin `Tool` contract, including its JSON Schema, permission checks, and `destructive` flag for writes.
2. Register the tool in `MainActivity.kt`.
3. Add the identical name, required fields, parameter types, and description to the agent-facing schema in `web/src/agent.ts` or `web/src/extraTools.ts` (whichever owns that tool group). Compare the two registries before opening a PR.
4. Add a focused JVM unit test for parsing, guards, or core behavior and a web test when the frontend handles a new result shape. Run `npm test`, `npm run build`, `./gradlew :app:assembleDebug :app:testDebugUnitTest`.

## Architecture rules

- The WebView talks to native only through the `AndroidBridge` JS interface — no side channels.
- LLM requests always proxy through native OkHttp (WebView CORS + cert pinning constraints).
- Scripts (QuickJS) run on a dedicated thread with a 64 MB heap cap and interrupt-based timeouts; never move them onto the main thread.
- All bundled assets must be local (no CDN fetches at runtime).

## License

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
