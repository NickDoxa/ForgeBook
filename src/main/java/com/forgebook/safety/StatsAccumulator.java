package com.forgebook.safety;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;

/**
 * CMD-06: per-UUID + aggregate counters rendered by /forgebook stats.
 *
 * Fed exclusively by {@link RequestAuditLogger} (the three {@code log*} entry points
 * fan out to the three {@code record*} methods here — single source of truth for
 * the request lifecycle).
 *
 * Counter primitive: {@link LongAdder} (partitioned across cells — lower
 * contention than {@link java.util.concurrent.atomic.AtomicLong} on hot writers).
 * Lazy-create per-player entries via {@code computeIfAbsent} so the first record
 * for a UUID never loses (RESEARCH §Pattern 8).
 *
 * In-memory only. Stats reset on server restart (deliberate v1 simplification
 * per RESEARCH §Open Question 2 — persistence deferred).
 *
 * Render cap: top-10 players by request count (RESEARCH §Pitfall 8 — stays well
 * under the 32 KB vanilla chat packet limit even with thousands of unique UUIDs).
 *
 * Thread-safety: all writes go through LongAdder / ConcurrentHashMap.computeIfAbsent;
 * render() reads are best-effort snapshots (sum() on LongAdder is not atomic across
 * counters, but SAFE-04 has no consistency requirement — the /stats rendering is
 * informational).
 */
public final class StatsAccumulator {

    /** Per-player counter bundle. Fields final; values mutated via LongAdder. */
    public record PerPlayer(LongAdder requests, LongAdder inputTokens,
                             LongAdder outputTokens, LongAdder latencySumMs) {
        public PerPlayer() {
            this(new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder());
        }
    }

    private static final ConcurrentHashMap<UUID, PerPlayer> PER_PLAYER = new ConcurrentHashMap<>();
    private static final LongAdder TOTAL_REQUESTS   = new LongAdder();
    private static final LongAdder TOTAL_DENIED     = new LongAdder();
    private static final LongAdder TOTAL_INPUT_TOK  = new LongAdder();
    private static final LongAdder TOTAL_OUTPUT_TOK = new LongAdder();
    private static final LongAdder TOTAL_LATENCY_MS = new LongAdder();

    private StatsAccumulator() {}

    public static void recordSuccess(UUID uuid, int inTok, int outTok, long latencyMs) {
        PerPlayer p = PER_PLAYER.computeIfAbsent(uuid, k -> new PerPlayer());
        p.requests.increment();
        p.inputTokens.add(inTok);
        p.outputTokens.add(outTok);
        p.latencySumMs.add(latencyMs);
        TOTAL_REQUESTS.increment();
        TOTAL_INPUT_TOK.add(inTok);
        TOTAL_OUTPUT_TOK.add(outTok);
        TOTAL_LATENCY_MS.add(latencyMs);
    }

    /**
     * A request that was authorized (reached the provider) but failed — counts as
     * an initiated request (SAFE-02 semantics). No token / latency attribution
     * because the provider never returned a usable response.
     */
    public static void recordFailure(UUID uuid, ErrorCode code) {
        PerPlayer p = PER_PLAYER.computeIfAbsent(uuid, k -> new PerPlayer());
        p.requests.increment();
        TOTAL_REQUESTS.increment();
    }

    /**
     * A request rejected by Authorizer (DISABLED / FORBIDDEN / RATE_LIMITED).
     * Does NOT count as initiated (per SAFE-02 — "counts initiated requests") —
     * the kill-switch / OP-gate / rate-limit fires BEFORE initiation.
     */
    public static void recordDenied(UUID uuid, ErrorCode code) {
        TOTAL_DENIED.increment();
    }

    /**
     * Multi-line summary rendered by /forgebook stats.
     * Aggregate totals + top-10 per-player rows sorted by request count desc.
     * Output stays under 32 KB — safe for single chat packet (Pitfall 8).
     */
    public static String render() {
        StringBuilder sb = new StringBuilder(4_096);
        sb.append("ForgeBook stats (this server session):\n");
        sb.append("  total requests : ").append(TOTAL_REQUESTS.sum()).append('\n');
        sb.append("  total denied   : ").append(TOTAL_DENIED.sum()).append('\n');
        sb.append("  total in_tok   : ").append(TOTAL_INPUT_TOK.sum()).append('\n');
        sb.append("  total out_tok  : ").append(TOTAL_OUTPUT_TOK.sum()).append('\n');
        long totalReq = TOTAL_REQUESTS.sum();
        if (totalReq > 0) {
            sb.append("  mean latency_ms: ").append(TOTAL_LATENCY_MS.sum() / totalReq).append('\n');
        }
        sb.append("  per-player (top 10 by request count):\n");
        PER_PLAYER.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().requests.sum(),
                                           a.getValue().requests.sum()))
            .limit(10)
            .forEach(e -> sb.append("    ").append(e.getKey()).append(": req=")
                .append(e.getValue().requests.sum())
                .append(" tok=").append(e.getValue().inputTokens.sum()
                                      + e.getValue().outputTokens.sum())
                .append('\n'));
        return sb.toString();
    }

    /** @VisibleForTesting Reset all counters. Tests call in @BeforeEach. */
    public static void resetForTests() {
        PER_PLAYER.clear();
        TOTAL_REQUESTS.reset();
        TOTAL_DENIED.reset();
        TOTAL_INPUT_TOK.reset();
        TOTAL_OUTPUT_TOK.reset();
        TOTAL_LATENCY_MS.reset();
    }
}
