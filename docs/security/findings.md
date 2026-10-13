# WS06 security audit: keystore, interceptors, crypto, TLS

Audited revision: `main @ b105af9`. Line numbers below refer to that revision.
Severity scale: High / Medium / Low / Info.

## Encrypted on-disk format (v1)

The response cache (`cache/EncryptedCache.kt`) and the persistent cookie store
(`cookies/persistentstorage/EncryptedDaoWrapperForCookies.kt`) share one primitive and one key:

| Property | Value |
|---|---|
| Transformation | `AES/GCM/NoPadding` |
| Tag | 128 bit, appended to the ciphertext by the JCA provider |
| IV | 12 bytes (96 bit) from `SecureRandom`, Base64-encoded in its own Room column |
| Associated data | none |
| Key | AndroidKeyStore alias `HoodiesNetworkCacheKey`, AES, `GCM` / `NoPadding`, randomized encryption disabled |
| Key size | platform default for keys created before WS06 (128 bit observed on the API 35 emulator); 256 bit for keys created by WS06 and later |

WS06 does **not** change this format. Readability is proven in both directions for cache and cookies by
`Hoodies-Network/src/androidTest/kotlin/com/gap/hoodies_network/CryptoCompatTest.kt`, which carries a verbatim copy
of the pre-WS06 key generation, IV generation and AES code (`LegacyV1Crypto`) and runs in the recorded
`connectedDebugAndroidTest` loop on API 30 and API 35.

## Findings

| # | Area | Severity | Location (b105af9) | Finding | Recommendation | Status | Compat impact |
|---|---|---|---|---|---|---|---|
| 1 | Cipher mode | Info | `cache/EncryptedCache.kt:122-126` | `AES/GCM/NoPadding` with a 128-bit tag is an AEAD mode; tampered or wrong-key rows fail with `AEADBadTagException` instead of returning garbage. | Keep. | Kept; primitive also available as `crypto/AesGcm.kt` with named constants for mode, tag and IV length. | None |
| 2 | IV length / KDoc | Low | `cache/EncryptedCache.kt:128-134` | KDoc says "SecureRandom 16-byte IV" but the code generates 12 bytes. 12 bytes is the correct (NIST SP 800-38D recommended) GCM IV length; the KDoc is wrong, not the code. | Keep 12 bytes, fix the KDoc; reject IVs of any other length so a caller cannot silently write a row the format does not describe. | `crypto/AesGcm.kt` documents 12 bytes and rejects other lengths. Fixing the KDoc in `EncryptedCache.kt` is part of the delegation step (see Incomplete items). | None: every row ever written has a 12-byte IV |
| 3 | IV uniqueness | Info | `cache/EncryptedCache.kt:40-44`, `cookies/persistentstorage/EncryptedDaoWrapperForCookies.kt:44-48` | Each table re-draws the IV until it is unique in that table. Cache and cookies share the key but not the check, so a cross-table repeat is not detected. With 96-bit random IVs the collision probability is negligible (birthday bound ~2^-48 after 2^24 rows per key). | No change needed. If row counts ever grow large, rotate the key (requires versioning, see #5). | Documented in `AesGcm.genIV` KDoc. | None |
| 4 | Randomized encryption disabled | Low | `keystore/CacheKeyManager.kt:67` | `setRandomizedEncryptionRequired(false)` lets the caller choose the IV. This is required by the current code because it generates the IV itself; the risk is IV reuse, which is mitigated by #3. | Keep for existing keys (the flag is fixed at key creation and cannot be changed). A future cleanup could let the keystore pick the IV (`cipher.iv` after `init`) for new keys; that still stores a 12-byte IV and stays format-compatible, but it changes `EncryptedCache`/cookie DAO call sites owned by WS03. | Kept and documented in `CacheKeyManager.generateNewKey`. | None |
| 5 | Associated data | Low | `cache/EncryptedCache.kt:47,77` | No AAD, so a ciphertext row is not bound to its URL/body hash: an attacker with write access to the app's database could swap rows between keys. Such an attacker already controls app-private storage. | Only address together with a versioned v2 format (read v1, write v2) and an R1-style upgrade test. | Not changed (would change the on-disk format). | Breaking unless versioned |
| 6 | Key size | Low | `keystore/CacheKeyManager.kt:61-68` | No `setKeySize`, so the key size is whatever the provider defaults to (128 bit on the API 35 emulator, logged by `CryptoCompatTest`). | Set it explicitly. | New keys are generated with `setKeySize(256)`. Existing keys under the alias are reused as-is and never regenerated or rotated. | None: key size is not part of the ciphertext format; `CryptoCompatTest.newKeyIsAes256GcmAndLegacyKeyIsReused` proves a legacy key is reused |
| 7 | `println` key logging | Low | `keystore/CacheKeyManager.kt:36,47,58` | Every key lookup/generation is printed to stdout (logcat `System.out`) in release builds. No key material is printed, but it leaks keystore usage and is noise in host apps. | Remove. | Removed. | None |
| 8 | Keystore API usage | Info | `keystore/CacheKeyManager.kt:32-39,45-51` | The keystore is loaded twice per first call and the key is read via `getEntry(alias, null) as SecretKeyEntry`. Not deprecated, but `getKey(alias, null)` is the direct API for secret keys. | Load once, use `getKey`. | Done. | None (same key) |
| 9 | Deprecated APIs | Info | `keystore/**`, `interceptor/**` | No deprecated platform/JCA APIs in WS06-owned code. `@Throws` on `EncryptionDecryptionInterceptor.encryptRequest` sat above its KDoc, detaching the doc from the function. `lint-baseline.xml` contains no security issues (only dependency/SDK version checks, owned by WS01/WS02). | Fix annotation placement. | Done; `apiCheck` unchanged. | None |
| 10 | Interceptor contract | Info | `interceptor/EncryptionDecryptionInterceptor.kt:6-31`, `interceptor/Interceptor.kt:10-30` | Payload encryption hook was undocumented w.r.t. TLS; `Interceptor` hooks see full URLs, headers and bodies. | Document that payload encryption does not replace TLS/server authentication and that interceptors must not log unredacted data. | KDoc added; no signature change. | None |
| 11 | TLS / custom trust | Medium | `connection/BaseNetwork.kt:159-162`, `connection/queue/RequestQueue.kt:98` | A caller-supplied `SSLSocketFactory` is installed only for HTTPS URLs whose host equals `mSslHost`; every other host uses the platform default trust store and hostname verifier. The library itself never installs a trust-all `TrustManager` or `HostnameVerifier`. However the library cannot validate the supplied factory (a trust-all factory would silently disable certificate checks for that host), and the host match is exact (no subdomains, no port). | Document the contract on the public builder; recommend Network Security Config / certificate pinning over custom factories; consider rejecting a factory whose `TrustManager` accepts everything in debug builds. | **Reported, not fixed** (owner WS04, `connection/**`). | None |
| 12 | TLS config | Info | `config/HttpClientConfig.kt:8-60` | Only connect/read timeouts; no TLS versions, cipher suites or pinning. Timeout `0` means wait forever. Platform defaults (TLS 1.2+/1.3 on minSdk 28) apply, which is appropriate. | No TLS change needed; consider rejecting `0` timeouts. | **Reported, not fixed** (owner WS02, `config/**`). | None |
| 13 | Cleartext | Info | `src/androidTest/AndroidManifest.xml:7` | Tests enable `usesCleartextTraffic` for the localhost mock server. The library manifest does not, so host apps keep the platform default (cleartext blocked from targetSdk 28). | Keep test-only; scope to `localhost` via a test Network Security Config. | Reported (owner WS09). | None |
| 14 | Sensitive logging | Medium | `core/HoodiesNetworkClientNonInlined.kt:683,781,901-902,934-935,967,1012,1042,1070,1096,1110,1117,1181`; `connection/BaseNetwork.kt:53`; `utils/NetworkHelper.kt:30`; `request/query/UrlQueryParamRequest.kt:59`; `request/query/UrlQueryParamEncodedRequest.kt:55` | `Log.d` of URLs, query params, headers (may include `Authorization`/`Cookie`), request bodies and response bodies in release builds. | Remove or gate behind an opt-in debug logger with header/body redaction. | **Reported, not fixed** (owners WS04 `core/**`+`connection/**`, WS02 `utils/**`, WS05 `request/query/**`). | None |
| 15 | Permissions | Low | `src/main/AndroidManifest.xml:6` | `READ_EXTERNAL_STORAGE` is merged into every host app although the library does not need it for its own storage. | Remove or justify. | Reported (owner WS02). | None |
| 16 | Encrypted-data readability: cookie payload | Medium | `cookies/persistentstorage/EncryptedDaoWrapperForCookies.kt:27,43` | The AES layer round-trips correctly, but the plaintext is `Gson().toJson(HttpCookie)` via reflection. On API 35 this serializes only `{"httpOnly":false,"whenCreated":…}` (observed in `CryptoCompatTest` logcat), so name/value are lost and persistent cookies are unusable. This is the root cause of the pre-existing API 35 baseline failure `CookieTests#cookieTestPersistent`. | Serialize an explicit DTO (name, value, domain, path, maxAge, secure, httpOnly, version, whenCreated) and keep reading the legacy reflective JSON written on API ≤ 34. | **Reported, not fixed** (owner WS03, `cookies/**`). `CryptoCompatTest` asserts the library returns exactly what the legacy plaintext deserializes to, so it is independent of this bug. | Any fix must keep reading v1 JSON |
| 17 | Encrypted-data readability: key lifecycle | Info | `keystore/CacheKeyManager.kt:13,57-72` | Alias is constant and the key is never deleted or rotated by the library, so data survives library upgrades. It does not survive app uninstall or keystore wipes (expected for AndroidKeyStore). | Keep alias `HoodiesNetworkCacheKey`; never delete/regenerate an existing key. | Alias unchanged; `CryptoCompatTest` proves legacy-written cache and cookies decrypt with the current library and vice versa. | None |

## Incomplete items (owned by other workstreams)

- **WS03 (after WS03 merges, WS06 shared-file exception):** replace the bodies of `EncryptedCache.runAES`/`genIV`
  with delegation to `crypto/AesGcm` and fix the 16-byte KDoc:
  ```kotlin
  fun runAES(input: ByteArray, iv: ByteArray, cipherMode: Int): ByteArray = AesGcm.runAES(input, iv, cipherMode)
  fun genIV(): ByteArray = AesGcm.genIV()
  ```
  `CryptoCompatTest.cryptoPackageIsInterchangeableWithEncryptedCache` already proves the two are byte-for-byte
  interchangeable. Not done in WS06 because WS03 has not merged yet.
- **WS03:** finding #16 (cookie JSON on API 35).
- **WS04:** findings #11 and #14 (`connection/**`, `core/**`).
- **WS02:** findings #12, #14 (`utils/**`) and #15.
- **WS05:** finding #14 (`request/query/**`).
- **WS09:** finding #13.
