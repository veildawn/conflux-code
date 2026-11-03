# Claude Mobile

在 Android 上通过嵌入式 Linux 环境运行 Claude CLI 的客户端。

This repository is named `conflux-code`. The application is **Claude Mobile**: the launcher label is `Claude Mobile`, the Gradle project name is `ClaudeMobile`, and the application id is `com.claudemobile.app`.

## What it does

Claude Mobile is a Kotlin Android app. It bundles a Termux prefix (bash, shared libraries, and proot) and a prebuilt Ubuntu rootfs that already contains Node.js and the Claude CLI, then runs that CLI under proot on the device. The UI is Jetpack Compose with Hilt.

On first launch the app bootstraps the environment: extract the prefix, verify it, extract the rootfs for the device ABI, require at least one provider profile, then run a final check. If that environment is already ready, later launches open the session list. A failed migration of an older single API key keeps the app on the bootstrap screen so the migration can be retried. The bootstrap screen reports step progress and, when disk space is short, how much needs to be freed.

Chat drives the CLI with `--output-format stream-json` (plus `--verbose` and `--allowedTools` for `Bash`, `Edit`, and `Read`). The process working directory is `/root` inside the bundled rootfs. Sessions and messages are stored as Claude JSONL transcripts under that rootfs (`root/.claude/projects/-root/`). The in-memory overlay only holds a turn until the transcript catches up. `ClaudeSessionService` is a data-sync foreground service that keeps a CLI process alive in the background; tapping its notification reopens that session.

Provider profiles supply `ANTHROPIC_BASE_URL`, `ANTHROPIC_MODEL`, and exactly one of `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN`. Profiles are saved with EncryptedSharedPreferences. Built-in presets are GLM Coding Plan, MiniMax Token Plan, and Kimi Code Plan; a custom HTTPS endpoint is also supported. The editor can run a connection check against the profile. A new session cannot be started until an active profile exists.

Other screens:

- Sessions: create, search, rename, and delete.
- Chat: send, stream, cancel the current turn, retry a failed turn, copy a message or code block, and show tool-call blocks. An offline banner appears when the device has no network.
- Settings: active provider, language (follow system, English, or Simplified Chinese), theme (system, light, or dark), font scale, streaming render interval, default workspace path, whether to auto-start the foreground service, an environment health check, and a redacted diagnostics export.

The chat header shows the session workspace path. The current spawn path does not bind-mount that path into proot.

## Requirements

- JDK 17
- Android SDK 35 (`compileSdk` / `targetSdk` 35, `minSdk` 26). `local.properties` (gitignored) is the usual place for `sdk.dir`.
- Python 3 (`python3` or `python`) and `tar` on `PATH`. The app module extracts Termux `.deb` packages during the build.
- Network access on a clean build. Gradle downloads the Termux bootstrap archives and the `proot` and `libtalloc` packages; those files are not committed.
- A prebuilt rootfs tarball for every ABI you ship. Packaging fails when none are present. The app has no online rootfs install fallback.

ABIs declared in the app module: `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86`.

The wrapper is Gradle 8.9. Versions for the Android Gradle Plugin (8.7.3), Kotlin (2.1.0), Compose, and Hilt are in `gradle/libs.versions.toml`. `versionName` is `1.0.0`.

## Build the rootfs

`app/build-support/build-rootfs.sh` needs Docker with multi-arch emulation. It builds Ubuntu 24.04 with Node.js and `@anthropic-ai/claude-code` (npm `latest` unless `CLAUDE_VERSION` is set).

```sh
app/build-support/build-rootfs.sh arm64-v8a   # one ABI
app/build-support/build-rootfs.sh all          # every ABI listed in the script
```

Artifacts land in `app/build-support/prebuilt-rootfs/` (`rootfs-<abi>.tar.xz` and `manifest.tsv`) and are gitignored. Details are in `app/build-support/prebuilt-rootfs/README.md`.

## Build and run

```sh
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on a device or emulator whose ABI has a bundled rootfs:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew :app:assembleRelease` produces a minified release APK. This project does not declare a signing config.

## Tests

Unit tests use JUnit 5 and Kotest:

```sh
./gradlew test
```

`feature-settings` also has instrumented Compose tests. Those need a connected device or emulator:

```sh
./gradlew :feature-settings:connectedDebugAndroidTest
```

## Project structure

| Path | Role |
| --- | --- |
| `app` | `ClaudeMobileApplication`, `MainActivity`, navigation, bootstrap UI. |
| `feature-sessions` | Session list. |
| `feature-chat` | Conversation UI. |
| `feature-settings` | Settings and provider list, selection, and editor. |
| `core-domain` | Models, use cases, provider registry, stream-json parser. |
| `core-data` | Transcript store, DataStore settings, encrypted provider profiles, diagnostics. |
| `core-bridge` | proot and PTY process bridge, bootstrap, network monitor, foreground service. |
| `core-ui` | Compose theme and shared components. |
| `core-common` | Dispatchers, clock, UUID, and result types. |

Kotlin packages use the `com.claudemobile` prefix.

## License

MIT License. Copyright (c) 2026 Veildawn. See [LICENSE](LICENSE).
