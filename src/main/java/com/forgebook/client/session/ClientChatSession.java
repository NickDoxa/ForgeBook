package com.forgebook.client.session;

import com.forgebook.client.ui.ErrorCard;
import com.forgebook.client.ui.MessageBubble;
import com.forgebook.network.packet.ChatErrorPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client-side, per-session conversation holder. Ephemeral: cleared on ChatScreen.onClose()
 * (UI-05) AND on ClientPlayerNetworkEvent.LoggingOut (plan 04-03 SessionLifecycleListener).
 *
 * Singleton pattern copied from com.forgebook.safety.KillSwitch (private ctor + static INSTANCE).
 * Writes are synchronized (mirrors com.forgebook.safety.TokenBucket.tryAcquire's synchronized
 * pattern). Reads are synchronized for lists (defensive copy) or use volatile for pending flags
 * so render-thread reads do not tear.
 *
 * Stale-response guard (UI-D-11): append/appendError silently discard when requestId doesn't
 * match pendingRequestId. This happens when the screen closes between submit and response —
 * the response arrives, the requestId is unknown, and we drop it on the floor.
 *
 * UI-08 self-audit: this file's imports MUST NOT reference:
 *   - net.minecraft.* (enforced by SCAF-02 CI rule)
 *   - com.forgebook.ai.*
 *   - com.forgebook.config.ApiKey
 *   - com.forgebook.safety.* (except wire-protocol enums — none here)
 * ErrorCode from com.forgebook.network.packet is OK — it's a wire-protocol type, not a secret.
 */
public final class ClientChatSession {

    private static final ClientChatSession INSTANCE = new ClientChatSession();

    public static ClientChatSession get() {
        return INSTANCE;
    }

    private final List<MessageBubble> bubbles = new ArrayList<>();
    private final List<ErrorCard> errors = new ArrayList<>();
    private volatile UUID pendingRequestId = null;
    private volatile boolean pending = false;

    private ClientChatSession() {}

    /** Optimistic render: user types, we append immediately before server ack. */
    public synchronized void appendUserMessage(UUID id, String text) {
        bubbles.add(MessageBubble.user(text));
    }

    /** Enter pending state for the given requestId. Overrides any prior pending id. */
    public synchronized void markPending(UUID id) {
        this.pendingRequestId = id;
        this.pending = true;
    }

    /** Leave pending state without adding a bubble (used if submit aborted client-side). */
    public synchronized void markIdle() {
        this.pendingRequestId = null;
        this.pending = false;
    }

    /**
     * Server-reply append. Stale-guard: if requestId doesn't match the currently-pending id,
     * the reply is silently discarded. This handles the race: user closed screen mid-flight,
     * we cleared state, reply arrives late.
     */
    public synchronized void append(UUID requestId, String reply) {
        if (pendingRequestId == null || !pendingRequestId.equals(requestId)) return;
        // Claude replies are markdown. Minecraft's Font renders §-codes natively,
        // so we translate **bold**/*italic*/`code`/# headings here once (at
        // ingress) rather than on every render frame. See MarkdownToMinecraft.
        String rendered = com.forgebook.client.ui.MarkdownToMinecraft.convert(reply);
        bubbles.add(MessageBubble.assistant(rendered));
        pendingRequestId = null;
        pending = false;
    }

    /** Server-error append with identical stale-guard semantics. */
    public synchronized void appendError(UUID requestId, ChatErrorPacket.ErrorCode code, String humanReadable) {
        if (pendingRequestId == null || !pendingRequestId.equals(requestId)) return;
        errors.add(new ErrorCard(code, humanReadable));
        pendingRequestId = null;
        pending = false;
    }

    /** UI-05: empty all state. Called from ChatScreen.onClose() and on client logout. */
    public synchronized void clear() {
        bubbles.clear();
        errors.clear();
        pendingRequestId = null;
        pending = false;
    }

    /** Volatile read — safe from render thread without sync. */
    public boolean isPending() {
        return pending;
    }

    /** Volatile read — null when idle. */
    public UUID pendingRequestId() {
        return pendingRequestId;
    }

    /** Defensive copy — caller can mutate without racing internal list writes. */
    public synchronized List<MessageBubble> snapshotBubbles() {
        return List.copyOf(bubbles);
    }

    /** Defensive copy. */
    public synchronized List<ErrorCard> snapshotErrors() {
        return List.copyOf(errors);
    }
}
