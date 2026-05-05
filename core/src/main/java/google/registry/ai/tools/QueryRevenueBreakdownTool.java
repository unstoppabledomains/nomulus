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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

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

  private final Clock clock;

  @Inject
  public QueryRevenueBreakdownTool() {
    this(Clock.systemUTC());
  }

  /** Test-friendly constructor. */
  QueryRevenueBreakdownTool(Clock clock) {
    this.clock = clock;
  }

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
  public ToolResult executeWithStatus(JsonObject args, User user) {
    if (!args.has("tld") || args.get("tld").isJsonNull()) {
      return ToolResult.invalidArgs("Missing required arg: tld");
    }
    if (!args.has("start_date") || args.get("start_date").isJsonNull()) {
      return ToolResult.invalidArgs("Missing required arg: start_date");
    }
    if (!args.has("end_date") || args.get("end_date").isJsonNull()) {
      return ToolResult.invalidArgs("Missing required arg: end_date");
    }
    if (!args.has("group_by") || args.get("group_by").isJsonNull()) {
      return ToolResult.invalidArgs("Missing required arg: group_by");
    }
    String tld = args.get("tld").getAsString();
    String startDateStr = args.get("start_date").getAsString();
    String endDateStr = args.get("end_date").getAsString();
    String groupBy = args.get("group_by").getAsString();

    if (!ALLOWED_GROUP_BY.contains(groupBy)) {
      return ToolResult.invalidArgs(
          "Invalid group_by '" + groupBy + "'. Allowed: " + ALLOWED_GROUP_BY);
    }

    LocalDate start;
    LocalDate end;
    try {
      start = LocalDate.parse(startDateStr);
      end = LocalDate.parse(endDateStr);
    } catch (DateTimeParseException e) {
      return ToolResult.invalidArgs("Invalid date: " + e.getMessage());
    }
    if (end.isBefore(start)) {
      return ToolResult.invalidArgs("end_date must be on or after start_date");
    }
    if (start.plus(MAX_RANGE).isBefore(end)) {
      return ToolResult.invalidArgs("Date range exceeds 2-year cap");
    }

    Instant now = clock.instant();
    Instant parsedStart;
    Instant parsedEnd;
    try {
      parsedStart = ToolJpaHelper.parseDateTime(startDateStr, false);
      parsedEnd = ToolJpaHelper.parseDateTime(endDateStr, true);
    } catch (Exception e) {
      return ToolResult.invalidArgs("Invalid date: " + e.getMessage());
    }
    if (parsedStart.isAfter(now)) {
      return ToolResult.outOfRange(
          "requested " + startDateStr + ".." + endDateStr + "; latest data: " + now);
    }

    try {
      ToolJpaHelper.assertTldAccess(user, tld);
    } catch (AiToolException e) {
      return ToolResult.permissionDenied(e.getMessage());
    }
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
    JsonObject payload;
    try {
      payload =
          ToolJpaHelper.runExplore(
              ExploreDataSource.REVENUE, desc, effectiveTlds, columns, MAX_ROWS);
    } catch (AiToolException e) {
      return ToolResult.invalidArgs(e.getMessage());
    }
    int rowCount = payload.has("rowCount") ? payload.get("rowCount").getAsInt() : 0;
    if (rowCount > 0) {
      return ToolResult.ok(payload);
    }
    if (parsedEnd.isAfter(now)) {
      return ToolResult.outOfRange(
          "requested " + startDateStr + ".." + endDateStr + "; latest data: " + now);
    }
    Optional<ToolJpaHelper.DataExtent> extent =
        ToolJpaHelper.probeDataExtent(
            "DomainHistory", "history_modification_time", null, ImmutableSet.of());
    StringBuilder diag =
        new StringBuilder("no revenue for tld=").append(tld);
    diag.append(" between ").append(startDateStr).append(" and ").append(endDateStr);
    if (extent.isPresent()) {
      diag.append("; data exists ")
          .append(extent.get().min())
          .append(" to ")
          .append(extent.get().max());
    }
    return ToolResult.emptyForRange(payload, diag.toString());
  }
}
