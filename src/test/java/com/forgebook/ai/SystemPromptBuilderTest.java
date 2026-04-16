package com.forgebook.ai;

import com.forgebook.config.*;
import com.forgebook.integration.ModpackContext;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolException;
import com.forgebook.tool.ToolRegistry;
import com.forgebook.util.SafeHttpFetcher;
import com.google.gson.JsonObject;
import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.ForgeFeature;
import org.apache.maven.artifact.versioning.ArtifactVersion;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * SystemPromptBuilder unit tests (AI-08, D-08, D-09, D-10).
 * Tests 6–12 use build() directly (pure function — no IO).
 * Tests 13–14 use buildAndCacheInternal() (package-private seam).
 */
class SystemPromptBuilderTest {

    /** Minimal ConfigSnapshot with empty CurseForge fields (fetch returns Optional.empty). */
    private static ConfigSnapshot emptySnap;

    @BeforeAll
    static void setup() {
        emptySnap = new ConfigSnapshot(
            AiProviderKind.ANTHROPIC,
            new ApiKey(""),
            "claude-haiku-4-5",
            1024,
            Optional.empty(),        // curseforgeModpackId — empty → CF silent skip
            new ApiKey(""),
            true,
            10,
            false,
            WebSearchProviderKind.DUCKDUCKGO,
            new ApiKey(""),
            1
        );
    }

    @AfterEach
    void resetRegistry() {
        ToolRegistry.injectForTests(new LinkedHashMap<>());
    }

    // ─── Tests 6–12: pure build() ────────────────────────────────────────────

    @Test
    void test6_emptyModListEmptyModpack_containsIdentityAndAntiInjectionNoModpackSection() {
        String prompt = SystemPromptBuilder.build(List.of(), Optional.empty(), List.of(), emptySnap);

        assertTrue(prompt.contains("You are ForgeBook"), "Must contain identity preamble");
        assertTrue(prompt.contains("Installed mods:"), "Must contain mods section header");
        assertTrue(prompt.contains("Available tools:"), "Must contain tools section header");
        assertTrue(prompt.contains("trust=\"untrusted\""), "Must contain anti-injection rule");
        assertFalse(prompt.contains("Modpack:"), "Must NOT contain Modpack section when context empty");
    }

    @Test
    void test7_threeModsAllPresentInOutput() throws Exception {
        List<IModInfo> mods = List.of(
            fakeMod("alpha", "Alpha Mod", "1.0", "https://alpha.example/"),
            fakeMod("beta",  "Beta Mod",  "2.0", "https://beta.example/"),
            fakeMod("gamma", "Gamma Mod", "3.0", "https://gamma.example/")
        );
        String prompt = SystemPromptBuilder.build(mods, Optional.empty(), List.of(), emptySnap);

        assertTrue(prompt.contains("alpha"), "Must contain modId 'alpha'");
        assertTrue(prompt.contains("Alpha Mod"), "Must contain displayName 'Alpha Mod'");
        assertTrue(prompt.contains("beta"), "Must contain modId 'beta'");
        assertTrue(prompt.contains("Beta Mod"), "Must contain displayName 'Beta Mod'");
        assertTrue(prompt.contains("gamma"), "Must contain modId 'gamma'");
        assertTrue(prompt.contains("Gamma Mod"), "Must contain displayName 'Gamma Mod'");
    }

    @Test
    void test8_modWithNoUrlRenderedAsNoDocsUrl() throws Exception {
        List<IModInfo> mods = List.of(fakeMod("nourl", "No Url Mod", "1.0", null));
        String prompt = SystemPromptBuilder.build(mods, Optional.empty(), List.of(), emptySnap);

        assertTrue(prompt.contains("(no docs URL)"), "Must render '(no docs URL)' for mod with empty URL");
    }

    @Test
    void test9_withModpackContextContainsModpackSection() {
        var ctx = new ModpackContext("All the Mods 9", "Big modpack.");
        String prompt = SystemPromptBuilder.build(List.of(), Optional.of(ctx), List.of(), emptySnap);

        assertTrue(prompt.contains("Modpack:"), "Must contain Modpack section header");
        assertTrue(prompt.contains("All the Mods 9"), "Must contain modpack name");
        assertTrue(prompt.contains("Big modpack."), "Must contain modpack summary");
    }

    @Test
    void test10_antiInjectionRulesPresent() {
        String prompt = SystemPromptBuilder.build(List.of(), Optional.empty(), List.of(), emptySnap);

        assertTrue(prompt.contains("trust=\"untrusted\""),
            "Must contain the untrusted framing tag reference");
        assertTrue(prompt.contains("Never reveal") || prompt.contains("never reveal"),
            "Must contain rule about never revealing secrets");
    }

    @Test
    void test11_toolDescriptionsPresent() {
        Collection<Tool> tools = List.of(
            new TestTool("list_installed_mods", "Lists installed mods."),
            new TestTool("web_search", "Searches the web.")
        );
        String prompt = SystemPromptBuilder.build(List.of(), Optional.empty(), tools, emptySnap);

        assertTrue(prompt.contains("list_installed_mods"), "Must contain tool name");
        assertTrue(prompt.contains("Lists installed mods."), "Must contain tool description");
        assertTrue(prompt.contains("web_search"), "Must contain second tool name");
        assertTrue(prompt.contains("Searches the web."), "Must contain second tool description");
    }

    @Test
    void test12_largeModListAllIncluded() throws Exception {
        // 100 fake mods — all must appear in prompt (no truncation by builder, D-09)
        var mods = new ArrayList<IModInfo>();
        for (int i = 0; i < 100; i++) {
            mods.add(fakeMod("mod" + i, "Mod " + i, "1.0", null));
        }
        String prompt = SystemPromptBuilder.build(mods, Optional.empty(), List.of(), emptySnap);

        for (int i = 0; i < 100; i++) {
            assertTrue(prompt.contains("mod" + i), "Must contain modId 'mod" + i + "'");
        }
    }

    // ─── Tests 13–14: buildAndCacheInternal orchestration ───────────────────

    @Test
    void test13_buildAndCacheInternalOrchestrationSetsCache() throws Exception {
        // Reset caches to known state
        SystemPromptCache.set("");
        ModpackContextCache_set(Optional.empty());

        List<IModInfo> mods = List.of(
            fakeMod("testmod", "Test Mod", "1.0", null)
        );

        // Call with empty curseforge fields — CurseForgeClient.fetch returns Optional.empty
        SystemPromptBuilder.buildAndCacheInternal(emptySnap, mods, new SafeHttpFetcher(), null);

        // (a) SystemPromptCache should now be set (non-empty)
        String prompt = SystemPromptCache.get();
        assertFalse(prompt.isEmpty(), "SystemPromptCache must be non-empty after buildAndCacheInternal");
        assertTrue(prompt.contains("You are ForgeBook"), "Prompt must contain identity preamble");

        // (b) ModpackContextCache should be set to empty (no CurseForge configured)
        assertTrue(ModpackContextCache_get().isEmpty(), "ModpackContextCache must be empty when CF not configured");

        // (c) ToolRegistry must have 4 tools registered
        assertEquals(4, ToolRegistry.all().size(), "ToolRegistry must have 4 tools after init");

        // Cleanup
        SystemPromptCache.set("");
    }

    @Test
    void test14_buildAndCacheInternalEmptyCurseforgeGracefulNoModpackSection() throws Exception {
        SystemPromptCache.set("");
        ModpackContextCache_set(Optional.empty());

        SystemPromptBuilder.buildAndCacheInternal(emptySnap, List.of(), new SafeHttpFetcher(), null);

        // ModpackContextCache must be empty
        assertTrue(ModpackContextCache_get().isEmpty(), "ModpackContextCache must be empty");

        // Prompt must NOT contain Modpack section
        String prompt = SystemPromptCache.get();
        assertFalse(prompt.contains("Modpack:"), "Prompt must not contain Modpack section when CF not configured");

        // No exception thrown (tested implicitly by reaching this assertion)

        // ToolRegistry must still be initialized with 4 tools
        assertEquals(4, ToolRegistry.all().size(), "ToolRegistry must still have 4 tools");

        SystemPromptCache.set("");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Reflection helper to call ModpackContextCache.set() since it's in a different package. */
    private static void ModpackContextCache_set(Optional<ModpackContext> ctx) {
        com.forgebook.integration.ModpackContextCache.set(ctx);
    }

    private static Optional<ModpackContext> ModpackContextCache_get() {
        return com.forgebook.integration.ModpackContextCache.get();
    }

    /** Inline IModInfo stub — no Mockito. Implements all abstract methods. */
    private static IModInfo fakeMod(String modId, String displayName, String version, String url) {
        return new IModInfo() {
            @Override public String getModId() { return modId; }
            @Override public String getDisplayName() { return displayName; }
            @Override public ArtifactVersion getVersion() {
                return new org.apache.maven.artifact.versioning.DefaultArtifactVersion(version);
            }
            @Override public Optional<URL> getModURL() {
                if (url == null) return Optional.empty();
                try { return Optional.of(new URL(url)); }
                catch (MalformedURLException e) { return Optional.empty(); }
            }
            @Override public String getDescription() { return ""; }
            @Override public Optional<URL> getUpdateURL() { return Optional.empty(); }
            @Override public Optional<String> getLogoFile() { return Optional.empty(); }
            @Override public boolean getLogoBlur() { return false; }
            @Override public List<? extends IModInfo.ModVersion> getDependencies() { return List.of(); }
            @Override public List<? extends ForgeFeature.Bound> getForgeFeatures() { return List.of(); }
            @Override public IModFileInfo getOwningFile() { return null; }
            @Override public Map<String, Object> getModProperties() { return Map.of(); }
            @Override public String getNamespace() { return modId; }
            @Override public IConfigurable getConfig() { return null; }
        };
    }

    /** Simple Tool stub returning name + description only. */
    private static final class TestTool implements Tool {
        private final String name;
        private final String description;

        TestTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public JsonObject schema() { return new JsonObject(); }
        @Override public String invoke(JsonObject input) throws ToolException {
            return "stub";
        }
    }
}
