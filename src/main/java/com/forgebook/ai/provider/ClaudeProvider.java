package com.forgebook.ai.provider;

import com.forgebook.ai.AiProvider;
import com.forgebook.ai.AiTurn;
import com.forgebook.ai.ChatRequest;
import com.forgebook.ai.CircuitBreaker;
import com.forgebook.ai.RetryPolicy;
import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.ai.dto.ClaudeRequest;
import com.forgebook.ai.dto.ClaudeResponse;
import com.forgebook.ai.dto.ContentBlock;
import com.forgebook.ai.dto.ToolDef;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * AI-02, AI-06, AI-07 implementation.
 *
 * Wire contract (RESEARCH §1):
 *   POST https://api.anthropic.com/v1/messages
 *   Headers: x-api-key, anthropic-version, content-type: application/json, accept: application/json
 *   Body:    ClaudeRequest serialized via Gson
 *
 * Retry: RetryPolicy.DEFAULT (3 retries, 1s-2s-4s exp backoff, 30s cap, +-25% jitter).
 * Breaker: shared CircuitBreaker (5-failure / 5-min cool-off).
 *
 * NOT via SafeHttpFetcher -- SafeHttpFetcher's content-type allowlist excludes JSON
 * (RESEARCH "Existing-Code Findings" gap note). api.anthropic.com is a trusted,
 * fixed endpoint; raw HttpClient is correct here.
 */
public final class ClaudeProvider implements AiProvider {
    private static final Logger LOG = LogManager.getLogger();

    // Pinned constants (D-07, RESEARCH §1.1).
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Gson gson = new Gson();
    private final HttpExecutor http;
    private final CircuitBreaker breaker;
    private final RetryPolicy retry;

    public ClaudeProvider() {
        this(HttpExecutor.production(), new CircuitBreaker(), RetryPolicy.DEFAULT);
    }

    /** Package-private test seam. */
    ClaudeProvider(HttpExecutor http, CircuitBreaker breaker, RetryPolicy retry) {
        this.http = http;
        this.breaker = breaker;
        this.retry = retry;
    }

    @Override
    public CompletableFuture<AiTurn> chat(ChatRequest req) {
        if (breaker.isOpen()) {
            return CompletableFuture.completedFuture(new AiTurn.ProviderError(
                AiTurn.ProviderError.Kind.CIRCUIT_OPEN,
                "Circuit breaker is open (5 consecutive provider failures). Try again later.",
                Optional.empty()));
        }
        return CompletableFuture.supplyAsync(() -> runWithRetry(req));
    }

    private AiTurn runWithRetry(ChatRequest req) {
        ConfigSnapshot snap = ConfigHolder.get();
        String body = gson.toJson(toWireRequest(req));
        HttpRequest httpReq = HttpRequest.newBuilder(ENDPOINT)
            .header("x-api-key", snap.aiApiKey().raw())
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .header("accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        AiTurn lastError = null;
        for (int attempt = 0; attempt <= retry.maxAttempts(); attempt++) {
            try {
                HttpResponse<String> resp = http.send(httpReq);
                int status = resp.statusCode();

                if (status == 200) {
                    breaker.recordSuccess();
                    return parseResponse(resp.body());
                }

                Optional<Duration> retryAfter = parseRetryAfter(resp);
                AiTurn err = translateError(status, resp.body(), retryAfter);
                lastError = err;

                if (!RetryPolicy.shouldRetry(status, false) || attempt == retry.maxAttempts()) {
                    if (status >= 500 || status == 429 || status == 529) breaker.recordFailure();
                    return err;
                }
                Duration d = retry.delay(attempt, retryAfter);
                LOG.info("Anthropic {} attempt {}/{} — backing off {} ms",
                    status, attempt + 1, retry.maxAttempts() + 1, d.toMillis());
                if (d.toMillis() > 0) Thread.sleep(d.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new AiTurn.ProviderError(AiTurn.ProviderError.Kind.TRANSPORT,
                    "interrupted", Optional.empty());
            } catch (Exception e) {
                lastError = new AiTurn.ProviderError(AiTurn.ProviderError.Kind.TRANSPORT,
                    "io: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    Optional.empty());
                if (attempt == retry.maxAttempts()) {
                    breaker.recordFailure();
                    return lastError;
                }
                try {
                    Duration d = retry.delay(attempt, Optional.empty());
                    if (d.toMillis() > 0) Thread.sleep(d.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return lastError;
                }
            }
        }
        breaker.recordFailure();
        return lastError;
    }

    private AiTurn translateError(int status, String body, Optional<Duration> retryAfter) {
        if (status == 429) return new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.RATE_LIMITED, body, retryAfter);
        if (status == 529) return new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.OVERLOADED, body, Optional.empty());
        if (status >= 500) return new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.TRANSPORT, body, Optional.empty());
        return new AiTurn.ProviderError(AiTurn.ProviderError.Kind.PROVIDER, body, Optional.empty());
    }

    private static Optional<Duration> parseRetryAfter(HttpResponse<String> resp) {
        return resp.headers().firstValue("retry-after").map(v -> {
            try { return Duration.ofSeconds(Long.parseLong(v.trim())); }
            catch (NumberFormatException nfe) { return null; }
        });
    }

    AiTurn parseResponse(String body) {
        ClaudeResponse r = gson.fromJson(body, ClaudeResponse.class);
        String stop = r.stopReason();
        switch (stop == null ? "end_turn" : stop) {
            case "end_turn":
            case "stop_sequence": {
                String text = extractText(r);
                return new AiTurn.FinalReply(text, false, Optional.ofNullable(r.usage));
            }
            case "max_tokens": {
                String text = extractText(r);
                return new AiTurn.FinalReply(text, true, Optional.ofNullable(r.usage));
            }
            case "tool_use": {
                List<AiTurn.ToolUseBlock> uses = new ArrayList<>();
                for (ContentBlock b : r.content()) {
                    if ("tool_use".equals(b.type)) {
                        Map<String, Object> inputMap = b.input == null ? new HashMap<>()
                            : gson.fromJson(b.input, Map.class);
                        uses.add(new AiTurn.ToolUseBlock(b.id, b.name, inputMap));
                    }
                }
                return new AiTurn.ToolUses(uses);
            }
            default:
                return new AiTurn.ProviderError(AiTurn.ProviderError.Kind.PROVIDER,
                    "Unknown stop_reason: " + stop, Optional.empty());
        }
    }

    private static String extractText(ClaudeResponse r) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : r.content()) {
            if ("text".equals(b.type) && b.text != null) sb.append(b.text);
        }
        return sb.toString();
    }

    private ClaudeRequest toWireRequest(ChatRequest req) {
        List<ToolDef> tools = (req.tools() == null || req.tools().isEmpty()) ? null : req.tools();
        return new ClaudeRequest(req.model(), req.maxTokens(), req.system(), req.messages(), tools);
    }
}
