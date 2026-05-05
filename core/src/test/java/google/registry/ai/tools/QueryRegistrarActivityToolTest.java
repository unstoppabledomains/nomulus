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

/** Tests for {@link QueryRegistrarActivityTool}. */
class QueryRegistrarActivityToolTest extends AiToolTestBase {

  private final Clock javaClock =
      Clock.fixed(Instant.parse("2026-04-30T10:00:00.000Z"), ZoneOffset.UTC);
  private final QueryRegistrarActivityTool tool = new QueryRegistrarActivityTool(javaClock);

  @BeforeEach
  void setUp() {
    createTld("tld");
  }

  @Test
  void name_isStable() {
    assertThat(tool.name()).isEqualTo("query_registrar_activity");
  }

  @Test
  void inputSchema_requiresRegistrarId() {
    JsonObject schema = tool.inputSchema();
    assertThat(schema.getAsJsonArray("required").toString()).contains("registrar_id");
  }

  @Test
  void execute_missingRegistrarId_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("registrar_id");
  }

  @Test
  void execute_tldOutsideUserScope_returnsPermissionDenied() {
    createTld("other-tld");
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("registrar_id", "test-registrar");
    argsJson.addProperty("tld", "tld");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.PERMISSION_DENIED);
    assertThat(result.diagnostic()).ignoringCase().contains("permission denied");
  }

  @Test
  void execute_futureDateRange_returnsOutOfRange() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("registrar_id", "registrar1");
    argsJson.addProperty("tld", "tld");
    argsJson.addProperty("start_date", "2027-03-01");
    argsJson.addProperty("end_date", "2027-03-31");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.OUT_OF_RANGE);
  }

  /**
   * Regression test (SRE-1958 review): permission check must run BEFORE the future-date
   * OUT_OF_RANGE fast path, so an unmapped user cannot probe TLD existence by sending a
   * future date and observing OUT_OF_RANGE vs PERMISSION_DENIED.
   */
  @Test
  void execute_unauthorizedTldFutureRange_returnsPermissionDeniedNotOutOfRange() {
    createTld("other-tld");
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("registrar_id", "registrar1");
    argsJson.addProperty("tld", "tld");
    argsJson.addProperty("start_date", "2027-03-01");
    argsJson.addProperty("end_date", "2027-03-31");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.PERMISSION_DENIED);
  }

  @Test
  void execute_emptyForRange_returnsEmptyStatusWithDiagnostic() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("registrar_id", "registrar1");
    argsJson.addProperty("tld", "tld");
    argsJson.addProperty("start_date", "2026-01-01");
    argsJson.addProperty("end_date", "2026-01-31");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.EMPTY_FOR_RANGE);
    assertThat(result.diagnostic()).contains("registrar1");
  }
}
