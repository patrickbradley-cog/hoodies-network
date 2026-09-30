# Hoodies-Network Modernization Strategy

Status: **Phase 1 (foundation) merged to `main` in #2. Phase 2 workstreams branch from `main`.**
Fork: `patrickbradley-cog/hoodies-network`. No PRs are ever opened against `gapinc/hoodies-network`.
Baseline commit: `4846a5a` (`main`, untouched upstream code).

## 1. Measured baseline

All numbers below were measured in this environment on the untouched code. Nothing here is estimated.

| Check | Command | Environment | Result |
|---|---|---|---|
| Build | `./gradlew :Hoodies-Network:assembleDebug` | OpenJDK 11.0.32.1, Gradle 7.5.1, AGP 7.1.3 | **PASS** |
| Lint | `./gradlew :Hoodies-Network:lintDebug` | same | **PASS, 0 issues** ("No issues found.") |
| Instrumented tests, run 1 (recorded) | `./gradlew :Hoodies-Network:connectedDebugAndroidTest --continue` | API 30 x86_64 AVD (`api30`), KVM | **124 / 124 passed, 0 failed, 0 skipped** |
| Instrumented tests, run 2 (coverage) | `./gradlew :Hoodies-Network:createDebugCoverageReport` | same | **124 / 124 passed** — 0 diffs vs run 1 (no flakes observed across 2 runs) |
| Coverage (JaCoCo 0.8.7, androidTest) | from run 2 | same | line **84.32%** (1699/2015), branch **59.58%** (367/616), instruction 81.07%, method 85.47%, class 90.43% |
| Dependency vulnerabilities (OSV, `releaseRuntimeClasspath`, 55 artifacts) | OSV `querybatch` | — | **1 HIGH**: `com.google.code.gson:gson:2.8.8` → GHSA-4jrv-ppp4-jm57 (deserialization of untrusted data, fixed in 2.8.9). 0 critical. |

Evidence (committed in this PR unless noted):

- `baseline/test-results.json` — per-test pass/fail/skip + durations (124 entries)
- `baseline/coverage.json`, `baseline/coverage-report.xml` — JaCoCo baseline
- `baseline/lint/lint-results-debug.{txt,xml,sarif}` — lint baseline (empty)
- `baseline/release-runtime-classpath.txt` — resolved runtime dependency list used for the OSV scan
- `validation/baseline-api30/` — JUnit XML, Gradle log, annotated timeline; desktop and on-device recordings are attached to the PR (MP4s are git-ignored to keep the repo small)
- `validation/baseline-api30-run2/` — second run JUnit XML + diff table vs baseline

**Known failures at baseline: none.** Every one of the 124 tests is "baseline-passing" and must stay passing.

### 1.0 API 35 baseline (measured during Phase 1)

The untouched code was also run on the API 35 AVD. AGP 7.1.3's UTP runner crashes against an API 35 device (`IllegalAccessError` in protobuf), so the baseline test APK was installed and run with `adb shell am instrument -w -r` (raw output in `validation/baseline-api35/instrument.txt`, converted to JUnit XML there). Result: **121 / 124 passed, 3 failed** (`baseline/test-results-api35.json`). These three are **pre-existing API 35 failures**, not migration regressions:

| Test | Cause (from stack trace) | Owner |
|---|---|---|
| `SocketTimeOutTest#socketConnectTimeOutTest` | Mockito's `StackTraceFilter` loads `sun.misc.SharedSecrets`; the copy bundled in `sun-common-server.jar` shadows the platform class and calls `Unsafe.getUnsafe()` → `SecurityException: Unsafe access denied` on API 35 | WS07 (removing the bundled JARs) |
| `SocketTimeOutTest#socketReadTimeOutTest` | same class-init failure (`NoClassDefFoundError: StackTraceFilter`) | WS07 |
| `CookieTests#cookieTestPersistent` | `JSONException: No value for null` at `CookieTests.kt:66` — a cookie with a `null` name comes back from `PersistentCookieJar` on API 35 | WS02 (SDK 35 behaviour), with WS03 if the cause is in persistence |

Gates: on **API 30** all 124 must pass; on **API 35** zero regressions vs `baseline/test-results-api35.json`, and the three above must be fixed by their owners before the final PR.

### 1.1 Test-count discrepancy (42 vs 124)

The brief says "42 instrumented tests". The measured count is **124**: there are exactly 124 `@Test` methods across 19 classes in `Hoodies-Network/src/androidTest`, and Gradle reported `Starting 124 tests on api30(AVD) - 11`. `HoodiesNetworkClientTest` alone has 57. The README lists 18 class names (one of them, `ResponseDeliveryInstant`, is a helper with no `@Test` methods). "42" does not match any count we can derive from the repo. We use **124** as the baseline and treat the brief's number as out of date.

### 1.2 Baseline reproducibility notes (not committed, environment-only)

The untouched build cannot resolve today without help, which is itself a finding for WS01:

1. `org.ajoberstar.grgit:5.0.0` is not resolvable because `settings.gradle` has no `pluginManagement {}` repositories.
2. `org.jfrog.buildinfo:build-info-extractor-gradle:latest.release` floats; the current release no longer works with Gradle 7.5.1.
3. Maven Central returned HTTP 429 from this VM.

The baseline was built with an external init script (outside the repo) that only adds plugin repositories, uses Google's Maven Central mirror, and pins the JFrog plugin to `4.29.2`. No project source or build file was modified.

## 2. Architecture map

Sources: DeepWiki (`devin_ask_wiki_question` on the fork) + a full read of `Hoodies-Network/src/main` (56 Kotlin files).

```
HoodiesNetworkClient (public, inline/reified get/post/put/delete/patch… + Builder)
  └─ HoodiesNetworkClientNonInlined (public-but-internal-ish; Gson(), CoroutineScope(Dispatchers.IO).launch)
       ├─ Interceptor chain (interceptRequest / interceptNetwork / interceptResponse / interceptError)
       ├─ EncryptionDecryptionInterceptor (user-supplied request/response crypto)
       ├─ EncryptedCache  ── Room CacheDatabase "HoodiesNetworkCache" (v1, exportSchema=false)
       │     └─ runAES(): AES/GCM/NoPadding, 128-bit tag, 12-byte SecureRandom IV
       │           └─ CacheKeyManager: AndroidKeyStore alias "HoodiesNetworkCacheKey",
       │              setRandomizedEncryptionRequired(false), key size not set (platform default)
       ├─ CookieJar / PersistentCookieJar ── Room EncryptedCookieDatabase (v1, exportSchema=false)
       │     └─ EncryptedDaoWrapperForCookies: Gson(HttpCookie) → runAES
       └─ Request<T> (StringRequest, JsonObjectRequest, JsonArrayRequest, ImageRequest, File*, FormUrlEncoded, UrlQueryParam*)
            └─ RequestQueue (singleton, PriorityBlockingQueue, 4 × QueueHandler : Thread)
                 └─ NetworkHandler → BaseNetwork (HttpURLConnection, optional SSLSocketFactory,
                        cookies, response interception, cache write)
                      └─ ResponseDeliveryExecutor (Handler(Looper.getMainLooper()))
MockWebServerManager / WebServerHandler / HttpCall / MockServerMaker
  └─ com.sun.net.httpserver.* from bundled libs/http-2.2.1.jar + libs/sun-common-server.jar
```

Coupling that drives the workstream split:

- **Crypto is shared** by cache and cookies (`EncryptedCache.runAES/genIV`), so WS03 (Room) and WS06 (crypto) touch adjacent code → WS03 merges first, WS06 rebases.
- **Gson is used in three places**: public `api` dependency, `HoodiesNetworkClientNonInlined` (request/response), and cookie persistence (stored format!). Changing cookie serialization changes on-disk data.
- **The mock server is in the `main` source set**, so it ships in the release AAR, and `HttpCall.httpExchange: com.sun.net.httpserver.HttpExchange` is **public API that leaks a type from the bundled JAR**.
- **Unstructured concurrency** in two places: `EncryptedCache` (`CoroutineScope(Dispatchers.IO).launch` for writes) and `HoodiesNetworkClientNonInlined:708`. Plus a hand-rolled `Thread` pool in `RequestQueue`.
- **Compose, appcompat, material and core-ktx are declared but unused** by `src/main` (verified with ripgrep: zero imports, zero `@Composable`).

## 3. Version inventory: current → target

Target versions are the latest stable releases that satisfy the brief's constraints (Gradle **8.x**, AGP **8.x**) and were published ≥ 7 days ago, checked against Maven Central / Google Maven / services.gradle.org on 2026-09-30. Phase 1 re-verifies before pinning; anything newer that appears is only taken if it is ≥ 7 days old.

| Component | Current | Target | Published | Notes |
|---|---|---|---|---|
| JDK (build) | 11 | **17** | — | Gradle toolchain |
| Gradle wrapper | 7.5.1 | **8.14.5** | 2026-05-07 | latest 8.x (9.x exists; brief says 8.x) |
| AGP | 7.1.3 | **8.13.2** | 2025-12-11 | latest 8.x (9.x exists; brief says 8.x); `namespace` replaces manifest `package` |
| Kotlin | 1.6.10 | **2.4.20** | 2026-09-08 | K2 compiler; `compilerOptions` DSL |
| Compose | runtime/compiler 1.1.1 (unused) | **removed from library**; sample uses Compose BOM **2026.09.00** + `org.jetbrains.kotlin.plugin.compose` | 2026-09-09 | library has zero Compose usage |
| Annotation processing | kapt + annotationProcessor | **KSP 2.3.12** | 2026-09-10 | KSP2, decoupled from Kotlin version |
| compileSdk / targetSdk | 32 / 32 | **35 / 35** | — | minSdk stays **28** |
| Java/Kotlin bytecode target | 1.8 | **17** | — | library consumers need AGP 8+ anyway |
| Room | 2.4.2 | **2.8.5** | 2026-09-09 | schema export on, migration test |
| kotlinx-coroutines | 1.3.9 | **1.11.0** | 2026-05-09 | + `kotlinx-coroutines-test` for `runTest` |
| Gson (`api`) | 2.8.8 | **2.14.0** | 2026-04-24 | clears GHSA-4jrv-ppp4-jm57; stays `api` |
| androidx.core-ktx / appcompat / material | 1.6.0 / 1.3.1 / 1.4.0 | **removed** if still unused after WS02; otherwise latest stable | — | none referenced by `src/main` |
| Mock server | bundled `http-2.2.1.jar` + `sun-common-server.jar` | maintained, license-clean dependency (WS07 decides; leading candidate `com.squareup.okhttp3:mockwebserver3` **5.5.0**, 2026-08-17, Apache-2.0) | — | see risk R5 |
| Binary compatibility validator | — | **0.18.2** | 2026-09-03 | `apiCheck` in CI |
| Dokka | 1.7.10 | latest stable 2.x (WS01 pins) | — | |
| JaCoCo | 0.8.7 | latest stable (0.8.15 seen) | — | via `enableAndroidTestCoverage` |
| JFrog build-info plugin | `latest.release` | **removed** (unused) | — | floating version |
| Grgit / git-publish | 5.0.0 / 3.0.0 | removed or pinned+`pluginManagement` (WS01) | — | used only for docs publishing |
| JUnit4 / androidx.test / espresso / mockito-android | 4.12 / 1.4.0 & 1.1.3 / 3.4.0 / 4.8.0 | 4.13.2 / latest stable / latest stable / **5.x** (5.24.0 seen) | — | WS09 pins |
| Jetifier | on | **off** | — | no support-library deps |
| Build config | Groovy `buildscript {}` | **`gradle/libs.versions.toml` + plugins DSL** | — | Phase 1 |

## 4. Risk register

| ID | Risk | Likelihood / impact | Mitigation | Owner | Detection |
|---|---|---|---|---|---|
| R1 | **Room schema drift** breaks existing `HoodiesNetworkCache` / cookie DB on upgrade (Room 2.4.2 → 2.8.5, kapt → KSP). Both DBs are `version = 1, exportSchema = false`, so there is no baseline JSON schema. An identity-hash change without a migration causes "Room cannot verify the data integrity" or, if `fallbackToDestructiveMigration` is added, silent data loss. | Medium / High | (a) keep entity definitions byte-for-byte equivalent, stay on `version = 1` unless a change is needed; (b) turn on `exportSchema` and commit `schemas/…/1.json` generated from **unchanged** entities; (c) **Room 2.4.2 fixture test**: build baseline commit `4846a5a`, create DBs on-device, commit the raw `.db` files as androidTest assets; new test opens them with the upgraded Room and asserts rows are readable; (d) **in-place upgrade test**: install baseline APK, write cache+cookies, `adb install -r` the upgraded APK (Keystore key survives an update), read back. No destructive fallback allowed. | WS03 | fixture test + upgrade test in validation loop |
| R2 | **Public Gson `api` exposure**: Gson types are on consumers' compile classpath and inside `HoodiesNetworkClient` inline/reified functions (inlined into consumer bytecode). Upgrading 2.8.8 → 2.14.0 changes behaviour consumers can observe (stricter `TypeToken` validation, `JsonReader` strictness defaults, bundled R8 rules). | Medium / Medium | keep `api` scope; keep public signatures; add a `Serializer` abstraction behind the existing API (default: Gson) for future kotlinx.serialization; per-change BCV `apiCheck`; add JVM tests for number/null/nested generic handling before and after the bump. | WS05 | `apiCheck`, JSON tests |
| R3 | **Crypto / Keystore**: any change to cipher transformation, tag length, IV length, key alias, or key parameters makes existing encrypted cache and cookie rows unreadable. Known items: IV is 12 bytes but KDoc says 16; `setRandomizedEncryptionRequired(false)`; key size not set; no `setUserAuthenticationRequired`/StrongBox choice documented; key generation logs via `println`. | Medium / High | WS06 is **audit-first**: produce a findings table (severity, file:line, recommendation, compat impact). Fixes that keep the on-disk format (AES-GCM, 128-bit tag, 12-byte IV, same alias) land directly. Any format change must be versioned (read old, write new) and proven with the R1 upgrade test. | WS06 | findings table, upgrade test |
| R4 | **SDK 35 behaviour changes**: `READ_EXTERNAL_STORAGE` is ignored on 33+; tighter cleartext and `targetSandboxVersion` interactions; deprecations (`Handler()` no-arg, `NetworkInfo`/`activeNetworkInfo`, `ConnectivityManager` callbacks); package-visibility; `HttpURLConnection` behaviour on 35. | Medium / Medium | WS02 fixes deprecated/removed APIs with **no new lint baseline entries** (baseline lint is 0 issues, so any lint finding is new); CI runs API **28** (minSdk) and **35** (target). | WS02 | lint, API 28 + 35 runs |
| R5 | **Mock server replacement**: Android has no `com.sun.net.httpserver`; the JARs are 2008-era copies (`Created-By: 1.5.0 (Sun Microsystems Inc.)`) with no licence file, and `sun-common-server.jar` contains copied `sun.*` JDK classes (licence unclear). **`HttpCall.httpExchange` exposes `com.sun.net.httpserver.HttpExchange` in the public API**, so removing the JARs is a binary break for anyone who used that property. | High / Medium | preserve `MockWebServerManager`, `WebServerHandler`, `HttpCall`, `MockServerMaker` signatures that don't leak `com.sun.*`; for `HttpCall.httpExchange` either (a) keep a thin `com.sun.net.httpserver`-compatible shim or (b) take an **explicitly justified, documented break** recorded in the API dump and migration notes. WS07 must propose (a) vs (b) in its PR; the final PR calls it out. Document provenance + SHA-256 of removed JARs: `http-2.2.1.jar` `6686a96c76b769c3bdb03edf97462d9444ec6d58c94e7522a6f57184ddffe824`, `sun-common-server.jar` `cf9628d7c36e3c66185878035772af7292164015054267ff0b5dff4f03f0664f`. | WS07 | `apiCheck`, all 124 tests (they all use the mock server) |
| R6 | **Inline/reified public API**: `HoodiesNetworkClient.get/put/…` are `inline` and call into `HoodiesNetworkClientNonInlined`, so that class's members are effectively public ABI even if they look internal. | Medium / High | BCV dump from Phase 1 is the contract; no member of `HoodiesNetworkClientNonInlined` used from an inline body may change signature. | WS04, WS05, WS08 | `apiCheck` |
| R7 | **Concurrency rewrite** changes ordering/delivery semantics (main-thread delivery, priority queue, retry). | Medium / Medium | keep `RequestQueue` public surface; inject dispatchers; structured scopes with cancellation; `runTest` JVM tests; the 124 instrumented tests are the behavioural contract. | WS04 | tests |
| R8 | **Parallel-branch conflicts** between ten workstreams. | High / Low | strict path ownership (§5), shared-file exceptions listed explicitly, fixed merge order with rebase-before-merge, `apiCheck` on every merge. | Lead | Phase 3 |
| R9 | **Toolchain/network flakiness** (Maven Central 429s seen here, emulator boot). | Medium / Low | Gradle caching in CI, Google Maven Central mirror only in local init scripts (never committed), retry emulator boot; never paper over test failures. | WS01 / all | CI |

## 5. Workstreams and path ownership

Paths are relative to `Hoodies-Network/src/main/kotlin/com/gap/hoodies_network/` unless they start with `/` (repo root) or `src/` (module). Every workstream may also edit **its own new tests** and **its own entries** in `gradle/libs.versions.toml` and `Hoodies-Network/build.gradle.kts`. Everything else is off-limits; if a workstream needs a change elsewhere it records it in its PR's "Incomplete items" for the owner.

| WS | Branch | Scope | Owned paths | Acceptance criteria (beyond the common DoD) |
|---|---|---|---|---|
| 01 | `migration/ws-01-build` | Build, CI, coverage, publishing | `/build.gradle*`, `/settings.gradle*`, `/gradle.properties`, `/gradle/**` (except other WS catalog entries), `/.github/**`, Jacoco + Dokka + publishing config in module build file | no floating versions; JFrog plugin removed; Jetifier off; configuration + build cache on; `enableAndroidTestCoverage` replaces `testCoverageEnabled`; Jacoco + Dokka tasks work; `singleVariant("release")` publishing; workflows on actions v4, JDK 17, API 28 + 35 matrix, Gradle caching, artifact upload of JUnit/lint/coverage/api |
| 02 | `migration/ws-02-sdk35` | targetSdk 35 + platform APIs | `src/main/AndroidManifest.xml`, `/Hoodies-Network/lint.xml` (new), `utils/**`, `config/**`, `delivery/**`, `header/**` | targetSdk 35; deprecated/removed APIs fixed; unused permissions removed or justified; **0 lint issues** (no `lint-baseline.xml`) |
| 03 | `migration/ws-03-room` | Cache + cookie persistence | `cache/**`, `cookies/**`, `/Hoodies-Network/schemas/**`, `src/androidTest/assets/room-2.4.2/**` | KSP; `exportSchema = true` with committed v1 schemas; Room 2.4.2 fixture test + in-place upgrade test (R1) pass; no destructive fallback |
| 04 | `migration/ws-04-coroutines` | Concurrency | `core/**`, `connection/**` | no `GlobalScope` / ad-hoc `CoroutineScope(...).launch`; dispatchers injectable; cancellation propagates to `HttpURLConnection`; `runTest` JVM tests; **shared-file exception:** may change only scope/dispatcher plumbing in `cache/EncryptedCache.kt` (after WS03 merged) |
| 05 | `migration/ws-05-json` | JSON serialization | `request/json/**`, `request/query/**`, new `serialization/**` | Gson 2.14.0 kept as `api`; `Serializer` abstraction with Gson default; public API unchanged per `apiCheck`; **shared-file exception:** may replace `Gson()` construction/call sites in `core/HoodiesNetworkClientNonInlined.kt` (after WS04 merged) |
| 06 | `migration/ws-06-security` | Keystore, interceptors, crypto, TLS | `keystore/**`, `interceptor/**`, new `crypto/**`, `/docs/security/**` | findings table (cipher modes, IVs, key sizes, deprecated APIs, TLS/custom trust, encrypted-data readability); on-disk format preserved or versioned + proven by upgrade test; **shared-file exception:** may move `runAES`/`genIV` from `cache/EncryptedCache.kt` into `crypto/` leaving delegating functions (after WS03 merged); TLS findings in `connection/BaseNetwork.kt`/`config/HttpClientConfig.kt` are reported, fixed by owners |
| 07 | `migration/ws-07-mockserver` | Mock web server | `mockwebserver/**`, `/Hoodies-Network/libs/**` (delete), `/docs/mockwebserver-provenance.md` | JARs removed; maintained license-clean dependency; `MockWebServerManager` API preserved; R5 decision documented; all 124 tests green on the new server |
| 08 | `migration/ws-08-requests` | Request types | `request/*.kt` (top-level only: `Request`, `StringRequest`, `ImageRequest`, `FileRequest`, `FileUploadRequest`, `FormUrlEncodedRequest`, `CancellableMutableRequest`, `RetryableCancellableMutableRequest`) | cancellation/retry modernized with identical observable behaviour; KDoc on every public type/member in owned files |
| 09 | `migration/ws-09-tests` | Test infrastructure | `src/androidTest/**` infrastructure (`AndroidManifest.xml`, `mockwebserver/ServerManager.kt`, `testObjects/**`, runner config), new `src/test/**` scaffolding | AndroidX test / JUnit 4.13.2 / Mockito 5; no real-network dependency (host names like `gap.com` used only as strings are fine); Gradle Managed Devices for API 28 + 35; coverage before (baseline §1) vs after reported. **Never** deletes/ignores/weakens an existing test |
| 10 | `migration/ws-10-sample` | Sample + docs | `/examples/**`, new `/sample/**` module, `/README.adoc`, `/docs/**` (except security + provenance), `/MIGRATION_NOTES.md` | Compose sample with GET / POST / image / cache / interceptor screens against the local mock server; Compose UI test covering all screens; migration notes for consumers |

## 6. Phases and merge order

- **Phase 1 (lead, after approval):** `migration/foundation` from `main`. JDK 17, Gradle 8.14.5, AGP 8.13.2, Kotlin 2.4.20, `namespace`, `gradle/libs.versions.toml`, plugins DSL (Kotlin DSL), no floating versions, KSP wired only as needed to keep the build green, BCV 0.18.2 with the **current** API committed as `Hoodies-Network/api/Hoodies-Network.api`. Validate on **API 35** with the full loop. Behaviour-preserving only; the heavier changes belong to the workstreams.
- **Phase 2:** all ten child sessions start at the same time from the foundation commit, each on `migration/ws-NN-<slug>`, each opening a PR into `migration/foundation`.
- **Phase 3 merge order (fixed):** **01 → 03 → 06 → 07 → 04 → 05 → 08 → 02 → 09 → 10**. Before each merge: rebase onto the current `migration/foundation`, re-run `assembleDebug lintDebug apiCheck connectedDebugAndroidTest`, Devin Review pass. Then final validation on **API 28 + API 35 + sample UI test**, and one PR `migration/foundation` → `main` (**not merged**).

Why this order: build/CI first so every later merge is gated by the new CI; persistence (03) before crypto (06) because 06 edits code 03 owns; mock server (07) before code workstreams because all tests depend on it; concurrency (04) before JSON (05) and requests (08) because both call through `core/`; SDK 35 (02) late because it touches cross-cutting deprecations; tests (09) and sample/docs (10) last so they cover the final API.

## 7. Validation loop (every workstream, every iteration)

1. SDK: platforms 28 + 35, matching x86_64 system images, emulator with KVM, **visible window** (no `-no-window`).
2. Wait for `adb shell getprop sys.boot_completed` == `1`.
3. `scripts/validation/run_recorded.sh <workstream> <serial> -- :Hoodies-Network:assembleDebug :Hoodies-Network:lintDebug :Hoodies-Network:apiCheck :Hoodies-Network:connectedDebugAndroidTest` — records the desktop (emulator + terminal) with START / RUN / RESULT annotations and the device screen, collects JUnit XML and lint reports under `validation/<workstream>/`.
4. `python3 scripts/validation/parse_results.py --xml-dir validation/<ws>/junit --out validation/<ws>/test-results.json --baseline baseline/test-results.json --diff-md validation/<ws>/diff.md` — exits non-zero on any regression of a baseline-passing test.
5. Stop after **three** failed iterations and report the exact blocker. Never `@Ignore`, delete, or weaken a test.

## 8. Definition of done

- JDK 17, Gradle 8.x, AGP 8.x, Kotlin 2.x, compile/target SDK 35, KSP (no kapt), Compose removed from the library, current AndroidX/Room/coroutines/Gson, **no bundled JARs**, version catalog, no floating versions, every new version ≥ 7 days old.
- **124 / 124** baseline tests pass on API 28 **and** API 35; no `@Ignore`, deletions, or weakened assertions (reviewable via diff of `src/androidTest`).
- Lint: 0 issues (baseline is 0), no lint baseline file.
- `apiCheck` green against the Phase 1 dump; any break is listed and justified in the final PR (only R5 is currently anticipated).
- Dependency scan: no high or critical vulnerabilities (baseline has 1 high, which the Gson bump removes).
- Room fixture test (DB created by Room 2.4.2) and in-place upgrade test pass: existing cache and cookie data stay readable.
- Coverage reported before/after (baseline line 84.32%, branch 59.58%); the existing CI gate of 80% overall keeps passing.
- CI green on the final PR (API 28 + 35 matrix, lint, apiCheck, coverage).
- Final PR contains: version table, baseline vs final tests, coverage before/after, `apiCheck`, security findings, links to all ten workstream PRs + videos, known gaps.

## 9. Rollback plan

- `main` is never modified by this effort until a human merges the final PR. Rolling back before that = closing PRs / deleting `migration/*` branches.
- Each workstream merges into `migration/foundation` as a **single merge commit**, so any one workstream can be reverted with `git revert -m 1 <merge>` without touching the others; reverting follows reverse merge order to avoid conflicts.
- After release: the only non-code-reversible risk is **on-device data** (R1, R3). The DoD forbids destructive migrations and format changes without a read-old path, so an app that downgrades to the previous library version still sees Room `version = 1` databases with the same encryption format. If WS03/WS06 ever need a schema or format bump, that PR must include a downgrade note and the version bump is flagged in the final PR as a rollback constraint.
- Publishing: the release workflow publishes a new version; rollback for consumers is pinning the previous version (no artifact is overwritten).
