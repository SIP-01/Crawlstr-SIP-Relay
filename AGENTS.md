# AGENTS.md

Compact guide for OpenCode sessions working in this repo. `CLAUDE.md` has fuller component architecture, but its **Database section is outdated** (says version 11 / 3 entities — actually version 13 / 6 entities, see below). Trust this file where they disagree.

## Toolchain

- **JDK 21 required** (CI uses temurin 21; `compileOptions`/`kotlin.jvmTarget` = 21). Builds fail on older JDKs.
- Single Gradle module: `:app`. Package `com.greenart7c3.citrine`. App entry is `Citrine.kt` (Application) + `MainActivity.kt`.
- Versions: minSdk 26, targetSdk 36, compileSdk 37. Debug build adds `applicationIdSuffix = .debug` → debug applicationId is `com.greenart7c3.citrine.debug` (matters for content-provider authorities / intent filters).

## Commands

```bash
./gradlew ktlintCheck          # lint — runs on pre-commit
./gradlew ktlintFormat         # autofix ktlint issues
./gradlew lintDebug            # Android Lint — must pass with 0 errors before pushing
./gradlew lintFix              # Android Lint — applies safe auto-fixes (may need 2 runs)
./gradlew test                 # unit tests — runs on pre-push
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK (unsigned unless SIGN_RELEASE set)
./gradlew :app:test --tests "com.greenart7c3.citrine.GroupStateTest"  # single test class
```

- **CI order is `ktlintCheck -> test -> assembleDebug`** — match it locally before pushing. Also run `./gradlew lintDebug` and fix any errors (warnings are OK).
- **Always run `ktlintCheck` and `lintDebug` before committing** — `ktlintCheck` enforces Kotlin code style and `lintDebug` catches Android-specific issues (Compose, resources, manifest). Both must report 0 errors.
- **Git hooks auto-install**: `:app:preBuild` depends on the `installGitHook` task, which copies `git-hooks/{pre-commit,pre-push}` into `.git/hooks`. So the first build installs hooks. `pre-commit` = `ktlintCheck`; `pre-push` = `test`.
- **No instrumented tests exist** (`app/src/androidTest` is absent). `./gradlew connectedAndroidTest` is a no-op; don't rely on it.

## Database (corrects CLAUDE.md)

- Room DB file `citrine_database`, **version 14**, **7 entities**: `EventEntity`, `TagEntity`, `EventFTS`, plus `GroupEntity`, `GroupMemberEntity`, `GroupInviteEntity` (added in migration 12→13), and `WebIndexEntity` (SIP-01 structured index, added in migration 13→14; FK-cascades with its event). `EventEntity.expiresAt` added in 11→12.
- Migrations are top-level `val MIGRATION_N_(N+1)` in `database/AppDatabase.kt`. When changing schema: add a `MIGRATION_N_(N+1)`, bump the `@Database(version = ...)` **and `TARGET_VERSION`** in **both** `AppDatabase` and `HistoryDatabase`, and `.addMigrations(...)` it on **both** builders.
- There are **three** separate databases: `citrine_database` (`AppDatabase`), `citrine_history_database` (`HistoryDatabase`, only `EventEntity`+`TagEntity`, shares the same migrations), and `logs/LogDatabase`.
- KSP exports Room schemas to `app/schemas/` (checked in) via `ksp { room.schemaLocation }`.

## SIP-01 (Search Index Protocol)

- This fork is a **mobile SIP-01 indexing relay**: it validates kind 39697 web index observations at ingestion (spec §12.4), indexes them into `WebIndexEntity` (relay profile §2), answers the NIP-50 web-search operators (§4) from `EventRepository.createQuery`, and advertises an `uncaged_index` block in the NIP-11 document (§5).
- `server/sip01/WebDocument.kt` is the byte-compatibility-critical core (URL normalization §7, `d` tag §3, `x` content hash §8, validation §12.4). The spec §13 test vectors are in `app/src/test/.../WebDocumentTest.kt` — **keep them green**; drift breaks dedup against every other implementation.
- `server/sip01/Sip01SearchParser.kt` parses `filter.search` into operators; `Sip01Search.matches()` is the live-fanout predicate, `EventRepository.appendSip01Clauses` the SQL translation. Keep the two semantically identical.
- `service/Sip01IndexSync.kt` replicates kind 39697 from peer index relays (§6 federation) through the normal `innerProcessEvent` path (so sync'd events are validated + indexed too). Wired into `WebSocketServerService` next to `RelayAggregator`.
- Settings live in `Settings` (`sip01*`) and persist via `LocalPreferences`; UI is `ui/settings/Sip01SettingsScreen.kt` (route `Settings/Sip01`).

## Server architecture (non-obvious wiring)

- `service/CustomWebSocketService.kt` is a global `object` holding the single nullable `CustomWebSocketServer` instance (`CustomWebSocketService.server`). Started/stopped from `service/WebSocketServerService.kt` (foreground service). Many components feed events back into the server via `CustomWebSocketService.server?.innerProcessEvent(...)`.
- `server/Settings.kt` is a global `object`. Any settings change must be persisted with `LocalPreferences.saveSettingsToEncryptedStorage(Settings, context)` (EncryptedSharedPreferences), or it's lost on restart.
- Outbound relay traffic can route through **Tor** (`service/TorManager.kt`, `kmp-tor`), not just direct OkHttp WebSockets.
- Nostr protocol rules enforced in `server/CustomWebSocketServer.kt`:
  - Ephemeral: kinds 20000–29999 (deleted 1 min after creation if `deleteEphemeralEvents`).
  - Regular replaceable: kinds 0, 3, 10000–19999 — newest per pubkey+kind.
  - Parameterized replaceable: kinds 30000–39999 — newest per pubkey+kind+`d` tag.
  - Kind 5 deletes referenced events by the same pubkey.
  - NIP-42: with `authEnabled`, private kinds and `p`-tagged filters require authenticated pubkeys.

## Release

- Local release signing only happens when the `SIGN_RELEASE` env var is set; it then reads `keystore.properties` at repo root (gitignored, not checked in). Without it, `assembleRelease` produces unsigned APKs.
- `./build.sh <version> <appName>` runs `clean bundleRelease` + `assembleRelease`, moves outputs to `~/release/`, then invokes `generate_manifest.sh`. (Does not set `SIGN_RELEASE`.)
- CI (`create-release.yml`, on `v*` tags) signs via GitHub secrets, not `keystore.properties`.
- ABI splits are enabled: builds emit per-ABI APKs **plus** a universal APK. Debug universal artifact: `app/build/outputs/apk/debug/app-universal-debug.apk`.

## Dev / perf tooling

- `scripts/relay_bench.py` benchmarks the relay over WebSocket. Requires `pip install websockets` (optionally `coincurve` for faster signing). Run with `adb forward tcp:4869 tcp:4869` then `python3 relay_bench.py`, or `python3 relay_bench.py --url ws://<host>:4869`.

## Translations

- Managed via Crowdin (`crowdin.yml`). `androidResources.localeFilters` restricts shipped locales to `en, de, es, fr, ru, zh, zh-rCN`; `MissingTranslation` lint is disabled, so untranslated strings won't fail the build.
