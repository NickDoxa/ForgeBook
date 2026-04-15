# Feature Research

**Domain:** In-game AI assistant mod for Minecraft Forge 1.20.1 (player-facing chat UI + grounded item lookup command)
**Researched:** 2026-04-14
**Confidence:** MEDIUM-HIGH (HIGH on existing AI mod patterns via direct prior-art review; MEDIUM on precise cost-safety feature expectations — inferred from general server-mod conventions rather than ForgeBook-exact peers)

## Feature Landscape

Prior art surveyed:
- **AI assistant mods:** MCChatGPT (Bawnorton), AI Chat Mod, AIChat, Chat to ChatGPT, CreatureChat, Talking Mobs, Speaking Villagers
- **Item documentation mods:** JEI (Just Enough Items), Patchouli, Wiki Lookup (Forge), Item Descriptions, Everything Descriptions, Wawla, Extended Item Information
- **Inventory UI conventions:** Inventory Management, Inventory HUD+ (button placement + config)
- **Admin tooling precedent:** Command Cooldown, Permission Levels mod, vanilla `op-permission-level`

Themes pulled from prior art:

- Existing AI chat mods are overwhelmingly **chat-command-driven** (`/ask`, `/mcchatgpt-auth`). A docked-to-inventory GUI is **rare** — this is a differentiator opportunity for ForgeBook.
- Cost/safety UX is **immature** in the category. MCChatGPT shows per-message token cost on hover — that's essentially the state of the art. Most mods have no rate limit, no op gate, no kill switch.
- Item-docs mods split into two camps: **on-hover tooltip extension** (Wawla, Item Descriptions — press Ctrl) and **command/keybind to a wiki** (Wiki Lookup's `L` key + `/wiki`). Both coexist with JEI's inventory-screen sidebar — the sidebar pattern is battle-tested.
- Patchouli-style **structured book navigation** is the gold standard for curated mod documentation but requires the mod author to write it; ForgeBook targets mods that *didn't* ship a Patchouli book.

### Table Stakes (Users Expect These)

Features users assume exist. Missing these = product feels incomplete or unsafe.

| Feature | Why Expected | Complexity | Notes | Dependencies |
|---------|--------------|------------|-------|--------------|
| Chat UI opens from inventory (button or click) | Inventory sidebar pattern established by JEI; players learn this instantly | M | Button rendered inside `InventoryScreen` via Forge's `ScreenEvent.Init.Post` + `addRenderableWidget` | Forge screen events |
| `/forgebook item` command targeting main-hand item | Wiki Lookup's `L` keybind trained players to expect "one input → info on held thing" | M | Use `ServerPlayer.getMainHandItem()`; `minecraft:air` → friendly error | Command framework |
| `/forgebook item <modid:item_id>` explicit targeting | Power users and players in creative/admin who want lookups without inventory juggling | S | `ItemArgument` vanilla Brigadier type | Command framework |
| Answer cites the source mod and links to displayURL | Wiki Lookup opens the actual page; players need provenance to trust AI output | S | Append `Source: <displayURL>` to response | ModList introspection |
| OP-only gate by default (`op_only = true`) | AI calls cost money; admins expect opt-in by default (matches vanilla `op-permission-level` conventions) | S | Check `CommandSourceStack.hasPermission(2)` before dispatch | Forge config |
| Per-player rate limit when gate is opened | Command Cooldown plugin category exists precisely because unbounded commands get abused | M | In-memory sliding-window counter keyed by UUID; reset on server restart OK for v1 | Player session tracking |
| Forge TOML config for provider, model, API key | Every Forge mod uses `ForgeConfigSpec`; `config/forgebook-*.toml` is the expected location | S | Standard `CommonConfig` + `ServerConfig` split | Forge config |
| API key stays server-side only | Shared API key leak = modder liability. OP-visible secrets only. | M | Dispatch AI calls in dedicated server-thread executor; use custom packet channel for client→server chat req/resp | Networking channel |
| Chat UI does not replace the inventory | JEI set the expectation that QoL mods *augment*, not replace | S | Render as a sibling widget left of `leftPos`, not a new screen | Screen layout math |
| Graceful fallback when mod has no displayURL | Mod authors forget to populate `mods.toml` URL ~30% of the time; hard-fail is unacceptable | M | Fall back to `web_search("<mod name> <item> wiki")` tool-call | Web search tool |
| Error visible to player on API failure (timeout, 4xx, 5xx) | Silent failures are the #1 complaint on AI-mod CurseForge pages | S | Chat message: "AI unavailable: <reason>. Retry later or tell an admin." | Networking + AI client |
| Loading / "thinking" indicator in chat UI | Vanilla chat has no pending-state affordance; players will spam-click without one | S | Replace send button with a spinner + disable until reply arrives | UI state |
| Kill-switch admin command (`/forgebook disable`) | If the API budget blows up mid-session admins need one-command disable without server restart | S | Flip in-memory flag; persists until server restart or re-enable via `/forgebook enable` | Command framework |
| Request logging for admins (who asked what, token cost) | Server owners paying for API calls expect an audit trail | M | Write structured lines to `logs/forgebook.log`; include player UUID, prompt hash, token count, latency | File logging |
| Conversation-per-session isolation | PROJECT.md requires it; also matches privacy expectation players have with chat mods | S | Hold `Map<UUID, List<Message>>` server-side, clear on disconnect + UI close | Session tracking |
| Answer is delivered in chat UI (not global chat) | Players don't want AI noise in shared server chat; CreatureChat was criticized for this | S | Keep AI responses inside the sidebar widget, not `player.sendSystemMessage` | Chat UI rendering |
| Works on dedicated + integrated (singleplayer) servers | Forge mods are expected to run in both contexts | S | Test both; server-side dispatcher runs identically in integrated server | Server-side dispatcher |

### Differentiators (Competitive Advantage)

Features that set ForgeBook apart from existing AI mods and wiki-lookup mods.

| Feature | Value Proposition | Complexity | Notes | Dependencies |
|---------|-------------------|------------|-------|--------------|
| Chat UI docked to inventory (not a `/ask` chat command) | No other in-game AI mod ships a GUI; prior art is all slash-command. This is the signature UX. | M | Renders adjacent to inventory pane; vanilla GUI chrome for v1 | Table-stakes chat UI |
| Grounded item answers (RAG single-shot vs free-form LLM) | Wiki Lookup dumps the wiki page; MCChatGPT hallucinates. ForgeBook fetches the mod's own docs first. | L | Fetch displayURL → strip HTML → pass to model with the item id; model constrained to "use only this source" | HTTP fetch + HTML-to-text |
| Mod-list awareness via `list_installed_mods` tool | AI can answer "which of my mods adds crafting automation?" — uniquely possible inside Minecraft | S | Forge `ModList.get().getMods()` exposed as tool spec | Tool-use agent |
| CurseForge modpack context injection | "In ATM9, what's the recommended way to generate power?" — modpack-aware answers | M | Fetch pack name + description at startup; inject into system prompt | CurseForge API client |
| Missing-docs web-search fallback | Sparse-metadata mods (~30% of ecosystem) still get useful answers | M | `web_search` tool wired to Brave/Tavily; summarize top 3 results | Web search tool |
| Pluggable provider abstraction (Claude v1, OpenAI/Ollama stubbed) | Lets server owners pick their price/privacy tradeoff. Only CreatureChat does this well. | M | Interface `AiProvider`; v1 ships Anthropic adapter | Config-driven provider selection |
| Per-message token + cost display in chat UI | MCChatGPT does this on hover in chat; ForgeBook surfaces it inline so players self-regulate | S | Returned with response packet; rendered below each AI message | Networking packet shape |
| `/forgebook item` with zero args (held item default) | Lowest-friction item lookup in the ecosystem — beats Wiki Lookup's 2-step flow | S | Already in PROJECT.md scope | Command framework |
| Tool-using agent for chat, RAG single-shot for `/forgebook item` | Right tool for the right job: multi-turn exploration vs bounded question. Saves tokens. | L | Two code paths sharing the `AiProvider`; documented hybrid in PROJECT.md decisions | AI provider interface |
| In-game "recent questions" history (session-scoped) | Lets a player scroll back without re-asking — novel in category | S | List rendered above input; cleared on UI close | Session state |
| Admin-visible token budget report (`/forgebook stats`) | Server owners currently have zero visibility into API spend — surfacing it is differentiator + trust | M | Accumulate per-player + global counters in memory; `/forgebook stats` prints table | Request logging |

### Anti-Features (Commonly Requested, Often Problematic)

Features that seem good but create problems. Keep out of v1 scope.

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|-----------------|-------------|
| Auto-answer triggered by words in public chat | CreatureChat / AI Chat Mod do this; feels "smart" | Burns tokens on conversation that wasn't asking the AI; privacy nightmare (reads all chat); trivially weaponized by any player to drain the budget | Explicit opt-in via inventory button or `/forgebook` command only |
| AI reads other players' chat as "context" | MCChatGPT includes "last 10 messages" | Surveillance by design; in PvP/public servers this is a privacy red flag | Per-session isolation; only the asking player's inputs enter their session |
| AI issues in-game commands ("Keyword-Triggered Command System" in AI Chat Mod) | Lets AI grant items, teleport, etc. | Massive griefing surface; one prompt-injection in a fetched wiki page = server wipe | Chat UI returns text only; no `CommandSourceStack` ever handed to the model |
| Fine-tuning or local embeddings index of mod docs | "Make it faster and smarter" | Requires disk cache, invalidation logic, embeddings model choice — huge scope creep for a v1 mod | Fetch docs on demand (PROJECT.md already decides this) |
| Voice input / TTS output | Talking Mobs / Speaking Villagers ship this | Adds native audio deps, massively enlarges package size, opens a new class of bugs on Mac/Linux | Defer; explicitly listed as out of scope in PROJECT.md |
| Persistent conversation history across sessions | "I want to remember my last chat" | On-disk PII storage + GDPR-adjacent concerns + migration pain; conversations drift fast in AI anyway | Per-session only; PROJECT.md already decides this |
| Auto-crafting or inventory manipulation suggestions that execute | "Let the AI craft for me" | Griefing; conflict with every crafting QoL mod (JEI's R/U, Inventory Management) | Answers describe crafting; player still places items themselves |
| Streaming responses in chat UI | "ChatGPT streams, so should this" | Complicates the networking channel (incremental packets), error recovery, token-count reconciliation — all for UX polish | Deliver complete reply; add streaming in polish phase only if users ask |
| Default keybind for opening chat UI | "Press C to chat with AI" | Inventory mod category has > 50 mods fighting for keybinds; every default collision costs a support ticket | In-inventory button (PROJECT.md); users bind their own via Controls |
| Public chat announcements of AI use | "Show when someone asks the AI" | Social pressure discourages questions; defeats the core value of "lower friction than alt-tab" | Private to the asking player's UI |
| Per-player config GUI inside Options menu | "Let players pick their own model" | API key per player means multi-tenant key management + billing surface; enormous complexity | Server-wide config only in v1; per-player preferences deferred |
| AI can edit the Forge config file | "Self-tuning rate limits" | Exec-like capability; violates the "server secrets never leave server control" invariant | Config is human-edited only |
| Screenshot / view-frustum awareness ("what am I looking at?") | "Make it multimodal" | Requires vision model + image encoding pipeline + Forge render hooks; massive cost increase per query | Use `/forgebook item` on the held item; block-look lookup deferred |

## Feature Dependencies

```
[Forge config]
    └──enables──> [Provider adapter] ──enables──> [AI dispatcher (server)]
                                                      ├──enables──> [Chat UI networking channel]
                                                      ├──enables──> [/forgebook item command]
                                                      └──enables──> [Request logging + /forgebook stats]

[ModList introspection]
    ├──enables──> [list_installed_mods tool] ──enhances──> [Chat UI agent]
    └──enables──> [displayURL fetch] ──enables──> [Grounded item answers]
                                         └──falls back to──> [web_search tool]

[CurseForge API client] ──enhances──> [Chat UI agent system prompt]

[OP gate] ──relaxes to──> [Per-player rate limit] ──requires──> [Session tracking]

[Inventory screen button] ──opens──> [Chat UI]
                                         ├──requires──> [Networking channel]
                                         └──requires──> [Session state + loading indicator]

[Kill-switch command] ──short-circuits──> [AI dispatcher]

[Answer token/cost metadata] ──requires──> [Provider adapter returning usage]
                                  └──feeds──> [/forgebook stats] and [per-message cost display]

[Auto-answer on public chat] ──CONFLICTS──> [OP gate + rate limit + privacy model]   (anti-feature)
[AI issues commands]         ──CONFLICTS──> [Cost-safety invariants]                  (anti-feature)
```

### Dependency Notes

- **Provider adapter blocks everything AI-related:** Both chat UI and `/forgebook item` route through it. Ship the interface + Anthropic adapter in the same phase as first integration test.
- **Grounded item answers require displayURL fetch *and* web-search fallback:** The fallback is not optional — without it the command is broken for ~30% of mods in a large pack.
- **Rate limit requires session tracking but OP gate does not:** Early phases can ship OP-only safely; rate-limit code can come in the phase that opens the gate.
- **Token/cost display depends on provider returning usage metadata:** Anthropic returns `usage.input_tokens` / `output_tokens` — design the `AiProvider` return type to carry these from day one so downstream features (`/forgebook stats`, per-message display) slot in cleanly.
- **Kill-switch must be independent of config reload:** It's for "API is on fire right now" — runtime flag, not config rewrite.
- **Anti-features conflict with cost-safety:** Any future proposal to add public-chat triggers or AI-executed commands must re-open the cost + security model. Treat as blocked.

## MVP Definition

### Launch With (v1)

Minimum viable product — what's needed to validate the concept.

- [ ] Forge config with provider, model, API key, OP gate, rate limit, CF pack id, enable flags — essential for server-owner trust
- [ ] `AiProvider` interface + Anthropic Claude adapter returning reply + usage — unblocks everything else
- [ ] Server-side AI dispatcher behind custom packet channel — API key isolation
- [ ] Chat UI docked to inventory (button toggle, text input, message list, loading state) — signature UX
- [ ] Chat agent with `list_installed_mods`, `fetch_mod_docs_page`, `web_search`, conditional `get_modpack_context` tools — grounded multi-turn answers
- [ ] `/forgebook item` (held-item default + explicit `<modid:item_id>`) via RAG single-shot — cheap, bounded item lookup
- [ ] displayURL fetch + web-search fallback — answers work for all mods, not just well-documented ones
- [ ] Answer cites source URL — trust + auditability
- [ ] OP-only gate by default + per-player rate limit (`rate_limit_per_minute`) — cost safety
- [ ] Kill-switch (`/forgebook disable` / `enable`) — incident response
- [ ] Request logging to `logs/forgebook.log` with UUID + tokens + latency — admin audit trail
- [ ] `/forgebook stats` admin command — budget visibility
- [ ] Per-session conversation isolation (cleared on UI close + disconnect) — privacy + simplicity
- [ ] Graceful error messages in chat UI for API failure, rate-limit hit, OP-gate denial — UX floor
- [ ] CurseForge modpack context injection when `curseforge_modpack_id` set — optional enrichment

### Add After Validation (v1.x)

Features to add once core is working and real players are using it.

- [ ] Per-message token + cost display inline in chat UI — trigger: users ask "how much am I spending?"
- [ ] Session-scoped "recent questions" list above input — trigger: players re-ask same question in same session
- [ ] OpenAI provider adapter — trigger: any user requests it (stub already in v1)
- [ ] Ollama provider adapter (local models) — trigger: privacy-focused server owners request it
- [ ] Per-screen button position override (à la Inventory Management) — trigger: collision reports with other inventory mods
- [ ] Configurable hotkey (registered as unbound) — trigger: power-user requests
- [ ] Retry button on failed AI requests — trigger: transient-failure complaints

### Future Consideration (v2+)

Features to defer until product-market fit is established.

- [ ] Streaming responses — polish, not validation-blocking
- [ ] Per-server daily token cap — add when a user reports a bill surprise
- [ ] Persistent conversation history (opt-in, per-player) — requires storage design + privacy policy
- [ ] Local embeddings / doc cache — only meaningful if displayURL fetch latency becomes a real complaint
- [ ] Block-look lookup ("what am I looking at?" for blocks, not just items) — additive; requires raycast
- [ ] Fabric / NeoForge ports — depends on adoption signal on 1.20.1 Forge first
- [ ] Multi-version support (1.21.x, 1.19.x) — port after v1 stabilizes
- [ ] Per-player preferences (model choice, response length) — needs multi-tenant key handling or shared key + per-player quotas
- [ ] Patchouli-book export of common answers — interesting but niche
- [ ] Voice / TTS — explicit out-of-scope in PROJECT.md

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Inventory-docked chat UI | HIGH | MEDIUM | P1 |
| `/forgebook item` (held-item default) | HIGH | MEDIUM | P1 |
| Provider abstraction + Claude adapter | HIGH | MEDIUM | P1 |
| Server-side dispatcher + packet channel | HIGH | MEDIUM | P1 |
| OP-only gate + per-player rate limit | HIGH | MEDIUM | P1 |
| displayURL fetch + web-search fallback | HIGH | MEDIUM | P1 |
| Source citation in answers | HIGH | LOW | P1 |
| Kill-switch command | HIGH | LOW | P1 |
| Loading indicator + error surfacing | HIGH | LOW | P1 |
| Request logging | MEDIUM | LOW | P1 |
| `/forgebook stats` | MEDIUM | MEDIUM | P1 |
| CurseForge modpack context | MEDIUM | MEDIUM | P1 |
| Tool-using agent (mods + web) | HIGH | HIGH | P1 |
| Per-session conversation isolation | MEDIUM | LOW | P1 |
| Per-message cost display | MEDIUM | LOW | P2 |
| Session "recent questions" list | MEDIUM | LOW | P2 |
| OpenAI adapter | MEDIUM | LOW | P2 |
| Ollama adapter | MEDIUM | MEDIUM | P2 |
| Configurable button position | LOW | LOW | P2 |
| Retry on failure | LOW | LOW | P2 |
| Unbound hotkey registration | LOW | LOW | P2 |
| Streaming responses | LOW | HIGH | P3 |
| Persistent history | LOW | HIGH | P3 |
| Per-server daily cap | MEDIUM | MEDIUM | P3 |
| Block-look lookup | MEDIUM | MEDIUM | P3 |
| Local embeddings cache | LOW | HIGH | P3 |
| Fabric / NeoForge port | MEDIUM | HIGH | P3 |

**Priority key:**
- P1: Must have for v1 launch
- P2: Add in v1.x after validation
- P3: v2+ / future consideration

## Competitor Feature Analysis

| Feature | MCChatGPT | AI Chat Mod | Wiki Lookup | Item Descriptions | JEI | ForgeBook Approach |
|---------|-----------|-------------|-------------|-------------------|-----|--------------------|
| Surface | `/ask` slash command | Chat triggers + commands | `L` key + `/wiki` | Ctrl-hover tooltip | Inventory sidebar | Inventory sidebar button + `/forgebook item` (hybrid, best-of-both) |
| AI backing | OpenAI only | Many (via AI Chat) | None (opens wiki URL) | None (static text) | None | Pluggable (Claude default, OpenAI/Ollama stub) |
| Grounding | Chat history only | Configurable persona | Wiki URL only | Hardcoded | Recipe data | displayURL fetch + web-search fallback + modpack context |
| Cost visibility | Hover-tooltip tokens | None | N/A | N/A | N/A | Per-message cost + `/forgebook stats` |
| OP gate | None | Configurable | None | None | N/A | Default ON |
| Rate limit | None | Via plugins | None | None | N/A | Per-player per-minute, built in |
| Kill switch | None | None | None | None | N/A | `/forgebook disable` |
| Reads public chat | Last 10 messages (surveillance) | Keyword triggers (surveillance) | No | No | No | No — explicit opt-in only (privacy-first) |
| Executes commands | No | Yes (security hole) | No | No | No | No — text-only responses |
| Multi-provider | No | Yes | N/A | N/A | N/A | Yes — pluggable abstraction |
| Modpack awareness | No | No | No | No | No | Yes — CurseForge integration |
| Session isolation | Conversation IDs (persistent) | Persona state | N/A | N/A | N/A | Per-session, cleared on close |

**Net positioning:** ForgeBook is the first Forge AI mod to combine (a) a docked inventory GUI, (b) grounded answers using the mod's own docs, (c) cost-safety defaults server owners actually want, and (d) privacy by default (no public-chat surveillance, no command execution).

## Sources

- [MCChatGPT on CurseForge](https://www.curseforge.com/minecraft/mc-mods/mcchatgpt) — HIGH (prior-art features, token/cost hover UX, context-level config)
- [MCChatGPT on Modrinth](https://modrinth.com/mod/mcchatgpt) — HIGH
- [MCChatGPT GitHub (Bawnorton)](https://github.com/Bawnorton/MCChatGPT) — HIGH (command surface: `/ask`, `/mcchatgpt-auth`, `/setcontextlevel`, `/nextconversation`)
- [AI Chat Mod on CurseForge](https://www.curseforge.com/minecraft/mc-mods/aimod) — HIGH (keyword-triggered commands — the anti-feature reference)
- [CreatureChat on CurseForge](https://www.curseforge.com/minecraft/mc-mods/creaturechat) — MEDIUM (multi-provider support pattern: Anthropic, OpenAI, Gemini, Ollama, etc.)
- [Talking Mobs / Speaking Villagers on CurseForge](https://www.curseforge.com/minecraft/mc-mods/talking-mobs-chatgpt-and-tts) — LOW (voice/TTS anti-feature evidence)
- [JEI on CurseForge](https://www.curseforge.com/minecraft/mc-mods/jei) — HIGH (inventory-sidebar UX, R/U keybinds, search-as-filter)
- [Patchouli on Modrinth](https://modrinth.com/mod/patchouli) — MEDIUM (data-driven documentation precedent)
- [Wiki Lookup (Forge) on CurseForge](https://www.curseforge.com/minecraft/mc-mods/wiki-lookup) — HIGH (`L` key + `/wiki` UX; item-in-hand default)
- [Item Descriptions on Modrinth](https://modrinth.com/mod/item-descriptions) — MEDIUM (Ctrl-hover tooltip pattern)
- [Wawla (What Are We Looking At) on CurseForge](https://www.curseforge.com/minecraft/mc-mods/wawla) — MEDIUM (hover-enrichment precedent; inspires future block-look lookup)
- [Inventory Management on Modrinth](https://modrinth.com/mod/inventory-management) — HIGH (configurable per-screen button positioning; Ctrl-click editor)
- [Command Cooldown plugin (SpigotMC)](https://www.spigotmc.org/resources/command-cooldown-1-20-5-add-cooldowns-to-commands-prevent-spam-bungee-support.73696/) — MEDIUM (cooldown-per-command category established)
- [Permission Levels on Modrinth](https://modrinth.com/mod/permission-levels) — MEDIUM (vanilla op-permission-level model)
- [CurseForge REST API docs](https://docs.curseforge.com/rest-api/) — HIGH (modpack id → pack metadata)
- [Server.properties wiki — rate-limit, op-permission-level](https://minecraft.fandom.com/wiki/Server.properties) — HIGH (vanilla rate-limit + op-level conventions ForgeBook mirrors)

---
*Feature research for: in-game AI assistant mod (ForgeBook v1) on Minecraft Forge 1.20.1*
*Researched: 2026-04-14*
