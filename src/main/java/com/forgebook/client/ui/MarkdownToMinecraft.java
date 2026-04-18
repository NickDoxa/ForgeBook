package com.forgebook.client.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convert Claude's markdown output to Minecraft's §-code formatting so chat
 * bubbles render cleanly instead of showing raw markdown syntax.
 *
 * <p>Minecraft's {@code Font} natively understands section-sign ({@code §})
 * escape sequences — {@code §l} = bold, {@code §o} = italic, {@code §n} =
 * underline, {@code §m} = strikethrough, {@code §7} = gray, {@code §r} =
 * reset to default. Passing §-coded strings through {@code GuiGraphics.drawString}
 * produces inline formatting without any extra render-path work.
 *
 * <p>Supported conversions (most-specific match first to avoid overlap):
 * <ul>
 *   <li><code>```code fence```</code>                → {@code §7...§r}          (gray, multi-line)
 *   <li><code>`inline code`</code>                   → {@code §7...§r}          (gray)
 *   <li><code>**bold**</code>                        → {@code §l...§r}
 *   <li><code>__underline__</code>                   → {@code §n...§r}          (before *italic* to avoid _ clash)
 *   <li><code>*italic*</code> / <code>_italic_</code> → {@code §o...§r}
 *   <li><code>~~strike~~</code>                      → {@code §m...§r}
 *   <li><code># Heading</code> (any level)           → {@code §l...§r}          (bold, newline preserved)
 *   <li><code>- bullet</code> / <code>* bullet</code> → {@code • bullet}         (unicode bullet marker)
 *   <li><code>[link text](url)</code>                → {@code link text (url)}   (Minecraft chat has no click-links in custom Screens without extra work)
 * </ul>
 *
 * <p>Pure function — no Minecraft imports, trivially testable. The class lives
 * under {@code com.forgebook.client.ui} because conversion is a display concern;
 * it's applied in {@code ClientChatSession.append} before the bubble is added
 * to the session.
 *
 * <p>UI-08 firewall: no imports of {@code com.forgebook.ai.*},
 * {@code com.forgebook.safety.*}, or {@code com.forgebook.config.ApiKey}.
 */
public final class MarkdownToMinecraft {

    private static final String RESET = "\u00a7r";
    private static final String BOLD  = "\u00a7l";
    private static final String ITAL  = "\u00a7o";
    private static final String UND   = "\u00a7n";
    private static final String STRIKE = "\u00a7m";
    private static final String GRAY  = "\u00a77";
    private static final String GOLD  = "\u00a76";   // headings — tactical accent, body stays white

    // Order matters — more specific patterns MUST match before less specific ones.
    // Code fences ``` before inline `. ** before *. __ before _.
    private static final Pattern CODE_FENCE =
        Pattern.compile("```[a-zA-Z0-9]*\\s*([\\s\\S]*?)```");
    private static final Pattern CODE_INLINE =
        Pattern.compile("`([^`\\n]+)`");
    private static final Pattern BOLD_P =
        Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern UNDERLINE_P =
        Pattern.compile("__([^_\\n]+)__");
    private static final Pattern ITAL_ASTERISK =
        Pattern.compile("(?<![*\\w])\\*([^*\\n]+)\\*(?!\\*)");
    private static final Pattern ITAL_UNDERSCORE =
        Pattern.compile("(?<![_\\w])_([^_\\n]+)_(?![_\\w])");
    private static final Pattern STRIKE_P =
        Pattern.compile("~~([^~\\n]+)~~");
    private static final Pattern HEADING =
        Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern BULLET =
        Pattern.compile("^([ \\t]*)[-*+]\\s+", Pattern.MULTILINE);
    private static final Pattern MD_LINK =
        Pattern.compile("\\[([^\\]\\n]+)]\\(([^\\s)]+)\\)");

    private MarkdownToMinecraft() {}

    /**
     * Convert markdown text to Minecraft §-coded text. Idempotent on strings
     * that are already §-coded (§-codes do not collide with any markdown syntax).
     *
     * @param markdown input text, possibly containing markdown formatting
     * @return same text with markdown syntax replaced by §-codes; null-in → null-out
     */
    public static String convert(String markdown) {
        if (markdown == null) return null;
        if (markdown.isEmpty()) return markdown;

        String s = markdown;
        // Code fences first so their inner text isn't mangled by other patterns.
        s = replaceGroup1With(CODE_FENCE, s, GRAY, RESET);
        s = replaceGroup1With(CODE_INLINE, s, GRAY, RESET);
        s = replaceGroup1With(BOLD_P, s, BOLD, RESET);
        s = replaceGroup1With(UNDERLINE_P, s, UND, RESET);
        s = replaceGroup1With(ITAL_ASTERISK, s, ITAL, RESET);
        s = replaceGroup1With(ITAL_UNDERSCORE, s, ITAL, RESET);
        s = replaceGroup1With(STRIKE_P, s, STRIKE, RESET);

        // Headings: "# Title" → "§6§lTitle§r" (gold + bold; body stays default white).
        s = HEADING.matcher(s).replaceAll(GOLD + BOLD + "$2" + RESET);

        // Bullets: "- foo" → "• foo" keeping indentation.
        s = BULLET.matcher(s).replaceAll("$1\u2022 ");

        // Links: "[text](url)" → "text (url)".
        s = MD_LINK.matcher(s).replaceAll("$1 ($2)");

        return s;
    }

    /**
     * Convert markdown AND apply a base Minecraft color code. Used by command
     * surfaces so AI replies render in a consistent color (e.g. {@code §b} aqua)
     * instead of default white. Ensures nested resets restore the base color.
     *
     * <p>Transformation: the returned string is {@code baseColor + convert(markdown)}
     * with every mid-span reset ({@code §r}) replaced by {@code §r + baseColor}, and
     * a trailing {@code §r} appended so the color doesn't leak into subsequent chat
     * messages.
     *
     * @param markdown  input markdown text; null-in → null-out
     * @param baseColor Minecraft §-color code (e.g. {@code "\u00a7b"}); null or
     *                  blank coerces to pure {@link #convert(String)}
     * @return §-coded string with the base color applied, or null if input was null
     */
    public static String convertColored(String markdown, String baseColor) {
        if (markdown == null) return null;
        String converted = convert(markdown);
        if (baseColor == null || baseColor.isEmpty()) return converted;
        String restored = converted.replace(RESET, RESET + baseColor);
        return baseColor + restored + RESET;
    }

    /**
     * Wrap group(1) of each match in {@code prefix ... suffix} — shared helper
     * for bold / italic / underline / strike / code.
     */
    private static String replaceGroup1With(Pattern p, String input, String prefix, String suffix) {
        Matcher m = p.matcher(input);
        StringBuilder sb = new StringBuilder(input.length() + 32);
        int last = 0;
        while (m.find()) {
            sb.append(input, last, m.start());
            sb.append(prefix).append(m.group(1)).append(suffix);
            last = m.end();
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }
}
