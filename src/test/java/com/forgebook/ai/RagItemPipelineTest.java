package com.forgebook.ai;

import com.forgebook.ai.dto.Usage;
import com.forgebook.config.AiProviderKind;
import com.forgebook.config.ApiKey;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.config.WebSearchProviderKind;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.safety.Authorizer;
import com.forgebook.safety.StatsAccumulator;
import com.forgebook.util.SafeHttpFetcher;
import com.forgebook.util.UnsafeUrlException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seven-branch coverage for {@link RagItemPipeline#runInternal} (T-03-04 plan).
 *
 * <h2>Why not Mockito static/construction mocks?</h2>
 * The plan's {@code <action>} text sketched a {@code MockedStatic}/{@code MockedConstruction}
 * harness. RagItemPipeline ships with a cleaner package-private seam
 * ({@code runInternal} takes {@code Feedback}, {@link RagItemPipeline.AuthFn},
 * {@link RagItemPipeline.FetchFn}, and a {@code Function<ConfigSnapshot, AiProvider>})
 * — same pattern the Phase 2 {@code Authorizer} primitive overload used, and consistent
 * with CLAUDE.md "avoid mocking Minecraft classes". These tests drive {@code runInternal}
 * directly with pure-Java fakes and never touch {@link net.minecraft.server.level.ServerPlayer}
 * or {@link net.minecraft.commands.CommandSourceStack}.
 *
 * <h2>StatsAccumulator as the audit oracle</h2>
 * {@link StatsAccumulator} exposes {@link StatsAccumulator#render()} + {@code resetForTests()},
 * so verifying that {@code RequestAuditLogger.logSuccess/logFailure/logDenied} fired exactly once
 * is a state check, not a verify-mock. This keeps RequestAuditLogger as a concrete static
 * helper (SAFE-04) while still giving tests a crisp oracle.
 */
class RagItemPipelineTest {

    private static final URL CREATE_URL;
    static {
        try {
            CREATE_URL = new URL("https://create.fandom.com");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final ConfigSnapshot SNAP = new ConfigSnapshot(
        AiProviderKind.ANTHROPIC,
        new ApiKey("k"),
        "claude-haiku-4-5-20251001",
        2048,
        Optional.empty(),
        new ApiKey(""),
        false,  // opOnly=false so no OP check fires in production (tests bypass Authorizer anyway)
        10,
        false,
        WebSearchProviderKind.DUCKDUCKGO,
        new ApiKey(""),
        1
    );

    private RecordingFeedback feedback;
    private UUID uuid;

    @BeforeEach
    void setup() {
        feedback = new RecordingFeedback();
        uuid = UUID.randomUUID();
        SystemPromptCache.set("SYSTEM_PROMPT_MARKER");
        StatsAccumulator.resetForTests();
        // Clear the RagItemPipeline docs cache so each test observes a fresh
        // fetch codepath. Without this, a prior test's successful URL fetch
        // is memoised and the current test's fetch stub is never invoked —
        // masking failures the error-path tests expect to observe.
        RagItemPipeline.clearDocsCacheForTests();
    }

    @AfterEach
    void teardown() {
        StatsAccumulator.resetForTests();
    }

    // ================================================================================
    // Test 1: Authorizer denial stops before fetch / provider call.
    // ================================================================================
    @Test
    void auth_denied_stops_before_fetch() {
        AtomicInteger fetchCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();

        RagItemPipeline.AuthFn deny = snap ->
            new Authorizer.Denied(ErrorCode.DISABLED,
                "ForgeBook is temporarily disabled by an operator.",
                net.minecraft.network.chat.Component.translatable("forgebook.command.denied.disabled"));

        RagItemPipeline.FetchFn fetch = uri -> {
            fetchCalls.incrementAndGet();
            return new SafeHttpFetcher.Result("ignored", "text/html", uri);
        };

        RagItemPipeline.runInternal(
            feedback, uuid, "create", "create:cogwheel",
            Optional.of(CREATE_URL), RequestKind.ITEM,
            SNAP, deny, fetch, NO_CF,
            snap -> { providerCalls.incrementAndGet(); return scripted(); });

        assertEquals(0, fetchCalls.get(), "fetch must not be invoked when auth denies");
        assertEquals(0, providerCalls.get(), "provider must not be constructed when auth denies");
        assertNull(feedback.lastSuccess, "no success reply on denial");
        assertNotNull(feedback.lastFailure, "denial must surface via sendFailure");
        // Phase 5 / REL-02: Denied.feedback carries Component.translatable. The test's
        // RecordingFeedback uses the default sendFailureComponent impl which routes through
        // Component.getString() → the translation KEY verbatim (no language pack loaded).
        assertEquals("forgebook.command.denied.disabled", feedback.lastFailure);

        // StatsAccumulator.render() contains the aggregate line "total denied : <n>".
        String stats = StatsAccumulator.render();
        assertTrue(stats.contains("total denied   : 1"),
            "logDenied must record exactly one denial: " + stats);
        assertTrue(stats.contains("total requests : 0"),
            "denied requests must NOT count as initiated: " + stats);
    }

    // ================================================================================
    // Test 2: Empty modURL → PROVIDER failure, no fetch.
    // ================================================================================
    @Test
    void empty_mod_url_returns_provider_failure_without_fetch() {
        AtomicInteger fetchCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();

        RagItemPipeline.FetchFn fetch = uri -> {
            fetchCalls.incrementAndGet();
            return new SafeHttpFetcher.Result("ignored", "text/html", uri);
        };

        RagItemPipeline.runInternal(
            feedback, uuid, "create", "create:cogwheel",
            Optional.<URL>empty(), RequestKind.ITEM,
            SNAP, allow(), fetch, NO_CF,
            snap -> { providerCalls.incrementAndGet(); return scripted(); });

        assertEquals(0, fetchCalls.get(), "fetch must not be invoked when modURL is empty");
        assertEquals(0, providerCalls.get(), "provider must not be invoked when modURL is empty");
        assertNotNull(feedback.lastFailure, "empty modURL must surface via sendFailure");
        // Phase 5 / REL-02: sendFailureKey forwards the translation KEY (args dropped by default impl).
        // The modId arg ("create") is wired into the en_us.json "%s" format at render time in production.
        assertEquals("forgebook.command.item.no_docs_url", feedback.lastFailure);
        assertEquals("create", feedback.lastFailureArgs.length > 0 ? feedback.lastFailureArgs[0] : null,
            "modId must be passed as the first arg to Component.translatable");

        String stats = StatsAccumulator.render();
        assertTrue(stats.contains("total requests : 1"),
            "empty-modURL counts as an initiated-but-failed request: " + stats);
    }

    // ================================================================================
    // Test 3: UnsafeUrlException → TRANSPORT failure, provider never called.
    // ================================================================================
    @Test
    void unsafe_url_exception_becomes_transport_failure() {
        AtomicInteger providerCalls = new AtomicInteger();

        RagItemPipeline.FetchFn fetch = uri -> { throw new UnsafeUrlException(UnsafeUrlException.Reason.PRIVATE_IP); };

        RagItemPipeline.runInternal(
            feedback, uuid, "create", "create:cogwheel",
            Optional.of(CREATE_URL), RequestKind.ITEM,
            SNAP, allow(), fetch, NO_CF,
            snap -> { providerCalls.incrementAndGet(); return scripted(); });

        assertEquals(0, providerCalls.get(), "provider must not be invoked on unsafe URL");
        // Phase 5 / REL-02: sendFailureKey forwards the translation KEY to the test sink.
        assertEquals("forgebook.command.item.fetch_failed", feedback.lastFailure);
        assertNull(feedback.lastSuccess);

        String stats = StatsAccumulator.render();
        assertTrue(stats.contains("total requests : 1"),
            "fetch failure counts as an initiated-but-failed request: " + stats);
    }

    // ================================================================================
    // Test 4: IOException → TRANSPORT failure.
    // ================================================================================
    @Test
    void io_exception_becomes_transport_failure() {
        AtomicInteger providerCalls = new AtomicInteger();

        RagItemPipeline.FetchFn fetch = uri -> { throw new IOException("connect timed out"); };

        RagItemPipeline.runInternal(
            feedback, uuid, "create", "create:cogwheel",
            Optional.of(CREATE_URL), RequestKind.ITEM,
            SNAP, allow(), fetch, NO_CF,
            snap -> { providerCalls.incrementAndGet(); return scripted(); });

        assertEquals(0, providerCalls.get(), "provider must not be invoked on IOException");
        // Phase 5 / REL-02: sendFailureKey forwards the translation KEY to the test sink.
        assertEquals("forgebook.command.item.fetch_failed", feedback.lastFailure);
    }

    // ================================================================================
    // Test 5: Provider error → mapped human-readable message to user.
    // ================================================================================
    @Test
    void provider_error_returns_mapped_error_to_user() {
        RagItemPipeline.FetchFn fetch = uri ->
            new SafeHttpFetcher.Result(
                "<html><body><article>Cogwheel transmits rotation.</article></body></html>",
                "text/html",
                uri);

        Queue<AiTurn> q = new LinkedList<>();
        q.add(new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.TRANSPORT, "upstream 503", Optional.empty()));
        ScriptedAiProvider provider = new ScriptedAiProvider(q);

        RagItemPipeline.runInternal(
            feedback, uuid, "create", "create:cogwheel",
            Optional.of(CREATE_URL), RequestKind.ITEM,
            SNAP, allow(), fetch, NO_CF, snap -> provider);

        assertEquals(1, provider.callCount(), "provider must be called exactly once");
        assertTrue(provider.lastRequest().tools().isEmpty(),
            "Pattern 3 invariant: tools[] must be empty in ChatRequest");
        AiDispatcher.Error expected = AiDispatcher.mapError(
            new AiTurn.ProviderError(
                AiTurn.ProviderError.Kind.TRANSPORT, "upstream 503", Optional.empty()));
        // Phase 5 / REL-02: sendFailureComponent default impl stringifies via
        // Component.getString() → the translation key verbatim (no lang pack).
        assertEquals(expected.feedback().getString(), feedback.lastFailure,
            "failure text must equal AiDispatcher.mapError(...).feedback().getString()");
        assertEquals("forgebook.command.provider.transport", feedback.lastFailure);
        assertEquals(ErrorCode.TRANSPORT, expected.code());

        String stats = StatsAccumulator.render();
        assertTrue(stats.contains("total requests : 1"),
            "provider error counts as an initiated-but-failed request: " + stats);
    }

    // ================================================================================
    // Test 6: Happy path — reply ends with "\n\nSource: <url>" (CMD-07).
    // ================================================================================
    @Test
    void happy_path_final_reply_appends_source_citation() {
        RagItemPipeline.FetchFn fetch = uri ->
            new SafeHttpFetcher.Result(
                "<html><body><article>Cogwheel transmits rotation between stacked axes.</article></body></html>",
                "text/html",
                uri);

        Queue<AiTurn> q = new LinkedList<>();
        q.add(new AiTurn.FinalReply("Use it in a gearbox.", false,
            Optional.of(usage(123, 45))));
        ScriptedAiProvider provider = new ScriptedAiProvider(q);

        RagItemPipeline.runInternal(
            feedback, uuid, "create", "create:cogwheel",
            Optional.of(CREATE_URL), RequestKind.ITEM,
            SNAP, allow(), fetch, NO_CF, snap -> provider);

        assertNotNull(feedback.lastSuccess, "happy path must call sendSuccess");
        assertNull(feedback.lastFailure, "happy path must not call sendFailure");
        assertTrue(feedback.lastSuccess.endsWith("\n\nSource: https://create.fandom.com"),
            "reply must end with '\\n\\nSource: <modURL>' (CMD-07): " + feedback.lastSuccess);
        assertTrue(feedback.lastSuccess.startsWith("Use it in a gearbox."),
            "reply must preserve the provider's text: " + feedback.lastSuccess);
    }

    // ================================================================================
    // Test 7: Happy path — logSuccess fires exactly once, no failures/denials.
    // ================================================================================
    @Test
    void happy_path_audit_log_success_fires_exactly_once() {
        RagItemPipeline.FetchFn fetch = uri ->
            new SafeHttpFetcher.Result(
                "<html><body><article>Cogwheel transmits rotation between stacked axes.</article></body></html>",
                "text/html",
                uri);

        Queue<AiTurn> q = new LinkedList<>();
        q.add(new AiTurn.FinalReply("Use it in a gearbox.", false,
            Optional.of(usage(123, 45))));
        ScriptedAiProvider provider = new ScriptedAiProvider(q);

        RagItemPipeline.runInternal(
            feedback, uuid, "create", "create:cogwheel",
            Optional.of(CREATE_URL), RequestKind.ITEM,
            SNAP, allow(), fetch, NO_CF, snap -> provider);

        // StatsAccumulator reflects logSuccess → recordSuccess exactly once,
        // with the Usage-supplied token counts (123 input, 45 output).
        String stats = StatsAccumulator.render();
        assertTrue(stats.contains("total requests : 1"),
            "logSuccess must fire exactly once (total requests = 1): " + stats);
        assertTrue(stats.contains("total denied   : 0"),
            "happy path must not call logDenied: " + stats);
        assertTrue(stats.contains("total in_tok   : 123"),
            "input token attribution must come from Usage: " + stats);
        assertTrue(stats.contains("total out_tok  : 45"),
            "output token attribution must come from Usage: " + stats);
        assertTrue(stats.contains(uuid.toString()),
            "per-player row must be keyed by caller UUID: " + stats);

        // Provider is called exactly once (Pattern 3: no tool-use loop).
        assertEquals(1, provider.callCount());
        assertEquals(0, provider.lastRequest().tools().size(),
            "Pattern 3: tools[] must be empty so stop_reason cannot be tool_use");
    }

    // ================================================================================
    // Test 8: CurseForge URL + configured key → CF API used, SafeHttpFetcher NOT called.
    // ================================================================================
    @Test
    void curseforge_url_with_key_uses_cf_api_and_skips_http_fetch() throws Exception {
        URL cfUrl = new URL("https://www.curseforge.com/minecraft/mc-mods/simply-swords");
        ConfigSnapshot cfSnap = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC, new ApiKey("k"), "claude-haiku-4-5-20251001", 2048,
            Optional.empty(), new ApiKey("cf-key-set"),
            false, 10, false,
            WebSearchProviderKind.DUCKDUCKGO, new ApiKey(""), 1);

        AtomicInteger cfCalls = new AtomicInteger();
        AtomicInteger httpCalls = new AtomicInteger();
        RagItemPipeline.CfDescFn cfDesc = (slug, snap) -> {
            cfCalls.incrementAndGet();
            assertEquals("simply-swords", slug, "slug must be extracted from the CF URL path");
            return Optional.of("<html><body><article>Simply Swords adds new weapons.</article></body></html>");
        };
        RagItemPipeline.FetchFn fetch = uri -> {
            httpCalls.incrementAndGet();
            return new SafeHttpFetcher.Result("should-not-be-used", "text/html", uri);
        };

        Queue<AiTurn> q = new LinkedList<>();
        q.add(new AiTurn.FinalReply("Use them in combat.", false, Optional.of(usage(10, 5))));
        ScriptedAiProvider provider = new ScriptedAiProvider(q);

        RagItemPipeline.runInternal(
            feedback, uuid, "simplyswords", "simplyswords:katana",
            Optional.of(cfUrl), RequestKind.ITEM,
            cfSnap, allow(), fetch, cfDesc, snap -> provider);

        assertEquals(1, cfCalls.get(), "CurseForge API must be called exactly once");
        assertEquals(0, httpCalls.get(), "SafeHttpFetcher must NOT be called when CF API returns content");
        assertNotNull(feedback.lastSuccess, "happy path — success reply expected");
        assertTrue(feedback.lastSuccess.startsWith("Use them in combat."));
    }

    // ================================================================================
    // Test 9: CurseForge URL + configured key, CF API returns empty → falls through to SafeHttpFetcher.
    // ================================================================================
    @Test
    void curseforge_url_falls_through_to_http_when_cf_api_returns_empty() throws Exception {
        URL cfUrl = new URL("https://www.curseforge.com/minecraft/mc-mods/simply-swords");
        ConfigSnapshot cfSnap = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC, new ApiKey("k"), "claude-haiku-4-5-20251001", 2048,
            Optional.empty(), new ApiKey("cf-key-set"),
            false, 10, false,
            WebSearchProviderKind.DUCKDUCKGO, new ApiKey(""), 1);

        AtomicInteger cfCalls = new AtomicInteger();
        AtomicInteger httpCalls = new AtomicInteger();
        RagItemPipeline.CfDescFn cfDesc = (slug, snap) -> {
            cfCalls.incrementAndGet();
            return Optional.empty();  // simulate no hit / non-200 / missing key upstream
        };
        RagItemPipeline.FetchFn fetch = uri -> {
            httpCalls.incrementAndGet();
            return new SafeHttpFetcher.Result(
                "<html><body><article>Fallback HTML content.</article></body></html>",
                "text/html", uri);
        };

        Queue<AiTurn> q = new LinkedList<>();
        q.add(new AiTurn.FinalReply("Reply text.", false, Optional.of(usage(10, 5))));
        ScriptedAiProvider provider = new ScriptedAiProvider(q);

        RagItemPipeline.runInternal(
            feedback, uuid, "simplyswords", "simplyswords:katana",
            Optional.of(cfUrl), RequestKind.ITEM,
            cfSnap, allow(), fetch, cfDesc, snap -> provider);

        assertEquals(1, cfCalls.get(), "CF API must be attempted once");
        assertEquals(1, httpCalls.get(), "SafeHttpFetcher must be called as fallback");
        assertNotNull(feedback.lastSuccess);
    }

    // ================================================================================
    // Test 10: CurseForge URL WITHOUT configured key → CF API NOT called, straight to SafeHttpFetcher.
    // ================================================================================
    @Test
    void curseforge_url_without_key_skips_cf_api() throws Exception {
        URL cfUrl = new URL("https://www.curseforge.com/minecraft/mc-mods/simply-swords");
        // SNAP has blank curseforgeApiKey — should short-circuit CF API.
        AtomicInteger cfCalls = new AtomicInteger();
        AtomicInteger httpCalls = new AtomicInteger();
        RagItemPipeline.CfDescFn cfDesc = (slug, snap) -> {
            cfCalls.incrementAndGet();
            return Optional.of("<html>unused</html>");
        };
        RagItemPipeline.FetchFn fetch = uri -> {
            httpCalls.incrementAndGet();
            return new SafeHttpFetcher.Result(
                "<html><body><article>Direct fetch content.</article></body></html>",
                "text/html", uri);
        };

        Queue<AiTurn> q = new LinkedList<>();
        q.add(new AiTurn.FinalReply("r", false, Optional.of(usage(1, 1))));
        ScriptedAiProvider provider = new ScriptedAiProvider(q);

        RagItemPipeline.runInternal(
            feedback, uuid, "simplyswords", "simplyswords:katana",
            Optional.of(cfUrl), RequestKind.ITEM,
            SNAP, allow(), fetch, cfDesc, snap -> provider);

        assertEquals(0, cfCalls.get(), "CF API must NOT be called when api key is blank");
        assertEquals(1, httpCalls.get(), "SafeHttpFetcher must be the only fetch path");
    }

    // ================================================================================
    // Helpers
    // ================================================================================

    /**
     * Pure-Java Feedback stub that records what the pipeline sent.
     *
     * <p>Phase 5 / REL-02: overrides {@code sendFailureKey} explicitly so the args
     * array is captured alongside the key — the Feedback default impl would drop
     * args by routing to {@code sendFailure(key)}, which loses the parameterized
     * context needed for the no_docs_url modId assertion.
     */
    private static final class RecordingFeedback implements RagItemPipeline.Feedback {
        String lastSuccess;
        String lastFailure;
        Object[] lastFailureArgs = new Object[0];
        final List<String> allMessages = new ArrayList<>();

        @Override public void sendSuccess(String text) {
            lastSuccess = text;
            allMessages.add("SUCCESS: " + text);
        }
        @Override public void sendFailure(String text) {
            lastFailure = text;
            allMessages.add("FAILURE: " + text);
        }
        @Override public void sendFailureKey(String key, Object... args) {
            lastFailure = key;
            lastFailureArgs = args == null ? new Object[0] : args;
            allMessages.add("FAILURE_KEY: " + key + " args=" + java.util.Arrays.toString(args));
        }
        // sendFailureComponent uses the default impl (feedback.getString()) which returns
        // the translation key verbatim in the JUnit env — the assertion on lastFailure
        // (key string) works without an explicit override.
    }

    /** AuthFn that always allows. */
    private static RagItemPipeline.AuthFn allow() {
        return snap -> new Authorizer.Allowed();
    }

    /** CfDescFn that always reports "no CurseForge hit" — the default for non-CF tests. */
    private static final RagItemPipeline.CfDescFn NO_CF = (slug, snap) -> Optional.empty();

    /** Empty-provider factory for branches that should never call the provider. */
    private static AiProvider scripted() {
        Queue<AiTurn> q = new LinkedList<>();
        q.add(new AiTurn.FinalReply("should-not-be-reached", false, Optional.empty()));
        return new ScriptedAiProvider(q);
    }

    /** {@link Usage} has public mutable fields and no ctor — populate via setters. */
    private static Usage usage(int inTok, int outTok) {
        Usage u = new Usage();
        u.inputTokens = inTok;
        u.outputTokens = outTok;
        return u;
    }
}
