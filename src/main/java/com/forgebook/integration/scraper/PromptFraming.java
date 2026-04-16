package com.forgebook.integration.scraper;

import java.util.UUID;

/**
 * Wraps extracted mod-doc text in an anti-injection XML frame (D-10, RESEARCH §4.5).
 *
 * Output format:
 * <pre>
 *   &lt;mod_doc trust="untrusted" source="{url}" tag="{nonce}"&gt;
 *   {text (truncated to TOOL_OUTPUT_CAP chars)}
 *   [... truncated at 8,000 chars — full document at {url}]  (when text &gt; cap)
 *   &lt;/mod_doc tag="{same-nonce}"&gt;
 * </pre>
 *
 * The 8-char nonce in both opening and closing tags prevents adversarially crafted
 * closing-tag injections from breaking the framing (D-10 defense-in-depth).
 *
 * All methods are static — utility class, not instantiable.
 */
public final class PromptFraming {

    /**
     * Per-tool output character cap (D-14/D-15). Not operator-configurable in v1.
     * Public so ListInstalledModsTool and other tools can reference it directly.
     */
    public static final int TOOL_OUTPUT_CAP = 8_000;

    private PromptFraming() {}

    /**
     * Wrap {@code text} in the standard {@code <mod_doc trust="untrusted">} framing with
     * an 8-char random nonce. Truncates to {@link #TOOL_OUTPUT_CAP} chars if needed.
     *
     * @param text      the extracted readable text (may already be truncated by caller)
     * @param sourceUrl canonical URL of the source document
     * @return framed string ready to use as {@code tool_result.content}
     */
    public static String wrap(String text, String sourceUrl) {
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String body = truncate(text, sourceUrl);
        return "<mod_doc trust=\"untrusted\" source=\"" + sourceUrl + "\" tag=\"" + nonce + "\">\n"
             + body
             + "\n</mod_doc tag=\"" + nonce + "\">";
    }

    /**
     * Truncate {@code text} to {@link #TOOL_OUTPUT_CAP} characters, appending the
     * canonical marker when truncation occurs (D-14).
     *
     * Package-private for direct testing by {@code PromptFramingTest}.
     */
    static String truncate(String text, String sourceUrl) {
        if (text.length() <= TOOL_OUTPUT_CAP) return text;
        return text.substring(0, TOOL_OUTPUT_CAP)
             + "\n[... truncated at 8,000 chars — full document at " + sourceUrl + "]";
    }
}
