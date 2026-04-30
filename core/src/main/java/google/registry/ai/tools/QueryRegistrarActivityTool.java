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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.model.console.User;
import google.registry.ui.server.console.registrydash.ExploreDataSource;
import google.registry.ui.server.console.registrydash.ExploreQueryDescriptor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * AI tool: returns lifecycle activity (creates, renews, transfers, deletes) attributed to a
 * specific registrar, optionally narrowed by TLD and date range.
 *
 * <p>Wraps {@link ExploreDataSource#DOMAIN_ACTIVITY}.
 */
@Singleton
public class QueryRegistrarActivityTool implements AiTool {

  private static final int MAX_ROWS = 500;

  private static final List<String> COLUMNS =
      List.of("period", "tld", "activity_type", "registrar", "count_value");

  @Inject
  public QueryRegistrarActivityTool() {}

  @Override
  public String name() {
    return "query_registrar_activity";
  }

  @Override
  public String description() {
    return "Returns domain lifecycle activity (creates, renews, transfers, deletes) for a specific"
        + " registrar, optionally filtered by TLD and date range. Use when the user asks about"
        + " what a particular registrar has been doing.";
  }

  @Override
  public JsonObject inputSchema() {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject props = new JsonObject();

    JsonObject registrar = new JsonObject();
    registrar.addProperty("type", "string");
    registrar.addProperty("description", "Registrar id");
    props.add("registrar_id", registrar);

    JsonObject tld = new JsonObject();
    tld.addProperty("type", "string");
    tld.addProperty("description", "Optional TLD to narrow results");
    props.add("tld", tld);

    JsonObject startDate = new JsonObject();
    startDate.addProperty("type", "string");
    startDate.addProperty("description", "Optional ISO date for range start");
    props.add("start_date", startDate);

    JsonObject endDate = new JsonObject();
    endDate.addProperty("type", "string");
    endDate.addProperty("description", "Optional ISO date for range end");
    props.add("end_date", endDate);

    schema.add("properties", props);
    JsonArray required = new JsonArray();
    required.add("registrar_id");
    schema.add("required", required);
    return schema;
  }

  @Override
  public JsonElement execute(JsonObject args, User user) throws AiToolException {
    String registrarId = stringArg(args, "registrar_id");
    String tld = optionalString(args, "tld");
    String startDate = optionalString(args, "start_date");
    String endDate = optionalString(args, "end_date");

    if (tld != null) {
      ToolJpaHelper.assertTldAccess(user, tld);
    }
    ImmutableSet<String> effectiveTlds = ToolJpaHelper.effectiveTlds(user, tld);

    List<String> dimensions = new ArrayList<>();
    dimensions.add("period");
    dimensions.add("tld");
    dimensions.add("activity_type");
    dimensions.add("registrar");

    ExploreQueryDescriptor desc =
        ToolJpaHelper.descriptor(
            ExploreDataSource.DOMAIN_ACTIVITY.name(),
            dimensions,
            List.of(),
            tld == null ? List.of() : List.of(tld),
            List.of(registrarId),
            List.of(),
            List.of(),
            startDate,
            endDate);

    JsonObject result =
        ToolJpaHelper.runExplore(
            ExploreDataSource.DOMAIN_ACTIVITY, desc, effectiveTlds, COLUMNS, MAX_ROWS);

    // Post-filter rows that don't match the registrar (DOMAIN_ACTIVITY doesn't natively filter
    // by registrar in WHERE; the dimension is the current_sponsor_registrar_id).
    JsonArray filtered = new JsonArray();
    for (JsonElement row : result.getAsJsonArray("rows")) {
      JsonObject obj = row.getAsJsonObject();
      if (obj.has("registrar")
          && !obj.get("registrar").isJsonNull()
          && obj.get("registrar").getAsString().equals(registrarId)) {
        filtered.add(obj);
      }
    }
    result.add("rows", filtered);
    result.addProperty("rowCount", filtered.size());
    return result;
  }

  private static String stringArg(JsonObject args, String key) throws AiToolException {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      throw new AiToolException("Missing required arg: " + key);
    }
    return args.get(key).getAsString();
  }

  private static String optionalString(JsonObject args, String key) {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      return null;
    }
    String s = args.get(key).getAsString();
    return s.isEmpty() ? null : s;
  }
}
