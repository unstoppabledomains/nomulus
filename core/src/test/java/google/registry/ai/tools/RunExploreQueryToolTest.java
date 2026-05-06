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
    String required = schema.getAsJsonArray("required").toString();
    assertThat(required).contains("data_source");
    assertThat(required).contains("tld");
    assertThat(required).contains("metrics");
    // Dates are per-source optional (SRE-1962): required for sources that support dateRange,
    // ignored for current-state sources like DOMAIN_COUNTS.
    assertThat(required).doesNotContain("start_date");
    assertThat(required).doesNotContain("end_date");
    assertThat(schema.getAsJsonObject("properties").getAsJsonObject("data_source").has("enum"))
        .isTrue();
  }

  @Test
  void description_documentsPerSourceDateRequirement() {
    String desc = tool.description();
    // DOMAIN_COUNTS is a current-state snapshot; REVENUE accepts a date range.
    assertThat(desc).contains("DOMAIN_COUNTS");
    assertThat(desc).contains("dates=n/a");
    assertThat(desc).contains("dates=required");
  }

  @Test
  void execute_missingDataSource_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("data_source");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("data_source");
  }

  @Test
  void execute_missingTld_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("tld");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("tld");
  }

  @Test
  void execute_missingMetrics_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("metrics");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("metrics");
  }

  @Test
  void execute_emptyMetrics_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.add("metrics", new com.google.gson.JsonArray());
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("non-empty");
  }

  @Test
  void execute_unknownDataSource_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.addProperty("data_source", "NOT_A_REAL_SOURCE");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).ignoringCase().contains("unknown data_source");
  }

  @Test
  void execute_invalidMetricForSource_returnsInvalidArgs() {
    // REVENUE allows {amount, netAmountToRegistry}. "count" is invalid.
    createTld("tld");
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.addProperty("data_source", "REVENUE");
    com.google.gson.JsonArray metrics = new com.google.gson.JsonArray();
    metrics.add("count");
    argsJson.add("metrics", metrics);
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("Unknown metric");
  }

  @Test
  void execute_invalidDimensionForSource_returnsInvalidArgs() {
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
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("Unknown dimension");
  }

  @Test
  void execute_tldOutsideUserScope_returnsPermissionDenied() {
    // Non-admin user mapped only to "other-tld" — querying "tld" must fail.
    createTld("other-tld");
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = baseArgs();
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.PERMISSION_DENIED);
    assertThat(result.diagnostic()).ignoringCase().contains("permission denied");
  }

  @Test
  void execute_domainCountsWithoutDates_succeeds() {
    // DOMAIN_COUNTS is a current-state snapshot — dates are not required and should not block.
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("data_source", "DOMAIN_COUNTS");
    argsJson.addProperty("tld", "tld");
    com.google.gson.JsonArray metrics = new com.google.gson.JsonArray();
    metrics.add("count");
    argsJson.add("metrics", metrics);
    com.google.gson.JsonArray dims = new com.google.gson.JsonArray();
    dims.add("registrar");
    argsJson.add("dimensions", dims);

    ToolResult result = tool.executeWithStatus(argsJson, user);

    // No domains in the test DB → EMPTY_FOR_RANGE, but crucially NOT INVALID_ARGS for missing
    // dates (the SRE-1962 regression case).
    assertThat(result.status())
        .isAnyOf(ToolResult.Status.OK, ToolResult.Status.EMPTY_FOR_RANGE);
    assertThat(result.data().getAsJsonObject().has("rows")).isTrue();
    // emptyForRange diagnostic should not mention a date range when none was supplied.
    if (result.status() == ToolResult.Status.EMPTY_FOR_RANGE) {
      assertThat(result.diagnostic()).doesNotContain("between");
    }
  }

  @Test
  void execute_domainCountsWithDates_stripsDatesAndAttachesNote() {
    // SRE-1962: when dates are supplied to a source that doesn't accept them, the tool must
    // silently strip the dates and surface a note instead of returning INVALID_ARGS.
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("data_source", "DOMAIN_COUNTS");
    argsJson.addProperty("tld", "tld");
    argsJson.addProperty("start_date", "2026-01-01");
    argsJson.addProperty("end_date", "2026-04-29");
    com.google.gson.JsonArray metrics = new com.google.gson.JsonArray();
    metrics.add("count");
    argsJson.add("metrics", metrics);

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertThat(result.status())
        .isAnyOf(ToolResult.Status.OK, ToolResult.Status.EMPTY_FOR_RANGE);
    JsonObject payload = result.data().getAsJsonObject();
    assertThat(payload.has("notes")).isTrue();
    assertThat(payload.getAsJsonArray("notes").get(0).getAsString())
        .ignoringCase()
        .contains("ignored");
    assertThat(payload.getAsJsonArray("notes").get(0).getAsString())
        .contains("DOMAIN_COUNTS");
  }

  @Test
  void execute_revenueWithoutDates_returnsInvalidArgs() {
    // REVENUE supports dateRange; dates remain required for it.
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("data_source", "REVENUE");
    argsJson.addProperty("tld", "tld");
    com.google.gson.JsonArray metrics = new com.google.gson.JsonArray();
    metrics.add("amount");
    argsJson.add("metrics", metrics);

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("REVENUE");
    assertThat(result.diagnostic()).ignoringCase().contains("required");
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
