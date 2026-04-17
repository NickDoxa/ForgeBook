package com.forgebook.ai;

import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.ai.dto.ToolDef;
import com.google.gson.JsonPrimitive;
import com.forgebook.ai.provider.ProviderFactory;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server singleton — the SOLE entry point for chat dispatch (AI-04).
 *
 * Invariants (PATTERNS §"AiDispatcher"):
 *   - dispatch() runs SYNCHRONOUSLY inside ChatRequestHandler's already-submitted
 *     AiExecutor task. It does NOT submit to AiExecutor itself.
 *   - AgentLoop.run is called inline; AgentLoop's parallel sub-tasks are what
 *     touch AiExecutor again (per Plan 06).
 *   - Final game-state mutation (network send) happens in ctx.enqueueWork —
 *     that's the handler's responsibility, NOT this class.
 *
 * Test seam: dispatcherForTests(provider) returns an instance with the provider
 * pre-injected. Production INSTANCE constructs the provider per-dispatch via
 * ProviderFactory.create(snap) so config reloads pick up provider changes
 * (e.g., operator switching from ANTHROPIC to OLLAMA via /forgebook reload).
 *
 * D-14 (single-snapshot read): ConfigHolder.get() called exactly once at entry;
 * the snapshot is passed to all downstream collaborators.
 *
 * Security invariant (T-02-07-04): this class NEVER calls ApiKey.raw().
 * API key access is confined to com.forgebook.ai.provider.* implementations.
 *
 * Phase 3 will add: OP gate (SAFE-01) + per-player rate limit (SAFE-02) at the
 * top of dispatch(). v1 has no permission check (operator must keep op_only=true
 * by default, which Phase 3 enforces).
 */
public final class AiDispatcher {

    private static final Logger LOG = LogManager.getLogger();
    private static final String FALLBACK_SYSTEM_PROMPT =
        "You are ForgeBook. Answer concisely. Use the provided tools when uncertain.";

    /** Sealed result mirroring AiTurn but with ErrorCode pre-mapped for the network layer. */
    public sealed interface Result permits Reply, Error {}

    /** Terminal reply from the AI agent. truncated=true when stop_reason == "max_tokens". */
    public record Reply(String text, boolean truncated) implements Result {}

    /**
     * Typed error ready for ChatErrorPacket encoding.
     *
     * <h3>Phase 5 (REL-02) — Option A split fields</h3>
     * Mirrors {@link com.forgebook.safety.Authorizer.Denied}:
     * <ul>
     *   <li>{@code humanReadable} — default-locale English fallback, preserved for
     *       {@link com.forgebook.network.packet.ChatErrorPacket} wire encoding.</li>
     *   <li>{@code feedback} — {@link Component#translatable(String, Object...)} for
     *       command-surface rendering (Ask/Item/RagItemPipeline).</li>
     * </ul>
     */
    public record Error(ErrorCode code, String humanReadable, Component feedback) implements Result {}

    /** Production singleton. Null injectedProvider means create provider per-dispatch. */
    public static final AiDispatcher INSTANCE = new AiDispatcher(null);

    /**
     * Null in production — INSTANCE.dispatch creates the provider per call from ProviderFactory.
     * Non-null only via dispatcherForTests for unit tests.
     */
    private final AiProvider injectedProvider;

    private AiDispatcher(AiProvider injectedProvider) {
        this.injectedProvider = injectedProvider;
    }

    /**
     * Test seam — returns a dispatcher that uses the given provider instead of ProviderFactory.
     * Production callers MUST use INSTANCE.
     */
    public static AiDispatcher dispatcherForTests(AiProvider provider) {
        return new AiDispatcher(provider);
    }

    /**
     * Dispatch a chat request. Returns Reply or Error — never throws.
     * MUST be called from inside an AiExecutor task (caller: ChatRequestHandler,
     * AskSubcommand — both after Authorizer.authorize Allowed).
     *
     * D-14: ConfigHolder.get() is called exactly once at entry — single volatile load.
     * Phase 3: emits RequestAuditLogger.logSuccess/logFailure on the two terminal paths.
     */
    public Result dispatch(com.forgebook.ai.DispatchContext dc) {
        // D-14: single volatile load of config snapshot at entry
        ConfigSnapshot snap = ConfigHolder.get();
        if (snap == null) {
            LOG.error("AiDispatcher invoked before ConfigHolder seeded — this should be " +
                "impossible after ServerStartingEvent. Check ForgeBookMod wiring.");
            return new Error(
                ErrorCode.PROVIDER,
                "ForgeBook not initialized — check server logs.",
                Component.translatable("forgebook.command.not_initialized"));
        }

        long startNanos = System.nanoTime();

        String systemPrompt = SystemPromptCache.get();
        if (systemPrompt.isEmpty()) {
            LOG.warn("SystemPromptCache empty (ServerStartedEvent may not have fired yet); " +
                "using fallback system prompt.");
            systemPrompt = FALLBACK_SYSTEM_PROMPT;
        }

        AiProvider provider = (injectedProvider != null)
            ? injectedProvider
            : ProviderFactory.create(snap);

        // Build initial message list (mutable — AgentLoop appends tool turns)
        List<ClaudeMessage> initialMessages = new ArrayList<>();
        initialMessages.add(ClaudeMessage.userText(dc.message()));

        // Convert ToolRegistry entries to ToolDef wire shapes
        Collection<Tool> tools = ToolRegistry.all();
        List<ToolDef> toolDefs = tools.stream()
            .map(t -> new ToolDef(t.name(), t.description(), t.schema()))
            .toList();

        ChatRequest req = new ChatRequest(
            snap.aiModel(),
            snap.maxTokens(),
            systemPrompt,
            initialMessages,
            toolDefs
        );

        String senderUuid = dc.sender() != null ? dc.sender().getUUID().toString() : "<no sender>";
        LOG.info("dispatch start uuid={} kind={} provider={} model={} max_tokens={} tools={} msg_len={}",
            senderUuid, dc.kind(), snap.aiProvider(), snap.aiModel(), snap.maxTokens(),
            toolDefs.size(), dc.message() == null ? 0 : dc.message().length());

        AgentLoop loop = new AgentLoop(provider);
        AiTurn outcome;
        try {
            outcome = loop.run(req);
        } catch (Exception e) {
            LOG.error("AgentLoop threw unexpected exception for {}",
                dc.sender() != null ? dc.sender().getUUID() : "<no sender>", e);
            return new Error(
                ErrorCode.PROVIDER,
                "Unexpected internal error.",
                Component.translatable("forgebook.command.provider.unexpected_internal"));
        }

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // Java 17 pattern-matching instanceof chain (sealed AiTurn variants).
        if (outcome instanceof AiTurn.FinalReply r) {
            int inTok = r.usage().map(u -> u.inputTokens).orElseGet(() -> estimateTokens(dc.message()));
            int outTok = r.usage().map(u -> u.outputTokens).orElseGet(() -> estimateTokens(r.text()));
            LOG.info("dispatch reply uuid={} in_tok={} out_tok={} truncated={} latency_ms={}",
                senderUuid, inTok, outTok, r.truncated(), latencyMs);
            if (dc.sender() != null) {
                com.forgebook.safety.RequestAuditLogger.logSuccess(
                    dc.sender().getUUID(), dc.kind(), inTok, outTok, latencyMs);
            }
            return new Reply(r.text(), r.truncated());
        } else if (outcome instanceof AiTurn.ProviderError err) {
            Error mapped = mapError(err);
            LOG.info("dispatch error uuid={} kind={} code={} latency_ms={} detail={}",
                senderUuid, err.kind(), mapped.code(), latencyMs, err.message());
            if (dc.sender() != null) {
                com.forgebook.safety.RequestAuditLogger.logFailure(
                    dc.sender().getUUID(), dc.kind(), mapped.code(), 0, 0, latencyMs);
            }
            return mapped;
        } else {
            // AiTurn.ToolUses — AgentLoop should never return this (it loops until
            // FinalReply or ProviderError or iteration cap). Defensive case.
            LOG.error("AgentLoop returned ToolUses instead of FinalReply/ProviderError — " +
                "this is a Plan 06 bug; surfacing as PROVIDER error.");
            if (dc.sender() != null) {
                com.forgebook.safety.RequestAuditLogger.logFailure(
                    dc.sender().getUUID(), dc.kind(), ErrorCode.PROVIDER, 0, 0, latencyMs);
            }
            return new Error(
                ErrorCode.PROVIDER,
                "AI agent did not produce a final reply.",
                Component.translatable("forgebook.command.provider.no_final_reply"));
        }
    }

    /** Fallback token estimate when Usage is absent: 1 token per ~4 chars. */
    private static int estimateTokens(String s) {
        return s == null ? 0 : Math.max(1, s.length() / 4);
    }

    /**
     * Maps ProviderError.Kind to ChatErrorPacket.ErrorCode per RESEARCH §7.5 / D-13.
     * Package-private so AiDispatcherTest can call it directly for mapError tests 11-17.
     */
    static Error mapError(AiTurn.ProviderError err) {
        return switch (err.kind()) {
            case TRANSPORT       -> new Error(
                ErrorCode.TRANSPORT,
                "Transient network issue. Try again.",
                Component.translatable("forgebook.command.provider.transport"));
            case PROVIDER        -> new Error(
                ErrorCode.PROVIDER,
                "AI provider returned an error.",
                Component.translatable("forgebook.command.provider_error"));
            case OVERLOADED      -> new Error(
                ErrorCode.OVERLOADED,
                "Server is busy. Try again.",
                Component.translatable("forgebook.command.overloaded"));
            case RATE_LIMITED    -> new Error(
                ErrorCode.RATE_LIMITED,
                "You're sending requests too fast.",
                Component.translatable("forgebook.command.provider.rate_limited"));
            case NOT_IMPLEMENTED -> new Error(
                ErrorCode.PROVIDER,
                "This AI provider is not implemented in v1.",
                Component.translatable("forgebook.command.provider.not_implemented"));
            case CIRCUIT_OPEN    -> new Error(
                ErrorCode.PROVIDER,
                "AI provider is temporarily unavailable.",
                Component.translatable("forgebook.command.provider.circuit_open"));
            case ITERATION_CAP   -> new Error(
                ErrorCode.PROVIDER,
                "AI agent could not complete the task within " +
                    AgentLoop.MAX_ITERATIONS + " iterations.",
                Component.translatable(
                    "forgebook.command.provider.iteration_cap", AgentLoop.MAX_ITERATIONS));
        };
    }
}
