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

/** Returns domain transaction activity data for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashDomainActivityAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashDomainActivityAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/domain-activity";

  private static final Set<String> VALID_GRANULARITIES = Set.of("15min", "hour", "day", "month");

  // --- Standard granularity SQL (hour, day, month) ---
  // Uses DomainHistory.history_modification_time (actual event time) instead of
  // DomainTransactionRecord.reporting_time (which includes grace period offsets
  // that push dates into the future by days or weeks).

  private static final String ACTIVITY_ALL_TEMPLATE =
      """
      SELECT date_trunc('%s', dh.history_modification_time) AS period,
             d.tld,
             CASE
               WHEN dh.history_type = 'DOMAIN_CREATE' THEN 'CREATES'
               WHEN dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 'RENEWS'
               WHEN dh.history_type = 'DOMAIN_TRANSFER_APPROVE' THEN 'TRANSFERS'
               WHEN dh.history_type = 'DOMAIN_DELETE' THEN 'DELETES'
               WHEN dh.history_type = 'DOMAIN_RESTORE' THEN 'RESTORES'
               ELSE 'OTHER'
             END AS activity_type,
             COUNT(*) AS total_count
      FROM "DomainHistory" dh
      JOIN "Domain" d ON d.repo_id = dh.domain_repo_id
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_modification_time <= CURRENT_TIMESTAMP
        AND dh.history_type IN (
          'DOMAIN_CREATE', 'DOMAIN_RENEW', 'DOMAIN_AUTORENEW',
          'DOMAIN_TRANSFER_APPROVE', 'DOMAIN_DELETE', 'DOMAIN_RESTORE')
      GROUP BY period, d.tld, activity_type
      ORDER BY period, d.tld
      """;

  private static final String ACTIVITY_SCOPED_TEMPLATE =
      """
      SELECT date_trunc('%s', dh.history_modification_time) AS period,
             d.tld,
             CASE
               WHEN dh.history_type = 'DOMAIN_CREATE' THEN 'CREATES'
               WHEN dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 'RENEWS'
               WHEN dh.history_type = 'DOMAIN_TRANSFER_APPROVE' THEN 'TRANSFERS'
               WHEN dh.history_type = 'DOMAIN_DELETE' THEN 'DELETES'
               WHEN dh.history_type = 'DOMAIN_RESTORE' THEN 'RESTORES'
               ELSE 'OTHER'
             END AS activity_type,
             COUNT(*) AS total_count
      FROM "DomainHistory" dh
      JOIN "Domain" d ON d.repo_id = dh.domain_repo_id
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_modification_time <= CURRENT_TIMESTAMP
        AND d.tld IN :tlds
        AND dh.history_type IN (
          'DOMAIN_CREATE', 'DOMAIN_RENEW', 'DOMAIN_AUTORENEW',
          'DOMAIN_TRANSFER_APPROVE', 'DOMAIN_DELETE', 'DOMAIN_RESTORE')
      GROUP BY period, d.tld, activity_type
      ORDER BY period, d.tld
      """;

  // --- 15-minute bucket SQL ---

  private static final String ACTIVITY_15MIN_ALL =
      """
      SELECT date_trunc('hour', dh.history_modification_time)
               + floor(extract(minute from dh.history_modification_time) / 15)
               * interval '15 minutes' AS period,
             d.tld,
             CASE
               WHEN dh.history_type = 'DOMAIN_CREATE' THEN 'CREATES'
               WHEN dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 'RENEWS'
               WHEN dh.history_type = 'DOMAIN_TRANSFER_APPROVE' THEN 'TRANSFERS'
               WHEN dh.history_type = 'DOMAIN_DELETE' THEN 'DELETES'
               WHEN dh.history_type = 'DOMAIN_RESTORE' THEN 'RESTORES'
               ELSE 'OTHER'
             END AS activity_type,
             COUNT(*) AS total_count
      FROM "DomainHistory" dh
      JOIN "Domain" d ON d.repo_id = dh.domain_repo_id
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_modification_time <= CURRENT_TIMESTAMP
        AND dh.history_type IN (
          'DOMAIN_CREATE', 'DOMAIN_RENEW', 'DOMAIN_AUTORENEW',
          'DOMAIN_TRANSFER_APPROVE', 'DOMAIN_DELETE', 'DOMAIN_RESTORE')
      GROUP BY period, d.tld, activity_type
      ORDER BY period, d.tld
      """;

  private static final String ACTIVITY_15MIN_SCOPED =
      """
      SELECT date_trunc('hour', dh.history_modification_time)
               + floor(extract(minute from dh.history_modification_time) / 15)
               * interval '15 minutes' AS period,
             d.tld,
             CASE
               WHEN dh.history_type = 'DOMAIN_CREATE' THEN 'CREATES'
               WHEN dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 'RENEWS'
               WHEN dh.history_type = 'DOMAIN_TRANSFER_APPROVE' THEN 'TRANSFERS'
               WHEN dh.history_type = 'DOMAIN_DELETE' THEN 'DELETES'
               WHEN dh.history_type = 'DOMAIN_RESTORE' THEN 'RESTORES'
               ELSE 'OTHER'
             END AS activity_type,
             COUNT(*) AS total_count
      FROM "DomainHistory" dh
      JOIN "Domain" d ON d.repo_id = dh.domain_repo_id
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_modification_time <= CURRENT_TIMESTAMP
        AND d.tld IN :tlds
        AND dh.history_type IN (
          'DOMAIN_CREATE', 'DOMAIN_RENEW', 'DOMAIN_AUTORENEW',
          'DOMAIN_TRANSFER_APPROVE', 'DOMAIN_DELETE', 'DOMAIN_RESTORE')
      GROUP BY period, d.tld, activity_type
      ORDER BY period, d.tld
      """;

  private static final String CURRENT_COUNTS_ALL =
      """
      SELECT d.tld, COUNT(d) FROM Domain d
      WHERE d.deletionTime > CURRENT_TIMESTAMP
      GROUP BY d.tld
      """;

  private static final String CURRENT_COUNTS_SCOPED =
      """
      SELECT d.tld, COUNT(d) FROM Domain d
      WHERE d.deletionTime > CURRENT_TIMESTAMP
        AND d.tld IN :tlds
      GROUP BY d.tld
      """;

  private final Optional<Integer> months;
  private final Optional<Integer> lookbackHours;
  private final Optional<String> granularity;

  @Inject
  public RegistryDashDomainActivityAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("months") Optional<Integer> months,
      @Parameter("lookbackHours") Optional<Integer> lookbackHours,
      @Parameter("granularity") Optional<String> granularity) {
    super(consoleApiParams);
    this.months = months;
    this.lookbackHours = lookbackHours;
    this.granularity = granularity;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DOMAIN_ACTIVITY)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> tlds =
        isAdmin ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    if (!isAdmin && tlds.isEmpty()) {
      Map<String, Object> empty = new HashMap<>();
      empty.put("activity", List.of());
      empty.put("currentCounts", Map.of());
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

    Instant startDate = ZonedDateTime.now(ZoneOffset.UTC)
        .minus(hoursBack, ChronoUnit.HOURS).toInstant();

    String resolvedGran = gran;
    tm().transact(
        () -> {
          String sql = buildSql(resolvedGran, isAdmin);

          // Activity data from DomainHistory (uses actual event time)
          @SuppressWarnings("unchecked")
          List<Object[]> activityResults;
          if (isAdmin) {
            activityResults = tm().getEntityManager()
                .createNativeQuery(sql)
                .setParameter("startDate", startDate)
                .getResultList();
          } else {
            activityResults = tm().getEntityManager()
                .createNativeQuery(sql)
                .setParameter("startDate", startDate)
                .setParameter("tlds", tlds)
                .getResultList();
          }

          List<Map<String, Object>> activity = new ArrayList<>();
          for (Object[] row : activityResults) {
            Instant period;
            Object periodRaw = row[0];
            if (periodRaw instanceof java.sql.Timestamp ts) {
              period = ts.toInstant();
            } else if (periodRaw instanceof Instant inst) {
              period = inst;
            } else if (periodRaw instanceof java.time.OffsetDateTime odt) {
              period = odt.toInstant();
            } else {
              period = Instant.parse(periodRaw.toString());
            }
            String tld = (String) row[1];
            String type = (String) row[2];
            Number count = (Number) row[3];

            Map<String, Object> entry = new HashMap<>();
            entry.put("period", formatPeriod(period, resolvedGran));
            entry.put("tld", tld);
            entry.put("type", type);
            entry.put("count", count.longValue());
            activity.add(entry);
          }

          // Current domain counts by TLD
          @SuppressWarnings("unchecked")
          List<Object[]> countResults =
              isAdmin
                  ? tm().getEntityManager()
                      .createQuery(CURRENT_COUNTS_ALL)
                      .getResultList()
                  : tm().getEntityManager()
                      .createQuery(CURRENT_COUNTS_SCOPED)
                      .setParameter("tlds", tlds)
                      .getResultList();

          Map<String, Long> currentCounts = new HashMap<>();
          for (Object[] row : countResults) {
            currentCounts.put((String) row[0], (Long) row[1]);
          }

          Map<String, Object> response = new HashMap<>();
          response.put("activity", activity);
          response.put("currentCounts", currentCounts);

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private static String buildSql(String granularity, boolean isAdmin) {
    if ("15min".equals(granularity)) {
      return isAdmin ? ACTIVITY_15MIN_ALL : ACTIVITY_15MIN_SCOPED;
    }
    // For hour, day, month — use the template with String.format
    // These values are from a validated allowlist, safe to interpolate
    return isAdmin
        ? String.format(ACTIVITY_ALL_TEMPLATE, granularity)
        : String.format(ACTIVITY_SCOPED_TEMPLATE, granularity);
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
