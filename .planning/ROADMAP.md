# Roadmap: ForgeBook

**Created:** 2026-04-14
**Granularity:** coarse (3-5 phases, 1-3 plans per phase)
**Mode:** YOLO, parallel plans enabled
**Core Value:** A player holding an unfamiliar item from an unfamiliar mod gets a grounded, trustworthy answer about what it does and how to use it — without alt-tabbing to a wiki.

## Phases

- [ ] **Phase 1: Foundations & Safe Egress** — Forge 1.20.1-47.4.18 skeleton with client-classloader firewall, SERVER-only secret config, `SimpleChannel` wiring, and `SafeHttpFetcher` SSRF guard so every top-5 critical pitfall is prevented on day one.
- [ ] **Phase 2: AI Engine & Grounding** — Pluggable `AiProvider` (Claude v1, OpenAI/Ollama stubs), `AgentLoop` with hard caps, tool registry (`ListInstalledMods`, `FetchModDocsPage`, `WebSearch`, `GetModpackContext`), and optional CurseForge enrichment.
- [ ] **Phase 3: Command Surface & Safety Controls** — `/forgebook` Brigadier tree (`item`/`ask`/`reload`/`disable`/`enable`/`stats`) exercising the full pipeline without GUI, with OP gate, per-UUID rate limiter, circuit breaker, structured errors, and audit logging.
- [ ] **Phase 4: In-Inventory Chat UI** — Inventory button injector, docked `ChatScreen`, per-session `ClientChatSession`, loading/error states, and scale-aware layout — all still routing every AI call through the server dispatcher.
- [ ] **Phase 5: Release Polish** — Logo placeholders, `en_us.json`, README with security posture, compat matrix (JEI/REI/Sodium/Jade/Quark/etc.), and a prod-jar smoke test on a clean dedicated server.

## Phase Details

### Phase 1: Foundations & Safe Egress
**Goal**: The project is a loadable Forge 1.20.1-47.4.18 mod on both client and dedicated server, with every CRITICAL-pitfall guardrail (classloader firewall, SERVER-only secrets, off-tick HTTP, SSRF-safe fetcher) enforced before any AI code lands.
**Depends on**: Nothing (first phase)
**Requirements**: SCAF-01, SCAF-02, SCAF-03, SCAF-04, SCAF-05, SCAF-06, SCAF-07, SCAF-08, CFG-01, CFG-02, CFG-03, CFG-04, CFG-05, CFG-06, CFG-07, NET-01, NET-02, NET-03, NET-04, NET-05, NET-06
**Success Criteria** (what must be TRUE):
  1. `./gradlew runClient` and `./gradlew runServer` both launch a clean mod on Forge 1.20.1-47.4.18 with Java 17 from a fresh checkout, and a headless CI smoke run loads the mod on a dedicated server without `NoClassDefFoundError` from client-only classes.
  2. `forgebook-server.toml` materializes every SERVER-tier field (including `ai_api_key`, `curseforge_api_key`, `op_only`, `rate_limit_per_minute`); any log line that touches an API key renders `<redacted>`, and `/forgebook reload` atomically swaps the `ConfigSnapshot` without a restart.
  3. A `ChatRequestPacket` sent from the client is received by the server on `SimpleChannel "forgebook:main"`, bounced back as a `ChatResponsePacket`, and rendered on the client — with the server-side handler provably hopping HTTP work off the main thread via the dedicated `aiExecutor` before any `enqueueWork` game-state touch.
  4. `SafeHttpFetcher` rejects, with observable error codes, any URL that is http-only, resolves to a private/loopback/link-local IP, exceeds 3 redirect hops, returns >1 MB, violates the content-type allowlist, or exceeds the 15s timeout — proven by unit tests covering every rule.
**Plans**: 5 plans
  - [x] 01-01-PLAN.md — Scaffold & build: MDK extraction, Gradle/FG6/Parchment, jsoup relocation via jarJar, @Mod entry, manifest/license, runClient/runServer checkpoint
  - [x] 01-02-PLAN.md — Config & secrets: dual ForgeConfigSpec (SERVER + CLIENT), ApiKey + ConfigSnapshot + ConfigHolder, Log4j2 ApiKeyScrubFilter, /forgebook reload Brigadier command
  - [x] 01-03-PLAN.md — Networking: AiExecutor lifecycle, three packets + ChunkedPayload, ForgebookNetwork SimpleChannel registration, ChatRequestHandler echo
  - [x] 01-04-PLAN.md — Safe egress: UnsafeUrlException + Cidr + SafeHttpFetcher with SNI workaround, one unit test per Reason value
  - [x] 01-05-PLAN.md — CI & testing: GitHub Actions workflow (firewall lint + build + runGameTestServer + leak scrape), ChatEchoGameTest, local+GHA human-verify
**UI hint**: no

### Phase 2: AI Engine & Grounding
**Goal**: A server-side `AiDispatcher` can answer a grounded question end-to-end by driving Claude (v1 default) through a capped tool-using agent loop that consults the installed mod list, fetches mod docs, falls back to web search, and optionally enriches the system prompt with CurseForge modpack context.
**Depends on**: Phase 1
**Requirements**: AI-01, AI-02, AI-03, AI-04, AI-05, AI-06, AI-07, AI-08, TOOL-01, TOOL-02, TOOL-03, TOOL-04, TOOL-05, TOOL-06, TOOL-07, CF-01, CF-02, CF-03
**Success Criteria** (what must be TRUE):
  1. `ClaudeProvider` (hand-rolled on `java.net.http.HttpClient` + Gson) successfully completes a real `v1/messages` turn end-to-end; `OpenAiProvider` and `OllamaProvider` compile, are selectable via `ai_provider`, and throw a clear "not implemented in v1" error when invoked.
  2. `AgentLoop` drives a multi-step `ToolUses → tool execution → next turn` cycle that terminates at `FinalReply`, truncates or stops cleanly at the 6-iteration hard cap, never retries a 4xx, retries 5xx up to 3 times with exponential backoff, and trips the circuit breaker after 5 consecutive failures.
  3. All four tools (`ListInstalledModsTool`, `FetchModDocsPageTool`, `WebSearchTool`, `GetModpackContextTool`) return valid results for their happy path and frame every fetched document as `<mod_doc trust="untrusted">...</mod_doc>` in the next model turn; oversized outputs are truncated with a visible marker rather than silently dropped.
  4. With `curseforge_modpack_id` set, the pre-rendered system prompt (built at `ServerStartedEvent`) contains the modpack name + description fetched exactly once at startup; with the ID missing, startup completes without error and the prompt degrades gracefully.
  5. A synthetic mod with empty `getModURL()` triggers the missing-docs fallback: `FetchModDocsPageTool` returns a structured "no docs" result and the agent follows up with `WebSearchTool`, producing a final answer that cites at least one source URL.
**Plans**: 7 plans
  - [x] 02-01-PLAN.md — Config extension: WebSearchProviderKind enum, MAX_TOKENS / WEB_SEARCH_PROVIDER / WEB_SEARCH_API_KEY fields, AI_MODEL default bump to claude-haiku-4-5, ApiKeyScrubFilter covers X-Subscription-Token
  - [x] 02-02-PLAN.md — Contracts & DTOs: AiProvider interface, sealed AiTurn (FinalReply/ToolUses/ProviderError), ChatRequest, Anthropic Gson DTOs (ClaudeRequest/Response/Message, ContentBlock, ToolDef, Usage, ClaudeError), Tool interface + ToolResult + ToolException
  - [x] 02-03-PLAN.md — Provider layer: ClaudeProvider (hand-rolled HttpClient + Gson, anthropic-version 2023-06-01), CircuitBreaker (5-fail / 5-min cooldown), RetryPolicy (3-retry / 30s cap / respects retry-after), HttpExecutor test seam, OpenAi + Ollama NOT_IMPLEMENTED stubs
  - [x] 02-04-PLAN.md — CurseForge integration: ModpackContext record, CurseForgeClient.fetch (x-api-key + Accept headers, 500-char summary cap), ModpackContextCache volatile holder, graceful degradation (CF-02)
  - [x] 02-05-PLAN.md — Tools & grounding: ModDocsScraper (8-step selector chain + denoise, D-16), PromptFraming (<mod_doc trust="untrusted"> nonce-tagged framing, 8000-char truncation per D-14), WebSearchAdapter interface + DuckDuckGoHtmlAdapter (default) + BraveSearchAdapter (fallback), all four tool impls, ToolRegistry
  - [x] 02-06-PLAN.md — Agent core: AgentLoop (6-iteration cap, parallel D-11, error-tolerant D-12), SystemPromptBuilder (pure build + buildAndCache orchestration), SystemPromptCache volatile holder, ScriptedAiProvider test seam
  - [x] 02-07-PLAN.md — Wiring & SC-5: AiDispatcher sealed Result + INSTANCE, ProviderFactory, ChatRequestHandler upgrade from echo to dispatch, ForgeBookMod ServerStartedEvent listener, ForgebookReloadCommand prompt rebuild, end-to-end SC-5 AgentLoopE2ETest
**UI hint**: no

### Phase 3: Command Surface & Safety Controls
**Goal**: A player on a headless server can use `/forgebook item`, `/forgebook ask`, and admin subcommands to exercise the full AI pipeline — including OP gating, per-player rate limiting, kill-switch, structured error taxonomy, and audit logging — without any GUI dependency.
**Depends on**: Phase 2
**Requirements**: CMD-01, CMD-02, CMD-03, CMD-04, CMD-05, CMD-06, CMD-07, SAFE-01, SAFE-02, SAFE-03, SAFE-04, SAFE-05, SAFE-06
**Success Criteria** (what must be TRUE):
  1. `/forgebook item` with no args returns a grounded answer for the caller's main-hand item using the RAG single-shot path (fetch `getModURL()` → scraper → one Claude call, no tool loop), and `/forgebook item <modid:item_id>` works for any registered item; every reply cites the source URL(s) consulted.
  2. `/forgebook ask <message...>` returns a single-turn chat reply from the command line; `/forgebook reload`, `/forgebook disable`, `/forgebook enable`, and `/forgebook stats` are all OP-gated (`hasPermission(2)`) and behave as specified (atomic reload, global kill-switch, per-player counters + token usage + latency stats).
  3. With `op_only = true`, a non-OP player calling `/forgebook item` receives a `FORBIDDEN` feedback message and no provider call is made; with `op_only = false`, the same player is bound by a per-UUID token bucket sized from `rate_limit_per_minute` — on exhaustion they receive a human-readable `RATE_LIMITED` message stating retry-after seconds, while OPs bypass entirely.
  4. Every AI request emits one structured log line (player UUID, request kind, est. input tokens, response tokens, latency, outcome) with zero message content logged by default; every error bubbled to a player falls into the fixed taxonomy `TRANSPORT`/`RATE_LIMITED`/`FORBIDDEN`/`PROVIDER`/`DISABLED` with no stack traces or raw provider payloads leaking to the client.
  5. Server-side packet handlers re-check OP permission on every packet arrival, so a spoofed client cannot bypass the gate by sending a request directly.
**Plans**: 7 plans
  - [x] 03-01-PLAN.md — Safety primitives: RequestKind + DispatchContext + KillSwitch + TokenBucket + RateLimiter + RateLimiterHolder (CMD-05, SAFE-02, SAFE-03)
  - [x] 03-02-PLAN.md — Observability: StatsAccumulator (per-UUID LongAdder + top-10 render) + RequestAuditLogger (named 'forgebook.audit' logger, fans out to stats) (SAFE-04, CMD-06)
  - [x] 03-03-PLAN.md — Authorizer + AiDispatcher integration: sealed Result, 4-step auth order, AiTurn.FinalReply Optional<Usage> extension, dispatch(DispatchContext) refactor with audit emission (SAFE-01, SAFE-02, SAFE-03, SAFE-04, SAFE-05)
  - [x] 03-04-PLAN.md — RagItemPipeline: SafeHttpFetcher -> scraper -> framing -> single-shot provider.chat with empty tools[] + 'Source:' citation (CMD-02, CMD-07)
  - [x] 03-05-PLAN.md — ChatRequestHandler SAFE-06 precheck: Authorizer before AiExecutor.submit on network thread, no queue consumption on denial (SAFE-06)
  - [x] 03-06a-PLAN.md — Wiring foundation: ForgebookReloadCommand.executeReload extraction + RateLimiterHolder.swap + ForgebookCommands tree registration (ask/item/reload/disable/enable/stats) + subcommand stub classes + ForgeBookMod listener swap + RateLimiterHolder seeding on ServerStartingEvent (CMD-01, CMD-04)
  - [x] 03-06b-PLAN.md — Subcommand bodies: ItemSubcommand (held + argument forms, modURL lookup, RagItemPipeline submit) + AskSubcommand (AiDispatcher dispatch with server.execute hop) + AdminSubcommands (disable/enable/stats) + in-game smoke checkpoint (CMD-02, CMD-03, CMD-05, CMD-06)
**UI hint**: no

### Phase 4: In-Inventory Chat UI
**Goal**: A player with the mod installed can click a button inside the vanilla inventory screen, open a docked chat panel, hold a multi-turn conversation that uses the full tool-using agent, see loading and error states clearly, and have the conversation evaporate when they close the screen or disconnect — all without the client ever touching an API key.
**Depends on**: Phase 3
**Requirements**: UI-01, UI-02, UI-03, UI-04, UI-05, UI-06, UI-07, UI-08
**Success Criteria** (what must be TRUE):
  1. Opening the inventory screen shows a ForgeBook toggle button at a fixed offset relative to `leftPos`/`topPos` that does not overlap vanilla widgets; clicking it opens a `ChatScreen` rendered adjacent to (not replacing) the inventory, with the vanilla inventory still fully visible and interactable.
  2. A player types a question, sees a loading indicator while the server processes it, and receives an assistant `MessageBubble` in the scrollable conversation view — with inline error surfacing (not a toast, not a crash) when the server returns any `ChatErrorPacket` in the Phase-3 taxonomy.
  3. Closing the chat screen OR disconnecting from the server clears the entire in-memory `ClientChatSession`; reopening the screen starts a fresh session, with no prior messages visible.
  4. At GUI scales 1 through 4 on screens ≥1280×720 the chat panel renders without clipping vanilla widgets or the chat content itself; a minimum-width or stacked fallback triggers on smaller resolutions.
  5. With `enable_chat_interface = false` (CLIENT config), the button is never injected and the `ChatScreen` cannot be opened; the client source tree contains zero code paths that read or carry an API key value.
**Plans**: 6 plans
  - [x] 04-01-PLAN.md — Value types + error-taxonomy color map: ChatEntry sealed interface, MessageBubble/ErrorCard records, LoadingIndicator dot cycler, enumerative color test (UI-04)
  - [x] 04-02-PLAN.md — Pure-function seams + session state: ChatPanelLayout.compute, InventoryButtonGeometry.compute, ClientChatSession singleton with stale-guard (UI-05, UI-07)
  - [x] 04-03-PLAN.md — Packet-sink bridge + disconnect listener: ClientPacketSinks volatile-sink holder, ChatResponsePacket/ChatErrorPacket handler rewires, SessionLifecycleListener (UI-05, UI-08)
  - [x] 04-04-PLAN.md — ChatPanelWidget: AbstractWidget subclass with bubble/error/loading rendering, scroll math, Font.split wrapping (UI-04)
  - [x] 04-05-PLAN.md — ChatScreen + InventoryButtonInjector + en_us.json + UI-08 CI grep + ClientSetup wiring (UI-01, UI-02, UI-03, UI-05, UI-06, UI-08)
  - [x] 04-06-PLAN.md — Human smoke checkpoint: live runClient+runServer UAT across all 5 ROADMAP success criteria (UI-01, UI-02, UI-04, UI-05, UI-06, UI-07, UI-08)
**UI hint**: yes

### Phase 5: Release Polish
**Goal**: The mod ships as a tagged release with user-droppable logo slots, full localization coverage, a README that teaches server owners the security posture, a documented mod-compatibility matrix, and a prod-jar smoke test on a clean dedicated server — nothing shipped relies on dev-environment assumptions.
**Depends on**: Phase 4
**Requirements**: REL-01, REL-02, REL-03, REL-04, REL-05
**Success Criteria** (what must be TRUE):
  1. Both logo asset slots (`src/main/resources/logo.png` and `src/main/resources/assets/forgebook/textures/gui/logo.png`) exist as documented placeholders with a README note pointing the user at where to drop the final designed asset; the mod loads cleanly with placeholders in place.
  2. `assets/forgebook/lang/en_us.json` covers every user-facing string — button label, chat placeholder, loading text, every error taxonomy message, every command feedback line — with zero raw English literals remaining in user-visible code paths.
  3. README documents installation, every config field, the server-side-only API key posture, the OP-only default, and the `chmod 600 forgebook-server.toml` recommendation; `THIRD_PARTY_NOTICES.md` credits jsoup.
  4. A mod-compatibility matrix is recorded in the repo covering JEI, REI, Sodium/Embeddium, Iris/Oculus, Jade, Mouse Tweaks, Quark, and Inventory HUD+, manually verified at GUI scales 1 and 2.
  5. The built jar (not a dev run) loads and serves a real `/forgebook item` query on a clean Forge 1.20.1-47.4.18 dedicated server with nothing else installed but ForgeBook.
**Plans**: 6 plans
  - [x] 05-01-PLAN.md — Packaging finalization: bump build.gradle to 1.0.0, populate mods.toml credits/issueTrackerURL, create second logo slot as byte-copy of existing placeholder (REL-01)
  - [x] 05-02-PLAN.md — Docs: write repo-root README.md (install, full config tables, security posture, chmod 600, commands, compatibility/logo/credits/license cross-links); verify LICENSE + THIRD_PARTY_NOTICES.md (jsoup MIT) unchanged (REL-03)
  - [x] 05-03-PLAN.md — i18n resource: expand en_us.json from 21 → 47 keys with 26 new forgebook.command.* keys (REL-02)
  - [x] 05-04-PLAN.md — i18n refactor: Authorizer.Denied + AiDispatcher.Error split-field (String humanReadable + Component feedback); 5 command classes + RagItemPipeline use Component.translatable; wire format preserved (REL-02)
  - [ ] 05-05-PLAN.md — Compat matrix: docs/COMPATIBILITY.md with 8-row skeleton + 9-step testing protocol + re-run triggers; human-verify checkpoint for row-filling (REL-04)
  - [ ] 05-06-PLAN.md — Release smoke: docs/RELEASE-SMOKE.md with 9-step protocol; automated Step 1 (clean build + jsoup relocation check); human-verify checkpoint for Steps 2-9 on clean dedicated server (REL-05)
**UI hint**: no

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundations & Safe Egress | 0/5 | Not started | - |
| 2. AI Engine & Grounding | 0/7 | Not started | - |
| 3. Command Surface & Safety Controls | 0/7 | Not started | - |
| 4. In-Inventory Chat UI | 0/? | Not started | - |
| 5. Release Polish | 0/? | Not started | - |

## Coverage

- v1 requirements: 59 total
- Mapped to phases: 59
- Unmapped: 0

| Category | Count | Phase |
|----------|-------|-------|
| SCAF | 8 | Phase 1 |
| CFG | 7 | Phase 1 |
| NET | 6 | Phase 1 |
| AI | 8 | Phase 2 |
| TOOL | 7 | Phase 2 |
| CF | 3 | Phase 2 |
| CMD | 7 | Phase 3 |
| SAFE | 6 | Phase 3 |
| UI | 8 | Phase 4 |
| REL | 5 | Phase 5 |

---
*Roadmap created: 2026-04-14*
*Last updated: 2026-04-16 after Phase 3 planning (Plan 06 split into 06a + 06b per checker feedback)*
