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
import google.registry.model.tld.Tlds;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Returns the list of TLDs and registrars available to the current user for filtering.
 *
 * <p>FTE users see all TLDs and REAL registrars. Non-admin users see only those in their
 * RoRegistry membership scope.
 */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashFilterOptionsAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashFilterOptionsAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/filter-options";

  private static final String ALL_REAL_REGISTRARS =
      "SELECT r FROM Registrar r WHERE r.type = :type";

  @Inject
  public RegistryDashFilterOptionsAction(ConsoleApiParams consoleApiParams) {
    super(consoleApiParams);
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DASHBOARD_OVERVIEW)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;

    tm().transact(
        () -> {
          ImmutableSet<String> tlds;
          List<Registrar> registrars;

          if (isAdmin) {
            tlds = Tlds.getTlds();
            registrars =
                tm().getEntityManager()
                    .createQuery(ALL_REAL_REGISTRARS, Registrar.class)
                    .setParameter("type", Registrar.Type.REAL)
                    .getResultList();
          } else {
            tlds = RegistryDashAccessUtil.getMappedTlds(user.getEmailAddress());
            ImmutableSet<String> registrarIds =
                RegistryDashAccessUtil.getRegistrarIdsForTlds(tlds);
            if (registrarIds.isEmpty()) {
              Map<String, Object> empty = new HashMap<>();
              empty.put("tlds", List.of());
              empty.put("registrars", List.of());
              consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(empty));
              consoleApiParams.response().setStatus(SC_OK);
              return;
            }
            registrars =
                tm().getEntityManager()
                    .createQuery(ALL_REAL_REGISTRARS, Registrar.class)
                    .setParameter("type", Registrar.Type.REAL)
                    .getResultList()
                    .stream()
                    .filter(r -> registrarIds.contains(r.getRegistrarId()))
                    .toList();
          }

          List<Map<String, Object>> registrarList =
              registrars.stream()
                  .map(
                      r -> {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("registrarId", r.getRegistrarId());
                        entry.put("registrarName", r.getRegistrarName());
                        entry.put(
                            "allowedTlds",
                            r.getAllowedTlds().stream()
                                .filter(tlds::contains)
                                .sorted()
                                .toList());
                        return entry;
                      })
                  .toList();

          Map<String, Object> response = new HashMap<>();
          response.put("tlds", tlds.stream().sorted().toList());
          response.put("registrars", registrarList);

          consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }
}
