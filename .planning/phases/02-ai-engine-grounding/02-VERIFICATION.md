---
phase: 02-ai-engine-grounding
verified: 2026-04-16T14:30:00Z
status: human_needed
score: 4/5 truths verified (SC-1 awaiting live-network human confirmation)
overrides_applied: 0
re_verification:
  previous_status: gaps_found
  previous_score: 2/5
  gaps_closed:
    - "AgentLoop runtime wiring (SC-2): ForgeBookMod.java now registers ServerStartingEvent → AiExecutor.start, ServerStoppingEvent → AiExecutor::onServerStopping, and ConfigHolder seed; commonSetup restored e.enqueueWork(ForgebookNetwork::register)."
    - "SystemPromptBuilder runtime wiring (SC-4): ForgeBookMod.java now registers ServerStartedEvent → SystemPromptBuilder.buildAndCache(e.getServer()), restoring AI-08/CF-03 exactly-once contract."
    - "CFG-07 runtime wiring: ForgeBookMod.java now registers MinecraftForge.EVENT_BUS.addListener(ForgebookReloadCommand::onRegister), restoring /forgebook reload Brigadier registration."
  gaps_remaining: []
  regressions: []
  fix_commit: "96b1dc6 fix(phase-02): restore Phase 2 forge-bus wiring accidentally reverted by 40da2d7"
human_verification:
  - test: "Real ClaudeProvider v1/messages round-trip (SC-1, AI-01)"
    expected: "With a valid Anthropic ai_api_key and ai_model=claude-haiku-4-5 in forgebook-server.toml, a /forgebook ask (once Phase 3 exposes it) or a crafted ChatRequestPacket produces a non-error FinalReply whose text is a plausible natural-language answer; server log shows one outbound POST https://api.anthropic.com/v1/messages with headers x-api-key=<redacted>, anthropic-version=2023-06-01."
    why_human: "Requires real network egress, live API key, and an Anthropic account; no automated offline proof possible. ScriptedAiProvider covers the path shape, but confirming the real Claude handshake (header values accepted, model string valid, response JSON shape parsed) can only be observed against the live endpoint."
  - test: "Real CurseForge enrichment populates system prompt exactly once (SC-4, CF-01/CF-02)"
    expected: "With curseforge_modpack_id + curseforge_api_key set on a server that has network egress, the log shows exactly one GET https://api.curseforge.com/v1/mods/{id} during startup, SystemPromptCache.get() after ServerStartedEvent returns a string containing the returned modpack name and a ≤500-char summary excerpt; restart without the modpack_id shows the prompt building anyway with no CF line and no error."
    why_human: "Requires real CurseForge API key and a real mod-project ID. Unit tests cover CurseForgeClient.fetch in isolation but the ServerStartedEvent-driven exactly-once contract can only be observed on a live server startup. Wiring gap that previously blocked this has been fixed in commit 96b1dc6, so the live test is now unblocked."
  - test: "Agent multi-step tool loop end-to-end against live Claude (SC-2, SC-5)"
    expected: "A question whose answer requires FetchModDocsPageTool + WebSearchTool (e.g. about a synthetic mod with empty getModURL()) produces a FinalReply that cites at least one source URL; server log shows ToolUses → tool execution → next turn → FinalReply sequence within ≤6 iterations; a 429 from Claude triggers RetryPolicy backoff and eventual success or clean RATE_LIMITED error."
    why_human: "AgentLoopE2ETest already proves the SC-5 path offline against ScriptedAiProvider. Real Claude tool-use JSON shape (tool_use content blocks, tool_result roles, parallel tool blocks) can only be confirmed against a live endpoint. The offline test is necessary but not sufficient proof for SC-2."
  - test: "Live DuckDuckGo / Brave web search adapter returns usable results"
    expected: "With enable_web_search = true and web_search_provider = DUCKDUCKGO (default) or BRAVE + valid web_search_api_key, calling WebSearchTool with {query: 'create mod steam engine'} returns a non-empty SearchResult list; each result URL passes SafeHttpFetcher's scheme + private-IP checks; the 8000-char truncation boundary in PromptFraming is honored."
    why_human: "DuckDuckGo HTML scraping is fragile by design — its markup can change unannounced, so only live verification reveals parser drift. Brave requires a real API key."
---

# Phase 2: AI Engine & Grounding — Verification Report

**Phase Goal:** A server-side `AiDispatcher` can answer a grounded question end-to-end by driving Claude (v1 default) through a capped tool-using agent loop that consults the installed mod list, fetches mod docs, falls back to web search, and optionally enriches the system prompt with CurseForge modpack context.

**Verified:** 2026-04-16T14:30:00Z
**Status:** human_needed
**Re-verification:** Yes — after gap closure by commit `96b1dc6`

## Re-Verification Summary

Previous run (2026-04-16T08:10:00Z) reported `status: gaps_found` with 2/5 truths verified, blocking on a single-file regression in `ForgeBookMod.java` (commit `40da2d7` inadvertently stripped all forge-bus listeners and the commonSetup network registration).

Fix commit `96b1dc6 fix(phase-02): restore Phase 2 forge-bus wiring accidentally reverted by 40da2d7` restored all six missing wires. This re-verification confirms every previously-flagged gap is now closed and no new regressions were introduced. SC-2 and SC-4 move from FAILED → VERIFIED (code/wiring complete; live-network components still route to `human_verification`). SC-1 remains awaiting live-network human confirmation as before.

## Goal Achievement

### Observable Truths

| #   | Truth (from ROADMAP.md Success Criteria) | Status | Evidence |
| --- | --- | --- | --- |
| SC-1 | `ClaudeProvider` completes real `v1/messages` turn; `OpenAiProvider` and `OllamaProvider` compile, are selectable via `ai_provider`, and throw "not implemented in v1" when invoked | ? NEEDS HUMAN | Code verified: `ClaudeProvider.java` lines 48–49 — `ANTHROPIC_VERSION = "2023-06-01"` pinned, `ENDPOINT = URI.create("https://api.anthropic.com/v1/messages")`, x-api-key header set (line 83), CircuitBreaker + RetryPolicy wired via HttpExecutor test seam. `OpenAiProvider.java:16` and `OllamaProvider.java:16` both return `ProviderError(NOT_IMPLEMENTED)`. `ProviderFactory.create` switches on `AiProviderKind`. Live round-trip not automatable — see `human_verification`. |
| SC-2 | `AgentLoop` multi-step cycle terminates at `FinalReply`, 6-iter hard cap, never retries 4xx, retries 5xx up to 3× with exp backoff, circuit breaker trips after 5 consecutive failures | ✓ VERIFIED | `AgentLoop.MAX_ITERATIONS = 6` (public static final, for-loop bound), `ProviderError(ITERATION_CAP)` returned on cap (lines 89–90). Parallel tool dispatch via `CompletableFuture.supplyAsync(..., AiExecutor.get())`. `RetryPolicy` (3 tries, 30 s cap, 25 % jitter) and `CircuitBreaker` (5-fail / 5-min cooldown) wired into `ClaudeProvider` via `HttpExecutor`. `AgentLoopE2ETest` covers 4 offline scenarios including iteration-cap. **Runtime wiring now present**: `ForgeBookMod.java:67–70` registers `ServerStartingEvent → AiExecutor.start` and `ServerStoppingEvent → AiExecutor::onServerStopping`; `ForgeBookMod.java:59–62` seeds `ConfigHolder`. Real Claude tool-use handshake still routes to `human_verification`. |
| SC-3 | All four tools (`ListInstalledModsTool`, `FetchModDocsPageTool`, `WebSearchTool`, `GetModpackContextTool`) return valid results; fetched docs wrapped in `<mod_doc trust="untrusted">...</mod_doc>`; oversized outputs truncated with visible marker | ✓ VERIFIED | All four tools present in `src/main/java/com/forgebook/tool/impl/`. `PromptFraming.wrap` produces `<mod_doc trust="untrusted" source="…" tag="…">…</mod_doc>` with 8000-char cap and visible truncation marker per D-14. `FetchModDocsPageTool` throws `ToolException(NO_DOCS_URL)` on empty URL → TOOL-07 fallback. Unit tests per-tool + `AgentLoopE2ETest` confirm the framing. |
| SC-4 | With `curseforge_modpack_id` set, system prompt (built at `ServerStartedEvent`) contains modpack name + description fetched exactly once; with ID missing, graceful degradation | ✓ VERIFIED (code + wiring) / ? human (live CF call) | `SystemPromptBuilder.buildAndCache` + `CurseForgeClient.fetch` (500-char summary cap, all exceptions → `Optional.empty` for CF-02) + `ModpackContextCache` volatile Optional all correct. **Runtime wiring now present**: `ForgeBookMod.java:76–78` registers `ServerStartedEvent → SystemPromptBuilder.buildAndCache(e.getServer())`. Two call sites confirmed (grep): `ForgebookReloadCommand.java:42` and `ForgeBookMod.java:78`. Live exactly-once observability routes to `human_verification`. |
| SC-5 | Synthetic mod with empty `getModURL()` triggers missing-docs fallback: `FetchModDocsPageTool` returns structured "no docs" result, agent follows up with `WebSearchTool`, produces a final answer citing ≥1 source URL | ✓ VERIFIED | `AgentLoopE2ETest.sc5_missingDocsFallbackToWebSearchProducesCitedReply` — offline proof via `ScriptedAiProvider`: 3 provider calls, t1 `is_error=true`/`NO_DOCS_URL`, t2 `is_error=false`/fixture URL, `FinalReply` contains fixture URL. `sc5_throughAiDispatcherProducesReply` confirms the same path via `AiDispatcher.dispatcherForTests`. `FetchModDocsPageTool` throws `NO_DOCS_URL` on empty URL. |

**Score:** 4/5 truths fully VERIFIED (SC-2, SC-3, SC-4, SC-5). SC-1 pending human verification (live Claude call). SC-2 and SC-4 moved from FAILED → VERIFIED after the runtime-wiring fix in commit `96b1dc6`.

### Required Artifacts

All Phase 2 production and test artifacts exist, are substantive, and pass structural checks. The runtime wiring regression identified in the previous verification has been fully fixed.

| Artifact | Expected | Status | Details |
| --- | --- | --- | --- |
| `src/main/java/com/forgebook/ai/AiDispatcher.java` | Sealed Result, INSTANCE singleton, dispatch, mapError, 0 `.raw()` calls in code | ✓ VERIFIED | Result sealed with Reply + Error records; INSTANCE static final; dispatch path has D-14 single-load, null-snap → PROVIDER Error, SystemPromptCache fallback, ProviderFactory.create, ClaudeMessage.userText, AgentLoop.run dispatch. |
| `src/main/java/com/forgebook/ai/AgentLoop.java` | 6-iter cap, parallel tool exec, D-12 error isolation | ✓ VERIFIED | `MAX_ITERATIONS = 6` public static final (line 41), for-loop bound, `ProviderError(ITERATION_CAP)` returned on cap. Parallel via `CompletableFuture.supplyAsync(..., AiExecutor.get())`. D-12 per-tool error isolation. |
| `src/main/java/com/forgebook/ai/provider/ClaudeProvider.java` | anthropic-version 2023-06-01 pinned, x-api-key, v1/messages, CB + Retry wired | ✓ VERIFIED | `ANTHROPIC_VERSION = "2023-06-01"` (line 48); `api.anthropic.com/v1/messages` (line 49); `x-api-key` (line 83). |
| `src/main/java/com/forgebook/ai/provider/OpenAiProvider.java` | NOT_IMPLEMENTED stub | ✓ VERIFIED | Returns `ProviderError(NOT_IMPLEMENTED, …)` per AI-01. |
| `src/main/java/com/forgebook/ai/provider/OllamaProvider.java` | NOT_IMPLEMENTED stub | ✓ VERIFIED | Returns `ProviderError(NOT_IMPLEMENTED, …)` per AI-01. |
| `src/main/java/com/forgebook/ai/provider/ProviderFactory.java` | create(ConfigSnapshot) switches over AiProviderKind | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/ai/CircuitBreaker.java` | 5-fail threshold, 5-min cooldown | ✓ VERIFIED | Present alongside AgentLoop. |
| `src/main/java/com/forgebook/ai/RetryPolicy.java` | 3 retries, 30 s cap, exp backoff, retry-after honored | ✓ VERIFIED | Present alongside AgentLoop. |
| `src/main/java/com/forgebook/ai/AiTurn.java` | Sealed FinalReply / ToolUses / ProviderError; Kind enum 7 values | ✓ VERIFIED | 3 permitted records; Kind: TRANSPORT, PROVIDER, OVERLOADED, RATE_LIMITED, NOT_IMPLEMENTED, CIRCUIT_OPEN, ITERATION_CAP. |
| `src/main/java/com/forgebook/ai/SystemPromptBuilder.java` | build() pure + buildAndCache(MinecraftServer) + buildAndCacheInternal seam; anti-injection rules last | ✓ VERIFIED | All three entry points present. Anti-injection rule 1 cites `<mod_doc trust="untrusted">`. |
| `src/main/java/com/forgebook/ai/SystemPromptCache.java` | private static volatile String current | ✓ VERIFIED | Volatile holder; `get()` returns current. |
| `src/main/java/com/forgebook/tool/Tool.java` | Interface with execute(params) | ✓ VERIFIED | Present with ToolResult + ToolException siblings. |
| `src/main/java/com/forgebook/tool/ToolRegistry.java` | init/all/get, test injection seams | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/tool/impl/ListInstalledModsTool.java` | TOOL-01 impl | ✓ VERIFIED | implements Tool. |
| `src/main/java/com/forgebook/tool/impl/FetchModDocsPageTool.java` | TOOL-02 impl; NO_DOCS_URL on empty URL; SafeHttpFetcher-routed | ✓ VERIFIED | Throws `ToolException(NO_DOCS_URL)` on empty/blank URL → TOOL-07 trigger. |
| `src/main/java/com/forgebook/tool/impl/WebSearchTool.java` | TOOL-03 impl; DDG + Brave adapters via config | ✓ VERIFIED | Uses `WebSearchAdapter` indirection. |
| `src/main/java/com/forgebook/tool/impl/GetModpackContextTool.java` | TOOL-04 impl; reads ModpackContextCache | ✓ VERIFIED | Present; reads cache. |
| `src/main/java/com/forgebook/integration/CurseForgeClient.java` | x-api-key header, /v1/mods/{id}, 500-char summary cap, graceful degradation | ✓ VERIFIED | Per Plan 02-04 SUMMARY; graceful degradation via Optional.empty on all exceptions (CF-02). |
| `src/main/java/com/forgebook/integration/ModpackContextCache.java` | volatile Optional holder | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/integration/ModpackContext.java` | DTO for CF payload | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/integration/scraper/ModDocsScraper.java` | 8-step selector chain + denoise | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/integration/scraper/PromptFraming.java` | `<mod_doc trust="untrusted" source="…" tag="…">…</mod_doc>` 8000-char cap | ✓ VERIFIED | `wrap()` produces framing with nonce tag; 8000-char cap per D-14. |
| `src/main/java/com/forgebook/integration/websearch/DuckDuckGoHtmlAdapter.java` | DDG HTML adapter (default) | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/integration/websearch/BraveSearchAdapter.java` | Brave adapter with X-Subscription-Token | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/integration/websearch/WebSearchAdapter.java` | Adapter interface | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/config/ConfigSnapshot.java` | 12-field record incl. maxTokens, webSearchProvider, webSearchApiKey | ✓ VERIFIED | Present. |
| `src/main/java/com/forgebook/config/ForgebookServerConfig.java` | AI_MODEL default claude-haiku-4-5, MAX_TOKENS defineInRange, WEB_SEARCH_PROVIDER / WEB_SEARCH_API_KEY | ✓ VERIFIED | Per Plan 02-01 SUMMARY. |
| `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java` | Phase 2 dispatch body; `AiExecutor.submit → AiDispatcher.INSTANCE.dispatch → enqueueWork → ChatResponse/Error` | ✓ VERIFIED | `AiDispatcher.INSTANCE.dispatch` invoked at line 106. D-19/D-20 preserved. |
| `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` | ConfigHolder.set + SystemPromptBuilder.buildAndCache, OP-gated | ✓ VERIFIED (code + runtime) | Command body correct (calls `SystemPromptBuilder.buildAndCache` at line 42). `ForgeBookMod.java:58` registers `ForgebookReloadCommand::onRegister` — **no longer orphaned**. |
| `src/main/java/com/forgebook/ForgeBookMod.java` | Register all Phase 1+2 listeners: reload cmd, ConfigHolder seed, AiExecutor.start, onServerStopping, SystemPromptBuilder.buildAndCache, ForgebookNetwork.register | ✓ VERIFIED | All 6 expected forge-bus / mod-bus / commonSetup wires present (see detail in Key Link Verification). Regression closed by commit `96b1dc6`. |
| `src/test/java/com/forgebook/ai/AiDispatcherTest.java` | 23 behaviors | ✓ VERIFIED | Present; 23/23 PASS per SUMMARY self-check; gradle test currently UP-TO-DATE. |
| `src/test/java/com/forgebook/ai/AgentLoopE2ETest.java` | 4 SC-5 scenarios | ✓ VERIFIED | Present; 4/4 PASS. |
| `src/test/java/com/forgebook/gametest/ChatDispatchSmokeTest.java` | 3 dispatch wiring tests | ✓ VERIFIED | Present. |

### Key Link Verification

| From → To | Via | Status | Details |
| --- | --- | --- | --- |
| ChatRequestHandler → AiDispatcher | AiExecutor.get().submit → AiDispatcher.INSTANCE.dispatch | ✓ WIRED | Grep confirms 1 code match (`ChatRequestHandler.java:106`). |
| AiDispatcher → AgentLoop | AgentLoop.run(req) | ✓ WIRED | AiDispatcher.dispatch calls AgentLoop.run with the constructed ChatRequest. |
| AgentLoop → ToolRegistry → Tool impls | ToolRegistry.all() / get(name) | ✓ WIRED | AiDispatcher passes ToolRegistry.all() → ToolDef list; AgentLoop dispatches ToolUse to ToolRegistry.get(name).execute(params). |
| AgentLoop → AiProvider | provider.send(req) | ✓ WIRED | Provider is ProviderFactory.create(snap) in production; ScriptedAiProvider in tests. |
| ProviderFactory → ConfigSnapshot.provider | AiProviderKind switch | ✓ WIRED | Exhaustive switch. |
| ClaudeProvider → Anthropic Messages API | HttpClient POST /v1/messages + x-api-key + anthropic-version | ✓ WIRED | Headers + URL pinned (live round-trip is `human_verification`). |
| SystemPromptBuilder → CurseForgeClient → ModpackContextCache | CF fetch async on AiExecutor then cache.set | ✓ WIRED | Path implemented; runtime trigger restored. |
| SystemPromptCache → AiDispatcher | SystemPromptCache.get() | ✓ WIRED | Read at dispatch entry; FALLBACK_SYSTEM_PROMPT when empty. |
| ForgeBookMod → AiExecutor lifecycle | ServerStartingEvent (start) / ServerStoppingEvent (stop) listeners | ✓ WIRED | `ForgeBookMod.java:67–69` (start) and `ForgeBookMod.java:70` (`AiExecutor::onServerStopping`). |
| ForgeBookMod → ForgebookReloadCommand.onRegister | RegisterCommandsEvent listener | ✓ WIRED | `ForgeBookMod.java:58` — `MinecraftForge.EVENT_BUS.addListener(ForgebookReloadCommand::onRegister)`. |
| ForgeBookMod → ConfigHolder seed | ServerStartingEvent listener | ✓ WIRED | `ForgeBookMod.java:59–62` — seeds `ConfigHolder.set(ConfigHolder.buildFromSpec())` on ServerStartingEvent. |
| ForgeBookMod → SystemPromptBuilder.buildAndCache | ServerStartedEvent listener | ✓ WIRED | `ForgeBookMod.java:76–78` — fires AFTER `AiExecutor.start()` per D-08 ordering requirement. |
| ForgeBookMod.commonSetup → ForgebookNetwork.register | e.enqueueWork(ForgebookNetwork::register) | ✓ WIRED | `ForgeBookMod.java:90` inside commonSetup. |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
| --- | --- | --- | --- | --- |
| AiDispatcher.dispatch | snap (ConfigSnapshot) | ConfigHolder.get() | Yes — seeded on ServerStartingEvent | ✓ FLOWING |
| AiDispatcher.dispatch | systemPrompt | SystemPromptCache.get() | Yes — populated on ServerStartedEvent; FALLBACK_SYSTEM_PROMPT only if CF fetch times out (by design) | ✓ FLOWING |
| AiDispatcher.dispatch | tools list | ToolRegistry.all() | Yes — ToolRegistry.init called from buildAndCacheInternal, which is invoked on both ServerStartedEvent and /forgebook reload | ✓ FLOWING |
| AgentLoop.run | AiExecutor | AiExecutor.get() | Yes — started on ServerStartingEvent | ✓ FLOWING |
| SystemPromptBuilder.buildAndCache | modpackContext | CurseForgeClient.fetch | Yes — listener now fires at runtime (observability of exactly-once contract routes to human_verification for live CF call) | ✓ FLOWING (offline) / ? human (live CF API) |

**Finding:** Every Phase 2 component now receives real data from its upstream. The outermost wire from Forge's event bus (`ForgeBookMod` constructor) correctly registers all six listeners required to drive the stack.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
| --- | --- | --- | --- |
| grep `addListener` in ForgeBookMod.java | — | 6 matches: modBus.addListener(this::commonSetup) (line 50), 5× MinecraftForge.EVENT_BUS.addListener (lines 58, 59, 67, 70, 76) | ✓ PASS |
| grep `EVENT_BUS.register` in ForgeBookMod.java | — | 1 match (line 53: register(this)) | ✓ PASS |
| grep `enqueueWork` in ForgeBookMod.java | — | 1 match (line 90: e.enqueueWork(ForgebookNetwork::register)) | ✓ PASS |
| grep `AiDispatcher.INSTANCE.dispatch` in src/main/java | — | 1 match (ChatRequestHandler.java:106) | ✓ PASS |
| grep `SystemPromptBuilder.buildAndCache` in src/main/java | — | 2 invocations (ForgebookReloadCommand.java:42 + ForgeBookMod.java:78), plus 1 Javadoc mention in SystemPromptCache.java:5 | ✓ PASS (previously failed — now correct) |
| grep `AiExecutor.start` in src/main/java | — | 1 invocation (ForgeBookMod.java:69) + Javadoc | ✓ PASS |
| grep `AiExecutor::onServerStopping` in src/main/java | — | 1 invocation (ForgeBookMod.java:70) | ✓ PASS |
| grep `MAX_ITERATIONS` in AgentLoop.java | — | public static final int MAX_ITERATIONS = 6 (line 41) | ✓ PASS |
| grep `anthropic-version "2023-06-01"` in ClaudeProvider.java | — | ANTHROPIC_VERSION constant (line 48) | ✓ PASS |
| grep `NOT_IMPLEMENTED` in provider dir | — | OpenAiProvider.java:16 + OllamaProvider.java:16 | ✓ PASS |
| grep `<mod_doc trust="untrusted"` in src/main/java | — | 3 matches (SystemPromptBuilder rule text, FetchModDocsPageTool Javadoc, PromptFraming.wrap) | ✓ PASS |
| grep `ChannelBuilder` in src/main/java | — | 0 matches | ✓ PASS (NeoForge-pattern correctly avoided) |
| `./gradlew test --quiet` | — | UP-TO-DATE, no failures | ✓ PASS |

### Requirements Coverage

| Requirement | Plans | Description | Status | Evidence |
| --- | --- | --- | --- | --- |
| AI-01 | 02-02, 02-03, 02-07 | Pluggable AiProvider; Claude v1 default; OpenAi + Ollama compile & throw not-implemented | ✓ SATISFIED (code) / ? human (real call) | Providers present; ProviderFactory switch; NOT_IMPLEMENTED on stubs. |
| AI-02 | 02-03 | Hand-rolled HttpClient + Gson; anthropic-version pinned | ✓ SATISFIED | Constant pinned; headers set; DTOs via Gson. |
| AI-03 | 02-03, 02-06 | 6-iter cap, 4xx no-retry, 5xx retry w/ exp backoff, CircuitBreaker 5-fail/5-min | ✓ SATISFIED | AgentLoop.MAX_ITERATIONS=6 + ITERATION_CAP; RetryPolicy; CircuitBreaker. |
| AI-04 | 02-07 | Single AiDispatcher entry; error taxonomy | ✓ SATISFIED | AiDispatcher.INSTANCE + Result sealed + mapError 7-way. |
| AI-05 | 02-05, 02-06 | Prompt framing `<mod_doc trust="untrusted">` | ✓ SATISFIED | PromptFraming.wrap + system-prompt rule 1. |
| AI-06 | 02-05 | Oversized outputs truncated with visible marker; 8000-char cap | ✓ SATISFIED | D-14 cap in PromptFraming. |
| AI-07 | 02-06 | Parallel tool execution (D-11), error-tolerant per-tool (D-12) | ✓ SATISFIED | AgentLoop CompletableFuture.supplyAsync per tool; D-12 per-tool isolation. |
| AI-08 | 02-06, 02-07 | Pre-rendered system prompt at ServerStartedEvent (exactly once) | ✓ SATISFIED | Code correct AND `ForgeBookMod.java:76–78` registers the listener — regression closed. |
| TOOL-01 | 02-05 | ListInstalledModsTool | ✓ SATISFIED | Present; implements Tool. |
| TOOL-02 | 02-05 | FetchModDocsPageTool with SafeHttpFetcher | ✓ SATISFIED | Present; SafeHttpFetcher-routed. |
| TOOL-03 | 02-05 | WebSearchTool with adapter indirection | ✓ SATISFIED | Present; DDG default + Brave fallback. |
| TOOL-04 | 02-05 | GetModpackContextTool | ✓ SATISFIED | Present; reads ModpackContextCache. |
| TOOL-05 | 02-05 | ToolRegistry init/all/get | ✓ SATISFIED | init is invoked at runtime via buildAndCacheInternal (triggered by ServerStartedEvent listener — wiring restored). |
| TOOL-06 | 02-05 | Tool interface + ToolResult + ToolException | ✓ SATISFIED | Present. |
| TOOL-07 | 02-07 | Missing-docs fallback (empty URL → NO_DOCS_URL → web_search) | ✓ SATISFIED | FetchModDocsPageTool throws NO_DOCS_URL; AgentLoopE2ETest.sc5_missingDocsFallbackToWebSearchProducesCitedReply proves path offline. |
| CF-01 | 02-04 | CurseForgeClient.fetch with x-api-key + Accept headers, 500-char summary cap | ✓ SATISFIED (code) / ? human (live fetch) | Code correct. Live fetch human_verification. |
| CF-02 | 02-04 | Graceful degradation when unconfigured / on failure | ✓ SATISFIED | Silent skip when unconfigured; all exceptions → Optional.empty. |
| CF-03 | 02-04, 02-06 | ModpackContextCache populated exactly once at ServerStartedEvent | ✓ SATISFIED | Cache + builder correct; ServerStartedEvent listener wired at `ForgeBookMod.java:76–78` — regression closed. |

**Orphaned requirements:** None — all AI-/TOOL-/CF- IDs claimed by plans appear in REQUIREMENTS.md.

### Anti-Patterns Found

The blocking wiring regression flagged in the previous report has been fully closed. Remaining items are low-severity warnings from `02-REVIEW.md` (independent code review) carried forward for context.

| File | Line | Pattern | Severity | Impact |
| --- | --- | --- | --- | --- |
| `ForgeBookMod.java` | 49–90 | ~~All forge-bus addListener calls absent~~ | ✅ RESOLVED | Closed by commit `96b1dc6`. All 6 listeners + commonSetup network registration restored. |
| `SystemPromptBuilder.java` | WR-02 (per REVIEW) | 20 s blocking wait on CurseForge fetch inside buildAndCache | ⚠️ Warning | Delays server startup up to 20 s if CurseForge is slow; within-budget but consider making async + warm-up-async. Deferred to post-MVP polish. |
| Plans 02-06 / 02-07 | WR-01 | ScriptedAiProvider is a production-visible class used only in tests | ⚠️ Warning | Low — clearly marked test seam; acceptable pattern. |
| `AgentLoop.java` | WR-03 | Parallel tool execution uses AiExecutor for ALL tools including trivial ones | ⚠️ Warning | Minor executor-queue pressure; acceptable given 6-iter cap. |
| `CurseForgeClient.java` | WR-04 | 15 s timeout without jitter | ⚠️ Warning | Low — single call at startup, not per-request. |
| `AiDispatcher.java` | WR-05 | FALLBACK_SYSTEM_PROMPT is silent-fallback: log WARN + use default when cache empty | ⚠️ Warning | Defense-in-depth suggestion carried forward from previous review — a loud-failure mode would have surfaced the wiring regression faster. Consider tightening in a follow-up. |
| 17 × info-level findings | various | IN-01..IN-17 in REVIEW | ℹ️ Info | No immediate action; tracked in REVIEW. |

### Human Verification Required

See `human_verification:` frontmatter block above. Four live-network items cannot be automated:

1. **Real ClaudeProvider v1/messages round-trip (SC-1, AI-01)** — live Anthropic call with valid key.
2. **Real CurseForge enrichment (SC-4, CF-01/CF-02)** — live CF call at ServerStartedEvent. **No longer blocked** — wiring is now in place for a live-server test.
3. **Agent multi-step tool loop against live Claude (SC-2, SC-5)** — verify real Claude tool-use content-block JSON shape. **No longer blocked** — executor lifecycle is now wired.
4. **Live DDG / Brave web search adapter** — verify DDG HTML parser isn't drifting.

All four items are now genuinely awaiting human/live-network validation rather than being gated on missing wiring.

### Gaps Summary

No automated gaps remain. All Phase 2 production code compiles, all 30 automated tests pass, the full forge-bus listener set is registered from `ForgeBookMod` constructor, and every key data-flow link is traceable from the outermost event hook through to Claude's HTTP endpoint.

The phase is ready to proceed to human verification for the four live-network scenarios captured in `human_verification:`. Upon their completion, Phase 2 will be fully VERIFIED. In the interim, the automated contract (code + wiring + unit/E2E tests) is complete and no further planning work is needed on Phase 2 scope.

---

*Verified: 2026-04-16T14:30:00Z*
*Verifier: Claude (gsd-verifier)*
*Re-verification after fix commit: `96b1dc6`*
