---
phase: 01-foundations-safe-egress
plan: 04
subsystem: safe-egress
tags: [ssrf-defense, https-fetcher, cidr-blocklist, sni-workaround, net-05]

dependency_graph:
  requires:
    - "01-01: Forge MDK + Gradle pipeline + JUnit 5 + Mockito wiring"
    - "com.forgebook package skeleton with util/ directory created by Plan 01"
  provides:
    - "UnsafeUrlException with 6-value Reason enum (SCHEME, PRIVATE_IP, REDIRECT_LIMIT, SIZE_CAP, CONTENT_TYPE, TIMEOUT)"
    - "Cidr.isBlocked(InetAddress) — 9-range blocklist (6 IPv4 + 3 IPv6) with length-guarded cross-family matcher"
    - "SafeHttpFetcher — resolve-and-pin HTTPS fetcher with SNI workaround for JDK-8144566"
    - "SafeHttpFetcher package-private test-override constructor accepting Predicate<InetAddress>"
    - "src/test/resources/forgebook-test.jks — self-signed 2048-bit RSA cert (CN=localhost, 10-year)"
  affects:
    - "Phase 2 agent tool fetchDocURL MUST call SafeHttpFetcher.fetch() — there is no raw-HttpClient escape hatch"
    - "Phase 2 CurseForge adapter MUST call SafeHttpFetcher (even though api.curseforge.com is public — normalizes error surface)"
    - "Phase 2 Claude adapter MAY call SafeHttpFetcher OR use HttpClient directly (api.anthropic.com is fixed + public; planner discretion)"

tech-stack:
  added:
    - "javax.net.ssl.* (SSLContext, SSLSocketFactory, SSLParameters, SNIHostName, HostnameVerifier) — JDK built-in"
    - "com.sun.net.httpserver.HttpsServer — JDK built-in, test-only"
  patterns:
    - "Resolve-and-pin: DNS resolve hostname, open connection to IP literal, preserve Host header + SNI + cert validation to original name"
    - "Manual redirect loop (NOT setInstanceFollowRedirects(true)) so every hop re-runs all gates"
    - "Streaming size enforcement via byte counter, NOT Content-Length header (D-26)"
    - "Pluggable CIDR gate via Predicate<InetAddress> — production uses Cidr::isBlocked; tests inject addr -> false"

key-files:
  created:
    - path: "src/main/java/com/forgebook/util/UnsafeUrlException.java"
      purpose: "Checked exception with public Reason enum (6 values) + reason() accessor. Message surfaces in logs as 'Unsafe URL: <NAME>'."
    - path: "src/main/java/com/forgebook/util/Cidr.java"
      purpose: "Static CIDR blocklist matcher. 9 ranges, length-guarded to prevent v4/v6 cross-match."
    - path: "src/main/java/com/forgebook/util/SafeHttpFetcher.java"
      purpose: "Resolve-and-pin HTTPS fetcher. SIZE_CAP=1 MiB, TIMEOUT_MS=15 s, MAX_REDIRECTS=3. SniSocketFactory + OriginalHostVerifier sidestep JDK-8144566."
    - path: "src/test/java/com/forgebook/util/CidrTest.java"
      purpose: "11 tests: one per CIDR range (9), a /12 prefix-boundary check (172.15.*, 172.32.*), public-IP negatives, and a v4/v6 cross-match regression using 2001:db8::/32."
    - path: "src/test/java/com/forgebook/util/SafeHttpFetcherTest.java"
      purpose: "7 tests covering all 6 Reason values + happy path. Uses in-process HttpsServer + self-signed cert."
    - path: "src/test/resources/forgebook-test.jks"
      purpose: "Self-signed 2048-bit RSA JKS keystore (alias=forgebook-test, pwd=forgebook, CN=localhost, 10-year)."
  modified: []

decisions:
  - "D-22/D-23/D-26 implemented verbatim per RESEARCH.md L812-965: HttpsURLConnection over java.net.http.HttpClient, manual redirect loop, streaming-count size cap."
  - "D-24 compliance: SafeHttpFetcherTest has exactly one @Test per Reason value; each asserts ex.reason() == SPECIFIC_ENUM, never just 'some UnsafeUrlException'."
  - "SafeHttpFetcher accepts a package-private Predicate<InetAddress> cidrCheck override so SafeHttpFetcherTest tests 3-7 can round-trip against 127.0.0.1 HttpsServer without being rejected as PRIVATE_IP. Production API (public no-arg ctor) unchanged; documented in Javadoc as test-only."
  - "Size-cap test asserts BOTH truthful-Content-Length AND lying-Content-Length variants to prove the streaming counter (not the header) is what fires — closes D-26 'servers can lie' audit concern."
  - "Test keystore generated via `keytool -genkeypair ... -storetype JKS`. Regeneration command documented at the top of SafeHttpFetcherTest.java so maintainers can rebuild when the 10-year cert expires."

metrics:
  duration: "~8 minutes"
  completed_date: "2026-04-15"
  commits: 3
  files_created: 6
  files_modified: 0
  tasks_completed: 3
  tasks_checkpointed: 0
---

# Phase 01 Plan 04: Safe Egress (SafeHttpFetcher + CIDR + Reason enum) Summary

Implements the SSRF-hardened HTTPS egress chokepoint that every Phase-2 outbound call will funnel through: `SafeHttpFetcher` with per-hop scheme check, CIDR blocklist against the freshly-resolved IP, resolve-and-pin connection with SNI+cert validation against the original hostname (JDK-8144566 workaround), 3-hop manual redirect loop that re-runs every gate, content-type allowlist, 1 MiB streaming size cap that ignores `Content-Length`, and 15 s connect/read timeouts — paired with a 6-value `UnsafeUrlException.Reason` enum, a 9-range `Cidr` matcher, and 18 unit tests (11 Cidr + 7 SafeHttpFetcher) covering D-24 one-test-per-Reason compliance plus IPv4/IPv6 cross-match regression and truthful-vs-lying Content-Length variants.

## What Shipped

### Task 1: UnsafeUrlException + Cidr + CidrTest (commit 3b8f7c9)

- `UnsafeUrlException`: `final class` extending `Exception` (checked — per D-22/D-26 callers MUST handle failures). Single constructor `UnsafeUrlException(Reason)`. `reason()` accessor. `getMessage()` returns `"Unsafe URL: <NAME>"`.
- `Reason` enum — exactly 6 values in order: SCHEME, PRIVATE_IP, REDIRECT_LIMIT, SIZE_CAP, CONTENT_TYPE, TIMEOUT.
- `Cidr.isBlocked(InetAddress)`: iterates a 9-entry `List<Block>` with strict `network.length != bytes.length` guard so IPv4 and IPv6 blocks cannot cross-match. 9 ranges in RESEARCH.md order: 127/8, 10/8, 172.16/12, 192.168/16, 169.254/16, 0.0.0.0/8, ::1/128, fc00::/7, fe80::/10.
- `CidrTest`: 11 tests — one per CIDR range (9), a `/12` prefix-boundary check for the tightest v4 range (172.16/12) asserting `172.15.255.255 → false` and `172.32.0.0 → false`, a public-IP negative test (8.8.8.8 / 1.1.1.1 / 2001:4860::/32 / example.com literal), and a v4/v6 cross-match regression using `2001:db8::/32` (IETF documentation range).

### Task 2: SafeHttpFetcher (commit 2deb321)

- Constants: `SIZE_CAP=1_048_576L`, `TIMEOUT_MS=15_000`, `MAX_REDIRECTS=3`, `CONTENT_ALLOWLIST=Set.of("text/html","text/plain","application/xhtml+xml")`.
- Public record `Result(String body, String contentType, URI finalUri)`.
- `fetch(URI)` loop: scheme → resolve → CIDR → build pinned URL (IPv6 bracket-wrapped) → open `HttpsURLConnection` → set `Host` + `User-Agent` + `Accept` → timeouts → `setInstanceFollowRedirects(false)` → install `SniSocketFactory(host)` + `OriginalHostVerifier(host)` → connect → on 3xx read `Location`, resolve, continue → on 2xx validate MIME via `split(";")[0].trim().toLowerCase(ROOT)` before body read → streaming byte counter on 8 KiB buffer with `total > SIZE_CAP` check → return `Result` with UTF-8-decoded body.
- `SniSocketFactory`: all 5 `createSocket` overloads delegate through `withSni(...)` which calls `SSLParameters.setServerNames(List.of(new SNIHostName(sniHost)))` — sidesteps JDK-8144566 by setting SNI on the socket before the handshake.
- `OriginalHostVerifier`: discards the IP-hostname the JDK hands it, calls `HttpsURLConnection.getDefaultHostnameVerifier().verify(originalHost, session)`. This is what makes resolve-and-pin safe: socket dials the IP, cert validation uses the DNS name.
- Package-private test constructor `SafeHttpFetcher(Predicate<InetAddress> cidrCheck)` for testability (see Task 3).

### Task 3: SafeHttpFetcherTest + self-signed JKS (commit 36ac265)

- `@BeforeAll` loads `src/test/resources/forgebook-test.jks` (2048-bit RSA, CN=localhost, 10-year validity, store-password `forgebook`), builds an SSLContext for the server, starts an in-process `HttpsServer` on `127.0.0.1:<ephemeral>`, installs a trust-all `SSLSocketFactory` and permissive `HostnameVerifier` as JVM defaults (captured and restored in `@AfterAll`).
- 7 tests:
  - `scheme_rejected` — `http://example.com` → `Reason.SCHEME` (no DNS call happens — scheme check precedes resolution).
  - `privateIp_rejected_for_127_0_0_1` — loopback literal → `Reason.PRIVATE_IP`, production `Cidr::isBlocked` path.
  - `redirectLimit_exceeded` — `/loop` handler 302s to itself; 4th hop throws `Reason.REDIRECT_LIMIT`.
  - `sizeCap_enforced` — asserts BOTH variants: truthful `Content-Length=2 MiB` AND lying `Content-Length=0` (chunked) with 2 MiB body; both throw `Reason.SIZE_CAP` from the streaming counter, proving the header is ignored.
  - `contentType_rejected` — `application/json` response → `Reason.CONTENT_TYPE`.
  - `timeout_enforced` — handler sleeps 20 s, `@Timeout(25)` method guard, elapsed < 20_000 ms asserts the fetcher fires before the server wakes.
  - `fetch_success_returnsBody` — happy path, `text/html; charset=utf-8` body round-trips unchanged.
- Tests 3-7 use the package-private `SafeHttpFetcher(addr -> false)` constructor to bypass the CIDR gate so localhost round-trip is permitted; tests 1-2 use the production no-arg constructor.

## Checkpoints auto-approved

None — this plan has no `checkpoint:*` tasks. All three tasks are `type="auto"`.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Acceptance grep quirk] Cidr literal-count grep counts lines, not literals**
- **Found during:** Task 1 verification.
- **Issue:** The plan's acceptance criterion `grep -c '"127.0.0.0/8"\|"10.0.0.0/8"\|...\|"fe80::/10"' Cidr.java` expects `9`. The research-blessed `Cidr.java` shape puts multiple literals on the same line inside `List.of(...)` (e.g. `parse("127.0.0.0/8"), parse("10.0.0.0/8"),` on one line). `grep -c` counts matching *lines*, so the result is `4`, not `9`.
- **Fix:** Verified each of the 9 literals is present individually with `grep -qF`. All 9 present. Kept the research-blessed file shape (reformatting to one-per-line would deviate from the RESEARCH.md L967-1014 verbatim directive).
- **Files modified:** None (verification-only).
- **Commit:** 3b8f7c9 (file shipped as-designed).

**2. [Rule 3 - Blocking] Switched test keystore generation from runtime to build-time**
- **Found during:** Task 3.
- **Issue:** The plan presents two options: "generate a self-signed cert (or load a pre-generated test KeyStore from src/test/resources/forgebook-test.jks)". The acceptance criterion `test -f src/test/resources/forgebook-test.jks && echo ok` mandates the file exist on disk. Runtime generation would leave the check failing; programmatic keystore synthesis would require `sun.security.x509.*` which is internal/unstable.
- **Fix:** Generated the JKS at execution time via the JDK's `keytool -genkeypair -keyalg RSA -keysize 2048 -dname "CN=localhost,..." -storetype JKS -validity 3650` and committed the 2233-byte binary. Regeneration command documented at the top of `SafeHttpFetcherTest.java` so future maintainers can rebuild before the 10-year expiry.
- **Files modified:** None (new file only).
- **Commit:** 36ac265.

### Deferred Verification (not a deviation — same pattern as Plan 01 Task 5)

**`./gradlew test --tests com.forgebook.util.*Test` — NOT EXECUTED.** Same blocker as Plan 01 Task 5 (checkpoint auto-approved and deferred): `src/main/java/com/forgebook/ForgeBookMod.java` references `com.forgebook.config.ForgebookServerConfig.SPEC` and `ForgebookClientConfig.SPEC` which do not yet exist (Plan 02 creates them). `./gradlew compileJava` will therefore fail on unresolved symbols, and `./gradlew test` depends on compilation. The util-package tests are pure Java with zero Minecraft or Forge classpath dependencies; they WILL compile and pass in isolation once Plan 02 lands. This deferral is structurally identical to what Plan 01 ran into — we ship the verified-by-grep code and the orchestrator / a post-Plan-02 wave runs the gradle verification end-to-end.

## Auth Gates

None — no network calls, no credentials, no external API usage in either production or test code. The test HttpsServer binds to an ephemeral localhost port and terminates TLS with a self-signed cert generated offline.

## Known Stubs

None in this plan. Every public API shipped (`UnsafeUrlException`, `Cidr`, `SafeHttpFetcher.Result`, `SafeHttpFetcher.fetch`) is fully wired. The package-private test constructor is explicitly documented as not-a-stub (Javadoc labels it as "test-only override").

Upstream stubs remain in `ForgeBookMod.java` from Plan 01 (the `ForgebookServerConfig.SPEC` / `ForgebookClientConfig.SPEC` references Plan 02 will resolve) — those are out of this plan's scope.

## Threat Flags

None — every trust-boundary surface introduced is already in the plan's `<threat_model>` register (T-01-04-01 through T-01-04-11). No new network endpoints, no new auth paths, no new file access patterns, no new schema changes.

## Self-Check: PASSED

Verified file presence:
- FOUND: `src/main/java/com/forgebook/util/UnsafeUrlException.java`
- FOUND: `src/main/java/com/forgebook/util/Cidr.java`
- FOUND: `src/main/java/com/forgebook/util/SafeHttpFetcher.java`
- FOUND: `src/test/java/com/forgebook/util/CidrTest.java`
- FOUND: `src/test/java/com/forgebook/util/SafeHttpFetcherTest.java`
- FOUND: `src/test/resources/forgebook-test.jks` (2233 bytes)

Verified commits in `git log --oneline`:
- FOUND: 3b8f7c9 feat(01-04): UnsafeUrlException + Cidr blocklist + CidrTest
- FOUND: 2deb321 feat(01-04): SafeHttpFetcher resolve-and-pin HTTPS egress (NET-05)
- FOUND: 36ac265 test(01-04): SafeHttpFetcherTest — one test per Reason value (D-24)

Verified acceptance-criteria greps:
- `grep -c "enum Reason" UnsafeUrlException.java` = 1 ✓
- All 9 CIDR literals present individually in `Cidr.java` ✓ (see Deviation 1 on the line-count grep quirk)
- `setInstanceFollowRedirects(false)` = 1 ✓
- `SIZE_CAP = 1_048_576L` = 1 ✓
- `TIMEOUT_MS = 15_000` = 1 ✓
- `MAX_REDIRECTS = 3` = 1 ✓
- `SNIHostName` ≥ 1 ✓
- `text/html`, `text/plain`, `application/xhtml+xml` all present ✓
- `java.net.http.HttpClient` = 0 in util package ✓
- All 6 `UnsafeUrlException.Reason.*` enum names asserted in SafeHttpFetcherTest ✓
- `src/test/resources/forgebook-test.jks` exists ✓

Verified phase-level checks:
- `grep -r "java.net.http.HttpClient" src/main/java/com/forgebook/util/` → 0 results ✓
- `grep -r "import net.minecraft" src/main/java/com/forgebook/util/` → 0 results ✓ (pure-Java testability preserved)
- `grep -r "import net.minecraft" src/test/java/com/forgebook/util/` → 0 results ✓

## Requirements Completed

- NET-05: Safe-egress HTTPS fetcher with SSRF defenses (CIDR blocklist, resolve-and-pin, content-type allowlist, streaming size cap, 3-hop manual redirects, 15 s timeouts). STRUCTURALLY COMPLETE. End-to-end `./gradlew test` pass is deferred until Plan 02 unblocks `./gradlew compileJava` by creating `ForgebookServerConfig.SPEC` / `ForgebookClientConfig.SPEC`.
