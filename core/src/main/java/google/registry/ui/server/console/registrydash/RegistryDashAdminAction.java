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
import static google.registry.request.Action.Method.DELETE;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrydash.RegistryDashboardRoTldMapping;
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
import java.util.Optional;

/** Handles admin CRUD for TLD mappings and provides system reference data. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashAdminAction.PATH,
    method = {GET, POST, DELETE},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashAdminAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/admin";

  private static final String ALL_TLD_MAPPINGS =
      """
      SELECT m FROM RegistryDashboardRoTldMapping m
      ORDER BY m.userEmailAddress, m.tld
      """;

  private static final String ALL_TLDS =
      "SELECT t.tldStr FROM Tld t ORDER BY t.tldStr";

  private static final String ALL_REAL_REGISTRARS =
      "SELECT r FROM Registrar r WHERE r.type = :type ORDER BY r.registrarId";

  private final Optional<AdminPayload> adminPayload;

  @Inject
  public RegistryDashAdminAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registryDashAdmin") Optional<AdminPayload> adminPayload) {
    super(consoleApiParams);
    this.adminPayload = adminPayload;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    tm().transact(
        () -> {
          List<RegistryDashboardRoTldMapping> mappings =
              tm().getEntityManager()
                  .createQuery(ALL_TLD_MAPPINGS, RegistryDashboardRoTldMapping.class)
                  .getResultList();
          List<Map<String, Object>> mappingList = new ArrayList<>();
          for (RegistryDashboardRoTldMapping m : mappings) {
            mappingList.add(mappingToMap(m));
          }

          @SuppressWarnings("unchecked")
          List<String> tlds =
              tm().getEntityManager()
                  .createQuery(ALL_TLDS)
                  .getResultList();

          List<Registrar> registrars =
              tm().getEntityManager()
                  .createQuery(ALL_REAL_REGISTRARS, Registrar.class)
                  .setParameter("type", Registrar.Type.REAL)
                  .getResultList();
          List<Map<String, Object>> registrarList = new ArrayList<>();
          for (Registrar r : registrars) {
            Map<String, Object> rMap = new HashMap<>();
            rMap.put("registrarId", r.getRegistrarId());
            rMap.put("registrarName", r.getRegistrarName());
            rMap.put("allowedTlds", r.getAllowedTlds());
            registrarList.add(rMap);
          }

          Map<String, Object> systemInfo = new HashMap<>();
          systemInfo.put("tlds", tlds);
          systemInfo.put("registrars", registrarList);

          Map<String, Object> response = new HashMap<>();
          response.put("mappings", mappingList);
          response.put("systemInfo", systemInfo);

          consoleApiParams.response().setPayload(
              consoleApiParams.gson().toJson(response));
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  @Override
  protected void postHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    AdminPayload payload =
        adminPayload.orElseThrow(
            () -> new IllegalArgumentException("Request body is required"));

    if (payload.userEmailAddress() == null || payload.userEmailAddress().isBlank()) {
      setFailedResponse("userEmailAddress is required", SC_BAD_REQUEST);
      return;
    }
    if (payload.tld() == null || payload.tld().isBlank()) {
      setFailedResponse("tld is required", SC_BAD_REQUEST);
      return;
    }

    RegistryDashboardRoTldMapping mapping =
        new RegistryDashboardRoTldMapping(payload.userEmailAddress(), payload.tld());

    tm().transact(() -> tm().getEntityManager().persist(mapping));
    consoleApiParams.response().setPayload(
        consoleApiParams.gson().toJson(mappingToMap(mapping)));
    consoleApiParams.response().setStatus(SC_OK);
  }

  @Override
  protected void deleteHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    AdminPayload payload =
        adminPayload.orElseThrow(
            () -> new IllegalArgumentException("Request body is required"));

    if (payload.id() == null) {
      setFailedResponse("id is required for delete", SC_BAD_REQUEST);
      return;
    }

    tm().transact(
        () -> {
          RegistryDashboardRoTldMapping existing =
              tm().getEntityManager()
                  .find(RegistryDashboardRoTldMapping.class, payload.id());
          if (existing == null) {
            setFailedResponse("Mapping not found", SC_BAD_REQUEST);
            return;
          }
          tm().getEntityManager().remove(existing);
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private static Map<String, Object> mappingToMap(RegistryDashboardRoTldMapping m) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", m.getId());
    map.put("userEmailAddress", m.getUserEmailAddress());
    map.put("tld", m.getTld());
    map.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
    return map;
  }

  /** Payload record for POST (create) and DELETE operations. */
  public record AdminPayload(String userEmailAddress, String tld, Long id) {}
}
