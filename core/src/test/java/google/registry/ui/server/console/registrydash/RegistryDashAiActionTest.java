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

package google.registry.ui.server.console.registrydash;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.TestLogHandler;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import google.registry.ai.AiOrchestrator;
import google.registry.ai.AiRateLimiter;
import google.registry.config.RegistryConfigSettings;
import google.registry.model.console.User;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleApiParams;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistryDashAiActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-01-01T00:00:00Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Mock private AiOrchestrator orchestrator;
  private AiRateLimiter rateLimiter;
  private ConsoleApiParams params;
  private FakeResponse response;

  @BeforeEach
  void setUp() {
    User fteUser = DatabaseHelper.createAdminUser("fte@test.com");
    AuthResult authResult = AuthResult.createUser(fteUser);
    params = ConsoleApiParamsUtils.createFake(authResult);
    response = (FakeResponse) params.response();
    rateLimiter = new AiRateLimiter(clock, 120);
    when(params.request().getMethod()).thenReturn("POST");
  }

  @Test
  void testSuccess_streamsResponse() throws Exception {
    String payload =
        "{\"page\":\"domain-activity\",\"promptType\":\"summarize_trends\","
            + "\"chartData\":{\"activity\":[]},\"conversationHistory\":["
            + "{\"role\":\"user\",\"content\":\"Summarize trends\"}"
            + "]}";
    JsonElement json = JsonParser.parseString(payload);

    doAnswer(
            invocation -> {
              Consumer<AiOrchestrator.OrchestratorEvent> sink = invocation.getArgument(4);
              sink.accept(new AiOrchestrator.TextEvent("Hello "));
              sink.accept(new AiOrchestrator.TextEvent("world"));
              sink.accept(new AiOrchestrator.DoneEvent());
              return ImmutableList.of();
            })
        .when(orchestrator)
        .run(any(), any(), any(), any(), any());

    RegistryDashAiAction action =
        new RegistryDashAiAction(
            params, Optional.of(json), orchestrator, rateLimiter, defaultPromptConfig());
    action.run();

    assertThat(response.getStatus()).isEqualTo(200);
    String written = response.getStringWriter().toString();
    assertThat(written).contains("Hello ");
    assertThat(written).contains("world");
    assertThat(written).contains("[DONE]");
  }

  @Test
  void testToolUse_emitsToolFrames() throws Exception {
    String payload =
        "{\"page\":\"domain-activity\",\"promptType\":\"summarize_trends\","
            + "\"chartData\":{},\"conversationHistory\":[]}";
    JsonElement json = JsonParser.parseString(payload);

    doAnswer(
            invocation -> {
              Consumer<AiOrchestrator.OrchestratorEvent> sink = invocation.getArgument(4);
              com.google.gson.JsonObject args = new com.google.gson.JsonObject();
              args.addProperty("tld", "example");
              sink.accept(new AiOrchestrator.ToolUseEvent("query_transfers", args));
              sink.accept(new AiOrchestrator.ToolResultEvent("query_transfers", true));
              sink.accept(new AiOrchestrator.TextEvent("done"));
              sink.accept(new AiOrchestrator.DoneEvent());
              return ImmutableList.of("query_transfers");
            })
        .when(orchestrator)
        .run(any(), any(), any(), any(), any());

    RegistryDashAiAction action =
        new RegistryDashAiAction(
            params, Optional.of(json), orchestrator, rateLimiter, defaultPromptConfig());
    action.run();

    String written = response.getStringWriter().toString();
    assertThat(written).contains("\"type\":\"tool_use\"");
    assertThat(written).contains("\"tool\":\"query_transfers\"");
    assertThat(written).contains("\"type\":\"tool_result\"");
    assertThat(written).contains("\"ok\":true");
  }

  @Test
  void testBadRequest_missingPayload() {
    RegistryDashAiAction action =
        new RegistryDashAiAction(
            params, Optional.empty(), orchestrator, rateLimiter, defaultPromptConfig());
    action.run();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testBadRequest_invalidPage() {
    String payload =
        "{\"page\":\"invalid\",\"promptType\":\"summarize_trends\","
            + "\"chartData\":{},\"conversationHistory\":[]}";
    JsonElement json = JsonParser.parseString(payload);

    RegistryDashAiAction action =
        new RegistryDashAiAction(
            params, Optional.of(json), orchestrator, rateLimiter, defaultPromptConfig());
    action.run();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testSystemPrompt_drawnFromConfig() throws Exception {
    RegistryConfigSettings.Prompts promptConfig = new RegistryConfigSettings.Prompts();
    promptConfig.version = "test-v1";
    promptConfig.basePreamble = "PREAMBLE_FROM_TEST";
    promptConfig.responseGuidance = "GUIDANCE_FROM_TEST";
    promptConfig.toolsHeader = "";
    promptConfig.promptTypes = ImmutableMap.of("summarize_trends", "BODY_FROM_TEST");
    promptConfig.pageHints = ImmutableMap.of("portfolio", "HINT_FROM_TEST");
    promptConfig.menus = ImmutableMap.of();

    String captured = capturedSystemPrompt(promptConfig, "portfolio", "summarize_trends");

    assertThat(captured).contains("PREAMBLE_FROM_TEST");
    assertThat(captured).contains("BODY_FROM_TEST");
    assertThat(captured).contains("HINT_FROM_TEST");
    assertThat(captured).contains("GUIDANCE_FROM_TEST");
  }

  @Test
  void testRequest_logsPromptVersion() throws Exception {
    RegistryConfigSettings.Prompts p = defaultPromptConfig();
    p.version = "logged-version-xyz";

    TestLogHandler handler = new TestLogHandler();
    Logger logger = Logger.getLogger(RegistryDashAiAction.class.getName());
    logger.addHandler(handler);
    try {
      capturedSystemPrompt(p, "domain-activity", "summarize_trends");
      boolean found =
          handler.getStoredLogRecords().stream()
              .anyMatch(
                  r -> {
                    if (r.getParameters() != null) {
                      for (Object param : r.getParameters()) {
                        if ("logged-version-xyz".equals(String.valueOf(param))) {
                          return true;
                        }
                      }
                    }
                    return r.getMessage() != null
                        && r.getMessage().contains("logged-version-xyz");
                  });
      assertThat(found).isTrue();
    } finally {
      logger.removeHandler(handler);
    }
  }

  @Test
  void testRateLimitExceeded() {
    AiRateLimiter strictLimiter = new AiRateLimiter(clock, 0);
    String payload =
        "{\"page\":\"domain-activity\",\"promptType\":\"summarize_trends\","
            + "\"chartData\":{\"activity\":[]},\"conversationHistory\":["
            + "{\"role\":\"user\",\"content\":\"test\"}"
            + "]}";
    JsonElement json = JsonParser.parseString(payload);

    RegistryDashAiAction action =
        new RegistryDashAiAction(
            params, Optional.of(json), orchestrator, strictLimiter, defaultPromptConfig());
    action.run();

    assertThat(response.getStatus()).isEqualTo(429);
  }

  private RegistryConfigSettings.Prompts defaultPromptConfig() {
    RegistryConfigSettings.Prompts p = new RegistryConfigSettings.Prompts();
    p.version = "test-v1";
    p.basePreamble = "You are an expert domain registry analyst.";
    p.responseGuidance = "Be concise.";
    p.toolsHeader = "";
    p.promptTypes =
        ImmutableMap.of(
            "summarize_trends", "Summarize trends.",
            "find_anomalies", "Find anomalies.",
            "suggest_actions", "Suggest actions.",
            "identify_risks", "Identify risks.");
    p.pageHints = ImmutableMap.of();
    p.menus = ImmutableMap.of();
    return p;
  }

  private String capturedSystemPrompt(
      RegistryConfigSettings.Prompts promptConfig, String page, String promptType)
      throws Exception {
    String payload =
        String.format(
            "{\"page\":\"%s\",\"promptType\":\"%s\",\"chartData\":{},\"conversationHistory\":[]}",
            page, promptType);
    JsonElement json = JsonParser.parseString(payload);

    String[] capturedPrompt = new String[1];
    doAnswer(
            invocation -> {
              capturedPrompt[0] = invocation.getArgument(0);
              Consumer<AiOrchestrator.OrchestratorEvent> sink = invocation.getArgument(4);
              sink.accept(new AiOrchestrator.TextEvent("ok"));
              sink.accept(new AiOrchestrator.DoneEvent());
              return ImmutableList.of();
            })
        .when(orchestrator)
        .run(any(), any(), any(), any(), any());

    RegistryDashAiAction action =
        new RegistryDashAiAction(
            params, Optional.of(json), orchestrator, rateLimiter, promptConfig);
    action.run();
    return capturedPrompt[0];
  }
}
