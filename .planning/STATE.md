---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
status: planning
last_updated: "2026-04-16T08:01:47.381Z"
progress:
  total_phases: 5
  completed_phases: 2
  total_plans: 12
  completed_plans: 12
  percent: 100
---

# ForgeBook — Project State

**Last updated:** 2026-04-16 after Phase 2 transition
**Status:** Ready to plan Phase 3

## Project Reference

See: .planning/PROJECT.md (updated 2026-04-16)

**Core value:** A player holding an unfamiliar item from an unfamiliar mod gets a grounded, trustworthy answer about what it does and how to use it — without alt-tabbing to a wiki.

**Current focus:** Phase 3 — Command Surface & Safety Controls

## Current Position

Phase: 3 (command-surface-&-safety-controls) — READY TO PLAN
Plan: Not started

- **Phase:** 3
- **Plan:** Not started
- **Status:** Awaiting `/gsd-discuss-phase 3` or `/gsd-plan-phase 3`
- **Progress:** [████░░░░░░] 40% (2/5 phases complete — Phase 1 Foundations, Phase 2 AI Engine)

## Mode & Configuration

- **Mode:** YOLO
- **Granularity:** coarse (3-5 phases, 1-3 plans per phase)
- **Parallel plans:** enabled
- **Research:** complete (see `.planning/research/`)
- **Platform:** Forge 1.20.1-47.4.18, Java 17, Gradle 8.1.1 + ForgeGradle 6.0.x
- **AI provider (default):** Anthropic Claude (hand-rolled via `java.net.http.HttpClient` + Gson)
- **AI providers (stubs):** OpenAI, Ollama

## Performance Metrics

| Metric | Value |
|--------|-------|
| Phases planned | 5 |
| Phases complete | 2 |
| Requirements v1 | 59 |
| Requirements mapped | 59 (100%) |
| Plans executed | 12 |

## Accumulated Context

### Decisions (from PROJECT.md)

- Anthropic Claude as v1 default AI provider; pluggable interface with OpenAI + Ollama stubs compilable from day one.
- Hybrid agent strategy: tool-using loop for chat UI, RAG single-shot for `/forgebook item`.
- OP-only by default; when opened, per-player rate limit via in-memory token bucket; OPs bypass.
- Chat context per-session only — cleared on UI close or disconnect; no on-disk persistence.
- In-inventory button (no default keybind) for chat UI entry.
- `/forgebook` as single top-level command with `item`, `ask`, `reload`, `disable`, `enable`, `stats` subcommands.
- Held item is default target for `/forgebook item`.
- Client + server both required — reduces optional-networking complexity.
- Missing-docs → web search fallback.
- CurseForge integration optional; enriches system prompt when configured.
- Mod ID: `forgebook`; package: `com.forgebook`.
- License: MIT (default).
- Forge 1.20.1-47.4.18 is user-pinned (47.4.10 is the documented "Recommended" build — both are acceptable).

### Architecture Invariants (from research)

- **Client classloader firewall:** `net.minecraft.client.*` may only be imported under `com.forgebook.client.*`; `DistExecutor.safeRunWhenOn(Dist.CLIENT, ...)` is the only client-entry path.
- **SERVER-tier secrets:** every API key, provider setting, op/rate setting lives in `forgebook-server.toml`; `CLIENT` tier holds only `enable_chat_interface`.
- **Off-tick HTTP:** all HTTP via `HttpClient.sendAsync` on a dedicated `aiExecutor`; only final state mutation in `ctx.enqueueWork`.
- **SafeHttpFetcher:** single egress chokepoint with https-only scheme allowlist, private-IP block, redirect revalidation (≤3 hops), 1 MB cap, content-type allowlist, 15s timeout.
- **Agent caps:** hard 6-step cap on `AgentLoop`; retries max 3 on 5xx with exponential backoff (cap 30s); circuit breaker trips after 5 consecutive failures, cools 5 minutes.
- **Prompt-injection framing:** every fetched document wrapped in `<mod_doc trust="untrusted">...</mod_doc>` in the next model turn.
- **SimpleChannel in 1.20.1:** use `NetworkRegistry.newSimpleChannel(...)` — NOT the `ChannelBuilder` fluent API (that's NeoForge / 1.20.2+).
- **`IModInfo.getModURL()`** is correct — `getDisplayURL()` does not exist.

### TODOs

- None yet (populated during phase planning).

### Blockers

- None.

### Research Flags (carry into plan-phase)

- **Phase 1:** Exact `ScreenEvent.Init.Post` button-insertion API for Forge 47.x (confirm `event.addListener` or fall back to `renderables` list).
- **Phase 2:** Pin current `anthropic-version` header value; confirm latest Claude tool-use JSON shape.
- **Phase 2:** Choose jsoup readability heuristic and web-search backend (DuckDuckGo HTML / Brave / Tavily).
- **Phase 2:** Verify CurseForge REST rate-limit behavior empirically.
- **Phase 4:** Spike — `AbstractContainerScreen<InventoryMenu>` subclass vs standalone `Screen`.

## Session Continuity

**Last session end:** 2026-04-16 (Phase 2 re-verified after wiring fix 96b1dc6; 4/5 SC verified in code, SC-1 awaits live Claude call tracked in 02-HUMAN-UAT.md).

**Stopped at:** Phase 2 complete, ready to plan Phase 3.
**Resume file:** None.

**Next action:** Run `/gsd-discuss-phase 3` to gather context before planning Phase 3 (Command Surface & Safety Controls), or jump to `/gsd-plan-phase 3`.

**Resume hint:** Phase 3 is where the AI pipeline (Phase 2) becomes player-reachable. It adds `/forgebook item|ask|reload|disable|enable|stats` with OP gating (`hasPermission(2)`), per-UUID token-bucket rate limit, global kill-switch, and the fixed error taxonomy (`TRANSPORT`/`RATE_LIMITED`/`FORBIDDEN`/`PROVIDER`/`DISABLED`). The RAG single-shot path for `/forgebook item` is distinct from Phase 2's tool-using loop — fetch `getModURL()` → scraper → one Claude call, no agent loop.

---
*State initialized: 2026-04-14; Phase 2 transition: 2026-04-16*
