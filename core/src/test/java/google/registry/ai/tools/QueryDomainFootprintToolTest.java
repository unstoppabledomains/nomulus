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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import google.registry.model.console.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link QueryDomainFootprintTool}. */
class QueryDomainFootprintToolTest extends AiToolTestBase {

  private final QueryDomainFootprintTool tool = new QueryDomainFootprintTool();

  @BeforeEach
  void setUp() {
    createTld("tld");
    createTld("other-tld");
  }

  @Test
  void name_isStable() {
    assertThat(tool.name()).isEqualTo("query_domain_footprint");
  }

  @Test
  void description_mentionsCurrentStateAndNoDates() {
    String desc = tool.description();
    assertThat(desc).ignoringCase().contains("current-state");
    assertThat(desc).ignoringCase().contains("no date");
  }

  @Test
  void inputSchema_hasNoRequiredFields() {
    JsonObject schema = tool.inputSchema();
    assertThat(schema.getAsJsonArray("required")).hasSize(0);
    JsonObject props = schema.getAsJsonObject("properties");
    assertThat(props.has("tlds")).isTrue();
    assertThat(props.has("registrar_ids")).isTrue();
    assertThat(props.has("group_by")).isTrue();
    assertThat(props.has("limit")).isTrue();
    JsonArray groupByEnum = props.getAsJsonObject("group_by").getAsJsonArray("enum");
    assertThat(groupByEnum.toString()).contains("registrar");
    assertThat(groupByEnum.toString()).contains("tld");
    assertThat(groupByEnum.toString()).contains("both");
  }

  @Test
  void execute_admin_defaultGroupByBoth_emptyForRange() {
    // No domains in test DB → empty result, but the tool must succeed (not INVALID_ARGS).
    User user = createFteUser("admin@example.com");

    ToolResult result = tool.executeWithStatus(new JsonObject(), user);

    assertToolResultStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
    JsonObject payload = result.data().getAsJsonObject();
    assertThat(payload.has("rows")).isTrue();
    assertThat(payload.get("totalDomains").getAsLong()).isEqualTo(0L);
  }

  @Test
  void execute_admin_groupByRegistrar_emptyForRange() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("group_by", "registrar");

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertToolResultStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
  }

  @Test
  void execute_admin_groupByTld_emptyForRange() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("group_by", "tld");

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertToolResultStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
  }

  @Test
  void execute_invalidGroupBy_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("group_by", "registrar_id");

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).ignoringCase().contains("invalid group_by");
  }

  @Test
  void execute_negativeLimit_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("limit", -5);

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("limit");
  }

  @Test
  void execute_unauthorizedTld_returnsPermissionDenied() {
    // Non-admin mapped only to "other-tld" — requesting "tld" must fail.
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = new JsonObject();
    JsonArray tlds = new JsonArray();
    tlds.add("tld");
    argsJson.add("tlds", tlds);

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertToolResultStatus(result, ToolResult.Status.PERMISSION_DENIED);
    assertThat(result.diagnostic()).ignoringCase().contains("permission denied");
  }

  @Test
  void execute_admin_tldsFilterSubset_runsScopedQuery() {
    // Admin filtering to one TLD; still empty (no domains) but tool must succeed.
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    JsonArray tlds = new JsonArray();
    tlds.add("tld");
    argsJson.add("tlds", tlds);

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertToolResultStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
  }

  @Test
  void execute_explicitLimit_returnsPayloadWithRowCount() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("limit", 5);

    ToolResult result = tool.executeWithStatus(argsJson, user);

    assertToolResultStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
    JsonObject payload = result.data().getAsJsonObject();
    assertThat(payload.get("rowCount").getAsInt()).isEqualTo(0);
    assertThat(payload.has("totalDomains")).isTrue();
  }
}
