// Copyright 2026 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.ai;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import google.registry.ai.tools.AiTool;
import google.registry.ai.tools.AiToolRegistry;
import google.registry.ai.tools.ToolResult;
import google.registry.model.console.User;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Multi-turn loop that lets Claude call backend {@link AiTool}s mid-conversation.
 *
 * <p>Per turn: send conversation + tool definitions to Anthropic; for each {@code tool_use} block
 * that comes back, execute the matching tool and append a {@code tool_result} message to the
 * conversation; resend. Stop when Anthropic emits {@code stop_reason = end_turn} or when the turn
 * cap is hit.
 *
 * <p>Streams text deltas and tool-use signals to the caller via the supplied {@link Consumer}.
 */
@Singleton
public class AiOrchestrator {

  private static final int MAX_TURNS = 5;
  private static final Gson GSON = new Gson();
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final AnthropicClient anthropicClient;
  private final AiToolRegistry registry;
  private final AnthropicModelCatalog catalog;
  private final String defaultModelShorthand;
  private final boolean complexityRoutingEnabled;

  @Inject
  public AiOrchestrator(
      AnthropicClient anthropicClient,
      AiToolRegistry registry,
      AnthropicModelCatalog catalog,
      @Named("anthropicDefaultModel") String defaultModelShorthand,
      @Named("complexityRoutingEnabled") boolean complexityRoutingEnabled) {
    this.anthropicClient = anthropicClient;
    this.registry = registry;
    this.catalog = catalog;
    this.defaultModelShorthand = defaultModelShorthand;
    this.complexityRoutingEnabled = complexityRoutingEnabled;
  }

  /**
   * Runs a multi-turn conversation and returns the ordered list of tool names that were invoked.
   * Caller's {@link Consumer} sees every {@link OrchestratorEvent} in order.
   *
   * <p>Turn 0 always runs on the user-selected model (so the initial user-facing reply uses what
   * the user picked). Subsequent turns may be routed to a cheaper model based on the max
   * complexity of tools executed in the prior turn (see {@link AiTool#complexity}).
   */
  public ImmutableList<String> run(
      String systemPrompt,
      List<AiAnalyzeRequest.ConversationMessage> initialHistory,
      String modelOverride,
      User user,
      Consumer<OrchestratorEvent> sink)
      throws IOException {

    JsonArray messages = new JsonArray();
    if (initialHistory != null) {
      for (AiAnalyzeRequest.ConversationMessage msg : initialHistory) {
        JsonObject obj = new JsonObject();
        obj.addProperty("role", msg.role);
        obj.addProperty("content", msg.content);
        messages.add(obj);
      }
    }

    JsonArray tools = registry.anthropicToolDefinitions();
    List<String> toolsUsed = new ArrayList<>();

    String userSelectedShorthand = modelOverride != null ? modelOverride : defaultModelShorthand;
    String userSelectedModelId =
        catalog
            .resolveModelId(userSelectedShorthand)
            .or(() -> catalog.resolveModelId(defaultModelShorthand))
            .orElseThrow(
                () -> new IllegalStateException("No usable Anthropic model available in catalog"));

    AiTool.Complexity prevTurnMaxComplexity = null;

    for (int turn = 0; turn < MAX_TURNS; turn++) {
      String turnModelId =
          (turn == 0 || prevTurnMaxComplexity == null)
              ? userSelectedModelId
              : routeForComplexity(prevTurnMaxComplexity, userSelectedModelId);

      List<PendingToolCall> pending = new ArrayList<>();
      AnthropicClient.StreamResult result =
          anthropicClient.streamMessageWithTools(
              systemPrompt,
              messages,
              turnModelId,
              tools,
              event -> {
                if (event instanceof AnthropicClient.TextDelta td) {
                  sink.accept(new TextEvent(td.text()));
                } else if (event instanceof AnthropicClient.ToolUseBlock tu) {
                  JsonObject argsObj =
                      tu.input() instanceof JsonObject jo ? jo : new JsonObject();
                  sink.accept(new ToolUseEvent(tu.name(), argsObj));
                  pending.add(new PendingToolCall(tu.toolUseId(), tu.name(), argsObj));
                }
              });

      List<String> toolNamesThisTurn = pending.stream().map(p -> p.name).toList();
      logger.atInfo().log(
          "AI turn=%d, model=%s, prevMaxComplexity=%s, tools=%s,"
              + " inputTokens=%d, outputTokens=%d",
          turn,
          turnModelId,
          prevTurnMaxComplexity,
          toolNamesThisTurn,
          result.inputTokens(),
          result.outputTokens());

      if (pending.isEmpty()) {
        // No tool calls this turn — Claude is done.
        sink.accept(new DoneEvent());
        return ImmutableList.copyOf(toolsUsed);
      }

      // Append the assistant turn (containing the tool_use blocks) to the conversation.
      JsonObject assistantMsg = new JsonObject();
      assistantMsg.addProperty("role", "assistant");
      assistantMsg.add("content", result.assistantContent());
      messages.add(assistantMsg);

      AiTool.Complexity turnMax = AiTool.Complexity.EASY;
      // Execute each tool and append a single user message containing all tool_results.
      JsonArray toolResultsContent = new JsonArray();
      for (PendingToolCall p : pending) {
        toolsUsed.add(p.name);
        JsonObject resultBlock = new JsonObject();
        resultBlock.addProperty("type", "tool_result");
        resultBlock.addProperty("tool_use_id", p.toolUseId);
        Optional<AiTool> maybeTool = registry.get(p.name);
        ToolResult toolResult;
        if (maybeTool.isEmpty()) {
          toolResult = ToolResult.invalidArgs("Unknown tool: " + p.name);
        } else {
          AiTool tool = maybeTool.get();
          turnMax = maxComplexity(turnMax, tool.complexity());
          try {
            toolResult = tool.executeWithStatus(p.args, user);
          } catch (AiTool.AiToolException e) {
            // Tools should now return a typed ToolResult instead of throwing for user-visible
            // failures (bad args, permission denied, etc.). A throw here is a backstop for
            // genuinely unexpected runtime conditions; surface as INTERNAL_ERROR.
            toolResult = ToolResult.internalError(sanitize(e.getMessage()));
          } catch (RuntimeException e) {
            // Don't leak stack traces to Claude — sanitize to a short, opaque message.
            toolResult =
                ToolResult.internalError("Tool execution error: " + e.getClass().getSimpleName());
          }
        }
        if (toolResult.isError()) {
          resultBlock.addProperty("is_error", true);
        }
        resultBlock.addProperty("content", GSON.toJson(toolResult.toJson()));
        sink.accept(
            new ToolResultEvent(p.name, !toolResult.isError(), toolResult.status(),
                toolResult.diagnostic()));
        toolResultsContent.add(resultBlock);
      }
      prevTurnMaxComplexity = turnMax;

      JsonObject userMsg = new JsonObject();
      userMsg.addProperty("role", "user");
      userMsg.add("content", toolResultsContent);
      messages.add(userMsg);
    }

    // Hit the turn cap. Surface as done.
    sink.accept(new DoneEvent());
    return ImmutableList.copyOf(toolsUsed);
  }

  /**
   * Picks the model id for a post-tool synthesis turn given the max complexity of tools just
   * executed. EASY → haiku family, MEDIUM → sonnet family, COMPLEX → fall through to the
   * user-selected model. If the routing flag is off, always returns the user-selected model.
   */
  private String routeForComplexity(AiTool.Complexity max, String userSelectedModelId) {
    if (!complexityRoutingEnabled) {
      return userSelectedModelId;
    }
    String shorthand =
        switch (max) {
          case EASY -> "haiku";
          case MEDIUM -> "sonnet";
          case COMPLEX -> null;
        };
    if (shorthand == null) {
      return userSelectedModelId;
    }
    return catalog.resolveModelId(shorthand).orElse(userSelectedModelId);
  }

  private static AiTool.Complexity maxComplexity(AiTool.Complexity a, AiTool.Complexity b) {
    return a.ordinal() >= b.ordinal() ? a : b;
  }

  // -- Events -------------------------------------------------------------------------------

  public sealed interface OrchestratorEvent
      permits TextEvent, ToolUseEvent, ToolResultEvent, DoneEvent {}

  public record TextEvent(String text) implements OrchestratorEvent {}

  public record ToolUseEvent(String tool, JsonObject args) implements OrchestratorEvent {}

  public record ToolResultEvent(
      String tool, boolean ok, ToolResult.Status status, @Nullable String diagnostic)
      implements OrchestratorEvent {}

  public record DoneEvent() implements OrchestratorEvent {}

  private record PendingToolCall(String toolUseId, String name, JsonObject args) {}

  private static String sanitize(String message) {
    if (message == null || message.isEmpty()) {
      return "Tool failed";
    }
    // Cap to a reasonable length so we don't echo a giant message back to Claude.
    return message.length() > 240 ? message.substring(0, 240) : message;
  }
}
