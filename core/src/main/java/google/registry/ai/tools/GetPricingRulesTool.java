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

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import google.registry.model.console.User;
import google.registry.ui.server.console.registrydash.ExploreDataSource;
import google.registry.ui.server.console.registrydash.ExploreQueryDescriptor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * AI tool: returns active pricing rules for a TLD, optionally narrowed by registrar.
 *
 * <p>Wraps {@link ExploreDataSource#PRICING_RULES}.
 */
@Singleton
public class GetPricingRulesTool implements AiTool {

  private static final int MAX_ROWS = 200;

  private static final List<String> COLUMNS =
      List.of("registrar", "tld", "operation", "price_amount_sum", "currency");

  @Inject
  public GetPricingRulesTool() {}

  @Override
  public String name() {
    return "get_pricing_rules";
  }

  @Override
  public String description() {
    return "Returns the current pricing rules (per-tld, per-registrar, per-operation prices). Use"
        + " when the user asks about pricing, fees, or rate cards.";
  }

  @Override
  public JsonObject inputSchema() {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject props = new JsonObject();

    JsonObject tld = new JsonObject();
    tld.addProperty("type", "string");
    tld.addProperty("description", "TLD to query");
    props.add("tld", tld);

    JsonObject registrar = new JsonObject();
    registrar.addProperty("type", "string");
    registrar.addProperty(
        "description", "Optional registrar id to narrow results to a single registrar");
    props.add("registrar_id", registrar);

    schema.add("properties", props);
    JsonArray required = new JsonArray();
    required.add("tld");
    schema.add("required", required);
    return schema;
  }

  @Override
  public ToolResult executeWithStatus(JsonObject args, User user) {
    String tld =
        args.has("tld") && !args.get("tld").isJsonNull()
            ? args.get("tld").getAsString()
            : null;
    if (tld == null || tld.isEmpty()) {
      return ToolResult.invalidArgs("Missing required arg: tld");
    }
    String registrarId =
        args.has("registrar_id") && !args.get("registrar_id").isJsonNull()
            ? args.get("registrar_id").getAsString()
            : null;

    try {
      ToolJpaHelper.assertTldAccess(user, tld);
    } catch (AiToolException e) {
      return ToolResult.permissionDenied(e.getMessage());
    }
    ImmutableSet<String> effectiveTlds = ToolJpaHelper.effectiveTlds(user, tld);

    ExploreQueryDescriptor desc =
        ToolJpaHelper.descriptor(
            ExploreDataSource.PRICING_RULES.name(),
            List.of("registrar", "tld", "operation"),
            List.of("priceAmount"),
            List.of(tld),
            registrarId == null ? List.of() : List.of(registrarId),
            List.of(),
            List.of(),
            null,
            null);

    JsonObject payload;
    try {
      payload =
          ToolJpaHelper.runExplore(
              ExploreDataSource.PRICING_RULES, desc, effectiveTlds, COLUMNS, MAX_ROWS);
    } catch (AiToolException e) {
      return ToolResult.invalidArgs(e.getMessage());
    }
    int rowCount = payload.has("rowCount") ? payload.get("rowCount").getAsInt() : 0;
    if (rowCount > 0) {
      return ToolResult.ok(payload);
    }
    String diag =
        registrarId == null
            ? "no active pricing rules for tld=" + tld
            : "no active pricing rules for tld=" + tld + ", registrar=" + registrarId;
    return ToolResult.emptyForRange(payload, diag);
  }
}
