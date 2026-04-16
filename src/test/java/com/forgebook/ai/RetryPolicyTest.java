package com.forgebook.ai;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void test1_retryableStatuses() {
        assertTrue(RetryPolicy.shouldRetry(429, false), "429 must be retryable");
        assertTrue(RetryPolicy.shouldRetry(500, false), "500 must be retryable");
        assertTrue(RetryPolicy.shouldRetry(502, false), "502 must be retryable");
        assertTrue(RetryPolicy.shouldRetry(503, false), "503 must be retryable");
        assertTrue(RetryPolicy.shouldRetry(504, false), "504 must be retryable");
        assertTrue(RetryPolicy.shouldRetry(529, false), "529 must be retryable");
    }

    @Test
    void test2_nonRetryableStatuses() {
        assertFalse(RetryPolicy.shouldRetry(400, false), "400 must not be retryable");
        assertFalse(RetryPolicy.shouldRetry(401, false), "401 must not be retryable");
        assertFalse(RetryPolicy.shouldRetry(402, false), "402 must not be retryable");
        assertFalse(RetryPolicy.shouldRetry(403, false), "403 must not be retryable");
        assertFalse(RetryPolicy.shouldRetry(404, false), "404 must not be retryable");
        assertFalse(RetryPolicy.shouldRetry(413, false), "413 must not be retryable");
    }

    @Test
    void test3_ioExceptionAlwaysRetries() {
        assertTrue(RetryPolicy.shouldRetry(0, true),
            "IOException flag must always result in retry");
    }

    @Test
    void test4_delayForAttemptZeroIsWithinJitterBounds() {
        RetryPolicy policy = RetryPolicy.DEFAULT;
        // baseDelay=1s, jitter=0.25 → range [750ms, 1250ms]
        long lower = (long) (policy.baseDelay().toMillis() * (1 - policy.jitter()));
        long upper = (long) (policy.baseDelay().toMillis() * (1 + policy.jitter()));

        for (int i = 0; i < 50; i++) {
            long ms = policy.delay(0, Optional.empty()).toMillis();
            assertTrue(ms >= lower && ms <= upper,
                "Attempt 0 delay " + ms + " must be in [" + lower + ", " + upper + "]");
        }
    }

    @Test
    void test5_delayIsCappedAtMaxDelay() {
        RetryPolicy policy = RetryPolicy.DEFAULT;
        // attempt=10 → 2^10 * baseDelay far exceeds maxDelay
        // With ±25% jitter the upper bound is maxDelay * 1.25 — but the cap is applied before jitter
        // The plan says "never exceeds 30s even before jitter"; our impl caps before adding jitter
        for (int i = 0; i < 20; i++) {
            long ms = policy.delay(10, Optional.empty()).toMillis();
            assertTrue(ms <= policy.maxDelay().toMillis() * 1.25 + 1,
                "Delay must not exceed maxDelay * (1+jitter), got " + ms);
        }
    }

    @Test
    void test6_retryAfterOverridesExponentialBackoff() {
        RetryPolicy policy = RetryPolicy.DEFAULT;
        Duration retryAfter = Duration.ofSeconds(15);
        Duration result = policy.delay(0, Optional.of(retryAfter));
        assertEquals(15_000L, result.toMillis(),
            "When retry-after is present, delay must be min(retryAfter, maxDelay)");
    }
}
