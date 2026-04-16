---
phase: 03-command-surface-safety-controls
plan: "06a"
type: execute
wave: 4
depends_on: ["01", "02", "03", "04", "05"]
files_modified:
  - src/main/java/com/forgebook/command/ForgebookReloadCommand.java
  - src/main/java/com/forgebook/command/ForgebookCommands.java
  - src/main/java/com/forgebook/ForgeBookMod.java
autonomous: true
requirements:
  - CMD-01
  - CMD-04
tags: [brigadier, command-tree, reload, rate-limiter-swap, listener-swap, wiring-foundation]

must_haves:
  truths:
    - "/forgebook root exists with exactly six subcommands declared: ask, item, reload, disable, enable, stats"
    - "/forgebook ask and /forgebook item OMIT .requires(...) — OP gating is runtime via Authorizer, not Brigadier-structural (Pitfall 1)"
    - "/forgebook reload, disable, enable, stats all carry .requires(src -> src.hasPermission(2))"
    - "/forgebook reload: ConfigHolder.set → SystemPromptBuilder.buildAndCache → RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute())) — in that order"
    - "ForgeBookMod line 58 listener is com.forgebook.command.ForgebookCommands::onRegister (swapped from ForgebookReloadCommand::onRegister)"
    - "ForgeBookMod seeds RateLimiterHolder on ServerStartingEvent alongside ConfigHolder and AiExecutor"
  artifacts:
    - path: "src/main/java/com/forgebook/command/ForgebookReloadCommand.java"
      provides: "executeReload extracted to public static; body also swaps RateLimiterHolder"
      contains: "RateLimiterHolder.swap"
    - path: "src/main/java/com/forgebook/command/ForgebookCommands.java"
      provides: "Brigadier root tree — registers all 6 subcommands via onRegister(RegisterCommandsEvent). Executors wired to static refs in ItemSubcommand/AskSubcommand/AdminSubcommands (those classes materialize in Plan 06b but must be resolvable at compile time)."
      contains: "public static void onRegister(RegisterCommandsEvent event)"
    - path: "src/main/java/com/forgebook/ForgeBookMod.java"
      provides: "Listener swap: ForgebookCommands::onRegister replaces ForgebookReloadCommand::onRegister; RateLimiterHolder seeded on ServerStartingEvent"
      contains: "ForgebookCommands::onRegister"
  key_links:
    - from: "src/main/java/com/forgebook/command/ForgebookReloadCommand.java"
      to: "src/main/java/com/forgebook/safety/RateLimiterHolder.java"
      via: "RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute())) after ConfigHolder.set"
      pattern: "RateLimiterHolder\\.swap"
    - from: "src/main/java/com/forgebook/ForgeBookMod.java"
      to: "src/main/java/com/forgebook/command/ForgebookCommands.java"
      via: "MinecraftForge.EVENT_BUS.addListener(ForgebookCommands::onRegister)"
      pattern: "ForgebookCommands::onRegister"
---

<objective>
Wave 4 wiring foundation. Extract `ForgebookReloadCommand.executeReload` into a reusable public static (so the new root tree can delegate to it), register the six-subcommand Brigadier root via `ForgebookCommands.onRegister`, and swap `ForgeBookMod`'s command listener from the old single-literal registrar to the new tree + seed `RateLimiterHolder` on `ServerStartingEvent`. The subcommand executor classes (`ItemSubcommand`, `AskSubcommand`, `AdminSubcommands`) are delivered in Plan 06b (wave 5) — this plan writes their method-reference call sites under a forward-declaration discipline: create the empty-body stub classes in the same wave as `ForgebookCommands` so compilation succeeds, then fill them in 06b.

Purpose: This plan delivers CMD-01 (root tree registration) and CMD-06 (reload includes rate-limiter swap). The subcommand method bodies (CMD-02 / CMD-03 / CMD-04) belong to Plan 06b. Splitting the foundation wiring out lets reviewers lock in the structural shape — listener swap, reload ordering, tree topology — before executor logic lands.

Output: Three production files modified (ForgebookReloadCommand, ForgeBookMod) or created (ForgebookCommands). Plan 06b creates the three subcommand files. Because Java requires referenced methods to exist at compile time, this plan MUST also create stub versions of `ItemSubcommand.executeHeld`, `ItemSubcommand.executeWithArg`, `AskSubcommand.execute`, `AdminSubcommands.executeDisable`, `AdminSubcommands.executeEnable`, `AdminSubcommands.executeStats` that throw `UnsupportedOperationException("Plan 06b pending")`. Plan 06b replaces those stub bodies.
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

# Existing analogs
@src/main/java/com/forgebook/command/ForgebookReloadCommand.java
@src/main/java/com/forgebook/ForgeBookMod.java
@src/main/java/com/forgebook/config/ConfigHolder.java
@src/main/java/com/forgebook/config/ConfigSnapshot.java

# Phase 3 outputs (Plan 01)
@src/main/java/com/forgebook/safety/RateLimiter.java
@src/main/java/com/forgebook/safety/RateLimiterHolder.java

<interfaces>
<!-- Brigadier types already on the classpath (MC 1.20.1 Forge). -->

From com.mojang.brigadier.* (bundled with Minecraft):
```java
Command.SINGLE_SUCCESS  // int constant = 1
CommandContext<S>       // .getSource() -> S, .getArgument(name, type)
StringArgumentType.greedyString()
```

From net.minecraft.commands.* (1.20.1 vanilla):
```java
Commands.literal(String) -> LiteralArgumentBuilder<CommandSourceStack>
Commands.argument(String, ArgumentType<T>) -> RequiredArgumentBuilder<CommandSourceStack, T>
CommandSourceStack.hasPermission(int) -> boolean
CommandSourceStack.sendSuccess(Supplier<Component>, boolean) -> void
CommandSourceStack.sendFailure(Component) -> void
CommandSourceStack.getServer() -> MinecraftServer
```

From net.minecraft.commands.arguments.item.ItemArgument (1.20.1):
```java
ItemArgument.item(CommandBuildContext) -> ItemArgument
```

From net.minecraftforge.event.RegisterCommandsEvent (1.20.1):
```java
RegisterCommandsEvent.getDispatcher() -> CommandDispatcher<CommandSourceStack>
RegisterCommandsEvent.getBuildContext() -> CommandBuildContext   // required by ItemArgument.item(...)
```

From Plan 01 outputs:
```java
RateLimiterHolder.get() / swap(RateLimiter)
RateLimiter(int ratePerMinute)   // constructor
```
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: ForgebookReloadCommand — extract executeReload, add RateLimiterHolder.swap</name>
  <files>src/main/java/com/forgebook/command/ForgebookReloadCommand.java</files>
  <read_first>
    - src/main/java/com/forgebook/command/ForgebookReloadCommand.java (Phase 1 — current body)
    - src/main/java/com/forgebook/safety/RateLimiter.java (Plan 01 — constructor signature)
    - src/main/java/com/forgebook/safety/RateLimiterHolder.java (Plan 01 — swap method)
    - src/main/java/com/forgebook/config/ConfigSnapshot.java (rateLimitPerMinute accessor)
  </read_first>
  <behavior>
    - executeReload is a public static method that: reloads config, rebuilds system prompt, swaps rate limiter, sends feedback, logs, returns SINGLE_SUCCESS
    - onRegister still registers the reload literal (preserving Phase 1/2 behavior) BUT it is now only used as a fallback/back-compat — the new ForgebookCommands tree (Task 2 below) calls executeReload directly
    - Every existing caller of onRegister continues to work (Phase 1 test `ForgebookReloadCommandTest` still passes)
  </behavior>
  <action>
    **Modify `src/main/java/com/forgebook/command/ForgebookReloadCommand.java` as follows:**

    **Step 1: Extract the .executes body to a new `public static int executeReload(CommandContext<CommandSourceStack> ctx)` method.**

    Add new imports:
    - `import com.forgebook.config.ConfigSnapshot;`
    - `import com.forgebook.safety.RateLimiter;`
    - `import com.forgebook.safety.RateLimiterHolder;`
    - `import com.mojang.brigadier.context.CommandContext;`
    - `import net.minecraft.commands.CommandSourceStack;`

    New method (place AFTER `onRegister`, BEFORE the closing brace):
    ```java
    /**
     * Reload body extracted for reuse by {@link com.forgebook.command.ForgebookCommands}.
     * Runs on the server tick thread (Brigadier dispatch). Performs three actions in
     * order:
     *   1. ConfigHolder.set(ConfigHolder.buildFromSpec()) — rebuild the snapshot from TOML.
     *   2. SystemPromptBuilder.buildAndCache(server) — re-render cached system prompt.
     *   3. RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute())) — resize limiter.
     *
     * D-14 invariant: the new snapshot is read ONCE via ConfigHolder.get() after set(),
     * passed to the RateLimiter constructor, and never re-read in this method.
     *
     * Pitfall 6 (RESEARCH): swapping the RateLimiter drops all in-flight per-UUID token
     * buckets. This is intentional — a reload is a "fresh slate" event. Benign leak.
     */
    public static int executeReload(CommandContext<CommandSourceStack> ctx) {
        ConfigHolder.set(ConfigHolder.buildFromSpec());
        // D-08: rebuild system prompt AFTER ConfigHolder.set so buildAndCache reads the new snapshot.
        com.forgebook.ai.SystemPromptBuilder.buildAndCache(ctx.getSource().getServer());
        // CMD-06 / Phase 3: resize the rate limiter to match the new config. Single volatile load.
        ConfigSnapshot snap = ConfigHolder.get();
        RateLimiterHolder.swap(new RateLimiter(snap.rateLimitPerMinute()));
        ctx.getSource().sendSuccess(
            () -> Component.literal("ForgeBook config + system prompt reloaded."), true);
        LOG.info("ForgeBook config reloaded by {}", ctx.getSource().getTextName());
        return Command.SINGLE_SUCCESS;
    }
    ```

    **Step 2: Simplify `onRegister` to delegate to `executeReload`:**
    ```java
    public static void onRegister(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("forgebook")
                .then(Commands.literal("reload")
                    .requires(src -> src.hasPermission(2))
                    .executes(ForgebookReloadCommand::executeReload)));
    }
    ```

    **Step 3: Do NOT delete onRegister.** It remains callable for backward-compatible test contexts (Phase 1 `ForgebookReloadCommandTest` may call it). The ForgeBookMod listener swap (Task 3 below) replaces the production listener with `ForgebookCommands::onRegister`, but the old method must still compile and work when invoked directly from a test.

    **Anti-patterns to reject:**
    - Do NOT swap RateLimiterHolder BEFORE ConfigHolder.set — readers would see an old snapshot paired with a new limiter (Pitfall 6 ordering).
    - Ordering is `ConfigHolder.set` → `buildAndCache` → `RateLimiterHolder.swap`. `buildAndCache` submits async work to AiExecutor per D-20; the RateLimiter swap lands regardless of async completion. Correct.
    - Do NOT read `rateLimitPerMinute` from a second `ConfigHolder.get()` call after `swap` — single volatile load per D-14.
  </action>
  <verify>
    <automated>./gradlew compileJava --no-daemon -x test</automated>
  </verify>
  <done>
    - `ForgebookReloadCommand.java` compiles.
    - `grep -c 'public static int executeReload' src/main/java/com/forgebook/command/ForgebookReloadCommand.java` returns exactly 1.
    - `grep -c 'RateLimiterHolder\.swap' src/main/java/com/forgebook/command/ForgebookReloadCommand.java` returns exactly 1.
    - The `ConfigHolder.set` line appears BEFORE `RateLimiterHolder.swap` in file order: `awk '/ConfigHolder\.set/{c=NR} /RateLimiterHolder\.swap/{s=NR} END{exit !(c<s)}' src/main/java/com/forgebook/command/ForgebookReloadCommand.java` exits 0.
    - Existing Phase 1/2 test `./gradlew test --tests "com.forgebook.command.ForgebookReloadCommandTest"` (if exists) still passes.
  </done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: ForgebookCommands — Brigadier root tree + stub subcommand classes for compile</name>
  <files>src/main/java/com/forgebook/command/ForgebookCommands.java, src/main/java/com/forgebook/command/ItemSubcommand.java, src/main/java/com/forgebook/command/AskSubcommand.java, src/main/java/com/forgebook/command/AdminSubcommands.java</files>
  <read_first>
    - src/main/java/com/forgebook/command/ForgebookReloadCommand.java (Task 1 output — executeReload signature + onRegister shape)
    - .planning/phases/03-command-surface-safety-controls/03-PATTERNS.md lines 45-75 (ForgebookCommands analog pattern)
  </read_first>
  <behavior>
    - onRegister registers `Commands.literal("forgebook")` with six `.then(...)` children: ask, item, reload, disable, enable, stats
    - ask: `.then(Commands.literal("ask").then(Commands.argument("message", StringArgumentType.greedyString()).executes(AskSubcommand::execute)))`
    - item has TWO branches: `.then(Commands.literal("item").executes(ItemSubcommand::executeHeld).then(Commands.argument("item", ItemArgument.item(buildCtx)).executes(ItemSubcommand::executeWithArg)))`
    - reload / disable / enable / stats ALL carry `.requires(src -> src.hasPermission(2))` immediately after their literal, BEFORE `.executes(...)`
    - ask / item OMIT `.requires(...)` — OP gating is runtime via Authorizer inside the executes bodies (Pitfall 1)
    - ItemSubcommand / AskSubcommand / AdminSubcommands are created as STUB CLASSES in this task with method signatures matching the method references above; bodies throw UnsupportedOperationException("Plan 06b pending"). Plan 06b replaces the bodies.
  </behavior>
  <action>
    **Create `src/main/java/com/forgebook/command/ForgebookCommands.java`:**

    Package + imports:
    - `package com.forgebook.command;`
    - `import com.mojang.brigadier.arguments.StringArgumentType;`
    - `import net.minecraft.commands.Commands;`
    - `import net.minecraft.commands.arguments.item.ItemArgument;`
    - `import net.minecraftforge.event.RegisterCommandsEvent;`
    - `import org.apache.logging.log4j.LogManager;`
    - `import org.apache.logging.log4j.Logger;`

    Body:
    ```java
    /**
     * Brigadier root registrar for /forgebook. Wires the full six-subcommand tree
     * (ask, item, reload, disable, enable, stats). Registered as a forge-bus listener
     * from {@link com.forgebook.ForgeBookMod}.
     *
     * <h2>Pitfall 1: .requires vs runtime authorization</h2>
     * `reload`, `disable`, `enable`, `stats` use `.requires(src -> src.hasPermission(2))`
     * because they're admin-only at the command-structure level — non-OPs don't even
     * see them in tab-completion.
     *
     * `ask` and `item` DO NOT use `.requires(...)` because `op_only` is a runtime config
     * knob (CFG-*). Even when `op_only=true`, the command itself is still syntactically
     * available to all players — the runtime {@link com.forgebook.safety.Authorizer}
     * gate inside the .executes body returns FORBIDDEN when appropriate. This matches
     * the CHAT_UI path (ChatRequestHandler.handleForTest Plan 05), keeping the auth
     * decision in one place per RequestKind.
     *
     * <h2>Explicit bus binding</h2>
     * This class is not {@literal @Mod.EventBusSubscriber}-annotated. {@link ForgeBookMod}
     * wires it via {@code MinecraftForge.EVENT_BUS.addListener(ForgebookCommands::onRegister)}
     * — the Forge bus dispatches RegisterCommandsEvent, not the mod bus.
     */
    public final class ForgebookCommands {

        private static final Logger LOG = LogManager.getLogger();

        private ForgebookCommands() {}

        public static void onRegister(RegisterCommandsEvent event) {
            event.getDispatcher().register(
                Commands.literal("forgebook")
                    // ask (runtime-auth via Authorizer — no .requires)
                    .then(Commands.literal("ask")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                            .executes(AskSubcommand::execute)))
                    // item (two branches; runtime-auth via Authorizer)
                    .then(Commands.literal("item")
                        .executes(ItemSubcommand::executeHeld)
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                            .executes(ItemSubcommand::executeWithArg)))
                    // reload (admin — structural OP gate)
                    .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ForgebookReloadCommand::executeReload))
                    // disable (admin)
                    .then(Commands.literal("disable")
                        .requires(src -> src.hasPermission(2))
                        .executes(AdminSubcommands::executeDisable))
                    // enable (admin)
                    .then(Commands.literal("enable")
                        .requires(src -> src.hasPermission(2))
                        .executes(AdminSubcommands::executeEnable))
                    // stats (admin)
                    .then(Commands.literal("stats")
                        .requires(src -> src.hasPermission(2))
                        .executes(AdminSubcommands::executeStats)));

            LOG.info("ForgeBook commands registered: ask, item, reload, disable, enable, stats");
        }
    }
    ```

    **Create STUB `src/main/java/com/forgebook/command/ItemSubcommand.java`:**
    ```java
    package com.forgebook.command;

    import com.mojang.brigadier.context.CommandContext;
    import com.mojang.brigadier.exceptions.CommandSyntaxException;
    import net.minecraft.commands.CommandSourceStack;

    /**
     * Stub — Plan 06a wires the Brigadier tree; Plan 06b replaces these bodies
     * with the authorize → AiExecutor.submit → RagItemPipeline dispatch logic.
     * Method signatures are frozen here so ForgebookCommands::executeHeld /
     * ::executeWithArg method references compile.
     */
    public final class ItemSubcommand {
        private ItemSubcommand() {}

        public static int executeHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            throw new UnsupportedOperationException("Plan 06b pending");
        }

        public static int executeWithArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            throw new UnsupportedOperationException("Plan 06b pending");
        }
    }
    ```

    **Create STUB `src/main/java/com/forgebook/command/AskSubcommand.java`:**
    ```java
    package com.forgebook.command;

    import com.mojang.brigadier.context.CommandContext;
    import com.mojang.brigadier.exceptions.CommandSyntaxException;
    import net.minecraft.commands.CommandSourceStack;

    /** Stub — see Plan 06b. */
    public final class AskSubcommand {
        private AskSubcommand() {}

        public static int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
            throw new UnsupportedOperationException("Plan 06b pending");
        }
    }
    ```

    **Create STUB `src/main/java/com/forgebook/command/AdminSubcommands.java`:**
    ```java
    package com.forgebook.command;

    import com.mojang.brigadier.context.CommandContext;
    import net.minecraft.commands.CommandSourceStack;

    /** Stub — see Plan 06b. */
    public final class AdminSubcommands {
        private AdminSubcommands() {}

        public static int executeDisable(CommandContext<CommandSourceStack> ctx) {
            throw new UnsupportedOperationException("Plan 06b pending");
        }

        public static int executeEnable(CommandContext<CommandSourceStack> ctx) {
            throw new UnsupportedOperationException("Plan 06b pending");
        }

        public static int executeStats(CommandContext<CommandSourceStack> ctx) {
            throw new UnsupportedOperationException("Plan 06b pending");
        }
    }
    ```

    **Constraints:**
    - `.requires` is applied to the sub-literal (e.g. `Commands.literal("reload")`), NOT to the `.then(...)` chain result. Wrong ordering compiles but gates the wrong node.
    - `ItemArgument.item(event.getBuildContext())` — `getBuildContext` is a 1.20.1 API (confirmed in RESEARCH line 434). Do not use `ItemArgument.item(new Object())` or other cargo-culted variants from 1.19.x tutorials.
    - No `@Mod.EventBusSubscriber` annotation — explicit listener registration per CLAUDE.md anti-pattern list.
  </action>
  <verify>
    <automated>./gradlew compileJava --no-daemon -x test</automated>
  </verify>
  <done>
    - All four files compile.
    - `grep -c '.then(Commands.literal(' src/main/java/com/forgebook/command/ForgebookCommands.java` returns exactly 6.
    - `grep -c '.requires(src -> src.hasPermission(2))' src/main/java/com/forgebook/command/ForgebookCommands.java` returns exactly 4 (reload + disable + enable + stats; NOT ask/item).
    - `grep -c 'ItemArgument.item(event.getBuildContext())' src/main/java/com/forgebook/command/ForgebookCommands.java` returns exactly 1.
    - `grep -c '@Mod\.EventBusSubscriber' src/main/java/com/forgebook/command/ForgebookCommands.java` returns 0.
    - `grep -c 'Plan 06b pending' src/main/java/com/forgebook/command/ItemSubcommand.java` returns exactly 2.
    - `grep -c 'Plan 06b pending' src/main/java/com/forgebook/command/AskSubcommand.java` returns exactly 1.
    - `grep -c 'Plan 06b pending' src/main/java/com/forgebook/command/AdminSubcommands.java` returns exactly 3.
  </done>
</task>

<task type="auto">
  <name>Task 3: ForgeBookMod — swap listener + seed RateLimiterHolder on ServerStartingEvent</name>
  <files>src/main/java/com/forgebook/ForgeBookMod.java</files>
  <read_first>
    - src/main/java/com/forgebook/ForgeBookMod.java (Phase 1/2 — current body)
    - src/main/java/com/forgebook/safety/RateLimiterHolder.java (Plan 01 output — swap method)
    - src/main/java/com/forgebook/safety/RateLimiter.java (Plan 01 — constructor)
    - src/main/java/com/forgebook/config/ConfigHolder.java
  </read_first>
  <behavior>
    - Line 58 old: `MinecraftForge.EVENT_BUS.addListener(com.forgebook.command.ForgebookReloadCommand::onRegister);`
    - Line 58 new: `MinecraftForge.EVENT_BUS.addListener(com.forgebook.command.ForgebookCommands::onRegister);`
    - ServerStartingEvent listener (currently seeds ConfigHolder) now also seeds RateLimiterHolder: new `RateLimiter(ConfigHolder.get().rateLimitPerMinute())` passed to `RateLimiterHolder.swap(...)` AFTER the ConfigHolder.set call
    - Order of ServerStartingEvent listeners: ConfigHolder.set → RateLimiterHolder.swap → AiExecutor.start (AiExecutor is a separate listener, relative order across listeners is Forge-defined; but within the ConfigHolder-seeding listener, the RateLimiter seeding happens inline AFTER ConfigHolder.set)
  </behavior>
  <action>
    Modify `src/main/java/com/forgebook/ForgeBookMod.java`:

    **Change 1 — line 58:** Replace `com.forgebook.command.ForgebookReloadCommand::onRegister` with `com.forgebook.command.ForgebookCommands::onRegister`. Update the comment on lines 55-57 to reflect the new scope:
    ```java
    // Plan 03 (Phase 3) wiring: full /forgebook command tree (ask/item/reload/disable/enable/stats) +
    // initial snapshots on server start. D-15: /forgebook reload remains the only config reload trigger.
    // ServerStartingEvent seeds ConfigHolder + RateLimiterHolder so downstream readers can
    // assume non-null after server start.
    MinecraftForge.EVENT_BUS.addListener(com.forgebook.command.ForgebookCommands::onRegister);
    ```

    **Change 2 — augment the existing ServerStartingEvent listener (currently lines 59-62):**
    ```java
    MinecraftForge.EVENT_BUS.addListener(
        (net.minecraftforge.event.server.ServerStartingEvent e) -> {
            com.forgebook.config.ConfigHolder.set(
                com.forgebook.config.ConfigHolder.buildFromSpec());
            // Phase 3 Plan 01/06a: seed RateLimiterHolder with the initial snapshot's
            // rate_limit_per_minute. /forgebook reload (Plan 06a Task 1) swaps this
            // on subsequent reloads via the same pattern.
            com.forgebook.safety.RateLimiterHolder.swap(
                new com.forgebook.safety.RateLimiter(
                    com.forgebook.config.ConfigHolder.get().rateLimitPerMinute()));
        });
    ```

    **Changes NOT to make:**
    - Do NOT delete the `ForgebookReloadCommand::onRegister` import/reference elsewhere — the reload class is still used (ForgebookCommands delegates to `ForgebookReloadCommand::executeReload` via method reference).
    - Do NOT change the AiExecutor lifecycle listeners (lines 67-70). They are independent of the RateLimiter seeding.
    - Do NOT change the SystemPromptBuilder ServerStartedEvent listener (lines 76-78).
    - Do NOT remove `DistExecutor.safeRunWhenOn` (line 81-82). D-10 firewall invariant.
  </action>
  <verify>
    <automated>./gradlew build --no-daemon -x test</automated>
  </verify>
  <done>
    - `ForgeBookMod.java` compiles; full build passes.
    - `grep -c 'ForgebookCommands::onRegister' src/main/java/com/forgebook/ForgeBookMod.java` returns exactly 1.
    - `grep -c 'ForgebookReloadCommand::onRegister' src/main/java/com/forgebook/ForgeBookMod.java` returns 0 (old listener replaced).
    - `grep -c 'RateLimiterHolder\.swap' src/main/java/com/forgebook/ForgeBookMod.java` returns exactly 1.
    - The ConfigHolder.set call appears BEFORE the RateLimiterHolder.swap call in file order: `awk '/ConfigHolder\.set/{c=NR} /RateLimiterHolder\.swap/{s=NR} END{exit !(c<s)}' src/main/java/com/forgebook/ForgeBookMod.java` exits 0.
    - Full build: `./gradlew build --no-daemon -x test` passes.
  </done>
</task>

</tasks>

<threat_model>

## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Brigadier `.executes` → Subcommand method ref | Tick-thread entry; ForgebookCommands wires refs to stub classes whose bodies are finalized in Plan 06b. |
| ServerStartingEvent → RateLimiterHolder | Single-threaded init on main server thread; no concurrent reads until server accepts connections. |
| `.requires` structural gate | Brigadier evaluates `.requires` once per tab-completion frame — rejection here doesn't even show the command name. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-03-06a-01 | Spoofing | Client crafts a fake command via a packet handler instead of the Brigadier client parser | mitigate | Brigadier dispatches on the server — the client-parsed command is re-validated server-side. `.requires(src -> src.hasPermission(2))` on admin subcommands reads server-side op list, not client claims. |
| T-03-06a-02 | Elevation of Privilege | Non-OP calls `/forgebook disable` by sending raw Brigadier packet | mitigate | Brigadier dispatcher re-evaluates `.requires` server-side on every dispatch — cannot be bypassed by crafting packets. |
| T-03-06a-03 | Tampering | `/forgebook reload` races with in-flight chat requests; old snapshot readers see half-swapped state | mitigate | D-14 single-volatile-load invariant — every callsite reads `ConfigHolder.get()` ONCE per request. The swap sequence (ConfigHolder.set → RateLimiterHolder.swap) is explicit; worst case is a request uses old config + new limiter or vice versa, both valid safety configurations. Pitfall 6 documented. |
| T-03-06a-04 | Denial of Service | Stub subcommands throw `UnsupportedOperationException` if invoked before Plan 06b lands; player sees a raw stack trace via Brigadier | accept | Plan 06a and 06b land in the same phase and are never released independently. Verification task in Plan 06b asserts no stub bodies remain. If an operator somehow ran a 06a-only build, they see a Brigadier "Unknown error" feedback — no secrets exposed. |

</threat_model>

<verification>
- `./gradlew build --no-daemon -x test` — full compile passes (stubs resolve)
- `./gradlew test --no-daemon` — no regressions in Phase 1/2 test suites
- Existing `ForgebookReloadCommandTest` (Phase 1) continues to pass against the extracted executeReload
</verification>

<success_criteria>
- ForgebookReloadCommand.executeReload is a public static reusable method
- /forgebook reload body includes RateLimiterHolder.swap after ConfigHolder.set
- ForgebookCommands.onRegister declares all six subcommands with the correct .requires placement
- Three subcommand stub classes exist so method references compile; bodies throw "Plan 06b pending"
- ForgeBookMod listener swap is the only change to production wiring
- RateLimiterHolder is seeded on ServerStartingEvent alongside ConfigHolder
- CMD-01 (root tree registered) and CMD-06 (reload swaps limiter) are complete
</success_criteria>

<output>
After completion, create `.planning/phases/03-command-surface-safety-controls/03-06a-SUMMARY.md`.
</output>
</content>
