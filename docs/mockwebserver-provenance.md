# Mock web server: provenance of the removed JARs and their replacement

Workstream WS07 (GOL-171) of `MIGRATION_STRATEGY.md`, risk R5.

## Removed artifacts

Both JARs were added in the initial commit of the upstream repository (`699eb9f`, 2023-02-28,
"Initial commit") as local `implementation(files(...))` dependencies of `:Hoodies-Network`. Neither
has a Maven coordinate, a `pom.properties`, a licence or a notice file.

| File | Size (bytes) | SHA-256 | `META-INF/MANIFEST.MF` | Contents |
|---|---|---|---|---|
| `Hoodies-Network/libs/http-2.2.1.jar` | 82 265 | `6686a96c76b769c3bdb03edf97462d9444ec6d58c94e7522a6f57184ddffe824` | `Created-By: 1.5.0 (Sun Microsystems Inc.)` | 78 entries, all dated 2008-12-05: 19 classes in `com/sun/net/httpserver`, 2 in `com/sun/net/httpserver/spi`, 47 in `sun/net/httpserver`. A copy of the JDK 6 `com.sun.net.httpserver` implementation. |
| `Hoodies-Network/libs/sun-common-server.jar` | 1 701 943 | `cf9628d7c36e3c66185878035772af7292164015054267ff0b5dff4f03f0664f` | `Created-By: 1.8.0_102 (Oracle Corporation)` | 892 entries: 171 classes in `sun/misc/**` (including `sun/misc/SharedSecrets`) and ~700 in `sun/security/**` (x509, krb5, jgss, pkcs, provider, policytool, ...). Copied JDK 8u102 internals. |

Why they had to go:

* **Licence**: both are redistributed copies of Sun/Oracle JDK internals with no licence file; the
  terms under which they were copied cannot be established.
* **Shadowing platform classes**: `sun-common-server.jar` puts its own `sun.misc.SharedSecrets` on
  the test classpath. On API 35 Mockito's `StackTraceFilter` loads it, its static initializer calls
  `Unsafe.getUnsafe()`, and ART throws `SecurityException: Unsafe access denied`. This is the root
  cause of the API 35 failures of `SocketTimeOutTest#socketConnectTimeOutTest` and
  `#socketReadTimeOutTest`.
* **Unmaintained**: the HTTP server code dates from 2008 and receives no fixes.

## Replacement

`com.squareup.okhttp3:mockwebserver3:5.3.2` (Square, Apache License 2.0,
<https://github.com/square/okhttp>), declared in `gradle/libs.versions.toml` as
`okhttp-mockwebserver3` and consumed via `implementation(libs.okhttp.mockwebserver3)`. It pulls in
`com.squareup.okhttp3:okhttp` (Android variant `okhttp-android`) and `com.squareup.okio:okio`, both
Apache 2.0.

5.5.0 was the leading candidate, but its `okhttp-android` AAR declares `minCompileSdk=37` (5.4.0:
36) and `:Hoodies-Network` compiles against android-35 with AGP 8.13 (max supported 36).
`compileSdk`/AGP are owned by another workstream, so WS07 pins the newest release whose AAR
metadata accepts compileSdk 35. Bumping to 5.5.0 is a one-line catalog change once compileSdk ≥ 37.

## How the public API is preserved (R5, option a)

`MockWebServerManager`, `WebServerHandler`, `HttpCall` and `MockServerMaker` keep their exact
signatures, including `HttpCall.httpExchange`, `HttpCall.getHeaders()`,
`HttpCall.setResponseHeaders(Headers)` and `WebServerHandler.internalHandler`.

To do that without the JARs, the library ships small Kotlin re-implementations of the three
`com.sun.net.httpserver` types those signatures use, under
`Hoodies-Network/src/main/kotlin/com/gap/hoodies_network/mockwebserver/httpserver/`:

* `Headers` — `MutableMap<String, MutableList<String>>` with the JDK's key normalization
  (first character upper-case, the rest lower-case), `getFirst`, `add`, `set`.
* `HttpExchange` — abstract class with the request/response members the mock server uses
  (`requestHeaders`, `responseHeaders`, `requestURI`, `requestMethod`, `requestBody`,
  `responseBody`, `remoteAddress`, `localAddress`, `protocol`, `responseCode`,
  `sendResponseHeaders`, `get/setAttribute`, `close`). `getHttpContext()`, `getPrincipal()` and
  `setStreams()` are not provided.
* `HttpHandler` — `fun interface` with `handle(HttpExchange)`.

These are new code written against the public JDK API contract, not copies of JDK sources.

`MockWebServerManager` runs a `mockwebserver3.MockWebServer` with a `Dispatcher` that reproduces
the old `HttpServer` behaviour:

* binds the wildcard address on the configured port (default 6969);
* routes by longest context-path prefix, returning the JDK's `404 Not Found` page when no context
  matches;
* dispatches one exchange at a time, like the JDK server's single dispatcher thread;
* converts `sendResponseHeaders(code, length)` into a fixed-length (`length > 0`), chunked
  (`length == 0`) or empty (`length == -1`) response with the JDK reason phrase and a `Date` header;
* leaves the connection open without a response when the handler never calls
  `sendResponseHeaders` (e.g. `HEAD`/`TRACE` to a handler that only registers `get { }`), and closes
  it when the handler throws;
* reports the real client/server socket addresses via `remoteAddress`/`localAddress`.

### Build note

kapt compiles Java stubs of all Kotlin sources with the host JDK, whose `jdk.httpserver` module
owns `com.sun.net.httpserver`. `Hoodies-Network/build.gradle.kts` therefore passes
`--limit-modules java.se,jdk.unsupported` to kapt's javac. Android compilation itself uses the
Android system image, which has no such package.

### API dump

`Hoodies-Network/api/Hoodies-Network.api` gains entries for the three `com/sun/net/httpserver`
classes. All existing `com.gap.hoodies_network.mockwebserver` entries are unchanged, so existing
callers remain source- and binary-compatible; the only removed members are the
`HttpExchange.getHttpContext()`, `getPrincipal()` and `setStreams()` methods and the
`com.sun.net.httpserver.HttpServer`/`HttpContext`/`spi` types, none of which were reachable from the
library's public API.
