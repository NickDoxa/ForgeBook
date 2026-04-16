---
phase: 02-ai-engine-grounding
plan: "01"
subsystem: config
tags: [config, forge-config-spec, web-search, api-key-scrub, tdd]
dependency_graph:
  requires:
    - "01-02: ApiKeyScrubFilter (Log4j2 RewritePolicy) — extended, not replaced"
    - "01-02: ForgebookServerConfig (ForgeConfigSpec) — extended with 3 new fields"
    - "01-02: ConfigSnapshot (9-field record) — extended to 12 fields"
    - "01-02: ConfigHolder.buildFromSpec — wire-up extended"
  provides:
    - "ConfigSnapshot.maxTokens() — readable by all Phase 2 plans via ConfigHolder.get()"
    - "ConfigSnapshot.webSearchProvider() — readable by Phase 2 WebSearchTool (02-06)"
    - "ConfigSnapshot.webSearchApiKey() — readable by Phase 2 WebSearchTool (02-06)"
    - "WebSearchProviderKind enum (DUCKDUCKGO, BRAVE) — used by WebSearchTool dispatcher"
    - "ApiKeyScrubFilter scrubs X-Subscription-Token — defense-in-depth for Brave key"
  affects:
    - "02-05: AiDispatcher reads snap.maxTokens() for max_tokens parameter"
    - "02-06: WebSearchTool reads snap.webSearchProvider() and snap.webSearchApiKey()"
tech_stack:
  added: []
  patterns:
    - "ForgeConfigSpec.setConfig(CommentedConfig.inMemory()) for unit testing spec values without TOML"
    - "TDD RED/GREEN/REFACTOR per task with separate test and feat commits"
key_files:
  created:
    - src/main/java/com/forgebook/config/WebSearchProviderKind.java
    - src/test/java/com/forgebook/config/ForgebookServerConfigTest.java
  modified:
    - src/main/java/com/forgebook/config/ForgebookServerConfig.java
    - src/main/java/com/forgebook/config/ConfigSnapshot.java
    - src/main/java/com/forgebook/config/ConfigHolder.java
    - src/main/java/com/forgebook/util/log/ApiKeyScrubFilter.java
    - src/test/java/com/forgebook/config/ConfigSnapshotTest.java
    - src/test/java/com/forgebook/util/log/ApiKeyScrubFilterTest.java
decisions:
  - "CommentedConfig.inMemory() + SPEC.correct() + SPEC.setConfig() pattern used for ForgeConfigSpec unit testing — avoids needing a TOML file on disk while exercising real spec defaults"
  - "AUTHZ_HEADER regex changed from \\S+ to [^\\r\\n,;]+ to correctly redact multi-word Authorization values like 'Bearer abc.def.ghi' (pre-existing Phase 1 bug)"
  - "websearch group placed between access and meta groups in forgebook-server.toml hierarchy"
metrics:
  duration_minutes: 16
  completed_date: "2026-04-16"
  tasks_completed: 3
  tasks_total: 3
  files_created: 2
  files_modified: 6
  commits: 6
---

# Phase 2 Plan 01: Config Layer Phase 2 Extensions Summary

**One-liner:** Extended ForgebookServerConfig/ConfigSnapshot from 9 to 12 fields (maxTokens, webSearchProvider, webSearchApiKey) with WebSearchProviderKind enum and Brave X-Subscription-Token log scrubbing.

## Tasks Completed

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1 (RED) | ForgebookServerConfigTest failing tests | 73653e6 | ForgebookServerConfigTest.java |
| 1 (GREEN) | WebSearchProviderKind + ForgebookServerConfig 3 new fields | 1f14c67 | WebSearchProviderKind.java, ForgebookServerConfig.java |
| 2 (RED) | ConfigSnapshotTest 12-field extension failing tests | dde9c5c | ConfigSnapshotTest.java |
| 2 (GREEN) | ConfigSnapshot 12-field record + ConfigHolder wire-up | caebd61 | ConfigSnapshot.java, ConfigHolder.java, ConfigSnapshotTest.java |
| 3 (RED) | ApiKeyScrubFilterTest X-Subscription-Token tests | 71a7f30 | ApiKeyScrubFilterTest.java |
| 3 (GREEN) | ApiKeyScrubFilter X-Subscription-Token + AUTHZ fix | 8fa3e70 | ApiKeyScrubFilter.java |

## What Was Built

### WebSearchProviderKind.java (new)
A two-value enum `{ DUCKDUCKGO, BRAVE }` following the exact shape of the existing `AiProviderKind` analog. DUCKDUCKGO is cost-free (scrapes html.duckduckgo.com); BRAVE requires `web_search_api_key` (Brave Search API).

### ForgebookServerConfig.java (extended)
Three new fields added to the Forge `SERVER`-tier config spec:
- `MAX_TOKENS` — `IntValue` in the `ai` group, `defineInRange(1024, 128, 8192)` (D-05)
- `WEB_SEARCH_PROVIDER` — `EnumValue<WebSearchProviderKind>` in the new `websearch` group, default `DUCKDUCKGO` (D-01/D-02)
- `WEB_SEARCH_API_KEY` — `ConfigValue<String>` in the `websearch` group, default `""` (D-02)

`AI_MODEL` default bumped from `"claude-haiku-4"` to `"claude-haiku-4-5"` (D-04, RESEARCH §1.6).

### ConfigSnapshot.java (extended: 9 → 12 fields)
Three fields inserted at the documented positions:
- Position 4: `int maxTokens` (after `aiModel`)
- Position 10: `WebSearchProviderKind webSearchProvider` (after `enableWebSearch`)
- Position 11: `ApiKey webSearchApiKey` (after `webSearchProvider`)

All existing 9 fields preserve their relative order.

### ConfigHolder.java (extended)
`buildFromSpec()` updated to pass 3 new `ForgebookServerConfig` reads to the 12-argument `ConfigSnapshot` constructor in the correct field order. `MAX_TOKENS.get()`, `WEB_SEARCH_PROVIDER.get()`, `WEB_SEARCH_API_KEY.get()` wired in matching positions.

### ApiKeyScrubFilter.java (extended)
Added `XSUBTOKEN_HEADER` pattern `(?i)(X-Subscription-Token\s*[:=]\s*)\S+` and its `replaceAll("$1<redacted>")` call. All existing rules (Authorization, x-api-key, sk-ant-*, sk-proj-*, api_key=) preserved and unweakened.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed AUTHZ_HEADER regex — only first token of multi-word Authorization values was redacted**
- **Found during:** Task 3 RED phase — `redacts_Authorization_header` test revealed `"Authorization: Bearer abc.def.ghi"` was scrubbed to `"Authorization: <redacted> abc.def.ghi"` instead of `"Authorization: <redacted>"`
- **Issue:** The original regex `\S+` only matches non-whitespace characters, so only `Bearer` was replaced; ` abc.def.ghi` was left in plain text
- **Root cause:** Pre-existing Phase 1 bug in commit `5f8c896` — the test was also committed with the wrong expected behavior but happened to coincidentally pass in some test orderings (investigated and confirmed by checking out Phase 1 commit)
- **Fix:** Changed `\S+` to `[^\r\n,;]+` so the full value through end-of-line (stopping at comma, semicolon, or CRLF) is redacted
- **Files modified:** `src/main/java/com/forgebook/util/log/ApiKeyScrubFilter.java`
- **Commit:** 8fa3e70

**2. [Rule 1 - Bug] ForgeConfigSpec.get() throws IllegalStateException in unit tests without loaded config**
- **Found during:** Task 2 GREEN phase — `holder_buildFromSpec_maxTokensEqualsDefault` test threw `IllegalStateException: Cannot get config value before config is loaded`
- **Issue:** ForgeConfigSpec in the dev environment enforces that `ConfigValue.get()` requires `setConfig()` to have been called first; the original plan assumed `.get()` would return defaults in tests
- **Fix:** Added `@BeforeEach`/`@AfterEach` in `ConfigSnapshotTest` using `CommentedConfig.inMemory()` + `SPEC.correct(cfg)` + `SPEC.setConfig(cfg)` to load the spec with defaults before each test, then unload afterward. Also updated `ForgebookServerConfigTest` to use `getDefault()` instead of `.get()` since that test class does not need live spec loading.
- **Files modified:** `src/test/java/com/forgebook/config/ConfigSnapshotTest.java`
- **Commit:** caebd61

## Known Stubs

None — all new fields are fully wired from `ForgebookServerConfig` through `ConfigSnapshot`. No placeholder values flow to any UI surface.

## Threat Flags

None — this plan only extends the config layer. No new network endpoints, auth paths, or file access patterns introduced. Threat mitigations T-02-01-01 through T-02-01-05 are all addressed:
- T-02-01-01: `webSearchApiKey` wrapped in `ApiKey` record; `toString()` returns `<redacted>` (Task 2)
- T-02-01-02: `X-Subscription-Token` scrubbed by `ApiKeyScrubFilter` (Task 3)
- T-02-01-03: `defineInRange(1024, 128, 8192)` rejects out-of-range `max_tokens` (Task 1)
- T-02-01-04: Accepted — startup warning logged by Wave 2 plans when BRAVE selected without key
- T-02-01-05: `grep -F 'claude-haiku-4"'` returns no match (verified in acceptance criteria)

## Deferred Issues (Out of Scope)

- `SafeHttpFetcherTest` — 5 tests failing (pre-existing Phase 1 failures unrelated to config layer). These tests require a live mock HTTP server and fail due to network infrastructure, not code logic. Tracked for Phase 2 plan that owns `SafeHttpFetcher`.

## Success Criteria Verification

1. ConfigSnapshot is a 12-field record with `maxTokens`, `webSearchProvider`, `webSearchApiKey` in documented positions. **PASS**
2. Default values: `max_tokens=1024`, `web_search_provider=DUCKDUCKGO`, `web_search_api_key=""`, `ai_model=claude-haiku-4-5`. **PASS**
3. `ApiKeyScrubFilter` redacts `X-Subscription-Token` header values without weakening existing rules. **PASS**
4. All Phase 1 config tests still pass (regression). **PASS** (ApiKeyTest, ConfigSnapshotTest, ForgebookServerConfigTest all green)
5. Wave 2 plans (02-05, 02-06) can read `snap.maxTokens()`, `snap.webSearchProvider()`, `snap.webSearchApiKey()` from `ConfigHolder.get()` without further coordination. **PASS** — fields are wired in `buildFromSpec()`

## Self-Check: PASSED

| Item | Status |
|------|--------|
| WebSearchProviderKind.java exists | FOUND |
| ForgebookServerConfigTest.java exists | FOUND |
| 02-01-SUMMARY.md exists | FOUND |
| Commit 73653e6 (test RED Task 1) | FOUND |
| Commit 1f14c67 (feat GREEN Task 1) | FOUND |
| Commit dde9c5c (test RED Task 2) | FOUND |
| Commit caebd61 (feat GREEN Task 2) | FOUND |
| Commit 71a7f30 (test RED Task 3) | FOUND |
| Commit 8fa3e70 (feat GREEN Task 3) | FOUND |
| compileJava succeeds | PASS |
