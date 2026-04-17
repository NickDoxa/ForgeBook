package com.forgebook.command;

import com.forgebook.ai.AiDispatcher;
import com.forgebook.ai.DispatchContext;
import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.safety.Authorizer;
import com.forgebook.safety.RateLimiter;
import com.forgebook.safety.RateLimiterHolder;
import com.forgebook.safety.RequestAuditLogger;
import com.forgebook.util.AiExecutor;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * /forgebook ask &lt;message&gt; — tools-enabled {@link AiDispatcher} dispatch (CMD-03).
 *
 * <p>Plan 06a wired the Brigadier tree; this file fills the stub body. The public
 * {@link #execute(CommandContext)} entry point unpacks the Minecraft types
 * ({@link CommandSourceStack}, {@link ServerPlayer}, {@link MinecraftServer}) into
 * pure-Java primitives and delegates to the package-private {@link #executeInternal}
 * seam. The seam then:
 *
 * <ol>
 *   <li>Loads a single {@link ConfigSnapshot} (D-14 single volatile read).</li>
 *   <li>Calls {@link Authorizer#authorize} on the TICK THREAD, BEFORE any
 *       {@link AiExecutor#get()}/{@code submit} — SAFE-06 invariant mirrored from
 *       {@link com.forgebook.network.handler.ChatRequestHandler}.</li>
 *   <li>On {@link Authorizer.Denied} emits {@link RequestAuditLogger#logDenied} + {@code sendFailure},
 *       returns 0.</li>
 *   <li>On {@link Authorizer.Allowed} submits a task to {@link AiExecutor}; inside the task
 *       calls {@link AiDispatcher#dispatch(DispatchContext)} then hops the response back to
 *       the tick thread via {@code server.execute(...)} for the final {@code sendSuccess} /
 *       {@code sendFailure} (Pitfall 2).</li>
 *   <li>{@link RejectedExecutionException} from queue overflow (D-20) → OVERLOADED audit + failure.</li>
 * </ol>
 *
 * <h2>Why server.execute(...) for the final send</h2>
 * The {@link AiExecutor} body runs off-tick. {@link CommandSourceStack#sendSuccess} and
 * {@link CommandSourceStack#sendFailure} must run on the server tick thread (Pitfall 2 —
 * disconnected-player safety). Wrapping the translation+send in
 * {@code server.execute(...)} ensures that invariant is maintained. This differs from
 * {@link ItemSubcommand}, which defers the hop to a Plan 04 v2 fix inside
 * {@link com.forgebook.ai.RagItemPipeline}.
 *
 * <h2>Audit logging split</h2>
 * {@link AiDispatcher#dispatch} already emits {@link RequestAuditLogger#logSuccess} and
 * {@link RequestAuditLogger#logFailure} on its two terminal paths. This subcommand only
 * emits the two paths {@code AiDispatcher} cannot: {@link RequestAuditLogger#logDenied}
 * (authorizer denial before dispatch) and {@link RequestAuditLogger#logFailure} with
 * {@link ErrorCode#OVERLOADED} (queue overflow before dispatch).
 *
 * <h2>Test seam</h2>
 * The package-private {@link #executeInternal} seam takes pure-Java {@link Function},
 * {@link Supplier}, and {@link Consumer} collaborators so tests never touch
 * {@link ServerPlayer}, {@link CommandSourceStack}, or {@link MinecraftServer}. Same
 * discipline as {@link ItemSubcommand#executeInternal} and
 * {@link com.forgebook.ai.RagItemPipeline#runInternal}.
 */
public final class AskSubcommand {

    private static final Logger LOG = LogManager.getLogger();

    private AskSubcommand() {}

    // ------------------------------------------------------------------
    // Public entry point (production)
    // ------------------------------------------------------------------

    public static int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String message = StringArgumentType.getString(ctx, "message");
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();

        return executeInternal(
            src,
            player,
            player.getUUID(),
            message,
            ConfigHolder::get,
            RateLimiterHolder::get,
            dc -> AiDispatcher.INSTANCE.dispatch(dc),
            AiExecutor::get,
            server::execute,
            sender -> new DispatchContext(message, sender, RequestKind.ASK));
    }

    // ------------------------------------------------------------------
    // Package-private core + test seam
    // ------------------------------------------------------------------

    /**
     * Core pipeline. Separated from {@link #execute} so unit tests can drive it with
     * pure-Java stubs for every Minecraft-or-static dependency.
     *
     * <p>Invariants enforced here:
     * <ul>
     *   <li>{@link Authorizer#authorize} is consulted BEFORE any executor access.</li>
     *   <li>{@link RequestKind#ASK} is used for both authorize and DispatchContext construction.</li>
     *   <li>Final {@code sendSuccess} / {@code sendFailure} for the Allowed path is always
     *       wrapped in the {@code tickThreadHop} (production: {@code server.execute(...)}).</li>
     * </ul>
     */
    static int executeInternal(
            CommandSourceStack src,
            ServerPlayer player,
            UUID uuid,
            String message,
            Supplier<ConfigSnapshot> snapshotSupplier,
            Supplier<RateLimiter> limiterSupplier,
            Function<DispatchContext, AiDispatcher.Result> dispatcher,
            Supplier<ExecutorService> executorSupplier,
            Consumer<Runnable> tickThreadHop,
            Function<ServerPlayer, DispatchContext> contextFactory) {

        // D-14: single volatile load.
        ConfigSnapshot snap = snapshotSupplier.get();
        if (snap == null) {
            sendFailureKey(src, "forgebook.command.not_initialized");
            return 0;
        }
        long startNanos = System.nanoTime();
        LOG.info("ask request uuid={} msg_len={}",
            uuid, message == null ? 0 : message.length());
        Authorizer.Result auth = Authorizer.authorize(
            snap, player, RequestKind.ASK, limiterSupplier.get());
        if (auth instanceof Authorizer.Denied d) {
            LOG.info("ask denied uuid={} code={}", uuid, d.code());
            RequestAuditLogger.logDenied(uuid, RequestKind.ASK, d.code(), startNanos);
            // Phase 5 / REL-02: Denied.feedback carries the Component.translatable.
            // humanReadable is the English fallback used by the wire path + test sink.
            if (src != null) {
                src.sendFailure(d.feedback());
            }
            failureSinkForTests.accept(d.humanReadable());
            return 0;
        }

        try {
            executorSupplier.get().submit(() -> {
                try {
                    AiDispatcher.Result result = dispatcher.apply(contextFactory.apply(player));
                    // Hop back to tick thread for the final send (Pitfall 2).
                    tickThreadHop.accept(() -> {
                        if (result instanceof AiDispatcher.Reply r) {
                            LOG.info("ask reply uuid={} truncated={} text_len={}",
                                uuid, r.truncated(), r.text() == null ? 0 : r.text().length());
                            // INTENTIONAL — AI reply text is model-generated prose, not a translation key.
                            // Component.literal here per i18n audit carve-out.
                            sendSuccess(src, r.text());
                        } else if (result instanceof AiDispatcher.Error err) {
                            LOG.info("ask error uuid={} code={}", uuid, err.code());
                            // Phase 5 / REL-02: Error.feedback carries the Component.translatable.
                            if (src != null) {
                                src.sendFailure(err.feedback());
                            }
                            failureSinkForTests.accept(err.humanReadable());
                        }
                    });
                } catch (Exception ex) {
                    LOG.error("Dispatch failed for ASK by {}", uuid, ex);
                    tickThreadHop.accept(() -> sendFailureKey(src, "forgebook.command.internal_error"));
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warn("aiExecutor rejected ASK submission for {}; returning OVERLOADED.", uuid);
            RequestAuditLogger.logFailure(
                uuid, RequestKind.ASK, ErrorCode.OVERLOADED, 0, 0, 0L);
            sendFailureKey(src, "forgebook.command.overloaded");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void sendSuccess(CommandSourceStack src, String text) {
        if (src != null) {
            // INTENTIONAL — AI reply text is model-generated prose, not a translation key.
            // Component.literal here per i18n audit carve-out.
            src.sendSuccess(() -> Component.literal(text), false);
        }
        successSinkForTests.accept(text);
    }

    /**
     * Phase 5 / REL-02: failure emission that wraps a translation key in
     * {@link Component#translatable(String, Object...)} for the command surface.
     * The key (not the resolved prose) is forwarded to the test sink, so assertions
     * flip from English prose to key strings.
     */
    private static void sendFailureKey(CommandSourceStack src, String key, Object... args) {
        if (src != null) {
            src.sendFailure(Component.translatable(key, args));
        }
        failureSinkForTests.accept(key);
    }

    /**
     * Test sinks — null-safe no-ops in production. Unit tests install to capture
     * the text passed to the production CommandSourceStack methods (without constructing
     * a real source). MUST be reset in test teardown.
     */
    /* @VisibleForTesting */ static volatile Consumer<String> successSinkForTests = s -> {};
    /* @VisibleForTesting */ static volatile Consumer<String> failureSinkForTests = s -> {};
}
