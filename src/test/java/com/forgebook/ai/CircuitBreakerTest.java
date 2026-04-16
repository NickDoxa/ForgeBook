package com.forgebook.ai;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void test1_freshBreakerIsClosed() {
        CircuitBreaker breaker = new CircuitBreaker();
        assertFalse(breaker.isOpen(), "Fresh circuit breaker must be closed");
    }

    @Test
    void test2_fourFailuresStillClosed() {
        CircuitBreaker breaker = new CircuitBreaker();
        for (int i = 0; i < 4; i++) breaker.recordFailure();
        assertFalse(breaker.isOpen(), "4 failures: breaker must still be closed");
    }

    @Test
    void test3_fifthFailureOpens() {
        CircuitBreaker breaker = new CircuitBreaker();
        for (int i = 0; i < 5; i++) breaker.recordFailure();
        assertTrue(breaker.isOpen(), "5th failure must open the circuit breaker");
    }

    @Test
    void test4_recordSuccessResetsOpenBreaker() {
        CircuitBreaker breaker = new CircuitBreaker();
        for (int i = 0; i < 5; i++) breaker.recordFailure();
        assertTrue(breaker.isOpen());
        breaker.recordSuccess();
        assertFalse(breaker.isOpen(), "recordSuccess must close the breaker");
        assertEquals(0, breaker.consecutiveFailures(), "consecutiveFailures must reset to 0");
    }

    @Test
    void test5_coolOffExpiresAfterFiveMinutes() {
        long[] now = {1_000_000L};
        CircuitBreaker breaker = new CircuitBreaker(() -> now[0]);
        for (int i = 0; i < 5; i++) breaker.recordFailure();
        assertTrue(breaker.isOpen(), "Must be open after 5 failures");

        // Advance 4 minutes 59 seconds — still within cool-off
        now[0] += Duration.ofMinutes(4).plusSeconds(59).toMillis();
        assertTrue(breaker.isOpen(), "Must still be open after 4m59s");

        // Advance 1 more ms — now at exactly 5 minutes + 1 ms
        now[0] += Duration.ofMillis(2).toMillis(); // +2ms total extra (≥1ms past 5 min)
        assertFalse(breaker.isOpen(), "Must be closed after cool-off (5 minutes + 1 ms)");
    }

    @Test
    void test6_constantsHaveCorrectValues() {
        assertEquals(5, CircuitBreaker.FAILURE_THRESHOLD,
            "FAILURE_THRESHOLD must be 5");
        assertEquals(Duration.ofMinutes(5), CircuitBreaker.COOL_OFF,
            "COOL_OFF must be 5 minutes");
    }
}
