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
import java.util.List;

/**
 * AI tool: future-expiration curve for a TLD bucketed by month.
 *
 * <p>Wraps {@link ExploreDataSource#EXPIRATION_CURVE}. {@code months_ahead} is clamped to [1, 60].
 */
@Singleton
public class QueryExpirationCurveTool implements AiTool {

  static final int MAX_ROWS = 100;
  static final int MIN_MONTHS = 1;
  static final int MAX_MONTHS = 60;

  private static final List<String> COLUMNS = List.of("tld", "month", "count_sum");

  private final java.time.Clock clock;

  @Inject
  public QueryExpirationCurveTool() {
    this(java.time.Clock.systemUTC());
  }

  /** Test-friendly constructor. */
  QueryExpirationCurveTool(java.time.Clock clock) {
    this.clock = clock;
  }

  @Override
  public String name() {
    return "query_expiration_curve";
  }

  @Override
  public String description() {
    return "Returns the count of domains in a TLD that expire in each upcoming month. Use when the"
        + " user asks how many domains will expire in the next N months or wants to forecast"
        + " renewals.";
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

    JsonObject monthsAhead = new JsonObject();
    monthsAhead.addProperty("type", "integer");
    monthsAhead.addProperty(
        "description", "How many months into the future to project (clamped to [1, 60])");
    props.add("months_ahead", monthsAhead);

    schema.add("properties", props);
    JsonArray required = new JsonArray();
    required.add("tld");
    required.add("months_ahead");
    schema.add("required", required);
    return schema;
  }

  @Override
  public JsonElement execute(JsonObject args, User user) throws AiToolException {
    if (!args.has("tld") || args.get("tld").isJsonNull()) {
      throw new AiToolException("Missing required arg: tld");
    }
    String tld = args.get("tld").getAsString();
    if (!args.has("months_ahead") || args.get("months_ahead").isJsonNull()) {
      throw new AiToolException("Missing required arg: months_ahead");
    }
    int monthsAhead;
    try {
      monthsAhead = args.get("months_ahead").getAsInt();
    } catch (NumberFormatException | UnsupportedOperationException e) {
      throw new AiToolException("months_ahead must be an integer");
    }
    monthsAhead = Math.max(MIN_MONTHS, Math.min(MAX_MONTHS, monthsAhead));

    ToolJpaHelper.assertTldAccess(user, tld);
    ImmutableSet<String> effectiveTlds = ToolJpaHelper.effectiveTlds(user, tld);

    LocalDate today = LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
    LocalDate endDate = today.plusMonths(monthsAhead);

    ExploreQueryDescriptor desc =
        ToolJpaHelper.descriptor(
            ExploreDataSource.EXPIRATION_CURVE.name(),
            List.of("tld", "month"),
            List.of("count"),
            List.of(tld),
            List.of(),
            List.of(),
            List.of(),
            today.toString(),
            endDate.toString());

    return ToolJpaHelper.runExplore(
        ExploreDataSource.EXPIRATION_CURVE, desc, effectiveTlds, COLUMNS, MAX_ROWS);
  }
}
