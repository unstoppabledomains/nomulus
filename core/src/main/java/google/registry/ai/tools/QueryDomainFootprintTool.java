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
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.ui.server.console.registrydash.ExploreDataSource;
import google.registry.ui.server.console.registrydash.ExploreQueryDescriptor;
import google.registry.ui.server.console.registrydash.RegistryDashAccessUtil;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * AI tool: snapshot of currently-registered domains under management, optionally grouped by
 * registrar and/or TLD. Backed by {@link ExploreDataSource#DOMAIN_COUNTS} but with an obvious
 * shape so the LLM doesn't have to learn the dateless-source quirk of {@code run_explore_query}.
 *
 * <p>Answers questions like "who is the largest registrar across all TLDs?" or "how many domains
 * under .foo by registrar?". No date params — DOMAIN_COUNTS is a current-state aggregator.
 */
@Singleton
public class QueryDomainFootprintTool implements AiTool {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;
  private static final ImmutableSet<String> ALLOWED_GROUP_BY =
      ImmutableSet.of("registrar", "tld", "both");

  @Inject
  public QueryDomainFootprintTool() {}

  @Override
  public String name() {
    return "query_domain_footprint";
  }

  @Override
  public Complexity complexity() {
    return Complexity.MEDIUM;
  }

  @Override
  public String description() {
    return "Snapshot of currently-registered domains under management. Returns a count of active"
        + " domains, optionally grouped by registrar, by tld, or by both. Use to answer questions"
        + " like 'who is the largest registrar?' or 'how many domains under .example?'. No date"
        + " params — this is a current-state snapshot, not a time series. Results are sorted by"
        + " count descending.";
  }

  @Override
  public JsonObject inputSchema() {
    JsonObject schema = new JsonObject();
    schema.addProperty("type", "object");
    JsonObject props = new JsonObject();

    JsonObject tlds = new JsonObject();
    tlds.addProperty("type", "array");
    JsonObject tldsItems = new JsonObject();
    tldsItems.addProperty("type", "string");
    tlds.add("items", tldsItems);
    tlds.addProperty(
        "description",
        "Optional. Filter to these TLDs. If omitted, all TLDs the caller has access to are"
            + " included.");
    props.add("tlds", tlds);

    JsonObject registrarIds = new JsonObject();
    registrarIds.addProperty("type", "array");
    JsonObject registrarIdsItems = new JsonObject();
    registrarIdsItems.addProperty("type", "string");
    registrarIds.add("items", registrarIdsItems);
    registrarIds.addProperty("description", "Optional. Filter to these registrar IDs.");
    props.add("registrar_ids", registrarIds);

    JsonObject groupBy = new JsonObject();
    groupBy.addProperty("type", "string");
    groupBy.addProperty(
        "description",
        "How to group the count. 'registrar' = one row per registrar (across all in-scope TLDs),"
            + " 'tld' = one row per TLD, 'both' = one row per (tld, registrar). Default: 'both'.");
    JsonArray groupByEnum = new JsonArray();
    ALLOWED_GROUP_BY.forEach(groupByEnum::add);
    groupBy.add("enum", groupByEnum);
    props.add("group_by", groupBy);

    JsonObject limit = new JsonObject();
    limit.addProperty("type", "integer");
    limit.addProperty(
        "description",
        "Optional. Maximum rows to return. Default 100, capped at " + MAX_LIMIT + ".");
    props.add("limit", limit);

    schema.add("properties", props);
    schema.add("required", new JsonArray());
    return schema;
  }

  @Override
  public ToolResult executeWithStatus(JsonObject args, User user) {
    String groupBy = "both";
    if (args.has("group_by") && !args.get("group_by").isJsonNull()) {
      groupBy = args.get("group_by").getAsString();
      if (!ALLOWED_GROUP_BY.contains(groupBy)) {
        return ToolResult.invalidArgs(
            "Invalid group_by '" + groupBy + "'. Allowed: " + ALLOWED_GROUP_BY);
      }
    }
    int limit = DEFAULT_LIMIT;
    if (args.has("limit") && !args.get("limit").isJsonNull()) {
      limit = args.get("limit").getAsInt();
      if (limit <= 0) {
        return ToolResult.invalidArgs("limit must be positive");
      }
      limit = Math.min(limit, MAX_LIMIT);
    }
    List<String> requestedTlds = optionalStringArrayArg(args, "tlds");
    List<String> registrarIds = optionalStringArrayArg(args, "registrar_ids");

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> accessTlds =
        isAdmin
            ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    if (!isAdmin) {
      for (String t : requestedTlds) {
        if (!accessTlds.contains(t)) {
          return ToolResult.permissionDenied("Permission denied for tld: " + t);
        }
      }
    }
    ImmutableSet<String> effectiveTlds =
        RegistryDashAccessUtil.applyFilter(accessTlds, ImmutableSet.copyOf(requestedTlds), isAdmin);

    List<String> dimensions =
        switch (groupBy) {
          case "registrar" -> List.of("registrar");
          case "tld" -> List.of("tld");
          default -> List.of("tld", "registrar");
        };

    ExploreQueryDescriptor desc =
        ToolJpaHelper.descriptor(
            ExploreDataSource.DOMAIN_COUNTS.name(),
            dimensions,
            List.of("count"),
            requestedTlds,
            registrarIds,
            List.of(),
            List.of(),
            null,
            null);

    logger.atInfo().log(
        "AI tool query_domain_footprint: user=%s tlds=%s registrarIds=%s groupBy=%s limit=%d",
        user.getEmailAddress(), requestedTlds, registrarIds, groupBy, limit);

    List<String> columns = new ArrayList<>(dimensions);
    columns.add("count");

    // The DOMAIN_COUNTS SQL is `ORDER BY count_value DESC LIMIT :maxRows`, so the rows we get
    // back are already the top-N groups even when the true cardinality exceeds the limit. We pass
    // `limit` directly so the SQL returns at most that many groups.
    JsonObject payload;
    try {
      payload =
          ToolJpaHelper.runExplore(
              ExploreDataSource.DOMAIN_COUNTS, desc, effectiveTlds, columns, limit);
    } catch (AiToolException e) {
      return ToolResult.invalidArgs(e.getMessage());
    }

    // Authoritative total — independent of row truncation. Computed via a separate ungrouped
    // COUNT(*) so we don't under-report when there are more groups than `limit`.
    long total =
        ToolJpaHelper.countActiveDomains(effectiveTlds, ImmutableSet.copyOf(registrarIds));
    payload.addProperty("totalDomains", total);
    // `truncated` is true when the SQL hit the limit (more groups exist than were returned).
    int rowCount = payload.has("rowCount") ? payload.get("rowCount").getAsInt() : 0;
    payload.addProperty("truncated", rowCount >= limit);

    if (rowCount == 0) {
      return ToolResult.emptyForRange(
          payload, "no domains under management for the requested filters");
    }
    return ToolResult.ok(payload);
  }

  private static List<String> optionalStringArrayArg(JsonObject args, String key) {
    if (!args.has(key) || args.get(key).isJsonNull()) {
      return List.of();
    }
    JsonArray arr = args.getAsJsonArray(key);
    List<String> out = new ArrayList<>(arr.size());
    for (JsonElement el : arr) {
      out.add(el.getAsString());
    }
    return out;
  }
}
