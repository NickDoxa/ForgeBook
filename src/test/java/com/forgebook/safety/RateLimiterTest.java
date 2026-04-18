package com.forgebook.safety;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void fiveRequestsPerMinute_allowsOneThenLimits_withBurstCap() {
        // 1.0.6: burst is capped at 1 regardless of rpm (Anthropic TPM mitigation).
        // First request is Allowed, second is Limited. Sustained throughput still
        // ~5/min via refill (1 token every 12s for rpm=5).
        RateLimiter rl = new RateLimiter(5);
        UUID alice = UUID.randomUUID();
        assertInstanceOf(RateLimiter.Allowed.class, rl.tryAcquire(alice),
            "first request should be Allowed");
        RateLimiter.Outcome second = rl.tryAcquire(alice);
        assertInstanceOf(RateLimiter.Limited.class, second);
        RateLimiter.Limited limited = (RateLimiter.Limited) second;
        assertTrue(limited.retryAfterSeconds() >= 1L,
            "retryAfterSeconds must be >= 1; got " + limited.retryAfterSeconds());
    }

    @Test
    void zeroRpmIsCoercedToOne_noDivideByZero() {
        RateLimiter rl = new RateLimiter(0);
        UUID uid = UUID.randomUUID();
        assertInstanceOf(RateLimiter.Allowed.class, rl.tryAcquire(uid));
        assertInstanceOf(RateLimiter.Limited.class, rl.tryAcquire(uid));
    }

    @Test
    void negativeRpmIsCoercedToOne_noException() {
        RateLimiter rl = new RateLimiter(-5);
        UUID uid = UUID.randomUUID();
        assertInstanceOf(RateLimiter.Allowed.class, rl.tryAcquire(uid));
    }

    @Test
    void separateUuidsHaveIndependentBuckets() {
        RateLimiter rl = new RateLimiter(1);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertInstanceOf(RateLimiter.Allowed.class, rl.tryAcquire(alice));
        // Alice exhausted, but Bob gets a fresh bucket.
        assertInstanceOf(RateLimiter.Allowed.class, rl.tryAcquire(bob));
        assertInstanceOf(RateLimiter.Limited.class, rl.tryAcquire(alice));
    }

    @Test
    void sealedOutcome_exhaustiveInstanceofHandling() {
        RateLimiter rl = new RateLimiter(1);
        UUID uid = UUID.randomUUID();
        RateLimiter.Outcome first = rl.tryAcquire(uid);
        RateLimiter.Outcome second = rl.tryAcquire(uid);
        // Compile-time check: sealed interface with two permits
        String label1 = (first instanceof RateLimiter.Allowed) ? "allowed"
            : (first instanceof RateLimiter.Limited l) ? ("limited:" + l.retryAfterSeconds())
            : fail("unexpected Outcome variant");
        String label2 = (second instanceof RateLimiter.Allowed) ? "allowed"
            : (second instanceof RateLimiter.Limited l) ? ("limited:" + l.retryAfterSeconds())
            : fail("unexpected Outcome variant");
        assertEquals("allowed", label1);
        assertTrue(label2.startsWith("limited:"));
    }
}
