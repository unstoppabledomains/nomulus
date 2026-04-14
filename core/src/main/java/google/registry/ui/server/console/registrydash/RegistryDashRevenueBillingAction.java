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
import static google.registry.request.Action.Method.GET;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Returns revenue and billing data for the registry dashboard with configurable granularity.
 *
 * <p>Supports lookbackHours + granularity params for fine-grained time windows. The legacy
 * "months" param is still accepted for backward compatibility.
 */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashRevenueBillingAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashRevenueBillingAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/revenue-billing";

  private static final Set<String> VALID_GRANULARITIES = Set.of("15min", "hour", "day", "month");

  // --- Standard granularity SQL (hour, day, month) ---
  // Uses DomainHistory.history_modification_time for the period timestamp instead of
  // BillingEvent.event_time. For most operations these are identical, but for transfers
  // the BillingEvent.event_time is set to the speculative auto-approval date (days/weeks
  // in the future), while DomainHistory records when the action was actually taken.

  private static final String REVENUE_ALL_TEMPLATE =
      """
      SELECT date_trunc('%s', dh.history_modification_time) AS period,
             d.tld, b.reason,
             SUM(b.cost_amount) AS total_amount,
             SUM(b.cost_amount - COALESCE(cb.rsp_retained_fee_amount, 0))
               AS total_net_amount_to_registry,
             b.cost_currency
      FROM "BillingEvent" b
      JOIN "Domain" d ON d.repo_id = b.domain_repo_id
      JOIN "DomainHistory" dh ON dh.history_revision_id = b.domain_history_revision_id
        AND dh.domain_repo_id = b.domain_repo_id
      LEFT JOIN LATERAL (
        SELECT rsp_retained_fee_amount
        FROM "RegistryDashboardCostBasis"
        WHERE (tld = d.tld OR tld = '*')
          AND operation = b.reason
          AND effective_date <= dh.history_modification_time
        ORDER BY CASE WHEN tld = d.tld THEN 0 ELSE 1 END,
                 effective_date DESC
        LIMIT 1
      ) cb ON true
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_modification_time <= :endDate
        AND b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')
      GROUP BY date_trunc('%s', dh.history_modification_time), d.tld, b.reason, b.cost_currency
      ORDER BY period, d.tld
      """;

  private static final String REVENUE_SCOPED_TEMPLATE =
      """
      SELECT date_trunc('%s', dh.history_modification_time) AS period,
             d.tld, b.reason,
             SUM(b.cost_amount) AS total_amount,
             SUM(b.cost_amount - COALESCE(cb.rsp_retained_fee_amount, 0))
               AS total_net_amount_to_registry,
             b.cost_currency
      FROM "BillingEvent" b
      JOIN "Domain" d ON d.repo_id = b.domain_repo_id
      JOIN "DomainHistory" dh ON dh.history_revision_id = b.domain_history_revision_id
        AND dh.domain_repo_id = b.domain_repo_id
      LEFT JOIN LATERAL (
        SELECT rsp_retained_fee_amount
        FROM "RegistryDashboardCostBasis"
        WHERE (tld = d.tld OR tld = '*')
          AND operation = b.reason
          AND effective_date <= dh.history_modification_time
        ORDER BY CASE WHEN tld = d.tld THEN 0 ELSE 1 END,
                 effective_date DESC
        LIMIT 1
      ) cb ON true
      WHERE dh.history_modification_time >= :startDate
        AND d.tld IN :tlds
        AND b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')
      GROUP BY date_trunc('%s', dh.history_modification_time), d.tld, b.reason, b.cost_currency
      ORDER BY period, d.tld
      """;

  // --- 15-minute bucket SQL ---

  private static final String REVENUE_15MIN_ALL =
      """
      SELECT date_trunc('hour', dh.history_modification_time)
               + floor(extract(minute from dh.history_modification_time) / 15)
                 * interval '15 minutes' AS period,
             d.tld, b.reason,
             SUM(b.cost_amount) AS total_amount,
             SUM(b.cost_amount - COALESCE(cb.rsp_retained_fee_amount, 0))
               AS total_net_amount_to_registry,
             b.cost_currency
      FROM "BillingEvent" b
      JOIN "Domain" d ON d.repo_id = b.domain_repo_id
      JOIN "DomainHistory" dh ON dh.history_revision_id = b.domain_history_revision_id
        AND dh.domain_repo_id = b.domain_repo_id
      LEFT JOIN LATERAL (
        SELECT rsp_retained_fee_amount
        FROM "RegistryDashboardCostBasis"
        WHERE (tld = d.tld OR tld = '*')
          AND operation = b.reason
          AND effective_date <= dh.history_modification_time
        ORDER BY CASE WHEN tld = d.tld THEN 0 ELSE 1 END,
                 effective_date DESC
        LIMIT 1
      ) cb ON true
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_modification_time <= :endDate
        AND b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')
      GROUP BY period, d.tld, b.reason, b.cost_currency
      ORDER BY period, d.tld
      """;

  private static final String REVENUE_15MIN_SCOPED =
      """
      SELECT date_trunc('hour', dh.history_modification_time)
               + floor(extract(minute from dh.history_modification_time) / 15)
                 * interval '15 minutes' AS period,
             d.tld, b.reason,
             SUM(b.cost_amount) AS total_amount,
             SUM(b.cost_amount - COALESCE(cb.rsp_retained_fee_amount, 0))
               AS total_net_amount_to_registry,
             b.cost_currency
      FROM "BillingEvent" b
      JOIN "Domain" d ON d.repo_id = b.domain_repo_id
      JOIN "DomainHistory" dh ON dh.history_revision_id = b.domain_history_revision_id
        AND dh.domain_repo_id = b.domain_repo_id
      LEFT JOIN LATERAL (
        SELECT rsp_retained_fee_amount
        FROM "RegistryDashboardCostBasis"
        WHERE (tld = d.tld OR tld = '*')
          AND operation = b.reason
          AND effective_date <= dh.history_modification_time
        ORDER BY CASE WHEN tld = d.tld THEN 0 ELSE 1 END,
                 effective_date DESC
        LIMIT 1
      ) cb ON true
      WHERE dh.history_modification_time >= :startDate
        AND d.tld IN :tlds
        AND b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')
      GROUP BY period, d.tld, b.reason, b.cost_currency
      ORDER BY period, d.tld
      """;

  private final Optional<Integer> months;
  private final Optional<Integer> lookbackHours;
  private final Optional<String> granularity;
  private final java.time.Clock clock;

  @Inject
  public RegistryDashRevenueBillingAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("months") Optional<Integer> months,
      @Parameter("lookbackHours") Optional<Integer> lookbackHours,
      @Parameter("granularity") Optional<String> granularity) {
    this(consoleApiParams, months, lookbackHours, granularity, java.time.Clock.systemUTC());
  }

  /** Constructor that accepts a clock for testing. */
  RegistryDashRevenueBillingAction(
      ConsoleApiParams consoleApiParams,
      Optional<Integer> months,
      Optional<Integer> lookbackHours,
      Optional<String> granularity,
      java.time.Clock clock) {
    super(consoleApiParams);
    this.months = months;
    this.lookbackHours = lookbackHours;
    this.granularity = granularity;
    this.clock = clock;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_REVENUE_BILLING)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> tlds =
        isAdmin ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    if (!isAdmin && tlds.isEmpty()) {
      Map<String, Object> empty = new HashMap<>();
      empty.put("periodRevenue", List.of());
      Map<String, Object> emptyTotals = new HashMap<>();
      emptyTotals.put("totalRevenue", 0);
      emptyTotals.put("totalNetAmountToRegistry", 0);
      emptyTotals.put("currency", "USD");
      emptyTotals.put("byOperation", Map.of());
      emptyTotals.put("byOperationNetAmountToRegistry", Map.of());
      empty.put("totals", emptyTotals);
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(empty));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    // Resolve lookback period: prefer lookbackHours, fall back to months param
    int hoursBack;
    if (lookbackHours.isPresent()) {
      hoursBack = Math.max(1, Math.min(lookbackHours.get(), 17520));
    } else {
      int lookbackMonths = months.orElse(12);
      hoursBack = lookbackMonths * 730;
    }

    // Resolve and validate granularity
    String gran = granularity.orElse("month");
    if (!VALID_GRANULARITIES.contains(gran)) {
      gran = "month";
    }

    ZonedDateTime now = ZonedDateTime.now(clock);
    Instant startDate = now.minus(hoursBack, ChronoUnit.HOURS).toInstant();
    Instant endDate = now.toInstant();

    String resolvedGran = gran;
    tm().transact(
        () -> {
          String sql = buildSql(resolvedGran, isAdmin);

          @SuppressWarnings("unchecked")
          List<Object[]> results;
          if (isAdmin) {
            results = tm().getEntityManager()
                .createNativeQuery(sql)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .getResultList();
          } else {
            results = tm().getEntityManager()
                .createNativeQuery(sql)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setParameter("tlds", tlds)
                .getResultList();
          }

          List<Map<String, Object>> periodRevenue = new ArrayList<>();
          BigDecimal totalRevenue = BigDecimal.ZERO;
          BigDecimal totalNetAmountToRegistry = BigDecimal.ZERO;
          Map<String, BigDecimal> byOperation = new HashMap<>();
          Map<String, BigDecimal> byOperationNetAmountToRegistry = new HashMap<>();
          String currency = "USD";

          for (Object[] row : results) {
            // PostgreSQL may return Timestamp, Instant, or OffsetDateTime depending on the query
            Instant periodInstant;
            Object periodRaw = row[0];
            if (periodRaw instanceof java.sql.Timestamp ts) {
              periodInstant = ts.toInstant();
            } else if (periodRaw instanceof Instant inst) {
              periodInstant = inst;
            } else if (periodRaw instanceof java.time.OffsetDateTime odt) {
              periodInstant = odt.toInstant();
            } else {
              periodInstant = Instant.parse(periodRaw.toString());
            }
            String tld = (String) row[1];
            String operation = (String) row[2];
            BigDecimal amount = (BigDecimal) row[3];
            BigDecimal netAmountToRegistry = row[4] != null ? (BigDecimal) row[4] : BigDecimal.ZERO;
            String cur = (String) row[5];

            Map<String, Object> entry = new HashMap<>();
            entry.put("period", formatPeriod(periodInstant, resolvedGran));
            entry.put("tld", tld);
            entry.put("operation", operation);
            entry.put("amount", amount);
            entry.put("netAmountToRegistry", netAmountToRegistry);
            entry.put("currency", cur);
            periodRevenue.add(entry);

            totalRevenue = totalRevenue.add(amount);
            totalNetAmountToRegistry = totalNetAmountToRegistry.add(netAmountToRegistry);
            byOperation.merge(operation, amount, BigDecimal::add);
            byOperationNetAmountToRegistry.merge(operation, netAmountToRegistry, BigDecimal::add);
            currency = cur;
          }

          Map<String, Object> totals = new HashMap<>();
          totals.put("totalRevenue", totalRevenue);
          totals.put("totalNetAmountToRegistry", totalNetAmountToRegistry);
          totals.put("currency", currency);
          totals.put("byOperation", byOperation);
          totals.put("byOperationNetAmountToRegistry", byOperationNetAmountToRegistry);

          Map<String, Object> response = new HashMap<>();
          response.put("periodRevenue", periodRevenue);
          response.put("totals", totals);

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private static String buildSql(String granularity, boolean isAdmin) {
    if ("15min".equals(granularity)) {
      return isAdmin ? REVENUE_15MIN_ALL : REVENUE_15MIN_SCOPED;
    }
    // For hour, day, month — use the template with String.format
    // These values are from a validated allowlist, safe to interpolate
    String truncArg = granularity;
    return isAdmin
        ? String.format(REVENUE_ALL_TEMPLATE, truncArg, truncArg)
        : String.format(REVENUE_SCOPED_TEMPLATE, truncArg, truncArg);
  }

  private static String formatPeriod(Instant instant, String granularity) {
    ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
    return switch (granularity) {
      case "month" -> zdt.toLocalDate().toString().substring(0, 7);
      case "day" -> zdt.toLocalDate().toString();
      default -> instant.toString();
    };
  }
}
