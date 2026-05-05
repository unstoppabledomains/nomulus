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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import google.registry.ai.tools.AiTool;
import google.registry.ai.tools.AiToolRegistry;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiOrchestratorComplexityRoutingTest {

  private MockWebServer catalogServer;
  private AnthropicModelCatalog catalog;

  @BeforeEach
  void setUp() throws IOException {
    catalogServer = new MockWebServer();
    catalogServer.start();
    catalogServer.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                "{\"data\":["
                    + "{\"type\":\"model\",\"id\":\"claude-opus-4-6\",\"display_name\":\"Opus\","
                    + "\"created_at\":\"2026-04-01T00:00:00Z\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-sonnet-4-5-20250929\","
                    + "\"display_name\":\"Sonnet\",\"created_at\":\"2026-03-01T00:00:00Z\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-haiku-4-5-20251001\","
                    + "\"display_name\":\"Haiku\",\"created_at\":\"2026-02-01T00:00:00Z\"}"
                    + "]}"));
    catalog =
        new AnthropicModelCatalog(
            new OkHttpClient(), catalogServer.url("/").toString(), "test-api-key", 60);
  }

  @AfterEach
  void tearDown() throws IOException {
    catalogServer.shutdown();
  }

  @Test
  void turnZero_alwaysUsesUserSelectedModel() throws Exception {
    FakeAnthropicClient client = new FakeAnthropicClient();
    // Turn 0 emits no tool calls — orchestrator stops immediately.
    client.queueTurn();

    AiOrchestrator orchestrator =
        new AiOrchestrator(client, registryWith(), catalog, "sonnet", true);

    orchestrator.run("system", List.of(), "opus", fakeUser(), e -> {});

    assertThat(client.modelsUsed).containsExactly("claude-opus-4-6");
  }

  @Test
  void easyTool_routesNextTurnToHaiku() throws Exception {
    FakeAnthropicClient client = new FakeAnthropicClient();
    // Turn 0: model calls "easy_lookup". Turn 1: no more tools (final reply).
    client.queueTurn("easy_lookup");
    client.queueTurn();

    AiToolRegistry registry = registryWith(new StubTool("easy_lookup", AiTool.Complexity.EASY));
    AiOrchestrator orchestrator = new AiOrchestrator(client, registry, catalog, "sonnet", true);

    orchestrator.run("system", List.of(), "opus", fakeUser(), e -> {});

    assertThat(client.modelsUsed)
        .containsExactly("claude-opus-4-6", "claude-haiku-4-5-20251001")
        .inOrder();
  }

  @Test
  void mediumTool_routesNextTurnToSonnet() throws Exception {
    FakeAnthropicClient client = new FakeAnthropicClient();
    client.queueTurn("medium_query");
    client.queueTurn();

    AiToolRegistry registry =
        registryWith(new StubTool("medium_query", AiTool.Complexity.MEDIUM));
    AiOrchestrator orchestrator = new AiOrchestrator(client, registry, catalog, "sonnet", true);

    orchestrator.run("system", List.of(), "opus", fakeUser(), e -> {});

    assertThat(client.modelsUsed)
        .containsExactly("claude-opus-4-6", "claude-sonnet-4-5-20250929")
        .inOrder();
  }

  @Test
  void complexTool_keepsUserSelectedModelOnNextTurn() throws Exception {
    FakeAnthropicClient client = new FakeAnthropicClient();
    client.queueTurn("complex_explore");
    client.queueTurn();

    AiToolRegistry registry =
        registryWith(new StubTool("complex_explore", AiTool.Complexity.COMPLEX));
    AiOrchestrator orchestrator = new AiOrchestrator(client, registry, catalog, "sonnet", true);

    orchestrator.run("system", List.of(), "opus", fakeUser(), e -> {});

    assertThat(client.modelsUsed).containsExactly("claude-opus-4-6", "claude-opus-4-6").inOrder();
  }

  @Test
  void mixedTools_routesByMaxComplexity() throws Exception {
    FakeAnthropicClient client = new FakeAnthropicClient();
    client.queueTurn("easy_lookup", "medium_query");
    client.queueTurn();

    AiToolRegistry registry =
        registryWith(
            new StubTool("easy_lookup", AiTool.Complexity.EASY),
            new StubTool("medium_query", AiTool.Complexity.MEDIUM));
    AiOrchestrator orchestrator = new AiOrchestrator(client, registry, catalog, "sonnet", true);

    orchestrator.run("system", List.of(), "opus", fakeUser(), e -> {});

    // Max of EASY+MEDIUM is MEDIUM → sonnet on turn 1.
    assertThat(client.modelsUsed)
        .containsExactly("claude-opus-4-6", "claude-sonnet-4-5-20250929")
        .inOrder();
  }

  @Test
  void routingDisabled_alwaysUsesUserSelectedModel() throws Exception {
    FakeAnthropicClient client = new FakeAnthropicClient();
    client.queueTurn("easy_lookup");
    client.queueTurn();

    AiToolRegistry registry = registryWith(new StubTool("easy_lookup", AiTool.Complexity.EASY));
    AiOrchestrator orchestrator = new AiOrchestrator(client, registry, catalog, "sonnet", false);

    orchestrator.run("system", List.of(), "opus", fakeUser(), e -> {});

    assertThat(client.modelsUsed).containsExactly("claude-opus-4-6", "claude-opus-4-6").inOrder();
  }

  // -- Helpers ---------------------------------------------------------------

  private static AiToolRegistry registryWith(AiTool... tools) {
    return new AiToolRegistry(ImmutableList.copyOf(tools));
  }

  private static User fakeUser() {
    return new User.Builder()
        .setEmailAddress("test@example.com")
        .setUserRoles(new UserRoles.Builder().build())
        .build();
  }

  /** Captures the model id passed each turn and emits canned tool_use blocks. */
  private static final class FakeAnthropicClient extends AnthropicClient {
    final List<String> modelsUsed = new ArrayList<>();
    final Deque<List<String>> queuedTurns = new ArrayDeque<>();

    FakeAnthropicClient() {
      super(new OkHttpClient(), "http://example.invalid", "test-api-key");
    }

    void queueTurn(String... toolNames) {
      queuedTurns.add(List.of(toolNames));
    }

    @Override
    public StreamResult streamMessageWithTools(
        String systemPrompt,
        JsonArray messages,
        String model,
        JsonArray tools,
        Consumer<StreamEvent> sink) {
      modelsUsed.add(model);
      List<String> toolsThisTurn = queuedTurns.poll();
      if (toolsThisTurn != null) {
        int idx = 0;
        for (String name : toolsThisTurn) {
          sink.accept(new ToolUseBlock("id-" + idx, name, new JsonObject()));
          idx++;
        }
      }
      return new StreamResult("end_turn", new HashMap<>(), 0, 0);
    }
  }

  /** Minimal AiTool that tags itself with a chosen complexity. */
  private static final class StubTool implements AiTool {
    private final String name;
    private final Complexity complexity;

    StubTool(String name, Complexity complexity) {
      this.name = name;
      this.complexity = complexity;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return "stub";
    }

    @Override
    public JsonObject inputSchema() {
      return JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
    }

    @Override
    public Complexity complexity() {
      return complexity;
    }

    @Override
    public JsonElement execute(JsonObject args, User user) {
      return JsonParser.parseString("{\"ok\":true}");
    }
  }
}
