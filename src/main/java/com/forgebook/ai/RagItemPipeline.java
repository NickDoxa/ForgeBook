package com.forgebook.ai;

import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.ai.dto.ToolDef;
import com.forgebook.ai.provider.ProviderFactory;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.integration.CurseForgeClient;
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

    /**
     * Item-specific max_tokens ceiling. Overrides the global snap.maxTokens() only
     * when the global is higher — keeps /forgebook item responses snappy (Haiku
     * generates ~50 tok/s; 1024 tokens = ~20s, 400 tokens = ~8s). Item answers
     * rarely need more than a couple of paragraphs.
     */
    private static final int RAG_MAX_TOKENS = 400;

    /**
     * Per-request truncation of scraped doc text. Shorter input = shorter first-byte
     * latency from Claude. PromptFraming.TOOL_OUTPUT_CAP (8000) stays intact for the
     * tool-using agent loop; the RAG single-shot path uses this tighter cap.
     */
    private static final int RAG_SCRAPED_CAP = 3_000;

    /**
     * Scraped + framed document cache, keyed by canonical URL string. Hitting the
     * cache on the 2nd through Nth item query against the same mod skips both the
     * network fetch (~1-5s) AND the jsoup parse (~100ms-1s). Users typically browse
     * many items from the same mod in succession, so this is a huge UX win.
     *
     * <p>Bounded at {@link #DOCS_CACHE_MAX} entries with simple first-insertion
     * eviction — no weighted LRU needed at this scale. ConcurrentHashMap is safe
     * for the AiExecutor thread pool.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> DOCS_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int DOCS_CACHE_MAX = 32;

    static void clearDocsCacheForTests() { DOCS_CACHE.clear(); }

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
            // CurseForge description API — returns empty when URL isn't CF or key unset,
            // which causes the pipeline to fall through to the generic SafeHttpFetcher path.
            CurseForgeClient::fetchDescriptionBySlug,
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
                            CfDescFn cfDesc,
                            Function<ConfigSnapshot, AiProvider> providerFactory) {
        if (snap == null) {
            LOG.error("RagItemPipeline invoked before ConfigHolder seeded — this should be " +
                "impossible after ServerStartingEvent. Check ForgeBookMod wiring.");
            feedback.sendFailureKey("forgebook.command.not_initialized");
            return;
        }

        final long startNanos = System.nanoTime();

        // 1. Authorize (SAFE-01..SAFE-03, CMD-05 kill switch).
        Authorizer.Result auth = authFn.authorize(snap);
        if (auth instanceof Authorizer.Denied d) {
            LOG.info("rag denied uuid={} modId={} itemId={} code={}", uuid, modId, itemId, d.code());
            if (uuid != null) {
                RequestAuditLogger.logDenied(uuid, kind, d.code(), startNanos);
            }
            // Phase 5 / REL-02: Denied.feedback carries the Component.translatable.
            feedback.sendFailureComponent(d.feedback());
            return;
        }

        // 2. No docs URL → try web search as a last resort if the operator enabled it.
        //    Otherwise surface the no-docs message and let the user try /forgebook ask.
        Optional<URL> effectiveUrl = modURL;
        if (effectiveUrl.isEmpty()) {
            LOG.info("rag no_doc_url uuid={} modId={} trying web_search={}",
                uuid, modId, snap.enableWebSearch());
            effectiveUrl = tryWebSearchForDocUrl(modId, itemId, snap);
            if (effectiveUrl.isEmpty()) {
                LOG.info("rag fail uuid={} modId={} reason=no_docs_url", uuid, modId);
                if (uuid != null) {
                    RequestAuditLogger.logFailure(uuid, kind, ErrorCode.PROVIDER, 0, 0, elapsedMs(startNanos));
                }
                feedback.sendFailureKey("forgebook.command.item.no_docs_url", modId);
                return;
            }
            LOG.info("rag resolved_via_web_search uuid={} modId={} url={}",
                uuid, modId, effectiveUrl.get());
        }

        // 3. Fetch + scrape + frame — with per-URL cache to skip network+parse on repeat queries.
        //    CurseForge mod URLs are routed through the CF REST API when the operator has
        //    set curseforge_api_key — api.curseforge.com returns the mod's HTML description
        //    over an authenticated channel, bypassing the 403-by-default that the public
        //    HTML page returns to non-browser user agents. On any failure (no key, no
        //    search hit, non-200, parse error) the pipeline falls through to the generic
        //    SafeHttpFetcher path so non-CF hosts (github, fandom, rtd, etc.) still work.
        //    SafeHttpFetcher is still the SSRF-safe chokepoint for that fallback — T-03-04-02.
        final URL url = effectiveUrl.get();
        final String urlKey = url.toString();
        String framed = DOCS_CACHE.get(urlKey);
        if (framed != null) {
            LOG.info("rag docs_cache_hit uuid={} url={} framed_chars={}",
                uuid, urlKey, framed.length());
        }
        if (framed == null) {
            String readable = null;

            // 3a. CurseForge API shortcut — only attempted for curseforge.com URLs with a key.
            Optional<String> cfSlug = CurseForgeClient.extractSlugFromUrl(url);
            if (cfSlug.isPresent() && !snap.curseforgeApiKey().raw().isBlank()) {
                LOG.info("rag fetch uuid={} source=cf_api slug={}", uuid, cfSlug.get());
                try {
                    Optional<String> cfHtml = cfDesc.fetch(cfSlug.get(), snap);
                    if (cfHtml.isPresent()) {
                        readable = ModDocsScraper.extractReadable(cfHtml.get(), urlKey);
                        LOG.info("rag fetch_ok uuid={} source=cf_api slug={} html_chars={} readable_chars={}",
                            uuid, cfSlug.get(), cfHtml.get().length(), readable.length());
                    } else {
                        LOG.info("rag fetch_miss uuid={} source=cf_api slug={} — falling through to http",
                            uuid, cfSlug.get());
                    }
                } catch (Exception e) {
                    LOG.debug("CurseForge API lookup failed for slug {}: {}", cfSlug.get(), e.toString());
                    // fall through to SafeHttpFetcher
                }
            }

            // 3b. Generic HTTP fallback — covers non-CF hosts and CF URLs when no key is set.
            if (readable == null) {
                LOG.info("rag fetch uuid={} source=safe_http url={}", uuid, urlKey);
                try {
                    SafeHttpFetcher.Result r = fetch.fetch(URI.create(urlKey));
                    readable = ModDocsScraper.extractReadable(r.body(), urlKey);
                    LOG.info("rag fetch_ok uuid={} source=safe_http body_chars={} readable_chars={} content_type={}",
                        uuid, r.body() == null ? 0 : r.body().length(), readable.length(), r.contentType());
                } catch (UnsafeUrlException | IOException e) {
                    // Do NOT log the framed body (T-03-04-04). Only modId + URL + exception class.
                    LOG.warn("RAG fetch failed for {} {}: {}", modId, url, e.toString());
                    if (uuid != null) {
                        RequestAuditLogger.logFailure(uuid, kind, ErrorCode.TRANSPORT, 0, 0, elapsedMs(startNanos));
                    }
                    feedback.sendFailureKey("forgebook.command.item.fetch_failed");
                    return;
                }
            }

            // RAG-specific tighter truncation — keeps the prompt small so
            // Haiku's first-token-latency stays short. The full TOOL_OUTPUT_CAP
            // still applies to the tool-using agent loop.
            if (readable.length() > RAG_SCRAPED_CAP) {
                readable = readable.substring(0, RAG_SCRAPED_CAP)
                         + "\n[... truncated — full docs at " + urlKey + "]";
            }
            framed = PromptFraming.wrap(readable, urlKey);
            // Simple bounded cache — clear oldest half when we hit the cap.
            if (DOCS_CACHE.size() >= DOCS_CACHE_MAX) {
                DOCS_CACHE.keySet().stream().limit(DOCS_CACHE_MAX / 2)
                    .forEach(DOCS_CACHE::remove);
            }
            DOCS_CACHE.put(urlKey, framed);
        }

        // 4. Build a single-shot ChatRequest. EMPTY tools[] is the Pattern 3 anchor —
        //    Anthropic cannot return stop_reason="tool_use" when tools is empty.
        //    Cap max_tokens at RAG_MAX_TOKENS to keep interactive latency low.
        final String userMsg = String.format(RAG_USER_TEMPLATE, itemId, modId, framed);
        final String cachedPrompt = SystemPromptCache.get();
        final String systemPrompt = (cachedPrompt == null || cachedPrompt.isEmpty())
            ? FALLBACK_SYSTEM_PROMPT : cachedPrompt;
        final int maxTokens = Math.min(snap.maxTokens(), RAG_MAX_TOKENS);
        ChatRequest req = new ChatRequest(
            snap.aiModel(),
            maxTokens,
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
            feedback.sendFailureKey("forgebook.command.provider_error");
            return;
        }

        final long latencyMs = elapsedMs(startNanos);

        // 6. Branch on AiTurn (instanceof chain per Phase 2 precedent — no switch-on-pattern).
        if (turn instanceof AiTurn.FinalReply fr) {
            int inTok = fr.usage().map(u -> u.inputTokens)
                .orElseGet(() -> estimateTokens(userMsg, systemPrompt));
            int outTok = fr.usage().map(u -> u.outputTokens)
                .orElseGet(() -> estimateTokens(fr.text()));
            LOG.info("rag reply uuid={} modId={} itemId={} in_tok={} out_tok={} truncated={} text_len={} latency_ms={}",
                uuid, modId, itemId, inTok, outTok, fr.truncated(),
                fr.text() == null ? 0 : fr.text().length(), latencyMs);
            if (uuid != null) {
                RequestAuditLogger.logSuccess(uuid, kind, inTok, outTok, latencyMs);
            }
            // CMD-07: reply must end with "\n\nSource: <modURL>" — defense-in-depth citation.
            // Phase 5 / REL-02 — carve-out: the source label is NOT wrapped in
            // Component.translatable here because the containing AI reply is emitted via
            // sendSuccess → Component.literal (model prose, not a translation key).
            // Wrapping just the label would require restructuring sendSuccess to accept
            // a MutableComponent chain (out of scope for v1). The
            // forgebook.command.item.source_label key is retained in en_us.json for
            // v2 when that restructure happens (tracked as deferred work).
            // Preserving the literal "Source: " prefix keeps CMD-07 byte-compatible.
            String reply = fr.text() + "\n\nSource: " + url;
            feedback.sendSuccess(reply);
        } else if (turn instanceof AiTurn.ProviderError err) {
            AiDispatcher.Error mapped = AiDispatcher.mapError(err); // reuse Phase 2 mapping
            LOG.info("rag error uuid={} modId={} itemId={} kind={} code={} latency_ms={} detail={}",
                uuid, modId, itemId, err.kind(), mapped.code(), latencyMs, err.message());
            if (uuid != null) {
                RequestAuditLogger.logFailure(uuid, kind, mapped.code(), 0, 0, latencyMs);
            }
            // Phase 5 / REL-02: mapped.feedback carries the Component.translatable.
            feedback.sendFailureComponent(mapped.feedback());
        } else {
            // Defensive — a ToolUses return should be impossible with empty tools[],
            // but the sealed contract admits it, so surface as PROVIDER error with no user data.
            LOG.warn("rag unexpected uuid={} modId={} itemId={} — provider returned ToolUses with empty tools[]",
                uuid, modId, itemId);
            if (uuid != null) {
                RequestAuditLogger.logFailure(uuid, kind, ErrorCode.PROVIDER, 0, 0, latencyMs);
            }
            feedback.sendFailureKey("forgebook.command.provider_unexpected");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Last-resort URL resolution via web search. Called only when the mod declares
     * no displayURL, has no description-embedded URL, and no credits-embedded URL
     * (i.e. all three tiers of {@code ItemSubcommand.DEFAULT_MOD_URL_LOOKUP}
     * returned empty). Requires {@code enable_web_search = true} in config.
     *
     * <p>Issues a single web-search query — something like
     * {@code "<modId> <itemId> minecraft mod wiki"} — and takes the first result's
     * URL. Returns empty if web search is disabled, the adapter isn't configured,
     * or no result comes back.
     *
     * <p>This is a deliberate trade-off: an extra HTTP round trip (~1-2s) vs
     * telling the user "no docs available" when docs almost always DO exist on
     * the public web. Disabled by default (operator opt-in).
     */
    private static Optional<URL> tryWebSearchForDocUrl(String modId, String itemId,
                                                        ConfigSnapshot snap) {
        if (!snap.enableWebSearch()) return Optional.empty();
        try {
            com.forgebook.integration.websearch.WebSearchAdapter adapter =
                com.forgebook.integration.websearch.WebSearchAdapterFactory.create(snap);
            if (adapter == null) return Optional.empty();
            String query = modId + " " + itemId + " minecraft mod wiki";
            java.util.List<com.forgebook.integration.websearch.SearchResult> results =
                adapter.search(query, 3);
            for (com.forgebook.integration.websearch.SearchResult r : results) {
                try {
                    URL u = java.net.URI.create(r.url()).toURL();
                    // Skip obviously-bad fallbacks (social, auth-walled, etc.).
                    String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
                    if (host.contains("discord") || host.contains("twitter.com")
                        || host.contains("x.com") || host.contains("patreon")) continue;
                    return Optional.of(u);
                } catch (java.net.MalformedURLException | IllegalArgumentException skip) {
                    // try next candidate
                }
            }
        } catch (Throwable t) {
            LOG.debug("Web-search fallback failed for {} {}: {}", modId, itemId, t.toString());
        }
        return Optional.empty();
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
            // INTENTIONAL — AI reply text is model-generated prose, not a translation key.
            // Component.literal here per i18n audit carve-out (PATTERNS.md §RagItemPipeline).
            @Override public void sendSuccess(String text) {
                src.sendSuccess(() -> Component.literal(text), false);
            }
            // INTENTIONAL — default-path sendFailure(String) remains for callers that still
            // pass prose (none in Phase 5 production; retained for backwards compatibility
            // with any lingering test fakes).
            @Override public void sendFailure(String text) {
                src.sendFailure(Component.literal(text));
            }
            @Override public void sendFailureKey(String key, Object... args) {
                src.sendFailure(Component.translatable(key, args));
            }
            @Override public void sendFailureComponent(Component feedback) {
                src.sendFailure(feedback);
            }
        };
    }

    // ------------------------------------------------------------------
    // Test seams (all interfaces below are package-private by design —
    // only the in-package test class {@code RagItemPipelineTest} may depend on them)
    // ------------------------------------------------------------------

    /**
     * Abstraction over {@link CommandSourceStack#sendSuccess}/{@link CommandSourceStack#sendFailure}.
     *
     * <h3>Phase 5 (REL-02) additions</h3>
     * <ul>
     *   <li>{@link #sendFailureKey(String, Object...)} — emits a translatable failure
     *       with optional positional args. Production wraps with
     *       {@link Component#translatable(String, Object...)}. Default impl routes to
     *       {@link #sendFailure(String)} with the key itself, so pure-Java test
     *       {@link Feedback} stubs keep working without overrides (key ends up in
     *       {@code lastFailure} for key-based assertions).</li>
     *   <li>{@link #sendFailureComponent(Component)} — passes an already-constructed
     *       Component (for {@code Denied.feedback()} / {@code AiDispatcher.Error.feedback()}
     *       pass-through). Default impl stringifies via {@link Component#getString()}
     *       for test convenience.</li>
     * </ul>
     */
    interface Feedback {
        void sendSuccess(String text);
        void sendFailure(String text);

        /**
         * Phase 5 — emit a translatable failure with optional args.
         * Production overrides this to use Component.translatable(key, args).
         * Default impl falls back to sendFailure(key) for tests — the KEY ends up in
         * the test sink, which is the expected assertion shape per PATTERNS.md.
         */
        default void sendFailureKey(String key, Object... args) {
            sendFailure(key);
        }

        /**
         * Phase 5 — emit an already-constructed Component (for Denied.feedback /
         * AiDispatcher.Error.feedback pass-through). Default impl stringifies for tests.
         */
        default void sendFailureComponent(Component feedback) {
            sendFailure(feedback.getString());
        }
    }

    /** Test seam mirroring {@link SafeHttpFetcher#fetch(URI)} so tests avoid live network. */
    @FunctionalInterface
    interface FetchFn {
        SafeHttpFetcher.Result fetch(URI uri) throws UnsafeUrlException, IOException;
    }

    /**
     * Test seam mirroring {@link CurseForgeClient#fetchDescriptionBySlug(String, ConfigSnapshot)}
     * so tests can inject canned HTML (or an empty Optional to verify fallthrough) without
     * hitting api.curseforge.com.
     */
    @FunctionalInterface
    interface CfDescFn {
        Optional<String> fetch(String slug, ConfigSnapshot snap);
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
