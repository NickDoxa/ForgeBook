package com.forgebook.config;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ApiKeyTest {

    @Test void toString_returnsRedactedLiteral() {
        assertEquals("<redacted>", new ApiKey("sk-ant-supersecret").toString());
    }

    @Test void raw_returnsOriginal() {
        assertEquals("sk-ant-supersecret", new ApiKey("sk-ant-supersecret").raw());
    }

    @Test void nullInput_isCoercedToEmptyString_stillRedactsToString() {
        ApiKey k = new ApiKey(null);
        assertEquals("", k.raw());
        assertEquals("<redacted>", k.toString());
    }

    @Test void stringConcatenation_doesNotLeakRaw() {
        ApiKey k = new ApiKey("sk-ant-leak");
        String composed = "auth=" + k;  // implicit toString()
        assertEquals("auth=<redacted>", composed);
        assertFalse(composed.contains("sk-ant-leak"));
    }

    @Test void equalsAndHashCode_byRawValue() {
        assertEquals(new ApiKey("x"), new ApiKey("x"));
        assertEquals(new ApiKey("x").hashCode(), new ApiKey("x").hashCode());
        assertNotEquals(new ApiKey("x"), new ApiKey("y"));
    }
}
