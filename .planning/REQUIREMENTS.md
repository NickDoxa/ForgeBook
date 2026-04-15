# Requirements: ForgeBook

**Defined:** 2026-04-14
**Core Value:** A player holding an unfamiliar item from an unfamiliar mod gets a grounded, trustworthy answer about what it does and how to use it — without alt-tabbing to a wiki.

## v1 Requirements

### Scaffold (SCAF)

- [ ] **SCAF-01**: Forge 1.20.1-47.4.18 mod builds cleanly on Java 17 with Gradle 8.1.1 + ForgeGradle 6.0.x and Parchment `2023.09.03-1.20.1` mappings
- [ ] **SCAF-02**: Package layout enforces client-classloading firewall — `com.forgebook.client.*` is the only package that imports `net.minecraft.client.*`; all other packages (`config`, `network`, `ai`, `command`, `integration`, `util`) are client-safe
- [ ] **SCAF-03**: `mods.toml` declares `modId = "forgebook"`, `license = "MIT"`, `displayURL`, `logoFile = "logo.png"`, and `authors`
- [ ] **SCAF-04**: `@Mod` entry (`ForgeBookMod`) subscribes to common and mod event buses; `DistExecutor.safeRunWhenOn(Dist.CLIENT, …)` is the only client-entry path
- [ ] **SCAF-05**: Gradle shadow relocates `jsoup` to `com.forgebook.shadow.jsoup` without breaking ForgeGradle reobf
- [ ] **SCAF-06**: Both `runClient` and `runServer` launch configurations work out of the box on a clean checkout
- [ ] **SCAF-07**: CI runs a headless smoke test that loads the mod on a dedicated server (catches client-classloading leaks)
- [ ] **SCAF-08**: `LICENSE` (MIT) and `THIRD_PARTY_NOTICES.md` (jsoup attribution) are present at repo root

### Config (CFG)

- [ ] **CFG-01**: `ForgeConfigSpec` exposes a SERVER-tier spec with fields: `ai_provider` (enum: anthropic/openai/ollama), `ai_api_key` (string, redacted in logs), `ai_model` (string), `curseforge_modpack_id` (string, optional), `curseforge_api_key` (string, redacted), `op_only` (bool, default true), `rate_limit_per_minute` (int, default 5), `enable_web_search` (bool), `config_version` (int, = 1)
- [ ] **CFG-02**: `ForgeConfigSpec` exposes a CLIENT-tier spec with one field: `enable_chat_interface` (bool)
- [ ] **CFG-03**: `ApiKey` value type wraps string config values so `toString()` returns `"<redacted>"` and the raw key is reachable only via an explicit accessor used by the HTTP adapters
- [ ] **CFG-04**: Config is materialized into an immutable `ConfigSnapshot` record; reloads atomically swap the snapshot so in-flight requests see a consistent view
- [ ] **CFG-05**: Log interceptor scrubs `Authorization`, `x-api-key`, and any `sk-ant-` / `sk-proj-` prefixed substrings from log output
- [ ] **CFG-06**: `.gitignore` excludes `run/`, `.gradle/`, `build/`, `*.toml.bak`, and any `forgebook-server.toml` outside `config/` fixtures
- [ ] **CFG-07**: `/forgebook reload` (OP-only) triggers atomic config reload without restart

### Networking (NET)

- [ ] **NET-01**: `SimpleChannel "forgebook:main"` is registered via `NetworkRegistry.newSimpleChannel(...)` with protocol version string and bidirectional acceptor
- [ ] **NET-02**: `ChatRequestPacket` (C→S), `ChatResponsePacket` (S→C), and `ChatErrorPacket` (S→C) are defined with binary encode/decode and registered on both sides
- [ ] **NET-03**: Packet handlers invoke `ctx.enqueueWork(...)` before touching game state; HTTP work hops off the main thread via the dedicated `aiExecutor`
- [ ] **NET-04**: Payloads larger than 32 KB are chunked to avoid SimpleChannel size limits
- [ ] **NET-05**: `SafeHttpFetcher` enforces https-only scheme allowlist, private-IP / loopback / link-local block (`127/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `::1`, `fc00::/7`), redirect re-validation (max 3 hops), 1 MB response cap, content-type allowlist, and per-request timeout (default 15s)
- [ ] **NET-06**: End-to-end packet echo test passes in both integrated (SP) and dedicated (MP) server flows

### AI Provider + Agent (AI)

- [ ] **AI-01**: `AiProvider` interface defines `chat(ChatRequest) → CompletableFuture<AiTurn>` where `AiTurn` is a sealed type (`FinalReply` / `ToolUses` / `ProviderError`)
- [ ] **AI-02**: `ClaudeProvider` implementation calls `https://api.anthropic.com/v1/messages` via `java.net.http.HttpClient` with `x-api-key`, `anthropic-version`, and `content-type` headers; request/response DTOs defined with Gson
- [ ] **AI-03**: `OpenAiProvider` and `OllamaProvider` stubs are compilable and selectable via config; each throws a clear "not implemented in v1" error when invoked
- [ ] **AI-04**: `AiDispatcher` (server singleton) is the sole entry point: authorizes caller (OP gate + rate limit), enqueues work on `aiExecutor`, returns `CompletableFuture`
- [ ] **AI-05**: `AgentLoop` iterates `ToolUses → tool execution → next provider turn` with a hard cap of 6 iterations; exceeding the cap returns a structured error without further provider calls
- [ ] **AI-06**: Retry policy: max 3 retries on 5xx / connection errors with exponential backoff capped at 30s; 4xx errors never retry
- [ ] **AI-07**: Circuit breaker trips after 5 consecutive provider failures and cools for 5 minutes; tripped state returns a structured error to callers
- [ ] **AI-08**: System prompt is pre-rendered at `ServerStartedEvent` (includes installed mod list + optional modpack context) and reused across requests rather than rebuilt per call

### Tools (TOOL)

- [ ] **TOOL-01**: `Tool` interface defines `name`, `schema` (JSON), `invoke(args) → ToolResult`; `ToolRegistry` is populated at `ServerStartedEvent` from a static list
- [ ] **TOOL-02**: `ListInstalledModsTool` returns a compact list of installed mods with `modId`, `displayName`, `version`, and `modURL` (via `IModInfo.getModURL()`)
- [ ] **TOOL-03**: `FetchModDocsPageTool` takes a URL, routes through `SafeHttpFetcher`, and uses `ModDocsScraper` (jsoup readability heuristic: `<article>` → `<main>` → largest-text `<div>`) to return plain text; output is framed `<mod_doc trust="untrusted">...</mod_doc>` in the next model turn
- [ ] **TOOL-04**: `WebSearchTool` (gated by `enable_web_search`) returns title/snippet/url triples only — never raw page content; prompt-injection framing applied identically
- [ ] **TOOL-05**: `GetModpackContextTool` returns the cached `ModpackContext` (name + description) when `curseforge_modpack_id` is set; returns a clear "no modpack configured" result otherwise
- [ ] **TOOL-06**: Tool output larger than the per-turn content cap is truncated with a visible marker, never silently dropped
- [ ] **TOOL-07**: Missing-docs fallback: when `FetchModDocsPageTool` receives an empty URL or 404, it returns a structured "no docs" result; the agent falls back to `WebSearchTool`

### CurseForge (CF)

- [ ] **CF-01**: `CurseForgeClient` fetches `GET /v1/mods/{curseforge_modpack_id}` at `ServerStartedEvent` using `x-api-key` header; caches `ModpackContext` (name + summary) in memory
- [ ] **CF-02**: CurseForge integration is strictly optional: missing modpack ID or API key degrades gracefully (no errors, no prompt enrichment)
- [ ] **CF-03**: CurseForge requests never run per-user-message; only the single startup fetch (+ `/forgebook reload`)

### Commands (CMD)

- [ ] **CMD-01**: `/forgebook` registers as a Brigadier command with subcommands: `item`, `ask`, `reload`, `disable`, `enable`, `stats`
- [ ] **CMD-02**: `/forgebook item` with no args targets the caller's main-hand item; `/forgebook item <modid:item_id>` targets any registered item; item lookup uses RAG single-shot (fetch `getModURL()` → `ModDocsScraper` → single Claude call, no tool loop)
- [ ] **CMD-03**: `/forgebook ask <message…>` performs a single-turn chat exchange from the command line (no session, no UI); useful on headless servers
- [ ] **CMD-04**: `/forgebook reload` re-reads config atomically — OP-only (`requires(src -> src.hasPermission(2))`)
- [ ] **CMD-05**: `/forgebook disable` and `/forgebook enable` act as a global kill switch — OP-only; when disabled, both chat UI and `/forgebook item` / `ask` return a "temporarily disabled" message
- [ ] **CMD-06**: `/forgebook stats` returns per-player request count, estimated token usage, and latency stats for the current server session — OP-only
- [ ] **CMD-07**: Every command reply that originates from AI output cites the source URL(s) consulted

### Client UI (UI)

- [ ] **UI-01**: `InventoryButtonInjector` listens on `ScreenEvent.Init.Post` filtered to `InventoryScreen`; adds a toggle button at a fixed offset relative to `leftPos`/`topPos` that does not overlap vanilla widgets
- [ ] **UI-02**: Clicking the button opens `ChatScreen` — a subclass of `AbstractContainerScreen<InventoryMenu>` with shifted `leftPos` and a chat panel rendered at negative x offset (fallback: standalone `Screen` if slot hit-testing conflicts)
- [ ] **UI-03**: Chat UI uses vanilla-reused assets and any user-supplied logo only — no copyrighted third-party textures
- [ ] **UI-04**: `ChatWidget` renders a scrollable conversation with user + assistant `MessageBubble`s, a text input, a submit button, a loading indicator during in-flight requests, and an inline error surface
- [ ] **UI-05**: `ClientChatSession` keeps the conversation in memory only; it is cleared when the screen is closed OR when the player disconnects
- [ ] **UI-06**: UI respects `enable_chat_interface` client config — when false, no button is injected
- [ ] **UI-07**: UI renders correctly at GUI scales 1–4 and screen sizes ≥1280×720; clipping at small resolutions is prevented by a minimum-width rule or stacked fallback
- [ ] **UI-08**: Client never holds or displays any API key; all AI interactions are initiated by sending `ChatRequestPacket` to the server

### Safety + Cost Controls (SAFE)

- [ ] **SAFE-01**: OP-only gate is enforced server-side at the `AiDispatcher` boundary when `op_only = true`; when disabled, all players pass
- [ ] **SAFE-02**: `RateLimiter` implements a per-UUID token bucket with capacity and refill driven by `rate_limit_per_minute`; OPs bypass; buckets are in-memory only and count *initiated* requests (not just successful)
- [ ] **SAFE-03**: Rate-limited callers receive a `ChatErrorPacket` (UI) or command feedback (command) with a clear "you are rate limited, try again in Ns" message
- [ ] **SAFE-04**: Every AI request emits a structured log line: player UUID, request kind (chat/item/ask), estimated input tokens, response tokens, latency, outcome; no message content is logged by default
- [ ] **SAFE-05**: Error classes surfaced to the client are limited to `TRANSPORT` / `RATE_LIMITED` / `FORBIDDEN` / `PROVIDER` / `DISABLED`; stack traces and raw provider errors never reach clients
- [ ] **SAFE-06**: Packet handlers on the server re-validate permissions on every packet — clients sending a packet while not OP-gated does not bypass the OP check

### Release + Polish (REL)

- [ ] **REL-01**: `src/main/resources/logo.png` slot and `src/main/resources/assets/forgebook/textures/gui/logo.png` slot are documented and present as placeholders until the user drops in the designed asset
- [ ] **REL-02**: `assets/forgebook/lang/en_us.json` covers every user-facing string (button label, errors, command feedback)
- [ ] **REL-03**: README documents installation, config fields, security posture (server-side API key), OP-only default, and the "chmod 600 forgebook-server.toml" recommendation
- [ ] **REL-04**: A mod-compatibility matrix is documented for: JEI, REI, Sodium/Embeddium, Iris/Oculus, Jade, Mouse Tweaks, Quark, Inventory HUD+ — manually tested at GUI scales 1 + 2
- [ ] **REL-05**: Built jar smoke-tested on a clean Forge 47.4.18 dedicated server (not dev environment) before first tagged release

## v2 Requirements

### Streaming + UX

- **V2-UX-01**: Streamed token-by-token responses in the chat UI
- **V2-UX-02**: Optional persistent per-player conversation history with configurable retention

### Advanced Safety

- **V2-SAFE-01**: Per-server daily token cap (hard ceiling across all players)
- **V2-SAFE-02**: Max tokens per reply config
- **V2-SAFE-03**: Admin-configurable per-player monthly cap

### Advanced Grounding

- **V2-GR-01**: On-disk cache of scraped mod docs with TTL and manual invalidation
- **V2-GR-02**: Local embeddings index of mod docs for faster + cheaper retrieval
- **V2-GR-03**: "What am I looking at" — block/entity inspector driven by crosshair target

### Platform Expansion

- **V2-PLAT-01**: Fabric port
- **V2-PLAT-02**: NeoForge port
- **V2-PLAT-03**: Minecraft 1.21+ port

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| AI reads public chat for context | Surveillance + cost weaponization; privacy risk; deliberate anti-feature |
| AI executes in-game commands | Catastrophic griefing + prompt-injection blast radius; deliberate anti-feature |
| Auto-trigger on chat keywords | Cost weaponization; users must explicitly opt in per request |
| Default keybind for chat UI | Forge keybind ecosystem is saturated; inventory button is the single discoverable entry |
| Embedded fallback API key | Even a "demo" key invites abuse and cost; zero-key policy is non-negotiable |
| Voice / TTS | Scope + storage + cost; v1 is text-only |
| Persistent conversation history | Privacy surface + storage complexity; v2+ |
| Local embeddings / doc cache | Complexity; every query fetches fresh in v1 |
| Fabric / NeoForge support | Forge 1.20.1 only for v1; port is v2+ |
| Multiple Minecraft versions | 1.20.1 only; porting is v2+ |
| Per-server daily token cap | v1 uses per-player rate limit only |
| Streaming responses | Deferred to polish/v2 |
| Direct-message API key paste via chat | API keys must enter via `forgebook-server.toml` only — clients never hold keys |

## Traceability

Each v1 requirement maps to exactly one phase. See ROADMAP.md for phase goals and success criteria.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SCAF-01 | Phase 1 | Pending |
| SCAF-02 | Phase 1 | Pending |
| SCAF-03 | Phase 1 | Pending |
| SCAF-04 | Phase 1 | Pending |
| SCAF-05 | Phase 1 | Pending |
| SCAF-06 | Phase 1 | Pending |
| SCAF-07 | Phase 1 | Pending |
| SCAF-08 | Phase 1 | Pending |
| CFG-01 | Phase 1 | Pending |
| CFG-02 | Phase 1 | Pending |
| CFG-03 | Phase 1 | Pending |
| CFG-04 | Phase 1 | Pending |
| CFG-05 | Phase 1 | Pending |
| CFG-06 | Phase 1 | Pending |
| CFG-07 | Phase 1 | Pending |
| NET-01 | Phase 1 | Pending |
| NET-02 | Phase 1 | Pending |
| NET-03 | Phase 1 | Pending |
| NET-04 | Phase 1 | Pending |
| NET-05 | Phase 1 | Pending |
| NET-06 | Phase 1 | Pending |
| AI-01 | Phase 2 | Pending |
| AI-02 | Phase 2 | Pending |
| AI-03 | Phase 2 | Pending |
| AI-04 | Phase 2 | Pending |
| AI-05 | Phase 2 | Pending |
| AI-06 | Phase 2 | Pending |
| AI-07 | Phase 2 | Pending |
| AI-08 | Phase 2 | Pending |
| TOOL-01 | Phase 2 | Pending |
| TOOL-02 | Phase 2 | Pending |
| TOOL-03 | Phase 2 | Pending |
| TOOL-04 | Phase 2 | Pending |
| TOOL-05 | Phase 2 | Pending |
| TOOL-06 | Phase 2 | Pending |
| TOOL-07 | Phase 2 | Pending |
| CF-01 | Phase 2 | Pending |
| CF-02 | Phase 2 | Pending |
| CF-03 | Phase 2 | Pending |
| CMD-01 | Phase 3 | Pending |
| CMD-02 | Phase 3 | Pending |
| CMD-03 | Phase 3 | Pending |
| CMD-04 | Phase 3 | Pending |
| CMD-05 | Phase 3 | Pending |
| CMD-06 | Phase 3 | Pending |
| CMD-07 | Phase 3 | Pending |
| SAFE-01 | Phase 3 | Pending |
| SAFE-02 | Phase 3 | Pending |
| SAFE-03 | Phase 3 | Pending |
| SAFE-04 | Phase 3 | Pending |
| SAFE-05 | Phase 3 | Pending |
| SAFE-06 | Phase 3 | Pending |
| UI-01 | Phase 4 | Pending |
| UI-02 | Phase 4 | Pending |
| UI-03 | Phase 4 | Pending |
| UI-04 | Phase 4 | Pending |
| UI-05 | Phase 4 | Pending |
| UI-06 | Phase 4 | Pending |
| UI-07 | Phase 4 | Pending |
| UI-08 | Phase 4 | Pending |
| REL-01 | Phase 5 | Pending |
| REL-02 | Phase 5 | Pending |
| REL-03 | Phase 5 | Pending |
| REL-04 | Phase 5 | Pending |
| REL-05 | Phase 5 | Pending |

**Coverage:**
- v1 requirements: 59 total
- Mapped to phases: 59
- Unmapped: 0

---
*Requirements defined: 2026-04-14*
*Last updated: 2026-04-14 after roadmap creation*
