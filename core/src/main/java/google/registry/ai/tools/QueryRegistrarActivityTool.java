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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

  private final Clock clock;

  @Inject
  public QueryRegistrarActivityTool() {
    this(Clock.systemUTC());
  }

  /** Test-friendly constructor. */
  QueryRegistrarActivityTool(Clock clock) {
    this.clock = clock;
  }

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
  public ToolResult executeWithStatus(JsonObject args, User user) {
    if (!args.has("registrar_id") || args.get("registrar_id").isJsonNull()) {
      return ToolResult.invalidArgs("Missing required arg: registrar_id");
    }
    String registrarId = args.get("registrar_id").getAsString();
    String tld = optionalString(args, "tld");
    String startDate = optionalString(args, "start_date");
    String endDate = optionalString(args, "end_date");

    // Permission check must run BEFORE OUT_OF_RANGE so an unmapped user cannot probe
    // whether a TLD exists by sending a future date. See SRE-1958 PR review.
    if (tld != null) {
      try {
        ToolJpaHelper.assertTldAccess(user, tld);
      } catch (AiToolException e) {
        return ToolResult.permissionDenied(e.getMessage());
      }
    }
    ImmutableSet<String> effectiveTlds = ToolJpaHelper.effectiveTlds(user, tld);

    Instant now = clock.instant();
    if (startDate != null) {
      try {
        Instant parsedStart = ToolJpaHelper.parseDateTime(startDate, false);
        if (parsedStart.isAfter(now)) {
          return ToolResult.outOfRange(
              "requested " + startDate + ".." + endDate + "; latest data: " + now);
        }
      } catch (Exception e) {
        return ToolResult.invalidArgs("Invalid start_date: " + e.getMessage());
      }
    }
    Instant parsedEnd = null;
    if (endDate != null) {
      try {
        parsedEnd = ToolJpaHelper.parseDateTime(endDate, true);
      } catch (Exception e) {
        return ToolResult.invalidArgs("Invalid end_date: " + e.getMessage());
      }
    }

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

    JsonObject payload;
    try {
      payload =
          ToolJpaHelper.runExplore(
              ExploreDataSource.DOMAIN_ACTIVITY, desc, effectiveTlds, COLUMNS, MAX_ROWS);
    } catch (AiToolException e) {
      return ToolResult.invalidArgs(e.getMessage());
    }

    int rowCount = payload.has("rowCount") ? payload.get("rowCount").getAsInt() : 0;
    if (rowCount > 0) {
      return ToolResult.ok(payload);
    }
    if (parsedEnd != null && parsedEnd.isAfter(now)) {
      return ToolResult.outOfRange(
          "requested " + startDate + ".." + endDate + "; latest data: " + now);
    }
    Optional<ToolJpaHelper.DataExtent> extent =
        ToolJpaHelper.probeDataExtent(
            "DomainHistory", "history_modification_time", null, ImmutableSet.of());
    StringBuilder diag = new StringBuilder("no activity for registrar=").append(registrarId);
    if (tld != null) {
      diag.append(", tld=").append(tld);
    }
    if (startDate != null || endDate != null) {
      diag.append(" between ").append(startDate).append(" and ").append(endDate);
    }
    if (extent.isPresent()) {
      diag.append("; data exists ")
          .append(extent.get().min())
          .append(" to ")
          .append(extent.get().max());
    }
    return ToolResult.emptyForRange(payload, diag.toString());
  }

  private static String optionalString(JsonObject args, String key) {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      return null;
    }
    String s = args.get(key).getAsString();
    return s.isEmpty() ? null : s;
  }
}
