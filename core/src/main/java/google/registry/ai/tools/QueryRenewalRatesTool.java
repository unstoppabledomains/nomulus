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
import java.util.List;
import java.util.Optional;

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

  private final Clock clock;

  @Inject
  public QueryRenewalRatesTool() {
    this(Clock.systemUTC());
  }

  /** Test-friendly constructor. */
  QueryRenewalRatesTool(Clock clock) {
    this.clock = clock;
  }

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
    String tld = args.get("tld").getAsString();
    String startDate = args.get("start_date").getAsString();
    String endDate = args.get("end_date").getAsString();

    Instant now = clock.instant();
    Instant parsedStart;
    Instant parsedEnd;
    try {
      parsedStart = ToolJpaHelper.parseDateTime(startDate, false);
      parsedEnd = ToolJpaHelper.parseDateTime(endDate, true);
    } catch (Exception e) {
      return ToolResult.invalidArgs("Invalid date: " + e.getMessage());
    }
    // Permission check must run BEFORE OUT_OF_RANGE so an unmapped user cannot probe
    // whether a TLD exists by sending a future date. See SRE-1958 PR review.
    try {
      ToolJpaHelper.assertTldAccess(user, tld);
    } catch (AiToolException e) {
      return ToolResult.permissionDenied(e.getMessage());
    }
    ImmutableSet<String> effectiveTlds = ToolJpaHelper.effectiveTlds(user, tld);

    if (parsedStart.isAfter(now)) {
      return ToolResult.outOfRange(
          "requested " + startDate + ".." + endDate + "; latest data: " + now);
    }

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

    JsonObject payload;
    try {
      payload =
          ToolJpaHelper.runExplore(
              ExploreDataSource.RENEWAL_RATES, desc, effectiveTlds, COLUMNS, MAX_ROWS);
    } catch (AiToolException e) {
      return ToolResult.invalidArgs(e.getMessage());
    }
    int rowCount = payload.has("rowCount") ? payload.get("rowCount").getAsInt() : 0;
    if (rowCount > 0) {
      return ToolResult.ok(payload);
    }
    if (parsedEnd.isAfter(now)) {
      return ToolResult.outOfRange(
          "requested " + startDate + ".." + endDate + "; latest data: " + now);
    }
    Optional<ToolJpaHelper.DataExtent> extent =
        ToolJpaHelper.probeDataExtent(
            "DomainHistory", "history_modification_time", null, ImmutableSet.of());
    StringBuilder diag = new StringBuilder("no renewal data for tld=").append(tld);
    diag.append(" between ").append(startDate).append(" and ").append(endDate);
    if (extent.isPresent()) {
      diag.append("; data exists ")
          .append(extent.get().min())
          .append(" to ")
          .append(extent.get().max());
    }
    return ToolResult.emptyForRange(payload, diag.toString());
  }
}
