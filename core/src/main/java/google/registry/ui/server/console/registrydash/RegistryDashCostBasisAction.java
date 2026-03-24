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
import static google.registry.request.Action.Method.POST;
import static google.registry.request.Action.Method.PUT;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableSet;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.registrydash.RegistryDashboardCostBasis;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Handles cost basis CRUD for the registry dashboard. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashCostBasisAction.PATH,
    method = {GET, POST, PUT},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashCostBasisAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/cost-basis";

  private static final String ALL_COST_BASIS =
      """
      SELECT c FROM RegistryDashboardCostBasis c
      ORDER BY c.tld, c.operation, c.effectiveDate DESC
      """;

  private final Optional<RegistryDashboardCostBasis> costBasisPayload;

  @Inject
  public RegistryDashCostBasisAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registryDashCostBasis") Optional<RegistryDashboardCostBasis> costBasisPayload) {
    super(consoleApiParams);
    this.costBasisPayload = costBasisPayload;
  }

  @Override
  protected void getHandler(User user) {
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (!isAdmin
        && !user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    tm().transact(
        () -> {
          @SuppressWarnings("unchecked")
          List<RegistryDashboardCostBasis> results =
              tm().getEntityManager()
                  .createQuery(ALL_COST_BASIS, RegistryDashboardCostBasis.class)
                  .getResultList();
          List<Map<String, Object>> payload =
              new java.util.ArrayList<>();
          for (RegistryDashboardCostBasis c : results) {
            payload.add(costBasisToMap(c));
          }
          consoleApiParams.response().setPayload(
              consoleApiParams.gson().toJson(payload));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  @Override
  protected void postHandler(User user) {
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (!isAdmin
        && !user.getUserRoles().hasGlobalPermission(
            ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    RegistryDashboardCostBasis costBasis =
        costBasisPayload.orElseThrow(
            () -> new IllegalArgumentException(
                "Cost basis data is required"));

    ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
    costBasis.setEffectiveDate(
        costBasis.getEffectiveDate() != null
            ? costBasis.getEffectiveDate() : now);

    tm().transact(() -> tm().getEntityManager().persist(costBasis));
    consoleApiParams.response().setPayload(
        consoleApiParams.gson().toJson(costBasisToMap(costBasis)));
    consoleApiParams.response().setStatus(SC_OK);
  }

  @Override
  protected void putHandler(User user) {
    boolean isAdmin = user.getUserRoles().getGlobalRole() == GlobalRole.FTE;
    if (!isAdmin
        && !user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    RegistryDashboardCostBasis costBasis =
        costBasisPayload.orElseThrow(
            () -> new IllegalArgumentException("Cost basis data is required"));
    if (costBasis.getId() == null) {
      setFailedResponse("Cost basis ID is required for update", SC_BAD_REQUEST);
      return;
    }

    tm().transact(
        () -> {
          RegistryDashboardCostBasis existing =
              tm().getEntityManager()
                  .find(RegistryDashboardCostBasis.class, costBasis.getId());
          if (existing == null) {
            setFailedResponse("Cost basis entry not found", SC_BAD_REQUEST);
            return;
          }
          existing.setCostAmount(costBasis.getCostAmount());
          existing.setCostCurrency(costBasis.getCostCurrency());
          existing.setNotes(costBasis.getNotes());
          existing.setUpdatedAt(ZonedDateTime.now(java.time.ZoneOffset.UTC));
          tm().getEntityManager().merge(existing);
          consoleApiParams.response().setPayload(
              consoleApiParams.gson().toJson(costBasisToMap(existing)));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private static Map<String, Object> costBasisToMap(
      RegistryDashboardCostBasis c) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", c.getId());
    map.put("tld", c.getTld());
    map.put("operation", c.getOperation());
    map.put("costAmount", c.getCostAmount());
    map.put("costCurrency", c.getCostCurrency());
    map.put("effectiveDate", c.getEffectiveDate() != null ? c.getEffectiveDate().toString() : null);
    map.put("notes", c.getNotes());
    return map;
  }
}
