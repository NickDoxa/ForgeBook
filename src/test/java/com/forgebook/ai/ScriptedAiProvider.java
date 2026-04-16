package com.forgebook.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.LinkedList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Queue-driven AiProvider stub for AgentLoop tests (RESEARCH §9.2).
 * Avoids Mockito — Phase 1 testing convention is plain inline classes per
 * CLAUDE.md "Mockito ... only where pure-Java seams exist".
 *
 * Usage:
 *   var queue = new LinkedList<AiTurn>();
 *   queue.add(new AiTurn.ToolUses(List.of(new AiTurn.ToolUseBlock("u1", "x", Map.of()))));
 *   queue.add(new AiTurn.FinalReply("done", false));
 *   var loop = new AgentLoop(new ScriptedAiProvider(queue));
 */
public final class ScriptedAiProvider implements AiProvider {
    private final Queue<AiTurn> turns;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<ChatRequest> history = new ArrayList<>();

    public ScriptedAiProvider(Queue<AiTurn> turns) {
        this.turns = new LinkedList<>(turns);
    }

    @Override
    public synchronized CompletableFuture<AiTurn> chat(ChatRequest req) {
        calls.incrementAndGet();
        history.add(req);
        AiTurn next = turns.poll();
        if (next == null) {
            return CompletableFuture.completedFuture(new AiTurn.ProviderError(
                AiTurn.ProviderError.Kind.PROVIDER,
                "scripted provider exhausted",
                Optional.empty()));
        }
        return CompletableFuture.completedFuture(next);
    }

    public int callCount() { return calls.get(); }

    /** Returns the ChatRequest passed on the Nth chat(...) call (0-indexed). */
    public ChatRequest requestAt(int index) { return history.get(index); }

    /** Returns the number of ChatRequests captured so far (== callCount() for successful calls). */
    public int requestCount() { return history.size(); }

    /** Returns the most recent ChatRequest, or null if no calls have been made. */
    public ChatRequest lastRequest() {
        return history.isEmpty() ? null : history.get(history.size() - 1);
    }
}
