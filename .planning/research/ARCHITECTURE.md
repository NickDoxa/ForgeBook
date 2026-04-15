# Architecture Research

**Domain:** Minecraft Forge 1.20.1 mod — server-side AI dispatcher + client-side chat GUI + single-shot RAG command
**Researched:** 2026-04-14
**Confidence:** HIGH (Forge patterns, network, config, threading); MEDIUM (provider SDKs — no official Anthropic Java SDK, raw HTTP is the pragmatic path); MEDIUM (HTML extraction library choice — jsoup is ubiquitous, Boilerpipe is abandoned)

---

## Standard Architecture

### System Overview

```
┌─────────────────────────────── CLIENT (physical side) ──────────────────────────────┐
│                                                                                      │
│  ┌──────────────────────┐      ┌──────────────────────┐                              │
│  │  InventoryScreen     │      │  ChatScreen          │                              │
│  │  (vanilla, + our     │◄────►│  (our Screen, docked │                              │
│  │   injected button)   │ open │   left of inventory) │                              │
│  └──────────┬───────────┘      └──────────┬───────────┘                              │
│             │                             │                                          │
│             │   ScreenEvent.Init.Post     │  user hits Send                          │
│             │   (inject button)           ▼                                          │
│             │                   ┌─────────────────────┐                              │
│             │                   │  ClientChatSession  │  (session state; turn log;   │
│             │                   │                     │   latest error; pending id)  │
│             │                   └──────────┬──────────┘                              │
│             │                              │                                          │
│             │                              ▼ ChatRequestPacket                       │
└─────────────┼──────────────────────────────┼──────────────────────────────────────────┘
              │        SimpleChannel (forgebook:main)                                   
              │                              │                                          
┌─────────────┼──────────────────────────────▼──────────────── SERVER (physical side) ─┐
│             │                                                                        │
│             │   ┌───────────────────────────────────────────┐                        │
│             │   │          AiDispatcher (per-server)        │                        │
│             │   │  - receives ChatRequestPacket             │                        │
│             │   │  - OP/rate-limit gate                     │                        │
│             │   │  - enqueues job on async AI executor      │                        │
│             │   └───────────┬───────────────────┬───────────┘                        │
│             │               │                   │                                    │
│             │    ┌──────────▼─────────┐   ┌─────▼─────────────┐                      │
│             │    │ AiProvider (iface) │   │ ToolRegistry      │                      │
│             │    │  ClaudeProvider    │◄──│  ListModsTool     │                      │
│             │    │  OpenAiProvider*   │   │  FetchModDocsTool │                      │
│             │    │  OllamaProvider*   │   │  WebSearchTool    │                      │
│             │    └──────────┬─────────┘   │  ModpackCtxTool   │                      │
│             │               │             └─────┬─────────────┘                      │
│             │               │                   │                                    │
│             │               ▼                   ▼                                    │
│             │      ┌──────────────────────────────────┐                              │
│             │      │  Java 17 HttpClient (async)      │                              │
│             │      │   + jsoup for HTML extraction    │                              │
│             │      └──────────┬───────────────────────┘                              │
│             │                 │                                                      │
│             │                 ▼                                                      │
│             │     external: api.anthropic.com, mod.displayURL, api.curseforge.com,   │
│             │               duckduckgo.com or configured search API                  │
│             │                                                                        │
│             │   ┌─────────────────────────────────────────────┐                      │
│             │   │  ForgebookCommand (/forgebook item | ask |  │                      │
│             │   │   reload) — shares AiDispatcher             │                      │
│             │   └─────────────────────────────────────────────┘                      │
│             │                                                                        │
│             ▼ ChatResponsePacket (or ChatErrorPacket)                                 
│             (dispatched back to originating player on main thread)                    
└──────────────────────────────────────────────────────────────────────────────────────┘
```

Arrows on the boundary are the packet channel. AI outbound traffic is always from the server; the API key never crosses the boundary.

### Component Responsibilities

| Component | Responsibility | Typical Implementation |
|-----------|----------------|------------------------|
| `ForgeBookMod` (entry) | `@Mod` bootstrap, config registration, event bus wiring | Static initializer that dispatches to side-specific setup via `DistExecutor` |
| `ForgebookNetwork` | Owns the `SimpleChannel`, registers packet discriminators | One class, static `INSTANCE`, numeric ID counter |
| `ChatRequestPacket` / `ChatResponsePacket` / `ChatErrorPacket` | Wire types | Plain encode/decode `FriendlyByteBuf` |
| `AiDispatcher` (server singleton) | Authorize → rate-limit → enqueue → run agent loop → send reply packet | Held on `MinecraftServer` via capability-like static, started on `ServerStartedEvent`, stopped on `ServerStoppingEvent` |
| `RateLimiter` | Per-player token bucket; OP bypass | `ConcurrentHashMap<UUID, TokenBucket>` |
| `AiProvider` (interface) | `chat(List<Message>, List<ToolSpec>) -> AiTurnResult` | Sealed-ish, one adapter per provider |
| `ClaudeProvider` | Anthropic Messages API over HTTP/JSON | Java 17 `HttpClient.sendAsync`, minimal DTOs; no SDK (there is no first-party Anthropic Java SDK) |
| `Tool` (interface) | `name()`, `schema()`, `invoke(JsonNode args, ToolContext ctx)` | Registered into `ToolRegistry` at server-start |
| `ToolRegistry` | Maps tool name → `Tool`, assembles tool specs for provider | Simple map; iteration order deterministic |
| `ModDocsScraper` | Resolve `getDisplayURL()` → fetch → extract readable text | Java `HttpClient` + `jsoup` |
| `WebSearchClient` | Query a search API for wiki pages | HTTP call to DDG HTML/SERP endpoint behind `enable_web_search` flag |
| `CurseForgeClient` | Modpack ID → modpack name/description/mods | HTTP with header `x-api-key` |
| `SystemPromptBuilder` | Assembles system prompt per request: mod list, modpack context, guardrails | Called once per turn on server |
| `ForgebookCommand` | `/forgebook item|ask|reload` — shares `AiDispatcher` | Brigadier, server-side |
| `ChatScreen` (client) | Renders docked chat; drives submit, session state, error toasts | `net.minecraft.client.gui.screens.Screen`; opens alongside `InventoryScreen` via a custom parent screen |
| `InventoryButtonInjector` (client) | Adds "open ForgeBook" button inside inventory | `ScreenEvent.Init.Post` listener filtered on `InventoryScreen` |
| `ClientChatSession` | Turn log, in-flight request correlation IDs, error surface | Client-only class; cleared on screen close / disconnect |
| `ForgebookConfig` | `ForgeConfigSpec` wiring for the three types | Static `CLIENT_SPEC`, `SERVER_SPEC`; no COMMON for v1 |

## Recommended Project Structure

### Source set choice: single `src/main/java` + `DistExecutor`, NOT split source sets

Forge 1.20.1 supports split source sets (`src/client/java` etc.), but split sets exist to trade ergonomics for *stronger* class-loading safety. For a mod this small, they are overkill and they complicate the MDK's run configs. The robust-enough approach is:

- Single `src/main/java`.
- **Never** reference `net.minecraft.client.*` types from a class that is reachable on the dedicated server. Put all client code under `com.forgebook.client.*`. Put the listener that subscribes client-only events in that package too.
- Use `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init)` from the mod constructor instead of `@OnlyIn` sprinkled across methods. `@OnlyIn` remains useful only on overrides of vanilla methods that themselves carry it (rare in this mod).
- Guard any `FMLEnvironment.dist == Dist.CLIENT` branches at package boundaries — once you're inside `com.forgebook.client.*`, assume client side.

This satisfies the quality-gate constraint "components have clear single responsibilities" without taking on split-source-set build complexity.

### Directory layout

```
src/main/java/com/forgebook/
├── ForgeBookMod.java              # @Mod entry; event bus wiring; DistExecutor handoff
├── Constants.java                 # MOD_ID = "forgebook", channel name, protocol version
│
├── config/
│   ├── ForgebookConfig.java       # SERVER + CLIENT specs, static accessors
│   └── ConfigEvents.java          # onLoad/onReload to refresh cached values
│
├── network/
│   ├── ForgebookNetwork.java      # SimpleChannel INSTANCE, register packets
│   ├── ChatRequestPacket.java     # C→S: requestId, prompt, sessionTurnIndex
│   ├── ChatResponsePacket.java    # S→C: requestId, reply text, finishReason
│   ├── ChatErrorPacket.java       # S→C: requestId, errorKind, message
│   └── TypingIndicatorPacket.java # S→C: requestId, phase ("thinking"|"using_tool:X")  -- v1.1, not v1
│
├── ai/
│   ├── AiDispatcher.java          # server singleton; main entry for chat + item
│   ├── AgentLoop.java             # orchestrates provider↔tool turns
│   ├── SystemPromptBuilder.java   # assembles per-request system prompt
│   ├── RateLimiter.java           # per-player token bucket, OP bypass
│   ├── provider/
│   │   ├── AiProvider.java        # interface: chat(List<Msg>, Tools) -> Turn
│   │   ├── ClaudeProvider.java    # v1 default
│   │   ├── OpenAiProvider.java    # stub (throws UnsupportedOperationException)
│   │   └── OllamaProvider.java    # stub
│   └── tools/
│       ├── Tool.java              # interface
│       ├── ToolRegistry.java
│       ├── ListInstalledModsTool.java
│       ├── FetchModDocsPageTool.java
│       ├── WebSearchTool.java
│       └── GetModpackContextTool.java
│
├── command/
│   ├── ForgebookCommand.java      # /forgebook registration
│   ├── ItemSubcommand.java        # /forgebook item [<modid:item>]
│   ├── AskSubcommand.java         # /forgebook ask "<prompt>"
│   └── ReloadSubcommand.java      # /forgebook reload (config hot reload)
│
├── integration/
│   ├── curseforge/
│   │   ├── CurseForgeClient.java  # GET /v1/mods/{id}
│   │   └── ModpackContext.java    # cached at server start
│   └── docs/
│       ├── ModDocsScraper.java    # displayURL → readable text
│       └── HtmlReadability.java   # jsoup selectors + heuristics
│
├── util/
│   ├── AsyncExecutors.java        # named ThreadFactory, single shared executor
│   ├── ServerMainThread.java      # helper to hop back via server.execute(...)
│   └── JsonCodec.java             # tiny DTO helpers (Gson already on classpath)
│
└── client/                        # physically on client only — never imported by server
    ├── ClientSetup.java           # registers screen, key mappings (none in v1), listeners
    ├── gui/
    │   ├── ChatScreen.java        # the dockable screen
    │   ├── ChatWidget.java        # text list + input
    │   ├── MessageBubble.java
    │   └── InventoryButtonInjector.java   # ScreenEvent.Init.Post listener
    ├── session/
    │   └── ClientChatSession.java # per-inventory-open session state
    └── net/
        └── ClientPacketHandler.java  # resolves response/error packets to session

src/main/resources/
├── META-INF/mods.toml
├── pack.mcmeta
├── logo.png                       # mods.toml logoFile (user-supplied later)
└── assets/forgebook/
    ├── lang/en_us.json
    └── textures/gui/
        ├── chat_panel.png         # vanilla-style 9-slice placeholder
        └── logo.png               # user-supplied later
```

### Structure Rationale

- **`com.forgebook.client.*` is the only client-referencing package.** Nothing in `ai/`, `network/`, `command/`, `config/`, `integration/` imports `net.minecraft.client.*`. This is the class-loading firewall that replaces `@OnlyIn`.
- **Network and AI kept separate.** The network layer only knows packets; the AI layer only knows "given a prompt + player, return a reply." Either can be replaced without touching the other.
- **Provider under `ai/provider/` with stubs in tree from day one.** Having empty Ollama/OpenAI classes enforces the interface shape early — future contributors can't silently regress the abstraction.
- **`integration/` is the "external I/O" bucket.** Easy to mock in tests and easy to audit for "what talks to the outside world."
- **No `common/` module.** Community patterns for single-jar Forge mods favor discipline over Gradle source-set machinery for sub-10k-LOC mods.

## Architectural Patterns

### Pattern 1: Server-authoritative AI dispatcher

**What:** All outbound AI calls originate on the server process. Clients only send/receive opaque chat packets.
**When to use:** Always, for this mod. The API key lives in `server.toml`; a single code path touches it.
**Trade-offs:** Singleplayer "works" because Minecraft's integrated server is still a server thread with server config; one path handles both SP and MP. Downside: a singleplayer user editing the client `forgebook-client.toml` cannot set the API key — they edit the server-side config. That is the correct constraint to enforce.

**Example (pseudocode):**
```java
// server-side, inside ChatRequestPacket handler
public static void handle(ChatRequestPacket pkt, Supplier<NetworkEvent.Context> ctx) {
    NetworkEvent.Context c = ctx.get();
    c.enqueueWork(() -> {
        ServerPlayer player = c.getSender();
        AiDispatcher.get().submitChat(player, pkt.requestId(), pkt.prompt());
    });
    c.setPacketHandled(true);
}
```

### Pattern 2: Off-tick async executor, main-tick reply

**What:** HTTP I/O runs on a dedicated `Executor` (NOT `ForkJoinPool.commonPool`, NOT the server tick thread). Reply packets and any world/ModList reads that the tools need are hopped back onto the server main thread via `server.execute(...)` or `NetworkEvent.Context#enqueueWork`.
**When to use:** Any AI/HTTP call in this mod.
**Trade-offs:** Correctness over speed. Blocking the server tick for a 3s Claude call would freeze every player. `CompletableFuture` chained with `thenApplyAsync(..., aiExecutor)` for tool invocations that stay in I/O land, then one final hop back to main thread to send the reply.

**Example:**
```java
// AgentLoop.java
CompletableFuture<String> run(ServerPlayer player, String userPrompt) {
    return CompletableFuture
        .supplyAsync(() -> provider.chat(history, tools), aiExecutor)
        .thenComposeAsync(this::resolveToolCalls, aiExecutor) // recurse until no tool_use
        .thenApplyAsync(this::finalText, aiExecutor);
}

// Later, when replying:
player.server.execute(() ->
    ForgebookNetwork.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player),
        new ChatResponsePacket(requestId, replyText, finishReason)));
```

Tool implementations that must read game state (e.g., `ListInstalledModsTool` reading `ModList.get()` — technically thread-safe — or future tools reading world state) should explicitly hop to main thread:
```java
CompletableFuture<JsonNode> invoke(JsonNode args, ToolContext ctx) {
    return CompletableFuture.supplyAsync(() -> snapshotModList(), ctx.mainThreadExecutor())
                            .thenApplyAsync(this::toJson, ctx.aiExecutor());
}
```

### Pattern 3: Pluggable AI provider via narrow interface

**What:** A single interface `AiProvider` that every backend implements. One "turn" returns either a final text reply or a list of tool-use requests; the `AgentLoop` — not the provider — drives iteration.
**When to use:** Always, even though only Claude ships in v1. Keeping OpenAI/Ollama stubs in-tree enforces that nothing Claude-specific leaks into `AgentLoop`.

**Interface shape:**
```java
public interface AiProvider {
    String id();                                        // "claude", "openai", "ollama"
    AiTurn chat(ChatRequest req);                       // blocking; called from aiExecutor

    record ChatRequest(String model,
                       String systemPrompt,
                       List<Message> history,
                       List<ToolSpec> tools,
                       int maxTokens) {}

    sealed interface AiTurn permits FinalReply, ToolUses, ProviderError {}
    record FinalReply(String text, String finishReason) implements AiTurn {}
    record ToolUses(List<ToolCall> calls, List<Message> partialHistory) implements AiTurn {}
    record ProviderError(ErrorKind kind, String message) implements AiTurn {}
}
```

**Trade-offs:** Requires the agent loop to know how to append tool results in a provider-portable shape. Mitigation: `Message` is our internal type; providers translate to/from their wire format. Claude's tool use and OpenAI's function calling both fit this shape; Ollama's tool support is newer but compatible for adapter-level translation.

### Pattern 4: Tool registry resolved at server start, not class-load

**What:** `ToolRegistry.register(new ListInstalledModsTool())` is called in `ServerStartedEvent`, not in a static initializer. Tools receive a `ToolContext` on each invocation (carries `MinecraftServer`, `ServerPlayer`, `aiExecutor`, `mainThreadExecutor`).
**When to use:** Any time a tool needs live server state.
**Trade-offs:** You cannot use a tool in a unit test by instantiating the tool alone — but you can construct a stub `ToolContext`. That's the right shape.

### Pattern 5: Screen-adjacent overlay, not a replacement screen

**What:** The inventory button closes the `InventoryScreen` and opens a custom `ForgebookContainerScreen` that (a) renders the vanilla inventory at its normal x,y and (b) renders the chat panel to its left. The vanilla inventory slots continue to work.
**When to use:** For v1.
**Trade-offs vs. a `ScreenEvent.Render.Post` overlay on `InventoryScreen`:** an overlay approach runs inside the inventory screen's render loop, which means every input event must be stolen carefully — text-box focus, escape key, mouse scroll — and the inventory is in charge of the screen's closing behavior. A custom `Screen` subclass that embeds an inventory widget is more work but gives us deterministic input handling and makes "sits left of the inventory" layout trivial. Concretely: subclass `AbstractContainerScreen<InventoryMenu>`, set `imageWidth` and shift `leftPos` so the inventory sits in its normal relative location, and render the chat panel at a negative x offset.

A simpler v1 path if the above proves finicky during implementation: a standalone `Screen` (not `AbstractContainerScreen`) that does NOT host the real inventory but renders a non-interactive inventory-looking panel to the right for visual continuity. Flag during scaffold phase; pick based on how fussy slot hit-testing turns out to be.

## Data Flow

### Chat request flow (UI-driven, tool-using)

```
Player presses button in InventoryScreen
        │
        ▼
InventoryButtonInjector.onClick → Minecraft.setScreen(new ChatScreen(parent))
        │
        ▼
Player types, hits Send
        │  ClientChatSession.appendUser(prompt); generate requestId
        ▼
ForgebookNetwork.sendToServer(new ChatRequestPacket(requestId, prompt, turnIdx))
        │  [network boundary]
        ▼
Server-side packet handler (enqueueWork → main thread)
        │
        ▼
AiDispatcher.submitChat(player, requestId, prompt)
        │ 1. RateLimiter.tryAcquire(player) — OP bypass; else 429-equivalent error packet
        │ 2. Load/locate the per-player ChatSession on server (map keyed by UUID+screen epoch)
        │ 3. Build SystemPrompt (modlist, modpack ctx, guardrails)
        │ 4. Hand off to AgentLoop.run(session, prompt) — returns CompletableFuture<String>
        ▼
AgentLoop on aiExecutor:
        │   loop:
        │     turn = provider.chat(history, tools)
        │     if turn instanceof FinalReply → break with text
        │     if turn instanceof ToolUses   → for each call: toolRegistry.invoke(...) (may hop
        │                                     to main thread), append tool_result to history
        │     if turn instanceof ProviderError → break with error
        ▼
Final text or error
        │ server.execute(() -> ...)  [hop back to main thread]
        ▼
ForgebookNetwork.sendTo(player, new ChatResponsePacket(requestId, text, finishReason))
        │  [network boundary]
        ▼
Client: ClientPacketHandler.onResponse → ClientChatSession.appendAssistant(requestId, text)
        │
        ▼
ChatScreen re-renders from session; if error → shows inline red banner inside chat panel
```

### Item command flow (single-shot RAG)

```
Player runs /forgebook item [modid:item]
        │
        ▼
ForgebookCommand → ItemSubcommand.execute(ctx)
        │ 1. Resolve target item (main-hand if no arg)
        │ 2. Identify source modid from item ResourceLocation
        │ 3. displayURL = ModList.get().getModContainerById(modid).getModInfo().getDisplayURL()
        │ 4. if displayURL missing → fallback path (web_search via WebSearchClient)
        ▼
AiDispatcher.submitItem(player, item, displayURL)
        │ 1. Rate-limit check
        │ 2. ModDocsScraper.fetchReadable(displayURL) → text (jsoup, cap at N chars)
        │ 3. SystemPromptBuilder.buildItemPrompt(item, modpackCtx, docsText)
        │ 4. provider.chat({systemPrompt, userPrompt="What can I do with this item?"},
        │                  tools=NONE)  — single-shot, no agent loop
        ▼
Final text
        │
        ▼
player.sendSystemMessage(Component.literal(text))   // chat line, not packet
```

### State management

- **Server:** `Map<UUID, ServerChatSession>` keyed by player UUID. Session contains the ordered message history and the session epoch (bumped when the client reopens the chat screen — the client sends its epoch on first request so the server can detect reset). Sessions are dropped on disconnect (`PlayerLoggedOutEvent`) and on epoch change.
- **Client:** `ClientChatSession` lives on the `ChatScreen`. Closing the screen clears it. No disk persistence.
- **Config:** loaded once by Forge; reload via `/forgebook reload` triggers `ConfigEvents.onReload`, which rebuilds the `AiProvider` instance (because model/key may have changed) and resets the `RateLimiter` buckets.

### Key data flows

1. **API key flow:** never leaves the server. Read once from `ForgebookConfig.SERVER`, held in `ClaudeProvider` field, sent only as the `x-api-key` header on outbound HTTPS.
2. **Modpack context flow:** `ServerStartedEvent` triggers `CurseForgeClient.fetchModpack(configuredId)` asynchronously; result stored in `ModpackContext` singleton. If fetch fails, we log and continue without modpack context — `SystemPromptBuilder` checks for presence.
3. **Mod docs flow:** on-demand per tool call or per item command. No caching in v1 (per PROJECT.md). Every fetch uses a short timeout (5s connect, 10s read) and bounded body size (e.g., 500 KB max) to prevent a slow/huge page from DoS-ing a session.

## Scaling Considerations

Minecraft servers typically max at ~100 concurrent players; "scale" here is about **cost** and **tick-time safety**, not throughput.

| Scale | Architecture Adjustments |
|-------|--------------------------|
| 1–10 players | v1 as designed. Single shared `aiExecutor` of 4 threads. No caching. |
| 10–100 players | Add doc-fetch cache (in-memory, TTL ~10 min) keyed by displayURL. Increase `aiExecutor` to 8–16. Consider per-player concurrent-request cap (= 1). |
| 100+ players or public modpack servers | Introduce daily/hourly token budget cap per server (not just per-player). Evict sessions aggressively. Consider request queueing with depth limit so a burst of 50 simultaneous "/forgebook item" doesn't fan out to 50 HTTPS calls. |

### Scaling priorities

1. **First bottleneck: API cost runaway.** Mitigated in v1 by OP-only default + per-player rate limit. Next lever (v1.1): per-server daily token cap.
2. **Second bottleneck: `aiExecutor` thread exhaustion.** If 30 players all call `/forgebook item` simultaneously, a 4-thread pool becomes a queue. Fix: size executor with `min(availableProcessors*2, 16)`; add a `Semaphore`-based admission control with a clear user-facing "busy, try again" error.
3. **Third bottleneck: doc scraping blowups.** One mod's wiki returning 50 MB will OOM the pool. Fix: hard body-size cap in `ModDocsScraper` using `HttpClient`'s `BodySubscribers.ofByteArrayConsumer` with a counting wrapper, or truncate via `limit()`.

## Anti-Patterns

### Anti-Pattern 1: Running the AI call on the server tick thread

**What people do:** Call `provider.chat(...)` directly from the packet handler's `enqueueWork` block.
**Why it's wrong:** `enqueueWork` runs on the main server thread. A 2–8 second AI request freezes the tick loop, causing visible lag spikes and eventually the server's "can't keep up" watchdog.
**Do this instead:** In `enqueueWork`, do only the quick auth/rate-limit check, then hand off to `AiDispatcher` which dispatches to `aiExecutor`. Hop back to the main thread only for the final `sendTo(...)` or for tool invocations that need world state.

### Anti-Pattern 2: `@OnlyIn(Dist.CLIENT)` as a safety net on server-reachable classes

**What people do:** Annotate a field with `@OnlyIn(Dist.CLIENT)` and assume the server JVM will skip loading it.
**Why it's wrong:** `@OnlyIn` controls distribution, not class-loading. A `net.minecraft.client.gui.screens.Screen` reference in a field on a class touched from the server side will `NoClassDefFoundError` on a dedicated server.
**Do this instead:** Keep every client type inside `com.forgebook.client.*`. Enter that package only via `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init)`.

### Anti-Pattern 3: Global keybind for the chat UI

**What people do:** Register a `KeyMapping` on a default key to toggle the chat screen.
**Why it's wrong:** Mod ecosystems are crowded; keybind collisions (`R`, `G`, `C`, etc.) are constant complaints. The PROJECT.md already opts out of this.
**Do this instead:** Inventory button is the sole entry point in v1.

### Anti-Pattern 4: Shipping a caching layer "because we'll need it later"

**What people do:** Preemptively build an LRU/TTL cache for mod docs and CurseForge.
**Why it's wrong:** PROJECT.md explicitly says no cache in v1; adds complexity (eviction, invalidation on `/forgebook reload`) without a validated need.
**Do this instead:** Leave a clear insertion point — `ModDocsScraper.fetchReadable(url)` is the single call site. When a cache is needed, wrap this one method.

### Anti-Pattern 5: Coupling `AgentLoop` to Claude's wire format

**What people do:** Pass `anthropic.ContentBlock` objects through the agent loop.
**Why it's wrong:** Makes `OpenAiProvider`/`OllamaProvider` stubs impossible to satisfy without massive refactor.
**Do this instead:** Internal `Message`/`ToolCall`/`ToolResult` types, provider adapters do the translation.

### Anti-Pattern 6: Sending large payloads over `SimpleChannel` without chunking awareness

**What people do:** Stream a 20 KB AI reply as a single packet and assume it works.
**Why it's wrong:** Minecraft's packet size limit defaults to ~2 MB, but compressed strings >~30 KB can hit it in corner cases; more importantly, the vanilla `Component` chat cap and our UI rendering both assume bounded reply sizes.
**Do this instead:** Have `AiDispatcher` cap reply text to a safe size (e.g., 8 KB) with truncation notice. Streaming is deferred anyway per PROJECT.md.

## Integration Points

### External services

| Service | Integration Pattern | Notes |
|---------|---------------------|-------|
| Anthropic Claude Messages API | `HttpClient.sendAsync` POST to `https://api.anthropic.com/v1/messages` with `x-api-key` + `anthropic-version: 2023-06-01` | No official Java SDK; raw JSON is simpler and avoids a fat dependency. Tool schemas are JSON under `tools: [{name, description, input_schema}]` |
| CurseForge API | HTTPS GET with `x-api-key`; endpoint `/v1/mods/{modpackId}` | Optional; degrade gracefully when key absent or call fails |
| Mod displayURL pages | GET with `User-Agent: ForgeBook/<version>`; parse via jsoup | Unpredictable HTML; extract readable text by grabbing `<main>`, `<article>`, or the largest `<div>` by text length as a fallback |
| Web search (DuckDuckGo HTML or configured) | GET; parse result links via jsoup | Gated by `enable_web_search`; used only as the missing-docs fallback |

### Internal boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| Client ↔ Server | `SimpleChannel` packets only | Three packet types for v1: `ChatRequestPacket` (C→S), `ChatResponsePacket` (S→C), `ChatErrorPacket` (S→C). `TypingIndicatorPacket` deferred to v1.1. |
| `AiDispatcher` ↔ `AiProvider` | In-process method call; returns `AiTurn` | Provider never sees `ServerPlayer` — only the `ChatRequest` DTO |
| `AgentLoop` ↔ `Tool` | `CompletableFuture<JsonNode>`; tool receives `ToolContext` | Tools can hop to main thread via `ctx.mainThreadExecutor()` when needed |
| `ForgebookCommand` ↔ `AiDispatcher` | Shared server singleton; single-shot method (`submitItem`) separate from streaming chat flow | Command path deliberately skips tool loop |
| `ClientChatSession` ↔ `ChatScreen` | Screen polls session each frame; session is mutated by `ClientPacketHandler` | Keep session immutable-ish (append-only) to avoid render-tearing |

## Library & dependency choices (architecturally load-bearing)

- **HTTP:** `java.net.http.HttpClient` (built into Java 17). No OkHttp / Apache — avoids shadowing/relocation pain in the Forge classloader.
- **JSON:** Gson. Already on the Minecraft classpath; no new dependency.
- **HTML extraction:** `org.jsoup:jsoup:1.17.x`. Boilerpipe, while theoretically better for article extraction, is effectively abandoned (last release 2014, old `net.sourceforge.nekohtml` transitive deps conflict with newer JVMs). Jsoup + a small readability-style heuristic (prefer `<article>`, then `<main>`, then largest-text `<div>`, strip `nav/header/footer/aside`) is the pragmatic choice. Shadow and relocate to `com.forgebook.shadow.jsoup` to avoid classloader conflicts with any other mod bundling it.
- **No Anthropic SDK.** Anthropic does not publish a first-party Java SDK (only Python and TypeScript). A thin `ClaudeProvider` that posts JSON is ~150 lines and avoids the dependency entirely.

## Config loading order (specific to ForgeBook fields)

Per PROJECT.md the config file is `forgebook-common.toml` and/or `forgebook-server.toml`. The architecturally-correct mapping:

| Field | Config type | Rationale |
|-------|-------------|-----------|
| `enable_chat_interface` | CLIENT | Purely UI toggle; no server-side effect |
| `ai_provider` | SERVER | Provider selection gates which adapter is instantiated on the server |
| `ai_api_key` | SERVER | MUST NEVER be CLIENT or COMMON |
| `ai_model` | SERVER | Paired with provider; server-owned |
| `curseforge_modpack_id` | SERVER | Fetched at server start |
| `curseforge_api_key` | SERVER | Secret |
| `op_only` | SERVER | Authorization policy |
| `rate_limit_per_minute` | SERVER | Server-enforced |
| `enable_web_search` | SERVER | Outbound call policy |

**COMMON is not used in v1.** No field needs both sides. This keeps the secret surface small.

Loading order: `FMLCommonSetupEvent` fires after configs are loaded; `ServerStartedEvent` is the safe point to build `AiProvider` from config (the server config file is guaranteed loaded by then). `ConfigEvents.onReload` rebuilds the provider on `/forgebook reload`.

## System-prompt assembly location

`SystemPromptBuilder.buildForChat(player)` and `buildForItem(item, docsText)` both live server-side in `com.forgebook.ai.SystemPromptBuilder`. At chat time it composes:

1. Fixed guardrails header (who it is, don't make up items, prefer docs-grounded answers).
2. `ModList.get().getMods()` rendered as `modid: Display Name vX.Y.Z` (capped to keep token count sane; ~N=200 mod names is safe).
3. If `ModpackContext` present → "This server is running modpack <name>: <description>".
4. Reminder that tools are available.

## Error surface back to the client UI

- **Transport/networking error:** `ChatErrorPacket(requestId, ErrorKind.TRANSPORT, msg)` → chat panel renders a red inline bubble replacing the "thinking" placeholder for that requestId. Not a toast — toasts are easy to miss and we already own screen real estate.
- **Rate-limit hit:** `ErrorKind.RATE_LIMITED` → same inline bubble with a "try again in Ns" message derived from the token bucket.
- **Not authorized (non-OP, op_only=true):** `ErrorKind.FORBIDDEN` — on receipt, the client renders once and also disables the Send button until screen close. Also applies to /forgebook command (standard `CommandSourceStack` feedback).
- **Provider error (4xx/5xx/timeout):** `ErrorKind.PROVIDER` with a short message; server logs full stack at WARN.
- **Tool error:** handled inside `AgentLoop`; we append a `tool_result` with `is_error: true` and let the model decide whether to apologize to the user. Tool errors do NOT bubble to the client as ChatErrorPackets unless the loop gives up.

## Build order (unblocks-what)

```
Phase 1  Skeleton
  └─ mods.toml, @Mod class, Constants, Gradle deps (jsoup, gson already there)
     └─ unblocks all further phases

Phase 2  Config
  └─ ForgebookConfig (SERVER + CLIENT specs), ConfigEvents
     └─ unblocks AiProvider construction, RateLimiter, command auth

Phase 3  Network plumbing
  └─ ForgebookNetwork, ChatRequest/Response/ErrorPacket, handler stubs
  └─ Smoke test: client sends "hello", server echoes "hello back"
     └─ unblocks chat UI end-to-end test and AiDispatcher wiring

Phase 4  AI provider + agent loop (minimum viable)
  └─ AiProvider interface, ClaudeProvider (no tools yet), AgentLoop (single turn)
  └─ AiDispatcher (auth stub, no rate limit yet)
  └─ Async executor (AsyncExecutors)
  └─ Smoke test: from server console / debug command, ask Claude a question
     └─ unblocks tool integration and command path

Phase 5  Tool registry + first tool
  └─ Tool interface, ToolRegistry, ListInstalledModsTool
  └─ Extend AgentLoop to iterate on tool_use turns
     └─ unblocks remaining tools (which are variations on the pattern)

Phase 6  Remaining tools
  └─ FetchModDocsPageTool (pulls in ModDocsScraper + jsoup)
  └─ WebSearchTool (gated by config)
  └─ GetModpackContextTool (requires CurseForgeClient)
     └─ unblocks the real chat experience

Phase 7  Command path
  └─ ForgebookCommand, ItemSubcommand (reuses AiDispatcher + ModDocsScraper)
  └─ AskSubcommand, ReloadSubcommand
     └─ unblocks one of the two user surfaces end-to-end

Phase 8  Client UI
  └─ ClientSetup, InventoryButtonInjector, ChatScreen, ChatWidget, ClientChatSession, ClientPacketHandler
  └─ Integrate with the existing packet flow
     └─ unblocks the other user surface end-to-end

Phase 9  Rate limiting + OP gate
  └─ RateLimiter, wire into AiDispatcher
  └─ Error packet plumbing and inline error rendering
     └─ unblocks safe-to-publish state

Phase 10 Polish
  └─ System prompt tuning, logo slots, en_us lang, logging hygiene
  └─ Shadowing/relocation config, license headers, README
```

**Simplest-thing-that-works call-outs for v1:**

- In phase 3, implement packets as plain DTOs with manual `FriendlyByteBuf` encode/decode — no reflection-based codec library.
- In phase 4, ship `ClaudeProvider` with zero tools first and prove the roundtrip. The loop-with-tools case is a superset.
- In phase 5, tools returning mock data are acceptable for the first commit that wires the loop; replace with real implementations in phase 6.
- In phase 8, if the `AbstractContainerScreen` approach for embedding the real inventory fights us, fall back to a standalone `Screen` that just looks inventory-adjacent. The packet flow is identical either way.
- No caching in v1, full stop. The `ModDocsScraper.fetchReadable(url)` method signature is the single insertion point for a v1.1 cache.

## Sources

- [SimpleImpl - Forge Documentation](https://docs.minecraftforge.net/en/latest/networking/simpleimpl/) — authoritative for `SimpleChannel` registration pattern
- [Sides - Forge Documentation](https://docs.minecraftforge.net/en/1.14.x/concepts/sides/) — client/server class-loading rules (stable across versions)
- [DistExecutor docs / Forge Issue #5942](https://github.com/MinecraftForge/MinecraftForge/issues/5942) — why `@OnlyIn` is not a class-loading safety net
- [Configuration - Forge Documentation](https://docs.minecraftforge.net/en/latest/misc/config/) — CLIENT/SERVER/COMMON semantics
- [CompletableFuture with custom executors (Baeldung)](https://www.baeldung.com/java-completablefuture-threadpool) — `thenApplyAsync(..., executor)` discipline for off-tick work
- [Spigot forum: CompletableFuture runs on server thread despite executor](https://www.spigotmc.org/threads/completablefuture-is-run-on-server-thread-despite-given-executor-service.329622/) — gotcha that informs the "always `*Async(..., executor)`" rule
- [jsoup homepage](https://jsoup.org/) — chosen HTML parser
- [Anthropic Messages API / Agent SDK overview](https://platform.claude.com/docs/en/agent-sdk/overview) — tool-use loop shape (adapt from Python/TS to Java client-loop pattern)
- [Anthropic: Building agents with the Claude Agent SDK](https://www.anthropic.com/engineering/building-agents-with-the-claude-agent-sdk) — agent loop semantics
- PROJECT.md (local) — authoritative for scope, constraints, and decisions

---
*Architecture research for: Minecraft Forge 1.20.1 mod with server-side AI dispatcher and client-side chat GUI*
*Researched: 2026-04-14*
