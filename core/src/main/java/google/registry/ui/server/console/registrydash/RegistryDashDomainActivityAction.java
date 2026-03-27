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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Returns domain transaction activity data for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashDomainActivityAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashDomainActivityAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/domain-activity";

  private static final String ACTIVITY_ALL =
      """
      SELECT date_trunc('month', dtr.reporting_time) AS period,
             dtr.tld,
             CASE
               WHEN dtr.report_field LIKE 'NET_ADDS_%%' THEN 'CREATES'
               WHEN dtr.report_field LIKE 'NET_RENEWS_%%' THEN 'RENEWS'
               WHEN dtr.report_field = 'TRANSFER_SUCCESSFUL' THEN 'TRANSFERS'
               WHEN dtr.report_field IN ('DELETED_DOMAINS_GRACE', 'DELETED_DOMAINS_NOGRACE') THEN 'DELETES'
               WHEN dtr.report_field = 'RESTORED_DOMAINS' THEN 'RESTORES'
               ELSE 'OTHER'
             END AS activity_type,
             SUM(dtr.report_amount) AS total_count
      FROM "DomainTransactionRecord" dtr
      WHERE dtr.reporting_time >= :startDate
      GROUP BY period, dtr.tld, activity_type
      ORDER BY period, dtr.tld
      """;

  private static final String ACTIVITY_SCOPED =
      """
      SELECT date_trunc('month', dtr.reporting_time) AS period,
             dtr.tld,
             CASE
               WHEN dtr.report_field LIKE 'NET_ADDS_%%' THEN 'CREATES'
               WHEN dtr.report_field LIKE 'NET_RENEWS_%%' THEN 'RENEWS'
               WHEN dtr.report_field = 'TRANSFER_SUCCESSFUL' THEN 'TRANSFERS'
               WHEN dtr.report_field IN ('DELETED_DOMAINS_GRACE', 'DELETED_DOMAINS_NOGRACE') THEN 'DELETES'
               WHEN dtr.report_field = 'RESTORED_DOMAINS' THEN 'RESTORES'
               ELSE 'OTHER'
             END AS activity_type,
             SUM(dtr.report_amount) AS total_count
      FROM "DomainTransactionRecord" dtr
      WHERE dtr.reporting_time >= :startDate
        AND dtr.tld IN :tlds
      GROUP BY period, dtr.tld, activity_type
      ORDER BY period, dtr.tld
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

  @Inject
  public RegistryDashDomainActivityAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("months") Optional<Integer> months) {
    super(consoleApiParams);
    this.months = months;
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

    int lookbackMonths = months.orElse(12);
    Instant startDate =
        ZonedDateTime.now(ZoneOffset.UTC).minusMonths(lookbackMonths).toInstant();

    tm().transact(
        () -> {
          // Activity data from DomainTransactionRecord
          @SuppressWarnings("unchecked")
          List<Object[]> activityResults =
              isAdmin
                  ? tm().getEntityManager()
                      .createNativeQuery(ACTIVITY_ALL)
                      .setParameter("startDate", startDate)
                      .getResultList()
                  : tm().getEntityManager()
                      .createNativeQuery(ACTIVITY_SCOPED)
                      .setParameter("startDate", startDate)
                      .setParameter("tlds", tlds)
                      .getResultList();

          List<Map<String, Object>> activity = new ArrayList<>();
          for (Object[] row : activityResults) {
            Instant period = (Instant) row[0];
            String tld = (String) row[1];
            String type = (String) row[2];
            Number count = (Number) row[3];

            Map<String, Object> entry = new HashMap<>();
            entry.put("period", period.atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7));
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
}
