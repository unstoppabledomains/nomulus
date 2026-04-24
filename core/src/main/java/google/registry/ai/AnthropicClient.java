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
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Singleton
public class AnthropicClient {

  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final int MAX_TOKENS = 4096;
  private static final Map<String, String> MODEL_MAP = Map.of(
      "haiku", "claude-haiku-4-5-20251001",
      "sonnet", "claude-sonnet-4-5-20250929",
      "opus", "claude-opus-4-6");
  private static final Gson GSON = new Gson();

  private final OkHttpClient httpClient;
  private final String baseUrl;
  private final String apiKey;
  private final String defaultModel;

  @Inject
  public AnthropicClient(
      @Named("anthropicHttpClient") OkHttpClient httpClient,
      @Named("anthropicApiBaseUrl") String baseUrl,
      @Named("anthropicApiKey") String apiKey,
      @Named("anthropicDefaultModel") String defaultModel) {
    this.httpClient = httpClient;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.defaultModel = defaultModel;
  }

  public void streamMessage(
      String systemPrompt,
      List<AiAnalyzeRequest.ConversationMessage> conversationHistory,
      String modelOverride,
      Consumer<String> onChunk) throws IOException {

    String model = resolveModelId(modelOverride != null ? modelOverride : defaultModel);

    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("max_tokens", MAX_TOKENS);
    body.addProperty("stream", true);
    body.addProperty("system", systemPrompt);

    JsonArray messages = new JsonArray();
    if (conversationHistory != null) {
      for (AiAnalyzeRequest.ConversationMessage msg : conversationHistory) {
        JsonObject msgObj = new JsonObject();
        msgObj.addProperty("role", msg.role);
        msgObj.addProperty("content", msg.content);
        messages.add(msgObj);
      }
    }
    body.add("messages", messages);

    RequestBody requestBody = RequestBody.create(
        GSON.toJson(body), MediaType.parse("application/json"));

    Request request = new Request.Builder()
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

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data: ")) continue;
          String data = line.substring(6).trim();
          if (data.equals("[DONE]")) break;

          try {
            JsonObject event = GSON.fromJson(data, JsonObject.class);
            if (event.has("type")
                && event.get("type").getAsString().equals("content_block_delta")) {
              JsonObject delta = event.getAsJsonObject("delta");
              if (delta.has("text")) {
                onChunk.accept(delta.get("text").getAsString());
              }
            }
          } catch (Exception e) {
            // Skip non-JSON or non-delta lines
          }
        }
      }
    }
  }

  public static String resolveModelId(String shortName) {
    return MODEL_MAP.getOrDefault(shortName, MODEL_MAP.get("sonnet"));
  }

  /** Thrown when Anthropic returns 429. */
  public static class AnthropicRateLimitException extends IOException {
    public AnthropicRateLimitException(String message) {
      super(message);
    }
  }
}
