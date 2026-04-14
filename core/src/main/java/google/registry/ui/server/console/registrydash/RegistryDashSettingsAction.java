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

import static google.registry.request.Action.Method.GET;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import google.registry.model.console.ConsolePermission;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.registrydash.RoRegistry;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.util.Optional;

/** Returns the column visibility settings for the current user's registry. */
@Action(
    service = Service.CONSOLE,
    path = RegistryDashSettingsAction.PATH,
    method = {GET},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistryDashSettingsAction extends ConsoleApiAction {

  static final String PATH = "/console-api/registry-dash/settings";

  @Inject
  public RegistryDashSettingsAction(ConsoleApiParams consoleApiParams) {
    super(consoleApiParams);
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
}
