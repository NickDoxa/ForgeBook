package com.forgebook.ai;

import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.ai.dto.ToolDef;
import com.forgebook.ai.provider.ProviderFactory;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.integration.scraper.ModDocsScraper;
import com.forgebook.integration.scraper.PromptFraming;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.safety.Authorizer;
import com.forgebook.safety.RateLimiterHolder;
import com.forgebook.safety.RequestAuditLogger;
import com.forgebook.util.SafeHttpFetcher;
import com.forgebook.util.UnsafeUrlException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * RAG single-shot pipeline powering {@code /forgebook item} (CMD-02, CMD-07).
 *
 * <p>Behaviour — in order:
 * <ol>
 *   <li>Load the current {@link ConfigSnapshot} via {@link ConfigHolder#get()} (D-14 single volatile read).</li>
 *   <li>Authorize through {@link Authorizer#authorize(ConfigSnapshot, ServerPlayer, RequestKind, com.forgebook.safety.RateLimiter)}
 *       BEFORE any fetch or provider call (SAFE-06 ordering).</li>
 *   <li>If the mod has no documentation URL, emit a PROVIDER failure audit line + user-facing "try /forgebook ask" hint.</li>
 *   <li>Fetch the URL through {@link SafeHttpFetcher} (SSRF-safe chokepoint — same pattern as
 *       {@link com.forgebook.tool.impl.FetchModDocsPageTool}), extract readable text via {@link ModDocsScraper},
 *       and wrap it with {@link PromptFraming} ({@code <mod_doc trust="untrusted">} envelope — D-10).</li>
 *   <li>Issue EXACTLY ONE {@link com.forgebook.ai.AiProvider#chat(ChatRequest)} call with empty
 *       {@code tools[]} (Pattern 3 invariant — Anthropic cannot return {@code stop_reason = "tool_use"}
 *       when tools is empty, eliminating the whole tool-using loop body).</li>
 *   <li>On {@link AiTurn.FinalReply}, append {@code "\n\nSource: " + modURL} (CMD-07 citation invariant).</li>
 *   <li>Emit a {@link RequestAuditLogger} call on every terminal path — success, failure, or denied.</li>
 * </ol>
 *
 * <h2>Caller contract</h2>
 * This class does NOT wrap {@code sendSuccess}/{@code sendFailure} in {@code server.execute(...)}.
 * The caller (Plan 06 {@code ItemSubcommand}) is responsible for ensuring this runs on a thread
 * where those calls are safe — either the tick thread directly, or wrapped by the caller in
 * {@code server.execute(...)}. Double-hopping (caller + this class both calling {@code server.execute})
 * would be harmless but adds an extra tick of latency.
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>{@link SafeHttpFetcher} enforces the https-only scheme, private-IP block, redirect limit,
 *       and 1 MB body cap — mod authors cannot aim this pipeline at internal services.</li>
 *   <li>Framed doc is treated as untrusted input by the model (D-10 prompt-injection framing).</li>
 *   <li>No user message content is ever logged — audit payload is strictly metadata
 *       (uuid / kind / tokens / latency / outcome) per SAFE-04 + Pitfall 5.</li>
 * </ul>
 *
 * <h2>Test seam</h2>
 * The package-private {@link #runInternal} overload exists because
 * {@link ServerPlayer}, {@link CommandSourceStack}, and {@link Component#literal(String)} are hostile
 * to pure-JUnit instantiation outside the game harness (see CLAUDE.md "avoid mocking Minecraft classes"
 * and the Plan 03 {@link Authorizer} primitive-overload precedent). Unit tests construct a pure-Java
 * {@link Feedback} stub + an {@link AuthFn} lambda returning the desired {@link Authorizer.Result}
 * + {@link FetchFn} + {@link Function Function&lt;ConfigSnapshot, AiProvider&gt;} and drive
 * {@code runInternal} directly, avoiding any Minecraft classloading.
 */
public final class RagItemPipeline {

    private static final Logger LOG = LogManager.getLogger();

    /** Single-shot user prompt template — {itemId, modId, framed doc}. */
    private static final String RAG_USER_TEMPLATE =
        "Explain how to use this Minecraft item to a player who has never seen it.\n" +
        "Item: %s (mod: %s)\n" +
        "Cite the source URL in your reply. If the documentation below is empty " +
        "or off-topic, say you couldn't find documentation for this item.\n\n" +
        "%s";

    /** Canonical fallback system prompt when SystemPromptCache has not been seeded yet. */
    private static final String FALLBACK_SYSTEM_PROMPT =
        "You are ForgeBook. Cite source URLs. Be concise.";

    private RagItemPipeline() {}

    // ------------------------------------------------------------------
    // Public entry point (production)
    // ------------------------------------------------------------------

    /**
     * Execute the RAG single-shot pipeline for {@code /forgebook item}.
     *
     * @param src     Brigadier command source — used for {@code sendSuccess}/{@code sendFailure}
     * @param player  invoking player (already resolved from {@code ctx.getSource().getPlayerOrException()})
     * @param modId   mod id owning the target item (e.g. {@code "create"})
     * @param itemId  fully-qualified item id (e.g. {@code "create:cogwheel"})
     * @param modURL  the mod's {@code IModInfo.getModURL()} result — empty if the mod declares no docs URL
     * @param kind    request kind for audit logging / dispatch (typically {@link RequestKind#ITEM})
     */
    public static void run(CommandSourceStack src,
                           ServerPlayer player,
                           String modId,
                           String itemId,
                           Optional<URL> modURL,
                           RequestKind kind) {
        runInternal(
            feedbackOf(src),
            player == null ? null : player.getUUID(),
            modId,
            itemId,
            modURL,
            kind,
            ConfigHolder.get(),
            // Real OP gate / rate limit / kill switch consulted here in production.
            snap -> Authorizer.authorize(snap, player, kind, RateLimiterHolder.get()),
            uri -> new SafeHttpFetcher().fetch(uri),
            ProviderFactory::create
        );
    }

    // ------------------------------------------------------------------
    // Package-private core + test seam
    // ------------------------------------------------------------------

    /**
     * Core pipeline. Separated from {@link #run} so unit tests can drive it with pure-Java
     * stubs for every Minecraft-or-static dependency. Production always enters through
     * {@link #run}.
     *
     * <p>Invariants enforced here:
     * <ul>
     *   <li>Exactly one {@link RequestAuditLogger} call fires on every terminal path.</li>
     *   <li>{@link AuthFn#authorize} is consulted BEFORE any fetch or provider call.</li>
     *   <li>{@link ChatRequest#tools()} is always {@link List#of()} — never populated.</li>
     *   <li>On success, reply text ends with {@code "\n\nSource: " + modURL}.</li>
     * </ul>
     */
    static void runInternal(Feedback feedback,
                            UUID uuid,
                            String modId,
                            String itemId,
                            Optional<URL> modURL,
                            RequestKind kind,
                            ConfigSnapshot snap,
                            AuthFn authFn,
                            FetchFn fetch,
                            Function<ConfigSnapshot, AiProvider> providerFactory) {
        if (snap == null) {
            LOG.error("RagItemPipeline invoked before ConfigHolder seeded — this should be " +
                "impossible after ServerStartingEvent. Check ForgeBookMod wiring.");
            feedback.sendFailure("ForgeBook not initialized — check server logs.");
            return;
        }

        final long startNanos = System.nanoTime();

        // 1. Authorize (SAFE-01..SAFE-03, CMD-05 kill switch).
        Authorizer.Result auth = authFn.authorize(snap);
        if (auth instanceof Authorizer.Denied d) {
            if (uuid != null) {
                RequestAuditLogger.logDenied(uuid, kind, d.code(), startNanos);
            }
            feedback.sendFailure(d.humanReadable());
            return;
        }

        // 2. No docs URL → PROVIDER failure. User is directed to /forgebook ask for web search.
        if (modURL.isEmpty()) {
            if (uuid != null) {
                RequestAuditLogger.logFailure(uuid, kind, ErrorCode.PROVIDER, 0, 0, elapsedMs(startNanos));
            }
            feedback.sendFailure(
                "No documentation URL is registered for mod '" + modId +
                "'. Try /forgebook ask <question> for a web-search-backed answer.");
            return;
        }

        // 3. Fetch + scrape + frame (SafeHttpFetcher is the SSRF-safe chokepoint — T-03-04-02).
        final URL url = modURL.get();
        final String framed;
        try {
            SafeHttpFetcher.Result r = fetch.fetch(URI.create(url.toString()));
            String readable = ModDocsScraper.extractReadable(r.body(), url.toString());
            framed = PromptFraming.wrap(readable, url.toString());
        } catch (UnsafeUrlException | IOException e) {
            // Do NOT log the framed body (T-03-04-04). Only modId + URL + exception class.
            LOG.warn("RAG fetch failed for {} {}: {}", modId, url, e.toString());
            if (uuid != null) {
                RequestAuditLogger.logFailure(uuid, kind, ErrorCode.TRANSPORT, 0, 0, elapsedMs(startNanos));
            }
            feedback.sendFailure("Could not fetch mod documentation. Try again later.");
            return;
        }

        // 4. Build a single-shot ChatRequest. EMPTY tools[] is the Pattern 3 anchor —
        //    Anthropic cannot return stop_reason="tool_use" when tools is empty.
        final String userMsg = String.format(RAG_USER_TEMPLATE, itemId, modId, framed);
        final String cachedPrompt = SystemPromptCache.get();
        final String systemPrompt = (cachedPrompt == null || cachedPrompt.isEmpty())
            ? FALLBACK_SYSTEM_PROMPT : cachedPrompt;
        ChatRequest req = new ChatRequest(
            snap.aiModel(),
            snap.maxTokens(),
            systemPrompt,
            List.of(ClaudeMessage.userText(userMsg)),
            List.of()
        );

        // 5. Single provider call — NO tool-using loop.
        AiProvider provider = providerFactory.apply(snap);
        AiTurn turn;
        try {
            turn = provider.chat(req).join();
        } catch (Exception ex) {
            LOG.error("RAG provider call failed for {}", uuid, ex);
            if (uuid != null) {
                RequestAuditLogger.logFailure(uuid, kind, ErrorCode.PROVIDER, 0, 0, elapsedMs(startNanos));
            }
            feedback.sendFailure("AI provider returned an error.");
            return;
        }

        final long latencyMs = elapsedMs(startNanos);

        // 6. Branch on AiTurn (instanceof chain per Phase 2 precedent — no switch-on-pattern).
        if (turn instanceof AiTurn.FinalReply fr) {
            int inTok = fr.usage().map(u -> u.inputTokens)
                .orElseGet(() -> estimateTokens(userMsg, systemPrompt));
            int outTok = fr.usage().map(u -> u.outputTokens)
                .orElseGet(() -> estimateTokens(fr.text()));
            if (uuid != null) {
                RequestAuditLogger.logSuccess(uuid, kind, inTok, outTok, latencyMs);
            }
            // CMD-07: reply must end with "\n\nSource: <modURL>" — defense-in-depth citation.
            String reply = fr.text() + "\n\nSource: " + url;
            feedback.sendSuccess(reply);
        } else if (turn instanceof AiTurn.ProviderError err) {
            AiDispatcher.Error mapped = AiDispatcher.mapError(err); // reuse Phase 2 mapping
            if (uuid != null) {
                RequestAuditLogger.logFailure(uuid, kind, mapped.code(), 0, 0, latencyMs);
            }
            feedback.sendFailure(mapped.humanReadable());
        } else {
            // Defensive — a ToolUses return should be impossible with empty tools[],
            // but the sealed contract admits it, so surface as PROVIDER error with no user data.
            LOG.warn("Provider returned ToolUses with empty tools[] — this should never happen.");
            if (uuid != null) {
                RequestAuditLogger.logFailure(uuid, kind, ErrorCode.PROVIDER, 0, 0, latencyMs);
            }
            feedback.sendFailure("Unexpected provider response.");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Crude token estimate used when {@code AiTurn.FinalReply.usage()} is absent: 1 token ~= 4 chars. */
    private static int estimateTokens(String... parts) {
        int chars = 0;
        for (String p : parts) {
            if (p != null) chars += p.length();
        }
        return Math.max(1, chars / 4);
    }

    /** Bridges a real {@link CommandSourceStack} to the pure-Java {@link Feedback} seam. */
    private static Feedback feedbackOf(CommandSourceStack src) {
        return new Feedback() {
            @Override public void sendSuccess(String text) {
                src.sendSuccess(() -> Component.literal(text), false);
            }
            @Override public void sendFailure(String text) {
                src.sendFailure(Component.literal(text));
            }
        };
    }

    // ------------------------------------------------------------------
    // Test seams (all interfaces below are package-private by design —
    // only the in-package test class {@code RagItemPipelineTest} may depend on them)
    // ------------------------------------------------------------------

    /** Abstraction over {@link CommandSourceStack#sendSuccess}/{@link CommandSourceStack#sendFailure}. */
    interface Feedback {
        void sendSuccess(String text);
        void sendFailure(String text);
    }

    /** Test seam mirroring {@link SafeHttpFetcher#fetch(URI)} so tests avoid live network. */
    @FunctionalInterface
    interface FetchFn {
        SafeHttpFetcher.Result fetch(URI uri) throws UnsafeUrlException, IOException;
    }

    /**
     * Test seam mirroring the {@link Authorizer#authorize} invocation. Tests return a canned
     * {@link Authorizer.Result} without constructing a {@link ServerPlayer}.
     */
    @FunctionalInterface
    interface AuthFn {
        Authorizer.Result authorize(ConfigSnapshot snap);
    }
}
