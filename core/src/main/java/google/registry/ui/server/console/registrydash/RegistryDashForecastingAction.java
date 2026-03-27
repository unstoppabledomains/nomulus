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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Returns domain expiration forecasting and renewal rate data for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashForecastingAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashForecastingAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/forecasting";

  private static final String EXPIRATION_CURVE_ALL =
      """
      SELECT date_trunc('month', d.registrationExpirationTime) AS exp_month,
             d.tld, COUNT(d) AS domain_count
      FROM Domain d
      WHERE d.deletionTime > CURRENT_TIMESTAMP
        AND d.registrationExpirationTime > CURRENT_TIMESTAMP
        AND d.registrationExpirationTime < :endDate
      GROUP BY exp_month, d.tld
      ORDER BY exp_month
      """;

  private static final String EXPIRATION_CURVE_SCOPED =
      """
      SELECT date_trunc('month', d.registrationExpirationTime) AS exp_month,
             d.tld, COUNT(d) AS domain_count
      FROM Domain d
      WHERE d.deletionTime > CURRENT_TIMESTAMP
        AND d.registrationExpirationTime > CURRENT_TIMESTAMP
        AND d.registrationExpirationTime < :endDate
        AND d.tld IN :tlds
      GROUP BY exp_month, d.tld
      ORDER BY exp_month
      """;

  private static final String RENEWAL_RATES_NATIVE_ALL =
      """
      SELECT d.tld,
             COUNT(CASE WHEN dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 1 END) AS renewals,
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
             COUNT(CASE WHEN dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW') THEN 1 END) AS renewals,
             COUNT(CASE WHEN dh.history_type = 'DOMAIN_DELETE' THEN 1 END) AS deletions
      FROM "DomainHistory" dh
      JOIN "Domain" d ON d.repo_id = dh.domain_repo_id
      WHERE dh.history_modification_time >= :startDate
        AND dh.history_type IN ('DOMAIN_RENEW', 'DOMAIN_AUTORENEW', 'DOMAIN_DELETE')
        AND d.tld IN :tlds
      GROUP BY d.tld
      """;

  private final Optional<Integer> months;

  @Inject
  public RegistryDashForecastingAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("months") Optional<Integer> months) {
    super(consoleApiParams);
    this.months = months;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_FORECASTING)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> tlds =
        isAdmin ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    if (!isAdmin && tlds.isEmpty()) {
      Map<String, Object> empty = new HashMap<>();
      empty.put("expirationCurve", List.of());
      empty.put("renewalRates", List.of());
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(empty));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    int forecastMonths = months.orElse(12);

    tm().transact(
        () -> {
          // Expiration curve — uses JPQL (date_trunc works on DateTime via Hibernate)
          org.joda.time.DateTime endDate =
              org.joda.time.DateTime.now().plusMonths(forecastMonths);
          @SuppressWarnings("unchecked")
          List<Object[]> expirationResults =
              isAdmin
                  ? tm().getEntityManager()
                      .createQuery(EXPIRATION_CURVE_ALL)
                      .setParameter("endDate", endDate)
                      .getResultList()
                  : tm().getEntityManager()
                      .createQuery(EXPIRATION_CURVE_SCOPED)
                      .setParameter("endDate", endDate)
                      .setParameter("tlds", tlds)
                      .getResultList();

          List<Map<String, Object>> expirationCurve = new ArrayList<>();
          for (Object[] row : expirationResults) {
            Object monthVal = row[0];
            String tld = (String) row[1];
            long count = (Long) row[2];

            Map<String, Object> entry = new HashMap<>();
            entry.put("month", monthVal.toString().substring(0, 7));
            entry.put("tld", tld);
            entry.put("count", count);
            expirationCurve.add(entry);
          }

          // Renewal rates — native SQL (history_type is a string column)
          Instant startDate =
              ZonedDateTime.now(ZoneOffset.UTC).minusMonths(12).toInstant();
          @SuppressWarnings("unchecked")
          List<Object[]> renewalResults =
              isAdmin
                  ? tm().getEntityManager()
                      .createNativeQuery(RENEWAL_RATES_NATIVE_ALL)
                      .setParameter("startDate", startDate)
                      .getResultList()
                  : tm().getEntityManager()
                      .createNativeQuery(RENEWAL_RATES_NATIVE_SCOPED)
                      .setParameter("startDate", startDate)
                      .setParameter("tlds", tlds)
                      .getResultList();

          List<Map<String, Object>> renewalRates = new ArrayList<>();
          for (Object[] row : renewalResults) {
            String tld = (String) row[0];
            long renewals = ((Number) row[1]).longValue();
            long deletions = ((Number) row[2]).longValue();
            long total = renewals + deletions;
            BigDecimal renewalRate = total > 0
                ? BigDecimal.valueOf(renewals)
                    .divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP)
                : BigDecimal.ONE;

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
}
