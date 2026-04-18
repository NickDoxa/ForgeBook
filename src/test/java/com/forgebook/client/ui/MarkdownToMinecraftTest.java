package com.forgebook.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkdownToMinecraftTest {

    // §-code short-hands for clarity in assertions.
    private static final String R = "\u00a7r";
    private static final String L = "\u00a7l";
    private static final String O = "\u00a7o";
    private static final String N = "\u00a7n";
    private static final String M = "\u00a7m";
    private static final String G = "\u00a77";

    @Test void nullAndEmpty_returnSameShape() {
        assertNull(MarkdownToMinecraft.convert(null));
        assertEquals("", MarkdownToMinecraft.convert(""));
    }

    @Test void plainText_isUnchanged() {
        String input = "Just some regular text with no markdown.";
        assertEquals(input, MarkdownToMinecraft.convert(input));
    }

    @Test void bold_starStar_becomesBoldCode() {
        assertEquals("A " + L + "bold" + R + " word.",
            MarkdownToMinecraft.convert("A **bold** word."));
    }

    @Test void underline_doubleUnderscore_becomesUnderlineCode() {
        assertEquals("A " + N + "stressed" + R + " word.",
            MarkdownToMinecraft.convert("A __stressed__ word."));
    }

    @Test void italic_singleStar_becomesItalicCode() {
        assertEquals("An " + O + "emphasized" + R + " point.",
            MarkdownToMinecraft.convert("An *emphasized* point."));
    }

    @Test void italic_singleUnderscore_becomesItalicCode() {
        assertEquals("An " + O + "emphasized" + R + " point.",
            MarkdownToMinecraft.convert("An _emphasized_ point."));
    }

    @Test void strike_tildeTilde_becomesStrikeCode() {
        assertEquals(M + "old info" + R,
            MarkdownToMinecraft.convert("~~old info~~"));
    }

    @Test void inlineCode_becomesGrayCode() {
        assertEquals("Use " + G + "forgebook" + R + " for short.",
            MarkdownToMinecraft.convert("Use `forgebook` for short."));
    }

    @Test void codeFence_becomesGrayBlock() {
        String result = MarkdownToMinecraft.convert("```java\nint x = 1;\n```");
        assertTrue(result.startsWith(G));
        assertTrue(result.endsWith(R));
        assertTrue(result.contains("int x = 1;"));
    }

    @Test void heading_becomesGoldBold_hashesStripped() {
        // 1.0.7: tactical color — headings get a gold accent so body text can stay
        // default white. Prefix is §6§l, suffix §r.
        String goldBold = "\u00a76" + L;
        assertEquals(goldBold + "Title" + R,
            MarkdownToMinecraft.convert("# Title"));
        assertEquals(goldBold + "Subtitle" + R,
            MarkdownToMinecraft.convert("### Subtitle"));
    }

    @Test void bullet_dashBecomesUnicodeBullet() {
        assertEquals("\u2022 first\n\u2022 second",
            MarkdownToMinecraft.convert("- first\n- second"));
    }

    @Test void bullet_starBecomesUnicodeBullet() {
        assertEquals("\u2022 first",
            MarkdownToMinecraft.convert("* first"));
    }

    @Test void link_becomesTextWithUrlInParens() {
        assertEquals("see docs (https://example.com)",
            MarkdownToMinecraft.convert("see [docs](https://example.com)"));
    }

    @Test void mixed_boldItalicBullet_allConverted() {
        String in = "**Important**: this uses *Create* mechanics.\n- one\n- two";
        String out = MarkdownToMinecraft.convert(in);
        assertTrue(out.contains(L + "Important" + R));
        assertTrue(out.contains(O + "Create" + R));
        assertTrue(out.contains("\u2022 one"));
        assertTrue(out.contains("\u2022 two"));
    }

    @Test void boldInsideHeading_bothApplied() {
        // Headings strip the # and bold the content. An explicit **bold** inside
        // is still recognized and becomes double-bold-code (harmless — no visual
        // change from a single §l, and no extra §r that would leak styling).
        String out = MarkdownToMinecraft.convert("## Create: **Cogwheels**");
        assertTrue(out.contains(L));
        assertTrue(out.contains("Cogwheels"));
        assertTrue(!out.contains("**"));
        assertTrue(!out.startsWith("#"));
    }

    @Test void standaloneAsterisk_notConverted() {
        // A lone * without a matching close should NOT be dropped.
        String out = MarkdownToMinecraft.convert("5 * 3 = 15");
        assertEquals("5 * 3 = 15", out);
    }
}
