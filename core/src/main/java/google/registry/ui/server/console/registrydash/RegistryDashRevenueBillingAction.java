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
import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Returns monthly revenue and billing data for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashRevenueBillingAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashRevenueBillingAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/revenue-billing";

  private static final String MONTHLY_REVENUE_ALL =
      """
      SELECT date_trunc('month', b.event_time) AS month,
             d.tld, b.reason,
             SUM(b.cost_amount) AS total_amount,
             b.cost_currency
      FROM "BillingEvent" b
      JOIN "Domain" d ON d.repo_id = b.domain_repo_id
      WHERE b.event_time >= :startDate
        AND b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')
      GROUP BY date_trunc('month', b.event_time), d.tld, b.reason, b.cost_currency
      ORDER BY month, d.tld
      """;

  private static final String MONTHLY_REVENUE_SCOPED =
      """
      SELECT date_trunc('month', b.event_time) AS month,
             d.tld, b.reason,
             SUM(b.cost_amount) AS total_amount,
             b.cost_currency
      FROM "BillingEvent" b
      JOIN "Domain" d ON d.repo_id = b.domain_repo_id
      WHERE b.event_time >= :startDate
        AND d.tld IN :tlds
        AND b.reason IN ('CREATE', 'RENEW', 'TRANSFER', 'RESTORE')
      GROUP BY date_trunc('month', b.event_time), d.tld, b.reason, b.cost_currency
      ORDER BY month, d.tld
      """;

  private final Optional<Integer> months;

  @Inject
  public RegistryDashRevenueBillingAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("months") Optional<Integer> months) {
    super(consoleApiParams);
    this.months = months;
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
      empty.put("monthlyRevenue", List.of());
      empty.put("totals", Map.of("totalRevenue", 0, "currency", "USD", "byOperation", Map.of()));
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(empty));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    int lookbackMonths = months.orElse(12);
    Timestamp startDate =
        Timestamp.from(ZonedDateTime.now(java.time.ZoneOffset.UTC).minusMonths(lookbackMonths).toInstant());

    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<Object[]> results =
              isAdmin
                  ? tm().getEntityManager()
                      .createNativeQuery(MONTHLY_REVENUE_ALL)
                      .setParameter("startDate", startDate)
                      .getResultList()
                  : tm().getEntityManager()
                      .createNativeQuery(MONTHLY_REVENUE_SCOPED)
                      .setParameter("startDate", startDate)
                      .setParameter("tlds", tlds)
                      .getResultList();

          List<Map<String, Object>> monthlyRevenue = new ArrayList<>();
          BigDecimal totalRevenue = BigDecimal.ZERO;
          Map<String, BigDecimal> byOperation = new HashMap<>();
          String currency = "USD";

          for (Object[] row : results) {
            Timestamp month = (Timestamp) row[0];
            String tld = (String) row[1];
            String operation = (String) row[2];
            BigDecimal amount = (BigDecimal) row[3];
            String cur = (String) row[4];

            Map<String, Object> entry = new HashMap<>();
            entry.put("month", month.toLocalDateTime().toLocalDate().toString().substring(0, 7));
            entry.put("tld", tld);
            entry.put("operation", operation);
            entry.put("amount", amount);
            entry.put("currency", cur);
            monthlyRevenue.add(entry);

            totalRevenue = totalRevenue.add(amount);
            byOperation.merge(operation, amount, BigDecimal::add);
            currency = cur;
          }

          Map<String, Object> totals = new HashMap<>();
          totals.put("totalRevenue", totalRevenue);
          totals.put("currency", currency);
          totals.put("byOperation", byOperation);

          Map<String, Object> response = new HashMap<>();
          response.put("monthlyRevenue", monthlyRevenue);
          response.put("totals", totals);

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }
}
