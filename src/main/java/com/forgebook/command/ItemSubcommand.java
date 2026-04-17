package com.forgebook.command;

import com.forgebook.ai.RagItemPipeline;
import com.forgebook.ai.RequestKind;
import com.forgebook.config.ConfigHolder;
import com.forgebook.config.ConfigSnapshot;
import com.forgebook.network.packet.ChatErrorPacket.ErrorCode;
import com.forgebook.safety.Authorizer;
import com.forgebook.safety.RateLimiter;
import com.forgebook.safety.RateLimiterHolder;
import com.forgebook.safety.RequestAuditLogger;
import com.forgebook.util.AiExecutor;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URL;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * /forgebook item [modid:item] — RAG single-shot pipeline (CMD-02, CMD-07).
 *
 * <p>Plan 06a wired the Brigadier tree; this file fills the stub bodies. Entry points:
 * <ul>
 *   <li>{@link #executeHeld(CommandContext)} — main-hand item; sends failure when empty.</li>
 *   <li>{@link #executeWithArg(CommandContext)} — {@link ItemArgument} parsed item argument.</li>
 * </ul>
 *
 * <h2>Control flow</h2>
 * The public {@code executeHeld} / {@code executeWithArg} entry points unpack the Minecraft
 * types ({@link CommandSourceStack}, {@link ServerPlayer}, {@link ItemStack}) into pure-Java
 * primitives ({@link Item}, {@link UUID}, boolean) and delegate to the package-private
 * {@link #executeInternal} seam. The seam then:
 * <ol>
 *   <li>Resolves {@link Item} → {@link ResourceLocation} → {@code modId}/{@code itemId}.</li>
 *   <li>Resolves documentation URL via {@link net.minecraftforge.forgespi.language.IModInfo#getModURL()}
 *       (the correct method on {@code IModInfo} — see CLAUDE.md stack notes).</li>
 *   <li>Loads a single {@link ConfigSnapshot} (D-14 single volatile read).</li>
 *   <li>Calls {@link Authorizer#authorize} on the TICK THREAD, BEFORE any
 *       {@link AiExecutor#get()}/{@code submit} — SAFE-06 invariant mirrored from
 *       {@link com.forgebook.network.handler.ChatRequestHandler}. Spoofed permission claims
 *       can't forge past the server-side {@code hasPermissions(2)} read.</li>
 *   <li>On {@link Authorizer.Denied} emits {@link RequestAuditLogger#logDenied} + {@code sendFailure},
 *       returns 0.</li>
 *   <li>On {@link Authorizer.Allowed} submits the RAG pipeline via the injected executor supplier
 *       (HTTP + provider work off-tick).</li>
 *   <li>{@link RejectedExecutionException} from queue overflow (D-20) → OVERLOADED audit + failure.</li>
 * </ol>
 *
 * <h2>Pitfall 2 — off-tick sendSuccess (v1 limitation)</h2>
 * See the tradeoff block in 03-06b-PLAN.md. {@link RagItemPipeline#run} currently calls
 * {@code src.sendSuccess}/{@code src.sendFailure} synchronously from inside the submitted
 * {@link AiExecutor} task — those sends therefore execute off-tick. A one-line v2 fix
 * inside {@link RagItemPipeline} will wrap those sends in {@code src.getServer().execute(...)}.
 * For v1 this is benign except for the rare case of a player disconnecting mid-request
 * (logs a {@code NetworkPipelineException} without user-visible harm). See the
 * {@code TODO(v2)} marker below the {@code AiExecutor.get().submit(...)} call.
 *
 * <h2>Test seam</h2>
 * The package-private {@link #executeInternal} overload exists because
 * {@link ServerPlayer}, {@link CommandSourceStack}, and {@link ItemStack} are hostile
 * to pure-JUnit instantiation outside the game harness (see CLAUDE.md "avoid mocking
 * Minecraft classes" and the Plan 03 {@link Authorizer} primitive-overload precedent).
 * Unit tests construct pure-Java {@code Consumer<String>} sinks + canned lookup lambdas
 * and drive {@code executeInternal} directly, avoiding any Minecraft classloading.
 */
public final class ItemSubcommand {

    private static final Logger LOG = LogManager.getLogger();

    private ItemSubcommand() {}

    // ------------------------------------------------------------------
    // Public entry points (production)
    // ------------------------------------------------------------------

    public static int executeHeld(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        return executeInternal(
            ctx.getSource(), player,
            stack.isEmpty() ? null : stack.getItem(),
            player.getUUID(),
            /* isEmptyHand */ stack.isEmpty(),
            /* heldContext */ true,
            // IModInfo.getModURL() is the correct IModInfo entry point (see DEFAULT_MOD_URL_LOOKUP).
            DEFAULT_RESOURCE_LOOKUP,
            DEFAULT_MOD_URL_LOOKUP,
            ConfigHolder::get,
            RateLimiterHolder::get,
            AiExecutor::get);
    }

    public static int executeWithArg(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        // Let CommandSyntaxException propagate — Brigadier renders the standard error.
        ItemInput input = ItemArgument.getItem(ctx, "item");
        ItemStack stack = input.createItemStack(1, false);
        return executeInternal(
            ctx.getSource(), player,
            stack.getItem(),
            player.getUUID(),
            /* isEmptyHand */ false,
            /* heldContext */ false,
            DEFAULT_RESOURCE_LOOKUP,
            DEFAULT_MOD_URL_LOOKUP,
            ConfigHolder::get,
            RateLimiterHolder::get,
            AiExecutor::get);
    }

    // ------------------------------------------------------------------
    // Package-private core + test seam
    // ------------------------------------------------------------------

    /** Default lookup: ask ForgeRegistries for the item's resource location. */
    static final Function<Item, ResourceLocation> DEFAULT_RESOURCE_LOOKUP =
        item -> ForgeRegistries.ITEMS.getKey(item);

    /**
     * Pattern for an http/https URL embedded in free-text mods.toml fields
     * (description, credits). Terminates at whitespace, quotes, angle brackets,
     * closing paren/bracket, or a trailing period-followed-by-space that is
     * probably sentence punctuation (e.g. "Visit https://example.com." — we
     * want to keep the slash but not the sentence-ending period).
     */
    private static final java.util.regex.Pattern URL_PATTERN =
        java.util.regex.Pattern.compile("https?://[^\\s'\"<>()\\[\\]]+?(?=[.!?,]?(\\s|$))");

    /**
     * Pure-function URL extractor for free-text mod metadata.
     * Returns the first http(s) URL found in the given text, or empty.
     * Prefers URLs whose host looks like documentation (github, gitlab,
     * *.fandom.com, anything containing "wiki" or "docs") over social links.
     *
     * <p>Unit-testable without booting Minecraft. Exposed package-private
     * for tests under src/test/java/com/forgebook/command/.
     */
    static Optional<URL> extractUrlFromText(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        java.util.regex.Matcher m = URL_PATTERN.matcher(text);
        URL firstMatch = null;
        URL preferredMatch = null;
        while (m.find()) {
            String candidate = m.group();
            try {
                java.net.URI uri = java.net.URI.create(candidate);
                URL url = uri.toURL();
                String host = url.getHost() == null ? "" : url.getHost().toLowerCase();
                String path = url.getPath() == null ? "" : url.getPath().toLowerCase();
                boolean looksLikeDocs =
                    host.contains("github.com")
                        || host.contains("gitlab.com")
                        || host.endsWith(".fandom.com")
                        || host.contains("wiki")
                        || host.contains("docs")
                        || path.contains("wiki")
                        || path.contains("docs");
                boolean isSocial =
                    host.equals("twitter.com") || host.equals("x.com")
                        || host.contains("discord.gg") || host.contains("discord.com")
                        || host.contains("patreon.com") || host.contains("ko-fi.com")
                        || host.contains("buymeacoffee.com") || host.contains("paypal.");
                if (preferredMatch == null && looksLikeDocs && !isSocial) {
                    preferredMatch = url;
                }
                if (firstMatch == null && !isSocial) {
                    firstMatch = url;
                }
            } catch (java.net.MalformedURLException | IllegalArgumentException ignored) {
                // skip malformed matches; regex is greedy-ish
            }
        }
        return Optional.ofNullable(preferredMatch != null ? preferredMatch : firstMatch);
    }

    /**
     * Default lookup: three-tier resolver for a mod's documentation URL.
     * <ol>
     *   <li>{@link net.minecraftforge.forgespi.language.IModInfo#getModURL()} — the
     *       canonical {@code displayURL} field in mods.toml. Best when present.</li>
     *   <li>Scan the free-text {@code description} field for an http(s) URL. Many mod
     *       authors write "Visit my wiki at https://..." in the description but leave
     *       {@code displayURL} empty.</li>
     *   <li>Scan {@code credits} similarly, as a last resort.</li>
     * </ol>
     * Social links (Discord/Twitter/Patreon/etc.) are filtered out since they
     * rarely contain documentation.
     */
    static final Function<String, Optional<URL>> DEFAULT_MOD_URL_LOOKUP =
        modId -> {
            var container = ModList.get().getModContainerById(modId);
            if (container.isEmpty()) return Optional.empty();
            var info = container.get().getModInfo();
            // Tier 1: dedicated displayURL field.
            Optional<URL> fromField = info.getModURL();
            if (fromField.isPresent()) return fromField;
            // Tier 2: scrape description text.
            Optional<URL> fromDescription = extractUrlFromText(info.getDescription());
            if (fromDescription.isPresent()) return fromDescription;
            // Tier 3: scrape credits (often holds "based on mod X at https://...").
            try {
                // IModInfo doesn't expose getCredits() directly — reach through the config holder.
                // ModInfo in 1.20.1 keeps credits under configElement("credits"). If the API
                // shape isn't available at runtime, silently skip tier 3.
                java.util.Optional<String> credits = info.getConfig().getConfigElement("credits")
                    .filter(v -> v instanceof String)
                    .map(Object::toString);
                return credits.flatMap(ItemSubcommand::extractUrlFromText);
            } catch (Throwable t) {
                return Optional.empty();
            }
        };

    /**
     * Core pipeline — pure-Java types for every Minecraft-or-static dependency.
     * Tests drive this directly with canned lookups + a captured executor ref.
     *
     * <p>Invariants enforced here:
     * <ul>
     *   <li>{@link Authorizer#authorize} is consulted BEFORE any executor access.</li>
     *   <li>On denial / empty hand / missing snapshot, {@code executorSupplier} is NEVER called.</li>
     *   <li>{@link RagItemPipeline#run} inside the submitted Runnable is the production body;
     *       tests do not run the captured Runnable.</li>
     * </ul>
     *
     * @param src             source to send user feedback to (may be a real {@link CommandSourceStack} in production
     *                        or a test stub — see {@link #sendFailure}).
     * @param player          invoking player — null in unit tests; passed to {@link Authorizer#authorize}
     *                        (which tolerates null via primitive overload) and to {@link RagItemPipeline#run}.
     * @param item            resolved item (null when {@code isEmptyHand == true}).
     * @param uuid            sender UUID for audit logging.
     * @param isEmptyHand     {@code true} when /forgebook item was called with no args and no held item.
     * @param heldContext     {@code true} for the {@code executeHeld} entry (affects error copy only).
     * @param resourceLookup  Item → ResourceLocation mapper (production: ForgeRegistries.ITEMS.getKey).
     * @param modURLLookup    modId → Optional&lt;URL&gt; mapper (production: ModList → IModInfo.getModURL).
     * @param snapshotSupplier produces the current {@link ConfigSnapshot} (D-14).
     * @param limiterSupplier  produces the current {@link RateLimiter}.
     * @param executorSupplier produces the {@link ExecutorService} for the off-tick submit.
     */
    static int executeInternal(
            CommandSourceStack src,
            ServerPlayer player,
            Item item,
            UUID uuid,
            boolean isEmptyHand,
            boolean heldContext,
            Function<Item, ResourceLocation> resourceLookup,
            Function<String, Optional<URL>> modURLLookup,
            Supplier<ConfigSnapshot> snapshotSupplier,
            Supplier<RateLimiter> limiterSupplier,
            Supplier<ExecutorService> executorSupplier) {

        if (isEmptyHand) {
            sendFailureKey(src, "forgebook.command.item.no_held");
            return 0;
        }

        ResourceLocation rl = resourceLookup.apply(item);
        if (rl == null) {
            sendFailureKey(src, "forgebook.command.item.unknown");
            return 0;
        }
        String modId = rl.getNamespace();
        String itemId = rl.toString();
        Optional<URL> modURL = modURLLookup.apply(modId);

        // SAFE-06 (tick-thread mirror): authorize BEFORE any AiExecutor.submit.
        // D-14: single volatile load of ConfigSnapshot.
        ConfigSnapshot snap = snapshotSupplier.get();
        if (snap == null) {
            sendFailureKey(src, "forgebook.command.not_initialized");
            return 0;
        }
        long startNanos = System.nanoTime();
        Authorizer.Result auth = Authorizer.authorize(
            snap, player, RequestKind.ITEM, limiterSupplier.get());
        if (auth instanceof Authorizer.Denied d) {
            RequestAuditLogger.logDenied(uuid, RequestKind.ITEM, d.code(), startNanos);
            // Phase 5 / REL-02: Denied.feedback carries the Component.translatable.
            // humanReadable is the English fallback forwarded to the test sink.
            if (src != null) {
                src.sendFailure(d.feedback());
            }
            failureSinkForTests.accept(d.humanReadable());
            return 0;
        }

        // Allowed — dispatch to RagItemPipeline on the AI executor.
        // TODO(v2): RagItemPipeline currently calls src.sendSuccess / src.sendFailure
        // synchronously from inside this executor task — those sends therefore run
        // off-tick (Pitfall 2 — off-tick sendSuccess). v2 fix: wrap those calls in
        // src.getServer().execute(...) INSIDE RagItemPipeline. Out of scope for v1;
        // benign except for rare NetworkPipelineException on disconnected players.
        try {
            executorSupplier.get().submit(() -> {
                try {
                    RagItemPipeline.run(src, player, modId, itemId, modURL, RequestKind.ITEM);
                } catch (Exception ex) {
                    LOG.error("RAG pipeline failed for item {} by {}", itemId, uuid, ex);
                    sendFailureKey(src, "forgebook.command.internal_error");
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.warn("aiExecutor rejected RAG submission for {}; returning OVERLOADED.", uuid);
            RequestAuditLogger.logFailure(
                uuid, RequestKind.ITEM, ErrorCode.OVERLOADED, 0, 0, 0L);
            sendFailureKey(src, "forgebook.command.overloaded");
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Phase 5 / REL-02: failure emission that wraps a translation key in
     * {@link Component#translatable(String, Object...)} for the command surface.
     * The key (not the resolved prose) is forwarded to the test sink, so assertions
     * flip from English prose to key strings.
     *
     * Null-safe when {@code src} is null (unit tests). In production {@code src} is a
     * real {@link CommandSourceStack} and this delegates to {@link CommandSourceStack#sendFailure(Component)}.
     */
    private static void sendFailureKey(CommandSourceStack src, String key, Object... args) {
        if (src != null) {
            src.sendFailure(Component.translatable(key, args));
        }
        failureSinkForTests.accept(key);
    }

    /**
     * Test sink — null in production. Unit tests install this in @BeforeEach to capture
     * the text passed to {@link CommandSourceStack#sendFailure} (which production writes to
     * live and tests need to assert against without a real source).
     */
    /* @VisibleForTesting — default is a no-op; tests set and must reset in teardown. */
    static volatile Consumer<String> failureSinkForTests = s -> {};
}
