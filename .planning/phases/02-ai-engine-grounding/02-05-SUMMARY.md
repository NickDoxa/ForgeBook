---
phase: 02-ai-engine-grounding
plan: "05"
subsystem: tool-surface
tags: [tools, scraper, web-search, tool-registry, tdd, duckduckgo, brave, anti-injection]
dependency_graph:
  requires:
    - "02-01: ConfigSnapshot.webSearchProvider()/webSearchApiKey() + WebSearchProviderKind enum"
    - "02-02: Tool interface, ToolResult record, ToolException + Reason enum"
    - "02-02: Anthropic DTO set (used indirectly via AgentLoop downstream)"
    - "02-04: ModpackContextCache.get() for GetModpackContextTool"
    - "01-04: SafeHttpFetcher for SSRF-safe HTTP egress in scraper + adapters"
  provides:
    - "ModDocsScraper — host-specific HTML → markdown digest (CurseForge, Fandom, Read-the-Docs, generic body fallback)"
    - "PromptFraming — anti-prompt-injection banner wrapper for untrusted web text"
    - "SearchResult record(title, url, snippet) — provider-neutral search hit DTO"
    - "WebSearchAdapter — functional interface `List<SearchResult> search(String query, int limit)`"
    - "DuckDuckGoHtmlAdapter — html.duckduckgo.com scraper (no API key)"
    - "BraveSearchAdapter — api.search.brave.com JSON (X-Subscription-Token header)"
    - "ListInstalledModsTool — enumerates ModList.get() mod IDs + displayNames"
    - "FetchModDocsPageTool — SafeHttpFetcher + ModDocsScraper + PromptFraming pipeline"
    - "WebSearchTool — dispatches to configured WebSearchAdapter"
    - "GetModpackContextTool — reads ModpackContextCache (returns '(no modpack context)' when empty)"
    - "ToolRegistry — name → Tool static map + register()/get()/snapshot(), populated by Plan 07 ServerStartedEvent"
  affects:
    - "02-06: AgentLoop resolves tools through ToolRegistry.get(name)"
    - "02-07: AiDispatcher startup wiring registers all 4 tools"
tests_added: 48
tests_total_after: 163
status: complete
---

# Summary — Plan 02-05: Tools + Scraper + Web-Search Adapters

## What was built

Wired four Claude-facing tools through a central `ToolRegistry`, plus the shared infrastructure they depend on (scraper, prompt-framing, two web-search adapters). All implementations are stateless; state lives in Wave 2 caches (`ModpackContextCache`) and Phase 1 utilities (`SafeHttpFetcher`, `ApiKey`).

### Task 1 — Scraper + prompt framing
- `ModDocsScraper` classifies an HTML response by host (`curseforge.com`, `fandom.com`/`*.fandom.com`, `readthedocs.io`, `*.readthedocs.io`, otherwise generic body) and returns a markdown digest with noise (nav, ads, sidebars) stripped.
- `PromptFraming` wraps any externally-sourced text in an anti-injection banner so the LLM treats it as data, not instructions.

### Task 2 — Web-search primitives + adapters
- `SearchResult` record (`title`, `url`, `snippet`) — provider-neutral.
- `WebSearchAdapter` `@FunctionalInterface` — single method for impls + test doubles.
- `DuckDuckGoHtmlAdapter` — scrapes `html.duckduckgo.com/html/`, no API key required (default provider when `WebSearchProviderKind.DDG`).
- `BraveSearchAdapter` — calls `api.search.brave.com/res/v1/web/search`, auth via `X-Subscription-Token` (covered by `ApiKeyScrubFilter` extension from Plan 01).

### Task 3 — Tool implementations + `ToolRegistry`
- `ListInstalledModsTool` — iterates `ModList.get().getMods()`, emits `mod_id + displayName` pairs (AI-06).
- `FetchModDocsPageTool` — pipeline: `SafeHttpFetcher.fetch(url)` → `ModDocsScraper.scrape(html, host)` → `PromptFraming.wrap(digest)`. Handles missing-docs fallback (TOOL-07) by returning a typed `ToolResult` with `isError=false` and an explanatory message.
- `WebSearchTool` — resolves `WebSearchAdapter` from `ConfigSnapshot.webSearchProvider()`, dispatches query.
- `GetModpackContextTool` — reads `ModpackContextCache.get()`, returns the cached `ModpackContext` or `"(no modpack context)"` string when Plan 04's fetch returned empty (CF-03).
- `ToolRegistry` — static `ConcurrentHashMap<String, Tool>` with `register(name, tool)`, `get(name)`, `snapshot()`. Populated by Plan 07's `ServerStartedEvent` listener.

## Tests

48 new tests across 10 suites:
- `ModDocsScraperTest` — per-host golden-fixture HTML → expected markdown
- `PromptFramingTest` — banner wrap, empty string, round-trip stability
- `DuckDuckGoHtmlAdapterTest` — canned HTML fixture parsing, empty-result handling
- `BraveSearchAdapterTest` — canned JSON fixture parsing, 401 error path
- `ListInstalledModsToolTest` — ModList mocked, returns expected pairs
- `FetchModDocsPageToolTest` — SafeHttpFetcher + scraper pipeline, TOOL-07 fallback
- `WebSearchToolTest` — adapter dispatch via ConfigSnapshot
- `GetModpackContextToolTest` — populated + empty cache paths
- `ToolRegistryTest` — register/get/snapshot, duplicate register throws
- Fixtures: `curseforge-page.html`, `fandom-page.html`, `rtd-page.html`, `minimal-body.html`, `ddg-results.html`, `brave-results.json`

All 48 new tests pass. Total project test count: 163 (5 pre-existing `SafeHttpFetcherTest` SSL failures from Phase 1 unrelated to this plan).

## Commits
- `d5f5ad1` test(02-05): add failing tests for ModDocsScraper + PromptFraming (Task 1 RED)
- `0ead346` feat(02-05): implement ModDocsScraper + PromptFraming (Task 1 GREEN)
- `d402a16` test(02-05): add failing tests for web-search adapters (Task 2 RED)
- `1706364` feat(02-05): implement SearchResult + WebSearchAdapter + DDG + Brave adapters (Task 2 GREEN)
- `f059713` test(02-05): add failing tests for four tool impls + ToolRegistry (Task 3 RED)
- `7282ff3` feat(02-05): implement four tool impls + ToolRegistry (Task 3 GREEN)

## Deviations
None — all three tasks executed as planned. SUMMARY.md was written by the orchestrator because the executor agent was interrupted by a usage-limit cutoff after the Task 3 GREEN commit; all implementation and test work was complete at that point.

## Requirements satisfied
AI-06 (installed mods tool), TOOL-01 through TOOL-07 (tool surface + anti-injection + missing-docs fallback), CF-03 (tool reads cache, empty-cache graceful path).

## Key files
- `src/main/java/com/forgebook/tool/ToolRegistry.java`
- `src/main/java/com/forgebook/tool/impl/{ListInstalledModsTool,FetchModDocsPageTool,WebSearchTool,GetModpackContextTool}.java`
- `src/main/java/com/forgebook/integration/scraper/{ModDocsScraper,PromptFraming}.java`
- `src/main/java/com/forgebook/integration/websearch/{SearchResult,WebSearchAdapter,DuckDuckGoHtmlAdapter,BraveSearchAdapter}.java`

## Self-Check: PASSED
All 3 tasks committed, all 48 new tests pass, no new regressions. Ready for Wave 4 (`AgentLoop` consumes `ToolRegistry.get(name)`).
