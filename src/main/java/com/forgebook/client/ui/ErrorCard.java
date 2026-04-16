package com.forgebook.client.ui;

import com.forgebook.network.packet.ChatErrorPacket;

/**
 * An inline error card value type, rendered full-width of the chat panel with a
 * 4-px vertical stripe in the taxonomy color (UI-SPEC §"Error surfacing" + §"Phase-3
 * Error Taxonomy ⇢ UI Mapping"). Like MessageBubble, this holds only data;
 * rendering lives in ChatPanelWidget.
 */
public record ErrorCard(ChatErrorPacket.ErrorCode code, String humanReadable) implements ChatEntry {

    /**
     * Stripe ARGB color from UI-SPEC §"Phase-3 Error Taxonomy ⇢ UI Mapping" table.
     * Exhaustive switch — compiler enforces that every ErrorCode value is handled.
     */
    public static int stripeColor(ChatErrorPacket.ErrorCode code) {
        return switch (code) {
            case TRANSPORT    -> 0xFFF5A623;  // amber
            case RATE_LIMITED -> 0xFF4A90E2;  // blue
            case FORBIDDEN    -> 0xFFE74C3C;  // red
            case PROVIDER     -> 0xFFE74C3C;  // red
            case DISABLED     -> 0xFF808080;  // gray
            case OVERLOADED   -> 0xFFF5A623;  // amber
        };
    }

    /** i18n key for the error-card heading per UI-SPEC §"Copywriting Contract" table. */
    public static String headingKey(ChatErrorPacket.ErrorCode code) {
        return switch (code) {
            case TRANSPORT    -> "forgebook.error.transport.heading";
            case RATE_LIMITED -> "forgebook.error.rate_limited.heading";
            case FORBIDDEN    -> "forgebook.error.forbidden.heading";
            case PROVIDER     -> "forgebook.error.provider.heading";
            case DISABLED     -> "forgebook.error.disabled.heading";
            case OVERLOADED   -> "forgebook.error.overloaded.heading";
        };
    }

    /** i18n key for the error-card body per UI-SPEC §"Copywriting Contract" table. */
    public static String bodyKey(ChatErrorPacket.ErrorCode code) {
        return switch (code) {
            case TRANSPORT    -> "forgebook.error.transport.body";
            case RATE_LIMITED -> "forgebook.error.rate_limited.body";
            case FORBIDDEN    -> "forgebook.error.forbidden.body";
            case PROVIDER     -> "forgebook.error.provider.body";
            case DISABLED     -> "forgebook.error.disabled.body";
            case OVERLOADED   -> "forgebook.error.overloaded.body";
        };
    }
}
