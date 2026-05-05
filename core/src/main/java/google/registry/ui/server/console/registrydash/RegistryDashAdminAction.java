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
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import google.registry.ai.AnthropicModelCatalog;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrydash.RoRegistry;
import google.registry.model.registrydash.RoRegistryTld;
import google.registry.model.registrydash.RoRegistryUser;
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

/** Handles admin CRUD for registries, TLD assignments, and user assignments. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashAdminAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashAdminAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/admin";

  private static final String ALL_REGISTRIES =
      "SELECT r FROM RoRegistry r ORDER BY r.name";

  private static final String TLDS_FOR_REGISTRY =
      "SELECT t FROM RoRegistryTld t WHERE t.registryId = :registryId ORDER BY t.tld";

  private static final String USERS_FOR_REGISTRY =
      "SELECT u FROM RoRegistryUser u WHERE u.registryId = :registryId ORDER BY u.userEmail";

  private static final String ALL_TLDS =
      "SELECT t.tldStr FROM Tld t ORDER BY t.tldStr";

  private static final String ALL_REAL_REGISTRARS =
      "SELECT r FROM Registrar r WHERE r.type = :type ORDER BY r.registrarId";

  private final Optional<AdminPayload> adminPayload;
  private final AnthropicModelCatalog modelCatalog;

  @Inject
  public RegistryDashAdminAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registryDashAdmin") Optional<AdminPayload> adminPayload,
      AnthropicModelCatalog modelCatalog) {
    super(consoleApiParams);
    this.adminPayload = adminPayload;
    this.modelCatalog = modelCatalog;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.MANAGE_COST_BASIS)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    tm().transact(
        () -> {
          // Load all registries with their TLDs and users
          List<RoRegistry> registries =
              tm().getEntityManager()
                  .createQuery(ALL_REGISTRIES, RoRegistry.class)
                  .getResultList();

          List<Map<String, Object>> registryList = new ArrayList<>();
          for (RoRegistry reg : registries) {
            Map<String, Object> regMap = new HashMap<>();
            regMap.put("id", reg.getId());
            regMap.put("name", reg.getName());
            regMap.put("createdAt",
                reg.getCreatedAt() != null ? reg.getCreatedAt().toString() : null);

            List<RoRegistryTld> tlds =
                tm().getEntityManager()
                    .createQuery(TLDS_FOR_REGISTRY, RoRegistryTld.class)
                    .setParameter("registryId", reg.getId())
                    .getResultList();
            List<Map<String, Object>> tldList = new ArrayList<>();
            for (RoRegistryTld t : tlds) {
              Map<String, Object> tMap = new HashMap<>();
              tMap.put("id", t.getId());
              tMap.put("tld", t.getTld());
              tldList.add(tMap);
            }
            regMap.put("tlds", tldList);

            List<RoRegistryUser> users =
                tm().getEntityManager()
                    .createQuery(USERS_FOR_REGISTRY, RoRegistryUser.class)
                    .setParameter("registryId", reg.getId())
                    .getResultList();
            List<Map<String, Object>> userList = new ArrayList<>();
            for (RoRegistryUser u : users) {
              Map<String, Object> uMap = new HashMap<>();
              uMap.put("id", u.getId());
              uMap.put("userEmail", u.getUserEmail());
              userList.add(uMap);
            }
            regMap.put("users", userList);
            regMap.put("settings", reg.getSettings());

            registryList.add(regMap);
          }

          // System reference data
          @SuppressWarnings("unchecked")
          List<String> systemTlds =
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
          systemInfo.put("tlds", systemTlds);
          systemInfo.put("registrars", registrarList);

          Map<String, Object> response = new HashMap<>();
          response.put("registries", registryList);
          response.put("systemInfo", systemInfo);
          response.put("aiModelCatalog", modelCatalog.currentCatalog());
          response.put("aiModelCatalogFetchedAt", modelCatalog.lastFetchedAt().toString());

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

    String action = payload.action();
    if (action == null || action.isBlank()) {
      setFailedResponse("action is required", SC_BAD_REQUEST);
      return;
    }

    switch (action) {
      case "createRegistry" -> handleCreateRegistry(payload);
      case "deleteRegistry" -> handleDeleteRegistry(payload);
      case "addTld" -> handleAddTld(payload);
      case "removeTld" -> handleRemoveTld(payload);
      case "addUser" -> handleAddUser(payload);
      case "removeUser" -> handleRemoveUser(payload);
      case "updateSettings" -> handleUpdateSettings(payload);
      case "refreshAiModels" -> handleRefreshAiModels();
      default -> setFailedResponse("Unknown action: " + action, SC_BAD_REQUEST);
    }
  }

  private void handleRefreshAiModels() {
    modelCatalog.forceRefresh();
    Map<String, Object> response = new HashMap<>();
    response.put("aiModelCatalog", modelCatalog.currentCatalog());
    response.put("aiModelCatalogFetchedAt", modelCatalog.lastFetchedAt().toString());
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(response));
    consoleApiParams.response().setStatus(SC_OK);
  }

  private void handleCreateRegistry(AdminPayload payload) {
    if (payload.registryName() == null || payload.registryName().isBlank()) {
      setFailedResponse("registryName is required", SC_BAD_REQUEST);
      return;
    }
    RoRegistry registry = new RoRegistry(payload.registryName());
    tm().transact(() -> tm().getEntityManager().persist(registry));
    consoleApiParams.response().setStatus(SC_OK);
  }

  private void handleDeleteRegistry(AdminPayload payload) {
    if (payload.registryId() == null) {
      setFailedResponse("registryId is required", SC_BAD_REQUEST);
      return;
    }
    tm().transact(
        () -> {
          RoRegistry existing =
              tm().getEntityManager().find(RoRegistry.class, payload.registryId());
          if (existing == null) {
            setFailedResponse("Registry not found", SC_BAD_REQUEST);
            return;
          }
          tm().getEntityManager().remove(existing);
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private void handleAddTld(AdminPayload payload) {
    if (payload.registryId() == null) {
      setFailedResponse("registryId is required", SC_BAD_REQUEST);
      return;
    }
    if (payload.tld() == null || payload.tld().isBlank()) {
      setFailedResponse("tld is required", SC_BAD_REQUEST);
      return;
    }
    RoRegistryTld tldMapping = new RoRegistryTld(payload.registryId(), payload.tld());
    tm().transact(() -> tm().getEntityManager().persist(tldMapping));
    consoleApiParams.response().setStatus(SC_OK);
  }

  private void handleRemoveTld(AdminPayload payload) {
    if (payload.id() == null) {
      setFailedResponse("id is required", SC_BAD_REQUEST);
      return;
    }
    tm().transact(
        () -> {
          RoRegistryTld existing =
              tm().getEntityManager().find(RoRegistryTld.class, payload.id());
          if (existing == null) {
            setFailedResponse("TLD assignment not found", SC_BAD_REQUEST);
            return;
          }
          tm().getEntityManager().remove(existing);
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private void handleAddUser(AdminPayload payload) {
    if (payload.registryId() == null) {
      setFailedResponse("registryId is required", SC_BAD_REQUEST);
      return;
    }
    if (payload.userEmail() == null || payload.userEmail().isBlank()) {
      setFailedResponse("userEmail is required", SC_BAD_REQUEST);
      return;
    }
    RoRegistryUser userMapping = new RoRegistryUser(payload.registryId(), payload.userEmail());
    tm().transact(() -> tm().getEntityManager().persist(userMapping));
    consoleApiParams.response().setStatus(SC_OK);
  }

  private void handleRemoveUser(AdminPayload payload) {
    if (payload.id() == null) {
      setFailedResponse("id is required", SC_BAD_REQUEST);
      return;
    }
    tm().transact(
        () -> {
          RoRegistryUser existing =
              tm().getEntityManager().find(RoRegistryUser.class, payload.id());
          if (existing == null) {
            setFailedResponse("User assignment not found", SC_BAD_REQUEST);
            return;
          }
          tm().getEntityManager().remove(existing);
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  private void handleUpdateSettings(AdminPayload payload) {
    if (payload.registryId() == null) {
      setFailedResponse("registryId is required", SC_BAD_REQUEST);
      return;
    }
    if (payload.settings() == null) {
      setFailedResponse("settings is required", SC_BAD_REQUEST);
      return;
    }
    tm().transact(
        () -> {
          RoRegistry existing =
              tm().getEntityManager().find(RoRegistry.class, payload.registryId());
          if (existing == null) {
            setFailedResponse("Registry not found", SC_BAD_REQUEST);
            return;
          }
          existing.setSettings(payload.settings());
          tm().getEntityManager().merge(existing);
          consoleApiParams.response().setStatus(SC_OK);
        });
  }

  /** Payload record for all admin POST operations. */
  public record AdminPayload(
      String action,
      Long registryId,
      String registryName,
      String tld,
      String userEmail,
      Long id,
      String settings) {}
}
