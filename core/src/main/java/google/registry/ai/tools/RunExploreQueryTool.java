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
import com.google.common.flogger.FluentLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Optional;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.ui.server.console.registrydash.ExploreDataSource;
import google.registry.ui.server.console.registrydash.ExploreQueryDescriptor;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic AI tool: runs an arbitrary aggregation against the Explore engine. Escape hatch for
 * questions no specific tool covers.
 *
 * <p>Constrains Claude to a per-{@link ExploreDataSource} schema (Option A): the tool accepts a
 * data source name plus the dimensions/metrics/filters allowed by that source's allowlist, and
 * server-side validation rejects mismatches via {@link ExploreDataSource#validate}.
 *
 * <p>Hard caps and SQL {@code statement_timeout} are config-driven ({@code ai.tools.maxRows} and
 * {@code ai.tools.statementTimeoutSeconds}) so they can be tuned without a code change.
 */
@Singleton
public class RunExploreQueryTool implements AiTool {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final int maxRows;
  private final int statementTimeoutSeconds;

  @Inject
  public RunExploreQueryTool(
      @Config("aiToolsMaxRows") int maxRows,
      @Config("aiToolsStatementTimeoutSeconds") int statementTimeoutSeconds) {
    this.maxRows = maxRows;
    this.statementTimeoutSeconds = statementTimeoutSeconds;
  }

  @Override
  public String name() {
    return "run_explore_query";
  }

  @Override
  public Complexity complexity() {
    return Complexity.COMPLEX;
  }

  @Override
  public String description() {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "Use this only when no specific tool covers the question. Reach for query_transfers, "
            + "get_pricing_rules, query_registrar_activity, query_domain_details first. "
            + "Runs an aggregation against one of the registry's data sources and returns up to ")
        .append(maxRows)
        .append(" rows.\n\nValid (data_source, metrics, dimensions) combinations:");
    for (ExploreDataSource source : ExploreDataSource.values()) {
      sb.append("\n- ")
          .append(source.name())
          .append(": metrics=")
          .append(source.getAllowedMetrics())
          .append(", dimensions=")
          .append(source.getAllowedDimensions());
    }
    sb.append(
        "\n\nAlways pass start_date and end_date (ISO YYYY-MM-DD). Filters registrar_ids, "
            + "operations, activity_types are optional.");
    return sb.toString();
  }

  @Override
  public JsonObject inputSchema() {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");

    JsonObject props = new JsonObject();

    JsonObject dataSource = new JsonObject();
    dataSource.addProperty("type", "string");
    dataSource.addProperty(
        "description", "One of the 7 registry data sources (see tool description for valid set).");
    JsonArray dataSourceEnum = new JsonArray();
    for (ExploreDataSource s : ExploreDataSource.values()) {
      dataSourceEnum.add(s.name());
    }
    dataSource.add("enum", dataSourceEnum);
    props.add("data_source", dataSource);

    JsonObject tld = new JsonObject();
    tld.addProperty("type", "string");
    tld.addProperty("description", "Single TLD to scope the query to (e.g. 'example').");
    props.add("tld", tld);

    JsonObject startDate = new JsonObject();
    startDate.addProperty("type", "string");
    startDate.addProperty(
        "description", "ISO date or datetime, inclusive (e.g. '2026-04-01').");
    props.add("start_date", startDate);

    JsonObject endDate = new JsonObject();
    endDate.addProperty("type", "string");
    endDate.addProperty(
        "description", "ISO date or datetime, inclusive (e.g. '2026-04-30').");
    props.add("end_date", endDate);

    JsonObject metrics = new JsonObject();
    metrics.addProperty("type", "array");
    JsonObject metricsItems = new JsonObject();
    metricsItems.addProperty("type", "string");
    metrics.add("items", metricsItems);
    metrics.addProperty(
        "description",
        "Metric names valid for the chosen data_source (see tool description). Non-empty.");
    props.add("metrics", metrics);

    JsonObject dimensions = new JsonObject();
    dimensions.addProperty("type", "array");
    JsonObject dimensionsItems = new JsonObject();
    dimensionsItems.addProperty("type", "string");
    dimensions.add("items", dimensionsItems);
    dimensions.addProperty(
        "description",
        "Optional. Dimension names valid for the chosen data_source (group-by columns).");
    props.add("dimensions", dimensions);

    JsonObject registrarIds = new JsonObject();
    registrarIds.addProperty("type", "array");
    JsonObject registrarIdsItems = new JsonObject();
    registrarIdsItems.addProperty("type", "string");
    registrarIds.add("items", registrarIdsItems);
    registrarIds.addProperty("description", "Optional. Filter to these registrar IDs.");
    props.add("registrar_ids", registrarIds);

    JsonObject operations = new JsonObject();
    operations.addProperty("type", "array");
    JsonObject operationsItems = new JsonObject();
    operationsItems.addProperty("type", "string");
    operations.add("items", operationsItems);
    operations.addProperty(
        "description", "Optional. Filter to these operations (e.g. CREATE, RENEW, TRANSFER).");
    props.add("operations", operations);

    JsonObject activityTypes = new JsonObject();
    activityTypes.addProperty("type", "array");
    JsonObject activityTypesItems = new JsonObject();
    activityTypesItems.addProperty("type", "string");
    activityTypes.add("items", activityTypesItems);
    activityTypes.addProperty("description", "Optional. Filter to these activity types.");
    props.add("activity_types", activityTypes);

    schema.add("properties", props);

    JsonArray required = new JsonArray();
    required.add("data_source");
    required.add("tld");
    required.add("start_date");
    required.add("end_date");
    required.add("metrics");
    schema.add("required", required);

    return schema;
  }

  @Override
  public ToolResult executeWithStatus(JsonObject args, User user) {
    Optional<String> dataSourceName = stringArg(args, "data_source");
    if (dataSourceName.isEmpty()) {
      return ToolResult.invalidArgs("Missing required arg: data_source");
    }
    Optional<String> tld = stringArg(args, "tld");
    if (tld.isEmpty()) {
      return ToolResult.invalidArgs("Missing required arg: tld");
    }
    Optional<String> startDate = stringArg(args, "start_date");
    if (startDate.isEmpty()) {
      return ToolResult.invalidArgs("Missing required arg: start_date");
    }
    Optional<String> endDate = stringArg(args, "end_date");
    if (endDate.isEmpty()) {
      return ToolResult.invalidArgs("Missing required arg: end_date");
    }
    if (!args.has("metrics") || args.get("metrics").isJsonNull()) {
      return ToolResult.invalidArgs("Missing required arg: metrics");
    }
    List<String> metrics = readStringArray(args, "metrics");
    if (metrics.isEmpty()) {
      return ToolResult.invalidArgs("metrics must be non-empty");
    }
    List<String> dimensions = optionalStringArrayArg(args, "dimensions");
    List<String> registrarIds = optionalStringArrayArg(args, "registrar_ids");
    List<String> operations = optionalStringArrayArg(args, "operations");
    List<String> activityTypes = optionalStringArrayArg(args, "activity_types");

    ExploreDataSource source;
    try {
      source = ExploreDataSource.valueOf(dataSourceName.get());
    } catch (IllegalArgumentException e) {
      return ToolResult.invalidArgs(
          "Unknown data_source: " + dataSourceName.get() + ". Valid: " + validDataSourceNames());
    }

    try {
      ToolJpaHelper.assertTldAccess(user, tld.get());
    } catch (AiToolException e) {
      return ToolResult.permissionDenied(e.getMessage());
    }
    ImmutableSet<String> effectiveTlds = ToolJpaHelper.effectiveTlds(user, tld.get());

    ExploreQueryDescriptor desc =
        ToolJpaHelper.descriptor(
            source.name(),
            dimensions,
            metrics,
            List.of(tld.get()),
            registrarIds,
            operations,
            activityTypes,
            startDate.get(),
            endDate.get());

    // Tool-local audit log: descriptor-level detail not captured by the orchestrator's toolsUsed
    // list. Consolidation into the orchestrator's log line is a follow-up.
    logger.atInfo().log(
        "AI tool run_explore_query: user=%s tld=%s dataSource=%s dimensions=%s metrics=%s"
            + " registrarIds=%s operations=%s activityTypes=%s startDate=%s endDate=%s",
        user.getEmailAddress(),
        tld.get(),
        source.name(),
        dimensions,
        metrics,
        registrarIds,
        operations,
        activityTypes,
        startDate.get(),
        endDate.get());

    JsonObject payload;
    try {
      payload =
          ToolJpaHelper.runExplore(
              source,
              desc,
              effectiveTlds,
              columnsFor(dimensions, metrics),
              maxRows,
              statementTimeoutSeconds);
    } catch (AiToolException e) {
      return ToolResult.invalidArgs(e.getMessage());
    }
    int rowCount = payload.has("rowCount") ? payload.get("rowCount").getAsInt() : 0;
    if (rowCount > 0) {
      return ToolResult.ok(payload);
    }
    return ToolResult.emptyForRange(
        payload,
        "no rows for data_source="
            + source.name()
            + ", tld="
            + tld.get()
            + " between "
            + startDate.get()
            + " and "
            + endDate.get());
  }

  /**
   * The columns the SQL builder emits for a given query — ordered as dimensions first, then
   * metrics. This matches how {@link
   * google.registry.ui.server.console.registrydash.ExploreQueryBuilder} projects columns.
   */
  private static List<String> columnsFor(List<String> dimensions, List<String> metrics) {
    List<String> cols = new ArrayList<>();
    cols.addAll(dimensions);
    cols.addAll(metrics);
    return cols;
  }

  private static String validDataSourceNames() {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (ExploreDataSource s : ExploreDataSource.values()) {
      if (!first) {
        sb.append(", ");
      }
      sb.append(s.name());
      first = false;
    }
    sb.append("]");
    return sb.toString();
  }

  private static Optional<String> stringArg(JsonObject args, String key) {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      return Optional.empty();
    }
    return Optional.of(args.get(key).getAsString());
  }

  private static List<String> optionalStringArrayArg(JsonObject args, String key) {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      return List.of();
    }
    return readStringArray(args, key);
  }

  private static List<String> readStringArray(JsonObject args, String key) {
    JsonArray arr = args.getAsJsonArray(key);
    List<String> out = new ArrayList<>(arr.size());
    for (JsonElement el : arr) {
      out.add(el.getAsString());
    }
    return out;
  }
}
