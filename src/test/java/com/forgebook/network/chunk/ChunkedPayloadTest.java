package com.forgebook.network.chunk;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkedPayloadTest {

    @Test void smallPayload_returnsSingletonList_unchanged() {
        List<String> out = ChunkedPayload.split("hello");
        assertEquals(1, out.size());
        assertEquals("hello", out.get(0));
    }

    @Test void exactly32K_returnsSingletonList() {
        String s = "x".repeat(32_768);
        assertEquals(1, ChunkedPayload.split(s).size());
    }

    @Test void over32K_splitsIntoMultipleChunks() {
        String s = "x".repeat(100_000);
        List<String> out = ChunkedPayload.split(s);
        assertEquals(4, out.size());  // 32768 + 32768 + 32768 + 1696
        assertEquals(32_768, out.get(0).length());
        assertEquals(32_768, out.get(1).length());
        assertEquals(32_768, out.get(2).length());
        assertEquals(1_696, out.get(3).length());
    }

    @Test void roundTrip_splitThenReassemble_isIdentity() {
        String original = "a".repeat(70_000) + "b".repeat(5_000);
        List<String> chunks = ChunkedPayload.split(original);
        assertEquals(original, ChunkedPayload.reassemble(chunks));
    }

    @Test void nullPayload_throws() {
        assertThrows(IllegalArgumentException.class, () -> ChunkedPayload.split(null));
    }
}
