# ForgeBook

## What This Is

ForgeBook is a Minecraft Forge 1.20.1 mod that bridges AI with the in-game experience so players can understand the mods they're using. It ships two player-facing surfaces: a chat UI docked inside the inventory screen that lets players ask questions of an AI agent with internet and mod-list awareness, and a `/forgebook item` command that answers "what can I do with this?" for any mod-added item — grounded in the mod's documentation URL (and, when configured, the modpack's CurseForge metadata).

## Core Value

A player holding an unfamiliar item from an unfamiliar mod gets a grounded, trustworthy answer about what it does and how to use it — without alt-tabbing to a wiki.

## Requirements

### Validated

- ✓ Forge 1.20.1 / Forge 47.4.18 mod skeleton with common/client distribution, mod ID `forgebook`, Java 17 — Phase 1
- ✓ Forge config file exposing `enable_chat_interface`, `ai_provider`, `ai_api_key`, `ai_model`, `curseforge_modpack_id`, `curseforge_api_key`, `op_only`, `rate_limit_per_minute`, `enable_web_search` (plus `max_tokens`, `web_search_provider`, `web_search_api_key` added in Phase 2) — Phase 1 / extended Phase 2
- ✓ Pluggable AI provider abstraction; Anthropic Claude adapter ships (hand-rolled `HttpClient` + Gson, `anthropic-version=2023-06-01`); OpenAI + Ollama adapters stubbed as `NOT_IMPLEMENTED` — Phase 2
- ✓ Tool-using agent backs chat flow: `list_installed_mods`, `fetch_mod_docs_page(modid)`, `web_search(query)`, `get_modpack_context()` — Phase 2 (tool registry, `AgentLoop` 6-iter cap, `<mod_doc trust="untrusted">` framing)
- ✓ Missing-docs fallback: empty `getModURL()` → `FetchModDocsPageTool` `NO_DOCS_URL` → `WebSearchTool` (DuckDuckGo default, Brave adapter) → cited answer — Phase 2 (SC-5 proven end-to-end via `AgentLoopE2ETest`)
- ✓ CurseForge integration: `curseforge_modpack_id` → `CurseForgeClient.fetch` at `ServerStartedEvent`, 500-char summary cap, graceful degradation when unconfigured — Phase 2
- ✓ Client + server both require the mod installed — Phase 1
- ✓ Networking: `SimpleChannel` `forgebook:main` carrying `ChatRequestPacket` / `ChatResponsePacket` / `ChatErrorPacket`; API key never leaves the server (`AiDispatcher.INSTANCE` server-side only) — Phase 1 / wired to AI in Phase 2

### Active

- [ ] Chat UI rendered adjacent to (left of) the inventory screen, toggled via a button rendered inside the inventory screen (no default keybind)
- [ ] Chat UI uses vanilla Minecraft GUI assets or public-domain/permissively-licensed assets only — no custom-designed textures required for v1 apart from the user-supplied logo
- [ ] `/forgebook item` command: no args targets the main-hand item; optional `<modid:item_id>` arg targets any registered item. Uses RAG-style single-shot: fetch the item's source mod's `displayURL`/docs URL, pass relevant sections to the model, return answer
- [ ] OP-only gate by default (`op_only = true`); when disabled, a per-player rate limit (`rate_limit_per_minute`) applies
- [ ] Chat context is per-session only — cleared on UI close or disconnect; no on-disk conversation persistence
- [ ] Open source license (default: MIT unless the user opts for LGPL-3.0 later)

### Out of Scope

- Voice input / TTS output — text-only for v1
- Persistent conversation history across sessions — explicitly deferred to keep scope + privacy surface small
- Fine-tuning or local embeddings indexing of mod docs — docs are fetched on demand
- Fabric / Quilt / NeoForge support — 1.20.1 Forge only for v1
- Multiple Minecraft versions — 1.20.1 only; porting deferred
- Keybind-based UI toggle — v1 uses an in-inventory button instead; users who want one can rebind through the Controls menu only if we later register an unbound key (not a v1 goal)
- Per-server daily token cap — rate-limiting is per-player only in v1 (user can add later if costs surprise)
- Max-tokens-per-reply config — bounded implicitly by model defaults in v1
- Auto-update of mod docs cache — no caching layer in v1; each query fetches fresh
- Streaming responses — v1 returns complete replies; streaming is a polish-phase extension

## Context

- **Minecraft version:** 1.20.1
- **Forge version:** 47.4.18 (targets Forge MDK for 1.20.1)
- **Language/runtime:** Java 17 (required by 1.20.1)
- **Build:** Gradle with ForgeGradle 6.x
- **Target audience:** Modpack players (especially large kitchen-sink packs) who routinely encounter unfamiliar items and want lower friction than alt-tabbing to wikis
- **AI landscape:** Provider-agnostic abstraction matters — API pricing and model availability shift quarterly; Anthropic Claude chosen as the v1 default for strong tool-use behavior at Haiku price point
- **Mod metadata:** Forge's `ModList` / `IModInfo` exposes `getModURL()` (backed by the `displayURL = "..."` field in each mod's `mods.toml`), which many mods populate with wiki or CurseForge links — primary grounding source. Roughly ~30% of mods leave this empty, hence the web-search fallback
- **CurseForge API:** Public REST API; modpack ID → pack metadata + mod list. Optional because self-hosted/handcrafted servers don't always map to a CF pack
- **Security:** AI API keys live in the server-side config only. Client never sees the key; client sends chat requests to server via a dedicated packet channel; server forwards to AI provider
- **Logo:** User is designing a logo separately — placement location will be flagged during scaffold phase (expected: `src/main/resources/assets/forgebook/textures/gui/logo.png` and `src/main/resources/logo.png` for the mods.toml `logoFile`)

## Constraints

- **Tech stack**: Forge 1.20.1, Forge 47.4.18, Java 17, Gradle + ForgeGradle 6.x — locked by platform requirement
- **Distribution**: Client + server require mod installed — simplifies networking and UI determinism
- **Compatibility**: Must not conflict with common QoL mods (JEI/REI, Jade, etc.) — avoid global keybinds, render chat UI as an overlay screen not a replacement
- **Secrets**: AI API key and CurseForge API key must never be sent to clients — all outbound AI requests originate from the server process
- **Cost**: Default guardrails must prevent a single malicious player from draining the server owner's API budget — OP-only by default + per-player rate limit when opened up
- **Asset sourcing**: GUI assets must be either vanilla-reused or permissively licensed public assets — no assets scraped from copyrighted sources
- **Licensing**: Open source — MIT default; user can re-license before first tagged release

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Anthropic Claude as default AI provider (pluggable) | Strong tool-use, competitive Haiku pricing; abstraction keeps OpenAI/Ollama viable | ✓ Shipped — Phase 2 (ClaudeProvider v1/messages, `claude-haiku-4-5` default; OpenAi/Ollama `NOT_IMPLEMENTED`) |
| Hybrid agent strategy: tool-using for chat UI, RAG single-shot for `/forgebook item` | Chat is multi-turn and benefits from tools; item lookup is a bounded single question — cheaper as one-shot | Tool-using half ✓ Phase 2 (AgentLoop); RAG single-shot pending Phase 3 |
| OP-only by default, configurable off with per-player rate limit | Protects server owner's API budget from abuse; server owners can open up with knobs | — Pending Phase 3 |
| Chat context per-session only | Lowest storage + privacy footprint; matches "quick question" UX | — Pending Phase 4 |
| In-inventory button to toggle chat UI (no default keybind) | Avoids conflicts with other mods' keybinds; discoverable inside existing inventory flow | — Pending Phase 4 |
| `/forgebook` as single top-level command with subcommands (`item`, `ask`, `reload`) | Avoids polluting command namespace; extensible | `reload` ✓ Phase 2 (ForgebookReloadCommand); `item`/`ask` pending Phase 3 |
| Held item as default target for `/forgebook item` | Minimizes typing for the common case; explicit `<modid:item_id>` arg still supported | — Pending Phase 3 |
| Client + server both required | Removes optional-networking branching; simpler v1 | ✓ Shipped — Phase 1 |
| Missing-docs → web search fallback | Best UX for sparse-metadata mods; user accepts higher cost | ✓ Shipped — Phase 2 (SC-5 E2E) |
| CurseForge integration optional, enriches prompt when present | Many servers don't run CF packs; must degrade gracefully | ✓ Shipped — Phase 2 (CurseForgeClient + ModpackContextCache + SystemPromptBuilder) |
| Mod ID: `forgebook`, package: `com.forgebook` | Matches project name; short, collision-unlikely | ✓ Shipped — Phase 1 |
| License: MIT (default) | Permissive; allows modpack inclusion without friction | — Pending Phase 5 |
| Prompt-injection framing via `<mod_doc trust="untrusted">...</mod_doc>` with 8000-char cap + visible truncation marker (D-14) | Fetched third-party docs are untrusted input — must be framed so the model treats them as data, not instructions | ✓ Shipped — Phase 2 (PromptFraming) |
| `AgentLoop` hard 6-iter cap, 5xx retry (3 tries, 30s cap, exp backoff + retry-after), circuit breaker (5-fail / 5-min cooldown) | Cost protection + reliability against transient provider failures | ✓ Shipped — Phase 2 (RetryPolicy + CircuitBreaker) |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-04-16 after Phase 2 (AI Engine & Grounding)*
