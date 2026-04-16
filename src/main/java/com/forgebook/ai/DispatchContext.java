package com.forgebook.ai;

import net.minecraft.server.level.ServerPlayer;

/**
 * Immutable carrier for dispatch metadata (Phase 3 — replaces the Phase 2
 * (String, ServerPlayer) pair passed to AiDispatcher.dispatch).
 *
 * Carries the sender directly so downstream layers (RequestAuditLogger,
 * Authorizer) can read UUID + permission level without plumbing extra args.
 * Never serialized; lives only inside the server process.
 */
public record DispatchContext(String message, ServerPlayer sender, RequestKind kind) {}
