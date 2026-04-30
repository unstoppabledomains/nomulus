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
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * AI tool: revenue breakdown for a TLD over a date range, grouped by operation or period.
 *
 * <p>Wraps {@link ExploreDataSource#REVENUE}. Date range capped at 2 years to keep result sets and
 * query times bounded.
 */
@Singleton
public class QueryRevenueBreakdownTool implements AiTool {

  static final int MAX_ROWS = 100;
  private static final Period MAX_RANGE = Period.ofYears(2);
  private static final ImmutableSet<String> ALLOWED_GROUP_BY =
      ImmutableSet.of("operation", "period");

  @Inject
  public QueryRevenueBreakdownTool() {}

  @Override
  public String name() {
    return "query_revenue_breakdown";
  }

  @Override
  public String description() {
    return "Returns revenue (amount and net-to-registry) for a TLD over a date range, grouped by"
        + " 'operation' (CREATE/RENEW/TRANSFER/...) or 'period' (time bucket). Use when the user"
        + " asks for revenue figures, what drove revenue, or revenue trends over time.";
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
    startDate.addProperty("description", "ISO date inclusive (e.g. '2026-01-01')");
    props.add("start_date", startDate);

    JsonObject endDate = new JsonObject();
    endDate.addProperty("type", "string");
    endDate.addProperty("description", "ISO date inclusive (e.g. '2026-04-30')");
    props.add("end_date", endDate);

    JsonObject groupBy = new JsonObject();
    groupBy.addProperty("type", "string");
    groupBy.addProperty(
        "description",
        "Dimension to break revenue down by. Allowed: 'operation' (CREATE/RENEW/...) or 'period'"
            + " (time bucket).");
    JsonArray groupByEnum = new JsonArray();
    ALLOWED_GROUP_BY.forEach(groupByEnum::add);
    groupBy.add("enum", groupByEnum);
    props.add("group_by", groupBy);

    schema.add("properties", props);
    JsonArray required = new JsonArray();
    required.add("tld");
    required.add("start_date");
    required.add("end_date");
    required.add("group_by");
    schema.add("required", required);
    return schema;
  }

  @Override
  public JsonElement execute(JsonObject args, User user) throws AiToolException {
    String tld = stringArg(args, "tld");
    String startDateStr = stringArg(args, "start_date");
    String endDateStr = stringArg(args, "end_date");
    String groupBy = stringArg(args, "group_by");

    if (!ALLOWED_GROUP_BY.contains(groupBy)) {
      throw new AiToolException(
          "Invalid group_by '" + groupBy + "'. Allowed: " + ALLOWED_GROUP_BY);
    }

    LocalDate start;
    LocalDate end;
    try {
      start = LocalDate.parse(startDateStr);
      end = LocalDate.parse(endDateStr);
    } catch (DateTimeParseException e) {
      throw new AiToolException("Invalid date: " + e.getMessage());
    }
    if (end.isBefore(start)) {
      throw new AiToolException("end_date must be on or after start_date");
    }
    if (start.plus(MAX_RANGE).isBefore(end)) {
      throw new AiToolException("Date range exceeds 2-year cap");
    }

    ToolJpaHelper.assertTldAccess(user, tld);
    ImmutableSet<String> effectiveTlds = ToolJpaHelper.effectiveTlds(user, tld);

    ExploreQueryDescriptor desc =
        ToolJpaHelper.descriptor(
            ExploreDataSource.REVENUE.name(),
            List.of(groupBy),
            List.of("amount", "netAmountToRegistry"),
            List.of(tld),
            List.of(),
            List.of(),
            List.of(),
            startDateStr,
            endDateStr);

    List<String> columns = List.of(groupBy, "amount_sum", "netAmountToRegistry_sum");
    return ToolJpaHelper.runExplore(
        ExploreDataSource.REVENUE, desc, effectiveTlds, columns, MAX_ROWS);
  }

  private static String stringArg(JsonObject args, String key) throws AiToolException {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      throw new AiToolException("Missing required arg: " + key);
    }
    return args.get(key).getAsString();
  }
}
