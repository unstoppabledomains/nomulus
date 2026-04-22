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
import java.math.RoundingMode;
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

/** Returns domain expiration forecasting and renewal rate data for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashForecastingAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashForecastingAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/forecasting";

  private static final Set<String> VALID_GRANULARITIES = Set.of("15min", "hour", "day", "month");

  // --- Expiration curve: standard granularity (hour, day, month) via JPQL ---

  private static final String EXPIRATION_CURVE_ALL_TEMPLATE =
      """
      SELECT date_trunc('%s', d.registrationExpirationTime) AS exp_period,
             d.tld, COUNT(d) AS domain_count
      FROM Domain d
      WHERE d.deletionTime > CURRENT_TIMESTAMP
        AND d.registrationExpirationTime > CURRENT_TIMESTAMP
        AND d.registrationExpirationTime < :endDate
      GROUP BY exp_period, d.tld
      ORDER BY exp_period
      """;

  private static final String EXPIRATION_CURVE_SCOPED_TEMPLATE =
      """
      SELECT date_trunc('%s', d.registrationExpirationTime) AS exp_period,
             d.tld, COUNT(d) AS domain_count
      FROM Domain d
      WHERE d.deletionTime > CURRENT_TIMESTAMP
        AND d.registrationExpirationTime > CURRENT_TIMESTAMP
        AND d.registrationExpirationTime < :endDate
        AND d.tld IN :tlds
      GROUP BY exp_period, d.tld
      ORDER BY exp_period
      """;

  // --- Expiration curve: 15-minute buckets via native SQL ---

  private static final String EXPIRATION_CURVE_15MIN_ALL =
      """
      SELECT date_trunc('hour', d.registration_expiration_time)
               + floor(extract(minute from d.registration_expiration_time) / 15)
                 * interval '15 minutes' AS exp_period,
             d.tld, COUNT(*) AS domain_count
      FROM "Domain" d
      WHERE d.deletion_time > CURRENT_TIMESTAMP
        AND d.registration_expiration_time > CURRENT_TIMESTAMP
        AND d.registration_expiration_time < :endDate
      GROUP BY exp_period, d.tld
      ORDER BY exp_period
      """;

  private static final String EXPIRATION_CURVE_15MIN_SCOPED =
      """
      SELECT date_trunc('hour', d.registration_expiration_time)
               + floor(extract(minute from d.registration_expiration_time) / 15)
                 * interval '15 minutes' AS exp_period,
             d.tld, COUNT(*) AS domain_count
      FROM "Domain" d
      WHERE d.deletion_time > CURRENT_TIMESTAMP
        AND d.registration_expiration_time > CURRENT_TIMESTAMP
        AND d.registration_expiration_time < :endDate
        AND d.tld IN :tlds
      GROUP BY exp_period, d.tld
      ORDER BY exp_period
      """;

  // --- Renewal rates (always looks back 12 months) ---

  private static final String RENEWAL_RATES_NATIVE_ALL =
      """
      SELECT d.tld,
             COUNT(CASE WHEN dh.history_type IN (
               'DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 1 END) AS renewals,
             COUNT(CASE WHEN dh.history_type = 'DOMAIN_DELETE' THEN 1 END) AS deletions
      FROM "DomainHistory" dh
      JOIN "Domain" d ON d.repo_id = dh.domain_repo_id
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW', 'DOMAIN_DELETE')
      GROUP BY d.tld
      """;

  private static final String RENEWAL_RATES_NATIVE_SCOPED =
      """
      SELECT d.tld,
             COUNT(CASE WHEN dh.history_type IN (
               'DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 1 END) AS renewals,
             COUNT(CASE WHEN dh.history_type = 'DOMAIN_DELETE' THEN 1 END) AS deletions
      FROM "DomainHistory" dh
      JOIN "Domain" d ON d.repo_id = dh.domain_repo_id
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW', 'DOMAIN_DELETE')
        AND d.tld IN :tlds
      GROUP BY d.tld
      """;

  private final Optional<Integer> months;
  private final Optional<Integer> lookbackHours;
  private final Optional<String> granularity;
  private final ImmutableSet<String> filterTlds;
  private final ImmutableSet<String> filterRegistrarIds;

  @Inject
  public RegistryDashForecastingAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("months") Optional<Integer> months,
      @Parameter("lookbackHours") Optional<Integer> lookbackHours,
      @Parameter("granularity") Optional<String> granularity,
      @Parameter("filterTlds") ImmutableSet<String> filterTlds,
      @Parameter("filterRegistrarIds") ImmutableSet<String> filterRegistrarIds) {
    super(consoleApiParams);
    this.months = months;
    this.lookbackHours = lookbackHours;
    this.granularity = granularity;
    this.filterTlds = filterTlds;
    this.filterRegistrarIds = filterRegistrarIds;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_FORECASTING)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> accessTlds =
        isAdmin ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    ImmutableSet<String> tlds =
        RegistryDashAccessUtil.applyFilter(accessTlds, filterTlds, isAdmin);
    ImmutableSet<String> registrarIds = filterRegistrarIds;
    if (!isAdmin && tlds.isEmpty()) {
      Map<String, Object> empty = new HashMap<>();
      empty.put("expirationCurve", List.of());
      empty.put("renewalRates", List.of());
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(empty));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    // Resolve forecast horizon: prefer lookbackHours (used as forward horizon), fall back to months
    int hoursForward;
    if (lookbackHours.isPresent()) {
      hoursForward = Math.max(1, Math.min(lookbackHours.get(), 17520));
    } else {
      int forecastMonths = months.orElse(12);
      hoursForward = forecastMonths * 730;
    }

    // Resolve and validate granularity
    String gran = granularity.orElse("month");
    if (!VALID_GRANULARITIES.contains(gran)) {
      gran = "month";
    }

    String resolvedGran = gran;
    boolean use15min = "15min".equals(resolvedGran);

    tm().transact(
        () -> {
          boolean useScoped = !tlds.isEmpty();
          boolean hasRegistrarFilter = !registrarIds.isEmpty();

          // Expiration curve
          ZonedDateTime endZdt = ZonedDateTime.now(ZoneOffset.UTC)
              .plus(hoursForward, ChronoUnit.HOURS);

          @SuppressWarnings("unchecked")
          List<Object[]> expirationResults;
          if (use15min) {
            // Native SQL for 15-minute buckets
            Instant endInstant = endZdt.toInstant();
            String sql = useScoped ? EXPIRATION_CURVE_15MIN_SCOPED : EXPIRATION_CURVE_15MIN_ALL;
            if (hasRegistrarFilter) {
              sql = sql.replace(
                  "GROUP BY exp_period",
                  "AND d.current_sponsor_registrar_id IN :registrarIds\n      GROUP BY exp_period");
            }
            var expQuery = tm().getEntityManager()
                .createNativeQuery(sql)
                .setParameter("endDate", endInstant);
            if (useScoped) {
              expQuery.setParameter("tlds", tlds);
            }
            if (hasRegistrarFilter) {
              expQuery.setParameter("registrarIds", registrarIds);
            }
            expirationResults = expQuery.getResultList();
          } else {
            // JPQL for standard granularities (hour, day, month)
            String jpql = useScoped
                ? String.format(EXPIRATION_CURVE_SCOPED_TEMPLATE, resolvedGran)
                : String.format(EXPIRATION_CURVE_ALL_TEMPLATE, resolvedGran);
            if (hasRegistrarFilter) {
              jpql = jpql.replace(
                  "GROUP BY exp_period",
                  "AND d.currentSponsorRegistrarId IN :registrarIds\n      GROUP BY exp_period");
            }
            org.joda.time.DateTime endDate =
                org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC)
                    .plusHours(hoursForward);
            var expQuery = tm().getEntityManager()
                .createQuery(jpql)
                .setParameter("endDate", endDate);
            if (useScoped) {
              expQuery.setParameter("tlds", tlds);
            }
            if (hasRegistrarFilter) {
              expQuery.setParameter("registrarIds", registrarIds);
            }
            expirationResults = expQuery.getResultList();
          }

          List<Map<String, Object>> expirationCurve = new ArrayList<>();
          for (Object[] row : expirationResults) {
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
            long count = ((Number) row[2]).longValue();

            Map<String, Object> entry = new HashMap<>();
            entry.put("month", formatPeriod(periodInstant, resolvedGran));
            entry.put("tld", tld);
            entry.put("count", count);
            expirationCurve.add(entry);
          }

          // Renewal rates — native SQL, always looks back 12 months
          Instant startDate = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(12).toInstant();
          String renewalSql = useScoped ? RENEWAL_RATES_NATIVE_SCOPED : RENEWAL_RATES_NATIVE_ALL;
          if (hasRegistrarFilter) {
            renewalSql = renewalSql.replace(
                "GROUP BY d.tld",
                "AND d.current_sponsor_registrar_id IN :registrarIds\n      GROUP BY d.tld");
          }
          var renewalQuery = tm().getEntityManager()
              .createNativeQuery(renewalSql)
              .setParameter("startDate", startDate);
          if (useScoped) {
            renewalQuery.setParameter("tlds", tlds);
          }
          if (hasRegistrarFilter) {
            renewalQuery.setParameter("registrarIds", registrarIds);
          }
          @SuppressWarnings("unchecked")
          List<Object[]> renewalResults = renewalQuery.getResultList();

          List<Map<String, Object>> renewalRates = new ArrayList<>();
          for (Object[] row : renewalResults) {
            String tld = (String) row[0];
            long renewals = ((Number) row[1]).longValue();
            long deletions = ((Number) row[2]).longValue();
            long total = renewals + deletions;
            BigDecimal renewalRate = total > 0
                ? BigDecimal.valueOf(renewals * 100)
                    .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(100);

            Map<String, Object> entry = new HashMap<>();
            entry.put("tld", tld);
            entry.put("renewals", renewals);
            entry.put("deletions", deletions);
            entry.put("renewalRate", renewalRate);
            renewalRates.add(entry);
          }

          Map<String, Object> response = new HashMap<>();
          response.put("expirationCurve", expirationCurve);
          response.put("renewalRates", renewalRates);

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
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
