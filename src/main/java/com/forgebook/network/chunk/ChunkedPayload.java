package com.forgebook.network.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * NET-04: splits strings larger than 32 KiB into ordered chunks and reassembles
 * them. Phase 1 ships the utility + unit test; no production call site yet.
 * Phase 2's provider-response path will wire this in when responses exceed the
 * single-packet ceiling.
 *
 * Split is UTF-16-codepoint-based for predictable chunk sizes; if downstream
 * requires byte-accurate splitting for wire-frame planning, callers can
 * pre-encode to UTF-8 and chunk the byte[] themselves. For Phase 1 / Phase 2's
 * mostly-ASCII chat payloads, codepoint splitting is safe and simpler.
 */
public final class ChunkedPayload {

    public static final int MAX_CHUNK = 32_768;

    private ChunkedPayload() {}

    public static List<String> split(String payload) {
        if (payload == null) throw new IllegalArgumentException("payload is null");
        if (payload.length() <= MAX_CHUNK) return List.of(payload);

        List<String> out = new ArrayList<>();
        int len = payload.length();
        for (int start = 0; start < len; start += MAX_CHUNK) {
            int end = Math.min(start + MAX_CHUNK, len);
            out.add(payload.substring(start, end));
        }
        return out;
    }

    public static String reassemble(List<String> chunks) {
        if (chunks == null) throw new IllegalArgumentException("chunks is null");
        if (chunks.size() == 1) return chunks.get(0);
        StringBuilder sb = new StringBuilder();
        for (String c : chunks) sb.append(c);
        return sb.toString();
    }
}
