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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.registrydash.RoRegistry;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.util.Optional;

/** Returns the column visibility settings for the current user's registry. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashSettingsAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashSettingsAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/settings";

  private static final int MAX_SAVED_VIEWS = 20;

  private final Optional<JsonElement> settingsPayload;

  @Inject
  public RegistryDashSettingsAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("settingsPayload") Optional<JsonElement> settingsPayload) {
    super(consoleApiParams);
    this.settingsPayload = settingsPayload;
  }

  @Override
  protected void getHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DASHBOARD_OVERVIEW)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    // FTE/admin users always see everything
    if (user.getUserRoles().getGlobalRole() == GlobalRole.FTE) {
      consoleApiParams.response().setPayload("{}");
      consoleApiParams.response().setStatus(SC_OK);
      return;
    }

    Optional<RoRegistry> registry =
        RegistryDashAccessUtil.getRegistryForUser(user.getEmailAddress());
    String settings = registry.map(RoRegistry::getSettings).orElse("{}");

    consoleApiParams.response().setPayload(settings);
    consoleApiParams.response().setStatus(SC_OK);
  }

  @Override
  protected void postHandler(User user) {
    if (!user.getUserRoles().hasGlobalPermission(ConsolePermission.VIEW_DASHBOARD_OVERVIEW)) {
      consoleApiParams.response().setStatus(SC_FORBIDDEN);
      return;
    }

    // FTE users should use the admin endpoint
    if (user.getUserRoles().getGlobalRole() == GlobalRole.FTE) {
      setFailedResponse("FTE users must use the admin endpoint", SC_FORBIDDEN);
      return;
    }

    Optional<RoRegistry> registryOpt =
        RegistryDashAccessUtil.getRegistryForUser(user.getEmailAddress());
    if (registryOpt.isEmpty()) {
      setFailedResponse("No registry found for user", SC_BAD_REQUEST);
      return;
    }

    if (settingsPayload.isEmpty()) {
      setFailedResponse("Request body is required", SC_BAD_REQUEST);
      return;
    }

    JsonObject incoming = settingsPayload.get().getAsJsonObject();

    // Validate savedExploreViews if present
    if (incoming.has("savedExploreViews")) {
      JsonElement views = incoming.get("savedExploreViews");
      if (!views.isJsonArray()) {
        setFailedResponse("savedExploreViews must be an array", SC_BAD_REQUEST);
        return;
      }
      JsonArray viewsArray = views.getAsJsonArray();
      if (viewsArray.size() > MAX_SAVED_VIEWS) {
        setFailedResponse(
            "savedExploreViews exceeds maximum of " + MAX_SAVED_VIEWS + " entries",
            SC_BAD_REQUEST);
        return;
      }
    }

    RoRegistry registry = registryOpt.get();

    tm().transact(
        () -> {
          // Re-read inside the transaction for consistency
          RoRegistry current =
              tm().getEntityManager().find(RoRegistry.class, registry.getId());
          if (current == null) {
            setFailedResponse("Registry not found", SC_BAD_REQUEST);
            return;
          }

          // Parse existing settings and merge incoming on top
          JsonObject existing = JsonParser.parseString(current.getSettings()).getAsJsonObject();
          for (String key : incoming.keySet()) {
            existing.add(key, incoming.get(key));
          }

          current.setSettings(existing.toString());
          tm().getEntityManager().merge(current);

          consoleApiParams.response().setPayload(existing.toString());
          consoleApiParams.response().setStatus(SC_OK);
        });
  }
}
