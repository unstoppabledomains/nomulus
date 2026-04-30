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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import google.registry.ai.tools.AiTool.AiToolException;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.ui.server.console.registrydash.ExploreDataSource;
import google.registry.ui.server.console.registrydash.ExploreQueryBuilder;
import google.registry.ui.server.console.registrydash.ExploreQueryDescriptor;
import google.registry.ui.server.console.registrydash.RegistryDashAccessUtil;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers for AI tools that wrap the Explore query infrastructure. */
final class ToolJpaHelper {

  private ToolJpaHelper() {}

  /** Resolves the user's TLD access scope, intersecting with a single requested TLD if given. */
  static ImmutableSet<String> effectiveTlds(User user, String requestedTld) {
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> accessTlds =
        isAdmin
            ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    ImmutableSet<String> requested =
        requestedTld == null || requestedTld.isEmpty()
            ? ImmutableSet.of()
            : ImmutableSet.of(requestedTld);
    return RegistryDashAccessUtil.applyFilter(accessTlds, requested, isAdmin);
  }

  /** Asserts the requested TLD is in the user's access scope (admins bypass). */
  static void assertTldAccess(User user, String tld) throws AiToolException {
    if (tld == null || tld.isEmpty()) {
      return;
    }
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (isAdmin) {
      return;
    }
    ImmutableSet<String> accessTlds =
        RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    if (!accessTlds.contains(tld)) {
      throw new AiToolException("Permission denied for tld: " + tld);
    }
  }

  /**
   * Runs an Explore-engine query and returns the rows as a JSON array of {@code {column: value}}
   * objects. No statement-timeout applied.
   */
  static JsonObject runExplore(
      ExploreDataSource source,
      ExploreQueryDescriptor desc,
      ImmutableSet<String> effectiveTlds,
      List<String> columns,
      int maxRows)
      throws AiToolException {
    return runExplore(source, desc, effectiveTlds, columns, maxRows, 0);
  }

  /**
   * Runs an Explore-engine query with an optional SQL {@code statement_timeout}. When {@code
   * statementTimeoutSeconds > 0}, the timeout is applied via {@code SET LOCAL statement_timeout}
   * inside the same transaction; on exceedance the underlying {@code PSQLException} is converted to
   * a user-visible {@link AiToolException}. When {@code statementTimeoutSeconds <= 0}, no timeout
   * is applied (existing behavior).
   */
  static JsonObject runExplore(
      ExploreDataSource source,
      ExploreQueryDescriptor desc,
      ImmutableSet<String> effectiveTlds,
      List<String> columns,
      int maxRows,
      int statementTimeoutSeconds)
      throws AiToolException {
    try {
      source.validate(desc);
    } catch (IllegalArgumentException e) {
      throw new AiToolException(e.getMessage());
    }
    String sql = ExploreQueryBuilder.build(source, desc, effectiveTlds);

    ExploreQueryDescriptor.ExploreFilters filters = desc.getFilters();
    Instant startDate = null;
    Instant endDate = null;
    if (filters.getDateRange() != null) {
      try {
        startDate = parseDateTime(filters.getDateRange().getStart(), false);
        endDate = parseDateTime(filters.getDateRange().getEnd(), true);
      } catch (Exception e) {
        throw new AiToolException("Invalid date_range: " + e.getMessage());
      }
    }
    final Instant fStart = startDate;
    final Instant fEnd = endDate;

    try {
      return tm().transact(
          () -> {
            if (statementTimeoutSeconds > 0) {
              tm().getEntityManager()
                  .createNativeQuery(
                      "SET LOCAL statement_timeout = " + (statementTimeoutSeconds * 1000L))
                  .executeUpdate();
            }
            Query query = tm().getEntityManager().createNativeQuery(sql);
            query.setParameter("maxRows", maxRows);
            if (!effectiveTlds.isEmpty() && sql.contains(":tlds")) {
              query.setParameter("tlds", effectiveTlds);
            }
            if (fStart != null && sql.contains(":startDate")) {
              query.setParameter("startDate", fStart);
            }
            if (fEnd != null && sql.contains(":endDate")) {
              query.setParameter("endDate", fEnd);
            }
            if (!filters.getOperations().isEmpty() && sql.contains(":operations")) {
              query.setParameter("operations", filters.getOperations());
            }
            if (!filters.getRegistrarIds().isEmpty() && sql.contains(":registrarIds")) {
              query.setParameter("registrarIds", filters.getRegistrarIds());
            }
            if (!filters.getActivityTypes().isEmpty() && sql.contains(":activityTypes")) {
              query.setParameter("activityTypes", filters.getActivityTypes());
            }

            @SuppressWarnings("unchecked")
            List<Object> raw = query.getResultList();

            JsonArray rows = new JsonArray();
            for (Object r : raw) {
              JsonObject rowObj = new JsonObject();
              if (r instanceof Object[] arr) {
                for (int i = 0; i < arr.length && i < columns.size(); i++) {
                  addNormalized(rowObj, columns.get(i), arr[i]);
                }
              } else if (!columns.isEmpty()) {
                addNormalized(rowObj, columns.get(0), r);
              }
              rows.add(rowObj);
            }

            JsonObject out = new JsonObject();
            out.add("rows", rows);
            out.addProperty("rowCount", rows.size());
            out.addProperty("truncated", rows.size() >= maxRows);
            return out;
          });
    } catch (RuntimeException e) {
      if (isStatementTimeout(e)) {
        throw new AiToolException(
            "Query exceeded "
                + statementTimeoutSeconds
                + "s — try a narrower date range or smaller scope.");
      }
      throw e;
    }
  }

  /** Returns true if {@code e}'s cause chain contains a Postgres statement-timeout cancellation. */
  private static boolean isStatementTimeout(Throwable e) {
    Throwable cur = e;
    while (cur != null) {
      String msg = cur.getMessage();
      if (msg != null && Ascii.toLowerCase(msg).contains("statement timeout")) {
        return true;
      }
      // PostgreSQL SQLSTATE 57014 = query_canceled (the code statement_timeout uses).
      if (cur instanceof java.sql.SQLException sqlEx && "57014".equals(sqlEx.getSQLState())) {
        return true;
      }
      cur = cur.getCause();
    }
    return false;
  }

  private static void addNormalized(JsonObject obj, String key, Object val) {
    if (val == null) {
      obj.add(key, com.google.gson.JsonNull.INSTANCE);
      return;
    }
    String stringified = normalize(val);
    if (val instanceof Number n) {
      obj.addProperty(key, n);
    } else if (val instanceof Boolean b) {
      obj.addProperty(key, b);
    } else {
      obj.addProperty(key, stringified);
    }
  }

  private static String normalize(Object val) {
    if (val instanceof java.sql.Timestamp ts) {
      return ts.toInstant().toString();
    }
    if (val instanceof java.time.OffsetDateTime odt) {
      return odt.toInstant().toString();
    }
    if (val instanceof Instant inst) {
      return inst.toString();
    }
    return val.toString();
  }

  static Instant parseDateTime(String value, boolean isEndDate) {
    try {
      return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      LocalDate date = LocalDate.parse(value);
      if (isEndDate) {
        return date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
      }
      return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
  }

  /**
   * Builds an {@link ExploreQueryDescriptor} programmatically by serializing args to JSON and
   * deserializing into the descriptor (whose fields are otherwise private with no setters).
   */
  static ExploreQueryDescriptor descriptor(
      String dataSource,
      List<String> dimensions,
      List<String> metrics,
      List<String> tlds,
      List<String> registrarIds,
      List<String> operations,
      List<String> activityTypes,
      String startDate,
      String endDate) {
    Map<String, Object> raw = new HashMap<>();
    raw.put("dataSource", dataSource);
    raw.put("dimensions", dimensions);
    if (metrics != null && !metrics.isEmpty()) {
      java.util.List<Map<String, String>> metricSpecs = new java.util.ArrayList<>();
      for (String m : metrics) {
        metricSpecs.add(Map.of("field", m, "aggregation", "sum"));
      }
      raw.put("metrics", metricSpecs);
    }

    Map<String, Object> filters = new HashMap<>();
    if (tlds != null && !tlds.isEmpty()) {
      filters.put("tlds", tlds);
    }
    if (registrarIds != null && !registrarIds.isEmpty()) {
      filters.put("registrarIds", registrarIds);
    }
    if (operations != null && !operations.isEmpty()) {
      filters.put("operations", operations);
    }
    if (activityTypes != null && !activityTypes.isEmpty()) {
      filters.put("activityTypes", activityTypes);
    }
    if (startDate != null && endDate != null) {
      filters.put("dateRange", Map.of("start", startDate, "end", endDate));
    }
    if (!filters.isEmpty()) {
      raw.put("filters", filters);
    }

    com.google.gson.Gson gson = new com.google.gson.Gson();
    return gson.fromJson(gson.toJsonTree(raw), ExploreQueryDescriptor.class);
  }
}
