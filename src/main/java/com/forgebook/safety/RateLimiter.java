package com.forgebook.safety;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAFE-02 per-UUID token bucket. Counts INITIATED requests.
 *
 * Capacity and refill driven from ConfigSnapshot.rateLimitPerMinute:
 *   capacity     = 1                               // burst cap, always 1 (see below)
 *   refillPerSec = max(1, requestsPerMinute) / 60  // sustained throughput
 *
 * Burst is capped at 1 regardless of rpm. Reason: a single player bursting the
 * full rpm (e.g. 30) in one second can blow Anthropic's 10k input-tokens/min
 * org-level limit — each ForgeBook request spends ~2-3k tokens on the system
 * prompt + conversation context. Capping burst at 1 forces spacing so sustained
 * throughput stays on the sustainable refill curve (one token every 60/rpm
 * seconds). Sustained throughput over a full minute remains rpm.
 *
 * Buckets created lazily via ConcurrentHashMap.computeIfAbsent — first request
 * for a UUID always Allowed (bucket starts at capacity=1).
 *
 * On /forgebook reload (Plan 06), RateLimiterHolder.swap(new RateLimiter(rpm))
 * replaces the whole instance — simplest correct reload semantics. The benign
 * swap race (Pitfall 6) allows at most one in-flight request per reload event
 * to skip the new limit; acceptable v1.
 *
 * Stale-bucket cleanup is a TODO if operators report memory growth on very
 * long-running servers (see RESEARCH §Pattern 4 "Stale-bucket cleanup").
 */
public final class RateLimiter {

    /** Fixed burst cap. See class javadoc. */
    private static final int BURST_CAPACITY = 1;

    private final int capacity;
    private final double refillPerSec;
    private final ConcurrentHashMap<UUID, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int requestsPerMinute) {
        int rpm = Math.max(1, requestsPerMinute);
        this.capacity = BURST_CAPACITY;
        this.refillPerSec = rpm / 60.0;
    }

    public Outcome tryAcquire(UUID uuid) {
        TokenBucket b = buckets.computeIfAbsent(uuid, k -> new TokenBucket(capacity));
        return b.tryAcquire(capacity, refillPerSec);
    }

    /** Result type. Sealed to force exhaustive handling in Authorizer. */
    public sealed interface Outcome permits Allowed, Limited {}

    /** Token consumed; caller proceeds. */
    public record Allowed() implements Outcome {}

    /**
     * Bucket empty. retryAfterSeconds is always >= 1 (SAFE-03: human-readable
     * "try again in Ns" message must cite a truthy count).
     */
    public record Limited(long retryAfterSeconds) implements Outcome {}
}
