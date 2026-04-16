package com.forgebook.client.ui;

/**
 * Sealed interface for conversation entries. Enables exhaustive instanceof
 * pattern-matching in the render loop. Mirrors the sealed-interface + record-variants
 * pattern from com.forgebook.safety.RateLimiter.Outcome (RateLimiter.java:41-50).
 */
public sealed interface ChatEntry permits MessageBubble, ErrorCard {}
