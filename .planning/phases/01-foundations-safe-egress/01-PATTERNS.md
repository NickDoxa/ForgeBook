# Phase 1: Foundations & Safe Egress — Pattern Map

**Mapped:** 2026-04-15
**Files analyzed:** 31 (all NEW — greenfield phase)
**In-repo analogs found:** 0 / 31 (none exist; repo contains only `.planning/`, `.git/`, `CLAUDE.md`)
**External reference coverage:** 31 / 31 (every file has a concrete external anchor in RESEARCH.md)

> **Greenfield note.** There is no `src/` tree in ForgeBook yet. The "Closest Analog" column therefore cites the RESEARCH.md section (and, transitively, the authoritative external source) that the executor must copy shape from. Line numbers point into `.planning/phases/01-foundations-safe-egress/01-RESEARCH.md` unless otherwise noted.

---

## File Classification

Grouped by subsystem so the planner can assemble plans along clean boundaries. Each row lists `New File | Role | Data Flow | External Reference | Anchor`.

### Subsystem A — Scaffold & Build (SCAF-01/03/04/05/06/08, D-01..D-09)

| New File | Role | Data Flow | External Reference | Anchor |
|----------|------|-----------|--------------------|--------|
| `build.gradle` | build-config | build-time | RESEARCH §"Pattern 2: Gradle Plugin DSL" + §"jsoup Relocation via jarJar" | RESEARCH.md L358–414, L636–725 |
| `gradle.properties` | build-config | build-time | RESEARCH §"Pattern 2" (`jsoupVersion=1.17.2`) + Forge MDK defaults | RESEARCH.md L416 |
| `settings.gradle` | build-config | build-time | Forge MDK 1.20.1-47.4.18 unmodified | `forge-1.20.1-47.4.18-mdk.zip` settings.gradle (maven.minecraftforge.net) |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` | build-config | build-time | MDK-shipped Gradle 8.1.1 wrapper, verbatim | D-04 locks version |
| `.gitignore` | repo-config | — | RESEARCH §"Repo Files (SCAF-08, CFG-06)" | RESEARCH.md L1285–1296 |
| `LICENSE` | repo-config | — | Standard MIT text (user is author) | PROJECT.md licensing constraint |
| `THIRD_PARTY_NOTICES.md` | repo-config | — | RESEARCH §"Repo Files" jsoup attribution template | RESEARCH.md L1277–1284 |
| `logo.png` | static-asset | — | 1×1 placeholder referenced by `mods.toml` `logoFile` | RESEARCH.md L285, L1234 |
| `src/main/resources/META-INF/mods.toml` | manifest | — | RESEARCH §"mods.toml Stanza" | RESEARCH.md L1222–1258 |
| `src/main/resources/pack.mcmeta` | manifest | — | RESEARCH §"pack.mcmeta" (pack_format = 15) | RESEARCH.md L1260–1271 |
| `src/main/java/com/forgebook/ForgeBookMod.java` | entry-point | event-driven | RESEARCH §"Pattern 1: @Mod Bootstrap" | RESEARCH.md L298–356 |
| `src/main/java/com/forgebook/client/ClientSetup.java` | entry-point (client-dist-gated) | event-driven | RESEARCH §"Pattern 1" (`DistExecutor.safeRunWhenOn` target) | RESEARCH.md L346–348, CLAUDE.md §"Stack Patterns by Variant" |

### Subsystem B — Config & Secrets (CFG-01..07, D-12..D-16)

| New File | Role | Data Flow | External Reference | Anchor |
|----------|------|-----------|--------------------|--------|
| `src/main/java/com/forgebook/config/ApiKey.java` | value-type | — | RESEARCH §"ConfigSnapshot & /forgebook reload" + D-13 | RESEARCH.md L1019–1051, CONTEXT.md D-13 |
| `src/main/java/com/forgebook/config/ForgebookServerConfig.java` | config-spec | request-response (get/set) | RESEARCH §"Dual ForgeConfigSpec Registration" + CLAUDE.md §(g) config tier table | CLAUDE.md L~130 (Forge config tier table) |
| `src/main/java/com/forgebook/config/ForgebookClientConfig.java` | config-spec | request-response | Same as above, CLIENT tier, `enable_chat_interface` only | CLAUDE.md §(g), CONTEXT.md D-12 |
| `src/main/java/com/forgebook/config/ConfigSnapshot.java` | value-type (record) | — | RESEARCH §"ConfigSnapshot & /forgebook reload" | RESEARCH.md L1019–1031 |
| `src/main/java/com/forgebook/config/ConfigHolder.java` | state-holder | pub-sub (volatile ref swap) | RESEARCH §"ConfigSnapshot & /forgebook reload" | RESEARCH.md L1033–1051, CONTEXT.md D-14 |
| `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` | command | event-driven | RESEARCH §"ConfigSnapshot & /forgebook reload" (Brigadier snippet) | RESEARCH.md L1053–1065, CLAUDE.md §(b) |
| `src/main/java/com/forgebook/util/log/ApiKeyScrubFilter.java` | log-filter-plugin | transform (stream rewrite) | RESEARCH §"Log4j2 Filter Plugin (CFG-05)" + Log4j2 manual | RESEARCH.md L727–810 |
| `src/main/resources/log4j2.xml` | config-file | — | RESEARCH §"Log4j2 Filter Plugin" XML block | RESEARCH.md L790–805 |

### Subsystem C — Networking (NET-01..04, D-17..D-21)

| New File | Role | Data Flow | External Reference | Anchor |
|----------|------|-----------|--------------------|--------|
| `src/main/java/com/forgebook/network/ForgebookNetwork.java` | channel-registry | registration | RESEARCH §"Pattern 3: SimpleChannel Registration" | RESEARCH.md L418–470 |
| `src/main/java/com/forgebook/network/packet/ChatRequestPacket.java` | packet (C→S) | request-response | RESEARCH §"Pattern 4: Packet Shapes" | RESEARCH.md L474–484 |
| `src/main/java/com/forgebook/network/packet/ChatResponsePacket.java` | packet (S→C) | request-response | RESEARCH §"Pattern 4" | RESEARCH.md L486–495 |
| `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java` | packet (S→C) + `ErrorCode` enum | request-response | RESEARCH §"Pattern 4" | RESEARCH.md L497–511 |
| `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java` | handler | event-driven (network thread → executor → main) | RESEARCH §"Pattern 5: ChatRequestHandler (Echo + Executor Hop)" | RESEARCH.md L515–554 |
| `src/main/java/com/forgebook/network/chunk/ChunkedPayload.java` | utility | transform (split/join) | RESEARCH §"Packet Size Limits (NET-04)" | RESEARCH.md L1134–1144 |
| `src/main/java/com/forgebook/util/AiExecutor.java` | executor-lifecycle | event-driven | RESEARCH §"aiExecutor Lifecycle (NET-03, D-20)" | RESEARCH.md L1072–1132 |

### Subsystem D — Safe Egress (NET-05, D-22..D-26)

| New File | Role | Data Flow | External Reference | Anchor |
|----------|------|-----------|--------------------|--------|
| `src/main/java/com/forgebook/util/SafeHttpFetcher.java` | service | request-response (HTTPS egress) | RESEARCH §"SafeHttpFetcher Resolve-and-Pin" + JDK-8144566 workaround | RESEARCH.md L812–965 |
| `src/main/java/com/forgebook/util/UnsafeUrlException.java` | exception + `Reason` enum | — | RESEARCH §"SafeHttpFetcher" (uses `UnsafeUrlException.Reason.*`) + CONTEXT.md D-24 | RESEARCH.md L840, L872, L882, L894, L899 |
| `src/main/java/com/forgebook/util/Cidr.java` | utility | transform | RESEARCH §"CIDR Matcher (NET-05, D-25)" | RESEARCH.md L967–1014 |

### Subsystem E — CI & Testing (D-27..D-29, SCAF-07)

| New File | Role | Data Flow | External Reference | Anchor |
|----------|------|-----------|--------------------|--------|
| `.github/workflows/build.yml` | ci-workflow | — | RESEARCH §"GitHub Actions Workflow (D-29, SCAF-07)" | RESEARCH.md L1160–1220 |
| `src/test/java/com/forgebook/config/ApiKeyTest.java` | test (unit) | — | RESEARCH §"Testing Strategy" D-27 + JUnit 5 | RESEARCH.md L289 |
| `src/test/java/com/forgebook/config/ConfigSnapshotTest.java` | test (unit) | — | Same | RESEARCH.md L290 |
| `src/test/java/com/forgebook/util/CidrTest.java` | test (unit) | — | Same | RESEARCH.md L291 |
| `src/test/java/com/forgebook/util/SafeHttpFetcherTest.java` | test (unit, 6 `Reason` cases) | — | RESEARCH §"User Constraints" D-24 (one test per Reason) | RESEARCH.md L292, CONTEXT.md D-24 |
| `src/test/java/com/forgebook/util/AiExecutorRejectionTest.java` | test (unit) | — | RESEARCH §"User Constraints" D-21 | RESEARCH.md L293, CONTEXT.md D-21 |
| `src/test/java/com/forgebook/gametest/ChatEchoGameTest.java` | test (GameTest) | event-driven | RESEARCH §"GameTest for NET-06 E2E Packet Echo" | RESEARCH.md L1146–1158 |

---

## Pattern Assignments

### Subsystem A — Scaffold & Build

#### `build.gradle`

**External reference:** RESEARCH.md §"Pattern 2: Gradle Plugin DSL" (L358–414) and §"jsoup Relocation via jarJar" (L636–725).

**Plugin block to copy** (RESEARCH.md L362–369):
```groovy
plugins {
    id 'eclipse'
    id 'idea'
    id 'maven-publish'
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'org.parchmentmc.librarian.forgegradle' version '1.+'
    id 'java'
}
```

**Minecraft / run-configs block** (RESEARCH.md L375–397) — note the `forge.enabledGameTestNamespaces` property is required per Pitfall 8 (L625–628).

**jsoup relocation path** — use the shadow-ShadowJar-task-as-class-not-plugin approach (RESEARCH.md L697–721), **NOT** the eachFile path-rename at L666–691 (the research explicitly rejects that variant for not rewriting bytecode cross-references).

**Anti-patterns to avoid** (CLAUDE.md §"What NOT to Use"): applying the `shadow` plugin to the main project; using Gradle ≥ 8.4.

#### `gradle.properties`

**External reference:** RESEARCH.md L416.

**Required additions:**
```properties
jsoupVersion=1.17.2
```
(Everything else remains as the MDK ships it.)

#### `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`

**External reference:** Forge MDK zip `https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.18/forge-1.20.1-47.4.18-mdk.zip` — copy verbatim per CONTEXT.md D-01 bootstrap procedure.

#### `.gitignore`

**External reference:** RESEARCH.md L1285–1296.

**Exact content to copy** (RESEARCH.md L1286–1295):
```gitignore
run/
.gradle/
build/
*.toml.bak
forgebook-server.toml
!config/forgebook-server.toml
```

#### `LICENSE`

**External reference:** Standard MIT License text. Copyright holder: "Nick Doxa" (from `mods.toml` authors field, RESEARCH.md L1237).

#### `THIRD_PARTY_NOTICES.md`

**External reference:** RESEARCH.md L1277–1284 (template).

#### `logo.png`

**External reference:** 1×1 PNG placeholder. Referenced by `mods.toml` `logoFile="logo.png"` (RESEARCH.md L1234). Phase 1 ships a stub; Phase 5 polish pass replaces it.

#### `src/main/resources/META-INF/mods.toml`

**External reference:** RESEARCH.md §"mods.toml Stanza (SCAF-03)" L1222–1258.

**Dependencies block to copy** (L1243–1255) pins `forge` `[47.4.18,)` and `minecraft` `[1.20.1,1.20.2)`, `side="BOTH"` on both.

#### `src/main/resources/pack.mcmeta`

**External reference:** RESEARCH.md L1262–1269.
```json
{ "pack": { "pack_format": 15, "description": "ForgeBook resources" } }
```

#### `src/main/java/com/forgebook/ForgeBookMod.java`

**External reference:** RESEARCH.md §"Pattern 1: @Mod Bootstrap" L298–356.

**Bootstrap body to copy** (L320–353) — two `ModLoadingContext.get().registerConfig(...)` calls, mod-bus listener for `commonSetup`, forge-bus listeners for `AiExecutor::onServerStopping` and `ForgebookReloadCommand::onRegister`, and `DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> com.forgebook.client.ClientSetup::init)`.

**Also wire** (not shown in RESEARCH L320 block but required by D-20): `AiExecutor::start` on `ServerStartingEvent` — RESEARCH.md L1132 calls this out explicitly as a TODO for the bootstrap class.

#### `src/main/java/com/forgebook/client/ClientSetup.java`

**External reference:** RESEARCH.md L253, L346–348 (target of `DistExecutor.safeRunWhenOn`).

**Phase 1 surface:** a single `public static void init()` method that is a no-op with a `LOG.info("ForgeBook client initialized")` line. Phase 4 expands it. Whatever it does, it may import `net.minecraft.client.*` — this package is the **only** package allowed to per D-10.

### Subsystem B — Config & Secrets

#### `src/main/java/com/forgebook/config/ApiKey.java`

**External reference:** CONTEXT.md D-13 + RESEARCH.md L1040 / L1044 (usage sites).

**Contract** (from D-13): record wrapping raw `String`. `toString()` returns `"<redacted>"`. Raw reachable only via explicit `raw()`. CI grep-lint (RESEARCH.md L1197–1202) flags callers of `.raw()` outside `com.forgebook.ai` and `com.forgebook.integration` (both reserved for later phases — in Phase 1 nothing may call `.raw()`).

#### `src/main/java/com/forgebook/config/ForgebookServerConfig.java`

**External reference:** CLAUDE.md §(g) config-tier table + RESEARCH.md L1038–1050 (uses of `ForgebookServerConfig.*`).

**Required static fields** (derived from the `ConfigSnapshot` reads at RESEARCH.md L1038–1050):
- `AI_PROVIDER` (enum → use `defineEnum` over `AiProviderKind`)
- `AI_API_KEY` (String, default `""`)
- `AI_MODEL` (String)
- `CURSEFORGE_MODPACK_ID` (String, default `""`)
- `CURSEFORGE_API_KEY` (String, default `""`)
- `OP_ONLY` (boolean, default `true`)
- `RATE_LIMIT_PER_MINUTE` (int, default per PROJECT.md)
- `ENABLE_WEB_SEARCH` (boolean, default `false`)
- `CONFIG_VERSION` (int, default `1`)
- `SPEC` (final `ForgeConfigSpec` built by the builder)

Register tier: `ModConfig.Type.SERVER` at filename `forgebook-server.toml` (RESEARCH.md L329–332).

**Anti-pattern to avoid** (CLAUDE.md §"What NOT to Use"): calling `.sync()` on `ForgeConfigSpec.Builder` — no such decorator exists; SERVER syncs to client at login automatically.

#### `src/main/java/com/forgebook/config/ForgebookClientConfig.java`

**External reference:** CONTEXT.md D-12 + CLAUDE.md §(g).

**Contract:** one boolean field `ENABLE_CHAT_INTERFACE` (default `true`), registered as `ModConfig.Type.CLIENT` at `forgebook-client.toml` (RESEARCH.md L333–335).

#### `src/main/java/com/forgebook/config/ConfigSnapshot.java`

**External reference:** RESEARCH.md L1019–1031.

**Exact record signature:**
```java
public record ConfigSnapshot(
    AiProviderKind aiProvider,
    ApiKey aiApiKey,
    String aiModel,
    Optional<String> curseforgeModpackId,
    ApiKey curseforgeApiKey,
    boolean opOnly,
    int rateLimitPerMinute,
    boolean enableWebSearch,
    int configVersion
) {}
```
`AiProviderKind` is a sibling enum in this package (minimum: `ANTHROPIC`, `OPENAI`, `OLLAMA`; Phase 1 only needs the enum to exist for config typing — no implementations).

#### `src/main/java/com/forgebook/config/ConfigHolder.java`

**External reference:** RESEARCH.md L1033–1051.

**Core pattern to copy:**
```java
public final class ConfigHolder {
    private static volatile ConfigSnapshot current = null;
    public static ConfigSnapshot get() { return current; }
    public static void set(ConfigSnapshot s) { current = s; }
    public static ConfigSnapshot buildFromSpec() { /* L1037–1050 body */ }
}
```

**Thread-safety rationale** (RESEARCH.md L1068): `volatile` reference + immutable record = single-read consistency for packet handlers, command executors, and `aiExecutor` workers.

#### `src/main/java/com/forgebook/command/ForgebookReloadCommand.java`

**External reference:** RESEARCH.md §"ConfigSnapshot & /forgebook reload" L1053–1065.

**Core pattern to copy:**
```java
public static void onRegister(RegisterCommandsEvent event) {
    event.getDispatcher().register(
        Commands.literal("forgebook")
            .then(Commands.literal("reload")
                .requires(src -> src.hasPermission(2))   // OP-gated
                .executes(ctx -> {
                    ConfigHolder.set(ConfigHolder.buildFromSpec());
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("ForgeBook config reloaded."), true);
                    return Command.SINGLE_SUCCESS;
                })));
}
```

Wired from `ForgeBookMod` ctor via `MinecraftForge.EVENT_BUS.addListener(ForgebookReloadCommand::onRegister)` (RESEARCH.md L343).

**Directory note:** CONTEXT.md L273 reserves the `command` package for later phases but explicitly allows creating it "this phase for this one file." Keep the package scope narrow.

#### `src/main/java/com/forgebook/util/log/ApiKeyScrubFilter.java`

**External reference:** RESEARCH.md §"Log4j2 Filter Plugin (CFG-05)" L727–810 + Log4j2 manual (Extending — Filters / RewriteAppender).

**Imports & annotation pattern** (L732–743):
```java
@Plugin(name = "ApiKeyScrub", category = Filter.CATEGORY, elementType = Filter.ELEMENT_TYPE)
public final class ApiKeyScrubFilter extends AbstractFilter { ... }
```

**Pattern set to copy verbatim** (L745–751) — five regexes covering `Authorization`, `x-api-key`, `sk-ant-…`, `sk-proj-…`, `api_key=…`.

**OPEN DESIGN QUESTION flagged to planner** (RESEARCH.md L808–810): Log4j2's native `Filter` API filters pass/block, not rewrite. Production-correct choice is between:
1. `RewriteAppender` + custom `RewritePolicy` plugin (wraps existing appenders).
2. Custom `StringLayout` wrapper applied to each appender.
The research leaves this under D-16 "Claude's Discretion". **Planner must pick one and document the choice in PLAN.md.**

#### `src/main/resources/log4j2.xml`

**External reference:** RESEARCH.md L790–805.

**Critical attribute** (per Pitfall 5, L608–613): the `<Configuration>` root must include `packages="com.forgebook.util.log"` so Log4j2 picks up the plugin without an annotation processor.

### Subsystem C — Networking

#### `src/main/java/com/forgebook/network/ForgebookNetwork.java`

**External reference:** RESEARCH.md §"Pattern 3: SimpleChannel Registration (NET-01)" L418–470.

**Channel construction** (L432–437):
```java
public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
    new ResourceLocation("forgebook", "main"),
    () -> PROTOCOL_VERSION,
    PROTOCOL_VERSION::equals,
    PROTOCOL_VERSION::equals
);
```

**Register method** (L439–466) — critical asymmetry: `ChatRequestPacket` uses `consumerNetworkThread` (handler must schedule on `aiExecutor` before touching main), `ChatResponsePacket` and `ChatErrorPacket` use `consumerMainThread` (client-side state mutation).

**Anti-pattern to avoid** (CLAUDE.md §"What NOT to Use"): `NetworkRegistry.ChannelBuilder` fluent API — that's NeoForge / 1.20.2+.

#### `src/main/java/com/forgebook/network/packet/ChatRequestPacket.java`

**External reference:** RESEARCH.md §"Pattern 4: Packet Shapes (NET-02)" L474–484.

**Record + codec pattern:** record of `(UUID requestId, String message)`, static `encode/decode(FriendlyByteBuf)` using `writeUUID` / `writeUtf(max=32_000)` / matching reads.

#### `src/main/java/com/forgebook/network/packet/ChatResponsePacket.java`

**External reference:** RESEARCH.md L486–495. Same shape as `ChatRequestPacket` plus a client-side `handleOnClient(ChatResponsePacket, Supplier<NetworkEvent.Context>)` — Phase 1 body is just `LOG.info(...)` (Phase 4 replaces with UI append).

#### `src/main/java/com/forgebook/network/packet/ChatErrorPacket.java`

**External reference:** RESEARCH.md L497–511.

**Enum + record pattern:**
```java
public enum ErrorCode { OVERLOADED, TRANSPORT, RATE_LIMITED, FORBIDDEN, PROVIDER, DISABLED }
public record ChatErrorPacket(UUID requestId, ErrorCode code, String humanReadable) { ... }
```
Codec uses `writeEnum` / `readEnum(ErrorCode.class)` + `writeUtf(max=512)`. Phase 1 only emits `OVERLOADED`; other codes declared so Phase 3 doesn't need a wire-schema change.

#### `src/main/java/com/forgebook/network/handler/ChatRequestHandler.java`

**External reference:** RESEARCH.md §"Pattern 5: ChatRequestHandler (Echo + Executor Hop, D-19)" L515–554.

**Core pattern to copy** (L530–553):
```java
public static void handle(ChatRequestPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
    NetworkEvent.Context ctx = ctxSupplier.get();
    ServerPlayer sender = ctx.getSender();
    ctx.setPacketHandled(true);
    if (sender == null) return;
    try {
        AiExecutor.get().submit(() -> {
            String reply = "echo: " + pkt.message();
            ChatResponsePacket resp = new ChatResponsePacket(pkt.requestId(), reply);
            ctx.enqueueWork(() ->
                ForgebookNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sender), resp));
        });
    } catch (RejectedExecutionException e) {
        ChatErrorPacket err = new ChatErrorPacket(pkt.requestId(),
            ErrorCode.OVERLOADED, "Server is busy. Try again.");
        ForgebookNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender), err);
    }
}
```

**Anti-patterns called out** (RESEARCH.md L556–564):
- Wrapping HTTP/executor work inside `enqueueWork` (inverts D-19 — freezes server tick).
- Using `consumerMainThread` for the C→S packet (auto-enqueues before the executor hop).

#### `src/main/java/com/forgebook/network/chunk/ChunkedPayload.java`

**External reference:** RESEARCH.md §"Packet Size Limits (NET-04)" L1134–1144.

**Phase 1 surface:** utility with `split(String, 32_768)` producing ordered chunks plus a re-assembly `Collector`. No production call site in Phase 1 — existence + a JUnit test satisfies NET-04 per RESEARCH.md L1141. Phase 2's provider-response path will wire it in.

#### `src/main/java/com/forgebook/util/AiExecutor.java`

**External reference:** RESEARCH.md §"aiExecutor Lifecycle (NET-03, D-20)" L1072–1132.

**Lifecycle pattern to copy verbatim** (L1083–1129): fixed 4/4 `ThreadPoolExecutor`, `ArrayBlockingQueue(64)`, `AbortPolicy`, thread factory naming `forgebook-ai-N`, `setDaemon(false)`, `start()` / `onServerStopping()` / `shutdown → awaitTermination(5s) → shutdownNow()`.

**Lifecycle wiring** (RESEARCH.md L1132): `start()` on `ServerStartingEvent`, `onServerStopping` on `ServerStoppingEvent` — both listeners registered from `ForgeBookMod` ctor (`MinecraftForge.EVENT_BUS.addListener(AiExecutor::onServerStopping)` is shown at RESEARCH.md L342; a parallel line for `ServerStartingEvent → AiExecutor::start` must be added by the planner).

### Subsystem D — Safe Egress

#### `src/main/java/com/forgebook/util/SafeHttpFetcher.java`

**External reference:** RESEARCH.md §"SafeHttpFetcher Resolve-and-Pin (NET-05, D-22/D-23)" L812–965.

**Pinned-IP fetch loop** (L836–903): iterate up to `MAX_REDIRECTS`, validate scheme/IP/content-type on each hop, stream-read with size-cap counting (DO NOT trust `Content-Length` — see Pitfall 6, L615–618).

**SNI workaround — mandatory** (L915–962 + L585–590 pitfall):
- `SniSocketFactory` sets `SNIHostName(originalHost)` on each created socket.
- `OriginalHostVerifier` delegates to the default verifier but with the ORIGINAL hostname, not the pinned IP.
- Both must be set on the `HttpsURLConnection` before `connect()` is called.

**Constants (copy verbatim from L828–832):**
```java
public static final long SIZE_CAP = 1_048_576L;
public static final int TIMEOUT_MS = 15_000;
public static final int MAX_REDIRECTS = 3;
private static final Set<String> CONTENT_ALLOWLIST = Set.of(
    "text/html", "text/plain", "application/xhtml+xml");
```

**Anti-pattern to avoid** (RESEARCH.md L562): using `HttpClient.followRedirects(NORMAL)`. Must be `Redirect.NEVER` / `setInstanceFollowRedirects(false)`.

#### `src/main/java/com/forgebook/util/UnsafeUrlException.java`

**External reference:** CONTEXT.md D-24 + RESEARCH.md L840–899 (usage sites).

**Contract:** checked exception carrying a public `enum Reason { SCHEME, PRIVATE_IP, REDIRECT_LIMIT, SIZE_CAP, CONTENT_TYPE, TIMEOUT }`. Exactly six values to match Success Criterion #4 and D-24's 1:1 unit-test mapping.

#### `src/main/java/com/forgebook/util/Cidr.java`

**External reference:** RESEARCH.md §"CIDR Matcher (NET-05, D-25)" L967–1014.

**Block list to copy verbatim** (L981–986):
```java
private static final List<Block> BLOCKED = List.of(
    parse("127.0.0.0/8"),  parse("10.0.0.0/8"),
    parse("172.16.0.0/12"),parse("192.168.0.0/16"),
    parse("169.254.0.0/16"),parse("0.0.0.0/8"),
    parse("::1/128"),      parse("fc00::/7"),    parse("fe80::/10")
);
```

**Bit-mask match pattern** (L997–1004): `fullBytes = prefix/8`, `partialBits = prefix%8`, apply byte-level `==` on full bytes then masked byte compare on the partial.

### Subsystem E — CI & Testing

#### `.github/workflows/build.yml`

**External reference:** RESEARCH.md §"GitHub Actions Workflow (D-29, SCAF-07)" L1160–1220.

**Required steps (in order):**
1. `actions/checkout@v4`
2. `actions/setup-java@v4` (Temurin 17)
3. Gradle cache (`~/.gradle/caches`, `~/.gradle/wrapper`, `~/.gradle/caches/forge_gradle`)
4. Firewall lint grep (L1191–1195) — no `net.minecraft.client.*` imports outside `com.forgebook.client`
5. `ApiKey.raw()` caller lint (L1197–1202) — allowed only in `com.forgebook.(ai|integration)` (both empty in Phase 1 → any `.raw()` call fails the build)
6. `./gradlew --no-daemon build`
7. `./gradlew --no-daemon runGameTestServer`
8. Classloader-leak smoke grep on `run/gametest/logs/latest.log` for `NoClassDefFoundError.*net/minecraft/client` (L1210–1217)

**Critical note** (RESEARCH.md L1213–1217): the smoke step must grep the log file, NOT rely on exit code — dedicated servers can exit cleanly even when a client class fails to load at startup.

#### Unit tests (src/test/java/com/forgebook/...)

**External reference:** CONTEXT.md D-27, D-21, D-24 + RESEARCH.md L288–294.

- **`ApiKeyTest`:** assert `new ApiKey("sk-xxx").toString().equals("<redacted>")`; assert `.raw()` returns the original string.
- **`ConfigSnapshotTest`:** assert record fields are correctly wired from `buildFromSpec()`; assert the snapshot is immutable (all accessors exist, no setters).
- **`CidrTest`:** for each of the nine blocked ranges, pick a representative address and assert `Cidr.isBlocked` returns `true`; pick a public address and assert `false`.
- **`SafeHttpFetcherTest`:** exactly six methods, one per `UnsafeUrlException.Reason`. Use a local mini-HTTP server (or stub the JDK classes where tractable) — `Reason.SCHEME` can be tested without any network (pass `http://...`).
- **`AiExecutorRejectionTest`:** start executor, submit 65 never-returning tasks (> queue capacity 64 + 4 active = 68 boundary), assert the 69th triggers `RejectedExecutionException`, then verify the handler translates it to a `ChatErrorPacket(OVERLOADED)`.

#### `src/test/java/com/forgebook/gametest/ChatEchoGameTest.java`

**External reference:** RESEARCH.md §"GameTest for NET-06 E2E Packet Echo (D-28)" L1146–1158.

**OPEN DESIGN QUESTION flagged to planner** (L1151–1157): true wire-level C→S→C round-trip requires a two-process harness that CI can't easily do. RESEARCH recommends a server-only assertion pattern: invoke `ChatRequestHandler.handle` with a fake `Context`, verify `aiExecutor.submit` is called, and that the submitted task ultimately calls `enqueueWork` with a matching `ChatResponsePacket`. **Planner must decide whether to use a server-only GameTest assertion or defer true MP wire coverage to Phase 5.**

**Gradle wiring** (RESEARCH.md L389–395, L628): `gameTestServer` run config must set `forge.enabledGameTestNamespaces=forgebook` to avoid executing vanilla's tests.

---

## Shared Patterns

### S-1. Event-Bus Registration Discipline

**Applies to:** `ForgeBookMod.java`, `AiExecutor.java`, `ForgebookReloadCommand.java`, plus any future listener.

**Source:** CLAUDE.md §"What NOT to Use" (explicit-bus rule) + RESEARCH.md L337–343.

**Rule:** every `@SubscribeEvent` class must specify both `bus = …` and `modid = …` on `@Mod.EventBusSubscriber`. Two buses in play:
- `FMLJavaModLoadingContext.get().getModEventBus()` — registration events (`FMLCommonSetupEvent`, `RegisterCommandsEvent` is NOT here — it's on the Forge bus), config events.
- `MinecraftForge.EVENT_BUS` — game-lifecycle events (`ServerStartingEvent`, `ServerStoppingEvent`, `RegisterCommandsEvent`).

**Anti-pattern:** `@Mod.EventBusSubscriber` without `bus = …` — default shifts across Forge versions.

### S-2. Immutable-Snapshot via Volatile-Reference

**Applies to:** `ConfigHolder` (Phase 1); will apply to any future config-adjacent state holder.

**Source:** RESEARCH.md L1033–1051 + L1068.

**Rule:** state holder declares `private static volatile T current`; getter returns `current`; setter performs a single volatile-reference assignment to an already-built immutable `T`. Multiple consuming threads read the reference once at request entry; consistency follows from `T` being a deeply-immutable record.

### S-3. Secrets Never Touch `toString()` / Logs

**Applies to:** `ApiKey` + Log4j2 scrubber (belt-and-braces).

**Source:** CONTEXT.md D-13 + D-16.

**Rule #1 (value-type):** `ApiKey.toString()` returns `"<redacted>"`; raw only via `.raw()`; CI grep (RESEARCH.md L1197–1202) enforces call-site allowlist.

**Rule #2 (global):** Log4j2 filter/rewrite plugin at the root logger catches anything library code might log — required because neither jsoup nor `HttpClient` nor Forge know about `ApiKey`.

### S-4. Off-Tick HTTP — Executor First, `enqueueWork` Only for Game-State Touch

**Applies to:** `ChatRequestHandler` (Phase 1); every future handler that calls a provider (Phase 2+).

**Source:** RESEARCH.md Pattern 5 (L515–554) + Pitfall 3 (L597–601) + CONTEXT.md D-19.

**Rule:** handlers schedule `aiExecutor.submit(...)`; only the final game-state mutation is wrapped in `ctx.enqueueWork(...)`. Reverse the nesting and the server tick freezes on every HTTP call.

### S-5. Dist-Gated Client Imports

**Applies to:** every file under `src/main/java/`.

**Source:** CONTEXT.md D-10, D-11 + RESEARCH.md L1191–1195 (CI enforcement).

**Rule:** `import net.minecraft.client.*` is permitted only inside `com.forgebook.client.*`. Every client-entry path goes through `DistExecutor.safeRunWhenOn(Dist.CLIENT, ...)`. The CI firewall-lint grep is the compile-time enforcement.

### S-6. Packet-Encode Length Caps

**Applies to:** all three packets in `com.forgebook.network.packet`.

**Source:** RESEARCH.md L479 (`writeUtf(message, 32_000)`), L503 (`writeUtf(humanReadable, 512)`).

**Rule:** every `FriendlyByteBuf.writeUtf` / `readUtf` call must take an explicit max-length argument. Prevents a malicious peer from OOMing the opposite side with a 2 GiB string.

---

## No Analog Found

The entire phase has no in-repo analog — this is a greenfield bootstrap. Every file listed above derives its shape from an external reference (RESEARCH.md section, Forge docs, CLAUDE.md anti-pattern entry). The planner should treat RESEARCH.md as the canonical shape source and CLAUDE.md as the anti-pattern / "What NOT to Use" reference.

| File | Reason no analog | Fallback |
|------|------------------|----------|
| (all 31 files) | Greenfield repo — `src/` tree is created in this phase | External reference table above |

---

## Files Requiring a Design Decision (flagged to planner)

Two files carry unresolved design choices under CONTEXT.md "Claude's Discretion" that the planner must lock before PLAN.md ships:

### Flag #1 — `src/main/java/com/forgebook/util/log/ApiKeyScrubFilter.java`

**Decision required:** choose between `Filter` + message rewrite path vs. `RewriteAppender` + `RewritePolicy` vs. `StringLayout` wrapper.

**Source:** CONTEXT.md D-16 "Claude's Discretion" + RESEARCH.md L808–810 ("Honest note").

**Guidance from research:** Log4j2's `Filter` API natively filters pass/block, not rewrite — the simpler correct path is a `RewriteAppender` + custom `RewritePolicy` plugin. But the plugin must still be registered with `packages="com.forgebook.util.log"` in `log4j2.xml` (Pitfall 5, L608–613).

### Flag #2 — `build.gradle` (jsoup relocation task)

**Decision required:** one-off `Jar` subclass with `eachFile` path rename (simple, but does NOT rewrite bytecode cross-references) vs. `ShadowJar` task class used without applying the shadow plugin (correct bytecode relocation, recommended by research).

**Source:** CONTEXT.md D-08 "Claude's Discretion" + RESEARCH.md L638–725.

**Guidance from research:** L693–695 explicitly flags the path-rename-only variant as insufficient for jsoup because internal class-to-class references are compiled names. L697–723 shows the correct `ShadowJar-task-as-class` variant. Planner should pick this and note why the path-rename variant was rejected.

### Minor Discretion Items (not file-scoped)

Also under CONTEXT.md Claude's Discretion (L67–73) but not requiring pre-plan decisions — they surface naturally during execution:
- Specific jsoup patch version → `1.17.2` already recommended by RESEARCH.md L134, L416.
- CI provider → GitHub Actions locked by D-29.
- Package split under `com.forgebook.network` → RESEARCH.md L261–270 recommends `.packet / .handler / .chunk`.
- Thread-naming pattern → RESEARCH.md L1099 shows `forgebook-ai-N`.

---

## Metadata

**Analog search scope:** `C:\Users\Nick\IdeaProjects\ForgeBook\src\` (confirmed empty via Glob `src/**/*`)
**Files scanned:** 0 existing sources (repo is greenfield pre-scaffold)
**External references resolved:** RESEARCH.md (31 distinct anchors), CLAUDE.md (anti-patterns + config-tier table), Forge MDK zip (build wrapper + examplemod layout)
**Pattern extraction date:** 2026-04-15
