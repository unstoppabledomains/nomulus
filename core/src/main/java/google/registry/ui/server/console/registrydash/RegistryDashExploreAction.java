// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console.registrydash;

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** POST endpoint for flexible data exploration queries on the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashExploreAction.PATH,
    method = {POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashExploreAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/explore";

  private final Optional<ExploreQueryDescriptor> exploreQuery;

  @Inject
  public RegistryDashExploreAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registryDashExplore") Optional<ExploreQueryDescriptor> exploreQuery) {
    super(consoleApiParams);
    this.exploreQuery = exploreQuery;
  }

  @Override
  protected void postHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DATA_EXPLORE)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    if (exploreQuery.isEmpty()) {
      consoleApiParams.response().setStatus(SC_BAD_REQUEST);
      consoleApiParams.response().setPayload("{\"error\":\"Missing request body\"}");
      return;
    }

    ExploreQueryDescriptor desc = exploreQuery.get();

    // Parse and validate the data source
    ExploreDataSource source;
    try {
      source = ExploreDataSource.valueOf(desc.getDataSource());
    } catch (IllegalArgumentException | NullPointerException e) {
      consoleApiParams.response().setStatus(SC_BAD_REQUEST);
      consoleApiParams.response().setPayload(
          "{\"error\":\"Unknown dataSource: " + desc.getDataSource() + "\"}");
      return;
    }

    // Validate the descriptor against the source's allowlist
    try {
      source.validate(desc);
    } catch (IllegalArgumentException e) {
      consoleApiParams.response().setStatus(SC_BAD_REQUEST);
      consoleApiParams.response().setPayload(
          "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
      return;
    }

    // Resolve access scoping
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> accessTlds =
        isAdmin ? ImmutableSet.of() : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    ImmutableSet<String> requestedTlds =
        ImmutableSet.copyOf(desc.getFilters().getTlds());
    ImmutableSet<String> effectiveTlds =
        RegistryDashAccessUtil.applyFilter(accessTlds, requestedTlds, isAdmin);

    // Build SQL
    String sql;
    try {
      sql = ExploreQueryBuilder.build(source, desc, effectiveTlds);
    } catch (IllegalArgumentException e) {
      consoleApiParams.response().setStatus(SC_BAD_REQUEST);
      consoleApiParams.response().setPayload(
          "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
      return;
    }

    // Resolve date range (if present)
    ExploreQueryDescriptor.ExploreFilters filters = desc.getFilters();
    Instant startDate = null;
    Instant endDate = null;
    if (filters.getDateRange() != null) {
      try {
        startDate =
            LocalDate.parse(filters.getDateRange().getStart())
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        endDate =
            LocalDate.parse(filters.getDateRange().getEnd())
                .plusDays(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
      } catch (Exception e) {
        consoleApiParams.response().setStatus(SC_BAD_REQUEST);
        consoleApiParams.response().setPayload("{\"error\":\"Invalid date range format\"}");
        return;
      }
    }

    // Map activity types from friendly names to DB values
    List<String> mappedActivityTypes = new ArrayList<>();
    for (String friendly : filters.getActivityTypes()) {
      mappedActivityTypes.addAll(mapActivityType(friendly));
    }

    int maxRows = desc.getLimit();

    // Capture final locals for lambda
    final Instant finalStartDate = startDate;
    final Instant finalEndDate = endDate;
    final List<String> finalMappedActivityTypes = mappedActivityTypes;
    final ImmutableSet<String> finalEffectiveTlds = effectiveTlds;

    // Build column names for response
    List<String> columns = buildColumnNames(source, desc);

    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          jakarta.persistence.Query query =
              tm().getEntityManager().createNativeQuery(sql);

          query.setParameter("maxRows", maxRows);

          if (!finalEffectiveTlds.isEmpty()) {
            query.setParameter("tlds", finalEffectiveTlds);
          }
          if (finalStartDate != null) {
            query.setParameter("startDate", finalStartDate);
          }
          if (finalEndDate != null) {
            query.setParameter("endDate", finalEndDate);
          }
          if (!finalMappedActivityTypes.isEmpty()) {
            query.setParameter("activityTypes", finalMappedActivityTypes);
          }
          if (!filters.getOperations().isEmpty()) {
            query.setParameter("operations", filters.getOperations());
          }
          if (!filters.getRegistrarIds().isEmpty()) {
            query.setParameter("registrarIds", filters.getRegistrarIds());
          }

          @SuppressWarnings("unchecked")
          List<Object> rawResults = query.getResultList();

          // Normalize rows: each may be Object[] or a single scalar
          List<List<Object>> rows = new ArrayList<>();
          for (Object raw : rawResults) {
            List<Object> rowList = new ArrayList<>();
            if (raw instanceof Object[] arr) {
              for (Object cell : arr) {
                rowList.add(normalizeValue(cell));
              }
            } else {
              rowList.add(normalizeValue(raw));
            }
            rows.add(rowList);
          }

          boolean truncated = rows.size() >= maxRows;

          Map<String, Object> response = new HashMap<>();
          response.put("columns", columns);
          response.put("rows", rows);
          response.put("truncated", truncated);
          response.put("totalRows", rows.size());

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private static List<String> buildColumnNames(
      ExploreDataSource source, ExploreQueryDescriptor desc) {
    List<String> cols = new ArrayList<>();
    // Dimensions first
    for (String dim : desc.getDimensions()) {
      cols.add(dim);
    }
    // Metrics: field + "_" + aggregation
    for (ExploreQueryDescriptor.MetricSpec m : desc.getMetrics()) {
      cols.add(m.getField() + "_" + m.getAggregation());
    }
    // Currency column for revenue/pricing sources
    if (source == ExploreDataSource.REVENUE || source == ExploreDataSource.PRICING_RULES) {
      cols.add("currency");
    }
    return cols;
  }

  private static Object normalizeValue(Object val) {
    if (val == null) {
      return null;
    }
    if (val instanceof java.sql.Timestamp ts) {
      return ts.toInstant().toString();
    }
    if (val instanceof java.time.OffsetDateTime odt) {
      return odt.toInstant().toString();
    }
    if (val instanceof Instant inst) {
      return inst.toString();
    }
    return val;
  }

  private static List<String> mapActivityType(String friendlyName) {
    return switch (friendlyName) {
      case "CREATES" -> List.of("DOMAIN_CREATE");
      case "RENEWS" -> List.of("DOMAIN_RENEW", "DOMAIN_AUTORENEW");
      case "TRANSFERS" -> List.of("DOMAIN_TRANSFER_APPROVE");
      case "DELETES" -> List.of("DOMAIN_DELETE");
      case "RESTORES" -> List.of("DOMAIN_RESTORE");
      default -> List.of();
    };
  }
}
