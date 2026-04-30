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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link QueryTransfersTool}. */
class QueryTransfersToolTest extends AiToolTestBase {

  private final QueryTransfersTool tool = new QueryTransfersTool();

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
  void execute_missingTld_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("tld");
    assertAiToolException(() -> tool.execute(argsJson, user), "tld");
  }

  @Test
  void execute_missingStartDate_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("start_date");
    assertAiToolException(() -> tool.execute(argsJson, user), "start_date");
  }

  @Test
  void execute_missingEndDate_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = baseArgs();
    argsJson.remove("end_date");
    assertAiToolException(() -> tool.execute(argsJson, user), "end_date");
  }

  @Test
  void execute_tldOutsideUserScope_throwsPermissionDenied() {
    createTld("other-tld");
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = baseArgs();
    assertAiToolException(() -> tool.execute(argsJson, user), "Permission denied");
  }

  private static JsonObject baseArgs() {
    JsonObject obj = new JsonObject();
    obj.addProperty("tld", "tld");
    obj.addProperty("start_date", "2026-04-01");
    obj.addProperty("end_date", "2026-04-29");
    return obj;
  }
}
