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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import google.registry.ai.AiRateLimiter;
import google.registry.ai.AnthropicClient;
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
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RegistryDashAiActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2026-01-01T00:00:00Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Mock private AnthropicClient anthropicClient;
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
    String payload = "{\"page\":\"domain-activity\",\"promptType\":\"summarize_trends\","
        + "\"chartData\":{\"activity\":[]},\"conversationHistory\":["
        + "{\"role\":\"user\",\"content\":\"Summarize trends\"}"
        + "]}";
    JsonElement json = JsonParser.parseString(payload);

    doAnswer(invocation -> {
      Consumer<String> onChunk = invocation.getArgument(3);
      onChunk.accept("Hello ");
      onChunk.accept("world");
      return null;
    }).when(anthropicClient).streamMessage(any(), any(), any(), any());

    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.of(json), anthropicClient, rateLimiter);
    action.run();

    assertThat(response.getStatus()).isEqualTo(200);
    String written = response.getStringWriter().toString();
    assertThat(written).contains("Hello ");
    assertThat(written).contains("world");
    assertThat(written).contains("[DONE]");
  }

  @Test
  void testBadRequest_missingPayload() {
    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.empty(), anthropicClient, rateLimiter);
    action.run();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testBadRequest_invalidPage() {
    String payload = "{\"page\":\"invalid\",\"promptType\":\"summarize_trends\","
        + "\"chartData\":{},\"conversationHistory\":[]}";
    JsonElement json = JsonParser.parseString(payload);

    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.of(json), anthropicClient, rateLimiter);
    action.run();

    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testRateLimitExceeded() {
    AiRateLimiter strictLimiter = new AiRateLimiter(clock, 0);
    String payload = "{\"page\":\"domain-activity\",\"promptType\":\"summarize_trends\","
        + "\"chartData\":{\"activity\":[]},\"conversationHistory\":["
        + "{\"role\":\"user\",\"content\":\"test\"}"
        + "]}";
    JsonElement json = JsonParser.parseString(payload);

    RegistryDashAiAction action = new RegistryDashAiAction(
        params, Optional.of(json), anthropicClient, strictLimiter);
    action.run();

    assertThat(response.getStatus()).isEqualTo(429);
  }
}
