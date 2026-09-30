# Hoodies-Network migration notes for consumers

These notes describe what changes for apps that depend on
`com.gap.androidlibraries:hoodies-networkandroid` as the library moves from the
original toolchain (JDK 11, AGP 7.1, Kotlin 1.6) to the modernized one tracked in
[`MIGRATION_STRATEGY.md`](MIGRATION_STRATEGY.md). The public Kotlin/Java API is
guarded by the binary-compatibility validator (`./gradlew :Hoodies-Network:apiCheck`,
dump in `Hoodies-Network/api/Hoodies-Network.api`); any intentional break is listed
under [Breaking changes](#breaking-changes).

## TL;DR checklist

1. Build your app with **JDK 17** and **AGP 8.x** (the library's bytecode targets Java 17).
2. Use a **Kotlin 2.x** compiler. The library is compiled with Kotlin 2.4.20 (K2), and
   `HoodiesNetworkClient.get/post/put/patch/delete` are `inline`/`reified`, so their
   bodies are compiled into *your* bytecode.
3. Compile your app against **API 35** or newer. `minSdk` is still **28**.
4. Don't count on the library pulling in Compose. It never used Compose; anything you
   need, declare in your own module (see the [sample app](#reference-sample-app)).
5. If you use `HttpCall.getHeaders()` / `HttpCall.httpExchange` from the bundled
   mock server, read [Mock server types](#mock-server-types-comsunnethttpserver).

## Build toolchain

| | Before | Now | What consumers need |
|---|---|---|---|
| JDK used to build | 11 | 17 | JDK 17 for your Gradle build |
| Java / Kotlin bytecode target | 1.8 | 17 | `compileOptions` / `jvmTarget` 17, which AGP 8 already requires |
| Android Gradle Plugin | 7.1.3 | 8.13.2 | AGP 8.x |
| Gradle | 7.5.1 | 8.14.5 | the Gradle version your AGP requires |
| Kotlin | 1.6.10 | 2.4.20 | Kotlin 2.x compiler |
| compileSdk / minSdk | 32 / 28 | 35 / 28 | `compileSdk >= 35`; `minSdk` unchanged |
| Build scripts | Groovy + `buildscript {}` | Kotlin DSL + `gradle/libs.versions.toml` | none; affects contributors only |

The published coordinates are unchanged: `com.gap.androidlibraries:hoodies-networkandroid`.

## Dependencies

- **Gson stays an `api` dependency.** Gson types appear in the public API and inside the
  inline request functions, so Gson is on your compile classpath. The strategy keeps
  Gson behind the existing signatures and upgrades it from 2.8.8 to 2.14.0 in its own
  workstream (WS05). If you depend on Gson's lenient parsing or on reflective
  `TypeToken` behaviour, re-run your JSON tests once that version lands.
- **Compose was removed from the library.** The old build declared Compose
  runtime/compiler 1.1.1 but no library code used it. One side effect is that the
  compiler-generated `public static final int $stable` fields have gone from the public
  classes (see `baseline/api-javap-diff-foundation.txt`). Nothing should read those
  fields. If your app got Compose transitively from Hoodies-Network, declare it yourself.
- Room, kotlinx-coroutines, AndroidX core/appcompat/material and the test libraries are
  `implementation` dependencies. Their upgrades don't change your compile classpath.
  They can still change the transitive versions Gradle resolves in your app.

## Mock server types (`com.sun.net.httpserver`)

`MockWebServerManager`, `WebServerHandler`, `HttpCall` and `MockServerMaker` are in the
library's `main` source set and ship in the AAR. Two members of `HttpCall` expose types
from the bundled `http-2.2.1.jar`, which is on the library's `implementation` classpath:

- `HttpCall.httpExchange: com.sun.net.httpserver.HttpExchange`
- `HttpCall.getHeaders(): com.sun.net.httpserver.Headers`

Since the jar is not exported, Kotlin code in your module that calls these members fails
to compile with `Cannot access class 'com.sun.net.httpserver.Headers'`. The classes are
packaged in the AAR at runtime, so compiling against the jar is enough:

```kotlin
// build.gradle.kts of the module that writes WebServerHandlers
dependencies {
    implementation("com.gap.androidlibraries:hoodies-networkandroid:<version>")
    compileOnly(files("<path to>/http-2.2.1.jar"))
}
```

The sample app does exactly this (`sample/build.gradle.kts`). Handlers that only use
`HttpCall.respond(...)`, `getBodyString()` and `getFormUrlEncodedParameters()` don't need it.

The bundled jars are due to be replaced by a maintained mock server (workstream WS07,
risk R5 in the strategy). That change either keeps a `com.sun.net.httpserver`-compatible
shim or makes a documented, justified break to `httpExchange`. Its PR will update this
section with the chosen option.

## Breaking changes

None to the public API so far. `apiCheck` passes against the Phase 1 dump. The only
binary-visible difference is the removal of the Compose `$stable` fields described
above.

## Reference sample app

`:sample` is a Jetpack Compose app that uses the library the way a consumer would. It
runs the library's own `MockWebServerManager` in-process on `localhost:8089`, so it
needs no external network. It has one screen per core feature:

| Screen | Library API shown |
|---|---|
| GET | `client.get<Greeting>("greeting")`, a typed JSON response |
| POST | `client.post<Note, EchoResponse>("echo", note)`, typed body serialization |
| Image | `client.getImage(...)`, decoded to a `Bitmap` |
| Cache | `CacheEnabled(staleDataThreshold, applicationContext)` compared with `CacheDisabled()`, with the mock server's hit counter showing whether a response came from cache |
| Interceptor | an `Interceptor` that adds an auth header in `interceptRequest` and logs the network/request/response/error stages |

Build and run it:

```sh
./gradlew :sample:installDebug
adb shell am start -n com.gap.hoodies_network.sample/.MainActivity
./gradlew :sample:connectedDebugAndroidTest   # Compose UI test covering all five screens
```

The sample uses its own Compose dependencies (Compose BOM, `activity-compose`,
`lifecycle-*-compose`) and the `org.jetbrains.kotlin.plugin.compose` compiler plugin.
None of them leak into the library. It is pinned to Compose BOM **2026.06.01**
(Compose UI 1.11.4). Compose BOM 2026.09.00 and later (Compose 1.12) need AGP 9.1+ and
compileSdk 37 according to their AAR metadata. The build stays on AGP 8.x, so it can't
consume them yet.
