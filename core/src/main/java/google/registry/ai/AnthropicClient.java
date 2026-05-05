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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Hand-rolled OkHttp client for Anthropic's Messages API with streaming + tool-use support.
 *
 * <p>Two streaming modes:
 *
 * <ul>
 *   <li>{@link #streamMessage(String, List, String, Consumer)} — text-only, used by
 *       single-turn analysis without tools.
 *   <li>{@link #streamMessageWithTools} — tool-use aware. Yields {@link StreamEvent} values for
 *       text deltas, tool-use blocks, and message-stop signals; the caller (the orchestrator)
 *       decides whether to execute tools and recurse.
 * </ul>
 */
@Singleton
public class AnthropicClient {

  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final int MAX_TOKENS = 4096;
  private static final Gson GSON = new Gson();

  private final OkHttpClient httpClient;
  private final String baseUrl;
  private final String apiKey;

  @Inject
  public AnthropicClient(
      @Named("anthropicHttpClient") OkHttpClient httpClient,
      @Named("anthropicApiBaseUrl") String baseUrl,
      @Named("anthropicApiKey") String apiKey) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
  }

  /**
   * Streams plain text deltas (no tools). Equivalent to {@link #streamMessageWithTools} called
   * with an empty tool list and a callback that only consumes {@link TextDelta}.
   *
   * @param model fully-qualified Anthropic model id (e.g. {@code claude-sonnet-4-5-20250929}).
   *     Resolve shorthand via {@link AnthropicModelCatalog#resolveModelId} before calling.
   */
  public void streamMessage(
      String systemPrompt,
      List<AiAnalyzeRequest.ConversationMessage> conversationHistory,
      String model,
      Consumer<String> onChunk)
      throws IOException {
    JsonArray messages = new JsonArray();
    if (conversationHistory != null) {
      for (AiAnalyzeRequest.ConversationMessage msg : conversationHistory) {
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("role", msg.role);
        msgObj.addProperty("content", msg.content);
        messages.add(msgObj);
      }
    }
    streamMessageInternal(
        systemPrompt,
        messages,
        model,
        new JsonArray(),
        event -> {
          if (event instanceof TextDelta td) {
            onChunk.accept(td.text());
          }
        });
  }

  /**
   * Tool-use-aware streaming. Caller supplies the full conversation as a {@link JsonArray} of
   * Anthropic message objects (so the orchestrator can append assistant tool_use blocks and user
   * tool_result blocks across turns).
   *
   * @param model fully-qualified Anthropic model id; see {@link #streamMessage}.
   */
  public StreamResult streamMessageWithTools(
      String systemPrompt,
      JsonArray messages,
      String model,
      JsonArray tools,
      Consumer<StreamEvent> sink)
      throws IOException {
    return streamMessageInternal(systemPrompt, messages, model, tools, sink);
  }

  private StreamResult streamMessageInternal(
      String systemPrompt,
      JsonArray messages,
      String model,
      JsonArray tools,
      Consumer<StreamEvent> sink)
      throws IOException {
    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("max_tokens", MAX_TOKENS);
    body.addProperty("stream", true);
    body.addProperty("system", systemPrompt);
    body.add("messages", messages);
    if (tools != null && tools.size() > 0) {
      body.add("tools", tools);
    }

    RequestBody requestBody =
        RequestBody.create(GSON.toJson(body), MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/v1/messages")
            .post(requestBody)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .build();

    try (Response response = httpClient.newCall(request).execute()) {
      if (response.code() == 429) {
        throw new AnthropicRateLimitException("Anthropic API rate limited: " + response.code());
      }
      if (!response.isSuccessful()) {
        throw new IOException("Anthropic API error: " + response.code());
      }

      // Per-turn state: track open content blocks so we can assemble tool_use blocks (which
      // arrive as input_json_delta accumulations).
      Map<Integer, BlockBuilder> blocks = new HashMap<>();
      String stopReason = null;
      int inputTokens = 0;
      int outputTokens = 0;

      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data: ")) {
            continue;
          }
          String data = line.substring(6).trim();
          if (data.equals("[DONE]")) {
            break;
          }
          try {
            JsonObject event = GSON.fromJson(data, JsonObject.class);
            String type = event.has("type") ? event.get("type").getAsString() : "";
            switch (type) {
              case "content_block_start" -> {
                int index = event.has("index") ? event.get("index").getAsInt() : 0;
                JsonObject block = event.getAsJsonObject("content_block");
                String blockType = block.get("type").getAsString();
                BlockBuilder bb = new BlockBuilder(blockType);
                if ("tool_use".equals(blockType)) {
                  bb.toolUseId = block.get("id").getAsString();
                  bb.toolName = block.get("name").getAsString();
                }
                blocks.put(index, bb);
              }
              case "content_block_delta" -> {
                int index = event.has("index") ? event.get("index").getAsInt() : 0;
                JsonObject delta = event.getAsJsonObject("delta");
                String dType = delta.get("type").getAsString();
                BlockBuilder bb =
                    blocks.computeIfAbsent(
                        index,
                        i ->
                            new BlockBuilder(
                                "input_json_delta".equals(dType) ? "tool_use" : "text"));
                if ("text_delta".equals(dType) && delta.has("text")) {
                  String text = delta.get("text").getAsString();
                  bb.text.append(text);
                  sink.accept(new TextDelta(text));
                } else if ("input_json_delta".equals(dType) && delta.has("partial_json")) {
                  bb.partialJson.append(delta.get("partial_json").getAsString());
                }
              }
              case "content_block_stop" -> {
                int index = event.has("index") ? event.get("index").getAsInt() : 0;
                BlockBuilder bb = blocks.get(index);
                if (bb != null && "tool_use".equals(bb.blockType)) {
                  JsonObject input;
                  try {
                    input =
                        bb.partialJson.length() == 0
                            ? new JsonObject()
                            : GSON.fromJson(bb.partialJson.toString(), JsonObject.class);
                  } catch (Exception e) {
                    input = new JsonObject();
                  }
                  sink.accept(new ToolUseBlock(bb.toolUseId, bb.toolName, input));
                }
              }
              case "message_start" -> {
                if (event.has("message") && event.get("message").isJsonObject()) {
                  JsonObject msg = event.getAsJsonObject("message");
                  if (msg.has("usage") && msg.get("usage").isJsonObject()) {
                    JsonObject usage = msg.getAsJsonObject("usage");
                    if (usage.has("input_tokens") && !usage.get("input_tokens").isJsonNull()) {
                      inputTokens = usage.get("input_tokens").getAsInt();
                    }
                    if (usage.has("output_tokens") && !usage.get("output_tokens").isJsonNull()) {
                      outputTokens = usage.get("output_tokens").getAsInt();
                    }
                  }
                }
              }
              case "message_delta" -> {
                if (event.has("delta")) {
                  JsonObject d = event.getAsJsonObject("delta");
                  if (d.has("stop_reason") && !d.get("stop_reason").isJsonNull()) {
                    stopReason = d.get("stop_reason").getAsString();
                  }
                }
                if (event.has("usage") && event.get("usage").isJsonObject()) {
                  JsonObject usage = event.getAsJsonObject("usage");
                  if (usage.has("output_tokens") && !usage.get("output_tokens").isJsonNull()) {
                    outputTokens = usage.get("output_tokens").getAsInt();
                  }
                }
              }
              case "message_stop" -> sink.accept(new MessageStop(stopReason));
              default -> {
                // ignore (ping, etc.)
              }
            }
          } catch (Exception e) {
            // Skip malformed events; don't kill the stream.
          }
        }
      }
      return new StreamResult(stopReason, blocks, inputTokens, outputTokens);
    }
  }

  // -- Stream events (tagged union) -------------------------------------------------------------

  public sealed interface StreamEvent permits TextDelta, ToolUseBlock, MessageStop {}

  public record TextDelta(String text) implements StreamEvent {}

  public record ToolUseBlock(String toolUseId, String name, JsonElement input)
      implements StreamEvent {}

  public record MessageStop(String stopReason) implements StreamEvent {}

  /** Result handed back from a single Anthropic call. */
  public record StreamResult(
      String stopReason, Map<Integer, BlockBuilder> blocks, int inputTokens, int outputTokens) {

    /**
     * Reconstructs the assistant turn's content blocks for appending to the conversation history
     * before the next call (so Anthropic sees its prior tool_use when we send the tool_result).
     */
    public JsonArray assistantContent() {
      JsonArray content = new JsonArray();
      // Iterate by index order to preserve block ordering.
      blocks.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              e -> {
                BlockBuilder bb = e.getValue();
                JsonObject obj = new JsonObject();
                obj.addProperty("type", bb.blockType);
                if ("text".equals(bb.blockType)) {
                  obj.addProperty("text", bb.text.toString());
                } else if ("tool_use".equals(bb.blockType)) {
                  obj.addProperty("id", bb.toolUseId);
                  obj.addProperty("name", bb.toolName);
                  try {
                    obj.add(
                        "input",
                        bb.partialJson.length() == 0
                            ? new JsonObject()
                            : GSON.fromJson(bb.partialJson.toString(), JsonObject.class));
                  } catch (Exception ex) {
                    obj.add("input", new JsonObject());
                  }
                }
                content.add(obj);
              });
      return content;
    }
  }

  /** Per-block accumulator (text for text blocks, partial JSON for tool_use). */
  public static final class BlockBuilder {
    final String blockType;
    final StringBuilder text = new StringBuilder();
    final StringBuilder partialJson = new StringBuilder();
    String toolUseId;
    String toolName;

    BlockBuilder(String blockType) {
      this.blockType = blockType;
    }
  }

  /** Thrown when Anthropic returns 429. */
  public static class AnthropicRateLimitException extends IOException {
    public AnthropicRateLimitException(String message) {
      super(message);
    }
  }
}
