# Stack Research

**Domain:** Minecraft Forge 1.20.1 mod with AI (Anthropic Claude) integration and optional CurseForge API integration
**Researched:** 2026-04-14
**Confidence:** HIGH (platform toolchain + Forge APIs), MEDIUM (library-integration tradeoffs — these are verified against docs but the exact "best" HTTP client choice is a judgment call), LOW flagged where noted

---

## Executive Recommendation (one-liner)

Use **Forge 1.20.1-47.4.10 (recommended) with MDK + ForgeGradle 6.x, Java 17, Parchment mappings (2023.09.03-1.20.1)**, hand-roll a tiny `AIProvider` abstraction backed by **Java 17's `java.net.http.HttpClient` + Forge's bundled Gson 2.10**, register one **`SimpleChannel`** for chat packets, hook **`ScreenEvent.Init.Post`** on `InventoryScreen` to inject a button + render an adjacent `Screen`, and use **`ForgeConfigSpec` SERVER** for all secrets/AI config (never CLIENT). **Do NOT** pull in `anthropic-java` — it drags OkHttp+Jackson+Kotlin and forces Jar-in-Jar shadow relocation for marginal benefit over Claude's trivial REST surface.

---

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Minecraft | 1.20.1 | Platform target | Locked by project |
| Minecraft Forge | 47.4.10 (recommended) or 47.4.20 (latest) | Mod loader | 47.4.10 is the "Recommended" build on files.minecraftforge.net; 47.4.18 mentioned in PROJECT.md is between the two and fine, but pin to 47.4.10 unless a specific fix in .11–.20 is needed. HIGH confidence via files.minecraftforge.net. |
| Java JDK | 17 (Temurin/Adoptium recommended) | Language/runtime | Minecraft 1.20.1 runs on Java 17 exactly; 21 will break ForgeGradle reobf and vanilla bytecode. HIGH. |
| Gradle | 8.1.1 (wrapper shipped in MDK) | Build tool | Ships with the official 1.20.1 MDK; don't upgrade past 8.3.x without testing — ForgeGradle 6 has known friction with Gradle 8.4+. HIGH. |
| ForgeGradle | 6.0.x (current: 6.0.29) | Minecraft toolchain plugin | The only supported FG line for 1.20.1. Applied via `plugins { id 'net.minecraftforge.gradle' version '[6.0.16,6.2)' }` (MDK default range). HIGH. |
| Parchment mappings | `2023.09.03-1.20.1` | Parameter names + javadoc overlay | Layers human-readable param names on top of official Mojang mappings. Makes `p_60999_` readable as `pBlockState`. Community-standard for 1.20.1. MEDIUM-HIGH (verified via parchmentmc.org; exact date is the latest stable for 1.20.1 as of late 2024). |
| Gson | 2.10 (ships with vanilla MC 1.20.1) | JSON ser/de | Already on the classpath — zero dependency cost. Used by Mojang for datapack JSON. Pure Gson handles Claude's API shape (nested objects, arrays) trivially. HIGH. |
| `java.net.http.HttpClient` | JDK 17 built-in | HTTP client for AI + CurseForge calls from server | No extra dep, no shadowing needed, HTTP/2 + async out of the box, sufficient for JSON REST. HIGH. |

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Forge `SimpleChannel` | Built-in (`net.minecraftforge.network.NetworkRegistry` / `SimpleChannel` in 1.20.1) | Client↔server packet channel | Always — single channel `forgebook:main` carries `ChatRequestC2SPacket`, `ChatResponseS2CPacket`, `ChatErrorS2CPacket`. In 1.20.1 (Forge 47.x) use `NetworkRegistry.newSimpleChannel(...)`. The `ChannelBuilder` fluent API only appeared in NeoForge / 1.20.2+ Forge; don't cargo-cult from 1.20.4 tutorials. MEDIUM-HIGH. |
| Brigadier | Bundled with MC | Slash-command parser | Register via `@SubscribeEvent` on `RegisterCommandsEvent` → `event.getDispatcher().register(Commands.literal("forgebook").then(...))`. HIGH. |
| `ForgeConfigSpec` | Built-in (`net.minecraftforge.common.ForgeConfigSpec`) | TOML config | Used for all config fields. Registered in mod constructor via `ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC)`. HIGH. |
| JUnit 5 (Jupiter) | 5.10.x | Unit tests of pure-Java logic (prompt builders, URL extractors, rate-limiter math) | Use for anything that doesn't touch `net.minecraft.*` — no game harness needed. HIGH. |
| Mockito | 5.x | Mocking for JUnit tests | Only where pure-Java seams exist; avoid mocking Minecraft classes. MEDIUM. |
| Forge GameTest framework | Built-in | In-game integration tests | Optional; valuable for the `/forgebook item` command and packet roundtrip. Spins up a headless server via the `runGameTestServer` Gradle task. MEDIUM. |
| `mcjunitlib` | 1.20.1-compatible fork if available | JUnit inside a running MC env | Only if you need to assert against live MC state from JUnit. Historically finicky across MC versions; prefer GameTest. LOW — flag for phase research. |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| IntelliJ IDEA | IDE (user's choice per cwd: `C:\Users\Nick\IdeaProjects\ForgeBook`) | Run `./gradlew genIntellijRuns` once after MDK extraction to create `runClient` / `runServer` / `runData` run configs. |
| Gradle wrapper | Reproducible builds | Use `./gradlew` (or `gradlew.bat`) — never a system Gradle. |
| `mods.toml` / `pack.mcmeta` | Mod metadata + resource pack descriptor | Located at `src/main/resources/META-INF/mods.toml` and `src/main/resources/pack.mcmeta`. `mods.toml` declares `modId = "forgebook"`, `displayURL`, `logoFile = "logo.png"`. |
| `mixin` (optional) | Bytecode surgery | **Not needed for v1.** Forge events + `ScreenEvent.Init.Post` cover inventory GUI injection. Skip unless a later feature demands it. |
| Spotless / Checkstyle | Code formatting | Optional polish; not required for MVP. |

---

## Installation

ForgeBook is a Gradle/Java project, not npm. Setup is one-time and file-based:

```bash
# 1. Download the Forge 1.20.1-47.4.10 MDK
#    https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-mdk.zip

# 2. Extract into the (already empty) ForgeBook repo root, merging with .planning/ and .git/

# 3. From the repo root:
./gradlew genIntellijRuns     # generate run configs
./gradlew build               # first build (slow — decompiles MC)
./gradlew runClient           # smoke test
```

`build.gradle` key bits (to be authored in scaffold phase — all HIGH confidence):

```gradle
plugins {
    id 'eclipse'
    id 'idea'
    id 'maven-publish'
    id 'net.minecraftforge.gradle' version '[6.0.16,6.2)'
    id 'org.parchmentmc.librarian.forgegradle' version '1.+' // parchment
}

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

minecraft {
    mappings channel: 'parchment', version: '2023.09.03-1.20.1'
    // accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg') // add only if needed
    runs {
        client { workingDirectory project.file('run'); /* ... */ }
        server { workingDirectory project.file('run'); /* ... */ }
        data   { /* datagen */ }
        gameTestServer { /* for GameTest */ }
    }
}

dependencies {
    minecraft 'net.minecraftforge:forge:1.20.1-47.4.10'

    // No runtime deps — Gson + HttpClient come free.
    // Tests only:
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
    testImplementation 'org.mockito:mockito-core:5.11.0'
}

test { useJUnitPlatform() }
```

---

## Per-Feature Stack Decisions

### (a) Chat UI next to inventory screen

**Recommended:** Subscribe to `ScreenEvent.Init.Post` (forge event bus), check `event.getScreen() instanceof InventoryScreen`, then call `event.addListener(...)` to inject a `Button` widget into the inventory GUI. On click, open a *separate* `Screen` subclass (`ChatScreen extends Screen`) that renders *adjacent* to the inventory rather than replacing it — by storing the `InventoryScreen` reference and rendering it first in our `render()` override, then drawing the chat panel in the left margin.

**Why not** modify `InventoryScreen` itself via mixin: event-based is the idiomatic Forge path, compatible with other inventory-modifying mods (JEI, Quark, Curios), and doesn't need access-transformers.

**Why not** `RenderGuiOverlayEvent`: that's for HUD overlays drawn during gameplay, not on top of other `Screen`s. Wrong tool.

Reference: [Forge Screens docs](https://docs.minecraftforge.net/en/latest/gui/screens/). Confidence: MEDIUM-HIGH — the event name and pattern are stable for 1.20.1; exact button insertion API (`event.addListener`) should be double-checked during scaffold.

### (b) Slash commands

**Recommended:** `@SubscribeEvent` on `RegisterCommandsEvent` (fired on server startup and `/reload`). Structure:

```java
dispatcher.register(
    Commands.literal("forgebook")
        .requires(src -> src.hasPermission(config.opOnly ? 2 : 0))
        .then(Commands.literal("item")
            .executes(ctx -> runItem(ctx, null))
            .then(Commands.argument("id", ItemArgument.item(buildContext))
                .executes(ctx -> runItem(ctx, ItemArgument.getItem(ctx, "id")))))
        .then(Commands.literal("ask")
            .then(Commands.argument("query", StringArgumentType.greedyString())
                .executes(this::runAsk)))
        .then(Commands.literal("reload")
            .requires(src -> src.hasPermission(2))
            .executes(this::runReload))
);
```

HIGH confidence. Use `ItemArgument.item(ctx.buildContext())` not `ResourceLocationArgument` — it integrates with the client's autocomplete for item IDs.

### (c) HTTP client for AI + CurseForge calls (server-side)

**Recommended:** `java.net.http.HttpClient` (JDK 17 built-in).

Rationale (rank-ordered):

1. **Zero shadowing risk.** Anything that isn't part of the JDK or Minecraft's bundled classpath risks classloader conflicts with other mods or needs Jar-in-Jar relocation. `HttpClient` is part of Java itself — no relocation, no bundling, no conflicts.
2. **Feature-sufficient.** HTTP/2, async (`sendAsync` returns `CompletableFuture`), timeouts, bearer auth via `Authorization` header — covers Anthropic's Messages API and CurseForge REST entirely.
3. **Anthropic's Messages API is ~1 endpoint.** `POST https://api.anthropic.com/v1/messages` with JSON body. No SDK advantage worth the dep cost.
4. **Keeps jar small.** Matters for modpack inclusion — every MB is a sync penalty for players.

**Why not OkHttp:** Adds ~1MB + Kotlin stdlib in transitive dep trees. Needs Jar-in-Jar with shadow relocation (`com.squareup.okhttp3` → `com.forgebook.shadow.okhttp3`) to avoid colliding with any other mod that bundles OkHttp (several do). Net complexity loss for no runtime gain in this use case.

**Why not Apache HttpClient 5:** Similar bloat, older API ergonomics, synchronous-by-default.

**Why not `anthropic-java` (official SDK v2.25.0):** Pulls in OkHttp + Jackson + coroutines-style fluent API. Same shadowing problem. Claude's Messages API JSON shape is small enough (~50 lines of DTOs) that hand-rolling against `HttpClient + Gson` is less code than integrating + relocating the SDK. If streaming is ever added (explicitly out of scope for v1), revisit — the SDK's SSE handling would start to pay off.

Pattern:

```java
public final class AnthropicProvider implements AIProvider {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private static final Gson GSON = new Gson();
    private static final URI URL = URI.create("https://api.anthropic.com/v1/messages");

    @Override
    public CompletableFuture<String> complete(Prompt p, String apiKey) {
        var req = HttpRequest.newBuilder(URL)
            .timeout(Duration.ofSeconds(60))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(p.toClaudeBody())))
            .build();
        return HTTP.sendAsync(req, BodyHandlers.ofString())
            .thenApply(this::extractText);
    }
}
```

Confidence: HIGH for the approach. MEDIUM for the specific `anthropic-version` header value — pin the exact date from current Claude docs at scaffold time.

### (d) JSON library

**Recommended:** **Gson 2.10** (already bundled with vanilla MC 1.20.1).

Confidence: HIGH. Mojang uses Gson internally for every JSON file in vanilla, so it's on the mod classpath automatically. No dependency declaration needed.

**Do NOT** add Jackson, Moshi, or Gson as an explicit `implementation` dep — you'll either get a version clash with Mojang's bundled 2.10 or needlessly bloat the jar.

### (e) Anthropic SDK decision

**Recommended:** **Hand-roll** against Messages API. See (c) above for rationale.

DTOs needed (Gson-friendly POJOs):
- `ClaudeRequest { model, max_tokens, system, messages[], tools[]? }`
- `ClaudeMessage { role, content }` (where `content` is `String` or `List<ContentBlock>`)
- `ClaudeResponse { content[], stop_reason, usage }`
- `ContentBlock { type, text?, name?, input?, id? }` — unified for `text` / `tool_use` / `tool_result`

Total: ~80 lines of DTOs + 1 provider class. Smaller than the relocation Gradle config for the SDK would be.

### (f) CurseForge API

**Recommended endpoint set:** the public **CurseForge for Studios REST API** at `https://api.curseforge.com/v1/*`. Relevant endpoint for our use case:

- `GET /v1/mods/{modId}` — fetch modpack metadata by its CurseForge project ID (`curseforge_modpack_id` in config maps directly to this). Returns name, summary, description, logo URL.

**Auth:** `x-api-key: <key>` header. Keys are issued from https://console.curseforge.com/ — the server owner creates one and pastes it into `forgebook-server.toml`.

**Do NOT** use the old "Twitch/CurseForge API" URLs (api.cfwidget.com, addons-ecs.forgesvc.net) — those are deprecated/unofficial aggregators that can and do go dark.

Confidence: HIGH (verified via docs.curseforge.com/rest-api and support.curseforge.com).

### (g) Forge config: which tier for which field?

`ForgeConfigSpec` supports three `ModConfig.Type`s. The file naming convention is `<modid>-<type>.toml`.

| Config field (from PROJECT.md)     | Tier       | Why                                                                 |
|------------------------------------|------------|---------------------------------------------------------------------|
| `enable_chat_interface`            | **CLIENT** | Controls whether the in-inventory button renders — purely visual.   |
| `ai_provider`                      | **SERVER** | Server-only decision; client never calls the provider.              |
| `ai_api_key`                       | **SERVER** | Secret. Must never touch client jar or network.                     |
| `ai_model`                         | **SERVER** | Paired with api_key; provider-specific.                             |
| `curseforge_modpack_id`            | **SERVER** | Fetched server-side at startup; client doesn't need it.             |
| `curseforge_api_key`               | **SERVER** | Secret. SERVER only.                                                |
| `op_only`                          | **SERVER** | Permission gate enforced on server.                                 |
| `rate_limit_per_minute`            | **SERVER** | Enforced server-side per SteveId.                                   |
| `enable_web_search`                | **SERVER** | Server controls whether the tool is exposed to the agent.           |

**Do NOT** use `COMMON` for anything here. `COMMON` is synced to both sides and is the wrong tier for secrets. (Historically `COMMON` has confused modders; in 1.20.1 Forge, prefer explicit `CLIENT`/`SERVER`.)

Confidence: HIGH.

### (h) Networking: SimpleChannel

**Recommended pattern for Forge 1.20.1 (47.x):**

```java
public final class ForgeBookNet {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation("forgebook", "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, ChatRequestC2SPacket.class,
            ChatRequestC2SPacket::encode, ChatRequestC2SPacket::decode, ChatRequestC2SPacket::handle);
        CHANNEL.registerMessage(id++, ChatResponseS2CPacket.class,
            ChatResponseS2CPacket::encode, ChatResponseS2CPacket::decode, ChatResponseS2CPacket::handle);
        CHANNEL.registerMessage(id++, ChatErrorS2CPacket.class,
            ChatErrorS2CPacket::encode, ChatErrorS2CPacket::decode, ChatErrorS2CPacket::handle);
    }
}
```

Confidence: MEDIUM-HIGH. The `NetworkRegistry.newSimpleChannel` factory is what Forge 47.x exposes. The newer `ChannelBuilder` fluent API on the official docs "latest" page targets 1.20.4+/NeoForge — **do not** copy from there. Verify at scaffold time by reading `net.minecraftforge.network.NetworkRegistry` source for the pinned Forge build.

### (i) Mod metadata access (`IModInfo`)

**Recommended:** `ModList.get().getMods()` returns `List<IModInfo>`. Per mod:

- `getModId()` → `String`
- `getDisplayName()` → `String`
- `getDescription()` → `String`
- `getVersion()` → `ArtifactVersion`
- **`getModURL()` → `Optional<URL>`** — this is the website/wiki URL. `mods.toml`'s `displayURL = "..."` field populates it.
- `getUpdateURL()` → `Optional<URL>` — update-check URL, separate concept.
- `getLogoFile()` → `Optional<String>`

**Important correction vs. PROJECT.md:** PROJECT.md line 55 references `getDisplayURL()` — **that method does not exist** on `IModInfo`. The correct method is **`getModURL()`**, populated from the `displayURL = "..."` line in each mod's `mods.toml`. Update PROJECT.md's context accordingly or reference the method as "the URL returned by `IModInfo.getModURL()` (declared as `displayURL` in `mods.toml`)".

Confidence: HIGH (verified against `MinecraftForge/ForgeSPI` source).

### (j) Testing

**Two-tier strategy:**

1. **JUnit 5 (Jupiter) for pure Java** — prompt builders, tool-schema JSON shaping, rate-limiter logic, URL extractors. No Minecraft classes. Fast. Runs on `./gradlew test`. HIGH confidence.
2. **Forge GameTest framework for integration** — register a `gameTestServer` run config and test packet roundtrip + command execution against a headless MC server. Runs on `./gradlew runGameTestServer`. MEDIUM — well-documented for 1.18.x/1.20.x but has some boilerplate. Add in a later phase, not MVP.

**Do NOT** invest in `mcjunitlib` unless JUnit-in-live-MC is required. It's a niche library, frequently lags MC version bumps, and GameTest covers the same need through the official path.

---

## Alternatives Considered

| Recommended                         | Alternative              | When to Use Alternative |
|-------------------------------------|--------------------------|--------------------------|
| `java.net.http.HttpClient`          | OkHttp 4.x               | Only if you later need fine-grained connection pooling, custom interceptors, or SSE streaming — then Jar-in-Jar it with `shadow` relocation. |
| Hand-rolled Anthropic client        | `com.anthropic:anthropic-java:2.25.0` | If Anthropic introduces non-trivial auth (beyond `x-api-key`) or streaming becomes in-scope. |
| Gson (bundled)                      | Jackson (`com.fasterxml.jackson.core:*`) | Never for this project — redundant with bundled Gson. |
| Parchment mappings                  | Official Mojang (MojMaps) | If the team strictly wants zero unofficial deps; accept `p_60999_`-style parameter names. |
| `ForgeConfigSpec` (TOML)            | Cloth Config             | If a GUI config editor is desired in-game. Not MVP. |
| `SimpleChannel`                     | Custom `Connection` handler via `ChannelHandler` | Never, for our needs — SimpleChannel covers request/response cleanly. |
| `ScreenEvent.Init.Post` injection   | Mixin into `InventoryScreen` | Only if Forge events don't expose what's needed. For v1, events are sufficient. |

---

## What NOT to Use

| Avoid                                               | Why                                                                                                          | Use Instead                                         |
|-----------------------------------------------------|--------------------------------------------------------------------------------------------------------------|-----------------------------------------------------|
| **`com.anthropic:anthropic-java` in mod jar**       | Pulls OkHttp + Jackson + Kotlin; needs `jarJar` + relocation or it'll clash with other mods; high overhead for a 1-endpoint API. | `HttpClient + Gson` DTOs (~80 LOC).                 |
| **OpenAI / Ollama official SDKs in mod jar**        | Same bloat/shadowing story. Each provider is one REST call.                                                  | Same `AIProvider` interface implemented per-provider with `HttpClient`. |
| **Fabric API / Fabric Loader patterns**             | Different mod loader — `ClientModInitializer`, `FabricLoader.getInstance().getAllMods()`, `mixins.json` differ. | Forge equivalents above.                            |
| **`NetworkRegistry.ChannelBuilder` fluent API**     | That API is 1.20.2+/NeoForge. In Forge 47.x you'll get compile errors or subtle runtime breakage.            | `NetworkRegistry.newSimpleChannel(...)`.            |
| **`RenderGuiOverlayEvent` for the chat panel**      | That event fires during HUD rendering, not during `Screen` rendering. Panel won't show on top of inventory.  | `ScreenEvent.Render.Post` or a sibling `Screen`.     |
| **`@Mod.EventBusSubscriber` without `bus = ...`**   | Defaults changed across Forge versions; implicit bus selection causes "event never fires" bugs.              | Always specify `bus = Bus.MOD` or `bus = Bus.FORGE` explicitly. |
| **`ForgeConfigSpec.Builder` with `.sync()` calls**  | There's no config sync decorator — syncing is controlled by the `ModConfig.Type`.                            | Choose `SERVER` (syncs to client at login) or `CLIENT` (never syncs). |
| **`IModInfo.getDisplayURL()`**                      | Method doesn't exist on the interface.                                                                       | `IModInfo.getModURL()`.                             |
| **Gradle 8.4+ with ForgeGradle 6**                  | Known incompatibilities (Gradle config-cache + FG's reobf). Use 8.1.1 shipped in MDK.                         | Stick with MDK-provided wrapper version.            |
| **Shadow plugin (`com.github.johnrengelman.shadow`) for runtime deps** | Forge has first-class `jarJar` for this. Using shadow risks duplicating Forge classes into your jar.         | Forge's `jarJar { }` block (if any deps are needed). |
| **Access transformers when events suffice**         | Adds an `accesstransformer.cfg`, complicates compat.                                                         | Event hooks first; AT only as last resort.           |
| **Mojang mappings on a name basis alone**           | `p_60999_`-style parameter names are unreadable.                                                             | Layer Parchment on top of Mojang mappings.          |
| **Mixing `runtime_mappings = 'parchment'` with `mappings channel: 'official'`** | Conflicts; you'll get "mappings not found" at decompile. | Set `mappings channel: 'parchment', version: '2023.09.03-1.20.1'` and apply the `org.parchmentmc.librarian.forgegradle` plugin. |

---

## Stack Patterns by Variant

**If streaming responses are later added (polish phase):**
- Switch from `BodyHandlers.ofString()` to `BodyHandlers.ofLines()` for SSE parsing, OR
- Adopt OkHttp via `jarJar` with relocation — streaming ergonomics improve significantly.

**If Ollama (local) becomes the primary provider:**
- Point `HttpClient` at `http://localhost:11434/api/chat` — no auth header needed.
- Add a `connectTimeout` fallback (Ollama can be slow on first model load).
- Same `AIProvider` interface; just another impl.

**If modpack auto-detection is added (detect CurseForge pack from filesystem):**
- Look for `manifest.json` in the game directory root (CurseForge packs ship this).
- Parse with Gson. Then fall through to REST API for enrichment.

**If a key-bind toggle is ever added (currently out of scope):**
- Register via `RegisterKeyMappingsEvent` with `conflictContext: InGame` and **default to `InputConstants.UNKNOWN`** (unbound) — avoids keymap collisions with other mods per the PROJECT.md compatibility constraint.

---

## Version Compatibility

| Package A                              | Compatible With                          | Notes                                                                 |
|----------------------------------------|------------------------------------------|-----------------------------------------------------------------------|
| Forge 1.20.1-47.4.10                   | Java 17 (exactly)                        | Java 21 will fail; Java 16 won't have required APIs.                  |
| ForgeGradle 6.0.x                      | Gradle 8.1.1 – 8.3.x                     | Avoid Gradle 8.4+ until tested — known config-cache issues.            |
| Parchment `2023.09.03-1.20.1`          | MC 1.20.1 only                           | Do not mix version strings (e.g. `-1.20.2`) — mapping fail.            |
| `anthropic-version: 2023-06-01` header | Messages API (all current Claude models) | Pin to latest documented at scaffold time.                             |
| CurseForge REST API v1                 | Any Java HTTP client                     | Token required in `x-api-key` header for all requests.                 |
| Gson 2.10 (bundled)                    | MC 1.20.1 runtime                        | Don't declare in `dependencies { }` — it's already there.              |
| SimpleChannel protocol version string  | Forge 47.x                               | Both client and server must match or connection refused — bump the string on breaking packet changes. |

---

## Sources

- [Downloads for Minecraft Forge for Minecraft 1.20.1](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) — Confirmed 47.4.10 is "Recommended", 47.4.20 is "Latest", MDK URL pattern. HIGH.
- [Forge Documentation: Getting Started (1.20.1)](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/) — MDK layout, Java 17 requirement. HIGH.
- [Forge Documentation: The Mod Files (1.20.1)](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/modfiles/) — `mods.toml` fields including `displayURL`. HIGH.
- [Forge Documentation: Screens](https://docs.minecraftforge.net/en/latest/gui/screens/) — Screen / AbstractContainerScreen / GuiGraphics patterns. HIGH (but note "latest" may drift past 1.20.1).
- [Forge Documentation: SimpleImpl](https://docs.minecraftforge.net/en/latest/networking/simpleimpl/) — SimpleChannel registration. Verify class location for 47.x at scaffold time. MEDIUM.
- [Forge Documentation: Configuration (1.19.x, pattern unchanged in 1.20.1)](https://docs.minecraftforge.net/en/1.19.x/misc/config/) — ForgeConfigSpec CLIENT/SERVER/COMMON tiers. HIGH.
- [Forge Documentation: Jar-in-Jar (fg-5.x, pattern extended in fg-6)](https://docs.minecraftforge.net/en/fg-5.x/dependencies/jarinjar/) — Nested dep bundling. HIGH.
- [ParchmentMC: Getting Started](https://parchmentmc.org/docs/getting-started) — Parchment plugin setup and date format. HIGH.
- [Parchment repo — versions/1.20.x README](https://github.com/ParchmentMC/Parchment/blob/versions/1.20.x/README.md) — Version date strings. HIGH.
- [Anthropic Java SDK on GitHub](https://github.com/anthropics/anthropic-sdk-java) — Version 2.25.0, uses OkHttp+Jackson. HIGH.
- [Anthropic API: Messages reference](https://platform.claude.com/docs/en/api) — Endpoint, auth, version header. HIGH.
- [CurseForge for Studios REST API docs](https://docs.curseforge.com/rest-api/) — Auth via `x-api-key`, endpoint `/v1/mods/{modId}`. HIGH.
- [CurseForge: About the API and How to Apply for a Key](https://support.curseforge.com/support/solutions/articles/9000208346-about-the-curseforge-api-and-how-to-apply-for-a-key) — Key issuance from console.curseforge.com. HIGH.
- [ForgeSPI — IModInfo.java source](https://github.com/MinecraftForge/ForgeSPI/blob/master/src/main/java/net/minecraftforge/forgespi/language/IModInfo.java) — Confirmed method names: `getModURL()`, `getUpdateURL()`, `getDisplayName()`, no `getDisplayURL()`. HIGH.
- [Forge GameTest gist (SizableShrimp)](https://gist.github.com/SizableShrimp/60ad4109e3d0a23107a546b3bc0d9752) — GameTest framework usage in Forge. MEDIUM.
- [Gemwire Forge Community Wiki: SimpleChannel](https://forge.gemwire.uk/wiki/SimpleChannel) — SimpleChannel pattern reference. MEDIUM.
- [NeoForged: Networking Rework](https://neoforged.net/news/20.4networking-rework/) — Context on what changed post-1.20.1 (confirms `ChannelBuilder` fluent API is NOT in 1.20.1 Forge). MEDIUM.

---

## Flags / Open Questions for Later Phases

1. **Exact `ScreenEvent` subclass to inject the inventory button** — confirm `ScreenEvent.Init.Post` plus `event.addListener(...)` at scaffold time; if `addListener` isn't on the event, fall back to reflecting into the screen's `renderables` list or using `ScreenEvent.Render.Pre` + manual button hit-testing. Flag for Phase 2 (core scaffold).
2. **Claude `anthropic-version` header value** — pin to the most recent version string documented at implementation time; `2023-06-01` is a stable long-lived value but a newer one may unlock features (tool-use extensions).
3. **Parchment version string drift** — `2023.09.03-1.20.1` is correct as of research time. Check `parchmentmc.org/docs/getting-started` at scaffold time for a possibly-newer patch release on the 1.20.1 branch.
4. **Forge `NetworkRegistry` exact class path** — `net.minecraftforge.network.NetworkRegistry` is correct for 1.20.1 (post-1.17 rename from `fml.network`), but confirm via IDE autocomplete once MDK is resolved.
5. **GameTest vs JUnit split for packet roundtrip** — defer to a test-strategy micro-spike in the testing phase.

---

*Stack research for: Minecraft Forge 1.20.1 AI-integrated mod*
*Researched: 2026-04-14*
