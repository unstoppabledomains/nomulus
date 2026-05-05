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

/** Tests for {@link QueryDomainDetailsTool}. */
class QueryDomainDetailsToolTest extends AiToolTestBase {

  private final QueryDomainDetailsTool tool = new QueryDomainDetailsTool();

  @BeforeEach
  void setUp() {
    createTld("tld");
  }

  @Test
  void name_isStable() {
    assertThat(tool.name()).isEqualTo("query_domain_details");
  }

  @Test
  void inputSchema_requiresDomainName() {
    JsonObject schema = tool.inputSchema();
    assertThat(schema.getAsJsonArray("required").toString()).contains("domain_name");
  }

  @Test
  void execute_missingDomainName_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("domain_name");
  }

  @Test
  void execute_unknownDomain_returnsInvalidArgs() {
    User user = createFteUser("admin@example.com");
    JsonObject argsJson = new JsonObject();
    argsJson.addProperty("domain_name", "no-such-domain.tld");
    ToolResult result = tool.executeWithStatus(argsJson, user);
    assertToolResultStatus(result, ToolResult.Status.INVALID_ARGS);
    assertThat(result.diagnostic()).contains("Domain not found");
  }
}
