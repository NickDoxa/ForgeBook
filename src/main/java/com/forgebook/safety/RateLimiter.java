package com.forgebook.safety;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAFE-02 per-UUID token bucket. Counts INITIATED requests.
 *
 * Capacity and refill driven from ConfigSnapshot.rateLimitPerMinute:
 *   capacity     = max(1, requestsPerMinute)       // 0 and negative coerced to 1
 *   refillPerSec = capacity / 60.0
 *
 * Buckets created lazily via ConcurrentHashMap.computeIfAbsent — first request
 * for a UUID always Allowed (bucket starts at capacity).
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

    private final int capacity;
    private final double refillPerSec;
    private final ConcurrentHashMap<UUID, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int requestsPerMinute) {
        this.capacity = Math.max(1, requestsPerMinute);
        this.refillPerSec = this.capacity / 60.0;
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
