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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicClientTest {

  private MockWebServer server;
  private AnthropicClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client = new AnthropicClient(
        new OkHttpClient(),
        server.url("/").toString(),
        "test-api-key",
        "sonnet");
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void testStreamingRequest_sendsCorrectHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody("event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"delta\":"
            + "{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n\n"));

    List<String> chunks = new ArrayList<>();
    client.streamMessage("system prompt", List.of(), "sonnet", chunks::add);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-api-key")).isEqualTo("test-api-key");
    assertThat(request.getHeader("anthropic-version")).isEqualTo("2023-06-01");
    assertThat(request.getHeader("Content-Type")).startsWith("application/json");
    assertThat(request.getBody().readUtf8()).contains("\"stream\":true");
  }

  @Test
  void testStreamingRequest_parsesTextChunks() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody("event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"delta\":"
            + "{\"type\":\"text_delta\",\"text\":\"Hello \"}}\n\n"
            + "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"delta\":"
            + "{\"type\":\"text_delta\",\"text\":\"world\"}}\n\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n\n"));

    List<String> chunks = new ArrayList<>();
    client.streamMessage("system prompt", List.of(), "sonnet", chunks::add);

    assertThat(chunks).containsExactly("Hello ", "world").inOrder();
  }

  @Test
  void testStreamingRequest_handlesApiError() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(500)
        .setBody("{\"error\":{\"message\":\"Internal error\"}}"));

    List<String> chunks = new ArrayList<>();
    IOException thrown = assertThrows(IOException.class,
        () -> client.streamMessage("system prompt", List.of(), "sonnet", chunks::add));
    assertThat(thrown.getMessage()).contains("500");
  }

  @Test
  void testStreamingRequest_handlesRateLimit() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(429)
        .setBody("{\"error\":{\"message\":\"Rate limited\"}}"));

    List<String> chunks = new ArrayList<>();
    AnthropicClient.AnthropicRateLimitException thrown =
        assertThrows(AnthropicClient.AnthropicRateLimitException.class,
            () -> client.streamMessage("system prompt", List.of(), "sonnet", chunks::add));
    assertThat(thrown.getMessage()).contains("429");
  }

  @Test
  void testModelMapping() {
    assertThat(AnthropicClient.resolveModelId("haiku")).isEqualTo("claude-haiku-4-5-20251001");
    assertThat(AnthropicClient.resolveModelId("sonnet")).isEqualTo("claude-sonnet-4-6-20250514");
    assertThat(AnthropicClient.resolveModelId("opus")).isEqualTo("claude-opus-4-6-20250514");
  }
}
