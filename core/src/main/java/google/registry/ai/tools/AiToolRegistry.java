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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Optional;

/** Registry of {@link AiTool}s the AI orchestrator can dispatch to. */
@Singleton
public class AiToolRegistry {

  private final ImmutableMap<String, AiTool> toolsByName;

  @Inject
  public AiToolRegistry(
      QueryTransfersTool queryTransfers,
      GetPricingRulesTool getPricingRules,
      QueryRegistrarActivityTool queryRegistrarActivity,
      QueryDomainDetailsTool queryDomainDetails,
      GetRegistrarDetailsTool getRegistrarDetails,
      GetTldConfigTool getTldConfig,
      QueryRevenueBreakdownTool queryRevenueBreakdown,
      QueryRenewalRatesTool queryRenewalRates,
      QueryExpirationCurveTool queryExpirationCurve) {
    this(
        ImmutableList.of(
            queryTransfers,
            getPricingRules,
            queryRegistrarActivity,
            queryDomainDetails,
            getRegistrarDetails,
            getTldConfig,
            queryRevenueBreakdown,
            queryRenewalRates,
            queryExpirationCurve));
  }

  /** Test-friendly constructor. */
  public AiToolRegistry(Iterable<AiTool> tools) {
    ImmutableMap.Builder<String, AiTool> b = ImmutableMap.builder();
    for (AiTool tool : tools) {
      b.put(tool.name(), tool);
    }
    this.toolsByName = b.buildOrThrow();
  }

  public Optional<AiTool> get(String name) {
    return Optional.ofNullable(toolsByName.get(name));
  }

  public ImmutableList<AiTool> all() {
    return ImmutableList.copyOf(toolsByName.values());
  }

  /**
   * The {@code tools} array Anthropic's Messages API expects: each entry has {@code name},
   * {@code description}, and {@code input_schema} fields.
   */
  public JsonArray anthropicToolDefinitions() {
    JsonArray arr = new JsonArray();
    for (AiTool tool : toolsByName.values()) {
      JsonObject def = new JsonObject();
      def.addProperty("name", tool.name());
      def.addProperty("description", tool.description());
      def.add("input_schema", tool.inputSchema());
      arr.add(def);
    }
    return arr;
  }
}
