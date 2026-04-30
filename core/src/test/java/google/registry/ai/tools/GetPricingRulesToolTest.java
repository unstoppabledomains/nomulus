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

/** Tests for {@link GetPricingRulesTool}. */
class GetPricingRulesToolTest extends AiToolTestBase {

  private final GetPricingRulesTool tool = new GetPricingRulesTool();

  @BeforeEach
  void setUp() {
    createTld("tld");
  }

  @Test
  void name_isStable() {
    assertThat(tool.name()).isEqualTo("get_pricing_rules");
  }

  @Test
  void inputSchema_requiresTld() {
    JsonObject schema = tool.inputSchema();
    assertThat(schema.getAsJsonArray("required").toString()).contains("tld");
  }

  @Test
  void execute_missingTld_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    assertAiToolException(() -> tool.execute(argsJson, user), "tld");
  }

  @Test
  void execute_emptyTld_throws() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("tld", "");
    assertAiToolException(() -> tool.execute(argsJson, user), "tld");
  }

  @Test
  void execute_tldOutsideUserScope_throwsPermissionDenied() {
    createTld("other-tld");
    User user = createRoUser("ro@example.com");
    mapUserToTld("ro@example.com", "other-tld");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("tld", "tld");
    assertAiToolException(() -> tool.execute(argsJson, user), "Permission denied");
  }
}
