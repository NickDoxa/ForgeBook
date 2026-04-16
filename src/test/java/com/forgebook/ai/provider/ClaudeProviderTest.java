package com.forgebook.ai.provider;

import com.forgebook.ai.AiTurn;
import com.forgebook.ai.ChatRequest;
import com.forgebook.ai.CircuitBreaker;
import com.forgebook.ai.RetryPolicy;
import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.config.AiProviderKind;
import com.forgebook.config.ApiKey;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.config.WebSearchProviderKind;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeProviderTest {

    // ---- Fixtures ----

    private static final ConfigSnapshot TEST_SNAP = new ConfigSnapshot(
        AiProviderKind.ANTHROPIC, new ApiKey("sk-ant-test"), "claude-haiku-4-5", 1024,
        Optional.empty(), new ApiKey(""), true, 5, false,
        WebSearchProviderKind.DUCKDUCKGO, new ApiKey(""), 1);

    private static final ChatRequest SIMPLE_REQ = new ChatRequest(
        "claude-haiku-4-5", 1024, "You are helpful.",
        List.of(ClaudeMessage.userText("Hello")), List.of());

    /** Zero-delay retry policy so tests don't sleep. */
    private static final RetryPolicy FAST = new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 0.0);

    @BeforeEach
    void seedConfig() {
        ConfigHolder.set(TEST_SNAP);
    }

    // ---- HttpResponse stub ----

    static HttpResponse<String> mockResponse(int status, String body) {
        return mockResponse(status, body, Map.of());
    }

    static HttpResponse<String> mockResponse(int status, String body, Map<String, String> extraHeaders) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public String body() { return body; }
            @Override public HttpHeaders headers() {
                return HttpHeaders.of(extraHeaders.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, e -> List.of(e.getValue()))),
                    (a, b) -> true);
            }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://api.anthropic.com/v1/messages"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    // ---- Recording executor ----

    static class RecordingExecutor implements HttpExecutor {
        final List<HttpRequest> calls = new ArrayList<>();
        final Iterator<HttpResponse<String>> responses;

        RecordingExecutor(List<HttpResponse<String>> resps) {
            this.responses = resps.iterator();
        }

        @Override
        public HttpResponse<String> send(HttpRequest req) {
            calls.add(req);
            return responses.next();
        }
    }

    private static String fixture(String name) {
        try (var in = ClaudeProviderTest.class.getClassLoader()
                .getResourceAsStream("forgebook/phase2/" + name)) {
            if (in == null) throw new RuntimeException("Fixture not found: " + name);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- Tests ----

    @Test
    void test1_happyPath_endTurn() {
        var exec = new RecordingExecutor(List.of(
            mockResponse(200, fixture("claude-end-turn.json"))));
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        AiTurn result = assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        assertInstanceOf(AiTurn.FinalReply.class, result);
        AiTurn.FinalReply reply = (AiTurn.FinalReply) result;
        assertEquals("hello", reply.text());
        assertFalse(reply.truncated());
        assertEquals(1, exec.calls.size());
    }

    @Test
    void test2_toolUsePath() {
        var exec = new RecordingExecutor(List.of(
            mockResponse(200, fixture("claude-tool-use.json"))));
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        AiTurn result = assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        assertInstanceOf(AiTurn.ToolUses.class, result);
        AiTurn.ToolUses toolUses = (AiTurn.ToolUses) result;
        assertEquals(1, toolUses.uses().size());
        assertEquals("toolu_01", toolUses.uses().get(0).id());
        assertEquals("fetch_mod_docs_page", toolUses.uses().get(0).name());
    }

    @Test
    void test3_maxTokensStop_truncatedTrue() {
        String body = "{\"id\":\"msg_3\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-4-5\"," +
            "\"content\":[{\"type\":\"text\",\"text\":\"truncated text\"}],\"stop_reason\":\"max_tokens\"," +
            "\"usage\":{\"input_tokens\":10,\"output_tokens\":1024}}";
        var exec = new RecordingExecutor(List.of(mockResponse(200, body)));
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        AiTurn result = assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        assertInstanceOf(AiTurn.FinalReply.class, result);
        assertTrue(((AiTurn.FinalReply) result).truncated());
    }

    @Test
    void test4_400NoRetry() {
        var exec = new RecordingExecutor(List.of(
            mockResponse(400, fixture("claude-4xx.json"))));
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        AiTurn result = assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        assertInstanceOf(AiTurn.ProviderError.class, result);
        assertEquals(AiTurn.ProviderError.Kind.PROVIDER, ((AiTurn.ProviderError) result).kind());
        assertEquals(1, exec.calls.size(), "400 must not be retried");
    }

    @Test
    void test5_500RetriedThreeTimes() {
        var responses = List.of(
            mockResponse(500, "error"),
            mockResponse(500, "error"),
            mockResponse(500, "error"),
            mockResponse(500, "error"));
        var exec = new RecordingExecutor(responses);
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        AiTurn result = assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        assertInstanceOf(AiTurn.ProviderError.class, result);
        assertEquals(AiTurn.ProviderError.Kind.TRANSPORT, ((AiTurn.ProviderError) result).kind());
        assertEquals(4, exec.calls.size(), "500 must be retried 3 times (4 total calls)");
    }

    @Test
    void test6_529ClassifiedAsOverloaded() {
        var responses = List.of(
            mockResponse(529, fixture("claude-529.json")),
            mockResponse(529, fixture("claude-529.json")),
            mockResponse(529, fixture("claude-529.json")),
            mockResponse(529, fixture("claude-529.json")));
        var exec = new RecordingExecutor(responses);
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        AiTurn result = assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        assertInstanceOf(AiTurn.ProviderError.class, result);
        assertEquals(AiTurn.ProviderError.Kind.OVERLOADED, ((AiTurn.ProviderError) result).kind());
    }

    @Test
    void test7_429WithRetryAfterThenSuccess() {
        var responses = List.of(
            mockResponse(429, "", Map.of("retry-after", "0")),
            mockResponse(200, fixture("claude-end-turn.json")));
        var exec = new RecordingExecutor(responses);
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        AiTurn result = assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        assertInstanceOf(AiTurn.FinalReply.class, result);
        assertEquals(2, exec.calls.size(), "Should retry once after 429 and succeed");
    }

    @Test
    void test8_headerPinning() {
        var exec = new RecordingExecutor(List.of(
            mockResponse(200, fixture("claude-end-turn.json"))));
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        HttpRequest req = exec.calls.get(0);
        assertEquals("sk-ant-test", req.headers().firstValue("x-api-key").orElse("MISSING"),
            "x-api-key must equal snap.aiApiKey().raw()");
        assertEquals("2023-06-01", req.headers().firstValue("anthropic-version").orElse("MISSING"),
            "anthropic-version must be pinned to 2023-06-01");
        assertEquals("application/json", req.headers().firstValue("content-type").orElse("MISSING"),
            "content-type must be application/json");
    }

    @Test
    void test9_bodyPinning() throws Exception {
        var exec = new RecordingExecutor(List.of(
            mockResponse(200, fixture("claude-end-turn.json"))));
        var provider = new ClaudeProvider(exec, new CircuitBreaker(), FAST);

        assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        HttpRequest req = exec.calls.get(0);
        String bodyStr = req.bodyPublisher()
            .map(pub -> {
                var sub = new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    @Override public void onNext(java.nio.ByteBuffer item) { byte[] b = new byte[item.remaining()]; item.get(b); baos.write(b, 0, b.length); }
                    @Override public void onError(Throwable t) { latch.countDown(); }
                    @Override public void onComplete() { latch.countDown(); }
                };
                pub.subscribe(sub);
                try { sub.latch.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                return sub.baos.toString(java.nio.charset.StandardCharsets.UTF_8);
            })
            .orElse("");

        assertTrue(bodyStr.contains("\"model\":\"claude-haiku-4-5\""), "Body must contain model field");
        assertTrue(bodyStr.contains("\"max_tokens\":1024"), "Body must contain max_tokens field");
        assertTrue(bodyStr.contains("\"system\":"), "Body must contain system field");
        assertTrue(bodyStr.contains("\"messages\":"), "Body must contain messages field");
    }

    @Test
    void test10_circuitBreakerOpenReturnsCircuitOpenError() {
        var exec = new RecordingExecutor(List.of());
        CircuitBreaker breaker = new CircuitBreaker();
        for (int i = 0; i < 5; i++) breaker.recordFailure(); // pre-trip
        var provider = new ClaudeProvider(exec, breaker, FAST);

        AiTurn result = assertTimeout(Duration.ofSeconds(5), () -> provider.chat(SIMPLE_REQ).join());

        assertInstanceOf(AiTurn.ProviderError.class, result);
        assertEquals(AiTurn.ProviderError.Kind.CIRCUIT_OPEN, ((AiTurn.ProviderError) result).kind());
        assertEquals(0, exec.calls.size(), "Pre-tripped breaker must make 0 HTTP calls");
    }

    @Test
    void test11_breakerRecordsSuccessAndFailure() {
        // Success path
        var exec1 = new RecordingExecutor(List.of(
            mockResponse(200, fixture("claude-end-turn.json"))));
        CircuitBreaker breaker1 = new CircuitBreaker();
        var provider1 = new ClaudeProvider(exec1, breaker1, FAST);
        assertTimeout(Duration.ofSeconds(5), () -> provider1.chat(SIMPLE_REQ).join());
        assertEquals(0, breaker1.consecutiveFailures(), "Success must reset consecutiveFailures to 0");

        // Failure path — 4 failures should increment counter
        var responses = new ArrayList<HttpResponse<String>>();
        for (int i = 0; i < 4; i++) responses.add(mockResponse(500, "err"));
        var exec2 = new RecordingExecutor(responses);
        CircuitBreaker breaker2 = new CircuitBreaker();
        var provider2 = new ClaudeProvider(exec2, breaker2, new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 0.0));
        assertTimeout(Duration.ofSeconds(5), () -> provider2.chat(SIMPLE_REQ).join());
        assertTrue(breaker2.consecutiveFailures() > 0, "Failures must increment consecutiveFailures");
    }
}
