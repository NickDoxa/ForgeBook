# ForgeBook

> **AI-powered item docs inside Minecraft.** Hold an unfamiliar item, ask what it does, get a cited answer grounded in the mod's own documentation — no alt-tab, no wiki hunting.

<p align="center">
  <img src="src/main/resources/logo.png" alt="ForgeBook logo" width="128" />
</p>

<p align="center">
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT License"></a>
  <img src="https://img.shields.io/badge/minecraft-1.20.1-green.svg" alt="Minecraft 1.20.1">
  <img src="https://img.shields.io/badge/forge-47.4.18%2B-orange.svg" alt="Forge 47.4.18+">
  <img src="https://img.shields.io/badge/java-17-red.svg" alt="Java 17">
  <img src="https://img.shields.io/badge/AI-Claude-8A2BE2.svg" alt="Claude-powered">
</p>

ForgeBook is a Minecraft Forge 1.20.1 mod that puts an AI agent inside your inventory screen. When you hold an item from some mod you don't recognize, ForgeBook reads that mod's own documentation URL (or, if configured, the modpack's CurseForge metadata), grounds an LLM on that source, and hands you a trustworthy, cited answer about what the item does and how to use it.

The design goal: **zero wiki tabs open during gameplay.**

## Table of Contents

- [What it does](#what-it-does)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [How it works](#how-it-works)
- [Security posture](#security-posture)
- [Compatibility](#compatibility)
- [Building from source](#building-from-source)
- [Contributing](#contributing)
- [Roadmap](#roadmap)
- [FAQ](#faq)
- [Credits](#credits)
- [License](#license)
- [Author](#author)

## What it does

Two player-facing surfaces, same backend:

### 1. Inventory chat UI

Open your inventory. Click the small **"Ask ForgeBook"** button docked next to the vanilla slots. A chat panel slides in alongside (not over) the inventory. Type a question — about the mods you have installed, about a specific item, about a mechanic that isn't clicking — and the agent answers, with source URLs cited inline.

- Docked alongside inventory — doesn't replace or hide vanilla UI
- Multi-turn within a session; conversation clears on close or disconnect
- Loading + error states are inline (no toasts, no crashes)
- Scale-aware: renders cleanly at GUI scales 1 through 4

### 2. Slash commands

Skip the UI entirely and stay on the command line:

```
/forgebook item                           # ask about the item in your main hand
/forgebook item <modid:item_id>           # ask about a specific item
/forgebook ask <message…>                 # one-shot chat reply
```

Plus OP-only admin:

```
/forgebook reload            # atomic config reload
/forgebook disable           # kill switch (blocks all new AI requests)
/forgebook enable            # re-enable after disable
/forgebook stats             # per-player + aggregate usage
```

### Example

```
[Player] /forgebook item
(holding: Create:creative_motor)

ForgeBook: A Creative Motor is an admin/creative-mode block from the Create
mod that provides infinite rotational force (256 SU) at a configurable RPM.
Right-click to set the speed. Unlike normal motors, it requires no fuel or
input — it's intended for testing contraptions or creative-mode builds.

Source: https://create.fandom.com/wiki/Creative_Motor
```

## Quick start

**Players** (client or single-player):

1. Install [Forge 1.20.1-47.4.18+](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html).
2. Drop `forgebook-1.0.0.jar` in your `mods/` folder.
3. Launch. Open inventory → click the **Ask** button.

> On multiplayer servers, the server owner must install the same jar and set an API key. ForgeBook can't run client-only — all AI calls originate from the server.

**Server owners:** see [docs/BUILD-AND-INSTALL.md](docs/BUILD-AND-INSTALL.md) for the full server setup guide including API key configuration, file permissions, and rate-limit tuning.

## Configuration

### Server (`config/forgebook-server.toml`) — contains secrets

| Field | Type | Default | Purpose |
|---|---|---|---|
| `ai_provider` | enum | `"anthropic"` | `anthropic`, `openai`, or `ollama`. Only `anthropic` is implemented in v1; the others are selectable stubs. |
| `ai_api_key` | string | `""` | Your Anthropic API key. **Secret.** |
| `ai_model` | string | `"claude-haiku-4-5"` | Anthropic model ID. |
| `max_tokens` | int | `1024` | Per-response output cap. |
| `curseforge_modpack_id` | string | `""` | Optional CurseForge project ID for modpack enrichment. |
| `curseforge_api_key` | string | `""` | Optional CurseForge API key. **Secret.** |
| `op_only` | bool | `true` | When true, only OPs (permission level 2+) can use ForgeBook. |
| `rate_limit_per_minute` | int | `5` | Per-player request cap per minute. OPs bypass. |
| `enable_web_search` | bool | `false` | Allow the agent to invoke the web search tool. |
| `web_search_provider` | enum | `"duckduckgo_html"` | `duckduckgo_html` or `brave`. |
| `web_search_api_key` | string | `""` | Only needed for `brave`. |

Edit, save, then as an OP run `/forgebook reload` — no server restart required.

### Client (`config/forgebook-client.toml`) — no secrets

| Field | Type | Default | Purpose |
|---|---|---|---|
| `enable_chat_interface` | bool | `true` | When false, the inventory button is not injected. |

## How it works

```
 ┌────────── CLIENT ─────────────┐        ┌────────── SERVER ───────────────────┐
 │                               │        │                                     │
 │  InventoryButtonInjector      │        │  ChatRequestHandler                 │
 │           ↓                   │        │       ↓                             │
 │  ChatScreen / ChatPanelWidget │  SimpleChannel │                             │
 │           ↓                   │ ─────packets──▶│  Authorizer                 │
 │  ClientChatSession            │  (request/resp │  (op-gate, rate-limit,      │
 │           ↓                   │  /error, UUID) │   kill-switch)              │
 │  ForgebookNetwork.CHANNEL     │                │       ↓                     │
 │  .sendToServer(…)             │                │  AiDispatcher               │
 │                               │                │       ↓                     │
 │  ❌ no api keys               │                │  AgentLoop ┌─→ Claude API   │
 │  ❌ no provider imports       │                │      ↓     ├─→ ModDocs      │
 │  ❌ no safety imports         │                │   Tools    │   (jsoup scrape)│
 │                               │                │            ├─→ Web search   │
 │                               │                │            └─→ CurseForge   │
 └───────────────────────────────┘                └─────────────────────────────┘
```

**Key architectural invariants:**

- **Client/server firewall.** Nothing in `com.forgebook.client.*` imports AI, safety, or config secrets. Enforced by a package-firewall CI check.
- **Server-only secrets.** All HTTP calls to Anthropic, CurseForge, and web search happen server-side via `java.net.http.HttpClient`. The client never sees a key.
- **SSRF-safe fetches.** Mod documentation URLs are fetched through `SafeHttpFetcher`, which rejects `http://`, private-IP, loopback, and oversized responses before a byte is read.
- **Pluggable providers.** `AiProvider` is an interface; `ClaudeProvider` is the default, with `OpenAiProvider` + `OllamaProvider` as selectable stubs ready for future contributions.
- **Grounded-by-default agent loop.** The tool registry exposes `ListInstalledMods`, `FetchModDocsPage`, `WebSearch`, and `GetModpackContext`. Fetched docs are framed with `<mod_doc trust="untrusted">…</mod_doc>` so the model treats them as context, not instructions.

### Source layout

```
src/main/java/com/forgebook/
├── ai/                  # AgentLoop, AiDispatcher, provider/, dto/
├── client/              # session/, ui/ (Screen, widgets, layout math)
├── command/             # Brigadier /forgebook tree + subcommands
├── config/              # ForgebookServerConfig, ForgebookClientConfig, ConfigHolder
├── integration/         # scraper/ (jsoup), websearch/ (DDG + Brave adapters)
├── network/             # SimpleChannel, packets, chunking, handlers
├── safety/              # Authorizer, RateLimiter, KillSwitch, StatsAccumulator
├── tool/                # Tool interface + 4 impls
└── util/                # Logging, SafeHttpFetcher, misc helpers
```

~6,300 LOC main, ~6,600 LOC test. Test suite runs via `./gradlew test`.

## Security posture

ForgeBook is built around a single hard claim: **your API keys never leave the server process.**

- **Server-only egress.** All outbound AI + CurseForge + web search calls originate on the server.
- **Package firewall.** `com.forgebook.client.*` and `com.forgebook.client.session.*` cannot import `com.forgebook.ai.*`, `com.forgebook.safety.*`, or `com.forgebook.config.ApiKey`. CI runs a grep-based check on every commit.
- **SSRF guard.** `SafeHttpFetcher` rejects non-HTTPS, private-IP, loopback, and link-local destinations; caps response size at 1 MB; enforces a content-type allowlist; limits redirects to 3; times out at 15 s.
- **Log redaction.** A custom Log4j2 `ApiKeyScrubFilter` scrubs `Authorization`, `x-api-key`, `sk-ant-`, and `sk-proj-` patterns from all log output before it hits disk.
- **Cost guards.** `op_only=true` + per-player token bucket (`rate_limit_per_minute=5`) by default. OPs bypass. All requests are audit-logged to `logs/forgebook-audit.log`.
- **Kill switch.** `/forgebook disable` trips an `AtomicBoolean` that halts all new requests server-wide without needing a restart.
- **File permissions.** `forgebook-server.toml` stores plaintext API keys. Run `chmod 600 config/forgebook-server.toml` on Linux/macOS, or restrict the NTFS ACL on Windows.

## Compatibility

Tested against the common QoL mod cohort — JEI, REI, Embeddium (Sodium fork), Oculus (Iris fork), Jade, Mouse Tweaks, Quark, Inventory HUD+. Full matrix in [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

Design decisions that help compat:

- The inventory button is injected via `ScreenEvent.Init.Post` at a fixed offset from the inventory's `leftPos`/`topPos` — no overlap with vanilla slots, the recipe book toggle, or creative tabs.
- The chat panel is rendered as a sibling `Screen` with parent-render pass-through, not as an `AbstractContainerScreen` replacement.
- No global keybindings. If a keybinding is ever added, it will default to `UNBOUND` per Forge convention.
- No Mixins. All injection uses Forge events.

## Building from source

Requirements: JDK 17, the repo's Gradle wrapper (never a system Gradle).

```bash
git clone https://github.com/NickDoxa/ForgeBook.git
cd ForgeBook
./gradlew build                                       # → build/libs/forgebook-1.0.0.jar
./gradlew test                                        # full test suite
./gradlew runClient                                   # dev-launch client with mod loaded
./gradlew runServer                                   # dev-launch dedicated server
```

Full build, install, and release instructions in [docs/BUILD-AND-INSTALL.md](docs/BUILD-AND-INSTALL.md). Pre-release smoke protocol in [docs/RELEASE-SMOKE.md](docs/RELEASE-SMOKE.md).

## Contributing

Contributions are welcome. Please open an issue before starting significant work so we can sanity-check the design.

**Ground rules:**

- Respect the client/server firewall. PRs that import `com.forgebook.ai.*` from `com.forgebook.client.*` will fail CI.
- Never log, print, or expose API keys. The scrub filter catches known prefixes, but prevention > cleanup.
- New AI providers: implement `AiProvider`, wire through `ProviderFactory`, add a ScriptedAiProvider-style test.
- New tools: implement `Tool`, register in `ToolRegistry`, frame all externally-sourced text with `<mod_doc trust="untrusted">…</mod_doc>` (see `PromptFraming`).
- Follow the existing pure-function test seam pattern (look at `Authorizer.authorize` or `RagItemPipeline.runInternal`) — it's what lets us test Minecraft-entangled logic without booting a client.
- Atomic commits with conventional-commit-style messages (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`, `test:`).

**Good first issues:**

- Implement a real `OpenAiProvider` (currently a stub returning `NOT_IMPLEMENTED`).
- Implement a real `OllamaProvider` for local/self-hosted LLM support.
- Add narrator support to `ChatPanelWidget` (per-message narration instead of panel-as-a-whole).
- Write a `BraveSearchAdapter` fallback for when DuckDuckGo rate-limits.

## Roadmap

**v1.0 (shipped):** Inventory chat UI, slash commands, grounded agent loop, Claude provider, CurseForge enrichment, safety controls (authz, rate-limiting, kill-switch, SSRF guard, log scrubbing), 21+26 i18n keys, compat matrix, release smoke protocol.

**v2 (possible, unscheduled):**

- Streaming responses (`HttpClient` SSE or OkHttp migration)
- Multi-chunk reply assembly for > 32 KB responses
- OpenAI + Ollama real providers
- Configurable button position + in-game config GUI (Cloth Config)
- Per-message narrator entries for accessibility
- Conversation persistence across screen opens (opt-in)
- CI-driven tagged releases

Open an issue to vote on priorities or propose additions.

## FAQ

**Q: Do players need an API key?**
No. Only the server owner needs an Anthropic key. The client never sees it. In single-player, you're effectively running both — same rule applies.

**Q: Does it work server-side-only or client-side-only?**
Neither. Both sides must have the mod. This is a deliberate simplification — packet contracts and UI determinism are easier to reason about when you can assume both ends speak the same protocol.

**Q: Is it expensive?**
Default config is conservative — 5 requests/min per non-OP player, ~1000-token responses, Claude Haiku-class model. Typical cost is pennies per hour of active play per player. Tune `rate_limit_per_minute` and `max_tokens` to taste. Monitor `logs/forgebook-audit.log` for usage stats.

**Q: Can I swap in a local LLM?**
The `ai_provider = "ollama"` setting is reserved for exactly this. The adapter stub is in place; someone needs to wire it through. See Contributing.

**Q: Will it break my modpack?**
Tested clean against JEI, REI, Embeddium, Oculus, Jade, Mouse Tweaks, Quark, and Inventory HUD+. If a specific mod collides, open an issue with logs and the modpack list — compat fixes are high-priority.

**Q: Can I bundle ForgeBook in a modpack?**
Yes — MIT-licensed. Keep `THIRD_PARTY_NOTICES.md` with the jar.

## Credits

- Third-party attributions: [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- Built on [Minecraft Forge](https://minecraftforge.net/), thanks to the Forge maintainers and the [Parchment](https://parchmentmc.org/) mapping project for the readable parameter names that made development bearable.
- AI: [Anthropic Claude](https://www.anthropic.com/)
- HTML scraping: [jsoup](https://jsoup.org/) (MIT, classes merged into the mod jar under their original `org.jsoup` package)

## License

[MIT](LICENSE) — do what you want, keep the attribution.

## Author

**Nick Doxa** — [GitHub](https://github.com/NickDoxa) · [Issues](https://github.com/NickDoxa/ForgeBook/issues)

Built for the simple satisfaction of being able to ask "what is this thing?" without tabbing out of Minecraft. If ForgeBook saves you from a wiki hunt, consider [opening an issue](https://github.com/NickDoxa/ForgeBook/issues) with a feature request or a bug — those are the contributions I value most.
