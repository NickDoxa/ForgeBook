package com.forgebook.ai;

import com.forgebook.ai.dto.ClaudeMessage;
import com.forgebook.ai.dto.ContentBlock;
import com.forgebook.tool.Tool;
import com.forgebook.tool.ToolException;
import com.forgebook.tool.ToolRegistry;
import com.forgebook.util.AiExecutor;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded-iteration state machine that drives the multi-turn tool-use loop (AI-05).
 *
 * Design decisions:
 *  - MAX_ITERATIONS = 6: hard cap enforced by the for-loop bound.
 *  - Parallel tool execution (D-11): all ToolUseBlocks in one turn are submitted
 *    concurrently via AiExecutor; results joined in original order before the next call.
 *  - Error-tolerant tool execution (D-12): per-tool failures are converted to
 *    is_error=true tool_result blocks — the loop continues rather than aborting.
 *  - Provider errors (ProviderError, ITERATION_CAP) propagate directly to the caller.
 *
 * Threading: run() blocks the calling thread (AiExecutor worker) via .join().
 * MUST NOT be called on the server main thread or Forge network thread.
 */
public final class AgentLoop {

    private static final Logger LOG = LogManager.getLogger();
    private static final Gson GSON = new Gson();

    /**
     * Hard cap on provider calls per request (AI-05).
     * Lowered from 6 → 3 to bound interactive chat latency. A typical answer
     * needs: (1) tool selection turn, (2) tool execution + final-answer turn.
     * A third iteration handles the case where the first tool came back empty
     * and the agent wants a second tool. Six iterations routinely pushed chat
     * latency past the 30s UX budget.
     */
    public static final int MAX_ITERATIONS = 3;

    private final AiProvider provider;

    public AgentLoop(AiProvider provider) {
        this.provider = provider;
    }

    /**
     * Run the agent loop starting from the given request.
     *
     * @param initialReq the initial request containing system prompt, model, tools, and the user message
     * @return AiTurn — either a FinalReply (success), ProviderError (provider/network error), or
     *         ProviderError(ITERATION_CAP) if MAX_ITERATIONS was reached
     */
    public AiTurn run(ChatRequest initialReq) {
        List<ClaudeMessage> messages = new ArrayList<>(initialReq.messages());

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            ChatRequest req = new ChatRequest(
                initialReq.model(),
                initialReq.maxTokens(),
                initialReq.system(),
                List.copyOf(messages),
                initialReq.tools()
            );

            AiTurn turn = provider.chat(req).join();

            if (turn instanceof AiTurn.FinalReply r) {
                return r;
            } else if (turn instanceof AiTurn.ProviderError err) {
                return err;
            } else if (turn instanceof AiTurn.ToolUses uses) {
                // Append assistant message with the tool_use blocks
                messages.add(assistantMessage(uses));

                // Execute all tools in parallel (D-11), join in order
                List<JsonObject> results = executeParallel(uses.uses());

                // Append user message with all tool_result blocks
                messages.add(userMessageWithToolResults(results));
                // continue to next iteration
            }
        }

        // Iteration cap reached (AI-05)
        return new AiTurn.ProviderError(
            AiTurn.ProviderError.Kind.ITERATION_CAP,
            "exceeded " + MAX_ITERATIONS + " iterations",
            Optional.empty()
        );
    }

    /**
     * Execute all tool uses in parallel on AiExecutor; return results in original order (D-11).
     */
    private List<JsonObject> executeParallel(List<AiTurn.ToolUseBlock> uses) {
        List<CompletableFuture<JsonObject>> futures = uses.stream()
            .map(use -> CompletableFuture.supplyAsync(() -> invokeTool(use), AiExecutor.get()))
            .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream().map(CompletableFuture::join).toList(); // order preserved (D-11)
    }

    /**
     * Invoke a single tool with full failure isolation (D-12).
     * Catches ALL exceptions and converts them to structured error tool_result blocks.
     */
    private JsonObject invokeTool(AiTurn.ToolUseBlock use) {
        Tool t;
        try {
            t = ToolRegistry.get(use.name());
        } catch (IllegalArgumentException e) {
            // Tool not registered — UNKNOWN_TOOL error
            LOG.warn("Unknown tool requested: {}", use.name());
            return toolResultError(use.id(), "UNKNOWN_TOOL", "no tool registered for: " + use.name());
        }

        try {
            JsonObject inputJson = GSON.toJsonTree(use.input()).getAsJsonObject();
            String out = t.invoke(inputJson);
            return toolResultSuccess(use.id(), out);
        } catch (ToolException te) {
            LOG.warn("tool {} failed: {}", use.name(), te.reason(), te);
            return toolResultError(use.id(), te.reason().name(), te.getMessage());
        } catch (Exception e) {
            LOG.error("tool {} threw unexpected exception", use.name(), e);
            return toolResultError(use.id(), "FETCH_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Build a successful tool_result JSON object.
     * Shape: { type: "tool_result", tool_use_id: id, content: text }
     */
    private static JsonObject toolResultSuccess(String id, String text) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "tool_result");
        obj.addProperty("tool_use_id", id);
        obj.addProperty("content", text);
        return obj;
    }

    /**
     * Build an error tool_result JSON object (D-12).
     * Shape: { type: "tool_result", tool_use_id: id, content: {"error": code, "detail": detail}, is_error: true }
     */
    private static JsonObject toolResultError(String id, String code, String detail) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "tool_result");
        obj.addProperty("tool_use_id", id);
        // Content as JSON string encoding {"error": code, "detail": detail}
        JsonObject contentObj = new JsonObject();
        contentObj.addProperty("error", code);
        contentObj.addProperty("detail", detail);
        obj.addProperty("content", contentObj.toString());
        obj.addProperty("is_error", true);
        return obj;
    }

    /**
     * Build an assistant message containing the tool_use blocks from an AiTurn.ToolUses.
     * Anthropic protocol: when the model returns tool_use blocks, the next turn must include
     * the assistant message that produced them (RESEARCH §1.4).
     */
    private static ClaudeMessage assistantMessage(AiTurn.ToolUses uses) {
        JsonArray blocks = new JsonArray();
        for (AiTurn.ToolUseBlock use : uses.uses()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_use");
            block.addProperty("id", use.id());
            block.addProperty("name", use.name());
            block.add("input", GSON.toJsonTree(use.input()));
            blocks.add(block);
        }
        return new ClaudeMessage("assistant", blocks);
    }

    /**
     * Build a user message containing all tool_result blocks (one per tool use).
     * The results list is in original tool_use order (D-11 guarantees this).
     */
    private static ClaudeMessage userMessageWithToolResults(List<JsonObject> results) {
        JsonArray blocks = new JsonArray();
        for (JsonObject result : results) {
            blocks.add(result);
        }
        return new ClaudeMessage("user", blocks);
    }
}
