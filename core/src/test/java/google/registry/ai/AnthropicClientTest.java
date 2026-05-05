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

  private static final String SONNET_ID = "claude-sonnet-4-5-20250929";

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    client = new AnthropicClient(new OkHttpClient(), server.url("/").toString(), "test-api-key");
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
    client.streamMessage("system prompt", List.of(), SONNET_ID, chunks::add);

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-api-key")).isEqualTo("test-api-key");
    assertThat(request.getHeader("anthropic-version")).isEqualTo("2023-06-01");
    assertThat(request.getHeader("Content-Type")).startsWith("application/json");
    String body = request.getBody().readUtf8();
    assertThat(body).contains("\"stream\":true");
    assertThat(body).contains("\"model\":\"" + SONNET_ID + "\"");
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
    client.streamMessage("system prompt", List.of(), SONNET_ID, chunks::add);

    assertThat(chunks).containsExactly("Hello ", "world").inOrder();
  }

  @Test
  void testStreamingRequest_handlesApiError() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(500)
        .setBody("{\"error\":{\"message\":\"Internal error\"}}"));

    List<String> chunks = new ArrayList<>();
    IOException thrown = assertThrows(IOException.class,
        () -> client.streamMessage("system prompt", List.of(), SONNET_ID, chunks::add));
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
            () -> client.streamMessage("system prompt", List.of(), SONNET_ID, chunks::add));
    assertThat(thrown.getMessage()).contains("429");
  }

  @Test
  void testStreamingRequest_capturesTokenUsage() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody("event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":"
            + "{\"usage\":{\"input_tokens\":42,\"output_tokens\":1}}}\n\n"
            + "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"delta\":"
            + "{\"type\":\"text_delta\",\"text\":\"hi\"}}\n\n"
            + "event: message_delta\n"
            + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":7}}\n\n"
            + "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n\n"));

    AnthropicClient.StreamResult result = client.streamMessageWithTools(
        "system prompt", new com.google.gson.JsonArray(), SONNET_ID,
        new com.google.gson.JsonArray(), e -> {});

    assertThat(result.inputTokens()).isEqualTo(42);
    assertThat(result.outputTokens()).isEqualTo(7);
    assertThat(result.stopReason()).isEqualTo("end_turn");
  }
}
