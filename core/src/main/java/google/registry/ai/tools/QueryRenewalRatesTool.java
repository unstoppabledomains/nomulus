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
import java.util.List;

/**
 * AI tool: returns renewal rate stats (renewals, deletions, rate) for a TLD over a date range.
 *
 * <p>Wraps {@link ExploreDataSource#RENEWAL_RATES}.
 */
@Singleton
public class QueryRenewalRatesTool implements AiTool {

  static final int MAX_ROWS = 100;

  private static final List<String> COLUMNS =
      List.of("tld", "renewals_sum", "deletions_sum", "renewalRate_sum");

  @Inject
  public QueryRenewalRatesTool() {}

  @Override
  public String name() {
    return "query_renewal_rates";
  }

  @Override
  public String description() {
    return "Returns renewal-rate stats (renewals, deletions, computed rate) for a TLD over a date"
        + " range. Use when the user asks how a TLD is performing on renewals or what the renewal"
        + " rate trend looks like.";
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

    JsonObject startDate = new JsonObject();
    startDate.addProperty("type", "string");
    startDate.addProperty("description", "ISO date inclusive");
    props.add("start_date", startDate);

    JsonObject endDate = new JsonObject();
    endDate.addProperty("type", "string");
    endDate.addProperty("description", "ISO date inclusive");
    props.add("end_date", endDate);

    schema.add("properties", props);
    JsonArray required = new JsonArray();
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
            ExploreDataSource.RENEWAL_RATES.name(),
            List.of("tld"),
            List.of("renewals", "deletions", "renewalRate"),
            List.of(tld),
            List.of(),
            List.of(),
            List.of(),
            startDate,
            endDate);

    return ToolJpaHelper.runExplore(
        ExploreDataSource.RENEWAL_RATES, desc, effectiveTlds, COLUMNS, MAX_ROWS);
  }

  private static String stringArg(JsonObject args, String key) throws AiToolException {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      throw new AiToolException("Missing required arg: " + key);
    }
    return args.get(key).getAsString();
  }
}
