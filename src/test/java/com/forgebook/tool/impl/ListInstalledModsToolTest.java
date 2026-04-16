package com.forgebook.tool.impl;

import com.forgebook.tool.ToolException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.locating.ForgeFeature;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for ListInstalledModsTool (Plan 02-05, Task 3 RED).
 * Uses a Supplier<List<? extends IModInfo>> seam — no Forge runtime needed.
 */
class ListInstalledModsToolTest {

    /** Minimal stub IModInfo — only implements the 4 fields we actually use. */
    static class StubModInfo implements IModInfo {
        private final String modId;
        private final String displayName;
        private final String version;
        private final String modUrl;

        StubModInfo(String modId, String displayName, String version, String modUrl) {
            this.modId = modId;
            this.displayName = displayName;
            this.version = version;
            this.modUrl = modUrl;
        }

        @Override public String getModId() { return modId; }
        @Override public String getDisplayName() { return displayName; }
        @Override public ArtifactVersion getVersion() { return new DefaultArtifactVersion(version); }

        @Override public Optional<URL> getModURL() {
            if (modUrl == null || modUrl.isBlank()) return Optional.empty();
            try { return Optional.of(new URL(modUrl)); }
            catch (Exception e) { return Optional.empty(); }
        }

        // Unused interface methods — minimal stubs
        @Override public IModFileInfo getOwningFile() { return null; }
        @Override public String getDescription() { return ""; }
        @Override public List<? extends IModInfo.ModVersion> getDependencies() { return List.of(); }
        @Override public List<? extends ForgeFeature.Bound> getForgeFeatures() { return List.of(); }
        @Override public String getNamespace() { return modId; }
        @Override public Map<String, Object> getModProperties() { return Map.of(); }
        @Override public Optional<URL> getUpdateURL() { return Optional.empty(); }
        @Override public Optional<String> getLogoFile() { return Optional.empty(); }
        @Override public boolean getLogoBlur() { return false; }
        @Override public IConfigurable getConfig() { return null; }
    }

    private static ListInstalledModsTool toolWith(List<? extends IModInfo> mods) {
        return new ListInstalledModsTool(() -> mods);
    }

    /** Test 1: name() == "list_installed_mods". */
    @Test
    void name_isCorrect() {
        assertEquals("list_installed_mods", toolWith(List.of()).name());
    }

    /** Test 2: schema() has type:object, properties.filter:string, required:[]. */
    @Test
    void schema_hasExpectedShape() {
        JsonObject schema = toolWith(List.of()).schema();
        assertEquals("object", schema.get("type").getAsString());
        assertTrue(schema.has("properties"), "Should have properties");
        JsonObject props = schema.getAsJsonObject("properties");
        assertTrue(props.has("filter"), "Should have filter property");
        assertEquals("string", props.getAsJsonObject("filter").get("type").getAsString());
        assertTrue(schema.has("required"), "Should have required array");
        assertEquals(0, schema.getAsJsonArray("required").size(), "required should be empty");
    }

    /** Test 3: invoke({}) on 3 mods returns JSON array of 3 objects with expected keys. */
    @Test
    void invoke_noFilter_returnsAllMods() throws ToolException {
        List<StubModInfo> mods = List.of(
            new StubModInfo("modA", "Mod A", "1.0", "https://a.example.com"),
            new StubModInfo("modB", "Mod B", "2.0", "https://b.example.com"),
            new StubModInfo("modC", "Mod C", "3.0", "")
        );
        String result = toolWith(mods).invoke(new JsonObject());
        var arr = JsonParser.parseString(result).getAsJsonArray();
        assertEquals(3, arr.size());
        var first = arr.get(0).getAsJsonObject();
        assertTrue(first.has("modId"), "Should have modId");
        assertTrue(first.has("displayName"), "Should have displayName");
        assertTrue(first.has("version"), "Should have version");
        assertTrue(first.has("modURL"), "Should have modURL");
    }

    /** Test 4: invoke({filter:"jei"}) returns only mods with "jei" in modId (case-insensitive). */
    @Test
    void invoke_withFilter_returnsOnlyMatchingMods() throws ToolException {
        List<StubModInfo> mods = List.of(
            new StubModInfo("jei", "Just Enough Items", "1.0", ""),
            new StubModInfo("jeiBridge", "JEI Bridge", "1.1", ""),
            new StubModInfo("thermalExpansion", "Thermal Expansion", "2.0", "")
        );
        JsonObject input = new JsonObject();
        input.addProperty("filter", "jei");
        String result = toolWith(mods).invoke(input);
        var arr = JsonParser.parseString(result).getAsJsonArray();
        assertEquals(2, arr.size(), "Should return only mods matching 'jei' filter");
        assertTrue(arr.get(0).getAsJsonObject().get("modId").getAsString().contains("jei"));
    }

    /** Test 5: When a mod has no modURL, modURL field is "" (not null). */
    @Test
    void invoke_noModUrl_returnsEmptyStringNotNull() throws ToolException {
        List<StubModInfo> mods = List.of(
            new StubModInfo("nourl", "No URL Mod", "1.0", null)
        );
        String result = toolWith(mods).invoke(new JsonObject());
        var arr = JsonParser.parseString(result).getAsJsonArray();
        var entry = arr.get(0).getAsJsonObject();
        assertEquals("", entry.get("modURL").getAsString(),
            "modURL should be empty string when mod has no URL");
    }

    /** Test 6: Output > 8000 chars is truncated. */
    @Test
    void invoke_largeOutput_isTruncated() throws ToolException {
        // Create enough mods with long names to exceed 8000 chars
        List<StubModInfo> mods = java.util.stream.IntStream.range(0, 300)
            .mapToObj(i -> new StubModInfo(
                "mod" + i,
                "A".repeat(50) + " Mod " + i,
                "1.0." + i,
                "https://example.com/mod/" + i))
            .toList();
        String result = toolWith(mods).invoke(new JsonObject());
        // Output should be capped around 8000 chars + truncation marker
        assertTrue(result.length() <= 8_500,
            "Output should be truncated when > 8000 chars, but was: " + result.length());
        assertTrue(result.contains("truncated"), "Truncated output should contain truncation marker");
    }
}
