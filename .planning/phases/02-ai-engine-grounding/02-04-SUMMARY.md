---
phase: 02-ai-engine-grounding
plan: "04"
subsystem: integration
tags: [curseforge, http-client, volatile-cache, tdd, dto, optional-degradation]
dependency_graph:
  requires:
    - "02-01: ConfigSnapshot 12-field record (curseforgeModpackId, curseforgeApiKey accessors)"
    - "01-02: ApiKey record (.raw() on com.forgebook.integration allowlist)"
  provides:
    - "ModpackContext record(name, summary) — DTO for cached modpack metadata"
    - "CurseForgeClient.fetch(ConfigSnapshot) -> Optional<ModpackContext> — CF-01 one-shot fetcher"
    - "ModpackContextCache.get()/set() — volatile holder for Plan 06 ServerStartedEvent writer and Plan 05 GetModpackContextTool reader"
  affects:
    - "02-05: GetModpackContextTool reads ModpackContextCache.get()"
    - "02-06: SystemPromptBuilder reads ModpackContextCache.get() for modpack context enrichment"
    - "02-07: ForgeBookMod ServerStartedEvent listener calls CurseForgeClient.fetch(snap) + ModpackContextCache.set(...)"
tech_stack:
  added: []
  patterns:
    - "Direct java.net.http.HttpClient (NOT SafeHttpFetcher) for trusted fixed-egress API — same exemption as ClaudeProvider"
    - "Gson inner private records for JSON DTO deserialization (unknown fields ignored by default)"
    - "Volatile Optional<T> singleton matching ConfigHolder Phase 1 idiom"
    - "TDD RED/GREEN per task with separate test and feat commits"
key_files:
  created:
    - src/main/java/com/forgebook/integration/ModpackContext.java
    - src/main/java/com/forgebook/integration/CurseForgeClient.java
    - src/main/java/com/forgebook/integration/ModpackContextCache.java
    - src/test/java/com/forgebook/integration/CurseForgeClientTest.java
    - src/test/java/com/forgebook/integration/ModpackContextCacheTest.java
    - src/test/resources/forgebook/phase2/curseforge/atm9-200.json
    - src/test/resources/forgebook/phase2/curseforge/atm9-401.json
    - src/test/resources/forgebook/phase2/curseforge/atm9-malformed.json
  modified: []
decisions:
  - "Direct HttpClient used for api.curseforge.com (trusted fixed egress, same exemption as ClaudeProvider per RESEARCH §'Phase 2 clients of SafeHttpFetcher')"
  - "parseResponse is package-private for unit testability — fetch wraps it in try/catch per CF-02"
  - "Gson private inner records as DTO (unknown fields silently ignored) — no custom adapter needed"
  - "Two .raw() call sites in CurseForgeClient (guard + header) — both in com.forgebook.integration.* allowlist"
metrics:
  duration_minutes: 8
  completed_date: "2026-04-16"
  tasks_completed: 2
  tasks_total: 2
  files_created: 8
  files_modified: 0
  commits: 4
---

# Phase 2 Plan 04: CurseForge Integration Summary

**One-liner:** CurseForge REST API v1 one-shot fetcher (HttpClient + Gson) with volatile ModpackContext cache — CF-01/CF-02/CF-03 fully satisfied at unit-test and code-review levels.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | CurseForgeClientTest failing tests + fixtures | ba4cb0b | CurseForgeClientTest.java, atm9-200.json, atm9-401.json, atm9-malformed.json |
| 1 (GREEN) | ModpackContext + CurseForgeClient implementation | 0c42d1d | ModpackContext.java, CurseForgeClient.java |
| 2 (RED) | ModpackContextCacheTest failing tests | dd3c694 | ModpackContextCacheTest.java |
| 2 (GREEN) | ModpackContextCache volatile holder | cd4c682 | ModpackContextCache.java |

## What Was Built

### ModpackContext.java (new)
A 1-line Java record `public record ModpackContext(String name, String summary) {}`. Immutable DTO for the modpack metadata fetched at server startup. CF-01 / D-18.

### CurseForgeClient.java (new)
Static utility class implementing the CF-01 one-shot fetch pattern:
- `public static Optional<ModpackContext> fetch(ConfigSnapshot snap)` — silent skip (CF-02) when `curseforgeModpackId` is absent or `curseforgeApiKey` is blank; non-200 HTTP status → WARN + `Optional.empty()`; all exceptions → WARN + `Optional.empty()`.
- `static ModpackContext parseResponse(String body)` — package-private for unit testability; uses Gson private inner records (`CurseForgeResponse { ModData data }`, `ModData { int id; String name; String summary }`); defends against null fields; truncates summary to 500 chars (SUMMARY_CAP, T-02-04-06).
- Uses `java.net.http.HttpClient` directly with `x-api-key` + `Accept: application/json` headers and `Duration.ofSeconds(15)` timeout.
- Threat mitigations: T-02-04-01 (no key value in logs), T-02-04-02 (15s timeout), T-02-04-03 (fixed URI prefix), T-02-04-06 (500-char summary cap).

### ModpackContextCache.java (new)
Exact PATTERNS skeleton — `public final class` with `private static volatile Optional<ModpackContext> current = Optional.empty()`, `private` no-arg constructor, `get()` and `set()` static methods. Mirrors `ConfigHolder` from Phase 1. CF-03 enforced architecturally: only written by the ServerStartedEvent listener (Plan 07) and `/forgebook reload` command.

### Test Fixtures (new)
- `atm9-200.json` — ATM9 modpack 200 OK response (id=520914, name="All the Mods 9")
- `atm9-401.json` — CurseForge Unauthorized error envelope `{"error":"Unauthorized"}`
- `atm9-malformed.json` — missing `data` wrapper `{"name":"orphan"}` for exception-path coverage

## Requirements Wiring

| Requirement | Status | How Satisfied |
|-------------|--------|---------------|
| CF-01 | Satisfied (code + unit-test level) | `CurseForgeClient.fetch` calls `GET /v1/mods/{id}` with `x-api-key` header at ServerStartedEvent (wired in Plan 07) |
| CF-02 | Satisfied (unit-test level for parse + config-gating; code-review level for HTTP-error branches) | Missing config → silent return; non-200 → WARN + empty; any exception → WARN + empty |
| CF-03 | Satisfied (architectural) | HTTP call only in `CurseForgeClient.fetch` — grep confirms no other `api.curseforge.com` reference in production code |

**Note for Plan 07 (ForgeBookMod integration):** The wiring points are:
```java
// ServerStartedEvent listener (off-tick via AiExecutor):
Optional<ModpackContext> ctx = CurseForgeClient.fetch(ConfigHolder.get());
ModpackContextCache.set(ctx);
```

## Test Results

| Test Class | Tests | Pass | Fail |
|------------|-------|------|------|
| CurseForgeClientTest | 6 | 6 | 0 |
| ModpackContextCacheTest | 4 | 4 | 0 |
| **Total** | **10** | **10** | **0** |

## Deviations from Plan

None — plan executed exactly as written. Both production classes match the PATTERNS skeletons verbatim. Test helpers (loadFixture, snapshot) follow the plan's specified signatures.

## Known Stubs

None — all classes are fully implemented. `ModpackContextCache` holds `Optional.empty()` at startup (correct default, not a stub); it will be populated by Plan 07's `ServerStartedEvent` listener.

## Threat Flags

No new threat surface beyond the plan's `<threat_model>`. All 7 STRIDE threats addressed:
- T-02-04-01: `LOG.warn` never logs `curseforgeApiKey().raw()` — only modpackId and statusCode
- T-02-04-02: `Duration.ofSeconds(15)` timeout on `HttpRequest`
- T-02-04-03: Fixed `ENDPOINT` prefix — even adversarial modpackId can only produce 4xx
- T-02-04-04: Accepted — TLS certificate validation via JDK defaults
- T-02-04-05: Accepted — WARN log on failure is sufficient operator visibility
- T-02-04-06: `SUMMARY_CAP = 500` truncation in `parseResponse`
- T-02-04-07: `catch (Exception e)` logs the exception without logging request headers

## Self-Check: PASSED

| Item | Status |
|------|--------|
| ModpackContext.java exists | FOUND |
| CurseForgeClient.java exists | FOUND |
| ModpackContextCache.java exists | FOUND |
| CurseForgeClientTest.java exists | FOUND |
| ModpackContextCacheTest.java exists | FOUND |
| atm9-200.json fixture exists | FOUND |
| atm9-401.json fixture exists | FOUND |
| atm9-malformed.json fixture exists | FOUND |
| Commit ba4cb0b (test RED Task 1) | FOUND |
| Commit 0c42d1d (feat GREEN Task 1) | FOUND |
| Commit dd3c694 (test RED Task 2) | FOUND |
| Commit cd4c682 (feat GREEN Task 2) | FOUND |
| compileJava succeeds | PASS |
| 10 integration tests pass | PASS |
| No SafeHttpFetcher in CurseForgeClient code | PASS (Javadoc-only reference) |
| api.curseforge.com single prod call site | PASS |
| volatile field in ModpackContextCache | PASS |
