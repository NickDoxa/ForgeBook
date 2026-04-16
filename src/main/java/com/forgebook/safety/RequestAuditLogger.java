package com.forgebook.safety;

import java.util.UUID;

import com.forgebook.ai.RequestKind;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SAFE-04: one structured log line per AI request. Fans out to
 * {@link StatsAccumulator} so /forgebook stats and the log stream
 * are the single source of truth for the request lifecycle.
 *
 * <h2>Named logger</h2>
 * Uses {@code LogManager.getLogger("forgebook.audit")} — NOT the default class-name
 * logger. Operators can route this logger separately in their log4j2.xml:
 * <pre>{@code
 *   <Logger name="forgebook.audit" level="INFO" additivity="false">
 *       <AppenderRef ref="AuditFile"/>
 *   </Logger>
 * }</pre>
 *
 * <h2>Payload constraint (SAFE-04 / Pitfall 5)</h2>
 * NEVER log user message content. Fields are restricted to metadata:
 * uuid, kind, in_tok, out_tok, latency_ms, outcome. Violations are enforced by
 * code review; defense-in-depth via {@link com.forgebook.util.log.ApiKeyScrubFilter}
 * (global Log4j2 appender wrap, Phase 1) scrubs any key-shaped substring.
 *
 * <h2>Call sites (Plans 03-06)</h2>
 * - {@code AiDispatcher.dispatch} — success and failure (FinalReply / ProviderError)
 * - {@code RagItemPipeline.run} — success, failure (fetch IOException), denied (auth)
 * - {@code ChatRequestHandler.handle} — denied (SAFE-06 precheck)
 * - {@code AskSubcommand} / {@code ItemSubcommand} — denied (auth precheck)
 */
public final class RequestAuditLogger {

    /** Dedicated named logger — ALL audit lines flow through this instance. */
    private static final Logger AUDIT = LogManager.getLogger("forgebook.audit");

    private RequestAuditLogger() {}

    /** Successful request: provider returned FinalReply. */
    public static void logSuccess(UUID uuid, RequestKind kind,
                                  int inputTokens, int outputTokens, long latencyMs) {
        AUDIT.info("uuid={} kind={} in_tok={} out_tok={} latency_ms={} outcome=OK",
            uuid, kind, inputTokens, outputTokens, latencyMs);
        StatsAccumulator.recordSuccess(uuid, inputTokens, outputTokens, latencyMs);
    }

    /**
     * Failed request: provider returned ProviderError, or transport layer threw.
     * inputTokens / outputTokens may be 0 if failure happened before any provider reply.
     */
    public static void logFailure(UUID uuid, RequestKind kind, ErrorCode code,
                                  int inputTokens, int outputTokens, long latencyMs) {
        AUDIT.info("uuid={} kind={} in_tok={} out_tok={} latency_ms={} outcome={}",
            uuid, kind, inputTokens, outputTokens, latencyMs, code);
        StatsAccumulator.recordFailure(uuid, code);
    }

    /**
     * Request denied by Authorizer (kill switch / OP gate / rate limit).
     * Latency measured from the caller's startNanos so operators can see how long
     * auth took (usually sub-ms; anomaly if not).
     */
    public static void logDenied(UUID uuid, RequestKind kind, ErrorCode code, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        AUDIT.info("uuid={} kind={} in_tok=0 out_tok=0 latency_ms={} outcome={}",
            uuid, kind, latencyMs, code);
        StatsAccumulator.recordDenied(uuid, code);
    }
}
