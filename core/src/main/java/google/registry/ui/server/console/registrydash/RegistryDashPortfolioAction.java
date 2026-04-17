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
import google.registry.model.registrar.Registrar;
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

/** Returns the registrar portfolio for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashPortfolioAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashPortfolioAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/portfolio";

  private static final String REGISTRARS_QUERY =
      """
      SELECT r FROM Registrar r
      WHERE r.registrarId IN :registrarIds
      """;

  private static final String DOMAIN_COUNTS_SCOPED_QUERY =
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
  public RegistryDashPortfolioAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("filterTlds") ImmutableSet<String> filterTlds,
      @Parameter("filterRegistrarIds") ImmutableSet<String> filterRegistrarIds) {
    super(consoleApiParams);
    this.filterTlds = filterTlds;
    this.filterRegistrarIds = filterRegistrarIds;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_REGISTRAR_PORTFOLIO)) {
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
    if (!filterTlds.isEmpty() && filterRegistrarIds.isEmpty() && !tlds.isEmpty()) {
      registrarIds = RegistryDashAccessUtil.getRegistrarIdsForTlds(tlds);
    } else {
      registrarIds =
          RegistryDashAccessUtil.applyFilter(accessRegistrarIds, filterRegistrarIds, isAdmin);
    }
    if (!isAdmin && registrarIds.isEmpty()) {
      consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(List.of()));
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    tm().transact(
        () -> {
          boolean useScoped = !tlds.isEmpty() || !registrarIds.isEmpty();
          @SuppressWarnings("unchecked")
          List<Registrar> registrars =
              !useScoped
                  ? tm().getEntityManager()
                      .createQuery("SELECT r FROM Registrar r", Registrar.class)
                      .getResultList()
                  : tm().getEntityManager()
                      .createQuery(REGISTRARS_QUERY, Registrar.class)
                      .setParameter("registrarIds", registrarIds)
                      .getResultList();

          @SuppressWarnings("unchecked")
          List<Object[]> domainCounts =
              !useScoped
                  ? tm().getEntityManager()
                      .createQuery(
                          "SELECT d.currentSponsorRegistrarId, COUNT(d)"
                              + " FROM Domain d"
                              + " WHERE d.deletionTime > CURRENT_TIMESTAMP"
                              + " GROUP BY d.currentSponsorRegistrarId")
                      .getResultList()
                  : tm().getEntityManager()
                      .createQuery(DOMAIN_COUNTS_SCOPED_QUERY)
                      .setParameter("registrarIds", registrarIds)
                      .setParameter("tlds", tlds)
                      .getResultList();

          Map<String, Long> countMap = new HashMap<>();
          for (Object[] row : domainCounts) {
            countMap.put((String) row[0], (Long) row[1]);
          }

          List<Map<String, Object>> portfolio = new java.util.ArrayList<>();
          for (Registrar r : registrars) {
            // Filter allowedTlds to scope — shows all TLDs when unscoped, or only matching ones
            ImmutableSet<String> visibleTlds = !useScoped
                ? r.getAllowedTlds()
                : r.getAllowedTlds().stream()
                    .filter(tlds::contains)
                    .collect(ImmutableSet.toImmutableSet());
            Map<String, Object> entry = new HashMap<>();
            entry.put("registrarId", r.getRegistrarId());
            entry.put("registrarName", r.getRegistrarName());
            entry.put("state", r.getState() != null ? r.getState().toString() : "ACTIVE");
            entry.put("domainCount", countMap.getOrDefault(r.getRegistrarId(), 0L));
            entry.put("allowedTlds", visibleTlds);
            portfolio.add(entry);
          }

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(portfolio));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }
}
