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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.model.console.User;
import google.registry.ui.server.console.registrydash.ExploreDataSource;
import google.registry.ui.server.console.registrydash.ExploreQueryDescriptor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * AI tool: returns domain-level transfer activity for a TLD over a date range.
 *
 * <p>Wraps {@link ExploreDataSource#TRANSACTIONS} filtered to TRANSFER operations.
 */
@Singleton
public class QueryTransfersTool implements AiTool {

  private static final int MAX_ROWS = 100;

  private static final List<String> COLUMNS =
      List.of(
          "timestamp",
          "domain_name",
          "tld",
          "registrar",
          "operation",
          "amount",
          "net_amount_to_registry",
          "currency");

  @Inject
  public QueryTransfersTool() {}

  @Override
  public String name() {
    return "query_transfers";
  }

  @Override
  public String description() {
    return "Returns the list of domain transfers for a TLD within a date range. Includes the"
        + " timestamp, domain name, gaining registrar, and amount. Use when the user asks for"
        + " specific domains that transferred or which registrars are involved in transfers.";
  }

  @Override
  public JsonObject inputSchema() {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject props = new JsonObject();

    JsonObject tld = new JsonObject();
    tld.addProperty("type", "string");
    tld.addProperty("description", "TLD to query (e.g. 'example')");
    props.add("tld", tld);

    JsonObject startDate = new JsonObject();
    startDate.addProperty("type", "string");
    startDate.addProperty("description", "ISO date or datetime, inclusive (e.g. '2026-04-01')");
    props.add("start_date", startDate);

    JsonObject endDate = new JsonObject();
    endDate.addProperty("type", "string");
    endDate.addProperty("description", "ISO date or datetime, inclusive (e.g. '2026-04-29')");
    props.add("end_date", endDate);

    schema.add("properties", props);
    com.google.gson.JsonArray required = new com.google.gson.JsonArray();
    required.add("tld");
    required.add("start_date");
    required.add("end_date");
    schema.add("required", required);
    return schema;
  }

  @Override
  public JsonElement execute(JsonObject args, User user) throws AiToolException {
    String tld = stringArg(args, "tld");
    String startDate = stringArg(args, "start_date");
    String endDate = stringArg(args, "end_date");

    ToolJpaHelper.assertTldAccess(user, tld);
    ImmutableSet<String> effectiveTlds = ToolJpaHelper.effectiveTlds(user, tld);

    ExploreQueryDescriptor desc =
        ToolJpaHelper.descriptor(
            ExploreDataSource.TRANSACTIONS.name(),
            List.of(),
            List.of("amount"),
            List.of(tld),
            List.of(),
            List.of("TRANSFER"),
            List.of(),
            startDate,
            endDate);

    return ToolJpaHelper.runExplore(
        ExploreDataSource.TRANSACTIONS, desc, effectiveTlds, COLUMNS, MAX_ROWS);
  }

  private static String stringArg(JsonObject args, String key) throws AiToolException {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      throw new AiToolException("Missing required arg: " + key);
    }
    return args.get(key).getAsString();
  }
}
