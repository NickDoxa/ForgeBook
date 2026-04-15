# Project Research Summary — ForgeBook

**Project:** ForgeBook
**Domain:** Minecraft Forge 1.20.1 mod with server-side LLM agent (in-inventory chat UI + grounded `/forgebook item` command + optional CurseForge enrichment)
**Researched:** 2026-04-14
**Confidence:** HIGH on Forge platform mechanics, networking, threading, classloading, and OWASP-style LLM security; MEDIUM on exact provider/SDK choices, HTML extraction library, and CurseForge rate-limit specifics

## Executive Summary

ForgeBook is a server-authoritative Forge 1.20.1 mod whose hard constraints — secret API keys must never reach a client, every outbound HTTP call must run off the server tick thread, and the answer surface must remain trustworthy in the face of adversarial mod docs — dictate the architecture more than the AI features themselves do. The right shape is well-established: a single `SimpleChannel` carrying request/response/error packets between a client `ChatScreen` (opened via a `ScreenEvent.Init.Post`-injected button on `InventoryScreen`) and a server-side `AiDispatcher` that authorizes, rate-limits, and dispatches work onto a dedicated `Executor`, with a narrow `AiProvider` interface (Claude in v1; OpenAI/Ollama stubs from day one) and a `Tool` registry resolved at server start.

Stack-wise the prescription is opinionated and minimal: **Forge 1.20.1-47.4.18 (user-specified; 47.4.10 is the documented "Recommended" build — acceptable either way), Java 17, Gradle 8.1.1 + ForgeGradle 6.0.x, Parchment `2023.09.03-1.20.1` mappings, JDK `java.net.http.HttpClient`, bundled Gson, jsoup (shadow-relocated) for HTML extraction**. Do **not** pull in the official Anthropic Java SDK — it drags OkHttp/Jackson/Kotlin and forces Jar-in-Jar relocation; Claude's Messages API is ~150 lines of hand-rolled HttpClient + DTOs. Also: **`IModInfo.getModURL()` is the correct accessor**, not `getDisplayURL()` — the latter method does not exist on the interface. PROJECT.md has been corrected.

The dominant risks are not technical novelty but well-known classes of failure: blocking the tick thread with synchronous HTTP (CRITICAL), API key leakage via `COMMON` config or unscrubbed logs (CRITICAL), unbounded tool/retry loops draining the server owner's API budget (CRITICAL), prompt injection and SSRF via attacker-controlled mod `displayURL` values (CRITICAL), and dedicated-server `NoClassDefFoundError` from client classes leaking into common code (CRITICAL). All preventable with day-one discipline — package isolation, `SERVER`-only secrets, hard step caps, scheme/IP allowlists, and `enqueueWork` + `*Async(executor)` rigor — but expensive to retrofit, so they belong in the earliest phases.

## Key Findings

### Recommended Stack

**Core technologies:**
- **Minecraft Forge 1.20.1-47.4.18** (user choice; Forge's "Recommended" for 1.20.1 is 47.4.10 — both work, noted for visibility)
- **Java 17 (Temurin)** — required by 1.20.1
- **Gradle 8.1.1 + ForgeGradle `[6.0.16,6.2)`** — MDK defaults
- **Parchment `2023.09.03-1.20.1`** — readable parameter names
- **`java.net.http.HttpClient` (JDK 17 built-in)** — async HTTP/2; zero shadowing risk
- **Gson 2.10 (bundled with MC)** — already on classpath
- **jsoup 1.17.x (shadow-relocated to `com.forgebook.shadow.jsoup`)** — sole third-party runtime dep; HTML readability extraction
- **Forge `SimpleChannel` via `NetworkRegistry.newSimpleChannel(...)`** — 1.20.1 API; NOT the `ChannelBuilder` fluent API (that's NeoForge / 1.20.2+)
- **`ForgeConfigSpec` SERVER tier for all secrets** — never `COMMON` or `CLIENT`

**Hand-roll, do not import:** `anthropic-java` SDK, OpenAI SDK, OkHttp, Apache HttpClient, Jackson.

### Expected Features

**Must have (table stakes):**
- Inventory-injected button opening a docked `ChatScreen` (no default keybind)
- `/forgebook item` with held-item default and explicit `<modid:item_id>` arg
- Source URL citation on every answer (trust + auditability)
- OP-only gate by default + per-player rate limit when opened up
- Server-side API key isolation via custom packet channel
- Loading indicator + visible error surfacing in chat UI
- Per-session conversation isolation
- Graceful fallback when a mod has no `getModURL()` value
- Kill-switch admin command (`/forgebook disable`)
- Request logging with player UUID + token estimate + latency

**Should have (differentiators):**
- Tool-using agent for chat (multi-turn) + RAG single-shot for `/forgebook item`
- Pluggable `AiProvider` abstraction
- CurseForge modpack context injection when configured
- Web-search fallback for sparse-metadata mods
- `/forgebook stats` admin command for budget visibility

**Defer (v2+):** Streaming responses, persistent history, local embeddings, voice/TTS, Fabric/NeoForge ports, per-server daily token cap, block-look extension.

**Hard anti-features:** AI reads public chat, AI executes in-game commands, auto-trigger on chat keywords, default keybind, embedded fallback API key.

### Architecture Approach

Single-jar, single source set, with a **package-isolation classloading firewall**: anything touching `net.minecraft.client.*` lives under `com.forgebook.client.*` and is reached only via `DistExecutor.safeRunWhenOn(Dist.CLIENT, …)`. All AI/HTTP work runs on a dedicated `Executor`; final reply send and game-state mutations hop back to the server main thread via `server.execute(…)` or `ctx.enqueueWork(…)`. The `AiProvider` interface is the only Claude-aware seam; the `AgentLoop` drives tool-use iteration with a hard step cap. Every outbound fetch goes through a single `SafeHttpFetcher` enforcing scheme allowlist, private-IP block, redirect re-validation, body size cap, and timeout.

**Major components:**
1. `ForgeBookMod` + `ForgebookConfig` — bootstrap + SERVER/CLIENT specs
2. `ForgebookNetwork` + packet types — `SimpleChannel "forgebook:main"`
3. `AiDispatcher` (server singleton) — authorize → rate-limit → enqueue
4. `AiProvider` + `ClaudeProvider` — narrow interface; OpenAI/Ollama stubs
5. `AgentLoop` + `ToolRegistry` + `Tool`s — `ListInstalledMods`, `FetchModDocsPage`, `WebSearch`, `GetModpackContext`
6. `SafeHttpFetcher` + `ModDocsScraper` (jsoup)
7. `CurseForgeClient` + `ModpackContext` — single startup fetch
8. `ForgebookCommand` + subcommands — `item`, `ask`, `reload`, `disable`, `enable`, `stats`
9. `com.forgebook.client.*` — `InventoryButtonInjector`, `ChatScreen`, `ClientChatSession`
10. `RateLimiter` — per-UUID token bucket, OP bypass

### Critical Pitfalls (Top 5)

1. **Blocking the server tick with sync HTTP** — every outbound call via `HttpClient.sendAsync` on dedicated `Executor`; only final state mutation in `enqueueWork`.
2. **API key leakage** — `SERVER` spec only; `record ApiKey` with redacting `toString()`; header scrubbing in logs; CI regex for `sk-ant-`/`sk-proj-`.
3. **Cost blow-up** — hard 6-step agent cap; max 3 retries with exponential backoff; circuit breaker on 5 consecutive failures; pre-render system prompt; rate limiter counts initiated requests.
4. **Prompt injection + SSRF** — wrap fetched content with "treat as data" framing; `SafeHttpFetcher` enforces https-only, private-IP block, redirect revalidation (3 hops), 1MB cap, content-type filter.
5. **Client classloading leaks** — strict `com.forgebook.client.*` isolation; enter via `DistExecutor.safeRunWhenOn`; `runServer` in CI.

Also HIGH: packet thread/side/size discipline, mod-compat (JEI/Sodium/Iris/Mouse Tweaks/Quark), hallucination (always cite), CurseForge TOS (single startup fetch), config migration (`config_version = 1` day one), 404/Cloudflare loop detection, dev-vs-prod jar parity.

## Implications for Roadmap

10-phase build order (coarse granularity will likely compress to 5–6 GSD phases).

1. **Skeleton + Forge bootstrap** — package isolation firewall, toolchain, license, logo slot
2. **Config** — SERVER secrets, `ApiKey` redactor, `config_version`, atomic reload
3. **Network + `SafeHttpFetcher`** — SimpleChannel echo + uniform egress policy
4. **AI provider + single-turn loop** — Claude HttpClient + DTOs; OpenAI/Ollama stubs
5. **Tool layer + remaining tools** — step cap day one; prompt-injection framing
6. **CurseForge integration** — single startup fetch; graceful degradation
7. **Command path** — `/forgebook item|ask|reload|disable|enable|stats`
8. **Client UI** — button injector + docked `ChatScreen`
9. **Rate limit + OP gate + error UX** — token bucket + circuit breaker + inline errors
10. **Polish + release prep** — lang/logo, compat matrix, prod-jar smoke

### Phase Ordering Rationale

- Skeleton → Config → Network first: CRITICAL pitfalls cluster there, cheapest to avoid as foundation.
- Provider before tools: tool-iterating loop is a superset of single-turn; prove Claude wire-format first.
- Command path before Client UI: command exercises full pipeline without GUI dependency.
- Rate limiting after UX surfaces: error-bubble rendering and command feedback land together.

### Research Flags (deeper dig during phase planning)

- **Phase 1:** Exact `ScreenEvent.Init.Post` button-insertion API for 47.x; `NetworkRegistry` path.
- **Phase 4:** Current `anthropic-version` header; tool-use JSON shape.
- **Phase 5:** jsoup readability heuristic; web-search backend choice (DuckDuckGo HTML / Brave / Tavily).
- **Phase 6:** CurseForge rate-limit empirical verification.
- **Phase 8:** `AbstractContainerScreen<InventoryMenu>` subclass vs standalone `Screen` — quick spike.

## Confidence Assessment

| Area | Confidence |
|------|------------|
| Stack | HIGH |
| Features | MEDIUM-HIGH |
| Architecture | HIGH |
| Pitfalls | HIGH |

**Overall: HIGH.** Open questions scoped and tied to specific phases.

## Sources

- files.minecraftforge.net (1.20.1 downloads)
- docs.minecraftforge.net (Getting Started, SimpleImpl, Screens, Configuration, Sides, mods.toml)
- ForgeSPI — `IModInfo.java` source (confirmed `getModURL()`)
- parchmentmc.org (Getting Started)
- Anthropic Messages API docs; Agent SDK overview
- CurseForge for Studios REST API docs
- OWASP LLM Top 10 (2024); OWASP SSRF Prevention Cheat Sheet
- Prior-art mods: JEI, Wiki Lookup, MCChatGPT, AI Chat Mod, CreatureChat, Item Descriptions, Patchouli

---
*Research completed: 2026-04-14*
*Ready for requirements + roadmap: yes*
