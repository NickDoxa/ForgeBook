## Project

**ForgeBook**

ForgeBook is a Minecraft Forge 1.20.1 mod that bridges AI with the in-game experience so players can understand the mods they're using. It ships two player-facing surfaces: a chat UI docked inside the inventory screen that lets players ask questions of an AI agent with internet and mod-list awareness, and a `/forgebook item` command that answers "what can I do with this?" for any mod-added item — grounded in the mod's documentation URL (and, when configured, the modpack's CurseForge metadata).

**Core Value:** A player holding an unfamiliar item from an unfamiliar mod gets a grounded, trustworthy answer about what it does and how to use it — without alt-tabbing to a wiki.

### Constraints

- **Tech stack**: Forge 1.20.1, Forge 47.4.18, Java 17, Gradle + ForgeGradle 6.x — locked by platform requirement
- **Distribution**: Client + server require mod installed — simplifies networking and UI determinism
- **Compatibility**: Must not conflict with common QoL mods (JEI/REI, Jade, etc.) — avoid global keybinds, render chat UI as an overlay screen not a replacement
- **Secrets**: AI API key and CurseForge API key must never be sent to clients — all outbound AI requests originate from the server process
- **Cost**: Default guardrails must prevent a single malicious player from draining the server owner's API budget — OP-only by default + per-player rate limit when opened up
- **Asset sourcing**: GUI assets must be either vanilla-reused or permissively licensed public assets — no assets scraped from copyrighted sources
- **Licensing**: Open source — MIT default; user can re-license before first tagged release
## Technology Stack

## Executive Recommendation (one-liner)
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
## Installation
# 1. Download the Forge 1.20.1-47.4.10 MDK
#    https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-mdk.zip
# 2. Extract into the (already empty) ForgeBook repo root, merging with .planning/ and .git/
# 3. From the repo root:
## Per-Feature Stack Decisions
### (a) Chat UI next to inventory screen
### (b) Slash commands
### (c) HTTP client for AI + CurseForge calls (server-side)
### (d) JSON library
### (e) Anthropic SDK decision
- `ClaudeRequest { model, max_tokens, system, messages[], tools[]? }`
- `ClaudeMessage { role, content }` (where `content` is `String` or `List<ContentBlock>`)
- `ClaudeResponse { content[], stop_reason, usage }`
- `ContentBlock { type, text?, name?, input?, id? }` — unified for `text` / `tool_use` / `tool_result`
### (f) CurseForge API
- `GET /v1/mods/{modId}` — fetch modpack metadata by its CurseForge project ID (`curseforge_modpack_id` in config maps directly to this). Returns name, summary, description, logo URL.
### (g) Forge config: which tier for which field?
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
### (h) Networking: SimpleChannel
### (i) Mod metadata access (`IModInfo`)
- `getModId()` → `String`
- `getDisplayName()` → `String`
- `getDescription()` → `String`
- `getVersion()` → `ArtifactVersion`
- **`getModURL()` → `Optional<URL>`** — this is the website/wiki URL. `mods.toml`'s `displayURL = "..."` field populates it.
- `getUpdateURL()` → `Optional<URL>` — update-check URL, separate concept.
- `getLogoFile()` → `Optional<String>`
### (j) Testing
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
## Stack Patterns by Variant
- Switch from `BodyHandlers.ofString()` to `BodyHandlers.ofLines()` for SSE parsing, OR
- Adopt OkHttp via `jarJar` with relocation — streaming ergonomics improve significantly.
- Point `HttpClient` at `http://localhost:11434/api/chat` — no auth header needed.
- Add a `connectTimeout` fallback (Ollama can be slow on first model load).
- Same `AIProvider` interface; just another impl.
- Look for `manifest.json` in the game directory root (CurseForge packs ship this).
- Parse with Gson. Then fall through to REST API for enrichment.
- Register via `RegisterKeyMappingsEvent` with `conflictContext: InGame` and **default to `InputConstants.UNKNOWN`** (unbound) — avoids keymap collisions with other mods per the PROJECT.md compatibility constraint.
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
## Flags / Open Questions for Later Phases
