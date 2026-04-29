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
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.ai.tools.AiTool;
import google.registry.ai.tools.AiToolRegistry;
import google.registry.model.console.User;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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

  private final AnthropicClient anthropicClient;
  private final AiToolRegistry registry;

  @Inject
  public AiOrchestrator(AnthropicClient anthropicClient, AiToolRegistry registry) {
    this.anthropicClient = anthropicClient;
    this.registry = registry;
  }

  /**
   * Runs a multi-turn conversation and returns the ordered list of tool names that were invoked.
   * Caller's {@link Consumer} sees every {@link OrchestratorEvent} in order.
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

    for (int turn = 0; turn < MAX_TURNS; turn++) {
      List<PendingToolCall> pending = new ArrayList<>();
      AnthropicClient.StreamResult result =
          anthropicClient.streamMessageWithTools(
              systemPrompt,
              messages,
              modelOverride,
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

      // Execute each tool and append a single user message containing all tool_results.
      JsonArray toolResultsContent = new JsonArray();
      for (PendingToolCall p : pending) {
        toolsUsed.add(p.name);
        JsonObject resultBlock = new JsonObject();
        resultBlock.addProperty("type", "tool_result");
        resultBlock.addProperty("tool_use_id", p.toolUseId);
        Optional<AiTool> maybeTool = registry.get(p.name);
        if (maybeTool.isEmpty()) {
          resultBlock.addProperty("is_error", true);
          resultBlock.addProperty("content", "Unknown tool: " + p.name);
          sink.accept(new ToolResultEvent(p.name, false));
        } else {
          try {
            JsonElement out = maybeTool.get().execute(p.args, user);
            resultBlock.addProperty("content", GSON.toJson(out));
            sink.accept(new ToolResultEvent(p.name, true));
          } catch (AiTool.AiToolException e) {
            resultBlock.addProperty("is_error", true);
            resultBlock.addProperty("content", e.getMessage());
            sink.accept(new ToolResultEvent(p.name, false));
          } catch (RuntimeException e) {
            resultBlock.addProperty("is_error", true);
            resultBlock.addProperty(
                "content", "Tool execution error: " + e.getClass().getSimpleName());
            sink.accept(new ToolResultEvent(p.name, false));
          }
        }
        toolResultsContent.add(resultBlock);
      }

      JsonObject userMsg = new JsonObject();
      userMsg.addProperty("role", "user");
      userMsg.add("content", toolResultsContent);
      messages.add(userMsg);
    }

    // Hit the turn cap. Surface as done.
    sink.accept(new DoneEvent());
    return ImmutableList.copyOf(toolsUsed);
  }

  // -- Events -------------------------------------------------------------------------------

  public sealed interface OrchestratorEvent
      permits TextEvent, ToolUseEvent, ToolResultEvent, DoneEvent {}

  public record TextEvent(String text) implements OrchestratorEvent {}

  public record ToolUseEvent(String tool, JsonObject args) implements OrchestratorEvent {}

  public record ToolResultEvent(String tool, boolean ok) implements OrchestratorEvent {}

  public record DoneEvent() implements OrchestratorEvent {}

  private record PendingToolCall(String toolUseId, String name, JsonObject args) {}
}
