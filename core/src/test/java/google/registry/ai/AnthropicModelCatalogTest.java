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
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.Optional;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicModelCatalogTest {

  private MockWebServer server;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  @Test
  void resolveFromData_groupsByFamilyAndCapsAtThree() {
    JsonArray data =
        JsonParser.parseString(
                "["
                    + "{\"type\":\"model\",\"id\":\"claude-opus-4-6\","
                    + "\"display_name\":\"Opus 4.6\",\"created_at\":\"2026-04-01T00:00:00Z\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-opus-4-5\","
                    + "\"display_name\":\"Opus 4.5\",\"created_at\":\"2026-01-01T00:00:00Z\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-opus-4-4\","
                    + "\"display_name\":\"Opus 4.4\",\"created_at\":\"2025-10-01T00:00:00Z\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-opus-4-3\","
                    + "\"display_name\":\"Opus 4.3\",\"created_at\":\"2025-07-01T00:00:00Z\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-sonnet-4-5-20250929\","
                    + "\"display_name\":\"Sonnet 4.5\",\"created_at\":\"2026-03-01T00:00:00Z\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-haiku-4-5-20251001\","
                    + "\"display_name\":\"Haiku 4.5\",\"created_at\":\"2026-02-01T00:00:00Z\"}"
                    + "]")
            .getAsJsonArray();

    ImmutableMap<String, ImmutableList<AnthropicModelCatalog.ModelInfo>> result =
        AnthropicModelCatalog.resolveFromData(data);

    assertThat(result.keySet()).containsExactly("opus", "sonnet", "haiku");
    // Top 3 newest opus, in newest-first order.
    assertThat(result.get("opus").stream().map(AnthropicModelCatalog.ModelInfo::id).toList())
        .containsExactly("claude-opus-4-6", "claude-opus-4-5", "claude-opus-4-4")
        .inOrder();
    assertThat(result.get("sonnet")).hasSize(1);
    assertThat(result.get("haiku")).hasSize(1);
  }

  @Test
  void resolveFromData_dropsNonGaIds() {
    JsonArray data =
        JsonParser.parseString(
                "["
                    + "{\"type\":\"model\",\"id\":\"claude-opus-4-6\","
                    + "\"display_name\":\"Opus 4.6\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-opus-4-7-beta\","
                    + "\"display_name\":\"Opus 4.7 beta\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-sonnet-4-6-preview\","
                    + "\"display_name\":\"Sonnet 4.6 preview\"},"
                    + "{\"type\":\"model\",\"id\":\"claude-haiku-5-experimental\","
                    + "\"display_name\":\"Haiku exp\"}"
                    + "]")
            .getAsJsonArray();

    ImmutableMap<String, ImmutableList<AnthropicModelCatalog.ModelInfo>> result =
        AnthropicModelCatalog.resolveFromData(data);

    assertThat(result.get("opus").stream().map(AnthropicModelCatalog.ModelInfo::id).toList())
        .containsExactly("claude-opus-4-6");
    assertThat(result.get("sonnet")).isEmpty();
    assertThat(result.get("haiku")).isEmpty();
  }

  @Test
  void resolveFromData_skipsNonClaudeAndNonModelEntries() {
    JsonArray data =
        JsonParser.parseString(
                "["
                    + "{\"type\":\"model\",\"id\":\"claude-opus-4-6\"},"
                    + "{\"type\":\"model\",\"id\":\"gpt-4o\"},"
                    + "{\"type\":\"deprecated\",\"id\":\"claude-opus-3\"}"
                    + "]")
            .getAsJsonArray();

    ImmutableMap<String, ImmutableList<AnthropicModelCatalog.ModelInfo>> result =
        AnthropicModelCatalog.resolveFromData(data);

    assertThat(result.get("opus").stream().map(AnthropicModelCatalog.ModelInfo::id).toList())
        .containsExactly("claude-opus-4-6");
  }

  @Test
  void fetchAndCache_fetchesOnceThenServesFromCache() throws Exception {
    server.enqueue(modelsResponse());
    AnthropicModelCatalog catalog = newCatalog(60);

    ImmutableMap<String, ImmutableList<AnthropicModelCatalog.ModelInfo>> first =
        catalog.currentCatalog();
    ImmutableMap<String, ImmutableList<AnthropicModelCatalog.ModelInfo>> second =
        catalog.currentCatalog();

    assertThat(first.get("opus").get(0).id()).isEqualTo("claude-opus-4-6");
    assertThat(second).isEqualTo(first);
    assertThat(server.getRequestCount()).isEqualTo(1);
  }

  @Test
  void forceRefresh_invalidatesCacheSoNextReadFetches() throws Exception {
    server.enqueue(modelsResponse());
    server.enqueue(modelsResponse());
    AnthropicModelCatalog catalog = newCatalog(60);

    catalog.currentCatalog();
    catalog.forceRefresh();
    catalog.currentCatalog();

    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void resolveModelId_returnsNewestForFamilyShorthand() throws Exception {
    server.enqueue(modelsResponse());
    AnthropicModelCatalog catalog = newCatalog(60);

    Optional<String> resolved = catalog.resolveModelId("opus");

    assertThat(resolved).hasValue("claude-opus-4-6");
  }

  @Test
  void resolveModelId_passesThroughFullyQualifiedClaudeIds() throws Exception {
    server.enqueue(modelsResponse());
    AnthropicModelCatalog catalog = newCatalog(60);

    Optional<String> resolved = catalog.resolveModelId("claude-opus-9-9-20991231");

    assertThat(resolved).hasValue("claude-opus-9-9-20991231");
  }

  @Test
  void fetchFailure_fallsBackToSeedSoChatStaysWorking() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
    AnthropicModelCatalog catalog = newCatalog(60);

    ImmutableMap<String, ImmutableList<AnthropicModelCatalog.ModelInfo>> result =
        catalog.currentCatalog();

    // Seed has at least one entry per family.
    assertThat(result.get("opus")).isNotEmpty();
    assertThat(result.get("sonnet")).isNotEmpty();
    assertThat(result.get("haiku")).isNotEmpty();
  }

  private AnthropicModelCatalog newCatalog(int ttlMinutes) {
    return new AnthropicModelCatalog(
        new OkHttpClient(), server.url("/").toString(), "test-api-key", ttlMinutes);
  }

  private static MockResponse modelsResponse() {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            "{\"data\":["
                + "{\"type\":\"model\",\"id\":\"claude-opus-4-6\","
                + "\"display_name\":\"Opus 4.6\",\"created_at\":\"2026-04-01T00:00:00Z\"},"
                + "{\"type\":\"model\",\"id\":\"claude-sonnet-4-5-20250929\","
                + "\"display_name\":\"Sonnet 4.5\",\"created_at\":\"2026-03-01T00:00:00Z\"},"
                + "{\"type\":\"model\",\"id\":\"claude-haiku-4-5-20251001\","
                + "\"display_name\":\"Haiku 4.5\",\"created_at\":\"2026-02-01T00:00:00Z\"}"
                + "]}");
  }
}
