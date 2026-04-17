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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Returns aggregate overview data for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashOverviewAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashOverviewAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/overview";

  private static final String DOMAIN_COUNT_BY_REGISTRAR_SCOPED =
      """
      SELECT d.currentSponsorRegistrarId, COUNT(d)
      FROM Domain d
      WHERE d.currentSponsorRegistrarId IN :registrarIds
        AND d.tld IN :tlds
        AND d.deletionTime > CURRENT_TIMESTAMP
      GROUP BY d.currentSponsorRegistrarId
      """;

  private final ImmutableSet<String> filterTlds;
  private final ImmutableSet<String> filterRegistrarIds;

  @Inject
  public RegistryDashOverviewAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("filterTlds") ImmutableSet<String> filterTlds,
      @Parameter("filterRegistrarIds") ImmutableSet<String> filterRegistrarIds) {
    super(consoleApiParams);
    this.filterTlds = filterTlds;
    this.filterRegistrarIds = filterRegistrarIds;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DASHBOARD_OVERVIEW)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    ImmutableSet<String> accessTlds =
        isAdmin ? ImmutableSet.of()
            : RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
    ImmutableSet<String> tlds =
        RegistryDashAccessUtil.applyFilter(accessTlds, filterTlds, isAdmin);
    ImmutableSet<String> accessRegistrarIds =
        isAdmin ? ImmutableSet.of()
            : RegistryDashAccessUtil.getRegistrarIdsForTlds(accessTlds);
    ImmutableSet<String> registrarIds;
    // When TLD filter is applied but registrar filter is not, derive registrars from filtered TLDs
    if (!filterTlds.isEmpty() && filterRegistrarIds.isEmpty() && !tlds.isEmpty()) {
      registrarIds = RegistryDashAccessUtil.getRegistrarIdsForTlds(tlds);
    } else {
      registrarIds =
          RegistryDashAccessUtil.applyFilter(accessRegistrarIds, filterRegistrarIds, isAdmin);
    }
    if (!isAdmin && registrarIds.isEmpty()) {
      Map<String, Object> empty = new HashMap<>();
      empty.put("totalDomains", 0L);
      empty.put("activeRegistrars", 0);
      empty.put("domainsByRegistrar", List.of());
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(empty));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    tm().transact(
        () -> {
          boolean useScoped = !tlds.isEmpty() || !registrarIds.isEmpty();
          @SuppressWarnings("unchecked")
          List<Object[]> results =
              !useScoped
                  ? tm().getEntityManager()
                      .createQuery(
                          "SELECT d.currentSponsorRegistrarId, COUNT(d)"
                              + " FROM Domain d"
                              + " WHERE d.deletionTime > CURRENT_TIMESTAMP"
                              + " GROUP BY d.currentSponsorRegistrarId")
                      .getResultList()
                  : tm().getEntityManager()
                      .createQuery(DOMAIN_COUNT_BY_REGISTRAR_SCOPED)
                      .setParameter("registrarIds", registrarIds)
                      .setParameter("tlds", tlds)
                      .getResultList();

          long totalDomains = 0;
          int activeRegistrars = 0;
          List<Map<String, Object>> domainsByRegistrar = new java.util.ArrayList<>();
          for (Object[] row : results) {
            String regId = (String) row[0];
            long count = (Long) row[1];
            totalDomains += count;
            activeRegistrars++;
            Map<String, Object> entry = new HashMap<>();
            entry.put("registrarId", regId);
            entry.put("count", count);
            domainsByRegistrar.add(entry);
          }

          Map<String, Object> response = new HashMap<>();
          response.put("totalDomains", totalDomains);
          response.put("activeRegistrars", isAdmin ? activeRegistrars : registrarIds.size());
          response.put("domainsByRegistrar", domainsByRegistrar);

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }
}
