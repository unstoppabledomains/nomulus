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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Returns per-TLD domain breakdown for a single registrar. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashRegistrarDetailAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashRegistrarDetailAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/registrar-detail";

  private final String registrarId;
  private final ImmutableSet<String> filterTlds;

  @Inject
  public RegistryDashRegistrarDetailAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registrarId") String registrarId,
      @Parameter("filterTlds") ImmutableSet<String> filterTlds) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.filterTlds = filterTlds;
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

    if (!isAdmin && tlds.isEmpty()) {
      consoleApiParams.response().setPayload("[]");
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<Object[]> results;
          if (isAdmin && tlds.isEmpty()) {
            results = tm().getEntityManager()
                .createQuery(
                    "SELECT d.tld, COUNT(d) FROM Domain d "
                        + "WHERE d.currentSponsorRegistrarId = :registrarId "
                        + "AND d.deletionTime > CURRENT_TIMESTAMP "
                        + "GROUP BY d.tld ORDER BY COUNT(d) DESC")
                .setParameter("registrarId", registrarId)
                .getResultList();
          } else {
            results = tm().getEntityManager()
                .createQuery(
                    "SELECT d.tld, COUNT(d) FROM Domain d "
                        + "WHERE d.currentSponsorRegistrarId = :registrarId "
                        + "AND d.tld IN :tlds "
                        + "AND d.deletionTime > CURRENT_TIMESTAMP "
                        + "GROUP BY d.tld ORDER BY COUNT(d) DESC")
                .setParameter("registrarId", registrarId)
                .setParameter("tlds", tlds)
                .getResultList();
          }

          List<Map<String, Object>> response = new ArrayList<>();
          for (Object[] row : results) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("tld", row[0]);
            entry.put("count", row[1]);
            response.add(entry);
          }

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }
}
