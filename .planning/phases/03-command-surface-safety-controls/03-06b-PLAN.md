---
phase: 03-command-surface-safety-controls
plan: "06b"
type: execute
wave: 5
depends_on: ["01", "02", "03", "04", "05", "06a"]
files_modified:
  - src/main/java/com/forgebook/command/ItemSubcommand.java
  - src/main/java/com/forgebook/command/AskSubcommand.java
  - src/main/java/com/forgebook/command/AdminSubcommands.java
  - src/test/java/com/forgebook/command/ItemSubcommandTest.java
  - src/test/java/com/forgebook/command/AskSubcommandTest.java
  - src/test/java/com/forgebook/command/AdminSubcommandsTest.java
autonomous: false
requirements:
  - CMD-02
  - CMD-03
  - CMD-05
  - CMD-06
tags: [item, ask, admin, subcommand-bodies, rag-pipeline, kill-switch, stats, in-game-smoke]

must_haves:
  truths:
    - "/forgebook item with no args targets the held item; with <item> argument uses ItemArgument.getItem(ctx, 'item')"
    - "ItemSubcommand and AskSubcommand call Authorizer.authorize BEFORE AiExecutor.submit on the tick thread (SAFE-06 invariant mirrored)"
    - "ItemSubcommand resolves modURL via IModInfo.getModURL() — never getDisplayURL()"
    - "/forgebook disable flips KillSwitch.setDisabled(true); /forgebook enable flips false; feedback uses sendSuccess(..., true) to broadcast to OPs"
    - "/forgebook stats sends StatsAccumulator.render() as a Component.literal (broadcast=false)"
    - "All stub UnsupportedOperationException bodies from Plan 06a are replaced; grep -c 'Plan 06b pending' across the three files returns 0"
  artifacts:
    - path: "src/main/java/com/forgebook/command/ItemSubcommand.java"
      provides: "executeHeld + executeWithArg — resolves modId + modURL, authorizes, submits RagItemPipeline via AiExecutor"
      contains: "RagItemPipeline.run"
    - path: "src/main/java/com/forgebook/command/AskSubcommand.java"
      provides: "execute(CommandContext) — authorizes then dispatches ASK via AiDispatcher (tools-enabled path)"
      contains: "AiDispatcher.INSTANCE.dispatch"
    - path: "src/main/java/com/forgebook/command/AdminSubcommands.java"
      provides: "executeDisable/executeEnable/executeStats — OP-gated synchronous KillSwitch + StatsAccumulator operations"
      contains: "KillSwitch.setDisabled"
  key_links:
    - from: "src/main/java/com/forgebook/command/ItemSubcommand.java"
      to: "src/main/java/com/forgebook/ai/RagItemPipeline.java"
      via: "AiExecutor.submit(() -> RagItemPipeline.run(...))"
      pattern: "RagItemPipeline\\.run"
    - from: "src/main/java/com/forgebook/command/AskSubcommand.java"
      to: "src/main/java/com/forgebook/ai/AiDispatcher.java"
      via: "AiDispatcher.INSTANCE.dispatch(new DispatchContext(...))"
      pattern: "AiDispatcher\\.INSTANCE\\.dispatch"
---

<objective>
Wave 5 — fill in the three subcommand executor bodies whose stubs Plan 06a registered in the Brigadier tree. ItemSubcommand performs tick-thread authorize then off-tick RagItemPipeline dispatch; AskSubcommand performs tick-thread authorize then off-tick AiDispatcher dispatch with a server.execute hop for feedback; AdminSubcommands runs three synchronous OP operations (disable / enable / stats) on the tick thread.

Purpose: Delivers CMD-02 (item held + argument forms), CMD-03 (disable/enable), and CMD-04 (stats). This is the last implementation plan of Phase 3; after this plan plus the in-game smoke checkpoint, all 13 Phase 3 requirements close.

Output: Three subcommand body rewrites (stubs from Plan 06a are replaced) + three unit test files. An in-game smoke test checkpoint (task 4) verifies the full six-subcommand surface end-to-end against a dev server.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/03-command-surface-safety-controls/03-RESEARCH.md
@.planning/phases/03-command-surface-safety-controls/03-PATTERNS.md
@.planning/phases/03-command-surface-safety-controls/03-01-PLAN.md
@.planning/phases/03-command-surface-safety-controls/03-02-PLAN.md
@.planning/phases/03-command-surface-safety-controls/03-03-PLAN.md
@.planning/phases/03-command-surface-safety-controls/03-04-PLAN.md
@.planning/phases/03-command-surface-safety-controls/03-05-PLAN.md
@.planning/phases/03-command-surface-safety-controls/03-06a-PLAN.md

# Existing analogs
@src/main/java/com/forgebook/network/handler/ChatRequestHandler.java
@src/main/java/com/forgebook/tool/impl/ListInstalledModsTool.java
@src/main/java/com/forgebook/util/AiExecutor.java
@src/main/java/com/forgebook/config/ConfigHolder.java
@src/main/java/com/forgebook/config/ConfigSnapshot.java

# Phase 3 outputs (Plans 01-06a)
@src/main/java/com/forgebook/ai/RequestKind.java
@src/main/java/com/forgebook/ai/DispatchContext.java
@src/main/java/com/forgebook/ai/RagItemPipeline.java
@src/main/java/com/forgebook/ai/AiDispatcher.java
@src/main/java/com/forgebook/safety/Authorizer.java
@src/main/java/com/forgebook/safety/KillSwitch.java
@src/main/java/com/forgebook/safety/RateLimiter.java
@src/main/java/com/forgebook/safety/RateLimiterHolder.java
@src/main/java/com/forgebook/safety/RequestAuditLogger.java
@src/main/java/com/forgebook/safety/StatsAccumulator.java
@src/main/java/com/forgebook/command/ForgebookCommands.java
@src/main/java/com/forgebook/command/ItemSubcommand.java
@src/main/java/com/forgebook/command/AskSubcommand.java
@src/main/java/com/forgebook/command/AdminSubcommands.java

<interfaces>
<!-- Types from Plan 01/02/03/04 — these are the contracts subcommand bodies consume. -->

```java
RequestKind { CHAT_UI, ASK, ITEM }
DispatchContext(String message, ServerPlayer sender, RequestKind kind)
AiDispatcher.Result dispatch(DispatchContext dc)   // sealed: Reply(text, truncated) | Error(code, humanReadable)
sealed Authorizer.Result permits Allowed, Denied
Authorizer.Result authorize(ConfigSnapshot, ServerPlayer, RequestKind, RateLimiter)
Authorizer.Denied.code() -> ErrorCode; .humanReadable() -> String
RateLimiterHolder.get() -> RateLimiter
KillSwitch.isDisabled() / setDisabled(boolean)
StatsAccumulator.render() -> String  // top-10 players + aggregates, chat-bounded
RequestAuditLogger.logSuccess/logFailure/logDenied(...)
RagItemPipeline.run(CommandSourceStack, ServerPlayer, String modId, String itemId, Optional<URL> modURL, RequestKind)
AiExecutor.get() -> ExecutorService
```

```java
ItemStack.isEmpty() -> boolean
ItemStack.getItem() -> Item
ForgeRegistries.ITEMS.getKey(Item) -> ResourceLocation
ResourceLocation.getNamespace() -> String
ResourceLocation.toString() -> String
ModList.get().getModContainerById(String) -> Optional<ModContainer>
ModContainer.getModInfo() -> IModInfo
IModInfo.getModURL() -> Optional<URL>   // NOT getDisplayURL()
ItemArgument.getItem(CommandContext, String) -> ItemInput
ItemInput.createItemStack(int count, boolean allowOversizedStacks) -> ItemStack
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: ItemSubcommand — held/arg item resolution + modURL lookup + RagItemPipeline submission</name>
  <files>src/main/java/com/forgebook/command/ItemSubcommand.java, src/test/java/com/forgebook/command/ItemSubcommandTest.java</files>
  <read_first>
    - src/main/java/com/forgebook/ai/RagItemPipeline.java (Plan 04 — public entry point signature)
    - src/main/java/com/forgebook/tool/impl/ListInstalledModsTool.java lines 82-95 (modURL resolution idiom)
    - src/main/java/com/forgebook/network/handler/ChatRequestHandler.java (executor-hop pattern)
    - .planning/phases/03-command-surface-safety-controls/03-PATTERNS.md lines 78-118 (ItemSubcommand analog mapping)
  </read_first>
  <behavior>
    - `executeHeld`: resolves sender; reads main-hand `ItemStack`; if empty sends failure "Hold an item in your main hand, or use /forgebook item <id>." and returns 0. Otherwise delegates to private `resolveAndDispatch`.
    - `executeWithArg`: resolves sender; resolves `ItemStack` via `ItemArgument.getItem(ctx, "item").createItemStack(1, false)`; lets any `CommandSyntaxException` propagate so Brigadier renders the standard error. Delegates to `resolveAndDispatch`.
    - `resolveAndDispatch` shared pipeline: `ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem())`; extract `modId = rl.getNamespace()`, `itemId = rl.toString()`; resolve `Optional<URL> modURL = ModList.get().getModContainerById(modId).map(c -> c.getModInfo()).flatMap(IModInfo::getModURL)`.
    - SAFE-06 tick-thread mirror: single `ConfigHolder.get()` load (D-14), call `Authorizer.authorize(snap, player, RequestKind.ITEM, RateLimiterHolder.get())` BEFORE any `AiExecutor.submit`. On `Denied` emit `RequestAuditLogger.logDenied(...)` + `sendFailure(humanReadable)` + return 0. On `Allowed` submit a task that runs `RagItemPipeline.run(src, player, modId, itemId, modURL, RequestKind.ITEM)` inside `AiExecutor.get().submit(...)`.
    - Executor rejection handling: catch `RejectedExecutionException`, emit `logFailure(OVERLOADED)` + `sendFailure("Server is busy. Try again.")`.
    - v1 threading limitation (Pitfall 2): `RagItemPipeline.run` calls `src.sendSuccess` / `sendFailure` synchronously from inside its own body. Because the pipeline runs inside the AiExecutor task, those sends execute off-tick. A `// TODO(v2)` comment adjacent to the `AiExecutor.get().submit(...)` call MUST reference "off-tick sendSuccess" or "Pitfall 2". v2 fix is a one-line pipeline-internal `src.getServer().execute(...)` wrap; out of scope here.
  </behavior>
  <tradeoff>
    **Off-tick feedback trade-off (v1 accepted, v2 tracked):** The cleanest design wraps each `sendSuccess`/`sendFailure` in `src.getServer().execute(...)` so they always run on the tick thread. `RagItemPipeline` (Plan 04) currently calls those methods directly from its body. Because the pipeline is invoked inside `AiExecutor.submit`, the feedback runs on the AI executor thread rather than the tick thread.

    Minecraft 1.20.1 tolerates `sendSuccess` from non-tick threads in practice, but a player who disconnects mid-request could cause `NetworkPipelineException` (Pitfall 2 in RESEARCH). Three fixes were considered:
    1. Wrap the entire `RagItemPipeline.run` call in `server.execute(...)` — WRONG; moves HTTP+provider work onto the tick thread.
    2. Proxy `CommandSourceStack` so every send auto-hops — too much machinery for one use site.
    3. Change `RagItemPipeline` to hop internally (`src.getServer().execute(() -> src.sendSuccess(...))`) — correct, one-line diff, but belongs to a Plan 04 revision.

    v1 ships with fix #3 deferred. Risk is limited to disconnected-player corner cases, which log as exceptions without affecting correctness for connected players. The `// TODO(v2)` breadcrumb below keeps the follow-up discoverable.
  </tradeoff>
  <action>
    **Replace the stub body of `src/main/java/com/forgebook/command/ItemSubcommand.java`** created in Plan 06a. Keep the package + private constructor; replace the two method bodies and add the helper.

    Required imports (add to the existing stub):
    - `import com.forgebook.ai.RagItemPipeline;`
    - `import com.forgebook.ai.RequestKind;`
    - `import com.forgebook.config.ConfigHolder;`
    - `import com.forgebook.config.ConfigSnapshot;`
    - `import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;`
    - `import com.forgebook.safety.Authorizer;`
    - `import com.forgebook.safety.RateLimiterHolder;`
    - `import com.forgebook.safety.RequestAuditLogger;`
    - `import com.forgebook.util.AiExecutor;`
    - `import com.mojang.brigadier.Command;`
    - `import net.minecraft.commands.arguments.item.ItemArgument;`
    - `import net.minecraft.commands.arguments.item.ItemInput;`
    - `import net.minecraft.network.chat.Component;`
    - `import net.minecraft.resources.ResourceLocation;`
    - `import net.minecraft.server.level.ServerPlayer;`
    - `import net.minecraft.world.InteractionHand;`
    - `import net.minecraft.world.item.ItemStack;`
    - `import net.minecraftforge.fml.ModList;`
    - `import net.minecraftforge.registries.ForgeRegistries;`
    - `import org.apache.logging.log4j.LogManager;`
    - `import org.apache.logging.log4j.Logger;`
    - `import java.net.URL;`
    - `import java.util.Optional;`
    - `import java.util.concurrent.RejectedExecutionException;`

    Body:
    ```java
    public final class ItemSubcommand {
        private static final Logger LOG = LogManager.getLogger();
        private ItemSubcommand() {}

        public static int executeHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
            if (stack.isEmpty()) {
                ctx.getSource().sendFailure(Component.literal("Hold an item in your main hand, or use /forgebook item <id>."));
                return 0;
            }
            return resolveAndDispatch(ctx, player, stack);
        }

        public static int executeWithArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            ItemInput input = ItemArgument.getItem(ctx, "item");
            ItemStack stack = input.createItemStack(1, false);
            return resolveAndDispatch(ctx, player, stack);
        }

        private static int resolveAndDispatch(CommandContext<CommandSourceStack> ctx,
                                              ServerPlayer player, ItemStack stack) {
            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (rl == null) {
                ctx.getSource().sendFailure(Component.literal("Could not identify item."));
                return 0;
            }
            String modId = rl.getNamespace();
            String itemId = rl.toString();
            Optional<URL> modURL = ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo())
                .flatMap(info -> info.getModURL());

            // SAFE-06 (tick-thread mirror): authorize BEFORE submitting to AiExecutor.
            // D-14: single volatile load of config snapshot.
            ConfigSnapshot snap = ConfigHolder.get();
            if (snap == null) {
                ctx.getSource().sendFailure(Component.literal("ForgeBook not initialized — check server logs."));
                return 0;
            }
            long startNanos = System.nanoTime();
            Authorizer.Result auth = Authorizer.authorize(snap, player, RequestKind.ITEM,
                                                         RateLimiterHolder.get());
            if (auth instanceof Authorizer.Denied d) {
                RequestAuditLogger.logDenied(player.getUUID(), RequestKind.ITEM, d.code(), startNanos);
                ctx.getSource().sendFailure(Component.literal(d.humanReadable()));
                return 0;
            }

            // Allowed — dispatch to RagItemPipeline on the AI executor (HTTP + provider call off-tick).
            // TODO(v2): RagItemPipeline currently calls src.sendSuccess synchronously from this
            // executor thread (Pitfall 2 — off-tick sendSuccess). v2 fix: wrap those calls in
            // src.getServer().execute(...) inside RagItemPipeline. Out of scope for v1; benign
            // except for a rare NetworkPipelineException on disconnected players.
            CommandSourceStack src = ctx.getSource();
            try {
                AiExecutor.get().submit(() -> {
                    try {
                        RagItemPipeline.run(src, player, modId, itemId, modURL, RequestKind.ITEM);
                    } catch (Exception ex) {
                        LOG.error("RAG pipeline failed for item {} by {}", itemId, player.getUUID(), ex);
                        src.sendFailure(Component.literal("Internal error."));
                    }
                });
            } catch (RejectedExecutionException e) {
                LOG.warn("aiExecutor rejected RAG submission for {}; returning OVERLOADED.", player.getUUID());
                RequestAuditLogger.logFailure(player.getUUID(), RequestKind.ITEM,
                                              ErrorCode.OVERLOADED, 0, 0, 0L);
                ctx.getSource().sendFailure(Component.literal("Server is busy. Try again."));
            }
            return Command.SINGLE_SUCCESS;
        }
    }
    ```

    **Create `src/test/java/com/forgebook/command/ItemSubcommandTest.java`:**

    Minimum four tests — use JUnit 5 + Mockito + MockedStatic pattern from Plan 04 Task 2. Tests:
    1. `empty_hand_returns_zero_and_sendFailure_without_submit`
    2. `auth_denied_emits_logDenied_and_sendFailure_without_submit`
    3. `allowed_path_submits_rag_pipeline_to_aiexecutor`
    4. `executor_rejection_emits_OVERLOADED_failure`

    Use `MockedStatic<ConfigHolder>`, `MockedStatic<Authorizer>`, `MockedStatic<RateLimiterHolder>`, `MockedStatic<AiExecutor>`, `MockedStatic<RequestAuditLogger>`, `MockedStatic<ForgeRegistries>`, `MockedStatic<ModList>`. Mock `CommandContext`, `CommandSourceStack`, `ServerPlayer`, `ItemStack`. Use `new ResourceLocation("create", "creative_motor")` for itemId stubs.

    For Test 3: stub `AiExecutor.get()` → mocked ExecutorService; capture the Runnable; **do not execute it** (we're only asserting the submit happened). The actual RagItemPipeline.run is already tested by Plan 04 Task 2.
  </action>
  <verify>
    <automated>./gradlew test --no-daemon --tests "com.forgebook.command.ItemSubcommandTest"</automated>
  </verify>
  <done>
    - `ItemSubcommand.java` compiles.
    - `grep -c 'IModInfo::getModURL\|info.getModURL' src/main/java/com/forgebook/command/ItemSubcommand.java` returns at least 1.
    - `grep -c 'getDisplayURL' src/main/java/com/forgebook/command/ItemSubcommand.java` returns 0.
    - `grep -c 'Authorizer\.authorize' src/main/java/com/forgebook/command/ItemSubcommand.java` returns exactly 1.
    - `grep -c 'RagItemPipeline\.run' src/main/java/com/forgebook/command/ItemSubcommand.java` returns exactly 1.
    - `grep -c 'TODO(v2)' src/main/java/com/forgebook/command/ItemSubcommand.java` returns at least 1.
    - A line within 3 lines of the TODO(v2) marker mentions "off-tick sendSuccess" or "Pitfall 2": `grep -A3 'TODO(v2)' src/main/java/com/forgebook/command/ItemSubcommand.java | grep -E 'off-tick sendSuccess|Pitfall 2'` produces at least one match.
    - Authorizer.authorize appears BEFORE AiExecutor.get().submit in file order.
    - `grep -c 'Plan 06b pending' src/main/java/com/forgebook/command/ItemSubcommand.java` returns 0 (stubs replaced).
    - All 4 tests in `ItemSubcommandTest` pass.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: AskSubcommand — authorize + AiDispatcher dispatch via AiExecutor</name>
  <files>src/main/java/com/forgebook/command/AskSubcommand.java, src/test/java/com/forgebook/command/AskSubcommandTest.java</files>
  <read_first>
    - src/main/java/com/forgebook/network/handler/ChatRequestHandler.java (Plan 05 output — the template this subcommand mirrors for tick-thread)
    - src/main/java/com/forgebook/ai/AiDispatcher.java (dispatch(DispatchContext) signature)
    - .planning/phases/03-command-surface-safety-controls/03-PATTERNS.md lines 122-139 (AskSubcommand analog mapping)
  </read_first>
  <behavior>
    - `execute`: resolves sender via `getPlayerOrException`; captures `StringArgumentType.getString(ctx, "message")`.
    - D-14 single volatile load: read `ConfigSnapshot snap = ConfigHolder.get()`; if null, sendFailure + return 0.
    - Authorize with `RequestKind.ASK` and `RateLimiterHolder.get()` BEFORE `AiExecutor.submit`. On `Denied` emit `logDenied` + `sendFailure(humanReadable)` + return 0.
    - On `Allowed`: submit to `AiExecutor.get()`; inside the task call `AiDispatcher.INSTANCE.dispatch(new DispatchContext(message, player, RequestKind.ASK))`; translate the sealed `Result` to `sendSuccess(Reply)` or `sendFailure(Error)` WRAPPED in `server.execute(...)` so the final send lands on the tick thread (Pitfall 2).
    - Handle `RejectedExecutionException` → `sendFailure("Server is busy. Try again.")` + `logFailure(OVERLOADED)`.
    - Audit success/failure logging for the Allowed path is emitted BY `AiDispatcher.dispatch` (Plan 03 output); this subcommand only emits `logDenied` and the `OVERLOADED` failure.
  </behavior>
  <action>
    Create `src/main/java/com/forgebook/command/AskSubcommand.java` (replacing the Plan 06a stub body). Imports mirror ItemSubcommand plus:
    - `import com.forgebook.ai.AiDispatcher;`
    - `import com.forgebook.ai.DispatchContext;`
    - `import com.mojang.brigadier.arguments.StringArgumentType;`
    - `import net.minecraft.server.MinecraftServer;`

    Body:
    ```java
    public final class AskSubcommand {
        private static final Logger LOG = LogManager.getLogger();
        private AskSubcommand() {}

        public static int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            String message = StringArgumentType.getString(ctx, "message");

            // D-14: single volatile load.
            ConfigSnapshot snap = ConfigHolder.get();
            if (snap == null) {
                ctx.getSource().sendFailure(Component.literal("ForgeBook not initialized — check server logs."));
                return 0;
            }
            long startNanos = System.nanoTime();
            Authorizer.Result auth = Authorizer.authorize(snap, player, RequestKind.ASK,
                                                         RateLimiterHolder.get());
            if (auth instanceof Authorizer.Denied d) {
                RequestAuditLogger.logDenied(player.getUUID(), RequestKind.ASK, d.code(), startNanos);
                ctx.getSource().sendFailure(Component.literal(d.humanReadable()));
                return 0;
            }

            CommandSourceStack src = ctx.getSource();
            MinecraftServer server = src.getServer();
            try {
                AiExecutor.get().submit(() -> {
                    try {
                        AiDispatcher.Result result = AiDispatcher.INSTANCE.dispatch(
                            new DispatchContext(message, player, RequestKind.ASK));
                        // Hop back to tick thread for the final send (Pitfall 2).
                        server.execute(() -> {
                            if (result instanceof AiDispatcher.Reply r) {
                                src.sendSuccess(() -> Component.literal(r.text()), false);
                            } else if (result instanceof AiDispatcher.Error err) {
                                src.sendFailure(Component.literal(err.humanReadable()));
                            }
                        });
                    } catch (Exception ex) {
                        LOG.error("Dispatch failed for ASK by {}", player.getUUID(), ex);
                        server.execute(() -> src.sendFailure(Component.literal("Internal error.")));
                    }
                });
            } catch (RejectedExecutionException e) {
                LOG.warn("aiExecutor rejected ASK submission for {}; returning OVERLOADED.", player.getUUID());
                RequestAuditLogger.logFailure(player.getUUID(), RequestKind.ASK,
                                              ErrorCode.OVERLOADED, 0, 0, 0L);
                ctx.getSource().sendFailure(Component.literal("Server is busy. Try again."));
            }
            return Command.SINGLE_SUCCESS;
        }
    }
    ```

    **Create `src/test/java/com/forgebook/command/AskSubcommandTest.java`** — mirror ItemSubcommandTest structure. Minimum 5 tests:
    1. `auth_denied_emits_logDenied_and_sendFailure_without_submit`
    2. `allowed_path_submits_to_aiexecutor`
    3. `reply_translates_to_sendSuccess`
    4. `error_translates_to_sendFailure`
    5. `executor_rejection_emits_OVERLOADED`

    Use `MockedStatic<AiDispatcher>` + instance stubbing: `when(AiDispatcher.INSTANCE.dispatch(any())).thenReturn(...)`. Use `ArgumentCaptor<Runnable>` on `server.execute(...)`; invoke the captured runnable manually to trigger the send assertion. For tests 3/4, also run the `AiExecutor.submit` runnable synchronously via `doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })`.
  </action>
  <verify>
    <automated>./gradlew test --no-daemon --tests "com.forgebook.command.AskSubcommandTest"</automated>
  </verify>
  <done>
    - `AskSubcommand.java` compiles.
    - `grep -c 'AiDispatcher\.INSTANCE\.dispatch' src/main/java/com/forgebook/command/AskSubcommand.java` returns exactly 1.
    - `grep -c 'RequestKind\.ASK' src/main/java/com/forgebook/command/AskSubcommand.java` returns at least 2 (authorize + DispatchContext).
    - `grep -c 'server\.execute' src/main/java/com/forgebook/command/AskSubcommand.java` returns at least 2 (reply/error hop + exception hop).
    - Authorizer.authorize appears BEFORE AiExecutor.get().submit in file order.
    - `grep -c 'Plan 06b pending' src/main/java/com/forgebook/command/AskSubcommand.java` returns 0.
    - All 5 tests in `AskSubcommandTest` pass.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 3: AdminSubcommands — disable / enable / stats (OP-only, synchronous)</name>
  <files>src/main/java/com/forgebook/command/AdminSubcommands.java, src/test/java/com/forgebook/command/AdminSubcommandsTest.java</files>
  <read_first>
    - src/main/java/com/forgebook/command/ForgebookReloadCommand.java (sendSuccess broadcast pattern)
    - src/main/java/com/forgebook/safety/KillSwitch.java (Plan 01)
    - src/main/java/com/forgebook/safety/StatsAccumulator.java (Plan 02 — render() method)
    - .planning/phases/03-command-surface-safety-controls/03-PATTERNS.md lines 143-167 (AdminSubcommands analog)
  </read_first>
  <behavior>
    - `executeDisable`: `KillSwitch.setDisabled(true)`; sendSuccess("ForgeBook disabled..." or "already disabled", broadcast=true); LOG.info.
    - `executeEnable`: `KillSwitch.setDisabled(false)`; sendSuccess("ForgeBook enabled..." or "already enabled", broadcast=true); LOG.info.
    - `executeStats`: sendSuccess(`StatsAccumulator.render()`, broadcast=false) — stats are informational, don't spam other OPs.
    - All three return `Command.SINGLE_SUCCESS`.
    - No AiExecutor hop — pure synchronous in-memory operations on the tick thread.
  </behavior>
  <action>
    Replace the Plan 06a stub body in `src/main/java/com/forgebook/command/AdminSubcommands.java`:

    ```java
    package com.forgebook.command;

    import com.forgebook.safety.KillSwitch;
    import com.forgebook.safety.StatsAccumulator;
    import com.mojang.brigadier.Command;
    import com.mojang.brigadier.context.CommandContext;
    import net.minecraft.commands.CommandSourceStack;
    import net.minecraft.network.chat.Component;
    import org.apache.logging.log4j.LogManager;
    import org.apache.logging.log4j.Logger;

    /**
     * OP-gated admin subcommands for /forgebook. All three operate on in-memory
     * state and return synchronously on the tick thread.
     *
     * <h2>Broadcast semantics</h2>
     * disable/enable use sendSuccess(..., true) — broadcasts to all OPs so other admins
     * see the kill-switch change. stats uses sendSuccess(..., false) because the caller
     * explicitly requested the output.
     */
    public final class AdminSubcommands {
        private static final Logger LOG = LogManager.getLogger();
        private AdminSubcommands() {}

        public static int executeDisable(CommandContext<CommandSourceStack> ctx) {
            boolean wasEnabled = !KillSwitch.isDisabled();
            KillSwitch.setDisabled(true);
            ctx.getSource().sendSuccess(
                () -> Component.literal(wasEnabled
                    ? "ForgeBook disabled. New requests will return DISABLED."
                    : "ForgeBook is already disabled."),
                true);
            LOG.info("ForgeBook disabled by {}", ctx.getSource().getTextName());
            return Command.SINGLE_SUCCESS;
        }

        public static int executeEnable(CommandContext<CommandSourceStack> ctx) {
            boolean wasDisabled = KillSwitch.isDisabled();
            KillSwitch.setDisabled(false);
            ctx.getSource().sendSuccess(
                () -> Component.literal(wasDisabled
                    ? "ForgeBook enabled. New requests will be processed."
                    : "ForgeBook is already enabled."),
                true);
            LOG.info("ForgeBook enabled by {}", ctx.getSource().getTextName());
            return Command.SINGLE_SUCCESS;
        }

        public static int executeStats(CommandContext<CommandSourceStack> ctx) {
            String rendered = StatsAccumulator.render();
            ctx.getSource().sendSuccess(() -> Component.literal(rendered), false);
            return Command.SINGLE_SUCCESS;
        }
    }
    ```

    **Create `src/test/java/com/forgebook/command/AdminSubcommandsTest.java`** — minimum 5 tests:
    1. `executeDisable_flips_killswitch_and_broadcasts`
    2. `executeDisable_noop_message_when_already_disabled`
    3. `executeEnable_flips_killswitch_and_broadcasts`
    4. `executeEnable_noop_message_when_already_enabled`
    5. `executeStats_sends_StatsAccumulator_render_without_broadcast`

    For KillSwitch tests: KillSwitch is a real static holder from Plan 01. Tests use it directly (no MockedStatic); reset via `@BeforeEach KillSwitch.setDisabled(false); @AfterEach KillSwitch.setDisabled(false);`.

    For StatsAccumulator test: use `MockedStatic<StatsAccumulator>` stubbing `render()` → known string; assert the sendSuccess Component's `getString()` equals that string and the broadcast arg is `false`.
  </action>
  <verify>
    <automated>./gradlew test --no-daemon --tests "com.forgebook.command.AdminSubcommandsTest"</automated>
  </verify>
  <done>
    - `AdminSubcommands.java` compiles.
    - `grep -c 'KillSwitch\.setDisabled' src/main/java/com/forgebook/command/AdminSubcommands.java` returns exactly 2.
    - `grep -c 'StatsAccumulator\.render' src/main/java/com/forgebook/command/AdminSubcommands.java` returns exactly 1.
    - `grep -c 'sendSuccess.*true' src/main/java/com/forgebook/command/AdminSubcommands.java` returns at least 2 (disable + enable broadcasts).
    - `grep -c 'sendSuccess.*false' src/main/java/com/forgebook/command/AdminSubcommands.java` returns at least 1 (stats not broadcast).
    - `grep -c 'Plan 06b pending' src/main/java/com/forgebook/command/AdminSubcommands.java` returns 0.
    - All 5 tests pass.
  </done>
</task>

<task type="checkpoint:human-verify" gate="blocking">
  <name>Task 4: In-game smoke test — /forgebook all six subcommands on a dev server</name>
  <files>build/libs/forgebook-*.jar, logs/latest.log</files>
  <action>
    Run ./gradlew runServer and connect with a Minecraft 1.20.1 Forge client (./gradlew runClient) that has the mod installed. Exercise every /forgebook subcommand against a live server per the how-to-verify steps. The executor does NOT run these steps — they require a human operator with the Minecraft client focused on-screen.

    The full Phase 3 command surface being verified:
    - /forgebook ask &lt;message&gt; — routes through AiDispatcher (tools-enabled Anthropic call)
    - /forgebook item — uses held item; RAG single-shot pipeline with Source: citation
    - /forgebook item &lt;id&gt; — same pipeline with argument-specified item
    - /forgebook reload — reloads config + rebuilds system prompt + swaps rate limiter
    - /forgebook disable — flips kill switch on; subsequent requests return DISABLED
    - /forgebook enable — flips kill switch off
    - /forgebook stats — renders per-player + aggregate metrics
    SAFE-06 precheck on CHAT_UI packets (Plan 05), rate limiting (Plan 01), audit logging (Plan 02), kill-switch short-circuit (Plan 03) all active.
  </action>
  <verify>
    <automated>./gradlew build --no-daemon</automated>
    Human verification per how-to-verify below — blocking.
  </verify>
  <done>
    All six subcommands behave as specified under both OP and non-OP accounts; audit log lines appear for every request; rate limit fires after N calls for non-OP; kill-switch short-circuits on DISABLE; human approves.
  </done>
  <how-to-verify>
    1. `./gradlew runServer` — start the dev server.
    2. Connect with a Minecraft 1.20.1 client that has the mod installed (`./gradlew runClient` on a second machine/profile, or connect to localhost).
    3. As the initial OP:
       - `/forgebook ask What is iron used for?` → expect a Claude reply in chat (may take 2-10 seconds).
       - Hold an iron ingot, then `/forgebook item` → expect a reply ending with `Source: https://minecraft.wiki/...` or similar mod URL.
       - `/forgebook item create:creative_motor` (if Create mod is installed) → same shape.
       - `/forgebook disable` → see broadcast message. Now `/forgebook ask test` → expect "ForgeBook is currently disabled..." response.
       - `/forgebook enable` → see broadcast. Now `/forgebook ask test` → works again.
       - `/forgebook stats` → expect a multi-line summary of request counts.
       - `/forgebook reload` → expect "ForgeBook config + system prompt reloaded."
    4. Quit, edit `config/forgebook-server.toml` to set `op_only=false` and `rate_limit_per_minute=3`. Restart.
    5. Join as a non-OP (use `/op` then `/deop` on a second account).
       - `/forgebook ask hello` → should work (op_only=false).
       - Repeat 4 more times within a minute → 4th or 5th call should return "Rate limit exceeded. Try again in Ns."
       - `/forgebook disable` → expect "You do not have permission to use this command" (Brigadier structural gate, Pitfall 1 compliant).
    6. Check `logs/latest.log` for the `[forgebook.audit]` named logger output — every request should emit one line with uuid, kind, outcome, tokens, latency.
  </how-to-verify>
  <resume-signal>Type "approved" when the above flows all behave as described, or describe issues for the executor to investigate.</resume-signal>
</task>

</tasks>

<threat_model>

## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Brigadier `.executes` → Subcommand body | Tick-thread entry; `CommandSourceStack` + `ServerPlayer` are authentic (server-bound). |
| Subcommand → AiExecutor task | Task body runs off-tick; must re-resolve time-sensitive state via D-14 pattern. |
| Item → ModList metadata | Mod authors supply `getModURL()` via `mods.toml`; treated as untrusted URL (SafeHttpFetcher gates SSRF). |
| AiExecutor task → server.execute | Feedback hops back to tick thread for AskSubcommand; Item path carries v1 Pitfall 2 limitation documented via TODO(v2). |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-03-06b-01 | Tampering | `/forgebook ask <message>` includes `\n` or control chars to inject into the provider prompt | mitigate | `StringArgumentType.greedyString()` preserves the raw message but Brigadier strips trailing newlines per argument. AiDispatcher/RagItemPipeline frame the message in a template — no direct system-prompt injection possible. |
| T-03-06b-02 | Repudiation | OP denies having issued `/forgebook disable` | mitigate | `LOG.info("ForgeBook disabled by {}", ctx.getSource().getTextName())` — tied to authenticated player UUID/name. Broadcast message shows attribution to other OPs. |
| T-03-06b-03 | Denial of Service | Non-OP spams `/forgebook item` to exhaust rate limit for all players | mitigate | `RateLimiter` is per-UUID (Plan 01 ConcurrentHashMap keyed by UUID) — one player's exhaustion doesn't affect others. OPs bypass rate limit (Plan 03 Authorizer). |
| T-03-06b-04 | Information Disclosure | `/forgebook stats` renders other players' UUIDs to any OP | accept | Stats are operator-visible by design (CMD-04 requirement). Only OPs (`.requires(src -> src.hasPermission(2))`) can invoke. Per-player breakdown is essential for cost attribution. |
| T-03-06b-05 | Denial of Service | `/forgebook stats` output exceeds 32KB Minecraft chat limit on a heavy server | mitigate | `StatsAccumulator.render()` caps output to top-10 players by request count (Plan 02 Pitfall 8). Aggregate metrics are fixed-size. Renders stay under 2KB. |
| T-03-06b-06 | Denial of Service | ItemSubcommand off-tick sendSuccess crashes on disconnected player | accept | v1 Pitfall 2 known limitation. TODO(v2) breadcrumb recorded. Logs the exception; no data leak. Follow-up is a one-line pipeline fix. |

</threat_model>

<verification>
- `./gradlew build --no-daemon` — full build passes (compile + all tests + resources)
- `./gradlew test --no-daemon --tests "com.forgebook.command.*"` — all three new test classes pass
- `./gradlew test --no-daemon` — no regressions in Phase 1/2 test suites
- In-game smoke (Task 4) — human verification of all six subcommands
- Audit log inspection — `logs/latest.log` contains `[forgebook.audit]` lines with no user message content
</verification>

<success_criteria>
- Ask + item subcommands work for authorized players; deny with humanReadable messages otherwise
- Disable/enable toggle `KillSwitch` and broadcast to OPs
- Stats renders `StatsAccumulator.render()` output capped at top-10 players
- No stub bodies remain: `grep -rc 'Plan 06b pending' src/main/java/com/forgebook/command/` returns 0
- ItemSubcommand carries a TODO(v2) comment referencing "off-tick sendSuccess" or "Pitfall 2"
- Phase 3 requirements closed: CMD-02, CMD-03, CMD-04 (CMD-01 + CMD-06 closed by Plan 06a)
- Combined with Plans 01-06a: all 13 Phase 3 requirements complete (CMD-01..07 + SAFE-01..06)
</success_criteria>

<output>
After completion, create `.planning/phases/03-command-surface-safety-controls/03-06b-SUMMARY.md`.
</output>
</content>
