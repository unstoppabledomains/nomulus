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

package google.registry.ai.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;

import com.google.gson.JsonObject;
import google.registry.model.console.User;
import google.registry.ui.server.console.registrydash.ExploreDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link RunExploreQueryTool}. */
class RunExploreQueryToolTest extends AiToolTestBase {

  // Small maxRows to make truncation easy to assert; statementTimeoutSeconds=0 disables it.
  private final RunExploreQueryTool tool =
      new RunExploreQueryTool(
          /* maxRows= */ 5, /* statementTimeoutSeconds= */ 0);

  @BeforeEach
  void setUp() {
    createTld("tld");
  }

  @Test
  void name_isStable() {
    assertThat(tool.name()).isEqualTo("run_explore_query");
  }

  @Test
  void description_enumeratesAllSevenDataSources() {
    String desc = tool.description();
    for (ExploreDataSource s : ExploreDataSource.values()) {
      assertThat(desc).contains(s.name());
    }
  }

  @Test
  void description_hasTieBreakerLeadIn() {
    assertThat(tool.description())
        .contains("Use this only when no specific tool covers the question");
  }

  @Test
  void inputSchema_hasRequiredFields() {
    JsonObject schema = tool.inputSchema();
    assertThat(schema.getAsJsonArray("required").toString())
        .contains("data_source");
    assertThat(schema.getAsJsonArray("required").toString()).contains("tld");
    assertThat(schema.getAsJsonArray("required").toString()).contains("start_date");
    assertThat(schema.getAsJsonArray("required").toString()).contains("end_date");
    assertThat(schema.getAsJsonArray("required").toString()).contains("metrics");
    assertThat(schema.getAsJsonObject("properties").getAsJsonObject("data_source").has("enum"))
        .isTrue();
  }

  @Test
  void execute_missingDataSource_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("data_source");
    assertAiToolException(() -> tool.execute(argsJson, user), "data_source");
  }

  @Test
  void execute_missingTld_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("tld");
    assertAiToolException(() -> tool.execute(argsJson, user), "tld");
  }

  @Test
  void execute_missingMetrics_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("metrics");
    assertAiToolException(() -> tool.execute(argsJson, user), "metrics");
  }

  @Test
  void execute_emptyMetrics_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.add("metrics", new com.google.gson.JsonArray());
    assertAiToolException(() -> tool.execute(argsJson, user), "non-empty");
  }

  @Test
  void execute_unknownDataSource_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.addProperty("data_source", "NOT_A_REAL_SOURCE");
    assertAiToolException(() -> tool.execute(argsJson, user), "unknown data_source");
  }

  @Test
  void execute_invalidMetricForSource_throws() {
    // REVENUE allows {amount, netAmountToRegistry}. "count" is invalid.
    createTld("tld");
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.addProperty("data_source", "REVENUE");
    com.google.gson.JsonArray metrics = new com.google.gson.JsonArray();
    metrics.add("count");
    argsJson.add("metrics", metrics);
    assertAiToolException(() -> tool.execute(argsJson, user), "Unknown metric");
  }

  @Test
  void execute_invalidDimensionForSource_throws() {
    // REVENUE allows dimensions {tld, operation, period}. "registrar" is invalid.
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.addProperty("data_source", "REVENUE");
    com.google.gson.JsonArray metrics = new com.google.gson.JsonArray();
    metrics.add("amount");
    argsJson.add("metrics", metrics);
    com.google.gson.JsonArray dims = new com.google.gson.JsonArray();
    dims.add("registrar");
    argsJson.add("dimensions", dims);
    assertAiToolException(() -> tool.execute(argsJson, user), "Unknown dimension");
  }

  @Test
  void execute_tldOutsideUserScope_throwsPermissionDenied() {
    // Non-admin user mapped only to "other-tld" — querying "tld" must fail.
    createTld("other-tld");
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = baseArgs();
    assertAiToolException(() -> tool.execute(argsJson, user), "Permission denied");
  }

  /** Minimal valid args for DOMAIN_ACTIVITY. */
  private static JsonObject baseArgs() {
    JsonObject obj = new JsonObject();
    obj.addProperty("data_source", "DOMAIN_ACTIVITY");
    obj.addProperty("tld", "tld");
    obj.addProperty("start_date", "2026-04-01");
    obj.addProperty("end_date", "2026-04-29");
    com.google.gson.JsonArray metrics = new com.google.gson.JsonArray();
    metrics.add("count");
    obj.add("metrics", metrics);
    return obj;
  }
}
