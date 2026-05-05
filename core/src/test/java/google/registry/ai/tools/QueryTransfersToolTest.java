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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link QueryTransfersTool}. */
class QueryTransfersToolTest extends AiToolTestBase {

  // Java Clock pinned to the same instant as the AiToolTestBase FakeClock (2026-04-30T10:00Z).
  private final Clock javaClock =
      Clock.fixed(Instant.parse("2026-04-30T10:00:00.000Z"), ZoneOffset.UTC);
  private final QueryTransfersTool tool = new QueryTransfersTool(javaClock);

  @BeforeEach
  void setUp() {
    createTld("tld");
  }

  @Test
  void name_isStable() {
    assertThat(tool.name()).isEqualTo("query_transfers");
  }

  @Test
  void inputSchema_requiresTldAndDates() {
    JsonObject schema = tool.inputSchema();
    String required = schema.getAsJsonArray("required").toString();
    assertThat(required).contains("tld");
    assertThat(required).contains("start_date");
    assertThat(required).contains("end_date");
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
  void execute_missingStartDate_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("start_date");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("start_date");
  }

  @Test
  void execute_missingEndDate_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("end_date");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("end_date");
  }

  @Test
  void execute_tldOutsideUserScope_returnsPermissionDenied() {
    createTld("other-tld");
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = baseArgs();
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.PERMISSION_DENIED);
    assertThat(result.diagnostic()).ignoringCase().contains("permission denied");
  }

  /**
   * Regression test: previously the tool returned an empty {rows: []} for "March 2027", and the
   * LLM looped retrying. Now we recognise the request as past the latest data and return
   * OUT_OF_RANGE on a single round-trip.
   */
  @Test
  void execute_futureDateRange_returnsOutOfRange_march2027NoLoop() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.addProperty("start_date", "2027-03-01");
    argsJson.addProperty("end_date", "2027-03-31");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.OUT_OF_RANGE);
    assertThat(result.diagnostic()).contains("2027-03");
  }

  /**
   * Regression test (SRE-1958 review): the permission check must run BEFORE the
   * future-date OUT_OF_RANGE fast path, so an unmapped user cannot probe whether a TLD
   * exists by sending a future date and observing OUT_OF_RANGE vs PERMISSION_DENIED.
   */
  @Test
  void execute_unauthorizedTldFutureRange_returnsPermissionDeniedNotOutOfRange() {
    createTld("other-tld");
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = baseArgs();
    argsJson.addProperty("start_date", "2027-03-01");
    argsJson.addProperty("end_date", "2027-03-31");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.PERMISSION_DENIED);
  }

  @Test
  void execute_emptyForRange_returnsEmptyStatusWithDiagnostic() {
    User user = createFteUser("admin@example.com");
    // Valid range (in the past) but no data has been persisted in this test → EMPTY_FOR_RANGE.
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("tld", "tld");
    argsJson.addProperty("start_date", "2026-01-01");
    argsJson.addProperty("end_date", "2026-01-31");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
    assertThat(result.diagnostic()).contains("tld");
  }

  private static JsonObject baseArgs() {
    JsonObject obj = new JsonObject();
    obj.addProperty("tld", "tld");
    obj.addProperty("start_date", "2026-04-01");
    obj.addProperty("end_date", "2026-04-29");
    return obj;
  }
}
